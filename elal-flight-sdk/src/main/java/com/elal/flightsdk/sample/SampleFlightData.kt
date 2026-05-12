package com.elal.flightsdk.sample

import com.elal.flightsdk.model.AppLanguage
import com.elal.flightsdk.model.Carrier
import com.elal.flightsdk.model.Direction
import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.model.Stage
import com.elal.flightsdk.utils.toFormattedTime
import com.elal.flightsdk.utils.wallClockNow
import java.time.temporal.ChronoUnit

object SampleFlightData {

    private val departureBase: FlightContentState
        get() {
            val now = wallClockNow()
            return FlightContentState(
                flightNumber = "LY 317",
                flightDate = "2026-04-26",
                direction = Direction.DEPARTURE,
                carrier = Carrier.EL_AL,
                origin = "TLV",
                destination = "JFK",
                departureTime = now,
                arrivalTime = now.plus(3, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES),
                stage = Stage.CHECK_IN,
                language = AppLanguage.HE
            )
        }

    private val arrivalBase: FlightContentState
        get() {
            val now = wallClockNow()
            return FlightContentState(
                flightNumber = "LY 316",
                flightDate = "2026-04-26",
                direction = Direction.ARRIVAL,
                carrier = Carrier.EL_AL,
                origin = "JFK",
                destination = "TLV",
                departureTime = now,
                arrivalTime = now.plus(3, ChronoUnit.MINUTES),
                stage = Stage.CHECK_IN,
                language = AppLanguage.HE
            )
        }

    // DEPARTURE: TLV → JFK — no BAGGAGE stage
    val journeySimulationSequence: List<FlightContentState>
        get() {
            val base = departureBase
            val depTime = base.departureTime.toFormattedTime()
            val arrTime = base.arrivalTime.toFormattedTime()
            return listOf(
                base.copy(stage = Stage.CHECK_IN,   deskTitle = "דלפקי צ'ק-אין:",                   checkInCounters = "D78-99"),
                base.copy(stage = Stage.GATE_READY, gateTitle = "שער:",                             gate = "D3", terminal = "3"),
                base.copy(stage = Stage.BOARDING,   title = "האזור שלך:",                            gate = "D3", terminal = "3", boardingZone = "Zone 1"),
                base.copy(stage = Stage.LAST_CALL,  title = "לא ניתן לעלות לאחר סגירת השער:",        gate = "D3", terminal = "3"),
                base.copy(stage = Stage.DEPARTED,   title = "המראה ב-$depTime"),
                base.copy(stage = Stage.IN_FLIGHT,  title = "נחיתה ב-$arrTime"),
                base.copy(stage = Stage.LANDED,     title = "ברוכים הבאים ל-${base.destination}",   terminal = "1"),
                base.copy(stage = Stage.COMPLETED)
            )
        }

    // ARRIVAL: JFK → TLV — includes BAGGAGE before COMPLETED
    val arrivalSimulationSequence: List<FlightContentState>
        get() {
            val base = arrivalBase
            val depTime = base.departureTime.toFormattedTime()
            val arrTime = base.arrivalTime.toFormattedTime()
            return listOf(
                base.copy(stage = Stage.CHECK_IN,   deskTitle = "דלפקי צ'ק-אין:",                   checkInCounters = "B12-18"),
                base.copy(stage = Stage.GATE_READY, gateTitle = "שער:",                             gate = "B7", terminal = "4"),
                base.copy(stage = Stage.BOARDING,   title = "האזור שלך:",                            gate = "B7", terminal = "4", boardingZone = "Zone 1"),
                base.copy(stage = Stage.LAST_CALL,  title = "לא ניתן לעלות לאחר סגירת השער:",        gate = "B7", terminal = "4"),
                base.copy(stage = Stage.DEPARTED,   title = "המראה ב-$depTime"),
                base.copy(stage = Stage.IN_FLIGHT,  title = "נחיתה ב-$arrTime"),
                base.copy(stage = Stage.LANDED,     title = "כבודה תגיע לקרוסלה:",                   terminal = "3", baggageCarousel = "3"),
                base.copy(stage = Stage.BAGGAGE,    title = "כבודה תגיע לקרוסלה:",                   baggageCarousel = "12"),
                base.copy(stage = Stage.COMPLETED)
            )
        }
}
