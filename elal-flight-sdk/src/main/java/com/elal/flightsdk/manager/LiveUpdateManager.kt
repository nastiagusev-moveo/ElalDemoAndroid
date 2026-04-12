package com.elal.flightsdk.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import com.elal.flightsdk.R
import com.elal.flightsdk.model.JourneyAttributes
import com.elal.flightsdk.model.JourneyContentState
import com.elal.flightsdk.model.JourneyStatus

/**
 * Manages the lifecycle of Android 16 Live Update notifications using Notification.ProgressStyle.
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

    @Throws(LiveUpdateError::class)
    fun startJourney(
        context: Context,
        attributes: JourneyAttributes,
        initialState: JourneyContentState
    ) {
        if (isActive) throw LiveUpdateError.ActivityAlreadyRunning()

        ensureChannel(context)
        currentAttributes = attributes
        postNotification(context, attributes, initialState)
    }

    @Throws(LiveUpdateError::class)
    fun updateJourney(context: Context, state: JourneyContentState) {
        val attrs = currentAttributes ?: throw LiveUpdateError.NoActiveActivity()
        postNotification(context, attrs, state)
    }

    fun endJourney(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
        currentAttributes = null
    }

    private fun postNotification(
        context: Context,
        attributes: JourneyAttributes,
        state: JourneyContentState
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val progressStyle = buildProgressStyle(attributes, state)

        val contentText = buildContentText(state)
        val statusBarText = buildStatusBarText(attributes, state)

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(Icon.createWithResource(context, R.drawable.ic_notification))
            .setContentTitle(statusBarText)
            .setContentText(contentText)
            .setStyle(progressStyle)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Builds a ProgressStyle with 7 points and 6 segments representing the journey statuses.
     *
     * Points:  [0] Check In  [1] Board Soon  [2] Boarding  [3] Last Call  [4] Taking Off  [5] On The Way  [6] Arrival
     * Segments:     seg 0         seg 1           seg 2         seg 3           seg 4           seg 5
     *
     * Error states show 0 progress.
     */
    private fun computeFlightProgress(attributes: JourneyAttributes, state: JourneyContentState): Double {
        val eta = state.estimatedArrivalTime ?: return 0.0
        val total = eta.epochSecond - attributes.scheduledDepartureTime.epochSecond
        if (total <= 0) return 0.0
        val elapsed = java.time.Instant.now().epochSecond - attributes.scheduledDepartureTime.epochSecond
        return (elapsed.toDouble() / total).coerceIn(0.0, 1.0)
    }

    private fun buildProgressStyle(attributes: JourneyAttributes, state: JourneyContentState): Notification.ProgressStyle {
        val greenColor = 0xFF4CAF50.toInt()
        val greyColor = 0xFF9E9E9E.toInt()

        val numSegments = 6
        val segLen = PROGRESS_MAX / numSegments

        // Map status to a progress index (0-based point index)
        val progressIndex = when (state.status) {
            JourneyStatus.CONNECTION_ERROR, JourneyStatus.GENERAL_ERROR -> -1
            JourneyStatus.CHECK_IN -> 0
            JourneyStatus.BOARDING_SOON, JourneyStatus.BOARDING_DELAYED -> 1
            JourneyStatus.BOARDING -> 2
            JourneyStatus.LAST_CALL -> 3
            JourneyStatus.TAKING_OFF -> 4
            JourneyStatus.ON_THE_WAY -> 5
            JourneyStatus.ARRIVAL -> 6
        }

        val point0 = Notification.ProgressStyle.Point(0).setColor(
            if (progressIndex >= 0) greenColor else greyColor
        )
        val point1 = Notification.ProgressStyle.Point(segLen).setColor(
            if (progressIndex >= 1) greenColor else greyColor
        )
        val point2 = Notification.ProgressStyle.Point(segLen * 2).setColor(
            if (progressIndex >= 2) greenColor else greyColor
        )
        val point3 = Notification.ProgressStyle.Point(segLen * 3).setColor(
            if (progressIndex >= 3) greenColor else greyColor
        )
        val point4 = Notification.ProgressStyle.Point(segLen * 4).setColor(
            if (progressIndex >= 4) greenColor else greyColor
        )
        val point5 = Notification.ProgressStyle.Point(segLen * 5).setColor(
            if (progressIndex >= 5) greenColor else greyColor
        )
        val point6 = Notification.ProgressStyle.Point(PROGRESS_MAX).setColor(
            if (progressIndex >= 6) greenColor else greyColor
        )

        val seg0 = Notification.ProgressStyle.Segment(segLen).setColor(
            if (progressIndex > 0) greenColor else greyColor
        )
        val seg1 = Notification.ProgressStyle.Segment(segLen).setColor(
            if (progressIndex > 1) greenColor else greyColor
        )
        val seg2 = Notification.ProgressStyle.Segment(segLen).setColor(
            if (progressIndex > 2) greenColor else greyColor
        )
        val seg3 = Notification.ProgressStyle.Segment(segLen).setColor(
            if (progressIndex > 3) greenColor else greyColor
        )
        val seg4 = Notification.ProgressStyle.Segment(segLen).setColor(
            if (progressIndex > 4) greenColor else greyColor
        )
        val seg5 = Notification.ProgressStyle.Segment(segLen).setColor(
            if (progressIndex >= 6) greenColor else greyColor
        )

        val progress = when (state.status) {
            JourneyStatus.CONNECTION_ERROR, JourneyStatus.GENERAL_ERROR -> 0
            JourneyStatus.CHECK_IN -> 0
            JourneyStatus.BOARDING_SOON, JourneyStatus.BOARDING_DELAYED -> segLen
            JourneyStatus.BOARDING -> segLen * 2
            JourneyStatus.LAST_CALL -> segLen * 3
            JourneyStatus.TAKING_OFF -> segLen * 4
            JourneyStatus.ON_THE_WAY -> segLen * 5 + (computeFlightProgress(attributes, state) * segLen).toInt()
            JourneyStatus.ARRIVAL -> PROGRESS_MAX
        }

        return Notification.ProgressStyle()
            .setProgressPoints(listOf(point0, point1, point2, point3, point4, point5, point6))
            .setProgressSegments(listOf(seg0, seg1, seg2, seg3, seg4, seg5))
            .setProgress(progress)
            .setStyledByProgress(false)
    }

    private fun buildContentText(state: JourneyContentState): String {
        return when (state.status) {
            JourneyStatus.CHECK_IN ->
                "With Baggage · ${state.checkInDesk ?: "--"}"
            JourneyStatus.BOARDING_SOON ->
                "Wait for boarding to open · Gate ${state.gate ?: "--"}"
            JourneyStatus.BOARDING_DELAYED ->
                "Wait for boarding to open · Gate ${state.gate ?: "--"}"
            JourneyStatus.BOARDING ->
                "Boarding will be called by groups · Group ${state.boardingGroup ?: "--"} · Gate ${state.gate ?: "--"}"
            JourneyStatus.LAST_CALL ->
                "Gate is closing · No boarding after the gate closes · Gate ${state.gate ?: "--"}"
            JourneyStatus.TAKING_OFF ->
                "Please take a seat"
            JourneyStatus.ON_THE_WAY ->
                "On the way · ETA ${state.estimatedArrivalTime ?: "--"}"
            JourneyStatus.ARRIVAL ->
                "Welcome! · Terminal ${state.terminal ?: "--"}"
            JourneyStatus.CONNECTION_ERROR ->
                "You seem to be offline. Check your connection."
            JourneyStatus.GENERAL_ERROR ->
                "Error has occurred"
        }
    }

    private fun buildStatusBarText(
        attributes: JourneyAttributes,
        state: JourneyContentState
    ): String {
        return when (state.status) {
            JourneyStatus.CHECK_IN -> "${attributes.flightNumber} Check In"
            JourneyStatus.BOARDING_SOON -> "${attributes.flightNumber} Boarding Soon"
            JourneyStatus.BOARDING_DELAYED -> "${attributes.flightNumber} Boarding Delayed"
            JourneyStatus.BOARDING -> "${attributes.flightNumber} Boarding"
            JourneyStatus.LAST_CALL -> "${attributes.flightNumber} Last Call"
            JourneyStatus.TAKING_OFF -> "${attributes.flightNumber} Taking Off"
            JourneyStatus.ON_THE_WAY -> "${attributes.flightNumber} On The Way"
            JourneyStatus.ARRIVAL -> "${attributes.flightNumber} Arrival"
            JourneyStatus.CONNECTION_ERROR -> "${attributes.flightNumber} Connection Error"
            JourneyStatus.GENERAL_ERROR -> "${attributes.flightNumber} Error"
        }
    }
}
