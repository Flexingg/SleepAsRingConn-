package com.randallengineering.sleepasringconn

import android.app.Application
import com.randallengineering.sleepasringconn.ble.BleConnectionManager

class SleepAsRingConnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BleConnectionManager.init(this)
        if (com.randallengineering.sleepasringconn.healthconnect.HealthConnectSyncScheduler.isAutoSyncEnabled(this)) {
            com.randallengineering.sleepasringconn.healthconnect.HealthConnectSyncScheduler.schedulePeriodicSync(this)
        }
    }
}
