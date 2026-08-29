package com.tonyisup.poseguidesnap.camera

/**
 * Thread-safe pure timestamp policy that bounds Task 10 live analysis to at most 10 Hz.
 *
 * Camera timestamps are explicit nonnegative monotonic nanoseconds. Skips never move the accepted
 * anchor, and comparisons subtract only after proving that the candidate is newer, so the interval
 * check cannot overflow.
 */
internal class AnalysisCadenceGate {
    enum class Decision {
        ACCEPTED_FIRST,
        ACCEPTED_INTERVAL,
        SKIPPED_TOO_SOON,
        SKIPPED_STALE,
    }

    /** Detached immutable counters for diagnostics; no image or inference data is retained. */
    data class Snapshot(
        val received: Long,
        val accepted: Long,
        val skippedTooSoon: Long,
        val skippedStale: Long,
        val lastAcceptedTimestampNanos: Long?,
    )

    private var received = 0L
    private var accepted = 0L
    private var skippedTooSoon = 0L
    private var skippedStale = 0L
    private var lastAcceptedTimestampNanos: Long? = null

    @Synchronized
    fun decide(monotonicTimestampNanos: Long): Decision {
        require(monotonicTimestampNanos >= 0) {
            "monotonicTimestampNanos must be nonnegative"
        }

        val lastAccepted = lastAcceptedTimestampNanos
        val decision = when {
            lastAccepted == null -> Decision.ACCEPTED_FIRST
            monotonicTimestampNanos <= lastAccepted -> Decision.SKIPPED_STALE
            monotonicTimestampNanos - lastAccepted >= MINIMUM_INTERVAL_NANOS ->
                Decision.ACCEPTED_INTERVAL
            else -> Decision.SKIPPED_TOO_SOON
        }
        ensureCountersCanRecord(decision)

        received += 1L
        when (decision) {
            Decision.ACCEPTED_FIRST,
            Decision.ACCEPTED_INTERVAL -> {
                accepted += 1L
                lastAcceptedTimestampNanos = monotonicTimestampNanos
            }
            Decision.SKIPPED_TOO_SOON -> skippedTooSoon += 1L
            Decision.SKIPPED_STALE -> skippedStale += 1L
        }
        return decision
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        received = received,
        accepted = accepted,
        skippedTooSoon = skippedTooSoon,
        skippedStale = skippedStale,
        lastAcceptedTimestampNanos = lastAcceptedTimestampNanos,
    )

    private fun ensureCountersCanRecord(decision: Decision) {
        check(received < Long.MAX_VALUE) { "Cadence received counter overflow" }
        val outcomeCounter = when (decision) {
            Decision.ACCEPTED_FIRST,
            Decision.ACCEPTED_INTERVAL -> accepted
            Decision.SKIPPED_TOO_SOON -> skippedTooSoon
            Decision.SKIPPED_STALE -> skippedStale
        }
        check(outcomeCounter < Long.MAX_VALUE) { "Cadence outcome counter overflow" }
    }

    companion object {
        /** Fixed Task 10 prototype policy: one accepted frame per 100 ms, or 10 Hz maximum. */
        const val MINIMUM_INTERVAL_NANOS = 100_000_000L
    }
}
