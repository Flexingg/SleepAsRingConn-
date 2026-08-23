package com.randallengineering.sleepasringconn

import com.randallengineering.sleepasringconn.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {

    @Test
    fun testSM3StandardVector() {
        val input = "abc".toByteArray(Charsets.US_ASCII)
        val hash = SM3.hash(input)
        val hex = hash.joinToString("") { "%02x".format(it) }
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", hex.lowercase())
    }

    @Test
    fun testRingAuthKnownPairs() {
        // Known ring: F8:79:99:F7:03:AD
        // V = 0xF7 ^ 0x03 ^ 0xAD = 0x59
        val mac = RingAuth.parseMacString("F8:79:99:F7:03:AD")
        val v = RingAuth.macTailXor(mac).toInt() and 0xFF
        assertEquals(0x59, v)

        // Challenge 0xb0 -> 31 82 67
        val respB0 = RingAuth.computeAuthResponse(0xB0.toByte(), mac)
        assertEquals(0x31.toByte(), respB0[0])
        assertEquals(0x82.toByte(), respB0[1])
        assertEquals(0x67.toByte(), respB0[2])

        // Challenge 0xe5 -> 52 0b e1
        val respE5 = RingAuth.computeAuthResponse(0xE5.toByte(), mac)
        assertEquals(0x52.toByte(), respE5[0])
        assertEquals(0x0B.toByte(), respE5[1])
        assertEquals(0xE1.toByte(), respE5[2])
    }

    @Test
    fun testFrameXorValidation() {
        // Valid frame: 86 00 86
        val validFrame = byteArrayOf(0x86.toByte(), 0x00, 0x86.toByte())
        assertTrue(RingProtocol.isFrameValid(validFrame))

        // Invalid frame
        val invalidFrame = byteArrayOf(0x86.toByte(), 0x00, 0x00)
        assertFalse(RingProtocol.isFrameValid(invalidFrame))
    }

    @Test
    fun testDeviceStatusParsing() {
        // [10] [4c=76%] [04=on charger] [00] [00 51=81 steps] [01 64=35.6C] [01 65=35.7C] [00 00 00 00] [0f a1=4001mV] [46=70% case] [xor]
        val frameBody = byteArrayOf(
            0x10, 0x4C, 0x04, 0x00,
            0x00, 0x51,
            0x01, 0x64,
            0x01, 0x65,
            0x00, 0x00, 0x00, 0x00,
            0x0F, 0xA1.toByte(),
            0x00,
            0x46.toByte()
        )
        val xor = RingProtocol.computeXorTrailer(frameBody)
        val fullFrame = frameBody + xor

        val status = DeviceStatus.parse(fullFrame)
        assertNotNull(status)
        assertEquals(76, status!!.batteryPercent)
        assertTrue(status.isOnCharger)
        assertFalse(status.isSportMode)
        assertEquals(81, status.quarterHourSteps)
        assertNotNull(status.skinTemperature)
        assertEquals(35.65, status.skinTemperature!!.celsius, 0.01)
        assertEquals(4001, status.batteryVoltageMv)
        assertNotNull(status.caseBattery)
        assertEquals(70, status.caseBattery!!.percent)
        assertFalse(status.caseBattery!!.isCharging)
    }

    @Test
    fun testBulkRecordParsing() {
        // Construct a valid 23-byte record for a sleep epoch
        // Counter: 0x0C2298C3 (seconds since epoch)
        // HR: 68 (0x44)
        // HRV: 65 (0x41)
        // Conf: 9
        // RR: 120 (120/8 = 15.0 brpm)
        // SpO2: 98 (0x62)
        // item2p5: 0x0A
        // acti_counts: 10 bytes
        // info: 0x00
        // trailer: 0x00 0x00
        val recordBytes = byteArrayOf(
            0x0C, 0x22, 0x98.toByte(), 0xC3.toByte(),
            0x44, 0x41, 0x09, 120.toByte(),
            0x62, 0x0A,
            0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00
        )

        val record = BulkRecord.parseRecord(recordBytes)
        assertNotNull(record)
        assertEquals(BulkRecordLayout.SLEEP_VITALS, record!!.layout)
        assertEquals(68, record.heartRate)
        assertEquals(65, record.hrvRmssd)
        assertEquals(98, record.spo2Percent)
        assertEquals(15.0, record.respiratoryRate ?: 0.0, 0.01)
    }
}
