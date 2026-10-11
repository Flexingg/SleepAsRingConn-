package com.randallengineering.sleepasringconn.protocol

import com.randallengineering.sleepasringconn.data.DeviceStatusEntity
import java.util.Calendar

/**
 * Pure step fold (port of OpenCircuit StepAccumulator.swift, PROTOCOL.md 5.4).
 *
 * The 0x10/0x87 steps field is a per-15-minute-bucket running count (cleared at each wall-clock
 * quarter boundary), NOT a daily total or per-frame increment. Folding observed values:
 * while the value climbs credit the increment; when it drops (bucket rolled) credit the new raw
 * value in full; on a new calendar day credit the raw value in full.
 */
object StepAccumulator {
    /** One credited delta, attributed to the frame timestamp it was observed in. */
    data class Credit(val timestampMillis: Long, val delta: Int)

    /** Delta to add for a single observation. */
    fun update(previousRaw: Int?, newRaw: Int, dayChanged: Boolean): Int {
        val previous = previousRaw ?: return newRaw
        if (dayChanged) return newRaw
        return if (newRaw >= previous) newRaw - previous else newRaw
    }

    private fun dayStart(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Fold time-ordered (timestampMillis, raw) samples into positive credits. */
    fun fold(samples: List<Pair<Long, Int>>): List<Credit> {
        val out = ArrayList<Credit>()
        var prevRaw: Int? = null
        var prevDay: Long? = null
        for ((ts, raw) in samples.sortedBy { it.first }) {
            val day = dayStart(ts)
            val dayChanged = prevRaw != null && prevDay != day
            val delta = update(prevRaw, raw, dayChanged)
            if (delta > 0) out.add(Credit(ts, delta))
            prevRaw = raw
            prevDay = day
        }
        return out
    }

    fun foldLogs(logs: List<DeviceStatusEntity>): List<Credit> =
        fold(logs.map { it.timestampMillis to it.quarterHourSteps })

    /** Total steps credited over all given logs (callers pass one day's logs for a daily total). */
    fun total(logs: List<DeviceStatusEntity>): Int = foldLogs(logs).sumOf { it.delta }

    /** Daily total for the local day containing [dayMillis], from any list of logs. */
    fun dailyTotal(logs: List<DeviceStatusEntity>, dayMillis: Long): Int {
        val start = dayStart(dayMillis)
        val end = start + 26L * 3600_000L // DST-safe upper bound, filtered below
        return total(logs.filter { it.timestampMillis in start until end && dayStart(it.timestampMillis) == start })
    }
}
