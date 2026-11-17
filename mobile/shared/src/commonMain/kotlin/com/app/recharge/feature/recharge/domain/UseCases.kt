package com.app.recharge.feature.recharge.domain

import com.app.recharge.core.network.ApiError
import com.app.recharge.core.network.ApiResult
import com.app.recharge.feature.recharge.data.RechargeRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface PayResult {
    /** The payment call was answered. The order may be paid, still pending, or failed (declined). */
    data class Done(val order: Order) : PayResult

    /** The order exists but the answer to the payment call was lost. Check the status; never charge again. */
    data class Unknown(val orderId: String) : PayResult

    /** Nothing was charged: the order could not be created or the payment was rejected up front. */
    data class Failed(val error: ApiError) : PayResult
}

/**
 * Create the order, then pay it. Safe to run again with the same idempotency key (after a crash, a lost
 * response, or a second tap): the server returns the same order, and an order that is already paid or
 * pending is returned as-is without a second charge.
 */
class PayForPlanUseCase(private val repository: RechargeRepository) {

    suspend operator fun invoke(numberId: String, planId: String, idempotencyKey: String, paymentToken: String): PayResult {
        val order = when (val created = repository.createOrder(numberId, planId, idempotencyKey)) {
            is ApiResult.Failure -> return PayResult.Failed(created.error)
            is ApiResult.Success -> created.value
        }
        if (!order.status.payable) return PayResult.Done(order)

        return when (val paid = repository.pay(order.id, paymentToken)) {
            is ApiResult.Success -> PayResult.Done(paid.value)
            is ApiResult.Failure ->
                if (paid.error.outcomeUnknown) PayResult.Unknown(order.id) else PayResult.Failed(paid.error)
        }
    }
}

/**
 * Polls an order until it settles, backing off between polls. Emits every result so the UI can show
 * progress and transient errors. Stops on a settled status, a permanent client error, or after maxPolls.
 */
class ObserveOrderUseCase(private val repository: RechargeRepository) {

    operator fun invoke(
        orderId: String,
        delaysMs: List<Long> = DEFAULT_DELAYS_MS,
        maxPolls: Int = MAX_POLLS,
    ): Flow<ApiResult<Order>> = flow {
        var poll = 0
        while (poll < maxPolls) {
            val result = repository.order(orderId)
            emit(result)
            when (result) {
                is ApiResult.Success -> if (result.value.status.settled) return@flow
                is ApiResult.Failure ->
                    if (result.error.kind == com.app.recharge.core.network.ApiErrorKind.CLIENT) return@flow
            }
            delay(delaysMs[minOf(poll, delaysMs.lastIndex)])
            poll++
        }
    }

    companion object {
        val DEFAULT_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L)
        const val MAX_POLLS = 40
    }
}
