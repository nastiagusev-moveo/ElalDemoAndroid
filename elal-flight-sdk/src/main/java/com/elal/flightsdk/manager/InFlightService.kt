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
            LiveUpdateManager.restoreFromPrefs(this, startServiceIfActive = false)
        }
        val notification = LiveUpdateManager.buildCurrentNotification(this)
        if (notification != null) {
            if (!startForegroundSafely(notification)) return START_NOT_STICKY
        } else {
            Log.e(TAG, "buildCurrentNotification returned null — showing GENERAL_ERROR and stopping")
            val errorNotification = LiveUpdateManager.buildGeneralErrorNotification(this)
            if (!startForegroundSafely(errorNotification)) return START_NOT_STICKY
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        return START_STICKY
    }

    /**
     * Android 12+ checks background-start eligibility a second time inside Service.startForeground()
     * (mAllowStartForeground), so the try-catch around startForegroundService() in the caller does
     * not cover this path. It is hit in particular on START_STICKY restarts after process death
     * (null intent), where the app is in the background with no exemption. On failure we stop the
     * service and return START_NOT_STICKY so the system doesn't restart it into the same crash.
     * The Live Update notification is posted separately via NotificationManager and stays visible;
     * only the process-keep-alive service is forgone until the next eligible start.
     */
    private fun startForegroundSafely(notification: android.app.Notification): Boolean {
        return try {
            startForeground(LiveUpdateManager.NOTIFICATION_ID, notification)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground denied (background start restriction) — stopping service: ${e.message}")
            stopSelf()
            false
        }
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        screenReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
