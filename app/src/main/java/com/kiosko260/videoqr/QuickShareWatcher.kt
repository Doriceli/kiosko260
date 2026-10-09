package com.kiosko260.videoqr

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * Detecta los videos nuevos que Quick Share deja en Descargas y avisa con su Uri.
 * Solo considera los que llegan después de [start], para no reprocesar lo que ya había.
 */
class QuickShareWatcher(
    private val context: Context,
    private val onNewVideo: (Uri) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val seen = mutableSetOf<Long>()
    private var startedAtSeconds = 0L
    private var running = false

    private val scanRunnable = Runnable { scan() }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // Quick Share escribe el archivo por partes: esperar un poco a que termine.
            handler.removeCallbacks(scanRunnable)
            handler.postDelayed(scanRunnable, 3_000)
        }
    }

    fun start() {
        if (running) return
        running = true
        startedAtSeconds = System.currentTimeMillis() / 1000 - 5
        context.contentResolver.registerContentObserver(COLLECTION, true, observer)
        // Respaldo por si algún equipo no notifica el cambio.
        handler.postDelayed(periodicScan, 10_000)
    }

    fun stop() {
        running = false
        context.contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(periodicScan)
    }

    private val periodicScan = object : Runnable {
        override fun run() {
            scan()
            if (running) handler.postDelayed(this, 10_000)
        }
    }

    private fun scan() {
        if (!running) return
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.Video.Media.DATE_ADDED} >= ? AND " +
            "${MediaStore.Video.Media.IS_PENDING} = 0 AND " +
            "${MediaStore.Video.Media.SIZE} > 0"
        val args = arrayOf(Config.QUICK_SHARE_FOLDER_LIKE, startedAtSeconds.toString())

        runCatching {
            context.contentResolver.query(
                COLLECTION, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    if (seen.add(id)) onNewVideo(ContentUris.withAppendedId(COLLECTION, id))
                }
            }
        }
    }

    private companion object {
        val COLLECTION: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }
}
