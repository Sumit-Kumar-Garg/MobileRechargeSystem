package com.app.recharge.core

import com.app.recharge.FakeTokens
import com.app.recharge.FixedNetwork
import com.app.recharge.RecordingRecorder
import com.app.recharge.core.network.ApiClient
import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.network.ApiResult
import com.app.recharge.core.network.NetworkState
import com.app.recharge.core.network.TransportType
import com.app.recharge.core.telemetry.SessionManager
import com.app.recharge.feature.recharge.data.ConsentRequest
import com.app.recharge.feature.recharge.data.DemoTokenRequest
import com.app.recharge.feature.recharge.data.NumberDto
import com.app.recharge.feature.recharge.data.TokenResponseDto
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The shared HTTP layer. It replaces the four OkHttp interceptor tests of the Android-only app
 * (trace headers, bearer token, offline short-circuit, call timing).
 */
class ApiClientTest {

    private val server = FakeServer()
    private val numberJson = """{"id":"n1","msisdnMasked":"+49 *** ***4567","market":"DE","futureField":1}"""

    private suspend fun ApiClient.number(): ApiResult<NumberDto> = get("v1/numbers/n1")

    // ---- headers ----------------------------------------------------------------------------------------

    @Test fun addsCorrelationSessionTraceAndLanguageHeaders() = runTest {
        val session = SessionManager()
        server.enqueue(body = numberJson)

        apiClient(server, session = session, language = "de-DE").number()

        val headers = server.last.headers
        assertEquals(session.sessionId, headers[ApiClient.SESSION_ID])
        assertEquals(36, headers.getValue(ApiClient.CORRELATION_ID).length)
        assertTrue(Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-01").matches(headers.getValue(ApiClient.TRACEPARENT)))
        assertEquals("de-DE", headers[ApiClient.ACCEPT_LANGUAGE])
    }

    @Test fun correlationIdIsNewForEveryRequest() = runTest {
        val client = apiClient(server)
        server.enqueue(body = numberJson)
        server.enqueue(body = numberJson)

        client.number()
        client.number()

        assertNotEquals(server.requests[0].headers[ApiClient.CORRELATION_ID], server.requests[1].headers[ApiClient.CORRELATION_ID])
    }

    @Test fun journeyHeaderOnlyWhileAJourneyIsActive() = runTest {
        val session = SessionManager()
        val client = apiClient(server, session = session)
        server.enqueue(body = numberJson)
        server.enqueue(body = numberJson)

        client.number()
        val journey = session.startJourney()
        client.number()

        assertNull(server.requests[0].headers[ApiClient.JOURNEY_ID])
        assertEquals(journey, server.requests[1].headers[ApiClient.JOURNEY_ID])
    }

    @Test fun buildsTheUrlFromBaseUrlPathAndQuery() = runTest {
        server.enqueue(body = numberJson)

        apiClient(server).get<NumberDto>("v1/numbers/n1", mapOf("market" to "DE"))

        assertEquals("GET", server.last.method)
        assertEquals("/v1/numbers/n1", server.last.path)
        assertEquals("market=DE", server.last.query)
    }

    // ---- token ------------------------------------------------------------------------------------------

    @Test fun addsBearerToken() = runTest {
        server.enqueue(body = numberJson)
        apiClient(server, tokens = FakeTokens(accessToken = "abc")).number()
        assertEquals("Bearer abc", server.last.headers[ApiClient.AUTHORIZATION])
    }

    @Test fun sendsNothingWithoutToken() = runTest {
        server.enqueue(body = numberJson)
        apiClient(server).number()
        assertNull(server.last.headers[ApiClient.AUTHORIZATION])
    }

    @Test fun neverSendsTheTokenToTheLoginEndpoint() = runTest {
        server.enqueue(body = """{"accessToken":"new","tokenType":"Bearer","expiresInSeconds":900,"userId":"u1"}""")

        val result = apiClient(server, tokens = FakeTokens(accessToken = "old"))
            .post<DemoTokenRequest, TokenResponseDto>("v1/demo/token", DemoTokenRequest("u1"), authenticated = false)

        assertTrue(result is ApiResult.Success)
        assertNull(server.last.headers[ApiClient.AUTHORIZATION])
        assertEquals("""{"userId":"u1"}""", server.last.body)
    }

    @Test fun a401GetsAFreshTokenOnceAndRepeatsTheCall() = runTest {
        val tokens = FakeTokens(accessToken = "expired")
        val client = apiClient(server, tokens = tokens)
        val rejected = mutableListOf<String>()
        client.refreshToken = { old -> rejected += old; "fresh".also { tokens.accessToken = it } }
        server.enqueue(status = 401)
        server.enqueue(body = numberJson)

        val result = client.number()

        assertTrue(result is ApiResult.Success)
        assertEquals(listOf("expired"), rejected)
        assertEquals(listOf("Bearer expired", "Bearer fresh"), server.requests.map { it.headers[ApiClient.AUTHORIZATION] })
    }

    @Test fun aSecond401IsReturnedSoTheCallCanNeverLoop() = runTest {
        val tokens = FakeTokens(accessToken = "expired")
        val client = apiClient(server, tokens = tokens)
        var refreshes = 0
        client.refreshToken = { refreshes++; "fresh".also { tokens.accessToken = it } }
        server.enqueue(status = 401)
        server.enqueue(status = 401)

        val error = (client.number() as ApiResult.Failure).error

        assertEquals(ApiErrorKind.UNAUTHORIZED, error.kind)
        assertEquals(1, refreshes)
        assertEquals(2, server.requests.size)
    }

    @Test fun whenNoFreshTokenCanBeFetchedThe401IsReturned() = runTest {
        val client = apiClient(server, tokens = FakeTokens(accessToken = "expired"))
        client.refreshToken = { null }
        server.enqueue(status = 401)

        assertEquals(ApiErrorKind.UNAUTHORIZED, (client.number() as ApiResult.Failure).error.kind)
        assertEquals(1, server.requests.size)
    }

    // ---- offline, errors --------------------------------------------------------------------------------

    @Test fun failsFastWhenOfflineWithoutTouchingTheNetwork() = runTest {
        val recorder = RecordingRecorder()
        val client = apiClient(server, network = FixedNetwork(NetworkState.OFFLINE)).also { it.recorder = recorder }

        val error = (client.number() as ApiResult.Failure).error

        assertEquals(ApiErrorKind.OFFLINE, error.kind)
        assertFalse(error.outcomeUnknown)                 // never sent, so safe to try again
        assertTrue(server.requests.isEmpty())
        assertEquals("offline", recorder.calls.single().outcome)
    }

    @Test fun problemJsonBecomesATypedError() = runTest {
        server.enqueue(422, """{"status":422,"detail":"limit","code":"NUMBER_LIMIT_EXCEEDED","correlationId":"c-1"}""")

        val error = (apiClient(server).number() as ApiResult.Failure).error

        assertEquals(ApiErrorKind.CLIENT, error.kind)
        assertEquals(422, error.httpStatus)
        assertEquals("NUMBER_LIMIT_EXCEEDED", error.code)
        assertEquals("c-1", error.correlationId)
    }

    @Test fun timeoutsMeanTheOutcomeIsUnknown() = runTest {
        val recorder = RecordingRecorder()
        val client = apiClient(server).also { it.recorder = recorder }
        server.enqueueFailure(HttpRequestTimeoutException("http://backend.test/v1/numbers/n1", 100))

        val error = (client.number() as ApiResult.Failure).error

        assertEquals(ApiErrorKind.TIMEOUT, error.kind)
        assertTrue(error.outcomeUnknown)
        assertEquals("timeout", recorder.calls.single().outcome)
    }

    @Test fun connectionFailuresBecomeNetworkErrors() = runTest {
        val recorder = RecordingRecorder()
        val client = apiClient(server).also { it.recorder = recorder }
        server.enqueueFailure(IllegalStateException("connection reset"))

        assertEquals(ApiErrorKind.NETWORK, (client.number() as ApiResult.Failure).error.kind)
        assertEquals("io", recorder.calls.single().outcome)
    }

    @Test fun aBodyWeCannotReadIsAnErrorNotACrash() = runTest {
        server.enqueue(body = "<html>maintenance</html>")
        assertEquals(ApiErrorKind.UNKNOWN, (apiClient(server).number() as ApiResult.Failure).error.kind)
    }

    @Test fun noContentAnswersAreNullForOptionalCalls() = runTest {
        server.enqueue(status = 204)
        assertEquals(ApiResult.Success(null), apiClient(server).getOrNull<NumberDto>("v1/recommendations"))
    }

    @Test fun sendIgnoresTheAnswerBody() = runTest {
        server.enqueue(body = "{}")

        val result = apiClient(server).send(HttpMethod.Put, "v1/consents/analytics", ConsentRequest(true))

        assertEquals(ApiResult.Success(Unit), result)
        assertEquals("PUT", server.last.method)
        assertEquals("""{"granted":true}""", server.last.body)
    }

    // ---- telemetry --------------------------------------------------------------------------------------

    @Test fun reportsOneEventPerCallWithTemplatedEndpointAndNetworkType() = runTest {
        val recorder = RecordingRecorder()
        val wifi = FixedNetwork(NetworkState(transport = TransportType.WIFI))
        val client = apiClient(server, network = wifi).also { it.recorder = recorder }
        server.enqueue(body = numberJson, serverTiming = "app;dur=42")

        client.get<NumberDto>("v1/orders/7b0e6c1a-3f52-4c8e-9d0a-1b2c3d4e5f60")

        val call = recorder.calls.single()
        assertEquals("/v1/orders/{id}", call.endpoint)          // ids never leave the phone in telemetry
        assertEquals("GET", call.method)
        assertEquals(200, call.status)
        assertEquals("success", call.outcome)
        assertEquals(42L, call.serverDurationMs)
        assertEquals("wifi", call.networkType)
        assertEquals(numberJson.length.toLong(), call.bytesReceived)
    }

    @Test fun classifiesHttpErrorsAndCountsPostBodies() = runTest {
        val recorder = RecordingRecorder()
        val client = apiClient(server).also { it.recorder = recorder }
        server.enqueue(status = 503)

        client.send(HttpMethod.Put, "v1/consents/analytics", ConsentRequest(true))

        val call = recorder.calls.single()
        assertEquals("http_error", call.outcome)
        assertEquals(503, call.status)
        assertEquals("""{"granted":true}""".length.toLong(), call.bytesSent)
    }

    @Test fun aRefreshedCallIsReportedOnce() = runTest {
        val recorder = RecordingRecorder()
        val tokens = FakeTokens(accessToken = "expired")
        val client = apiClient(server, tokens = tokens).also { it.recorder = recorder }
        client.refreshToken = { "fresh".also { tokens.accessToken = it } }
        server.enqueue(status = 401)
        server.enqueue(body = numberJson)

        client.number()

        assertEquals(listOf(200), recorder.calls.map { it.status })
    }
}
