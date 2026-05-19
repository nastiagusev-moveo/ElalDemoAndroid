package com.elal.demoapp.firebase

import com.elal.flightsdk.manager.LiveUpdateManager
import com.elal.flightsdk.manager.onFailure
import com.elal.flightsdk.model.AppLanguage
import com.elal.flightsdk.model.Carrier
import com.elal.flightsdk.model.Direction
import com.elal.flightsdk.model.FlightContentState
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

        val isStart = event == "start" || !LiveUpdateManager.isActive
        val fallback = if (!isStart) LiveUpdateManager.stateFlow.value else null

        val state = runCatching { data.toFlightContentState(fallback) }
            .onFailure { log.e("Failed to parse FCM data: ${it.message}", it) }
            .getOrNull() ?: return

        val result = if (isStart) {
            LiveUpdateManager.startJourney(applicationContext, state)
        } else {
            LiveUpdateManager.updateJourney(applicationContext, state)
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
        terminal = get("terminal")?.takeIf { it.isNotEmpty() } ?: fallback?.terminal?.takeIf { it.isNotEmpty() } ?: "--",
        arrivalTerminal = get("arrivalTerminal")?.takeIf { it.isNotEmpty() } ?: fallback?.arrivalTerminal?.takeIf { it.isNotEmpty() } ?: "--",
        gate = get("gate")?.takeIf { it.isNotEmpty() } ?: fallback?.gate?.takeIf { it.isNotEmpty() } ?: "--",
        baggageCarousel = get("baggageCarousel")?.takeIf { it.isNotEmpty() } ?: fallback?.baggageCarousel?.takeIf { it.isNotEmpty() } ?: "--",
        checkInCounters = get("checkInCounters")?.takeIf { it.isNotEmpty() } ?: fallback?.checkInCounters?.takeIf { it.isNotEmpty() } ?: "--",
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
