package com.elal.flightsdk.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
enum class Stage {
    CHECK_IN, GATE_READY, BOARDING, LAST_CALL, DEPARTED, ON_THE_WAY, LANDED, BAGGAGE, COMPLETED,
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
    val activityId: String = "sim-123",
    val flightNumber: String = "",
    val flightDate: String = "",
    val direction: Direction = Direction.DEPARTURE,
    val carrier: Carrier = Carrier.EL_AL,
    val origin: String,
    val destination: String,
    @Serializable(with = InstantSerializer::class) val departureTime: Instant,
    @Serializable(with = InstantSerializer::class) val arrivalTime: Instant,
    val terminal: String? = null,
    val gate: String? = null,
    val baggageCarousel: String? = null,
    val stage: Stage,
    val checkInCounters: String? = null,
    val boardingZone: String? = null,
    val progressFraction: Double = 0.0,
    val flightStatus: String? = null,
    val message: String = "",
    val language: AppLanguage = AppLanguage.EN
)
