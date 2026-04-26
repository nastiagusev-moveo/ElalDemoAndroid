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
import com.elal.flightsdk.model.Direction
import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.model.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var uiDebugMode: Boolean = false

    private val _stateFlow = MutableStateFlow<FlightContentState?>(null)
    val stateFlow: StateFlow<FlightContentState?> = _stateFlow.asStateFlow()

    private var currentState: FlightContentState? = null
    val isActive: Boolean get() = currentState != null
    private var flightStartRealtimeMs: Long = 0L
    private var flightTotalDurationMs: Long = 0L
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
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

    fun startJourney(context: Context, state: FlightContentState): EmptyResult<LiveUpdateError> {
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
            return Result.Failure(LiveUpdateError.NotificationsNotEnabled())
        if (isActive) return Result.Failure(LiveUpdateError.ActivityAlreadyRunning())
        log.i("startJourney: flight=${state.flightNumber} stage=${state.stage}")
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
        if (currentState == null) return Result.Failure(LiveUpdateError.NoActiveActivity())
        log.d("updateJourney: stage=${state.stage}")

        currentState = state
        saveStateToPrefs(context, state)

        val serviceIntent = android.content.Intent(context, OnTheWayService::class.java)

        if (state.stage == Stage.COMPLETED) {
            endJourney(context)  // emits null to stateFlow
            return Result.Success(Unit)
        }

        _stateFlow.value = state

        if (state.stage == Stage.DEPARTED) {
            if (selfUpdateState == null) {
                setupRealtimeClocks(state)
                startSelfUpdate(state)
                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                selfUpdateState = state
                setupRealtimeClocks(state)
            }
        } else {
            stopSelfUpdate()
            flightStartRealtimeMs = 0L
            flightTotalDurationMs = 0L
            // LANDED: keep the service alive so Android doesn't tear down the foreground
            // notification before postNotification() replaces it.
            if (state.stage != Stage.LANDED && state.stage != Stage.BAGGAGE) {
                context.stopService(serviceIntent)
            }
        }

        postNotification(context, state)
        return Result.Success(Unit)
    }

    fun endJourney(context: Context) {
        log.i("endJourney")
        stopSelfUpdate()
        context.stopService(android.content.Intent(context, OnTheWayService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        context.getSharedPreferences("journey_prefs", Context.MODE_PRIVATE).edit().clear().apply()
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
        state: FlightContentState,
        isSelfUpdate: Boolean = false
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(chipIconFor(state.stage))
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
        debugNextStageIntent?.let { builder.addAction(0, "Next Stage", it) }
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
        val state = selfUpdateState ?: return null
        return buildNotification(context, state, isSelfUpdate = true)
    }

    private fun saveStateToPrefs(context: Context, state: FlightContentState) {
        val prefs = context.getSharedPreferences("journey_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("state", jsonFormat.encodeToString(state))
            .apply()
    }

    fun restoreFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("journey_prefs", Context.MODE_PRIVATE)
        val stateJson = prefs.getString("state", null) ?: return
        try {
            val state = jsonFormat.decodeFromString<FlightContentState>(stateJson)
            currentState = state
            _stateFlow.value = state
            appContext = context.applicationContext
            if (state.stage == Stage.DEPARTED) {
                setupRealtimeClocks(state)
                startSelfUpdate(state)
            }
        } catch (e: Exception) {
            log.e("restoreFromPrefs: corrupt prefs, clearing — ${e.message}", e)
            prefs.edit().clear().apply()
        }
    }

    /**
     * Segment layout — always exactly 3 segments, never changes:
     *  seg0 (short) + seg1 (short) + seg2 (long / flight path)
     *
     * Step 1 – CHECK_IN:                   progress=0        → all grey,     no tracker
     * Step 2 – GATE_READY, BOARDING:       progress=seg0     → seg0 blue,   no tracker
     * Step 3 – DEPARTED (start):           progress=seg0+1   → seg0+1 blue, airplane at start
     * Step 4 – DEPARTED (in-flight):       progress grows    → blue trail,  airplane moves
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
            Stage.CONNECTION_ERROR, Stage.GENERAL_ERROR -> -1
            Stage.CHECK_IN                              ->  0
            Stage.GATE_READY, Stage.BOARDING            ->  1
            Stage.DEPARTED                              ->  2
            Stage.LANDED, Stage.BAGGAGE                 ->  4
            Stage.COMPLETED                             ->  4
        }

        val segments = listOf(
            ProgressStyle.Segment(seg0Len).setColor(ProgressBarBlue),
            ProgressStyle.Segment(seg1Len).setColor(ProgressBarBlue),
            ProgressStyle.Segment(longSegLen).setColor(ProgressBarBlue)
        )

        val progress: Int = when {
            state.stage == Stage.DEPARTED -> {
                val flightProgress = computeFlightProgress()
                val filled = (flightProgress * longSegLen).toInt().coerceIn(0, longSegLen - 1)
                if (uiDebugMode) log.d("[AIRPLANE] fill=$filled / $longSegLen | progressBar=${flightStart + filled} / $PROGRESS_MAX")
                flightStart + filled
            }
            progressIndex >= 4 -> PROGRESS_MAX      // LANDED/BAGGAGE — end of bar
            progressIndex >= 2 -> flightStart       // DEPARTED start — start of flight path
            progressIndex >= 1 -> seg0Len           // GATE_READY/BOARDING — end of seg0
            else               -> 0                 // CHECK_IN / errors
        }

        val style = ProgressStyle()
            .setProgressSegments(segments)
            .setProgress(progress)
            .setStyledByProgress(true)

        val trackerIcon = when (state.stage) {
            Stage.DEPARTED -> R.drawable.ic_airplane_move_left_2
            Stage.LANDED,
            Stage.BAGGAGE  -> R.drawable.ic_airplane_land_left_2
            else           -> R.drawable.ic_airplane_placeholder_transparent
        }
        style.setProgressTrackerIcon(IconCompat.createWithResource(context, trackerIcon))

        return style
    }

    private fun setupRealtimeClocks(state: FlightContentState) {
        val now = java.time.Instant.now()
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
            Stage.BOARDING    -> context.getString(R.string.notif_content_boarding, bidi.unicodeWrap(state.gate.orDash()))
            Stage.DEPARTED    -> context.getString(R.string.notif_content_departed, bidi.unicodeWrap(state.arrivalTime.toFormattedTime()))
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

    private fun chipIconFor(stage: Stage): Int = when (stage) {
        Stage.CHECK_IN                   -> R.drawable.ic_chip_checkin
        Stage.GATE_READY, Stage.BOARDING -> R.drawable.ic_chip_boarding
        Stage.DEPARTED                   -> R.drawable.ic_chip_ontheway
        Stage.LANDED, Stage.BAGGAGE,
        Stage.COMPLETED                  -> R.drawable.ic_chip_arrival
        Stage.CONNECTION_ERROR,
        Stage.GENERAL_ERROR              -> R.drawable.ic_chip_error
    }

    private fun buildChipText(context: Context, state: FlightContentState): String = when (state.stage) {
        Stage.CHECK_IN    -> state.checkInCounters ?: context.getString(R.string.notif_chip_check_in)
        Stage.GATE_READY,
        Stage.BOARDING    -> state.gate ?: context.getString(R.string.notif_chip_boarding)
        Stage.DEPARTED    -> context.getString(R.string.notif_chip_on_the_way)
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
            Stage.DEPARTED    -> "$flightNumber • $arrTime • ${context.getString(R.string.notif_statusbar_departed)}"
            Stage.LANDED      -> "$flightNumber • $arrTime • ${context.getString(R.string.notif_statusbar_landed)}"
            Stage.BAGGAGE     -> "$flightNumber • $arrTime • ${context.getString(R.string.notif_statusbar_baggage)}"
            Stage.COMPLETED   -> "$flightNumber • $arrTime • ${context.getString(R.string.notif_statusbar_completed)}"
            Stage.CONNECTION_ERROR -> context.getString(R.string.notif_statusbar_connection_error)
            Stage.GENERAL_ERROR    -> context.getString(R.string.notif_statusbar_general_error)
        }
    }
}
