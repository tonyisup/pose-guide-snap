package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import kotlin.math.hypot
import kotlin.math.min

/** One transient body snapshot, never serialized/logged. Translation and scale remain observable. */
internal class CalibrationMotionSample private constructor(
    private val points: Map<PoseLandmark, Pair<Double, Double>>,
    private val torsoLength: Double,
    private val aspectRatio: Double,
) {
    fun displacementFrom(anchor: CalibrationMotionSample): Double {
        if (points.keys != anchor.points.keys || aspectRatio != anchor.aspectRatio) return Double.POSITIVE_INFINITY
        val scale = min(torsoLength, anchor.torsoLength)
        return points.maxOf { (identity, point) ->
            val previous = anchor.points.getValue(identity)
            hypot(point.first - previous.first, point.second - previous.second) / scale
        }
    }

    override fun toString() = "CalibrationMotionSample(redacted)"

    companion object {
        // Called only after the guide has qualified the complete, single-person body.
        fun from(qualifiedLandmarks: List<Landmark>, imageSize: PoseImageSize): CalibrationMotionSample? {
            val aspect = imageSize.aspectRatio
            val points = qualifiedLandmarks.associate { it.type to (it.x * aspect to it.y) }
            fun midpoint(left: PoseLandmark, right: PoseLandmark): Pair<Double, Double>? {
                val first = points[left] ?: return null
                val second = points[right] ?: return null
                return (first.first + second.first) / 2 to (first.second + second.second) / 2
            }
            val shoulders = midpoint(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER) ?: return null
            val hips = midpoint(PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP) ?: return null
            val torso = hypot(shoulders.first - hips.first, shoulders.second - hips.second)
            if (!torso.isFinite() || torso <= 1e-9) return null
            return CalibrationMotionSample(points, torso, aspect)
        }
    }
}

/** Bounded memory: one anchor snapshot. Compare with the window anchor, not just the prior frame. */
internal class CalibrationSettlingTracker {
    private var anchor: CalibrationMotionSample? = null
    private var stableSince: Long? = null
    private var lastFrameAt: Long? = null

    fun reset() {
        anchor = null
        stableSince = null
        lastFrameAt = null
    }

    fun record(sample: CalibrationMotionSample?, nowMs: Long) {
        if (sample == null) { reset(); return }
        val gap = lastFrameAt?.let { nowMs - it !in 1..MAX_FRAME_GAP_MS } == true
        val previous = anchor
        if (gap || previous == null || sample.displacementFrom(previous) > MAX_DISPLACEMENT) {
            anchor = sample
            stableSince = nowMs
        }
        lastFrameAt = nowMs
    }

    fun isSettled(nowMs: Long): Boolean =
        lastFrameAt?.let { nowMs - it in 0..MAX_FRAME_GAP_MS } == true &&
            stableSince?.let { nowMs - it >= SETTLING_MS } == true

    override fun toString() = "CalibrationSettlingTracker(redacted)"

    companion object {
        const val SETTLING_MS = 1_000L
        const val MAX_FRAME_GAP_MS = 750L
        // Debug heuristic, pending device calibration. Separate from target-position acceptance.
        const val MAX_DISPLACEMENT = 0.06
    }
}
