package com.kiosko260.videoqr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.annotation.OptIn
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Edita un video con Media3 Transformer:
 *  1. cámara lenta ([Config.SLOW_MOTION_SPEED]),
 *  2. marco (marco.png estirado a todo el cuadro) + logo arriba a la izquierda,
 *  3. reemplaza el audio por una canción que se repite hasta que termina el video.
 */
@OptIn(UnstableApi::class)
class VideoProcessor(private val context: Context) {

    private val customization = Customization(context)

    suspend fun process(
        input: Uri,
        music: MusicTrack?,
        output: File,
        onProgress: (Int) -> Unit,
    ): File {
        val (width, height) = withContext(Dispatchers.IO) { outputSize(input) }
        val overlay = withContext(Dispatchers.Default) { buildOverlay(width, height) }

        val videoEffects = mutableListOf<Effect>(
            SpeedChangeEffect(Config.SLOW_MOTION_SPEED),
            Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT),
        )
        if (overlay != null) {
            videoEffects += OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(overlay)))
        }

        val video = EditedMediaItem.Builder(MediaItem.fromUri(input))
            // El audio original en cámara lenta suena mal: se sustituye por la canción.
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val sequences = mutableListOf(EditedMediaItemSequence(video))
        if (music != null) {
            val musicItem = MediaItem.Builder()
                .setUri(music.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(music.startMs).build()
                )
                .build()
            val song = EditedMediaItem.Builder(musicItem).setRemoveVideo(true).build()
            // isLooping = true: la canción se repite/corta para durar exactamente lo que dura el video.
            sequences += EditedMediaItemSequence(listOf(song), /* isLooping= */ true)
        }
        val composition = Composition.Builder(sequences).build()

        output.parentFile?.mkdirs()
        return withContext(Dispatchers.Main) { export(composition, output, onProgress) }
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

    /** Tamaño final en orientación de pantalla (respetando la rotación del video), con números pares. */
    private fun outputSize(input: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, input)
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) w = h.also { h = w }

            val scale = min(1f, Config.MAX_SHORT_SIDE_PX.toFloat() / min(w, h))
            return even(w * scale) to even(h * scale)
        } finally {
            retriever.release()
        }
    }

    private fun even(value: Float): Int = (value / 2f).roundToInt() * 2

    /** Une marco y logo en una sola imagen transparente del mismo tamaño que el video. */
    private fun buildOverlay(width: Int, height: Int): Bitmap? {
        val frame = customization.loadBitmap(Config.FRAME_FILE)
        val logo = customization.loadBitmap(Config.LOGO_FILE)
        if (frame == null && logo == null) return null

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        frame?.let {
            canvas.drawBitmap(it, null, Rect(0, 0, width, height), paint)
            it.recycle()
        }
        logo?.let {
            val margin = (width * Config.LOGO_MARGIN_FRACTION).roundToInt()
            val logoWidth = (width * Config.LOGO_WIDTH_FRACTION).roundToInt()
            val logoHeight = (logoWidth.toFloat() * it.height / it.width).roundToInt()
            canvas.drawBitmap(it, null, Rect(margin, margin, margin + logoWidth, margin + logoHeight), paint)
            it.recycle()
        }
        return result
    }
}
