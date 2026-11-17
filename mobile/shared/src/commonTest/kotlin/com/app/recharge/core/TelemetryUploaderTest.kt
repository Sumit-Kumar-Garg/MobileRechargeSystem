package com.app.recharge.core

import com.app.recharge.FakeTelemetryQueue
import com.app.recharge.core.network.ApiError
import com.app.recharge.core.network.ApiErrorKind
import com.app.recharge.core.network.ApiResult
import com.app.recharge.core.network.httpError
import com.app.recharge.core.telemetry.QueuedEvent
import com.app.recharge.core.telemetry.SessionManager
import com.app.recharge.core.telemetry.TelemetryUploader
import com.app.recharge.feature.recharge.data.TelemetryBatchDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

class TelemetryUploaderTest {

    private val queue = FakeTelemetryQueue()
    private val session = SessionManager()
    private val sent = mutableListOf<TelemetryBatchDto>()

    /** Answers given to the uploader, in order; the last one repeats. */
    private var answers: List<ApiResult<Unit>> = listOf(ApiResult.Success(Unit))

    private val uploader = TelemetryUploader(
        queue = queue,
        send = { batch ->
            sent += batch
            answers[minOf(sent.size - 1, answers.lastIndex)]
        },
        session = session,
        appVersion = "1.0.0",
    )

    private fun seed(count: Int) = repeat(count) { i ->
        queue.rows += QueuedEvent(
            id = "id-$i", type = "journey", name = "event_$i", occurredAt = "2026-11-07T10:00:00Z",
            journeyId = null, orderId = null, propsJson = """{"plan":"M-28"}""", createdAt = i.toLong(),
        )
    }

    private fun status(code: Int): ApiResult<Unit> = ApiResult.Failure(httpError(code, null))

    @Test fun uploadsADeliveredBatchAndDeletesIt() = runTest {
        seed(3)

        assertTrue(uploader.uploadAll())

        assertTrue(queue.rows.isEmpty())
        val batch = sent.single()
        assertEquals(session.sessionId, batch.sessionId)
        assertEquals("1.0.0", batch.appVersion)
        assertEquals(listOf("event_0", "event_1", "event_2"), batch.events.map { it.name })
        assertEquals("M-28", batch.events[0].props["plan"]!!.jsonPrimitive.content)
    }

    @Test fun nothingToSendMeansNoRequest() = runTest {
        assertTrue(uploader.uploadAll())
        assertTrue(sent.isEmpty())
    }

    @Test fun networkFailureKeepsTheDataForALaterRetry() = runTest {
        listOf(ApiErrorKind.OFFLINE, ApiErrorKind.NETWORK, ApiErrorKind.TIMEOUT).forEach { kind ->
            queue.rows.clear()
            seed(2)
            answers = listOf(ApiResult.Failure(ApiError(kind)))
            assertFalse(uploader.uploadAll(), "kind $kind")
            assertEquals(2, queue.rows.size, "kind $kind")
        }
    }

    @Test fun retryableStatusesKeepTheData() = runTest {
        listOf(500, 503, 401, 408, 429).forEach { code ->
            queue.rows.clear()
            seed(2)
            answers = listOf(status(code))
            assertFalse(uploader.uploadAll(), "status $code")
            assertEquals(2, queue.rows.size, "status $code")
        }
    }

    @Test fun aPermanentlyRejectedBatchIsDroppedInsteadOfRetriedForever() = runTest {
        seed(2)
        answers = listOf(status(400))
        assertTrue(uploader.uploadAll())
        assertTrue(queue.rows.isEmpty())
    }

    @Test fun largeQueuesAreSentInSeveralBatches() = runTest {
        seed(150)

        assertTrue(uploader.uploadAll())

        assertEquals(listOf(100, 50), sent.map { it.events.size })
        assertTrue(queue.rows.isEmpty())
    }

    @Test fun stopsAtTheFirstFailureAndKeepsTheRest() = runTest {
        seed(150)
        answers = listOf(ApiResult.Success(Unit), status(500))

        assertFalse(uploader.uploadAll())

        assertEquals(50, queue.rows.size)
    }

    @Test fun corruptStoredPropsDoNotBreakTheBatch() = runTest {
        seed(1)
        queue.rows[0] = queue.rows[0].copy(propsJson = "not json")

        assertTrue(uploader.uploadAll())
        assertTrue(sent.single().events[0].props.isEmpty())
    }
}
