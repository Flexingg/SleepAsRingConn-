package com.randallengineering.sleepasringconn.protocol

/**
 * Decodes 0x15 live stream samples.
 *
 * Wire format:
 *   HR mode (06 01 00):
 *     15 00 <hr> 0A B0 <xor> (6 bytes)
 *     byte[2] = HR bpm (30..220, values < 30 are optical warm-up sentinels e.g. ~8)
 *
 *   SpO2 mode (06 02 00):
 *     15 01 ... <spo2 at byte[14]> ... (>= 15 bytes)
 *     byte[14] = SpO2 % (70..100)
 */
object LiveSample {
    fun parseHeartRate(packet: ByteArray): Int? {
        if (packet.size < 3 || packet[0] != 0x15.toByte() || packet[1] != 0x00.toByte()) return null
        val hr = packet[2].toInt() and 0xFF
        // Values < 30 (e.g. ~8) are warm-up sentinels before optical PPG locks
        return if (hr in 30..220 && hr != 0x9F) hr else null
    }

    fun isWarmup(packet: ByteArray): Boolean {
        if (packet.size < 3 || packet[0] != 0x15.toByte() || packet[1] != 0x00.toByte()) return false
        val b2 = packet[2].toInt() and 0xFF
        return b2 in 1..29
    }

    fun parseSpo2(packet: ByteArray): Int? {
        if (packet.size < 15 || packet[0] != 0x15.toByte() || packet[1] != 0x01.toByte()) return null
        val spo2 = packet[14].toInt() and 0xFF
        return if (spo2 in 70..100) spo2 else null
    }
}
