package com.randallengineering.sleepasringconn.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

@SuppressLint("MissingPermission")
object HrBroadcastManager {
    private const val TAG = "HrBroadcastManager"

    val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    val BODY_SENSOR_LOCATION_UUID: UUID = UUID.fromString("00002A38-0000-1000-8000-00805f9b34fb")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var hrMeasurementCharacteristic: BluetoothGattCharacteristic? = null

    private val subscribedDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
    private val connectedDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _connectedDeviceCount = MutableStateFlow(0)
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _lastBroadcastBpm = MutableStateFlow<Int?>(null)
    val lastBroadcastBpm: StateFlow<Int?> = _lastBroadcastBpm.asStateFlow()

    private val _statusMessage = MutableStateFlow("Idle")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var tickerJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isSupported(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return false
        return adapter.isMultipleAdvertisementSupported
    }

    fun startBroadcast(context: Context): Boolean {
        if (_isBroadcasting.value) return true

        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _statusMessage.value = "Bluetooth is disabled"
            return false
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            _statusMessage.value = "BLE Advertising not supported"
            return false
        }

        // 1. Setup GATT Server
        val server = bluetoothManager?.openGattServer(context, gattServerCallback)
        if (server == null) {
            _statusMessage.value = "Failed to open GATT Server"
            return false
        }
        gattServer = server

        val hrService = BluetoothGattService(HEART_RATE_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Heart Rate Measurement Characteristic (Notify only)
        val hrMeasurement = BluetoothGattCharacteristic(
            HEART_RATE_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // CCCD Descriptor to allow Peloton to subscribe to notifications
        val cccd = BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        hrMeasurement.addDescriptor(cccd)
        hrMeasurementCharacteristic = hrMeasurement

        // Body Sensor Location Characteristic (Finger = 0x03)
        val bodyLocation = BluetoothGattCharacteristic(
            BODY_SENSOR_LOCATION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        bodyLocation.value = byteArrayOf(0x03) // 0x03 = Finger

        hrService.addCharacteristic(hrMeasurement)
        hrService.addCharacteristic(bodyLocation)
        server.addService(hrService)

        // 2. Start Advertising with Scan Response for instant Peloton discovery
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            _isBroadcasting.value = true
            _statusMessage.value = "Advertising (Ready for Peloton / Zwift)..."
            Log.d(TAG, "Started BLE Heart Rate Advertising")

            // 3. Start 1 Hz Heartbeat Transmission Loop (Mandatory for Peloton/Zwift stability)
            startPeriodicHeartbeatTicker()

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising: ${e.message}", e)
            _statusMessage.value = "Adv error: ${e.message}"
            stopBroadcast()
            return false
        }
    }

    private fun startPeriodicHeartbeatTicker() {
        tickerJob?.cancel()
        tickerJob = coroutineScope.launch {
            while (isActive && _isBroadcasting.value) {
                delay(1000) // Standard 1 Hz BLE HRM rate
                val currentBpm = _lastBroadcastBpm.value
                if (currentBpm != null && currentBpm > 0) {
                    sendNotificationPacket(currentBpm)
                }
            }
        }
    }

    fun stopBroadcast() {
        tickerJob?.cancel()
        tickerJob = null

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising: ${e.message}")
        }
        advertiser = null

        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server: ${e.message}")
        }
        gattServer = null
        hrMeasurementCharacteristic = null

        subscribedDevices.clear()
        connectedDevices.clear()

        _isBroadcasting.value = false
        _connectedDeviceCount.value = 0
        _connectedDeviceName.value = null
        _statusMessage.value = "Idle"
        Log.d(TAG, "Stopped BLE Heart Rate Broadcast")
    }

    fun broadcastHeartRate(bpm: Int) {
        if (!_isBroadcasting.value || bpm <= 0) return
        _lastBroadcastBpm.value = bpm
        sendNotificationPacket(bpm)
    }

    private fun sendNotificationPacket(bpm: Int) {
        val characteristic = hrMeasurementCharacteristic ?: return
        val server = gattServer ?: return

        // Standard Heart Rate Measurement Packet:
        // Byte 0: Flags -> 0x06 (8-bit format, Sensor Contact Supported & Detected)
        // Byte 1: Heart Rate Value (uint8, 0..255)
        val packet = byteArrayOf(
            0x06.toByte(),
            (bpm.coerceIn(0, 255)).toByte()
        )

        characteristic.value = packet

        val targets = synchronized(subscribedDevices) {
            if (subscribedDevices.isNotEmpty()) {
                subscribedDevices.toList()
            } else {
                // If Peloton connected but hasn't written CCCD yet, push to all connected centrals
                synchronized(connectedDevices) { connectedDevices.toList() }
            }
        }

        for (device in targets) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, characteristic, false, packet)
                } else {
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error notifying device ${device.address}: ${e.message}")
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "BLE HR Advertising started successfully")
            _statusMessage.value = "Broadcasting (Ready for Peloton / Zwift)"
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                else -> "Error code $errorCode"
            }
            Log.e(TAG, "BLE HR Advertising failed: $errorMsg")
            _statusMessage.value = "Adv failed: $errorMsg"
            _isBroadcasting.value = false
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "GATT Server connection state change: ${device.address} -> $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                val name = device.name ?: "Peloton / Fitness Receiver"
                _connectedDeviceName.value = name
                _connectedDeviceCount.value = connectedDevices.size
                _statusMessage.value = "Connected to $name"
                Log.i(TAG, "Central device connected to GATT Server: ${device.address} ($name)")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                subscribedDevices.remove(device)
                _connectedDeviceCount.value = connectedDevices.size
                if (connectedDevices.isEmpty()) {
                    _connectedDeviceName.value = null
                    _statusMessage.value = "Broadcasting (Ready for Peloton / Zwift)"
                    Log.i(TAG, "All centrals disconnected from GATT Server")
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BODY_SENSOR_LOCATION_UUID) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0x03))
            } else if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                val bpm = _lastBroadcastBpm.value ?: 70
                val packet = byteArrayOf(0x06.toByte(), (bpm.coerceIn(0, 255)).toByte())
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, packet)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, characteristic.value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                val value = if (subscribedDevices.contains(device)) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, descriptor.value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedDevices.add(device)
                    connectedDevices.add(device)
                    _connectedDeviceCount.value = subscribedDevices.size
                    _connectedDeviceName.value = device.name ?: "Peloton / Fitness Receiver"
                    _statusMessage.value = "Streaming HR (1 Hz) to ${_connectedDeviceName.value}"
                    Log.i(TAG, "Device subscribed to HR notifications: ${device.address}")
                } else {
                    subscribedDevices.remove(device)
                    _connectedDeviceCount.value = subscribedDevices.size
                    Log.i(TAG, "Device unsubscribed from HR notifications: ${device.address}")
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }
    }
}
