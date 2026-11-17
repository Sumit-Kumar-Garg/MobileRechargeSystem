package com.app.recharge.feature.recharge

import com.app.recharge.core.FakeServer
import com.app.recharge.core.apiClient
import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.network.ApiResult
import com.app.recharge.feature.recharge.data.RechargeApi
import com.app.recharge.feature.recharge.data.RechargeRepositoryImpl
import com.app.recharge.feature.recharge.domain.OrderStatus
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Repository + API + HTTP layer together, against a fake backend. */
class RechargeRepositoryTest {

    private val server = FakeServer()
    private val repository = RechargeRepositoryImpl(RechargeApi(apiClient(server)))

    private val orderJson = """
        {"id":"o1","status":"PAYMENT_PENDING","numberId":"n1","planId":"p1","planCode":"M-28","planName":"Prepaid M",
         "amountMinor":1500,"currency":"EUR","validityDays":28,"msisdnMasked":"+49 *** ***4567","futureField":true}
    """.trimIndent()

    @Test fun mapsPlansIncludingBenefitsAndAllowances() = runTest {
        server.enqueue(body = """
            {"market":"DE","plans":[{"id":"p1","code":"S-28","market":"DE","name":"Prepaid S",
              "price":{"amountMinor":1000,"currency":"EUR"},"validityDays":28,
              "allowances":{"dataMb":3000,"voiceMin":100},
              "benefits":[{"type":"NETWORK","label":"5G network included"}]}]}
        """.trimIndent())

        val plans = (repository.plans("DE") as ApiResult.Success).value

        assertEquals(1, plans.size)
        assertEquals("Prepaid S", plans[0].name)
        assertEquals(1000, plans[0].priceMinor)
        assertEquals(3000, plans[0].dataMb)
        assertNull(plans[0].sms)
        assertEquals(listOf("5G network included"), plans[0].benefits)
        assertEquals("/v1/plans", server.last.path)
        assertEquals("market=DE", server.last.query)
    }

    @Test fun linkNumberSendsTheNumberAndMapsTheMaskedAnswer() = runTest {
        server.enqueue(201, """{"id":"n1","msisdnMasked":"+49 *** ***4567","market":"DE"}""")

        val number = (repository.linkNumber("+491701234567") as ApiResult.Success).value

        assertEquals("n1", number.id)
        assertEquals("DE", number.market)
        assertEquals("POST", server.last.method)
        assertEquals("""{"msisdn":"+491701234567"}""", server.last.body)
    }

    @Test fun createOrderSendsIdempotencyKeyAndBody() = runTest {
        server.enqueue(201, orderJson)

        val result = repository.createOrder("n1", "p1", "key-1234567890abcdef")

        assertTrue(result is ApiResult.Success)
        assertEquals("/v1/orders", server.last.path)
        assertEquals("key-1234567890abcdef", server.last.headers["Idempotency-Key"])
        assertEquals("""{"numberId":"n1","planId":"p1"}""", server.last.body)
    }

    @Test fun unknownOrderStatusFromANewerServerDoesNotCrash() = runTest {
        server.enqueue(body = orderJson.replace("PAYMENT_PENDING", "SOME_FUTURE_STATUS"))
        val order = (repository.order("o1") as ApiResult.Success).value
        assertEquals(OrderStatus.UNKNOWN, order.status)
    }

    @Test fun payParsesTheOrder() = runTest {
        server.enqueue(body = orderJson)
        val order = (repository.pay("o1", "tok_success") as ApiResult.Success).value
        assertEquals(OrderStatus.PAYMENT_PENDING, order.status)
        assertEquals("/v1/orders/o1/pay", server.last.path)
        assertEquals("""{"paymentToken":"tok_success"}""", server.last.body)
    }

    @Test fun problemJsonBecomesATypedError() = runTest {
        server.enqueue(422, """{"status":422,"detail":"limit","code":"NUMBER_LIMIT_EXCEEDED","correlationId":"c-1"}""")

        val error = (repository.linkNumber("+491701234567") as ApiResult.Failure).error

        assertEquals(ApiErrorKind.CLIENT, error.kind)
        assertEquals("NUMBER_LIMIT_EXCEEDED", error.code)
        assertEquals("c-1", error.correlationId)
    }

    @Test fun serverErrorsMeanTheOutcomeIsUnknown() = runTest {
        server.enqueue(status = 502)
        val error = (repository.pay("o1", "tok") as ApiResult.Failure).error
        assertEquals(ApiErrorKind.SERVER, error.kind)
        assertTrue(error.outcomeUnknown)
    }

    @Test fun lostResponsesBecomeTimeouts() = runTest {
        server.enqueueFailure(HttpRequestTimeoutException("http://backend.test/v1/orders/o1/pay", 20_000))
        val error = (repository.pay("o1", "tok") as ApiResult.Failure).error
        assertEquals(ApiErrorKind.TIMEOUT, error.kind)
        assertTrue(error.outcomeUnknown)
    }

    @Test fun recommendationNoContentIsNull() = runTest {
        server.enqueue(status = 204)
        assertNull(repository.recommendation("n1"))
    }

    @Test fun recommendationIsMapped() = runTest {
        server.enqueue(body = """{"planId":"p1","planCode":"M-28","reason":"USAGE_FIT","personalized":true,"cheaperAlternativePlanId":"p0"}""")
        val r = repository.recommendation("n1")!!
        assertEquals("p1", r.planId)
        assertTrue(r.personalized)
        assertEquals("p0", r.cheaperAlternativePlanId)
        assertNull(r.explanation)
        assertEquals("/v1/recommendations", server.last.path)
        assertEquals("numberId=n1", server.last.query)
    }

    @Test fun theAdvisorsExplanationIsKeptButCutToAFixedLength() = runTest {
        val long = "x".repeat(500)
        server.enqueue(body = """{"planId":"p1","planCode":"M-28","reason":"AI_ADVISOR","personalized":true,"explanation":"$long"}""")

        val r = repository.recommendation("n1")!!

        assertEquals("AI_ADVISOR", r.reason)
        assertEquals(220, r.explanation!!.length)
    }

    @Test fun recommendationNeverThrowsEvenOnServerErrorOrTimeout() = runTest {
        server.enqueue(status = 500)
        assertNull(repository.recommendation("n1"))
        server.enqueueFailure(HttpRequestTimeoutException("http://backend.test/v1/recommendations", 5_000))
        assertNull(repository.recommendation("n1"))
    }

    @Test fun consentIsSentToTheRightPurpose() = runTest {
        server.enqueue(body = "{}")
        assertTrue(repository.setConsent("analytics", true) is ApiResult.Success)
        assertEquals("PUT", server.last.method)
        assertEquals("/v1/consents/analytics", server.last.path)
        assertEquals("""{"granted":true}""", server.last.body)
    }

    @Test fun consentFailureIsReportedNotThrown() = runTest {
        server.enqueue(status = 500)
        assertFalse(repository.setConsent("analytics", true) is ApiResult.Success)
    }
}
