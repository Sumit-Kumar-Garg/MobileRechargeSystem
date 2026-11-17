package com.app.recharge.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.app.recharge.core.network.NetworkMonitor
import com.app.recharge.core.network.NetworkState
import com.app.recharge.core.network.mapTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks the default network via ConnectivityManager. */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val _state = MutableStateFlow(readNow())
    private var started = false

    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _state.value = fromCapabilities(caps)
        }

        override fun onLost(network: Network) {
            _state.value = offline()
        }
    }

    @Synchronized
    override fun start() {
        if (started) return
        started = true
        connectivity.registerDefaultNetworkCallback(callback)
    }

    private fun readNow(): NetworkState {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return if (caps == null) offline() else fromCapabilities(caps)
    }

    private fun offline() =
        NetworkState.OFFLINE.copy(dataSaver = isDataSaver(), powerSave = power?.isPowerSaveMode == true)

    private fun fromCapabilities(caps: NetworkCapabilities) = NetworkState(
        connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        transport = mapTransport(
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        ),
        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        downKbps = caps.linkDownstreamBandwidthKbps,
        upKbps = caps.linkUpstreamBandwidthKbps,
        dataSaver = isDataSaver(),
        powerSave = power?.isPowerSaveMode == true,
    )

    private fun isDataSaver() =
        connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
}
