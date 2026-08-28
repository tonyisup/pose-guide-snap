package com.tonyisup.poseguidesnap.domain.match

import com.tonyisup.poseguidesnap.domain.model.MatchGateFailure
import com.tonyisup.poseguidesnap.domain.model.MatchResult
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Pure deterministic pose matcher with independently explainable mandatory gates.
 *
 * The caller supplies image-level framing evidence and explicitly canonicalized unmirrored and
 * optional mirrored candidates. This class never reads image state or invents a mirror transform.
 */
class DefaultPoseMatcher(
    private val policy: MatchPolicy = MatchPolicy.developmentDefaults(),
) {
    fun match(
        reference: PoseFeatures,
        observed: PoseFeatures,
        mirroredObserved: PoseFeatures? = null,
        mirrorAllowed: Boolean,
        detectedPersonCount: Int,
        framingScore: Double,
    ): MatchResult {
        require(!observed.mirrorUsed) { "observed must be the explicitly unmirrored candidate" }
        require(mirroredObserved == null || mirroredObserved.mirrorUsed) {
            "mirroredObserved must carry mirrorUsed=true"
        }
        require(detectedPersonCount >= 0) { "detectedPersonCount must be nonnegative" }
        require(framingScore.isFinite() && framingScore in 0.0..1.0) {
            "framingScore must be finite and in [0, 1]"
        }

        val unmirroredEvidence = score(reference, observed)
        val selected = if (mirrorAllowed && mirroredObserved != null) {
            val mirroredEvidence = score(reference, mirroredObserved)
            select(unmirroredEvidence, mirroredEvidence)
        } else {
            unmirroredEvidence
        }

        val failures = linkedSetOf<MatchGateFailure>()
        when {
            detectedPersonCount == 0 -> failures += MatchGateFailure.NO_PERSON
            detectedPersonCount > 1 -> failures += MatchGateFailure.MULTIPLE_PEOPLE
        }
        if (selected.landmarkCoverage < policy.minimumLandmarkCoverage) {
            failures += MatchGateFailure.INSUFFICIENT_LANDMARK_COVERAGE
        }
        if (framingScore < policy.minimumFramingScore) {
            failures += MatchGateFailure.POOR_FRAMING
        }
        if (selected.angularSimilarity < policy.minimumAngularSimilarity) {
            failures += MatchGateFailure.ANGULAR_MISMATCH
        }
        if (selected.positionalSimilarity < policy.minimumPositionalSimilarity) {
            failures += MatchGateFailure.POSITIONAL_MISMATCH
        }
        if (selected.overallMatch < policy.minimumOverallMatch) {
            failures += MatchGateFailure.LOW_OVERALL_MATCH
        }

        return MatchResult(
            landmarkCoverage = selected.landmarkCoverage,
            framingScore = framingScore,
            angularSimilarity = selected.angularSimilarity,
            positionalSimilarity = selected.positionalSimilarity,
            overallMatch = selected.overallMatch,
            gateFailures = failures,
            mirrorUsed = selected.mirrorUsed,
            eligibleForLock = failures.isEmpty(),
        )
    }

    private fun score(reference: PoseFeatures, observed: PoseFeatures): CandidateEvidence {
        val landmarkCoverage = landmarkCoverage(reference, observed)
        val angularSimilarity = angularSimilarity(reference, observed)
        val positionalSimilarity = positionalSimilarity(reference, observed)
        // Missing evidence remains an explicit zero; weights are never renormalized by availability.
        val overallMatch = (
            policy.angularWeight * angularSimilarity +
                policy.positionalWeight * positionalSimilarity
        ).coerceIn(0.0, 1.0)
        val failedCandidateGateCount = listOf(
            landmarkCoverage < policy.minimumLandmarkCoverage,
            angularSimilarity < policy.minimumAngularSimilarity,
            positionalSimilarity < policy.minimumPositionalSimilarity,
            overallMatch < policy.minimumOverallMatch,
        ).count { it }

        return CandidateEvidence(
            landmarkCoverage = landmarkCoverage,
            angularSimilarity = angularSimilarity,
            positionalSimilarity = positionalSimilarity,
            overallMatch = overallMatch,
            failedCandidateGateCount = failedCandidateGateCount,
            mirrorUsed = observed.mirrorUsed,
        )
    }

    /**
     * Lock-safe deterministic candidate ordering: fewer failed candidate-specific mandatory gates
     * (coverage, angular, positional, overall), then higher overall, angular, positional, and
     * coverage evidence. An exact tie preserves the first (unmirrored) candidate. Person and
     * framing gates are omitted because they are shared by both candidates.
     */
    private fun select(
        unmirrored: CandidateEvidence,
        mirrored: CandidateEvidence,
    ): CandidateEvidence = when {
        mirrored.failedCandidateGateCount != unmirrored.failedCandidateGateCount ->
            if (mirrored.failedCandidateGateCount < unmirrored.failedCandidateGateCount) mirrored else unmirrored
        mirrored.overallMatch != unmirrored.overallMatch ->
            if (mirrored.overallMatch > unmirrored.overallMatch) mirrored else unmirrored
        mirrored.angularSimilarity != unmirrored.angularSimilarity ->
            if (mirrored.angularSimilarity > unmirrored.angularSimilarity) mirrored else unmirrored
        mirrored.positionalSimilarity != unmirrored.positionalSimilarity ->
            if (mirrored.positionalSimilarity > unmirrored.positionalSimilarity) mirrored else unmirrored
        mirrored.landmarkCoverage != unmirrored.landmarkCoverage ->
            if (mirrored.landmarkCoverage > unmirrored.landmarkCoverage) mirrored else unmirrored
        else -> unmirrored
    }

    /** Confidence-weighted observed support divided by total reference support. */
    private fun landmarkCoverage(reference: PoseFeatures, observed: PoseFeatures): Double {
        val denominator = reference.points.values.sumOf { it.confidence }
        if (denominator <= 0.0) return 0.0
        val numerator = reference.points.entries.sumOf { (identity, referencePoint) ->
            min(referencePoint.confidence, observed.points[identity]?.confidence ?: 0.0)
        }
        return (numerator / denominator).coerceIn(0.0, 1.0)
    }

    /** Conservative-confidence-weighted 3D distance mapped linearly onto [0, 1]. */
    private fun positionalSimilarity(reference: PoseFeatures, observed: PoseFeatures): Double {
        val distances = mutableListOf<WeightedValue>()
        reference.points.forEach { (identity, referencePoint) ->
            val observedPoint = observed.points[identity] ?: return@forEach
            val weight = min(referencePoint.confidence, observedPoint.confidence)
            if (weight <= 0.0) return@forEach
            val distance = hypot(
                hypot(referencePoint.x - observedPoint.x, referencePoint.y - observedPoint.y),
                referencePoint.z - observedPoint.z,
            )
            distances += WeightedValue(value = distance, weight = weight)
        }
        val meanDistance = scaleSafeWeightedMean(distances) ?: return 0.0
        if (!meanDistance.isFinite()) return 0.0
        return (1.0 - meanDistance / policy.positionErrorAtZeroSimilarity).coerceIn(0.0, 1.0)
    }

    /** Conservative-weighted absolute angle error mapped with the natural pi denominator. */
    private fun angularSimilarity(reference: PoseFeatures, observed: PoseFeatures): Double {
        val errors = mutableListOf<WeightedValue>()
        reference.jointAngles.forEach { (key, referenceAngle) ->
            val observedAngle = observed.jointAngles[key] ?: return@forEach
            val weight = min(referenceAngle.weight, observedAngle.weight)
            if (weight <= 0.0) return@forEach
            errors += WeightedValue(
                value = abs(referenceAngle.radians - observedAngle.radians),
                weight = weight,
            )
        }
        val meanError = scaleSafeWeightedMean(errors) ?: return 0.0
        return (1.0 - meanError / Math.PI).coerceIn(0.0, 1.0)
    }

    private data class CandidateEvidence(
        val landmarkCoverage: Double,
        val angularSimilarity: Double,
        val positionalSimilarity: Double,
        val overallMatch: Double,
        val failedCandidateGateCount: Int,
        val mirrorUsed: Boolean,
    )

    private data class WeightedValue(
        val value: Double,
        val weight: Double,
    )

    /**
     * Computes a weighted mean after dividing every weight by the largest weight. This preserves
     * relative weights while preventing positive subnormal evidence from underflowing to a false
     * zero contribution. Non-finite values fail closed through a non-finite result.
     */
    private fun scaleSafeWeightedMean(values: List<WeightedValue>): Double? {
        val maximumWeight = values.maxOfOrNull(WeightedValue::weight) ?: return null
        if (maximumWeight <= 0.0) return null

        var weightedSum = 0.0
        var scaledWeightSum = 0.0
        values.forEach { weightedValue ->
            if (!weightedValue.value.isFinite()) return Double.POSITIVE_INFINITY
            val scaledWeight = weightedValue.weight / maximumWeight
            weightedSum += scaledWeight * weightedValue.value
            scaledWeightSum += scaledWeight
        }
        if (scaledWeightSum <= 0.0) return null
        return weightedSum / scaledWeightSum
    }
}
