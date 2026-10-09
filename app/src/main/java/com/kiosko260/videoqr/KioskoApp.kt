package com.kiosko260.videoqr

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/** Estado que muestra la pantalla. */
data class KioskState(
    val status: String = "Esperando videos por Quick Share…",
    val progress: Int? = null,
    val pending: Int = 0,
    val lastUrl: String? = null,
)

/**
 * Mantiene vivos el servidor y la cola de edición aunque la pantalla se gire o se recree.
 * Los videos se procesan de uno en uno, en orden de llegada.
 */
@OptIn(UnstableApi::class)
class KioskoApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = Channel<Uri>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(KioskState())
    val state: StateFlow<KioskState> = _state.asStateFlow()

    lateinit var outputDir: File
        private set
    lateinit var customization: Customization
        private set
    private var server: VideoServer? = null

    /** null = canción aleatoria. */
    @Volatile var selectedTrack: MusicTrack? = null

    override fun onCreate() {
        super.onCreate()
        outputDir = File(getExternalFilesDir(null), "editados").apply { mkdirs() }
        customization = Customization(this)
        deleteOldVideos()
        startServer()
        scope.launch { processQueue() }
    }

    fun enqueue(uri: Uri) {
        _state.update { it.copy(pending = it.pending + 1) }
        queue.trySend(uri)
    }

    fun startServer() {
        if (server?.isAlive == true) return
        server = VideoServer(outputDir, Config.SERVER_PORT).also {
            runCatching { it.start() }.onFailure { e -> Log.e(TAG, "No se pudo iniciar el servidor", e) }
        }
    }

    fun shareUrl(id: String): String? =
        NetworkUtils.localIpAddress()?.let { "http://$it:${Config.SERVER_PORT}/v/$id" }

    private suspend fun processQueue() {
        val processor = VideoProcessor(this)
        for (uri in queue) {
            val id = newId()
            val track = selectedTrack ?: customization.musicTracks().randomOrNull()
            _state.update {
                it.copy(status = "Editando video" + (track?.let { t -> " con «${t.title}»" } ?: "") + "…", progress = 0)
            }
            try {
                processor.process(uri, track, File(outputDir, "$id.mp4")) { p ->
                    _state.update { it.copy(progress = p) }
                }
                val url = shareUrl(id)
                _state.update {
                    it.copy(
                        status = if (url != null) "¡Listo! Escanea el QR para descargar tu video"
                        else "Video listo, pero el kiosko no está conectado a Wi-Fi/hotspot",
                        progress = null,
                        lastUrl = url ?: it.lastUrl,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error editando $uri", e)
                _state.update { it.copy(status = "Error al editar el video: ${e.message}", progress = null) }
            } finally {
                _state.update { it.copy(pending = (it.pending - 1).coerceAtLeast(0)) }
            }
        }
    }

    private fun newId(): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..10).map { chars.random() }.joinToString("")
    }

    private fun deleteOldVideos() {
        val limit = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(Config.KEEP_OUTPUT_HOURS)
        outputDir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "KioskoApp"
    }
}
