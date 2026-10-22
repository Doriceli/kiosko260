package com.kiosko260.videoqr

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Un video terminado que está en Download/PROCESADOS. */
data class ProcessedVideo(
    val id: String,
    val file: File,
    val createdAt: LocalDateTime?,
    val number: Int?,
    val authorized: Boolean,
    /** Nombre del evento y número del video dentro del evento (salen del nombre del archivo). */
    val event: String? = null,
    val eventNumber: Int? = null,
)

/**
 * Guarda los videos terminados en `Almacenamiento interno/Download/PROCESADOS/` y sus miniaturas
 * en la carpeta privada de la app (para que no ensucien la galería).
 */
class VideoLibrary(private val context: Context) {

    val videosDir: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Config.PROCESADOS_FOLDER,
    ).apply { mkdirs() }

    /** Copias de los videos con autorización para redes: Download/PROCESADOS/AUTORIZADOS. */
    val authorizedDir: File = File(videosDir, Config.AUTORIZADOS_FOLDER).apply { mkdirs() }

    val thumbnailsDir: File = File(context.getExternalFilesDir(null), "miniaturas").apply { mkdirs() }

    private val numbers = context.getSharedPreferences("numeros", Context.MODE_PRIVATE)
    private val codes = context.getSharedPreferences("codigos", Context.MODE_PRIVATE)

    /**
     * Código corto de 4 caracteres para el video (p. ej. `K7P2`), para escribir la dirección a mano:
     * `192.168.1.20:8080/K7P2`. Sin letras que se confunden (0/O, 1/I/L).
     */
    @Synchronized
    fun shortCode(id: String): String {
        codes.getString("id:$id", null)?.let { return it }
        var code: String
        do {
            code = (1..4).map { CODE_CHARS.random() }.joinToString("")
        } while (codes.contains("c:$code"))
        codes.edit().putString("id:$id", code).putString("c:$code", id).apply()
        return code
    }

    fun idForCode(code: String): String? = codes.getString("c:${code.uppercase()}", null)

    /**
     * Id del video, que también es el nombre del archivo:
     * `<Evento>_<nº en el evento>_<fecha y hora><4 al azar>`, p. ej. `Boda-Ana-y-Luis_007_20261015183005aZ3k`.
     * Sin evento: `20261015183005aZ3k`.
     */
    fun newId(event: String = "", eventNumber: Int = 0): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val stamp = LocalDateTime.now().format(ID_DATE) + (1..4).map { chars.random() }.joinToString("")
        val safeEvent = sanitizeEvent(event)
        return if (safeEvent.isEmpty()) stamp else "${safeEvent}_${eventNumber.toString().padStart(3, '0')}_$stamp"
    }

    fun videoFile(id: String) = File(videosDir, "$id.mp4")
    fun thumbnailFile(id: String) = File(thumbnailsDir, "$id.jpg")

    /** Copia el video exportado a PROCESADOS y lo registra en la galería. */
    fun save(id: String, exported: File, number: Int): File {
        val target = videoFile(id)
        exported.copyTo(target, overwrite = true)
        exported.delete()
        numbers.edit().putInt(id, number).apply()
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
        return target
    }

    fun isAuthorized(id: String) = File(authorizedDir, "$id.mp4").isFile

    /**
     * Marca o desmarca "Autorizo compartir mi video en redes sociales": copia el video a
     * AUTORIZADOS (o lo quita de ahí) y deja constancia en AUTORIZADOS/registro.csv.
     */
    fun setAuthorized(id: String, authorized: Boolean) {
        val source = videoFile(id).takeIf { it.isFile } ?: return
        val copy = File(authorizedDir, "$id.mp4")
        if (authorized) {
            if (!copy.isFile) source.copyTo(copy, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(copy.absolutePath), arrayOf("video/mp4"), null)
        } else {
            copy.delete()
            MediaScannerConnection.scanFile(context, arrayOf(copy.absolutePath), null, null)
        }
        runCatching {
            val log = File(authorizedDir, "registro.csv")
            if (!log.exists()) log.writeText("fecha_hora,video,autoriza\n")
            log.appendText("${LocalDateTime.now().format(LOG_DATE)},$id.mp4,${if (authorized) "SI" else "NO"}\n")
        }
    }

    fun find(id: String): ProcessedVideo? = videoFile(id).takeIf { it.isFile }?.let(::toProcessed)

    /** Videos de PROCESADOS, del más nuevo al más viejo. */
    fun list(): List<ProcessedVideo> =
        videosDir.listFiles { f -> f.isFile && f.extension.equals("mp4", ignoreCase = true) }
            .orEmpty()
            .filter { ID_PATTERN.matches(it.nameWithoutExtension) }
            .sortedByDescending { it.lastModified() }
            .map(::toProcessed)

    private fun toProcessed(file: File): ProcessedVideo {
        val id = file.nameWithoutExtension
        val match = NAME_PARTS.matchEntire(id)
        val createdAt = match?.let { runCatching { LocalDateTime.parse(it.groupValues[3], ID_DATE) }.getOrNull() }
        val number = numbers.getInt(id, 0).takeIf { it > 0 }
        val event = match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.replace('-', ' ')
        val eventNumber = match?.groupValues?.get(2)?.toIntOrNull()
        return ProcessedVideo(id, file, createdAt, number, isAuthorized(id), event, eventNumber)
    }

    companion object {
        val ID_PATTERN = Regex("^[A-Za-z0-9_-]{6,80}$")
        private const val CODE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        /** (evento)_(número)_(yyyyMMddHHmmss)xxxx, con evento y número opcionales. */
        private val NAME_PARTS = Regex("""^(?:([A-Za-z0-9-]+)_(\d+)_)?(\d{14})[A-Za-z0-9]{4}$""")

        /** "Boda Ana & Luis" → "Boda-Ana-Luis" (sin acentos ni símbolos, máx. 30 caracteres). */
        fun sanitizeEvent(event: String): String =
            Normalizer.normalize(event.trim(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^A-Za-z0-9]+"), "-")
                .trim('-')
                .take(30)
                .trim('-')
        private val ID_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private val LOG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
