package com.app.recharge.feature.recharge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.recharge.core.network.ApiError
import com.app.recharge.core.network.ApiResult
import com.app.recharge.core.telemetry.SessionManager
import com.app.recharge.core.telemetry.TelemetryRecorder
import com.app.recharge.feature.recharge.domain.ObserveOrderUseCase
import com.app.recharge.feature.recharge.domain.Order
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(val order: Order? = null, val error: ApiError? = null)

/** The server is the source of truth: this screen only ever shows what the API says, whatever a push said. */
class StatusViewModel(
    private val observeOrder: ObserveOrderUseCase,
    private val telemetry: TelemetryRecorder,
    private val session: SessionManager,
    private val orderId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var finalReported = false

    init {
        start()
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        start()
    }

    private fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            observeOrder(orderId).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        _state.value = StatusUiState(order = result.value)
                        if (result.value.status.settled && !finalReported) {
                            finalReported = true
                            telemetry.journey("status_final", mapOf("status" to result.value.status.name))
                            session.endJourney()
                        }
                    }
                    is ApiResult.Failure -> _state.update { it.copy(error = result.error) }
                }
            }
        }
    }
}
