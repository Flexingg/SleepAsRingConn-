package com.randallengineering.sleepasringconn.goals

import android.content.Context
import com.randallengineering.sleepasringconn.data.DeviceStatusEntity
import com.randallengineering.sleepasringconn.data.EpochEntity
import com.randallengineering.sleepasringconn.data.SleepSessionEntity
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

object GoalEngine {

    data class EvaluationResult(
        val goals: List<GoalProgress>,
        val overallCompliancePct: Int,
        val totalStreakDays: Int,
        val tips: List<HealthTip>,
        val badges: List<MilestoneBadge>
    )

    fun evaluate(
        context: Context,
        epochs: List<EpochEntity>,
        sessions: List<SleepSessionEntity>,
        statusLogs: List<DeviceStatusEntity>
    ): EvaluationResult {
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())

        // Build 7 daily bins for the past 7 days (today is index 6)
        val dailyBins = (0..6).map { daysAgo ->
            val dayCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -(6 - daysAgo))
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startMillis = dayCal.timeInMillis
            val endMillis = startMillis + 24 * 60 * 60 * 1000 - 1
            val label = dayFormat.format(Date(startMillis))
            DailyBin(startMillis, endMillis, label)
        }

        val goalProgressList = GoalType.entries.map { goalType ->
            val isEnabled = GoalPreferences.isGoalEnabled(context, goalType)
            val target = GoalPreferences.getTarget(context, goalType)

            val dailyResults = dailyBins.map { bin ->
                val actual = calculateDailyActual(goalType, bin, epochs, sessions, statusLogs)
                val isAchieved = if (actual != null) {
                    when (goalType) {
                        GoalType.RESTING_HR -> actual <= target
                        GoalType.BEDTIME_CONSISTENCY -> abs(actual - target) <= 0.75f // within 45 min
                        else -> actual >= target
                    }
                } else false

                DailyGoalResult(
                    dayTimestamp = bin.startMillis,
                    dayLabel = bin.label,
                    actualValue = actual,
                    isAchieved = isAchieved
                )
            }

            val nonNullActuals = dailyResults.mapNotNull { it.actualValue }
            val weeklyAvg = if (nonNullActuals.isNotEmpty()) nonNullActuals.average().toFloat() else null
            val todayActual = dailyResults.lastOrNull()?.actualValue ?: dailyResults.dropLast(1).lastOrNull { it.actualValue != null }?.actualValue

            val achievedCount = dailyResults.count { it.isAchieved }
            val validCount = dailyResults.count { it.actualValue != null }
            val achievementRate = if (validCount > 0) ((achievedCount.toFloat() / validCount.toFloat()) * 100).toInt() else 0

            // Calculate streak (consecutive achieved days up to latest day with data)
            var streak = 0
            for (res in dailyResults.reversed()) {
                if (res.actualValue != null) {
                    if (res.isAchieved) {
                        streak++
                    } else {
                        break
                    }
                }
            }

            val status = when {
                validCount == 0 -> GoalStatus.NO_DATA
                dailyResults.lastOrNull()?.isAchieved == true -> GoalStatus.ACHIEVED
                weeklyAvg != null && (if (goalType.higherIsBetter) weeklyAvg >= target else weeklyAvg <= target) -> GoalStatus.ON_TRACK
                else -> GoalStatus.OFF_TRACK
            }

            GoalProgress(
                goalType = goalType,
                targetValue = target,
                todayValue = todayActual,
                weeklyAverage = weeklyAvg,
                achievementRatePct = achievementRate,
                currentStreakDays = streak,
                past7Days = dailyResults,
                status = status
            )
        }

        // Overall compliance across all enabled goals
        val totalAchievedDays = goalProgressList.sumOf { p -> p.past7Days.count { it.isAchieved } }
        val totalTrackedDays = goalProgressList.sumOf { p -> p.past7Days.count { it.actualValue != null } }
        val overallCompliance = if (totalTrackedDays > 0) ((totalAchievedDays.toFloat() / totalTrackedDays.toFloat()) * 100).toInt() else 0
        val maxStreak = goalProgressList.maxOfOrNull { it.currentStreakDays } ?: 0

        val tips = generateTips(goalProgressList)
        val badges = generateBadges(goalProgressList)

        return EvaluationResult(
            goals = goalProgressList,
            overallCompliancePct = overallCompliance,
            totalStreakDays = maxStreak,
            tips = tips,
            badges = badges
        )
    }

    private data class DailyBin(val startMillis: Long, val endMillis: Long, val label: String)

    private fun calculateDailyActual(
        goalType: GoalType,
        bin: DailyBin,
        epochs: List<EpochEntity>,
        sessions: List<SleepSessionEntity>,
        statusLogs: List<DeviceStatusEntity>
    ): Float? {
        return when (goalType) {
            GoalType.SLEEP_DURATION -> {
                val daySessions = sessions.filter { it.endTimeMillis in bin.startMillis..bin.endMillis || it.startTimeMillis in bin.startMillis..bin.endMillis }
                if (daySessions.isNotEmpty()) {
                    val totalMinutes = daySessions.sumOf { it.totalDurationMinutes }
                    totalMinutes / 60.0f
                } else null
            }
            GoalType.DEEP_SLEEP_PCT -> {
                val daySessions = sessions.filter { it.endTimeMillis in bin.startMillis..bin.endMillis || it.startTimeMillis in bin.startMillis..bin.endMillis }
                if (daySessions.isNotEmpty()) {
                    val totalMin = daySessions.sumOf { it.totalDurationMinutes }
                    val deepMin = daySessions.sumOf { it.deepMinutes }
                    if (totalMin > 0) (deepMin.toFloat() / totalMin.toFloat()) * 100f else null
                } else null
            }
            GoalType.RESTING_HR -> {
                val daySessions = sessions.filter { it.endTimeMillis in bin.startMillis..bin.endMillis || it.startTimeMillis in bin.startMillis..bin.endMillis }
                val sessionHrs = daySessions.mapNotNull { it.avgHeartRate }
                if (sessionHrs.isNotEmpty()) {
                    sessionHrs.average().toFloat()
                } else {
                    val dayEpochs = epochs.filter { it.timestampMillis in bin.startMillis..bin.endMillis && it.heartRate != null }
                    if (dayEpochs.isNotEmpty()) {
                        val hrs = dayEpochs.mapNotNull { it.heartRate }.sorted()
                        val index = (hrs.size * 0.1).toInt().coerceIn(0, hrs.size - 1)
                        hrs[index].toFloat()
                    } else null
                }
            }
            GoalType.HRV_RECOVERY -> {
                val daySessions = sessions.filter { it.endTimeMillis in bin.startMillis..bin.endMillis || it.startTimeMillis in bin.startMillis..bin.endMillis }
                val sessionHrvs = daySessions.mapNotNull { it.avgHrvRmssd }
                if (sessionHrvs.isNotEmpty()) {
                    sessionHrvs.average().toFloat()
                } else {
                    val dayEpochs = epochs.filter { it.timestampMillis in bin.startMillis..bin.endMillis && it.hrvRmssd != null }
                    if (dayEpochs.isNotEmpty()) {
                        dayEpochs.mapNotNull { it.hrvRmssd }.average().toFloat()
                    } else null
                }
            }
            GoalType.DAILY_STEPS -> {
                val dayLogs = statusLogs.filter { it.timestampMillis in bin.startMillis..bin.endMillis }
                if (dayLogs.isNotEmpty()) {
                    dayLogs.sumOf { it.quarterHourSteps }.toFloat()
                } else null
            }
            GoalType.BEDTIME_CONSISTENCY -> {
                val daySessions = sessions.filter { it.endTimeMillis in bin.startMillis..bin.endMillis || it.startTimeMillis in bin.startMillis..bin.endMillis }
                val mainSession = daySessions.maxByOrNull { it.totalDurationMinutes }
                if (mainSession != null) {
                    val cal = Calendar.getInstance().apply { timeInMillis = mainSession.startTimeMillis }
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val minute = cal.get(Calendar.MINUTE)
                    var decimalHour = hour + (minute / 60.0f)
                    if (decimalHour < 12.0f) decimalHour += 24.0f
                    decimalHour
                } else null
            }
            GoalType.SPO2_FLOOR -> {
                val daySessions = sessions.filter { it.endTimeMillis in bin.startMillis..bin.endMillis || it.startTimeMillis in bin.startMillis..bin.endMillis }
                val sessionSpo2s = daySessions.mapNotNull { it.avgSpo2 }
                if (sessionSpo2s.isNotEmpty()) {
                    sessionSpo2s.minOrNull()?.toFloat()
                } else {
                    val dayEpochs = epochs.filter { it.timestampMillis in bin.startMillis..bin.endMillis && it.spo2Percent != null }
                    if (dayEpochs.isNotEmpty()) {
                        dayEpochs.mapNotNull { it.spo2Percent }.minOrNull()?.toFloat()
                    } else null
                }
            }
        }
    }

    private fun generateTips(goals: List<GoalProgress>): List<HealthTip> {
        val tips = mutableListOf<HealthTip>()

        val strongStreak = goals.filter { it.currentStreakDays >= 3 }.maxByOrNull { it.currentStreakDays }
        if (strongStreak != null) {
            tips.add(
                HealthTip(
                    id = "streak_${strongStreak.goalType.id}",
                    title = "🔥 ${strongStreak.currentStreakDays}-Day Streak on ${strongStreak.goalType.title}!",
                    message = "Outstanding consistency! You've achieved your ${strongStreak.goalType.title} target of ${strongStreak.goalType.formatValue(strongStreak.targetValue)} for ${strongStreak.currentStreakDays} consecutive days. Habits like this solidify long-term metabolic and neurological resilience.",
                    category = TipCategory.RECOVERY,
                    severity = TipSeverity.CELEBRATION,
                    metricSource = strongStreak.goalType
                )
            )
        }

        val hrvGoal = goals.find { it.goalType == GoalType.HRV_RECOVERY }
        if (hrvGoal != null) {
            val avg = hrvGoal.weeklyAverage
            val today = hrvGoal.todayValue
            if (today != null && avg != null && today < avg * 0.82f) {
                tips.add(
                    HealthTip(
                        id = "hrv_dip_alert",
                        title = "Autonomic Recovery Advisory (HRV Dip)",
                        message = "Your nocturnal HRV dropped to %.0f ms today (18%% below your 7-day average of %.0f ms). This indicates elevated sympathetic nervous system tone, which can result from intense workouts, delayed digestion, or stress. Prioritize hydration with magnesium/electrolytes, 4-7-8 breathing, and light mobility work today.".format(today, avg),
                        category = TipCategory.RECOVERY,
                        severity = TipSeverity.WARNING,
                        metricSource = GoalType.HRV_RECOVERY
                    )
                )
            } else if (avg != null && avg >= hrvGoal.targetValue) {
                tips.add(
                    HealthTip(
                        id = "hrv_high_readiness",
                        title = "High Vagal Tone & Readiness",
                        message = "Your weekly HRV is robust at %.0f ms (above your %.0f ms target). High parasympathetic activity indicates excellent cardiovascular adaptation and readiness for physical exertion.".format(avg, hrvGoal.targetValue),
                        category = TipCategory.RECOVERY,
                        severity = TipSeverity.INSIGHT,
                        metricSource = GoalType.HRV_RECOVERY
                    )
                )
            }
        }

        val deepGoal = goals.find { it.goalType == GoalType.DEEP_SLEEP_PCT }
        if (deepGoal != null) {
            val deepAvg = deepGoal.weeklyAverage
            if (deepAvg != null && deepAvg < 18f) {
                tips.add(
                    HealthTip(
                        id = "deep_sleep_boost",
                        title = "Optimizing Stage 3 Slow-Wave Deep Sleep",
                        message = "Deep sleep averaged %.0f%% this week (target %.0f%%). Deep sleep concentrates in the first third of the night and requires a rapid core body temperature decline. Keep your room temperature at 65-68°F (18-20°C), avoid heavy meals within 3 hours of sleep, and minimize blue light exposure after 9 PM.".format(deepAvg, deepGoal.targetValue),
                        category = TipCategory.SLEEP_HYGIENE,
                        severity = TipSeverity.ADVICE,
                        metricSource = GoalType.DEEP_SLEEP_PCT
                    )
                )
            }
        }

        val rhrGoal = goals.find { it.goalType == GoalType.RESTING_HR }
        if (rhrGoal != null) {
            val rhrAvg = rhrGoal.weeklyAverage
            if (rhrAvg != null && rhrAvg > rhrGoal.targetValue + 3) {
                tips.add(
                    HealthTip(
                        id = "rhr_elevation",
                        title = "Nocturnal Heart Rate Baseline",
                        message = "Your resting heart rate averaged %.0f BPM (above your target of %.0f BPM). Elevated resting heart rate overnight is frequently tied to late-night caffeine, alcohol metabolism, or evening digestion. Finishing dinner 3+ hours before bed allows your heart rate to reach its true baseline floor early in the night.".format(rhrAvg, rhrGoal.targetValue),
                        category = TipCategory.CARDIO,
                        severity = TipSeverity.ADVICE,
                        metricSource = GoalType.RESTING_HR
                    )
                )
            }
        }

        val bedtimeGoal = goals.find { it.goalType == GoalType.BEDTIME_CONSISTENCY }
        if (bedtimeGoal != null && bedtimeGoal.achievementRatePct < 60) {
            tips.add(
                HealthTip(
                    id = "bedtime_circadian",
                    title = "Circadian Anchoring & Sleep Latency",
                    message = "Your sleep onset fluctuated across several nights this week. Aligning your bedtime within a consistent 30-minute window sets your suprachiasmatic nucleus master clock, speeding up sleep onset and maximizing REM sleep density.",
                    category = TipCategory.CIRCADIAN,
                    severity = TipSeverity.INSIGHT,
                    metricSource = GoalType.BEDTIME_CONSISTENCY
                )
            )
        }

        val spo2Goal = goals.find { it.goalType == GoalType.SPO2_FLOOR }
        if (spo2Goal != null) {
            val spo2Min = spo2Goal.past7Days.mapNotNull { it.actualValue }.minOrNull()
            if (spo2Min != null && spo2Min < 94f) {
                tips.add(
                    HealthTip(
                        id = "spo2_position",
                        title = "Nocturnal Oxygen & Sleep Position",
                        message = "Nocturnal SpO2 dipped to %.0f%% on recent nights. Sleeping on your back (supine) can cause gravitational airway narrowing; sleeping on your left or right side with a contour pillow keeps the upper airway patent and stabilizes blood oxygenation.".format(spo2Min),
                        category = TipCategory.SLEEP_HYGIENE,
                        severity = TipSeverity.ADVICE,
                        metricSource = GoalType.SPO2_FLOOR
                    )
                )
            }
        }

        val stepsGoal = goals.find { it.goalType == GoalType.DAILY_STEPS }
        if (stepsGoal != null) {
            val stepsAvg = stepsGoal.weeklyAverage
            if (stepsAvg != null && stepsAvg < stepsGoal.targetValue * 0.8f) {
                tips.add(
                    HealthTip(
                        id = "steps_sleep_pressure",
                        title = "Adenosine Sleep Drive via Daily Steps",
                        message = "Daily steps averaged %,.0f. Accumulating moderate physical movement throughout the day steadily builds adenosine in the basal forebrain, which dramatically increases nocturnal sleep drive and makes falling asleep effortless.".format(stepsAvg),
                        category = TipCategory.ACTIVITY,
                        severity = TipSeverity.INSIGHT,
                        metricSource = GoalType.DAILY_STEPS
                    )
                )
            }
        }

        if (tips.isEmpty()) {
            tips.add(
                HealthTip(
                    id = "general_wellness",
                    title = "Optimal Biometric Equilibrium",
                    message = "All monitored physiological biomarkers are tracking within healthy parameters! Continue maintaining your sleep duration, daytime activity, and regular wind-down routine.",
                    category = TipCategory.RECOVERY,
                    severity = TipSeverity.INSIGHT
                )
            )
        }

        return tips
    }

    private fun generateBadges(goals: List<GoalProgress>): List<MilestoneBadge> {
        val badges = mutableListOf<MilestoneBadge>()

        val sleepGoal = goals.find { it.goalType == GoalType.SLEEP_DURATION }
        val sleepDaysHit = sleepGoal?.past7Days?.count { it.isAchieved } ?: 0
        badges.add(
            MilestoneBadge(
                id = "sleep_master",
                title = "Sleep Champion",
                description = "Hit 8+ hours of sleep for 5 nights in a week",
                progress = (sleepDaysHit / 5.0f).coerceIn(0f, 1f),
                isUnlocked = sleepDaysHit >= 5,
                levelDescription = "$sleepDaysHit / 5 Nights"
            )
        )

        val deepGoal = goals.find { it.goalType == GoalType.DEEP_SLEEP_PCT }
        val deepDaysHit = deepGoal?.past7Days?.count { it.isAchieved } ?: 0
        badges.add(
            MilestoneBadge(
                id = "deep_dynamo",
                title = "Deep Sleep Dynamo",
                description = "Achieve 20%+ deep restorative sleep on 4 nights",
                progress = (deepDaysHit / 4.0f).coerceIn(0f, 1f),
                isUnlocked = deepDaysHit >= 4,
                levelDescription = "$deepDaysHit / 4 Nights"
            )
        )

        val hrvGoal = goals.find { it.goalType == GoalType.HRV_RECOVERY }
        val hrvDaysHit = hrvGoal?.past7Days?.count { it.isAchieved } ?: 0
        badges.add(
            MilestoneBadge(
                id = "hrv_elite",
                title = "Vagal Recovery Elite",
                description = "Maintain high HRV recovery on 5 days",
                progress = (hrvDaysHit / 5.0f).coerceIn(0f, 1f),
                isUnlocked = hrvDaysHit >= 5,
                levelDescription = "$hrvDaysHit / 5 Days"
            )
        )

        val rhrGoal = goals.find { it.goalType == GoalType.RESTING_HR }
        val rhrDaysHit = rhrGoal?.past7Days?.count { it.isAchieved } ?: 0
        badges.add(
            MilestoneBadge(
                id = "cardio_anchor",
                title = "Cardio Rest Anchor",
                description = "Keep resting HR below target for 5 nights",
                progress = (rhrDaysHit / 5.0f).coerceIn(0f, 1f),
                isUnlocked = rhrDaysHit >= 5,
                levelDescription = "$rhrDaysHit / 5 Nights"
            )
        )

        val bedtimeGoal = goals.find { it.goalType == GoalType.BEDTIME_CONSISTENCY }
        val bedtimeDaysHit = bedtimeGoal?.past7Days?.count { it.isAchieved } ?: 0
        badges.add(
            MilestoneBadge(
                id = "circadian_anchor",
                title = "Circadian Anchor",
                description = "Fall asleep within target bedtime window 5 times",
                progress = (bedtimeDaysHit / 5.0f).coerceIn(0f, 1f),
                isUnlocked = bedtimeDaysHit >= 5,
                levelDescription = "$bedtimeDaysHit / 5 Nights"
            )
        )

        val spo2Goal = goals.find { it.goalType == GoalType.SPO2_FLOOR }
        val spo2DaysHit = spo2Goal?.past7Days?.count { it.isAchieved } ?: 0
        badges.add(
            MilestoneBadge(
                id = "oxygen_sentinel",
                title = "Oxygen Sentinel",
                description = "Maintain 95%+ SpO2 stability on all 7 nights",
                progress = (spo2DaysHit / 7.0f).coerceIn(0f, 1f),
                isUnlocked = spo2DaysHit >= 7,
                levelDescription = "$spo2DaysHit / 7 Nights"
            )
        )

        return badges
    }
}
