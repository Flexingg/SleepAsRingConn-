package com.randallengineering.sleepasringconn.data

import android.content.Context
import com.randallengineering.sleepasringconn.analytics.SleepDetectionConfig
import com.randallengineering.sleepasringconn.analytics.SleepDetectionMode
import com.randallengineering.sleepasringconn.analytics.SleepDetectionSensitivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Detection settings in effect from [fromMillis] until the next entry. */
data class DetectionSettingsEntry(
    val fromMillis: Long,
    val mode: SleepDetectionMode,
    val sensitivity: SleepDetectionSensitivity
) {
    val config: SleepDetectionConfig get() = SleepDetectionConfig.of(sensitivity, mode)
}

/**
 * User settings for sleep detection, plus the in-progress manual (Start/Stop) session.
 *
 * Settings only apply going forward: every change is stored with the time it was made, and
 * each session is detected with the settings that were in effect when it started, so past
 * sessions never change when the mode or sensitivity does.
 */
object SleepDetectionPreferences {
    private const val PREFS_NAME = "sleep_detection_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_MANUAL_START = "manual_start_millis"
    private const val KEY_HISTORY = "settings_history_json"

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
        recordChange(context, mode, getSensitivity(context))
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getSensitivity(context: Context): SleepDetectionSensitivity =
        prefs(context).getString(KEY_SENSITIVITY, null)
            ?.let { runCatching { SleepDetectionSensitivity.valueOf(it) }.getOrNull() }
            ?: SleepDetectionSensitivity.BALANCED

    fun setSensitivity(context: Context, sensitivity: SleepDetectionSensitivity) {
        if (sensitivity == getSensitivity(context)) return
        recordChange(context, getMode(context), sensitivity)
        prefs(context).edit().putString(KEY_SENSITIVITY, sensitivity.name).apply()
    }

    /** Current settings (the ones new sessions are detected with). */
    fun getConfig(context: Context): SleepDetectionConfig =
        SleepDetectionConfig.of(getSensitivity(context), getMode(context))

    /**
     * Every settings period, oldest first. The first entry starts at [Long.MIN_VALUE]: the
     * settings used before the first change (the current ones if nothing was ever changed).
     */
    fun getHistory(context: Context): List<DetectionSettingsEntry> {
        val json = prefs(context).getString(KEY_HISTORY, null)
            ?: return listOf(DetectionSettingsEntry(Long.MIN_VALUE, getMode(context), getSensitivity(context)))
        val list = mutableListOf<DetectionSettingsEntry>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(
                    DetectionSettingsEntry(
                        fromMillis = o.getLong("from"),
                        mode = runCatching { SleepDetectionMode.valueOf(o.getString("mode")) }
                            .getOrDefault(SleepDetectionMode.AUTOMATIC),
                        sensitivity = runCatching { SleepDetectionSensitivity.valueOf(o.getString("sensitivity")) }
                            .getOrDefault(SleepDetectionSensitivity.BALANCED)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (list.isEmpty()) {
            return listOf(DetectionSettingsEntry(Long.MIN_VALUE, getMode(context), getSensitivity(context)))
        }
        return list.sortedBy { it.fromMillis }
    }

    /** Stores a settings change taking effect now, keeping the earlier periods as they were. */
    private fun recordChange(
        context: Context,
        mode: SleepDetectionMode,
        sensitivity: SleepDetectionSensitivity,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        // getHistory() before the first change returns the current settings from the start
        // of time, which keeps everything detected so far exactly as it is.
        val history = getHistory(context).filter { it.fromMillis < nowMillis }.toMutableList()
        history.add(DetectionSettingsEntry(nowMillis, mode, sensitivity))
        // Collapse consecutive identical periods (e.g. toggling back and forth)
        val collapsed = mutableListOf<DetectionSettingsEntry>()
        for (e in history) {
            val last = collapsed.lastOrNull()
            if (last != null && last.mode == e.mode && last.sensitivity == e.sensitivity) continue
            collapsed.add(e)
        }
        val array = JSONArray()
        for (e in collapsed) {
            array.put(JSONObject().apply {
                put("from", e.fromMillis)
                put("mode", e.mode.name)
                put("sensitivity", e.sensitivity.name)
            })
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
        // Only sessions from now on can differ; the Health Connect fingerprint picks that up.
        SleepSessionManager.onSessionsChanged(context, affectedStartMillis = nowMillis)
    }

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
