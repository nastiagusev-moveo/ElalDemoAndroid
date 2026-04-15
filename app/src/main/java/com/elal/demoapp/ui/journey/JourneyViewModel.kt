package com.elal.demoapp.ui.journey

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elal.demoapp.debug.DebugNextStageReceiver // TODO: DEBUG - Remove before release
import com.elal.flightsdk.manager.LiveUpdateManager
import com.elal.flightsdk.model.JourneyContentState
import com.elal.flightsdk.model.JourneyStatus
import com.elal.flightsdk.sample.SampleFlightData
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

    fun startActivity() {
        try {
            val attributes = SampleFlightData.journeyAttributes
            val sequence = SampleFlightData.journeySimulationSequence
            val state = sequence[0]
            DebugNextStageReceiver.setup(getApplication()) // TODO: DEBUG - Remove before release
            LiveUpdateManager.startJourney(getApplication(), attributes, state)
            _isActivityActive.value = true
            currentStateIndex = 0
            _statusText.value = state.status.displayName
            _detailText.value = "Flight LY 001 · TLV → JFK"
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Failed to start: ${e.message}"
        }
    }

    fun updateToNextState() {
        val sequence = SampleFlightData.journeySimulationSequence
        currentStateIndex = (currentStateIndex + 1).coerceAtMost(sequence.size - 1)
        val state = sequence[currentStateIndex]

        try {
            DebugNextStageReceiver.syncIndex(currentStateIndex) // TODO: DEBUG - Remove before release
            LiveUpdateManager.updateJourney(getApplication(), state)
            updateUI(state)
            manageArrivalWatch(state)
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Failed to update: ${e.message}"
        }
    }

    fun endActivity() {
        try {
            DebugNextStageReceiver.teardown() // TODO: DEBUG - Remove before release
            stopArrivalWatch()
            LiveUpdateManager.endJourney(getApplication())
            _isActivityActive.value = false
            _isSimulating.value = false
            simulationJob?.cancel()
            simulationJob = null
            _statusText.value = "No Active Activity"
            _detailText.value = null
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Failed to end: ${e.message}"
        }
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
                try {
                    LiveUpdateManager.updateJourney(getApplication(), state)
                    updateUI(state)
                    manageArrivalWatch(state)
                } catch (e: Exception) {
                    _errorMessage.value = "Simulation error: ${e.message}"
                }
            }
            // After final state, wait a moment then end
            delay(5_000L)
            endActivity()
        }
    }

    fun setConnectionError() {
        val state = JourneyContentState(status = JourneyStatus.CONNECTION_ERROR)
        try {
            if (!_isActivityActive.value) {
                val attributes = SampleFlightData.journeyAttributes
                LiveUpdateManager.startJourney(getApplication(), attributes, state)
                _isActivityActive.value = true
            } else {
                LiveUpdateManager.updateJourney(getApplication(), state)
            }
            updateUI(state)
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Failed to set connection error: ${e.message}"
        }
    }

    fun setGeneralError() {
        val state = JourneyContentState(status = JourneyStatus.GENERAL_ERROR)
        try {
            if (!_isActivityActive.value) {
                val attributes = SampleFlightData.journeyAttributes
                LiveUpdateManager.startJourney(getApplication(), attributes, state)
                _isActivityActive.value = true
            } else {
                LiveUpdateManager.updateJourney(getApplication(), state)
            }
            updateUI(state)
            _errorMessage.value = null
        } catch (e: Exception) {
            _errorMessage.value = "Failed to set error: ${e.message}"
        }
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
        _statusText.value = state.status.displayName
        _detailText.value = when (state.status) {
            JourneyStatus.CHECK_IN ->
                "Desk: ${state.checkInDesk ?: "--"}"
            JourneyStatus.BOARDING_SOON ->
                "Gate ${state.gate ?: "--"} · Seat ${state.seat ?: "--"} · Boarding soon"
            JourneyStatus.BOARDING_DELAYED ->
                "Gate ${state.gate ?: "--"} · Boarding delayed"
            JourneyStatus.BOARDING ->
                "Gate ${state.gate ?: "--"} · Seat ${state.seat ?: "--"} · Group ${state.boardingZone ?: "--"}"
            JourneyStatus.LAST_CALL ->
                "Gate ${state.gate ?: "--"} · Gate closing soon"
            JourneyStatus.TAKING_OFF ->
                "Taking off soon"
            JourneyStatus.ON_THE_WAY ->
                "On the way"
            JourneyStatus.ARRIVAL ->
                "Carousel ${state.baggageCarousel ?: "--"}"
            JourneyStatus.CONNECTION_ERROR ->
                "Connection error"
            JourneyStatus.GENERAL_ERROR ->
                "Something went wrong"
        }
    }

    companion object {
        private const val ARRIVAL_CHECK_INTERVAL_MS = 2_000L
    }
}
