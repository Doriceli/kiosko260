package com.kiosko260.videoqr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * Busca marco, logo y canciones primero en
 * `Android/data/com.kiosko260.videoqr/files/personalizacion/` (se pueden cambiar por USB sin recompilar)
 * y, si no están, en los assets del APK.
 */
class Customization(private val context: Context) {

    val folder: File = File(context.getExternalFilesDir(null), "personalizacion").apply {
        File(this, Config.MUSIC_DIR).mkdirs()
    }

    fun loadBitmap(name: String): Bitmap? {
        val external = File(folder, name)
        if (external.isFile) return BitmapFactory.decodeFile(external.absolutePath)
        return runCatching {
            context.assets.open(name).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    fun musicTracks(): List<MusicTrack> {
        val tracks = mutableListOf<MusicTrack>()
        File(folder, Config.MUSIC_DIR).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.forEach { tracks += MusicTrack.fromFileName(it.name, Uri.fromFile(it)) }
        context.assets.list(Config.MUSIC_DIR)
            ?.filter { it.substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS }
            ?.forEach { tracks += MusicTrack.fromFileName(it, Uri.parse("asset:///${Config.MUSIC_DIR}/$it")) }
        return tracks.sortedBy { it.title.lowercase() }
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
