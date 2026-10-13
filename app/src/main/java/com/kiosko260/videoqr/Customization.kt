package com.kiosko260.videoqr

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Busca el logo y las canciones, en este orden:
 *  1. `Almacenamiento interno/Download/INSUMOS/` ([Config.INSUMOS_FOLDER]),
 *  2. `Android/data/com.kiosko260.videoqr/files/personalizacion/`,
 *  3. los assets del APK.
 */
class Customization(private val context: Context) {

    val folder: File = File(context.getExternalFilesDir(null), "personalizacion").apply {
        File(this, Config.MUSIC_DIR).mkdirs()
    }

    private val insumosDir: File =
        File(Environment.getExternalStorageDirectory(), Config.INSUMOS_FOLDER)

    /** Imagen de INSUMOS (o de la carpeta de personalización), o null si no existe. */
    fun loadInsumoImage(fileName: String): Bitmap? {
        insumosImageUri(fileName)?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { return it }
        }
        listOf(File(insumosDir, fileName), File(folder, fileName)).forEach { file ->
            if (file.canRead()) BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        return null
    }

    fun loadLogo(): Bitmap? {
        loadInsumoImage(Config.LOGO_FILE)?.let { return it }
        return runCatching {
            context.assets.open(Config.FALLBACK_LOGO_ASSET).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    fun musicTracks(): List<MusicTrack> {
        val tracks = insumosAudio().toMutableList()

        // Si MediaStore aún no indexó la carpeta, se intenta leerla directamente.
        if (tracks.isEmpty()) {
            insumosDir.listFiles()
                ?.filter { it.isFile && it.canRead() && it.extension.lowercase() in AUDIO_EXTENSIONS }
                ?.forEach { tracks += MusicTrack.fromFileName(it.name, Uri.fromFile(it)) }
        }
        File(folder, Config.MUSIC_DIR).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.forEach { tracks += MusicTrack.fromFileName(it.name, Uri.fromFile(it)) }
        context.assets.list(Config.MUSIC_DIR)
            ?.filter { it.substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS }
            ?.forEach { tracks += MusicTrack.fromFileName(it, Uri.parse("asset:///${Config.MUSIC_DIR}/$it")) }
        return tracks.distinctBy { it.title.lowercase() }.sortedBy { it.title.lowercase() }
    }

    /** Duración de una canción en milisegundos, o 0 si no se pudo leer. */
    fun durationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "asset") {
                context.assets.openFd(uri.path!!.removePrefix("/")).use {
                    retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    private fun insumosAudio(): List<MusicTrack> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val tracks = mutableListOf<MusicTrack>()
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("${Config.INSUMOS_FOLDER}/%"),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    tracks += MusicTrack.fromFileName(name, ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
                }
            }
        }
        return tracks
    }

    private fun insumosImageUri(fileName: String): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("${Config.INSUMOS_FOLDER}/%", fileName),
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
            }
        }.getOrNull()
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg")
    }
}

/**
 * Una canción. Si el nombre del archivo termina en `@segundos` (p. ej. `cruel_summer@52.mp3`)
 * la música empieza en ese segundo, útil para arrancar directamente en el coro.
 */
data class MusicTrack(val title: String, val uri: Uri, val startMs: Long) {
    companion object {
        private val START_SUFFIX = Regex("""^(.*)@(\d+)$""")

        fun fromFileName(fileName: String, uri: Uri): MusicTrack {
            val base = fileName.substringBeforeLast('.')
            val match = START_SUFFIX.matchEntire(base)
            val title = (match?.groupValues?.get(1) ?: base).replace('_', ' ')
            val startMs = (match?.groupValues?.get(2)?.toLongOrNull() ?: 0L) * 1000
            return MusicTrack(title, uri, startMs)
        }
    }
}
