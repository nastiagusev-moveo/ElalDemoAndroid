package com.elal.flightsdk.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
enum class Stage {
    CHECK_IN, GATE_READY, BOARDING_PENDING, BOARDING, LAST_CALL, DEPARTURE_PENDING, DEPARTED, LANDING_SOON,
    LANDED, BAGGAGE, COMPLETED,
    CONNECTION_ERROR, GENERAL_ERROR
}

@Serializable
enum class Direction { DEPARTURE, ARRIVAL }

@Serializable
enum class Carrier { EL_AL, SUN_DOR }

@Serializable
enum class AppLanguage { EN, HE }

@Serializable
data class FlightContentState(
    val activityId: String = "",
    val flightNumber: String = "",
    val flightDate: String = "",
    val direction: Direction,
    val carrier: Carrier,
    val pnr: String = "",
    val origin: String,
    val destination: String,
    @Serializable(with = InstantSerializer::class) val departureTime: Instant,
    @Serializable(with = InstantSerializer::class) val arrivalTime: Instant,
    /** UTC offset of [departureTime]'s airport, e.g. "-04". Needed to compare it against the device clock. */
    val departureTimezoneOffset: String? = null,
    val terminal: String? = null,
    val arrivalTerminal: String? = null,
    val gate: String? = null,
    val baggageCarousel: String? = null,
    val stage: Stage,
    val checkInCounters: String? = "--",
    val boardingZone: String? = null,
    val progressFraction: Double = 0.0,
    val flightStatus: String? = null,
    val flightStatusText: String = "",
    val title: String = "",
    val stageTitle: String = "",
    val message: String = "",
    val terminalTitle: String = "",
    val baggageText: String = "",
    val carryOnText: String = "",
    val deskTitle: String = "",
    val gateTitle: String = "",
    val carouselTitle: String = "",
    @Serializable(with = InstantSerializer::class) val dismissalDate: Instant? = null,
    /** Total flight time in **minutes**, as sent by the server. Drives the DEPARTED countdown verbatim. */
    val flightDuration: Int? = null,
    val hasConnections: Boolean = false,
    val language: AppLanguage
)
