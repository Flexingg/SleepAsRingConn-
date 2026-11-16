package com.randallengineering.sleepasringconn.sleepasandroid

import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.protocol.BulkRecordLayout
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns the ring's motion history into the 10-second movement stream Sleep as Android expects.
 *
 * Why this exists: a RingConn Gen 2 has no live accelerometer stream. Its only motion data is the
 * five 30-second activity counts inside each 2.5-minute `0x4C` history epoch, which reach the
 * phone minutes after the fact. Sleep as Android, on the other hand, has no timestamps on
 * `DATA_UPDATE`: it simply appends every value it receives as the next 10-second slot (see
 * GenericWatchReceiver). So what matters is that we emit exactly one value per 10 s of wall time,
 * in order, each value describing the right moment.
 *
 * This class keeps a playout cursor in wall time. Each [drain] emits the slots between the cursor
 * and the newest moment the ring has reported on (or, if the ring has gone quiet, up to [maxLagMs]
 * behind now, with a neutral filler, so the stream never stalls and SaA's 4-minute restart timer
 * never fires). The resulting actigraph is shifted by the ring's reporting latency (typically
 * 2-4 minutes) but is otherwise time-aligned and continuous.
 *
 * Every value is strictly positive: when the first value of a batch is > 0, SaA discards every
 * 0.0 in that batch, which silently compresses the timeline.
 */
class ActigraphTimeline(
    private val slotMs: Long = SLOT_MS,
    private val maxLagMs: Long = DEFAULT_MAX_LAG_MS,
) {
    /** 30 s bin start (wall ms, clock-corrected) -> motion count above the resting floor. */
    private val bins = TreeMap<Long, Int>()
    private val seenCounters = HashSet<Long>()
    private val floorWindow = ArrayDeque<Int>()

    private var cursorMs = -1L
    private var coveredUntilMs = 0L
    private var calibrated = false

    /** Correction added to ring timestamps (whole hours; see [calibrate]). */
    var clockOffsetMs: Long = 0L
        private set

    val isStarted: Boolean get() = cursorMs >= 0

    /** Wall time (ms) up to which the ring has delivered motion; 0 before the first epoch. */
    val coveredUntil: Long get() = coveredUntilMs

    /**
     * Starts the playout [leadMs] in the past, so motion the ring already reported for the last
     * few minutes plays out at once instead of the graph staying empty until the next epoch.
     */
    @Synchronized
    fun start(nowMs: Long, leadMs: Long = 0L) {
        val from = nowMs - leadMs
        cursorMs = from - from % slotMs
        bins.clear()
        seenCounters.clear()
        coveredUntilMs = 0L
        // floorWindow and calibration survive restarts: the resting floor is a hardware property
        // and the clock offset does not change between nights.
    }

    @Synchronized
    fun stop() {
        cursorMs = -1L
        bins.clear()
        seenCounters.clear()
    }

    /** Wall-clock time (ms) at which [record] started, after clock correction. */
    fun wallTimeOf(record: BulkRecord): Long = record.timestampMillis + clockOffsetMs

    /**
     * Adds history epochs (any order, duplicates ignored). IDLE epochs (ring off-finger or on the
     * charger) carry no motion and are left as gaps.
     */
    @Synchronized
    fun ingest(records: List<BulkRecord>, nowMs: Long, allowCalibration: Boolean = true) {
        if (records.isEmpty()) return
        // Only a live drain reaches "now"; stored history may be hours old and would fake an offset.
        if (allowCalibration) calibrate(records, nowMs)
        for (r in records.sortedBy { it.counter }) {
            if (!seenCounters.add(r.counter)) continue
            val start = wallTimeOf(r)
            val end = start + BulkRecord.EPOCH_SECONDS * 1000L
            if (end > coveredUntilMs) coveredUntilMs = end
            if (r.layout == BulkRecordLayout.IDLE) continue
            val counts = r.motion
            for (c in counts) {
                floorWindow.addLast(c)
                while (floorWindow.size > FLOOR_WINDOW_BINS) floorWindow.removeFirst()
            }
            val floor = restingFloor()
            counts.forEachIndexed { i, c ->
                bins[start + i * BIN_MS] = (c - floor).coerceAtLeast(0)
            }
        }
        if (seenCounters.size > 4096) seenCounters.clear()
    }

    /**
     * Emits the movement values for every 10 s slot that is now ready, oldest first.
     * Returns an empty array when nothing new is ready.
     */
    @Synchronized
    fun drain(nowMs: Long): FloatArray {
        if (cursorMs < 0) return FloatArray(0)
        val ready = maxOf(minOf(coveredUntilMs, nowMs), nowMs - maxLagMs)
        val until = ready - ready % slotMs
        if (until <= cursorMs) return FloatArray(0)
        val n = ((until - cursorMs) / slotMs).toInt()
        val out = FloatArray(n)
        for (k in 0 until n) {
            val t = cursorMs + k * slotMs
            val bin = bins.floorEntry(t)
            out[k] = if (bin != null && t < bin.key + BIN_MS) toSaaValue(bin.value) else STILL_VALUE
        }
        cursorMs = until
        bins.headMap(cursorMs - 60 * 60_000L).clear()
        return out
    }

    private fun restingFloor(): Int {
        if (floorWindow.isEmpty()) return 0
        val sorted = floorWindow.sorted()
        return sorted[(sorted.size * FLOOR_PERCENTILE).toInt().coerceIn(0, sorted.size - 1)]
    }

    /**
     * The ring's counters are documented with two anchors exactly 4 h apart, and the same ring has
     * been seen to jump between them. A drain always returns records up to about "now", so if the
     * newest record ends a whole number of hours away from the phone clock, that is the offset.
     */
    private fun calibrate(records: List<BulkRecord>, nowMs: Long) {
        if (calibrated) return
        val newest = records.maxByOrNull { it.counter } ?: return
        val endMs = newest.timestampMillis + BulkRecord.EPOCH_SECONDS * 1000L
        val residual = nowMs - endMs
        // Only calibrate from a drain that reached the present (within 7 h either way).
        if (abs(residual) > 7 * HOUR_MS) return
        calibrated = true
        val hours = (residual.toDouble() / HOUR_MS).roundToLong()
        // Only the documented anchor difference (4 h) is corrected; anything else is left alone.
        if (abs(hours) == 4L && abs(residual - hours * HOUR_MS) < 20 * 60_000L) {
            clockOffsetMs = hours * HOUR_MS
        }
    }

    companion object {
        const val SLOT_MS = 10_000L
        const val BIN_MS = 30_000L
        const val DEFAULT_MAX_LAG_MS = 6 * 60_000L
        private const val HOUR_MS = 3_600_000L
        /** Two hours of 30 s bins. */
        private const val FLOOR_WINDOW_BINS = 240
        private const val FLOOR_PERCENTILE = 0.10

        /** Value for a motionless slot: small but non-zero (SaA drops zeros, see class doc). */
        const val STILL_VALUE = 0.025f

        /** Maps a floor-subtracted 30 s activity count to SaA's m/s^2-like movement scale. */
        fun toSaaValue(count: Int): Float =
            if (count <= 0) STILL_VALUE else (0.06f + count * 0.03f).coerceAtMost(3.5f)
    }
}
