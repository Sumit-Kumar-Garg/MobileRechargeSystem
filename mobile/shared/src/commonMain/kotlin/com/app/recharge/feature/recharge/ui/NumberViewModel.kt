package com.app.recharge.feature.recharge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.recharge.core.network.ApiError
import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.network.ApiResult
import com.app.recharge.core.telemetry.SessionManager
import com.app.recharge.core.telemetry.TelemetryRecorder
import com.app.recharge.feature.recharge.data.RechargeRepository
import com.app.recharge.feature.recharge.domain.LinkedNumber
import com.app.recharge.feature.recharge.domain.PhoneNumberValidator
import com.app.recharge.feature.recharge.domain.PhoneValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NumberError { INVALID, UNSUPPORTED, LIMIT, OFFLINE, GENERIC }

data class NumberUiState(
    val loading: Boolean = false,
    val error: NumberError? = null,
    val linked: LinkedNumber? = null,
)

class NumberViewModel(
    private val repository: RechargeRepository,
    private val telemetry: TelemetryRecorder,
    private val session: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(NumberUiState())
    val state: StateFlow<NumberUiState> = _state.asStateFlow()

    fun submit(raw: String) {
        if (_state.value.loading) return                       // ignore double taps
        val e164 = when (val v = PhoneNumberValidator.validate(raw)) {
            is PhoneValidation.Valid -> v.e164
            is PhoneValidation.Invalid -> {
                _state.update {
                    it.copy(error = if (v.reason == PhoneValidation.Reason.UNSUPPORTED) NumberError.UNSUPPORTED else NumberError.INVALID)
                }
                return
            }
        }
        _state.update { it.copy(loading = true, error = null) }
        session.startJourney()                                  // one journey per recharge attempt
        telemetry.journey("number_entered")                     // never the number itself
        viewModelScope.launch {
            when (val result = repository.linkNumber(e164)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, linked = result.value) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = result.error.toNumberError()) }
            }
        }
    }

    fun consumeLinked() = _state.update { it.copy(linked = null) }

    private fun ApiError.toNumberError() = when {
        kind == ApiErrorKind.OFFLINE -> NumberError.OFFLINE
        code == "NUMBER_LIMIT_EXCEEDED" -> NumberError.LIMIT
        kind == ApiErrorKind.CLIENT && code == "REQUEST_INVALID" -> NumberError.INVALID
        else -> NumberError.GENERIC
    }
}
