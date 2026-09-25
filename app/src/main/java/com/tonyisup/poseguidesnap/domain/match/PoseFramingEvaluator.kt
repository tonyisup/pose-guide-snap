package com.tonyisup.poseguidesnap.domain.match

import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import kotlin.math.hypot
import kotlin.math.min

/** Explicitly uncalibrated confidence, coverage, and center-error policy for framing analysis. */
data class FramingPolicy(
    val minimumLandmarkConfidence: Double,
    val minimumSharedLandmarkCount: Int,
    val centerErrorAtZeroSimilarity: Double,
) {
    init {
        require(
            minimumLandmarkConfidence.isFinite() && minimumLandmarkConfidence in 0.0..1.0,
        ) { "minimumLandmarkConfidence must be finite and in [0, 1]" }
        require(minimumSharedLandmarkCount in MINIMUM_BODY_REGION_LANDMARKS..MOVENET_LANDMARKS.size) {
            "minimumSharedLandmarkCount must fit the required body regions and MoveNet"
        }
        require(centerErrorAtZeroSimilarity.isFinite() && centerErrorAtZeroSimilarity > 0.0) {
            "centerErrorAtZeroSimilarity must be finite and positive"
        }
    }

    companion object {
        fun developmentDefaults(): FramingPolicy = FramingPolicy(
            minimumLandmarkConfidence = 0.25,
            minimumSharedLandmarkCount = 13,
            centerErrorAtZeroSimilarity = 0.5,
        )
    }
}

enum class FramingEvidenceStatus {
    EVALUATED,
    INVALID_REFERENCE,
    INVALID_PERSON_COUNT,
    INSUFFICIENT_BODY_EVIDENCE,
    DEGENERATE_BODY_EXTENT,
}

/** Scalar-only framing evidence. It retains no landmarks, image identity, path, or URI. */
data class FramingEvidence(
    val status: FramingEvidenceStatus,
    val sharedLandmarkCount: Int,
    val centerSimilarity: Double,
    val scaleSimilarity: Double,
    val framingScore: Double,
) {
    init {
        require(sharedLandmarkCount in 0..MOVENET_LANDMARKS.size)
        requireFramingNormalized(centerSimilarity, "centerSimilarity")
        requireFramingNormalized(scaleSimilarity, "scaleSimilarity")
        requireFramingNormalized(framingScore, "framingScore")
        require(framingScore <= centerSimilarity && framingScore <= scaleSimilarity) {
            "framingScore cannot hide a failed component"
        }
        require(
            status == FramingEvidenceStatus.EVALUATED ||
                centerSimilarity == 0.0 && scaleSimilarity == 0.0 && framingScore == 0.0,
        ) {
            "unavailable framing evidence must fail closed"
        }
    }

    companion object {
        internal fun unavailable(
            status: FramingEvidenceStatus,
            sharedLandmarkCount: Int = 0,
        ) = FramingEvidence(status, sharedLandmarkCount, 0.0, 0.0, 0.0)
    }
}

/**
 * Compares subject composition in image-normalized coordinates without reusing canonical pose
 * similarity. The full confidence-qualified reference body defines the expected extent; the live
 * extent uses matching qualified identities, so missing extremities cannot also shrink the
 * reference box. Center alignment and diagonal scale agreement remain independent. The lower
 * component is the framing score so one cannot conceal the other.
 */
class PoseFramingEvaluator(
    private val policy: FramingPolicy = FramingPolicy.developmentDefaults(),
) {
    fun evaluate(reference: PoseObservation, observed: PoseObservation): FramingEvidence {
        if (reference.detectedPersonCount != 1) {
            return FramingEvidence.unavailable(FramingEvidenceStatus.INVALID_REFERENCE)
        }
        if (observed.detectedPersonCount != 1) {
            return FramingEvidence.unavailable(FramingEvidenceStatus.INVALID_PERSON_COUNT)
        }

        val referenceByIdentity = retained(reference).associateBy(Landmark::type)
        val observedByIdentity = retained(observed).associateBy(Landmark::type)
        if (
            referenceByIdentity.size < policy.minimumSharedLandmarkCount ||
            !referenceByIdentity.keys.containsAll(TORSO_ANCHORS) ||
            !hasEveryRequiredBodyRegion(referenceByIdentity.keys)
        ) {
            return FramingEvidence.unavailable(FramingEvidenceStatus.INVALID_REFERENCE)
        }
        val sharedIdentities = referenceByIdentity.keys.intersect(observedByIdentity.keys)
        if (
            sharedIdentities.size < policy.minimumSharedLandmarkCount ||
            !sharedIdentities.containsAll(TORSO_ANCHORS) ||
            !hasEveryRequiredBodyRegion(sharedIdentities)
        ) {
            return FramingEvidence.unavailable(
                FramingEvidenceStatus.INSUFFICIENT_BODY_EVIDENCE,
                sharedIdentities.size,
            )
        }

        val referenceBounds = bounds(referenceByIdentity.values.toList())
        val observedBounds = bounds(sharedIdentities.map(observedByIdentity::getValue))
        if (referenceBounds.diagonal <= 0.0 || observedBounds.diagonal <= 0.0) {
            return FramingEvidence.unavailable(
                FramingEvidenceStatus.DEGENERATE_BODY_EXTENT,
                sharedIdentities.size,
            )
        }

        val centerError = hypot(
            referenceBounds.centerX - observedBounds.centerX,
            referenceBounds.centerY - observedBounds.centerY,
        )
        val centerSimilarity = (
            1.0 - centerError / policy.centerErrorAtZeroSimilarity
            ).coerceIn(0.0, 1.0)
        val scaleSimilarity = min(
            observedBounds.diagonal / referenceBounds.diagonal,
            referenceBounds.diagonal / observedBounds.diagonal,
        ).coerceIn(0.0, 1.0)

        return FramingEvidence(
            status = FramingEvidenceStatus.EVALUATED,
            sharedLandmarkCount = sharedIdentities.size,
            centerSimilarity = centerSimilarity,
            scaleSimilarity = scaleSimilarity,
            framingScore = min(centerSimilarity, scaleSimilarity),
        )
    }

    private fun retained(observation: PoseObservation): List<Landmark> =
        observation.landmarks.filter { landmark ->
            landmark.type in MOVENET_LANDMARKS &&
                min(landmark.visibility, landmark.presence) >= policy.minimumLandmarkConfidence
        }
}

private data class BodyBounds(
    val centerX: Double,
    val centerY: Double,
    val diagonal: Double,
)

private fun bounds(landmarks: List<Landmark>): BodyBounds {
    val minimumX = landmarks.minOf(Landmark::x)
    val maximumX = landmarks.maxOf(Landmark::x)
    val minimumY = landmarks.minOf(Landmark::y)
    val maximumY = landmarks.maxOf(Landmark::y)
    return BodyBounds(
        centerX = minimumX / 2.0 + maximumX / 2.0,
        centerY = minimumY / 2.0 + maximumY / 2.0,
        diagonal = hypot(maximumX - minimumX, maximumY - minimumY),
    )
}

private val TORSO_ANCHORS = setOf(
    PoseLandmark.LEFT_SHOULDER,
    PoseLandmark.RIGHT_SHOULDER,
    PoseLandmark.LEFT_HIP,
    PoseLandmark.RIGHT_HIP,
)

private val REQUIRED_BODY_REGIONS = listOf(
    setOf(
        PoseLandmark.NOSE,
        PoseLandmark.LEFT_EYE,
        PoseLandmark.RIGHT_EYE,
        PoseLandmark.LEFT_EAR,
        PoseLandmark.RIGHT_EAR,
    ),
    setOf(PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST),
    setOf(PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST),
    setOf(PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE),
    setOf(PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE),
)

private const val MINIMUM_BODY_REGION_LANDMARKS = 9

private fun hasEveryRequiredBodyRegion(identities: Set<PoseLandmark>): Boolean =
    REQUIRED_BODY_REGIONS.all { region -> region.any(identities::contains) }

private val MOVENET_LANDMARKS = setOf(
    PoseLandmark.NOSE,
    PoseLandmark.LEFT_EYE,
    PoseLandmark.RIGHT_EYE,
    PoseLandmark.LEFT_EAR,
    PoseLandmark.RIGHT_EAR,
    PoseLandmark.LEFT_SHOULDER,
    PoseLandmark.RIGHT_SHOULDER,
    PoseLandmark.LEFT_ELBOW,
    PoseLandmark.RIGHT_ELBOW,
    PoseLandmark.LEFT_WRIST,
    PoseLandmark.RIGHT_WRIST,
    PoseLandmark.LEFT_HIP,
    PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_KNEE,
    PoseLandmark.RIGHT_KNEE,
    PoseLandmark.LEFT_ANKLE,
    PoseLandmark.RIGHT_ANKLE,
)

private fun requireFramingNormalized(value: Double, name: String) {
    require(value.isFinite() && value in 0.0..1.0) {
        "$name must be finite and in [0, 1]"
    }
}
