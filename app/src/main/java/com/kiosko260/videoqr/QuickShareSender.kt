package com.kiosko260.videoqr

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

/**
 * Abre Quick Share con el video ya procesado. Desde la ventana de Quick Share se puede elegir
 * "Código QR" o "Copiar enlace" (lo genera Samsung; requiere internet) o enviarlo a un
 * dispositivo cercano (funciona sin internet).
 *
 * Android no permite que otra app lea el QR o el enlace que crea Quick Share, por eso se abre
 * su propia ventana con el video listo.
 */
object QuickShareSender {

    private val QUICK_SHARE_PACKAGES = listOf(
        "com.samsung.android.app.sharelive", // Quick Share de Samsung (Galaxy Tab S8)
        "com.google.android.gms",            // Quick Share de Google
    )

    fun intentFor(context: Context, video: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", video)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(video.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Directo a Quick Share si está instalado; si no, la hoja de compartir del sistema.
        val pm = context.packageManager
        QUICK_SHARE_PACKAGES.forEach { pkg ->
            val direct = Intent(send).setPackage(pkg)
            if (pm.queryIntentActivities(direct, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()) return direct
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
