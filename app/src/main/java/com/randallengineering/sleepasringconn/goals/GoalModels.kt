package com.randallengineering.sleepasringconn.goals

import androidx.compose.ui.graphics.Color
import com.randallengineering.sleepasringconn.ui.theme.*

enum class GoalType(
    val id: String,
    val title: String,
    val description: String,
    val unit: String,
    val defaultTarget: Float,
    val minTarget: Float,
    val maxTarget: Float,
    val step: Float,
    val higherIsBetter: Boolean,
    val color: Color
) {
    SLEEP_DURATION(
        id = "sleep_duration",
        title = "Sleep Duration",
        description = "Total hours of sleep per night",
        unit = "hrs",
        defaultTarget = 8.0f,
        minTarget = 5.0f,
        maxTarget = 11.0f,
        step = 0.5f,
        higherIsBetter = true,
        color = SleepPurple
    ),
    DEEP_SLEEP_PCT(
        id = "deep_sleep_pct",
        title = "Deep Sleep Ratio",
        description = "Percentage of sleep in physical restoration deep stage",
        unit = "%",
        defaultTarget = 20.0f,
        minTarget = 10.0f,
        maxTarget = 35.0f,
        step = 1.0f,
        higherIsBetter = true,
        color = DeepSleepBlue
    ),
    RESTING_HR(
        id = "resting_hr",
        title = "Resting HR Floor",
        description = "Nocturnal baseline resting heart rate",
        unit = "BPM",
        defaultTarget = 55.0f,
        minTarget = 40.0f,
        maxTarget = 80.0f,
        step = 1.0f,
        higherIsBetter = false,
        color = HeartRateRed
    ),
    HRV_RECOVERY(
        id = "hrv_recovery",
        title = "HRV Recovery (RMSSD)",
        description = "Autonomic nervous system recovery index",
        unit = "ms",
        defaultTarget = 50.0f,
        minTarget = 20.0f,
        maxTarget = 120.0f,
        step = 5.0f,
        higherIsBetter = true,
        color = Color(0xFF9C27B0)
    ),
    DAILY_STEPS(
        id = "daily_steps",
        title = "Daily Active Steps",
        description = "Physical movement and daily activity volume",
        unit = "steps",
        defaultTarget = 10000.0f,
        minTarget = 3000.0f,
        maxTarget = 25000.0f,
        step = 500.0f,
        higherIsBetter = true,
        color = StepsGreen
    ),
    BEDTIME_CONSISTENCY(
        id = "bedtime_consistency",
        title = "Bedtime Consistency",
        description = "Target sleep onset time (within 30m window)",
        unit = "time",
        defaultTarget = 23.0f, // 11:00 PM
        minTarget = 20.0f, // 8:00 PM
        maxTarget = 26.0f, // 2:00 AM next day
        step = 0.5f,
        higherIsBetter = true,
        color = TempAmber
    ),
    SPO2_FLOOR(
        id = "spo2_floor",
        title = "SpO2 Oxygen Stability",
        description = "Nocturnal blood oxygen floor baseline",
        unit = "%",
        defaultTarget = 95.0f,
        minTarget = 90.0f,
        maxTarget = 99.0f,
        step = 1.0f,
        higherIsBetter = true,
        color = Spo2Blue
    );

    fun formatValue(value: Float?): String {
        if (value == null) return "--"
        return when (this) {
            SLEEP_DURATION -> "%.1fh".format(value)
            DEEP_SLEEP_PCT -> "%.0f%%".format(value)
            RESTING_HR -> "%.0f BPM".format(value)
            HRV_RECOVERY -> "%.0f ms".format(value)
            DAILY_STEPS -> "%,.0f".format(value)
            BEDTIME_CONSISTENCY -> {
                val hour = (value.toInt()) % 24
                val min = ((value - value.toInt()) * 60).toInt()
                val amPm = if (hour < 12 || hour == 24) "AM" else "PM"
                val displayHour = when {
                    hour == 0 || hour == 24 -> 12
                    hour > 12 -> hour - 12
                    else -> hour
                }
                "%d:%02d %s".format(displayHour, min, amPm)
            }
            SPO2_FLOOR -> "%.0f%%".format(value)
        }
    }
}

enum class GoalStatus {
    ACHIEVED,
    ON_TRACK,
    OFF_TRACK,
    NO_DATA
}

data class DailyGoalResult(
    val dayTimestamp: Long,
    val dayLabel: String,
    val actualValue: Float?,
    val isAchieved: Boolean
)

data class GoalProgress(
    val goalType: GoalType,
    val targetValue: Float,
    val todayValue: Float?,
    val weeklyAverage: Float?,
    val achievementRatePct: Int,
    val currentStreakDays: Int,
    val past7Days: List<DailyGoalResult>,
    val status: GoalStatus
)

enum class TipCategory(val label: String) {
    RECOVERY("Recovery & Autonomic"),
    SLEEP_HYGIENE("Sleep Architecture"),
    CARDIO("Cardiovascular"),
    CIRCADIAN("Circadian Rhythm"),
    ACTIVITY("Physical Activity")
}

enum class TipSeverity {
    CELEBRATION,
    INSIGHT,
    ADVICE,
    WARNING
}

data class HealthTip(
    val id: String,
    val title: String,
    val message: String,
    val category: TipCategory,
    val severity: TipSeverity,
    val metricSource: GoalType? = null
)

data class MilestoneBadge(
    val id: String,
    val title: String,
    val description: String,
    val progress: Float,
    val isUnlocked: Boolean,
    val levelDescription: String
)
