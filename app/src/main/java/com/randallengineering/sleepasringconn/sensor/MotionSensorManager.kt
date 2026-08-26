package com.randallengineering.sleepasringconn.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.randallengineering.sleepasringconn.sleepasandroid.SleepAsAndroidBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * High-precision Accelerometer & Motion Telemetry Engine.
 *
 * Fuses native hardware accelerometer changes (m/s²) with RingConn BLE IMU motion counts
 * and provides 10-second max acceleration batches to Sleep as Android per the official spec.
 */
class MotionSensorManager private constructor(private val context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "MotionSensorManager"
        const val STANDARD_GRAVITY = 9.80665f

        @Volatile
        private var INSTANCE: MotionSensorManager? = null

        fun getInstance(context: Context): MotionSensorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MotionSensorManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _rawAcceleration = MutableStateFlow(Triple(0f, 0f, 0f))
    val rawAcceleration: StateFlow<Triple<Float, Float, Float>> = _rawAcceleration.asStateFlow()

    private val _currentMagnitude = MutableStateFlow(0f)
    val currentMagnitude: StateFlow<Float> = _currentMagnitude.asStateFlow()

    private val _last10sMaxAcceleration = MutableStateFlow(0f)
    val last10sMaxAcceleration: StateFlow<Float> = _last10sMaxAcceleration.asStateFlow()

    private var currentIntervalPeak: Float = 0f
    private val batchBuffer = mutableListOf<Float>()
    private var isListening = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var aggregationJob: Job? = null

    fun start() {
        if (isListening) return
        isListening = true

        accelerometer?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            Log.i(TAG, "Hardware accelerometer registered successfully.")
        }

        start10SecondAggregationLoop()
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        sensorManager?.unregisterListener(this)
        aggregationJob?.cancel()
        aggregationJob = null
        batchBuffer.clear()
        Log.i(TAG, "Motion sensor stopped.")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        _rawAcceleration.value = Triple(x, y, z)

        // Raw vector magnitude
        val vectorMagnitude = sqrt(x * x + y * y + z * z)
        // Delta from standard gravity (rest = 0.0 m/s²)
        val deltaAccel = abs(vectorMagnitude - STANDARD_GRAVITY)

        // Filter sensor noise floor (sub-0.08 m/s² is sensor thermal noise at rest)
        val filteredDelta = if (deltaAccel > 0.08f) deltaAccel else 0.0f
        _currentMagnitude.value = filteredDelta

        synchronized(this) {
            if (filteredDelta > currentIntervalPeak) {
                currentIntervalPeak = filteredDelta
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Reports physical motion detected from RingConn BLE telemetry (e.g. 0x4C activity counts, step delta).
     */
    fun reportRingMotion(magnitudeMps2: Float) {
        synchronized(this) {
            if (magnitudeMps2 > currentIntervalPeak) {
                currentIntervalPeak = magnitudeMps2
            }
        }
        _currentMagnitude.value = magnitudeMps2
    }

    private fun start10SecondAggregationLoop() {
        aggregationJob?.cancel()
        aggregationJob = scope.launch {
            while (isActive && isListening) {
                // In test sensor mode (batchSize <= 1), sample every 1 second; otherwise every 10 seconds per SaA docs
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
