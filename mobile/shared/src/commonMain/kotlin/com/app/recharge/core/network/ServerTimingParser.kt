package com.app.recharge.core.network

import kotlin.math.roundToLong

/** Reads "Server-Timing: app;dur=12.5" so total latency can be split into server time and network time. */
object ServerTimingParser {
    fun appDurationMs(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        for (metric in header.split(',')) {
            val parts = metric.trim().split(';').map { it.trim() }
            if (parts.firstOrNull() != "app") continue
            val dur = parts.drop(1).firstOrNull { it.startsWith("dur=") }?.removePrefix("dur=")
            dur?.toDoubleOrNull()?.let { return it.roundToLong() }
        }
        return null
    }
}
