package com.elal.flightsdk.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.ProgressStyle
import androidx.core.graphics.drawable.IconCompat
import com.elal.flightsdk.R
import com.elal.flightsdk.model.JourneyAttributes
import com.elal.flightsdk.model.JourneyContentState
import com.elal.flightsdk.model.JourneyStatus
import java.time.temporal.ChronoUnit

/**
 * Manages the lifecycle of Live Update notifications.
 * Uses NotificationCompat throughout — ProgressStyle renders on Android 16+ automatically,
 * falls back to plain title + content text on older devices.
 * No need to check Build.VERSION.SDK_INT for crashes — the compat library handles that.
 */
object LiveUpdateManager {

    private const val NOTIFICATION_ID = 7001
    private const val CHANNEL_ID = "elal_journey"
    private const val PROGRESS_MAX = 240
    private const val SELF_UPDATE_INTERVAL_MS = 1_000L

    private const val ProgressBarBlue = 0xFF1B358F.toInt()
    private const val ProgressBarGrey = 0xFF8E93A5.toInt()

    private var currentAttributes: JourneyAttributes? = null
    val isActive: Boolean get() = currentAttributes != null

    // Recorded the moment we first enter ON_THE_WAY so progress always starts from 0.
    // Both are latched once on the first ON_THE_WAY entry and never replaced mid-flight,
    // so repeated updateJourney calls can't shift the ETA and cause jumps.
    private var onTheWayStartTime: java.time.Instant? = null
    private var onTheWayEta: java.time.Instant? = null
    private var appContext: Context? = null

    // Self-update timer: re-posts the notification periodically so the airplane icon moves live.
    private val handler = Handler(Looper.getMainLooper())
    private var selfUpdateState: JourneyContentState? = null
    private val selfUpdateRunnable: Runnable = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            val attrs = currentAttributes ?: return
            val state = selfUpdateState ?: return
            postNotification(ctx, attrs, state, isSelfUpdate = true)
            if (computeFlightProgress(state) >= 1.0) {
                stopSelfUpdate()
                return
            }
            handler.postDelayed(this, SELF_UPDATE_INTERVAL_MS)
        }
    }

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
        appContext = context.applicationContext
        ensureChannel(context)
        currentAttributes = attributes
        onTheWayStartTime = null
        onTheWayEta = null
        postNotification(context, attributes, initialState)
    }

    fun updateJourney(context: Context, state: JourneyContentState) {
        val attrs = currentAttributes ?: throw LiveUpdateError.NoActiveActivity()
        // Latch start time and ETA the first time we enter ON_THE_WAY; clear for any other status.
        if (state.status == JourneyStatus.ON_THE_WAY) {
            if (onTheWayStartTime == null) {
                onTheWayStartTime = java.time.Instant.now()
                onTheWayEta = state.estimatedArrivalTime
            }
            startSelfUpdate(state)
        } else {
            onTheWayStartTime = null
            onTheWayEta = null
            stopSelfUpdate()
        }
        postNotification(context, attrs, state)
    }

    fun endJourney(context: Context) {
        stopSelfUpdate()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        currentAttributes = null
        onTheWayStartTime = null
        onTheWayEta = null
        appContext = null
    }

    private fun startSelfUpdate(state: JourneyContentState) {
        selfUpdateState = state
        handler.removeCallbacks(selfUpdateRunnable)
        handler.postDelayed(selfUpdateRunnable, SELF_UPDATE_INTERVAL_MS)
    }

    private fun stopSelfUpdate() {
        handler.removeCallbacks(selfUpdateRunnable)
        selfUpdateState = null
    }

    private fun postNotification(
        context: Context,
        attributes: JourneyAttributes,
        state: JourneyContentState,
        isSelfUpdate: Boolean = false
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(buildStatusBarText(attributes, state))
            .setContentText(buildContentText(state))
            .setStyle(buildProgressStyle(context, state))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            // Silence: for Errors and when airplane moves (ON_THE_WAY stage)
            .setOnlyAlertOnce(
                isSelfUpdate ||
                state.status == JourneyStatus.CONNECTION_ERROR ||
                state.status == JourneyStatus.GENERAL_ERROR
            )
            //.build() // TODO: DEBUG - revert

        // TODO: DEBUG - Remove before release
        debugNextStageIntent?.let { notification.addAction(0, "Next Stage", it) }
        //

        nm.notify(NOTIFICATION_ID, notification.build())
//        nm.notify(NOTIFICATION_ID, notification) // TODO: DEBUG - revert
    }

    /**
     * Segment layout — always exactly 3 segments, never changes:
     *  seg0 (short) + seg1 (short) + seg2 (long / flight path)
     *
     * All segments are set to blue; styledByProgress(true) lets the framework
     * color everything before the progress point in the segment's color (blue)
     * and everything after in its default unfilled style (grey).
     * This avoids splitting the flight segment into sub-segments, which would
     * introduce visible inter-segment gaps and cause layout jumps.
     *
     * Step 1 – CHECK_IN:                   progress=0        → all grey,     no tracker
     * Step 2 – BOARDING_SOON … LAST_CALL:  progress=seg0     → seg0 blue,   no tracker
     * Step 3 – TAKING_OFF:                 progress=seg0+1   → seg0+1 blue, airplane at start
     * Step 4 – ON_THE_WAY:                 progress grows    → blue trail,  airplane moves
     * Step 5 – ARRIVAL:                    progress=max      → all blue,    landing icon
     */
    private fun buildProgressStyle(
        context: Context,
        state: JourneyContentState
    ): ProgressStyle {
        val shortSegLen  = PROGRESS_MAX / 5               // 48
        val flightStart  = shortSegLen * 2               // 96
        val longSegLen   = PROGRESS_MAX - flightStart    // 144 — fills the rest so segments total PROGRESS_MAX (240)
        val totalSegLen  = shortSegLen * 2 + longSegLen  // 200

        val progressIndex = when (state.status) {
            JourneyStatus.CONNECTION_ERROR, JourneyStatus.GENERAL_ERROR  -> -1
            JourneyStatus.CHECK_IN                                       ->  0
            JourneyStatus.BOARDING_SOON, JourneyStatus.BOARDING_DELAYED,
            JourneyStatus.BOARDING, JourneyStatus.LAST_CALL              ->  1
            JourneyStatus.TAKING_OFF                                     ->  2
            JourneyStatus.ON_THE_WAY                                     ->  3
            JourneyStatus.ARRIVAL                                        ->  4
        }

        val segments = listOf(
            ProgressStyle.Segment(shortSegLen).setColor(ProgressBarBlue),
            ProgressStyle.Segment(shortSegLen).setColor(ProgressBarBlue),
            ProgressStyle.Segment(longSegLen).setColor(ProgressBarBlue)
        )

        val progress: Int = when {
            state.status == JourneyStatus.ON_THE_WAY -> {
                val flightProgress = computeFlightProgress(state)
                val filled = (flightProgress * longSegLen).toInt().coerceIn(0, longSegLen)
                flightStart + filled
            }
            progressIndex >= 4 -> totalSegLen             // ARRIVAL — end of bar
            progressIndex >= 2 -> flightStart             // TAKING_OFF — start of flight path
            progressIndex >= 1 -> shortSegLen             // BOARDING states — end of seg0
            else               -> 0                       // CHECK_IN / errors
        }

        Log.d("SegDbg", "${state.status} [$shortSegLen,$shortSegLen,$longSegLen] total=$totalSegLen progress=$progress/$PROGRESS_MAX")
        val style = ProgressStyle()
            .setProgressSegments(segments)
            .setProgress(progress)
            .setStyledByProgress(true)

        val trackerIcon = when (state.status) {
            JourneyStatus.TAKING_OFF -> R.drawable.ic_airplane_taking_off_left_2
            JourneyStatus.ON_THE_WAY -> R.drawable.ic_airplane_move_left_2
            JourneyStatus.ARRIVAL -> R.drawable.ic_airplane_land_left_2
            else                  -> null
        }
        trackerIcon?.let { style.setProgressTrackerIcon(IconCompat.createWithResource(context, it)) }

        return style
    }

    /**
     * Returns 0.0 → 1.0 based on time elapsed since ON_THE_WAY was entered
     * vs the total duration until estimatedArrivalTime (truncated to the minute boundary).
     * Uses the latched [onTheWayEta] so repeated updateJourney calls with a fresh state
     * can never shift the ETA and cause the airplane to jump or freeze.
     */
    private fun computeFlightProgress(state: JourneyContentState): Double {
        val eta       = onTheWayEta       ?: return 0.0
        val startTime = onTheWayStartTime ?: return 0.0
        val etaTruncated = eta.truncatedTo(ChronoUnit.MINUTES)
        val total = etaTruncated.epochSecond - startTime.epochSecond
        if (total <= 0) return 1.0
        val elapsed = java.time.Instant.now().epochSecond - startTime.epochSecond
        return (elapsed.toDouble() / total).coerceIn(0.0, 1.0)
    }

    private fun buildContentText(state: JourneyContentState): String = when (state.status) {
        JourneyStatus.CHECK_IN         -> "Check-in desks: ${state.checkInDesk ?: "--"}"
        JourneyStatus.BOARDING_SOON    -> "Gate: ${state.gate ?: "--"}"
        JourneyStatus.BOARDING_DELAYED -> "Delayed, Gate: ${state.gate ?: "--"}"
        JourneyStatus.BOARDING         -> "Your zone: ${state.boardingZone ?: "--"}"
        JourneyStatus.LAST_CALL        -> "No boarding after the gate closes: ${state.gate ?: "--"}"
        JourneyStatus.TAKING_OFF       -> "Taking off at ${formatTime(state.takeoffTime)}"
        JourneyStatus.ON_THE_WAY       -> "Landing at ${formatTime(state.estimatedArrivalTime)}"
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
