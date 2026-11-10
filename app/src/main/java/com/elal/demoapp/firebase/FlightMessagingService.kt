package com.elal.demoapp.firebase

import com.elal.flightsdk.manager.LiveUpdateManager
import com.elal.flightsdk.manager.onFailure
import com.elal.flightsdk.model.AppLanguage
import com.elal.flightsdk.model.Carrier
import com.elal.flightsdk.model.Direction
import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.model.QueueLoad
import com.elal.flightsdk.model.Stage
import com.elal.flightsdk.utils.Logger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class FlightMessagingService : FirebaseMessagingService() {

    private val log = Logger("FlightMessagingService")

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) return

        val event = data["event"]?.lowercase()

        LiveUpdateManager.ensureChannel(applicationContext)

        if (event == "end") {
            log.i("FCM event=end — ending journey")
            LiveUpdateManager.endJourney(applicationContext)
            return
        }

        // A process kill wipes the in-memory singleton. Rebuild it from prefs first so an incremental
        // update isn't force-routed to startJourney with a null fallback — which would then fail to
        // parse on the missing required fields and silently drop the update.
        if (!LiveUpdateManager.isActive) {
            LiveUpdateManager.restoreFromPrefs(applicationContext, startServiceIfActive = false)
        }

        val isStart = event == "start" || !LiveUpdateManager.isActive
        val fallback = if (!isStart) LiveUpdateManager.stateFlow.value else null

        val state = runCatching { data.toFlightContentState(fallback) }
            .onFailure { log.e("Failed to parse FCM data: ${it.message}", it) }
            .getOrNull() ?: return

        // google.sent_time — stamped by FCM's servers, not by this handset. Handed to the SDK as the
        // trusted "now" so the DEPARTED countdown survives a device with auto-time switched off.
        // Zero when FCM did not supply one; the SDK then falls back to the device clock.
        val serverNowMs = message.sentTime

        val result = if (isStart) {
            LiveUpdateManager.startJourney(applicationContext, state, serverNowMs = serverNowMs)
        } else {
            LiveUpdateManager.updateJourney(applicationContext, state, serverNowMs = serverNowMs)
        }

        result.onFailure { error ->
            log.e("LiveUpdateManager rejected FCM event='$event' (${state.stage}): $error")
        }
    }

    override fun onNewToken(token: String) {
        log.i("New FCM registration token received")
        // TODO: send token to your backend so it can target this device
    }
}

private fun Map<String, String>.toFlightContentState(fallback: FlightContentState? = null): FlightContentState =
    FlightContentState(
        activityId = get("activityId")?.takeIf { it.isNotEmpty() } ?: fallback?.activityId ?: "",
        flightNumber = get("flightNumber")?.takeIf { it.isNotEmpty() } ?: fallback?.flightNumber?.takeIf { it.isNotEmpty() } ?: "--",
        flightDate = get("flightDate")?.takeIf { it.isNotEmpty() } ?: fallback?.flightDate ?: "",
        direction = get("direction")?.takeIf { it.isNotEmpty() }
                            ?.let { Direction.valueOf(it.uppercase()) }
                            ?: fallback?.direction
                            ?: Direction.valueOf(requireField("direction").uppercase()),
        carrier = get("carrier")?.takeIf { it.isNotEmpty() }
                            ?.let { Carrier.valueOf(it.uppercase()) }
                            ?: fallback?.carrier
                            ?: Carrier.valueOf(requireField("carrier").uppercase()),
        pnr = get("pnr")?.takeIf { it.isNotEmpty() } ?: fallback?.pnr ?: "",
        origin = get("origin")?.takeIf { it.isNotEmpty() } ?: fallback?.origin ?: requireField("origin"),
        destination = get("destination")?.takeIf { it.isNotEmpty() } ?: fallback?.destination ?: requireField("destination"),
        departureTime = get("departureTime")?.takeIf { it.isNotEmpty() }?.let { parseServerTime(it) }
                            ?: fallback?.departureTime
                            ?: parseServerTime(requireField("departureTime")),
        arrivalTime = get("arrivalTime")?.takeIf { it.isNotEmpty() }?.let { parseServerTime(it) }
                            ?: fallback?.arrivalTime
                            ?: parseServerTime(requireField("arrivalTime")),
        // Kept as the raw server string: departureTime stays airport-local for display, and the SDK
        // applies this offset only where it needs to compare against the device clock.
        departureTimezoneOffset = get("departureTimezoneOffset")?.takeIf { it.isNotEmpty() }
                            ?: fallback?.departureTimezoneOffset,
        terminal = get("terminal")?.takeIf { it.isNotEmpty() } ?: fallback?.terminal?.takeIf { it.isNotEmpty() } ?: "--",
        arrivalTerminal = get("arrivalTerminal")?.takeIf { it.isNotEmpty() } ?: fallback?.arrivalTerminal?.takeIf { it.isNotEmpty() } ?: "--",
        gate = get("gate")?.takeIf { it.isNotEmpty() } ?: fallback?.gate?.takeIf { it.isNotEmpty() } ?: "--",
        // No merge: an absent or blank baggageCarousel clears the belt (renders title only).
        baggageCarousel = get("baggageCarousel")?.takeIf { it.isNotBlank() } ?: "--",
        checkInCounters = get("checkInCounters")?.takeIf { it.isNotEmpty() } ?: fallback?.checkInCounters?.takeIf { it.isNotEmpty() } ?: "--",
        // Queue / waiting-times fields are CHECK_IN-only: merged from the fallback like the counters so an
        // incremental push that omits them keeps the last known load.
        checkInCounterNumbers = get("checkInCounterNumbers")?.takeIf { it.isNotBlank() } ?: fallback?.checkInCounterNumbers,
        checkInZone = get("checkInZone")?.takeIf { it.isNotBlank() } ?: fallback?.checkInZone,
        checkInQueueLoad = QueueLoad.fromString(get("checkInQueueLoad")) ?: fallback?.checkInQueueLoad,
        checkInQueueLoadText = get("checkInQueueLoadText")?.takeIf { it.isNotBlank() } ?: fallback?.checkInQueueLoadText,
        selfServiceQueueLoad = QueueLoad.fromString(get("selfServiceQueueLoad")) ?: fallback?.selfServiceQueueLoad,
        selfServiceQueueLoadText = get("selfServiceQueueLoadText")?.takeIf { it.isNotBlank() } ?: fallback?.selfServiceQueueLoadText,
        boardingZone = get("boardingZone")?.takeIf { it.isNotEmpty() } ?: fallback?.boardingZone?.takeIf { it.isNotEmpty() } ?: "--",
        stage = Stage.valueOf(requireField("stage").uppercase()),
        progressFraction = get("progressFraction")?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                            ?: fallback?.progressFraction
                            ?: 0.0,
        flightStatus = get("flightStatus")?.takeIf { it.isNotEmpty() } ?: fallback?.flightStatus?.takeIf { it.isNotEmpty() } ?: "--",
        flightStatusText = get("flightStatusText") ?: fallback?.flightStatusText ?: "",
        title = get("title") ?: fallback?.title ?: "",
        stageTitle = get("stageTitle") ?: fallback?.stageTitle ?: "",
        message = get("message") ?: fallback?.message ?: "",
        terminalTitle = get("terminalTitle") ?: fallback?.terminalTitle ?: "",
        baggageText = get("baggageText") ?: fallback?.baggageText ?: "",
        carryOnText = get("carryOnText") ?: fallback?.carryOnText ?: "",
        deskTitle = get("deskTitle") ?: fallback?.deskTitle ?: "",
        gateTitle = get("gateTitle") ?: fallback?.gateTitle ?: "",
        carouselTitle = get("carouselTitle") ?: fallback?.carouselTitle ?: "",
        dismissalDate = get("dismissalDate")?.takeIf { it.isNotEmpty() }?.let { parseInstant(it) }
                            ?: fallback?.dismissalDate,
        flightDuration = get("flightDuration")?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                            ?: fallback?.flightDuration,
        hasConnections = get("hasConnections")?.takeIf { it.isNotEmpty() }?.equals("TRUE", ignoreCase = true)
                            ?: fallback?.hasConnections
                            ?: false,
        language = get("language")?.uppercase()
                            ?.let { runCatching { AppLanguage.valueOf(it) }.getOrElse { AppLanguage.EN } }
                            ?: fallback?.language
                            ?: AppLanguage.EN
    )

private fun parseServerTime(value: String): Instant =
    LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .toInstant(ZoneOffset.UTC)

private fun parseInstant(value: String): Instant = Instant.parse(value)

private fun Map<String, String>.requireField(key: String): String =
    get(key)?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Missing required FCM field: '$key'")
