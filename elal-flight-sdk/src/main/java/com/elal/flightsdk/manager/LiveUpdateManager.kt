package com.elal.flightsdk.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.ProgressStyle
import androidx.core.graphics.drawable.IconCompat
import androidx.core.text.BidiFormatter
import com.elal.flightsdk.R
import com.elal.flightsdk.model.Direction
import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.model.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.elal.flightsdk.utils.Logger
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

    var uiDebugMode: Boolean = false

    private val _stateFlow = MutableStateFlow<FlightContentState?>(null)
    val stateFlow: StateFlow<FlightContentState?> = _stateFlow.asStateFlow()

    private var currentState: FlightContentState? = null
    val isActive: Boolean get() = currentState != null
    private var flightStartRealtimeMs: Long = 0L
    private var flightTotalDurationMs: Long = 0L
    private const val DEPARTED_TO_IN_FLIGHT_DELAY_MS = 20 * 60 * 1000L  // 20 minutes

    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var selfUpdateState: FlightContentState? = null
    private var departedInFlightRunnable: Runnable? = null
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
        postNotification(context, state)
        return Result.Success(Unit)
    }

    fun updateJourney(context: Context, state: FlightContentState): EmptyResult<LiveUpdateError> {
        if (!isSupported) return Result.Success(Unit)
        if (currentState == null) return Result.Failure(LiveUpdateError.NoActiveActivity())
        log.d("updateJourney: stage=${state.stage}")

        currentState = state
        JourneyPrefs.saveState(context, state)

        val serviceIntent = android.content.Intent(context, InFlightService::class.java)

        if (state.stage == Stage.COMPLETED) {
            endJourney(context)  // emits null to stateFlow
            return Result.Success(Unit)
        }

        _stateFlow.value = state

        when (state.stage) {
            Stage.DEPARTED -> {
                stopSelfUpdate()
                cancelDepartedTimer()
                flightStartRealtimeMs = 0L
                flightTotalDurationMs = 0L
                scheduleDepartedToInFlightTimer(context)
                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
            }
            Stage.IN_FLIGHT -> {
                cancelDepartedTimer()
                if (selfUpdateState == null) {
                    setupRealtimeClocks(state)
                    startSelfUpdate(state)
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    selfUpdateState = state
                    setupRealtimeClocks(state)
                }
            }
            Stage.LANDED, Stage.BAGGAGE -> {
                cancelDepartedTimer()
                stopSelfUpdate()
                flightStartRealtimeMs = 0L
                flightTotalDurationMs = 0L
            }
            else -> {
                cancelDepartedTimer()
                stopSelfUpdate()
                flightStartRealtimeMs = 0L
                flightTotalDurationMs = 0L
                context.stopService(serviceIntent)
            }
        }

        postNotification(context, state)
        return Result.Success(Unit)
    }

    fun endJourney(context: Context) {
        if (!isSupported) return
        log.i("endJourney")
        cancelDepartedTimer()
        stopSelfUpdate()
        context.stopService(android.content.Intent(context, InFlightService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        JourneyPrefs.clear(context)
        currentState = null
        _stateFlow.value = null
        flightStartRealtimeMs = 0L
        flightTotalDurationMs = 0L
        appContext = null
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

    private fun scheduleDepartedToInFlightTimer(context: Context) {
        val targetMs = System.currentTimeMillis() + DEPARTED_TO_IN_FLIGHT_DELAY_MS
        JourneyPrefs.saveInFlightTargetMs(context, targetMs)
        postInFlightHandlerAfter(DEPARTED_TO_IN_FLIGHT_DELAY_MS)
        log.d("⏱️ DEPARTED timer scheduled: IN_FLIGHT will fire in ${DEPARTED_TO_IN_FLIGHT_DELAY_MS / 1000}s")
    }

    private fun postInFlightHandlerAfter(delayMs: Long) {
        val runnable = Runnable {
            departedInFlightRunnable = null
            val ctx = appContext ?: return@Runnable
            val latestState = currentState ?: return@Runnable
            log.d("⏱️ DEPARTED→IN_FLIGHT: timer fired — triggering IN_FLIGHT internally")
            updateJourney(ctx, latestState.copy(stage = Stage.IN_FLIGHT))
        }
        departedInFlightRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun cancelDepartedTimer() {
        departedInFlightRunnable?.let {
            handler.removeCallbacks(it)
            log.d("⏱️ DEPARTED timer cancelled")
        }
        departedInFlightRunnable = null
        appContext?.let { JourneyPrefs.clearInFlightTargetMs(it) }
    }

    fun pauseTicks() {
        handler.removeCallbacks(selfUpdateRunnable)
    }

    fun resumeTicks() {
        handler.removeCallbacks(selfUpdateRunnable)
        handler.post(selfUpdateRunnable)
    }

    private fun buildNotification(
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
        builder.setShortCriticalText(buildChipText(context, state))
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

    fun restoreFromPrefs(context: Context) {
        if (!isSupported) return
        val state = JourneyPrefs.loadState(context) ?: return
        currentState = state
        _stateFlow.value = state
        appContext = context.applicationContext
        when (state.stage) {
            Stage.IN_FLIGHT -> {
                setupRealtimeClocks(state)
                startSelfUpdate(state)
            }
            Stage.DEPARTED -> {
                val targetMs = JourneyPrefs.loadInFlightTargetMs(context)
                if (targetMs > 0L) {
                    val remainingMs = targetMs - System.currentTimeMillis()
                    if (remainingMs <= 0) {
                        log.i("⏱️ RESTORE: DEPARTED timer already expired — firing IN_FLIGHT immediately")
                        JourneyPrefs.clearInFlightTargetMs(context)
                        val inFlightState = state.copy(stage = Stage.IN_FLIGHT)
                        currentState = inFlightState
                        _stateFlow.value = inFlightState
                        JourneyPrefs.saveState(context, inFlightState)
                        setupRealtimeClocks(inFlightState)
                        startSelfUpdate(inFlightState)
                    } else {
                        log.i("⏱️ RESTORE: DEPARTED timer resuming — ${remainingMs / 1000}s remaining")
                        postInFlightHandlerAfter(remainingMs)
                    }
                }
            }
            else -> Unit
        }
    }

    /**
     * Segment layout — always exactly 3 segments, never changes:
     *  seg0 (short) + seg1 (short) + seg2 (long / flight path)
     *
     * Step 1 – CHECK_IN:                   progress=0        → all grey,     no tracker
     * Step 2 – GATE_READY, BOARDING, LAST_CALL:       progress=seg0     → seg0 blue,   no tracker
     * Step 3 – DEPARTED (start):           progress=seg0+1   → seg0+1 blue, airplane at start
     * Step 4 – IN_FLIGHT (in-flight):     progress grows    → blue trail,  airplane moves
     * Step 5 – LANDED, BAGGAGE:            progress=max      → all blue,    landing icon
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
            Stage.GATE_READY, Stage.BOARDING, Stage.LAST_CALL ->  1
            Stage.DEPARTED, Stage.IN_FLIGHT                 ->  2
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
            state.stage == Stage.IN_FLIGHT -> {
                val flightProgress = computeFlightProgress()
                val filled = (flightProgress * longSegLen).toInt().coerceIn(0, longSegLen - 1)
                if (uiDebugMode) log.d("[AIRPLANE] fill=$filled / $longSegLen | progressBar=${flightStart + filled} / $PROGRESS_MAX")
                flightStart + filled
            }
            progressIndex >= 4 -> PROGRESS_MAX      // LANDED/BAGGAGE — end of bar
            progressIndex >= 2 -> flightStart       // DEPARTED/IN_FLIGHT — airplane at path start
            progressIndex >= 1 -> seg0Len           // GATE_READY/BOARDING/LAST_CALL — end of seg0
            else               -> 0                 // CHECK_IN / errors
        }

        val style = ProgressStyle()
            .setProgressSegments(segments)
            .setProgress(progress)
            .setStyledByProgress(true)

        val trackerIcon = when (state.stage) {
            Stage.DEPARTED             -> R.drawable.ic_airplane_taking_off_left_2
            Stage.IN_FLIGHT           -> R.drawable.ic_airplane_move_left_2
            Stage.LANDED, Stage.BAGGAGE -> R.drawable.ic_airplane_land_left_2
            else                       -> R.drawable.ic_airplane_placeholder_transparent
        }
        style.setProgressTrackerIcon(IconCompat.createWithResource(context, trackerIcon))

        return style
    }

    private fun setupRealtimeClocks(state: FlightContentState) {
        val now = wallClockNow()
        val remainingMs = state.arrivalTime.toEpochMilli() - now.toEpochMilli()
        log.d("(Arrival - now) => remainingMs: $remainingMs")

        flightTotalDurationMs = remainingMs.coerceAtLeast(1_000L)
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

    private fun buildContentText(context: Context, state: FlightContentState): String {
        val bidi = BidiFormatter.getInstance()
        return when (state.stage) {
            Stage.CHECK_IN    -> context.getString(R.string.notif_content_check_in, bidi.unicodeWrap(state.checkInCounters.orDash()))
            Stage.GATE_READY  -> context.getString(R.string.notif_content_gate_ready, bidi.unicodeWrap(state.gate.orDash()))
            Stage.BOARDING    -> context.getString(R.string.notif_content_boarding, bidi.unicodeWrap(state.boardingZone.orDash()))
            Stage.LAST_CALL   -> context.getString(R.string.notif_content_last_call, bidi.unicodeWrap(state.gate.orDash()))
            Stage.DEPARTED    -> context.getString(R.string.notif_content_departed, bidi.unicodeWrap(state.departureTime.toFormattedTime()))
            Stage.IN_FLIGHT  -> context.getString(R.string.notif_content_in_flight, bidi.unicodeWrap(state.arrivalTime.toFormattedTime()))
            Stage.LANDED      ->
                if (state.direction == Direction.ARRIVAL)
                    context.getString(R.string.notif_content_arrival_baggage, bidi.unicodeWrap(state.terminal.orDash()))
                else
                    context.getString(R.string.notif_content_arrival_welcome, bidi.unicodeWrap(state.destination))
            Stage.BAGGAGE     -> context.getString(R.string.notif_content_arrival_baggage, bidi.unicodeWrap(state.baggageCarousel.orDash()))
            Stage.COMPLETED   -> context.getString(R.string.notif_content_arrival_welcome, bidi.unicodeWrap(state.destination))
            Stage.CONNECTION_ERROR -> context.getString(R.string.notif_content_connection_error)
            Stage.GENERAL_ERROR    -> context.getString(R.string.notif_content_general_error)
        }
    }

    private fun buildChipText(context: Context, state: FlightContentState): String = when (state.stage) {
        Stage.CHECK_IN    -> state.checkInCounters ?: context.getString(R.string.notif_chip_check_in)
        Stage.GATE_READY,
        Stage.BOARDING,
        Stage.LAST_CALL   -> state.gate ?: context.getString(R.string.notif_chip_boarding)
        Stage.DEPARTED    -> context.getString(R.string.notif_chip_departed)
        Stage.IN_FLIGHT  -> context.getString(R.string.notif_chip_in_flight)
        Stage.LANDED,
        Stage.BAGGAGE,
        Stage.COMPLETED   -> context.getString(R.string.notif_chip_arrival)
        Stage.CONNECTION_ERROR -> context.getString(R.string.notif_chip_connection_error)
        Stage.GENERAL_ERROR    -> context.getString(R.string.notif_chip_general_error)
    }

    private fun buildStatusBarText(context: Context, state: FlightContentState): String {
        val bidi = BidiFormatter.getInstance()
        val depTime = state.departureTime.toFormattedTime()
        val arrTime = state.arrivalTime.toFormattedTime()
        val flightNumber = bidi.unicodeWrap(state.flightNumber)
        return when (state.stage) {
            Stage.CHECK_IN    -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_check_in)}"
            Stage.GATE_READY  -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_gate_ready)}"
            Stage.BOARDING    -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_boarding)}"
            Stage.LAST_CALL   -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_last_call)}"
            Stage.DEPARTED    -> "$flightNumber • $depTime • ${context.getString(R.string.notif_statusbar_departed)}"
            Stage.IN_FLIGHT  -> "$flightNumber • $arrTime • ${context.getString(R.string.notif_statusbar_in_flight)}"
            Stage.LANDED      -> "$flightNumber • $arrTime • ${context.getString(R.string.notif_statusbar_landed)}"
            Stage.BAGGAGE     -> "$flightNumber • $arrTime • ${context.getString(R.string.notif_statusbar_baggage)}"
            Stage.COMPLETED   -> "$flightNumber • $arrTime • ${context.getString(R.string.notif_statusbar_completed)}"
            Stage.CONNECTION_ERROR -> context.getString(R.string.notif_statusbar_connection_error)
            Stage.GENERAL_ERROR    -> context.getString(R.string.notif_statusbar_general_error)
        }
    }
}
