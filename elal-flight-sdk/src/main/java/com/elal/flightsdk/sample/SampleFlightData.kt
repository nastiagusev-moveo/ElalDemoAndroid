package com.elal.flightsdk.sample

import com.elal.flightsdk.model.Carrier
import com.elal.flightsdk.model.Direction
import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.model.Stage
import java.time.Instant
import java.time.temporal.ChronoUnit

object SampleFlightData {

    private val departureBase: FlightContentState
        get() {
            val now = Instant.now()
            return FlightContentState(
                flightNumber = "LY 317",
                flightDate = "2026-04-26",
                direction = Direction.DEPARTURE,
                carrier = Carrier.EL_AL,
                origin = "TLV",
                destination = "JFK",
                departureTime = now,
                arrivalTime = now.plus(5, ChronoUnit.MINUTES).plus(30, ChronoUnit.SECONDS),
                stage = Stage.CHECK_IN
            )
        }

    private val arrivalBase: FlightContentState
        get() {
            val now = Instant.now()
            return FlightContentState(
                flightNumber = "LY 316",
                flightDate = "2026-04-26",
                direction = Direction.ARRIVAL,
                carrier = Carrier.EL_AL,
                origin = "JFK",
                destination = "TLV",
                departureTime = now,
                arrivalTime = now.plus(5, ChronoUnit.MINUTES).plus(30, ChronoUnit.SECONDS),
                stage = Stage.CHECK_IN
            )
        }

    // DEPARTURE: TLV → JFK — no BAGGAGE stage
    val journeySimulationSequence: List<FlightContentState>
        get() {
            val base = departureBase
            return listOf(
                base.copy(stage = Stage.CHECK_IN,   checkInCounters = "D78-99"),
                base.copy(stage = Stage.GATE_READY,  gate = "D3", terminal = "3"),
                base.copy(stage = Stage.BOARDING,    gate = "D3", terminal = "3"),
                base.copy(stage = Stage.LAST_CALL,   gate = "D3", terminal = "3"),
                base.copy(stage = Stage.TAKING_OFF),
                base.copy(stage = Stage.DEPARTED),
                base.copy(stage = Stage.LANDED,      terminal = "1"),
                base.copy(stage = Stage.COMPLETED)
            )
        }

    // ARRIVAL: JFK → TLV — includes BAGGAGE before COMPLETED
    val arrivalSimulationSequence: List<FlightContentState>
        get() {
            val base = arrivalBase
            return listOf(
                base.copy(stage = Stage.CHECK_IN,   checkInCounters = "B12-18"),
                base.copy(stage = Stage.GATE_READY,  gate = "B7", terminal = "4"),
                base.copy(stage = Stage.BOARDING,    gate = "B7", terminal = "4"),
                base.copy(stage = Stage.LAST_CALL,   gate = "B7", terminal = "4"),
                base.copy(stage = Stage.TAKING_OFF),
                base.copy(stage = Stage.DEPARTED),
                base.copy(stage = Stage.LANDED,      terminal = "3"),
                base.copy(stage = Stage.BAGGAGE,     baggageCarousel = "12"),
                base.copy(stage = Stage.COMPLETED)
            )
        }
}
