package com.eseger70.sabercontroller.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SaberBleManager(
    context: Context,
    private val targetDeviceName: String = "FEASYCOM"
) {
    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        DISCOVERING,
        READY
    }

    data class CommandResult(
        val command: String,
        val success: Boolean,
        val response: String? = null,
        val error: String? = null,
        val attempts: Int = 0
    )

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var activeScanCallback: ScanCallback? = null
    private val parser = FramedResponseParser()

    private val commandMutex = Mutex()
    @Volatile private var pendingResponse: CompletableDeferred<String>? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs = _logs.asSharedFlow()

    private val _frames = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val frames = _frames.asSharedFlow()

    private val scanTimeoutRunnable = Runnable {
        if (_connectionState.value == ConnectionState.SCANNING) {
            stopScan()
            updateState(ConnectionState.DISCONNECTED)
            log("Scan timed out before finding $targetDeviceName")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                log("Connection change status=$status newState=$newState")
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected to ${gatt.device.address}")
                    updateState(ConnectionState.DISCOVERING)
                    if (!gatt.discoverServices()) {
                        log("Failed to start service discovery")
                        disconnect()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected")
                    resetGattState()
                    updateState(ConnectionState.DISCONNECTED)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: status=$status")
                return
            }

            writeCharacteristic = findCharacteristic(gatt.services, WRITE_UUID)
            notifyCharacteristic = findCharacteristic(gatt.services, NOTIFY_UUID)

            if (writeCharacteristic == null || notifyCharacteristic == null) {
                log("Required characteristics not found. write=${writeCharacteristic != null} notify=${notifyCharacteristic != null}")
                dumpServices(gatt.services)
                return
            }

            log("Characteristics resolved. Enabling notifications")
            if (!enableNotifications(gatt, notifyCharacteristic!!)) {
                log("Failed to enable notifications")
                return
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    updateState(ConnectionState.READY)
                    log("Notifications enabled; ready")
                } else {
                    log("Descriptor write failed: status=$status")
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleIncoming(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncoming(value)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToTarget(scanTimeoutMs: Long = 15_000L) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            log("Bluetooth adapter unavailable")
            return
        }
        if (!adapter.isEnabled) {
            log("Bluetooth is disabled")
            return
        }
        if (_connectionState.value == ConnectionState.READY ||
            _connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.DISCOVERING
        ) {
            log("Already connecting/connected")
            return
        }

        disconnect()
        parser.clear()
        updateState(ConnectionState.SCANNING)

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            updateState(ConnectionState.DISCONNECTED)
            log("BLE scanner unavailable")
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val scanName = result.scanRecord?.deviceName
                if (!matchesTarget(device.name, scanName)) return

                log("Found target ${device.address} (${device.name ?: scanName ?: "unknown"})")
                stopScan()
                connectGatt(device)
            }

            override fun onScanFailed(errorCode: Int) {
                log("Scan failed with code=$errorCode")
                stopScan()
                updateState(ConnectionState.DISCONNECTED)
            }
        }

        activeScanCallback = callback

        val filters = listOf(
            ScanFilter.Builder()
                .setDeviceName(targetDeviceName)
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        log("Starting scan for $targetDeviceName")
        scanner.startScan(filters, settings, callback)
        mainHandler.postDelayed(scanTimeoutRunnable, scanTimeoutMs)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        pendingResponse?.cancel()
        pendingResponse = null
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        resetGattState()
        updateState(ConnectionState.DISCONNECTED)
    }

    suspend fun sendCommand(
        command: String,
        awaitResponse: Boolean = true,
        timeoutMs: Long = 2_500L,
        retries: Int = 1
    ): CommandResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return CommandResult(
                command = command,
                success = false,
                error = "Command is empty"
            )
        }

        return commandMutex.withLock {
            if (_connectionState.value != ConnectionState.READY) {
                return@withLock CommandResult(
                    command = trimmed,
                    success = false,
                    error = "Not connected"
                )
            }

            val attemptsTotal = retries + 1
            repeat(attemptsTotal) { index ->
                val attempt = index + 1
                val responseLatch = if (awaitResponse) CompletableDeferred<String>() else null
                pendingResponse = responseLatch

                val writeOk = writeCommand("$trimmed\r\n")
                if (!writeOk) {
                    pendingResponse = null
                    log("Write failed for '$trimmed' attempt=$attempt")
                    return@withLock CommandResult(
                        command = trimmed,
                        success = false,
                        error = "Write failed",
                        attempts = attempt
                    )
                }

                log("TX >> $trimmed")

                if (responseLatch == null) {
                    pendingResponse = null
                    return@withLock CommandResult(
                        command = trimmed,
                        success = true,
                        attempts = attempt
                    )
                }

                try {
                    val response = withTimeout(timeoutMs) { responseLatch.await() }
                    pendingResponse = null
                    return@withLock CommandResult(
                        command = trimmed,
                        success = true,
                        response = response,
                        attempts = attempt
                    )
                } catch (_: TimeoutCancellationException) {
                    pendingResponse = null
                    log("Timeout waiting for response to '$trimmed' attempt=$attempt")
                }
            }

            CommandResult(
                command = trimmed,
                success = false,
                error = "Timed out waiting for response",
                attempts = attemptsTotal
            )
        }
    }

    fun close() {
        disconnect()
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        updateState(ConnectionState.CONNECTING)
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            device.createBond()
            log("Requested pairing for ${device.address}")
        }

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        val callback = activeScanCallback ?: return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.stopScan(callback)
        activeScanCallback = null
    }

    private fun matchesTarget(deviceName: String?, scanRecordName: String?): Boolean {
        return targetDeviceName.equals(deviceName, ignoreCase = true) ||
            targetDeviceName.equals(scanRecordName, ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val notifySet = gatt.setCharacteristicNotification(characteristic, true)
        if (!notifySet) {
            return false
        }

        val ccc = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
        if (ccc == null) {
            log("CCC descriptor missing; continuing without descriptor write")
            updateState(ConnectionState.READY)
            return true
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(ccc)
            }
        }
    }

    private fun findCharacteristic(
        services: List<BluetoothGattService>,
        uuid: UUID
    ): BluetoothGattCharacteristic? {
        for (service in services) {
            for (characteristic in service.characteristics) {
                if (characteristic.uuid == uuid) return characteristic
            }
        }
        return null
    }

    private fun handleIncoming(value: ByteArray?) {
        val bytes = value ?: return
        if (bytes.isEmpty()) return
        val chunk = bytes.toString(StandardCharsets.UTF_8)
        log("RX << ${escapeForLog(chunk)}")

        val frames = parser.consume(chunk)
        if (frames.isEmpty()) return

        for (frame in frames) {
            _frames.tryEmit(frame)
            log("FRAME << ${escapeForLog(frame)}")
            val waiter = pendingResponse
            if (waiter != null && !waiter.isCompleted) {
                waiter.complete(frame)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(payload: String): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = writeCharacteristic ?: return false
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    private fun resetGattState() {
        writeCharacteristic = null
        notifyCharacteristic = null
        bluetoothGatt = null
        parser.clear()
    }

    private fun dumpServices(services: List<BluetoothGattService>) {
        for (service in services) {
            log("Service ${service.uuid}")
            for (characteristic in service.characteristics) {
                log("  Char ${characteristic.uuid}")
            }
        }
    }

    private fun updateState(state: ConnectionState) {
        _connectionState.value = state
        log("State -> $state")
    }

    private fun log(message: String) {
        val line = "${timestampFormat.format(Date())} $message"
        _logs.tryEmit(line)
    }

    private fun escapeForLog(value: String): String {
        return value
            .replace("\r", "\\r")
            .replace("\n", "\\n")
    }

    companion object {
        private val WRITE_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
