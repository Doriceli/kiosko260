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
 *  2. formato vertical 9:16 a 1080×1920 (recorta al centro si el video es horizontal),
 *  3. retoque de contraste/color,
 *  4. marco con degradado + sticker [Config.HASHTAG] + logo arriba a la izquierda,
 *  5. reemplaza el audio por una canción recortada a la duración exacta del video final.
 */
@OptIn(UnstableApi::class)
class VideoProcessor(private val context: Context) {

    private val customization = Customization(context)

    suspend fun process(
        input: Uri,
        music: MusicTrack?,
        speedEffect: SpeedEffect,
        output: File,
        onProgress: (Int) -> Unit,
    ): File {
        val inputDurationUs = withContext(Dispatchers.IO) { durationUs(input) }
        val overlay = withContext(Dispatchers.Default) { buildOverlay(Config.OUTPUT_WIDTH, Config.OUTPUT_HEIGHT) }
        val speed = speedEffect.toSpeedProvider(inputDurationUs)

        val videoEffects = listOf<Effect>(
            SpeedChangeEffect(speed),
            Presentation.createForWidthAndHeight(
                Config.OUTPUT_WIDTH, Config.OUTPUT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
            ),
            Contrast(Config.CONTRAST),
            HslAdjustment.Builder().adjustSaturation(Config.SATURATION).build(),
            OverlayEffect(listOf<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(overlay))),
        )

        val video = EditedMediaItem.Builder(MediaItem.fromUri(input))
            // El audio original con cambios de velocidad suena mal: se sustituye por la canción.
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val sequences = mutableListOf(EditedMediaItemSequence.Builder(listOf(video)).build())
        if (music != null) {
            val finalDurationMs = speed.outputDurationUs(inputDurationUs) / 1000
            val songItems = withContext(Dispatchers.IO) { musicItems(music, finalDurationMs) }
            if (songItems.isNotEmpty()) sequences += EditedMediaItemSequence.Builder(songItems).build()
        }
        val composition = Composition.Builder(sequences).build()

        output.parentFile?.mkdirs()
        return withContext(Dispatchers.Main) { export(composition, output, onProgress) }
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

    private suspend fun export(composition: Composition, output: File, onProgress: (Int) -> Unit): File =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val progressHolder = ProgressHolder()
            lateinit var poll: Runnable

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(Config.VIDEO_BITRATE).build()
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

    private fun durationUs(input: Uri): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, input)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return ms * 1000
        } finally {
            retriever.release()
        }
    }

    private fun even(value: Float): Int = (value / 2f).roundToInt() * 2

    /**
     * Marco con degradado (amarillo → naranja → rojo → rosa), sticker con el hashtag y logo.
     * El logo queda debajo de las pestañas de TikTok/Reels; el sticker, pequeño en la esquina
     * inferior derecha ([Config.HASHTAG_SIZE]) para no tapar el video.
     */
    private fun buildOverlay(width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val unit = width / 100f // 1 % del ancho

        val palette = intArrayOf(Config.AMARILLO, Config.NARANJA, Config.NARANJA_FUEGO, Config.ROJO, Config.ROSA)

        // Borde con degradado diagonal y esquinas interiores redondeadas.
        val border = 2.2f * unit
        val radius = 5f * unit
        val frame = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRoundRect(RectF(border, border, width - border, height - border), radius, radius, Path.Direction.CW)
        }
        canvas.drawPath(frame, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), palette, null, Shader.TileMode.CLAMP)
        })

        // Sticker pequeño con el hashtag en la esquina inferior derecha, apenas girado y semitransparente.
        val size = Config.HASHTAG_SIZE * unit
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = size
            textAlign = Paint.Align.CENTER
            setShadowLayer(0.3f * unit, 0f, 0.2f * unit, Config.CORAL)
        }
        val textWidth = textPaint.measureText(Config.HASHTAG)
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
                intArrayOf(Config.AMARILLO, Config.NARANJA, Config.ROJO, Config.ROSA), null, Shader.TileMode.CLAMP,
            )
            setShadowLayer(0.8f * unit, 0f, 0.4f * unit, Color.argb(90, 0, 0, 0))
        })
        canvas.drawRoundRect(pill, pillHeight / 2, pillHeight / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.4f * unit
            color = Color.WHITE
        })
        val baseline = centerY - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(Config.HASHTAG, centerX, baseline, textPaint)
        canvas.restore()

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
}
