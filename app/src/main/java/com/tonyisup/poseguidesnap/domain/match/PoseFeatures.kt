package com.tonyisup.poseguidesnap.domain.match

import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import java.util.Collections
import java.util.LinkedHashMap

data class CanonicalPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val confidence: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "canonical coordinates must be finite"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "confidence must be finite and in [0, 1]"
        }
    }
}

enum class JointAngleKey {
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
}

data class WeightedJointAngle(
    val radians: Double,
    val weight: Double,
) {
    init {
        require(radians.isFinite() && radians in 0.0..Math.PI) {
            "radians must be finite and in [0, pi]"
        }
        require(weight.isFinite() && weight in 0.0..1.0) {
            "weight must be finite and in [0, 1]"
        }
    }
}

@ConsistentCopyVisibility
data class PoseFeatures private constructor(
    val points: Map<PoseLandmark, CanonicalPoint>,
    val mirrorUsed: Boolean,
    val jointAngles: Map<JointAngleKey, WeightedJointAngle>,
) {
    companion object {
        fun create(
            points: Map<PoseLandmark, CanonicalPoint>,
            mirrorUsed: Boolean,
            jointAngles: Map<JointAngleKey, WeightedJointAngle>,
        ): PoseFeatures = PoseFeatures(
            points = immutableMap(points),
            mirrorUsed = mirrorUsed,
            jointAngles = immutableMap(jointAngles),
        )
    }
}

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))