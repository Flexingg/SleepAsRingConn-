package com.randallengineering.sleepasringconn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "epoch_records")
data class EpochEntity(
    @PrimaryKey val counter: Long,
    val timestampMillis: Long,
    val channel: Int,
    val layout: String,
    val heartRate: Int?,
    val hrvRmssd: Int?,
    val confidence: Int,
    val respiratoryRate: Double?,
    val spo2Percent: Int?,
    val rawBytes: ByteArray,
    val isSyncedToHealthConnect: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EpochEntity
        return counter == other.counter
    }

    override fun hashCode(): Int = counter.hashCode()
}

@Entity(tableName = "device_status_logs")
data class DeviceStatusEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val batteryPercent: Int,
    val stateByte: Int,
    val isOnCharger: Boolean,
    val isSportMode: Boolean,
    val quarterHourSteps: Int,
    val skinTemperatureC: Double?,
    val batteryVoltageMv: Int?,
    val caseBatteryPercent: Int?,
    val caseIsCharging: Boolean?
)

@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey val startTimeMillis: Long,
    val endTimeMillis: Long,
    val totalDurationMinutes: Int,
    val awakeMinutes: Int,
    val lightMinutes: Int,
    val deepMinutes: Int,
    val remMinutes: Int,
    val avgHeartRate: Int?,
    val avgHrvRmssd: Int?,
    val avgSpo2: Int?,
    val avgRespiratoryRate: Double?,
    val sleepScore: Int,
    val isSyncedToHealthConnect: Boolean = false
)
