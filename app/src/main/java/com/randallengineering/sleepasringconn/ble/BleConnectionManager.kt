package com.randallengineering.sleepasringconn.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.randallengineering.sleepasringconn.data.AppDatabase
import com.randallengineering.sleepasringconn.data.DeviceStatusEntity
import com.randallengineering.sleepasringconn.data.EpochEntity
import com.randallengineering.sleepasringconn.protocol.*
import com.randallengineering.sleepasringconn.sleepasandroid.SleepAsAndroidBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
object BleConnectionManager {
    private const val TAG = "BleConnectionManager"

    private var appContext: Context? = null
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var targetDeviceAddress: String? = null
    private var deviceMacBytes: ByteArray? = null

    // State Flows for UI
    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState = _connectionState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _latestDeviceStatus = MutableStateFlow<DeviceStatus?>(null)
    val latestDeviceStatus = _latestDeviceStatus.asStateFlow()

    private val _liveHeartRate = MutableStateFlow<Int?>(null)
    val liveHeartRate = _liveHeartRate.asStateFlow()

    private val _liveSpo2 = MutableStateFlow<Int?>(null)
    val liveSpo2 = _liveSpo2.asStateFlow()

    private val _isLiveMonitoring = MutableStateFlow(false)
    val isLiveMonitoring = _isLiveMonitoring.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing = _isSyncing.asStateFlow()

    private val _isRingLedOn = MutableStateFlow(false)
    val isRingLedOn = _isRingLedOn.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs = _recentLogs.asStateFlow()

    private val commandQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isWriting = false
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Polling job for live metrics
    private var livePollJob: Job? = null

    // Movement tracking buffer for Sleep as Android
    private val movementBuffer = mutableListOf<Float>()
    private var lastMovementFlushTime = System.currentTimeMillis()

    private val PREF_KEY_MAC = "paired_ring_mac"

    fun init(context: Context) {
        appContext = context.applicationContext
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        // Check if previously paired device exists and auto-connect
        val prefs = context.getSharedPreferences("ringconn_prefs", Context.MODE_PRIVATE)
        val savedMac = prefs.getString(PREF_KEY_MAC, null)
        if (!savedMac.isNullOrEmpty()) {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(savedMac)
                if (device != null) {
                    log("Auto-connecting to saved RingConn ($savedMac)...")
                    connect(device)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-connect to $savedMac", e)
            }
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val entry = "[$timestamp] $msg"
        _recentLogs.value = (_recentLogs.value.takeLast(100) + entry)
    }

    fun startScan() {
        val adapter = bluetoothAdapter ?: run {
            log("Bluetooth adapter not available")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            log("BLE Scanner not available")
            return
        }

        _discoveredDevices.value = emptyList()
        val filter = ScanFilter.Builder().build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        log("Starting BLE scan for RingConn devices...")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        log("BLE scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: ""
            if (name.isNotEmpty()) {
                val current = _discoveredDevices.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.address == device.address }
                if (existingIndex >= 0) {
                    current[existingIndex] = device
                } else {
                    current.add(device)
                }

                // Sort: Devices containing "RingConn" (case-insensitive) at the top, then other names
                val sorted = current.sortedWith(
                    compareByDescending<BluetoothDevice> {
                        val dName = it.name ?: ""
                        dName.contains("RingConn", ignoreCase = true)
                    }.thenBy { it.name ?: it.address }
                )
                _discoveredDevices.value = sorted
                if (name.contains("RingConn", ignoreCase = true)) {
                    log("Found RingConn device: $name (${device.address})")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("BLE Scan failed with code: $errorCode")
        }
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        targetDeviceAddress = device.address
        deviceMacBytes = RingAuth.parseMacString(device.address)

        // Persist MAC address
        appContext?.getSharedPreferences("ringconn_prefs", Context.MODE_PRIVATE)?.edit()
            ?.putString(PREF_KEY_MAC, device.address)
            ?.apply()

        _connectionState.value = "Connecting to ${device.name ?: device.address}..."
        log("Connecting to ${device.address}...")

        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        log("Disconnecting...")
        livePollJob?.cancel()
        _isRingLedOn.value = false
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _isConnected.value = false
        _connectionState.value = "Disconnected"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT Connected. Requesting MTU 512...")
                _isConnected.value = true
                _connectionState.value = "Connected. Configuring..."
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT Disconnected (status: $status)")
                _isConnected.value = false
                _connectionState.value = "Disconnected"
                livePollJob?.cancel()
                writeCharacteristic = null
                gatt.close()
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }

                // If not explicitly disconnected by user, auto-reconnect
                val savedMac = targetDeviceAddress
                if (savedMac != null) {
                    coroutineScope.launch {
                        delay(1500)
                        if (!_isConnected.value && targetDeviceAddress == savedMac) {
                            log("Reconnecting to $savedMac...")
                            bluetoothAdapter?.getRemoteDevice(savedMac)?.let { dev ->
                                connect(dev)
                            }
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU changed to $mtu. Discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Services discovered successfully.")
                val dataService = gatt.getService(RingProtocol.DATA_SERVICE_UUID)
                if (dataService != null) {
                    val notifyChar = dataService.getCharacteristic(RingProtocol.NOTIFY_CHAR_UUID)
                    writeCharacteristic = dataService.getCharacteristic(RingProtocol.WRITE_CHAR_UUID)

                    if (notifyChar != null) {
                        coroutineScope.launch {
                            delay(100)
                            enableNotification(gatt, notifyChar)
                        }
                    } else {
                        log("Notify characteristic not found!")
                    }
                } else {
                    log("RingConn Data Service not found!")
                }
            } else {
                log("Service discovery failed: $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Notify CCCD enabled! Initiating status handshake 01 00 00...")
                _connectionState.value = "Connected & Authenticating"
                coroutineScope.launch {
                    delay(50)
                    // Initial status read to prompt challenge response
                    sendCommand(RingProtocol.CMD_STATUS_0)
                }
            } else {
                log("onDescriptorWrite failed with status $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingPacket(value)
        }

        // For Android 12 and below compatibility
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            handleIncomingPacket(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            isWriting = false
            processNextCommand()
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(RingProtocol.CCCD_UUID)
        if (descriptor != null) {
            log("Enabling CCCD notifications...")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    fun sendCommand(cmd: ByteArray) {
        val wasEmpty = commandQueue.isEmpty()
        commandQueue.add(cmd)
        if (wasEmpty) {
            processNextCommand()
        }
    }

    private fun processNextCommand() {
        val gatt = bluetoothGatt ?: return
        val writeChar = writeCharacteristic ?: return
        val nextCmd = commandQueue.poll() ?: return

        coroutineScope.launch {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    writeChar,
                    nextCmd,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
            } else {
                @Suppress("DEPRECATION")
                writeChar.value = nextCmd
                @Suppress("DEPRECATION")
                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(writeChar)
            }
            delay(30)
            processNextCommand()
        }
    }

    private fun handleIncomingPacket(packet: ByteArray) {
        if (packet.isEmpty()) return
        val opcode = packet[0].toInt() and 0xFF
        val hex = packet.joinToString(" ") { "%02X".format(it) }

        when (opcode) {
            0x81 -> {
                // Status / Auth challenge reply
                if (packet.size >= 4 && packet[1] == 0x00.toByte()) {
                    val challenge = packet[2]
                    val mac = deviceMacBytes ?: byteArrayOf(0, 0, 0, 0, 0, 0)
                    log("Received Auth Challenge: 0x%02X. Computing SM3 response...".format(challenge))
                    val authCmd = RingAuth.createAuthCommand(challenge, mac)
                    sendCommand(authCmd)
                    _connectionState.value = "Connected & Streaming"
                }
            }

            0x10, 0x87 -> {
                // Device descriptor (Battery, Temp, Steps, Voltage, Case)
                val status = DeviceStatus.parse(packet)
                if (status != null) {
                    _latestDeviceStatus.value = status
                    coroutineScope.launch {
                        appContext?.let { ctx ->
                            val db = AppDatabase.getDatabase(ctx)
                            db.deviceStatusDao().insert(
                                DeviceStatusEntity(
                                    timestampMillis = status.timestamp,
                                    batteryPercent = status.batteryPercent,
                                    stateByte = status.stateByte,
                                    isOnCharger = status.isOnCharger,
                                    isSportMode = status.isSportMode,
                                    quarterHourSteps = status.quarterHourSteps,
                                    skinTemperatureC = status.skinTemperature?.celsius,
                                    batteryVoltageMv = status.batteryVoltageMv,
                                    caseBatteryPercent = status.caseBattery?.percent,
                                    caseIsCharging = status.caseBattery?.isCharging
                                )
                            )
                        }
                    }
                }
            }

            0x4C -> {
                // Bulk history record page
                val records = BulkRecord.parsePage(packet)
                log("Received 0x4C history page with ${records.size} epochs. ACKing with CC 00 00...")
                sendCommand(RingProtocol.CMD_PAGE_ACK_4C)

                if (records.isNotEmpty()) {
                    coroutineScope.launch {
                        appContext?.let { ctx ->
                            val db = AppDatabase.getDatabase(ctx)
                            val entities = records.map { rec ->
                                EpochEntity(
                                    counter = rec.counter,
                                    timestampMillis = rec.timestampMillis,
                                    channel = 0,
                                    layout = rec.layout.name,
                                    heartRate = rec.heartRate,
                                    hrvRmssd = rec.hrvRmssd,
                                    confidence = rec.confidence,
                                    respiratoryRate = rec.respiratoryRate,
                                    spo2Percent = rec.spo2Percent,
                                    rawBytes = rec.raw
                                )
                            }
                            db.epochDao().insertAll(entities)
                        }
                    }
                }
            }

            0x47 -> {
                // Bulk PPG optical trend page (ACK only)
                sendCommand(RingProtocol.CMD_PAGE_ACK_47)
            }

            0x4D -> {
                // Sport history page (ACK only)
                sendCommand(RingProtocol.CMD_PAGE_ACK_4D)
            }

            0x4E -> {
                // Sport / Continuous Live Stream frame (ACK with CE 00 00)
                sendCommand(RingProtocol.CMD_PAGE_ACK_4E)
                if (packet.size >= 3) {
                    val hr = packet[2].toInt() and 0xFF
                    if (hr in 30..220) {
                        _liveHeartRate.value = hr
                        log("Live HR (0x4E): $hr BPM")
                        if (SleepAsAndroidBridge.isTrackingActive) {
                            appContext?.let { ctx ->
                                SleepAsAndroidBridge.sendHeartRateData(ctx, floatArrayOf(hr.toFloat()))
                                SleepAsAndroidBridge.sendExtraSensorData(ctx, hr = hr.toFloat())
                            }
                        }
                    }
                }
            }

            0x11 -> {
                // Heartbeat notification -> reply 91 00 00
                sendCommand(RingProtocol.CMD_HEARTBEAT_ACK)
            }

            0x15 -> {
                // Live sample response
                if (packet.size >= 3 && packet[1] == 0x00.toByte()) {
                    val hr = packet[2].toInt() and 0xFF
                    if (hr in 30..220) {
                        _liveHeartRate.value = hr
                        log("Live HR (0x15): $hr BPM")

                        // Forward to Sleep as Android if tracking
                        if (SleepAsAndroidBridge.isTrackingActive) {
                            appContext?.let { ctx ->
                                SleepAsAndroidBridge.sendHeartRateData(ctx, floatArrayOf(hr.toFloat()))
                                SleepAsAndroidBridge.sendExtraSensorData(ctx, hr = hr.toFloat())
                            }
                        }
                    }
                } else if (packet.size >= 15 && packet[1] == 0x01.toByte()) {
                    val spo2 = packet[14].toInt() and 0xFF
                    if (spo2 in 70..100) {
                        _liveSpo2.value = spo2
                        log("Live SpO2: $spo2 %")

                        if (SleepAsAndroidBridge.isTrackingActive) {
                            appContext?.let { ctx ->
                                SleepAsAndroidBridge.sendExtraSensorData(ctx, spo2 = spo2.toFloat())
                            }
                        }
                    }
                }
            }

            0x82 -> {
                log("Sync open ACK (0x82): $hex")
            }

            0x86 -> {
                log("Live mode ACK (0x86): $hex")
            }

            0x50 -> {
                log("End of history stream (0x50)")
                _isSyncing.value = false
            }

            else -> {
                Log.d(TAG, "Unhandled RX frame (0x%02X): $hex".format(opcode))
            }
        }
    }

    fun syncHistory() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        coroutineScope.launch {
            log("Opening history sync on Sleep Channel (0x00)...")
            sendCommand(RingProtocol.createSyncUpToNowCommand(RingProtocol.CHANNEL_SLEEP))
            sendCommand(RingProtocol.CMD_FETCH)

            delay(3000)

            log("Opening history sync on All-Day Channel (0x03)...")
            sendCommand(RingProtocol.createSyncUpToNowCommand(RingProtocol.CHANNEL_ALL_DAY))
            sendCommand(RingProtocol.CMD_FETCH)
        }
    }

    fun startLiveMonitoring(hrMode: Boolean = true) {
        _isLiveMonitoring.value = true
        livePollJob?.cancel()

        coroutineScope.launch {
            log("Starting continuous live measurement mode (${if (hrMode) "HR" else "SpO2"})...")
            sendCommand(RingProtocol.CMD_STATUS_QUERY)
            delay(150)
            sendCommand(if (hrMode) RingProtocol.CMD_LIVE_HR_MODE else RingProtocol.CMD_LIVE_SPO2_MODE)
            delay(150)
            sendCommand(RingProtocol.CMD_FETCH)
            delay(150)
            sendCommand(RingProtocol.CMD_POLL)

            // Continuous maintainer loop: queries live samples every 1.5 seconds and re-primes every 10s
            var tick = 0
            livePollJob = launch {
                while (isActive && _isLiveMonitoring.value) {
                    delay(1500)
                    tick++
                    sendCommand(RingProtocol.CMD_POLL)
                    if (tick % 6 == 0) {
                        // Keepalive re-prime
                        sendCommand(if (hrMode) RingProtocol.CMD_LIVE_HR_MODE else RingProtocol.CMD_LIVE_SPO2_MODE)
                        sendCommand(RingProtocol.CMD_FETCH)
                    }
                }
            }
        }
    }

    fun stopLiveMonitoring() {
        _isLiveMonitoring.value = false
        livePollJob?.cancel()
        livePollJob = null
        sendCommand(RingProtocol.CMD_SPORT_STOP)
        log("Stopped live monitoring")
    }

    fun toggleFindRingLed(enable: Boolean? = null) {
        val targetState = enable ?: !_isRingLedOn.value
        _isRingLedOn.value = targetState
        log("Toggling Ring LED: ${if (targetState) "ON" else "OFF"}")
        sendCommand(if (targetState) RingProtocol.CMD_FIND_RING_LED_ON else RingProtocol.CMD_FIND_RING_LED_OFF)
    }
}
