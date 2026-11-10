package com.elal.flightsdk.sample

import com.elal.flightsdk.model.AppLanguage
import com.elal.flightsdk.model.Carrier
import com.elal.flightsdk.model.Direction
import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.model.QueueLoad
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
                arrivalTime = now.plus(5, ChronoUnit.MINUTES),
                stage = Stage.CHECK_IN,
                language = AppLanguage.HE
            )
        }

    // DEPARTURE: TLV → JFK — no BAGGAGE stage
    val journeySimulationSequence: List<FlightContentState>
        get() {
            val base = departureBase
            val arrTime = base.arrivalTime.toFormattedTime()
            return listOf(
                base.copy(stage = Stage.CHECK_IN,   deskTitle = "דלפקי צ'ק-אין:",                   checkInCounters = "D78-99",
                    title = "דלפקי צ'ק-אין: D78-99", message = "שירות עצמי: אזור D",
                    checkInCounterNumbers = "78-99", checkInZone = "אזור D",
                    checkInQueueLoad = QueueLoad.MEDIUM, checkInQueueLoadText = "עומס בינוני",
                    selfServiceQueueLoad = QueueLoad.LOW, selfServiceQueueLoadText = "עומס נמוך"),
                base.copy(stage = Stage.GATE_READY, gateTitle = "שער:",                             gate = "D3", terminal = "3"),
                base.copy(stage = Stage.BOARDING_PENDING, message = "ההעלייה למטוס תחל בקרוב", gateTitle = "שער:", gate = "D3", terminal = "3"),
                base.copy(stage = Stage.BOARDING,   title = "האזור שלך:",                            gate = "D3", terminal = "3", boardingZone = "Zone 1"),
                base.copy(stage = Stage.LAST_CALL,  title = "לא ניתן לעלות לאחר סגירת השער:",        gate = "D3", terminal = "3"),
                base.copy(stage = Stage.DEPARTURE_PENDING, stageTitle = "יוצאת בקרוב", message = "זמן טיסה משוער", flightDuration = 210),
                base.copy(stage = Stage.DEPARTED,   title = "נחיתה ב-$arrTime"),
                base.copy(stage = Stage.LANDING_SOON, message = "נוחתים בקרוב"),
                base.copy(stage = Stage.LANDED,     title = "ברוכים הבאים ל-${base.destination}",   terminal = "1"),
                base.copy(stage = Stage.COMPLETED)
            )
        }

    // ARRIVAL: JFK → TLV — includes BAGGAGE before COMPLETED
    val arrivalSimulationSequence: List<FlightContentState>
        get() {
            val base = arrivalBase
            val arrTime = base.arrivalTime.toFormattedTime()
            return listOf(
                base.copy(stage = Stage.CHECK_IN,   deskTitle = "דלפקי צ'ק-אין:",                   checkInCounters = "B12-18",
                    title = "דלפקי צ'ק-אין: B12-18", message = "שירות עצמי: אזור B",
                    checkInCounterNumbers = "12-18", checkInZone = "אזור B",
                    checkInQueueLoad = QueueLoad.HIGH, checkInQueueLoadText = "עומס גבוה",
                    selfServiceQueueLoad = QueueLoad.LOW, selfServiceQueueLoadText = "עומס נמוך"),
                base.copy(stage = Stage.GATE_READY, gateTitle = "שער:",                             gate = "B7", terminal = "4"),
                base.copy(stage = Stage.BOARDING_PENDING, message = "ההעלייה למטוס תחל בקרוב", gateTitle = "שער:", gate = "B7", terminal = "4"),
                base.copy(stage = Stage.BOARDING,   title = "האזור שלך:",                            gate = "B7", terminal = "4", boardingZone = "Zone 1"),
                base.copy(stage = Stage.LAST_CALL,  title = "לא ניתן לעלות לאחר סגירת השער:",        gate = "B7", terminal = "4"),
                base.copy(stage = Stage.DEPARTURE_PENDING, stageTitle = "יוצאת בקרוב", message = "זמן טיסה משוער", flightDuration = 210),
                base.copy(stage = Stage.DEPARTED,   title = "נחיתה ב-$arrTime"),
                base.copy(stage = Stage.LANDING_SOON, message = "נוחתים בקרוב"),
                base.copy(stage = Stage.LANDED,     title = "כבודה תגיע לקרוסלה:",                   terminal = "3", baggageCarousel = "3"),
                base.copy(stage = Stage.BAGGAGE,    title = "כבודה תגיע לקרוסלה:",                   baggageCarousel = "12"),
                base.copy(stage = Stage.COMPLETED)
            )
        }
}
