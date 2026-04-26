package com.elal.flightsdk.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
enum class Stage {
    CHECK_IN, GATE_READY, BOARDING, DEPARTED, LANDED, BAGGAGE, COMPLETED,
    CONNECTION_ERROR, GENERAL_ERROR
}

@Serializable
enum class Direction { DEPARTURE, ARRIVAL }

@Serializable
enum class Carrier { EL_AL, SUN_DOR }

@Serializable
data class FlightContentState(
    val activityId: String = "sim-123",
    val flightNumber: String,
    val flightDate: String,
    val direction: Direction,
    val carrier: Carrier,
    val origin: String,
    val destination: String,
    @Serializable(with = InstantSerializer::class) val departureTime: Instant,
    @Serializable(with = InstantSerializer::class) val arrivalTime: Instant,
    val terminal: String? = null,
    val gate: String? = null,
    val baggageCarousel: String? = null,
    val stage: Stage,
    val checkInCounters: String? = null,
    val progressFraction: Double = 0.0,
    val flightStatus: String? = null,
    val message: String = "",
    val language: String = "EN"
)
