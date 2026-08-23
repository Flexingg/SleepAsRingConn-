package com.randallengineering.sleepasringconn.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import com.randallengineering.sleepasringconn.analytics.SleepSession
import com.randallengineering.sleepasringconn.analytics.SleepStage
import com.randallengineering.sleepasringconn.data.DeviceStatusEntity
import com.randallengineering.sleepasringconn.data.EpochEntity
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) {

    val healthConnectClient: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    val isAvailable: Boolean
        get() = healthConnectClient != null

    val requiredPermissions = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getWritePermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getWritePermission(BodyTemperatureRecord::class),
        HealthPermission.getReadPermission(BodyTemperatureRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(requiredPermissions)
    }

    suspend fun writeEpochs(epochs: List<EpochEntity>): Int {
        val client = healthConnectClient ?: return 0
        if (epochs.isEmpty()) return 0

        val hrRecords = mutableListOf<HeartRateRecord>()
        val hrvRecords = mutableListOf<HeartRateVariabilityRmssdRecord>()
        val spo2Records = mutableListOf<OxygenSaturationRecord>()
        val rrRecords = mutableListOf<RespiratoryRateRecord>()

        for (epoch in epochs) {
            val startTime = Instant.ofEpochMilli(epoch.timestampMillis)
            val endTime = Instant.ofEpochMilli(epoch.timestampMillis + 150_000L)

            // Heart Rate
            epoch.heartRate?.let { hr ->
                val sample = HeartRateRecord.Sample(
                    time = startTime,
                    beatsPerMinute = hr.toLong()
                )
                hrRecords.add(
                    HeartRateRecord(
                        startTime = startTime,
                        startZoneOffset = ZoneOffset.systemDefault().rules.getOffset(startTime),
                        endTime = endTime,
                        endZoneOffset = ZoneOffset.systemDefault().rules.getOffset(endTime),
                        samples = listOf(sample)
                    )
                )
            }

            // HRV RMSSD
            epoch.hrvRmssd?.let { hrv ->
                hrvRecords.add(
                    HeartRateVariabilityRmssdRecord(
                        time = startTime,
                        zoneOffset = ZoneOffset.systemDefault().rules.getOffset(startTime),
                        heartRateVariabilityMillis = hrv.toDouble()
                    )
                )
            }

            // SpO2
            epoch.spo2Percent?.let { spo2 ->
                spo2Records.add(
                    OxygenSaturationRecord(
                        time = startTime,
                        zoneOffset = ZoneOffset.systemDefault().rules.getOffset(startTime),
                        percentage = Percentage(spo2.toDouble())
                    )
                )
            }

            // Respiratory Rate
            epoch.respiratoryRate?.let { rr ->
                rrRecords.add(
                    RespiratoryRateRecord(
                        time = startTime,
                        zoneOffset = ZoneOffset.systemDefault().rules.getOffset(startTime),
                        rate = rr
                    )
                )
            }
        }

        var writtenCount = 0
        if (hrRecords.isNotEmpty()) {
            client.insertRecords(hrRecords)
            writtenCount += hrRecords.size
        }
        if (hrvRecords.isNotEmpty()) {
            client.insertRecords(hrvRecords)
            writtenCount += hrvRecords.size
        }
        if (spo2Records.isNotEmpty()) {
            client.insertRecords(spo2Records)
            writtenCount += spo2Records.size
        }
        if (rrRecords.isNotEmpty()) {
            client.insertRecords(rrRecords)
            writtenCount += rrRecords.size
        }

        return writtenCount
    }

    suspend fun writeSkinTemperatures(statusLogs: List<DeviceStatusEntity>): Int {
        val client = healthConnectClient ?: return 0
        val tempRecords = mutableListOf<BodyTemperatureRecord>()

        for (log in statusLogs) {
            val tempC = log.skinTemperatureC ?: continue
            val time = Instant.ofEpochMilli(log.timestampMillis)
            tempRecords.add(
                BodyTemperatureRecord(
                    time = time,
                    zoneOffset = ZoneOffset.systemDefault().rules.getOffset(time),
                    temperature = Temperature.celsius(tempC)
                )
            )
        }

        if (tempRecords.isNotEmpty()) {
            client.insertRecords(tempRecords)
        }
        return tempRecords.size
    }

    suspend fun writeSleepSession(session: SleepSession): Boolean {
        val client = healthConnectClient ?: return false

        val startInstant = Instant.ofEpochMilli(session.startTimeMillis)
        val endInstant = Instant.ofEpochMilli(session.endTimeMillis)

        val stages = session.epochs.map { epoch ->
            val stageStartTime = Instant.ofEpochMilli(epoch.timestampMillis)
            val stageEndTime = Instant.ofEpochMilli(epoch.timestampMillis + 150_000L)
            val stageType = when (epoch.stage) {
                SleepStage.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
                SleepStage.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
                SleepStage.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
                SleepStage.REM -> SleepSessionRecord.STAGE_TYPE_REM
            }
            SleepSessionRecord.Stage(
                startTime = stageStartTime,
                endTime = stageEndTime,
                stage = stageType
            )
        }

        val sleepRecord = SleepSessionRecord(
            startTime = startInstant,
            startZoneOffset = ZoneOffset.systemDefault().rules.getOffset(startInstant),
            endTime = endInstant,
            endZoneOffset = ZoneOffset.systemDefault().rules.getOffset(endInstant),
            title = "Sleep (RingConn Gen 2)",
            notes = "Recorded with SleepAsRingConn (Local First)",
            stages = stages
        )

        client.insertRecords(listOf(sleepRecord))
        return true
    }
}
