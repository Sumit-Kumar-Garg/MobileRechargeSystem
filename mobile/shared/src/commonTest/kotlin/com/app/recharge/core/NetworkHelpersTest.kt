package com.app.recharge.core

import com.app.recharge.core.network.AdaptiveTimeouts
import com.app.recharge.core.network.EndpointTemplates
import com.app.recharge.core.network.NetworkState
import com.app.recharge.core.network.NetworkTransition
import com.app.recharge.core.network.ServerTimingParser
import com.app.recharge.core.network.Traceparent
import com.app.recharge.core.network.TransportType
import com.app.recharge.core.network.mapTransport
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class EndpointTemplatesTest {
    @Test fun replacesUuidsWithPlaceholder() {
        assertEquals(
            "/v1/orders/{id}/pay",
            EndpointTemplates.normalize("/v1/orders/3f2a9c1e-7b4d-4f6e-9a21-0c5d8e7f1a2b/pay"),
        )
    }

    @Test fun replacesLongNumbers() {
        assertEquals("/v1/things/{id}", EndpointTemplates.normalize("/v1/things/123456"))
    }

    @Test fun leavesStaticPathsAlone() {
        assertEquals("/v1/plans", EndpointTemplates.normalize("/v1/plans"))
        assertEquals("/v1/consents/analytics", EndpointTemplates.normalize("/v1/consents/analytics"))
    }

    @Test fun neverLeaksMixedCaseUuid() {
        val out = EndpointTemplates.normalize("/v1/orders/3F2A9C1E-7B4D-4F6E-9A21-0C5D8E7F1A2B")
        assertEquals("/v1/orders/{id}", out)
    }
}

class ServerTimingParserTest {
    @Test fun parsesAppDuration() = assertEquals(12L, ServerTimingParser.appDurationMs("app;dur=12.0"))
    @Test fun roundsToNearestMillisecond() = assertEquals(13L, ServerTimingParser.appDurationMs("app;dur=12.5"))
    @Test fun findsAppAmongSeveralMetrics() =
        assertEquals(40L, ServerTimingParser.appDurationMs("db;dur=5, app;desc=\"x\";dur=40.2, cache;dur=1"))
    @Test fun ignoresOtherMetrics() = assertNull(ServerTimingParser.appDurationMs("db;dur=5"))
    @Test fun handlesMissingOrGarbageHeader() {
        assertNull(ServerTimingParser.appDurationMs(null))
        assertNull(ServerTimingParser.appDurationMs(""))
        assertNull(ServerTimingParser.appDurationMs("app;dur=abc"))
        assertNull(ServerTimingParser.appDurationMs("app"))
    }
}

class TraceparentTest {
    @Test fun matchesW3cFormat() {
        assertTrue(Traceparent.generate().matches(Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-01")))
    }

    @Test fun generatesDifferentIds() = assertNotEquals(Traceparent.generate(), Traceparent.generate())

    @Test fun isDeterministicForASeededRandom() =
        assertEquals(Traceparent.generate(Random(42)), Traceparent.generate(Random(42)))
}

class NetworkStateTest {
    @Test fun transportMappingPrefersUnderlyingNetworkOverVpn() {
        assertEquals(TransportType.WIFI, mapTransport(wifi = true, cellular = false, ethernet = false, vpn = true))
        assertEquals(TransportType.CELLULAR, mapTransport(wifi = false, cellular = true, ethernet = false, vpn = true))
        assertEquals(TransportType.VPN, mapTransport(wifi = false, cellular = false, ethernet = false, vpn = true))
        assertEquals(TransportType.UNKNOWN, mapTransport(false, false, false, false))
    }

    @Test fun telemetryTypeUsesTheBackendAllowlist() {
        assertEquals("wifi", NetworkState(transport = TransportType.WIFI).telemetryType)
        assertEquals("cellular", NetworkState(transport = TransportType.CELLULAR).telemetryType)
        assertEquals("unknown", NetworkState(transport = TransportType.NONE).telemetryType)
        assertEquals("unknown", NetworkState(transport = TransportType.UNKNOWN).telemetryType)
    }

    @Test fun slowMeansLowBandwidthOrNotValidatedWhileConnected() {
        assertTrue(NetworkState(connected = true, downKbps = 500).isSlow)
        assertTrue(NetworkState(connected = true, validated = false).isSlow)
        assertFalse(NetworkState(connected = true, downKbps = 20_000).isSlow)
        assertFalse(NetworkState(connected = true, downKbps = 0).isSlow)          // unknown estimate is not "slow"
        assertFalse(NetworkState.OFFLINE.isSlow)
    }

    @Test fun describesTransitions() {
        val wifi = NetworkState(transport = TransportType.WIFI, downKbps = 50_000)
        val cell = NetworkState(transport = TransportType.CELLULAR, downKbps = 50_000)
        val slowCell = NetworkState(transport = TransportType.CELLULAR, downKbps = 400)
        assertEquals("network_lost", NetworkTransition.describe(wifi, NetworkState.OFFLINE))
        assertEquals("network_restored", NetworkTransition.describe(NetworkState.OFFLINE, wifi))
        assertEquals("network_handover", NetworkTransition.describe(wifi, cell))
        assertEquals("network_quality_changed", NetworkTransition.describe(cell, slowCell))
        assertNull(NetworkTransition.describe(wifi, wifi.copy(downKbps = 60_000)))
    }
}

class AdaptiveTimeoutsTest {
    private val good = NetworkState(transport = TransportType.WIFI, downKbps = 50_000)
    private val slow = NetworkState(transport = TransportType.CELLULAR, downKbps = 300)

    @Test fun goodNetworkGetsTightTimeouts() {
        val t = AdaptiveTimeouts.forState(good, isPayment = false)
        assertEquals(5_000L, t.connectMs)
        assertEquals(10_000L, t.readMs)
    }

    @Test fun slowNetworkGetsGenerousTimeouts() {
        val t = AdaptiveTimeouts.forState(slow, isPayment = false)
        assertEquals(10_000L, t.connectMs)
        assertEquals(20_000L, t.readMs)
    }

    @Test fun paymentsAlwaysGetALongReadTimeout() {
        assertEquals(AdaptiveTimeouts.PAYMENT_READ_MS, AdaptiveTimeouts.forState(good, isPayment = true).readMs)
        assertEquals(AdaptiveTimeouts.PAYMENT_READ_MS, AdaptiveTimeouts.forState(slow, isPayment = true).readMs)
    }

    @Test fun onlyNoConnectionAtAllCountsAsOffline() {
        assertTrue(AdaptiveTimeouts.isOffline(NetworkState.OFFLINE))
        assertFalse(AdaptiveTimeouts.isOffline(good))
        assertFalse(AdaptiveTimeouts.isOffline(slow))
    }

    @Test fun notValidatedNetworksCountAsSlow() {
        assertEquals(20_000L, AdaptiveTimeouts.forState(good.copy(validated = false), isPayment = false).readMs)
    }
}
