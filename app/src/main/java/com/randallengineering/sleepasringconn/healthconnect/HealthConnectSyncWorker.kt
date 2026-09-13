package com.randallengineering.sleepasringconn.healthconnect

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.randallengineering.sleepasringconn.analytics.SleepStagingEngine
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import com.randallengineering.sleepasringconn.data.AppDatabase
import com.randallengineering.sleepasringconn.protocol.BulkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HealthConnectSyncWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val healthConnectManager = HealthConnectManager(context)
            if (!healthConnectManager.isAvailable) {
                HealthConnectSyncScheduler.recordSyncResult(context, "Health Connect unavailable", 0)
                return@withContext Result.failure()
            }

            if (!healthConnectManager.hasAllPermissions()) {
                HealthConnectSyncScheduler.recordSyncResult(context, "Permissions required", 0)
                return@withContext Result.retry()
            }

            val database = AppDatabase.getDatabase(context)

            // 1. If ring is currently connected, trigger a quick history sync
            if (BleConnectionManager.isConnected.value && !BleConnectionManager.isLiveMonitoring.value) {
                try {
                    BleConnectionManager.syncHistory()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 2. Fetch pending unsynced epochs
            val unsyncedEpochs = database.epochDao().getUnsyncedEpochs()
            var totalExported = 0

            if (unsyncedEpochs.isNotEmpty()) {
                // Write individual biometric series (HR, HRV, SpO2, Respiratory)
                val writtenCount = healthConnectManager.writeEpochs(unsyncedEpochs)
                totalExported += writtenCount

                // Extract & write sleep sessions if present
                val bulkRecords = unsyncedEpochs.mapNotNull { BulkRecord.parseRecord(it.rawBytes) }
                val sessions = SleepStagingEngine.extractAllSleepSessions(bulkRecords)
                for (session in sessions) {
                    try {
                        healthConnectManager.writeSleepSession(session)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Mark synced in database
                database.epochDao().markSynced(unsyncedEpochs.map { it.counter })
            }

            // 3. Sync recent skin temperatures
            val recentStatus = database.deviceStatusDao().getRecentStatusLogsList(50)
            if (recentStatus.isNotEmpty()) {
                val tempCount = healthConnectManager.writeSkinTemperatures(recentStatus)
                totalExported += tempCount
            }

            val statusMsg = if (totalExported > 0) {
                "Synced $totalExported records successfully"
            } else {
                "Up to date (0 pending)"
            }

            HealthConnectSyncScheduler.recordSyncResult(context, statusMsg, totalExported)
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            HealthConnectSyncScheduler.recordSyncResult(context, "Sync error: ${e.message}", 0)
            Result.retry()
        }
    }
}
