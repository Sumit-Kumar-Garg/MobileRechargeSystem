package com.app.recharge.core

import com.app.recharge.FakeConsent
import com.app.recharge.FakeFlusher
import com.app.recharge.FakeTelemetryQueue
import com.app.recharge.core.telemetry.NetworkCallEvent
import com.app.recharge.core.telemetry.QueueTelemetryRecorder
import com.app.recharge.core.telemetry.SessionManager
import kotlinx.datetime.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class QueueTelemetryRecorderTest {

    private val dao = FakeTelemetryQueue()
    private val consent = FakeConsent(analyticsGranted = true)
    private val session = SessionManager()
    private val flusher = FakeFlusher()
    private var tick = 0L
    private var roll = 0.99

    private fun recorder(maxQueue: Int = 500, sampleRate: Double = 0.10) = QueueTelemetryRecorder(
        queue = dao, consent = consent, session = session, flusher = flusher,
        scope = CoroutineScope(Dispatchers.Unconfined),
        platform = "ios",
        clock = { Instant.fromEpochMilliseconds(1_000 + tick++) },
        successSampleRate = sampleRate, random = { roll }, maxQueue = maxQueue,
    )

    private fun call(outcome: String = "success", endpoint: String = "/v1/plans") = NetworkCallEvent(
        endpoint = endpoint, method = "GET", status = if (outcome == "success") 200 else null, outcome = outcome,
        durationMs = 120, serverDurationMs = 30, dnsMs = 5, connectMs = 20, tlsMs = 40, ttfbMs = 60,
        bytesSent = 0, bytesReceived = 512, protocol = "h2", connectionReused = false, networkType = "wifi",
    )

    private fun props(index: Int = 0): JsonObject = Json.parseToJsonElement(dao.rows.sortedBy { it.createdAt }[index].propsJson).jsonObject

    @Test fun behavioralEventsAreDroppedWithoutConsent() {
        consent.analyticsGranted = false
        val r = recorder()
        r.journey("plans_viewed"); r.lifecycle("app_foreground"); r.configChange("config_changed")
        assertTrue(dao.rows.isEmpty())
        assertEquals(0, flusher.flushes)
    }

    @Test fun behavioralEventsAreStoredWithConsentAndCarryTheJourneyId() {
        val journey = session.startJourney()
        val r = recorder()
        r.journey("plan_selected", mapOf("plan" to "M-28", "recommended" to true, "count" to 3))
        r.lifecycle("app_background")
        r.configChange("config_changed")

        assertEquals(listOf("journey", "lifecycle", "config_change"), dao.rows.map { it.type })
        assertEquals(journey, dao.rows[0].journeyId)
        val p = props(0)
        assertEquals("M-28", p["plan"]!!.jsonPrimitive.content)
        assertTrue(p["recommended"]!!.jsonPrimitive.boolean)
        assertEquals(3, p["count"]!!.jsonPrimitive.int)
    }

    @Test fun eventIdsAreUniqueUuids() {
        val r = recorder()
        r.journey("a"); r.journey("b")
        assertEquals(2, dao.rows.map { it.id }.toSet().size)
        assertTrue(dao.rows.all { it.id.matches(Regex("[0-9a-f-]{36}")) })
    }

    @Test fun networkMeasurementsDoNotNeedConsent() {
        consent.analyticsGranted = false
        recorder().networkCall(call(outcome = "timeout"))
        assertEquals(1, dao.rows.size)
        assertEquals("network_call", dao.rows[0].type)
    }

    @Test fun errorsAreAlwaysKeptButMostSuccessesAreSampledOut() {
        val r = recorder(sampleRate = 0.10)
        roll = 0.50
        r.networkCall(call(outcome = "success"))
        assertTrue(dao.rows.isEmpty())

        roll = 0.05
        r.networkCall(call(outcome = "success"))
        assertEquals(1, dao.rows.size)

        roll = 0.99
        r.networkCall(call(outcome = "timeout"))
        r.networkCall(call(outcome = "http_error"))
        assertEquals(3, dao.rows.size)
    }

    @Test fun uploadsAreNeverMeasured() {
        roll = 0.0
        recorder().networkCall(call(endpoint = "/v1/telemetry/batch"))
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun networkCallPropsContainTheMeasurementsAndNoUrlsOrIds() {
        roll = 0.0
        recorder().networkCall(call())
        val p = props()
        assertEquals("/v1/plans", p["endpoint"]!!.jsonPrimitive.content)
        assertEquals("wifi", p["networkType"]!!.jsonPrimitive.content)
        assertEquals(120, p["durationMs"]!!.jsonPrimitive.int)
        assertEquals(30, p["serverDurationMs"]!!.jsonPrimitive.int)
        assertEquals("ios", p["os"]!!.jsonPrimitive.content)      // which platform measured it
        assertFalse(p.containsKey("url"))
    }

    @Test fun theQueueIsCappedAndTheOldestEventsAreDroppedFirst() {
        val r = recorder(maxQueue = 3)
        listOf("e1", "e2", "e3", "e4", "e5").forEach { r.journey(it) }
        assertEquals(listOf("e3", "e4", "e5"), dao.rows.sortedBy { it.createdAt }.map { it.name })
    }

    @Test fun everyStoredEventSchedulesAnUpload() {
        val r = recorder()
        r.journey("a"); r.lifecycle("b")
        assertEquals(2, flusher.flushes)
    }
}
