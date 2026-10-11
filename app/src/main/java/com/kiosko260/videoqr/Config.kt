package com.kiosko260.videoqr

import android.graphics.Color

/** Ajustes del kiosko. Cambia aquí los valores y vuelve a compilar. */
object Config {
    /** Texto del sticker del marco. */
    const val HASHTAG = "#etu2026"

    /** Efecto de velocidad que se usa al abrir la app (se puede cambiar en pantalla). */
    val DEFAULT_SPEED_EFFECT = SpeedEffect.SPEED_RAMP

    /**
     * Formato vertical 9:16 de Instagram Reels y TikTok. Los videos horizontales se recortan
     * al centro para llenar la pantalla.
     */
    const val OUTPUT_WIDTH = 1080
    const val OUTPUT_HEIGHT = 1920

    /** Calidad del video final (bits por segundo). 10 Mbps se ve nítido en Reels/TikTok. */
    const val VIDEO_BITRATE = 10_000_000

    /** Retoque "aesthetic": un poco más de contraste y color. 0 = sin cambios. */
    const val CONTRAST = 0.08f
    const val SATURATION = 12f

    /**
     * Carpeta de insumos dentro de "Almacenamiento interno":
     *  - [LOGO_FILE] (marco.png) se usa como logo arriba a la izquierda.
     *  - Todos los .mp3 de la carpeta se usan como música.
     */
    const val INSUMOS_FOLDER = "Download/INSUMOS"
    const val LOGO_FILE = "marco.png"

    /** Carpeta donde se guardan los videos terminados (Almacenamiento interno > Download > PROCESADOS). */
    const val PROCESADOS_FOLDER = "PROCESADOS"

    /** Ancho del logo como fracción del ancho del video. */
    const val LOGO_WIDTH_FRACTION = 0.30f

    /**
     * Paleta energética/juvenil. Se usa en el marco, la pantalla del kiosko y la página de descarga.
     */
    val NARANJA = Color.parseColor("#F68425")
    val ROJO = Color.parseColor("#FB3A4B")
    val NARANJA_FUEGO = Color.parseColor("#FF7034")
    val CORAL = Color.parseColor("#F54945")
    val AMARILLO = Color.parseColor("#FCAB14")
    val ROSA = Color.parseColor("#F178AF")
    val ROSA_VIEJO = Color.parseColor("#E47493")

    /**
     * Carpeta que se vigila (patrón SQL LIKE sobre RELATIVE_PATH de MediaStore).
     * Samsung guarda lo recibido por Quick Share en "Download/Quick Share/".
     * En Pixel y otros equipos se guarda directamente en "Download/": en ese caso usa "Download/%".
     */
    const val QUICK_SHARE_FOLDER_LIKE = "%Quick Share%"

    /** Puerto del servidor local de descargas. */
    const val SERVER_PORT = 8080

    /** Respaldo dentro del APK (assets/) si no se encuentra nada en la carpeta de insumos. */
    const val FALLBACK_LOGO_ASSET = "logo.png"
    const val MUSIC_DIR = "music"
}
