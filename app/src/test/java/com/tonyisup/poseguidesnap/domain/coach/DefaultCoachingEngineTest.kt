package com.tonyisup.poseguidesnap.domain.coach

import com.tonyisup.poseguidesnap.domain.model.BodySide
import com.tonyisup.poseguidesnap.domain.model.CoachingCue
import com.tonyisup.poseguidesnap.domain.model.CoachingDirection
import com.tonyisup.poseguidesnap.domain.model.CoachingJoint
import com.tonyisup.poseguidesnap.domain.model.MatchGateFailure
import com.tonyisup.poseguidesnap.domain.model.MatchResult
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCoachingEngineTest {
    @Test
    fun coverageFailureOutranksFramingAndLimbAfterPersistence() {
        val engine = DefaultCoachingEngine(policy(persistence = 10L))
        val candidates = listOf(
            candidate(CoachingCue.CenterInFrame, magnitude = 0.9),
            candidate(move(CoachingJoint.WRIST), magnitude = 1.0),
        )
        val match = result(
            failures = setOf(
                MatchGateFailure.INSUFFICIENT_LANDMARK_COVERAGE,
                MatchGateFailure.POOR_FRAMING,
                MatchGateFailure.POSITIONAL_MISMATCH,
            ),
            landmarkCoverage = 0.4,
        )

        val pending = engine.evaluate(CoachingState(), match, candidates, 100L)
        val emitted = engine.evaluate(pending.nextState, match, candidates, 110L)

        assertNull(pending.cueToEmit)
        assertEquals(CoachingCue.IncludeFullBody, pending.nextState.pending?.cue)
        assertEquals(0.6, pending.nextState.pending?.magnitude ?: -1.0, 0.0)
        assertEquals(CoachingCue.IncludeFullBody, emitted.cueToEmit)
        assertNull(emitted.nextState.pending)
    }

    @Test
    fun framingFailureSelectsFramingBeforeLargerPoseCandidate() {
        val decision = immediateEngine().evaluate(
            CoachingState(),
            result(setOf(MatchGateFailure.POOR_FRAMING, MatchGateFailure.ANGULAR_MISMATCH)),
            listOf(
                candidate(CoachingCue.MoveCloser, magnitude = 0.4),
                candidate(move(CoachingJoint.WRIST), magnitude = 1.0),
            ),
            0L,
        )

        assertEquals(CoachingCue.MoveCloser, decision.cueToEmit)
    }

    @Test
    fun framingFailureWithoutConfidentFramingEvidenceEmitsNothing() {
        val engine = immediateEngine()
        val match = result(setOf(MatchGateFailure.POOR_FRAMING))

        listOf(
            emptyList(),
            listOf(candidate(CoachingCue.CenterInFrame, magnitude = 1.0, confidence = 0.74)),
            listOf(candidate(move(CoachingJoint.WRIST), magnitude = 1.0)),
        ).forEach { candidates ->
            val decision = engine.evaluate(CoachingState(), match, candidates, 0L)
            assertNull(decision.cueToEmit)
            assertNull(decision.nextState.pending)
        }
    }

    @Test
    fun poseMismatchSelectsLargestConfidentActionableCandidateAndDeduplicatesCue() {
        val expected = CoachingCue.TurnShoulders(CoachingDirection.RIGHT)
        val decision = immediateEngine().evaluate(
            CoachingState(),
            result(setOf(MatchGateFailure.ANGULAR_MISMATCH)),
            listOf(
                candidate(move(CoachingJoint.WRIST), magnitude = 0.65, confidence = 0.9),
                candidate(move(CoachingJoint.ELBOW), magnitude = 1.0, confidence = 0.74),
                candidate(expected, magnitude = 0.7, confidence = 0.8),
                candidate(expected, magnitude = 0.7, confidence = 0.95),
                candidate(CoachingCue.CenterInFrame, magnitude = 0.99),
            ),
            0L,
        )

        assertEquals(expected, decision.cueToEmit)
        assertEquals(0.7, decision.nextState.lastEmissions.getValue(expected).magnitude, 0.0)
    }

    @Test
    fun stableSemanticTieBreakIsIndependentOfCandidateOrder() {
        val elbow = candidate(move(CoachingJoint.ELBOW), magnitude = 0.8, confidence = 0.9)
        val wrist = candidate(move(CoachingJoint.WRIST), magnitude = 0.8, confidence = 0.9)
        val shoulder = candidate(move(CoachingJoint.SHOULDER), magnitude = 0.8, confidence = 0.9)
        val candidates = listOf(elbow, wrist, shoulder)
        val match = result(setOf(MatchGateFailure.POSITIONAL_MISMATCH))
        val decisions = permutations(candidates).map { order ->
            immediateEngine().evaluate(CoachingState(), match, order, 0L)
        }

        assertEquals(6, decisions.size)
        assertTrue(decisions.all { it == decisions.first() })
        assertEquals(elbow.cue, decisions.first().cueToEmit)
    }

    @Test
    fun lowConfidenceJointIsIgnored() {
        val decision = immediateEngine().evaluate(
            CoachingState(),
            result(setOf(MatchGateFailure.POSITIONAL_MISMATCH)),
            listOf(candidate(move(CoachingJoint.KNEE), magnitude = 1.0, confidence = 0.749999)),
            0L,
        )

        assertNull(decision.cueToEmit)
    }

    @Test
    fun mirrorMapsCanonicalPoseCueBackToLivePersonSemantics() {
        val match = result(setOf(MatchGateFailure.POSITIONAL_MISMATCH), mirrorUsed = true)
        val wristUp = CoachingCue.MoveJoint(CoachingJoint.WRIST, BodySide.LEFT, CoachingDirection.UP)
        val wristLeft = CoachingCue.MoveJoint(CoachingJoint.WRIST, BodySide.LEFT, CoachingDirection.LEFT)

        val up = immediateEngine().evaluate(CoachingState(), match, listOf(candidate(wristUp)), 0L)
        val lateral = immediateEngine().evaluate(CoachingState(), match, listOf(candidate(wristLeft)), 0L)

        assertEquals(
            CoachingCue.MoveJoint(CoachingJoint.WRIST, BodySide.RIGHT, CoachingDirection.UP),
            up.cueToEmit,
        )
        assertEquals(
            CoachingCue.MoveJoint(CoachingJoint.WRIST, BodySide.RIGHT, CoachingDirection.RIGHT),
            lateral.cueToEmit,
        )
    }

    @Test
    fun mirrorHelperIsAnInvolutionForEverySupportedCueAndLeavesFramingUnchanged() {
        val cues = listOf(
            move(CoachingJoint.WRIST, BodySide.LEFT, CoachingDirection.LEFT),
            move(CoachingJoint.KNEE, BodySide.RIGHT, CoachingDirection.FORWARD),
            CoachingCue.TurnShoulders(CoachingDirection.LEFT),
            CoachingCue.LeanTorso(CoachingDirection.RIGHT),
            CoachingCue.CenterInFrame,
            CoachingCue.IncludeFullBody,
            CoachingCue.MoveCloser,
            CoachingCue.MoveFartherAway,
            CoachingCue.PoseMatched,
        )

        cues.forEach { cue -> assertEquals(cue, mapCueForMirror(mapCueForMirror(cue))) }
        assertEquals(CoachingCue.CenterInFrame, mapCueForMirror(CoachingCue.CenterInFrame))
        assertEquals(CoachingCue.TurnShoulders(CoachingDirection.RIGHT), mapCueForMirror(CoachingCue.TurnShoulders(CoachingDirection.LEFT)))
        assertEquals(CoachingCue.LeanTorso(CoachingDirection.LEFT), mapCueForMirror(CoachingCue.LeanTorso(CoachingDirection.RIGHT)))
    }

    @Test
    fun personCountFailureClearsPendingAndLowOverallAloneHasNoCue() {
        val engine = DefaultCoachingEngine(policy(persistence = 10L))
        val poseMismatch = result(setOf(MatchGateFailure.ANGULAR_MISMATCH))
        val candidate = candidate(move(CoachingJoint.ELBOW))
        val pending = engine.evaluate(CoachingState(), poseMismatch, listOf(candidate), 0L).nextState

        listOf(MatchGateFailure.NO_PERSON, MatchGateFailure.MULTIPLE_PEOPLE).forEach { failure ->
            val decision = engine.evaluate(pending, result(setOf(failure)), listOf(candidate), 1L)
            assertNull(decision.cueToEmit)
            assertNull(decision.nextState.pending)
        }
        val lowOverall = engine.evaluate(
            pending,
            result(setOf(MatchGateFailure.LOW_OVERALL_MATCH)),
            listOf(candidate),
            1L,
        )
        assertNull(lowOverall.cueToEmit)
        assertNull(lowOverall.nextState.pending)
    }

    @Test
    fun eligibleMatchEmitsPoseMatchedThroughPersistenceAndSuppressesRepeat() {
        val engine = DefaultCoachingEngine(policy(persistence = 10L, suppression = 100L))
        val match = result()

        val pending = engine.evaluate(CoachingState(), match, emptyList(), 0L)
        val emitted = engine.evaluate(pending.nextState, match, emptyList(), 10L)
        val repeatPending = engine.evaluate(emitted.nextState, match, emptyList(), 11L)
        val suppressed = engine.evaluate(repeatPending.nextState, match, emptyList(), 21L)

        assertNull(pending.cueToEmit)
        assertEquals(CoachingCue.PoseMatched, emitted.cueToEmit)
        assertNull(repeatPending.cueToEmit)
        assertNull(suppressed.cueToEmit)
        assertEquals(CoachingCue.PoseMatched, suppressed.nextState.pending?.cue)
        assertEquals(10L, suppressed.nextState.lastEmissions.getValue(CoachingCue.PoseMatched).timestampNanos)
    }

    @Test
    fun persistenceUsesExactBoundaryAndCandidateDiscontinuityRestartsTimer() {
        val engine = DefaultCoachingEngine(policy(persistence = 10L))
        val match = result(setOf(MatchGateFailure.ANGULAR_MISMATCH))
        val elbow = candidate(move(CoachingJoint.ELBOW))
        val wrist = candidate(move(CoachingJoint.WRIST))

        val first = engine.evaluate(CoachingState(), match, listOf(elbow), 100L)
        val changed = engine.evaluate(first.nextState, match, listOf(wrist), 105L)
        val before = engine.evaluate(changed.nextState, match, listOf(wrist), 114L)
        val boundary = engine.evaluate(before.nextState, match, listOf(wrist), 115L)

        assertNull(first.cueToEmit)
        assertEquals(100L, first.nextState.pending?.firstSeenTimestampNanos)
        assertEquals(105L, changed.nextState.pending?.firstSeenTimestampNanos)
        assertNull(before.cueToEmit)
        assertEquals(wrist.cue, boundary.cueToEmit)
    }

    @Test
    fun noCueDiscontinuityResetsPersistenceTimer() {
        val engine = DefaultCoachingEngine(policy(persistence = 10L))
        val match = result(setOf(MatchGateFailure.ANGULAR_MISMATCH))
        val candidate = candidate(move(CoachingJoint.ELBOW))
        val first = engine.evaluate(CoachingState(), match, listOf(candidate), 0L)
        val gap = engine.evaluate(first.nextState, match, emptyList(), 9L)
        val restarted = engine.evaluate(gap.nextState, match, listOf(candidate), 10L)
        val tooEarly = engine.evaluate(restarted.nextState, match, listOf(candidate), 19L)
        val emitted = engine.evaluate(tooEarly.nextState, match, listOf(candidate), 20L)

        assertNull(gap.nextState.pending)
        assertEquals(10L, restarted.nextState.pending?.firstSeenTimestampNanos)
        assertNull(tooEarly.cueToEmit)
        assertEquals(candidate.cue, emitted.cueToEmit)
    }

    @Test
    fun backwardsAndNegativeTimestampsAreRejectedWithoutOverflowArithmetic() {
        val engine = DefaultCoachingEngine(policy(persistence = 5L))
        val match = result(setOf(MatchGateFailure.POSITIONAL_MISMATCH))
        val candidate = candidate(move(CoachingJoint.HIP))

        assertThrows(IllegalArgumentException::class.java) {
            engine.evaluate(CoachingState(), match, listOf(candidate), -1L)
        }
        val atTen = engine.evaluate(CoachingState(), match, listOf(candidate), 10L)
        assertThrows(IllegalArgumentException::class.java) {
            engine.evaluate(atTen.nextState, match, listOf(candidate), 9L)
        }

        val nearMaximum = engine.evaluate(CoachingState(), match, listOf(candidate), Long.MAX_VALUE - 5L)
        val maximum = engine.evaluate(nearMaximum.nextState, match, listOf(candidate), Long.MAX_VALUE)
        assertEquals(candidate.cue, maximum.cueToEmit)
    }

    @Test
    fun repeatSuppressionIsPerFinalCueAndAcceptsExactIntervalBoundary() {
        val engine = DefaultCoachingEngine(policy(persistence = 0L, suppression = 100L, worsening = 1.0))
        val match = result(setOf(MatchGateFailure.POSITIONAL_MISMATCH))
        val elbow = candidate(move(CoachingJoint.ELBOW), magnitude = 0.4)
        val wrist = candidate(move(CoachingJoint.WRIST), magnitude = 0.4)

        val first = engine.evaluate(CoachingState(), match, listOf(elbow), 0L)
        val other = engine.evaluate(first.nextState, match, listOf(wrist), 1L)
        val suppressed = engine.evaluate(other.nextState, match, listOf(elbow), 99L)
        val boundary = engine.evaluate(suppressed.nextState, match, listOf(elbow), 100L)

        assertEquals(elbow.cue, first.cueToEmit)
        assertEquals(wrist.cue, other.cueToEmit)
        assertNull(suppressed.cueToEmit)
        assertEquals(elbow.cue, boundary.cueToEmit)
        assertEquals(100L, boundary.nextState.lastEmissions.getValue(elbow.cue).timestampNanos)
        assertEquals(1L, boundary.nextState.lastEmissions.getValue(wrist.cue).timestampNanos)
        assertEquals(2, boundary.nextState.lastEmissions.size)
    }

    @Test
    fun materialWorseningBelowThresholdSuppressesAndEqualityPermitsEarlyRepeat() {
        val engine = DefaultCoachingEngine(policy(persistence = 0L, suppression = 100L, worsening = 0.2))
        val match = result(setOf(MatchGateFailure.POSITIONAL_MISMATCH))
        val cue = move(CoachingJoint.ANKLE)

        val first = engine.evaluate(CoachingState(), match, listOf(candidate(cue, magnitude = 0.4)), 0L)
        val below = engine.evaluate(first.nextState, match, listOf(candidate(cue, magnitude = Math.nextDown(0.6))), 1L)
        val equality = engine.evaluate(below.nextState, match, listOf(candidate(cue, magnitude = 0.6)), 2L)

        assertNull(below.cueToEmit)
        assertEquals(cue, equality.cueToEmit)
        assertEquals(2L, equality.nextState.lastEmissions.getValue(cue).timestampNanos)
        assertEquals(0.6, equality.nextState.lastEmissions.getValue(cue).magnitude, 0.0)
    }

    @Test
    fun zeroDurationPolicyEmitsImmediatelyAndRemainsDeterministic() {
        val engine = DefaultCoachingEngine(policy(persistence = 0L, suppression = 0L, worsening = 0.0))
        val match = result(setOf(MatchGateFailure.ANGULAR_MISMATCH))
        val candidates = listOf(candidate(move(CoachingJoint.WRIST), magnitude = 0.9))

        val first = engine.evaluate(CoachingState(), match, candidates, 7L)
        val second = engine.evaluate(first.nextState, match, candidates, 7L)
        val replay = engine.evaluate(CoachingState(), match, candidates, 7L)

        assertEquals(candidates.single().cue, first.cueToEmit)
        assertEquals(candidates.single().cue, second.cueToEmit)
        assertEquals(first, replay)
    }

    @Test
    fun policyAndCandidateRejectNonFiniteOutOfRangeAndUnsupportedValues() {
        val invalidPolicies = listOf<() -> Unit>(
            { policy(confidence = Double.NaN) },
            { policy(confidence = Double.POSITIVE_INFINITY) },
            { policy(confidence = -0.01) },
            { policy(confidence = 1.01) },
            { policy(persistence = -1L) },
            { policy(suppression = -1L) },
            { policy(worsening = Double.NaN) },
            { policy(worsening = Double.NEGATIVE_INFINITY) },
            { policy(worsening = -0.01) },
            { policy(worsening = 1.01) },
        )
        invalidPolicies.forEach { construct ->
            assertThrows(IllegalArgumentException::class.java) { construct() }
        }

        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { CoachingCandidate(CoachingCue.CenterInFrame, invalid, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { CoachingCandidate(CoachingCue.CenterInFrame, 1.0, invalid) }
        }

        listOf(
            CoachingCue.CaptureStarting,
            CoachingCue.NextPose,
            CoachingCue.Paused,
            CoachingCue.ShootComplete,
            CoachingCue.PoseMatched,
            CoachingCue.HoldStill,
        ).forEach { unsupported ->
            assertThrows(IllegalArgumentException::class.java) { candidate(unsupported) }
        }
    }

    @Test
    fun stateValidatesTemporalEvidenceSnapshotsLedgerAndHasNoPublicCopyBypass() {
        val cue = move(CoachingJoint.ELBOW)
        val mutableLedger = linkedMapOf<CoachingCue, CoachingEmissionRecord>(
            cue to CoachingEmissionRecord(4L, 0.5),
        )
        val state = CoachingState(lastEmissions = mutableLedger, lastEvaluationTimestampNanos = 5L)
        mutableLedger.clear()

        assertEquals(1, state.lastEmissions.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (state.lastEmissions as MutableMap<CoachingCue, CoachingEmissionRecord>)[CoachingCue.CenterInFrame] =
                CoachingEmissionRecord(5L, 0.4)
        }
        assertFalse(
            CoachingState::class.java.declaredMethods
                .filter { it.name.startsWith("copy") }
                .any { Modifier.isPublic(it.modifiers) },
        )

        assertThrows(IllegalArgumentException::class.java) { PendingCoachingCue(cue, -1L, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { PendingCoachingCue(cue, 0L, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { CoachingEmissionRecord(-1L, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { CoachingEmissionRecord(0L, 1.1) }
        assertThrows(IllegalArgumentException::class.java) { CoachingState(lastEvaluationTimestampNanos = -1L) }
        assertThrows(IllegalArgumentException::class.java) {
            CoachingState(
                pending = PendingCoachingCue(cue, 6L, 0.5),
                lastEvaluationTimestampNanos = 5L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoachingState(
                lastEmissions = mapOf(cue to CoachingEmissionRecord(6L, 0.5)),
                lastEvaluationTimestampNanos = 5L,
            )
        }
    }

    @Test
    fun candidateSurfaceContainsNoFreeTextAppearanceOrNumericSpokenScore() {
        val fields = CoachingCandidate::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.type }

        assertEquals(setOf("cue", "magnitude", "confidence"), fields.keys)
        assertFalse(fields.values.any { it == String::class.java })
    }

    @Test
    fun repeatedCallsEmitAtMostOneCueAndProduceEqualDecisionAndState() {
        val engine = immediateEngine()
        val match = result(setOf(MatchGateFailure.ANGULAR_MISMATCH))
        val candidates = listOf(
            candidate(move(CoachingJoint.WRIST), magnitude = 0.9),
            candidate(move(CoachingJoint.ELBOW), magnitude = 0.8),
            candidate(CoachingCue.TurnShoulders(CoachingDirection.LEFT), magnitude = 0.7),
        )

        val decisions = List(100) { engine.evaluate(CoachingState(), match, candidates, 42L) }

        assertTrue(decisions.all { it == decisions.first() })
        assertEquals(candidates.first().cue, decisions.first().cueToEmit)
        assertEquals(1, decisions.first().nextState.lastEmissions.size)
    }

    @Test
    fun developmentDefaultsAreExplicitUncalibratedValues() {
        assertEquals(
            CoachingPolicy(0.75, 500_000_000L, 3_000_000_000L, 0.15),
            CoachingPolicy.developmentDefaults(),
        )
    }

    private fun immediateEngine(): DefaultCoachingEngine =
        DefaultCoachingEngine(policy(persistence = 0L))

    private fun policy(
        confidence: Double = 0.75,
        persistence: Long = 0L,
        suppression: Long = 0L,
        worsening: Double = 0.15,
    ): CoachingPolicy = CoachingPolicy(
        minimumCandidateConfidence = confidence,
        minimumPersistenceNanos = persistence,
        repeatSuppressionNanos = suppression,
        materialWorseningDelta = worsening,
    )

    private fun candidate(
        cue: CoachingCue,
        magnitude: Double = 0.8,
        confidence: Double = 0.9,
    ): CoachingCandidate = CoachingCandidate(cue, magnitude, confidence)

    private fun move(
        joint: CoachingJoint,
        side: BodySide = BodySide.LEFT,
        direction: CoachingDirection = CoachingDirection.UP,
    ): CoachingCue.MoveJoint = CoachingCue.MoveJoint(joint, side, direction)

    private fun result(
        failures: Set<MatchGateFailure> = emptySet(),
        mirrorUsed: Boolean = false,
        landmarkCoverage: Double = 1.0,
    ): MatchResult = MatchResult(
        landmarkCoverage = landmarkCoverage,
        framingScore = 1.0,
        angularSimilarity = 1.0,
        positionalSimilarity = 1.0,
        overallMatch = if (failures.isEmpty()) 1.0 else 0.5,
        gateFailures = failures,
        mirrorUsed = mirrorUsed,
        eligibleForLock = failures.isEmpty(),
    )

    private fun <T> permutations(values: List<T>): List<List<T>> =
        if (values.size <= 1) {
            listOf(values)
        } else {
            values.flatMapIndexed { index, value ->
                permutations(values.filterIndexed { candidateIndex, _ -> candidateIndex != index })
                    .map { remainder -> listOf(value) + remainder }
            }
        }
}
