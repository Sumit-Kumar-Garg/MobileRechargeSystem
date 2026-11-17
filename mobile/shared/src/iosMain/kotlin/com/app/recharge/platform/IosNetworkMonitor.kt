package com.app.recharge.platform

import com.app.recharge.core.network.NetworkMonitor
import com.app.recharge.core.network.NetworkState
import com.app.recharge.core.network.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_get_main_queue

/** Tracks the connection with NWPathMonitor, the iOS counterpart of Android's ConnectivityManager callback. */
class IosNetworkMonitor : NetworkMonitor {

    // Assume a connection until the first update arrives, so the very first request is not refused as offline.
    private val _state = MutableStateFlow(NetworkState())
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val monitor = nw_path_monitor_create()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        nw_path_monitor_set_update_handler(monitor) { path ->
            val connected = nw_path_get_status(path) == nw_path_status_satisfied
            _state.value = if (!connected) {
                NetworkState.OFFLINE
            } else {
                NetworkState(
                    connected = true,
                    transport = when {
                        nw_path_uses_interface_type(path, nw_interface_type_wifi) -> TransportType.WIFI
                        nw_path_uses_interface_type(path, nw_interface_type_cellular) -> TransportType.CELLULAR
                        nw_path_uses_interface_type(path, nw_interface_type_wired) -> TransportType.ETHERNET
                        else -> TransportType.UNKNOWN
                    },
                    metered = nw_path_is_expensive(path),
                    validated = true,
                    dataSaver = nw_path_is_constrained(path),      // Low Data Mode
                )
            }
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }
}
