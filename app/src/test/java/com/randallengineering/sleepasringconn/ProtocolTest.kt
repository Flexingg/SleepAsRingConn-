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

    @Test
    fun testBulkRecordParsing_zeroConfidence_stillParsesVitals() {
        // Conf: 0 (byte[6] = 0x00, common in daytime epochs)
        val recordBytes = byteArrayOf(
            0x0C, 0x22, 0x98.toByte(), 0xC3.toByte(),
            0x48, 0x32, 0x00, 112.toByte(), // HR=72, HRV=50, Conf=0, RR=14.0
            0x61, 0x0A, // SpO2=97%
            0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00
        )

        val record = BulkRecord.parseRecord(recordBytes)
        assertNotNull(record)
        assertEquals(72, record!!.heartRate)
        assertEquals(50, record.hrvRmssd)
        assertEquals(97, record.spo2Percent)
        assertEquals(14.0, record.respiratoryRate ?: 0.0, 0.01)
    }

    @Test
    fun testBulkRecordParsing_hr159_noLongerExcluded() {
        val recordBytes = byteArrayOf(
            0x0C, 0x22, 0x98.toByte(), 0xC3.toByte(),
            0x9F.toByte(), 0x32, 0x00, 112.toByte(),
            0x61, 0x0A,
            0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00
        )
        val record = BulkRecord.parseRecord(recordBytes)
        assertNotNull(record)
        assertEquals(159, record!!.heartRate)
        assertEquals(50, record.hrvRmssd)
    }

    @Test
    fun testBulkRecord_badMarkerRejected() {
        val bytes = ByteArray(23)
        bytes[0] = 0x0D
        assertNull(BulkRecord.parseRecord(bytes))
    }

    @Test
    fun testBulkRecord_activityMagnitudesNibbleUnpacking() {
        val raw = ByteArray(23)
        raw[0] = 0x0C
        raw[4] = 70; raw[5] = 40; raw[6] = 5; raw[7] = 120; raw[8] = 98.toByte(); raw[9] = 0x0A
        for (i in 10 until 15) raw[i] = 1
        // nibbles: 123 456 789 ABC DEF, info 5  => bytes 12 34 56 78 9A BC DE F5
        val packed = intArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF5)
        for (i in packed.indices) raw[15 + i] = packed[i].toByte()
        val rec = BulkRecord.parseRecord(raw)!!
        assertEquals(listOf(0x123, 0x456, 0x789, 0xABC, 0xDEF), rec.activityMagnitudes)
        assertEquals(5, rec.activityInfoNibble)
        assertFalse(rec.motionIntensityTailIsZero)
        assertEquals(listOf(1, 1, 1, 1, 1), rec.motion)
    }

    @Test
    fun testBulkRecord_vitalGating() {
        // ACTIVITY layout (spo2 byte 0x12) with non-zero tail: HRV dropped, SpO2 null
        val raw = ByteArray(23)
        raw[0] = 0x0C; raw[4] = 80; raw[5] = 50; raw[7] = 120; raw[8] = 0x12; raw[9] = 0x0A
        raw[16] = 3
        val moving = BulkRecord.parseRecord(raw)!!
        assertEquals(BulkRecordLayout.ACTIVITY, moving.layout)
        assertNull(moving.hrvRmssd)
        assertNull(moving.spo2Percent)
        assertEquals(80, moving.heartRate)
        raw[16] = 0
        assertEquals(50, BulkRecord.parseRecord(raw)!!.hrvRmssd)
        // HRV > 200 rejected, RR out of range rejected
        val hi = ByteArray(23)
        hi[0] = 0x0C; hi[4] = 60; hi[5] = 201.toByte(); hi[7] = 20; hi[8] = 98.toByte(); hi[9] = 0x0A
        val r = BulkRecord.parseRecord(hi)!!
        assertNull(r.hrvRmssd)
        assertNull(r.respiratoryRate) // 2.5 brpm
    }

    @Test
    fun testBulkPage_validation() {
        val rec = ByteArray(23)
        rec[0] = 0x0C; rec[4] = 60; rec[5] = 40; rec[7] = 120; rec[8] = 98.toByte(); rec[9] = 0x0A
        val body = byteArrayOf(0x4C, 0x00, 0x00) + rec
        val page = body + RingProtocol.computeXorTrailer(body)
        assertEquals(1, BulkRecord.parsePage(page).size)

        val bad1 = byteArrayOf(0x4C, 0x01, 0x00) + rec
        assertTrue(BulkRecord.parsePage(bad1 + RingProtocol.computeXorTrailer(bad1)).isEmpty())
        val bad2 = byteArrayOf(0x4C, 0x00, 0x00) + rec + byteArrayOf(0x00)
        assertTrue(BulkRecord.parsePage(bad2 + RingProtocol.computeXorTrailer(bad2)).isEmpty())
        assertTrue(BulkRecord.parsePage(page.copyOf().also { it[5] = 99 }).isEmpty())
    }

    @Test
    fun testSportRecord4D_singleByteStepsAndLengthCheck() {
        val rec = byteArrayOf(0x0C, 0x22, 0x98.toByte(), 0x88.toByte(), 159.toByte(), 7, 1, 0, 0, 5, 0)
        val body = byteArrayOf(0x4D, 0x00, 0x01) + rec
        val parsed = SportRecord.parse4D(body + RingProtocol.computeXorTrailer(body))
        assertEquals(1, parsed.size)
        assertEquals(7, parsed[0].steps)
        assertEquals(159, parsed[0].heartRate)
        val body2 = body + byteArrayOf(0x00)
        assertTrue(SportRecord.parse4D(body2 + RingProtocol.computeXorTrailer(body2)).isEmpty())
    }

    @Test
    fun testDeviceStatus_invalidBatteryRejected() {
        val body = ByteArray(18)
        body[0] = 0x10
        body[1] = 0
        assertNull(DeviceStatus.parse(body + RingProtocol.computeXorTrailer(body)))
        body[1] = 101
        assertNull(DeviceStatus.parse(body + RingProtocol.computeXorTrailer(body)))
    }

    @Test
    fun testSportRecord4E_decodesHeartRateAtByte5_notTimestamp() {
        // Wire format of 0x4E (13 bytes):
        // [0] = 0x4E
        // [1:5] = cursor: 0x0C 0x22 0x98 0x88 (136 decimal in low byte!)
        // [5] = HR: 74 bpm (0x4A)
        // [6] = steps: 5
        // [7:12] = misc/conf
        // [12] = XOR checksum
        val body = byteArrayOf(
            0x4E,
            0x0C, 0x22, 0x98.toByte(), 136.toByte(), // cursor has 136 in byte[4]
            74.toByte(), // genuine HR is byte[5]!
            5.toByte(),  // steps is byte[6]
            0x00, 0x10, 0x27, 0x03, 0x00
        )
        val xor = RingProtocol.computeXorTrailer(body)
        val frame = body + xor

        val parsed = SportRecord.parse4E(frame)
        assertNotNull(parsed)
        assertEquals(74, parsed!!.heartRate) // Must be 74 bpm, NEVER 136 bpm!
        assertEquals(5, parsed.steps)
    }

    @Test
    fun testLiveSample15_decodesHeartRate() {
        // [15] [00] [5b = 91 bpm] [0a] [b0] [xor = f4]
        val frame = byteArrayOf(
            0x15, 0x00, 0x5B, 0x0A, 0xB0.toByte(), 0xF4.toByte()
        )
        val hr = LiveSample.parseHeartRate(frame)
        assertEquals(91, hr)
        assertFalse(LiveSample.isWarmup(frame))
    }

    @Test
    fun testLiveSample15_warmupSentinel_filtered() {
        // [15] [00] [08 = warmup sentinel] [0a] [b0] [xor]
        val body = byteArrayOf(0x15, 0x00, 0x08, 0x0A, 0xB0.toByte())
        val xor = RingProtocol.computeXorTrailer(body)
        val frame = body + xor

        val hr = LiveSample.parseHeartRate(frame)
        assertNull(hr) // Must be null, filtered
        assertTrue(LiveSample.isWarmup(frame))
    }

    @Test
    fun testLiveSample15_decodesSpo2() {
        // Long frame with [1] = 0x01, and [14] = 98%
        val bytes = ByteArray(16)
        bytes[0] = 0x15
        bytes[1] = 0x01
        bytes[14] = 98.toByte()

        val spo2 = LiveSample.parseSpo2(bytes)
        assertEquals(98, spo2)
    }
}
