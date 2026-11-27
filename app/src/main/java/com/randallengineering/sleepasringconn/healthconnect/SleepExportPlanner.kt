package com.randallengineering.sleepasringconn.healthconnect

import com.randallengineering.sleepasringconn.analytics.SleepSession
import com.randallengineering.sleepasringconn.analytics.SleepStage

/**
 * Pure (Android-free) logic that turns the app's staged sessions into what Health Connect
 * accepts: stages that are contiguous, non-overlapping, merged into runs and clamped inside
 * the session bounds. Health Connect rejects a whole SleepSessionRecord whose stages spill
 * past its start/end, so the clamping is what keeps the staged record from being dropped.
 */
object SleepExportPlanner {

    const val EPOCH_MS = 150_000L

    /** A session is considered finished once this much newer ring data exists after it. */
    const val SETTLE_AFTER_LAST_DATA_MS = 30 * 60 * 1000L

    /** ...or once it ended this long ago in wall-clock time, even with no newer data. */
    const val SETTLE_AFTER_WALL_CLOCK_MS = 2 * 60 * 60 * 1000L

    data class StageSpan(val startMillis: Long, val endMillis: Long, val stage: SleepStage)

    fun buildStages(session: SleepSession): List<StageSpan> {
        val start = session.startTimeMillis
        val end = session.endTimeMillis
        if (end <= start) return emptyList()

        val epochs = session.epochs
            .filter { it.timestampMillis < end && it.timestampMillis + EPOCH_MS > start }
            .sortedBy { it.timestampMillis }
        if (epochs.isEmpty()) return emptyList()

        val raw = mutableListOf<StageSpan>()
        for ((i, epoch) in epochs.withIndex()) {
            val s = maxOf(epoch.timestampMillis, start, raw.lastOrNull()?.endMillis ?: start)
            var e = minOf(epoch.timestampMillis + EPOCH_MS, end)
            val next = epochs.getOrNull(i + 1)
            if (next != null) e = minOf(e, next.timestampMillis)
            if (e > s) raw.add(StageSpan(s, e, epoch.stage))
        }

        // Merge back-to-back spans of the same stage into one run.
        val merged = mutableListOf<StageSpan>()
        for (span in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.stage == span.stage && last.endMillis == span.startMillis) {
                merged[merged.size - 1] = last.copy(endMillis = span.endMillis)
            } else {
                merged.add(span)
            }
        }
        return merged
    }

    /** True once the session can no longer grow with incoming data. */
    fun isSettled(session: SleepSession, latestDataMillis: Long, nowMillis: Long): Boolean =
        latestDataMillis - session.endTimeMillis >= SETTLE_AFTER_LAST_DATA_MS ||
            nowMillis - session.endTimeMillis >= SETTLE_AFTER_WALL_CLOCK_MS

    /** Stable fingerprint of what would be exported; unchanged => nothing to rewrite. */
    fun fingerprint(sessions: List<SleepSession>): String = sessions
        .sortedBy { it.startTimeMillis }
        .joinToString("|") { s ->
            val stages = buildStages(s).joinToString(",") { "${it.startMillis}-${it.endMillis}-${it.stage.ordinal}" }
            "${s.startTimeMillis}:${s.endTimeMillis}:${s.isNap}:${s.sessionLabel}:${stages.hashCode()}"
        }
        .hashCode()
        .toString()
}
