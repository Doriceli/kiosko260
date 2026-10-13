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
import kotlinx.coroutines.withContext
import java.io.File

/** El video cuyo QR se muestra en pantalla. */
data class SharedVideo(val id: String, val url: String?, val thumbnail: String?, val number: Int?, val label: String)

/** Estado que muestra la pantalla. */
data class KioskState(
    val status: String = "Esperando videos por Quick Share…",
    val progress: Int? = null,
    val pending: Int = 0,
    val current: SharedVideo? = null,
    val todayCount: Int = 0,
    /** Cambia cada vez que se termina un video, para refrescar la pestaña de procesados. */
    val libraryVersion: Int = 0,
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

    lateinit var library: VideoLibrary
        private set
    lateinit var customization: Customization
        private set
    private lateinit var counter: DailyCounter
    private lateinit var processor: VideoProcessor
    private var server: VideoServer? = null

    /** null = canción aleatoria. */
    @Volatile var selectedTrack: MusicTrack? = null
    @Volatile var speedEffect: SpeedEffect = Config.DEFAULT_SPEED_EFFECT

    /** Diseño de logos; se guarda para que siga igual al reabrir la app. */
    var overlayLayout: OverlayLayout
        get() = OverlayLayout.entries.getOrNull(prefs.getInt(KEY_LAYOUT, 0)) ?: OverlayLayout.CLASSIC
        set(value) = prefs.edit().putInt(KEY_LAYOUT, value.ordinal).apply()
    private val prefs by lazy { getSharedPreferences("ajustes", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        library = VideoLibrary(this)
        customization = Customization(this)
        counter = DailyCounter(this)
        processor = VideoProcessor(this)
        refreshCounter()
        startServer()
        scope.launch { processQueue() }
    }

    fun enqueue(uri: Uri) {
        _state.update { it.copy(pending = it.pending + 1) }
        queue.trySend(uri)
    }

    /** Vuelve a leer el contador (a medianoche pasa a 0). */
    fun refreshCounter() {
        _state.update { it.copy(todayCount = counter.today()) }
    }

    fun startServer() {
        if (server?.isAlive == true) return
        server = VideoServer(library, Config.SERVER_PORT).also {
            runCatching { it.start() }.onFailure { e -> Log.e(TAG, "No se pudo iniciar el servidor", e) }
        }
    }

    fun shareUrl(id: String): String? =
        NetworkUtils.localIpAddress()?.let { "http://$it:${Config.SERVER_PORT}/v/$id" }

    /** Vuelve a mostrar el QR de un video de la pestaña "Procesados". */
    fun showExisting(video: ProcessedVideo) {
        scope.launch {
            val thumbnail = withContext(Dispatchers.IO) { thumbnailFor(video.id) }
            val url = shareUrl(video.id)
            _state.update {
                it.copy(
                    status = if (url != null) "Escanea el QR para descargar este video"
                    else "El kiosko no está conectado a Wi-Fi/hotspot",
                    current = SharedVideo(video.id, url, thumbnail?.absolutePath, video.number, labelFor(video)),
                )
            }
        }
    }

    /** Miniatura del video; si no existe (p. ej. tras reinstalar la app) se vuelve a generar. */
    fun thumbnailFor(id: String): File? {
        val thumbnail = library.thumbnailFile(id)
        if (thumbnail.isFile) return thumbnail
        val video = library.videoFile(id).takeIf { it.isFile } ?: return null
        return processor.createThumbnail(video, thumbnail)
    }

    fun labelFor(video: ProcessedVideo): String {
        val time = video.createdAt?.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")) ?: ""
        return listOfNotNull(video.number?.let { "Video #$it" }, time.ifEmpty { null }).joinToString(" · ")
    }

    private suspend fun processQueue() {
        for (uri in queue) {
            val id = library.newId()
            val track = selectedTrack ?: withContext(Dispatchers.IO) { customization.musicTracks() }.randomOrNull()
            val effect = speedEffect
            val layout = overlayLayout
            _state.update {
                it.copy(status = "Editando video" + (track?.let { t -> " con «${t.title}»" } ?: "") + "…", progress = 0)
            }
            try {
                val exported = processor.process(uri, track, effect, layout, File(cacheDir, "$id.mp4")) { p ->
                    _state.update { it.copy(progress = p) }
                }
                val number = counter.increment()
                val saved = withContext(Dispatchers.IO) {
                    library.save(id, exported, number)
                    processor.createThumbnail(library.videoFile(id), library.thumbnailFile(id))
                    library.find(id)
                } ?: error("No se pudo guardar el video en Download/${Config.PROCESADOS_FOLDER}")
                val url = shareUrl(id)
                _state.update {
                    it.copy(
                        status = if (url != null) "¡Listo! Escanea el QR para descargar tu video"
                        else "Video listo, pero el kiosko no está conectado a Wi-Fi/hotspot",
                        progress = null,
                        current = SharedVideo(id, url, library.thumbnailFile(id).absolutePath, number, labelFor(saved)),
                        todayCount = number,
                        libraryVersion = it.libraryVersion + 1,
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

    private companion object {
        const val TAG = "KioskoApp"
        const val KEY_LAYOUT = "diseno"
    }
}
