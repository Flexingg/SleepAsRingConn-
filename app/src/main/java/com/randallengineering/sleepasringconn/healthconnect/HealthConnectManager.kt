package com.randallengineering.sleepasringconn.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import com.randallengineering.sleepasringconn.analytics.SleepSession
import com.randallengineering.sleepasringconn.analytics.SleepStage
import com.randallengineering.sleepasringconn.data.DeviceStatusEntity
import com.randallengineering.sleepasringconn.data.EpochEntity
import com.randallengineering.sleepasringconn.data.SleepSessionEntity
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
            val offset = ZoneOffset.systemDefault().rules.getOffset(startTime)
            val endOffset = ZoneOffset.systemDefault().rules.getOffset(endTime)

            // Heart Rate
            epoch.heartRate?.let { hr ->
                val sample = HeartRateRecord.Sample(
                    time = startTime,
                    beatsPerMinute = hr.toLong()
                )
                hrRecords.add(
                    HeartRateRecord(
                        startTime = startTime,
                        startZoneOffset = offset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        samples = listOf(sample),
                        metadata = Metadata(clientRecordId = "ringconn_hr_${epoch.timestampMillis}")
                    )
                )
            }

            // HRV RMSSD
            epoch.hrvRmssd?.let { hrv ->
                hrvRecords.add(
                    HeartRateVariabilityRmssdRecord(
                        time = startTime,
                        zoneOffset = offset,
                        heartRateVariabilityMillis = hrv.toDouble(),
                        metadata = Metadata(clientRecordId = "ringconn_hrv_${epoch.timestampMillis}")
                    )
                )
            }

            // SpO2
            epoch.spo2Percent?.let { spo2 ->
                spo2Records.add(
                    OxygenSaturationRecord(
                        time = startTime,
                        zoneOffset = offset,
                        percentage = Percentage(spo2.toDouble()),
                        metadata = Metadata(clientRecordId = "ringconn_spo2_${epoch.timestampMillis}")
                    )
                )
            }

            // Respiratory Rate
            epoch.respiratoryRate?.let { rr ->
                rrRecords.add(
                    RespiratoryRateRecord(
                        time = startTime,
                        zoneOffset = offset,
                        rate = rr,
                        metadata = Metadata(clientRecordId = "ringconn_rr_${epoch.timestampMillis}")
                    )
                )
            }
        }

        var writtenCount = 0
        if (hrRecords.isNotEmpty()) {
            hrRecords.chunked(250).forEach { chunk ->
                client.insertRecords(chunk)
                writtenCount += chunk.size
            }
        }
        if (hrvRecords.isNotEmpty()) {
            hrvRecords.chunked(250).forEach { chunk ->
                client.insertRecords(chunk)
                writtenCount += chunk.size
            }
        }
        if (spo2Records.isNotEmpty()) {
            spo2Records.chunked(250).forEach { chunk ->
                client.insertRecords(chunk)
                writtenCount += chunk.size
            }
        }
        if (rrRecords.isNotEmpty()) {
            rrRecords.chunked(250).forEach { chunk ->
                client.insertRecords(chunk)
                writtenCount += chunk.size
            }
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
                    temperature = Temperature.celsius(tempC),
                    metadata = Metadata(clientRecordId = "ringconn_temp_${log.timestampMillis}")
                )
            )
        }

        if (tempRecords.isNotEmpty()) {
            tempRecords.chunked(250).forEach { chunk ->
                client.insertRecords(chunk)
            }
        }
        return tempRecords.size
    }

    suspend fun writeSteps(statusLogs: List<DeviceStatusEntity>): Int {
        val client = healthConnectClient ?: return 0
        if (statusLogs.isEmpty()) return 0

        // Re-read from the start of the earliest log's local day so the fold has a proper
        // baseline (the caller passes only a recent slice) and per-bucket sums are stable
        // across runs (deterministic clientRecordId => upsert, no duplicates).
        val earliest = statusLogs.minOf { it.timestampMillis }
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = earliest
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val fullLogs = try {
            com.randallengineering.sleepasringconn.data.AppDatabase.getDatabase(context)
                .deviceStatusDao().getStatusLogsSince(cal.timeInMillis)
        } catch (e: Exception) {
            statusLogs.sortedBy { it.timestampMillis }
        }

        // Fold the per-bucket running count into deltas; credit each delta to the 15-min
        // wall-clock bucket its frame belongs to.
        val bucketMs = 15 * 60 * 1000L
        val bucketTotals = com.randallengineering.sleepasringconn.protocol.StepAccumulator
            .foldLogs(fullLogs)
            .filter { it.timestampMillis >= earliest - bucketMs }
            .groupBy { it.timestampMillis / bucketMs }
            .mapValues { (_, credits) -> credits.sumOf { it.delta } }

        val stepRecords = mutableListOf<StepsRecord>()
        for ((bucketIndex, total) in bucketTotals) {
            if (total > 0) {
                val bucketStartMillis = bucketIndex * bucketMs
                val startTime = Instant.ofEpochMilli(bucketStartMillis)
                val endTime = Instant.ofEpochMilli(bucketStartMillis + bucketMs)
                stepRecords.add(
                    StepsRecord(
                        startTime = startTime,
                        startZoneOffset = ZoneOffset.systemDefault().rules.getOffset(startTime),
                        endTime = endTime,
                        endZoneOffset = ZoneOffset.systemDefault().rules.getOffset(endTime),
                        count = total.toLong(),
                        metadata = Metadata(clientRecordId = "ringconn_steps_${bucketStartMillis}")
                    )
                )
            }
        }

        if (stepRecords.isNotEmpty()) {
            stepRecords.chunked(250).forEach { chunk ->
                client.insertRecords(chunk)
            }
        }
        return stepRecords.size
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
            title = if (session.isNap) "Nap (RingConn Gen 2)" else session.sessionLabel,
            notes = "Recorded with SleepAsRingConn (Local First)",
            stages = stages,
            metadata = Metadata(clientRecordId = "ringconn_sleep_${session.startTimeMillis}")
        )

        client.insertRecords(listOf(sleepRecord))
        return true
    }

    suspend fun writeSleepSessionEntity(entity: SleepSessionEntity): Boolean {
        val client = healthConnectClient ?: return false

        val startInstant = Instant.ofEpochMilli(entity.startTimeMillis)
        val endInstant = Instant.ofEpochMilli(entity.endTimeMillis)

        val sleepRecord = SleepSessionRecord(
            startTime = startInstant,
            startZoneOffset = ZoneOffset.systemDefault().rules.getOffset(startInstant),
            endTime = endInstant,
            endZoneOffset = ZoneOffset.systemDefault().rules.getOffset(endInstant),
            title = if (entity.isNap) "Nap (RingConn Gen 2)" else entity.sessionLabel,
            notes = "Recorded with SleepAsRingConn (Local First)",
            metadata = Metadata(clientRecordId = "ringconn_sleep_${entity.startTimeMillis}")
        )

        client.insertRecords(listOf(sleepRecord))
        return true
    }
}
