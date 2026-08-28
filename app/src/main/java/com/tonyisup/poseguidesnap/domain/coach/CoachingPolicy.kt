package com.tonyisup.poseguidesnap.domain.coach

import com.tonyisup.poseguidesnap.domain.model.CoachingCue

/**
 * Immutable thresholds and durations for [DefaultCoachingEngine].
 *
 * [developmentDefaults] is deliberately uncalibrated prototype policy. Its values are not a
 * production-quality claim and must be calibrated before any release decision relies on them.
 */
data class CoachingPolicy(
    val minimumCandidateConfidence: Double,
    val minimumPersistenceNanos: Long,
    val repeatSuppressionNanos: Long,
    val materialWorseningDelta: Double,
) {
    init {
        requireNormalized(minimumCandidateConfidence, "minimumCandidateConfidence")
        require(minimumPersistenceNanos >= 0L) {
            "minimumPersistenceNanos must be nonnegative"
        }
        require(repeatSuppressionNanos >= 0L) {
            "repeatSuppressionNanos must be nonnegative"
        }
        requireNormalized(materialWorseningDelta, "materialWorseningDelta")
    }

    companion object {
        /** Uncalibrated development-only values for deterministic tests and prototypes. */
        fun developmentDefaults(): CoachingPolicy = CoachingPolicy(
            minimumCandidateConfidence = 0.75,
            minimumPersistenceNanos = 500_000_000L,
            repeatSuppressionNanos = 3_000_000_000L,
            materialWorseningDelta = 0.15,
        )
    }
}

/** Fixed-vocabulary evidence proposed to the coaching reducer. */
data class CoachingCandidate(
    val cue: CoachingCue,
    val magnitude: Double,
    val confidence: Double,
) {
    init {
        require(isCandidateCue(cue)) {
            "unsupported coaching candidate cue: $cue"
        }
        requireNormalized(magnitude, "magnitude")
        requireNormalized(confidence, "confidence")
    }
}

internal fun isFramingCue(cue: CoachingCue): Boolean = when (cue) {
    CoachingCue.CenterInFrame,
    CoachingCue.IncludeFullBody,
    CoachingCue.MoveCloser,
    CoachingCue.MoveFartherAway,
    -> true

    else -> false
}

internal fun isActionablePoseCue(cue: CoachingCue): Boolean = when (cue) {
    is CoachingCue.MoveJoint,
    is CoachingCue.TurnShoulders,
    is CoachingCue.LeanTorso,
    -> true

    else -> false
}

private fun isCandidateCue(cue: CoachingCue): Boolean =
    isFramingCue(cue) || isActionablePoseCue(cue)

private fun requireNormalized(value: Double, name: String) {
    require(value.isFinite() && value in 0.0..1.0) {
        "$name must be finite and in [0, 1], but was $value"
    }
}
