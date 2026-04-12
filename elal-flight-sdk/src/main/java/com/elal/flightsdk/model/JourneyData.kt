package com.elal.flightsdk.model

import java.time.Instant

/**
 * Static attributes for a journey — set once when the Live Update is created.
 */
data class JourneyAttributes(
    val flightNumber: String,
    val airline: String = "El Al",
    val passengerName: String,
    val departureAirportCode: String,
    val departureCity: String,
    val arrivalAirportCode: String,
    val arrivalCity: String,
    val scheduledDepartureTime: Instant,
    val scheduledArrivalTime: Instant
)

/**
 * Dynamic content state for a journey — updated as the journey progresses.
 */
data class JourneyContentState(
    val status: JourneyStatus,
    val delayMinutes: Int = 0,

    // Common
    val gate: String? = null,
    val terminal: String? = null,
    val seat: String? = null,
    val boardingGroup: String? = null,

    // Check-In
    val checkInDesk: String? = null,
    val checkInStartTime: Instant? = null,

    // Checked In / Boarding / Final Call
    val boardingTime: Instant? = null,
    val gateClosingTime: Instant? = null,

    // Taking Off
    val takeoffTime: Instant? = null,

    // On The Way
    val estimatedArrivalTime: Instant? = null,

    // Arrived / Welcome
    val baggageCarousel: String? = null
)
