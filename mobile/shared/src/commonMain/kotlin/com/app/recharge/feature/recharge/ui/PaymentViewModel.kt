package com.app.recharge.feature.recharge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.recharge.core.network.ApiError
import com.app.recharge.core.telemetry.TelemetryRecorder
import com.app.recharge.feature.recharge.domain.OrderStatus
import com.app.recharge.feature.recharge.domain.PayForPlanUseCase
import com.app.recharge.feature.recharge.domain.PayResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PaymentUiState {
    data object Idle : PaymentUiState
    data object Processing : PaymentUiState
    data object Declined : PaymentUiState
    data class Failed(val error: ApiError) : PaymentUiState

    /** Navigate to the status screen. Also used when the answer was lost: the status screen resolves it. */
    data class Done(val orderId: String) : PaymentUiState
}

class PaymentViewModel(
    private val payForPlan: PayForPlanUseCase,
    private val telemetry: TelemetryRecorder,
    private val numberId: String,
    private val planId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<PaymentUiState>(PaymentUiState.Idle)
    val state: StateFlow<PaymentUiState> = _state.asStateFlow()

    /**
     * @param idempotencyKey one key per order attempt. The screen keeps it in saved state, so it survives
     * rotation AND process death: if the app dies mid-payment, tapping Pay again replays the same order
     * instead of creating (and charging for) a second one.
     */
    fun onPayClicked(paymentToken: String, idempotencyKey: String) {
        if (_state.value is PaymentUiState.Processing) return        // double-tap guard
        _state.value = PaymentUiState.Processing
        telemetry.journey("pay_tapped")
        viewModelScope.launch {
            _state.value = when (val result = payForPlan(numberId, planId, idempotencyKey, paymentToken)) {
                is PayResult.Done ->
                    if (result.order.status == OrderStatus.PAYMENT_FAILED) PaymentUiState.Declined
                    else PaymentUiState.Done(result.order.id)
                is PayResult.Unknown -> PaymentUiState.Done(result.orderId)
                is PayResult.Failed -> PaymentUiState.Failed(result.error)
            }
        }
    }

    fun consumeDone() {
        if (_state.value is PaymentUiState.Done) _state.value = PaymentUiState.Idle
    }
}
