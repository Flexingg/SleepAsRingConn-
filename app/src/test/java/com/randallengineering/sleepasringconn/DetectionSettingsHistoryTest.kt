package com.randallengineering.sleepasringconn

import com.randallengineering.sleepasringconn.analytics.SleepDetectionMode
import com.randallengineering.sleepasringconn.analytics.SleepDetectionSensitivity
import com.randallengineering.sleepasringconn.data.DetectionSettingsEntry
import com.randallengineering.sleepasringconn.data.SleepSessionManager
import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.protocol.BulkRecordLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class DetectionSettingsHistoryTest {

    private val epoch = 150_000L

    private fun record(t: Long, heartRate: Int, motion: Int) = BulkRecord(
        raw = ByteArray(23),
        counter = t / 1000L,
        timestampEpochSeconds = t / 1000L,
        layout = BulkRecordLayout.SLEEP_VITALS,
        heartRate = heartRate,
        hrvRmssd = 45,
        confidence = 9,
        respiratoryRate = 15.0,
        spo2Percent = 97,
        activityCounts = byteArrayOf(0, motion.toByte(), 0, 0, 0)
    )

    private fun at(hour: Int, daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -daysAgo)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Two nights (3 and 2 days ago), each followed by an active morning. */
    private fun twoNights(): List<BulkRecord> {
        val records = mutableListOf<BulkRecord>()
        for (daysAgo in listOf(4, 3)) {
            val start = at(23, daysAgo)
            for (i in 0 until 168) records.add(record(start + i * epoch, 52, 1))
            val end = start + 168 * epoch
            for (i in 0 until 24) records.add(record(end + i * epoch, 85, 14))
        }
        return records
    }

    @Test
    fun switchingToManualKeepsEarlierSessions() {
        val records = twoNights()
        val switchAt = at(12, 3) // between the two nights
        val history = listOf(
            DetectionSettingsEntry(Long.MIN_VALUE, SleepDetectionMode.AUTOMATIC, SleepDetectionSensitivity.BALANCED),
            DetectionSettingsEntry(switchAt, SleepDetectionMode.MANUAL, SleepDetectionSensitivity.BALANCED)
        )
        val sessions = SleepSessionManager.detectWithSettingsHistory(records, history)
        assertEquals("Only the night before the switch is kept", 1, sessions.size)
        assertEquals(true, sessions.first().startTimeMillis < switchAt)
    }

    @Test
    fun unchangedSettingsDetectEverything() {
        val history = listOf(
            DetectionSettingsEntry(Long.MIN_VALUE, SleepDetectionMode.AUTOMATIC, SleepDetectionSensitivity.BALANCED)
        )
        assertEquals(2, SleepSessionManager.detectWithSettingsHistory(twoNights(), history).size)
    }

    @Test
    fun manualThenAutomaticOnlyDetectsAfterTheChange() {
        val switchAt = at(12, 3)
        val history = listOf(
            DetectionSettingsEntry(Long.MIN_VALUE, SleepDetectionMode.MANUAL, SleepDetectionSensitivity.BALANCED),
            DetectionSettingsEntry(switchAt, SleepDetectionMode.AUTOMATIC, SleepDetectionSensitivity.BALANCED)
        )
        val sessions = SleepSessionManager.detectWithSettingsHistory(twoNights(), history)
        assertEquals(1, sessions.size)
        assertEquals(true, sessions.first().startTimeMillis >= switchAt)
    }
}
