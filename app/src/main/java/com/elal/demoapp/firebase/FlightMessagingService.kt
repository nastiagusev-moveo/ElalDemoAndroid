package com.elal.demoapp.firebase

import com.elal.flightsdk.manager.LiveUpdateManager
import com.elal.flightsdk.manager.onFailure
import com.elal.flightsdk.model.Carrier
import com.elal.flightsdk.model.Direction
import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.model.Stage
import com.elal.flightsdk.utils.Logger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.time.Instant

/**
 * Receives FCM data messages and drives the live-update notification.
 *
 * Expected flat data fields:
 *   event, activityId, flightNumber, flightDate, direction, carrier,
 *   origin, destination, departureTime, arrivalTime, terminal, gate,
 *   baggageCarousel, checkInCounters, stage, message, language
 *
 * Called even when the app is killed (FCM wakes the process).
 */
class FlightMessagingService : FirebaseMessagingService() {

    private val log = Logger("FlightMessagingService")

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) return

        val state = runCatching { data.toFlightContentState() }
            .onFailure { log.e("Failed to parse FCM data: ${it.message}", it) }
            .getOrNull() ?: return

        LiveUpdateManager.ensureChannel(applicationContext)

        val result = if (LiveUpdateManager.isActive) {
            LiveUpdateManager.updateJourney(applicationContext, state)
        } else {
            LiveUpdateManager.startJourney(applicationContext, state)
        }

        result.onFailure { error ->
            log.e("LiveUpdateManager rejected FCM state (${state.stage}): $error")
        }
    }

    override fun onNewToken(token: String) {
        log.i("New FCM registration token received")
        // TODO: send token to your backend so it can target this device
    }
}

private fun Map<String, String>.toFlightContentState(): FlightContentState =
    FlightContentState(
        activityId      = get("activityId") ?: "",
        flightNumber    = get("flightNumber") ?: "",
        flightDate      = get("flightDate") ?: "",
        direction       = Direction.valueOf(requireField("direction").uppercase()),
        carrier         = Carrier.valueOf(requireField("carrier").uppercase()),
        origin          = requireField("origin"),
        destination     = requireField("destination"),
        departureTime   = Instant.parse(requireField("departureTime")),
        arrivalTime     = Instant.parse(requireField("arrivalTime")),
        terminal        = get("terminal")?.takeIf { it.isNotEmpty() },
        gate            = get("gate")?.takeIf { it.isNotEmpty() },
        baggageCarousel = get("baggageCarousel")?.takeIf { it.isNotEmpty() },
        checkInCounters = get("checkInCounters")?.takeIf { it.isNotEmpty() },
        stage           = Stage.valueOf(requireField("stage").uppercase()),
        message         = get("message") ?: "",
        language        = get("language") ?: "EN"
    )

private fun Map<String, String>.requireField(key: String): String =
    get(key)?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Missing required FCM field: '$key'")
