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
import com.eseger70.sabercontroller.databinding.PageSaberBinding
import com.eseger70.sabercontroller.databinding.PageTracksBinding
import com.eseger70.sabercontroller.databinding.SheetDebugBinding
import com.eseger70.sabercontroller.databinding.SheetEffectsBinding
import com.eseger70.sabercontroller.track.VisualAssignmentResolver
import com.eseger70.sabercontroller.track.VisualAssignmentRule
import com.eseger70.sabercontroller.track.VisualAssignmentScope
import com.eseger70.sabercontroller.track.VisualAssignmentStore
import com.eseger70.sabercontroller.ui.MainPagerAdapter
import com.eseger70.sabercontroller.ui.SectionedListAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

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
    private lateinit var visualAssignmentStore: VisualAssignmentStore

    private val logLines = ArrayDeque<String>()
    private var lastFrameLine: String? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var currentConnectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var suppressVolumeCallbacks = false

    private var saberPageBinding: PageSaberBinding? = null
    private var tracksPageBinding: PageTracksBinding? = null
    private var effectsSheetBinding: SheetEffectsBinding? = null
    private var debugSheetBinding: SheetDebugBinding? = null
    private var effectsBottomSheet: BottomSheetDialog? = null
    private var debugBottomSheet: BottomSheetDialog? = null

    private val presetAdapter by lazy {
        SectionedListAdapter<SaberCommandResponseParser.PresetRow>(
            context = this,
            labelProvider = { row -> row.label },
            headerProvider = { row -> row is SaberCommandResponseParser.PresetRow.Header },
            enabledProvider = { row ->
                row is SaberCommandResponseParser.PresetRow.Preset ||
                    row is SaberCommandResponseParser.PresetRow.Header
            },
            headerStateProvider = { row ->
                when (row) {
                    is SaberCommandResponseParser.PresetRow.Header -> {
                        SectionedListAdapter.HeaderState(
                            expanded = row.expanded,
                            childCount = row.childCount
                        )
                    }
                    else -> null
                }
            }
        )
    }
    private val trackAdapter by lazy {
        SectionedListAdapter<SaberCommandResponseParser.TrackRow>(
            context = this,
            labelProvider = { row -> row.label },
            headerProvider = { row -> row is SaberCommandResponseParser.TrackRow.Header },
            enabledProvider = { row ->
                row is SaberCommandResponseParser.TrackRow.Track ||
                    row is SaberCommandResponseParser.TrackRow.Header
            },
            levelProvider = { row -> row.level },
            headerStateProvider = { row ->
                when (row) {
                    is SaberCommandResponseParser.TrackRow.Header -> {
                        SectionedListAdapter.HeaderState(
                            expanded = row.expanded,
                            childCount = row.childCount
                        )
                    }
                    else -> null
                }
            }
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
    private val trackVisualOverrideAdapter by lazy {
        ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf<String>()
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }
    private val visualAssignmentScopeAdapter by lazy {
        ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf(
                getString(R.string.visual_assignment_scope_track),
                getString(R.string.visual_assignment_scope_album),
                getString(R.string.visual_assignment_scope_category),
                getString(R.string.visual_assignment_scope_default)
            )
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
    private var selectedTrackHeaderKey: String? = null
    private var currentPresetIndex: Int? = null
    private var currentVolume: Int? = null
    private var trackActive: Boolean? = null
    private var trackPaused: Boolean? = null
    private var trackPositionMs: Long? = null
    private var trackLengthMs: Long? = null
    private var trackPolicy: String? = null
    private var trackSessionMode: String? = null
    private var trackVisualSelectedId: Int? = null
    private var trackVisualName: String? = null
    private var trackVisualActive: Boolean? = null
    private var trackVisualActiveName: String? = null
    private var trackVisualPreviewActive: Boolean? = null
    private var queueScopeHeaderKey: String? = null
    private var queueAutoAdvanceEnabled: Boolean = false
    private var queueRepeatEnabled: Boolean = false
    private var shuffleEnabled: Boolean = false
    private var sessionOverrideVisualId: Int? = null

    private var presetEntries: List<SaberCommandResponseParser.PresetEntry> = emptyList()
    private var presetRows: List<SaberCommandResponseParser.PresetRow> = emptyList()
    private var trackPaths: List<String> = emptyList()
    private var trackRows: List<SaberCommandResponseParser.TrackRow> = emptyList()
    private var trackVisualOptions: List<SaberCommandResponseParser.TrackVisualOption> = emptyList()
    private var visualAssignmentRules: List<VisualAssignmentRule> = emptyList()
    private var activeQueueOrder: List<String> = emptyList()
    private val queueHistory = mutableListOf<String>()
    private val expandedPresetHeaderIndices = linkedSetOf<Int>()
    private val expandedTrackHeaderKeys = linkedSetOf<String>()
    private var trackMonitorJob: Job? = null

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
        visualAssignmentStore = VisualAssignmentStore(applicationContext)
        visualAssignmentRules = visualAssignmentStore.loadRules()
        applySystemInsets()
        setupPager()
        wireCommonUi()
        collectBleState()
        renderAll()
        appendLog("Ready. Connect to the paired FEASYCOM saber or scan fallback")
    }

    override fun onDestroy() {
        stopTrackMonitor()
        effectsBottomSheet?.dismiss()
        debugBottomSheet?.dismiss()
        bleManager.close()
        super.onDestroy()
    }

    private fun setupPager() {
        pagerAdapter = MainPagerAdapter(
            onSaberPageBound = { pageBinding -> bindSaberPage(pageBinding) },
            onMusicPageBound = { pageBinding -> bindTracksPage(pageBinding) }
        )
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 2

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_saber)
                else -> getString(R.string.tab_music)
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

        binding.buttonDebug.setOnClickListener {
            showDebugBottomSheet()
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
            R.string.tab_music
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
        pageBinding.buttonOpenEffects.setOnClickListener {
            showEffectsBottomSheet()
        }
        pageBinding.buttonRefreshVolume.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { refreshVolumeInternal() }
            }
        }
        pageBinding.listPresets.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            when (val row = presetRows.getOrNull(position)) {
                is SaberCommandResponseParser.PresetRow.Header -> togglePresetHeader(row.entry.index)
                is SaberCommandResponseParser.PresetRow.Preset -> {
                    runWithBlePermissions {
                        launchBleTask { selectPresetInternal(row.entry) }
                    }
                }
                null -> Unit
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
        if (pageBinding.spinnerTrackVisuals.adapter !== trackVisualAdapter) {
            pageBinding.spinnerTrackVisuals.adapter = trackVisualAdapter
        }
        if (pageBinding.spinnerTrackVisualOverride.adapter !== trackVisualOverrideAdapter) {
            pageBinding.spinnerTrackVisualOverride.adapter = trackVisualOverrideAdapter
        }
        if (pageBinding.spinnerVisualAssignmentScope.adapter !== visualAssignmentScopeAdapter) {
            pageBinding.spinnerVisualAssignmentScope.adapter = visualAssignmentScopeAdapter
        }

        pageBinding.buttonRefreshTracks.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { refreshTrackListInternal() }
            }
        }
        pageBinding.buttonStopTrack.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { stopTrackInternal() }
            }
        }
        pageBinding.buttonPlayTrackGroup.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { playSelectedTrackGroupInternal() }
            }
        }
        pageBinding.buttonPreviousTrack.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { stepTrackQueueInternal(direction = -1) }
            }
        }
        pageBinding.buttonTogglePauseTrack.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { toggleTrackPauseInternal() }
            }
        }
        pageBinding.buttonNextTrack.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { stepTrackQueueInternal(direction = 1) }
            }
        }
        pageBinding.buttonShuffleQueue.setOnClickListener {
            shuffleEnabled = !shuffleEnabled
            rebuildQueueForCurrentScope(preserveCurrent = true)
            trackStatus = if (shuffleEnabled) "Shuffle enabled" else "Shuffle disabled"
            renderAll()
        }
        pageBinding.buttonRepeatQueue.setOnClickListener {
            queueRepeatEnabled = !queueRepeatEnabled
            trackStatus = if (queueRepeatEnabled) {
                "Repeat enabled for the active folder queue"
            } else {
                "Repeat disabled for the active folder queue"
            }
            renderAll()
        }
        pageBinding.buttonExpandAllTracks.setOnClickListener { expandAllTrackHeaders() }
        pageBinding.buttonCollapseAllTracks.setOnClickListener { collapseAllTrackHeaders() }
        pageBinding.toggleTrackPolicy.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            runWithBlePermissions {
                when (checkedId) {
                    R.id.buttonPolicyAuto -> launchBleTask { setTrackPolicyInternal("auto") }
                    R.id.buttonPolicySaber -> launchBleTask { setTrackPolicyInternal("preserve") }
                    R.id.buttonPolicyMusic -> launchBleTask { setTrackPolicyInternal("visual") }
                }
            }
        }
        pageBinding.buttonRefreshTrackVisuals.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { refreshTrackVisualOptionsInternal() }
            }
        }
        pageBinding.buttonApplyTrackVisual.setOnClickListener {
            val option = selectedTrackVisualOptionFromMusicUi() ?: return@setOnClickListener
            runWithBlePermissions {
                launchBleTask { setTrackVisualInternal(option) }
            }
        }
        pageBinding.buttonClearTrackVisual.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { clearTrackVisualInternal() }
            }
        }
        pageBinding.buttonPreviewTrackVisual.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { previewTrackVisualInternal() }
            }
        }
        pageBinding.buttonStopTrackVisualPreview.setOnClickListener {
            runWithBlePermissions {
                launchBleTask { stopTrackVisualPreviewInternal() }
            }
        }
        pageBinding.buttonAssignTrackVisual.setOnClickListener {
            val option = selectedTrackVisualOptionFromMusicUi() ?: return@setOnClickListener
            assignVisualRuleFromUi(option)
        }
        pageBinding.buttonClearVisualAssignment.setOnClickListener {
            clearVisualRuleFromUi()
        }
        pageBinding.spinnerTrackVisualOverride.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sessionOverrideVisualId = trackVisualOverrideIdForPosition(position)
                renderAll()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        pageBinding.listTracks.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            when (val row = trackRows.getOrNull(position)) {
                is SaberCommandResponseParser.TrackRow.Header -> {
                    selectedTrackHeaderKey = row.key
                    toggleTrackHeader(row.key)
                }
                is SaberCommandResponseParser.TrackRow.Track -> {
                    selectedTrackPath = row.path
                    selectedTrackHeaderKey = SaberCommandResponseParser.deepestTrackHeaderKey(row.path)
                    renderAll()
                    runWithBlePermissions {
                        launchBleTask { playTrackInternal(row.path) }
                    }
                }
                null -> Unit
            }
        }

        renderTracksPage(pageBinding)
    }

    private fun showEffectsBottomSheet() {
        if (effectsBottomSheet?.isShowing == true) {
            renderEffectsSheet()
            return
        }

        val sheetBinding = SheetEffectsBinding.inflate(layoutInflater)
        effectsSheetBinding = sheetBinding
        effectsBottomSheet = BottomSheetDialog(this).apply {
            setContentView(sheetBinding.root)
            setOnDismissListener {
                effectsSheetBinding = null
                effectsBottomSheet = null
            }
        }

        sheetBinding.buttonClash.setOnClickListener { triggerEffect("cl", "Clash triggered") }
        sheetBinding.buttonStab.setOnClickListener { triggerEffect("sb", "Stab triggered") }
        sheetBinding.buttonForce.setOnClickListener { triggerEffect("fo", "Force effect triggered") }
        sheetBinding.buttonBlast.setOnClickListener { triggerEffect("bt", "Blast triggered") }
        sheetBinding.buttonLockup.setOnClickListener { triggerEffect("lk", "Lockup toggled") }
        sheetBinding.buttonDrag.setOnClickListener { triggerEffect("dg", "Drag toggled") }
        sheetBinding.buttonLightningBlock.setOnClickListener {
            triggerEffect("lb", "Lightning block toggled")
        }
        sheetBinding.buttonMelt.setOnClickListener { triggerEffect("mt", "Melt toggled") }
        renderEffectsSheet()
        effectsBottomSheet?.show()
    }

    private fun showDebugBottomSheet() {
        if (debugBottomSheet?.isShowing == true) {
            renderDebugSheet()
            return
        }

        val sheetBinding = SheetDebugBinding.inflate(layoutInflater)
        debugSheetBinding = sheetBinding
        debugBottomSheet = BottomSheetDialog(this).apply {
            setContentView(sheetBinding.root)
            setOnDismissListener {
                debugSheetBinding = null
                debugBottomSheet = null
            }
        }

        sheetBinding.buttonCopyLog.setOnClickListener {
            copyTextToClipboard(buildLogText(), getString(R.string.log_copied))
        }
        sheetBinding.buttonCopyLastFrame.setOnClickListener {
            val frame = lastFrameLine
            if (frame.isNullOrBlank()) {
                showToast(getString(R.string.no_frame_captured))
            } else {
                copyTextToClipboard(frame, getString(R.string.last_frame_copied))
            }
        }
        sheetBinding.inputRawCommand.doAfterTextChanged {
            updateRawCommandControls(sheetBinding)
        }
        sheetBinding.inputRawCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sheetBinding.buttonSendRawCommand.performClick()
                true
            } else {
                false
            }
        }
        sheetBinding.buttonSendRawCommand.setOnClickListener {
            val command = sheetBinding.inputRawCommand.text
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

            val awaitResponse = sheetBinding.checkAwaitResponse.isChecked
            runWithBlePermissions {
                launchBleTask { sendRawCommandInternal(command, awaitResponse) }
            }
        }

        renderDebugSheet()
        debugBottomSheet?.show()
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
                            ensureTrackMonitorRunning()
                            saberStatus = "Connected. Syncing saber data..."
                            trackStatus = "Connected. Syncing track data..."
                            visualsStatus = "Connected. Syncing track visual data..."
                            effectsStatus = ""
                            renderAll()
                            launchBleTask { syncAllDataInternal() }
                        }

                        if (state == ConnectionState.DISCONNECTED && previous != ConnectionState.DISCONNECTED) {
                            stopTrackMonitor()
                            bladeState = null
                            nowPlaying = null
                            selectedTrackPath = null
                            selectedTrackHeaderKey = null
                            currentPresetIndex = null
                            currentVolume = null
                            trackActive = null
                            trackPaused = null
                            trackPositionMs = null
                            trackLengthMs = null
                            presetEntries = emptyList()
                            presetRows = emptyList()
                            trackPaths = emptyList()
                            trackRows = emptyList()
                            expandedPresetHeaderIndices.clear()
                            expandedTrackHeaderKeys.clear()
                            queueScopeHeaderKey = null
                            queueAutoAdvanceEnabled = false
                            queueRepeatEnabled = false
                            shuffleEnabled = false
                            sessionOverrideVisualId = null
                            activeQueueOrder = emptyList()
                            queueHistory.clear()
                            trackPolicy = null
                            trackSessionMode = null
                            trackVisualSelectedId = null
                            trackVisualName = null
                            trackVisualActive = null
                            trackVisualActiveName = null
                            trackVisualPreviewActive = null
                            trackVisualOptions = emptyList()
                            visualAssignmentRules = visualAssignmentStore.loadRules()
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

    private fun ensureTrackMonitorRunning() {
        if (trackMonitorJob?.isActive == true) return
        trackMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(1_000L)
                if (currentConnectionState != ConnectionState.READY) continue
                val shouldPoll = trackActive == true || trackPaused == true || queueAutoAdvanceEnabled
                if (!shouldPoll) continue

                val previousTrack = nowPlaying
                val result = runCommand(
                    command = "gt",
                    awaitResponse = true,
                    successLog = false,
                    logTraffic = false
                )
                if (!result.success) continue

                val state = applyTrackRuntimeState(result.response)
                renderAll()
                if (previousTrack != null && state?.trackActive == false && state.trackPaused != true) {
                    advanceQueuedTrackInternal(previousTrack)
                }
            }
        }
    }

    private fun stopTrackMonitor() {
        trackMonitorJob?.cancel()
        trackMonitorJob = null
    }

    private suspend fun runCommand(
        command: String,
        awaitResponse: Boolean = true,
        timeoutMs: Long = 3_000L,
        retries: Int = 1,
        successLog: Boolean = awaitResponse,
        logTraffic: Boolean = true
    ): SaberBleManager.CommandResult {
        val result = bleManager.sendCommand(
            command = command,
            awaitResponse = awaitResponse,
            timeoutMs = timeoutMs,
            retries = retries,
            logTraffic = logTraffic
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
        rebuildPresetRows(ensureCurrentPresetVisible = true)
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
        ensureCurrentPresetHeaderExpanded()
        rebuildPresetRows(ensureCurrentPresetVisible = false)
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
        ensureCurrentPresetHeaderExpanded()
        rebuildPresetRows(ensureCurrentPresetVisible = false)
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
        if (selectedTrackPath !in trackPaths) {
            selectedTrackPath = nowPlaying?.takeIf(trackPaths::contains)
        }
        val validHeaderKeys = SaberCommandResponseParser.allTrackHeaderKeys(trackPaths)
        if (selectedTrackHeaderKey !in validHeaderKeys) {
            selectedTrackHeaderKey = SaberCommandResponseParser.deepestTrackHeaderKey(selectedTrackPath ?: nowPlaying)
        }
        if (queueScopeHeaderKey !in validHeaderKeys) {
            queueScopeHeaderKey = null
            queueAutoAdvanceEnabled = false
        }
        rebuildTrackRows(ensureCurrentTrackVisible = true)
        rebuildQueueForCurrentScope(preserveCurrent = true)
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
        rebuildTrackRows(ensureCurrentTrackVisible = true)
        trackStatus = if (nowPlaying == null) {
            "Nothing playing"
        } else {
            buildTrackStatus(nowPlaying)
        }
        renderAll()
    }

    private suspend fun playTrackInternal(
        trackPath: String,
        queueScopeKey: String? = selectedTrackHeaderKey ?: SaberCommandResponseParser.deepestTrackHeaderKey(trackPath),
        enableAutoAdvance: Boolean = false
    ) {
        val previousTrack = nowPlaying
        selectedTrackPath = trackPath
        selectedTrackHeaderKey = queueScopeKey ?: SaberCommandResponseParser.deepestTrackHeaderKey(trackPath)
        queueScopeHeaderKey = selectedTrackHeaderKey
        queueAutoAdvanceEnabled = enableAutoAdvance
        trackPaused = false
        trackActive = true
        trackStatus = "Play requested: ${displayTrackName(trackPath)}"
        if (!enableAutoAdvance) {
            rebuildQueueForCurrentScope(anchorTrack = trackPath, preserveCurrent = false)
        }
        if (!previousTrack.isNullOrBlank() && previousTrack != trackPath) {
            pushQueueHistory(previousTrack)
        }
        renderAll()

        applyEffectiveVisualForTrack(trackPath)
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
        if (!result.success) {
            queueAutoAdvanceEnabled = false
            return
        }

        val immediateState = applyTrackRuntimeState(result.response)
        if (immediateState?.visualRejectedReason == "blade_on") {
            queueAutoAdvanceEnabled = false
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
                queueAutoAdvanceEnabled = false
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
        queueAutoAdvanceEnabled = false
        trackPaused = false
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

    private suspend fun playSelectedTrackGroupInternal() {
        val scopeKey = resolveTrackQueueScopeKey()
        val queue = queueTracksForScope(scopeKey)
        if (scopeKey == null || queue.isEmpty()) {
            trackStatus = "Select a folder/category or track first."
            renderAll()
            return
        }

        queueScopeHeaderKey = scopeKey
        queueAutoAdvanceEnabled = true
        val scopeLabel = displayTrackQueueScope(scopeKey)
        val startTrack = when {
            trackPaused == true && nowPlaying in queue -> nowPlaying
            selectedTrackPath in queue -> selectedTrackPath
            nowPlaying in queue -> nowPlaying
            else -> queue.firstOrNull()
        } ?: run {
            trackStatus = "No playable tracks in $scopeLabel"
            renderAll()
            return
        }
        rebuildQueueForCurrentScope(anchorTrack = startTrack, preserveCurrent = false)

        if (trackPaused == true && nowPlaying == startTrack) {
            trackStatus = "Resuming $scopeLabel"
            renderAll()
            resumeTrackInternal()
            return
        }

        if (nowPlaying == startTrack && trackPaused != true) {
            trackStatus = "Queue armed for $scopeLabel"
            renderAll()
            return
        }

        trackStatus = "Playing $scopeLabel (${queue.size} tracks)"
        renderAll()
        playTrackInternal(startTrack, queueScopeKey = scopeKey, enableAutoAdvance = true)
    }

    private suspend fun stepTrackQueueInternal(direction: Int) {
        val scopeKey = resolveTrackQueueScopeKey()
        val queue = activeQueueForScope(scopeKey)
        if (scopeKey == null || queue.isEmpty()) {
            trackStatus = "Select a folder/category or track first."
            renderAll()
            return
        }

        if (direction < 0 && queueHistory.isNotEmpty()) {
            val previousTrack = queueHistory.removeLast()
            queueScopeHeaderKey = scopeKey
            playTrackInternal(
                trackPath = previousTrack,
                queueScopeKey = scopeKey,
                enableAutoAdvance = true
            )
            return
        }

        val currentPath = nowPlaying ?: selectedTrackPath
        val currentIndex = currentPath?.let(queue::indexOf) ?: -1
        var targetIndex = currentIndex + direction
        if (currentIndex < 0) {
            targetIndex = if (direction >= 0) 0 else queue.lastIndex
        }
        if (targetIndex !in queue.indices) {
            if (queueRepeatEnabled) {
                targetIndex = if (direction >= 0) 0 else queue.lastIndex
            } else {
                trackStatus = if (direction >= 0) {
                    "Reached end of ${displayTrackQueueScope(scopeKey)}"
                } else {
                    "Already at the first track in ${displayTrackQueueScope(scopeKey)}"
                }
                renderAll()
                return
            }
        }

        queueScopeHeaderKey = scopeKey
        playTrackInternal(
            trackPath = queue[targetIndex],
            queueScopeKey = scopeKey,
            enableAutoAdvance = true
        )
    }

    private suspend fun toggleTrackPauseInternal() {
        val scopeKey = resolveTrackQueueScopeKey()
        if (nowPlaying == null && trackPaused != true) {
            val fallbackTrack = selectedTrackPath ?: queueTracksForScope(scopeKey).firstOrNull()
            if (fallbackTrack == null) {
                trackStatus = "Select a track or folder to play."
                renderAll()
                return
            }
            playTrackInternal(
                trackPath = fallbackTrack,
                queueScopeKey = scopeKey,
                enableAutoAdvance = queueAutoAdvanceEnabled
            )
            return
        }

        if (trackPaused == true) {
            resumeTrackInternal()
            return
        }

        pauseTrackInternal()
    }

    private suspend fun pauseTrackInternal() {
        val result = runCommand(
            command = "ta",
            awaitResponse = true,
            successLog = false
        )
        if (!result.success) return

        applyTrackRuntimeState(result.response)
        trackStatus = if (trackPaused == true) {
            "Paused ${displayTrackName(nowPlaying)}"
        } else if (nowPlaying != null) {
            buildTrackStatus(nowPlaying)
        } else {
            "Nothing playing"
        }
        renderAll()
    }

    private suspend fun resumeTrackInternal() {
        val result = runCommand(
            command = "tr",
            awaitResponse = true,
            successLog = false
        )
        if (!result.success) return

        applyTrackRuntimeState(result.response)
        trackStatus = if (nowPlaying != null) {
            buildTrackStatus(nowPlaying)
        } else {
            "Nothing playing"
        }
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

    private suspend fun previewTrackVisualInternal() {
        val option = selectedTrackVisualOptionFromMusicUi()
            ?: trackVisualOptions.firstOrNull { it.id == (trackVisualSelectedId ?: 0) }

        if (option == null || option.id == 0) {
            visualsStatus = "Choose a track visual before previewing it."
            renderAll()
            return
        }

        visualsStatus = "Previewing ${option.name}"
        renderAll()

        val result = runCommand(
            command = "tvp ${option.id}",
            awaitResponse = true,
            successLog = false
        )
        if (!result.success) return

        val state = applyTrackRuntimeState(result.response)
        visualsStatus = when (state?.visualPreviewRejectedReason) {
            "blade_on" -> "Preview requires the blade to be off."
            "track_active" -> "Stop track playback before starting a preview."
            else -> "Previewing ${option.name}"
        }
        renderAll()
    }

    private suspend fun stopTrackVisualPreviewInternal() {
        visualsStatus = "Stopping track visual preview"
        renderAll()

        val result = runCommand(
            command = "tvx",
            awaitResponse = true,
            successLog = false
        )
        if (!result.success) return

        applyTrackRuntimeState(result.response)
        visualsStatus = "Track visual preview stopped"
        renderAll()
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
        if (effectsBottomSheet?.isShowing == true) {
            renderEffectsSheet()
        }
        if (debugBottomSheet?.isShowing == true) {
            renderDebugSheet()
        }
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
        pageBinding.buttonOpenEffects.isEnabled = canInteract
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
        applyChipTone(
            pageBinding.textTrackStatusValue,
            when {
                currentConnectionState != ConnectionState.READY -> ChipTone.WARNING
                trackPaused == true -> ChipTone.WARNING
                trackActive == true -> ChipTone.SUCCESS
                else -> ChipTone.NEUTRAL
            }
        )
        pageBinding.textNowPlayingValue.text = activeTrackPath()?.let(::displayTrackName)
            ?: getString(R.string.state_none)
        pageBinding.textNowPlayingValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (activeTrackPath().isNullOrBlank()) R.color.app_text_secondary else R.color.app_primary
            )
        )
        pageBinding.textTrackCountValue.text = if (trackPaths.isEmpty()) {
            getString(R.string.track_count_empty)
        } else {
            "${trackPaths.size} tracks"
        }
        val selectedScopeKey = selectedTrackHeaderKey ?: queueScopeHeaderKey
        pageBinding.textSelectedTrackGroupValue.text = displayTrackQueueScope(selectedScopeKey)
        applyChipTone(
            pageBinding.textSelectedTrackGroupValue,
            if (selectedScopeKey == null) ChipTone.NEUTRAL else ChipTone.PRIMARY
        )
        val queueModeText = when {
            trackPaused == true && shuffleEnabled && queueRepeatEnabled -> "Paused | Shuffle | Repeat"
            trackPaused == true && shuffleEnabled -> "Paused | Shuffle"
            trackPaused == true && queueRepeatEnabled -> "Paused | Repeat"
            trackPaused == true -> "Paused"
            queueAutoAdvanceEnabled && shuffleEnabled && queueRepeatEnabled -> "Queue | Shuffle | Repeat"
            queueAutoAdvanceEnabled && shuffleEnabled -> "Queue | Shuffle"
            queueAutoAdvanceEnabled && queueRepeatEnabled -> "Queue | Repeat"
            queueAutoAdvanceEnabled -> "Queue"
            shuffleEnabled -> "Shuffle"
            queueRepeatEnabled -> "Repeat"
            else -> getString(R.string.queue_mode_single_track)
        }
        pageBinding.textQueueModeValue.text = queueModeText
        applyChipTone(
            pageBinding.textQueueModeValue,
            when {
                trackPaused == true -> ChipTone.WARNING
                queueAutoAdvanceEnabled || shuffleEnabled || queueRepeatEnabled -> ChipTone.PRIMARY
                else -> ChipTone.NEUTRAL
            }
        )

        val elapsedMs = trackPositionMs?.coerceAtLeast(0L)
        val lengthMs = trackLengthMs?.takeIf { it > 0L }
        pageBinding.textElapsedValue.text = formatElapsed(elapsedMs)
        pageBinding.textRemainingValue.text = if (lengthMs == null) {
            getString(R.string.time_unknown)
        } else {
            formatRemaining(elapsedMs, lengthMs)
        }
        pageBinding.textRemainingValue.visibility = if (lengthMs == null) View.GONE else View.VISIBLE
        pageBinding.progressTrack.alpha = if (lengthMs == null) 0.35f else 1.0f
        val progressValue = if (elapsedMs != null && lengthMs != null && lengthMs > 0L) {
            ((elapsedMs.toDouble() / lengthMs.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        pageBinding.progressTrack.progress = progressValue

        trackAdapter.items = trackRows
        val selectedTrackRowIndex = trackRows.indexOfFirst { row ->
            row is SaberCommandResponseParser.TrackRow.Track &&
                row.path == (selectedTrackPath ?: nowPlaying)
        }
        trackAdapter.selectedPosition = if (selectedTrackRowIndex >= 0) {
            selectedTrackRowIndex
        } else {
            trackRows.indexOfFirst { row ->
                row is SaberCommandResponseParser.TrackRow.Header &&
                    row.key == selectedTrackHeaderKey
            }
        }

        val canInteract = currentConnectionState == ConnectionState.READY
        val queueScopeKey = resolveTrackQueueScopeKey()
        val queueTrackCount = activeQueueForScope(queueScopeKey).size
        pageBinding.buttonRefreshTracks.isEnabled = canInteract
        pageBinding.buttonStopTrack.isEnabled = canInteract && (nowPlaying != null || trackPaused == true)
        pageBinding.buttonPlayTrackGroup.isEnabled = canInteract && queueTrackCount > 0
        pageBinding.buttonPreviousTrack.isEnabled = canInteract && queueTrackCount > 0
        pageBinding.buttonTogglePauseTrack.isEnabled =
            canInteract && (nowPlaying != null || trackPaused == true || selectedTrackPath != null)
        pageBinding.buttonNextTrack.isEnabled = canInteract && queueTrackCount > 0
        pageBinding.buttonRepeatQueue.isEnabled = canInteract && queueTrackCount > 0
        pageBinding.buttonShuffleQueue.isEnabled = canInteract && queueTrackCount > 0
        pageBinding.buttonExpandAllTracks.isEnabled = canInteract && trackPaths.isNotEmpty()
        pageBinding.buttonCollapseAllTracks.isEnabled = canInteract && trackPaths.isNotEmpty()
        pageBinding.listTracks.isEnabled = canInteract && trackRows.isNotEmpty()
        pageBinding.buttonTogglePauseTrack.icon = AppCompatResources.getDrawable(
            this,
            if (trackPaused == true) R.drawable.ic_control_play else R.drawable.ic_control_pause
        )
        pageBinding.buttonShuffleQueue.backgroundTintList =
            ContextCompat.getColorStateList(
                this,
                if (shuffleEnabled) R.color.app_primary_tint else R.color.app_surface
            )
        pageBinding.buttonRepeatQueue.backgroundTintList =
            ContextCompat.getColorStateList(
                this,
                if (queueRepeatEnabled) R.color.app_primary_tint else R.color.app_surface
            )

        pageBinding.textTrackVisualPolicyValue.text = displayTrackPolicy(trackPolicy)
        applyChipTone(
            pageBinding.textTrackVisualPolicyValue,
            when (trackPolicy) {
                "visual" -> ChipTone.SUCCESS
                "preserve" -> ChipTone.WARNING
                "auto" -> ChipTone.PRIMARY
                else -> ChipTone.NEUTRAL
            }
        )
        pageBinding.textTrackVisualRuntimeValue.text = buildTrackVisualRuntimeSummary()
        applyChipTone(
            pageBinding.textTrackVisualRuntimeValue,
            when {
                trackVisualPreviewActive == true -> ChipTone.SUCCESS
                trackVisualActive == true -> ChipTone.SUCCESS
                trackSessionMode == "preserve" -> ChipTone.WARNING
                trackSessionMode == "visual" -> ChipTone.PRIMARY
                else -> ChipTone.NEUTRAL
            }
        )
        pageBinding.textVisualAssignmentSummaryValue.text = buildVisualAssignmentSummary()

        trackVisualAdapter.clear()
        trackVisualAdapter.addAll(trackVisualOptions.map { option -> option.name })
        trackVisualAdapter.notifyDataSetChanged()

        val selectedIndex = trackVisualOptions.indexOfFirst { option ->
            option.id == (trackVisualSelectedId ?: 0)
        }
        if (selectedIndex >= 0 && pageBinding.spinnerTrackVisuals.selectedItemPosition != selectedIndex) {
            pageBinding.spinnerTrackVisuals.setSelection(selectedIndex, false)
        }

        trackVisualOverrideAdapter.clear()
        trackVisualOverrideAdapter.add(getString(R.string.visual_override_use_mappings))
        trackVisualOverrideAdapter.addAll(trackVisualOptions.filter { it.id != 0 }.map { it.name })
        trackVisualOverrideAdapter.notifyDataSetChanged()
        val overrideSelection = trackVisualOverrideSelectionForId(sessionOverrideVisualId)
        if (pageBinding.spinnerTrackVisualOverride.selectedItemPosition != overrideSelection) {
            pageBinding.spinnerTrackVisualOverride.setSelection(overrideSelection, false)
        }

        pageBinding.buttonRefreshTrackVisuals.isEnabled = canInteract
        pageBinding.buttonPolicyAuto.isEnabled = canInteract
        pageBinding.buttonPolicySaber.isEnabled = canInteract
        pageBinding.buttonPolicyMusic.isEnabled = canInteract
        pageBinding.buttonApplyTrackVisual.isEnabled = canInteract && trackVisualOptions.isNotEmpty()
        pageBinding.buttonClearTrackVisual.isEnabled = canInteract && (trackVisualSelectedId ?: 0) != 0
        pageBinding.buttonPreviewTrackVisual.isEnabled = canInteract && trackVisualOptions.isNotEmpty()
        pageBinding.buttonStopTrackVisualPreview.isEnabled = canInteract && trackVisualPreviewActive == true
        pageBinding.buttonAssignTrackVisual.isEnabled = canInteract && trackVisualOptions.isNotEmpty()
        pageBinding.buttonClearVisualAssignment.isEnabled = canInteract

        when (trackPolicy) {
            "visual" -> pageBinding.toggleTrackPolicy.check(R.id.buttonPolicyMusic)
            "preserve" -> pageBinding.toggleTrackPolicy.check(R.id.buttonPolicySaber)
            else -> pageBinding.toggleTrackPolicy.check(R.id.buttonPolicyAuto)
        }
    }

    private fun renderEffectsSheet() {
        val pageBinding = effectsSheetBinding ?: return
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

    private fun renderDebugSheet() {
        val pageBinding = debugSheetBinding ?: return
        pageBinding.textLog.text = buildLogText()
        updateRawCommandControls(pageBinding)
        pageBinding.scrollLog.post {
            pageBinding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateRawCommandControls(pageBinding: SheetDebugBinding) {
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
        if (debugBottomSheet?.isShowing == true) {
            renderDebugSheet()
        }
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
        if (state.trackActive == false && state.trackPaused != true) {
            nowPlaying = null
        } else if (state.nowPlaying != null) {
            nowPlaying = state.nowPlaying
        }
        state.trackActive?.let { trackActive = it }
        state.trackPaused?.let { trackPaused = it }
        state.positionMs?.let { trackPositionMs = it }
        state.lengthMs?.let { trackLengthMs = it }
        if (state.nowPlaying != null) {
            selectedTrackPath = state.nowPlaying
            if (selectedTrackHeaderKey == null) {
                selectedTrackHeaderKey = SaberCommandResponseParser.deepestTrackHeaderKey(state.nowPlaying)
            }
        }
        state.policy?.let { trackPolicy = it }
        state.sessionMode?.let { trackSessionMode = it }
        state.visualSelectedId?.let { trackVisualSelectedId = it }
        state.visualName?.let { trackVisualName = it }
        state.visualActive?.let { trackVisualActive = it }
        state.visualActiveName?.let { trackVisualActiveName = it }
        state.visualPreviewActive?.let { trackVisualPreviewActive = it }
        if (trackPaths.isNotEmpty()) {
            rebuildTrackRows(ensureCurrentTrackVisible = true)
            rebuildQueueForCurrentScope(preserveCurrent = true)
        }
        return state
    }

    private fun togglePresetHeader(headerIndex: Int) {
        if (!expandedPresetHeaderIndices.add(headerIndex)) {
            expandedPresetHeaderIndices.remove(headerIndex)
        }
        rebuildPresetRows(ensureCurrentPresetVisible = false)
        renderAll()
    }

    private fun toggleTrackHeader(headerKey: String) {
        if (!expandedTrackHeaderKeys.add(headerKey)) {
            expandedTrackHeaderKeys.remove(headerKey)
        }
        rebuildTrackRows(ensureCurrentTrackVisible = false)
        renderAll()
    }

    private fun expandAllTrackHeaders() {
        expandedTrackHeaderKeys.clear()
        expandedTrackHeaderKeys.addAll(SaberCommandResponseParser.allTrackHeaderKeys(trackPaths))
        rebuildTrackRows(ensureCurrentTrackVisible = false)
        renderAll()
    }

    private fun collapseAllTrackHeaders() {
        expandedTrackHeaderKeys.clear()
        rebuildTrackRows(ensureCurrentTrackVisible = false)
        renderAll()
    }

    private fun rebuildPresetRows(ensureCurrentPresetVisible: Boolean) {
        if (ensureCurrentPresetVisible) {
            ensureCurrentPresetHeaderExpanded()
        }
        presetRows = SaberCommandResponseParser.buildPresetRows(
            entries = presetEntries,
            expandedHeaderIndices = expandedPresetHeaderIndices
        )
    }

    private fun ensureCurrentPresetHeaderExpanded() {
        val headerIndex = headerIndexForPreset(currentPresetIndex) ?: return
        expandedPresetHeaderIndices.add(headerIndex)
    }

    private fun rebuildTrackRows(ensureCurrentTrackVisible: Boolean) {
        if (ensureCurrentTrackVisible) {
            ensureCurrentTrackHeadersExpanded()
        }
        trackRows = SaberCommandResponseParser.buildTrackRows(
            trackPaths = trackPaths,
            expandedHeaderKeys = expandedTrackHeaderKeys
        )
    }

    private fun ensureCurrentTrackHeadersExpanded() {
        for (key in SaberCommandResponseParser.trackHeaderKeysForPath(activeTrackPath())) {
            expandedTrackHeaderKeys.add(key)
        }
    }

    private fun resolveTrackQueueScopeKey(): String? {
        val preferredKeys = listOfNotNull(
            selectedTrackHeaderKey,
            queueScopeHeaderKey,
            SaberCommandResponseParser.deepestTrackHeaderKey(activeTrackPath())
        )
        for (key in preferredKeys) {
            if (queueTracksForScope(key).isNotEmpty()) {
                return key
            }
        }
        return null
    }

    private fun queueTracksForScope(scopeKey: String?): List<String> {
        return SaberCommandResponseParser.tracksForHeader(trackPaths, scopeKey)
    }

    private fun activeQueueForScope(scopeKey: String?): List<String> {
        if (scopeKey == null) return emptyList()
        val baseQueue = queueTracksForScope(scopeKey)
        if (activeQueueOrder.isEmpty() || queueScopeHeaderKey != scopeKey) {
            return baseQueue
        }
        return activeQueueOrder.filter(baseQueue::contains)
    }

    private fun rebuildQueueForCurrentScope(
        anchorTrack: String? = activeTrackPath(),
        preserveCurrent: Boolean
    ) {
        val scopeKey = resolveTrackQueueScopeKey()
        if (scopeKey == null) {
            activeQueueOrder = emptyList()
            if (!preserveCurrent) {
                queueHistory.clear()
            }
            return
        }
        val queue = queueTracksForScope(scopeKey)
        if (queue.isEmpty()) {
            activeQueueOrder = emptyList()
            queueHistory.clear()
            return
        }

        activeQueueOrder = buildQueueOrder(
            scopeKey = scopeKey,
            queue = queue,
            anchorTrack = anchorTrack
        )
        queueScopeHeaderKey = scopeKey
        if (!preserveCurrent) {
            queueHistory.clear()
        }
    }

    private fun buildQueueOrder(
        scopeKey: String,
        queue: List<String>,
        anchorTrack: String?
    ): List<String> {
        if (!shuffleEnabled || queue.size <= 1) return queue

        val stableSeed = scopeKey.hashCode().toLong()
        val shuffled = queue.toMutableList()
        val anchor = anchorTrack?.takeIf(shuffled::contains)
        if (anchor != null) {
            shuffled.remove(anchor)
        }
        shuffled.shuffle(Random(stableSeed))
        return buildList {
            if (anchor != null) add(anchor)
            addAll(shuffled)
        }
    }

    private fun pushQueueHistory(trackPath: String) {
        if (queueHistory.lastOrNull() == trackPath) return
        queueHistory.add(trackPath)
        if (queueHistory.size > 50) {
            queueHistory.removeAt(0)
        }
    }

    private suspend fun advanceQueuedTrackInternal(previousTrackPath: String) {
        val scopeKey = queueScopeHeaderKey ?: resolveTrackQueueScopeKey()
        val queue = activeQueueForScope(scopeKey)
        if (scopeKey == null || queue.isEmpty()) {
            queueAutoAdvanceEnabled = false
            renderAll()
            return
        }

        pushQueueHistory(previousTrackPath)
        val currentIndex = queue.indexOf(previousTrackPath)
        var nextIndex = currentIndex + 1
        if (currentIndex < 0) {
            nextIndex = 0
        }
        if (nextIndex !in queue.indices) {
            if (queueRepeatEnabled) {
                nextIndex = 0
            } else {
                queueAutoAdvanceEnabled = false
                trackStatus = "Finished ${displayTrackQueueScope(scopeKey)}"
                renderAll()
                return
            }
        }

        trackStatus = "Continuing ${displayTrackQueueScope(scopeKey)}"
        renderAll()
        playTrackInternal(
            trackPath = queue[nextIndex],
            queueScopeKey = scopeKey,
            enableAutoAdvance = true
        )
    }

    private fun headerIndexForPreset(presetIndex: Int?): Int? {
        val targetIndex = presetIndex ?: return null
        var currentHeaderIndex: Int? = null
        for (entry in presetEntries) {
            if (entry.isHeader) {
                currentHeaderIndex = entry.index
                continue
            }
            if (entry.index == targetIndex) {
                return currentHeaderIndex
            }
        }
        return null
    }

    private fun selectedTrackVisualOptionFromMusicUi(): SaberCommandResponseParser.TrackVisualOption? {
        val pageBinding = tracksPageBinding ?: return null
        return pageBinding.spinnerTrackVisuals.selectedItemPosition
            .takeIf { it in trackVisualOptions.indices }
            ?.let(trackVisualOptions::get)
    }

    private fun trackVisualOverrideIdForPosition(position: Int): Int? {
        if (position <= 0) return null
        return trackVisualOptions.filter { it.id != 0 }
            .getOrNull(position - 1)
            ?.id
    }

    private fun trackVisualOverrideSelectionForId(visualId: Int?): Int {
        if ((visualId ?: 0) <= 0) return 0
        val options = trackVisualOptions.filter { it.id != 0 }
        val index = options.indexOfFirst { it.id == visualId }
        return if (index >= 0) index + 1 else 0
    }

    private fun selectedAssignmentScopeFromUi(): VisualAssignmentScope {
        return when (tracksPageBinding?.spinnerVisualAssignmentScope?.selectedItemPosition ?: 0) {
            0 -> VisualAssignmentScope.TRACK
            1 -> VisualAssignmentScope.ALBUM
            2 -> VisualAssignmentScope.CATEGORY
            else -> VisualAssignmentScope.DEFAULT
        }
    }

    private fun assignVisualRuleFromUi(option: SaberCommandResponseParser.TrackVisualOption) {
        val scope = selectedAssignmentScopeFromUi()
        val trackPath = activeTrackPath() ?: selectedTrackPath
        val scopeKey = VisualAssignmentResolver.scopeKeyForTrack(scope, trackPath)
        if (scopeKey.isNullOrBlank()) {
            visualsStatus = "Select a track or folder before assigning a visual."
            renderAll()
            return
        }
        visualAssignmentRules = visualAssignmentRules
            .filterNot { it.scope == scope && it.scopeKey == scopeKey }
            .plus(
                VisualAssignmentRule(
                    scope = scope,
                    scopeKey = scopeKey,
                    visualId = option.id
                )
            )
        visualAssignmentStore.saveRules(visualAssignmentRules)
        visualsStatus = "Mapped ${option.name} to ${displayAssignmentScope(scope, scopeKey)}"
        renderAll()
    }

    private fun clearVisualRuleFromUi() {
        val scope = selectedAssignmentScopeFromUi()
        val trackPath = activeTrackPath() ?: selectedTrackPath
        val scopeKey = VisualAssignmentResolver.scopeKeyForTrack(scope, trackPath)
        if (scopeKey.isNullOrBlank()) {
            visualsStatus = "No assignment scope available to clear."
            renderAll()
            return
        }
        val updatedRules = visualAssignmentRules.filterNot { it.scope == scope && it.scopeKey == scopeKey }
        if (updatedRules.size == visualAssignmentRules.size) {
            visualsStatus = "No saved visual mapping for ${displayAssignmentScope(scope, scopeKey)}"
            renderAll()
            return
        }
        visualAssignmentRules = updatedRules
        visualAssignmentStore.saveRules(visualAssignmentRules)
        visualsStatus = "Cleared mapping for ${displayAssignmentScope(scope, scopeKey)}"
        renderAll()
    }

    private suspend fun applyEffectiveVisualForTrack(trackPath: String) {
        val resolution = VisualAssignmentResolver.resolve(
            trackPath = trackPath,
            overrideVisualId = sessionOverrideVisualId,
            rules = visualAssignmentRules
        )
        val targetVisualId = resolution.visualId ?: return
        if (trackVisualSelectedId == targetVisualId) return
        val option = trackVisualOptions.firstOrNull { it.id == targetVisualId } ?: return
        setTrackVisualInternal(option)
    }

    private fun buildVisualAssignmentSummary(): String {
        val trackPath = activeTrackPath() ?: selectedTrackPath
        val resolution = VisualAssignmentResolver.resolve(
            trackPath = trackPath,
            overrideVisualId = sessionOverrideVisualId,
            rules = visualAssignmentRules
        )
        val visualName = when {
            (sessionOverrideVisualId ?: 0) > 0 ->
                trackVisualOptions.firstOrNull { it.id == sessionOverrideVisualId }?.name
                    ?: "Visual #$sessionOverrideVisualId"
            resolution.visualId != null ->
                trackVisualOptions.firstOrNull { it.id == resolution.visualId }?.name
                    ?: "Visual #${resolution.visualId}"
            else -> displaySelectedTrackVisual()
        }

        return when {
            (sessionOverrideVisualId ?: 0) > 0 -> "Session override: $visualName"
            resolution.matchedScope != null && resolution.matchedScopeKey != null ->
                "Mapped ${displayAssignmentScope(resolution.matchedScope, resolution.matchedScopeKey)} -> $visualName"
            else -> getString(R.string.visual_assignment_default_summary)
        }
    }

    private fun displayAssignmentScope(scope: VisualAssignmentScope, scopeKey: String): String {
        return when (scope) {
            VisualAssignmentScope.TRACK -> "track ${displayTrackName(scopeKey)}"
            VisualAssignmentScope.ALBUM -> "album ${SaberCommandResponseParser.trackHeaderLabel(scopeKey) ?: scopeKey}"
            VisualAssignmentScope.CATEGORY -> "category ${SaberCommandResponseParser.trackHeaderLabel(scopeKey) ?: scopeKey}"
            VisualAssignmentScope.DEFAULT -> "default playback"
        }
    }

    private fun buildTrackStatus(trackPath: String?): String {
        val base = if (trackPaused == true) {
            "Paused ${displayTrackName(trackPath)}"
        } else {
            "Playing ${displayTrackName(trackPath)}"
        }
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
        val modeText = when (trackVisualPreviewActive) {
            true -> trackVisualActiveName?.let { "Preview: $it" } ?: getString(R.string.track_session_preview)
            else -> displayTrackSessionMode(trackSessionMode)
        }
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

    private fun formatElapsed(positionMs: Long?): String {
        val seconds = ((positionMs ?: 0L) / 1000L).coerceAtLeast(0L)
        return formatClock(seconds)
    }

    private fun formatRemaining(positionMs: Long?, lengthMs: Long?): String {
        val remainingSeconds = when {
            lengthMs == null || lengthMs <= 0L -> 0L
            else -> ((lengthMs - (positionMs ?: 0L)).coerceAtLeast(0L) / 1000L)
        }
        return "-${formatClock(remainingSeconds)}"
    }

    private fun formatClock(totalSeconds: Long): String {
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun displayTrackQueueScope(scopeKey: String?): String {
        return SaberCommandResponseParser.trackHeaderLabel(scopeKey)
            ?: getString(R.string.music_scope_none)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

}
