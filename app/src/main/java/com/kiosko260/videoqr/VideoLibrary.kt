package com.kiosko260.videoqr

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Un video terminado que está en Download/PROCESADOS. */
data class ProcessedVideo(
    val id: String,
    val file: File,
    val createdAt: LocalDateTime?,
    val number: Int?,
    val authorized: Boolean,
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

    /** Id con fecha y hora + 4 caracteres al azar, p. ej. `20261011183005aZ3k` (también es el nombre del archivo). */
    fun newId(): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return LocalDateTime.now().format(ID_DATE) + (1..4).map { chars.random() }.joinToString("")
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
        val createdAt = runCatching { LocalDateTime.parse(id.take(14), ID_DATE) }.getOrNull()
        val number = numbers.getInt(id, 0).takeIf { it > 0 }
        return ProcessedVideo(id, file, createdAt, number, isAuthorized(id))
    }

    companion object {
        val ID_PATTERN = Regex("^[A-Za-z0-9]{6,32}$")
        private val ID_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private val LOG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
