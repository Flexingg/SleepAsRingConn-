package com.randallengineering.sleepasringconn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.randallengineering.sleepasringconn.MainActivity
import com.randallengineering.sleepasringconn.R
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import com.randallengineering.sleepasringconn.data.AppDatabase
import com.randallengineering.sleepasringconn.healthconnect.HealthConnectManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class RingSyncService : Service() {
    companion object {
        const val CHANNEL_ID = "ringconn_service_channel"
        const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, RingSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RingSyncService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var database: AppDatabase

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification("RingConn service active", "Monitoring ring status")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        healthConnectManager = HealthConnectManager(this)
        database = AppDatabase.getDatabase(this)

        // Observe device status and update notification
        serviceScope.launch {
            BleConnectionManager.latestDeviceStatus.collectLatest { status ->
                if (status != null) {
                    val tempStr = status.skinTemperature?.let { " · %.1f°C".format(it.celsius) } ?: ""
                    val chargingStr = if (status.isOnCharger) "⚡ Charging" else "${status.batteryPercent}% Battery"
                    val content = "$chargingStr$tempStr · ${status.quarterHourSteps} steps (15m)"
                    updateNotification(content)
                }
            }
        }

        // Background periodic history sync loop (every 30 minutes)
        serviceScope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L)
                if (BleConnectionManager.isConnected.value && !BleConnectionManager.isLiveMonitoring.value) {
                    BleConnectionManager.syncHistory()

                    // Sync unsynced records to Health Connect
                    try {
                        val unsynced = database.epochDao().getUnsyncedEpochs()
                        if (unsynced.isNotEmpty()) {
                            val count = healthConnectManager.writeEpochs(unsynced)
                            if (count > 0) {
                                database.epochDao().markSynced(unsynced.map { it.counter })
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RingConn Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps connection with RingConn Gen 2 and syncs health data"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification("RingConn Gen 2", content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
