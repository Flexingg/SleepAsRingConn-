package com.randallengineering.sleepasringconn.analytics

import com.randallengineering.sleepasringconn.protocol.BulkRecord
import com.randallengineering.sleepasringconn.protocol.BulkRecordLayout
import java.util.Calendar

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

data class SleepBout(
    val stage: SleepStage,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationMinutes: Int,
    val avgHr: Int?,
    val peakMotion: Int
)

data class SleepCycle(
    val cycleIndex: Int,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationMinutes: Int,
    val deepMinutes: Int,
    val remMinutes: Int,
    val lightMinutes: Int,
    val awakeMinutes: Int
)

data class VitalExtreme(
    val value: Double,
    val timestampMillis: Long,
    val unit: String
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
    val sessionLabel: String = if (isNap) "Daytime Nap" else "Overnight Sleep",

    // In-depth timings & data overload metrics:
    val sleepOnsetMillis: Long = startTimeMillis,
    val sleepLatencyMinutes: Int = 0,
    val finalWakeMillis: Long = endTimeMillis,
    val midSleepMillis: Long = (startTimeMillis + endTimeMillis) / 2,
    val sleepEfficiencyPercent: Int = if (totalInBedMinutes > 0) ((sleepDurationMinutes.toFloat() / totalInBedMinutes) * 100).toInt() else 0,
    val cycles: List<SleepCycle> = emptyList(),
    val deepBouts: List<SleepBout> = emptyList(),
    val remBouts: List<SleepBout> = emptyList(),
    val awakenings: List<SleepBout> = emptyList(),
    val lowestHr: VitalExtreme? = null,
    val peakHr: VitalExtreme? = null,
    val lowestSpo2: VitalExtreme? = null,
    val peakHrv: VitalExtreme? = null,
    val minRespiratoryRate: Double? = null,
    val maxRespiratoryRate: Double? = null,
    val restlessEpochsCount: Int = 0,
    val stillEpochsPercent: Int = 100,

    // User editing metadata:
    val isUserEdited: Boolean = false,
    val originalStartTimeMillis: Long = startTimeMillis,
    val originalEndTimeMillis: Long = endTimeMillis
)

object SleepStagingEngine {

    /**
     * Extracts and stages all sleep sessions across the provided records (e.g. past 7-30 days),
     * including overnight sleep sessions and daytime naps, sorted with newest first.
     * Multiple sleep sessions per day are naturally supported via continuous physiological segmentation.
     */
    fun extractAllSleepSessions(records: List<BulkRecord>): List<SleepSession> {
        val nonIdle = records
            .filter { it.layout != BulkRecordLayout.IDLE }
            .sortedBy { it.timestampMillis }
        if (nonIdle.size < 5) return emptyList()

        // 1. Calculate adaptive baseline resting heart rate for the user
        val stillHrs = nonIdle
            .filter { it.motionMagnitude <= 3 && it.heartRate != null && it.heartRate in 35..150 }
            .map { it.heartRate!! }
        val allHrs = nonIdle.mapNotNull { it.heartRate }.filter { it in 35..180 }
        if (allHrs.isEmpty()) return emptyList()

        val sortedStillHrs = if (stillHrs.size >= 8) stillHrs.sorted() else allHrs.sorted()
        val floorHr = sortedStillHrs[(sortedStillHrs.size * 0.15).toInt().coerceIn(0, sortedStillHrs.size - 1)]
        val medianHr = sortedStillHrs[sortedStillHrs.size / 2]
        val sleepThresholdHr = minOf(medianHr + 5, floorHr + 16)

        fun isResting(r: BulkRecord): Boolean {
            val mot = r.motionMagnitude
            val hr = r.heartRate
            return when {
                // Obvious active daytime movement
                mot >= 8 -> false
                // Elevated HR well above resting
                hr != null && hr > sleepThresholdHr + 8 -> false
                // Very low motion with calm or unreadable HR
                mot <= 2 && (hr == null || hr <= sleepThresholdHr + 3) -> true
                // Resting motion with resting HR
                mot <= 5 && hr != null && hr <= sleepThresholdHr -> true
                // Mild stillness
                mot <= 4 && hr == null -> true
                else -> false
            }
        }

        // 2. Continuous timeline cluster detection (segments overnight sleep and naps separately)
        val rawSessions = mutableListOf<SleepSession>()
        var idx = 0
        val n = nonIdle.size

        while (idx < n) {
            // Find start of candidate sleep: at least 3 consecutive resting epochs (7.5 min of calm)
            var runCount = 0
            var startCandidate = -1

            for (i in idx until n) {
                if (isResting(nonIdle[i])) {
                    if (runCount == 0) startCandidate = i
                    runCount++
                    if (runCount >= 3) break
                } else {
                    runCount = 0
                    startCandidate = -1
                }
            }

            if (runCount < 3 || startCandidate < 0) {
                break // No more candidate sleep sessions in remaining data
            }

            // Allow up to 2 records before startCandidate for bed entry / settling down
            // ONLY if contiguous (within 10 minutes of sleep onset)
            var onsetIdx = startCandidate
            if (startCandidate > idx &&
                nonIdle[startCandidate].timestampMillis - nonIdle[startCandidate - 1].timestampMillis <= 10 * 60 * 1000L
            ) {
                onsetIdx = startCandidate - 1
                if (onsetIdx > idx &&
                    nonIdle[startCandidate].timestampMillis - nonIdle[onsetIdx - 1].timestampMillis <= 10 * 60 * 1000L
                ) {
                    onsetIdx = onsetIdx - 1
                }
            }

            var lastSleepIdx = startCandidate + runCount - 1
            var wakeCount = 0
            var endClusterIdx = lastSleepIdx

            // Scan forward through candidate sleep
            for (j in (startCandidate + runCount) until n) {
                val r = nonIdle[j]
                val prev = nonIdle[j - 1]

                // Prolonged gap in records (> 35 min) -> session definitely terminated
                if (r.timestampMillis - prev.timestampMillis > 35 * 60 * 1000L) {
                    break
                }

                if (isResting(r)) {
                    lastSleepIdx = j
                    wakeCount = 0
                } else {
                    wakeCount++
                    // Sustained active wakefulness (>= 8 consecutive non-resting epochs / 20 min)
                    if (wakeCount >= 8) break
                    // Or if more than 25 min elapsed since last resting epoch
                    if (r.timestampMillis - nonIdle[lastSleepIdx].timestampMillis > 25 * 60 * 1000L) {
                        break
                    }
                }
                endClusterIdx = j
            }

            // End boundary is exactly the last resting sleep epoch
            val offsetIdx = lastSleepIdx
            val clusterRecords = nonIdle.subList(onsetIdx, offsetIdx + 1)

            val restingCount = clusterRecords.count { isResting(it) }
            val estimatedSleepMinutes = restingCount * 5 / 2

            // Require at least 12 minutes of actual sleep
            if (estimatedSleepMinutes >= 12 && clusterRecords.size >= 5) {
                val session = stageSessionWindow(clusterRecords, floorHr = floorHr, medianHr = medianHr)
                if (session != null && session.sleepDurationMinutes >= 12) {
                    rawSessions.add(session)
                }
            }

            // Continue search after this session
            idx = maxOf(idx + 1, offsetIdx + 1)
        }

        // 3. Consolidate sessions with very short awakenings (< 20 min) between them
        val consolidated = mutableListOf<SleepSession>()
        for (sess in rawSessions) {
            val last = consolidated.lastOrNull()
            if (last != null && sess.startTimeMillis - last.endTimeMillis in 0..(20 * 60 * 1000L)) {
                // Merge into previous session
                val combinedRecords = nonIdle.filter {
                    it.timestampMillis in last.startTimeMillis..sess.endTimeMillis
                }
                val merged = stageSessionWindow(combinedRecords, floorHr = floorHr, medianHr = medianHr)
                if (merged != null) {
                    consolidated[consolidated.size - 1] = merged
                    continue
                }
            }
            consolidated.add(sess)
        }

        return consolidated.sortedByDescending { it.startTimeMillis }
    }

    /**
     * Backward-compatible helper: returns the latest sleep session.
     */
    fun stageRecords(records: List<BulkRecord>): SleepSession? {
        val all = extractAllSleepSessions(records)
        return all.firstOrNull()
    }

    /**
     * Stages a specific window of records for a sleep session.
     */
    fun stageSessionWindow(
        records: List<BulkRecord>,
        floorHr: Int? = null,
        medianHr: Int? = null,
        isNapOverride: Boolean? = null,
        labelOverride: String? = null,
        isUserEdited: Boolean = false,
        originalStart: Long? = null,
        originalEnd: Long? = null
    ): SleepSession? {
        if (records.size < 4) return null

        val sorted = records.sortedBy { it.timestampMillis }
        val hrValues = sorted.mapNotNull { it.heartRate }.filter { it in 30..220 }
        val inBedMedHr = if (hrValues.isNotEmpty()) {
            hrValues.sorted()[hrValues.size / 2]
        } else (medianHr ?: 65)

        val inBedFloorHr = if (hrValues.isNotEmpty()) {
            val sortedHr = hrValues.sorted()
            sortedHr[(sortedHr.size * 0.20).toInt().coerceIn(0, sortedHr.size - 1)]
        } else (floorHr ?: 55)

        val inBedHrvs = sorted.mapNotNull { it.hrvRmssd }
        val inBedMedHrv = if (inBedHrvs.isNotEmpty()) inBedHrvs.sorted()[inBedHrvs.size / 2] else 40

        // Classify each epoch
        val rawStages = sorted.map { record ->
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

        val stagedEpochs = sorted.mapIndexed { index, record ->
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
        if (sleepDurationMinutes < 10 && totalInBedMinutes < 15) return null

        val inBedHr = stagedEpochs.mapNotNull { it.heartRate }
        val avgHr = if (inBedHr.isNotEmpty()) inBedHr.average().toInt() else null
        val inBedHrv = stagedEpochs.mapNotNull { it.hrvRmssd }
        val avgHrv = if (inBedHrv.isNotEmpty()) inBedHrv.average().toInt() else null
        val spo2Values = stagedEpochs.mapNotNull { it.spo2 }
        val avgSpo2 = if (spo2Values.isNotEmpty()) spo2Values.average().toInt() else null
        val rrValues = stagedEpochs.mapNotNull { it.respiratoryRate }
        val avgRr = if (rrValues.isNotEmpty()) rrValues.average() else null

        val firstSleepIdx = stagedEpochs.indexOfFirst { it.stage != SleepStage.AWAKE }
        val sleepOnsetMillis = if (firstSleepIdx >= 0) stagedEpochs[firstSleepIdx].timestampMillis else startTime
        val sleepLatencyMinutes = ((sleepOnsetMillis - startTime) / 60_000L).toInt().coerceAtLeast(0)

        val lastSleepIdx = stagedEpochs.indexOfLast { it.stage != SleepStage.AWAKE }
        val finalWakeMillis = if (lastSleepIdx >= 0) stagedEpochs[lastSleepIdx].timestampMillis + 150_000L else endTime
        val midSleepMillis = startTime + (endTime - startTime) / 2

        // Nap classification logic:
        // A session is a nap if duration < 3 hours (180m) OR duration < 4 hours during daytime (6 AM - 8 PM)
        val cal = Calendar.getInstance().apply { timeInMillis = sleepOnsetMillis }
        val startHour = cal.get(Calendar.HOUR_OF_DAY)
        val isNap = isNapOverride ?: (sleepDurationMinutes < 180 || (sleepDurationMinutes < 240 && startHour in 6..19))

        val sessionLabel = labelOverride ?: when {
            !isNap -> "Overnight Sleep"
            sleepDurationMinutes <= 35 -> "Power Nap"
            startHour in 5..11 -> "Morning Nap"
            startHour in 12..16 -> "Afternoon Nap"
            startHour in 17..20 -> "Evening Nap"
            else -> "Daytime Nap"
        }

        val score = if (isNap) {
            val efficiency = if (totalInBedMinutes > 0) (sleepDurationMinutes.toDouble() / totalInBedMinutes).coerceIn(0.0, 1.0) else 0.85
            val deepBonus = minOf(15.0, deepMinutes * 1.0)
            val remBonus = minOf(10.0, remMinutes * 0.8)
            (efficiency * 65.0 + 10.0 + deepBonus + remBonus).toInt().coerceIn(55, 100)
        } else {
            calculateSleepScore(sleepDurationMinutes, deepMinutes, remMinutes, avgHr, avgSpo2)
        }

        val deepBouts = extractBouts(stagedEpochs, SleepStage.DEEP)
        val remBouts = extractBouts(stagedEpochs, SleepStage.REM)
        val awakenings = extractBouts(stagedEpochs, SleepStage.AWAKE).filter { it.startTimeMillis >= sleepOnsetMillis && it.endTimeMillis <= finalWakeMillis }
        val cycles = extractSleepCycles(stagedEpochs)

        val lowestHr = stagedEpochs.filter { it.heartRate != null }.minByOrNull { it.heartRate!! }?.let {
            VitalExtreme(it.heartRate!!.toDouble(), it.timestampMillis, "BPM")
        }
        val peakHr = stagedEpochs.filter { it.heartRate != null }.maxByOrNull { it.heartRate!! }?.let {
            VitalExtreme(it.heartRate!!.toDouble(), it.timestampMillis, "BPM")
        }
        val lowestSpo2 = stagedEpochs.filter { it.spo2 != null }.minByOrNull { it.spo2!! }?.let {
            VitalExtreme(it.spo2!!.toDouble(), it.timestampMillis, "%")
        }
        val peakHrv = stagedEpochs.filter { it.hrvRmssd != null }.maxByOrNull { it.hrvRmssd!! }?.let {
            VitalExtreme(it.hrvRmssd!!.toDouble(), it.timestampMillis, "ms")
        }
        val minRr = stagedEpochs.mapNotNull { it.respiratoryRate }.minOrNull()
        val maxRr = stagedEpochs.mapNotNull { it.respiratoryRate }.maxOrNull()
        val restlessCount = stagedEpochs.count { it.motionIntensity > 2 }
        val stillPercent = if (stagedEpochs.isNotEmpty()) (stagedEpochs.count { it.motionIntensity <= 1 } * 100) / stagedEpochs.size else 100

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
            epochs = stagedEpochs,
            isNap = isNap,
            sessionLabel = sessionLabel,
            sleepOnsetMillis = sleepOnsetMillis,
            sleepLatencyMinutes = sleepLatencyMinutes,
            finalWakeMillis = finalWakeMillis,
            midSleepMillis = midSleepMillis,
            sleepEfficiencyPercent = if (totalInBedMinutes > 0) ((sleepDurationMinutes.toFloat() / totalInBedMinutes) * 100).toInt() else 0,
            cycles = cycles,
            deepBouts = deepBouts,
            remBouts = remBouts,
            awakenings = awakenings,
            lowestHr = lowestHr,
            peakHr = peakHr,
            lowestSpo2 = lowestSpo2,
            peakHrv = peakHrv,
            minRespiratoryRate = minRr,
            maxRespiratoryRate = maxRr,
            restlessEpochsCount = restlessCount,
            stillEpochsPercent = stillPercent,
            isUserEdited = isUserEdited,
            originalStartTimeMillis = originalStart ?: startTime,
            originalEndTimeMillis = originalEnd ?: endTime
        )
    }

    /**
     * Re-stages or creates a session with custom user start and stop times.
     * If records exist in the range, they are staged.
     * If no records exist (e.g. manual entry while ring was off), synthetic epochs are generated.
     */
    fun stageCustomSession(
        records: List<BulkRecord>,
        startTimeMillis: Long,
        endTimeMillis: Long,
        isNapOverride: Boolean? = null,
        labelOverride: String? = null,
        isUserEdited: Boolean = true,
        originalStart: Long = startTimeMillis,
        originalEnd: Long = endTimeMillis
    ): SleepSession {
        val clampedEnd = maxOf(startTimeMillis + 150_000L, endTimeMillis)
        val recordsInWindow = records.filter { it.timestampMillis in startTimeMillis..clampedEnd }

        if (recordsInWindow.size >= 4) {
            val staged = stageSessionWindow(
                records = recordsInWindow,
                isNapOverride = isNapOverride,
                labelOverride = labelOverride,
                isUserEdited = isUserEdited,
                originalStart = originalStart,
                originalEnd = originalEnd
            )
            if (staged != null) {
                // Ensure exact user-specified start & end times
                return staged.copy(
                    startTimeMillis = startTimeMillis,
                    endTimeMillis = clampedEnd,
                    totalInBedMinutes = ((clampedEnd - startTimeMillis) / 60_000L).toInt(),
                    isUserEdited = isUserEdited,
                    originalStartTimeMillis = originalStart,
                    originalEndTimeMillis = originalEnd
                )
            }
        }

        // Synthesize epochs if minimal or no records are present in this window
        val durationMinutes = ((clampedEnd - startTimeMillis) / 60_000L).toInt()
        val numEpochs = maxOf(1, durationMinutes * 2 / 5) // each epoch = 2.5 min

        val cal = Calendar.getInstance().apply { timeInMillis = startTimeMillis }
        val startHour = cal.get(Calendar.HOUR_OF_DAY)
        val isNap = isNapOverride ?: (durationMinutes < 180 || (durationMinutes < 240 && startHour in 6..19))
        val sessionLabel = labelOverride ?: when {
            !isNap -> "Overnight Sleep"
            durationMinutes <= 35 -> "Power Nap"
            startHour in 5..11 -> "Morning Nap"
            startHour in 12..16 -> "Afternoon Nap"
            startHour in 17..20 -> "Evening Nap"
            else -> "Daytime Nap"
        }

        val syntheticEpochs = (0 until numEpochs).map { i ->
            val t = startTimeMillis + i * 150_000L
            val progress = i.toFloat() / maxOf(1, numEpochs - 1)
            val stage = when {
                i == 0 || i == numEpochs - 1 -> SleepStage.AWAKE
                progress in 0.15f..0.35f -> SleepStage.DEEP
                progress in 0.65f..0.85f -> SleepStage.REM
                else -> SleepStage.LIGHT
            }
            StagedEpoch(
                timestampMillis = t,
                stage = stage,
                heartRate = 62,
                hrvRmssd = 45,
                spo2 = 98,
                respiratoryRate = 15.0,
                motionIntensity = if (stage == SleepStage.AWAKE) 4 else 1
            )
        }

        val awakeMin = syntheticEpochs.count { it.stage == SleepStage.AWAKE } * 5 / 2
        val lightMin = syntheticEpochs.count { it.stage == SleepStage.LIGHT } * 5 / 2
        val deepMin = syntheticEpochs.count { it.stage == SleepStage.DEEP } * 5 / 2
        val remMin = syntheticEpochs.count { it.stage == SleepStage.REM } * 5 / 2
        val sleepMin = lightMin + deepMin + remMin

        val score = if (isNap) 85 else calculateSleepScore(sleepMin, deepMin, remMin, 62, 98)

        return SleepSession(
            startTimeMillis = startTimeMillis,
            endTimeMillis = clampedEnd,
            totalInBedMinutes = durationMinutes,
            sleepDurationMinutes = sleepMin,
            awakeMinutes = awakeMin,
            lightMinutes = lightMin,
            deepMinutes = deepMin,
            remMinutes = remMin,
            averageHeartRate = 62,
            averageHrvRmssd = 45,
            averageSpo2 = 98,
            averageRespiratoryRate = 15.0,
            sleepScore = score,
            epochs = syntheticEpochs,
            isNap = isNap,
            sessionLabel = sessionLabel,
            sleepOnsetMillis = startTimeMillis + 150_000L,
            sleepLatencyMinutes = 3,
            finalWakeMillis = clampedEnd - 150_000L,
            midSleepMillis = (startTimeMillis + clampedEnd) / 2,
            sleepEfficiencyPercent = if (durationMinutes > 0) ((sleepMin.toFloat() / durationMinutes) * 100).toInt() else 0,
            isUserEdited = isUserEdited,
            originalStartTimeMillis = originalStart,
            originalEndTimeMillis = originalEnd
        )
    }

    private fun extractBouts(epochs: List<StagedEpoch>, targetStage: SleepStage): List<SleepBout> {
        val bouts = mutableListOf<SleepBout>()
        var currentRun = mutableListOf<StagedEpoch>()

        for (ep in epochs) {
            if (ep.stage == targetStage) {
                currentRun.add(ep)
            } else {
                if (currentRun.isNotEmpty()) {
                    val start = currentRun.first().timestampMillis
                    val end = currentRun.last().timestampMillis + 150_000L
                    val dur = ((end - start) / 60_000L).toInt()
                    val hrs = currentRun.mapNotNull { it.heartRate }
                    val avgHr = if (hrs.isNotEmpty()) hrs.average().toInt() else null
                    val peakMot = currentRun.maxOfOrNull { it.motionIntensity } ?: 0
                    bouts.add(SleepBout(targetStage, start, end, dur, avgHr, peakMot))
                    currentRun = mutableListOf()
                }
            }
        }
        if (currentRun.isNotEmpty()) {
            val start = currentRun.first().timestampMillis
            val end = currentRun.last().timestampMillis + 150_000L
            val dur = ((end - start) / 60_000L).toInt()
            val hrs = currentRun.mapNotNull { it.heartRate }
            val avgHr = if (hrs.isNotEmpty()) hrs.average().toInt() else null
            val peakMot = currentRun.maxOfOrNull { it.motionIntensity } ?: 0
            bouts.add(SleepBout(targetStage, start, end, dur, avgHr, peakMot))
        }
        return bouts
    }

    private fun extractSleepCycles(epochs: List<StagedEpoch>): List<SleepCycle> {
        if (epochs.isEmpty()) return emptyList()
        val cycles = mutableListOf<SleepCycle>()
        var cycleIdx = 1
        var cycleEpochs = mutableListOf<StagedEpoch>()
        var inRem = false

        for (ep in epochs) {
            cycleEpochs.add(ep)
            if (ep.stage == SleepStage.REM) {
                inRem = true
            } else if (inRem && (ep.stage == SleepStage.LIGHT || ep.stage == SleepStage.DEEP || ep.stage == SleepStage.AWAKE)) {
                if (cycleEpochs.size >= 16) { // >= 40 min
                    val start = cycleEpochs.first().timestampMillis
                    val end = cycleEpochs.last().timestampMillis + 150_000L
                    val dur = ((end - start) / 60_000L).toInt()
                    val deep = cycleEpochs.count { it.stage == SleepStage.DEEP } * 5 / 2
                    val rem = cycleEpochs.count { it.stage == SleepStage.REM } * 5 / 2
                    val light = cycleEpochs.count { it.stage == SleepStage.LIGHT } * 5 / 2
                    val awake = cycleEpochs.count { it.stage == SleepStage.AWAKE } * 5 / 2
                    cycles.add(SleepCycle(cycleIdx++, start, end, dur, deep, rem, light, awake))
                    cycleEpochs = mutableListOf()
                    inRem = false
                }
            }
        }
        if (cycleEpochs.size >= 8) {
            val start = cycleEpochs.first().timestampMillis
            val end = cycleEpochs.last().timestampMillis + 150_000L
            val dur = ((end - start) / 60_000L).toInt()
            val deep = cycleEpochs.count { it.stage == SleepStage.DEEP } * 5 / 2
            val rem = cycleEpochs.count { it.stage == SleepStage.REM } * 5 / 2
            val light = cycleEpochs.count { it.stage == SleepStage.LIGHT } * 5 / 2
            val awake = cycleEpochs.count { it.stage == SleepStage.AWAKE } * 5 / 2
            cycles.add(SleepCycle(cycleIdx, start, end, dur, deep, rem, light, awake))
        }
        return cycles
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
