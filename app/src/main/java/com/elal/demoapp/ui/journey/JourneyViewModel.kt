package com.elal.demoapp.ui.journey

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elal.demoapp.R
import com.elal.demoapp.debug.DebugNextStageReceiver // TODO: DEBUG - Remove before release
import com.elal.demoapp.ui.util.UiText
import com.elal.flightsdk.manager.LiveUpdateError
import com.elal.flightsdk.manager.LiveUpdateManager
import com.elal.flightsdk.manager.onFailure
import com.elal.flightsdk.manager.onSuccess
import com.elal.flightsdk.model.JourneyAttributes
import com.elal.flightsdk.model.JourneyContentState
import com.elal.flightsdk.model.JourneyStatus
import com.elal.flightsdk.sample.SampleFlightData
import com.elal.flightsdk.utils.orDash
import com.elal.flightsdk.utils.toFormattedTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

class JourneyViewModel(application: Application) : AndroidViewModel(application) {

    private val _statusText = MutableStateFlow<UiText>(UiText.StringResource(R.string.no_active_activity))
    val statusText: StateFlow<UiText> = _statusText.asStateFlow()

    private val _detailText = MutableStateFlow<UiText?>(null)
    val detailText: StateFlow<UiText?> = _detailText.asStateFlow()

    private val _isActivityActive = MutableStateFlow(false)
    val isActivityActive: StateFlow<Boolean> = _isActivityActive.asStateFlow()

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

    private var currentStateIndex = 0
    private var simulationJob: Job? = null
    private var arrivalWatchJob: Job? = null

    // TODO: DEBUG - Remove before release (along with all DebugNextStageReceiver usages)
    private val isDebuggable: Boolean
        get() = (getApplication<Application>().applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun startActivity(attributes: JourneyAttributes = SampleFlightData.journeyAttributes) {
        val sequence = SampleFlightData.journeySimulationSequence
        val state = sequence[0]
        if (isDebuggable) DebugNextStageReceiver.setup(getApplication()) // TODO: DEBUG - Remove before release
        LiveUpdateManager.startJourney(getApplication(), attributes, state)
            .onSuccess {
                _isActivityActive.value = true
                currentStateIndex = 0
                _statusText.value = UiText.StringResource(state.status.displayNameResId)
                _detailText.value = UiText.StringResource(
                    R.string.detail_start_flight,
                    attributes.flightNumber,
                    attributes.departureAirportCode,
                    attributes.arrivalAirportCode
                )
                _errorMessage.value = null
            }
            .onFailure { _errorMessage.value = it.toDisplayMessage() }
    }

    fun updateToNextState() {
        val sequence = SampleFlightData.journeySimulationSequence
        currentStateIndex = (currentStateIndex + 1).coerceAtMost(sequence.size - 1)
        val state = sequence[currentStateIndex]
        if (isDebuggable) DebugNextStageReceiver.syncIndex(currentStateIndex) // TODO: DEBUG - Remove before release
        LiveUpdateManager.updateJourney(getApplication(), state)
            .onSuccess {
                updateUI(state)
                manageArrivalWatch(state)
                _errorMessage.value = null
            }
            .onFailure { _errorMessage.value = it.toDisplayMessage() }
    }

    fun endActivity() {
        if (isDebuggable) DebugNextStageReceiver.teardown() // TODO: DEBUG - Remove before release
        stopArrivalWatch()
        LiveUpdateManager.endJourney(getApplication())
        _isActivityActive.value = false
        _isSimulating.value = false
        simulationJob?.cancel()
        simulationJob = null
        _statusText.value = UiText.StringResource(R.string.no_active_activity)
        _detailText.value = null
        _errorMessage.value = null
    }

    fun simulateJourney() {
        if (_isSimulating.value) return

        startActivity(SampleFlightData.journeyAttributesForSimulation)
        if (!_isActivityActive.value) return

        _isSimulating.value = true
        currentStateIndex = 0

        val sequence = SampleFlightData.journeySimulationSequence

        simulationJob = viewModelScope.launch {
            for (i in 1 until sequence.size) {
                delay(5_000L)
                currentStateIndex = i
                val state = sequence[i]
                LiveUpdateManager.updateJourney(getApplication(), state)
                    .onSuccess {
                        updateUI(state)
                        manageArrivalWatch(state)
                    }
                    .onFailure { _errorMessage.value = it.toDisplayMessage() }
            }
            delay(5_000L)
            endActivity()
        }
    }

    fun setConnectionError() {
        val state = JourneyContentState(status = JourneyStatus.CONNECTION_ERROR)
        if (!_isActivityActive.value) {
            LiveUpdateManager.startJourney(getApplication(), SampleFlightData.journeyAttributes, state)
                .onSuccess {
                    _isActivityActive.value = true
                    updateUI(state)
                    _errorMessage.value = null
                }
                .onFailure { _errorMessage.value = it.toDisplayMessage() }
        } else {
            LiveUpdateManager.updateJourney(getApplication(), state)
                .onSuccess {
                    updateUI(state)
                    _errorMessage.value = null
                }
                .onFailure { _errorMessage.value = it.toDisplayMessage() }
        }
    }

    fun setGeneralError() {
        val state = JourneyContentState(status = JourneyStatus.GENERAL_ERROR)
        if (!_isActivityActive.value) {
            LiveUpdateManager.startJourney(getApplication(), SampleFlightData.journeyAttributes, state)
                .onSuccess {
                    _isActivityActive.value = true
                    updateUI(state)
                    _errorMessage.value = null
                }
                .onFailure { _errorMessage.value = it.toDisplayMessage() }
        } else {
            LiveUpdateManager.updateJourney(getApplication(), state)
                .onSuccess {
                    updateUI(state)
                    _errorMessage.value = null
                }
                .onFailure { _errorMessage.value = it.toDisplayMessage() }
        }
    }

    private fun LiveUpdateError.toDisplayMessage(): UiText = when (this) {
        is LiveUpdateError.ActivityAlreadyRunning  -> UiText.StringResource(R.string.error_already_running)
        is LiveUpdateError.NoActiveActivity        -> UiText.StringResource(R.string.error_no_active)
        is LiveUpdateError.NotificationsNotEnabled -> UiText.StringResource(R.string.error_notifications_disabled)
        is LiveUpdateError.FailedToStart           -> UiText.StringResource(R.string.error_failed_to_start, cause.message ?: "")
    }

    /**
     * Auto-advances to the next state (ARRIVAL) once the estimated arrival time is reached.
     * Live progress animation is handled internally by [LiveUpdateManager].
     */
    private fun manageArrivalWatch(state: JourneyContentState) {
        if (state.status == JourneyStatus.ON_THE_WAY) {
            startArrivalWatch(state)
        } else {
            stopArrivalWatch()
        }
    }

    private fun startArrivalWatch(state: JourneyContentState) {
        stopArrivalWatch()
        val eta = state.estimatedArrivalTime ?: return
        arrivalWatchJob = viewModelScope.launch {
            while (isActive) {
                delay(ARRIVAL_CHECK_INTERVAL_MS)
                if (Instant.now() >= eta) {
                    updateToNextState()
                    break
                }
            }
        }
    }

    private fun stopArrivalWatch() {
        arrivalWatchJob?.cancel()
        arrivalWatchJob = null
    }

    private fun updateUI(state: JourneyContentState) {
        val attributes = SampleFlightData.journeyAttributes
        _statusText.value = UiText.StringResource(state.status.displayNameResId)
        _detailText.value = when (state.status) {
            JourneyStatus.CHECK_IN         -> UiText.StringResource(R.string.detail_check_in, state.checkInDesk.orDash())
            JourneyStatus.BOARDING_SOON    -> UiText.StringResource(R.string.detail_boarding_soon, state.gate.orDash())
            JourneyStatus.BOARDING_DELAYED -> UiText.StringResource(R.string.detail_boarding_delayed, state.gate.orDash())
            JourneyStatus.BOARDING         -> UiText.StringResource(R.string.detail_boarding, state.boardingZone.orDash())
            JourneyStatus.LAST_CALL        -> UiText.StringResource(R.string.detail_last_call, state.gate.orDash())
            JourneyStatus.TAKING_OFF       -> UiText.StringResource(R.string.detail_taking_off, state.takeoffTime.toFormattedTime())
            JourneyStatus.ON_THE_WAY       -> UiText.StringResource(R.string.detail_on_the_way, state.estimatedArrivalTime.toFormattedTime())
            JourneyStatus.ARRIVAL          ->
                if (attributes.arrivalAirportCode == "TLV")
                    UiText.StringResource(R.string.detail_arrival_baggage, state.terminal.orDash())
                else
                    UiText.StringResource(R.string.detail_arrival_welcome, attributes.arrivalCity)
            JourneyStatus.CONNECTION_ERROR -> UiText.StringResource(R.string.detail_connection_error)
            JourneyStatus.GENERAL_ERROR    -> UiText.StringResource(R.string.detail_general_error)
        }
    }

    companion object {
        private const val ARRIVAL_CHECK_INTERVAL_MS = 2_000L
    }
}
