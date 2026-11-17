package com.app.recharge.feature.recharge.data

import com.app.recharge.core.network.ApiClient
import com.app.recharge.core.network.ApiResult
import io.ktor.http.HttpMethod

/** The backend's endpoints, one function each. All calls go through [ApiClient]. */
class RechargeApi(private val client: ApiClient) {

    suspend fun plans(market: String): ApiResult<PlansResponseDto> =
        client.get("v1/plans", mapOf("market" to market))

    suspend fun linkNumber(body: LinkNumberRequest): ApiResult<NumberDto> = client.post("v1/numbers", body)

    suspend fun createOrder(idempotencyKey: String, body: CreateOrderRequest): ApiResult<OrderDto> =
        client.post("v1/orders", body, headers = mapOf("Idempotency-Key" to idempotencyKey))

    suspend fun order(id: String): ApiResult<OrderDto> = client.get("v1/orders/$id")

    suspend fun pay(id: String, body: PayRequest): ApiResult<OrderDto> = client.post("v1/orders/$id/pay", body)

    /** Null when there is nothing to recommend (204). */
    suspend fun recommendation(numberId: String): ApiResult<RecommendationDto?> =
        client.getOrNull("v1/recommendations", mapOf("numberId" to numberId))

    suspend fun registerDevice(body: DeviceRequest): ApiResult<Unit> = client.send(HttpMethod.Put, "v1/devices", body)

    suspend fun setConsent(purpose: String, body: ConsentRequest): ApiResult<Unit> =
        client.send(HttpMethod.Put, "v1/consents/$purpose", body)

    suspend fun telemetry(body: TelemetryBatchDto): ApiResult<Unit> =
        client.send(HttpMethod.Post, "v1/telemetry/batch", body)

    /** Demo profile only. Real deployments authenticate against the company identity provider. */
    suspend fun demoToken(body: DemoTokenRequest): ApiResult<TokenResponseDto> =
        client.post("v1/demo/token", body, authenticated = false)
}
