package com.randallengineering.sleepasringconn

import com.randallengineering.sleepasringconn.analytics.SleepDetectionConfig
import com.randallengineering.sleepasringconn.analytics.SleepDetectionMode
import com.randallengineering.sleepasringconn.analytics.SleepDetectionSensitivity
import com.randallengineering.sleepasringconn.analytics.SleepStagingEngine
import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.protocol.BulkRecordLayout
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class SleepFalsePositiveTest {

    private val epoch = 150_000L

    private fun record(
        t: Long,
        heartRate: Int? = 60,
        motion: Int = 1,
        confidence: Int = 9,
        flatMotion: Boolean = false
    ) = BulkRecord(
        raw = ByteArray(23),
        counter = t / 1000L,
        timestampEpochSeconds = t / 1000L,
        layout = BulkRecordLayout.SLEEP_VITALS,
        heartRate = heartRate,
        hrvRmssd = heartRate?.let { 45 },
        confidence = confidence,
        respiratoryRate = heartRate?.let { 15.0 },
        spo2Percent = heartRate?.let { 97 },
        // A ring lying on a table reports the same count in every bin (no movement at all)
        activityCounts = if (flatMotion) byteArrayOf(1, 1, 1, 1, 1) else byteArrayOf(0, motion.toByte(), 0, 0, 0)
    )

    /** Today at [hour]:[minute], shifted back [daysAgo] days. */
    private fun at(hour: Int, minute: Int = 0, daysAgo: Int = 1): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -daysAgo)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** A real night 23:00-06:30 at a sleeping HR of 50, so the user's HR floor is known. */
    private fun night(records: MutableList<BulkRecord>) {
        val start = at(23, daysAgo = 2)
        for (i in 0 until 180) records.add(record(start + i * epoch, heartRate = 50))
    }

    private fun active(records: MutableList<BulkRecord>, from: Long, count: Int) {
        for (i in 0 until count) records.add(record(from + i * epoch, heartRate = 85, motion = 14))
    }

    @Test
    fun ringSetDownDuringTheDayIsNotSleep() {
        val records = mutableListOf<BulkRecord>()
        night(records)
        active(records, at(10), 16)
        // 13:00-15:00: ring on the desk -> no HR lock, no movement
        val desk = at(13)
        for (i in 0 until 48) records.add(record(desk + i * epoch, heartRate = null, confidence = 0, flatMotion = true))
        active(records, desk + 48 * epoch, 16)

        val sessions = SleepStagingEngine.extractAllSleepSessions(records, SleepDetectionConfig.BALANCED)
        assertEquals("Only the real night should be found", 1, sessions.size)
        assertFalse(sessions.first().isNap)
    }

    @Test
    fun quietRestWithAwakeHeartRateIsNotANap() {
        val records = mutableListOf<BulkRecord>()
        night(records)
        // 09:00-17:20 at a desk: fairly still, awake HR ~72 (sets a realistic resting median)
        val desk = at(9)
        for (i in 0 until 200) records.add(record(desk + i * epoch, heartRate = 72, motion = 3))
        active(records, at(18), 16)
        // 19:00-20:30 on the couch: still, but HR stays at awake-resting 64 (floor 50)
        val couch = at(19)
        for (i in 0 until 36) records.add(record(couch + i * epoch, heartRate = 64, motion = 1))
        active(records, couch + 36 * epoch, 16)

        val balanced = SleepStagingEngine.extractAllSleepSessions(records, SleepDetectionConfig.BALANCED)
        assertEquals("Couch rest should be rejected", 1, balanced.size)

        // Relaxed keeps the old behaviour and counts it
        val relaxed = SleepStagingEngine.extractAllSleepSessions(records, SleepDetectionConfig.RELAXED)
        assertEquals(2, relaxed.size)
    }

    @Test
    fun realNapWithHeartRateDropIsKept() {
        val records = mutableListOf<BulkRecord>()
        night(records)
        active(records, at(13), 16)
        val nap = at(14)
        for (i in 0 until 24) records.add(record(nap + i * epoch, heartRate = 57, motion = 1))
        active(records, nap + 24 * epoch, 16)

        val sessions = SleepStagingEngine.extractAllSleepSessions(records, SleepDetectionConfig.BALANCED)
        assertEquals(2, sessions.size)
        assertTrue(sessions.any { it.isNap })
    }

    @Test
    fun overnightOnlyDropsNaps() {
        val records = mutableListOf<BulkRecord>()
        night(records)
        active(records, at(13), 16)
        val nap = at(14)
        for (i in 0 until 24) records.add(record(nap + i * epoch, heartRate = 57, motion = 1))
        active(records, nap + 24 * epoch, 16)

        val config = SleepDetectionConfig.of(SleepDetectionSensitivity.BALANCED, SleepDetectionMode.OVERNIGHT_ONLY)
        val sessions = SleepStagingEngine.extractAllSleepSessions(records, config)
        assertEquals(1, sessions.size)
        assertFalse(sessions.first().isNap)
    }

    @Test
    fun ringTakenOffInTheMorningDoesNotStretchTheNight() {
        val records = mutableListOf<BulkRecord>()
        val start = at(23, daysAgo = 2)
        for (i in 0 until 168) records.add(record(start + i * epoch, heartRate = 53))
        val nightEnd = start + 168 * epoch
        // Ring put on the nightstand at 06:00 and left there for 3 hours
        for (i in 0 until 72) records.add(record(nightEnd + i * epoch, heartRate = null, confidence = 0, flatMotion = true))

        val sessions = SleepStagingEngine.extractAllSleepSessions(records, SleepDetectionConfig.BALANCED)
        assertEquals(1, sessions.size)
        assertTrue(
            "Session should end when the ring came off",
            kotlin.math.abs(sessions.first().endTimeMillis - nightEnd) <= 2 * epoch
        )
    }

    @Test
    fun strictRejectsMotionlessRingEvenWithReadings() {
        val records = mutableListOf<BulkRecord>()
        night(records)
        active(records, at(15), 16)
        // Ring on a charger/table that still reports a plausible HR, but nothing ever moves
        val table = at(16)
        for (i in 0 until 40) records.add(record(table + i * epoch, heartRate = 52, flatMotion = true))
        active(records, table + 40 * epoch, 16)

        val strict = SleepStagingEngine.extractAllSleepSessions(records, SleepDetectionConfig.STRICT)
        assertEquals(1, strict.size)
    }
}
