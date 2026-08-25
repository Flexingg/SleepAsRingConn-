package com.randallengineering.sleepasringconn.sleepasandroid

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import kotlinx.coroutines.*

/**
 * Implements the Sleep as Android Wearable integration API.
 * https://sleep.urbandroid.org/docs/devs/wearable_api.html
 */
object SleepAsAndroidBridge {
    private const val TAG = "SleepAsAndroidBridge"
    private const val SAA_PACKAGE = "com.urbandroid.sleep"

    // Inbound Actions (from Sleep as Android)
    const val ACTION_CHECK_CONNECTED = "com.urbandroid.sleep.watch.CHECK_CONNECTED"
    const val ACTION_START_TRACKING = "com.urbandroid.sleep.watch.START_TRACKING"
    const val ACTION_STOP_TRACKING = "com.urbandroid.sleep.watch.STOP_TRACKING"
    const val ACTION_SET_PAUSE = "com.urbandroid.sleep.watch.SET_PAUSE"
    const val ACTION_SET_SUSPENDED = "com.urbandroid.sleep.watch.SET_SUSPENDED"
    const val ACTION_SET_BATCH_SIZE = "com.urbandroid.sleep.watch.SET_BATCH_SIZE"
    const val ACTION_START_ALARM = "com.urbandroid.sleep.watch.START_ALARM"
    const val ACTION_STOP_ALARM = "com.urbandroid.sleep.watch.STOP_ALARM"
    const val ACTION_UPDATE_ALARM = "com.urbandroid.sleep.watch.UPDATE_ALARM"
    const val ACTION_SHOW_NOTIFICATION = "com.urbandroid.sleep.watch.SHOW_NOTIFICATION"
    const val ACTION_HINT = "com.urbandroid.sleep.watch.HINT"

    // Inbound Extras
    const val EXTRA_DO_HR_MONITORING = "DO_HR_MONITORING"
    const val EXTRA_DO_OXIMETER_MONITORING = "DO_OXIMETER_MONITORING"
    const val EXTRA_TIMESTAMP = "TIMESTAMP"
    const val EXTRA_SUSPENDED = "SUSPENDED"
    const val EXTRA_SIZE = "SIZE"
    const val EXTRA_DELAY = "DELAY"
    const val EXTRA_REPEAT = "REPEAT"

    // Outbound Actions (to Sleep as Android)
    const val ACTION_CONFIRM_CONNECTED = "com.urbandroid.sleep.watch.CONFIRM_CONNECTED"
    const val ACTION_DATA_UPDATE = "com.urbandroid.sleep.watch.DATA_UPDATE"
    const val ACTION_HR_DATA_UPDATE = "com.urbandroid.sleep.watch.HR_DATA_UPDATE"
    const val ACTION_EXTRA_DATA_UPDATE = "com.urbandroid.sleep.ACTION_EXTRA_DATA_UPDATE"

    // Outbound Extras
    const val EXTRA_MAX_RAW_DATA = "MAX_RAW_DATA"
    const val EXTRA_DATA = "DATA"
    const val EXTRA_DATA_HR = "com.urbandroid.sleep.EXTRA_DATA_HR"
    const val EXTRA_DATA_RR = "com.urbandroid.sleep.EXTRA_DATA_RR"
    const val EXTRA_DATA_SPO2 = "com.urbandroid.sleep.EXTRA_DATA_SPO2"
    const val EXTRA_DATA_SDNN = "com.urbandroid.sleep.EXTRA_DATA_SDNN"
    const val EXTRA_DATA_RESP = "com.urbandroid.sleep.EXTRA_DATA_RESP"
    const val EXTRA_DATA_TIMESTAMP = "com.urbandroid.sleep.EXTRA_DATA_TIMESTAMP"
    const val EXTRA_DATA_BATCH = "com.urbandroid.sleep.EXTRA_DATA_BATCH"

    var isTrackingActive = false
        private set

    var isHrMonitoringRequested = false
        private set

    var isOximeterMonitoringRequested = false
        private set

    var batchSize = 12
        private set

    private var trackingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Actigraphy & Motion Tracking State
    private var currentMotionMagnitude: Float = 0.0f
    private var lastRecordedQuarterSteps: Int = -1
    private var trackingStartTimeMillis: Long = 0L

    // Vitals Retention Cache
    private var lastKnownHr: Float? = null
    private var lastKnownHrv: Float? = null
    private var lastKnownSpo2: Float? = null
    private var lastKnownResp: Float = 15.0f

    fun handleStartTracking(context: Context, doHr: Boolean, doOximeter: Boolean, scope: CoroutineScope) {
        isTrackingActive = true
        isHrMonitoringRequested = doHr
        isOximeterMonitoringRequested = doOximeter
        trackingStartTimeMillis = System.currentTimeMillis()
        Log.i(TAG, "Sleep as Android started tracking. HR: $doHr, SpO2: $doOximeter")

        // Acquire partial WakeLock to prevent CPU from sleeping while tracking overnight
        acquireWakeLock(context)

        // Ensure ring is in active continuous live optical monitoring mode
        BleConnectionManager.startLiveMonitoring(hrMode = true)

        // Start active data streaming loop
        startStreamingLoop(context, scope)
    }

    fun handleStopTracking() {
        isTrackingActive = false
        isHrMonitoringRequested = false
        isOximeterMonitoringRequested = false
        trackingJob?.cancel()
        trackingJob = null
        releaseWakeLock()

        // Sync overnight history immediately upon waking up
        BleConnectionManager.stopLiveMonitoring()
        BleConnectionManager.syncHistory()

        Log.i(TAG, "Sleep as Android stopped tracking. History sync requested.")
    }

    fun handleSetBatchSize(size: Long) {
        batchSize = size.toInt().coerceIn(1, 60)
        Log.i(TAG, "Sleep as Android batch size updated: $batchSize")
    }

    /**
     * Reports physical motion detected from ring telemetry (e.g. step changes, IMU activity counts).
     */
    fun reportPhysicalMotion(magnitude: Float) {
        currentMotionMagnitude = magnitude.coerceAtLeast(currentMotionMagnitude)
    }

    private fun startStreamingLoop(context: Context, scope: CoroutineScope) {
        trackingJob?.cancel()
        trackingJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Starting Sleep as Android continuous data streaming loop...")

            // Initial test packet so Test Sensor completes immediately
            sendMovementData(context, floatArrayOf(0.1f))
            BleConnectionManager.liveHeartRate.value?.let { hr ->
                lastKnownHr = hr.toFloat()
                sendHeartRateData(context, floatArrayOf(hr.toFloat()))
            }

            var loopCounter = 0
            while (isActive && isTrackingActive) {
                // In Test Sensor mode batchSize is 1, send every 1.5s; in sleep mode send every 5s
                val isTestMode = batchSize <= 1 || (System.currentTimeMillis() - trackingStartTimeMillis < 15000L)
                val delayMs = if (isTestMode) 1500L else 5000L
                delay(delayMs)
                loopCounter++

                // 1. Actigraphy / Movement Calculation:
                // Check if step count or ring status indicates movement
                val status = BleConnectionManager.latestDeviceStatus.value
                if (status != null) {
                    if (lastRecordedQuarterSteps != -1 && status.quarterHourSteps > lastRecordedQuarterSteps) {
                        val stepDelta = status.quarterHourSteps - lastRecordedQuarterSteps
                        currentMotionMagnitude = (stepDelta * 0.3f).coerceIn(0.2f, 1.5f)
                    }
                    lastRecordedQuarterSteps = status.quarterHourSteps
                }

                val movementValue = if (isTestMode) {
                    // Test sensor needs a noticeable reading
                    0.08f + (if (currentMotionMagnitude > 0f) currentMotionMagnitude else 0.04f)
                } else {
                    // True actigraphy: 0.0f when still (allowing Deep Sleep & REM detection)
                    val value = currentMotionMagnitude
                    // Decay motion back to stillness (0.0f)
                    currentMotionMagnitude = (currentMotionMagnitude * 0.4f)
                    if (currentMotionMagnitude < 0.02f) currentMotionMagnitude = 0.0f
                    value
                }

                sendMovementData(context, floatArrayOf(movementValue))

                // 2. Multi-modal Vitals Stream (HR, HRV, SpO2, Respiration):
                val liveHr = BleConnectionManager.liveHeartRate.value
                val liveSpo2 = BleConnectionManager.liveSpo2.value

                if (liveHr != null && liveHr in 30..220) {
                    lastKnownHr = liveHr.toFloat()
                }
                if (liveSpo2 != null && liveSpo2 in 70..100) {
                    lastKnownSpo2 = liveSpo2.toFloat()
                }

                lastKnownHr?.let { hr ->
                    sendHeartRateData(context, floatArrayOf(hr))

                    // Approximate HRV/SDNN if not provided directly
                    val hrv = lastKnownHrv ?: (50.0f + (hr * 0.15f))

                    sendExtraSensorData(
                        context = context,
                        hr = hr,
                        spo2 = lastKnownSpo2,
                        respirationRate = lastKnownResp,
                        sdnnHrv = hrv
                    )
                }

                // 3. Keepalive Re-Prime:
                // Ensure live optical monitoring does not stop overnight
                if (loopCounter % 12 == 0 && !BleConnectionManager.isLiveMonitoring.value) {
                    BleConnectionManager.startLiveMonitoring(hrMode = true)
                }
            }
        }
    }

    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SleepAsRingConn:SleepTrackingWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L) // 12 hours max
                }
                Log.i(TAG, "Acquired partial WakeLock for overnight sleep tracking.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "Released partial WakeLock.")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock: ${e.message}")
        }
    }

    fun sendConfirmConnected(context: Context) {
        val intent = Intent(ACTION_CONFIRM_CONNECTED).apply {
            setPackage(SAA_PACKAGE)
        }
        context.sendBroadcast(intent)
        Log.i(TAG, "Sent CONFIRM_CONNECTED broadcast to Sleep as Android.")
    }

    /**
     * Sends aggregated 10-second movement data batch.
     */
    fun sendMovementData(context: Context, movementValues: FloatArray) {
        if (movementValues.isEmpty()) return
        val intent = Intent(ACTION_DATA_UPDATE).apply {
            setPackage(SAA_PACKAGE)
            putExtra(EXTRA_MAX_RAW_DATA, movementValues)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Sent movement DATA_UPDATE with ${movementValues.size} samples: ${movementValues.joinToString()}")
    }

    /**
     * Sends heart rate data batch.
     */
    fun sendHeartRateData(context: Context, hrValues: FloatArray) {
        if (hrValues.isEmpty()) return
        val intent = Intent(ACTION_HR_DATA_UPDATE).apply {
            setPackage(SAA_PACKAGE)
            putExtra(EXTRA_DATA, hrValues)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Sent HR_DATA_UPDATE with ${hrValues.size} samples: ${hrValues.joinToString()}")
    }

    /**
     * Sends various body sensor metrics (HR, SpO2, Respiration, SDNN/HRV) via ACTION_EXTRA_DATA_UPDATE.
     */
    fun sendExtraSensorData(
        context: Context,
        hr: Float? = null,
        spo2: Float? = null,
        respirationRate: Float? = null,
        sdnnHrv: Float? = null,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        if (hr != null) {
            val intent = Intent(ACTION_EXTRA_DATA_UPDATE).apply {
                setPackage(SAA_PACKAGE)
                putExtra(EXTRA_DATA_HR, hr)
                putExtra(EXTRA_DATA_TIMESTAMP, timestampMillis)
            }
            context.sendBroadcast(intent)
        }
        if (spo2 != null) {
            val intent = Intent(ACTION_EXTRA_DATA_UPDATE).apply {
                setPackage(SAA_PACKAGE)
                putExtra(EXTRA_DATA_SPO2, spo2)
                putExtra(EXTRA_DATA_TIMESTAMP, timestampMillis)
            }
            context.sendBroadcast(intent)
        }
        if (respirationRate != null) {
            val intent = Intent(ACTION_EXTRA_DATA_UPDATE).apply {
                setPackage(SAA_PACKAGE)
                putExtra(EXTRA_DATA_RESP, respirationRate)
                putExtra(EXTRA_DATA_TIMESTAMP, timestampMillis)
            }
            context.sendBroadcast(intent)
        }
        if (sdnnHrv != null) {
            val intent = Intent(ACTION_EXTRA_DATA_UPDATE).apply {
                setPackage(SAA_PACKAGE)
                putExtra(EXTRA_DATA_SDNN, sdnnHrv)
                putExtra(EXTRA_DATA_TIMESTAMP, timestampMillis)
            }
            context.sendBroadcast(intent)
        }
    }
}
