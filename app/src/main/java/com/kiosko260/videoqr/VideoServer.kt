package com.kiosko260.videoqr

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.io.FileInputStream

/**
 * Servidor HTTP dentro del celular del kiosko.
 *  - `/v/<id>`      página con vista previa y botón "Descargar".
 *  - `/f/<id>.mp4`  el archivo (con soporte de Range, necesario para reproducir en iPhone).
 *  - `/t/<id>.jpg`  la miniatura (portada del video).
 * El celular del cliente debe estar en la misma red Wi-Fi (o conectado al hotspot del kiosko).
 */
class VideoServer(private val videosDir: File, port: Int) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            path.startsWith("/v/") -> page(path.removePrefix("/v/"))
            path.startsWith("/f/") -> file(path.removePrefix("/f/").removeSuffix(".mp4"), session)
            path.startsWith("/t/") -> thumbnail(path.removePrefix("/t/").removeSuffix(".jpg"))
            else -> notFound()
        }
    }

    private fun resolve(id: String): File? {
        if (!ID_PATTERN.matches(id)) return null
        return File(videosDir, "$id.mp4").takeIf { it.isFile }
    }

    private fun page(id: String): Response {
        resolve(id) ?: return notFound()
        val html = """
            <!doctype html>
            <html lang="es"><head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Tu video</title>
            <style>
              body{margin:0;font-family:system-ui,sans-serif;background:linear-gradient(#061A47,#0B2A6F);
                   min-height:100vh;color:#fff;text-align:center;padding:16px;box-sizing:border-box}
              h1{color:#FFD700;margin:8px 0 0;font-size:34px}
              h2{color:#C9A227;font-weight:600;margin:4px 0 16px}
              video{width:100%;max-width:480px;border-radius:12px;background:#000;border:3px solid #C9A227}
              a.btn{display:block;max-width:480px;margin:16px auto;padding:16px;border-radius:12px;
                    background:linear-gradient(90deg,#C9A227,#FFD700,#C9A227);color:#061A47;
                    font-size:20px;font-weight:bold;text-decoration:none}
              p{color:#C8D3F0;font-size:15px;max-width:480px;margin:8px auto}
            </style></head><body>
            <h1>${Config.HASHTAG}</h1>
            <h2>¡Tu video está listo! 🎬</h2>
            <video src="/f/$id.mp4" poster="/t/$id.jpg" controls playsinline loop></video>
            <a class="btn" href="/f/$id.mp4?dl=1" download="video-$id.mp4">⬇️ Descargar video</a>
            <p>Después ábrelo desde tu galería (o en iPhone desde Archivos &rarr; Descargas &rarr; Compartir &rarr; Guardar video) y súbelo a Instagram, TikTok, WhatsApp…</p>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun file(id: String, session: IHTTPSession): Response {
        val file = resolve(id) ?: return notFound()
        val total = file.length()
        val range = session.headers["range"]

        val response = if (range != null && range.startsWith("bytes=")) {
            val (startText, endText) = range.removePrefix("bytes=").substringBefore(',').split('-', limit = 2)
                .let { it[0] to it.getOrElse(1) { "" } }
            val start: Long
            val end: Long
            if (startText.isEmpty()) { // "bytes=-500": últimos 500 bytes
                start = (total - (endText.toLongOrNull() ?: 0L)).coerceAtLeast(0L)
                end = total - 1
            } else {
                start = startText.toLongOrNull() ?: 0L
                end = (endText.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
            }
            if (start > end || start >= total) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */$total") }
            }
            val input = FileInputStream(file).apply { skip(start) }
            newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, MIME_MP4, input, end - start + 1).apply {
                addHeader("Content-Range", "bytes $start-$end/$total")
            }
        } else {
            newFixedLengthResponse(Response.Status.OK, MIME_MP4, FileInputStream(file), total)
        }

        response.addHeader("Accept-Ranges", "bytes")
        if (session.parameters.containsKey("dl")) {
            response.addHeader("Content-Disposition", "attachment; filename=\"video-$id.mp4\"")
        }
        return response
    }

    private fun thumbnail(id: String): Response {
        if (!ID_PATTERN.matches(id)) return notFound()
        val file = File(videosDir, "$id.jpg").takeIf { it.isFile } ?: return notFound()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(file), file.length())
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Video no encontrado o ya expiró.")

    private companion object {
        val ID_PATTERN = Regex("^[A-Za-z0-9]{6,32}$")
        const val MIME_MP4 = "video/mp4"
    }
}
