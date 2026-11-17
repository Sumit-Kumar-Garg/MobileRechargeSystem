package com.app.recharge.feature.recharge.domain

data class LinkedNumber(val id: String, val masked: String, val market: String)

data class Plan(
    val id: String,
    val code: String,
    val name: String,
    val priceMinor: Int,
    val currency: String,
    val validityDays: Int,
    val dataMb: Int?,
    val voiceMin: Int?,
    val sms: Int?,
    val benefits: List<String>,
)

data class Recommendation(
    val planId: String,
    val reason: String,
    val personalized: Boolean,
    val cheaperAlternativePlanId: String?,
    val explanation: String? = null,
)

/** Mirrors the backend state machine. Unknown values from newer servers map to UNKNOWN instead of crashing. */
enum class OrderStatus {
    CREATED, PAYMENT_PENDING, PAYMENT_FAILED, PAID, PROVISIONING, PROVISIONING_FAILED,
    COMPLETED, REFUND_INITIATED, REFUNDED, EXPIRED, UNKNOWN;

    /** Nothing further will happen without the user doing something (or ever). Polling can stop. */
    val settled: Boolean
        get() = this == COMPLETED || this == REFUNDED || this == EXPIRED || this == PAYMENT_FAILED

    /** The order can still accept a payment. */
    val payable: Boolean
        get() = this == CREATED || this == PAYMENT_FAILED

    companion object {
        fun fromApi(value: String): OrderStatus = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

data class Order(
    val id: String,
    val status: OrderStatus,
    val planName: String,
    val amountMinor: Int,
    val currency: String,
    val validityDays: Int,
    val msisdnMasked: String,
    val failureCode: String?,
    val validUntil: String?,
)
