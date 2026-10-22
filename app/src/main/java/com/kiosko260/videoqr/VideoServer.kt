package com.kiosko260.videoqr

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Servidor HTTP dentro del celular del kiosko.
 *  - `/v/<id>`      página con vista previa y botón "Descargar".
 *  - `/f/<id>.mp4`  el archivo (con soporte de Range, necesario para reproducir en iPhone).
 *  - `/t/<id>.jpg`  la miniatura (portada del video).
 * El celular del cliente debe estar en la misma red Wi-Fi (o conectado al hotspot del kiosko).
 */
class VideoServer(
    private val library: VideoLibrary,
    port: Int,
    private val hashtag: () -> String,
    /** Se llama (en el hilo del servidor) cuando un invitado terminó de descargar un video. */
    private val onDownloaded: (String) -> Unit = {},
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            path.startsWith("/v/") -> page(path.removePrefix("/v/"))
            path.startsWith("/f/") -> file(path.removePrefix("/f/").removeSuffix(".mp4"), session)
            path.startsWith("/t/") -> thumbnail(path.removePrefix("/t/").removeSuffix(".jpg"))
            // Dirección corta para escribir a mano: http://IP:8080/K7P2
            SHORT_CODE.matches(path) -> library.idForCode(path.trim('/'))?.let(::page) ?: notFound()
            else -> notFound()
        }
    }

    private fun resolve(id: String): File? {
        if (!VideoLibrary.ID_PATTERN.matches(id)) return null
        return library.videoFile(id).takeIf { it.isFile }
    }

    private fun page(id: String): Response {
        resolve(id) ?: return notFound()
        // Ojo: es un string de Kotlin; en el JavaScript no se usan plantillas con "$".
        val html = """
            <!doctype html>
            <html lang="es"><head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Tu video</title>
            <style>
              body{margin:0;font-family:system-ui,sans-serif;color:#fff;text-align:center;padding:16px;
                   box-sizing:border-box;min-height:100vh;
                   background:linear-gradient(160deg,#FB3A4B 0%,#F54945 20%,#F178AF 55%,#E47493 75%,#800080 100%)}
              h1{margin:6px 0 0;font-size:36px;font-weight:900;letter-spacing:1px;
                 text-shadow:0 3px 0 #800080,0 6px 18px rgba(0,0,0,.25);transform:rotate(-3deg)}
              h2{font-weight:700;margin:6px 0 14px}
              video{width:100%;max-width:420px;aspect-ratio:9/16;object-fit:cover;border-radius:22px;
                    background:#000;border:4px solid #fff;box-shadow:0 10px 30px rgba(0,0,0,.3)}
              .btn{position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;gap:12px;
                   max-width:420px;margin:0 auto 18px;padding:20px 16px;border-radius:999px;border:4px solid #fff;
                   font-size:23px;font-weight:900;text-decoration:none;color:#fff;
                   box-shadow:0 7px 0 rgba(0,0,0,.25);transition:background .25s;
                   -webkit-tap-highlight-color:transparent}
              .btn .fill{position:absolute;left:0;top:0;bottom:0;width:0;background:rgba(255,255,255,.35);transition:width .2s}
              .btn span,.btn svg{position:relative}
              .btn svg{width:30px;height:30px;flex:none}
              .idle{background:#1CC9B9}            /* turquesa */
              .loading{background:#F28B8B}         /* rojo tenue */
              .done{background:#1CC9B9}
              .bar{height:10px;max-width:420px;margin:-8px auto 18px;border-radius:99px;background:rgba(255,255,255,.3);overflow:hidden;display:none}
              .bar div{height:100%;width:0;background:#fff;transition:width .2s}
              p{font-size:15px;max-width:420px;margin:12px auto;text-shadow:0 1px 2px rgba(0,0,0,.25)}
            </style></head><body>
            <h1>${hashtag()}</h1>
            <h2>¡Tu video está listo! 🎬</h2>
            <a id="dl" class="btn idle" href="/f/$id.mp4?dl=1" download="video-$id.mp4">
              <div class="fill" id="fill"></div>
              <svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M12 3v13"/><path d="M6 11l6 6 6-6"/><path d="M4 21h16"/></svg>
              <span id="label">Descarga aquí</span>
            </a>
            <div class="bar" id="bar"><div id="barfill"></div></div>
            <video src="/f/$id.mp4" poster="/t/$id.jpg" controls playsinline loop></video>
            <p>Súbelo como Reel o TikTok 🔥 Ábrelo desde tu galería (en iPhone: Archivos &rarr; Descargas &rarr; Compartir &rarr; Guardar video) y etiqueta ${hashtag()}</p>
            <script>
            (function(){
              var btn=document.getElementById('dl'), label=document.getElementById('label'),
                  fill=document.getElementById('fill'), bar=document.getElementById('bar'),
                  barfill=document.getElementById('barfill'), busy=false;
              function show(state, pct){
                btn.className='btn '+state;
                if(state==='idle'){label.textContent='Descarga aquí';fill.style.width='0';bar.style.display='none';}
                if(state==='loading'){
                  label.textContent=pct>=0?('Descargando... '+pct+'%'):'Descargando...';
                  fill.style.width=(pct>=0?pct:0)+'%'; bar.style.display='block'; barfill.style.width=(pct>=0?pct:0)+'%';
                }
                if(state==='done'){label.textContent='✅ ¡Listo! Toca para descargar de nuevo';fill.style.width='0';bar.style.display='none';}
              }
              btn.addEventListener('click', function(e){
                // Navegadores sin soporte: descarga normal del enlace.
                if(!window.fetch||!window.ReadableStream||!window.URL||!window.Blob) return;
                e.preventDefault();
                if(busy) return;
                busy=true; show('loading',0);
                fetch(btn.getAttribute('href')).then(function(r){
                  if(!r.ok||!r.body) throw new Error('http');
                  var total=parseInt(r.headers.get('Content-Length')||'0',10), got=0, chunks=[], reader=r.body.getReader();
                  function pump(){
                    return reader.read().then(function(x){
                      if(x.done) return new Blob(chunks,{type:'video/mp4'});
                      chunks.push(x.value); got+=x.value.length;
                      show('loading', total ? Math.min(99, Math.round(got*100/total)) : -1);
                      return pump();
                    });
                  }
                  return pump();
                }).then(function(blob){
                  var url=URL.createObjectURL(blob), a=document.createElement('a');
                  a.href=url; a.download=btn.getAttribute('download');
                  document.body.appendChild(a); a.click(); a.remove();
                  setTimeout(function(){URL.revokeObjectURL(url);},60000);
                  busy=false; show('done');
                }).catch(function(){
                  busy=false; show('idle');
                  window.location.href=btn.getAttribute('href');
                });
              });
            })();
            </script>
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
            val input = watchDownload(FileInputStream(file).apply { skip(start) }, end - start + 1, id, session, end == total - 1)
            newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, MIME_MP4, input, end - start + 1).apply {
                addHeader("Content-Range", "bytes $start-$end/$total")
            }
        } else {
            newFixedLengthResponse(Response.Status.OK, MIME_MP4, watchDownload(FileInputStream(file), total, id, session, true), total)
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

    /**
     * Para las descargas (?dl=1) que llegan hasta el final del archivo, avisa cuando se enviaron
     * todos los bytes. Así la app sabe que el invitado ya tiene su video y puede apagar la red.
     */
    private fun watchDownload(input: InputStream, length: Long, id: String, session: IHTTPSession, reachesEnd: Boolean): InputStream {
        if (!reachesEnd || !session.parameters.containsKey("dl")) return input
        return object : FilterInputStream(input) {
            private var sent = 0L
            private var notified = false

            private fun count(n: Int): Int {
                if (n > 0) sent += n
                if (!notified && sent >= length) {
                    notified = true
                    runCatching { onDownloaded(id) }
                }
                return n
            }

            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Video no encontrado o ya expiró.")

    private companion object {
        const val MIME_MP4 = "video/mp4"
        val SHORT_CODE = Regex("^/[A-Za-z0-9]{4}/?$")
    }
}
