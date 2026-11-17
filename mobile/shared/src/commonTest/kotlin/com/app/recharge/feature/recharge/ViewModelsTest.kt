package com.app.recharge.feature.recharge

import app.cash.turbine.test
import com.app.recharge.FakeRechargeRepository
import com.app.recharge.MainDispatcherTest
import com.app.recharge.RecordingRecorder
import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.network.ApiResult
import com.app.recharge.core.telemetry.SessionManager
import com.app.recharge.failure
import com.app.recharge.feature.recharge.domain.LinkedNumber
import com.app.recharge.feature.recharge.domain.ObserveOrderUseCase
import com.app.recharge.feature.recharge.domain.OrderStatus
import com.app.recharge.feature.recharge.domain.PayForPlanUseCase
import com.app.recharge.feature.recharge.domain.Recommendation
import com.app.recharge.feature.recharge.ui.NumberError
import com.app.recharge.feature.recharge.ui.NumberViewModel
import com.app.recharge.feature.recharge.ui.PaymentUiState
import com.app.recharge.feature.recharge.ui.PaymentViewModel
import com.app.recharge.feature.recharge.ui.PlansUiState
import com.app.recharge.feature.recharge.ui.PlansViewModel
import com.app.recharge.feature.recharge.ui.StatusViewModel
import com.app.recharge.order
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class NumberViewModelTest : MainDispatcherTest() {

    private val repo = FakeRechargeRepository()
    private val recorder = RecordingRecorder()
    private val session = SessionManager()
    private fun vm() = NumberViewModel(repo, recorder, session)

    @Test fun invalidInputShowsAnErrorWithoutCallingTheServer() = runTest {
        val vm = vm()
        vm.submit("abc")
        assertEquals(NumberError.INVALID, vm.state.value.error)
        assertTrue(repo.linkedMsisdns.isEmpty())
        assertNull(session.currentJourneyId)
    }

    @Test fun unsupportedCountryHasItsOwnMessage() = runTest {
        val vm = vm()
        vm.submit("+1 415 555 0123")
        assertEquals(NumberError.UNSUPPORTED, vm.state.value.error)
    }

    @Test fun validInputLinksNormalizesAndStartsAJourney() = runTest {
        val vm = vm()
        vm.submit("0170 1234567")
        advanceUntilIdle()

        assertEquals(listOf("+491701234567"), repo.linkedMsisdns)
        assertEquals(LinkedNumber("number-1", "+49 *** ***4567", "DE"), vm.state.value.linked)
        assertFalse(vm.state.value.loading)
        assertNotNull(session.currentJourneyId)
    }

    @Test fun telemetryNeverContainsTheNumber() = runTest {
        val vm = vm()
        vm.submit("0170 1234567")
        advanceUntilIdle()

        assertEquals(listOf("number_entered"), recorder.names())
        assertTrue(recorder.events.single().props.isEmpty())
    }

    @Test fun doubleTapsAreIgnoredWhileLinking() = runTest {
        repo.linkDelayMs = 1_000
        val vm = vm()
        vm.submit("0170 1234567")
        vm.submit("0170 1234567")
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()

        assertEquals(1, repo.linkedMsisdns.size)
    }

    @Test fun serverErrorsMapToFriendlyErrors() = runTest {
        repo.linkResult = failure(ApiErrorKind.CLIENT, code = "NUMBER_LIMIT_EXCEEDED", status = 422)
        val vm = vm()
        vm.submit("0170 1234567")
        advanceUntilIdle()
        assertEquals(NumberError.LIMIT, vm.state.value.error)

        repo.linkResult = failure(ApiErrorKind.OFFLINE)
        vm.submit("0170 1234567")
        advanceUntilIdle()
        assertEquals(NumberError.OFFLINE, vm.state.value.error)

        repo.linkResult = failure(ApiErrorKind.SERVER, status = 500)
        vm.submit("0170 1234567")
        advanceUntilIdle()
        assertEquals(NumberError.GENERIC, vm.state.value.error)
    }

    @Test fun consumingTheResultClearsIt() = runTest {
        val vm = vm()
        vm.submit("0170 1234567")
        advanceUntilIdle()
        vm.consumeLinked()
        assertNull(vm.state.value.linked)
    }

    @Test fun anErrorClearsWhenTheUserTriesAgain() = runTest {
        val vm = vm()
        vm.submit("abc")
        vm.submit("0170 1234567")
        advanceUntilIdle()
        assertNull(vm.state.value.error)
    }
}

class PlansViewModelTest : MainDispatcherTest() {

    private val repo = FakeRechargeRepository()
    private val recorder = RecordingRecorder()
    private fun vm() = PlansViewModel(repo, recorder, numberId = "n1", market = "DE")

    private fun PlansViewModel.content() = state.value as PlansUiState.Content

    @Test fun showsAllPlans() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("S-28", "M-28", "L-28"), vm.content().items.map { it.plan.code })
        assertTrue(vm.content().items.none { it.recommended })
    }

    @Test fun marksTheRecommendedPlanAndTheCheaperAlternative() = runTest {
        repo.recommendationResult = Recommendation("plan-M-28", "USAGE_FIT", true, "plan-S-28")
        val vm = vm()
        advanceUntilIdle()

        val items = vm.content().items.associateBy { it.plan.code }
        assertTrue(items.getValue("M-28").recommended)
        assertTrue(items.getValue("S-28").cheaperAlternative)
        assertFalse(items.getValue("L-28").recommended)
        assertTrue(recorder.names().containsAll(listOf("plans_viewed", "recommendation_shown")))
    }

    @Test fun theAdvisorsExplanationIsShownOnTheRecommendedPlanOnly() = runTest {
        repo.recommendationResult =
            Recommendation("plan-M-28", "AI_ADVISOR", true, "plan-S-28", "You use about 8 GB a month.")
        val vm = vm()
        advanceUntilIdle()

        val items = vm.content().items.associateBy { it.plan.code }
        assertEquals("You use about 8 GB a month.", items.getValue("M-28").explanation)
        assertNull(items.getValue("S-28").explanation)
        assertNull(items.getValue("L-28").explanation)
    }

    @Test fun aSlowRecommendationNeverDelaysOrBreaksTheList() = runTest {
        repo.recommendationResult = Recommendation("plan-M-28", "USAGE_FIT", true, null)
        repo.recommendationDelayMs = PlansViewModel.RECOMMENDATION_TIMEOUT_MS + 700
        val vm = vm()
        advanceUntilIdle()

        assertTrue(vm.content().items.none { it.recommended })
        assertFalse(recorder.names().contains("recommendation_shown"))
        assertTrue(currentTime <= PlansViewModel.RECOMMENDATION_TIMEOUT_MS + 1)    // we did not wait for it
    }

    @Test fun noRecommendationIsFineToo() = runTest {
        repo.recommendationResult = null
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value is PlansUiState.Content)
    }

    @Test fun planLoadFailureShowsAnErrorThatCanBeRetried() = runTest {
        repo.plansResult = failure(ApiErrorKind.NETWORK)
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value is PlansUiState.Error)

        repo.plansResult = ApiResult.Success(listOf(com.app.recharge.plan()))
        vm.load()
        advanceUntilIdle()

        assertEquals(1, vm.content().items.size)
        assertEquals(2, repo.planRequests)
    }

    @Test fun selectingAPlanIsRecordedWithoutPersonalData() = runTest {
        repo.recommendationResult = Recommendation("plan-M-28", "USAGE_FIT", true, null)
        val vm = vm()
        advanceUntilIdle()

        vm.onPlanSelected(vm.content().items.first { it.plan.code == "M-28" })

        val selected = recorder.events.last()
        assertEquals("plan_selected", selected.name)
        assertEquals(mapOf("plan" to "M-28", "recommended" to true), selected.props)
    }
}

class PaymentViewModelTest : MainDispatcherTest() {

    private val repo = FakeRechargeRepository()
    private val recorder = RecordingRecorder()

    private val key = "0123456789abcdef0123456789abcdef"      // kept by the screen in saved state
    private fun vm() = PaymentViewModel(PayForPlanUseCase(repo), recorder, numberId = "n1", planId = "p1")

    @Test fun successGoesToTheStatusScreen() = runTest {
        val vm = vm()
        vm.onPayClicked("tok_success", key)
        advanceUntilIdle()
        assertEquals(PaymentUiState.Done("order-1"), vm.state.value)
        assertEquals(listOf("tok_success"), repo.payTokens)
    }

    @Test fun theKeyFromTheScreenIsSentWithTheOrder() = runTest {
        vm().onPayClicked("tok_success", key)
        advanceUntilIdle()
        assertEquals(listOf(key), repo.createKeys)
    }

    @Test fun aRecreatedViewModelReusesTheSameKeySoNoSecondOrderIsCreated() = runTest {
        val before = vm()
        before.onPayClicked("tok_success", key)
        advanceUntilIdle()

        val afterProcessDeath = vm()                      // new instance; the screen restored the same key
        afterProcessDeath.onPayClicked("tok_success", key)
        advanceUntilIdle()

        assertEquals(2, repo.createKeys.size)
        assertEquals(1, repo.createKeys.toSet().size)     // the server sees the same key twice and dedupes
    }

    @Test fun doubleTapsDoNotCreateTwoAttempts() = runTest {
        repo.createDelayMs = 1_000
        val vm = vm()
        vm.onPayClicked("tok_success", key)
        vm.onPayClicked("tok_success", key)
        assertEquals(PaymentUiState.Processing, vm.state.value)
        advanceUntilIdle()
        assertEquals(1, repo.createKeys.size)
    }

    @Test fun aDeclineStaysOnTheScreenAndRetriesOnTheSameOrder() = runTest {
        repo.payResult = ApiResult.Success(order(OrderStatus.PAYMENT_FAILED, failureCode = "card_declined"))
        val vm = vm()
        vm.onPayClicked("tok_decline", key)
        advanceUntilIdle()
        assertEquals(PaymentUiState.Declined, vm.state.value)

        repo.createResult = ApiResult.Success(order(OrderStatus.PAYMENT_FAILED))      // replay returns the same failed order
        repo.payResult = ApiResult.Success(order(OrderStatus.PAID))
        vm.onPayClicked("tok_success", key)
        advanceUntilIdle()

        assertEquals(PaymentUiState.Done("order-1"), vm.state.value)
        assertEquals(1, repo.createKeys.toSet().size)
    }

    @Test fun aLostResponseGoesToTheStatusScreenInsteadOfShowingAnError() = runTest {
        repo.payResult = failure(ApiErrorKind.TIMEOUT)
        val vm = vm()
        vm.onPayClicked("tok_timeout", key)
        advanceUntilIdle()
        assertEquals(PaymentUiState.Done("order-1"), vm.state.value)
    }

    @Test fun rejectionsBeforePaymentAreShownAsFailures() = runTest {
        repo.createResult = failure(ApiErrorKind.OFFLINE)
        val vm = vm()
        vm.onPayClicked("tok_success", key)
        advanceUntilIdle()
        val state = vm.state.value as PaymentUiState.Failed
        assertEquals(ApiErrorKind.OFFLINE, state.error.kind)
        assertTrue(repo.payTokens.isEmpty())
    }

    @Test fun consumingDoneResetsToIdleSoNavigationHappensOnce() = runTest {
        val vm = vm()
        vm.onPayClicked("tok_success", key)
        advanceUntilIdle()
        vm.consumeDone()
        assertEquals(PaymentUiState.Idle, vm.state.value)
    }

    @Test fun theTokenIsNeverRecordedInTelemetry() = runTest {
        val vm = vm()
        vm.onPayClicked("tok_success", key)
        advanceUntilIdle()
        assertEquals(listOf("pay_tapped"), recorder.names())
        assertTrue(recorder.events.all { it.props.isEmpty() })
    }
}

class StatusViewModelTest : MainDispatcherTest() {

    private val repo = FakeRechargeRepository()
    private val recorder = RecordingRecorder()
    private val session = SessionManager()
    private fun vm() = StatusViewModel(ObserveOrderUseCase(repo), recorder, session, orderId = "order-1")

    @Test fun followsTheOrderUntilItCompletes() = runTest {
        repo.orderResults.clear()
        repo.orderResults += listOf(
            ApiResult.Success(order(OrderStatus.PROVISIONING)),
            ApiResult.Success(order(OrderStatus.COMPLETED)),
        )
        val vm = vm()
        vm.state.test {
            advanceUntilIdle()
            var last = awaitItem()
            while (last.order?.status != OrderStatus.COMPLETED) last = awaitItem()
            assertEquals(OrderStatus.COMPLETED, last.order?.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun recordsTheFinalStatusOnceAndEndsTheJourney() = runTest {
        session.startJourney()
        repo.orderResults.clear()
        repo.orderResults += ApiResult.Success(order(OrderStatus.COMPLETED))
        vm()
        advanceUntilIdle()

        assertEquals(1, recorder.names().count { it == "status_final" })
        assertEquals(mapOf("status" to "COMPLETED"), recorder.events.single().props)
        assertNull(session.currentJourneyId)
    }

    @Test fun anInFlightOrderDoesNotEndTheJourney() = runTest {
        session.startJourney()
        repo.orderResults.clear()
        repo.orderResults += ApiResult.Success(order(OrderStatus.PROVISIONING))
        vm()
        // polling continues in the background; only the first poll has happened without advancing time
        assertNotNull(session.currentJourneyId)
        assertTrue(recorder.events.isEmpty())
    }

    @Test fun anErrorBeforeAnyOrderIsShownCanBeRetried() = runTest {
        repo.orderResults.clear()
        repo.orderResults += listOf(failure(ApiErrorKind.CLIENT, "RESOURCE_NOT_FOUND", 404))
        val vm = vm()
        advanceUntilIdle()
        assertNull(vm.state.value.order)
        assertEquals(ApiErrorKind.CLIENT, vm.state.value.error?.kind)

        repo.orderPolls = 0
        repo.orderResults.clear()
        repo.orderResults += ApiResult.Success(order(OrderStatus.COMPLETED))
        vm.retry()
        advanceUntilIdle()

        assertEquals(OrderStatus.COMPLETED, vm.state.value.order?.status)
        assertNull(vm.state.value.error)
    }

    @Test fun aTransientErrorKeepsTheLastKnownOrderVisible() = runTest {
        repo.orderResults.clear()
        repo.orderResults += listOf(
            ApiResult.Success(order(OrderStatus.PROVISIONING)),
            failure(ApiErrorKind.NETWORK),
            ApiResult.Success(order(OrderStatus.COMPLETED)),
        )
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OrderStatus.COMPLETED, vm.state.value.order?.status)
    }
}
