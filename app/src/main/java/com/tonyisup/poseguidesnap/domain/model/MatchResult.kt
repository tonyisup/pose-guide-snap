package com.tonyisup.poseguidesnap.domain.model

/** Mandatory matching gates that may independently block lock eligibility. */
enum class MatchGateFailure {
    NO_PERSON,
    MULTIPLE_PEOPLE,
    INSUFFICIENT_LANDMARK_COVERAGE,
    POOR_FRAMING,
    ANGULAR_MISMATCH,
    POSITIONAL_MISMATCH,
    LOW_OVERALL_MATCH,
}

/** Explainable matching evidence; threshold policy and matching algorithms live elsewhere. */
@ConsistentCopyVisibility
data class MatchResult private constructor(
    val landmarkCoverage: Double,
    val framingScore: Double,
    val angularSimilarity: Double,
    val positionalSimilarity: Double,
    val overallMatch: Double,
    val gateFailures: Set<MatchGateFailure>,
    val mirrorUsed: Boolean,
    val eligibleForLock: Boolean,
) {
    constructor(
        landmarkCoverage: Double,
        framingScore: Double,
        angularSimilarity: Double,
        positionalSimilarity: Double,
        overallMatch: Double,
        gateFailures: Iterable<MatchGateFailure>,
        mirrorUsed: Boolean,
        eligibleForLock: Boolean,
    ) : this(
        landmarkCoverage = landmarkCoverage,
        framingScore = framingScore,
        angularSimilarity = angularSimilarity,
        positionalSimilarity = positionalSimilarity,
        overallMatch = overallMatch,
        gateFailures = immutableSet(gateFailures),
        mirrorUsed = mirrorUsed,
        eligibleForLock = eligibleForLock,
    )

    init {
        requireNormalized(landmarkCoverage, "landmarkCoverage")
        requireNormalized(framingScore, "framingScore")
        requireNormalized(angularSimilarity, "angularSimilarity")
        requireNormalized(positionalSimilarity, "positionalSimilarity")
        requireNormalized(overallMatch, "overallMatch")
        require(!eligibleForLock || gateFailures.isEmpty()) {
            "eligibleForLock cannot be true when a mandatory gate failed"
        }
    }
}
