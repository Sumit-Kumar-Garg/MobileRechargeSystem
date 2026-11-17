package com.app.recharge.core.network

import kotlin.random.Random

/** W3C trace context, so the server's trace for a request is linked to this client call. */
object Traceparent {
    fun generate(random: Random = Random.Default): String {
        val traceId = hex(random, 16)
        val spanId = hex(random, 8)
        return "00-$traceId-$spanId-01"
    }

    private fun hex(random: Random, bytes: Int): String {
        var s: String
        do {
            s = random.nextBytes(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        } while (s.all { it == '0' })       // all-zero ids are invalid in W3C trace context
        return s
    }
}
