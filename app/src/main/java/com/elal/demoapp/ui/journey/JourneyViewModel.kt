package com.elal.demoapp.ui.journey

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elal.demoapp.debug.DebugNextStageReceiver // TODO: DEBUG - Remove before release
import com.elal.flightsdk.manager.LiveUpdateError
import com.elal.flightsdk.manager.LiveUpdateManager
import com.elal.flightsdk.manager.onFailure
import com.elal.flightsdk.manager.onSuccess
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

    private val _statusText = MutableStateFlow("No Active Activity")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _detailText = MutableStateFlow<String?>(null)
    val detailText: StateFlow<String?> = _detailText.asStateFlow()

    private val _isActivityActive = MutableStateFlow(false)
    val isActivityActive: StateFlow<Boolean> = _isActivityActive.asStateFlow()

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var currentStateIndex = 0
    private var simulationJob: Job? = null
    private var arrivalWatchJob: Job? = null

    // TODO: DEBUG - Remove before release (along with all DebugNextStageReceiver usages)
    private val isDebuggable: Boolean
        get() = (getApplication<Application>().applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun startActivity() {
        val attributes = SampleFlightData.journeyAttributes
        val sequence = SampleFlightData.journeySimulationSequence
        val state = sequence[0]
        if (isDebuggable) DebugNextStageReceiver.setup(getApplication()) // TODO: DEBUG - Remove before release
        LiveUpdateManager.startJourney(getApplication(), attributes, state)
            .onSuccess {
                _isActivityActive.value = true
                currentStateIndex = 0
                _statusText.value = state.status.displayName
                _detailText.value = "Flight LY 001 · TLV → JFK"
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
        _statusText.value = "No Active Activity"
        _detailText.value = null
        _errorMessage.value = null
    }

    fun simulateJourney() {
        if (_isSimulating.value) return

        startActivity()
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

    private fun LiveUpdateError.toDisplayMessage(): String = when (this) {
        is LiveUpdateError.ActivityAlreadyRunning -> "A live update is already running"
        is LiveUpdateError.NoActiveActivity -> "No active live update to modify"
        is LiveUpdateError.NotificationsNotEnabled -> "Notifications are not enabled"
        is LiveUpdateError.FailedToStart -> "Failed to start: ${cause.message}"
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
        _statusText.value = state.status.displayName
        _detailText.value = when (state.status) {
            JourneyStatus.CHECK_IN         -> "Check-in desks: ${state.checkInDesk.orDash()}"
            JourneyStatus.BOARDING_SOON    -> "Gate: ${state.gate.orDash()}"
            JourneyStatus.BOARDING_DELAYED -> "Delayed, Gate: ${state.gate.orDash()}"
            JourneyStatus.BOARDING         -> "Your zone: ${state.boardingZone.orDash()}"
            JourneyStatus.LAST_CALL        -> "No boarding after the gate closes: ${state.gate.orDash()}"
            JourneyStatus.TAKING_OFF       -> "Taking off at ${state.takeoffTime.toFormattedTime()}"
            JourneyStatus.ON_THE_WAY       -> "Landing at ${state.estimatedArrivalTime.toFormattedTime()}"
            JourneyStatus.ARRIVAL          ->
                if (attributes.arrivalAirportCode == "TLV") "Baggage claim will be at carousel: ${state.terminal.orDash()}"
                else "Welcome to ${attributes.arrivalCity}"
            JourneyStatus.CONNECTION_ERROR -> "You seem to be offline. Check your connection."
            JourneyStatus.GENERAL_ERROR    -> "Error has occurred"
        }
    }

    companion object {
        private const val ARRIVAL_CHECK_INTERVAL_MS = 2_000L
    }
}
