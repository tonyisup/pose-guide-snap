package com.tonyisup.poseguidesnap.calibration

import java.util.Locale
import kotlin.math.ceil

/** Separate diagnostic authorization; neither phase is a labeled whole-pose match sequence. */
internal class HandPlacementComparisonRequest private constructor(val id: String) {
    override fun toString() = "HandPlacementComparisonRequest(redacted)"

    companion object {
        fun fromRaw(authorization: String?, id: String?): HandPlacementComparisonRequest {
            require(authorization == DerivedCalibrationSequence.AUTHORIZATION) {
                "Explicit derived calibration authorization required"
            }
            require(id != null && id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) {
                "A bounded pseudonymous comparison identifier is required"
            }
            return HandPlacementComparisonRequest(id)
        }
    }
}

internal enum class HandPlacementPhase(val allowanceMs: Long, val instruction: String) {
    KNEES(30_000, "This is a hand-position comparison. Rest both hands on your knees, palms up. " +
        "You have thirty seconds to get comfortable."),
    LAP(15_000, "First measurement complete. Place both hands in your lap, away from your knees. " +
        "You have fifteen seconds to get comfortable."),
}

/**
 * Only streaming histograms and fixed counters survive each callback. No frame list, coordinates,
 * ordered distances or participant text. One transient motion anchor is cleared at every boundary.
 */
internal class CalibrationHandPlacementProbe : AutoCloseable {
    private class DistanceHistogram {
        private val bins = IntArray(1_001) // 0.01 torso-length upper bounds, through 10.00.
        private var count = 0
        private var minimum = Double.POSITIVE_INFINITY
        private var maximum = Double.NEGATIVE_INFINITY
        private var within025 = 0

        fun record(value: Double) {
            check(value.isFinite() && value in 0.0..10.0)
            bins[ceil(value * 100).toInt().coerceIn(0, bins.lastIndex)]++
            count++
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            if (value <= 0.25) within025++ // Fixed diagnostic cutoff, independent of future policy edits.
        }

        private fun percentileUpper(fraction: Double): Double? {
            if (count == 0) return null
            val rank = ceil(count * fraction).toInt()
            var cumulative = 0
            for (index in bins.indices) {
                cumulative += bins[index]
                if (cumulative >= rank) return index / 100.0
            }
            error("Histogram count is inconsistent")
        }

        fun summary(side: String): String =
            "${side}Min=${number(minimum.takeIf { count > 0 })} " +
                "${side}P50Upper=${number(percentileUpper(0.5))} " +
                "${side}P95Upper=${number(percentileUpper(0.95))} " +
                "${side}Max=${number(maximum.takeIf { count > 0 })} ${side}Within025=$within025"
    }

    private class PhaseSamples(val phase: HandPlacementPhase, val startedAtMs: Long) {
        var samples = 0
        var eligible = 0
        var unavailable = 0
        var unsettled = 0
        var discarded = 0
        var capped = false
        val left = DistanceHistogram()
        val right = DistanceHistogram()

        fun summary() = "phase=${phase.name} samples=$samples eligible=$eligible " +
            "unavailable=$unavailable unsettled=$unsettled discarded=$discarded capped=$capped " +
            left.summary("left") + " " + right.summary("right")
    }

    private val settling = CalibrationSettlingTracker()
    private val completed = mutableMapOf<HandPlacementPhase, PhaseSamples>()
    private var preparing: HandPlacementPhase? = null
    private var measuring: PhaseSamples? = null
    private var minimumSourceMs = Long.MAX_VALUE
    private var lastSourceMs: Long? = null
    private var closed = false

    @Synchronized
    fun prepare(phase: HandPlacementPhase, nowMs: Long) {
        check(!closed && preparing == null && measuring == null && phase.ordinal == completed.size)
        preparing = phase
        minimumSourceMs = nowMs
        settling.reset()
    }

    @Synchronized
    fun isSettled(nowMs: Long) = !closed && preparing != null && settling.isSettled(nowMs)

    @Synchronized
    fun restartSettling(nowMs: Long) {
        check(!closed && preparing != null && measuring == null)
        minimumSourceMs = nowMs
        settling.reset()
    }

    @Synchronized
    fun beginMeasurement(nowMs: Long) {
        check(!closed && measuring == null && isSettled(nowMs))
        measuring = PhaseSamples(checkNotNull(preparing), nowMs)
        preparing = null
        minimumSourceMs = nowMs
        settling.reset() // New evidence after the measurement announcement has completed.
    }

    @Synchronized
    fun observe(feedback: AlignmentFeedback, sourceMs: Long, nowMs: Long) {
        if (closed || preparing == null && measuring == null) return
        val window = measuring?.takeIf { nowMs - it.startedAtMs in 0 until MEASUREMENT_MS }
        if (measuring != null && window == null) return
        if (window != null) {
            if (window.samples == MAX_SAMPLES) { window.capped = true; return }
            window.samples++
        }
        if (sourceMs < minimumSourceMs || nowMs - sourceMs !in 0..750 ||
            lastSourceMs?.let { sourceMs <= it } == true) {
            window?.let { it.discarded++ }
            settling.reset()
            return
        }
        lastSourceMs = sourceMs
        val arms = feedback.armPose
        val complete = feedback.observedBounds != null && arms != null && feedback.motionSample != null
        val left = arms?.leftWristError
        val right = arms?.rightWristError
        if (!complete || left == null || right == null ||
            !left.isFinite() || !right.isFinite() || left !in 0.0..10.0 || right !in 0.0..10.0) {
            window?.let { it.unavailable++ }
            settling.reset()
            return
        }
        settling.record(feedback.motionSample, sourceMs)
        if (window == null) return
        if (!settling.isSettled(nowMs)) { window.unsettled++; return }
        window.eligible++
        window.left.record(left)
        window.right.record(right)
    }

    @Synchronized
    fun finishMeasurement(nowMs: Long): Boolean {
        val window = checkNotNull(measuring)
        check(!closed && nowMs - window.startedAtMs >= MEASUREMENT_MS)
        val freshAndSettled = settling.isSettled(nowMs)
        completed[window.phase] = window
        measuring = null
        settling.reset()
        return window.eligible >= MIN_ELIGIBLE_SAMPLES && !window.capped && freshAndSettled
    }

    @Synchronized
    fun summaries(): List<String> = HandPlacementPhase.entries.map { phase ->
        (completed[phase] ?: measuring?.takeIf { it.phase == phase } ?: PhaseSamples(phase, 0)).summary()
    }

    @Synchronized
    override fun close() {
        closed = true
        preparing = null
        settling.reset()
    }

    override fun toString() = "CalibrationHandPlacementProbe(redacted)"

    companion object {
        const val MEASUREMENT_MS = 10_000L
        const val MAX_SAMPLES = 200
        const val MIN_ELIGIBLE_SAMPLES = 30
        private fun number(value: Double?): String =
            value?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "unavailable"
    }
}

/** Explicit diagnostic measurements; never starts coaching, match admission or capture. */
internal class CalibrationHandPlacementComparisonProtocol(
    private val probe: CalibrationHandPlacementProbe,
    private val elapsedRealtimeMs: () -> Long,
    private val sleepMs: (Long) -> Unit,
    private val sayAndAwait: (String) -> Unit,
    private val blink: () -> Unit,
    private val ensureHealthy: () -> Unit,
) {
    fun run() {
        for (phase in HandPlacementPhase.entries) {
            sayAndAwait(phase.instruction)
            blink()
            val start = elapsedRealtimeMs()
            probe.prepare(phase, start)
            while (true) {
                ensureHealthy()
                val now = elapsedRealtimeMs()
                check(now - start < phase.allowanceMs + 15_000) {
                    "Comparison stopped without fresh settled body evidence"
                }
                if (now - start >= phase.allowanceMs && probe.isSettled(now)) break
                sleepMs(100)
            }
            sayAndAwait("Keep that position comfortably. Measuring for ten seconds.")
            val speechEndedAt = elapsedRealtimeMs()
            probe.restartSettling(speechEndedAt)
            while (true) {
                ensureHealthy()
                val now = elapsedRealtimeMs()
                check(now - speechEndedAt < 15_000) { "Comparison stopped without fresh post-speech stillness" }
                if (probe.isSettled(now)) break
                sleepMs(100)
            }
            probe.beginMeasurement(elapsedRealtimeMs())
            val measurementStartedAt = elapsedRealtimeMs()
            while (elapsedRealtimeMs() - measurementStartedAt < CalibrationHandPlacementProbe.MEASUREMENT_MS) {
                ensureHealthy()
                sleepMs(100)
            }
            ensureHealthy()
            check(probe.finishMeasurement(elapsedRealtimeMs())) {
                "Comparison stopped with insufficient settled samples"
            }
        }
        sayAndAwait("Comparison finished. You can relax.")
    }
}
