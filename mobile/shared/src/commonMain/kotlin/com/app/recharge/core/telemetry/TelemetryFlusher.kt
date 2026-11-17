package com.app.recharge.core.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Batches uploads: the first event schedules one upload a little later, further events ride along.
 * Runs in the app's own scope on both platforms. Events recorded just before the app is closed stay in the
 * queue and are sent on the next start (see [flushNow], called when the app comes to the foreground).
 */
class CoroutineTelemetryFlusher(
    private val scope: CoroutineScope,
    private val delayMs: Long = 15_000,
) : TelemetryFlusher {

    /** Set after construction: the uploader needs the API client, which reports to the recorder. */
    var upload: (suspend () -> Boolean)? = null

    private var pending: Job? = null

    override fun flushSoon() {
        if (pending?.isActive == true) return
        pending = scope.launch {
            delay(delayMs)
            upload?.invoke()
        }
    }

    fun flushNow() {
        pending?.cancel()
        pending = scope.launch { upload?.invoke() }
    }
}
