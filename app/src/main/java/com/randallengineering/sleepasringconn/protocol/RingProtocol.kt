package com.randallengineering.sleepasringconn.protocol

import java.util.UUID

object RingProtocol {
    // Primary GATT Service & Characteristics
    val DATA_SERVICE_UUID: UUID = UUID.fromString("8327ad99-2d87-4a22-a8ce-6dd7971c0437")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("8327ad97-2d87-4a22-a8ce-6dd7971c0437")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("8327ad98-2d87-4a22-a8ce-6dd7971c0437")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFO_SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val SYSTEM_ID_CHAR_UUID: UUID = UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb")

    // Epoch constant: 2019-12-31 12:00:00 UTC (1577793600 seconds)
    const val SYNC_EPOCH = 1577793600L

    // History Channels
    const val CHANNEL_SLEEP: Byte = 0x00
    const val CHANNEL_SPORT: Byte = 0x02
    const val CHANNEL_ALL_DAY: Byte = 0x03

    // Commands (sent verbatim ending with 0x00, NOT XOR checksummed)
    val CMD_STATUS_0 = byteArrayOf(0x01, 0x00, 0x00)
    val CMD_SYNC_ALL = byteArrayOf(0x02, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x01, 0x00)
    val CMD_STATUS_QUERY = byteArrayOf(0xD0.toByte(), 0x00, 0x00)
    val CMD_LIVE_HR_MODE = byteArrayOf(0x06, 0x01, 0x00)
    val CMD_LIVE_SPO2_MODE = byteArrayOf(0x06, 0x02, 0x00)
    val CMD_FETCH = byteArrayOf(0x07, 0x00, 0x00)
    val CMD_POLL = byteArrayOf(0x95.toByte(), 0x00, 0x00)
    val CMD_PAGE_ACK_47 = byteArrayOf(0xC7.toByte(), 0x00, 0x00)
    val CMD_PAGE_ACK_4C = byteArrayOf(0xCC.toByte(), 0x00, 0x00)
    val CMD_PAGE_ACK_4D = byteArrayOf(0xCD.toByte(), 0x00, 0x00)
    val CMD_PAGE_ACK_4E = byteArrayOf(0xCE.toByte(), 0x00, 0x00)
    val CMD_HEARTBEAT_ACK = byteArrayOf(0x91.toByte(), 0x00, 0x00)
    val CMD_FIND_RING_LED_ON = byteArrayOf(0x24, 0x01, 0x00)
    val CMD_FIND_RING_LED_OFF = byteArrayOf(0x24, 0x00, 0x00)
    val CMD_SPORT_START = byteArrayOf(0x06, 0x03, 0x07, 0x04, 0x00)
    val CMD_SPORT_STOP = byteArrayOf(0x06, 0x00, 0x00)

    fun createSyncSinceCommand(unixSeconds: Long, channel: Byte = CHANNEL_SLEEP): ByteArray {
        val delta = (unixSeconds - SYNC_EPOCH).coerceIn(0L, 0xFFFFFFFFL)
        val c = delta.toInt()
        return byteArrayOf(
            0x02, 0x00,
            ((c ushr 24) and 0xFF).toByte(),
            ((c ushr 16) and 0xFF).toByte(),
            ((c ushr 8) and 0xFF).toByte(),
            (c and 0xFF).toByte(),
            channel, 0x01, 0x00
        )
    }

    fun createSyncUpToNowCommand(channel: Byte = CHANNEL_SLEEP): ByteArray {
        return createSyncSinceCommand(System.currentTimeMillis() / 1000, channel)
    }

    /**
     * Verifies that the last byte of an incoming RX frame matches the XOR checksum of all prior bytes.
     */
    fun isFrameValid(frame: ByteArray): Boolean {
        if (frame.size < 2) return false
        var xorSum = 0
        for (i in 0 until frame.size - 1) {
            xorSum = xorSum xor (frame[i].toInt() and 0xFF)
        }
        return (xorSum and 0xFF) == (frame.last().toInt() and 0xFF)
    }

    fun computeXorTrailer(bytes: ByteArray): Byte {
        var xorSum = 0
        for (b in bytes) {
            xorSum = xorSum xor (b.toInt() and 0xFF)
        }
        return (xorSum and 0xFF).toByte()
    }
}
