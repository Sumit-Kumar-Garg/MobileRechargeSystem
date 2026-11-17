package com.app.recharge.core.telemetry

import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * sessionId lives as long as the process; journeyId identifies one recharge attempt from number entry to the
 * final status, and is sent on API calls so server-side events can be joined to client-side ones.
 */
class SessionManager {
    val sessionId: String = Uuid.random().toString()

    @Volatile
    var currentJourneyId: String? = null
        private set

    fun startJourney(): String = Uuid.random().toString().also { currentJourneyId = it }

    fun endJourney() {
        currentJourneyId = null
    }
}
