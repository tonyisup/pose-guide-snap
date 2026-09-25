package com.tonyisup.poseguidesnap.calibration

/** Instrumentation-thread timing. The torch callback must await the requested hardware state. */
internal class CalibrationWarmupCue(
    private val setTorchEnabled: (Boolean) -> Unit,
    private val elapsedRealtimeMs: () -> Long,
    private val sleepMs: (Long) -> Unit,
) {
    fun run(warmupMs: Int, onWarmupStarted: (Long) -> Unit) {
        require(warmupMs in CalibrationCollectionRequest.MIN_WARMUP_MS..CalibrationCollectionRequest.MAX_WARMUP_MS)
        val startedAt = blink(onWarmupStarted)
        sleepUntil(startedAt + warmupMs)
    }

    /** Spoken preparation waits on live readiness after the blink, not a fixed-duration sleep. */
    fun blink(onWarmupStarted: (Long) -> Unit): Long =
        try {
            setTorchEnabled(true)
            val started = elapsedRealtimeMs()
            onWarmupStarted(started)
            sleepUntil(started + BLINK_DURATION_MS)
            started
        } finally {
            // Also request off if enabling, the callback, or waiting fails. A failed off
            // acknowledgement aborts the caller before it can begin measurement.
            setTorchEnabled(false)
        }

    private fun sleepUntil(deadlineMs: Long) {
        val remainingMs = deadlineMs - elapsedRealtimeMs()
        if (remainingMs > 0L) sleepMs(remainingMs)
    }

    companion object {
        const val BLINK_DURATION_MS = 250L
    }
}
