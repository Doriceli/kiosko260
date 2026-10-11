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
class VideoServer(private val library: VideoLibrary, port: Int) : NanoHTTPD(port) {

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
        if (!VideoLibrary.ID_PATTERN.matches(id)) return null
        return library.videoFile(id).takeIf { it.isFile }
    }

    private fun page(id: String): Response {
        resolve(id) ?: return notFound()
        val html = """
            <!doctype html>
            <html lang="es"><head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Tu video</title>
            <style>
              body{margin:0;font-family:system-ui,sans-serif;color:#fff;text-align:center;padding:16px;
                   box-sizing:border-box;min-height:100vh;
                   background:linear-gradient(160deg,#FCAB14 0%,#F68425 25%,#FB3A4B 55%,#F178AF 85%,#E47493 100%)}
              h1{margin:8px 0 0;font-size:40px;font-weight:900;letter-spacing:1px;
                 text-shadow:0 3px 0 #F54945,0 6px 18px rgba(0,0,0,.25);transform:rotate(-3deg)}
              h2{font-weight:700;margin:6px 0 16px}
              video{width:100%;max-width:420px;aspect-ratio:9/16;object-fit:cover;border-radius:22px;
                    background:#000;border:4px solid #fff;box-shadow:0 10px 30px rgba(0,0,0,.3)}
              a.btn{display:block;max-width:420px;margin:18px auto;padding:18px;border-radius:999px;
                    background:#fff;color:#FB3A4B;font-size:21px;font-weight:900;text-decoration:none;
                    box-shadow:0 6px 0 #F54945}
              p{font-size:15px;max-width:420px;margin:8px auto;text-shadow:0 1px 2px rgba(0,0,0,.25)}
            </style></head><body>
            <h1>${Config.HASHTAG}</h1>
            <h2>¡Tu video está listo! 🎬</h2>
            <video src="/f/$id.mp4" poster="/t/$id.jpg" controls playsinline loop></video>
            <a class="btn" href="/f/$id.mp4?dl=1" download="video-$id.mp4">⬇️ Descargar video</a>
            <p>Súbelo como Reel o TikTok 🔥 Ábrelo desde tu galería (o en iPhone desde Archivos &rarr; Descargas &rarr; Compartir &rarr; Guardar video) y etiqueta ${Config.HASHTAG}</p>
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
        if (!VideoLibrary.ID_PATTERN.matches(id)) return notFound()
        val file = library.thumbnailFile(id).takeIf { it.isFile } ?: return notFound()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(file), file.length())
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Video no encontrado o ya expiró.")

    private companion object {
        const val MIME_MP4 = "video/mp4"
    }
}
