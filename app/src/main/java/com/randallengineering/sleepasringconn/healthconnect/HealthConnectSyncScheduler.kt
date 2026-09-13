package com.randallengineering.sleepasringconn.healthconnect

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import java.util.concurrent.TimeUnit

object HealthConnectSyncScheduler {

    private const val PREFS_NAME = "health_connect_sync_prefs"
    private const val KEY_AUTO_SYNC_ENABLED = "key_auto_sync_enabled"
    private const val KEY_SYNC_INTERVAL_MINUTES = "key_sync_interval_minutes"
    private const val KEY_LAST_SYNC_MILLIS = "key_last_sync_millis"
    private const val KEY_LAST_SYNC_STATUS = "key_last_sync_status"
    private const val KEY_LAST_SYNCED_COUNT = "key_last_synced_count"

    const val WORK_TAG_PERIODIC = "health_connect_periodic_sync"
    const val WORK_TAG_ONETIME = "health_connect_onetime_sync"

    fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAutoSyncEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTO_SYNC_ENABLED, true)
    }

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_AUTO_SYNC_ENABLED, enabled).apply()
        if (enabled) {
            schedulePeriodicSync(context)
        } else {
            cancelPeriodicSync(context)
        }
    }

    fun getSyncIntervalMinutes(context: Context): Int {
        return getPreferences(context).getInt(KEY_SYNC_INTERVAL_MINUTES, 30)
    }

    fun setSyncIntervalMinutes(context: Context, intervalMinutes: Int) {
        getPreferences(context).edit().putInt(KEY_SYNC_INTERVAL_MINUTES, intervalMinutes).apply()
        if (isAutoSyncEnabled(context)) {
            schedulePeriodicSync(context)
        }
    }

    fun getLastSyncMillis(context: Context): Long {
        return getPreferences(context).getLong(KEY_LAST_SYNC_MILLIS, 0L)
    }

    fun getLastSyncStatus(context: Context): String {
        return getPreferences(context).getString(KEY_LAST_SYNC_STATUS, "Never synced") ?: "Never synced"
    }

    fun getLastSyncedCount(context: Context): Int {
        return getPreferences(context).getInt(KEY_LAST_SYNCED_COUNT, 0)
    }

    fun recordSyncResult(context: Context, status: String, count: Int) {
        getPreferences(context).edit()
            .putLong(KEY_LAST_SYNC_MILLIS, System.currentTimeMillis())
            .putString(KEY_LAST_SYNC_STATUS, status)
            .putInt(KEY_LAST_SYNCED_COUNT, count)
            .apply()
    }

    fun schedulePeriodicSync(context: Context) {
        val intervalMinutes = getSyncIntervalMinutes(context).coerceAtLeast(15) // Android WorkManager min is 15 min

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<HealthConnectSyncWorker>(
            intervalMinutes.toLong(), TimeUnit.MINUTES,
            5L, TimeUnit.MINUTES // 5-minute flex window
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG_PERIODIC)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    fun cancelPeriodicSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG_PERIODIC)
    }

    fun triggerImmediateSync(context: Context) {
        val oneTimeRequest = OneTimeWorkRequestBuilder<HealthConnectSyncWorker>()
            .addTag(WORK_TAG_ONETIME)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_TAG_ONETIME,
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest
        )
    }
}
