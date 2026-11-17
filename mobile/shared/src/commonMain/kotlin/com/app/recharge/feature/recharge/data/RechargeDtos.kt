package com.app.recharge.feature.recharge.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class PriceDto(val amountMinor: Int, val currency: String)
@Serializable data class AllowancesDto(val dataMb: Int? = null, val voiceMin: Int? = null, val sms: Int? = null)
@Serializable data class BenefitDto(val type: String, val label: String)

@Serializable
data class PlanDto(
    val id: String,
    val code: String,
    val market: String,
    val name: String,
    val price: PriceDto,
    val validityDays: Int,
    val allowances: AllowancesDto = AllowancesDto(),
    val benefits: List<BenefitDto> = emptyList(),
)

@Serializable data class PlansResponseDto(val market: String, val plans: List<PlanDto>)

@Serializable data class NumberDto(val id: String, val msisdnMasked: String, val market: String)
@Serializable data class LinkNumberRequest(val msisdn: String)

@Serializable
data class OrderDto(
    val id: String,
    val status: String,
    val numberId: String,
    val planId: String,
    val planCode: String,
    val planName: String,
    val amountMinor: Int,
    val currency: String,
    val validityDays: Int,
    val msisdnMasked: String,
    val failureCode: String? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val completedAt: String? = null,
    val validUntil: String? = null,
)

@Serializable data class CreateOrderRequest(val numberId: String, val planId: String)
@Serializable data class PayRequest(val paymentToken: String)

@Serializable
data class RecommendationDto(
    val planId: String,
    val planCode: String,
    val reason: String,
    val personalized: Boolean = false,
    val cheaperAlternativePlanId: String? = null,
    val modelVersion: String? = null,
    /** One sentence from the AI advisor, already in the user's language. Null for rule-based picks. */
    val explanation: String? = null,
)

@Serializable data class DeviceRequest(val token: String)
@Serializable data class ConsentRequest(val granted: Boolean)

@Serializable data class DemoTokenRequest(val userId: String? = null)
@Serializable data class TokenResponseDto(val accessToken: String, val tokenType: String, val expiresInSeconds: Long, val userId: String)

@Serializable
data class TelemetryEventDto(
    val id: String,
    val type: String,
    val name: String,
    val occurredAt: String,
    val journeyId: String? = null,
    val orderId: String? = null,
    val props: JsonObject,
)

@Serializable
data class TelemetryBatchDto(val sessionId: String, val appVersion: String, val events: List<TelemetryEventDto>)
