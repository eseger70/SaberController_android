package com.eseger70.sabercontroller

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eseger70.sabercontroller.ble.SaberBleManager
import com.eseger70.sabercontroller.ble.SaberBleManager.ConnectionState
import com.eseger70.sabercontroller.ble.SaberCommandResponseParser
import com.eseger70.sabercontroller.databinding.ActivityMainBinding
import com.eseger70.sabercontroller.databinding.PageLogBinding
import com.eseger70.sabercontroller.databinding.PageSaberBinding
import com.eseger70.sabercontroller.databinding.PageTracksBinding
import com.eseger70.sabercontroller.ui.MainPagerAdapter
import com.eseger70.sabercontroller.ui.SectionedListAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: SaberBleManager
    private lateinit var pagerAdapter: MainPagerAdapter

    private val logLines = ArrayDeque<String>()
    private var lastFrameLine: String? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var currentConnectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var suppressVolumeCallbacks = false

    private var saberPageBinding: PageSaberBinding? = null
    private var tracksPageBinding: PageTracksBinding? = null
    private var logPageBinding: PageLogBinding? = null

    private val presetAdapter by lazy {
        SectionedListAdapter<SaberCommandResponseParser.PresetRow>(
            context = this,
            labelProvider = { row -> row.label },
            headerProvider = { row -> row is SaberCommandResponseParser.PresetRow.Header },
            enabledProvider = { row -> row is SaberCommandResponseParser.PresetRow.Preset }
        )
    }
    private val trackAdapter by lazy {
        SectionedListAdapter<SaberCommandResponseParser.TrackRow>(
            context = this,
            labelProvider = { row ->
                when (row) {
                    is SaberCommandResponseParser.TrackRow.Header -> row.title
                    is SaberCommandResponseParser.TrackRow.Track -> row.displayName
                }
            },
            headerProvider = { row -> row is SaberCommandResponseParser.TrackRow.Header },
            enabledProvider = { row -> row is SaberCommandResponseParser.TrackRow.Track }
        )
    }

    private var bladeState: Boolean? = null
    private var saberStatus: String = ""
    private var trackStatus: String = ""
    private var nowPlaying: String? = null
    private var currentPresetIndex: Int? = null
    private var currentVolume: Int? = null

    private var presetEntries: List<SaberCommandResponseParser.PresetEntry> = emptyList()
    private var presetRows: List<SaberCommandResponseParser.PresetRow> = emptyList()
    private var trackPaths: List<String> = emptyList()
    private var trackRows: List<SaberCommandResponseParser.TrackRow> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants
            .filterValues { granted -> !granted }
            .keys
            .toList()
        if (denied.isEmpty()) {
            pendingPermissionAction?.invoke()
        } else {
            appendLog("Permissions denied: ${denied.joinToString()}")
        }
        pendingPermissionAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = SaberBleManager(applicationContext)
        applySystemInsets()
        setupPager()
        wireCommonUi()
        collectBleState()
        renderAll()
        appendLog("Ready. Connect to the paired FEASYCOM saber or scan fallback")
    }

    override fun onDestroy() {
        bleManager.close()
        super.onDestroy()
    }

    private fun setupPager() {
        pagerAdapter = MainPagerAdapter(
            onSaberPageBound = { pageBinding -> bindSaberPage(pageBinding) },
            onTracksPageBound = { pageBinding -> bindTracksPage(pageBinding) },
            onLogPageBound = { pageBinding -> bindLogPage(pageBinding) }
        )
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 3

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_saber)
                1 -> getString(R.string.tab_tracks)
                else -> getString(R.string.tab_log)
            }
        }.attach()

        applyTabStyles()
    }

    private fun wireCommonUi() {
        binding.buttonConnect.setOnClickListener {
            runWithBlePermissions {
                bleManager.connectToTarget()
            }
        }

        binding.buttonDisconnect.setOnClickListener {
            runWithBlePermissions {
                bleManager.disconnect()
            }
        }
    }

    private fun applySystemInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val initialLeft = binding.root.paddingLeft
        val initialTop = binding.root.paddingTop
        val initialRight = binding.root.paddingRight
        val initialBottom = binding.root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun applyTabStyles() {
        val titles = listOf(
            R.string.tab_saber,
            R.string.tab_tracks,
            R.string.tab_log
        )

        titles.forEachIndexed { index, titleRes ->
            binding.tabLayout.getTabAt(index)?.customView = createTabLabel(getString(titleRes))
        }

        updateTabStyles()
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = updateTabStyles()

            override fun onTabUnselected(tab: TabLayout.Tab) = updateTabStyles()

            override fun onTabReselected(tab: TabLayout.Tab) = updateTabStyles()
        })
    }

    private fun createTabLabel(title: String): TextView {
        return TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            minWidth = dp(84)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun updateTabStyles() {
        for (index in 0 until binding.tabLayout.tabCount) {
            val label = binding.tabLayout.getTabAt(index)?.customView as? TextView ?: continue
            val selected = index == binding.tabLayout.selectedTabPosition
            label.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.app_text_primary else R.color.app_header_text
                )
            )
            label.background = if (selected) {
                AppCompatResources.getDrawable(this, R.drawable.bg_tab_selected)
            } else {
                null
            }
            label.alpha = if (selected) 1.0f else 0.92f
        }
    }

    private fun bindSaberPage(pageBinding: PageSaberBinding) {
        saberPageBinding = pageBinding
        if (pageBinding.listPresets.adapter !== presetAdapter) {
            pageBinding.listPresets.adapter = presetAdapter
        }

        pageBinding.buttonOn.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { setBladePowerInternal(isOn = true) }
            }
        }
        pageBinding.buttonOff.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { setBladePowerInternal(isOn = false) }
            }
        }
        pageBinding.buttonSyncSaber.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { syncSaberPageInternal() }
            }
        }
        pageBinding.buttonRefreshVolume.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { refreshVolumeInternal() }
            }
        }
        pageBinding.listPresets.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val row = presetRows.getOrNull(position)
            if (row is SaberCommandResponseParser.PresetRow.Preset) {
                runWithBlePermissions {
                    launchBleTask { selectPresetInternal(row.entry) }
                }
            }
        }
        pageBinding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressVolumeCallbacks) return
                pageBinding.textVolumeValue.text = progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (suppressVolumeCallbacks) return
                val targetVolume = seekBar?.progress ?: return
                runWithBlePermissions {
                    launchBleTask { setVolumeInternal(targetVolume) }
                }
            }
        })

        renderSaberPage(pageBinding)
    }

    private fun bindTracksPage(pageBinding: PageTracksBinding) {
        tracksPageBinding = pageBinding
        if (pageBinding.listTracks.adapter !== trackAdapter) {
            pageBinding.listTracks.adapter = trackAdapter
        }

        pageBinding.buttonRefreshTracks.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { refreshTrackListInternal() }
            }
        }
        pageBinding.buttonRefreshNowPlaying.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { refreshNowPlayingInternal() }
            }
        }
        pageBinding.buttonStopTrack.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { stopTrackInternal() }
            }
        }
        pageBinding.buttonRefreshVolume.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { refreshVolumeInternal() }
            }
        }
        pageBinding.listTracks.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val row = trackRows.getOrNull(position)
            if (row is SaberCommandResponseParser.TrackRow.Track) {
                runWithBlePermissions {
                    launchBleTask { playTrackInternal(row.path) }
                }
            }
        }
        pageBinding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressVolumeCallbacks) return
                pageBinding.textVolumeValue.text = progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (suppressVolumeCallbacks) return
                val targetVolume = seekBar?.progress ?: return
                runWithBlePermissions {
                    launchBleTask { setVolumeInternal(targetVolume) }
                }
            }
        })

        renderTracksPage(pageBinding)
    }

    private fun bindLogPage(pageBinding: PageLogBinding) {
        logPageBinding = pageBinding

        pageBinding.buttonCopyLog.setOnClickListener {
            copyTextToClipboard(buildLogText(), getString(R.string.log_copied))
        }

        pageBinding.buttonCopyLastFrame.setOnClickListener {
            val frame = lastFrameLine
            if (frame.isNullOrBlank()) {
                showToast(getString(R.string.no_frame_captured))
            } else {
                copyTextToClipboard(frame, getString(R.string.last_frame_copied))
            }
        }

        renderLogPage(pageBinding)
    }

    private fun collectBleState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    bleManager.connectionState.collect { state ->
                        val previous = currentConnectionState
                        currentConnectionState = state
                        binding.textConnectionStateValue.text = state.name
                        binding.buttonConnect.isEnabled = state == ConnectionState.DISCONNECTED
                        binding.buttonDisconnect.isEnabled = state != ConnectionState.DISCONNECTED
                        renderAll()

                        if (state == ConnectionState.READY && previous != ConnectionState.READY) {
                            saberStatus = "Connected. Syncing saber data..."
                            trackStatus = "Connected. Syncing track data..."
                            renderAll()
                            launchBleTask { syncAllDataInternal() }
                        }

                        if (state == ConnectionState.DISCONNECTED && previous != ConnectionState.DISCONNECTED) {
                            bladeState = null
                            nowPlaying = null
                            currentPresetIndex = null
                            currentVolume = null
                            presetEntries = emptyList()
                            presetRows = emptyList()
                            trackPaths = emptyList()
                            trackRows = emptyList()
                            saberStatus = "Disconnected"
                            trackStatus = "Disconnected"
                            renderAll()
                        }
                    }
                }
                launch {
                    bleManager.logs.collect { line ->
                        appendLog(line)
                    }
                }
            }
        }
    }

    private fun launchBleTask(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    private suspend fun runCommand(
        command: String,
        awaitResponse: Boolean = true,
        timeoutMs: Long = 3_000L,
        retries: Int = 1,
        successLog: Boolean = awaitResponse
    ): SaberBleManager.CommandResult {
        val result = bleManager.sendCommand(
            command = command,
            awaitResponse = awaitResponse,
            timeoutMs = timeoutMs,
            retries = retries
        )
        if (!result.success) {
            appendLog("Command '${result.command}' failed: ${result.error ?: "unknown"}")
        } else if (successLog) {
            appendLog("Command '${result.command}' completed")
        }
        return result
    }

    private suspend fun syncAllDataInternal() {
        syncSaberPageInternal()
        refreshTrackListInternal()
        refreshNowPlayingInternal(logCompletion = false)
    }

    private suspend fun syncSaberPageInternal() {
        refreshBladeStateInternal(logCompletion = false)
        refreshPresetListInternal()
        refreshCurrentPresetInternal(logCompletion = false)
        refreshVolumeInternal(logCompletion = false)
    }

    private suspend fun refreshBladeStateInternal(logCompletion: Boolean = true) {
        val result = runCommand(
            command = "get_on",
            awaitResponse = true,
            successLog = logCompletion
        )
        if (!result.success) return

        val parsedState = SaberCommandResponseParser.parseBladeState(result.response)
        if (parsedState == null) {
            appendLog("Unable to parse get_on response")
            return
        }

        bladeState = parsedState
        val currentPreset = currentPresetEntry()
        saberStatus = when {
            currentPreset?.isHeader == true -> {
                "Header preset selected. Choose a blade preset to ignite."
            }
            parsedState -> "Blade reported ON"
            else -> "Blade reported OFF"
        }
        renderAll()
    }

    private suspend fun refreshPresetListInternal() {
        val result = runCommand(
            command = "list_presets",
            awaitResponse = true,
            timeoutMs = 8_000L,
            successLog = false
        )
        if (!result.success) return

        presetEntries = SaberCommandResponseParser.parsePresetEntries(result.response)
        presetRows = SaberCommandResponseParser.buildPresetRows(presetEntries)
        saberStatus = if (presetEntries.isEmpty()) {
            "No presets returned by saber"
        } else {
            "Loaded ${presetEntries.size} presets"
        }
        renderAll()
    }

    private suspend fun refreshCurrentPresetInternal(logCompletion: Boolean = true) {
        val result = runCommand(
            command = "get_preset",
            awaitResponse = true,
            successLog = logCompletion
        )
        if (!result.success) return

        currentPresetIndex = SaberCommandResponseParser.parseCurrentPresetIndex(result.response)
        val currentPreset = currentPresetEntry()
        saberStatus = when {
            currentPreset == null -> "Current preset index unavailable"
            currentPreset.isHeader -> "Header preset selected. Choose a blade preset to ignite."
            else -> "Preset ready: ${currentPreset.displayName}"
        }
        renderAll()
    }

    private suspend fun selectPresetInternal(entry: SaberCommandResponseParser.PresetEntry) {
        if (entry.isHeader) {
            saberStatus = "Header presets are not selectable from the app"
            renderAll()
            return
        }

        saberStatus = "Selecting preset ${entry.displayName}"
        renderAll()
        val result = runCommand(
            command = "set_preset ${entry.index}",
            awaitResponse = true,
            timeoutMs = 5_000L,
            successLog = false
        )
        if (!result.success) return

        currentPresetIndex = entry.index
        saberStatus = "Preset selected: ${entry.displayName}"
        renderAll()
        refreshCurrentPresetInternal(logCompletion = false)
        refreshBladeStateInternal(logCompletion = false)
        refreshNowPlayingInternal(logCompletion = false)
    }

    private suspend fun setBladePowerInternal(isOn: Boolean) {
        if (isOn && currentPresetEntry()?.isHeader == true) {
            saberStatus = "Header preset selected. Choose a blade preset to ignite."
            renderAll()
            return
        }

        saberStatus = if (isOn) "Ignition requested" else "Retraction requested"
        renderAll()
        val command = if (isOn) "on" else "off"
        val result = runCommand(command = command, awaitResponse = true)
        if (!result.success) return
        refreshBladeStateInternal(logCompletion = false)
    }

    private suspend fun refreshVolumeInternal(logCompletion: Boolean = true) {
        val result = runCommand(
            command = "get_volume",
            awaitResponse = true,
            successLog = logCompletion
        )
        if (!result.success) return

        currentVolume = SaberCommandResponseParser.parseVolume(result.response)
        renderAll()
    }

    private suspend fun setVolumeInternal(volume: Int) {
        val normalized = volume.coerceIn(0, 3000)
        if (currentVolume == normalized) {
            renderAll()
            return
        }

        val result = runCommand(
            command = "set_volume $normalized",
            awaitResponse = true,
            successLog = false
        )
        if (!result.success) return

        currentVolume = normalized
        saberStatus = "Volume set to $normalized"
        trackStatus = "Volume set to $normalized"
        renderAll()
    }

    private suspend fun refreshTrackListInternal() {
        val result = runCommand(
            command = "lt",
            awaitResponse = true,
            timeoutMs = 8_000L,
            successLog = false
        )
        if (!result.success) return

        trackPaths = SaberCommandResponseParser.parseTrackPaths(result.response)
        trackRows = SaberCommandResponseParser.buildTrackRows(trackPaths)
        trackStatus = if (trackPaths.isEmpty()) {
            "No tracks returned by saber"
        } else {
            "Loaded ${trackPaths.size} tracks"
        }
        renderAll()
    }

    private suspend fun refreshNowPlayingInternal(logCompletion: Boolean = true) {
        val result = runCommand(
            command = "gt",
            awaitResponse = true,
            successLog = logCompletion
        )
        if (!result.success) return

        nowPlaying = SaberCommandResponseParser.parseNowPlaying(result.response)
        trackStatus = if (nowPlaying == null) {
            "Nothing playing"
        } else {
            "Playing ${displayTrackName(nowPlaying)}"
        }
        renderAll()
    }

    private suspend fun playTrackInternal(trackPath: String) {
        trackStatus = "Play requested: ${displayTrackName(trackPath)}"
        renderAll()
        val trackIndex = trackPaths.indexOf(trackPath)
        val playCommand = if (trackIndex >= 0) {
            "pt $trackIndex"
        } else {
            "play_track $trackPath"
        }
        val result = runCommand(
            command = playCommand,
            awaitResponse = true,
            timeoutMs = 5_000L,
            successLog = false
        )
        if (!result.success) return

        val immediateNowPlaying = SaberCommandResponseParser.parseNowPlaying(result.response)
        if (immediateNowPlaying != null) {
            nowPlaying = immediateNowPlaying
            trackStatus = "Playing ${displayTrackName(immediateNowPlaying)}"
            renderAll()
            return
        }

        appendLog("Unable to confirm play_track response for $trackPath")
        val confirmation = runCommand(
            command = "gt",
            awaitResponse = true,
            successLog = false
        )
        if (confirmation.success) {
            nowPlaying = SaberCommandResponseParser.parseNowPlaying(confirmation.response)
        }
        trackStatus = if (nowPlaying == null) {
            "Play not confirmed for ${displayTrackName(trackPath)}"
        } else {
            "Playing ${displayTrackName(nowPlaying)}"
        }
        renderAll()
    }

    private suspend fun stopTrackInternal() {
        trackStatus = "Stop requested"
        renderAll()
        val result = runCommand(
            command = "st",
            awaitResponse = true,
            successLog = false
        )
        if (!result.success) return

        val confirmation = runCommand(
            command = "gt",
            awaitResponse = true,
            successLog = false
        )
        if (confirmation.success) {
            nowPlaying = SaberCommandResponseParser.parseNowPlaying(confirmation.response)
        }
        trackStatus = if (nowPlaying == null) "Stopped" else "Still playing ${displayTrackName(nowPlaying)}"
        renderAll()
    }

    private fun renderAll() {
        saberPageBinding?.let { renderSaberPage(it) }
        tracksPageBinding?.let { renderTracksPage(it) }
        logPageBinding?.let { renderLogPage(it) }
    }

    private fun renderSaberPage(pageBinding: PageSaberBinding) {
        val currentPreset = currentPresetEntry()
        pageBinding.textSaberStatusValue.text = when {
            saberStatus.isNotBlank() -> saberStatus
            currentConnectionState != ConnectionState.READY -> currentConnectionState.name
            currentPreset?.isHeader == true -> "Header preset selected. Choose a blade preset to ignite."
            else -> "Ready"
        }
        pageBinding.textLastStateValue.text = when (bladeState) {
            true -> "ON (1)"
            false -> "OFF (0)"
            null -> getString(R.string.state_unknown)
        }
        pageBinding.textCurrentPresetValue.text = currentPreset?.let { entry ->
            if (entry.isHeader) {
                "${entry.displayName} (header)"
            } else {
                entry.displayName
            }
        } ?: getString(R.string.state_unknown)
        pageBinding.textPresetCountValue.text = if (presetEntries.isEmpty()) {
            getString(R.string.preset_count_empty)
        } else {
            "${presetEntries.size} presets"
        }

        presetAdapter.items = presetRows
        presetAdapter.selectedPosition = presetRows.indexOfFirst { row ->
            row.presetIndex == currentPresetIndex
        }

        val canInteract = currentConnectionState == ConnectionState.READY
        pageBinding.buttonOn.isEnabled = canInteract && currentPreset?.isHeader != true
        pageBinding.buttonOff.isEnabled = canInteract
        pageBinding.buttonSyncSaber.isEnabled = canInteract
        pageBinding.listPresets.isEnabled = canInteract && presetRows.isNotEmpty()
        pageBinding.buttonRefreshVolume.isEnabled = canInteract
        pageBinding.seekVolume.isEnabled = canInteract
        bindVolume(pageBinding.textVolumeValue, pageBinding.seekVolume)
    }

    private fun renderTracksPage(pageBinding: PageTracksBinding) {
        pageBinding.textTrackStatusValue.text = when {
            trackStatus.isNotBlank() -> trackStatus
            currentConnectionState != ConnectionState.READY -> currentConnectionState.name
            else -> "Ready"
        }
        pageBinding.textNowPlayingValue.text = nowPlaying ?: getString(R.string.state_none)
        pageBinding.textTrackCountValue.text = if (trackPaths.isEmpty()) {
            getString(R.string.track_count_empty)
        } else {
            "${trackPaths.size} tracks"
        }

        trackAdapter.items = trackRows
        trackAdapter.selectedPosition = trackRows.indexOfFirst { row ->
            row is SaberCommandResponseParser.TrackRow.Track && row.path == nowPlaying
        }

        val canInteract = currentConnectionState == ConnectionState.READY
        pageBinding.buttonRefreshTracks.isEnabled = canInteract
        pageBinding.buttonRefreshNowPlaying.isEnabled = canInteract
        pageBinding.buttonStopTrack.isEnabled = canInteract
        pageBinding.listTracks.isEnabled = canInteract && trackRows.isNotEmpty()
        pageBinding.buttonRefreshVolume.isEnabled = canInteract
        pageBinding.seekVolume.isEnabled = canInteract
        bindVolume(pageBinding.textVolumeValue, pageBinding.seekVolume)
    }

    private fun bindVolume(textView: TextView, seekBar: SeekBar) {
        val volume = currentVolume
        suppressVolumeCallbacks = true
        textView.text = volume?.toString() ?: getString(R.string.volume_unknown)
        if (volume != null && seekBar.progress != volume) {
            seekBar.progress = volume
        }
        suppressVolumeCallbacks = false
    }

    private fun renderLogPage(pageBinding: PageLogBinding) {
        pageBinding.textLog.text = buildLogText()
        pageBinding.scrollLog.post {
            pageBinding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun appendLog(line: String) {
        val maxLines = 350
        if (logLines.size >= maxLines) {
            logLines.removeFirst()
        }
        if (line.startsWith("FRAME<<")) {
            lastFrameLine = line
        }
        logLines.addLast(line)
        logPageBinding?.let { renderLogPage(it) }
    }

    private fun buildLogText(): String = logLines.joinToString(separator = "\n")

    private fun copyTextToClipboard(text: String, message: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        showToast(message)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun runWithBlePermissions(action: () -> Unit) {
        val missingPermissions = requiredBlePermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            action()
        } else {
            pendingPermissionAction = action
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun requiredBlePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun currentPresetEntry(): SaberCommandResponseParser.PresetEntry? {
        val targetIndex = currentPresetIndex ?: return null
        return presetEntries.firstOrNull { entry -> entry.index == targetIndex }
    }

    private fun displayTrackName(trackPath: String?): String {
        if (trackPath.isNullOrBlank()) return getString(R.string.state_none)
        return trackPath.substringAfterLast('/')
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
