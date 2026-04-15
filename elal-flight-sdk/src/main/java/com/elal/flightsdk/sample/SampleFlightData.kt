package com.elal.flightsdk.sample

import com.elal.flightsdk.model.JourneyAttributes
import com.elal.flightsdk.model.JourneyContentState
import com.elal.flightsdk.model.JourneyStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

object SampleFlightData {

    val journeyAttributes: JourneyAttributes
        get() {
            val now = Instant.now()
            return JourneyAttributes(
                flightNumber = "LY 317",
                airline = "El Al",
                passengerName = "Israel Israeli",
                departureAirportCode = "TLV",
                departureCity = "Tel Aviv",
                arrivalAirportCode = "JFK",
                arrivalCity = "New York",
                scheduledDepartureTime = now.plus(2, ChronoUnit.HOURS),
                scheduledArrivalTime = now.plus(13, ChronoUnit.HOURS)
            )
        }

    val journeySimulationSequence: List<JourneyContentState>
        get() {
            val now = Instant.now()

            val checkIn = JourneyContentState(
                status = JourneyStatus.CHECK_IN,
                checkInDesk = "D78-99",
                checkInStartTime = now.plus(2, ChronoUnit.HOURS)
            )

            val boardingSoon = JourneyContentState(
                status = JourneyStatus.BOARDING_SOON,
                gate = "D3",
                terminal = "3",
                seat = "12A",
                boardingZone = "Zone 1",
                boardingTime = now.plus(45, ChronoUnit.MINUTES),
                gateClosingTime = now.plus(1, ChronoUnit.HOURS)
            )

            val boardingDelayed = JourneyContentState(
                status = JourneyStatus.BOARDING_DELAYED,
                delayMinutes = 25,
                gate = "D3",
                terminal = "3",
                seat = "12A",
                boardingZone = "Zone 1",
                boardingTime = now.plus(70, ChronoUnit.MINUTES),
                gateClosingTime = now.plus(85, ChronoUnit.MINUTES)
            )

            val boarding = JourneyContentState(
                status = JourneyStatus.BOARDING,
                gate = "D3",
                terminal = "3",
                seat = "12A",
                boardingZone = "Zone 1",
                boardingTime = now,
                gateClosingTime = now.plus(20, ChronoUnit.MINUTES)
            )

            val lastCall = JourneyContentState(
                status = JourneyStatus.LAST_CALL,
                gate = "D3",
                terminal = "3",
                seat = "12A",
                boardingZone = "Zone 1",
                gateClosingTime = now.plus(5, ChronoUnit.MINUTES)
            )

            val takingOff = JourneyContentState(
                status = JourneyStatus.TAKING_OFF,
                takeoffTime = now.plus(7, ChronoUnit.MINUTES)
            )

            val onTheWay = JourneyContentState(
                status = JourneyStatus.ON_THE_WAY,
                estimatedArrivalTime = now.plus(11, ChronoUnit.HOURS)
            )

            val arrival = JourneyContentState(
                status = JourneyStatus.ARRIVAL,
                terminal = "1",
                estimatedArrivalTime = now,
                baggageCarousel = "25"
            )

            return listOf(
                checkIn, boardingSoon, boardingDelayed, boarding, lastCall, takingOff,
                onTheWay, arrival
            )
        }
}
