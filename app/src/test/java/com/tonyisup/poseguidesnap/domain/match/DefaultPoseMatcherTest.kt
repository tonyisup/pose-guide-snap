package com.tonyisup.poseguidesnap.domain.match

import com.tonyisup.poseguidesnap.domain.model.MatchGateFailure
import com.tonyisup.poseguidesnap.domain.model.MatchResult
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPoseMatcherTest {
    @Test
    fun identicalPoseProducesPerfectNamedEvidenceAndIsEligibleWithoutMirror() {
        val pose = standardFeatures()

        val result = DefaultPoseMatcher().match(
            reference = pose,
            observed = pose,
            mirrorAllowed = false,
            detectedPersonCount = 1,
            framingScore = 1.0,
        )

        assertEquals(1.0, result.landmarkCoverage, 0.0)
        assertEquals(1.0, result.framingScore, 0.0)
        assertEquals(1.0, result.angularSimilarity, 0.0)
        assertEquals(1.0, result.positionalSimilarity, 0.0)
        assertEquals(1.0, result.overallMatch, 0.0)
        assertEquals(emptySet<MatchGateFailure>(), result.gateFailures)
        assertFalse(result.mirrorUsed)
        assertTrue(result.eligibleForLock)
    }

    @Test
    fun clearlyDifferentPoseReportsEverySimilarityFailure() {
        val reference = features(
            points = mapOf(PoseLandmark.NOSE to point(0.0)),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.0)),
        )
        val different = features(
            points = mapOf(PoseLandmark.NOSE to point(2.0)),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(PI)),
        )

        val result = DefaultPoseMatcher().match(reference, different, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0)

        assertEquals(1.0, result.landmarkCoverage, 0.0)
        assertEquals(0.0, result.angularSimilarity, 0.0)
        assertEquals(0.0, result.positionalSimilarity, 0.0)
        assertEquals(0.0, result.overallMatch, 0.0)
        assertEquals(
            setOf(
                MatchGateFailure.ANGULAR_MISMATCH,
                MatchGateFailure.POSITIONAL_MISMATCH,
                MatchGateFailure.LOW_OVERALL_MATCH,
            ),
            result.gateFailures,
        )
        assertFalse(result.eligibleForLock)
    }

    @Test
    fun mirrorAllowedSelectsBetterExplicitCandidateWhileDisabledModeUsesUnmirrored() {
        val reference = features(
            points = mapOf(PoseLandmark.LEFT_WRIST to point(0.0)),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.0)),
        )
        val unmirrored = features(
            points = mapOf(PoseLandmark.LEFT_WRIST to point(2.0)),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(PI)),
        )
        val mirrored = features(
            mirrorUsed = true,
            points = reference.points,
            angles = reference.jointAngles,
        )
        val matcher = DefaultPoseMatcher()

        val allowed = matcher.match(reference, unmirrored, mirrored, mirrorAllowed = true, detectedPersonCount = 1, framingScore = 1.0)
        val disabled = matcher.match(reference, unmirrored, mirrored, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0)

        assertTrue(allowed.mirrorUsed)
        assertTrue(allowed.eligibleForLock)
        assertEquals(emptySet<MatchGateFailure>(), allowed.gateFailures)
        assertFalse(disabled.mirrorUsed)
        assertFalse(disabled.eligibleForLock)
        assertEquals(
            setOf(
                MatchGateFailure.ANGULAR_MISMATCH,
                MatchGateFailure.POSITIONAL_MISMATCH,
                MatchGateFailure.LOW_OVERALL_MATCH,
            ),
            disabled.gateFailures,
        )
    }

    @Test
    fun exactCandidateTiePrefersUnmirroredDeterministically() {
        val reference = standardFeatures()
        val unmirrored = standardFeatures()
        val mirrored = standardFeatures(mirrorUsed = true)
        val matcher = DefaultPoseMatcher()

        repeat(100) {
            val result = matcher.match(reference, unmirrored, mirrored, mirrorAllowed = true, detectedPersonCount = 1, framingScore = 1.0)
            assertFalse(result.mirrorUsed)
            assertTrue(result.eligibleForLock)
        }
    }

    @Test
    fun insufficientCoverageIsIndependentFromPerfectCommonPoseEvidence() {
        val reference = features(
            points = mapOf(
                PoseLandmark.LEFT_WRIST to point(0.0),
                PoseLandmark.RIGHT_WRIST to point(1.0),
            ),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.5)),
        )
        val observed = features(
            points = mapOf(PoseLandmark.LEFT_WRIST to point(0.0)),
            angles = reference.jointAngles,
        )

        val result = DefaultPoseMatcher().match(reference, observed, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0)

        assertEquals(0.5, result.landmarkCoverage, 0.0)
        assertEquals(1.0, result.angularSimilarity, 0.0)
        assertEquals(1.0, result.positionalSimilarity, 0.0)
        assertEquals(1.0, result.overallMatch, 0.0)
        assertEquals(setOf(MatchGateFailure.INSUFFICIENT_LANDMARK_COVERAGE), result.gateFailures)
    }

    @Test
    fun poorFramingIsIndependentFromPerfectPoseEvidence() {
        val pose = standardFeatures()

        val result = DefaultPoseMatcher().match(pose, pose, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 0.79)

        assertEquals(1.0, result.overallMatch, 0.0)
        assertEquals(setOf(MatchGateFailure.POOR_FRAMING), result.gateFailures)
        assertFalse(result.eligibleForLock)
    }

    @Test
    fun noPersonRetainsPerfectSubscoresAndReportsOnlyPersonGate() {
        val pose = standardFeatures()

        val result = DefaultPoseMatcher().match(pose, pose, mirrorAllowed = false, detectedPersonCount = 0, framingScore = 1.0)

        assertEquals(1.0, result.landmarkCoverage, 0.0)
        assertEquals(1.0, result.angularSimilarity, 0.0)
        assertEquals(1.0, result.positionalSimilarity, 0.0)
        assertEquals(1.0, result.overallMatch, 0.0)
        assertEquals(setOf(MatchGateFailure.NO_PERSON), result.gateFailures)
    }

    @Test
    fun multiplePeopleRetainsPerfectSubscoresAndReportsOnlyPersonGate() {
        val pose = standardFeatures()

        val result = DefaultPoseMatcher().match(pose, pose, mirrorAllowed = false, detectedPersonCount = 2, framingScore = 1.0)

        assertEquals(1.0, result.landmarkCoverage, 0.0)
        assertEquals(1.0, result.angularSimilarity, 0.0)
        assertEquals(1.0, result.positionalSimilarity, 0.0)
        assertEquals(1.0, result.overallMatch, 0.0)
        assertEquals(setOf(MatchGateFailure.MULTIPLE_PEOPLE), result.gateFailures)
    }

    @Test
    fun subnormalEvidenceAndFiniteDistanceOverflowFailClosedWithoutFabricatingPerfectScores() {
        val reference = features(
            points = mapOf(PoseLandmark.NOSE to point(0.0, confidence = Double.MIN_VALUE)),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.0, weight = Double.MIN_VALUE)),
        )
        val observed = features(
            points = mapOf(PoseLandmark.NOSE to point(0.5, confidence = Double.MIN_VALUE)),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.1, weight = Double.MIN_VALUE)),
        )
        val matcher = DefaultPoseMatcher(
            policy(
                minimumLandmarkCoverage = 0.5,
                minimumFramingScore = 0.0,
                minimumAngularSimilarity = 0.99,
                minimumPositionalSimilarity = 0.75,
                minimumOverallMatch = 0.9,
            ),
        )

        val subnormal = matcher.match(
            reference,
            observed,
            mirrorAllowed = false,
            detectedPersonCount = 1,
            framingScore = 1.0,
        )

        assertEquals(1.0, subnormal.landmarkCoverage, 0.0)
        assertEquals(1.0 - 0.1 / PI, subnormal.angularSimilarity, STRICT_TOLERANCE)
        assertEquals(0.5, subnormal.positionalSimilarity, STRICT_TOLERANCE)
        assertEquals(
            setOf(
                MatchGateFailure.ANGULAR_MISMATCH,
                MatchGateFailure.POSITIONAL_MISMATCH,
                MatchGateFailure.LOW_OVERALL_MATCH,
            ),
            subnormal.gateFailures,
        )
        assertFalse(subnormal.eligibleForLock)

        val extremeReference = features(
            points = mapOf(PoseLandmark.NOSE to point(Double.MAX_VALUE)),
        )
        val extremeObserved = features(
            points = mapOf(PoseLandmark.NOSE to point(-Double.MAX_VALUE)),
        )
        val overflow = DefaultPoseMatcher(
            policy(
                minimumLandmarkCoverage = 0.0,
                minimumFramingScore = 0.0,
                minimumAngularSimilarity = 0.0,
                minimumPositionalSimilarity = 0.5,
                minimumOverallMatch = 0.0,
            ),
        ).match(
            extremeReference,
            extremeObserved,
            mirrorAllowed = false,
            detectedPersonCount = 1,
            framingScore = 1.0,
        )
        assertEquals(0.0, overflow.positionalSimilarity, 0.0)
        assertEquals(setOf(MatchGateFailure.POSITIONAL_MISMATCH), overflow.gateFailures)
    }

    @Test
    fun candidateSelectionPrioritizesCoverageEligibilityBeforeHigherScalarSimilarity() {
        val reference = features(
            points = mapOf(
                PoseLandmark.LEFT_WRIST to point(0.0),
                PoseLandmark.RIGHT_WRIST to point(1.0),
            ),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.0)),
        )
        val coverageFailingUnmirrored = features(
            points = mapOf(PoseLandmark.LEFT_WRIST to point(0.0)),
            angles = reference.jointAngles,
        )
        val fullyEligibleMirrored = features(
            mirrorUsed = true,
            points = mapOf(
                PoseLandmark.LEFT_WRIST to point(0.1),
                PoseLandmark.RIGHT_WRIST to point(1.1),
            ),
            angles = reference.jointAngles,
        )

        val result = DefaultPoseMatcher().match(
            reference,
            coverageFailingUnmirrored,
            fullyEligibleMirrored,
            mirrorAllowed = true,
            detectedPersonCount = 1,
            framingScore = 1.0,
        )

        assertTrue(result.mirrorUsed)
        assertEquals(1.0, result.landmarkCoverage, 0.0)
        assertEquals(0.9, result.positionalSimilarity, STRICT_TOLERANCE)
        assertTrue(result.eligibleForLock)
        assertEquals(emptySet<MatchGateFailure>(), result.gateFailures)
    }

    @Test
    fun everyNumericGateAcceptsItsExactThresholdAndRejectsTheAdjacentLowerValue() {
        fun assertBoundary(
            gate: MatchGateFailure,
            exact: MatchResult,
            below: MatchResult,
        ) {
            assertFalse("$gate must pass at equality", gate in exact.gateFailures)
            assertTrue("$gate must fail immediately below", gate in below.gateFailures)
        }

        val coverageReference = features(points = mapOf(PoseLandmark.NOSE to point(0.0)))
        fun coverageObserved(confidence: Double) =
            features(points = mapOf(PoseLandmark.NOSE to point(0.0, confidence = confidence)))
        val coverageMatcher = DefaultPoseMatcher(
            policy(
                minimumLandmarkCoverage = 0.5,
                minimumFramingScore = 0.0,
                minimumAngularSimilarity = 0.0,
                minimumPositionalSimilarity = 0.0,
                minimumOverallMatch = 0.0,
            ),
        )
        assertBoundary(
            MatchGateFailure.INSUFFICIENT_LANDMARK_COVERAGE,
            coverageMatcher.match(coverageReference, coverageObserved(0.5), mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0),
            coverageMatcher.match(coverageReference, coverageObserved(Math.nextDown(0.5)), mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0),
        )

        val empty = features()
        val framingMatcher = DefaultPoseMatcher(
            policy(
                minimumLandmarkCoverage = 0.0,
                minimumFramingScore = 0.5,
                minimumAngularSimilarity = 0.0,
                minimumPositionalSimilarity = 0.0,
                minimumOverallMatch = 0.0,
            ),
        )
        assertBoundary(
            MatchGateFailure.POOR_FRAMING,
            framingMatcher.match(empty, empty, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 0.5),
            framingMatcher.match(empty, empty, mirrorAllowed = false, detectedPersonCount = 1, framingScore = Math.nextDown(0.5)),
        )

        val angleReference = features(angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.0)))
        fun angleObserved(radians: Double) =
            features(angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(radians)))
        val angleMatcher = DefaultPoseMatcher(
            policy(
                minimumLandmarkCoverage = 0.0,
                minimumFramingScore = 0.0,
                minimumAngularSimilarity = 0.5,
                minimumPositionalSimilarity = 0.0,
                minimumOverallMatch = 0.0,
            ),
        )
        assertBoundary(
            MatchGateFailure.ANGULAR_MISMATCH,
            angleMatcher.match(angleReference, angleObserved(PI / 2.0), mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0),
            angleMatcher.match(angleReference, angleObserved(Math.nextUp(PI / 2.0)), mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0),
        )

        val positionReference = features(points = mapOf(PoseLandmark.NOSE to point(0.0)))
        fun positionObserved(distance: Double) =
            features(points = mapOf(PoseLandmark.NOSE to point(distance)))
        val positionMatcher = DefaultPoseMatcher(
            policy(
                minimumLandmarkCoverage = 0.0,
                minimumFramingScore = 0.0,
                minimumAngularSimilarity = 0.0,
                minimumPositionalSimilarity = 0.5,
                minimumOverallMatch = 0.0,
            ),
        )
        assertBoundary(
            MatchGateFailure.POSITIONAL_MISMATCH,
            positionMatcher.match(positionReference, positionObserved(0.5), mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0),
            positionMatcher.match(positionReference, positionObserved(Math.nextUp(0.5)), mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0),
        )

        val overallMatcher = DefaultPoseMatcher(
            policy(
                minimumLandmarkCoverage = 0.0,
                minimumFramingScore = 0.0,
                minimumAngularSimilarity = 0.0,
                minimumPositionalSimilarity = 0.0,
                minimumOverallMatch = 0.5,
                angularWeight = 0.0,
                positionalWeight = 1.0,
            ),
        )
        assertBoundary(
            MatchGateFailure.LOW_OVERALL_MATCH,
            overallMatcher.match(positionReference, positionObserved(0.5), mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0),
            overallMatcher.match(positionReference, positionObserved(Math.nextUp(0.5)), mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0),
        )
    }

    @Test
    fun weightedAggregateCannotHideARequiredPositionalFailure() {
        val policy = policy(
            minimumLandmarkCoverage = 0.0,
            minimumFramingScore = 0.0,
            minimumAngularSimilarity = 0.5,
            minimumPositionalSimilarity = 0.5,
            minimumOverallMatch = 0.8,
            angularWeight = 0.9,
            positionalWeight = 0.1,
        )
        val reference = features(
            points = mapOf(PoseLandmark.NOSE to point(0.0)),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.0)),
        )
        val observed = features(
            points = mapOf(PoseLandmark.NOSE to point(1.0)),
            angles = reference.jointAngles,
        )

        val result = DefaultPoseMatcher(policy).match(reference, observed, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0)

        assertEquals(1.0, result.angularSimilarity, 0.0)
        assertEquals(0.0, result.positionalSimilarity, 0.0)
        assertEquals(0.9, result.overallMatch, STRICT_TOLERANCE)
        assertEquals(setOf(MatchGateFailure.POSITIONAL_MISMATCH), result.gateFailures)
        assertFalse(result.eligibleForLock)
    }

    @Test
    fun candidateSelectionPrefersFewerSimilarityGateFailuresBeforeHigherAggregate() {
        val policy = policy(
            minimumLandmarkCoverage = 0.0,
            minimumFramingScore = 0.0,
            minimumAngularSimilarity = 0.8,
            minimumPositionalSimilarity = 0.5,
            minimumOverallMatch = 0.9,
        )
        val reference = features(
            points = mapOf(PoseLandmark.NOSE to point(0.0)),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.0)),
        )
        val higherAggregateWithTwoFailures = features(
            points = reference.points,
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.3 * PI)),
        )
        val lowerAggregateWithOneFailure = features(
            mirrorUsed = true,
            points = mapOf(PoseLandmark.NOSE to point(0.32)),
            angles = reference.jointAngles,
        )

        val result = DefaultPoseMatcher(policy).match(
            reference,
            higherAggregateWithTwoFailures,
            lowerAggregateWithOneFailure,
            mirrorAllowed = true,
            detectedPersonCount = 1,
            framingScore = 1.0,
        )

        assertTrue(result.mirrorUsed)
        assertEquals(0.84, result.overallMatch, STRICT_TOLERANCE)
        assertEquals(setOf(MatchGateFailure.LOW_OVERALL_MATCH), result.gateFailures)
    }

    @Test
    fun emptyAndNonSharedEvidenceProducesBoundedZerosWithoutNaN() {
        val empty = features()
        val noSharedReference = features(
            points = mapOf(PoseLandmark.LEFT_WRIST to point(0.0)),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.0)),
        )
        val noSharedObserved = features(
            points = mapOf(PoseLandmark.RIGHT_WRIST to point(0.0)),
            angles = mapOf(JointAngleKey.RIGHT_ELBOW to angle(0.0)),
        )
        val matcher = DefaultPoseMatcher()

        listOf(
            matcher.match(empty, empty, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0),
            matcher.match(noSharedReference, noSharedObserved, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0),
        ).forEach { result ->
            assertEquals(0.0, result.landmarkCoverage, 0.0)
            assertEquals(0.0, result.angularSimilarity, 0.0)
            assertEquals(0.0, result.positionalSimilarity, 0.0)
            assertEquals(0.0, result.overallMatch, 0.0)
            assertEquals(
                setOf(
                    MatchGateFailure.INSUFFICIENT_LANDMARK_COVERAGE,
                    MatchGateFailure.ANGULAR_MISMATCH,
                    MatchGateFailure.POSITIONAL_MISMATCH,
                    MatchGateFailure.LOW_OVERALL_MATCH,
                ),
                result.gateFailures,
            )
        }
    }

    @Test
    fun zeroConfidenceEvidenceUsesZeroInsteadOfDividingByZero() {
        val zero = features(
            points = mapOf(PoseLandmark.NOSE to point(0.0, confidence = 0.0)),
            angles = mapOf(JointAngleKey.LEFT_ELBOW to angle(0.0, weight = 0.0)),
        )

        val result = DefaultPoseMatcher().match(zero, zero, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0)

        assertEquals(0.0, result.landmarkCoverage, 0.0)
        assertEquals(0.0, result.angularSimilarity, 0.0)
        assertEquals(0.0, result.positionalSimilarity, 0.0)
        assertEquals(0.0, result.overallMatch, 0.0)
    }

    @Test
    fun coverageAndPositionUseConservativeConfidenceWeights() {
        val reference = features(
            points = mapOf(
                PoseLandmark.LEFT_WRIST to point(0.0, confidence = 0.8),
                PoseLandmark.RIGHT_WRIST to point(0.0, confidence = 0.2),
            ),
        )
        val observed = features(
            points = mapOf(
                PoseLandmark.LEFT_WRIST to point(0.5, confidence = 0.4),
                PoseLandmark.RIGHT_WRIST to point(1.0, confidence = 1.0),
            ),
        )
        val matcher = DefaultPoseMatcher(policy(minimumAngularSimilarity = 0.0, minimumOverallMatch = 0.0))

        val result = matcher.match(reference, observed, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0)

        // Coverage: (min(.8,.4) + min(.2,1)) / (.8 + .2) = .6.
        assertEquals(0.6, result.landmarkCoverage, STRICT_TOLERANCE)
        // Mean distance: (.4*.5 + .2*1) / (.4 + .2) = 2/3; scale 1 gives similarity 1/3.
        assertEquals(1.0 / 3.0, result.positionalSimilarity, STRICT_TOLERANCE)
    }

    @Test
    fun angularSimilarityUsesConservativeWeightsAndNaturalPiScale() {
        val reference = features(
            angles = mapOf(
                JointAngleKey.LEFT_ELBOW to angle(0.0, weight = 0.8),
                JointAngleKey.RIGHT_ELBOW to angle(0.0, weight = 0.2),
            ),
        )
        val observed = features(
            angles = mapOf(
                JointAngleKey.LEFT_ELBOW to angle(PI / 2.0, weight = 0.4),
                JointAngleKey.RIGHT_ELBOW to angle(PI, weight = 1.0),
            ),
        )
        val matcher = DefaultPoseMatcher(policy(minimumLandmarkCoverage = 0.0, minimumPositionalSimilarity = 0.0, minimumOverallMatch = 0.0))

        val result = matcher.match(reference, observed, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0)

        // Mean error: (.4*pi/2 + .2*pi) / (.4 + .2) = 2*pi/3; similarity = 1/3.
        assertEquals(1.0 / 3.0, result.angularSimilarity, STRICT_TOLERANCE)
    }

    @Test
    fun positionScaleIsAppliedWithoutRenormalizingMissingAngularEvidence() {
        val reference = features(points = mapOf(PoseLandmark.NOSE to point(0.0)))
        val observed = features(points = mapOf(PoseLandmark.NOSE to point(1.0)))
        val matcher = DefaultPoseMatcher(
            policy(
                positionErrorAtZeroSimilarity = 2.0,
                minimumAngularSimilarity = 0.0,
                minimumPositionalSimilarity = 0.0,
                minimumOverallMatch = 0.0,
                angularWeight = 0.25,
                positionalWeight = 0.75,
            ),
        )

        val result = matcher.match(reference, observed, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0)

        assertEquals(0.0, result.angularSimilarity, 0.0)
        assertEquals(0.5, result.positionalSimilarity, STRICT_TOLERANCE)
        assertEquals(0.375, result.overallMatch, STRICT_TOLERANCE)
    }

    @Test
    fun developmentDefaultsAreExplicitUncalibratedPrototypeValues() {
        val defaults = MatchPolicy.developmentDefaults()

        assertEquals(0.75, defaults.minimumLandmarkCoverage, 0.0)
        assertEquals(0.8, defaults.minimumFramingScore, 0.0)
        assertEquals(0.85, defaults.minimumAngularSimilarity, 0.0)
        assertEquals(0.8, defaults.minimumPositionalSimilarity, 0.0)
        assertEquals(0.825, defaults.minimumOverallMatch, 0.0)
        assertEquals(1.0, defaults.positionErrorAtZeroSimilarity, 0.0)
        assertEquals(0.5, defaults.angularWeight, 0.0)
        assertEquals(0.5, defaults.positionalWeight, 0.0)
    }

    @Test
    fun policyRejectsInvalidThresholdsScaleWeightsAndWeightSums() {
        val invalidNormalized = listOf(-0.01, 1.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        val thresholdFactories = listOf<(Double) -> MatchPolicy>(
            { policy(minimumLandmarkCoverage = it) },
            { policy(minimumFramingScore = it) },
            { policy(minimumAngularSimilarity = it) },
            { policy(minimumPositionalSimilarity = it) },
            { policy(minimumOverallMatch = it) },
        )
        thresholdFactories.forEach { factory ->
            invalidNormalized.forEach { value ->
                assertThrows(IllegalArgumentException::class.java) { factory(value) }
            }
        }
        listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { policy(positionErrorAtZeroSimilarity = it) }
        }
        listOf(-0.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { policy(angularWeight = it, positionalWeight = 1.0) }
            assertThrows(IllegalArgumentException::class.java) { policy(angularWeight = 1.0, positionalWeight = it) }
        }
        assertThrows(IllegalArgumentException::class.java) { policy(angularWeight = 0.0, positionalWeight = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { policy(angularWeight = 0.5, positionalWeight = 0.500000000002) }
        policy(angularWeight = 0.5, positionalWeight = 0.5000000000005)
    }

    @Test
    fun matchRejectsInvalidPersonFramingAndCandidateMirrorEvidence() {
        val unmirrored = standardFeatures()
        val mirrored = standardFeatures(mirrorUsed = true)
        val matcher = DefaultPoseMatcher()

        assertThrows(IllegalArgumentException::class.java) {
            matcher.match(unmirrored, unmirrored, mirrorAllowed = false, detectedPersonCount = -1, framingScore = 1.0)
        }
        listOf(-0.01, 1.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                matcher.match(unmirrored, unmirrored, mirrorAllowed = false, detectedPersonCount = 1, framingScore = it)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            matcher.match(unmirrored, mirrored, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            matcher.match(
                unmirrored,
                unmirrored,
                mirroredObserved = unmirrored,
                mirrorAllowed = true,
                detectedPersonCount = 1,
                framingScore = 1.0,
            )
        }
    }

    @Test
    fun repeatedMatchingReturnsEqualIndependentResults() {
        val reference = standardFeatures()
        val observed = features(
            points = reference.points.mapValues { (_, value) -> value.copy(x = value.x + 0.1) },
            angles = reference.jointAngles,
        )
        val matcher = DefaultPoseMatcher()
        val first = matcher.match(reference, observed, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 0.9)

        repeat(100) {
            assertEquals(first, matcher.match(reference, observed, mirrorAllowed = false, detectedPersonCount = 1, framingScore = 0.9))
        }
    }

    private fun standardFeatures(mirrorUsed: Boolean = false): PoseFeatures = features(
        mirrorUsed = mirrorUsed,
        points = mapOf(
            PoseLandmark.LEFT_SHOULDER to point(-0.5, y = -0.5),
            PoseLandmark.RIGHT_SHOULDER to point(0.5, y = -0.5),
        ),
        angles = mapOf(
            JointAngleKey.LEFT_ELBOW to angle(PI / 2.0),
            JointAngleKey.RIGHT_ELBOW to angle(PI / 2.0),
        ),
    )

    private fun features(
        mirrorUsed: Boolean = false,
        points: Map<PoseLandmark, CanonicalPoint> = emptyMap(),
        angles: Map<JointAngleKey, WeightedJointAngle> = emptyMap(),
    ): PoseFeatures = PoseFeatures.create(points, mirrorUsed, angles)

    private fun point(
        x: Double,
        y: Double = 0.0,
        z: Double = 0.0,
        confidence: Double = 1.0,
    ): CanonicalPoint = CanonicalPoint(x, y, z, confidence)

    private fun angle(radians: Double, weight: Double = 1.0): WeightedJointAngle =
        WeightedJointAngle(radians, weight)

    private fun policy(
        minimumLandmarkCoverage: Double = 0.75,
        minimumFramingScore: Double = 0.8,
        minimumAngularSimilarity: Double = 0.85,
        minimumPositionalSimilarity: Double = 0.8,
        minimumOverallMatch: Double = 0.825,
        positionErrorAtZeroSimilarity: Double = 1.0,
        angularWeight: Double = 0.5,
        positionalWeight: Double = 0.5,
    ): MatchPolicy = MatchPolicy(
        minimumLandmarkCoverage,
        minimumFramingScore,
        minimumAngularSimilarity,
        minimumPositionalSimilarity,
        minimumOverallMatch,
        positionErrorAtZeroSimilarity,
        angularWeight,
        positionalWeight,
    )

    private companion object {
        const val STRICT_TOLERANCE = 1e-12
    }
}
