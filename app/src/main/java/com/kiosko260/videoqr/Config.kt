package com.kiosko260.videoqr

import android.graphics.Color

/** Ajustes del kiosko. Cambia aquí los valores y vuelve a compilar. */
object Config {
    /** Borde de colores alrededor del video. false = video a pantalla completa, sin margen. */
    const val SHOW_BORDER = false

    /** Hashtag por defecto de la esquina inferior derecha (se puede editar en pantalla antes de cada video). */
    const val HASHTAG = "#etu2026"

    /** Tamaño de letra del sticker, en % del ancho del video (antes 8; 4 = discreto). */
    const val HASHTAG_SIZE = 4f

    /** Opacidad del sticker: 255 = sólido, 200 ≈ 80 %. */
    const val HASHTAG_ALPHA = 215

    /** Efecto de velocidad que se usa al abrir la app (se puede cambiar en pantalla). */
    val DEFAULT_SPEED_EFFECT = SpeedEffect.SPEED_RAMP

    /**
     * Calidad: se respeta la resolución original del video (no se reescala).
     *  - Videos verticales: salen con su misma resolución (1080×1920, 2160×3840…).
     *  - Videos horizontales: se recortan al centro a 9:16 (Reels/TikTok) sin agrandarlos.
     * Solo si el lado largo supera este valor se reduce (3840 = 4K).
     */
    const val MAX_OUTPUT_LONG_SIDE = 3840

    /**
     * Bitrate del video final: el mayor entre el del video original y
     * (ancho × alto × [MIN_BITS_PER_PIXEL]) ≈ 12 Mbps en 1080p y ≈ 50 Mbps en 4K.
     */
    const val MIN_BITS_PER_PIXEL = 6
    const val MAX_VIDEO_BITRATE = 100_000_000

    /**
     * Tamaño máximo del video final, para que se transfiera rápido. La app baja el bitrate solo
     * lo necesario para no pasarse (un video de ~20 s queda en ~13 Mbps, que se ve muy bien en 1080p).
     */
    const val MAX_OUTPUT_MB = 35

    /** Nunca bajar de este bitrate (aunque el video sea muy largo y pase del límite). */
    const val MIN_VIDEO_BITRATE = 4_000_000

    /**
     * En los tramos acelerados del speed ramp un video de 60 fps llegaría a ~100 fps, algo que
     * muchos codificadores y redes no aceptan. Se limita a este valor (no afecta la cámara lenta).
     */
    const val MAX_FRAME_RATE = 60f

    /** Retoque de color opcional (0 = colores originales, sin retoque). Ej.: 0.08f y 12f. */
    const val CONTRAST = 0f
    const val SATURATION = 0f

    /**
     * Carpeta de insumos dentro de "Almacenamiento interno":
     *  - [LOGO_FILE] (marco.png) se usa como logo arriba a la izquierda.
     *  - Todos los .mp3 de la carpeta se usan como música.
     */
    const val INSUMOS_FOLDER = "Download/INSUMOS"
    const val LOGO_FILE = "marco.png"

    /**
     * Diseño "doble logo" (se activa con el interruptor de la pantalla, sin recompilar):
     * franja superior de 1/[TOP_BAND_FRACTION_DENOMINATOR] de la pantalla con [HASHTAG_IMAGE_FILE]
     * arriba y [LOGO_FILE] abajo. Si falta hashtag.png se usa el diseño clásico.
     */
    const val HASHTAG_IMAGE_FILE = "hashtag.png"
    const val TOP_BAND_FRACTION_DENOMINATOR = 9

    /** Carpeta donde se guardan los videos terminados (Almacenamiento interno > Download > PROCESADOS). */
    const val PROCESADOS_FOLDER = "PROCESADOS"

    /** Subcarpeta con los videos que el invitado autorizó compartir en redes: Download/PROCESADOS/AUTORIZADOS. */
    const val AUTORIZADOS_FOLDER = "AUTORIZADOS"

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

    /**
     * Cambio automático entre Quick Share y la red del kiosko (no pueden funcionar a la vez porque
     * los dos usan la antena Wi-Fi):
     *  - Mientras se espera un video, la red está APAGADA → Quick Share funciona normal.
     *  - Al empezar a editar un video se ENCIENDE la red para que el QR esté listo al terminar.
     *  - Se vuelve a apagar [AFTER_DOWNLOAD_SECONDS] después de que el invitado descarga su video,
     *    o a los [SHARE_WINDOW_MINUTES] minutos si nadie lo descarga.
     */
    const val SHARE_WINDOW_MINUTES = 4L

    /**
     * Con "Acceso a notificaciones" activado, pulsar "Aceptar" en las solicitudes de Quick Share.
     * Lo más fiable es configurar Quick Share para aceptar solo de tus dispositivos (ver README).
     */
    const val AUTO_ACCEPT_QUICK_SHARE = true
    const val AFTER_DOWNLOAD_SECONDS = 20L

    /** Puerto del servidor local de descargas. */
    const val SERVER_PORT = 8080

    /** Respaldo dentro del APK (assets/) si no se encuentra nada en la carpeta de insumos. */
    const val FALLBACK_LOGO_ASSET = "logo.png"
    const val MUSIC_DIR = "music"
}
