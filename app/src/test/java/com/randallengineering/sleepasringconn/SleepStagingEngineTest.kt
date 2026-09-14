package com.randallengineering.sleepasringconn

import com.randallengineering.sleepasringconn.analytics.SleepSession
import com.randallengineering.sleepasringconn.analytics.SleepStage
import com.randallengineering.sleepasringconn.analytics.SleepStagingEngine
import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.protocol.BulkRecordLayout
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class SleepStagingEngineTest {

    private fun createRecord(
        timestampMillis: Long,
        heartRate: Int = 60,
        motion: Int = 1,
        hrv: Int = 45,
        spo2: Int = 98,
        respiratoryRate: Double = 15.0
    ): BulkRecord {
        return BulkRecord(
            raw = ByteArray(23),
            counter = timestampMillis / 1000L,
            timestampEpochSeconds = timestampMillis / 1000L,
            layout = BulkRecordLayout.SLEEP_VITALS,
            heartRate = heartRate,
            hrvRmssd = hrv,
            confidence = 9,
            respiratoryRate = respiratoryRate,
            spo2Percent = spo2,
            activityCounts = byteArrayOf(0, motion.toByte(), 0, 0, 0)
        )
    }

    @Test
    fun testDetectsOvernightSleepAndNapAsSeparateSessions() {
        val records = mutableListOf<BulkRecord>()

        // 1. Overnight sleep: 11:30 PM (23:30) to 7:00 AM (07:00) next morning
        // 7.5 hours = 450 min = 180 epochs (every 150s = 2.5 min)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val overnightStart = cal.timeInMillis

        for (i in 0 until 180) {
            val t = overnightStart + i * 150_000L
            // Alternate deep, light, rem with resting HR and low motion
            val hr = if (i in 20..60) 52 else 60
            val mot = if (i == 90) 3 else 1
            records.add(createRecord(t, heartRate = hr, motion = mot))
        }
        val overnightEnd = overnightStart + 180 * 150_000L

        // 2. Daytime wakefulness: 7:00 AM to 14:00 (7 hours active)
        // High motion & daytime heart rate
        for (i in 0 until 50) {
            val t = overnightEnd + (i + 1) * 300_000L
            records.add(createRecord(t, heartRate = 85, motion = 12))
        }

        // 3. Afternoon Nap: 14:00 (2:00 PM) to 14:45 (2:45 PM)
        // 45 min = 18 epochs
        cal.apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 0)
        }
        val napStart = cal.timeInMillis

        for (i in 0 until 18) {
            val t = napStart + i * 150_000L
            records.add(createRecord(t, heartRate = 58, motion = 1))
        }
        val napEnd = napStart + 18 * 150_000L

        // 4. More daytime wakefulness after nap
        for (i in 0 until 30) {
            val t = napEnd + (i + 1) * 300_000L
            records.add(createRecord(t, heartRate = 90, motion = 15))
        }

        // Extract all sessions
        val sessions = SleepStagingEngine.extractAllSleepSessions(records)

        // Must detect exactly 2 sessions: Nap and Overnight Sleep!
        assertEquals("Should extract both overnight sleep and afternoon nap", 2, sessions.size)

        val napSession = sessions.find { it.isNap }
        val mainSession = sessions.find { !it.isNap }

        assertNotNull("Nap session should be found", napSession)
        assertNotNull("Overnight session should be found", mainSession)

        // Verify Nap Session details
        assertTrue(napSession!!.isNap)
        assertEquals("Afternoon Nap", napSession.sessionLabel)
        assertTrue(
            "Nap start should match nap onset",
            kotlin.math.abs(napStart - napSession.startTimeMillis) <= 300_000L
        )
        assertTrue(
            "Nap end should match nap wake-up",
            kotlin.math.abs(napEnd - napSession.endTimeMillis) <= 300_000L
        )
        assertTrue("Nap duration should be ~45 min", napSession.sleepDurationMinutes in 35..55)

        // Verify Overnight Sleep Session details
        assertFalse(mainSession!!.isNap)
        assertEquals("Overnight Sleep", mainSession.sessionLabel)
        assertTrue(
            "Overnight start should match bedtime",
            kotlin.math.abs(overnightStart - mainSession.startTimeMillis) <= 300_000L
        )
        assertTrue(
            "Overnight end should match morning wake-up",
            kotlin.math.abs(overnightEnd - mainSession.endTimeMillis) <= 300_000L
        )
        assertTrue("Overnight duration should be ~7.5 hours", mainSession.sleepDurationMinutes in 400..460)
    }

    @Test
    fun testPowerNapDetection() {
        val records = mutableListOf<BulkRecord>()

        // 25-minute power nap at 1:00 PM
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 13)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis

        // Active before
        for (i in -10..-1) {
            records.add(createRecord(start + i * 150_000L, heartRate = 88, motion = 14))
        }

        // Power nap: 10 epochs = 25 min
        for (i in 0 until 10) {
            records.add(createRecord(start + i * 150_000L, heartRate = 56, motion = 1))
        }

        // Active after
        for (i in 11..25) {
            records.add(createRecord(start + i * 150_000L, heartRate = 92, motion = 15))
        }

        val sessions = SleepStagingEngine.extractAllSleepSessions(records)
        assertEquals(1, sessions.size)

        val session = sessions.first()
        assertTrue(session.isNap)
        assertEquals("Power Nap", session.sessionLabel)
        assertTrue(session.sleepDurationMinutes in 20..30)
    }

    @Test
    fun testStageCustomSession() {
        val records = mutableListOf<BulkRecord>()
        val start = 1700000000000L
        for (i in 0 until 20) {
            records.add(createRecord(start + i * 150_000L, heartRate = 55, motion = 1))
        }

        // Edit session start and stop
        val customStart = start + 300_000L
        val customEnd = start + 2400_000L

        val customSession = SleepStagingEngine.stageCustomSession(
            records = records,
            startTimeMillis = customStart,
            endTimeMillis = customEnd,
            isNapOverride = true,
            labelOverride = "Edited Nap",
            isUserEdited = true
        )

        assertEquals(customStart, customSession.startTimeMillis)
        assertEquals(customEnd, customSession.endTimeMillis)
        assertTrue(customSession.isUserEdited)
        assertTrue(customSession.isNap)
        assertEquals("Edited Nap", customSession.sessionLabel)
        assertTrue(customSession.sleepDurationMinutes > 0)
    }

    @Test
    fun testStageCustomSessionWithSyntheticData() {
        // When no records exist in the window (manual entry when ring was off)
        val customStart = 1700000000000L
        val customEnd = customStart + 3600_000L // 1 hour

        val session = SleepStagingEngine.stageCustomSession(
            records = emptyList(),
            startTimeMillis = customStart,
            endTimeMillis = customEnd,
            isNapOverride = true
        )

        assertEquals(customStart, session.startTimeMillis)
        assertEquals(customEnd, session.endTimeMillis)
        assertEquals(60, session.totalInBedMinutes)
        assertTrue(session.sleepDurationMinutes in 50..60)
        assertTrue(session.isUserEdited)
        assertTrue(session.isNap)
        assertTrue(session.epochs.isNotEmpty())
    }

    @Test
    fun testMultipleNapsInSingleDay() {
        val records = mutableListOf<BulkRecord>()

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val midnight = cal.timeInMillis

        // 1. Overnight sleep: 00:00 to 07:00 (168 epochs)
        for (i in 0 until 168) {
            records.add(createRecord(midnight + i * 150_000L, heartRate = 56, motion = 1))
        }

        // Active morning: 07:00 to 10:30 (active motion)
        val morningWake = midnight + 168 * 150_000L
        for (i in 0 until 40) {
            records.add(createRecord(morningWake + (i + 1) * 300_000L, heartRate = 85, motion = 14))
        }

        // 2. Morning nap: 10:30 to 11:15 (45 min = 18 epochs)
        val morningNapStart = midnight + 10 * 3600_000L + 30 * 60_000L
        for (i in 0 until 18) {
            records.add(createRecord(morningNapStart + i * 150_000L, heartRate = 58, motion = 1))
        }

        // Active midday: 11:15 to 15:00
        val middayWake = morningNapStart + 18 * 150_000L
        for (i in 0 until 40) {
            records.add(createRecord(middayWake + (i + 1) * 300_000L, heartRate = 88, motion = 15))
        }

        // 3. Afternoon nap: 15:00 to 15:40 (40 min = 16 epochs)
        val afternoonNapStart = midnight + 15 * 3600_000L
        for (i in 0 until 16) {
            records.add(createRecord(afternoonNapStart + i * 150_000L, heartRate = 59, motion = 1))
        }

        // Active evening
        val eveningWake = afternoonNapStart + 16 * 150_000L
        for (i in 0 until 30) {
            records.add(createRecord(eveningWake + (i + 1) * 300_000L, heartRate = 82, motion = 12))
        }

        val sessions = SleepStagingEngine.extractAllSleepSessions(records)

        // Must detect all 3 sessions: Overnight, Morning Nap, Afternoon Nap!
        assertEquals("Should detect 3 separate sessions on the same day", 3, sessions.size)

        val overnight = sessions.find { !it.isNap }
        val naps = sessions.filter { it.isNap }

        assertNotNull("Overnight sleep detected", overnight)
        assertEquals(2, naps.size)

        val morningNap = naps.find { it.sessionLabel == "Morning Nap" }
        val afternoonNap = naps.find { it.sessionLabel == "Afternoon Nap" }

        assertNotNull("Morning nap detected", morningNap)
        assertNotNull("Afternoon nap detected", afternoonNap)

        assertTrue(morningNap!!.sleepDurationMinutes in 35..55)
        assertTrue(afternoonNap!!.sleepDurationMinutes in 30..50)
        assertTrue(overnight!!.sleepDurationMinutes in 380..440)
    }

    @Test
    fun testBriefNightAwakeningDoesNotSplitNight() {
        val records = mutableListOf<BulkRecord>()
        val start = 1700000000000L

        // Sleep 3 hours (72 epochs)
        for (i in 0 until 72) {
            records.add(createRecord(start + i * 150_000L, heartRate = 55, motion = 1))
        }

        // 5-minute bathroom awakening (2 epochs with motion and elevated HR)
        val wakeStart = start + 72 * 150_000L
        records.add(createRecord(wakeStart, heartRate = 80, motion = 9))
        records.add(createRecord(wakeStart + 150_000L, heartRate = 78, motion = 8))

        // Sleep another 4 hours (96 epochs)
        val secondSleepStart = wakeStart + 300_000L
        for (i in 0 until 96) {
            records.add(createRecord(secondSleepStart + i * 150_000L, heartRate = 54, motion = 1))
        }

        val sessions = SleepStagingEngine.extractAllSleepSessions(records)

        // Should consolidate into 1 consolidated overnight sleep session!
        assertEquals("Brief awakening should not split overnight sleep", 1, sessions.size)
        val session = sessions.first()
        assertFalse(session.isNap)
        assertTrue("Duration should reflect both sleep periods (~7h)", session.sleepDurationMinutes in 390..450)
    }
}
