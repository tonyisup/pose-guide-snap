package com.tonyisup.poseguidesnap.domain.match

import kotlin.math.abs

/**
 * Immutable thresholds, scales, and weights for [DefaultPoseMatcher].
 *
 * [developmentDefaults] is deliberately uncalibrated prototype policy. Its values are not a
 * production-quality claim and must be calibrated before any release decision relies on them.
 */
data class MatchPolicy(
    val minimumLandmarkCoverage: Double,
    val minimumFramingScore: Double,
    val minimumAngularSimilarity: Double,
    val minimumPositionalSimilarity: Double,
    val minimumOverallMatch: Double,
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
            minimumFramingScore = 0.8,
            minimumAngularSimilarity = 0.85,
            minimumPositionalSimilarity = 0.8,
            minimumOverallMatch = 0.825,
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
