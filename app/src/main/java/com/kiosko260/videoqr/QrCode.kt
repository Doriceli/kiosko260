package com.kiosko260.videoqr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Genera el QR dentro de la tablet (ZXing), sin internet. */
object QrCode {
    fun create(text: String, size: Int = 800): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2, // borde blanco que las cámaras necesitan para leerlo
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, // se lee aunque haya reflejos
            EncodeHintType.CHARACTER_SET to "UTF-8", // nombres de red con "@" u otros símbolos
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
