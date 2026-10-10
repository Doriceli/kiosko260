package com.kiosko260.videoqr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
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
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
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
 * Edita un video con Media3 Transformer:
 *  1. cámara lenta ([Config.SLOW_MOTION_SPEED]),
 *  2. marco azul/dorado con [Config.HASHTAG] + logo arriba a la izquierda,
 *  3. reemplaza el audio por una canción recortada a la duración exacta del video final.
 */
@OptIn(UnstableApi::class)
class VideoProcessor(private val context: Context) {

    private val customization = Customization(context)

    private data class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

    suspend fun process(
        input: Uri,
        music: MusicTrack?,
        output: File,
        onProgress: (Int) -> Unit,
    ): File {
        val info = withContext(Dispatchers.IO) { probe(input) }
        val overlay = withContext(Dispatchers.Default) { buildOverlay(info.width, info.height) }

        val videoEffects = mutableListOf<Effect>(
            SpeedChangeEffect(Config.SLOW_MOTION_SPEED),
            Presentation.createForWidthAndHeight(info.width, info.height, Presentation.LAYOUT_SCALE_TO_FIT),
            OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(overlay))),
        )

        val video = EditedMediaItem.Builder(MediaItem.fromUri(input))
            // El audio original en cámara lenta suena mal: se sustituye por la canción.
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val sequences = mutableListOf(EditedMediaItemSequence(video))
        if (music != null) {
            val finalDurationMs = (info.durationMs / Config.SLOW_MOTION_SPEED).toLong()
            val songItems = withContext(Dispatchers.IO) { musicItems(music, finalDurationMs) }
            if (songItems.isNotEmpty()) sequences += EditedMediaItemSequence(songItems)
        }
        val composition = Composition.Builder(sequences).build()

        output.parentFile?.mkdirs()
        return withContext(Dispatchers.Main) { export(composition, output, onProgress) }
    }

    /**
     * Trozos de la canción que suman exactamente [targetMs]. Si la canción es más corta que el
     * video, se repite desde el principio. (No se usa una secuencia en bucle porque Media3 calcula
     * su fin con la duración original del video, sin la cámara lenta, y la música se cortaba.)
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
            val frame = retriever.getFrameAtTime(durationUs / 3, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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

    /** Tamaño final en orientación de pantalla (respetando la rotación del video), con números pares. */
    private fun probe(input: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, input)
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) w = h.also { h = w }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val scale = min(1f, Config.MAX_SHORT_SIDE_PX.toFloat() / min(w, h))
            return VideoInfo(even(w * scale), even(h * scale), durationMs)
        } finally {
            retriever.release()
        }
    }

    private fun even(value: Float): Int = (value / 2f).roundToInt() * 2

    /** Marco azul/dorado con el hashtag + logo, en una sola imagen transparente del tamaño del video. */
    private fun buildOverlay(width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val unit = min(width, height) / 100f // 1 % del lado corto

        // Borde exterior azul.
        val border = 3.5f * unit
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = border
            color = Config.AZUL
            canvas.drawRect(border / 2, border / 2, width - border / 2, height - border / 2, this)
        }
        // Filete dorado (degradado dorado → amarillo oro → dorado).
        val gold = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(Config.DORADO, Config.AMARILLO_ORO, Config.DORADO), null, Shader.TileMode.CLAMP,
        )
        val goldLine = 0.9f * unit
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = goldLine
            shader = gold
            val inset = border + goldLine / 2
            canvas.drawRect(inset, inset, width - inset, height - inset, this)
        }

        // Franja inferior azul con el hashtag en dorado.
        val bandHeight = 11f * unit
        val bandTop = height - border - bandHeight
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, bandTop, 0f, height.toFloat(),
                Config.AZUL_CLARO, Config.AZUL, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(RectF(border, bandTop, width - border, height - border), this)
        }
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = gold
            canvas.drawRect(border, bandTop - goldLine, width - border, bandTop, this)
        }
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = gold
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 6.5f * unit
            textAlign = Paint.Align.CENTER
            setShadowLayer(0.6f * unit, 0f, 0.3f * unit, Config.AZUL)
            val baseline = bandTop + bandHeight / 2 - (descent() + ascent()) / 2
            canvas.drawText(Config.HASHTAG, width / 2f, baseline, this)
        }

        // Logo (marco.png de INSUMOS) arriba a la izquierda.
        customization.loadLogo()?.let { logo ->
            val margin = (width * Config.LOGO_MARGIN_FRACTION).roundToInt()
            val logoWidth = (width * Config.LOGO_WIDTH_FRACTION).roundToInt()
            val logoHeight = (logoWidth.toFloat() * logo.height / logo.width).roundToInt()
            canvas.drawBitmap(
                logo, null, Rect(margin, margin, margin + logoWidth, margin + logoHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            logo.recycle()
        }
        return result
    }
}
