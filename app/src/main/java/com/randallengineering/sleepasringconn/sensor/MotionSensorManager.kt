package com.randallengineering.sleepasringconn.sensor

import android.content.Context
import android.util.Log
import com.randallengineering.sleepasringconn.sleepasandroid.SleepAsAndroidBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-precision Ring Motion & Actigraphy Engine.
 *
 * Exclusively driven by genuine RingConn smart ring telemetry (3-axis activity counts,
 * sub-epoch motion pulses, step deltas, and ring work mode).
 *
 * ZERO phone hardware sensors are registered, queried, or utilized.
 */
class MotionSensorManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "MotionSensorManager"

        @Volatile
        private var INSTANCE: MotionSensorManager? = null

        fun getInstance(context: Context): MotionSensorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MotionSensorManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val _rawAcceleration = MutableStateFlow(Triple(0f, 0f, 0f))
    val rawAcceleration: StateFlow<Triple<Float, Float, Float>> = _rawAcceleration.asStateFlow()

    private val _currentMagnitude = MutableStateFlow(0f)
    val currentMagnitude: StateFlow<Float> = _currentMagnitude.asStateFlow()

    private val _last10sMaxAcceleration = MutableStateFlow(0f)
    val last10sMaxAcceleration: StateFlow<Float> = _last10sMaxAcceleration.asStateFlow()

    private val _lastDataSource = MutableStateFlow("Ring Hardware")
    val lastDataSource: StateFlow<String> = _lastDataSource.asStateFlow()

    private var currentIntervalPeak: Float = 0f
    private val batchBuffer = mutableListOf<Float>()
    private var isListening = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var aggregationJob: Job? = null
    private var decayJob: Job? = null
    private var phaseAngle: Double = 0.0

    fun start() {
        if (isListening) return
        isListening = true
        Log.i(TAG, "Ring motion tracking started. (Zero phone sensors active; Ring telemetry only)")

        start10SecondAggregationLoop()
        startDecayLoop()
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        aggregationJob?.cancel()
        aggregationJob = null
        decayJob?.cancel()
        decayJob = null
        batchBuffer.clear()
        _currentMagnitude.value = 0f
        _rawAcceleration.value = Triple(0f, 0f, 0f)
        Log.i(TAG, "Ring motion sensor stopped.")
    }

    /**
     * Reports physical motion detected exclusively from RingConn smart ring telemetry.
     *
     * @param magnitudeMps2 Motion intensity in m/s² equivalent (0.0 = stillness/deep sleep, >0.1 = movement)
     * @param source Tag describing the RingConn packet source (e.g., "Ring Actigraphy 0x4C", "Ring Steps 0x87")
     */
    fun reportRingMotion(magnitudeMps2: Float, source: String = "Ring BLE") {
        _lastDataSource.value = source
        synchronized(this) {
            if (magnitudeMps2 > currentIntervalPeak) {
                currentIntervalPeak = magnitudeMps2
            }
        }
        _currentMagnitude.value = magnitudeMps2

        // Synthesize 3-axis vector representation for UI reticle/visualizer based strictly on ring motion
        phaseAngle += 0.5
        val x = (magnitudeMps2 * cos(phaseAngle)).toFloat()
        val y = (magnitudeMps2 * sin(phaseAngle)).toFloat()
        val z = magnitudeMps2
        _rawAcceleration.value = Triple(x, y, z)
    }

    /**
     * Reports multiple sub-epoch motion values from RingConn history pages.
     */
    fun reportRingBatchMotions(motionsMps2: List<Float>, source: String = "Ring Epoch Records") {
        if (motionsMps2.isEmpty()) return
        val maxMotion = motionsMps2.maxOrNull() ?: 0f
        reportRingMotion(maxMotion, source)

        synchronized(this) {
            for (m in motionsMps2) {
                if (m > currentIntervalPeak) {
                    currentIntervalPeak = m
                }
            }
        }
    }

    private fun startDecayLoop() {
        decayJob?.cancel()
        decayJob = scope.launch {
            while (isActive && isListening) {
                delay(250)
                val curr = _currentMagnitude.value
                if (curr > 0.01f) {
                    val decayed = curr * 0.75f
                    val nextVal = if (decayed < 0.01f) 0f else decayed
                    _currentMagnitude.value = nextVal
                    val currentX = _rawAcceleration.value.first * 0.75f
                    val currentY = _rawAcceleration.value.second * 0.75f
                    val currentZ = _rawAcceleration.value.third * 0.75f
                    _rawAcceleration.value = Triple(currentX, currentY, currentZ)
                }
            }
        }
    }

    private fun start10SecondAggregationLoop() {
        aggregationJob?.cancel()
        aggregationJob = scope.launch {
            while (isActive && isListening) {
                val isTestMode = SleepAsAndroidBridge.batchSize <= 1
                val sampleIntervalMs = if (isTestMode) 1000L else 10000L

                delay(sampleIntervalMs)

                val intervalMax: Float
                synchronized(this@MotionSensorManager) {
                    intervalMax = currentIntervalPeak
                    currentIntervalPeak = 0f
                }

                _last10sMaxAcceleration.value = intervalMax

                // If Sleep as Android is currently tracking, collect and dispatch batches
                if (SleepAsAndroidBridge.isTrackingActive) {
                    batchBuffer.add(intervalMax)

                    val targetBatchSize = SleepAsAndroidBridge.batchSize
                    if (batchBuffer.size >= targetBatchSize) {
                        val batchToSend = batchBuffer.toFloatArray()
                        batchBuffer.clear()
                        SleepAsAndroidBridge.sendMovementData(context, batchToSend)
                    }
                }
            }
        }
    }
}
