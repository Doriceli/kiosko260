package com.kiosko260.videoqr

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.util.concurrent.TimeUnit

/** El video cuyo QR se muestra en pantalla. */
data class SharedVideo(
    val id: String,
    val url: String?,
    val thumbnail: String?,
    val number: Int?,
    val label: String,
    val authorized: Boolean = false,
)

/** Estado que muestra la pantalla. */
data class KioskState(
    val status: String = "Esperando videos por Quick Share…",
    val progress: Int? = null,
    val pending: Int = 0,
    val current: SharedVideo? = null,
    val todayCount: Int = 0,
    /** Videos hechos en el evento actual (se reinicia a mano con el botón Reiniciar). */
    val eventCount: Int = 0,
    /** Cambia cada vez que se termina un video, para refrescar la pestaña de procesados. */
    val libraryVersion: Int = 0,
    /** Red Wi-Fi propia del kiosko (sin internet), si está activa. */
    val hotspot: HotspotInfo? = null,
    val hotspotError: String? = null,
    /** Hora (System.currentTimeMillis) en que la red se apagará sola para volver a Quick Share. */
    val hotspotOffAt: Long? = null,
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

    /** Hashtag de la esquina inferior derecha; se edita en pantalla antes de generar el video. */
    var hashtag: String
        get() = prefs.getString(KEY_HASHTAG, null) ?: Config.HASHTAG
        set(value) = prefs.edit().putString(KEY_HASHTAG, value.trim()).apply()

    /** Nombre del evento actual: se agrega al nombre del archivo de cada video. */
    var eventName: String
        get() = prefs.getString(KEY_EVENT_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_EVENT_NAME, value.trim()).apply()

    private var eventCount: Int
        get() = prefs.getInt(KEY_EVENT_COUNT, 0)
        set(value) {
            prefs.edit().putInt(KEY_EVENT_COUNT, value).apply()
            _state.update { it.copy(eventCount = value) }
        }

    /** Reinicio MANUAL del contador del evento (cerrar la app no lo reinicia). */
    fun resetEventCounter() {
        eventCount = 0
    }

    /** Si el kiosko debe crear su propia red Wi-Fi sin internet (se recuerda). */
    var useOwnNetwork: Boolean
        get() = prefs.getBoolean(KEY_OWN_NETWORK, true)
        set(value) = prefs.edit().putBoolean(KEY_OWN_NETWORK, value).apply()

    private lateinit var hotspot: KioskHotspot

    override fun onCreate() {
        super.onCreate()
        library = VideoLibrary(this)
        customization = Customization(this)
        counter = DailyCounter(this)
        processor = VideoProcessor(this)
        hotspot = KioskHotspot(this) { info, error ->
            _state.update { it.copy(hotspot = info, hotspotError = error, hotspotOffAt = it.hotspotOffAt.takeIf { info != null }) }
            // La IP de la red nueva tarda un instante en estar lista: se recalcula el QR.
            scope.launch {
                delay(1_500)
                refreshCurrentUrl()
            }
        }
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
        _state.update { it.copy(todayCount = counter.today(), eventCount = eventCount) }
    }

    fun startServer() {
        if (server?.isAlive == true) return
        server = VideoServer(
            library,
            Config.SERVER_PORT,
            hashtag = { hashtag },
            onDownloaded = { scope.launch { onVideoDownloaded() } },
        ).also {
            runCatching { it.start() }.onFailure { e -> Log.e(TAG, "No se pudo iniciar el servidor", e) }
        }
    }

    /** Enlace de descarga. Se arma con la IP local del kiosko: no se consulta nada en internet. */
    fun shareUrl(id: String): String? =
        NetworkUtils.localIpAddress(preferHotspot = hotspot.isActive)?.let { "http://$it:${Config.SERVER_PORT}/v/$id" }

    /** Crea la red Wi-Fi propia (MainActivity ya pidió los permisos). */
    fun startOwnNetwork() = hotspot.start()

    fun stopOwnNetwork() = hotspot.stop()

    private var autoOffJob: Job? = null

    /**
     * MODO COMPARTIR: enciende la red del kiosko para que los invitados escaneen el QR.
     * Con [autoOff] se apaga sola pasado el tiempo de [Config.SHARE_WINDOW_MINUTES].
     */
    fun shareMode(autoOff: Boolean = true) {
        if (!useOwnNetwork) return
        startOwnNetwork()
        if (autoOff) scheduleAutoOff(TimeUnit.MINUTES.toMillis(Config.SHARE_WINDOW_MINUTES)) else cancelAutoOff()
    }

    /** MODO RECIBIR: apaga la red del kiosko para que Quick Share pueda usar el Wi-Fi. */
    fun receiveMode() {
        cancelAutoOff()
        stopOwnNetwork()
    }

    private fun scheduleAutoOff(delayMs: Long) {
        autoOffJob?.cancel()
        _state.update { it.copy(hotspotOffAt = System.currentTimeMillis() + delayMs) }
        autoOffJob = scope.launch {
            delay(delayMs)
            // No apagar si se está editando otro video (su QR se mostrará al terminar).
            if (_state.value.progress == null) receiveMode()
        }
    }

    private fun cancelAutoOff() {
        autoOffJob?.cancel()
        autoOffJob = null
        _state.update { it.copy(hotspotOffAt = null) }
    }

    /**
     * Llegó una solicitud de Quick Share (detectada por la notificación): se apaga la red al instante
     * para que la transferencia use el Wi-Fi. Al terminar, la edición del video la vuelve a encender.
     */
    fun onQuickShareIncoming() {
        scope.launch {
            if (hotspot.isActive) {
                receiveMode()
                _state.update { it.copy(status = "📥 Recibiendo video por Quick Share…") }
            }
        }
    }

    /** El invitado ya descargó su video: en unos segundos se vuelve al modo Quick Share. */
    private fun onVideoDownloaded() {
        if (hotspot.isActive && _state.value.progress == null) {
            scheduleAutoOff(TimeUnit.SECONDS.toMillis(Config.AFTER_DOWNLOAD_SECONDS))
        }
    }

    /** Recalcula el QR del video en pantalla (p. ej. al cambiar de red). */
    fun refreshCurrentUrl() {
        _state.update { state ->
            val current = state.current ?: return@update state
            state.copy(current = current.copy(url = shareUrl(current.id)))
        }
    }

    /** Casilla "Autorizo compartir mi video en redes sociales" del video en pantalla. */
    fun setAuthorized(authorized: Boolean) {
        val current = _state.value.current ?: return
        if (current.authorized == authorized) return
        _state.update { it.copy(current = current.copy(authorized = authorized)) }
        scope.launch {
            withContext(Dispatchers.IO) { library.setAuthorized(current.id, authorized) }
            _state.update { it.copy(libraryVersion = it.libraryVersion + 1) }
        }
    }

    /** Vuelve a mostrar el QR de un video de la pestaña "Procesados". */
    fun showExisting(video: ProcessedVideo) {
        shareMode()
        scope.launch {
            val thumbnail = withContext(Dispatchers.IO) { thumbnailFor(video.id) }
            val url = shareUrl(video.id)
            _state.update {
                it.copy(
                    status = if (url != null) "Escanea el QR para descargar este video"
                    else "Activa la red del kiosko para mostrar el QR",
                    current = SharedVideo(
                        video.id, url, thumbnail?.absolutePath, video.number, labelFor(video), video.authorized,
                    ),
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
        val title = when {
            video.event != null -> "${video.event} #${video.eventNumber}"
            video.number != null -> "Video #${video.number}"
            else -> null
        }
        return listOfNotNull(
            title,
            time.ifEmpty { null },
            "✅".takeIf { video.authorized },
        ).joinToString(" · ")
    }

    private suspend fun processQueue() {
        for (uri in queue) {
            val eventNumber = eventCount + 1
            val id = library.newId(eventName, eventNumber)
            val track = selectedTrack ?: withContext(Dispatchers.IO) { customization.musicTracks() }.randomOrNull()
            val effect = speedEffect
            val layout = overlayLayout
            val tag = hashtag
            _state.update {
                it.copy(status = "Editando video" + (track?.let { t -> " con «${t.title}»" } ?: "") + "…", progress = 0)
            }
            // El video ya llegó por Quick Share: se enciende la red para tener el QR listo al terminar.
            shareMode(autoOff = false)
            try {
                val exported = processor.process(uri, track, effect, layout, tag, File(cacheDir, "$id.mp4")) { p ->
                    _state.update { it.copy(progress = p) }
                }
                val number = counter.increment()
                eventCount = eventNumber
                val saved = withContext(Dispatchers.IO) {
                    library.save(id, exported, number)
                    processor.createThumbnail(library.videoFile(id), library.thumbnailFile(id))
                    library.find(id)
                } ?: error("No se pudo guardar el video en Download/${Config.PROCESADOS_FOLDER}")
                val url = shareUrl(id)
                _state.update {
                    it.copy(
                        status = if (url != null) "¡Listo! Escanea el QR para descargar tu video"
                        else "Video listo: activa la red del kiosko para mostrar el QR",
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
                // Con más videos en cola la red sigue encendida; si no, empieza la cuenta para apagarla.
                if (_state.value.pending == 0 && hotspot.isActive) {
                    scheduleAutoOff(TimeUnit.MINUTES.toMillis(Config.SHARE_WINDOW_MINUTES))
                }
            }
        }
    }

    private companion object {
        const val TAG = "KioskoApp"
        const val KEY_LAYOUT = "diseno"
        const val KEY_HASHTAG = "hashtag"
        const val KEY_EVENT_NAME = "evento_nombre"
        const val KEY_EVENT_COUNT = "evento_contador"
        const val KEY_OWN_NETWORK = "red_propia"
    }
}
