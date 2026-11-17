package com.app.recharge.core

import com.app.recharge.AppConfig
import com.app.recharge.FakeTokens
import com.app.recharge.FixedNetwork
import com.app.recharge.core.network.ApiClient
import com.app.recharge.core.telemetry.SessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json

/** One request as the fake server saw it. */
data class SeenRequest(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: String)

/**
 * A fake backend for tests: answers are queued, requests are recorded. It replaces the real HTTP engine,
 * so the same tests run on the JVM (Android) and on the iOS simulator.
 */
class FakeServer {
    private val answers = ArrayDeque<suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()
    val requests = mutableListOf<SeenRequest>()

    val engine = MockEngine { request ->
        requests += SeenRequest(
            method = request.method.value,
            path = request.url.encodedPath,
            query = request.url.encodedQuery,
            headers = request.headers.names().associateWith { request.headers[it].orEmpty() },
            body = (request.body as? TextContent)?.text.orEmpty(),
        )
        val answer = answers.removeFirstOrNull() ?: error("No answer queued for ${request.url}")
        answer(request)
    }

    fun enqueue(status: Int = 200, body: String = "", serverTiming: String? = null) {
        answers += {
            val headers = if (serverTiming == null) headersOf(HttpHeaders.ContentType, "application/json")
            else headersOf(HttpHeaders.ContentType to listOf("application/json"), "Server-Timing" to listOf(serverTiming))
            respond(body, HttpStatusCode.fromValue(status), headers)
        }
    }

    /** The call fails before any answer arrives (connection reset, timeout, ...). */
    fun enqueueFailure(cause: Throwable) {
        answers += { throw cause }
    }

    val last: SeenRequest get() = requests.last()
}

val testJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

fun apiClient(
    server: FakeServer,
    tokens: FakeTokens = FakeTokens(),
    session: SessionManager = SessionManager(),
    network: FixedNetwork = FixedNetwork(),
    language: String = "de-DE",
) = ApiClient(
    engine = server.engine,
    config = AppConfig(baseUrl = "http://backend.test/", appVersion = "1.0.0"),
    tokens = tokens,
    session = session,
    network = network,
    languageTag = { language },
    json = testJson,
)
