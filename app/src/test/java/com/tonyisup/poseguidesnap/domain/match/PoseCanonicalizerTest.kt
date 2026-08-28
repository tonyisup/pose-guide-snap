package com.tonyisup.poseguidesnap.domain.match

import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PoseCanonicalizerTest {
    @Test
    fun jsonFixturesLoadStrictlyAndBaselineContainsAllSemanticLandmarks() {
        val loaded = FIXTURE_NAMES.associateWith(::loadFixture)

        assertEquals(6, loaded.size)
        assertEquals(PoseLandmark.entries.toSet(), loaded.getValue("baseline").landmarks.map { it.type }.toSet())
        assertEquals(33, loaded.getValue("baseline").landmarks.size)
        assertEquals(31, loaded.getValue("occluded").landmarks.size)
    }

    @Test
    fun fixtureParserRejectsInvalidSchemaNumbersAndEnumNames() {
        assertThrows(IllegalArgumentException::class.java) {
            parseFixture("""{"monotonicTimestampNanos":1,"detectedPersonCount":0,"landmarks":[],"extra":true}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseFixture(
                """{"monotonicTimestampNanos":1,"detectedPersonCount":1,"landmarks":[{"type":"NOT_A_LANDMARK","x":0.5,"y":0.5,"z":0,"visibility":1,"presence":1}]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseFixture(
                """{"monotonicTimestampNanos":1,"detectedPersonCount":1,"landmarks":[{"type":"NOSE","x":"0.5","y":0.5,"z":0,"visibility":1,"presence":1}]}""",
            )
        }
    }

    @Test
    fun baselineIsCenteredAtTorsoMidpointAndScaledByTorsoLength() {
        val features = success("baseline")
        val leftShoulder = features.points.getValue(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = features.points.getValue(PoseLandmark.RIGHT_SHOULDER)
        val leftHip = features.points.getValue(PoseLandmark.LEFT_HIP)
        val rightHip = features.points.getValue(PoseLandmark.RIGHT_HIP)

        assertEquals(0.0, (leftShoulder.x + rightShoulder.x + leftHip.x + rightHip.x) / 4.0, STRICT_TOLERANCE)
        assertEquals(0.0, (leftShoulder.y + rightShoulder.y + leftHip.y + rightHip.y) / 4.0, STRICT_TOLERANCE)
        assertEquals(-0.14 / 0.26, leftShoulder.x, STRICT_TOLERANCE)
        assertEquals(-0.13 / 0.26, leftShoulder.y, STRICT_TOLERANCE)
        assertEquals(0.02 / 0.26, leftShoulder.z, STRICT_TOLERANCE)
    }

    @Test
    fun translationAndUniformScaleCanonicalizeToBaseline() {
        assertFeaturesClose(success("baseline"), success("translated"), STRICT_TOLERANCE)
        assertFeaturesClose(success("baseline"), success("scaled"), STRICT_TOLERANCE)
    }

    @Test
    fun explicitMirrorCandidateRestoresCoordinatesAndBilateralIdentities() {
        val baseline = success("baseline")
        val mirrored = success("mirrored", mirror = true)

        assertFeaturesClose(baseline, mirrored, STRICT_TOLERANCE, compareMirrorEvidence = false)
        assertTrue(mirrored.mirrorUsed)
        assertEquals(
            baseline.points.getValue(PoseLandmark.LEFT_WRIST),
            mirrored.points.getValue(PoseLandmark.LEFT_WRIST),
        )
    }

    @Test
    fun mirroredObservationRemainsDifferentWithoutExplicitTransform() {
        val baseline = success("baseline")
        val untransformed = success("mirrored", mirror = false)

        assertFalse(untransformed.mirrorUsed)
        assertTrue(
            abs(
                baseline.points.getValue(PoseLandmark.LEFT_WRIST).y -
                    untransformed.points.getValue(PoseLandmark.LEFT_WRIST).y,
            ) > 0.01,
        )
    }

    @Test
    fun confidenceUsesConservativeMinimumAndFiltersDependentAngles() {
        val baseline = success("baseline")
        val lowConfidence = success("low-confidence")

        assertEquals(0.92, baseline.points.getValue(PoseLandmark.LEFT_SHOULDER).confidence, 0.0)
        assertEquals(0.86, baseline.jointAngles.getValue(JointAngleKey.LEFT_ELBOW).weight, 0.0)
        assertFalse(lowConfidence.points.containsKey(PoseLandmark.LEFT_WRIST))
        assertFalse(lowConfidence.jointAngles.containsKey(JointAngleKey.LEFT_ELBOW))
        assertEquals(
            baseline.points.getValue(PoseLandmark.RIGHT_WRIST).confidence,
            lowConfidence.points.getValue(PoseLandmark.RIGHT_WRIST).confidence,
            0.0,
        )
    }

    @Test
    fun occludedLandmarksAndDependentAnglesAreOmittedWithoutInventedCoordinates() {
        val features = success("occluded")

        assertFalse(features.points.containsKey(PoseLandmark.RIGHT_ANKLE))
        assertFalse(features.points.containsKey(PoseLandmark.LEFT_INDEX))
        assertFalse(features.jointAngles.containsKey(JointAngleKey.RIGHT_KNEE))
        assertEquals(31, features.points.size)
    }

    @Test
    fun missingAndLowConfidenceTorsoAnchorsReturnTypedEvidence() {
        val baseline = loadFixture("baseline")
        val missingObservation = observationWith(
            baseline.landmarks.filterNot { it.type == PoseLandmark.LEFT_SHOULDER },
        )
        val lowConfidenceObservation = observationWith(
            baseline.landmarks.map {
                if (it.type == PoseLandmark.RIGHT_HIP) it.copy(visibility = 0.1) else it
            },
        )

        val missing = failure(canonicalizer.canonicalize(missingObservation))
        val lowConfidence = failure(canonicalizer.canonicalize(lowConfidenceObservation))
        assertEquals(PoseCanonicalizationFailureReason.MISSING_TORSO_ANCHORS, missing.reason)
        assertEquals(setOf(PoseLandmark.LEFT_SHOULDER), missing.missingTorsoAnchors)
        assertEquals(PoseCanonicalizationFailureReason.MISSING_TORSO_ANCHORS, lowConfidence.reason)
        assertEquals(setOf(PoseLandmark.RIGHT_HIP), lowConfidence.missingTorsoAnchors)
        assertThrows(UnsupportedOperationException::class.java) {
            (missing.missingTorsoAnchors as MutableSet).clear()
        }
    }

    @Test
    fun coincidentTorsoMidpointsReturnDegenerateScaleFailure() {
        val baseline = loadFixture("baseline")
        val anchors = TORSO_ANCHORS
        val degenerate = observationWith(
            baseline.landmarks.map {
                if (it.type in anchors) it.copy(x = 0.5, y = 0.5, z = 0.0) else it
            },
        )

        val failure = failure(canonicalizer.canonicalize(degenerate))
        assertEquals(PoseCanonicalizationFailureReason.DEGENERATE_TORSO_SCALE, failure.reason)
        assertTrue(failure.missingTorsoAnchors.isEmpty())
    }

    @Test
    fun finiteInputNormalizationOverflowReturnsTypedTorsoAnchorFailure() {
        val baseline = loadFixture("baseline")
        val overflow = observationWith(
            baseline.landmarks.map { landmark ->
                when (landmark.type) {
                    PoseLandmark.LEFT_SHOULDER -> landmark.copy(
                        x = 0.5,
                        y = 0.5,
                        z = Double.MAX_VALUE / 2.0,
                    )
                    PoseLandmark.RIGHT_SHOULDER -> landmark.copy(
                        x = 0.5,
                        y = 0.5,
                        z = -Double.MAX_VALUE / 2.0,
                    )
                    PoseLandmark.LEFT_HIP,
                    PoseLandmark.RIGHT_HIP,
                    -> landmark.copy(x = 0.5, y = 0.5, z = 2e-8)
                    else -> landmark
                }
            },
        )

        val failure = failure(canonicalizer.canonicalize(overflow))
        assertEquals(
            PoseCanonicalizationFailureReason.NON_FINITE_NORMALIZED_TORSO_ANCHORS,
            failure.reason,
        )
        assertEquals(
            setOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
            failure.invalidNormalizedTorsoAnchors,
        )
        assertTrue(failure.missingTorsoAnchors.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (failure.invalidNormalizedTorsoAnchors as MutableSet).clear()
        }

        val mirroredFailure = failure(canonicalizer.canonicalize(overflow, mirror = true))
        assertEquals(
            PoseCanonicalizationFailureReason.NON_FINITE_NORMALIZED_TORSO_ANCHORS,
            mirroredFailure.reason,
        )
        assertEquals(
            setOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
            mirroredFailure.invalidNormalizedTorsoAnchors,
        )
    }

    @Test
    fun policyRejectsNonFiniteOutOfRangeConfidenceAndNonPositiveScale() {
        listOf(-0.01, 1.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { PoseCanonicalizer(it, 1e-9) }
        }
        listOf(0.0, -0.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { PoseCanonicalizer(0.5, it) }
        }
        PoseCanonicalizer(0.0, Double.MIN_VALUE)
        PoseCanonicalizer(1.0, 1.0)
    }

    @Test
    fun featureOutputsSnapshotCallerMapsExposeUnmodifiableMapsAndNoPublicCopy() {
        val pointMap = linkedMapOf(PoseLandmark.NOSE to CanonicalPoint(0.0, 0.0, 0.0, 1.0))
        val angleMap = linkedMapOf(JointAngleKey.LEFT_ELBOW to WeightedJointAngle(1.0, 0.5))
        val features = PoseFeatures.create(pointMap, mirrorUsed = false, jointAngles = angleMap)
        pointMap.clear()
        angleMap.clear()

        assertEquals(1, features.points.size)
        assertEquals(1, features.jointAngles.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (features.points as MutableMap).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (features.jointAngles as MutableMap).clear()
        }
        assertFalse(PoseFeatures::class.java.methods.any { it.name == "copy" })
    }

    @Test
    fun supportedJointAnglesAreFiniteBoundedAndUseTripleMinimumConfidence() {
        val features = success("baseline")

        assertEquals(JointAngleKey.entries.toSet(), features.jointAngles.keys)
        features.jointAngles.values.forEach {
            assertTrue(it.radians.isFinite())
            assertTrue(it.radians in 0.0..Math.PI)
            assertTrue(it.weight in 0.0..1.0)
        }
        assertEquals(0.82, features.jointAngles.getValue(JointAngleKey.LEFT_KNEE).weight, 0.0)
    }

    @Test
    fun degenerateAngleVectorsAreOmitted() {
        val baseline = loadFixture("baseline")
        val shoulder = baseline.landmarks.single { it.type == PoseLandmark.LEFT_SHOULDER }
        val degenerateElbow = observationWith(
            baseline.landmarks.map {
                if (it.type == PoseLandmark.LEFT_ELBOW) {
                    it.copy(x = shoulder.x, y = shoulder.y, z = shoulder.z)
                } else {
                    it
                }
            },
        )

        val features = success(degenerateElbow)
        assertFalse(features.jointAngles.containsKey(JointAngleKey.LEFT_ELBOW))
        assertFalse(features.jointAngles.containsKey(JointAngleKey.LEFT_SHOULDER))
    }

    @Test
    fun bilateralIdentityMapIsCompleteAndInvolutive() {
        val mapped = PoseLandmark.entries.associateWith(PoseCanonicalizer::mirroredIdentity)

        assertEquals(PoseLandmark.entries.toSet(), mapped.values.toSet())
        mapped.forEach { (type, mirror) ->
            assertEquals(type, PoseCanonicalizer.mirroredIdentity(mirror))
        }
        assertEquals(PoseLandmark.NOSE, mapped.getValue(PoseLandmark.NOSE))
        assertEquals(PoseLandmark.RIGHT_WRIST, mapped.getValue(PoseLandmark.LEFT_WRIST))
        assertNotEquals(PoseLandmark.LEFT_HIP, mapped.getValue(PoseLandmark.LEFT_HIP))
    }

    @Test
    fun repeatedCallsAndSeededJitterAreDeterministicAndClose() {
        val baselineObservation = loadFixture("baseline")
        val baseline = success(baselineObservation)
        val seeds = listOf(7, 41, 20260828)

        seeds.forEach { seed ->
            val jitteredObservation = jittered(baselineObservation, seed)
            val first = success(jitteredObservation)
            val second = success(jitteredObservation)
            assertEquals(first, second)
            assertFeaturesClose(baseline, first, TEST_ONLY_JITTER_CHARACTERIZATION_TOLERANCE)
        }
    }

    @Test
    fun clearlyDifferentGeometryRemainsDifferentAfterNormalization() {
        val baselineObservation = loadFixture("baseline")
        val changedObservation = observationWith(
            baselineObservation.landmarks.map {
                if (it.type == PoseLandmark.LEFT_WRIST) it.copy(x = it.x + 0.08, y = it.y - 0.04) else it
            },
        )
        val baseline = success(baselineObservation)
        val changed = success(changedObservation)

        assertTrue(
            abs(
                baseline.points.getValue(PoseLandmark.LEFT_WRIST).x -
                    changed.points.getValue(PoseLandmark.LEFT_WRIST).x,
            ) > 0.1,
        )
        assertTrue(
            abs(
                baseline.jointAngles.getValue(JointAngleKey.LEFT_ELBOW).radians -
                    changed.jointAngles.getValue(JointAngleKey.LEFT_ELBOW).radians,
            ) > 0.05,
        )
    }

    @Test
    fun valueTypesRejectInvalidCoordinatesAnglesAndWeights() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalPoint(Double.NaN, 0.0, 0.0, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalPoint(0.0, 0.0, 0.0, 1.01)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WeightedJointAngle(-0.01, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WeightedJointAngle(1.0, Double.POSITIVE_INFINITY)
        }
    }

    private fun success(name: String, mirror: Boolean = false): PoseFeatures =
        success(loadFixture(name), mirror)

    private fun success(observation: PoseObservation, mirror: Boolean = false): PoseFeatures =
        when (val result = canonicalizer.canonicalize(observation, mirror)) {
            is PoseCanonicalizationResult.Success -> result.features
            is PoseCanonicalizationResult.Failure -> fail("Unexpected failure: ${result.reason}") as Nothing
        }

    private fun failure(result: PoseCanonicalizationResult): PoseCanonicalizationResult.Failure =
        result as? PoseCanonicalizationResult.Failure ?: fail("Expected failure but got $result") as Nothing

    private fun assertFeaturesClose(
        expected: PoseFeatures,
        actual: PoseFeatures,
        tolerance: Double,
        compareMirrorEvidence: Boolean = true,
    ) {
        assertEquals(expected.points.keys, actual.points.keys)
        expected.points.forEach { (type, expectedPoint) ->
            val actualPoint = actual.points.getValue(type)
            assertEquals("$type x", expectedPoint.x, actualPoint.x, tolerance)
            assertEquals("$type y", expectedPoint.y, actualPoint.y, tolerance)
            assertEquals("$type z", expectedPoint.z, actualPoint.z, tolerance)
            assertEquals("$type confidence", expectedPoint.confidence, actualPoint.confidence, 0.0)
        }
        assertEquals(expected.jointAngles.keys, actual.jointAngles.keys)
        expected.jointAngles.forEach { (key, expectedAngle) ->
            val actualAngle = actual.jointAngles.getValue(key)
            assertEquals("$key radians", expectedAngle.radians, actualAngle.radians, tolerance)
            assertEquals("$key weight", expectedAngle.weight, actualAngle.weight, 0.0)
        }
        if (compareMirrorEvidence) assertEquals(expected.mirrorUsed, actual.mirrorUsed)
    }

    private fun jittered(observation: PoseObservation, seed: Int): PoseObservation {
        val random = Random(seed)
        return observationWith(
            observation.landmarks.map {
                it.copy(
                    x = it.x + random.nextDouble(-JITTER_BOUND, JITTER_BOUND),
                    y = it.y + random.nextDouble(-JITTER_BOUND, JITTER_BOUND),
                    z = it.z + random.nextDouble(-JITTER_BOUND, JITTER_BOUND),
                )
            },
        )
    }

    private fun observationWith(landmarks: Iterable<Landmark>): PoseObservation =
        PoseObservation(landmarks, monotonicTimestampNanos = 123456789, detectedPersonCount = 1)

    private companion object {
        val canonicalizer = PoseCanonicalizer(minimumConfidence = 0.5, minimumTorsoScale = 1e-9)
        val FIXTURE_NAMES = listOf(
            "baseline",
            "translated",
            "scaled",
            "mirrored",
            "low-confidence",
            "occluded",
        )
        val TORSO_ANCHORS = setOf(
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
        )
        const val STRICT_TOLERANCE = 1e-10
        const val JITTER_BOUND = 1e-8
        const val TEST_ONLY_JITTER_CHARACTERIZATION_TOLERANCE = 5e-7
    }
}

private fun loadFixture(name: String): PoseObservation {
    val path = "/poses/$name.json"
    val text = PoseCanonicalizerTest::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
        ?: throw IllegalArgumentException("Missing fixture resource $path")
    return parseFixture(text)
}

private fun parseFixture(text: String): PoseObservation {
    val root = Json.parseToJsonElement(text).requireObject("fixture")
    root.requireExactKeys(setOf("monotonicTimestampNanos", "detectedPersonCount", "landmarks"), "fixture")
    val timestamp = root.requireNumber("monotonicTimestampNanos").long
    val personCountLong = root.requireNumber("detectedPersonCount").long
    require(personCountLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "detectedPersonCount is outside Int range"
    }
    val landmarks = root.getValue("landmarks").jsonArray.mapIndexed { index, element ->
        val landmark = element.requireObject("landmarks[$index]")
        landmark.requireExactKeys(
            setOf("type", "x", "y", "z", "visibility", "presence"),
            "landmarks[$index]",
        )
        Landmark(
            type = PoseLandmark.valueOf(landmark.requireString("type")),
            x = landmark.requireNumber("x").double,
            y = landmark.requireNumber("y").double,
            z = landmark.requireNumber("z").double,
            visibility = landmark.requireNumber("visibility").double,
            presence = landmark.requireNumber("presence").double,
        )
    }
    return PoseObservation(landmarks, timestamp, personCountLong.toInt())
}

private fun JsonElement.requireObject(context: String): JsonObject =
    runCatching { jsonObject }.getOrElse { throw IllegalArgumentException("$context must be an object", it) }

private fun JsonObject.requireExactKeys(expected: Set<String>, context: String) {
    require(keys == expected) { "$context keys must be exactly $expected, but were $keys" }
}

private fun JsonObject.requireString(name: String): String {
    val primitive = getValue(name).jsonPrimitive
    require(primitive.isString) { "$name must be a string" }
    return primitive.content
}

private fun JsonObject.requireNumber(name: String) = getValue(name).jsonPrimitive.also {
    require(!it.isString) { "$name must be a JSON number" }
}
