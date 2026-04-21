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
import androidx.core.text.BidiFormatter
import com.elal.flightsdk.R
import com.elal.flightsdk.model.JourneyAttributes
import com.elal.flightsdk.model.JourneyContentState
import com.elal.flightsdk.model.JourneyStatus
import com.google.gson.Gson
import com.elal.flightsdk.utils.Logger
import com.elal.flightsdk.utils.orDash
import com.elal.flightsdk.utils.toFormattedTime
import com.elal.flightsdk.utils.toHhMmSs
import com.elal.flightsdk.utils.toMmSs
import com.elal.flightsdk.utils.toMsReadable

/**
 * Manages the lifecycle of Live Update notifications.
 * Uses NotificationCompat throughout — ProgressStyle renders on Android 16+ automatically,
 * falls back to plain title + content text on older devices.
 * No need to check Build.VERSION.SDK_INT for crashes — the compat library handles that.
 */
object LiveUpdateManager {

    internal const val NOTIFICATION_ID = 7001
    private const val CHANNEL_ID = "elal_journey"
    private const val PROGRESS_MAX = 240
    // Android's ProgressStyle gap bug Fixed
    private const val SEG0_COMPENSATION = PROGRESS_MAX / 24  // = 10

    private const val ProgressBarBlue = 0xFF1B358F.toInt()
    private const val ProgressBarGrey = 0xFF8E93A5.toInt()

    private val log = Logger("LiveUpdateManager")

    var uiDebugMode: Boolean = false

    private var currentAttributes: JourneyAttributes? = null
    val isActive: Boolean get() = currentAttributes != null
    private var flightStartRealtimeMs: Long = 0L
    private var flightTotalDurationMs: Long = 0L
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var selfUpdateState: JourneyContentState? = null
    private val selfUpdateRunnable: Runnable = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            val attrs = currentAttributes ?: return
            val state = selfUpdateState ?: return
            val progress = computeFlightProgress()
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - flightStartRealtimeMs
            val nextTickMs = calculateNextTickInterval()
            log.d("✈️ FLIGHT PROGRESS: ${(progress * 100).toInt()}% Complete | Elapsed: ${elapsedMs.toMmSs()} / ${flightTotalDurationMs.toMmSs()} | Next update in ${nextTickMs / 1000}s")
            postNotification(ctx, attrs, state, isSelfUpdate = true)
            if (progress >= 1.0) {
                log.i("🏁 FLIGHT COMPLETE: Progress reached 100% — stopping updates")
                stopSelfUpdate()
                return
            }
            handler.postDelayed(this, nextTickMs)
        }
    }

    // TODO: for DEBUG only
    var debugNextStageIntent: PendingIntent? = null

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_description)
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
        flightStartRealtimeMs = 0L
        flightTotalDurationMs = 0L
        postNotification(context, attributes, initialState)
        return Result.Success(Unit)
    }

    fun updateJourney(context: Context, state: JourneyContentState): EmptyResult<LiveUpdateError> {
        val attrs = currentAttributes ?: return Result.Failure(LiveUpdateError.NoActiveActivity())
        log.d("updateJourney: status=${state.status}")

        saveStateToPrefs(context, attrs, state)

        val serviceIntent = android.content.Intent(context, OnTheWayService::class.java)

        if (state.status == JourneyStatus.ON_THE_WAY) {
            if (selfUpdateState == null) {
                setupRealtimeClocks(attrs, state)
                startSelfUpdate(state)
                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                // Re-anchor in case FCM pushed an updated ETA mid-flight
                selfUpdateState = state
                setupRealtimeClocks(attrs, state)
            }
        } else {
            stopSelfUpdate()
            flightStartRealtimeMs = 0L
            flightTotalDurationMs = 0L
            // ARRIVAL: keep the service alive so Android doesn't tear down the foreground
            // notification before postNotification() replaces it — killing it here causes a
            // dismiss/reopen flicker. endJourney() handles the cleanup a few seconds later.
            if (state.status != JourneyStatus.ARRIVAL) {
                context.stopService(serviceIntent)
            }
        }

        postNotification(context, attrs, state)
        return Result.Success(Unit)
    }

    fun endJourney(context: Context) {
        log.i("endJourney")
        stopSelfUpdate()
        context.stopService(android.content.Intent(context, OnTheWayService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        context.getSharedPreferences("journey_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        currentAttributes = null
        flightStartRealtimeMs = 0L
        flightTotalDurationMs = 0L
        appContext = null
    }

    private fun startSelfUpdate(state: JourneyContentState) {
        selfUpdateState = state
        handler.removeCallbacks(selfUpdateRunnable)
        val tickMs = calculateNextTickInterval()
        log.i("🚀 SELF-UPDATE STARTED: Airplane will move every ${tickMs / 1000}s")
        handler.postDelayed(selfUpdateRunnable, tickMs)
    }

    private fun stopSelfUpdate() {
        handler.removeCallbacks(selfUpdateRunnable)
        selfUpdateState = null
        log.i("🛑 SELF-UPDATE STOPPED")
    }

    fun pauseTicks() {
        handler.removeCallbacks(selfUpdateRunnable)
    }

    fun resumeTicks() {
        handler.removeCallbacks(selfUpdateRunnable)
        // Posts immediately; elapsedRealtime() calculates the correct position
        handler.post(selfUpdateRunnable)
    }

    private fun buildNotification(
        context: Context,
        attributes: JourneyAttributes,
        state: JourneyContentState,
        isSelfUpdate: Boolean = false
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(chipIconFor(state.status))
            .setContentTitle(buildStatusBarText(context, attributes, state))
            .setContentText(buildContentText(context, attributes, state))
            .setStyle(buildProgressStyle(context, state))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
        builder.setShortCriticalText(buildChipText(context, state))
            .setShowWhen(false)
            .setOnlyAlertOnce(
                isSelfUpdate ||
                state.status == JourneyStatus.CONNECTION_ERROR ||
                state.status == JourneyStatus.GENERAL_ERROR
            )
        // TODO: for DEBUG only
        debugNextStageIntent?.let { builder.addAction(0, "Next Stage", it) }
        return builder.build()
    }

    private fun postNotification(
        context: Context,
        attributes: JourneyAttributes,
        state: JourneyContentState,
        isSelfUpdate: Boolean = false
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(context, attributes, state, isSelfUpdate))
    }

    fun buildCurrentNotification(context: Context): android.app.Notification? {
        val attrs = currentAttributes ?: return null
        val state = selfUpdateState ?: return null
        return buildNotification(context, attrs, state, isSelfUpdate = true)
    }

    private fun saveStateToPrefs(context: Context, attrs: JourneyAttributes, state: JourneyContentState) {
        val prefs = context.getSharedPreferences("journey_prefs", Context.MODE_PRIVATE)
        val gson = Gson()
        prefs.edit()
            .putString("attrs", gson.toJson(attrs))
            .putString("state", gson.toJson(state))
            .apply()
    }

    fun restoreFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("journey_prefs", Context.MODE_PRIVATE)
        val attrsJson = prefs.getString("attrs", null)
        val stateJson = prefs.getString("state", null)
        if (attrsJson != null && stateJson != null) {
            val gson = Gson()
            currentAttributes = gson.fromJson(attrsJson, JourneyAttributes::class.java)
            val state = gson.fromJson(stateJson, JourneyContentState::class.java)
            appContext = context.applicationContext
            setupRealtimeClocks(currentAttributes!!, state)
            startSelfUpdate(state)
        }
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
        val flightStart  = seg0Len + seg1Len + 2                    // 88
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
                val flightProgress = computeFlightProgress()
                val filled = (flightProgress * longSegLen).toInt().coerceIn(0, longSegLen - 1)
                if (uiDebugMode) log.d("[AIRPLANE] fill=$filled / $longSegLen | progressBar=${flightStart + filled} / $PROGRESS_MAX")
                flightStart + filled
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

    private fun setupRealtimeClocks(attrs: JourneyAttributes, state: JourneyContentState) {
        val eta = state.estimatedArrivalTime ?: attrs.scheduledArrivalTime
        val etaSource = if (state.estimatedArrivalTime != null) "state.estimatedArrivalTime" else "attrs.scheduledArrivalTime (fallback)"
        val departureMs = attrs.scheduledDepartureTime.toEpochMilli()
        val etaMs = eta.toEpochMilli()
        val now = java.time.Instant.now()
        val nowWallClockMs = now.toEpochMilli()
        flightTotalDurationMs = etaMs - departureMs
        val elapsedSinceDepartureMs = nowWallClockMs - departureMs
        val remainingMs = etaMs - nowWallClockMs
        // Anchors monotonic clock to real elapsed time. Survives reboots because the
        // anchor is recomputed from wall-clock each time setupRealtimeClocks is called.
        flightStartRealtimeMs = android.os.SystemClock.elapsedRealtime() - elapsedSinceDepartureMs
        log.i(
            "\n📋 JOURNEY TIMELINE" +
            "\n   Scheduled Departure : ${attrs.scheduledDepartureTime.toHhMmSs()}" +
            "\n   Scheduled Arrival   : ${attrs.scheduledArrivalTime.toHhMmSs()}  (from attributes)" +
            "\n   Estimated Arrival   : ${eta.toHhMmSs()}  ($etaSource)" +
            "\n   Now                 : ${now.toHhMmSs()}" +
            "\n   ─────────────────────────────────────" +
            "\n   Total Duration      : ${flightTotalDurationMs.toMsReadable()}  (ETA − Departure)" +
            "\n   Elapsed Since Dep.  : ${elapsedSinceDepartureMs.toMsReadable()}  (Now − Departure)  ← initial jump" +
            "\n   Remaining           : ${remainingMs.toMsReadable()}  (ETA − Now)" +
            "\n   ─────────────────────────────────────" +
            "\n   Tick Interval       : ${calculateNextTickInterval() / 1000}s"
        )
    }

    private fun calculateNextTickInterval(): Long {
        if (flightTotalDurationMs <= 0) return 1_000L
        val onePercentMs = flightTotalDurationMs / 100
        return onePercentMs.coerceIn(1_000L, 300_000L)
    }

    private fun computeFlightProgress(): Double {
        if (flightTotalDurationMs <= 0) return 1.0
        val elapsed = android.os.SystemClock.elapsedRealtime() - flightStartRealtimeMs
        return (elapsed.toDouble() / flightTotalDurationMs).coerceIn(0.0, 1.0)
    }

    private fun buildContentText(context: Context, attributes: JourneyAttributes, state: JourneyContentState): String {
        val bidi = BidiFormatter.getInstance()
        return when (state.status) {
            JourneyStatus.CHECK_IN         -> context.getString(R.string.notif_content_check_in, bidi.unicodeWrap(state.checkInDesk.orDash()))
            JourneyStatus.BOARDING_SOON    -> context.getString(R.string.notif_content_boarding_soon, bidi.unicodeWrap(state.gate.orDash()))
            JourneyStatus.BOARDING_DELAYED -> context.getString(R.string.notif_content_boarding_delayed, bidi.unicodeWrap(state.gate.orDash()))
            JourneyStatus.BOARDING         -> context.getString(R.string.notif_content_boarding, bidi.unicodeWrap(state.boardingZone.orDash()))
            JourneyStatus.LAST_CALL        -> context.getString(R.string.notif_content_last_call, bidi.unicodeWrap(state.gate.orDash()))
            JourneyStatus.TAKING_OFF       -> context.getString(R.string.notif_content_taking_off, bidi.unicodeWrap(state.takeoffTime.toFormattedTime()))
            JourneyStatus.ON_THE_WAY       -> context.getString(R.string.notif_content_on_the_way, bidi.unicodeWrap(state.estimatedArrivalTime.toFormattedTime()))
            JourneyStatus.ARRIVAL          ->
                if (attributes.arrivalAirportCode == "TLV")
                    context.getString(R.string.notif_content_arrival_baggage, bidi.unicodeWrap(state.terminal.orDash()))
                else
                    context.getString(R.string.notif_content_arrival_welcome, bidi.unicodeWrap(attributes.arrivalCity))
            JourneyStatus.CONNECTION_ERROR -> context.getString(R.string.notif_content_connection_error)
            JourneyStatus.GENERAL_ERROR    -> context.getString(R.string.notif_content_general_error)
        }
    }

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

    private fun buildChipText(context: Context, state: JourneyContentState): String = when (state.status) {
        JourneyStatus.CHECK_IN         -> state.checkInDesk ?: context.getString(R.string.notif_chip_check_in)
        JourneyStatus.BOARDING_SOON,
        JourneyStatus.BOARDING_DELAYED,
        JourneyStatus.BOARDING,
        JourneyStatus.LAST_CALL        -> state.gate ?: context.getString(R.string.notif_chip_boarding)
        JourneyStatus.TAKING_OFF       -> context.getString(R.string.notif_chip_taking_off)
        JourneyStatus.ON_THE_WAY       -> context.getString(R.string.notif_chip_on_the_way)
        JourneyStatus.ARRIVAL          -> context.getString(R.string.notif_chip_arrival)
        JourneyStatus.CONNECTION_ERROR -> context.getString(R.string.notif_chip_connection_error)
        JourneyStatus.GENERAL_ERROR    -> context.getString(R.string.notif_chip_general_error)
    }

    private fun buildStatusBarText(context: Context, attributes: JourneyAttributes, state: JourneyContentState): String {
        val bidi = BidiFormatter.getInstance()
        val depTime = attributes.scheduledDepartureTime.toFormattedTime()
        val arrTime = (state.estimatedArrivalTime ?: attributes.scheduledArrivalTime).toFormattedTime()
        val flightNumber = bidi.unicodeWrap(attributes.flightNumber)
        return when (state.status) {
            JourneyStatus.CHECK_IN         -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_check_in)}"
            JourneyStatus.BOARDING_SOON    -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_boarding_soon)}"
            JourneyStatus.BOARDING_DELAYED -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_boarding_delayed)}"
            JourneyStatus.BOARDING         -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_boarding)}"
            JourneyStatus.LAST_CALL        -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_last_call)}"
            JourneyStatus.TAKING_OFF       -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_taking_off)}"
            JourneyStatus.ON_THE_WAY       -> "$flightNumber • $arrTime • ${context.getString(R.string.notif_statusbar_on_the_way)}"
            JourneyStatus.ARRIVAL          -> "$flightNumber • $arrTime • ${context.getString(R.string.notif_statusbar_arrival)}"
            JourneyStatus.CONNECTION_ERROR -> context.getString(R.string.notif_statusbar_connection_error)
            JourneyStatus.GENERAL_ERROR    -> context.getString(R.string.notif_statusbar_general_error)
        }
    }
}
