package com.randallengineering.sleepasringconn.sensor

import android.content.Context
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ring motion state for the dashboard visualiser.
 *
 * Display only. The Sleep as Android movement stream is produced by
 * [com.randallengineering.sleepasringconn.sleepasandroid.ActigraphTimeline], which works from the
 * ring's time-stamped history bins rather than from "whatever arrived in the last 10 s".
 * No phone sensors are used.
 */
class MotionSensorManager private constructor(@Suppress("unused") private val context: Context) {
    companion object {
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

    private val _lastDataSource = MutableStateFlow("Ring history (0x4C)")
    val lastDataSource: StateFlow<String> = _lastDataSource.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var users = 0
    private var decayJob: Job? = null
    private var phaseAngle = 0.0

    /** Reference-counted: the dashboard and the SaA bridge can both hold it open. */
    @Synchronized
    fun start() {
        users++
        if (decayJob?.isActive == true) return
        decayJob = scope.launch {
            while (isActive) {
                delay(250)
                val curr = _currentMagnitude.value
                if (curr > 0.01f) {
                    _currentMagnitude.value = if (curr * 0.8f < 0.01f) 0f else curr * 0.8f
                    val (x, y, z) = _rawAcceleration.value
                    _rawAcceleration.value = Triple(x * 0.8f, y * 0.8f, z * 0.8f)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            decayJob?.cancel()
            decayJob = null
            _currentMagnitude.value = 0f
            _rawAcceleration.value = Triple(0f, 0f, 0f)
        }
    }

    /** A single motion observation (e.g. a pedometer delta from the descriptor). */
    fun reportRingMotion(magnitude: Float, source: String = "Ring BLE") {
        _lastDataSource.value = source
        _currentMagnitude.value = magnitude
        phaseAngle += 0.5
        _rawAcceleration.value = Triple(
            (magnitude * cos(phaseAngle)).toFloat(),
            (magnitude * sin(phaseAngle)).toFloat(),
            magnitude,
        )
    }

    /** Motion levels decoded from a history page (one per 30 s bin). */
    fun reportRingBatchMotions(motions: List<Float>, source: String = "Ring history (0x4C)") {
        val peak = motions.maxOrNull() ?: return
        _last10sMaxAcceleration.value = peak
        reportRingMotion(peak, source)
    }

    /** The 10 s values just handed to Sleep as Android. */
    fun reportActigraphSlots(values: FloatArray) {
        val peak = values.maxOrNull() ?: return
        _last10sMaxAcceleration.value = values.last()
        reportRingMotion(peak, "Sleep as Android actigraph")
    }
}
