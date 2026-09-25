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

/**
 * Explainable matching evidence; threshold policy and matching algorithms live elsewhere.
 * [gateFailures] describes acquisition, while [releaseGateFailures] describes whether an existing
 * lock may remain held before the reducer's temporal release hysteresis expires.
 */
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
    val releaseGateFailures: Set<MatchGateFailure>,
    val eligibleForLockRetention: Boolean,
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
        releaseGateFailures: Iterable<MatchGateFailure> = gateFailures,
        eligibleForLockRetention: Boolean = eligibleForLock,
    ) : this(
        landmarkCoverage = landmarkCoverage,
        framingScore = framingScore,
        angularSimilarity = angularSimilarity,
        positionalSimilarity = positionalSimilarity,
        overallMatch = overallMatch,
        gateFailures = immutableSet(gateFailures),
        mirrorUsed = mirrorUsed,
        eligibleForLock = eligibleForLock,
        releaseGateFailures = immutableSet(releaseGateFailures),
        eligibleForLockRetention = eligibleForLockRetention,
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
        require(!eligibleForLockRetention || releaseGateFailures.isEmpty()) {
            "eligibleForLockRetention cannot be true when a release gate failed"
        }
        require(releaseGateFailures.all(gateFailures::contains)) {
            "release gate failures must be a subset of acquisition gate failures"
        }
        require(!eligibleForLock || eligibleForLockRetention) {
            "acquisition eligibility requires lock-retention eligibility"
        }
    }
}
