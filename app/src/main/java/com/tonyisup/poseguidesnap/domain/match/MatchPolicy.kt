package com.tonyisup.poseguidesnap.domain.match

import kotlin.math.abs

/**
 * Immutable thresholds, scales, and weights for [DefaultPoseMatcher]. The `minimum*` values gate
 * lock acquisition. Each `releaseMinimum*` value may be lower, allowing bounded score hysteresis
 * while a lock is already held; it can never exceed the matching acquisition threshold.
 *
 * [developmentDefaults] is deliberately uncalibrated prototype policy. Its values are not a
 * production-quality claim and must be calibrated before any release decision relies on them.
 *
 * Since 25 September 2026 the defaults gate on person count, body visibility, landmark coverage,
 * angular similarity, and the blended overall match only. The composition (framing) score and the
 * separate positional score remain reported, but their acquisition thresholds are zero: replaying
 * every recorded Pixel 6 calibration run showed those two gates rejected every correct-pose frame
 * ever recorded, while the blended score separated the correct poses from the one true negative.
 * Body visibility is a structural gate supplied by the caller, not a score threshold.
 */
data class MatchPolicy(
    val minimumLandmarkCoverage: Double,
    val minimumFramingScore: Double,
    val minimumAngularSimilarity: Double,
    val minimumPositionalSimilarity: Double,
    val minimumOverallMatch: Double,
    val releaseMinimumLandmarkCoverage: Double,
    val releaseMinimumFramingScore: Double,
    val releaseMinimumAngularSimilarity: Double,
    val releaseMinimumPositionalSimilarity: Double,
    val releaseMinimumOverallMatch: Double,
    val positionErrorAtZeroSimilarity: Double,
    val angularWeight: Double,
    val positionalWeight: Double,
) {
    init {
        requireNormalized(minimumLandmarkCoverage, "minimumLandmarkCoverage")
        requireNormalized(minimumFramingScore, "minimumFramingScore")
        requireNormalized(minimumAngularSimilarity, "minimumAngularSimilarity")
        requireNormalized(minimumPositionalSimilarity, "minimumPositionalSimilarity")
        requireNormalized(minimumOverallMatch, "minimumOverallMatch")
        requireReleaseThreshold(
            releaseMinimumLandmarkCoverage,
            minimumLandmarkCoverage,
            "releaseMinimumLandmarkCoverage",
        )
        requireReleaseThreshold(
            releaseMinimumFramingScore,
            minimumFramingScore,
            "releaseMinimumFramingScore",
        )
        requireReleaseThreshold(
            releaseMinimumAngularSimilarity,
            minimumAngularSimilarity,
            "releaseMinimumAngularSimilarity",
        )
        requireReleaseThreshold(
            releaseMinimumPositionalSimilarity,
            minimumPositionalSimilarity,
            "releaseMinimumPositionalSimilarity",
        )
        requireReleaseThreshold(
            releaseMinimumOverallMatch,
            minimumOverallMatch,
            "releaseMinimumOverallMatch",
        )
        require(positionErrorAtZeroSimilarity.isFinite() && positionErrorAtZeroSimilarity > 0.0) {
            "positionErrorAtZeroSimilarity must be finite and positive"
        }
        require(angularWeight.isFinite() && angularWeight >= 0.0) {
            "angularWeight must be finite and nonnegative"
        }
        require(positionalWeight.isFinite() && positionalWeight >= 0.0) {
            "positionalWeight must be finite and nonnegative"
        }
        require(angularWeight > 0.0 || positionalWeight > 0.0) {
            "angularWeight and positionalWeight cannot both be zero"
        }
        require(abs((angularWeight + positionalWeight) - 1.0) <= WEIGHT_SUM_TOLERANCE) {
            "angularWeight and positionalWeight must sum to 1 within $WEIGHT_SUM_TOLERANCE"
        }
    }

    companion object {
        /** Uncalibrated development-only values for deterministic tests and prototype behavior. */
        fun developmentDefaults(): MatchPolicy = MatchPolicy(
            minimumLandmarkCoverage = 0.75,
            minimumFramingScore = 0.0,
            minimumAngularSimilarity = 0.85,
            minimumPositionalSimilarity = 0.0,
            minimumOverallMatch = 0.825,
            releaseMinimumLandmarkCoverage = 0.70,
            releaseMinimumFramingScore = 0.0,
            releaseMinimumAngularSimilarity = 0.80,
            releaseMinimumPositionalSimilarity = 0.0,
            releaseMinimumOverallMatch = 0.775,
            positionErrorAtZeroSimilarity = 1.0,
            angularWeight = 0.5,
            positionalWeight = 0.5,
        )

        /** Strict allowance for harmless floating-point construction of a unit weight sum. */
        private const val WEIGHT_SUM_TOLERANCE = 1e-12
    }
}

private fun requireNormalized(value: Double, name: String) {
    require(value.isFinite() && value in 0.0..1.0) {
        "$name must be finite and in [0, 1]"
    }
}

private fun requireReleaseThreshold(value: Double, acquireValue: Double, name: String) {
    requireNormalized(value, name)
    require(value <= acquireValue) {
        "$name must not exceed its acquisition threshold"
    }
}
