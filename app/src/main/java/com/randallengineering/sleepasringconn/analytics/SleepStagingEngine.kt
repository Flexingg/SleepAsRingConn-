package com.randallengineering.sleepasringconn.analytics

import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.protocol.BulkRecordLayout

enum class SleepStage {
    AWAKE,
    LIGHT,
    DEEP,
    REM
}

data class StagedEpoch(
    val timestampMillis: Long,
    val stage: SleepStage,
    val heartRate: Int?,
    val hrvRmssd: Int?,
    val spo2: Int?,
    val respiratoryRate: Double?,
    val motionIntensity: Int
)

data class SleepSession(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val totalInBedMinutes: Int,
    val sleepDurationMinutes: Int,
    val awakeMinutes: Int,
    val lightMinutes: Int,
    val deepMinutes: Int,
    val remMinutes: Int,
    val averageHeartRate: Int?,
    val averageHrvRmssd: Int?,
    val averageSpo2: Int?,
    val averageRespiratoryRate: Double?,
    val sleepScore: Int,
    val epochs: List<StagedEpoch>
)

object SleepStagingEngine {

    /**
     * Staging algorithm:
     * 1. Extracts the most recent overnight sleep bout from multi-day history stream.
     * 2. Uses the sleep session's internal HR floor, median, HRV RMSSD, and 5-bin actigraphy counts.
     * 3. Classifies each 2.5-min epoch into AWAKE, LIGHT, DEEP, or REM.
     * 4. Consolidates transient single-epoch stage flips to maintain physiological sleep cycles.
     */
    fun stageRecords(records: List<BulkRecord>): SleepSession? {
        val nonIdle = records.filter { it.layout != BulkRecordLayout.IDLE }.sortedBy { it.timestampMillis }
        if (nonIdle.size < 12) return null

        // 1. Extract the latest overnight sleep session cluster
        val sessionRecords = extractLatestSleepSession(nonIdle) ?: return null
        if (sessionRecords.size < 12) return null

        val hrValues = sessionRecords.mapNotNull { it.heartRate }
        if (hrValues.isEmpty()) return null

        val sortedHr = hrValues.sorted()
        val floorHr = sortedHr[(sortedHr.size * 0.20).toInt().coerceIn(0, sortedHr.size - 1)]
        val medianHr = sortedHr[sortedHr.size / 2]

        val hrvValues = sessionRecords.mapNotNull { it.hrvRmssd }
        val medianHrv = if (hrvValues.isNotEmpty()) hrvValues.sorted()[hrvValues.size / 2] else 40

        // 2. Initial Stage Classification
        val initialStages = mutableListOf<SleepStage>()

        for (record in sessionRecords) {
            val motion = record.motionMagnitude
            val hr = record.heartRate
            val hrv = record.hrvRmssd

            val stage = when {
                // High motion or elevated heart rate indicates wakefulness
                motion >= 15 || (motion >= 5 && hr != null && hr > medianHr + 12) || (hr != null && hr > floorHr + 22 && motion >= 3) -> {
                    SleepStage.AWAKE
                }
                // Deep Sleep: Near-zero motion, lowest heart rate trough, stable low-variance HRV
                motion <= 2 && hr != null && hr <= medianHr && (hrv == null || hrv <= medianHrv + 12) -> {
                    SleepStage.DEEP
                }
                // REM Sleep: Muscle atonia (low motion), elevated HRV / heart rate fluctuation
                motion <= 4 && ((hrv != null && hrv >= medianHrv + 5) || (hr != null && hr in (medianHr + 1)..(medianHr + 8))) -> {
                    SleepStage.REM
                }
                // Light Sleep: Baseline restorative sleep
                else -> {
                    SleepStage.LIGHT
                }
            }
            initialStages.add(stage)
        }

        // 3. Stage Consolidation (smooth 1-epoch jitter)
        val smoothedStages = smoothStages(initialStages)

        val stagedEpochs = sessionRecords.mapIndexed { index, record ->
            StagedEpoch(
                timestampMillis = record.timestampMillis,
                stage = smoothedStages[index],
                heartRate = record.heartRate,
                hrvRmssd = record.hrvRmssd,
                spo2 = record.spo2Percent,
                respiratoryRate = record.respiratoryRate,
                motionIntensity = record.motionMagnitude
            )
        }

        val startTime = stagedEpochs.first().timestampMillis
        val endTime = stagedEpochs.last().timestampMillis + 150_000L
        val totalInBedMinutes = ((endTime - startTime) / 60_000L).toInt()

        val awakeMinutes = stagedEpochs.count { it.stage == SleepStage.AWAKE } * 5 / 2
        val lightMinutes = stagedEpochs.count { it.stage == SleepStage.LIGHT } * 5 / 2
        val deepMinutes = stagedEpochs.count { it.stage == SleepStage.DEEP } * 5 / 2
        val remMinutes = stagedEpochs.count { it.stage == SleepStage.REM } * 5 / 2

        // Actual sleep duration EXCLUDES awake time (only Light + Deep + REM)
        val sleepDurationMinutes = lightMinutes + deepMinutes + remMinutes

        val avgHr = if (hrValues.isNotEmpty()) hrValues.average().toInt() else null
        val avgHrv = if (hrvValues.isNotEmpty()) hrvValues.average().toInt() else null
        val spo2Values = stagedEpochs.mapNotNull { it.spo2 }
        val avgSpo2 = if (spo2Values.isNotEmpty()) spo2Values.average().toInt() else null
        val rrValues = stagedEpochs.mapNotNull { it.respiratoryRate }
        val avgRr = if (rrValues.isNotEmpty()) rrValues.average() else null

        val score = calculateSleepScore(sleepDurationMinutes, deepMinutes, remMinutes, avgHr, avgSpo2)

        return SleepSession(
            startTimeMillis = startTime,
            endTimeMillis = endTime,
            totalInBedMinutes = totalInBedMinutes,
            sleepDurationMinutes = sleepDurationMinutes,
            awakeMinutes = awakeMinutes,
            lightMinutes = lightMinutes,
            deepMinutes = deepMinutes,
            remMinutes = remMinutes,
            averageHeartRate = avgHr,
            averageHrvRmssd = avgHrv,
            averageSpo2 = avgSpo2,
            averageRespiratoryRate = avgRr,
            sleepScore = score,
            epochs = stagedEpochs
        )
    }

    /**
     * Extracts the most recent continuous sleep session from all records in history.
     * Clusters consecutive low-motion / sleep-vitals records (allowing brief awakenings <= 30m).
     */
    private fun extractLatestSleepSession(records: List<BulkRecord>): List<BulkRecord>? {
        if (records.isEmpty()) return null

        // Group into candidate bouts
        val bouts = mutableListOf<MutableList<BulkRecord>>()
        var currentBout = mutableListOf<BulkRecord>()
        var lastSleepTimestamp = 0L

        for (record in records) {
            val isRestCandidate = record.layout == BulkRecordLayout.SLEEP_VITALS ||
                    (record.motionMagnitude <= 8 && record.heartRate != null)

            if (currentBout.isEmpty()) {
                if (isRestCandidate) {
                    currentBout.add(record)
                    lastSleepTimestamp = record.timestampMillis
                }
            } else {
                val gapMinutes = (record.timestampMillis - lastSleepTimestamp) / 60_000L
                if (gapMinutes <= 45) { // Allow up to 45 minutes of restless/awake gap within the night
                    currentBout.add(record)
                    if (isRestCandidate) {
                        lastSleepTimestamp = record.timestampMillis
                    }
                } else {
                    if (currentBout.size >= 16) { // Minimum 40 minutes to consider a bout
                        bouts.add(currentBout)
                    }
                    currentBout = if (isRestCandidate) mutableListOf(record) else mutableListOf()
                    lastSleepTimestamp = if (isRestCandidate) record.timestampMillis else 0L
                }
            }
        }

        if (currentBout.size >= 16) {
            bouts.add(currentBout)
        }

        // Return the latest qualifying major sleep bout (or the largest recent one)
        val candidate = bouts.lastOrNull { bout ->
            val durationMinutes = ((bout.last().timestampMillis - bout.first().timestampMillis) / 60_000L)
            durationMinutes >= 60 // At least 1 hour of sleep
        } ?: bouts.maxByOrNull { it.size } ?: records.takeLast(288) // fallback to last 12h

        // Trim leading and trailing prolonged awake runs (> 30 min of awake before sleep or after waking up)
        return trimSleepBoutEdges(candidate)
    }

    private fun trimSleepBoutEdges(bout: List<BulkRecord>): List<BulkRecord> {
        if (bout.size < 12) return bout
        var startIdx = 0
        var endIdx = bout.size - 1

        // Trim leading daytime/active epochs before sleep onset
        while (startIdx < bout.size - 6 && bout[startIdx].motionMagnitude > 12) {
            startIdx++
        }

        // Trim trailing active epochs after final wake
        while (endIdx > startIdx + 6 && bout[endIdx].motionMagnitude > 12) {
            endIdx--
        }

        return bout.subList(startIdx, endIdx + 1)
    }

    private fun smoothStages(stages: List<SleepStage>): List<SleepStage> {
        if (stages.size < 3) return stages
        val smoothed = stages.toMutableList()

        for (i in 1 until stages.size - 1) {
            val prev = smoothed[i - 1]
            val curr = smoothed[i]
            val next = smoothed[i + 1]

            // If an isolated single epoch is sandwiched between identical stages, smooth it
            if (prev == next && curr != prev && curr != SleepStage.AWAKE) {
                smoothed[i] = prev
            }
        }
        return smoothed
    }

    private fun calculateSleepScore(
        sleepMinutes: Int,
        deepMinutes: Int,
        remMinutes: Int,
        avgHr: Int?,
        avgSpo2: Int?
    ): Int {
        var score = 0.0

        // Duration score (max 40): 7-9 hours optimal
        score += when {
            sleepMinutes >= 420 && sleepMinutes <= 540 -> 40.0
            sleepMinutes in 360..600 -> 32.0
            sleepMinutes in 300..660 -> 24.0
            else -> (sleepMinutes.toDouble() / 420.0 * 20.0).coerceIn(0.0, 20.0)
        }

        // Deep sleep ratio (max 20): 15-25% optimal
        val deepRatio = if (sleepMinutes > 0) deepMinutes.toDouble() / sleepMinutes.toDouble() else 0.0
        score += when {
            deepRatio >= 0.15 && deepRatio <= 0.25 -> 20.0
            deepRatio > 0.10 -> 15.0
            else -> 10.0
        }

        // REM sleep ratio (max 20): 20-25% optimal
        val remRatio = if (sleepMinutes > 0) remMinutes.toDouble() / sleepMinutes.toDouble() else 0.0
        score += when {
            remRatio >= 0.20 && remRatio <= 0.25 -> 20.0
            remRatio > 0.15 -> 15.0
            else -> 10.0
        }

        // SpO2 score (max 10)
        score += when {
            avgSpo2 == null -> 8.0
            avgSpo2 >= 96 -> 10.0
            avgSpo2 >= 93 -> 7.0
            else -> 4.0
        }

        // Resting HR stability score (max 10)
        score += when {
            avgHr == null -> 8.0
            avgHr <= 65 -> 10.0
            avgHr <= 75 -> 8.0
            else -> 6.0
        }

        return score.toInt().coerceIn(0, 100)
    }
}
