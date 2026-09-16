package com.randallengineering.sleepasringconn.protocol

/**
 * Decodes 0x4E (live sport snapshot, 13 bytes) and 0x4D (batch sport page) frames.
 *
 * 0x4E format (13 bytes):
 *   [0]      = 0x4E opcode
 *   [1:5]    = cursor:4 BE (seconds since SYNC_EPOCH) - interval end time
 *   [5]      = HR bpm (valid in 30..220 and != 0x9F)
 *   [6]      = steps / motion count in this ~10s interval
 *   [7:12]   = quality / misc fields
 *   [12]     = XOR checksum
 *
 * 0x4D format (variable):
 *   [0]      = 0x4D opcode
 *   [1]      = 0x00
 *   [2]      = count
 *   [3:end-1]= N * 11-byte records:
 *              [0:4] = cursor:4 BE
 *              [4]   = HR bpm
 *              [5:7] = motion count (uint16 LE)
 *              [7:9] = field3
 *              [9]   = confidence
 *              [10]  = pad
 *   [end]    = XOR checksum
 */
data class SportRecord(
    val timestampSeconds: Long,
    val heartRate: Int?,
    val steps: Int,
    val confidence: Int
) {
    companion object {
        fun parse4E(frame: ByteArray): SportRecord? {
            if (frame.size < 8 || frame[0] != 0x4E.toByte()) return null
            if (!RingProtocol.isFrameValid(frame)) return null
            val cursor = ((frame[1].toLong() and 0xFF) shl 24) or
                    ((frame[2].toLong() and 0xFF) shl 16) or
                    ((frame[3].toLong() and 0xFF) shl 8) or
                    (frame[4].toLong() and 0xFF)
            val hrRaw = frame[5].toInt() and 0xFF
            val hr = if (hrRaw in 30..220 && hrRaw != 0x9F) hrRaw else null
            val steps = frame[6].toInt() and 0xFF
            val conf = if (frame.size >= 11) frame[10].toInt() and 0xFF else 0
            return SportRecord(
                timestampSeconds = cursor + RingProtocol.SYNC_EPOCH,
                heartRate = hr,
                steps = steps,
                confidence = conf
            )
        }

        fun parse4D(page: ByteArray): List<SportRecord> {
            if (page.size < 4 || page[0] != 0x4D.toByte()) return emptyList()
            if (!RingProtocol.isFrameValid(page)) return emptyList()
            val payload = page.sliceArray(3 until page.size - 1)
            val recordLen = 11
            val count = payload.size / recordLen
            val results = mutableListOf<SportRecord>()
            for (i in 0 until count) {
                val off = i * recordLen
                val cursor = ((payload[off].toLong() and 0xFF) shl 24) or
                        ((payload[off + 1].toLong() and 0xFF) shl 16) or
                        ((payload[off + 2].toLong() and 0xFF) shl 8) or
                        (payload[off + 3].toLong() and 0xFF)
                val hrRaw = payload[off + 4].toInt() and 0xFF
                val hr = if (hrRaw in 30..220 && hrRaw != 0x9F) hrRaw else null
                val steps = (payload[off + 5].toInt() and 0xFF) or ((payload[off + 6].toInt() and 0xFF) shl 8)
                val conf = payload[off + 9].toInt() and 0xFF
                results.add(
                    SportRecord(
                        timestampSeconds = cursor + RingProtocol.SYNC_EPOCH,
                        heartRate = hr,
                        steps = steps,
                        confidence = conf
                    )
                )
            }
            return results
        }
    }
}
