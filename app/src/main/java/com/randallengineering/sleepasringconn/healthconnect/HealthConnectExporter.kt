package com.randallengineering.sleepasringconn.healthconnect

import android.content.Context
import com.randallengineering.sleepasringconn.data.AppDatabase
import com.randallengineering.sleepasringconn.data.SleepSessionManager

/**
 * The single Health Connect export routine, shared by the periodic worker, the ring
 * service loop and the "Sync now" button so they can't drift apart.
 *
 * Sleep is exported from the same 30-day window the Sleep screen analyses, so Health
 * Connect gets exactly the sessions the app shows. Sessions are only rebuilt from a slice
 * of new epochs in the old code, which produced dozens of short, overlapping, stage-less
 * fragments; here every export replaces this app's sleep records in the window.
 */
object HealthConnectExporter {

    const val SLEEP_WINDOW_MS = 30L * 24 * 60 * 60 * 1000L
    private const val KEY_SLEEP_FINGERPRINT = "key_sleep_export_fingerprint_v2"

    data class Result(val vitals: Int, val sleepSessions: Int, val tempAndSteps: Int) {
        val total get() = vitals + sleepSessions + tempAndSteps
    }

    suspend fun exportAll(context: Context, manager: HealthConnectManager = HealthConnectManager(context)): Result {
        val database = AppDatabase.getDatabase(context)

        // 1. Point-in-time vitals (HR / HRV / SpO2 / respiration). Upserts by clientRecordId.
        var vitals = 0
        for (round in 0 until 40) {
            val batch = database.epochDao().getUnsyncedEpochs()
            if (batch.isEmpty()) break
            vitals += manager.writeEpochs(batch)
            database.epochDao().markSynced(batch.map { it.counter })
        }

        // 2. Sleep sessions, computed over the full window (not just the new epochs).
        val sleepCount = exportSleep(context, manager)

        // 3. Skin temperature and 15-min step buckets.
        var tempAndSteps = 0
        val recentStatus = database.deviceStatusDao().getRecentStatusLogsList(50)
        if (recentStatus.isNotEmpty()) {
            tempAndSteps += manager.writeSkinTemperatures(recentStatus)
            tempAndSteps += manager.writeSteps(recentStatus)
        }

        return Result(vitals, sleepCount, tempAndSteps)
    }

    /** Returns the number of sleep sessions written (0 when nothing changed). */
    private suspend fun exportSleep(context: Context, manager: HealthConnectManager): Int {
        val database = AppDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val windowStart = now - SLEEP_WINDOW_MS

        val epochs = database.epochDao().getEpochsSince(windowStart)
        if (epochs.isEmpty()) return 0
        val latestData = epochs.maxOf { it.timestampMillis }

        val sessions = SleepSessionManager.getProcessedSessions(context, epochs)
            .filter { SleepExportPlanner.isSettled(it, latestData, now) }

        val prefs = HealthConnectSyncScheduler.getPreferences(context)
        val fingerprint = SleepExportPlanner.fingerprint(sessions)
        if (prefs.getString(KEY_SLEEP_FINGERPRINT, null) == fingerprint) return 0

        // Clear this app's own sleep records in the window (Health Connect only lets an app
        // delete its own data), then write the current set. This also removes the fragments
        // earlier versions left behind and reflects user edits / deletions.
        val clearFrom = minOf(windowStart, epochs.minOf { it.timestampMillis })
        manager.deleteOwnSleepSessions(clearFrom, now)
        var written = 0
        for (session in sessions) {
            try {
                manager.writeSleepSession(session)
                written++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (written == sessions.size) {
            prefs.edit().putString(KEY_SLEEP_FINGERPRINT, fingerprint).apply()
        }
        return written
    }
}
