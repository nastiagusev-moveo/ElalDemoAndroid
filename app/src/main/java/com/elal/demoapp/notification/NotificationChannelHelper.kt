package com.elal.demoapp.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannelHelper {

    private const val CHANNEL_ID = "elal_journey"
    private const val CHANNEL_NAME = "Journey Updates"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Live updates for your El Al journey"
            }
            nm.createNotificationChannel(channel)
        }
    }
}
