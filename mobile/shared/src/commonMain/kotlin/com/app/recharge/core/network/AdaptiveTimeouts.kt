package com.app.recharge.core.network

data class Timeouts(val connectMs: Long, val readMs: Long, val writeMs: Long)

object AdaptiveTimeouts {
    const val PAYMENT_READ_MS = 30_000L

    /**
     * Generous timeouts on slow links so slow is not mistaken for broken; tight ones on good links so real
     * failures surface quickly. Payments always get a long read timeout: an early abort would only make the
     * outcome unknown.
     */
    fun forState(state: NetworkState, isPayment: Boolean): Timeouts {
        val base = if (state.isSlow) Timeouts(10_000, 20_000, 20_000) else Timeouts(5_000, 10_000, 10_000)
        return if (isPayment) base.copy(readMs = maxOf(base.readMs, PAYMENT_READ_MS)) else base
    }

    /** No connection at all: fail at once instead of waiting for a timeout. */
    fun isOffline(state: NetworkState): Boolean = !state.connected && state.transport == TransportType.NONE
}
