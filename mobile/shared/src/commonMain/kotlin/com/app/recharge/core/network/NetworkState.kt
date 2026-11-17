package com.app.recharge.core.network

enum class TransportType(val label: String) {
    WIFI("wifi"), CELLULAR("cellular"), ETHERNET("ethernet"), VPN("vpn"), UNKNOWN("unknown"), NONE("none")
}

/**
 * What the phone's connection looks like right now. Measured, not guessed: it drives adaptive timeouts,
 * the offline/slow banners and the network dimension of every latency measurement we send to the backend.
 */
data class NetworkState(
    val connected: Boolean = true,
    val transport: TransportType = TransportType.UNKNOWN,
    val metered: Boolean = false,
    val validated: Boolean = true,
    val downKbps: Int = 0,
    val upKbps: Int = 0,
    val dataSaver: Boolean = false,
    val powerSave: Boolean = false,
) {
    /** Value from the backend's allowlist of network types. */
    val telemetryType: String
        get() = when (transport) {
            TransportType.WIFI, TransportType.CELLULAR, TransportType.ETHERNET, TransportType.VPN -> transport.label
            else -> "unknown"
        }

    /** Connected, but either not validated (captive portal, no real internet) or low estimated bandwidth. */
    val isSlow: Boolean
        get() = connected && (!validated || downKbps in 1..SLOW_KBPS)

    companion object {
        const val SLOW_KBPS = 1500
        val OFFLINE = NetworkState(connected = false, transport = TransportType.NONE, validated = false)
    }
}

interface NetworkStateProvider {
    val current: NetworkState
}

/** Watches the device's connection. Implemented per platform (ConnectivityManager / NWPathMonitor). */
interface NetworkMonitor : NetworkStateProvider {
    val state: kotlinx.coroutines.flow.StateFlow<NetworkState>
    override val current: NetworkState get() = state.value

    /** Starts listening. Safe to call more than once. */
    fun start()
}

/** Pure mapping so it can be unit tested. Wi-Fi wins over VPN because a VPN rides on an underlying transport. */
fun mapTransport(wifi: Boolean, cellular: Boolean, ethernet: Boolean, vpn: Boolean): TransportType = when {
    wifi -> TransportType.WIFI
    cellular -> TransportType.CELLULAR
    ethernet -> TransportType.ETHERNET
    vpn -> TransportType.VPN
    else -> TransportType.UNKNOWN
}

/** Describes a change worth recording, or null when nothing notable happened. */
object NetworkTransition {
    fun describe(prev: NetworkState, next: NetworkState): String? = when {
        prev.connected && !next.connected -> "network_lost"
        !prev.connected && next.connected -> "network_restored"
        prev.connected && next.connected && prev.transport != next.transport -> "network_handover"
        prev.isSlow != next.isSlow -> "network_quality_changed"
        else -> null
    }
}
