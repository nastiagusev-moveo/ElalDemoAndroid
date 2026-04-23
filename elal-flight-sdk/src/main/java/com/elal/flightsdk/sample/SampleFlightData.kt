package com.elal.flightsdk.sample

import com.elal.flightsdk.model.JourneyAttributes
import com.elal.flightsdk.model.JourneyContentState
import com.elal.flightsdk.model.JourneyStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

object SampleFlightData {

    val journeyAttributes: JourneyAttributes
        get() = buildAttributes()

    val journeyAttributesForSimulation: JourneyAttributes
        get() = buildAttributes()

    private fun buildAttributes(): JourneyAttributes {
        val departure = Instant.now()
        return JourneyAttributes(
            flightNumber = "LY 317",
            airline = "El Al",
            passengerName = "Israel Israeli",
            departureAirportCode = "TLV",
            departureCity = "Tel Aviv",
            arrivalAirportCode = "JFK",
            arrivalCity = "New York",
            scheduledDepartureTime = departure,
            scheduledArrivalTime = departure.plus(2, ChronoUnit.MINUTES)
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
                estimatedArrivalTime = now
                    .plus(5, ChronoUnit.MINUTES)
                    .plus(30, ChronoUnit.SECONDS)  // to get clean 2 minutes for the simulation plus (6 stages each stage 5 sec = 30 sec)
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
