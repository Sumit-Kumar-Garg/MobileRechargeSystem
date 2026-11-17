package com.app.recharge.core.network

import com.app.recharge.AppConfig
import com.app.recharge.core.security.TokenStore
import com.app.recharge.core.telemetry.NetworkCallEvent
import com.app.recharge.core.telemetry.NoOpTelemetryRecorder
import com.app.recharge.core.telemetry.SessionManager
import com.app.recharge.core.telemetry.TelemetryRecorder
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/** A raw answer: status, body text and the server's own timing. */
data class RawResponse(val status: Int, val body: String, val serverTiming: String?)

/**
 * The one place every HTTP call goes through, on Android and iOS alike. For each call it:
 * 1. fails at once when the device is offline;
 * 2. picks timeouts from the current network (payments always get a long read timeout);
 * 3. adds trace, correlation, session, journey and language headers;
 * 4. adds the bearer token, and on a 401 gets a fresh token once and retries;
 * 5. measures the call and reports it as telemetry (endpoint template only, never ids);
 * 6. returns an [ApiResult]: screens never see exceptions.
 */
class ApiClient(
    engine: HttpClientEngine,
    private val config: AppConfig,
    private val tokens: TokenStore,
    private val session: SessionManager,
    private val network: NetworkStateProvider,
    private val languageTag: () -> String,
    @PublishedApi internal val json: Json,
) {
    private val http = HttpClient(engine) {
        expectSuccess = false                 // non-2xx answers are mapped by us, not thrown
        install(HttpTimeout)
    }

    /** Set after construction: the recorder's uploader itself needs this client. */
    var recorder: TelemetryRecorder = NoOpTelemetryRecorder

    /**
     * Given the token the server just rejected, returns a fresh one, or null.
     * Set by the app graph (AuthRepository); called at most once per request.
     */
    var refreshToken: (suspend (rejectedToken: String) -> String?)? = null

    suspend inline fun <reified T> get(path: String, query: Map<String, String> = emptyMap()): ApiResult<T> =
        decodingSafe {
            execute(HttpMethod.Get, path, query = query).map { json.decodeFromString(serializer<T>(), it.body) }
        }

    /** For endpoints that may answer 204 (nothing to return): null instead of a value. */
    suspend inline fun <reified T> getOrNull(path: String, query: Map<String, String> = emptyMap()): ApiResult<T?> =
        decodingSafe {
            execute(HttpMethod.Get, path, query = query).map {
                if (it.status == 204 || it.body.isBlank()) null else json.decodeFromString(serializer<T>(), it.body)
            }
        }

    suspend inline fun <reified B, reified T> post(
        path: String,
        body: B,
        headers: Map<String, String> = emptyMap(),
        authenticated: Boolean = true,
    ): ApiResult<T> = decodingSafe {
        execute(
            HttpMethod.Post, path,
            bodyJson = json.encodeToString(serializer<B>(), body), headers = headers, authenticated = authenticated,
        ).map { json.decodeFromString(serializer<T>(), it.body) }
    }

    /** A call whose answer body does not matter (204, or a body we ignore). */
    suspend inline fun <reified B> send(method: HttpMethod, path: String, body: B): ApiResult<Unit> =
        execute(method, path, bodyJson = json.encodeToString(serializer<B>(), body)).map { }

    @PublishedApi
    internal suspend fun execute(
        method: HttpMethod,
        path: String,
        query: Map<String, String> = emptyMap(),
        bodyJson: String? = null,
        headers: Map<String, String> = emptyMap(),
        authenticated: Boolean = true,
    ): ApiResult<RawResponse> {
        val state = network.current
        val started = TimeSource.Monotonic.markNow()
        if (AdaptiveTimeouts.isOffline(state)) {
            report(method, path, null, "offline", 0, null, bodyJson, 0, state)
            return ApiResult.Failure(ApiError(ApiErrorKind.OFFLINE))
        }
        val timeouts = AdaptiveTimeouts.forState(state, isPayment = path.endsWith("/pay"))

        var refreshed = false
        while (true) {
            val token = if (authenticated) tokens.accessToken else null
            val raw = try {
                val response = http.request(config.baseUrl.trimEnd('/') + "/" + path.trimStart('/')) {
                    this.method = method
                    query.forEach { (key, value) -> parameter(key, value) }
                    header(CORRELATION_ID, Uuid.random().toString())
                    header(SESSION_ID, session.sessionId)
                    header(TRACEPARENT, Traceparent.generate())
                    header(ACCEPT_LANGUAGE, languageTag())
                    session.currentJourneyId?.let { header(JOURNEY_ID, it) }
                    token?.let { header(AUTHORIZATION, "Bearer $it") }
                    headers.forEach { (key, value) -> header(key, value) }
                    bodyJson?.let { setBody(TextContent(it, ContentType.Application.Json)) }
                    timeout {
                        connectTimeoutMillis = timeouts.connectMs
                        socketTimeoutMillis = timeouts.readMs
                        requestTimeoutMillis = timeouts.connectMs + timeouts.readMs
                    }
                }
                RawResponse(response.status.value, response.bodyAsText(), response.headers[SERVER_TIMING])
            } catch (e: CancellationException) {
                throw e                                   // the screen went away: never swallow this
            } catch (e: Exception) {
                val timedOut = e is HttpRequestTimeoutException || e is ConnectTimeoutException || e is SocketTimeoutException
                report(method, path, null, if (timedOut) "timeout" else "io", elapsedMs(started), null, bodyJson, 0, state)
                return ApiResult.Failure(ApiError(if (timedOut) ApiErrorKind.TIMEOUT else ApiErrorKind.NETWORK))
            }

            // One retry with a fresh token; a second 401 goes back to the caller, so this can never loop.
            val refresh = refreshToken
            if (raw.status == 401 && token != null && !refreshed && refresh != null) {
                refreshed = true
                if (refresh(token) != null) continue
            }

            val ok = raw.status in 200..299
            report(
                method, path, raw.status, if (ok) "success" else "http_error", elapsedMs(started),
                ServerTimingParser.appDurationMs(raw.serverTiming), bodyJson, raw.body.length.toLong(), state,
            )
            return if (ok) ApiResult.Success(raw) else ApiResult.Failure(httpError(raw.status, raw.body))
        }
    }

    private fun elapsedMs(started: TimeSource.Monotonic.ValueTimeMark) = started.elapsedNow().inWholeMilliseconds

    private fun report(
        method: HttpMethod, path: String, status: Int?, outcome: String, durationMs: Long, serverMs: Long?,
        bodyJson: String?, bytesReceived: Long, state: NetworkState,
    ) {
        recorder.networkCall(
            NetworkCallEvent(
                endpoint = EndpointTemplates.normalize("/" + path.trimStart('/')),
                method = method.value,
                status = status,
                outcome = outcome,
                durationMs = durationMs,
                serverDurationMs = serverMs,
                // Connection phases (DNS, connect, TLS) are not visible through the shared HTTP layer.
                dnsMs = null, connectMs = null, tlsMs = null, ttfbMs = null,
                bytesSent = bodyJson?.length?.toLong() ?: 0,
                bytesReceived = bytesReceived,
                protocol = null,
                connectionReused = false,
                networkType = state.telemetryType,
            ),
        )
    }

    companion object {
        const val CORRELATION_ID = "X-Correlation-Id"
        const val SESSION_ID = "X-Session-Id"
        const val JOURNEY_ID = "X-Journey-Id"
        const val TRACEPARENT = "traceparent"
        const val ACCEPT_LANGUAGE = "Accept-Language"
        const val AUTHORIZATION = "Authorization"
        const val SERVER_TIMING = "Server-Timing"
    }
}

/** A body we cannot read is an error result, not a crash. (SerializationException is an IllegalArgumentException.) */
@PublishedApi
internal inline fun <T> decodingSafe(block: () -> ApiResult<T>): ApiResult<T> = try {
    block()
} catch (e: IllegalArgumentException) {
    ApiResult.Failure(ApiError(ApiErrorKind.UNKNOWN, message = "Unexpected response"))
}
