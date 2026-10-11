package com.randallengineering.sleepasringconn.protocol

/**
 * Parses 0x4C bulk activity & sleep pages.
 *
 * Page layout:
 * [0] = 0x4C
 * [1] = 0x00
 * [2] = Remaining records countdown
 * [3..end-1] = N * 23-byte records
 * [end] = XOR checksum trailer
 *
 * 23-byte record layout:
 * [0..3] = 4-byte BE counter (seconds since SYNC_EPOCH)
 * [4] = Heart rate (BPM)
 * [5] = HRV RMSSD (ms)
 * [6] = Signal confidence (0..12)
 * [7] = Respiratory rate * 8 (divide by 8 -> breaths per minute)
 * [8] = SpO2 % (or 0x12, 0x13, 0x11 for awake/activity/sentinel)
 * [9] = item2p5 (~0x0A)
 * [10..14] = five 30s motion counts (model-dependent resting baseline: 0x01 Gen2, 0x0F Gen3)
 * [15..22] = five 12-bit big-endian magnitudes, nibble-packed (60 bits) + 4-bit info flag
 *            (low nibble of [22])
 */
enum class BulkRecordLayout {
    IDLE,
    SLEEP_VITALS,
    ACTIVITY
}

data class BulkRecord(
    val raw: ByteArray,
    val counter: Long,
    val timestampEpochSeconds: Long,
    val layout: BulkRecordLayout,
    val heartRate: Int?,
    val hrvRmssd: Int?,
    val confidence: Int,
    val respiratoryRate: Double?,
    val spo2Percent: Int?,
    val activityCounts: ByteArray
) {
    val timestampMillis: Long = timestampEpochSeconds * 1000L

    /** Five 30s motion counts, raw bytes [10..15) (resting baseline is model dependent). */
    val motion: List<Int>
        get() = activityCounts.take(5).map { it.toInt() and 0xFF }

    /**
     * Five 12-bit big-endian magnitudes from raw [15..23), nibble-packed: magnitude k is nibbles
     * 3k..3k+2 counting from the high nibble of raw[15]. Empty-zero when raw is too short.
     */
    val activityMagnitudes: List<Int>
        get() {
            if (raw.size < RECORD_LENGTH) return List(5) { 0 }
            fun nibble(i: Int): Int {
                val b = raw[15 + i / 2].toInt() and 0xFF
                return if (i % 2 == 0) b shr 4 else b and 0x0F
            }
            return (0 until 5).map { k ->
                (nibble(k * 3) shl 8) or (nibble(k * 3 + 1) shl 4) or nibble(k * 3 + 2)
            }
        }

    /** 4-bit info flag closing the magnitude block (low nibble of raw[22]). */
    val activityInfoNibble: Int
        get() = if (raw.size < RECORD_LENGTH) 0 else raw[22].toInt() and 0x0F

    /** True when raw [15..20) is all zero (byte-aligned "nothing moved" verdict of the ring). */
    val motionIntensityTailIsZero: Boolean
        get() = raw.size < 20 || (15 until 20).all { raw[it] == 0.toByte() }

    /**
     * Floor-free per-bin motion for single-record callers: each of the five motion counts minus
     * the record's own minimum. Across a run, prefer [floorSubtractedMotion].
     */
    val subEpochMotions: List<Int>
        get() {
            val m = motion
            if (m.isEmpty()) return emptyList()
            val lo = m.minOrNull() ?: 0
            return m.map { it - lo }
        }

    /** Floor-free fallback: spread (max - min) of the five motion counts. */
    val motionMagnitude: Int
        get() = subEpochMotions.maxOrNull() ?: 0

    companion object {
        const val RECORD_LENGTH = 23
        const val EPOCH_SECONDS = 150
        const val MOTION_FLOOR_WINDOW_EPOCHS = 12

        /**
         * Rolling low-percentile (10th) motion floor per record over a time-sorted list,
         * using a centred window of [MOTION_FLOOR_WINDOW_EPOCHS] epochs (~30 min) across all
         * five motion bins. Returned list is index-aligned with [records].
         */
        fun motionFloor(records: List<BulkRecord>): List<Int> {
            val n = records.size
            if (n == 0) return emptyList()
            val half = MOTION_FLOOR_WINDOW_EPOCHS / 2
            val perRecord = records.map { it.motion }
            return List(n) { i ->
                val lo = maxOf(0, i - half)
                val hi = minOf(n - 1, i + half)
                val vals = ArrayList<Int>((hi - lo + 1) * 5)
                for (j in lo..hi) vals.addAll(perRecord[j])
                if (vals.isEmpty()) 0 else {
                    vals.sort()
                    vals[(vals.size * 0.10).toInt().coerceIn(0, vals.size - 1)]
                }
            }
        }

        /**
         * Per-record motion level after subtracting the rolling floor: max over the five bins of
         * (count - floor), clamped at 0. Index-aligned with [records] (must be time-sorted).
         */
        fun floorSubtractedMotion(records: List<BulkRecord>): List<Int> {
            val floors = motionFloor(records)
            return records.mapIndexed { i, r ->
                (r.motion.maxOrNull() ?: 0).minus(floors[i]).coerceAtLeast(0)
            }
        }

        fun parsePage(page: ByteArray): List<BulkRecord> {
            if (page.size < 4) return emptyList()
            val op = page[0].toInt() and 0xFF
            if (op != 0x4C) return emptyList()
            if (!RingProtocol.isFrameValid(page)) return emptyList()
            if (page[1] != 0x00.toByte()) return emptyList()

            val recordsData = page.sliceArray(3 until page.size - 1)
            if (recordsData.size % RECORD_LENGTH != 0) return emptyList()
            val recordCount = recordsData.size / RECORD_LENGTH
            val results = mutableListOf<BulkRecord>()

            for (i in 0 until recordCount) {
                val chunk = recordsData.sliceArray(i * RECORD_LENGTH until (i + 1) * RECORD_LENGTH)
                val record = parseRecord(chunk)
                if (record != null) {
                    results.add(record)
                }
            }
            return results
        }

        fun parseRecord(raw: ByteArray): BulkRecord? {
            if (raw.size != RECORD_LENGTH) return null

            val counter = ((raw[0].toLong() and 0xFF) shl 24) or
                    ((raw[1].toLong() and 0xFF) shl 16) or
                    ((raw[2].toLong() and 0xFF) shl 8) or
                    (raw[3].toLong() and 0xFF)

            val timestampSec = counter + RingProtocol.SYNC_EPOCH
            // raw[0] is the counter's high byte (0x0C until 2026-11-28, then 0x0D...), not a fixed
            // marker; reject only implausible timestamps (before 2020 or more than a day ahead).
            if (timestampSec > System.currentTimeMillis() / 1000L + 86_400L) return null

            // Idle template: [4:8]=05 00 0c 00, [9]=0a, [10:15]=01x5, [15:22]=00x7
            val isIdle = raw[4] == 0x05.toByte() && raw[5] == 0x00.toByte() &&
                    raw[6] == 0x0C.toByte() && raw[7] == 0x00.toByte() &&
                    raw[9] == 0x0A.toByte() &&
                    (10 until 15).all { raw[it] == 0x01.toByte() } &&
                    (15 until 22).all { raw[it] == 0x00.toByte() }

            val spo2Byte = raw[8].toInt() and 0xFF
            val layout = when {
                isIdle -> BulkRecordLayout.IDLE
                spo2Byte == 0x12 || spo2Byte == 0x13 || spo2Byte == 0x11 -> BulkRecordLayout.ACTIVITY
                else -> BulkRecordLayout.SLEEP_VITALS
            }

            // Confidence: byte[6] (0 = no optical lock/extreme artifact, 1..12 = valid optical lock)
            val conf = raw[6].toInt() and 0xFF

            // Heart rate: byte[4]
            val rawHr = raw[4].toInt() and 0xFF
            val hr = if (!isIdle && rawHr in 30..220) rawHr else null

            // HRV: byte[5] (cap 200 ms; activity layout additionally needs a still epoch)
            val rawHrv = raw[5].toInt() and 0xFF
            val tailZero = (15 until 20).all { raw[it] == 0.toByte() }
            val hrvGate = when (layout) {
                BulkRecordLayout.SLEEP_VITALS -> true
                BulkRecordLayout.ACTIVITY -> tailZero
                BulkRecordLayout.IDLE -> false
            }
            val hrv = if (hrvGate && rawHrv in 1..200) rawHrv else null

            // Respiratory rate: byte[7] / 8.0
            val rawRr = raw[7].toInt() and 0xFF
            val rr = if (!isIdle && rawRr > 0) {
                val calculated = rawRr / 8.0
                if (calculated in 4.0..30.0) calculated else null
            } else null

            // SpO2: byte[8], sleep-vitals layout only
            val spo2 = if (layout == BulkRecordLayout.SLEEP_VITALS && spo2Byte in 70..100) spo2Byte else null

            val actiCounts = raw.sliceArray(10 until 20)

            return BulkRecord(
                raw = raw,
                counter = counter,
                timestampEpochSeconds = timestampSec,
                layout = layout,
                heartRate = hr,
                hrvRmssd = hrv,
                confidence = conf,
                respiratoryRate = rr,
                spo2Percent = spo2,
                activityCounts = actiCounts
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BulkRecord
        return counter == other.counter && raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var result = counter.hashCode()
        result = 31 * result + raw.contentHashCode()
        return result
    }
}
