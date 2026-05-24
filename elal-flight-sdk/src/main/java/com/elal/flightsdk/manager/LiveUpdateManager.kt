package com.elal.flightsdk.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.ProgressStyle
import androidx.core.graphics.drawable.IconCompat
import androidx.core.text.BidiFormatter
import com.elal.flightsdk.R
import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.model.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.elal.flightsdk.utils.Logger
import com.elal.flightsdk.utils.combineLabelAndValue
import com.elal.flightsdk.utils.orDash
import com.elal.flightsdk.utils.toFormattedTime
import com.elal.flightsdk.utils.toHhMmSs
import com.elal.flightsdk.utils.toMmSs
import com.elal.flightsdk.utils.toMsReadable
import com.elal.flightsdk.utils.wallClockNow

/**
 * Manages the lifecycle of Live Update notifications.
 * Requires Android 16 (API 36) — all public entry points are no-ops on older devices.
 */
object LiveUpdateManager {

    internal const val NOTIFICATION_ID = 7001
    internal const val CHANNEL_ID = "elal_journey"
    private const val PROGRESS_MAX = 240
    private const val MIN_API = 36 // Android 16

    private val isSupported: Boolean get() = Build.VERSION.SDK_INT >= MIN_API
    // Android's ProgressStyle gap bug Fixed
    private const val SEG0_COMPENSATION = PROGRESS_MAX / 24  // = 10
    private val log = Logger("LiveUpdateManager")

    // Safety net: if no server event arrives within this window, end the journey.
    // Protects against network outages that would otherwise leave the flow stuck.
    const val DEFAULT_WATCHDOG_MS: Long = 23L * 60L * 60L * 1000L
    const val DEBUG_WATCHDOG_MS: Long = 60L * 1000L

    var uiDebugMode: Boolean = false

    private val _stateFlow = MutableStateFlow<FlightContentState?>(null)
    val stateFlow: StateFlow<FlightContentState?> = _stateFlow.asStateFlow()

    data class WatchdogState(val startElapsedRealtimeMs: Long, val durationMs: Long)

    private val _watchdogFlow = MutableStateFlow<WatchdogState?>(null)
    val watchdogFlow: StateFlow<WatchdogState?> = _watchdogFlow.asStateFlow()

    private var watchdogDurationMs: Long = DEFAULT_WATCHDOG_MS

    private var currentState: FlightContentState? = null
    val isActive: Boolean get() = currentState != null
    private var flightStartRealtimeMs: Long = 0L
    private var flightTotalDurationMs: Long = 0L

    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = Runnable {
        log.i("⏰ WATCHDOG FIRED: ${watchdogDurationMs / 1000}s elapsed without server events — ending journey")
        appContext?.let { endJourney(it) }
    }

    private var selfUpdateState: FlightContentState? = null
    private val selfUpdateRunnable: Runnable = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            val state = selfUpdateState ?: return
            val progress = computeFlightProgress()
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - flightStartRealtimeMs
            val nextTickMs = calculateNextTickInterval()
            log.d("✈️ FLIGHT PROGRESS: ${(progress * 100).toInt()}% Complete | Elapsed: ${elapsedMs.toMmSs()} / ${flightTotalDurationMs.toMmSs()} | Next update in ${nextTickMs / 1000}s")
            postNotification(ctx, state, isSelfUpdate = true)
            if (progress >= 1.0) {
                log.i("🏁 FLIGHT COMPLETE: Progress reached 100% — stopping updates")
                stopSelfUpdate()
                return
            }
            handler.postDelayed(this, nextTickMs)
        }
    }

    // TODO: for DEBUG only
    //var debugNextStageIntent: PendingIntent? = null

    fun ensureChannel(context: Context) {
        if (!isSupported) return
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

    fun startJourney(context: Context, state: FlightContentState): EmptyResult<LiveUpdateError> {
        if (!isSupported) return Result.Success(Unit)
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
            return Result.Failure(LiveUpdateError.NotificationsNotEnabled())
        if (isActive) return Result.Failure(LiveUpdateError.ActivityAlreadyRunning())
        log.d("startJourney: flight=${state.flightNumber} stage=${state.stage}")
        appContext = context.applicationContext
        ensureChannel(context)
        currentState = state
        _stateFlow.value = state
        flightStartRealtimeMs = 0L
        flightTotalDurationMs = 0L
        resetWatchdog()
        postNotification(context, state)
        return Result.Success(Unit)
    }

    fun updateJourney(context: Context, state: FlightContentState): EmptyResult<LiveUpdateError> {
        if (!isSupported) return Result.Success(Unit)
        if (currentState == null) return Result.Failure(LiveUpdateError.NoActiveActivity())
        log.d("updateJourney: stage=${state.stage}")

        currentState = state
        JourneyPrefs.saveState(context, state)
        resetWatchdog()

        val serviceIntent = android.content.Intent(context, InFlightService::class.java)

        if (state.stage == Stage.COMPLETED) {
            endJourney(context)  // emits null to stateFlow
            return Result.Success(Unit)
        }

        _stateFlow.value = state

        when (state.stage) {
            Stage.DEPARTED -> {
                if (selfUpdateState == null) {
                    val stillInFlight = setupRealtimeClocks(state)
                    if (stillInFlight) startSelfUpdate(state) else selfUpdateState = state
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    selfUpdateState = state
                    setupRealtimeClocks(state)
                }
            }
            Stage.LANDED, Stage.BAGGAGE -> {
                resetFlightTimers()
            }
            else -> {
                resetFlightTimers()
                context.stopService(serviceIntent)
            }
        }

        postNotification(context, state)
        return Result.Success(Unit)
    }

    fun endJourney(context: Context) {
        if (!isSupported) return
        log.i("endJourney")
        resetFlightTimers()
        stopWatchdog()
        context.stopService(android.content.Intent(context, InFlightService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        JourneyPrefs.clear(context)
        currentState = null
        _stateFlow.value = null
        appContext = null
    }

    /**
     * Restart the watchdog. Called on every server event so the journey is killed only
     * after [watchdogDurationMs] of silence — protecting against stuck flows when network
     * outages prevent the server's final "end" event from reaching the device.
     */
    private fun resetWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        val start = android.os.SystemClock.elapsedRealtime()
        handler.postDelayed(watchdogRunnable, watchdogDurationMs)
        _watchdogFlow.value = WatchdogState(start, watchdogDurationMs)
        log.i("⏰ WATCHDOG RESET: will fire in ${watchdogDurationMs / 1000}s")
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        _watchdogFlow.value = null
    }

    /** Change the watchdog duration. If a watchdog is currently active, restart it with the new value. */
    fun setWatchdogDuration(durationMs: Long) {
        watchdogDurationMs = durationMs
        if (_watchdogFlow.value != null) resetWatchdog()
    }

    private fun startSelfUpdate(state: FlightContentState) {
        selfUpdateState = state
        handler.removeCallbacks(selfUpdateRunnable)
        val tickMs = calculateNextTickInterval()
        log.i(" SELF-UPDATE STARTED: Airplane will move every ${tickMs / 1000}s")
        handler.postDelayed(selfUpdateRunnable, tickMs)
    }

    private fun stopSelfUpdate() {
        handler.removeCallbacks(selfUpdateRunnable)
        selfUpdateState = null
        log.i("🛑 SELF-UPDATE NOT ACTIVE")
    }

    fun pauseTicks() {
        handler.removeCallbacks(selfUpdateRunnable)
    }

    fun resumeTicks() {
        handler.removeCallbacks(selfUpdateRunnable)
        handler.post(selfUpdateRunnable)
    }

    private fun buildNotification( // android's native notification object
        context: Context,
        state: FlightContentState,
        isSelfUpdate: Boolean = false
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_elal_chip)
            .setContentTitle(buildStatusBarText(context, state))
            .setContentText(buildContentText(context, state))
            .setStyle(buildProgressStyle(context, state))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)

        builder
            //.setShortCriticalText(buildChipText(context, state)) // Not Used, but kept for future use
            .setShowWhen(false)
            .setOnlyAlertOnce(
                isSelfUpdate ||
                state.stage == Stage.CONNECTION_ERROR ||
                state.stage == Stage.GENERAL_ERROR
            )
        // TODO: for DEBUG only
        //debugNextStageIntent?.let { builder.addAction(0, "Next Stage", it) }
        return builder.build()
    }

    private fun postNotification(
        context: Context,
        state: FlightContentState,
        isSelfUpdate: Boolean = false
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(context, state, isSelfUpdate))
    }

    fun buildCurrentNotification(context: Context): android.app.Notification? {
        val state = selfUpdateState ?: currentState ?: return null
        return buildNotification(context, state, isSelfUpdate = selfUpdateState != null)
    }

    internal fun buildGeneralErrorNotification(context: Context): android.app.Notification {
        val errorState = currentState?.copy(stage = Stage.GENERAL_ERROR)
        if (errorState != null) {
            log.e("buildGeneralErrorNotification: active journey — transitioning to GENERAL_ERROR (flight=${errorState.flightNumber})")
            _stateFlow.value = errorState
            return buildNotification(context, errorState)
        }
        // No active state — bare notification to satisfy the Android foreground service contract
        log.e("buildGeneralErrorNotification: no active state, showing bare error notification")
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chip_error)
            .setContentText(context.getString(R.string.notif_content_general_error))
            .build()
    }

    fun restoreFromPrefs(context: Context, startServiceIfActive: Boolean = true) {
        if (!isSupported) return
        val state = JourneyPrefs.loadState(context) ?: return
        currentState = state
        _stateFlow.value = state
        appContext = context.applicationContext
        resetWatchdog()
        when (state.stage) {
            Stage.DEPARTED -> {
                if (setupRealtimeClocks(state)) startSelfUpdate(state) else selfUpdateState = state
                if (startServiceIfActive) ensureInFlightService(context)
            }
            else -> Unit
        }
    }

    private fun ensureInFlightService(context: Context) {
        val intent = android.content.Intent(context, InFlightService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Segment layout — always exactly 3 segments, never changes:
     *  seg0 (short) + seg1 (short) + seg2 (long / flight path)
     *
     * Step 1 – CHECK_IN:                              progress=0           → all grey,         no tracker
     * Step 2 – GATE_READY, BOARDING:                  progress=seg0        → seg0 blue,        no tracker
     * Step 3 – LAST_CALL (ready to fly):              progress=flightStart → seg0+seg1 blue,   airplane at start (takeoff icon)
     * Step 4 – DEPARTED (in-flight):                  progress grows       → blue trail,       airplane moves
     * Step 5 – LANDED, BAGGAGE:                       progress=max         → all blue,         landing icon
     */
    private fun buildProgressStyle(
        context: Context,
        state: FlightContentState
    ): ProgressStyle {
        val shortSegLen  = PROGRESS_MAX / 5                      // 48
        val seg0Len      = shortSegLen - SEG0_COMPENSATION       // 38
        val seg1Len      = shortSegLen                           // 48
        val flightStart  = seg0Len + seg1Len + 2                    // 88
        val longSegLen   = PROGRESS_MAX - flightStart            // 154

        val progressIndex = when (state.stage) {
            Stage.CONNECTION_ERROR, Stage.GENERAL_ERROR      -> -1
            Stage.CHECK_IN                                   ->  0
            Stage.GATE_READY, Stage.BOARDING                 ->  1
            Stage.LAST_CALL, Stage.DEPARTED                  ->  2
            Stage.LANDED, Stage.BAGGAGE                      ->  4
            Stage.COMPLETED                                  ->  4
        }

        val segmentColor = context.getColor(R.color.progress_bar_segment)

        val segments = listOf(
            ProgressStyle.Segment(seg0Len).setColor(segmentColor),
            ProgressStyle.Segment(seg1Len).setColor(segmentColor),
            ProgressStyle.Segment(longSegLen).setColor(segmentColor)
        )

        val progress: Int = when {
            state.stage == Stage.DEPARTED -> {
                val flightProgress = computeFlightProgress()
                val filled = (flightProgress * longSegLen).toInt().coerceIn(0, longSegLen - 1)
                if (uiDebugMode) log.d("[AIRPLANE] fill=$filled / $longSegLen | progressBar=${flightStart + filled} / $PROGRESS_MAX")
                flightStart + filled
            }
            progressIndex >= 4 -> PROGRESS_MAX      // LANDED/BAGGAGE — end of bar
            progressIndex >= 2 -> flightStart       // LAST_CALL — airplane at path start
            progressIndex >= 1 -> seg0Len           // GATE_READY/BOARDING — end of seg0
            else               -> 0                 // CHECK_IN / errors
        }

        val style = ProgressStyle()
            .setProgressSegments(segments)
            .setProgress(progress)
            .setStyledByProgress(true)

        val trackerIcon = when (state.stage) {
            Stage.LAST_CALL            -> R.drawable.ic_airplane_taking_off_left_2
            Stage.DEPARTED             -> R.drawable.ic_airplane_move_left_2
            Stage.LANDED, Stage.BAGGAGE -> R.drawable.ic_airplane_land_left_2
            else                       -> R.drawable.ic_airplane_placeholder_transparent
        }
        style.setProgressTrackerIcon(IconCompat.createWithResource(context, trackerIcon))

        return style
    }

    /**
     * @return true if the flight is still in progress (arrival in the future), false if arrival
     * has already passed. When false, callers should skip [startSelfUpdate] — the airplane will
     * sit at full progress because [computeFlightProgress] returns 1.0 when duration <= 0.
     */
    private fun setupRealtimeClocks(state: FlightContentState): Boolean {
        val now = wallClockNow()
        val remainingMs = state.arrivalTime.toEpochMilli() - now.toEpochMilli()
        log.d("(Arrival - now) => remainingMs: $remainingMs")

        if (remainingMs <= 0) {
            log.d("⚠️ Arrival time already passed (${state.arrivalTime.toHhMmSs()} <= ${now.toHhMmSs()}) — skipping self-update tick, airplane parked at end of bar")
            flightTotalDurationMs = 0L
            flightStartRealtimeMs = 0L
            return false
        }

        flightTotalDurationMs = remainingMs
        flightStartRealtimeMs = android.os.SystemClock.elapsedRealtime()
        log.d("flightTotalDurationMs: $flightTotalDurationMs")
        log.i(
            "\n📋 JOURNEY TIMELINE" +
            "\n   Departure           : ${state.departureTime.toHhMmSs()}" +
            "\n   Arrival (ETA)       : ${state.arrivalTime.toHhMmSs()}" +
            "\n   Now                 : ${now.toHhMmSs()}" +
            "\n   ─────────────────────────────────────" +
            "\n   Remaining           : ${flightTotalDurationMs.toMsReadable()}  (ETA − Now)  ← bar fills over this time" +
            "\n   ─────────────────────────────────────" +
            "\n   Tick Interval       : ${calculateNextTickInterval() / 1000}s"
        )
        return true
    }

    private fun calculateNextTickInterval(): Long {
        if (flightTotalDurationMs <= 0) return 1_000L
        val onePercentMs = flightTotalDurationMs / 100
        return onePercentMs.coerceIn(1_000L, 60_000L)
    }

    private fun buildDepartedCountdown(context: Context, state: FlightContentState): String {
        val remainingMs = state.arrivalTime.toEpochMilli() - wallClockNow().toEpochMilli()
        if (remainingMs <= 0L) {
            return context.getString(R.string.notif_content_landing_soon)
        }
        val totalMinutes = remainingMs / 60_000L
        val hours = (totalMinutes / 60).toInt()
        val minutes = (totalMinutes % 60).toInt()
        return if (hours == 0) {
            val displayMinutes = minutes.coerceAtLeast(1)
            context.getString(R.string.notif_content_departed_minutes, displayMinutes)
        } else {
            context.getString(R.string.notif_content_departed, hours, minutes)
        }
    }

    private fun resetFlightTimers() {
        stopSelfUpdate()
        flightStartRealtimeMs = 0L
        flightTotalDurationMs = 0L
    }

    private fun computeFlightProgress(): Double {
        if (flightTotalDurationMs <= 0) return 1.0
        val elapsed = android.os.SystemClock.elapsedRealtime() - flightStartRealtimeMs
        return (elapsed.toDouble() / flightTotalDurationMs).coerceIn(0.0, 1.0)
    }

    private fun buildStatusBarText(context: Context, state: FlightContentState): String {
        val bidi = BidiFormatter.getInstance()
        val flightNumber = bidi.unicodeWrap(state.flightNumber.orDash())
        val stageTitle = state.stageTitle.orDash()
        return when (state.stage) {
            Stage.CHECK_IN, Stage.GATE_READY, Stage.BOARDING, Stage.LAST_CALL ->
                "$flightNumber • ${state.departureTime.toFormattedTime()} • $stageTitle"
            Stage.DEPARTED, Stage.LANDED, Stage.BAGGAGE, Stage.COMPLETED ->
                "$flightNumber • ${state.arrivalTime.toFormattedTime()} • $stageTitle"
            Stage.CONNECTION_ERROR -> context.getString(R.string.notif_statusbar_connection_error)
            Stage.GENERAL_ERROR    -> context.getString(R.string.notif_statusbar_general_error)
        }
    }

    private fun buildContentText(context: Context, state: FlightContentState): String {
        val bidi = BidiFormatter.getInstance()
        return when (state.stage) {
            Stage.CHECK_IN    -> combineLabelAndValue(state.title, state.checkInCounters.orDash())
            Stage.GATE_READY  -> combineLabelAndValue(state.message, combineLabelAndValue(state.gateTitle, state.gate.orDash()))
            Stage.BOARDING    -> combineLabelAndValue(state.title, state.message.orDash())
            Stage.LAST_CALL   -> combineLabelAndValue(state.title, state.gate.orDash())
            Stage.DEPARTED    -> buildDepartedCountdown(context, state)
            Stage.LANDED      -> if (state.baggageCarousel.isNullOrBlank() || state.baggageCarousel == "--") {
                state.title
            } else {
                combineLabelAndValue(state.message, state.baggageCarousel)
            }
            Stage.BAGGAGE     -> combineLabelAndValue(state.title, state.baggageCarousel.orDash())
            Stage.COMPLETED   -> context.getString(R.string.notif_content_arrival_welcome, bidi.unicodeWrap(state.destination))
            Stage.CONNECTION_ERROR -> context.getString(R.string.notif_content_connection_error)
            Stage.GENERAL_ERROR    -> context.getString(R.string.notif_content_general_error)
        }
    }

    // Not Used, but kept for future use
//    private fun buildChipText(context: Context, state: FlightContentState): String = when (state.stage) {
//        Stage.CHECK_IN    -> state.checkInCounters ?: context.getString(R.string.notif_chip_check_in)
//        Stage.GATE_READY,
//        Stage.BOARDING,
//        Stage.LAST_CALL   -> state.gate ?: context.getString(R.string.notif_chip_boarding)
//        Stage.DEPARTED    -> context.getString(R.string.notif_chip_departed)
//        Stage.LANDED,
//        Stage.BAGGAGE,
//        Stage.COMPLETED   -> context.getString(R.string.notif_chip_arrival)
//        Stage.CONNECTION_ERROR -> context.getString(R.string.notif_chip_connection_error)
//        Stage.GENERAL_ERROR    -> context.getString(R.string.notif_chip_general_error)
//    }
}
