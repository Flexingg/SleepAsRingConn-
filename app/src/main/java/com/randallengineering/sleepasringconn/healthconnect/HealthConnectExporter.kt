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
    private const val KEY_SLEEP_FINGERPRINT = "key_sleep_export_fingerprint_v3"

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

    private const val LEAD_IN_MS = 12L * 60 * 60 * 1000L
    private const val KEY_HISTORY_DONE = "key_sleep_history_export_v4"

    /**
     * Returns the number of sleep sessions written (0 when nothing changed).
     *
     * The last 30 days are recomputed every run and rewritten when they change. Older history
     * is recomputed once (per export version, or on [rebuildSleepHistory]) in 30-day chunks, so
     * fragments that earlier versions wrote on older nights are replaced too.
     */
    private suspend fun exportSleep(context: Context, manager: HealthConnectManager): Int {
        val database = AppDatabase.getDatabase(context)
        val prefs = HealthConnectSyncScheduler.getPreferences(context)
        val now = System.currentTimeMillis()
        val recentStart = now - SLEEP_WINDOW_MS
        var written = 0

        // Recent window. Epochs from 12 h earlier are included so a night that started just
        // before the window edge isn't cut; only sessions starting inside the window are kept.
        val epochs = database.epochDao().getEpochsSince(recentStart - LEAD_IN_MS)
        if (epochs.isNotEmpty()) {
            val latestData = epochs.maxOf { it.timestampMillis }
            val sessions = SleepSessionManager.getProcessedSessions(context, epochs)
                .filter { it.startTimeMillis >= recentStart }
                .filter { SleepExportPlanner.isSettled(it, latestData, now) }
            val fingerprint = SleepExportPlanner.fingerprint(sessions)
            if (prefs.getString(KEY_SLEEP_FINGERPRINT, null) != fingerprint) {
                val ok = replaceRange(manager, recentStart, now, sessions)
                written += ok
                if (ok == sessions.size) prefs.edit().putString(KEY_SLEEP_FINGERPRINT, fingerprint).apply()
            }
        }

        // Older history, once.
        if (!prefs.getBoolean(KEY_HISTORY_DONE, false)) {
            val earliest = database.epochDao().getEarliestTimestamp()
            var allOk = true
            var chunkEnd = recentStart
            while (earliest != null && chunkEnd > earliest) {
                val chunkStart = maxOf(chunkEnd - SLEEP_WINDOW_MS, earliest)
                val chunkEpochs = database.epochDao().getEpochsBetween(chunkStart - LEAD_IN_MS, chunkEnd + LEAD_IN_MS)
                val sessions = SleepSessionManager.getProcessedSessions(context, chunkEpochs, persist = false)
                    .filter { it.startTimeMillis in chunkStart until chunkEnd }
                val ok = replaceRange(manager, chunkStart, chunkEnd, sessions)
                written += ok
                if (ok != sessions.size) allOk = false
                chunkEnd = chunkStart
            }
            if (allOk) prefs.edit().putBoolean(KEY_HISTORY_DONE, true).apply()
        }
        return written
    }

    /**
     * Deletes this app's own sleep records in [from, to) (Health Connect never lets an app
     * delete other apps' data) and writes [sessions]. Returns how many were written.
     */
    private suspend fun replaceRange(
        manager: HealthConnectManager,
        from: Long,
        to: Long,
        sessions: List<com.randallengineering.sleepasringconn.analytics.SleepSession>
    ): Int {
        manager.deleteOwnSleepSessions(from, to)
        var written = 0
        for (session in sessions) {
            try {
                manager.writeSleepSession(session)
                written++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return written
    }

    /**
     * Forces the next export to rewrite this app's sleep records, so a deleted, added or edited
     * session (or a detection-setting change) reaches Health Connect. The last 30 days are
     * rewritten every time the sessions change; when [affectedStartMillis] is older than that,
     * or null (every session may have changed), the older history is rewritten once too.
     */
    fun invalidateSleep(context: Context, affectedStartMillis: Long?) {
        val prefs = HealthConnectSyncScheduler.getPreferences(context)
        val editor = prefs.edit().remove(KEY_SLEEP_FINGERPRINT)
        // One day of slack: the history pass covers sessions *starting* before the recent window
        val recentStart = System.currentTimeMillis() - SLEEP_WINDOW_MS + 24L * 60 * 60 * 1000L
        if (affectedStartMillis == null || affectedStartMillis < recentStart) {
            editor.remove(KEY_HISTORY_DONE)
        }
        editor.apply()
    }

    /**
     * Clears every sleep record this app has written to Health Connect (including ones from
     * before the oldest ring data still stored here, which can't be recomputed) and exports
     * the whole history again.
     */
    suspend fun rebuildSleepHistory(context: Context, manager: HealthConnectManager = HealthConnectManager(context)): Int {
        val prefs = HealthConnectSyncScheduler.getPreferences(context)
        prefs.edit().remove(KEY_SLEEP_FINGERPRINT).remove(KEY_HISTORY_DONE).apply()
        val earliest = AppDatabase.getDatabase(context).epochDao().getEarliestTimestamp()
            ?: System.currentTimeMillis()
        manager.deleteOwnSleepSessions(0L, earliest)
        return exportSleep(context, manager)
    }
}
