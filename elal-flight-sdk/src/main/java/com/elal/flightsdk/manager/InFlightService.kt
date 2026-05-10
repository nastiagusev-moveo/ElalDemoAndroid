package com.elal.flightsdk.manager

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

class InFlightService : Service() {

    companion object {
        private const val TAG = "InFlightService"
    }
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> LiveUpdateManager.pauseTicks()
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> LiveUpdateManager.resumeTicks()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LiveUpdateManager.isActive) {
            LiveUpdateManager.restoreFromPrefs(this)
        }
        val notification = LiveUpdateManager.buildCurrentNotification(this)
        if (notification != null) {
            startForeground(LiveUpdateManager.NOTIFICATION_ID, notification)
        } else {
            Log.e(TAG, "buildCurrentNotification returned null — showing GENERAL_ERROR and stopping")
            val errorNotification = LiveUpdateManager.buildGeneralErrorNotification(this)
            startForeground(LiveUpdateManager.NOTIFICATION_ID, errorNotification)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        screenReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
