package com.randallengineering.sleepasringconn.data

import android.content.Context
import com.randallengineering.sleepasringconn.analytics.SleepDetectionConfig
import com.randallengineering.sleepasringconn.analytics.SleepDetectionMode
import com.randallengineering.sleepasringconn.analytics.SleepDetectionSensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User settings for sleep detection, plus the in-progress manual (Start/Stop) session.
 */
object SleepDetectionPreferences {
    private const val PREFS_NAME = "sleep_detection_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_MANUAL_START = "manual_start_millis"

    /** Manual sessions shorter than this are discarded on Stop (accidental taps). */
    const val MIN_MANUAL_SESSION_MS = 10 * 60 * 1000L

    private val _manualStart = MutableStateFlow<Long?>(null)
    /** Start time of the manual session being recorded, or null. */
    val manualStart: StateFlow<Long?> = _manualStart.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context): SleepDetectionMode =
        prefs(context).getString(KEY_MODE, null)
            ?.let { runCatching { SleepDetectionMode.valueOf(it) }.getOrNull() }
            ?: SleepDetectionMode.AUTOMATIC

    fun setMode(context: Context, mode: SleepDetectionMode) {
        if (mode == getMode(context)) return
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        SleepSessionManager.onSessionsChanged(context, affectedStartMillis = null)
    }

    fun getSensitivity(context: Context): SleepDetectionSensitivity =
        prefs(context).getString(KEY_SENSITIVITY, null)
            ?.let { runCatching { SleepDetectionSensitivity.valueOf(it) }.getOrNull() }
            ?: SleepDetectionSensitivity.BALANCED

    fun setSensitivity(context: Context, sensitivity: SleepDetectionSensitivity) {
        if (sensitivity == getSensitivity(context)) return
        prefs(context).edit().putString(KEY_SENSITIVITY, sensitivity.name).apply()
        SleepSessionManager.onSessionsChanged(context, affectedStartMillis = null)
    }

    fun getConfig(context: Context): SleepDetectionConfig =
        SleepDetectionConfig.of(getSensitivity(context), getMode(context))

    /** Loads the persisted manual-session start into [manualStart]; call before reading it. */
    fun refreshManualStart(context: Context): Long? {
        val v = prefs(context).getLong(KEY_MANUAL_START, 0L).takeIf { it > 0L }
        _manualStart.value = v
        return v
    }

    /** Starts recording a manual session now. No-op if one is already running. */
    fun startManualSession(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        if (refreshManualStart(context) != null) return
        prefs(context).edit().putLong(KEY_MANUAL_START, nowMillis).apply()
        _manualStart.value = nowMillis
    }

    /**
     * Stops the manual session and saves it. Returns true when a session was saved, false when
     * none was running or it was too short to keep.
     */
    fun stopManualSession(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val start = refreshManualStart(context) ?: return false
        prefs(context).edit().remove(KEY_MANUAL_START).apply()
        _manualStart.value = null
        if (nowMillis - start < MIN_MANUAL_SESSION_MS) return false
        SleepSessionManager.addManualSession(context, start, nowMillis)
        return true
    }

    /** Throws away a running manual session without saving it. */
    fun cancelManualSession(context: Context) {
        prefs(context).edit().remove(KEY_MANUAL_START).apply()
        _manualStart.value = null
    }
}
