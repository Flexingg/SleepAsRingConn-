package com.randallengineering.sleepasringconn.data

import android.content.Context
import com.randallengineering.sleepasringconn.analytics.SleepSession
import com.randallengineering.sleepasringconn.analytics.SleepDetectionMode
import com.randallengineering.sleepasringconn.analytics.SleepStagingEngine
import com.randallengineering.sleepasringconn.healthconnect.HealthConnectExporter
import com.randallengineering.sleepasringconn.healthconnect.HealthConnectSyncScheduler
import com.randallengineering.sleepasringconn.protocol.BulkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class SessionOverride(
    val originalStartTimeMillis: Long,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val isNap: Boolean? = null,
    val isDeleted: Boolean = false,
    val isManualAddition: Boolean = false
)

object SleepSessionManager {
    private const val PREFS_NAME = "sleep_session_overrides_pref"
    private const val KEY_OVERRIDES = "session_overrides_json"
    private const val MATCH_TOLERANCE_MS = 45 * 60 * 1000L

    /** Index of the override whose original start is closest to [start] (within tolerance), or -1. */
    private fun closestOverride(overrides: List<SessionOverride>, start: Long): Int {
        var best = -1
        var bestDiff = Long.MAX_VALUE
        for ((i, ov) in overrides.withIndex()) {
            val diff = abs(ov.originalStartTimeMillis - start)
            if (diff < MATCH_TOLERANCE_MS && diff < bestDiff) {
                best = i
                bestDiff = diff
            }
        }
        return best
    }

    /**
     * Called after anything that changes which sessions exist or their times: makes the next
     * Health Connect export rewrite them (so deleted sessions are removed there too) and, when
     * auto-sync is on, runs that export now.
     */
    fun onSessionsChanged(context: Context, affectedStartMillis: Long?) {
        HealthConnectExporter.invalidateSleep(context, affectedStartMillis)
        if (HealthConnectSyncScheduler.isAutoSyncEnabled(context)) {
            try {
                HealthConnectSyncScheduler.triggerImmediateSync(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getOverrides(context: Context): List<SessionOverride> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_OVERRIDES, null) ?: return emptyList()
        val list = mutableListOf<SessionOverride>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val origStart = obj.getLong("originalStartTimeMillis")
                val start = obj.getLong("startTimeMillis")
                val end = obj.getLong("endTimeMillis")
                val isNap = if (obj.has("isNap")) obj.getBoolean("isNap") else null
                val isDeleted = obj.optBoolean("isDeleted", false)
                val isManual = obj.optBoolean("isManualAddition", false)
                list.add(SessionOverride(origStart, start, end, isNap, isDeleted, isManual))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun saveOverrides(context: Context, overrides: List<SessionOverride>) {
        val array = JSONArray()
        for (ov in overrides) {
            val obj = JSONObject().apply {
                put("originalStartTimeMillis", ov.originalStartTimeMillis)
                put("startTimeMillis", ov.startTimeMillis)
                put("endTimeMillis", ov.endTimeMillis)
                ov.isNap?.let { put("isNap", it) }
                put("isDeleted", ov.isDeleted)
                put("isManualAddition", ov.isManualAddition)
            }
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OVERRIDES, array.toString())
            .apply()
    }

    fun saveSessionEdit(
        context: Context,
        originalStart: Long,
        newStart: Long,
        newEnd: Long,
        isNap: Boolean? = null
    ) {
        val current = getOverrides(context).toMutableList()
        val matchIndex = closestOverride(current, originalStart)
        val override = SessionOverride(
            originalStartTimeMillis = if (matchIndex >= 0) current[matchIndex].originalStartTimeMillis else originalStart,
            startTimeMillis = newStart,
            endTimeMillis = newEnd,
            isNap = isNap,
            isDeleted = false,
            isManualAddition = if (matchIndex >= 0) current[matchIndex].isManualAddition else false
        )
        if (matchIndex >= 0) {
            current[matchIndex] = override
        } else {
            current.add(override)
        }
        saveOverrides(context, current)
        onSessionsChanged(context, minOf(originalStart, newStart))
    }

    fun resetSessionEdit(context: Context, originalStart: Long) {
        val current = getOverrides(context).toMutableList()
        val matchIndex = closestOverride(current, originalStart)
        if (matchIndex >= 0) {
            val existing = current[matchIndex]
            current.removeAt(matchIndex)
            saveOverrides(context, current)
            onSessionsChanged(context, minOf(originalStart, existing.startTimeMillis))
        }
    }

    fun deleteSession(context: Context, originalStart: Long) {
        val current = getOverrides(context).toMutableList()
        val matchIndex = closestOverride(current, originalStart)
        if (matchIndex >= 0) {
            val existing = current[matchIndex]
            if (existing.isManualAddition) {
                current.removeAt(matchIndex)
            } else {
                current[matchIndex] = existing.copy(isDeleted = true)
            }
        } else {
            current.add(
                SessionOverride(
                    originalStartTimeMillis = originalStart,
                    startTimeMillis = originalStart,
                    endTimeMillis = originalStart + 30 * 60 * 1000L,
                    isDeleted = true
                )
            )
        }
        saveOverrides(context, current)
        onSessionsChanged(context, originalStart)
    }

    fun addManualSession(
        context: Context,
        startTime: Long,
        endTime: Long,
        isNap: Boolean? = null
    ) {
        val current = getOverrides(context).toMutableList()
        current.add(
            SessionOverride(
                originalStartTimeMillis = startTime,
                startTimeMillis = startTime,
                endTimeMillis = endTime,
                isNap = isNap,
                isDeleted = false,
                isManualAddition = true
            )
        )
        saveOverrides(context, current)
        onSessionsChanged(context, startTime)
    }

    /**
     * Detects sessions using, for each one, the settings in effect when it started, so a mode
     * or sensitivity change only affects sessions from then on. Manual periods detect nothing.
     */
    internal fun detectWithSettingsHistory(
        records: List<BulkRecord>,
        history: List<DetectionSettingsEntry>
    ): List<SleepSession> {
        if (records.isEmpty()) return emptyList()
        val firstData = records.minOf { it.timestampMillis }
        val lastData = records.maxOf { it.timestampMillis }
        val found = mutableListOf<SleepSession>()
        val cache = mutableMapOf<com.randallengineering.sleepasringconn.analytics.SleepDetectionConfig, List<SleepSession>>()
        for ((i, entry) in history.withIndex()) {
            val from = entry.fromMillis
            val until = history.getOrNull(i + 1)?.fromMillis ?: Long.MAX_VALUE
            if (entry.mode == SleepDetectionMode.MANUAL) continue
            if (until <= firstData || from > lastData) continue // no data in this period
            val all = cache.getOrPut(entry.config) {
                SleepStagingEngine.extractAllSleepSessions(records, entry.config)
            }
            found += all.filter { it.startTimeMillis >= from && it.startTimeMillis < until }
        }
        // Around a change, sessions found under the old and new settings can overlap: keep the
        // one that started first.
        val kept = mutableListOf<SleepSession>()
        for (s in found.sortedBy { it.startTimeMillis }) {
            val last = kept.lastOrNull()
            if (last != null && s.startTimeMillis < last.endTimeMillis) continue
            kept.add(s)
        }
        return kept.sortedByDescending { it.startTimeMillis }
    }

    suspend fun getProcessedSessions(
        context: Context,
        epochEntities: List<EpochEntity>,
        persist: Boolean = true
    ): List<SleepSession> = withContext(Dispatchers.Default) {
        val bulkRecords = epochEntities.mapNotNull { BulkRecord.parseRecord(it.rawBytes) }
        val autoSessions = detectWithSettingsHistory(bulkRecords, SleepDetectionPreferences.getHistory(context))
        val overrides = getOverrides(context)

        val finalSessions = mutableListOf<SleepSession>()
        val matchedOverrideIndices = mutableSetOf<Int>()

        // Apply overrides to auto-detected sessions
        for (session in autoSessions) {
            val matchIdx = closestOverride(overrides, session.startTimeMillis)

            if (matchIdx >= 0) {
                matchedOverrideIndices.add(matchIdx)
                val ov = overrides[matchIdx]
                if (!ov.isDeleted) {
                    val staged = SleepStagingEngine.stageCustomSession(
                        records = bulkRecords,
                        startTimeMillis = ov.startTimeMillis,
                        endTimeMillis = ov.endTimeMillis,
                        isNapOverride = ov.isNap,
                        isUserEdited = true,
                        originalStart = session.startTimeMillis,
                        originalEnd = session.endTimeMillis
                    )
                    finalSessions.add(staged)
                }
            } else {
                finalSessions.add(session)
            }
        }

        // Add any manual sessions from overrides that were not matched to auto-detected sessions
        for ((idx, ov) in overrides.withIndex()) {
            if (!matchedOverrideIndices.contains(idx) && !ov.isDeleted) {
                val staged = SleepStagingEngine.stageCustomSession(
                    records = bulkRecords,
                    startTimeMillis = ov.startTimeMillis,
                    endTimeMillis = ov.endTimeMillis,
                    isNapOverride = ov.isNap,
                    isUserEdited = true,
                    originalStart = ov.originalStartTimeMillis,
                    originalEnd = ov.endTimeMillis
                )
                finalSessions.add(staged)
            }
        }

        val sorted = finalSessions.sortedByDescending { it.startTimeMillis }

        // Sync into Room DB for Goals (skipped for one-off history exports)
        if (persist) try {
            val database = AppDatabase.getDatabase(context)
            val entities = sorted.map { s ->
                SleepSessionEntity(
                    startTimeMillis = s.startTimeMillis,
                    endTimeMillis = s.endTimeMillis,
                    totalDurationMinutes = s.totalInBedMinutes,
                    awakeMinutes = s.awakeMinutes,
                    lightMinutes = s.lightMinutes,
                    deepMinutes = s.deepMinutes,
                    remMinutes = s.remMinutes,
                    avgHeartRate = s.averageHeartRate,
                    avgHrvRmssd = s.averageHrvRmssd,
                    avgSpo2 = s.averageSpo2,
                    avgRespiratoryRate = s.averageRespiratoryRate,
                    sleepScore = s.sleepScore,
                    isNap = s.isNap,
                    isUserEdited = s.isUserEdited,
                    sessionLabel = s.sessionLabel
                )
            }
            database.sleepSessionDao().replaceAllSessions(entities)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        sorted
    }
}
