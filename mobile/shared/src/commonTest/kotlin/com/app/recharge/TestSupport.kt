package com.app.recharge

import com.app.recharge.core.network.ApiError
import com.app.recharge.core.network.ApiResult
import com.app.recharge.core.network.NetworkState
import com.app.recharge.core.network.NetworkStateProvider
import com.app.recharge.core.security.TokenStore
import com.app.recharge.core.telemetry.ConsentState
import com.app.recharge.core.telemetry.NetworkCallEvent
import com.app.recharge.core.telemetry.QueuedEvent
import com.app.recharge.core.telemetry.TelemetryFlusher
import com.app.recharge.core.telemetry.TelemetryQueue
import com.app.recharge.core.telemetry.TelemetryRecorder
import com.app.recharge.feature.recharge.data.RechargeRepository
import com.app.recharge.feature.recharge.domain.LinkedNumber
import com.app.recharge.feature.recharge.domain.Order
import com.app.recharge.feature.recharge.domain.OrderStatus
import com.app.recharge.feature.recharge.domain.Plan
import com.app.recharge.feature.recharge.domain.Recommendation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** Replaces Dispatchers.Main so viewModelScope runs under the test scheduler (virtual time). */
abstract class MainDispatcherTest {
    @BeforeTest fun installMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun removeMainDispatcher() = Dispatchers.resetMain()
}

fun plan(code: String = "M-28", id: String = "plan-$code", priceMinor: Int = 1500, dataMb: Int? = 10_000) = Plan(
    id = id, code = code, name = "Prepaid $code", priceMinor = priceMinor, currency = "EUR", validityDays = 28,
    dataMb = dataMb, voiceMin = 300, sms = 300, benefits = listOf("5G network included"),
)

fun order(status: OrderStatus, id: String = "order-1", failureCode: String? = null) = Order(
    id = id, status = status, planName = "Prepaid M-28", amountMinor = 1500, currency = "EUR", validityDays = 28,
    msisdnMasked = "+49 *** ***4567", failureCode = failureCode, validUntil = null,
)

fun failure(kind: com.app.recharge.core.network.ApiErrorKind, code: String? = null, status: Int? = null) =
    ApiResult.Failure(ApiError(kind, httpStatus = status, code = code))

class RecordingRecorder : TelemetryRecorder {
    data class Event(val type: String, val name: String, val props: Map<String, Any>)

    val events = mutableListOf<Event>()
    val calls = mutableListOf<NetworkCallEvent>()

    override fun journey(name: String, props: Map<String, Any>) { events += Event("journey", name, props) }
    override fun lifecycle(name: String, props: Map<String, Any>) { events += Event("lifecycle", name, props) }
    override fun configChange(name: String, props: Map<String, Any>) { events += Event("config_change", name, props) }
    override fun networkCall(event: NetworkCallEvent) { calls += event }

    fun names() = events.map { it.name }
}

class FakeConsent(override var analyticsGranted: Boolean = true, override var personalizationGranted: Boolean = true) : ConsentState

class FakeFlusher : TelemetryFlusher {
    var flushes = 0
    override fun flushSoon() { flushes++ }
}

class FakeTelemetryQueue : TelemetryQueue {
    val rows = mutableListOf<QueuedEvent>()
    override suspend fun insert(event: QueuedEvent) { if (rows.none { it.id == event.id }) rows += event }
    override suspend fun count() = rows.size
    override suspend fun oldest(limit: Int) = rows.sortedBy { it.createdAt }.take(limit)
    override suspend fun delete(ids: List<String>) { rows.removeAll { it.id in ids } }
    override suspend fun deleteOldest(n: Int) { rows.sortedBy { it.createdAt }.take(n).forEach { rows.remove(it) } }
}

class FakeTokens(
    override var accessToken: String? = null,
    override var userId: String? = null,
    override var pushToken: String? = null,
) : TokenStore

class FixedNetwork(override var current: NetworkState = NetworkState()) : NetworkStateProvider

/** Scriptable repository: set the result fields, inspect the call lists. */
class FakeRechargeRepository : RechargeRepository {
    var linkResult: ApiResult<LinkedNumber> = ApiResult.Success(LinkedNumber("number-1", "+49 *** ***4567", "DE"))
    var plansResult: ApiResult<List<Plan>> = ApiResult.Success(listOf(plan("S-28", priceMinor = 1000, dataMb = 3000), plan("M-28"), plan("L-28", priceMinor = 2500, dataMb = 30_000)))
    var recommendationResult: Recommendation? = null
    var recommendationDelayMs = 0L
    var createResult: ApiResult<Order> = ApiResult.Success(order(OrderStatus.CREATED))
    var payResult: ApiResult<Order> = ApiResult.Success(order(OrderStatus.PAID))
    var createDelayMs = 0L
    var linkDelayMs = 0L
    val orderResults = mutableListOf<ApiResult<Order>>(ApiResult.Success(order(OrderStatus.COMPLETED)))

    val linkedMsisdns = mutableListOf<String>()
    val createKeys = mutableListOf<String>()
    val payTokens = mutableListOf<String>()
    var orderPolls = 0
    var planRequests = 0
    val consents = mutableListOf<Pair<String, Boolean>>()

    override suspend fun linkNumber(msisdn: String): ApiResult<LinkedNumber> {
        linkedMsisdns += msisdn
        if (linkDelayMs > 0) delay(linkDelayMs)
        return linkResult
    }

    override suspend fun plans(market: String): ApiResult<List<Plan>> {
        planRequests++
        return plansResult
    }

    override suspend fun recommendation(numberId: String): Recommendation? {
        if (recommendationDelayMs > 0) delay(recommendationDelayMs)
        return recommendationResult
    }

    override suspend fun createOrder(numberId: String, planId: String, idempotencyKey: String): ApiResult<Order> {
        createKeys += idempotencyKey
        if (createDelayMs > 0) delay(createDelayMs)
        return createResult
    }

    override suspend fun pay(orderId: String, paymentToken: String): ApiResult<Order> {
        payTokens += paymentToken
        return payResult
    }

    override suspend fun order(orderId: String): ApiResult<Order> {
        val result = orderResults[minOf(orderPolls, orderResults.lastIndex)]
        orderPolls++
        return result
    }

    override suspend fun setConsent(purpose: String, granted: Boolean): ApiResult<Unit> {
        consents += purpose to granted
        return ApiResult.Success(Unit)
    }
}
