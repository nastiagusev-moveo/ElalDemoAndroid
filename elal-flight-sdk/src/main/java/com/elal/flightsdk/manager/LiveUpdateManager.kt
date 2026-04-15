package com.elal.flightsdk.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.ProgressStyle
import androidx.core.graphics.drawable.IconCompat
import com.elal.flightsdk.R
import com.elal.flightsdk.model.JourneyAttributes
import com.elal.flightsdk.model.JourneyContentState
import com.elal.flightsdk.model.JourneyStatus

/**
 * Manages the lifecycle of Live Update notifications.
 * Uses NotificationCompat throughout — ProgressStyle renders on Android 16+ automatically,
 * falls back to plain title + content text on older devices.
 * No need to check Build.VERSION.SDK_INT for crashes — the compat library handles that.
 */
object LiveUpdateManager {

    private const val NOTIFICATION_ID = 7001
    private const val CHANNEL_ID = "elal_journey"
    private const val PROGRESS_MAX = 265

    private const val ProgressBarBlue = 0xFF1B358F.toInt()
    private const val ProgressBarGrey = 0xFF8E93A5.toInt()

    private var currentAttributes: JourneyAttributes? = null
    val isActive: Boolean get() = currentAttributes != null

    // TODO: DEBUG - Remove before release
    var debugNextStageIntent: PendingIntent? = null
    //

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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(buildStatusBarText(attributes, state))
            .setContentText(buildContentText(state))
            .setStyle(buildProgressStyle(context, attributes, state))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            //.build() // TODO: DEBUG - revert

        // TODO: DEBUG - Remove before release
        debugNextStageIntent?.let { notification.addAction(0, "Next Stage", it) }
        //

        nm.notify(NOTIFICATION_ID, notification.build())
//        nm.notify(NOTIFICATION_ID, notification) // TODO: DEBUG - revert
    }

    /**
     * 3 segments:
     *  seg0 (short) + seg1 (short) + seg2 (long — flight path, airplane moves along it)
     *
     * Step 1 – CHECK_IN:                              all grey,          no tracker
     * Step 2 – BOARDING_SOON / BOARDING / LAST_CALL:  seg0 blue,         no tracker
     * Step 3 – TAKING_OFF:                            seg0+seg1 blue,    airplane at start of seg2
     * Step 4 – ON_THE_WAY:                            seg0+seg1 blue,    airplane moves along seg2
     * Step 5 – ARRIVAL:                               all blue,          airplane at end of seg2
     */
    private fun buildProgressStyle(
        context: Context,
        attributes: JourneyAttributes,
        state: JourneyContentState
    ): ProgressStyle {
        val shortSegLen  = PROGRESS_MAX / 6   // 250
        val longSegLen   = PROGRESS_MAX / 2   // 500
        val flightStart  = shortSegLen * 2    // 500 — where the flight path begins
        val trackerInsetSpacing = longSegLen / 20    //  ~33 — nudge tracker past the segment gap

        val progressIndex = when (state.status) {
            JourneyStatus.CONNECTION_ERROR, JourneyStatus.GENERAL_ERROR  -> -1
            JourneyStatus.CHECK_IN                                       ->  0
            JourneyStatus.BOARDING_SOON, JourneyStatus.BOARDING_DELAYED,
            JourneyStatus.BOARDING, JourneyStatus.LAST_CALL              ->  1
            JourneyStatus.TAKING_OFF                                     ->  2
            JourneyStatus.ON_THE_WAY                                     ->  3
            JourneyStatus.ARRIVAL                                        ->  4
        }

        val seg0 = ProgressStyle.Segment(shortSegLen).setColor(if (progressIndex >= 1) ProgressBarBlue else ProgressBarGrey)
        val seg1 = ProgressStyle.Segment(shortSegLen).setColor(if (progressIndex >= 2) ProgressBarBlue else ProgressBarGrey)
        val seg2 = ProgressStyle.Segment(longSegLen) .setColor(if (progressIndex >= 4) ProgressBarBlue else ProgressBarGrey)

        val progress = when (state.status) {
            JourneyStatus.CONNECTION_ERROR, JourneyStatus.GENERAL_ERROR  -> 0
            JourneyStatus.CHECK_IN                                       -> 0
            JourneyStatus.BOARDING_SOON, JourneyStatus.BOARDING_DELAYED,
            JourneyStatus.BOARDING, JourneyStatus.LAST_CALL              -> shortSegLen
            JourneyStatus.TAKING_OFF                                     -> flightStart + trackerInsetSpacing
            JourneyStatus.ON_THE_WAY                                     -> flightStart + trackerInsetSpacing + ((computeFlightProgress(attributes, state) * (longSegLen - trackerInsetSpacing)).toInt())
            JourneyStatus.ARRIVAL                                        -> PROGRESS_MAX
        }

        val style = ProgressStyle()
            .setProgressSegments(listOf(seg0, seg1, seg2))
            .setProgress(progress)
            .setStyledByProgress(false)

        val trackerIcon = when (state.status) {
            JourneyStatus.TAKING_OFF  -> R.drawable.ic_airplane_move
            JourneyStatus.ON_THE_WAY  -> R.drawable.ic_airplane_move
            JourneyStatus.ARRIVAL     -> R.drawable.ic_airplane_move
            else                      -> null
        }
        trackerIcon?.let {
            style.setProgressTrackerIcon(IconCompat.createWithResource(context, it))
        }

        return style
    }

    private fun computeFlightProgress(attributes: JourneyAttributes, state: JourneyContentState): Double {
        val eta = state.estimatedArrivalTime ?: return 0.0
        val total = eta.epochSecond - attributes.scheduledDepartureTime.epochSecond
        if (total <= 0) return 0.0
        val elapsed = java.time.Instant.now().epochSecond - attributes.scheduledDepartureTime.epochSecond
        return (elapsed.toDouble() / total).coerceIn(0.0, 1.0)
    }

    private fun buildContentText(state: JourneyContentState): String = when (state.status) {
        JourneyStatus.CHECK_IN         -> "Check-in desks: ${state.checkInDesk ?: "--"}"
        JourneyStatus.BOARDING_SOON    -> "Gate: ${state.gate ?: "--"}"
        JourneyStatus.BOARDING_DELAYED -> "Delayed, Gate: ${state.gate ?: "--"}"
        JourneyStatus.BOARDING         -> "Your zone: ${state.boardingZone ?: "--"}"
        JourneyStatus.LAST_CALL        -> "No boarding after the gate closes: ${state.gate ?: "--"}"
        JourneyStatus.TAKING_OFF       -> "Please take a seat"
        JourneyStatus.ON_THE_WAY       -> "Landing in ${state.estimatedArrivalTime ?: "--"}"
        JourneyStatus.ARRIVAL          -> "Baggage claim will be at carousel: ${state.terminal ?: "--"}"
        JourneyStatus.CONNECTION_ERROR -> "You seem to be offline. Check your connection."
        JourneyStatus.GENERAL_ERROR    -> "Error has occurred"
    }

//    private fun buildActionText(state: JourneyContentState): String? = when (state.status) {
//        JourneyStatus.BOARDING_SOON    -> "Gate will open at ${formatTime(state.boardingTime)}"
//        JourneyStatus.BOARDING_DELAYED -> "Flight Delayed"
//        JourneyStatus.BOARDING         -> "Gate closes at ${formatTime(state.gateClosingTime)}"
//        JourneyStatus.LAST_CALL        -> "No boarding after gate closes"
//        else                           -> null
//    }
//
    private fun formatTime(instant: java.time.Instant?): String {
        if (instant == null) return "--"
        val local = instant.atZone(java.time.ZoneId.systemDefault())
        return "%d:%02d".format(local.hour, local.minute)
    }

    private fun buildStatusBarText(attributes: JourneyAttributes, state: JourneyContentState): String {
        val depTime = formatTime(attributes.scheduledDepartureTime)
        val arrTime = formatTime(
            state.estimatedArrivalTime ?: attributes.scheduledArrivalTime
        )
        val flightNumber = attributes.flightNumber
        return when (state.status) {
            JourneyStatus.CHECK_IN         -> "$flightNumber • $depTime • Baggage drop"
            JourneyStatus.BOARDING_SOON    -> "$flightNumber • $depTime • Boarding Soon"
            JourneyStatus.BOARDING_DELAYED -> "$flightNumber • $depTime • Boarding Delayed"
            JourneyStatus.BOARDING         -> "$flightNumber • $depTime • Boarding"
            JourneyStatus.LAST_CALL        -> "$flightNumber • $depTime • Last Call"
            JourneyStatus.TAKING_OFF       -> "$flightNumber • $depTime • Taking Off"
            JourneyStatus.ON_THE_WAY       -> "$flightNumber • $arrTime • On The Way"
            JourneyStatus.ARRIVAL          -> "$flightNumber • $arrTime • Arrival"
            JourneyStatus.CONNECTION_ERROR -> "Connection Error"
            JourneyStatus.GENERAL_ERROR    -> "Error"
        }
    }
}
