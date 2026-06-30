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
import com.elal.flightsdk.model.FlightContentState
import com.elal.flightsdk.model.Stage
import com.elal.flightsdk.sample.SampleFlightData
import com.elal.flightsdk.utils.orDash
import com.elal.flightsdk.utils.toFormattedTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    val watchdogFlow: StateFlow<LiveUpdateManager.WatchdogState?> = LiveUpdateManager.watchdogFlow

    private var currentStateIndex = 0
    private var activeSequence: List<FlightContentState> = SampleFlightData.journeySimulationSequence
    private var simulationJob: Job? = null

    init {
        viewModelScope.launch {
            LiveUpdateManager.stateFlow.collect { state ->
                if (state == null) {
                    _isActivityActive.value = false
                    _isSimulating.value = false
                    _statusText.value = UiText.StringResource(R.string.no_active_activity)
                    _detailText.value = null
                } else {
                    _isActivityActive.value = true
                    updateUI(state)
                }
            }
        }
    }

    fun enableDebugWatchdog() {
        LiveUpdateManager.setWatchdogDuration(LiveUpdateManager.DEBUG_WATCHDOG_MS)
    }

    // TODO: DEBUG - Remove before release (along with all DebugNextStageReceiver usages)
    private val isDebuggable: Boolean
        get() = (getApplication<Application>().applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun startActivity() {
        activeSequence = SampleFlightData.arrivalSimulationSequence
        val state = activeSequence[0]
        if (isDebuggable) DebugNextStageReceiver.setup(getApplication()) // TODO: DEBUG - Remove before release
        LiveUpdateManager.startJourney(getApplication(), state)
            .onSuccess {
                _isActivityActive.value = true
                currentStateIndex = 0
                _errorMessage.value = null
            }
            .onFailure { _errorMessage.value = it.toDisplayMessage() }
    }

    fun updateToNextState() {
        val sequence = activeSequence
        currentStateIndex = (currentStateIndex + 1).coerceAtMost(sequence.size - 1)
        val state = sequence[currentStateIndex]
        if (isDebuggable) DebugNextStageReceiver.syncIndex(currentStateIndex) // TODO: DEBUG - Remove before release
        LiveUpdateManager.updateJourney(getApplication(), state)
            .onSuccess {
                if (state.stage == Stage.COMPLETED) {
                    _isActivityActive.value = false
                }
                _errorMessage.value = null
            }
            .onFailure { _errorMessage.value = it.toDisplayMessage() }
    }

    fun endActivity() {
        if (isDebuggable) DebugNextStageReceiver.teardown() // TODO: DEBUG - Remove before release
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

        activeSequence = SampleFlightData.journeySimulationSequence
        startActivity()
        if (!_isActivityActive.value) return

        _isSimulating.value = true
        currentStateIndex = 0

        val sequence = activeSequence

        simulationJob = viewModelScope.launch {
            for (i in 1 until sequence.size) {
                delay(5_000L)
                currentStateIndex = i
                val state = sequence[i]
                LiveUpdateManager.updateJourney(getApplication(), state)
                    .onFailure { _errorMessage.value = it.toDisplayMessage() }
                if (state.stage == Stage.COMPLETED) break
            }

            endActivity()
        }
    }

    fun simulateArrivalJourney() {
        if (_isSimulating.value) return

        activeSequence = SampleFlightData.arrivalSimulationSequence
        val sequence = activeSequence

        LiveUpdateManager.startJourney(getApplication(), sequence[0])
            .onSuccess {
                _isActivityActive.value = true
                currentStateIndex = 0
                _errorMessage.value = null
            }
            .onFailure { _errorMessage.value = it.toDisplayMessage() }

        if (!_isActivityActive.value) return

        _isSimulating.value = true

        simulationJob = viewModelScope.launch {
            for (i in 1 until sequence.size) {
                delay(5_000L)
                currentStateIndex = i
                val state = sequence[i]
                LiveUpdateManager.updateJourney(getApplication(), state)
                    .onFailure { _errorMessage.value = it.toDisplayMessage() }
                if (state.stage == Stage.COMPLETED) break
            }

            endActivity()
        }
    }

    fun setConnectionError() {
        val errorState = SampleFlightData.journeySimulationSequence[0].copy(stage = Stage.CONNECTION_ERROR)
        if (!_isActivityActive.value) {
            LiveUpdateManager.startJourney(getApplication(), errorState)
                .onSuccess {
                    _isActivityActive.value = true
                    _errorMessage.value = null
                }
                .onFailure { _errorMessage.value = it.toDisplayMessage() }
        } else {
            LiveUpdateManager.updateJourney(getApplication(), errorState)
                .onSuccess { _errorMessage.value = null }
                .onFailure { _errorMessage.value = it.toDisplayMessage() }
        }
    }

    fun setGeneralError() {
        val errorState = SampleFlightData.journeySimulationSequence[0].copy(stage = Stage.GENERAL_ERROR)
        if (!_isActivityActive.value) {
            LiveUpdateManager.startJourney(getApplication(), errorState)
                .onSuccess {
                    _isActivityActive.value = true
                    _errorMessage.value = null
                }
                .onFailure { _errorMessage.value = it.toDisplayMessage() }
        } else {
            LiveUpdateManager.updateJourney(getApplication(), errorState)
                .onSuccess { _errorMessage.value = null }
                .onFailure { _errorMessage.value = it.toDisplayMessage() }
        }
    }

    private fun LiveUpdateError.toDisplayMessage(): UiText = when (this) {
        is LiveUpdateError.ActivityAlreadyRunning  -> UiText.StringResource(R.string.error_already_running)
        is LiveUpdateError.NoActiveActivity        -> UiText.StringResource(R.string.error_no_active)
        is LiveUpdateError.NotificationsNotEnabled -> UiText.StringResource(R.string.error_notifications_disabled)
        is LiveUpdateError.FailedToStart           -> UiText.StringResource(R.string.error_failed_to_start, cause.message ?: "")
    }

    private fun updateUI(state: FlightContentState) {
        _statusText.value = when (state.stage) {
            Stage.CHECK_IN         -> UiText.StringResource(R.string.status_check_in)
            Stage.GATE_READY       -> UiText.StringResource(R.string.status_gate_ready)
            Stage.AWAITING_BOARDING -> UiText.StringResource(R.string.status_awaiting_boarding)
            Stage.BOARDING         -> UiText.StringResource(R.string.status_boarding)
            Stage.LAST_CALL        -> UiText.StringResource(R.string.status_last_call)
            Stage.DEPARTED         -> UiText.StringResource(R.string.status_departed)
            Stage.LANDING_SOON     -> UiText.StringResource(R.string.status_landing_soon)
            Stage.LANDED           -> UiText.StringResource(R.string.status_landed)
            Stage.BAGGAGE          -> UiText.StringResource(R.string.status_baggage)
            Stage.COMPLETED        -> UiText.StringResource(R.string.status_completed)
            Stage.CONNECTION_ERROR -> UiText.StringResource(R.string.status_connection_error)
            Stage.GENERAL_ERROR    -> UiText.StringResource(R.string.status_general_error)
        }
        _detailText.value = when (state.stage) {
            Stage.CHECK_IN    -> UiText.StringResource(R.string.detail_check_in, state.checkInCounters.orDash())
            Stage.GATE_READY  -> UiText.StringResource(R.string.detail_gate_ready, state.gate.orDash())
            Stage.AWAITING_BOARDING -> UiText.StringResource(R.string.detail_awaiting_boarding, state.gate.orDash())
            Stage.BOARDING    -> UiText.StringResource(R.string.detail_boarding, state.gate.orDash())
            Stage.LAST_CALL   -> UiText.StringResource(R.string.detail_last_call, state.gate.orDash())
            Stage.DEPARTED    -> UiText.StringResource(R.string.detail_departed, state.arrivalTime.toFormattedTime())
            Stage.LANDING_SOON -> UiText.StringResource(R.string.detail_landing_soon, state.destination)
            Stage.LANDED      -> UiText.StringResource(R.string.detail_landed, state.destination)
            Stage.BAGGAGE     -> UiText.StringResource(R.string.detail_baggage, state.baggageCarousel.orDash())
            Stage.COMPLETED   -> null
            Stage.CONNECTION_ERROR -> UiText.StringResource(R.string.detail_connection_error)
            Stage.GENERAL_ERROR    -> UiText.StringResource(R.string.detail_general_error)
        }
    }

}
