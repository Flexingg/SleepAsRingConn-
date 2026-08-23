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
 * [10..19] = 10-byte activity counts blob (motion magnitude)
 * [20] = info flag
 * [21..22] = tail
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

    companion object {
        const val RECORD_LENGTH = 23
        const val EPOCH_SECONDS = 150

        fun parsePage(page: ByteArray): List<BulkRecord> {
            if (page.size < 4) return emptyList()
            if ((page[0].toInt() and 0xFF) != 0x4C) return emptyList()
            if (!RingProtocol.isFrameValid(page)) return emptyList()

            val recordsData = page.sliceArray(3 until page.size - 1)
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

            // Heart rate: byte[4]
            val rawHr = raw[4].toInt() and 0xFF
            val hr = if (!isIdle && rawHr in 30..220) rawHr else null

            // HRV: byte[5]
            val rawHrv = raw[5].toInt() and 0xFF
            val hrv = if (!isIdle && rawHrv in 1..250) rawHrv else null

            // Confidence: byte[6]
            val conf = raw[6].toInt() and 0xFF

            // Respiratory rate: byte[7] / 8.0
            val rawRr = raw[7].toInt() and 0xFF
            val rr = if (!isIdle && rawRr > 0) {
                val calculated = rawRr / 8.0
                if (calculated in 6.0..35.0) calculated else null
            } else null

            // SpO2: byte[8]
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
