package com.randallengineering.sleepasringconn

import com.randallengineering.sleepasringconn.analytics.SleepSession
import com.randallengineering.sleepasringconn.analytics.SleepStage
import com.randallengineering.sleepasringconn.analytics.StagedEpoch
import com.randallengineering.sleepasringconn.healthconnect.SleepExportPlanner
import com.randallengineering.sleepasringconn.healthconnect.SleepExportPlanner.EPOCH_MS
import org.junit.Assert.*
import org.junit.Test

class SleepExportPlannerTest {

    private val t0 = 1_700_000_000_000L

    private fun epoch(t: Long, stage: SleepStage) = StagedEpoch(t, stage, 55, 40, 97, 14.0, 0)

    private fun session(start: Long, end: Long, epochs: List<StagedEpoch>) = SleepSession(
        startTimeMillis = start, endTimeMillis = end,
        totalInBedMinutes = ((end - start) / 60_000L).toInt(), sleepDurationMinutes = 0,
        awakeMinutes = 0, lightMinutes = 0, deepMinutes = 0, remMinutes = 0,
        averageHeartRate = null, averageHrvRmssd = null, averageSpo2 = null,
        averageRespiratoryRate = null, sleepScore = 0, epochs = epochs
    )

    @Test
    fun stagesStayInsideSessionBoundsAndNeverOverlap() {
        // Custom-session style: last epoch spills past the session end, and two epochs are
        // closer than 150 s apart.
        val epochs = listOf(
            epoch(t0, SleepStage.AWAKE),
            epoch(t0 + EPOCH_MS, SleepStage.LIGHT),
            epoch(t0 + EPOCH_MS + 60_000, SleepStage.DEEP),
            epoch(t0 + 3 * EPOCH_MS, SleepStage.REM)
        )
        val end = t0 + 3 * EPOCH_MS + 30_000
        val stages = SleepExportPlanner.buildStages(session(t0, end, epochs))

        assertEquals(t0, stages.first().startMillis)
        assertEquals(end, stages.last().endMillis)
        stages.zipWithNext().forEach { (a, b) -> assertTrue(a.endMillis <= b.startMillis) }
        stages.forEach { assertTrue(it.endMillis > it.startMillis) }
    }

    @Test
    fun consecutiveSameStageEpochsMergeIntoOneRun() {
        val epochs = (0 until 6).map { epoch(t0 + it * EPOCH_MS, if (it < 4) SleepStage.LIGHT else SleepStage.DEEP) }
        val stages = SleepExportPlanner.buildStages(session(t0, t0 + 6 * EPOCH_MS, epochs))
        assertEquals(2, stages.size)
        assertEquals(SleepStage.LIGHT, stages[0].stage)
        assertEquals(t0 + 4 * EPOCH_MS, stages[0].endMillis)
        assertEquals(SleepStage.DEEP, stages[1].stage)
    }

    @Test
    fun ongoingSessionIsNotSettled() {
        val s = session(t0, t0 + 60 * 60_000L, emptyList())
        assertFalse(SleepExportPlanner.isSettled(s, latestDataMillis = s.endTimeMillis + 5 * 60_000L, nowMillis = s.endTimeMillis + 10 * 60_000L))
        assertTrue(SleepExportPlanner.isSettled(s, latestDataMillis = s.endTimeMillis + 31 * 60_000L, nowMillis = s.endTimeMillis + 31 * 60_000L))
        assertTrue(SleepExportPlanner.isSettled(s, latestDataMillis = s.endTimeMillis, nowMillis = s.endTimeMillis + 3 * 60 * 60_000L))
    }

    @Test
    fun fingerprintChangesWhenASessionChanges() {
        val epochs = (0 until 4).map { epoch(t0 + it * EPOCH_MS, SleepStage.LIGHT) }
        val a = session(t0, t0 + 4 * EPOCH_MS, epochs)
        assertEquals(SleepExportPlanner.fingerprint(listOf(a)), SleepExportPlanner.fingerprint(listOf(a)))
        assertNotEquals(SleepExportPlanner.fingerprint(listOf(a)), SleepExportPlanner.fingerprint(listOf(a.copy(endTimeMillis = a.endTimeMillis + EPOCH_MS))))
    }
}
