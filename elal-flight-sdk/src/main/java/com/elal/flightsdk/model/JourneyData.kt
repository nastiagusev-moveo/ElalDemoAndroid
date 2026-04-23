package com.elal.flightsdk.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Static attributes for a journey — set once when the Live Update is created.
 */
@Serializable
data class JourneyAttributes(
    val flightNumber: String,
    val airline: String = "El Al",
    val passengerName: String,
    val departureAirportCode: String,
    val departureCity: String,
    val arrivalAirportCode: String,
    val arrivalCity: String,
    @Serializable(with = InstantSerializer::class)
    val scheduledDepartureTime: Instant,
    @Serializable(with = InstantSerializer::class)
    val scheduledArrivalTime: Instant
)

/**
 * Dynamic content state for a journey — updated as the journey progresses.
 */
@Serializable
data class JourneyContentState(
    val status: JourneyStatus,
    val delayMinutes: Int = 0,

    // Common
    val gate: String? = null,
    val terminal: String? = null,
    val seat: String? = null,
    val boardingZone: String? = null,

    // Check-In
    val checkInDesk: String? = null,
    @Serializable(with = InstantSerializer::class)
    val checkInStartTime: Instant? = null,

    // Checked In / Boarding / Final Call
    @Serializable(with = InstantSerializer::class)
    val boardingTime: Instant? = null,
    @Serializable(with = InstantSerializer::class)
    val gateClosingTime: Instant? = null,

    // Taking Off
    @Serializable(with = InstantSerializer::class)
    val takeoffTime: Instant? = null,

    // On The Way
    @Serializable(with = InstantSerializer::class)
    val estimatedArrivalTime: Instant? = null,

    // Arrived / Welcome
    val baggageCarousel: String? = null
)
