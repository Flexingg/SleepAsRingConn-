package com.randallengineering.sleepasringconn.sleepasandroid

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import com.randallengineering.sleepasringconn.sensor.MotionSensorManager
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

    fun handleStartTracking(context: Context, doHr: Boolean, doOximeter: Boolean, scope: CoroutineScope) {
        isTrackingActive = true
        isHrMonitoringRequested = doHr
        isOximeterMonitoringRequested = doOximeter
        Log.i(TAG, "Sleep as Android started tracking. HR: $doHr, SpO2: $doOximeter, batchSize: $batchSize")

        // Acquire partial WakeLock to prevent CPU sleeping during overnight tracking
        acquireWakeLock(context)

        // Start hardware & ring motion fusion engine
        MotionSensorManager.getInstance(context).start()

        // Ensure ring is in active continuous live optical monitoring mode
        BleConnectionManager.startLiveMonitoring(hrMode = true)

        // Start periodic vitals streamer
        startVitalsStreamer(context, scope)
    }

    fun handleStopTracking(context: Context) {
        isTrackingActive = false
        isHrMonitoringRequested = false
        isOximeterMonitoringRequested = false
        trackingJob?.cancel()
        trackingJob = null

        // Stop motion aggregation
        MotionSensorManager.getInstance(context).stop()
        releaseWakeLock()

        // Sync overnight history immediately upon waking up
        BleConnectionManager.stopLiveMonitoring()
        BleConnectionManager.syncHistory()

        Log.i(TAG, "Sleep as Android stopped tracking. Full history sync requested.")
    }

    fun handleSetBatchSize(size: Long) {
        batchSize = size.toInt().coerceIn(1, 60)
        Log.i(TAG, "Sleep as Android batch size updated: $batchSize")
    }

    private fun startVitalsStreamer(context: Context, scope: CoroutineScope) {
        trackingJob?.cancel()
        trackingJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Starting periodic vitals streaming to Sleep as Android...")

            while (isActive && isTrackingActive) {
                delay(10000) // Pulse and sensor sync every 10 seconds

                val liveHr = BleConnectionManager.liveHeartRate.value
                val liveSpo2 = BleConnectionManager.liveSpo2.value

                if (liveHr != null && liveHr in 30..220) {
                    sendHeartRateData(context, floatArrayOf(liveHr.toFloat()))
                    sendExtraSensorData(
                        context = context,
                        hr = liveHr.toFloat(),
                        spo2 = liveSpo2?.toFloat(),
                        timestampMillis = System.currentTimeMillis()
                    )
                }

                // If live monitoring paused or ring idled out, re-assert live monitoring
                if (!BleConnectionManager.isLiveMonitoring.value) {
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
                Log.i(TAG, "Acquired partial WakeLock for overnight tracking.")
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
