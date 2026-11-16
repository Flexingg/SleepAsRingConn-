package com.randallengineering.sleepasringconn.sleepasandroid

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.protocol.BulkRecordLayout
import com.randallengineering.sleepasringconn.sensor.MotionSensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sleep as Android "DIY / generic watch" add-on.
 * API: https://sleep.urbandroid.org/docs/devs/wearable_api.html
 *
 * What Sleep as Android actually does with what we send (checked against its GenericWatchReceiver,
 * HrDataProducer and BTLEDataProducer):
 *  - `DATA_UPDATE`: reads `MAX_RAW_DATA` (falling back to `MAX_DATA`) and appends each value as the
 *    next 10 s slot. No timestamps. If the first value is > 0, every 0.0 in the batch is dropped.
 *    If no `DATA_UPDATE` arrives for 240 s, it re-sends `START_TRACKING`.
 *  - `HR_DATA_UPDATE`: `DATA` values are re-stamped 10 s apart ending at "now"; 27..150 bpm only.
 *  - `ACTION_EXTRA_DATA_UPDATE`: honours `EXTRA_DATA_TIMESTAMP`. If `EXTRA_DATA_BATCH` is present,
 *    every metric producer reads it, so each intent carries exactly one metric.
 *
 * The ring's motion only exists in its history epochs, so movement goes through
 * [ActigraphTimeline]; see there for the timing model.
 */
object SleepAsAndroidBridge {
    private const val TAG = "SleepAsAndroidBridge"
    private const val SAA_PACKAGE = "com.urbandroid.sleep"

    // Inbound actions (from Sleep as Android)
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

    // Inbound extras
    const val EXTRA_DO_HR_MONITORING = "DO_HR_MONITORING"
    const val EXTRA_DO_OXIMETER_MONITORING = "DO_OXIMETER_MONITORING"
    const val EXTRA_TIMESTAMP = "TIMESTAMP"
    const val EXTRA_SUSPENDED = "SUSPENDED"
    const val EXTRA_SIZE = "SIZE"
    const val EXTRA_DELAY = "DELAY"
    const val EXTRA_REPEAT = "REPEAT"

    // Outbound actions (to Sleep as Android)
    const val ACTION_CONFIRM_CONNECTED = "com.urbandroid.sleep.watch.CONFIRM_CONNECTED"
    const val ACTION_DATA_UPDATE = "com.urbandroid.sleep.watch.DATA_UPDATE"
    const val ACTION_HR_DATA_UPDATE = "com.urbandroid.sleep.watch.HR_DATA_UPDATE"
    const val ACTION_EXTRA_DATA_UPDATE = "com.urbandroid.sleep.ACTION_EXTRA_DATA_UPDATE"

    // Outbound extras
    const val EXTRA_MAX_RAW_DATA = "MAX_RAW_DATA"
    const val EXTRA_MAX_DATA = "MAX_DATA"
    const val EXTRA_DATA = "DATA"
    const val EXTRA_DATA_HR = "com.urbandroid.sleep.EXTRA_DATA_HR"
    const val EXTRA_DATA_RR = "com.urbandroid.sleep.EXTRA_DATA_RR"
    const val EXTRA_DATA_SPO2 = "com.urbandroid.sleep.EXTRA_DATA_SPO2"
    const val EXTRA_DATA_SDNN = "com.urbandroid.sleep.EXTRA_DATA_SDNN"
    const val EXTRA_DATA_TIMESTAMP = "com.urbandroid.sleep.EXTRA_DATA_TIMESTAMP"
    const val EXTRA_DATA_BATCH = "com.urbandroid.sleep.EXTRA_DATA_BATCH"

    /** SaA's own default batch (120 s of 10 s slots) until it says otherwise. */
    private const val DEFAULT_BATCH_SIZE = 12
    private const val TICK_MS = 10_000L
    /** A live HR reading older than this is not forwarded. */
    private const val LIVE_HR_FRESH_MS = 20_000L
    private const val EPOCH_MS = BulkRecord.EPOCH_SECONDS * 1000L

    @Volatile var isTrackingActive = false
        private set
    @Volatile var isHrMonitoringRequested = false
        private set
    @Volatile var isOximeterMonitoringRequested = false
        private set
    @Volatile var isSuspended = false
        private set
    @Volatile var batchSize = DEFAULT_BATCH_SIZE
        private set

    /** Human-readable bridge state for the Integrations screen. */
    data class Stats(
        val trackingSinceMs: Long = 0L,
        val movementValuesSent: Int = 0,
        val lastMovementBatchMs: Long = 0L,
        val hrValuesSent: Int = 0,
        val epochVitalsSent: Int = 0,
        val actigraphLagSeconds: Long? = null,
        val clockOffsetHours: Long = 0L,
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timeline = ActigraphTimeline()
    private val pendingMovement = ArrayList<Float>()
    private val sentEpochCounters = HashSet<Long>()
    /** Wall times at which a live HR value was forwarded, for de-duplicating epoch HR. */
    private val liveHrSentAt = ArrayDeque<Long>()

    @Volatile private var latestLiveHr: Int? = null
    @Volatile private var latestLiveHrAt = 0L
    private var trackingStartMs = 0L
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var appContext: Context? = null

    // ------------------------------------------------------------------ commands from SaA

    /**
     * START_TRACKING. Idempotent: SaA re-sends it whenever 240 s pass without movement data, and
     * a repeat must not reset the stream (that is what used to wipe live HR mid-night).
     */
    @Synchronized
    fun handleStartTracking(context: Context, doHr: Boolean, doOximeter: Boolean) {
        appContext = context.applicationContext
        isHrMonitoringRequested = doHr
        isOximeterMonitoringRequested = doOximeter
        if (isTrackingActive) {
            Log.i(TAG, "START_TRACKING repeated (HR=$doHr SpO2=$doOximeter); stream continues.")
            BleConnectionManager.setSleepTracking(true)
            return
        }
        isTrackingActive = true
        isSuspended = false
        val now = System.currentTimeMillis()
        trackingStartMs = now
        timeline.start(now)
        synchronized(pendingMovement) { pendingMovement.clear() }
        sentEpochCounters.clear()
        liveHrSentAt.clear()
        _stats.value = Stats(trackingSinceMs = now, clockOffsetHours = timeline.clockOffsetMs / 3_600_000L)
        Log.i(TAG, "Tracking started. HR=$doHr SpO2=$doOximeter batch=$batchSize")

        acquireWakeLock(context)
        MotionSensorManager.getInstance(context).start()
        BleConnectionManager.setSleepTracking(true)
        startTicker()
    }

    @Synchronized
    fun handleStopTracking(context: Context) {
        if (!isTrackingActive) return
        isTrackingActive = false
        tickerJob?.cancel()
        tickerJob = null
        // Send whatever movement is ready so the end of the night is not lost.
        val ctx = appContext ?: context.applicationContext
        val tail = timeline.drain(System.currentTimeMillis())
        val rest = synchronized(pendingMovement) {
            pendingMovement.addAll(tail.toList())
            pendingMovement.toFloatArray().also { pendingMovement.clear() }
        }
        if (rest.isNotEmpty()) sendMovementData(ctx, rest)
        timeline.stop()
        isHrMonitoringRequested = false
        isOximeterMonitoringRequested = false
        MotionSensorManager.getInstance(context).stop()
        releaseWakeLock()
        BleConnectionManager.setSleepTracking(false)
        Log.i(TAG, "Tracking stopped; full history sync requested.")
    }

    fun handleSetBatchSize(size: Long) {
        batchSize = size.toInt().coerceIn(1, 360)
        Log.i(TAG, "Batch size: $batchSize")
    }

    /** SET_SUSPENDED / SET_PAUSE. Per the API, values keep flowing while paused. */
    fun handleSuspended(suspended: Boolean) {
        isSuspended = suspended
        Log.i(TAG, "Suspended: $suspended")
    }

    // ------------------------------------------------------------------ input from the ring

    /** A settled live heart rate from the ring (`0x15`). */
    fun onLiveHeartRate(hr: Int) {
        latestLiveHr = hr
        latestLiveHrAt = System.currentTimeMillis()
    }

    /**
     * History epochs that were just persisted. Feeds the actigraph and backfills HR / HRV / SpO2
     * with their real timestamps for anything live HR did not cover.
     */
    fun onEpochs(records: List<BulkRecord>) {
        if (!isTrackingActive || records.isEmpty()) return
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        timeline.ingest(records, now)
        var sent = 0
        for (r in records.sortedBy { it.counter }) {
            if (r.layout == BulkRecordLayout.IDLE) continue
            val start = timeline.wallTimeOf(r)
            if (start < trackingStartMs - EPOCH_MS || start > now) continue
            if (!sentEpochCounters.add(r.counter)) continue
            val mid = start + EPOCH_MS / 2
            if (isHrMonitoringRequested) {
                val hr = r.heartRate
                if (hr != null && !liveHrCovered(start, start + EPOCH_MS)) {
                    sendSingleExtra(ctx, EXTRA_DATA_HR, hr.toFloat(), mid); sent++
                }
                r.hrvRmssd?.let { sendSingleExtra(ctx, EXTRA_DATA_SDNN, it.toFloat(), mid); sent++ }
            }
            if (isOximeterMonitoringRequested) {
                r.spo2Percent?.let { sendSingleExtra(ctx, EXTRA_DATA_SPO2, it.toFloat(), mid); sent++ }
            }
        }
        _stats.value = _stats.value.copy(
            epochVitalsSent = _stats.value.epochVitalsSent + sent,
            clockOffsetHours = timeline.clockOffsetMs / 3_600_000L,
        )
    }

    private fun liveHrCovered(fromMs: Long, toMs: Long): Boolean =
        synchronized(liveHrSentAt) { liveHrSentAt.any { it in fromMs until toMs } }

    // ------------------------------------------------------------------ 10 s ticker

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && isTrackingActive) {
                delay(TICK_MS - System.currentTimeMillis() % TICK_MS)
                try { tick() } catch (e: Exception) { Log.e(TAG, "Tick failed", e) }
            }
        }
    }

    private fun tick() {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()

        // Movement: whatever slots the timeline has ready, sent in SaA-sized batches.
        val ready = timeline.drain(now)
        if (ready.isNotEmpty()) {
            MotionSensorManager.getInstance(ctx).reportActigraphSlots(ready)
        }
        val batches = ArrayList<FloatArray>()
        synchronized(pendingMovement) {
            pendingMovement.addAll(ready.toList())
            val size = batchSize.coerceAtLeast(1)
            while (pendingMovement.size >= size) {
                batches.add(pendingMovement.subList(0, size).toFloatArray())
                pendingMovement.subList(0, size).clear()
            }
        }
        for (b in batches) sendMovementData(ctx, b)

        // Heart rate: one fresh live value per 10 s slot, which is exactly how SaA spaces them.
        val hr = latestLiveHr
        if (isHrMonitoringRequested && hr != null && now - latestLiveHrAt <= LIVE_HR_FRESH_MS) {
            sendHeartRateData(ctx, floatArrayOf(hr.toFloat()))
            synchronized(liveHrSentAt) {
                liveHrSentAt.addLast(now)
                while (liveHrSentAt.size > 1000) liveHrSentAt.removeFirst()
            }
            _stats.value = _stats.value.copy(hrValuesSent = _stats.value.hrValuesSent + 1)
        }

        val covered = timeline.coveredUntil
        _stats.value = _stats.value.copy(
            movementValuesSent = _stats.value.movementValuesSent + batches.sumOf { it.size },
            lastMovementBatchMs = if (batches.isNotEmpty()) now else _stats.value.lastMovementBatchMs,
            actigraphLagSeconds = if (covered > 0) ((now - covered) / 1000).coerceAtLeast(0) else null,
        )
    }

    // ------------------------------------------------------------------ outbound intents

    private fun broadcast(context: Context, intent: Intent) {
        intent.setPackage(SAA_PACKAGE)
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast ${intent.action} failed", e)
        }
    }

    fun sendConfirmConnected(context: Context) {
        broadcast(context, Intent(ACTION_CONFIRM_CONNECTED))
        Log.i(TAG, "CONFIRM_CONNECTED sent")
    }

    /** One `DATA_UPDATE` with 10 s movement maxima (all values must be > 0, see class doc). */
    fun sendMovementData(context: Context, movementValues: FloatArray) {
        if (movementValues.isEmpty()) return
        val safe = FloatArray(movementValues.size) { maxOf(movementValues[it], ActigraphTimeline.STILL_VALUE) }
        val intent = Intent(ACTION_DATA_UPDATE).apply {
            putExtra(EXTRA_MAX_RAW_DATA, safe)
            putExtra(EXTRA_MAX_DATA, safe)
        }
        broadcast(context, intent)
        Log.d(TAG, "DATA_UPDATE x${safe.size}: ${safe.joinToString { "%.3f".format(it) }}")
    }

    /** `HR_DATA_UPDATE`; SaA spaces the values 10 s apart ending now. */
    fun sendHeartRateData(context: Context, hrValues: FloatArray) {
        if (hrValues.isEmpty()) return
        broadcast(context, Intent(ACTION_HR_DATA_UPDATE).apply { putExtra(EXTRA_DATA, hrValues) })
        Log.d(TAG, "HR_DATA_UPDATE ${hrValues.joinToString()}")
    }

    private fun sendSingleExtra(context: Context, marker: String, value: Float, timestampMs: Long) {
        broadcast(context, Intent(ACTION_EXTRA_DATA_UPDATE).apply {
            putExtra(marker, value)
            putExtra(EXTRA_DATA_TIMESTAMP, timestampMs)
        })
    }

    /**
     * Sends any of HR / SpO2 / HRV as separate timestamped `ACTION_EXTRA_DATA_UPDATE` intents.
     * [respirationRate] is accepted for API compatibility but Sleep as Android has no extra for
     * breathing rate (EXTRA_DATA_RR means R-R intervals), so it is not sent.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sendExtraSensorData(
        context: Context,
        hr: Float? = null,
        spo2: Float? = null,
        respirationRate: Float? = null,
        sdnnHrv: Float? = null,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        hr?.let { sendSingleExtra(context, EXTRA_DATA_HR, it, timestampMillis) }
        spo2?.let { sendSingleExtra(context, EXTRA_DATA_SPO2, it, timestampMillis) }
        sdnnHrv?.let { sendSingleExtra(context, EXTRA_DATA_SDNN, it, timestampMillis) }
    }

    // ------------------------------------------------------------------ wake lock

    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepAsRingConn:SaaTracking")?.apply {
                setReferenceCounted(false)
                acquire(14 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquire failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release failed", e)
        }
        wakeLock = null
    }
}
