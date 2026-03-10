package com.eseger70.sabercontroller

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eseger70.sabercontroller.ble.SaberBleManager
import com.eseger70.sabercontroller.ble.SaberBleManager.ConnectionState
import com.eseger70.sabercontroller.ble.SaberCommandResponseParser
import com.eseger70.sabercontroller.databinding.ActivityMainBinding
import com.eseger70.sabercontroller.databinding.PageEffectsBinding
import com.eseger70.sabercontroller.databinding.PageLogBinding
import com.eseger70.sabercontroller.databinding.PageSaberBinding
import com.eseger70.sabercontroller.databinding.PageStylesBinding
import com.eseger70.sabercontroller.databinding.PageTracksBinding
import com.eseger70.sabercontroller.ui.MainPagerAdapter
import com.eseger70.sabercontroller.ui.SectionedListAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private enum class ChipTone(val backgroundRes: Int, val textColorRes: Int) {
        PRIMARY(R.drawable.bg_chip_primary, R.color.app_chip_primary_text),
        SUCCESS(R.drawable.bg_chip_success, R.color.app_chip_success_text),
        WARNING(R.drawable.bg_chip_warning, R.color.app_chip_warning_text),
        ERROR(R.drawable.bg_chip_error, R.color.app_chip_error_text),
        NEUTRAL(R.drawable.bg_chip_neutral, R.color.app_header_text)
    }

    companion object {
        private const val MAX_VOLUME = 2000
        private const val LOG_TAG_APP = "SaberCtrl"
        private const val LOG_TAG_TX = "SaberCtrlTx"
        private const val LOG_TAG_RX = "SaberCtrlRx"
        private const val LOG_TAG_FRAME = "SaberCtrlFrm"
        private const val LOG_TAG_WARN = "SaberCtrlWarn"
        private const val LOGCAT_CHUNK_SIZE = 3_500
    }

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
    private var stylesPageBinding: PageStylesBinding? = null
    private var effectsPageBinding: PageEffectsBinding? = null
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
            labelProvider = { row -> row.label },
            headerProvider = { row -> row is SaberCommandResponseParser.TrackRow.Header },
            enabledProvider = { row -> row is SaberCommandResponseParser.TrackRow.Track },
            levelProvider = { row -> row.level }
        )
    }
    private val trackVisualAdapter by lazy {
        ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf<String>()
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private var bladeState: Boolean? = null
    private var saberStatus: String = ""
    private var trackStatus: String = ""
    private var visualsStatus: String = ""
    private var effectsStatus: String = ""
    private var nowPlaying: String? = null
    private var selectedTrackPath: String? = null
    private var currentPresetIndex: Int? = null
    private var currentVolume: Int? = null
    private var trackPolicy: String? = null
    private var trackSessionMode: String? = null
    private var trackVisualSelectedId: Int? = null
    private var trackVisualName: String? = null
    private var trackVisualActive: Boolean? = null

    private var presetEntries: List<SaberCommandResponseParser.PresetEntry> = emptyList()
    private var presetRows: List<SaberCommandResponseParser.PresetRow> = emptyList()
    private var trackPaths: List<String> = emptyList()
    private var trackRows: List<SaberCommandResponseParser.TrackRow> = emptyList()
    private var trackVisualOptions: List<SaberCommandResponseParser.TrackVisualOption> = emptyList()

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
            onStylesPageBound = { pageBinding -> bindStylesPage(pageBinding) },
            onEffectsPageBound = { pageBinding -> bindEffectsPage(pageBinding) },
            onLogPageBound = { pageBinding -> bindLogPage(pageBinding) }
        )
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 5

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_saber)
                1 -> getString(R.string.tab_tracks)
                2 -> getString(R.string.tab_styles)
                3 -> getString(R.string.tab_effects)
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
            R.string.tab_styles,
            R.string.tab_effects,
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
            minWidth = dp(72)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
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
                selectedTrackPath = row.path
                renderAll()
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

    private fun bindStylesPage(pageBinding: PageStylesBinding) {
        stylesPageBinding = pageBinding
        if (pageBinding.spinnerTrackVisuals.adapter !== trackVisualAdapter) {
            pageBinding.spinnerTrackVisuals.adapter = trackVisualAdapter
        }

        pageBinding.buttonRefreshTrackVisuals.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { syncTrackVisualPageInternal() }
            }
        }
        pageBinding.buttonPolicyAuto.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { setTrackPolicyInternal("auto") }
            }
        }
        pageBinding.buttonPolicySaber.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { setTrackPolicyInternal("preserve") }
            }
        }
        pageBinding.buttonPolicyMusic.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { setTrackPolicyInternal("visual") }
            }
        }
        pageBinding.buttonApplyTrackVisual.setOnClickListener {
            val option = trackVisualOptions.getOrNull(pageBinding.spinnerTrackVisuals.selectedItemPosition)
            if (option == null) {
                visualsStatus = "Load track visuals from the saber first."
                renderAll()
            } else {
                runWithBlePermissions {
                    launchBleTask { setTrackVisualInternal(option) }
                }
            }
        }
        pageBinding.buttonClearTrackVisual.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { clearTrackVisualInternal() }
            }
        }

        renderStylesPage(pageBinding)
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

        pageBinding.inputRawCommand.doAfterTextChanged {
            updateRawCommandControls(pageBinding)
        }

        pageBinding.inputRawCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                pageBinding.buttonSendRawCommand.performClick()
                true
            } else {
                false
            }
        }

        pageBinding.buttonSendRawCommand.setOnClickListener {
            val command = pageBinding.inputRawCommand.text
                ?.toString()
                ?.trim()
                .orEmpty()
            if (command.isBlank()) {
                showToast(getString(R.string.raw_command_empty))
                return@setOnClickListener
            }
            if (currentConnectionState != ConnectionState.READY) {
                showToast(getString(R.string.raw_command_requires_connection))
                return@setOnClickListener
            }

            val awaitResponse = pageBinding.checkAwaitResponse.isChecked
            runWithBlePermissions {
                launchBleTask {
                    sendRawCommandInternal(
                        command = command,
                        awaitResponse = awaitResponse
                    )
                }
            }
        }

        renderLogPage(pageBinding)
    }

    private fun bindEffectsPage(pageBinding: PageEffectsBinding) {
        effectsPageBinding = pageBinding

        pageBinding.buttonClash.setOnClickListener { triggerEffect("cl", "Clash triggered") }
        pageBinding.buttonStab.setOnClickListener { triggerEffect("sb", "Stab triggered") }
        pageBinding.buttonForce.setOnClickListener { triggerEffect("fo", "Force effect triggered") }
        pageBinding.buttonBlast.setOnClickListener { triggerEffect("bt", "Blast triggered") }
        pageBinding.buttonLockup.setOnClickListener { triggerEffect("lk", "Lockup toggled") }
        pageBinding.buttonDrag.setOnClickListener { triggerEffect("dg", "Drag toggled") }
        pageBinding.buttonLightningBlock.setOnClickListener { triggerEffect("lb", "Lightning block toggled") }
        pageBinding.buttonMelt.setOnClickListener { triggerEffect("mt", "Melt toggled") }

        renderEffectsPage(pageBinding)
    }

    private fun collectBleState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    bleManager.connectionState.collect { state ->
                        val previous = currentConnectionState
                        currentConnectionState = state
                        binding.textConnectionStateValue.text = state.name
                        applyChipTone(
                            binding.textConnectionStateValue,
                            when (state) {
                                ConnectionState.READY -> ChipTone.SUCCESS
                                ConnectionState.DISCONNECTED -> ChipTone.NEUTRAL
                                else -> ChipTone.WARNING
                            }
                        )
                        binding.buttonConnect.isEnabled = state == ConnectionState.DISCONNECTED
                        binding.buttonDisconnect.isEnabled = state != ConnectionState.DISCONNECTED
                        renderAll()

                        if (state == ConnectionState.READY && previous != ConnectionState.READY) {
                            saberStatus = "Connected. Syncing saber data..."
                            trackStatus = "Connected. Syncing track data..."
                            visualsStatus = "Connected. Syncing track visual data..."
                            effectsStatus = ""
                            renderAll()
                            launchBleTask { syncAllDataInternal() }
                        }

                        if (state == ConnectionState.DISCONNECTED && previous != ConnectionState.DISCONNECTED) {
                            bladeState = null
                            nowPlaying = null
                            selectedTrackPath = null
                            currentPresetIndex = null
                            currentVolume = null
                            presetEntries = emptyList()
                            presetRows = emptyList()
                            trackPaths = emptyList()
                            trackRows = emptyList()
                            trackPolicy = null
                            trackSessionMode = null
                            trackVisualSelectedId = null
                            trackVisualName = null
                            trackVisualActive = null
                            trackVisualOptions = emptyList()
                            saberStatus = "Disconnected"
                            trackStatus = "Disconnected"
                            visualsStatus = ""
                            effectsStatus = ""
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
        refreshTrackVisualOptionsInternal(logCompletion = false)
    }

    private suspend fun syncTrackVisualPageInternal() {
        refreshTrackVisualOptionsInternal(logCompletion = false)
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

        currentVolume = SaberCommandResponseParser.parseVolume(result.response)?.coerceIn(0, MAX_VOLUME)
        renderAll()
    }

    private suspend fun setVolumeInternal(volume: Int) {
        val normalized = volume.coerceIn(0, MAX_VOLUME)
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
        if (selectedTrackPath !in trackPaths) {
            selectedTrackPath = nowPlaying?.takeIf(trackPaths::contains)
        }
        trackStatus = if (trackPaths.isEmpty()) {
            "No tracks returned by saber"
        } else {
            "Loaded ${trackPaths.size} tracks"
        }
        renderAll()
    }

    private suspend fun refreshTrackVisualOptionsInternal(logCompletion: Boolean = true) {
        val result = runCommand(
            command = "tvl",
            awaitResponse = true,
            timeoutMs = 5_000L,
            successLog = logCompletion
        )
        if (!result.success) return

        trackVisualOptions = SaberCommandResponseParser.parseTrackVisualOptions(result.response)
        visualsStatus = if (trackVisualOptions.isEmpty()) {
            "No track visuals returned by saber"
        } else {
            "Loaded ${trackVisualOptions.size} track visuals"
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

        applyTrackRuntimeState(result.response)
        if (selectedTrackPath == null && nowPlaying != null) {
            selectedTrackPath = nowPlaying
        }
        trackStatus = if (nowPlaying == null) {
            "Nothing playing"
        } else {
            buildTrackStatus(nowPlaying)
        }
        renderAll()
    }

    private suspend fun playTrackInternal(trackPath: String) {
        selectedTrackPath = trackPath
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

        val immediateState = applyTrackRuntimeState(result.response)
        if (immediateState?.visualRejectedReason == "blade_on") {
            trackStatus = "Music mode requires the blade to be off."
            renderAll()
            return
        }

        val immediateNowPlaying = nowPlaying
        if (immediateNowPlaying != null) {
            trackStatus = buildTrackStatus(immediateNowPlaying)
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
            val confirmedState = applyTrackRuntimeState(confirmation.response)
            if (confirmedState?.visualRejectedReason == "blade_on") {
                trackStatus = "Music mode requires the blade to be off."
                renderAll()
                return
            }
        }
        trackStatus = if (nowPlaying == null) {
            "Play not confirmed for ${displayTrackName(trackPath)}"
        } else {
            buildTrackStatus(nowPlaying)
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
            applyTrackRuntimeState(confirmation.response)
        } else {
            nowPlaying = null
        }
        trackStatus = if (nowPlaying == null) "Stopped" else "Still playing ${displayTrackName(nowPlaying)}"
        renderAll()
    }

    private suspend fun setTrackPolicyInternal(policy: String) {
        val displayPolicy = displayTrackPolicy(policy)
        visualsStatus = "Setting playback mode to $displayPolicy"
        renderAll()

        val result = runCommand(
            command = "tps $policy",
            awaitResponse = true,
            successLog = false
        )
        if (!result.success) return

        applyTrackRuntimeState(result.response)
        visualsStatus = "Playback mode set to $displayPolicy"
        renderAll()
        refreshNowPlayingInternal(logCompletion = false)
    }

    private suspend fun setTrackVisualInternal(option: SaberCommandResponseParser.TrackVisualOption) {
        val command = if (option.id == 0) "tvc" else "tvs ${option.id}"
        visualsStatus = if (option.id == 0) {
            "Clearing track visual selection"
        } else {
            "Selecting track visual ${option.name}"
        }
        renderAll()

        val result = runCommand(
            command = command,
            awaitResponse = true,
            successLog = false
        )
        if (!result.success) return

        applyTrackRuntimeState(result.response)
        visualsStatus = if (option.id == 0) {
            "Track visual cleared"
        } else {
            "Track visual set to ${option.name}"
        }
        renderAll()
        refreshNowPlayingInternal(logCompletion = false)
    }

    private suspend fun clearTrackVisualInternal() {
        setTrackVisualInternal(
            SaberCommandResponseParser.TrackVisualOption(
                id = 0,
                name = "None"
            )
        )
    }

    private fun triggerEffect(command: String, fallbackMessage: String) {
        runWithBlePermissions {
            launchBleTask { triggerEffectInternal(command, fallbackMessage) }
        }
    }

    private suspend fun triggerEffectInternal(command: String, fallbackMessage: String) {
        val currentPreset = currentPresetEntry()
        if (currentPreset == null) {
            effectsStatus = "Current preset unavailable."
            renderAll()
            return
        }
        if (currentPreset.isHeader) {
            effectsStatus = "Header preset selected. Choose a blade preset."
            renderAll()
            return
        }
        if (bladeState != true) {
            effectsStatus = "Turn blade on to use blade effects."
            renderAll()
            return
        }

        effectsStatus = "$fallbackMessage..."
        renderAll()

        val result = runCommand(
            command = command,
            awaitResponse = true,
            successLog = false
        )
        if (!result.success) return

        effectsStatus = firstResponseLine(result.response) ?: fallbackMessage
        renderAll()
    }

    private suspend fun sendRawCommandInternal(command: String, awaitResponse: Boolean) {
        val result = runCommand(
            command = command,
            awaitResponse = awaitResponse,
            timeoutMs = if (awaitResponse) 5_000L else 2_000L,
            retries = 1,
            successLog = true
        )
        if (!result.success || !awaitResponse) return

        var shouldRender = false
        if (applyTrackRuntimeState(result.response) != null) {
            shouldRender = true
        }
        if (command.equals("tvl", ignoreCase = true) ||
            command.equals("list_track_visuals", ignoreCase = true)
        ) {
            trackVisualOptions = SaberCommandResponseParser.parseTrackVisualOptions(result.response)
            shouldRender = true
        }
        if (shouldRender) {
            renderAll()
        }
    }

    private fun renderAll() {
        saberPageBinding?.let { renderSaberPage(it) }
        tracksPageBinding?.let { renderTracksPage(it) }
        stylesPageBinding?.let { renderStylesPage(it) }
        effectsPageBinding?.let { renderEffectsPage(it) }
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
        applyChipTone(
            pageBinding.textLastStateValue,
            when (bladeState) {
                true -> ChipTone.SUCCESS
                false -> ChipTone.ERROR
                null -> ChipTone.NEUTRAL
            }
        )
        pageBinding.textCurrentPresetValue.text = currentPreset?.let { entry ->
            if (entry.isHeader) {
                "${entry.displayName} (header)"
            } else {
                entry.displayName
            }
        } ?: getString(R.string.state_unknown)
        pageBinding.textCurrentPresetValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (currentPreset?.isHeader == true) R.color.app_chip_warning_text else R.color.app_text_primary
            )
        )
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
        pageBinding.textNowPlayingValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (nowPlaying.isNullOrBlank()) R.color.app_text_secondary else R.color.app_primary
            )
        )
        pageBinding.textTrackCountValue.text = if (trackPaths.isEmpty()) {
            getString(R.string.track_count_empty)
        } else {
            "${trackPaths.size} tracks"
        }

        trackAdapter.items = trackRows
        trackAdapter.selectedPosition = trackRows.indexOfFirst { row ->
            row is SaberCommandResponseParser.TrackRow.Track &&
                row.path == (selectedTrackPath ?: nowPlaying)
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

    private fun renderStylesPage(pageBinding: PageStylesBinding) {
        pageBinding.textStylesStatusValue.text = when {
            visualsStatus.isNotBlank() -> visualsStatus
            currentConnectionState != ConnectionState.READY -> currentConnectionState.name
            else -> getString(R.string.track_visual_status_default)
        }
        pageBinding.textTrackVisualPolicyValue.text = displayTrackPolicy(trackPolicy)
        pageBinding.textTrackVisualSelectionValue.text = displaySelectedTrackVisual()
        pageBinding.textTrackVisualRuntimeValue.text = buildTrackVisualRuntimeSummary()
        pageBinding.textTrackVisualTrackValue.text = activeTrackPath() ?: getString(R.string.state_none)
        pageBinding.textTrackVisualTrackValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (activeTrackPath().isNullOrBlank()) R.color.app_text_secondary else R.color.app_text_primary
            )
        )
        applyChipTone(
            pageBinding.textTrackVisualPolicyValue,
            when (trackPolicy) {
                "visual" -> ChipTone.SUCCESS
                "preserve" -> ChipTone.WARNING
                "auto" -> ChipTone.PRIMARY
                else -> ChipTone.NEUTRAL
            }
        )
        applyChipTone(
            pageBinding.textTrackVisualSelectionValue,
            if ((trackVisualSelectedId ?: 0) == 0) ChipTone.NEUTRAL else ChipTone.PRIMARY
        )
        applyChipTone(
            pageBinding.textTrackVisualRuntimeValue,
            when {
                trackVisualActive == true -> ChipTone.SUCCESS
                trackSessionMode == "preserve" -> ChipTone.WARNING
                trackSessionMode == "visual" -> ChipTone.PRIMARY
                else -> ChipTone.NEUTRAL
            }
        )

        trackVisualAdapter.clear()
        trackVisualAdapter.addAll(trackVisualOptions.map { option -> option.name })
        trackVisualAdapter.notifyDataSetChanged()

        val selectedIndex = trackVisualOptions.indexOfFirst { option ->
            option.id == (trackVisualSelectedId ?: 0)
        }
        if (selectedIndex >= 0 && pageBinding.spinnerTrackVisuals.selectedItemPosition != selectedIndex) {
            pageBinding.spinnerTrackVisuals.setSelection(selectedIndex, false)
        }

        val canInteract = currentConnectionState == ConnectionState.READY
        pageBinding.buttonRefreshTrackVisuals.isEnabled = canInteract
        pageBinding.buttonPolicyAuto.isEnabled = canInteract
        pageBinding.buttonPolicySaber.isEnabled = canInteract
        pageBinding.buttonPolicyMusic.isEnabled = canInteract
        pageBinding.buttonApplyTrackVisual.isEnabled = canInteract && trackVisualOptions.isNotEmpty()
        pageBinding.buttonClearTrackVisual.isEnabled = canInteract && (trackVisualSelectedId ?: 0) != 0

        when (trackPolicy) {
            "visual" -> pageBinding.toggleTrackPolicy.check(R.id.buttonPolicyMusic)
            "preserve" -> pageBinding.toggleTrackPolicy.check(R.id.buttonPolicySaber)
            else -> pageBinding.toggleTrackPolicy.check(R.id.buttonPolicyAuto)
        }
    }

    private fun renderEffectsPage(pageBinding: PageEffectsBinding) {
        val currentPreset = currentPresetEntry()
        pageBinding.textEffectsStatusValue.text = when {
            currentConnectionState != ConnectionState.READY -> currentConnectionState.name
            currentPreset == null -> "Current preset unavailable."
            currentPreset?.isHeader == true -> "Header preset selected. Choose a blade preset."
            bladeState != true -> "Turn blade on to use blade effects."
            effectsStatus.isNotBlank() -> effectsStatus
            else -> "Ready for blade effects"
        }
        val presetName = currentPreset?.displayName ?: getString(R.string.state_unknown)
        val bladeText = when (bladeState) {
            true -> "Blade ON"
            false -> "Blade OFF"
            null -> "Blade UNKNOWN"
        }
        pageBinding.textEffectsContextValue.text = "$bladeText | Preset $presetName"
        pageBinding.textEffectsContextValue.setTextColor(
            ContextCompat.getColor(
                this,
                when (bladeState) {
                    true -> R.color.app_chip_success_text
                    false -> R.color.app_chip_error_text
                    null -> R.color.app_text_secondary
                }
            )
        )

        val canUseEffects = currentConnectionState == ConnectionState.READY &&
            bladeState == true &&
            currentPreset != null &&
            currentPreset?.isHeader != true

        pageBinding.buttonClash.isEnabled = canUseEffects
        pageBinding.buttonStab.isEnabled = canUseEffects
        pageBinding.buttonForce.isEnabled = canUseEffects
        pageBinding.buttonBlast.isEnabled = canUseEffects
        pageBinding.buttonLockup.isEnabled = canUseEffects
        pageBinding.buttonDrag.isEnabled = canUseEffects
        pageBinding.buttonLightningBlock.isEnabled = canUseEffects
        pageBinding.buttonMelt.isEnabled = canUseEffects
    }

    private fun bindVolume(textView: TextView, seekBar: SeekBar) {
        val volume = currentVolume?.coerceIn(0, MAX_VOLUME)
        suppressVolumeCallbacks = true
        textView.text = volume?.toString() ?: getString(R.string.volume_unknown)
        if (volume != null && seekBar.progress != volume) {
            seekBar.progress = volume
        }
        suppressVolumeCallbacks = false
    }

    private fun renderLogPage(pageBinding: PageLogBinding) {
        pageBinding.textLog.text = buildLogText()
        updateRawCommandControls(pageBinding)
        pageBinding.scrollLog.post {
            pageBinding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateRawCommandControls(pageBinding: PageLogBinding) {
        val hasCommand = !pageBinding.inputRawCommand.text.isNullOrBlank()
        pageBinding.buttonSendRawCommand.isEnabled =
            currentConnectionState == ConnectionState.READY && hasCommand
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
        mirrorLogLineToLogcat(line)
        logPageBinding?.let { renderLogPage(it) }
    }

    private fun mirrorLogLineToLogcat(line: String) {
        val (tag, priority) = when {
            line.startsWith("TX >>") -> LOG_TAG_TX to Log.INFO
            line.startsWith("RX <<") -> LOG_TAG_RX to Log.INFO
            line.startsWith("FRAME <<") || line.startsWith("FRAME<<") -> LOG_TAG_FRAME to Log.INFO
            line.contains("failed", ignoreCase = true) ||
                line.contains("denied", ignoreCase = true) ||
                line.contains("timeout", ignoreCase = true) -> LOG_TAG_WARN to Log.WARN
            else -> LOG_TAG_APP to Log.DEBUG
        }

        if (line.length <= LOGCAT_CHUNK_SIZE) {
            writeLogcat(tag, priority, line)
            return
        }

        val chunks = line.chunked(LOGCAT_CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            writeLogcat(tag, priority, "[${index + 1}/${chunks.size}] $chunk")
        }
    }

    private fun writeLogcat(tag: String, priority: Int, message: String) {
        when (priority) {
            Log.WARN -> Log.w(tag, message)
            Log.ERROR -> Log.e(tag, message)
            Log.INFO -> Log.i(tag, message)
            else -> Log.d(tag, message)
        }
    }

    private fun buildLogText(): String = logLines.joinToString(separator = "\n")

    private fun firstResponseLine(response: String?): String? {
        return response
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
    }

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

    private fun applyChipTone(textView: TextView, tone: ChipTone) {
        textView.background = AppCompatResources.getDrawable(this, tone.backgroundRes)
        textView.setTextColor(ContextCompat.getColor(this, tone.textColorRes))
    }

    private fun activeTrackPath(): String? = selectedTrackPath ?: nowPlaying

    private fun applyTrackRuntimeState(response: String?) : SaberCommandResponseParser.TrackRuntimeState? {
        val state = SaberCommandResponseParser.parseTrackRuntimeState(response) ?: return null
        if (state.trackActive == false) {
            nowPlaying = null
        } else if (state.nowPlaying != null) {
            nowPlaying = state.nowPlaying
        }
        state.policy?.let { trackPolicy = it }
        state.sessionMode?.let { trackSessionMode = it }
        state.visualSelectedId?.let { trackVisualSelectedId = it }
        state.visualName?.let { trackVisualName = it }
        state.visualActive?.let { trackVisualActive = it }
        return state
    }

    private fun buildTrackStatus(trackPath: String?): String {
        val base = "Playing ${displayTrackName(trackPath)}"
        return if (trackVisualActive == true && !trackVisualName.isNullOrBlank()) {
            "$base with $trackVisualName"
        } else {
            base
        }
    }

    private fun displayTrackPolicy(policy: String?): String {
        return when (policy) {
            "auto" -> getString(R.string.track_policy_auto)
            "preserve" -> getString(R.string.track_policy_preserve)
            "visual" -> getString(R.string.track_policy_visual)
            null -> getString(R.string.state_unknown)
            else -> policy.replace('_', ' ')
        }
    }

    private fun displaySelectedTrackVisual(): String {
        return if ((trackVisualSelectedId ?: 0) == 0) {
            getString(R.string.track_visual_none)
        } else {
            trackVisualName ?: "Visual #${trackVisualSelectedId ?: 0}"
        }
    }

    private fun displayTrackSessionMode(mode: String?): String {
        return when (mode) {
            "visual" -> getString(R.string.track_session_visual)
            "preserve" -> getString(R.string.track_session_preserve)
            "audio_only" -> getString(R.string.track_session_audio_only)
            "none" -> getString(R.string.track_session_none)
            null -> getString(R.string.state_unknown)
            else -> mode.replace('_', ' ')
        }
    }

    private fun buildTrackVisualRuntimeSummary(): String {
        val modeText = displayTrackSessionMode(trackSessionMode)
        val activeText = when (trackVisualActive) {
            true -> getString(R.string.track_visual_active_yes)
            false -> getString(R.string.track_visual_active_no)
            null -> getString(R.string.state_unknown)
        }
        return "$modeText | $activeText"
    }

    private fun displayTrackName(trackPath: String?): String {
        if (trackPath.isNullOrBlank()) return getString(R.string.state_none)
        return trackPath.substringAfterLast('/')
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

}
