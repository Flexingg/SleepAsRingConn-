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
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import com.randallengineering.sleepasringconn.ble.HrBroadcastManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class HrBroadcastService : Service() {
    companion object {
        const val CHANNEL_ID = "hr_broadcast_channel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP_BROADCAST = "com.randallengineering.sleepasringconn.STOP_BROADCAST"

        fun start(context: Context) {
            val intent = Intent(context, HrBroadcastService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, HrBroadcastService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification("Peloton HR Broadcast", "Starting broadcast...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 1. Start BLE GATT Server & Advertising
        HrBroadcastManager.startBroadcast(this)

        // 2. Activate Live Real-Time PPG on the Ring
        serviceScope.launch {
            if (BleConnectionManager.isConnected.value && !BleConnectionManager.isLiveMonitoring.value) {
                BleConnectionManager.startLiveMonitoring(hrMode = true)
            }
        }

        // 3. Listen for live HR readings from the ring and forward to Peloton via GATT Server
        serviceScope.launch {
            BleConnectionManager.liveHeartRate.collectLatest { bpm ->
                if (bpm != null && bpm > 0) {
                    HrBroadcastManager.broadcastHeartRate(bpm)
                    val devName = HrBroadcastManager.connectedDeviceName.value
                    val statusStr = if (devName != null) "Streaming to $devName: $bpm BPM" else "Advertising (Ready for Peloton): $bpm BPM"
                    updateNotification(statusStr)
                }
            }
        }

        // 4. Track connected clients
        serviceScope.launch {
            HrBroadcastManager.statusMessage.collectLatest { status ->
                val bpm = HrBroadcastManager.lastBroadcastBpm.value
                val content = if (bpm != null) "$status · $bpm BPM" else status
                updateNotification(content)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_BROADCAST) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        HrBroadcastManager.stopBroadcast()
        if (BleConnectionManager.isLiveMonitoring.value) {
            BleConnectionManager.stopLiveMonitoring()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Peloton Heart Rate Broadcast",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Broadcasts real-time Heart Rate to Peloton, Zwift, and fitness devices"
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

        val stopIntent = Intent(this, HrBroadcastService::class.java).apply {
            action = ACTION_STOP_BROADCAST
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Broadcast", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification("Peloton HR Broadcast", content)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
