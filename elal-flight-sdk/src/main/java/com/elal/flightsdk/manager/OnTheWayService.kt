package com.elal.flightsdk.manager

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder

class OnTheWayService : Service() {
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
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Detach before the service fully dies so Android doesn't auto-remove notification 7001.
        // postNotification() may have already posted the next state (e.g. CONNECTION_ERROR mid-flight)
        // and we don't want the service teardown to wipe it.
        androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_DETACH)
        screenReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
