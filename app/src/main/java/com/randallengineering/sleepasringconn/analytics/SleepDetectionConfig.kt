package com.randallengineering.sleepasringconn.analytics

/** How sleep sessions are found. */
enum class SleepDetectionMode(val label: String, val description: String) {
    AUTOMATIC(
        "Automatic",
        "Detects overnight sleep and daytime naps from your ring data."
    ),
    OVERNIGHT_ONLY(
        "Overnight only",
        "Detects sleep at night only. Add daytime naps with Start/Stop or Add Session."
    ),
    MANUAL(
        "Manual",
        "Nothing is detected. Sessions come only from Start/Stop, Add Session, or Sleep as Android tracking."
    )
}

/** How much evidence an automatically detected session needs before it counts. */
enum class SleepDetectionSensitivity(val label: String, val description: String) {
    RELAXED(
        "Relaxed",
        "Catches the most naps but may flag quiet rest as sleep."
    ),
    BALANCED(
        "Balanced",
        "Ignores time when the ring wasn't worn. Daytime naps need a real heart-rate drop."
    ),
    STRICT(
        "Strict",
        "Daytime naps need at least 30 min and a large heart-rate drop. Ignores a ring left lying perfectly still."
    )
}

/**
 * Tunables for [SleepStagingEngine.extractAllSleepSessions]. Pure data, so the engine stays
 * Android-free and unit-testable.
 */
data class SleepDetectionConfig(
    /** Share of a candidate's epochs that need a real heart-rate reading (ring on the finger). */
    val minWornFraction: Double,
    /** This many epochs in a row without a heart-rate reading ends a session (ring taken off). */
    val maxNoHrRunEpochs: Int,
    /** Least sleep (minutes) a daytime session needs. Overnight sessions keep the 12-minute floor. */
    val daytimeMinSleepMinutes: Int,
    /**
     * A daytime session's median heart rate may be at most this far above the user's sleeping
     * heart-rate floor. Quiet rest while awake stays well above it; null disables the check.
     */
    val daytimeMaxHrAboveFloor: Int?,
    /** Reject sessions where the ring recorded no movement at all (lying on a table or charger). */
    val rejectMotionless: Boolean,
    /** Drop every session whose midpoint falls in the daytime. */
    val overnightOnly: Boolean = false
) {
    companion object {
        /** Daytime is a session midpoint in [DAY_START_HOUR, DAY_END_HOUR). */
        const val DAY_START_HOUR = 9
        const val DAY_END_HOUR = 20

        val RELAXED = SleepDetectionConfig(
            minWornFraction = 0.0,
            maxNoHrRunEpochs = Int.MAX_VALUE,
            daytimeMinSleepMinutes = 12,
            daytimeMaxHrAboveFloor = null,
            rejectMotionless = false
        )
        val BALANCED = SleepDetectionConfig(
            minWornFraction = 0.6,
            maxNoHrRunEpochs = 12, // 30 min
            daytimeMinSleepMinutes = 20,
            daytimeMaxHrAboveFloor = 10,
            rejectMotionless = false
        )
        val STRICT = SleepDetectionConfig(
            minWornFraction = 0.75,
            maxNoHrRunEpochs = 8, // 20 min
            daytimeMinSleepMinutes = 30,
            daytimeMaxHrAboveFloor = 6,
            rejectMotionless = true
        )

        val DEFAULT = BALANCED

        fun of(sensitivity: SleepDetectionSensitivity, mode: SleepDetectionMode): SleepDetectionConfig {
            val base = when (sensitivity) {
                SleepDetectionSensitivity.RELAXED -> RELAXED
                SleepDetectionSensitivity.BALANCED -> BALANCED
                SleepDetectionSensitivity.STRICT -> STRICT
            }
            return base.copy(overnightOnly = mode == SleepDetectionMode.OVERNIGHT_ONLY)
        }
    }
}
