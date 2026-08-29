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
     * Extracts and stages all sleep sessions across the provided records (e.g. past 7 days),
     * sorted with the newest session first.
     */
    fun extractAllSleepSessions(records: List<BulkRecord>): List<SleepSession> {
        val nonIdle = records.filter { it.layout != BulkRecordLayout.IDLE }.sortedBy { it.timestampMillis }
        if (nonIdle.size < 12) return emptyList()

        // 1. Partition records into nightly 24-hour buckets (from 6 PM to 3 PM next day)
        val nightBuckets = mutableMapOf<String, MutableList<BulkRecord>>()
        val cal = java.util.Calendar.getInstance()

        for (record in nonIdle) {
            cal.timeInMillis = record.timestampMillis
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            
            // If between midnight and 3 PM, belongs to the night that started yesterday
            val sessionDate = if (hour < 15) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
            } else {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
            }
            nightBuckets.getOrPut(sessionDate) { mutableListOf() }.add(record)
        }

        // 2. Stage each night independently
        val sessions = mutableListOf<SleepSession>()
        for ((_, nightRecords) in nightBuckets) {
            stageNight(nightRecords)?.let { sessions.add(it) }
        }

        return sessions.sortedByDescending { it.startTimeMillis }
    }

    /**
     * Backward-compatible helper: returns the latest sleep session.
     */
    fun stageRecords(records: List<BulkRecord>): SleepSession? {
        val all = extractAllSleepSessions(records)
        return all.firstOrNull()
    }

    private fun stageNight(records: List<BulkRecord>): SleepSession? {
        if (records.size < 12) return null

        val sorted = records.sortedBy { it.timestampMillis }
        val hrValues = sorted.mapNotNull { it.heartRate }
        if (hrValues.isEmpty()) return null

        val sortedHr = hrValues.sorted()
        val floorHr = sortedHr[(sortedHr.size * 0.20).toInt().coerceIn(0, sortedHr.size - 1)]
        val medianHr = sortedHr[sortedHr.size / 2]

        val hrvValues = sorted.mapNotNull { it.hrvRmssd }
        val medianHrv = if (hrvValues.isNotEmpty()) hrvValues.sorted()[hrvValues.size / 2] else 40

        // Find sleep onset (first quiet run) and sleep offset (last quiet run)
        var onsetIdx = 0
        while (onsetIdx < sorted.size - 6 && sorted[onsetIdx].motionMagnitude > 12) {
            onsetIdx++
        }

        var offsetIdx = sorted.size - 1
        while (offsetIdx > onsetIdx + 6 && sorted[offsetIdx].motionMagnitude > 12) {
            offsetIdx--
        }

        val inBedRecords = sorted.subList(onsetIdx, offsetIdx + 1)
        if (inBedRecords.size < 12) return null

        // Classify each epoch
        val rawStages = inBedRecords.map { record ->
            val motion = record.motionMagnitude
            val hr = record.heartRate
            val hrv = record.hrvRmssd

            when {
                // High motion or clear daytime elevation -> AWAKE
                motion >= 12 || (motion >= 4 && hr != null && hr > medianHr + 14) || (hr != null && hr > floorHr + 25) -> {
                    SleepStage.AWAKE
                }
                // Deep Sleep: Lowest nocturnal HR troughs, near-zero motion, calm HRV
                motion <= 2 && hr != null && hr <= medianHr && (hrv == null || hrv <= medianHrv + 10) -> {
                    SleepStage.DEEP
                }
                // REM Sleep: Muscle atonia (low motion), elevated HRV / HR fluctuation
                motion <= 4 && ((hrv != null && hrv >= medianHrv + 4) || (hr != null && hr in (medianHr + 1)..(medianHr + 8))) -> {
                    SleepStage.REM
                }
                // Light Sleep
                else -> {
                    SleepStage.LIGHT
                }
            }
        }

        val smoothedStages = smoothStages(rawStages)

        val stagedEpochs = inBedRecords.mapIndexed { index, record ->
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

        val sleepDurationMinutes = lightMinutes + deepMinutes + remMinutes
        if (sleepDurationMinutes < 15 && totalInBedMinutes < 30) return null

        val inBedHr = stagedEpochs.mapNotNull { it.heartRate }
        val avgHr = if (inBedHr.isNotEmpty()) inBedHr.average().toInt() else null
        val inBedHrv = stagedEpochs.mapNotNull { it.hrvRmssd }
        val avgHrv = if (inBedHrv.isNotEmpty()) inBedHrv.average().toInt() else null
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
