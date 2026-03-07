package com.eseger70.sabercontroller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eseger70.sabercontroller.ble.SaberBleManager.ConnectionState
import com.eseger70.sabercontroller.ble.SaberCommandResponseParser
import com.eseger70.sabercontroller.ble.SaberBleManager
import com.eseger70.sabercontroller.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: SaberBleManager
    private lateinit var scanDeviceAdapter: ArrayAdapter<String>
    private lateinit var trackAdapter: ArrayAdapter<String>

    private val discoveredDeviceLabels = mutableListOf<String>()
    private val discoveredDeviceAddresses = mutableListOf<String>()
    private val logLines = ArrayDeque<String>()
    private val trackPaths = mutableListOf<String>()
    private var pendingPermissionAction: (() -> Unit)? = null

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
        scanDeviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            discoveredDeviceLabels
        )
        trackAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_activated_1,
            trackPaths
        )
        binding.listDiscoveredDevices.adapter = scanDeviceAdapter
        binding.listDiscoveredDevices.setOnItemClickListener { _, _, position, _ ->
            val address = discoveredDeviceAddresses.getOrNull(position) ?: return@setOnItemClickListener
            runWithBlePermissions {
                bleManager.connectToDiscoveredDevice(address)
            }
        }
        binding.listTracks.adapter = trackAdapter
        binding.listTracks.setOnItemClickListener { _, _, position, _ ->
            val trackPath = trackPaths.getOrNull(position) ?: return@setOnItemClickListener
            runWithBlePermissions {
                playTrack(trackPath)
            }
        }

        wireUi()
        collectBleState()
        updateDiscoveredDevices(emptyList())
        updateTrackList(emptyList())
        updateNowPlaying(null)
        updateUiForConnectionState(ConnectionState.DISCONNECTED)

        appendLog("Ready. Tap Connect to scan for FEASYCOM")
    }

    override fun onDestroy() {
        bleManager.close()
        super.onDestroy()
    }

    private fun wireUi() {
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

        binding.buttonOn.setOnClickListener {
            runWithBlePermissions {
                setBladePower(isOn = true)
            }
        }

        binding.buttonOff.setOnClickListener {
            runWithBlePermissions {
                setBladePower(isOn = false)
            }
        }

        binding.buttonGetState.setOnClickListener {
            runWithBlePermissions {
                refreshBladeState()
            }
        }

        binding.buttonRefreshTracks.setOnClickListener {
            runWithBlePermissions {
                refreshTrackList()
            }
        }

        binding.buttonRefreshNowPlaying.setOnClickListener {
            runWithBlePermissions {
                refreshNowPlaying()
            }
        }

        binding.buttonStopTrack.setOnClickListener {
            runWithBlePermissions {
                stopTrack()
            }
        }
    }

    private fun collectBleState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    bleManager.connectionState.collect { state ->
                        binding.textConnectionStateValue.text = state.name
                        updateUiForConnectionState(state)
                    }
                }
                launch {
                    bleManager.logs.collect { line ->
                        appendLog(line)
                    }
                }
                launch {
                    bleManager.discoveredDevices.collect { devices ->
                        updateDiscoveredDevices(devices)
                    }
                }
            }
        }
    }

    private fun launchCommand(
        command: String,
        awaitResponse: Boolean,
        timeoutMs: Long = 3_000L,
        retries: Int = 1,
        onSuccess: ((String?) -> Unit)? = null
    ) {
        lifecycleScope.launch {
            val result = bleManager.sendCommand(
                command = command,
                awaitResponse = awaitResponse,
                timeoutMs = timeoutMs,
                retries = retries
            )
            if (!result.success) {
                appendLog("Command '${result.command}' failed: ${result.error ?: "unknown"}")
                return@launch
            }

            if (awaitResponse) {
                appendLog("Command '${result.command}' completed")
            }
            onSuccess?.invoke(result.response)
        }
    }

    private fun setBladePower(isOn: Boolean) {
        val command = if (isOn) "on" else "off"
        launchCommand(command = command, awaitResponse = true) {
            refreshBladeState()
        }
    }

    private fun refreshBladeState() {
        launchCommand(command = "get_on", awaitResponse = true) { response ->
            val bladeState = SaberCommandResponseParser.parseBladeState(response)
            if (bladeState == null) {
                appendLog("Unable to parse get_on response")
            } else {
                binding.textLastStateValue.text = if (bladeState) "ON (1)" else "OFF (0)"
            }
        }
    }

    private fun refreshTrackList() {
        launchCommand(
            command = "list_tracks",
            awaitResponse = true,
            timeoutMs = 6_000L
        ) { response ->
            val parsedTracks = SaberCommandResponseParser.parseTrackPaths(response)
            updateTrackList(parsedTracks)
            appendLog("Loaded ${parsedTracks.size} tracks")
        }
    }

    private fun refreshNowPlaying() {
        launchCommand(command = "get_track", awaitResponse = true) { response ->
            updateNowPlaying(SaberCommandResponseParser.parseNowPlaying(response))
        }
    }

    private fun playTrack(trackPath: String) {
        launchCommand(
            command = "play_track $trackPath",
            awaitResponse = true,
            timeoutMs = 4_000L
        ) { response ->
            val nowPlaying = SaberCommandResponseParser.parseNowPlaying(response)
            if (nowPlaying == null && !response.isNullOrBlank()) {
                appendLog("Unable to confirm play_track response for $trackPath")
            } else {
                updateNowPlaying(nowPlaying ?: trackPath)
                appendLog("Track selected: $trackPath")
            }
        }
    }

    private fun stopTrack() {
        launchCommand(command = "stop_track", awaitResponse = true) {
            updateNowPlaying(null)
        }
    }

    private fun updateTrackList(newTracks: List<String>) {
        trackPaths.clear()
        trackPaths.addAll(newTracks)
        trackAdapter.notifyDataSetChanged()

        binding.textTrackCountValue.text = if (newTracks.isEmpty()) {
            getString(R.string.track_count_empty)
        } else {
            "${newTracks.size} tracks"
        }

        if (newTracks.isEmpty()) {
            binding.listTracks.clearChoices()
            binding.listTracks.invalidateViews()
        }

        val ready = bleManager.connectionState.value == ConnectionState.READY
        binding.listTracks.isEnabled = ready && newTracks.isNotEmpty()

        val currentNowPlaying = binding.textNowPlayingValue.text
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != getString(R.string.state_none) }
        updateNowPlaying(currentNowPlaying)
    }

    private fun updateDiscoveredDevices(devices: List<SaberBleManager.ScannedDevice>) {
        discoveredDeviceAddresses.clear()
        discoveredDeviceLabels.clear()

        for (device in devices) {
            discoveredDeviceAddresses.add(device.address)
            discoveredDeviceLabels.add(buildDiscoveredDeviceLabel(device))
        }
        scanDeviceAdapter.notifyDataSetChanged()

        binding.textNearbyDeviceCountValue.text = if (devices.isEmpty()) {
            getString(R.string.nearby_device_count_empty)
        } else {
            "${devices.size} devices"
        }

        val state = bleManager.connectionState.value
        binding.listDiscoveredDevices.isEnabled =
            (state == ConnectionState.SCANNING || state == ConnectionState.DISCONNECTED) &&
                devices.isNotEmpty()
    }

    private fun buildDiscoveredDeviceLabel(device: SaberBleManager.ScannedDevice): String {
        val primaryName = device.deviceName
            ?: device.scanRecordName
            ?: getString(R.string.nearby_device_name_unknown)
        val secondaryName = device.scanRecordName
            ?.takeIf { it != device.deviceName }
            ?.let { " scan=$it" }
            .orEmpty()

        return "$primaryName$secondaryName | ${device.address} | RSSI ${device.rssi}"
    }

    private fun updateNowPlaying(trackPath: String?) {
        val normalizedTrack = trackPath?.takeIf { it.isNotBlank() }
        binding.textNowPlayingValue.text = normalizedTrack ?: getString(R.string.state_none)

        val selectedIndex = normalizedTrack?.let(trackPaths::indexOf) ?: -1
        if (selectedIndex >= 0) {
            binding.listTracks.setItemChecked(selectedIndex, true)
        } else {
            binding.listTracks.clearChoices()
        }
        binding.listTracks.invalidateViews()
    }

    private fun updateUiForConnectionState(state: ConnectionState) {
        val ready = state == ConnectionState.READY
        val disconnected = state == ConnectionState.DISCONNECTED

        binding.buttonConnect.isEnabled = disconnected
        binding.buttonDisconnect.isEnabled = !disconnected
        binding.buttonOn.isEnabled = ready
        binding.buttonOff.isEnabled = ready
        binding.buttonGetState.isEnabled = ready
        binding.buttonRefreshTracks.isEnabled = ready
        binding.buttonRefreshNowPlaying.isEnabled = ready
        binding.buttonStopTrack.isEnabled = ready
        binding.listTracks.isEnabled = ready && trackPaths.isNotEmpty()
        binding.listDiscoveredDevices.isEnabled =
            (state == ConnectionState.SCANNING || state == ConnectionState.DISCONNECTED) &&
                discoveredDeviceAddresses.isNotEmpty()
    }

    private fun appendLog(line: String) {
        val maxLines = 350
        if (logLines.size >= maxLines) {
            logLines.removeFirst()
        }
        logLines.addLast(line)
        binding.textLog.text = logLines.joinToString(separator = "\n")
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
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
}
