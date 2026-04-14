package com.elal.flightsdk.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.elal.flightsdk.R
import com.elal.flightsdk.model.JourneyAttributes
import com.elal.flightsdk.model.JourneyContentState
import com.elal.flightsdk.model.JourneyStatus

/**
 * Manages the lifecycle of Live Update notifications.
 * On Android 16+ (API 36) uses Notification.ProgressStyle for the native progress UI.
 * On older devices shows a plain notification with title and content text only.
 * Mirrors the iOS LiveActivityManager pattern: start → update → end.
 */
object LiveUpdateManager {

    private const val NOTIFICATION_ID = 7001
    private const val CHANNEL_ID = "elal_journey"
    private const val PROGRESS_MAX = 1000

    private var currentAttributes: JourneyAttributes? = null
    val isActive: Boolean get() = currentAttributes != null

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Journey Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Live updates for your El Al journey"
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun startJourney(context: Context, attributes: JourneyAttributes, initialState: JourneyContentState) {
        if (isActive) throw LiveUpdateError.ActivityAlreadyRunning()
        ensureChannel(context)
        currentAttributes = attributes
        postNotification(context, attributes, initialState)
    }

    fun updateJourney(context: Context, state: JourneyContentState) {
        val attrs = currentAttributes ?: throw LiveUpdateError.NoActiveActivity()
        postNotification(context, attrs, state)
    }

    fun endJourney(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        currentAttributes = null
    }

    private fun postNotification(
        context: Context,
        attributes: JourneyAttributes,
        state: JourneyContentState
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val contentText = buildContentText(state)
        val statusBarText = buildStatusBarText(attributes, state)

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(statusBarText)
                .setContentText(contentText)
                .setStyle(buildProgressStyle(attributes, state))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .build()
        } else {
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(statusBarText)
                .setContentText(contentText)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .build()
        }

        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 4 segments, no points.
     *
     * Step 1 – CHECK_IN:                              nothing green
     * Step 2 – BOARDING_SOON / BOARDING / LAST_CALL:  seg0 green
     * Step 3 – TAKING_OFF:                            seg0 + seg1 green
     * Step 4 – ON_THE_WAY:                            seg0 + seg1 + seg2 green
     * Step 5 – ARRIVAL:                               all 4 segments green
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildProgressStyle(
        attributes: JourneyAttributes,
        state: JourneyContentState
    ): Notification.ProgressStyle {
        val greenColor = 0xFF4CAF50.toInt()
        val greyColor = 0xFF9E9E9E.toInt()

        val numSegments = 4
        val segLen = PROGRESS_MAX / numSegments

        val progressIndex = when (state.status) {
            JourneyStatus.CONNECTION_ERROR, JourneyStatus.GENERAL_ERROR -> -1
            JourneyStatus.CHECK_IN -> 0
            JourneyStatus.BOARDING_SOON, JourneyStatus.BOARDING_DELAYED,
            JourneyStatus.BOARDING, JourneyStatus.LAST_CALL -> 1
            JourneyStatus.TAKING_OFF -> 2
            JourneyStatus.ON_THE_WAY -> 3
            JourneyStatus.ARRIVAL -> 4
        }

        val seg0 = Notification.ProgressStyle.Segment(segLen).setColor(if (progressIndex >= 1) greenColor else greyColor)
        val seg1 = Notification.ProgressStyle.Segment(segLen).setColor(if (progressIndex >= 2) greenColor else greyColor)
        val seg2 = Notification.ProgressStyle.Segment(segLen).setColor(if (progressIndex >= 3) greenColor else greyColor)
        val seg3 = Notification.ProgressStyle.Segment(segLen).setColor(if (progressIndex >= 4) greenColor else greyColor)

        val progress = when (state.status) {
            JourneyStatus.CONNECTION_ERROR, JourneyStatus.GENERAL_ERROR -> 0
            JourneyStatus.CHECK_IN -> 0
            JourneyStatus.BOARDING_SOON, JourneyStatus.BOARDING_DELAYED,
            JourneyStatus.BOARDING, JourneyStatus.LAST_CALL -> segLen
            JourneyStatus.TAKING_OFF -> segLen * 2
            JourneyStatus.ON_THE_WAY -> segLen * 3 + (computeFlightProgress(attributes, state) * segLen).toInt()
            JourneyStatus.ARRIVAL -> PROGRESS_MAX
        }

        return Notification.ProgressStyle()
            .setProgressSegments(listOf(seg0, seg1, seg2, seg3))
            .setProgress(progress)
            .setStyledByProgress(false)
    }

    private fun computeFlightProgress(attributes: JourneyAttributes, state: JourneyContentState): Double {
        val eta = state.estimatedArrivalTime ?: return 0.0
        val total = eta.epochSecond - attributes.scheduledDepartureTime.epochSecond
        if (total <= 0) return 0.0
        val elapsed = java.time.Instant.now().epochSecond - attributes.scheduledDepartureTime.epochSecond
        return (elapsed.toDouble() / total).coerceIn(0.0, 1.0)
    }

    private fun buildContentText(state: JourneyContentState): String = when (state.status) {
        JourneyStatus.CHECK_IN          -> "With Baggage · ${state.checkInDesk ?: "--"}"
        JourneyStatus.BOARDING_SOON     -> "Wait for boarding to open · Gate ${state.gate ?: "--"}"
        JourneyStatus.BOARDING_DELAYED  -> "Wait for boarding to open · Gate ${state.gate ?: "--"}"
        JourneyStatus.BOARDING          -> "Boarding by groups · Group ${state.boardingGroup ?: "--"} · Gate ${state.gate ?: "--"}"
        JourneyStatus.LAST_CALL         -> "Gate is closing · No boarding after gate closes · Gate ${state.gate ?: "--"}"
        JourneyStatus.TAKING_OFF        -> "Please take a seat"
        JourneyStatus.ON_THE_WAY        -> "On the way · ETA ${state.estimatedArrivalTime ?: "--"}"
        JourneyStatus.ARRIVAL           -> "Welcome! · Terminal ${state.terminal ?: "--"}"
        JourneyStatus.CONNECTION_ERROR  -> "You seem to be offline. Check your connection."
        JourneyStatus.GENERAL_ERROR     -> "Error has occurred"
    }

    private fun buildStatusBarText(attributes: JourneyAttributes, state: JourneyContentState): String = when (state.status) {
        JourneyStatus.CHECK_IN         -> "${attributes.flightNumber} Check In"
        JourneyStatus.BOARDING_SOON    -> "${attributes.flightNumber} Boarding Soon"
        JourneyStatus.BOARDING_DELAYED -> "${attributes.flightNumber} Boarding Delayed"
        JourneyStatus.BOARDING         -> "${attributes.flightNumber} Boarding"
        JourneyStatus.LAST_CALL        -> "${attributes.flightNumber} Last Call"
        JourneyStatus.TAKING_OFF       -> "${attributes.flightNumber} Taking Off"
        JourneyStatus.ON_THE_WAY       -> "${attributes.flightNumber} On The Way"
        JourneyStatus.ARRIVAL          -> "${attributes.flightNumber} Arrival"
        JourneyStatus.CONNECTION_ERROR -> "${attributes.flightNumber} Connection Error"
        JourneyStatus.GENERAL_ERROR    -> "${attributes.flightNumber} Error"
    }
}
