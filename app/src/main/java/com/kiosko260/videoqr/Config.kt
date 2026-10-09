package com.kiosko260.videoqr

/** Ajustes del kiosko. Cambia aquí los valores y vuelve a compilar. */
object Config {
    /** Velocidad del video: 0.5 = la mitad de rápido (cámara lenta x2), 0.25 = x4. */
    const val SLOW_MOTION_SPEED = 0.5f

    /** Ancho del logo (esquina superior izquierda) como fracción del ancho del video. */
    const val LOGO_WIDTH_FRACTION = 0.30f

    /** Separación del logo respecto a los bordes, como fracción del ancho del video. */
    const val LOGO_MARGIN_FRACTION = 0.04f

    /** El lado corto del video final no superará estos píxeles (1080 = Full HD). */
    const val MAX_SHORT_SIDE_PX = 1080

    /**
     * Carpeta que se vigila (patrón SQL LIKE sobre RELATIVE_PATH de MediaStore).
     * Samsung guarda lo recibido por Quick Share en "Download/Quick Share/".
     * En Pixel y otros equipos se guarda directamente en "Download/": en ese caso usa "Download/%".
     */
    const val QUICK_SHARE_FOLDER_LIKE = "%Quick Share%"

    /** Puerto del servidor local de descargas. */
    const val SERVER_PORT = 8080

    /** Los videos editados se borran pasado este tiempo para no llenar la memoria. */
    const val KEEP_OUTPUT_HOURS = 48L

    /** Archivos dentro de assets/ (o de la carpeta de personalización, que tiene prioridad). */
    const val FRAME_FILE = "marco.png"
    const val LOGO_FILE = "logo.png"
    const val MUSIC_DIR = "music"
}
