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
import kotlinx.coroutines.launch

class SleepAsAndroidReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SleepAsAndroidReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received broadcast intent from Sleep as Android: $action")

        when (action) {
            SleepAsAndroidBridge.ACTION_CHECK_CONNECTED -> {
                val isConnected = BleConnectionManager.isConnected.value
                if (isConnected) {
                    SleepAsAndroidBridge.sendConfirmConnected(context)
                }
            }

            SleepAsAndroidBridge.ACTION_START_TRACKING -> {
                val doHr = intent.getBooleanExtra(SleepAsAndroidBridge.EXTRA_DO_HR_MONITORING, true)
                val doOxi = intent.getBooleanExtra(SleepAsAndroidBridge.EXTRA_DO_OXIMETER_MONITORING, true)
                val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
                SleepAsAndroidBridge.handleStartTracking(context.applicationContext, doHr, doOxi, scope)

                // Start Foreground Service if not already running
                RingSyncService.startService(context)

                // Trigger live monitoring mode on the ring
                scope.launch {
                    BleConnectionManager.startLiveMonitoring(hrMode = true)
                }
            }

            SleepAsAndroidBridge.ACTION_STOP_TRACKING -> {
                SleepAsAndroidBridge.handleStopTracking()
                CoroutineScope(Dispatchers.IO).launch {
                    BleConnectionManager.stopLiveMonitoring()
                }
            }

            SleepAsAndroidBridge.ACTION_SET_BATCH_SIZE -> {
                val size = intent.getLongExtra(SleepAsAndroidBridge.EXTRA_SIZE, 12L)
                SleepAsAndroidBridge.handleSetBatchSize(size)
            }

            SleepAsAndroidBridge.ACTION_HINT -> {
                // Flash the ring's LED briefly for anti-snoring / lucid dreaming hint
                val repeat = intent.getIntExtra(SleepAsAndroidBridge.EXTRA_REPEAT, 1)
                CoroutineScope(Dispatchers.IO).launch {
                    for (i in 0 until repeat) {
                        BleConnectionManager.sendCommand(RingProtocol.CMD_FIND_RING_LED_ON)
                        kotlinx.coroutines.delay(1000)
                        BleConnectionManager.sendCommand(RingProtocol.CMD_FIND_RING_LED_OFF)
                        if (i < repeat - 1) kotlinx.coroutines.delay(500)
                    }
                }
            }

            SleepAsAndroidBridge.ACTION_START_ALARM,
            SleepAsAndroidBridge.ACTION_STOP_ALARM,
            SleepAsAndroidBridge.ACTION_SET_PAUSE,
            SleepAsAndroidBridge.ACTION_SET_SUSPENDED -> {
                Log.d(TAG, "Received SaA event: $action (extras: ${intent.extras})")
            }
        }
    }
}
