package com.tonyisup.poseguidesnap.domain.coach

import com.tonyisup.poseguidesnap.domain.model.BodySide
import com.tonyisup.poseguidesnap.domain.model.CoachingCue
import com.tonyisup.poseguidesnap.domain.model.CoachingDirection
import com.tonyisup.poseguidesnap.domain.model.MatchGateFailure
import com.tonyisup.poseguidesnap.domain.model.MatchResult
import java.math.BigDecimal
import java.util.Collections
import java.util.LinkedHashMap

/** A selected final output cue waiting to satisfy the persistence policy. */
data class PendingCoachingCue(
    val cue: CoachingCue,
    val firstSeenTimestampNanos: Long,
    val magnitude: Double,
) {
    init {
        require(isFinalOutputCue(cue)) { "pending cue is not a coaching-engine output: $cue" }
        require(firstSeenTimestampNanos >= 0L) {
            "firstSeenTimestampNanos must be nonnegative"
        }
        requireNormalized(magnitude, "magnitude")
    }
}

/** The most recent emission evidence for one final output cue. */
data class CoachingEmissionRecord(
    val timestampNanos: Long,
    val magnitude: Double,
) {
    init {
        require(timestampNanos >= 0L) { "timestampNanos must be nonnegative" }
        requireNormalized(magnitude, "magnitude")
    }
}

/**
 * Immutable reducer state supplied by and returned to the caller.
 *
 * The constructor validates temporal consistency and snapshots [lastEmissions]. This is a regular
 * immutable value class rather than a data class so no generated `copy` can bypass the snapshot.
 */
class CoachingState(
    pending: PendingCoachingCue? = null,
    lastEmissions: Map<CoachingCue, CoachingEmissionRecord> = emptyMap(),
    lastEvaluationTimestampNanos: Long? = null,
) {
    val pending: PendingCoachingCue? = pending
    val lastEmissions: Map<CoachingCue, CoachingEmissionRecord> = immutableLedger(lastEmissions)
    val lastEvaluationTimestampNanos: Long? = lastEvaluationTimestampNanos

    init {
        require(lastEvaluationTimestampNanos == null || lastEvaluationTimestampNanos >= 0L) {
            "lastEvaluationTimestampNanos must be null or nonnegative"
        }
        if (pending != null || this.lastEmissions.isNotEmpty()) {
            require(lastEvaluationTimestampNanos != null) {
                "temporal coaching evidence requires lastEvaluationTimestampNanos"
            }
        }
        pending?.let {
            require(it.firstSeenTimestampNanos <= requireNotNull(lastEvaluationTimestampNanos)) {
                "pending cue cannot have been first seen after the last evaluation"
            }
        }
        this.lastEmissions.forEach { (cue, record) ->
            require(isFinalOutputCue(cue)) { "emission ledger contains unsupported output cue: $cue" }
            require(record.timestampNanos <= requireNotNull(lastEvaluationTimestampNanos)) {
                "emission cannot be newer than the last evaluation"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is CoachingState &&
            pending == other.pending &&
            lastEmissions == other.lastEmissions &&
            lastEvaluationTimestampNanos == other.lastEvaluationTimestampNanos

    override fun hashCode(): Int {
        var result = pending?.hashCode() ?: 0
        result = 31 * result + lastEmissions.hashCode()
        result = 31 * result + (lastEvaluationTimestampNanos?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "CoachingState(pending=$pending, lastEmissions=$lastEmissions, " +
            "lastEvaluationTimestampNanos=$lastEvaluationTimestampNanos)"
}

/** At most one fixed-vocabulary cue and the next immutable reducer state. */
data class CoachingDecision(
    val nextState: CoachingState,
    val cueToEmit: CoachingCue?,
)

/**
 * Pure deterministic single-cue reducer.
 *
 * The caller owns state and the monotonic timestamp. There is no hidden clock, mutable engine
 * state, randomness, logging, filesystem access, session state, or audio behavior.
 */
class DefaultCoachingEngine(
    private val policy: CoachingPolicy = CoachingPolicy.developmentDefaults(),
) {
    fun evaluate(
        state: CoachingState,
        matchResult: MatchResult,
        candidates: Iterable<CoachingCandidate>,
        monotonicTimestampNanos: Long,
    ): CoachingDecision {
        require(monotonicTimestampNanos >= 0L) {
            "monotonicTimestampNanos must be nonnegative"
        }
        state.lastEvaluationTimestampNanos?.let { previous ->
            require(monotonicTimestampNanos >= previous) {
                "monotonicTimestampNanos moved backwards: $monotonicTimestampNanos < $previous"
            }
        }

        val selected = select(matchResult, candidates)
        if (selected == null) {
            return CoachingDecision(
                nextState = CoachingState(
                    pending = null,
                    lastEmissions = state.lastEmissions,
                    lastEvaluationTimestampNanos = monotonicTimestampNanos,
                ),
                cueToEmit = null,
            )
        }

        val pending = state.pending
            ?.takeIf { it.cue == selected.cue }
            ?.let {
                PendingCoachingCue(
                    cue = selected.cue,
                    firstSeenTimestampNanos = it.firstSeenTimestampNanos,
                    magnitude = selected.magnitude,
                )
            }
            ?: PendingCoachingCue(
                cue = selected.cue,
                firstSeenTimestampNanos = monotonicTimestampNanos,
                magnitude = selected.magnitude,
            )

        val persistenceElapsed = elapsed(
            now = monotonicTimestampNanos,
            then = pending.firstSeenTimestampNanos,
        )
        if (persistenceElapsed < policy.minimumPersistenceNanos) {
            return noEmission(state, pending, monotonicTimestampNanos)
        }

        val previousEmission = state.lastEmissions[selected.cue]
        if (previousEmission != null) {
            val repeatElapsed = elapsed(monotonicTimestampNanos, previousEmission.timestampNanos)
            val materiallyWorse = isAtLeastMateriallyWorse(
                currentMagnitude = selected.magnitude,
                previousMagnitude = previousEmission.magnitude,
                delta = policy.materialWorseningDelta,
            )
            if (repeatElapsed < policy.repeatSuppressionNanos && !materiallyWorse) {
                return noEmission(state, pending, monotonicTimestampNanos)
            }
        }

        val nextLedger = LinkedHashMap(state.lastEmissions)
        nextLedger[selected.cue] = CoachingEmissionRecord(
            timestampNanos = monotonicTimestampNanos,
            magnitude = selected.magnitude,
        )
        return CoachingDecision(
            nextState = CoachingState(
                pending = null,
                lastEmissions = nextLedger,
                lastEvaluationTimestampNanos = monotonicTimestampNanos,
            ),
            cueToEmit = selected.cue,
        )
    }

    private fun select(
        matchResult: MatchResult,
        candidates: Iterable<CoachingCandidate>,
    ): SelectedCue? {
        val failures = matchResult.gateFailures
        if (
            MatchGateFailure.NO_PERSON in failures ||
            MatchGateFailure.MULTIPLE_PEOPLE in failures
        ) {
            return null
        }
        if (MatchGateFailure.INSUFFICIENT_LANDMARK_COVERAGE in failures) {
            return SelectedCue(
                cue = CoachingCue.IncludeFullBody,
                magnitude = 1.0 - matchResult.landmarkCoverage,
                confidence = 1.0,
            )
        }
        if (MatchGateFailure.POOR_FRAMING in failures) {
            return strongestCandidate(matchResult, candidates, ::isFramingCue)
        }
        if (
            MatchGateFailure.ANGULAR_MISMATCH in failures ||
            MatchGateFailure.POSITIONAL_MISMATCH in failures
        ) {
            return strongestCandidate(matchResult, candidates, ::isActionablePoseCue)
        }
        if (matchResult.eligibleForLock) {
            return SelectedCue(CoachingCue.PoseMatched, magnitude = 1.0, confidence = 1.0)
        }
        return null
    }

    private fun strongestCandidate(
        matchResult: MatchResult,
        candidates: Iterable<CoachingCandidate>,
        supportedClass: (CoachingCue) -> Boolean,
    ): SelectedCue? = candidates
        .asSequence()
        .filter { it.confidence >= policy.minimumCandidateConfidence }
        .filter { supportedClass(it.cue) }
        .map { candidate ->
            SelectedCue(
                cue = if (matchResult.mirrorUsed) mapCueForMirror(candidate.cue) else candidate.cue,
                magnitude = candidate.magnitude,
                confidence = candidate.confidence,
            )
        }
        .sortedWith(SELECTED_CUE_ORDER)
        .distinctBy { it.cue }
        .firstOrNull()

    private fun noEmission(
        state: CoachingState,
        pending: PendingCoachingCue,
        monotonicTimestampNanos: Long,
    ): CoachingDecision = CoachingDecision(
        nextState = CoachingState(
            pending = pending,
            lastEmissions = state.lastEmissions,
            lastEvaluationTimestampNanos = monotonicTimestampNanos,
        ),
        cueToEmit = null,
    )
}

/** Mirror canonical/reference pose semantics exactly once into live-person semantics. */
internal fun mapCueForMirror(cue: CoachingCue): CoachingCue = when (cue) {
    is CoachingCue.MoveJoint -> cue.copy(
        side = cue.side.opposite(),
        direction = cue.direction.mirrorLateral(),
    )
    is CoachingCue.TurnShoulders -> CoachingCue.TurnShoulders(cue.direction.mirrorLateral())
    is CoachingCue.LeanTorso -> CoachingCue.LeanTorso(cue.direction.mirrorLateral())
    CoachingCue.CenterInFrame,
    CoachingCue.IncludeFullBody,
    CoachingCue.MoveCloser,
    CoachingCue.MoveFartherAway,
    CoachingCue.HoldStill,
    CoachingCue.PoseMatched,
    CoachingCue.CaptureStarting,
    CoachingCue.NextPose,
    CoachingCue.Paused,
    CoachingCue.ShootComplete,
    -> cue
}

private data class SelectedCue(
    val cue: CoachingCue,
    val magnitude: Double,
    val confidence: Double,
)

private val SELECTED_CUE_ORDER: Comparator<SelectedCue> =
    compareByDescending<SelectedCue> { it.magnitude }
        .thenByDescending { it.confidence }
        .thenBy { semanticKey(it.cue) }

private fun semanticKey(cue: CoachingCue): String = when (cue) {
    is CoachingCue.MoveJoint ->
        "MOVE_JOINT:${cue.joint.name}:${cue.side.name}:${cue.direction.name}"
    is CoachingCue.TurnShoulders -> "TURN_SHOULDERS:${cue.direction.name}"
    is CoachingCue.LeanTorso -> "LEAN_TORSO:${cue.direction.name}"
    CoachingCue.CenterInFrame -> "CENTER_IN_FRAME"
    CoachingCue.IncludeFullBody -> "INCLUDE_FULL_BODY"
    CoachingCue.MoveCloser -> "MOVE_CLOSER"
    CoachingCue.MoveFartherAway -> "MOVE_FARTHER_AWAY"
    CoachingCue.HoldStill -> "HOLD_STILL"
    CoachingCue.PoseMatched -> "POSE_MATCHED"
    CoachingCue.CaptureStarting -> "CAPTURE_STARTING"
    CoachingCue.NextPose -> "NEXT_POSE"
    CoachingCue.Paused -> "PAUSED"
    CoachingCue.ShootComplete -> "SHOOT_COMPLETE"
}

private fun BodySide.opposite(): BodySide = when (this) {
    BodySide.LEFT -> BodySide.RIGHT
    BodySide.RIGHT -> BodySide.LEFT
}

private fun CoachingDirection.mirrorLateral(): CoachingDirection = when (this) {
    CoachingDirection.LEFT -> CoachingDirection.RIGHT
    CoachingDirection.RIGHT -> CoachingDirection.LEFT
    CoachingDirection.UP,
    CoachingDirection.DOWN,
    CoachingDirection.FORWARD,
    CoachingDirection.BACKWARD,
    -> this
}

private fun elapsed(now: Long, then: Long): Long {
    require(now >= then) { "timestamp moved backwards: $now < $then" }
    return now - then
}

private fun isAtLeastMateriallyWorse(
    currentMagnitude: Double,
    previousMagnitude: Double,
    delta: Double,
): Boolean = BigDecimal.valueOf(currentMagnitude) >=
    BigDecimal.valueOf(previousMagnitude).add(BigDecimal.valueOf(delta))

private fun isFinalOutputCue(cue: CoachingCue): Boolean =
    isFramingCue(cue) || isActionablePoseCue(cue) || cue == CoachingCue.PoseMatched

private fun immutableLedger(
    values: Map<CoachingCue, CoachingEmissionRecord>,
): Map<CoachingCue, CoachingEmissionRecord> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private fun requireNormalized(value: Double, name: String) {
    require(value.isFinite() && value in 0.0..1.0) {
        "$name must be finite and in [0, 1], but was $value"
    }
}
