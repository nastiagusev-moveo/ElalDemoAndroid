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
 * On Android 16+ uses Notification.ProgressStyle for the native progress UI.
 * On older devices shows a plain ongoing notification.
 * Mirrors the iOS LiveActivityManager pattern: start → update → end.
 */
object LiveUpdateManager {

    private const val NOTIFICATION_ID = 7001
    private const val CHANNEL_ID = "elal_journey"

    // 5 segments × 200 = 1000 — clean integer math, no rounding gaps
    private const val PROGRESS_MAX = 1000
    private const val SEG_LEN = 200

    private val greenColor = 0xFF4CAF50.toInt()
    private val greyColor  = 0xFF9E9E9E.toInt()

    private var currentAttributes: JourneyAttributes? = null
    val isActive: Boolean get() = currentAttributes != null

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Journey Updates", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Live updates for your El Al journey" }
            )
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

    private fun postNotification(context: Context, attributes: JourneyAttributes, state: JourneyContentState) {
        val nm          = context.getSystemService(NotificationManager::class.java)
        val title       = buildStatusBarText(attributes, state)
        val contentText = buildContentText(state)

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(buildProgressStyle(attributes, state))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .build()
        } else {
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(contentText)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .build()
        }

        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 4 points between 5 segments — 9 total elements (system limit).
     * Boarding + Last Call share the same segment (progressIndex 2).
     *
     * [seg0] ● [seg1] ● [seg2] ● [seg3] ● [seg4]
     *       pt1      pt2      pt3      pt4
     *    BoardSoon  Board/LC  TakeOff  OnTheWay
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildProgressStyle(attributes: JourneyAttributes, state: JourneyContentState): Notification.ProgressStyle {
        val progressIndex = state.toProgressIndex()

        val point1 = Notification.ProgressStyle.Point(SEG_LEN).setColor(if (progressIndex >= 1) greenColor else greyColor)
        val point2 = Notification.ProgressStyle.Point(SEG_LEN * 2).setColor(if (progressIndex >= 2) greenColor else greyColor)
        val point3 = Notification.ProgressStyle.Point(SEG_LEN * 3).setColor(if (progressIndex >= 3) greenColor else greyColor)
        val point4 = Notification.ProgressStyle.Point(SEG_LEN * 4).setColor(if (progressIndex >= 4) greenColor else greyColor)

        val seg0 = Notification.ProgressStyle.Segment(SEG_LEN).setColor(if (progressIndex > 0) greenColor else greyColor)
        val seg1 = Notification.ProgressStyle.Segment(SEG_LEN).setColor(if (progressIndex > 1) greenColor else greyColor)
        val seg2 = Notification.ProgressStyle.Segment(SEG_LEN).setColor(if (progressIndex > 2) greenColor else greyColor)
        val seg3 = Notification.ProgressStyle.Segment(SEG_LEN).setColor(if (progressIndex > 3) greenColor else greyColor)
        val seg4 = Notification.ProgressStyle.Segment(SEG_LEN).setColor(if (progressIndex > 4) greenColor else greyColor)

        return Notification.ProgressStyle()
            .setProgressPoints(listOf(point1, point2, point3, point4))
            .setProgressSegments(listOf(seg0, seg1, seg2, seg3, seg4))
            .setProgress(computeProgress(attributes, state))
            .setStyledByProgress(false)
    }

    private fun JourneyContentState.toProgressIndex(): Int = when (status) {
        JourneyStatus.CONNECTION_ERROR,
        JourneyStatus.GENERAL_ERROR           -> -1
        JourneyStatus.CHECK_IN                ->  0
        JourneyStatus.BOARDING_SOON,
        JourneyStatus.BOARDING_DELAYED        ->  1
        JourneyStatus.BOARDING,
        JourneyStatus.LAST_CALL               ->  2
        JourneyStatus.TAKING_OFF              ->  3
        JourneyStatus.ON_THE_WAY              ->  4
        JourneyStatus.ARRIVAL                 ->  5
    }

    private fun computeProgress(attributes: JourneyAttributes, state: JourneyContentState): Int = when (state.status) {
        JourneyStatus.CONNECTION_ERROR,
        JourneyStatus.GENERAL_ERROR    -> 0
        JourneyStatus.CHECK_IN         -> 0
        JourneyStatus.BOARDING_SOON,
        JourneyStatus.BOARDING_DELAYED -> SEG_LEN
        JourneyStatus.BOARDING,
        JourneyStatus.LAST_CALL        -> SEG_LEN * 2
        JourneyStatus.TAKING_OFF       -> SEG_LEN * 3
        JourneyStatus.ON_THE_WAY       -> SEG_LEN * 4 + (computeFlightFraction(attributes, state) * SEG_LEN).toInt()
        JourneyStatus.ARRIVAL          -> PROGRESS_MAX
    }

    private fun computeFlightFraction(attributes: JourneyAttributes, state: JourneyContentState): Double {
        val eta   = state.estimatedArrivalTime ?: return 0.0
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

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun canPostPromotedNotifications(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
}
