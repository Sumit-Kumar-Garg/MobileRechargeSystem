package com.app.recharge.feature.recharge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.recharge.core.network.ApiError
import com.app.recharge.core.network.ApiResult
import com.app.recharge.core.telemetry.TelemetryRecorder
import com.app.recharge.feature.recharge.data.RechargeRepository
import com.app.recharge.feature.recharge.domain.Plan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class PlanItem(
    val plan: Plan,
    val recommended: Boolean,
    val cheaperAlternative: Boolean,
    /** Why the advisor picked this plan. Only set on the recommended plan, and only for AI advice. */
    val explanation: String? = null,
)

sealed interface PlansUiState {
    data object Loading : PlansUiState
    data class Content(val items: List<PlanItem>) : PlansUiState
    data class Error(val error: ApiError) : PlansUiState
}

class PlansViewModel(
    private val repository: RechargeRepository,
    private val telemetry: TelemetryRecorder,
    private val numberId: String,
    private val market: String,
) : ViewModel() {

    private val _state = MutableStateFlow<PlansUiState>(PlansUiState.Loading)
    val state: StateFlow<PlansUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = PlansUiState.Loading
        viewModelScope.launch {
            coroutineScope {
                val plansCall = async { repository.plans(market) }
                // The recommendation is a bonus: if it is slow or fails, the list simply has no badge.
                val recommendationCall = async {
                    withTimeoutOrNull(RECOMMENDATION_TIMEOUT_MS) { repository.recommendation(numberId) }
                }
                when (val plans = plansCall.await()) {
                    is ApiResult.Failure -> _state.value = PlansUiState.Error(plans.error)
                    is ApiResult.Success -> {
                        val recommendation = recommendationCall.await()
                        val items = plans.value.map {
                            PlanItem(
                                plan = it,
                                recommended = recommendation?.planId == it.id,
                                cheaperAlternative = recommendation?.cheaperAlternativePlanId == it.id,
                                explanation = if (recommendation?.planId == it.id) recommendation?.explanation else null,
                            )
                        }
                        _state.value = PlansUiState.Content(items)
                        telemetry.journey("plans_viewed", mapOf("count" to items.size))
                        items.firstOrNull { it.recommended }?.let {
                            telemetry.journey(
                                "recommendation_shown",
                                mapOf("plan" to it.plan.code, "reason" to (recommendation?.reason ?: "")),
                            )
                        }
                    }
                }
            }
        }
    }

    fun onPlanSelected(item: PlanItem) {
        telemetry.journey("plan_selected", mapOf("plan" to item.plan.code, "recommended" to item.recommended))
    }

    companion object {
        const val RECOMMENDATION_TIMEOUT_MS = 300L
    }
}
