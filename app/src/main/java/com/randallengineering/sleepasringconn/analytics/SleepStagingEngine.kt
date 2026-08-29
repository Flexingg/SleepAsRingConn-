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
    val epochs: List<StagedEpoch>,
    val isNap: Boolean = false,
    val sessionLabel: String = if (isNap) "Daytime Nap" else "Overnight Sleep"
)

object SleepStagingEngine {

    /**
     * Extracts and stages all sleep sessions across the provided records (e.g. past 7-30 days),
     * including overnight sleep sessions and daytime naps, sorted with newest first.
     */
    fun extractAllSleepSessions(records: List<BulkRecord>): List<SleepSession> {
        val nonIdle = records.filter { it.layout != BulkRecordLayout.IDLE }.sortedBy { it.timestampMillis }
        if (nonIdle.size < 6) return emptyList()

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

        // 2. Stage each night & detect naps
        val allSessions = mutableListOf<SleepSession>()
        for ((_, bucketRecords) in nightBuckets) {
            val mainSleep = stageNight(bucketRecords)
            if (mainSleep != null) {
                allSessions.add(mainSleep)
            }

            // Extract naps outside of main sleep
            val naps = extractNaps(bucketRecords, mainSleep)
            allSessions.addAll(naps)
        }

        return allSessions.sortedByDescending { it.startTimeMillis }
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
        val sleepThresholdHr = medianHr + 4

        // 1. Identify sleep candidate epochs (resting HR & still actigraphy)
        val isSleepList = sorted.map { record ->
            val hr = record.heartRate
            val mot = record.motionMagnitude
            hr != null && hr <= sleepThresholdHr && mot <= 5
        }

        // 2. Find consolidated Sleep Onset (first sustained run >= 5 epochs / 12.5m)
        var onsetIdx: Int? = null
        for (i in 0 until isSleepList.size - 5) {
            if (isSleepList.subList(i, i + 5).all { it }) {
                onsetIdx = maxOf(0, i - 2) // allow up to 5 min wind-down in bed
                break
            }
        }
        if (onsetIdx == null) return null

        // 3. Find consolidated Final Wake / Offset (last sustained run >= 4 epochs / 10m)
        var offsetIdx: Int? = null
        for (i in isSleepList.size - 4 downTo onsetIdx + 1) {
            if (isSleepList.subList(i, i + 4).all { it }) {
                offsetIdx = minOf(isSleepList.size - 1, i + 5) // allow 5-10 min waking up
                break
            }
        }
        if (offsetIdx == null || offsetIdx <= onsetIdx + 8) return null

        val inBedRecords = sorted.subList(onsetIdx, offsetIdx + 1)
        if (inBedRecords.size < 12) return null

        val inBedHrs = inBedRecords.mapNotNull { it.heartRate }
        val inBedMedHr = if (inBedHrs.isNotEmpty()) inBedHrs.sorted()[inBedHrs.size / 2] else medianHr
        val inBedFloorHr = if (inBedHrs.isNotEmpty()) inBedHrs.sorted()[(inBedHrs.size * 0.20).toInt().coerceIn(0, inBedHrs.size - 1)] else floorHr

        val inBedHrvs = inBedRecords.mapNotNull { it.hrvRmssd }
        val inBedMedHrv = if (inBedHrvs.isNotEmpty()) inBedHrvs.sorted()[inBedHrvs.size / 2] else 40

        // 4. Classify each epoch within the true bedtime window
        val rawStages = inBedRecords.map { record ->
            val motion = record.motionMagnitude
            val hr = record.heartRate
            val hrv = record.hrvRmssd

            when {
                // High motion or clear awake HR elevation -> AWAKE
                hr == null || motion >= 12 || (motion >= 4 && hr > inBedMedHr + 12) || hr > inBedFloorHr + 20 -> {
                    SleepStage.AWAKE
                }
                // Deep Sleep: Lowest nocturnal HR troughs, near-zero motion, calm HRV
                motion <= 2 && hr <= inBedMedHr && (hrv == null || hrv <= inBedMedHrv + 10) -> {
                    SleepStage.DEEP
                }
                // REM Sleep: Muscle atonia (low motion), elevated HRV / HR fluctuation
                motion <= 4 && ((hrv != null && hrv >= inBedMedHrv + 4) || (hr in (inBedMedHr + 1)..(inBedMedHr + 8))) -> {
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

    private fun extractNaps(records: List<BulkRecord>, mainSleep: SleepSession?): List<SleepSession> {
        val outsideRecords = records.filter { record ->
            if (mainSleep == null) true
            else {
                val t = record.timestampMillis
                t < (mainSleep.startTimeMillis - 15 * 60 * 1000L) || t > (mainSleep.endTimeMillis + 15 * 60 * 1000L)
            }
        }.sortedBy { it.timestampMillis }

        if (outsideRecords.size < 6) return emptyList()

        val hrValues = outsideRecords.mapNotNull { it.heartRate }
        if (hrValues.isEmpty()) return emptyList()
        val medianHr = hrValues.sorted()[hrValues.size / 2]
        val sleepThresholdHr = medianHr + 4

        val naps = mutableListOf<SleepSession>()
        var currentNapEpochs = mutableListOf<BulkRecord>()

        for (record in outsideRecords) {
            val hr = record.heartRate
            val mot = record.motionMagnitude
            val isResting = hr != null && hr <= sleepThresholdHr && mot <= 5

            if (isResting) {
                currentNapEpochs.add(record)
            } else {
                if (currentNapEpochs.size >= 6) { // >= 15 min
                    stageNapSession(currentNapEpochs)?.let { naps.add(it) }
                }
                currentNapEpochs = mutableListOf()
            }
        }

        if (currentNapEpochs.size >= 6) {
            stageNapSession(currentNapEpochs)?.let { naps.add(it) }
        }

        return naps
    }

    private fun stageNapSession(napRecords: List<BulkRecord>): SleepSession? {
        if (napRecords.size < 6) return null

        val hrValues = napRecords.mapNotNull { it.heartRate }
        if (hrValues.isEmpty()) return null
        val medHr = hrValues.sorted()[hrValues.size / 2]
        val floorHr = hrValues.sorted()[(hrValues.size * 0.20).toInt().coerceIn(0, hrValues.size - 1)]

        val hrvValues = napRecords.mapNotNull { it.hrvRmssd }
        val medHrv = if (hrvValues.isNotEmpty()) hrvValues.sorted()[hrvValues.size / 2] else 40

        val rawStages = napRecords.map { record ->
            val mot = record.motionMagnitude
            val hr = record.heartRate
            val hrv = record.hrvRmssd

            when {
                hr == null || mot >= 10 || (mot >= 4 && hr > medHr + 10) -> SleepStage.AWAKE
                mot <= 2 && hr <= medHr && (hrv == null || hrv <= medHrv + 8) -> SleepStage.DEEP
                mot <= 4 && ((hrv != null && hrv >= medHrv + 4) || (hr in (medHr + 1)..(medHr + 6))) -> SleepStage.REM
                else -> SleepStage.LIGHT
            }
        }

        val smoothed = smoothStages(rawStages)
        val stagedEpochs = napRecords.mapIndexed { idx, record ->
            StagedEpoch(
                timestampMillis = record.timestampMillis,
                stage = smoothed[idx],
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

        if (sleepDurationMinutes < 12) return null

        val inBedHr = stagedEpochs.mapNotNull { it.heartRate }
        val avgHr = if (inBedHr.isNotEmpty()) inBedHr.average().toInt() else null
        val inBedHrv = stagedEpochs.mapNotNull { it.hrvRmssd }
        val avgHrv = if (inBedHrv.isNotEmpty()) inBedHrv.average().toInt() else null
        val spo2Values = stagedEpochs.mapNotNull { it.spo2 }
        val avgSpo2 = if (spo2Values.isNotEmpty()) spo2Values.average().toInt() else null
        val rrValues = stagedEpochs.mapNotNull { it.respiratoryRate }
        val avgRr = if (rrValues.isNotEmpty()) rrValues.average() else null

        // Nap score: evaluated on rest quality
        val napScore = ((sleepDurationMinutes.toDouble() / maxOf(totalInBedMinutes, 1).toDouble()) * 80.0 + 20.0).toInt().coerceIn(60, 100)

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
            sleepScore = napScore,
            epochs = stagedEpochs,
            isNap = true,
            sessionLabel = if (sleepDurationMinutes <= 35) "Power Nap" else "Daytime Nap"
        )
    }
}
