package com.app.recharge.feature.recharge

import com.app.recharge.FakeRechargeRepository
import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.network.ApiResult
import com.app.recharge.failure
import com.app.recharge.feature.recharge.domain.ObserveOrderUseCase
import com.app.recharge.feature.recharge.domain.OrderStatus
import com.app.recharge.feature.recharge.domain.PayForPlanUseCase
import com.app.recharge.feature.recharge.domain.PayResult
import com.app.recharge.order
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class PayForPlanUseCaseTest {
    private val repo = FakeRechargeRepository()
    private val useCase = PayForPlanUseCase(repo)

    private suspend fun run() = useCase("n1", "p1", "idem-key-1234567890", "tok_success")

    @Test fun createsThenPays() = runTest {
        val result = run()
        assertEquals(PayResult.Done(order(OrderStatus.PAID)), result)
        assertEquals(listOf("idem-key-1234567890"), repo.createKeys)
        assertEquals(listOf("tok_success"), repo.payTokens)
    }

    @Test fun anAlreadyPaidOrderIsNeverChargedAgain() = runTest {
        repo.createResult = ApiResult.Success(order(OrderStatus.PAID))          // idempotent replay of a finished order
        val result = run()
        assertTrue(result is PayResult.Done)
        assertTrue(repo.payTokens.isEmpty())
    }

    @Test fun aPendingOrderIsNeverChargedAgain() = runTest {
        repo.createResult = ApiResult.Success(order(OrderStatus.PAYMENT_PENDING))
        assertEquals(PayResult.Done(order(OrderStatus.PAYMENT_PENDING)), run())
        assertTrue(repo.payTokens.isEmpty())
    }

    @Test fun aDeclinedOrderCanBePaidAgain() = runTest {
        repo.createResult = ApiResult.Success(order(OrderStatus.PAYMENT_FAILED))
        run()
        assertEquals(listOf("tok_success"), repo.payTokens)
    }

    @Test fun aDeclineIsReportedAsDoneWithAFailedOrder() = runTest {
        repo.payResult = ApiResult.Success(order(OrderStatus.PAYMENT_FAILED, failureCode = "card_declined"))
        val result = run() as PayResult.Done
        assertEquals(OrderStatus.PAYMENT_FAILED, result.order.status)
    }

    @Test fun aLostPaymentResponseIsUnknownNotFailed() = runTest {
        repo.payResult = failure(ApiErrorKind.TIMEOUT)
        assertEquals(PayResult.Unknown("order-1"), run())
    }

    @Test fun networkDropsAndServerErrorsAreUnknownToo() = runTest {
        repo.payResult = failure(ApiErrorKind.NETWORK)
        assertEquals(PayResult.Unknown("order-1"), run())
        repo.payResult = failure(ApiErrorKind.SERVER, status = 502)
        assertEquals(PayResult.Unknown("order-1"), run())
    }

    @Test fun anUpFrontRejectionIsAFailureNotAnUnknown() = runTest {
        repo.payResult = failure(ApiErrorKind.CLIENT, code = "PAYMENT_LIMIT_EXCEEDED", status = 429)
        val result = run() as PayResult.Failed
        assertEquals("PAYMENT_LIMIT_EXCEEDED", result.error.code)
    }

    @Test fun failingToCreateTheOrderMeansNothingWasCharged() = runTest {
        repo.createResult = failure(ApiErrorKind.OFFLINE)
        val result = run() as PayResult.Failed
        assertEquals(ApiErrorKind.OFFLINE, result.error.kind)
        assertTrue(repo.payTokens.isEmpty())
    }
}

class ObserveOrderUseCaseTest {
    private val repo = FakeRechargeRepository()
    private val useCase = ObserveOrderUseCase(repo)

    private fun success(status: OrderStatus) = ApiResult.Success(order(status))

    @Test fun pollsUntilTheOrderSettlesAndBacksOff() = runTest {
        repo.orderResults.clear()
        repo.orderResults += listOf(success(OrderStatus.PAID), success(OrderStatus.PROVISIONING), success(OrderStatus.COMPLETED))

        val emitted = useCase("order-1", delaysMs = listOf(1_000, 2_000, 4_000)).toList()

        assertEquals(3, emitted.size)
        assertEquals(OrderStatus.COMPLETED, (emitted.last() as ApiResult.Success).value.status)
        assertEquals(3_000L, currentTime)                    // waited 1s then 2s, not after the final poll
        assertEquals(3, repo.orderPolls)
    }

    @Test fun stopsImmediatelyWhenAlreadySettled() = runTest {
        val emitted = useCase("order-1").toList()
        assertEquals(1, emitted.size)
        assertEquals(0L, currentTime)
    }

    @Test fun aDeclinedPaymentIsSettledForTheUser() = runTest {
        repo.orderResults.clear()
        repo.orderResults += success(OrderStatus.PAYMENT_FAILED)
        assertEquals(1, useCase("order-1").toList().size)
    }

    @Test fun backoffCapsAtTheLastDelay() = runTest {
        repo.orderResults.clear()
        repo.orderResults += success(OrderStatus.PROVISIONING)
        useCase("order-1", delaysMs = listOf(1_000, 5_000), maxPolls = 4).toList()
        assertEquals(1_000L + 5_000L + 5_000L + 5_000L, currentTime)
    }

    @Test fun givesUpAfterMaxPolls() = runTest {
        repo.orderResults.clear()
        repo.orderResults += success(OrderStatus.PROVISIONING)
        assertEquals(3, useCase("order-1", delaysMs = listOf(10), maxPolls = 3).toList().size)
    }

    @Test fun transientErrorsAreEmittedAndPollingContinues() = runTest {
        repo.orderResults.clear()
        repo.orderResults += listOf(failure(ApiErrorKind.NETWORK), success(OrderStatus.COMPLETED))

        val emitted = useCase("order-1", delaysMs = listOf(100)).toList()

        assertTrue(emitted[0] is ApiResult.Failure)
        assertTrue(emitted[1] is ApiResult.Success)
    }

    @Test fun permanentClientErrorsStopPolling() = runTest {
        repo.orderResults.clear()
        repo.orderResults += failure(ApiErrorKind.CLIENT, code = "RESOURCE_NOT_FOUND", status = 404)

        val emitted = useCase("order-1").toList()

        assertEquals(1, emitted.size)
        assertEquals(1, repo.orderPolls)
    }
}
