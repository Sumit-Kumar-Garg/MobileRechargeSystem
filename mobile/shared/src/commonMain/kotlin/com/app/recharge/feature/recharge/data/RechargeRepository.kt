package com.app.recharge.feature.recharge.data

import com.app.recharge.core.network.ApiResult
import com.app.recharge.core.network.map
import com.app.recharge.feature.recharge.domain.LinkedNumber
import com.app.recharge.feature.recharge.domain.Order
import com.app.recharge.feature.recharge.domain.OrderStatus
import com.app.recharge.feature.recharge.domain.Plan
import com.app.recharge.feature.recharge.domain.Recommendation

interface RechargeRepository {
    suspend fun linkNumber(msisdn: String): ApiResult<LinkedNumber>
    suspend fun plans(market: String): ApiResult<List<Plan>>

    /** Null on any failure or when there is nothing to recommend. A recommendation is never required. */
    suspend fun recommendation(numberId: String): Recommendation?

    suspend fun createOrder(numberId: String, planId: String, idempotencyKey: String): ApiResult<Order>
    suspend fun pay(orderId: String, paymentToken: String): ApiResult<Order>
    suspend fun order(orderId: String): ApiResult<Order>
    suspend fun setConsent(purpose: String, granted: Boolean): ApiResult<Unit>
}

class RechargeRepositoryImpl(private val api: RechargeApi) : RechargeRepository {

    override suspend fun linkNumber(msisdn: String): ApiResult<LinkedNumber> =
        api.linkNumber(LinkNumberRequest(msisdn)).map { LinkedNumber(it.id, it.msisdnMasked, it.market) }

    override suspend fun plans(market: String): ApiResult<List<Plan>> =
        api.plans(market).map { response -> response.plans.map { it.toDomain() } }

    override suspend fun recommendation(numberId: String): Recommendation? =
        when (val result = api.recommendation(numberId)) {
            is ApiResult.Success -> result.value?.toDomain()
            is ApiResult.Failure -> null
        }

    override suspend fun createOrder(numberId: String, planId: String, idempotencyKey: String): ApiResult<Order> =
        api.createOrder(idempotencyKey, CreateOrderRequest(numberId, planId)).map { it.toDomain() }

    override suspend fun pay(orderId: String, paymentToken: String): ApiResult<Order> =
        api.pay(orderId, PayRequest(paymentToken)).map { it.toDomain() }

    override suspend fun order(orderId: String): ApiResult<Order> = api.order(orderId).map { it.toDomain() }

    override suspend fun setConsent(purpose: String, granted: Boolean): ApiResult<Unit> =
        api.setConsent(purpose, ConsentRequest(granted))
}

internal fun PlanDto.toDomain() = Plan(
    id = id,
    code = code,
    name = name,
    priceMinor = price.amountMinor,
    currency = price.currency,
    validityDays = validityDays,
    dataMb = allowances.dataMb,
    voiceMin = allowances.voiceMin,
    sms = allowances.sms,
    benefits = benefits.map { it.label },
)

internal fun OrderDto.toDomain() = Order(
    id = id,
    status = OrderStatus.fromApi(status),
    planName = planName,
    amountMinor = amountMinor,
    currency = currency,
    validityDays = validityDays,
    msisdnMasked = msisdnMasked,
    failureCode = failureCode,
    validUntil = validUntil,
)

internal fun RecommendationDto.toDomain() =
    Recommendation(planId, reason, personalized, cheaperAlternativePlanId, explanation?.take(MAX_EXPLANATION_LENGTH))

/** The server already limits this text; the app cuts it again so a faulty server cannot break the layout. */
private const val MAX_EXPLANATION_LENGTH = 220
