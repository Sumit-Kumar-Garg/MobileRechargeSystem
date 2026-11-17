package com.app.recharge.core.telemetry

/** One network call, reduced to numbers. No URLs with ids, no bodies, no headers. */
data class NetworkCallEvent(
    val endpoint: String,
    val method: String,
    val status: Int?,
    val outcome: String,                 // success | http_error | timeout | dns | tls | offline | io
    val durationMs: Long,
    val serverDurationMs: Long?,
    val dnsMs: Long?,
    val connectMs: Long?,
    val tlsMs: Long?,
    val ttfbMs: Long?,
    val bytesSent: Long,
    val bytesReceived: Long,
    val protocol: String?,
    val connectionReused: Boolean,
    val networkType: String,
)

/**
 * The only way features record analytics. Implementations decide about consent, sampling and delivery.
 * Props must never contain personal data (phone numbers, emails, tokens).
 */
interface TelemetryRecorder {
    fun journey(name: String, props: Map<String, Any> = emptyMap())
    fun lifecycle(name: String, props: Map<String, Any> = emptyMap())
    fun configChange(name: String, props: Map<String, Any> = emptyMap())
    fun networkCall(event: NetworkCallEvent)
}

object NoOpTelemetryRecorder : TelemetryRecorder {
    override fun journey(name: String, props: Map<String, Any>) = Unit
    override fun lifecycle(name: String, props: Map<String, Any>) = Unit
    override fun configChange(name: String, props: Map<String, Any>) = Unit
    override fun networkCall(event: NetworkCallEvent) = Unit
}

interface TelemetryFlusher {
    fun flushSoon()
}
