package com.randallengineering.sleepasringconn.healthconnect

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
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

            // 1. If ring is currently connected, trigger a quick history sync
            if (BleConnectionManager.isConnected.value && !BleConnectionManager.isLiveMonitoring.value) {
                try {
                    BleConnectionManager.syncHistory()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val totalExported = HealthConnectExporter.exportAll(context, healthConnectManager).total

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
