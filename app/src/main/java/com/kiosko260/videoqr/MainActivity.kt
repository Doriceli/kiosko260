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
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import com.kiosko260.videoqr.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as KioskoApp
    private var watcher: QuickShareWatcher? = null
    private var shownVideoId: String? = null
    private var shownLibraryVersion = -1
    private var tracks: List<MusicTrack> = emptyList()
    private lateinit var historyAdapter: HistoryAdapter

    /** Videos (Quick Share y PROCESADOS) + audio e imágenes (canciones y logo de Download/INSUMOS). */
    private val mediaPermissions =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) else if (Build.VERSION.SDK_INT == 29) arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            setupMusicSpinner() // ya se pueden leer las canciones de INSUMOS
            refreshHistory()
            if (hasPermission(mediaPermissions.first())) startWatcher() else binding.autoSwitch.isChecked = false
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

        setupTabs()
        setupHistory()

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
        setupSpeedSpinner()
        setupMusicSpinner()
        binding.folderInfo.text = getString(R.string.folders_info, Config.INSUMOS_FOLDER, Config.PROCESADOS_FOLDER)

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
        setupMusicSpinner() // por si se copiaron canciones nuevas
        val ip = NetworkUtils.localIpAddress()
        binding.networkInfo.text =
            if (ip != null) getString(R.string.network_ok, ip, Config.SERVER_PORT)
            else getString(R.string.network_missing)
    }

    override fun onDestroy() {
        stopWatcher()
        super.onDestroy()
    }

    private fun setupTabs() {
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_live))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_history))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val history = tab.position == 1
                binding.livePanel.visibility = if (history) View.GONE else View.VISIBLE
                binding.historyPanel.visibility = if (history) View.VISIBLE else View.GONE
                if (history) refreshHistory()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = onTabSelected(tab)
        })
    }

    private fun setupHistory() {
        historyAdapter = HistoryAdapter(lifecycleScope, app::thumbnailFor, app::labelFor) { video ->
            app.showExisting(video)
            binding.tabs.getTabAt(0)?.select()
        }
        binding.historyList.layoutManager = GridLayoutManager(this, 3)
        binding.historyList.adapter = historyAdapter
    }

    private fun refreshHistory() {
        lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) { app.library.list() }
            historyAdapter.submit(videos)
            binding.historyEmpty.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
            binding.historyCount.text = getString(R.string.history_count, videos.size)
        }
    }

    private fun render(state: KioskState) {
        binding.status.text = if (state.pending > 1) {
            "${state.status}  (${state.pending - 1} en cola)"
        } else state.status
        binding.counter.text = getString(R.string.counter_today, state.todayCount)

        binding.progress.visibility = if (state.progress != null) View.VISIBLE else View.GONE
        binding.progress.progress = state.progress ?: 0

        val current = state.current
        if (current != null && current.id + current.url != shownVideoId) {
            shownVideoId = current.id + current.url
            binding.preview.setImageBitmap(current.thumbnail?.let(BitmapFactory::decodeFile))
            binding.previewLabel.text = current.label
            if (current.url != null) {
                binding.qr.setImageBitmap(QrCode.create(current.url))
                binding.url.text = current.url
            } else {
                binding.qr.setImageDrawable(null)
                binding.url.setText(R.string.network_missing)
            }
            binding.resultRow.visibility = View.VISIBLE
        }

        if (state.libraryVersion != shownLibraryVersion) {
            shownLibraryVersion = state.libraryVersion
            if (binding.historyPanel.visibility == View.VISIBLE) refreshHistory()
        }
    }

    private fun setupSpeedSpinner() {
        val effects = SpeedEffect.entries
        binding.speedSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, effects.map { it.label })
        binding.speedSpinner.setSelection(effects.indexOf(app.speedEffect))
        binding.speedSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                app.speedEffect = effects[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
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
            permissionLauncher.launch(missing.toTypedArray())
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
