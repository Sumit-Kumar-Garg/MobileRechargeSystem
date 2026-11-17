package com.app.recharge.core.telemetry

import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.network.ApiResult
import com.app.recharge.feature.recharge.data.TelemetryBatchDto
import com.app.recharge.feature.recharge.data.TelemetryEventDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Sends queued events in batches. A batch is deleted only after the server accepted it.
 * Client errors (4xx except 408/429) mean the batch can never succeed, so it is dropped instead of being
 * retried forever; everything else keeps the data and reports failure so it is retried later.
 */
class TelemetryUploader(
    private val queue: TelemetryQueue,
    private val send: suspend (TelemetryBatchDto) -> ApiResult<Unit>,
    private val session: SessionManager,
    private val appVersion: String,
    private val json: Json = Json,
) {
    /** @return true when the queue is empty afterwards, false when something should be retried later */
    suspend fun uploadAll(maxBatches: Int = MAX_BATCHES): Boolean {
        repeat(maxBatches) {
            val rows = queue.oldest(BATCH_SIZE)
            if (rows.isEmpty()) return true
            val body = TelemetryBatchDto(session.sessionId, appVersion, rows.map(::toDto))
            when (val result = send(body)) {
                is ApiResult.Success -> queue.delete(rows.map { it.id })
                is ApiResult.Failure -> {
                    val status = result.error.httpStatus
                    val permanent = result.error.kind == ApiErrorKind.CLIENT && status != 408 && status != 429
                    if (permanent) queue.delete(rows.map { it.id }) else return false
                }
            }
        }
        return queue.count() == 0
    }

    private fun toDto(e: QueuedEvent) = TelemetryEventDto(
        id = e.id,
        type = e.type,
        name = e.name,
        occurredAt = e.occurredAt,
        journeyId = e.journeyId,
        orderId = e.orderId,
        props = runCatching { json.parseToJsonElement(e.propsJson) as JsonObject }.getOrDefault(JsonObject(emptyMap())),
    )

    companion object {
        const val BATCH_SIZE = 100
        const val MAX_BATCHES = 10
    }
}
