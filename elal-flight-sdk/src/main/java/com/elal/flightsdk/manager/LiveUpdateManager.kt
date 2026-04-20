package com.elal.flightsdk.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.ProgressStyle
import androidx.core.graphics.drawable.IconCompat
import com.elal.flightsdk.R
import com.elal.flightsdk.model.JourneyAttributes
import com.elal.flightsdk.model.JourneyContentState
import com.elal.flightsdk.model.JourneyStatus
import com.elal.flightsdk.utils.Logger
import com.elal.flightsdk.utils.orDash
import com.elal.flightsdk.utils.toFormattedTime
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

    // Android's ProgressStyle gap bug Fixed
    private const val SEG0_COMPENSATION = PROGRESS_MAX / 24  // = 10

    private const val ProgressBarBlue = 0xFF1B358F.toInt()
    private const val ProgressBarGrey = 0xFF8E93A5.toInt()

    private val log = Logger("LiveUpdateManager")

    private var currentAttributes: JourneyAttributes? = null
    val isActive: Boolean get() = currentAttributes != null
    private var onTheWayStartTime: java.time.Instant? = null
    private var onTheWayEta: java.time.Instant? = null
    private var appContext: Context? = null
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

    fun startJourney(context: Context, attributes: JourneyAttributes, initialState: JourneyContentState): EmptyResult<LiveUpdateError> {
        if (isActive) return Result.Failure(LiveUpdateError.ActivityAlreadyRunning())
        log.i("startJourney: flight=${attributes.flightNumber} status=${initialState.status}")
        appContext = context.applicationContext
        ensureChannel(context)
        currentAttributes = attributes
        onTheWayStartTime = null
        onTheWayEta = null
        postNotification(context, attributes, initialState)
        return Result.Success(Unit)
    }

    fun updateJourney(context: Context, state: JourneyContentState): EmptyResult<LiveUpdateError> {
        val attrs = currentAttributes ?: return Result.Failure(LiveUpdateError.NoActiveActivity())
        log.d("updateJourney: status=${state.status}")
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
        return Result.Success(Unit)
    }

    fun endJourney(context: Context) {
        log.i("endJourney")
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

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(chipIconFor(state.status))
            .setContentTitle(buildStatusBarText(attributes, state))
            .setContentText(buildContentText(state))
            .setStyle(buildProgressStyle(context, state))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)

        // Status-bar chip label: static text for now.
        // Chronometer countdown is commented out — it truncates seconds and drifts from the real ETA.
//        if (state.status == JourneyStatus.ON_THE_WAY) {
//            val eta = onTheWayEta ?: state.estimatedArrivalTime
//            if (eta != null) {
//                builder.setWhen(eta.toEpochMilli())
//                    .setShowWhen(true)
//                    .setUsesChronometer(true)
//                    .setChronometerCountDown(true)
//            } else {
//                builder.setShortCriticalText(buildChipText(state))
//            }
//        } else {
            builder.setShortCriticalText(buildChipText(state))
                .setShowWhen(false)
//        }

        val notification = builder
            // Silence: for Errors and when airplane moves (ON_THE_WAY stage)
            .setOnlyAlertOnce(
                isSelfUpdate ||
                state.status == JourneyStatus.CONNECTION_ERROR ||
                state.status == JourneyStatus.GENERAL_ERROR
            )
            //.build() // TODO: DEBUG - revert

        // TODO: DEBUG - Remove before release
        debugNextStageIntent?.let { notification.addAction(0, "Next Stage", it) }

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
        val shortSegLen  = PROGRESS_MAX / 5                      // 48
        val seg0Len      = shortSegLen - SEG0_COMPENSATION       // 38
        val seg1Len      = shortSegLen                           // 48
        val flightStart  = seg0Len + seg1Len                     // 86
        val longSegLen   = PROGRESS_MAX - flightStart            // 154

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
            ProgressStyle.Segment(seg0Len).setColor(ProgressBarBlue),
            ProgressStyle.Segment(seg1Len).setColor(ProgressBarBlue),
            ProgressStyle.Segment(longSegLen).setColor(ProgressBarBlue)
        )

        val progress: Int = when {
            state.status == JourneyStatus.ON_THE_WAY -> {
                val flightProgress = computeFlightProgress(state)
                val filled = (flightProgress * longSegLen).toInt().coerceIn(0, longSegLen - 1)
                flightStart + 1 + filled
            }
            progressIndex >= 4 -> PROGRESS_MAX      // ARRIVAL — end of bar
            progressIndex >= 2 -> flightStart       // TAKING_OFF — start of flight path
            progressIndex >= 1 -> seg0Len           // BOARDING states — end of seg0
            else               -> 0                 // CHECK_IN / errors
        }

        val style = ProgressStyle()
            .setProgressSegments(segments)
            .setProgress(progress)
            .setStyledByProgress(true)

        val trackerIcon = when (state.status) {
            JourneyStatus.TAKING_OFF -> R.drawable.ic_airplane_taking_off_left_2
            JourneyStatus.ON_THE_WAY -> R.drawable.ic_airplane_move_left_2
            JourneyStatus.ARRIVAL    -> R.drawable.ic_airplane_land_left_2
            else                     -> R.drawable.ic_airplane_placeholder_transparent // to avoid Android re-measures and layout jumps
        }
        style.setProgressTrackerIcon(IconCompat.createWithResource(context, trackerIcon))

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
        JourneyStatus.CHECK_IN         -> "Check-in desks: ${state.checkInDesk.orDash()}"
        JourneyStatus.BOARDING_SOON    -> "Gate: ${state.gate.orDash()}"
        JourneyStatus.BOARDING_DELAYED -> "Delayed, Gate: ${state.gate.orDash()}"
        JourneyStatus.BOARDING         -> "Your zone: ${state.boardingZone.orDash()}"
        JourneyStatus.LAST_CALL        -> "No boarding after the gate closes: ${state.gate.orDash()}"
        JourneyStatus.TAKING_OFF       -> "Taking off at ${state.takeoffTime.toFormattedTime()}"
        JourneyStatus.ON_THE_WAY       -> "Landing at ${state.estimatedArrivalTime.toFormattedTime()}"
        JourneyStatus.ARRIVAL          -> "Baggage claim will be at carousel: ${state.terminal.orDash()}"
        JourneyStatus.CONNECTION_ERROR -> "You seem to be offline. Check your connection."
        JourneyStatus.GENERAL_ERROR    -> "Error has occurred"
    }

    /**
     * Icon shown in the status-bar chip (Android 16 Live Update promoted chip)
     * and as the small icon in the notification header.
     */
    private fun chipIconFor(status: JourneyStatus): Int = when (status) {
        JourneyStatus.CHECK_IN         -> R.drawable.ic_chip_checkin
        JourneyStatus.BOARDING_SOON,
        JourneyStatus.BOARDING_DELAYED,
        JourneyStatus.BOARDING,
        JourneyStatus.LAST_CALL        -> R.drawable.ic_chip_boarding
        JourneyStatus.TAKING_OFF       -> R.drawable.ic_chip_takeoff
        JourneyStatus.ON_THE_WAY       -> R.drawable.ic_chip_ontheway
        JourneyStatus.ARRIVAL          -> R.drawable.ic_chip_arrival
        JourneyStatus.CONNECTION_ERROR,
        JourneyStatus.GENERAL_ERROR    -> R.drawable.ic_chip_error
    }

    /**
     * Short text shown next to the chip icon in the status bar.
     * Keep it tight — the chip has very limited width.
     */
    private fun buildChipText(state: JourneyContentState): String = when (state.status) {
        JourneyStatus.CHECK_IN         -> state.checkInDesk ?: "Check-in"
        JourneyStatus.BOARDING_SOON,
        JourneyStatus.BOARDING_DELAYED,
        JourneyStatus.BOARDING,
        JourneyStatus.LAST_CALL        -> state.gate ?: "Boarding"
        JourneyStatus.TAKING_OFF       -> "Please take a seat"
        JourneyStatus.ON_THE_WAY       -> "En route"
        JourneyStatus.ARRIVAL          -> "Arrival"
        JourneyStatus.CONNECTION_ERROR -> "Offline"
        JourneyStatus.GENERAL_ERROR    -> "Error"
    }

    private fun buildStatusBarText(attributes: JourneyAttributes, state: JourneyContentState): String {
        val depTime = attributes.scheduledDepartureTime.toFormattedTime()
        val arrTime = (state.estimatedArrivalTime ?: attributes.scheduledArrivalTime).toFormattedTime()
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
