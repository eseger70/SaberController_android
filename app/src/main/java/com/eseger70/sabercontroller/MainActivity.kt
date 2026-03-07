package com.eseger70.sabercontroller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eseger70.sabercontroller.ble.SaberBleManager
import com.eseger70.sabercontroller.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: SaberBleManager

    private val logLines = ArrayDeque<String>()
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
        wireUi()
        collectBleState()

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
                sendCommand(command = "on", awaitResponse = false)
            }
        }

        binding.buttonOff.setOnClickListener {
            runWithBlePermissions {
                sendCommand(command = "off", awaitResponse = false)
            }
        }

        binding.buttonGetState.setOnClickListener {
            runWithBlePermissions {
                sendCommand(command = "get_on", awaitResponse = true)
            }
        }
    }

    private fun collectBleState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    bleManager.connectionState.collect { state ->
                        binding.textConnectionStateValue.text = state.name
                    }
                }
                launch {
                    bleManager.logs.collect { line ->
                        appendLog(line)
                    }
                }
                launch {
                    bleManager.frames.collect { frame ->
                        updateStateValueFromFrame(frame)
                    }
                }
            }
        }
    }

    private fun sendCommand(command: String, awaitResponse: Boolean) {
        lifecycleScope.launch {
            val result = bleManager.sendCommand(
                command = command,
                awaitResponse = awaitResponse,
                timeoutMs = 3_000L,
                retries = 1
            )
            if (!result.success) {
                appendLog("Command '${result.command}' failed: ${result.error ?: "unknown"}")
                return@launch
            }

            if (awaitResponse) {
                appendLog("Command '${result.command}' response received")
                if (!result.response.isNullOrBlank()) {
                    updateStateValueFromFrame(result.response)
                }
            }
        }
    }

    private fun updateStateValueFromFrame(frame: String) {
        val line = frame
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it == "0" || it == "1" }

        if (line != null) {
            binding.textLastStateValue.text = if (line == "1") "ON (1)" else "OFF (0)"
        }
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

