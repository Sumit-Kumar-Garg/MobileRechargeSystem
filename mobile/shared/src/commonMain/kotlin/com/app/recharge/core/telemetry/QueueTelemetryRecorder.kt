package com.app.recharge.core.telemetry

import kotlin.random.Random
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persists events in a local queue; the flusher uploads them in batches.
 * - Behavioral events (journey, lifecycle, config) are recorded only with analytics consent.
 * - Network-call measurements are operational and carry no personal data, so they are always eligible,
 *   but successful calls are sampled (errors are always kept).
 * - The queue is capped; the oldest events are dropped first.
 */
class QueueTelemetryRecorder(
    private val queue: TelemetryQueue,
    private val consent: ConsentState,
    private val session: SessionManager,
    private val flusher: TelemetryFlusher,
    private val scope: CoroutineScope,
    private val platform: String = "unknown",
    private val clock: () -> Instant = { Clock.System.now() },
    private val successSampleRate: Double = DEFAULT_SAMPLE_RATE,
    private val random: () -> Double = { Random.nextDouble() },
    private val maxQueue: Int = MAX_QUEUE,
) : TelemetryRecorder {

    override fun journey(name: String, props: Map<String, Any>) = behavioral("journey", name, props)
    override fun lifecycle(name: String, props: Map<String, Any>) = behavioral("lifecycle", name, props)
    override fun configChange(name: String, props: Map<String, Any>) = behavioral("config_change", name, props)

    override fun networkCall(event: NetworkCallEvent) {
        if (event.endpoint == TELEMETRY_ENDPOINT) return          // never measure our own uploads
        if (event.outcome == "success" && random() >= successSampleRate) return
        val props = buildMap<String, Any> {
            put("endpoint", event.endpoint)
            put("method", event.method)
            event.status?.let { put("status", it) }
            put("outcome", event.outcome)
            put("durationMs", event.durationMs)
            event.serverDurationMs?.let { put("serverDurationMs", it) }
            event.dnsMs?.let { put("dnsMs", it) }
            event.connectMs?.let { put("connectMs", it) }
            event.tlsMs?.let { put("tlsMs", it) }
            event.ttfbMs?.let { put("ttfbMs", it) }
            put("bytesSent", event.bytesSent)
            put("bytesReceived", event.bytesReceived)
            event.protocol?.let { put("protocol", it) }
            put("networkType", event.networkType)
            put("os", platform)
        }
        enqueue("network_call", "api_call", props)
    }

    private fun behavioral(type: String, name: String, props: Map<String, Any>) {
        if (!consent.analyticsGranted) return
        enqueue(type, name, props + ("os" to platform))
    }

    private fun enqueue(type: String, name: String, props: Map<String, Any>) {
        val now = clock()
        val event = QueuedEvent(
            id = Uuid.random().toString(),
            type = type,
            name = name,
            occurredAt = now.toString(),
            journeyId = session.currentJourneyId,
            orderId = null,
            propsJson = toJson(props).toString(),
            createdAt = now.toEpochMilliseconds(),
        )
        scope.launch {
            queue.insert(event)
            val over = queue.count() - maxQueue
            if (over > 0) queue.deleteOldest(over)
            flusher.flushSoon()
        }
    }

    private fun toJson(props: Map<String, Any>): JsonObject = buildJsonObject {
        props.forEach { (key, value) ->
            when (value) {
                is Boolean -> put(key, value)
                is Number -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    companion object {
        const val MAX_QUEUE = 500
        const val DEFAULT_SAMPLE_RATE = 0.10
        const val TELEMETRY_ENDPOINT = "/v1/telemetry/batch"
    }
}
