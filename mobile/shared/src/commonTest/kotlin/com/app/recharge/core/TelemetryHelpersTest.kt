package com.app.recharge.core

import com.app.recharge.core.telemetry.ConfigDiff
import com.app.recharge.core.telemetry.ConfigSnapshot
import com.app.recharge.core.telemetry.SessionManager
import com.app.recharge.core.telemetry.WindowBuckets
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ConfigDiffTest {
    private val base = ConfigSnapshot("portrait", "de-DE", darkMode = false, fontScale = 1.0f, windowSize = "compact")

    @Test fun noChangeIsEmpty() = assertTrue(ConfigDiff.diff(base, base.copy()).isEmpty())

    @Test fun detectsRotation() = assertEquals(listOf("orientation"), ConfigDiff.diff(base, base.copy(orientation = "landscape")))

    @Test fun detectsSeveralChangesAtOnce() {
        val next = base.copy(orientation = "landscape", darkMode = true, windowSize = "medium")
        assertEquals(listOf("orientation", "dark_mode", "window_size"), ConfigDiff.diff(base, next))
    }

    @Test fun detectsLocaleAndFontScale() {
        assertEquals(listOf("locale", "font_scale"), ConfigDiff.diff(base, base.copy(locale = "en-GB", fontScale = 1.3f)))
    }

    @Test fun windowBucketsFollowMaterialBreakpoints() {
        assertEquals("compact", WindowBuckets.of(360))
        assertEquals("compact", WindowBuckets.of(599))
        assertEquals("medium", WindowBuckets.of(600))
        assertEquals("medium", WindowBuckets.of(839))
        assertEquals("expanded", WindowBuckets.of(840))
    }
}

class SessionManagerTest {
    @Test fun sessionIdIsStable() {
        val s = SessionManager()
        assertEquals(s.sessionId, s.sessionId)
    }

    @Test fun journeyStartsAndEnds() {
        val s = SessionManager()
        assertNull(s.currentJourneyId)
        val id = s.startJourney()
        assertEquals(id, s.currentJourneyId)
        s.endJourney()
        assertNull(s.currentJourneyId)
    }

    @Test fun eachJourneyGetsANewId() {
        val s = SessionManager()
        val first = s.startJourney()
        val second = s.startJourney()
        assertNotNull(second)
        assertTrue(first != second)
    }
}
