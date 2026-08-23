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
     * - Estimates nocturnal baseline HR and HRV.
     * - Uses motion magnitude from acti_counts, HR dip, HRV elevation, and SpO2 duty cycle alternation.
     * - Deep sleep: Very low motion, lowest HR (HR < baseline - 5), low HRV RMSSD variance.
     * - REM sleep: Low motion, elevated HRV RMSSD (HRV > baseline + 5), slightly higher HR variance.
     * - Light sleep: Low motion, moderate HR / HRV.
     * - Awake: Elevated motion intensity, high HR.
     */
    fun stageRecords(records: List<BulkRecord>): SleepSession? {
        val sorted = records.filter { it.layout != BulkRecordLayout.IDLE }.sortedBy { it.timestampMillis }
        if (sorted.size < 12) return null // At least 30 minutes of data (12 * 2.5 min)

        val hrValues = sorted.mapNotNull { it.heartRate }
        if (hrValues.isEmpty()) return null
        val baselineHr = hrValues.sorted()[hrValues.size / 2] // Median HR

        val hrvValues = sorted.mapNotNull { it.hrvRmssd }
        val baselineHrv = if (hrvValues.isNotEmpty()) hrvValues.sorted()[hrvValues.size / 2] else 40

        val stagedEpochs = mutableListOf<StagedEpoch>()

        for (record in sorted) {
            val motion = calculateMotionIntensity(record.activityCounts)
            val hr = record.heartRate
            val hrv = record.hrvRmssd

            val stage = when {
                motion > 45 || record.layout == BulkRecordLayout.ACTIVITY && (hr != null && hr > baselineHr + 15) -> {
                    SleepStage.AWAKE
                }
                motion <= 10 && hr != null && hr <= baselineHr - 4 && (hrv != null && hrv < baselineHrv + 10) -> {
                    SleepStage.DEEP
                }
                motion <= 15 && hrv != null && hrv >= baselineHrv + 8 -> {
                    SleepStage.REM
                }
                else -> {
                    SleepStage.LIGHT
                }
            }

            stagedEpochs.add(
                StagedEpoch(
                    timestampMillis = record.timestampMillis,
                    stage = stage,
                    heartRate = hr,
                    hrvRmssd = hrv,
                    spo2 = record.spo2Percent,
                    respiratoryRate = record.respiratoryRate,
                    motionIntensity = motion
                )
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

    private fun calculateMotionIntensity(activityCounts: ByteArray): Int {
        var sum = 0
        for (b in activityCounts) {
            val v = b.toInt() and 0xFF
            sum += if (v > 1) v else 0
        }
        return sum
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
