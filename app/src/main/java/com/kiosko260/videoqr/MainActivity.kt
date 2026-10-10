package com.kiosko260.videoqr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.kiosko260.videoqr.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as KioskoApp
    private var watcher: QuickShareWatcher? = null
    private var shownUrl: String? = null
    private var tracks: List<MusicTrack> = emptyList()

    /** Videos (Quick Share) + audio e imágenes (canciones y logo de Download/INSUMOS). */
    private val mediaPermissions =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            setupMusicSpinner() // ya se pueden leer las canciones de INSUMOS
            val videoGranted = result[mediaPermissions.first()] == true || hasPermission(mediaPermissions.first())
            if (videoGranted) startWatcher() else binding.autoSwitch.isChecked = false
        }

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(app::enqueue)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.pickButton.setOnClickListener {
            pickVideo.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    .build()
            )
        }
        binding.autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) ensurePermissionAndWatch() else stopWatcher()
        }
        binding.hashtag.text = Config.HASHTAG
        setupMusicSpinner()
        binding.folderInfo.text = getString(R.string.custom_folder_info, app.customization.folder.absolutePath)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.state.collect(::render)
            }
        }

        handleShareIntent(intent)
        ensurePermissionAndWatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        app.startServer()
        app.refreshCounter()
        setupMusicSpinner() // por si se copiaron canciones nuevas por USB
        val ip = NetworkUtils.localIpAddress()
        binding.networkInfo.text =
            if (ip != null) getString(R.string.network_ok, ip, Config.SERVER_PORT)
            else getString(R.string.network_missing)
    }

    override fun onDestroy() {
        stopWatcher()
        super.onDestroy()
    }

    private fun render(state: KioskState) {
        binding.status.text = if (state.pending > 1) {
            "${state.status}  (${state.pending - 1} en cola)"
        } else state.status
        binding.counter.text = getString(R.string.counter_today, state.todayCount)

        binding.progress.visibility = if (state.progress != null) View.VISIBLE else View.GONE
        binding.progress.progress = state.progress ?: 0

        if (state.lastUrl != shownUrl) {
            shownUrl = state.lastUrl
            if (state.lastUrl != null) {
                binding.qr.setImageBitmap(QrCode.create(state.lastUrl))
                binding.url.text = state.lastUrl
                binding.preview.setImageBitmap(state.lastThumbnail?.let(BitmapFactory::decodeFile))
                binding.previewLabel.text = getString(
                    R.string.preview_label, state.lastNumber,
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                )
                binding.resultRow.visibility = View.VISIBLE
            }
        }
    }

    private fun setupMusicSpinner() {
        val newTracks = app.customization.musicTracks()
        if (newTracks == tracks && binding.musicSpinner.adapter != null) return
        tracks = newTracks
        val labels = listOf(getString(R.string.music_random)) + tracks.map { it.title }
        binding.musicSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.musicSpinner.setSelection(tracks.indexOf(app.selectedTrack) + 1)
        binding.musicSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                app.selectedTrack = tracks.getOrNull(position - 1)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.musicWarning.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handleShareIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(app::enqueue)
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.forEach(app::enqueue)
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionAndWatch() {
        val missing = mediaPermissions.filterNot(::hasPermission)
        if (missing.isNotEmpty()) {
            requestPermissions.launch(missing.toTypedArray())
        } else if (binding.autoSwitch.isChecked) {
            startWatcher()
        }
    }

    private fun startWatcher() {
        if (watcher == null) {
            watcher = QuickShareWatcher(this, app::enqueue).also { it.start() }
        }
    }

    private fun stopWatcher() {
        watcher?.stop()
        watcher = null
    }
}
