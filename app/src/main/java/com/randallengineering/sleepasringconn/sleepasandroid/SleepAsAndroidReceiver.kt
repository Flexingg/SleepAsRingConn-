package com.randallengineering.sleepasringconn.sleepasandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.randallengineering.sleepasringconn.ble.BleConnectionManager
import com.randallengineering.sleepasringconn.protocol.RingProtocol
import com.randallengineering.sleepasringconn.service.RingSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SleepAsAndroidReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SleepAsAndroidReceiver"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        Log.i(TAG, "SaA -> $action")

        when (action) {
            SleepAsAndroidBridge.ACTION_CHECK_CONNECTED -> {
                // SaA repeats this every 5 s for up to 2 min, so a ring that is reconnecting now
                // gets confirmed on a later check instead of SaA falling back to phone sensors.
                if (BleConnectionManager.isReady.value) {
                    SleepAsAndroidBridge.sendConfirmConnected(app)
                } else {
                    startServiceSafely(app)
                    BleConnectionManager.ensureConnected()
                }
            }

            SleepAsAndroidBridge.ACTION_START_TRACKING -> {
                val doHr = intent.getBooleanExtra(SleepAsAndroidBridge.EXTRA_DO_HR_MONITORING, false)
                val doOxi = intent.getBooleanExtra(SleepAsAndroidBridge.EXTRA_DO_OXIMETER_MONITORING, false)
                startServiceSafely(app)
                BleConnectionManager.ensureConnected()
                SleepAsAndroidBridge.handleStartTracking(app, doHr, doOxi)
            }

            SleepAsAndroidBridge.ACTION_STOP_TRACKING -> SleepAsAndroidBridge.handleStopTracking(app)

            SleepAsAndroidBridge.ACTION_SET_BATCH_SIZE -> {
                val size = intent.getLongExtra(SleepAsAndroidBridge.EXTRA_SIZE, -1L)
                    .takeIf { it > 0 } ?: intent.getIntExtra(SleepAsAndroidBridge.EXTRA_SIZE, 12).toLong()
                SleepAsAndroidBridge.handleSetBatchSize(size)
            }

            SleepAsAndroidBridge.ACTION_SET_SUSPENDED -> {
                SleepAsAndroidBridge.handleSuspended(intent.getBooleanExtra(SleepAsAndroidBridge.EXTRA_SUSPENDED, false))
            }

            SleepAsAndroidBridge.ACTION_SET_PAUSE -> {
                val until = intent.getLongExtra(SleepAsAndroidBridge.EXTRA_TIMESTAMP, 0L)
                SleepAsAndroidBridge.handleSuspended(until > System.currentTimeMillis())
            }

            SleepAsAndroidBridge.ACTION_HINT -> {
                // Gen 2 has no motor: flash the locator LED for anti-snore / lucid-dream cues.
                val repeat = intent.getIntExtra(SleepAsAndroidBridge.EXTRA_REPEAT, 1).coerceIn(1, 10)
                val pending = goAsync()
                scope.launch {
                    try {
                        repeat(repeat) { i ->
                            BleConnectionManager.sendCommand(RingProtocol.CMD_FIND_RING_LED_ON)
                            delay(1000)
                            BleConnectionManager.sendCommand(RingProtocol.CMD_FIND_RING_LED_OFF)
                            if (i < repeat - 1) delay(500)
                        }
                    } finally {
                        BleConnectionManager.sendCommand(RingProtocol.CMD_FIND_RING_LED_OFF)
                        pending.finish()
                    }
                }
            }

            else -> Log.d(TAG, "Unhandled SaA event $action extras=${intent.extras?.keySet()}")
        }
    }

    /** Android 12+ can refuse a foreground-service start from the background; never crash on it. */
    private fun startServiceSafely(context: Context) {
        try {
            RingSyncService.startService(context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start RingSyncService from background: ${e.message}")
        }
    }
}
