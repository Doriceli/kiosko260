package com.kiosko260.videoqr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Edita un video con Media3 Transformer, pensado para Instagram Reels y TikTok:
 *  1. curva de velocidad ([SpeedEffect]: speed ramp, cámara lenta progresiva…),
 *  2. formato vertical 9:16 respetando la resolución original (los horizontales se recortan al centro),
 *  3. retoque de color opcional (desactivado por defecto),
 *  4. logos + hashtag (editable en pantalla) abajo a la derecha,
 *  5. reemplaza el audio por una canción recortada a la duración exacta del video final.
 * El bitrate busca la calidad del original, pero sin pasar de [Config.MAX_OUTPUT_MB] por archivo.
 */
@OptIn(UnstableApi::class)
class VideoProcessor(private val context: Context) {

    private val customization = Customization(context)

    /** HEVC si la tablet puede codificarlo (y [Config.PREFER_HEVC]); si no, H.264. */
    private val videoMimeType: String by lazy {
        val hasHevcEncoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) }
        }
        if (Config.PREFER_HEVC && hasHevcEncoder) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
    }

    private companion object {
        /**
         * Parte del límite de tamaño que se usa para el video (el resto es margen por si el
         * codificador se pasa un poco; si se pasa del límite, se recodifica con menos bitrate).
         */
        const val SIZE_SAFETY = 0.96
        /** Bitrate de audio AAC que usa Media3 por defecto (128 kbps). */
        const val AUDIO_BITRATE = 128 * 1024
    }

    suspend fun process(
        input: Uri,
        music: MusicTrack?,
        speedEffect: SpeedEffect,
        layout: OverlayLayout,
        hashtag: String,
        output: File,
        onProgress: (Int) -> Unit,
    ): File {
        val info = withContext(Dispatchers.IO) { probe(input) }
        val overlay = withContext(Dispatchers.Default) { buildOverlay(info.outputWidth, info.outputHeight, layout, hashtag) }
        val speed = speedEffect.toSpeedProvider(info.durationUs)

        val finalDurationUs = speed.outputDurationUs(info.durationUs)
        val songItems = if (music != null) {
            withContext(Dispatchers.IO) { musicItems(music, finalDurationUs / 1000) }
        } else emptyList()

        fun composition(): Composition {
            val videoEffects = mutableListOf<Effect>(
                SpeedChangeEffect(speed),
                FrameDropEffect.createDefaultFrameDropEffect(Config.MAX_FRAME_RATE),
                Presentation.createForWidthAndHeight(
                    info.outputWidth, info.outputHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                ),
            )
            if (Config.CONTRAST != 0f) videoEffects += Contrast(Config.CONTRAST)
            if (Config.SATURATION != 0f) videoEffects += HslAdjustment.Builder().adjustSaturation(Config.SATURATION).build()
            videoEffects += OverlayEffect(listOf<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(overlay)))

            val video = EditedMediaItem.Builder(MediaItem.fromUri(input))
                // El audio original con cambios de velocidad suena mal: se sustituye por la canción.
                .setRemoveAudio(true)
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val sequences = mutableListOf(EditedMediaItemSequence.Builder(listOf(video)).build())
            if (songItems.isNotEmpty()) sequences += EditedMediaItemSequence.Builder(songItems).build()
            return Composition.Builder(sequences)
                // Los videos HDR (muy comunes en Samsung/iPhone) se convierten a SDR con buen mapeo de
                // tonos; si no, se verían lavados en WhatsApp, Instagram y en la mayoría de celulares.
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
        }

        output.parentFile?.mkdirs()
        val maxBytes = Config.MAX_OUTPUT_MB * 1024L * 1024L
        var bitrate = bitrateForSize(info.bitrate, finalDurationUs, maxBytes)
        var result = withContext(Dispatchers.Main) { export(composition(), bitrate, output, onProgress) }

        // Los codificadores no siempre clavan el bitrate pedido: si se pasó del límite, se repite
        // una vez con un bitrate proporcionalmente menor.
        val size = result.length()
        if (size > maxBytes) {
            bitrate = (bitrate * (maxBytes.toDouble() / size) * 0.95).toInt().coerceAtLeast(Config.MIN_VIDEO_BITRATE)
            result = withContext(Dispatchers.Main) { export(composition(), bitrate, output, onProgress) }
        }
        return result
    }

    /**
     * Bitrate de video para que el archivo final no pase de [maxBytes]: el de calidad
     * ([qualityBitrate]) si cabe; si no, el máximo que cabe en esa duración (dejando lugar al audio).
     */
    private fun bitrateForSize(qualityBitrate: Int, durationUs: Long, maxBytes: Long): Int {
        val seconds = (durationUs / 1_000_000.0).coerceAtLeast(1.0)
        val availableBits = maxBytes * 8 * SIZE_SAFETY - AUDIO_BITRATE * seconds
        val budget = (availableBits / seconds).toInt()
        return minOf(qualityBitrate, budget).coerceAtLeast(Config.MIN_VIDEO_BITRATE)
    }

    /**
     * Trozos de la canción que suman exactamente [targetMs]. Si la canción es más corta que el
     * video, se repite desde el principio.
     */
    private fun musicItems(music: MusicTrack, targetMs: Long): List<EditedMediaItem> {
        val songMs = customization.durationMs(music.uri)
        if (songMs <= 0L || targetMs <= 0L) return emptyList()

        val items = mutableListOf<EditedMediaItem>()
        var remaining = targetMs
        var start = music.startMs.takeIf { it < songMs - 1_000 } ?: 0L
        while (remaining > 0 && items.size < 50) {
            val length = min(remaining, songMs - start)
            val mediaItem = MediaItem.Builder()
                .setUri(music.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start)
                        .setEndPositionMs(start + length)
                        .build()
                )
                .build()
            items += EditedMediaItem.Builder(mediaItem).setRemoveVideo(true).build()
            remaining -= length
            start = 0L
        }
        return items
    }

    private suspend fun export(composition: Composition, bitrate: Int, output: File, onProgress: (Int) -> Unit): File =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val progressHolder = ProgressHolder()
            lateinit var poll: Runnable

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(videoMimeType)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setBitrate(bitrate)
                                // Menos fotogramas clave = más bits para cada imagen = mejor calidad al mismo peso.
                                .setiFrameIntervalSeconds(Config.KEYFRAME_INTERVAL_SECONDS)
                                .build()
                        )
                        .setEnableFallback(true)
                        .build()
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        onProgress(100)
                        if (cont.isActive) cont.resume(output)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        handler.removeCallbacks(poll)
                        output.delete()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()

            poll = Runnable {
                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress)
                }
                handler.postDelayed(poll, 500)
            }

            transformer.start(composition, output.absolutePath)
            handler.post(poll)

            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                    output.delete()
                }
            }
        }

    /** Guarda una miniatura JPG del video editado (para mostrarla junto al QR). */
    fun createThumbnail(video: File, thumbnail: File): File? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) * 1000
            val frame = retriever.getFrameAtTime(durationUs / 2, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scale = min(1f, 540f / frame.width)
            val small = Bitmap.createScaledBitmap(frame, even(frame.width * scale), even(frame.height * scale), true)
            FileOutputStream(thumbnail).use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            thumbnail
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private data class VideoInfo(
        val outputWidth: Int,
        val outputHeight: Int,
        val durationUs: Long,
        val bitrate: Int,
    )

    /** Lee el video original y decide resolución y bitrate de salida sin perder calidad. */
    private fun probe(input: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, input)
            fun meta(key: Int) = retriever.extractMetadata(key)?.toLongOrNull()

            var w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080
            var h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (rotation == 90 || rotation == 270) w = h.also { h = w }

            // Horizontal → recorte 9:16 al centro con la altura original (sin agrandar).
            var outW = if (w > h) h * 9f / 16f else w.toFloat()
            var outH = h.toFloat()
            val scale = min(1f, Config.MAX_OUTPUT_LONG_SIDE / maxOf(outW, outH))
            outW *= scale
            outH *= scale
            val width = even(outW)
            val height = even(outH)

            val sourceBitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toInt() ?: 0
            val bitrate = maxOf(sourceBitrate, width * height * Config.MIN_BITS_PER_PIXEL)
                .coerceAtMost(Config.MAX_VIDEO_BITRATE)

            val durationUs = (meta(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0L) * 1000
            return VideoInfo(width, height, durationUs, bitrate)
        } finally {
            retriever.release()
        }
    }

    private fun even(value: Float): Int = (value / 2f).roundToInt() * 2

    /**
     * Sticker con el hashtag y logo sobre el video a pantalla completa. El borde con degradado
     * es opcional ([Config.SHOW_BORDER], desactivado).
     * El logo queda debajo de las pestañas de TikTok/Reels; el sticker, pequeño en la esquina
     * inferior derecha ([Config.HASHTAG_SIZE]) para no tapar el video.
     */
    private fun buildOverlay(width: Int, height: Int, layout: OverlayLayout, hashtag: String): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val unit = width / 100f // 1 % del ancho

        // Borde opcional con degradado diagonal y esquinas interiores redondeadas.
        val border = if (Config.SHOW_BORDER) 2.2f * unit else 0f
        if (Config.SHOW_BORDER) {
            val palette = intArrayOf(Config.AZUL_ELECTRICO, Config.ROSA, Config.ROJO, Config.MORADO)
            val radius = 5f * unit
            val frame = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addRoundRect(RectF(border, border, width - border, height - border), radius, radius, Path.Direction.CW)
            }
            canvas.drawPath(frame, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), palette, null, Shader.TileMode.CLAMP)
            })
        }

        if (layout == OverlayLayout.DOUBLE_TOP && drawTopBand(canvas, width, height)) {
            drawPlainHashtag(canvas, width, height, unit, border, hashtag)
            return result
        }

        if (hashtag.isNotBlank()) drawSticker(canvas, width, height, unit, border, hashtag)

        // Logo (marco.png de INSUMOS) arriba a la izquierda, debajo de las pestañas de TikTok/Reels.
        customization.loadLogo()?.let { logo ->
            val left = (7f * unit).roundToInt()
            val top = (height * 0.08f).roundToInt()
            val logoWidth = (width * Config.LOGO_WIDTH_FRACTION).roundToInt()
            val logoHeight = (logoWidth.toFloat() * logo.height / logo.width).roundToInt()
            canvas.drawBitmap(
                logo, null, Rect(left, top, left + logoWidth, top + logoHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            logo.recycle()
        }
        return result
    }

    /** Sticker pequeño de colores con el hashtag en la esquina inferior derecha (diseño clásico). */
    private fun drawSticker(canvas: Canvas, width: Int, height: Int, unit: Float, border: Float, hashtag: String) {
        val size = Config.HASHTAG_SIZE * unit
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size
            textAlign = Paint.Align.CENTER
            setShadowLayer(0.3f * unit, 0f, 0.2f * unit, Config.CORAL)
        }
        val textWidth = textPaint.measureText(hashtag)
        val pillWidth = textWidth + 1.2f * size
        val pillHeight = 1.6f * size
        val margin = border + 3.5f * unit
        val pill = RectF(width - margin - pillWidth, height - margin - pillHeight, width - margin, height - margin)
        val centerX = pill.centerX()
        val centerY = pill.centerY()
        canvas.saveLayerAlpha(null, Config.HASHTAG_ALPHA)
        canvas.rotate(-3f, centerX, centerY)
        canvas.drawRoundRect(pill, pillHeight / 2, pillHeight / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                pill.left, 0f, pill.right, 0f,
                intArrayOf(Config.AMARILLO, Config.ROJO, Config.ROSA, Config.MORADO), null, Shader.TileMode.CLAMP,
            )
            setShadowLayer(0.8f * unit, 0f, 0.4f * unit, Color.argb(90, 0, 0, 0))
        })
        canvas.drawRoundRect(pill, pillHeight / 2, pillHeight / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.4f * unit
            color = Color.WHITE
        })
        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(hashtag, centerX, baseline, textPaint)
        canvas.restore()
    }

    /**
     * Hashtag en letras blancas sin fondo, abajo a la derecha (diseño "doble logo").
     * Lleva una sombra suave para que se lea también sobre fondos claros.
     */
    private fun drawPlainHashtag(canvas: Canvas, width: Int, height: Int, unit: Float, border: Float, hashtag: String) {
        if (hashtag.isBlank()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = Config.HASHTAG_SIZE * unit
            textAlign = Paint.Align.RIGHT
            setShadowLayer(0.5f * unit, 0f, 0.2f * unit, Color.argb(140, 0, 0, 0))
        }
        val margin = border + 3.5f * unit
        canvas.drawText(hashtag, width - margin, height - margin - paint.descent(), paint)
    }

    /**
     * Diseño "doble logo": en la franja superior (1/9 de la altura) va hashtag.png arriba y
     * marco.png abajo, centrados y sin deformar. Devuelve false si no hay hashtag.png, para que
     * se use el diseño clásico.
     */
    private fun drawTopBand(canvas: Canvas, width: Int, height: Int): Boolean {
        val hashtag = customization.loadInsumoImage(Config.HASHTAG_IMAGE_FILE) ?: return false
        val logo = customization.loadInsumoImage(Config.LOGO_FILE)

        val band = height.toFloat() / Config.TOP_BAND_FRACTION_DENOMINATOR
        val padding = band * 0.06f
        val gap = band * 0.04f
        val images = listOfNotNull(hashtag, logo)
        val slotHeight = (band - 2 * padding - gap * (images.size - 1)) / images.size
        val maxWidth = width * 0.9f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        var top = padding
        for (image in images) {
            val scale = min(maxWidth / image.width, slotHeight / image.height)
            val w = image.width * scale
            val h = image.height * scale
            val left = (width - w) / 2
            val y = top + (slotHeight - h) / 2
            canvas.drawBitmap(image, null, RectF(left, y, left + w, y + h), paint)
            image.recycle()
            top += slotHeight + gap
        }
        return true
    }
}

/** Cómo se colocan los logos sobre el video. */
enum class OverlayLayout {
    /** marco.png arriba a la izquierda + sticker #etu2026 abajo a la derecha. */
    CLASSIC,

    /** hashtag.png arriba y marco.png abajo en el primer 1/9 de la pantalla + #etu2026 blanco abajo a la derecha. */
    DOUBLE_TOP,
}
