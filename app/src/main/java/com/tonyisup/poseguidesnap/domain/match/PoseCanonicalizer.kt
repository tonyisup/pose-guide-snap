package com.tonyisup.poseguidesnap.domain.match

import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import java.util.Collections
import java.util.LinkedHashSet
import kotlin.math.acos

/** Deterministic, detector-independent policy for torso normalization and feature extraction. */
class PoseCanonicalizer(
    val minimumConfidence: Double,
    val minimumTorsoScale: Double,
) {
    init {
        require(minimumConfidence.isFinite() && minimumConfidence in 0.0..1.0) {
            "minimumConfidence must be finite and in [0, 1]"
        }
        require(minimumTorsoScale.isFinite() && minimumTorsoScale > 0.0) {
            "minimumTorsoScale must be positive and finite"
        }
    }

    fun canonicalize(
        observation: PoseObservation,
        mirror: Boolean = false,
    ): PoseCanonicalizationResult {
        val retained = observation.landmarks
            .filter { it.confidence >= minimumConfidence }
            .associateByTo(linkedMapOf(), Landmark::type)
        val missingAnchors = TORSO_ANCHORS.filterTo(linkedSetOf()) { it !in retained }
        if (missingAnchors.isNotEmpty()) {
            return PoseCanonicalizationResult.Failure(
                reason = PoseCanonicalizationFailureReason.MISSING_TORSO_ANCHORS,
                missingTorsoAnchors = missingAnchors,
            )
        }

        val shoulderMidpoint = midpoint(
            retained.getValue(PoseLandmark.LEFT_SHOULDER).vector,
            retained.getValue(PoseLandmark.RIGHT_SHOULDER).vector,
        )
        val hipMidpoint = midpoint(
            retained.getValue(PoseLandmark.LEFT_HIP).vector,
            retained.getValue(PoseLandmark.RIGHT_HIP).vector,
        )
        val scale = (shoulderMidpoint - hipMidpoint).magnitude()
        if (!scale.isFinite() || scale <= minimumTorsoScale) {
            return PoseCanonicalizationResult.Failure(
                reason = PoseCanonicalizationFailureReason.DEGENERATE_TORSO_SCALE,
            )
        }
        val torsoCenter = midpoint(shoulderMidpoint, hipMidpoint)

        val canonicalPoints = linkedMapOf<PoseLandmark, CanonicalPoint>()
        retained.values.forEach { landmark ->
            val normalized = (landmark.vector - torsoCenter) / scale
            if (normalized.isFinite()) {
                val outputType = if (mirror) mirroredIdentity(landmark.type) else landmark.type
                canonicalPoints[outputType] = CanonicalPoint(
                    x = if (mirror) -normalized.x else normalized.x,
                    y = normalized.y,
                    z = normalized.z,
                    confidence = landmark.confidence,
                )
            }
        }

        val invalidNormalizedAnchors = TORSO_ANCHORS.filterTo(linkedSetOf()) { inputType ->
            val outputType = if (mirror) mirroredIdentity(inputType) else inputType
            outputType !in canonicalPoints
        }
        if (invalidNormalizedAnchors.isNotEmpty()) {
            return PoseCanonicalizationResult.Failure(
                reason = PoseCanonicalizationFailureReason.NON_FINITE_NORMALIZED_TORSO_ANCHORS,
                invalidNormalizedTorsoAnchors = invalidNormalizedAnchors,
            )
        }

        return PoseCanonicalizationResult.Success(
            PoseFeatures.create(
                points = canonicalPoints,
                mirrorUsed = mirror,
                jointAngles = extractAngles(canonicalPoints),
            ),
        )
    }

    companion object {
        fun mirroredIdentity(type: PoseLandmark): PoseLandmark = when (type) {
            PoseLandmark.NOSE -> PoseLandmark.NOSE
            PoseLandmark.LEFT_EYE_INNER -> PoseLandmark.RIGHT_EYE_INNER
            PoseLandmark.LEFT_EYE -> PoseLandmark.RIGHT_EYE
            PoseLandmark.LEFT_EYE_OUTER -> PoseLandmark.RIGHT_EYE_OUTER
            PoseLandmark.RIGHT_EYE_INNER -> PoseLandmark.LEFT_EYE_INNER
            PoseLandmark.RIGHT_EYE -> PoseLandmark.LEFT_EYE
            PoseLandmark.RIGHT_EYE_OUTER -> PoseLandmark.LEFT_EYE_OUTER
            PoseLandmark.LEFT_EAR -> PoseLandmark.RIGHT_EAR
            PoseLandmark.RIGHT_EAR -> PoseLandmark.LEFT_EAR
            PoseLandmark.MOUTH_LEFT -> PoseLandmark.MOUTH_RIGHT
            PoseLandmark.MOUTH_RIGHT -> PoseLandmark.MOUTH_LEFT
            PoseLandmark.LEFT_SHOULDER -> PoseLandmark.RIGHT_SHOULDER
            PoseLandmark.RIGHT_SHOULDER -> PoseLandmark.LEFT_SHOULDER
            PoseLandmark.LEFT_ELBOW -> PoseLandmark.RIGHT_ELBOW
            PoseLandmark.RIGHT_ELBOW -> PoseLandmark.LEFT_ELBOW
            PoseLandmark.LEFT_WRIST -> PoseLandmark.RIGHT_WRIST
            PoseLandmark.RIGHT_WRIST -> PoseLandmark.LEFT_WRIST
            PoseLandmark.LEFT_PINKY -> PoseLandmark.RIGHT_PINKY
            PoseLandmark.RIGHT_PINKY -> PoseLandmark.LEFT_PINKY
            PoseLandmark.LEFT_INDEX -> PoseLandmark.RIGHT_INDEX
            PoseLandmark.RIGHT_INDEX -> PoseLandmark.LEFT_INDEX
            PoseLandmark.LEFT_THUMB -> PoseLandmark.RIGHT_THUMB
            PoseLandmark.RIGHT_THUMB -> PoseLandmark.LEFT_THUMB
            PoseLandmark.LEFT_HIP -> PoseLandmark.RIGHT_HIP
            PoseLandmark.RIGHT_HIP -> PoseLandmark.LEFT_HIP
            PoseLandmark.LEFT_KNEE -> PoseLandmark.RIGHT_KNEE
            PoseLandmark.RIGHT_KNEE -> PoseLandmark.LEFT_KNEE
            PoseLandmark.LEFT_ANKLE -> PoseLandmark.RIGHT_ANKLE
            PoseLandmark.RIGHT_ANKLE -> PoseLandmark.LEFT_ANKLE
            PoseLandmark.LEFT_HEEL -> PoseLandmark.RIGHT_HEEL
            PoseLandmark.RIGHT_HEEL -> PoseLandmark.LEFT_HEEL
            PoseLandmark.LEFT_FOOT_INDEX -> PoseLandmark.RIGHT_FOOT_INDEX
            PoseLandmark.RIGHT_FOOT_INDEX -> PoseLandmark.LEFT_FOOT_INDEX
        }
    }
}

enum class PoseCanonicalizationFailureReason {
    MISSING_TORSO_ANCHORS,
    DEGENERATE_TORSO_SCALE,
    NON_FINITE_NORMALIZED_TORSO_ANCHORS,
}

sealed interface PoseCanonicalizationResult {
    data class Success(val features: PoseFeatures) : PoseCanonicalizationResult

    class Failure internal constructor(
        val reason: PoseCanonicalizationFailureReason,
        missingTorsoAnchors: Set<PoseLandmark> = emptySet(),
        invalidNormalizedTorsoAnchors: Set<PoseLandmark> = emptySet(),
    ) : PoseCanonicalizationResult {
        val missingTorsoAnchors: Set<PoseLandmark> =
            Collections.unmodifiableSet(LinkedHashSet(missingTorsoAnchors))
        val invalidNormalizedTorsoAnchors: Set<PoseLandmark> =
            Collections.unmodifiableSet(LinkedHashSet(invalidNormalizedTorsoAnchors))

        init {
            require(missingTorsoAnchors.all { it in TORSO_ANCHORS })
            require(invalidNormalizedTorsoAnchors.all { it in TORSO_ANCHORS })
            when (reason) {
                PoseCanonicalizationFailureReason.MISSING_TORSO_ANCHORS -> {
                    require(missingTorsoAnchors.isNotEmpty())
                    require(invalidNormalizedTorsoAnchors.isEmpty())
                }
                PoseCanonicalizationFailureReason.DEGENERATE_TORSO_SCALE -> {
                    require(missingTorsoAnchors.isEmpty())
                    require(invalidNormalizedTorsoAnchors.isEmpty())
                }
                PoseCanonicalizationFailureReason.NON_FINITE_NORMALIZED_TORSO_ANCHORS -> {
                    require(missingTorsoAnchors.isEmpty())
                    require(invalidNormalizedTorsoAnchors.isNotEmpty())
                }
            }
        }
    }
}

private val Landmark.confidence: Double
    get() = minOf(visibility, presence)

private val Landmark.vector: Vector3
    get() = Vector3(x, y, z)

private data class Vector3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)

    operator fun div(divisor: Double): Vector3 = Vector3(x / divisor, y / divisor, z / divisor)

    fun magnitude(): Double = Math.hypot(Math.hypot(x, y), z)

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

private fun midpoint(first: Vector3, second: Vector3): Vector3 = Vector3(
    x = first.x / 2.0 + second.x / 2.0,
    y = first.y / 2.0 + second.y / 2.0,
    z = first.z / 2.0 + second.z / 2.0,
)

private data class AngleDefinition(
    val first: PoseLandmark,
    val vertex: PoseLandmark,
    val third: PoseLandmark,
)

private fun extractAngles(
    points: Map<PoseLandmark, CanonicalPoint>,
): Map<JointAngleKey, WeightedJointAngle> {
    val angles = linkedMapOf<JointAngleKey, WeightedJointAngle>()
    ANGLE_DEFINITIONS.forEach { (key, definition) ->
        val first = points[definition.first] ?: return@forEach
        val vertex = points[definition.vertex] ?: return@forEach
        val third = points[definition.third] ?: return@forEach
        angleAt(first, vertex, third)?.let { radians ->
            angles[key] = WeightedJointAngle(
                radians = radians,
                weight = minOf(first.confidence, vertex.confidence, third.confidence),
            )
        }
    }
    return angles
}

private fun angleAt(
    first: CanonicalPoint,
    vertex: CanonicalPoint,
    third: CanonicalPoint,
): Double? {
    val firstVector = Vector3(first.x - vertex.x, first.y - vertex.y, first.z - vertex.z)
    val thirdVector = Vector3(third.x - vertex.x, third.y - vertex.y, third.z - vertex.z)
    val firstMagnitude = firstVector.magnitude()
    val thirdMagnitude = thirdVector.magnitude()
    if (firstMagnitude == 0.0 || thirdMagnitude == 0.0) return null
    if (!firstMagnitude.isFinite() || !thirdMagnitude.isFinite()) return null

    val cosine = (
        firstVector.x / firstMagnitude * (thirdVector.x / thirdMagnitude) +
            firstVector.y / firstMagnitude * (thirdVector.y / thirdMagnitude) +
            firstVector.z / firstMagnitude * (thirdVector.z / thirdMagnitude)
        ).coerceIn(-1.0, 1.0)
    return acos(cosine)
}

private val TORSO_ANCHORS = listOf(
    PoseLandmark.LEFT_SHOULDER,
    PoseLandmark.RIGHT_SHOULDER,
    PoseLandmark.LEFT_HIP,
    PoseLandmark.RIGHT_HIP,
)

private val ANGLE_DEFINITIONS = linkedMapOf(
    JointAngleKey.LEFT_ELBOW to AngleDefinition(
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.LEFT_ELBOW,
        PoseLandmark.LEFT_WRIST,
    ),
    JointAngleKey.RIGHT_ELBOW to AngleDefinition(
        PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.RIGHT_WRIST,
    ),
    JointAngleKey.LEFT_SHOULDER to AngleDefinition(
        PoseLandmark.LEFT_ELBOW,
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.LEFT_HIP,
    ),
    JointAngleKey.RIGHT_SHOULDER to AngleDefinition(
        PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.RIGHT_HIP,
    ),
    JointAngleKey.LEFT_HIP to AngleDefinition(
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.LEFT_HIP,
        PoseLandmark.LEFT_KNEE,
    ),
    JointAngleKey.RIGHT_HIP to AngleDefinition(
        PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.RIGHT_HIP,
        PoseLandmark.RIGHT_KNEE,
    ),
    JointAngleKey.LEFT_KNEE to AngleDefinition(
        PoseLandmark.LEFT_HIP,
        PoseLandmark.LEFT_KNEE,
        PoseLandmark.LEFT_ANKLE,
    ),
    JointAngleKey.RIGHT_KNEE to AngleDefinition(
        PoseLandmark.RIGHT_HIP,
        PoseLandmark.RIGHT_KNEE,
        PoseLandmark.RIGHT_ANKLE,
    ),
)
