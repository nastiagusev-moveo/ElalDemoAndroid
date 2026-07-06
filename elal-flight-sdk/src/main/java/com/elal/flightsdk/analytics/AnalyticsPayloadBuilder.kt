package com.elal.flightsdk.analytics

import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.model.Stage

/**
 * Builds the parameter maps that the SDK hands to [FlightAnalyticsListener].
 *
 * Field names match the spec in `analyticsliveactivity.docx` so the host app can
 * forward them verbatim to its analytics framework.
 */
internal object AnalyticsPayloadBuilder {

    private const val OS_PLATFORM = "android"

    fun activated(state: FlightContentState, triggerSource: String): Map<String, Any?> = mapOf(
        "timestamp" to System.currentTimeMillis(),
        "os_platform" to OS_PLATFORM,
        "flight_number" to state.flightNumber,
        "airline" to state.carrier.name,
        "pnr" to state.pnr,
        "trigger_source" to triggerSource,
    )

    fun clicked(state: FlightContentState, clickSource: String): Map<String, Any?> = mapOf(
        "timestamp" to System.currentTimeMillis(),
        "click_source" to clickSource,
        "os_platform" to OS_PLATFORM,
        "flight_number" to state.flightNumber,
        "airline" to state.carrier.name,
        "pnr" to state.pnr,
        "current_stage" to stageLabel(state.stage),
        "flight_status" to state.flightStatus,
        "main_message" to mainMessage(state),
        "secondary_message" to secondaryMessage(state),
        "dynamic_data_displayed" to dynamicData(state),
        "is_fallback_state" to isFallback(state),
    )

    private fun stageLabel(stage: Stage): String = when (stage) {
        Stage.CHECK_IN -> "Check-in open"
        Stage.GATE_READY -> "Gate ready"
        Stage.BOARDING_PENDING -> "Boarding pending"
        Stage.BOARDING -> "Boarding starts"
        Stage.LAST_CALL -> "Last call"
        Stage.DEPARTED -> "Flight departs"
        Stage.LANDING_SOON -> "Landing soon"
        Stage.LANDED -> "Arrival"
        Stage.BAGGAGE -> "Carrousel"
        Stage.COMPLETED -> "Completed"
        Stage.CONNECTION_ERROR -> "Connection error"
        Stage.GENERAL_ERROR -> "General error"
    }

    private fun mainMessage(state: FlightContentState): String =
        state.message.ifBlank { state.title }

    private fun secondaryMessage(state: FlightContentState): String =
        if (state.message.isNotBlank()) state.title else state.stageTitle

    private fun dynamicData(state: FlightContentState): String? = when (state.stage) {
        Stage.CHECK_IN -> state.checkInCounters?.takeIf { it.isNotBlank() && it != "--" }?.let { "Desk: $it" }
        Stage.GATE_READY, Stage.BOARDING_PENDING, Stage.LAST_CALL -> state.gate?.takeIf { it.isNotBlank() }?.let { "Gate: $it" }
        Stage.BOARDING -> state.boardingZone?.takeIf { it.isNotBlank() }?.let { "Zone: $it" }
            ?: state.gate?.takeIf { it.isNotBlank() }?.let { "Gate: $it" }
        Stage.LANDED, Stage.BAGGAGE -> state.baggageCarousel
            ?.takeIf { it.isNotBlank() && it != "--" }
            ?.let { "Carousel: $it" }
        else -> null
    }

    private fun isFallback(state: FlightContentState): Boolean =
        state.stage == Stage.CONNECTION_ERROR || state.stage == Stage.GENERAL_ERROR
}
