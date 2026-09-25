package com.tonyisup.poseguidesnap.domain.match

import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseFramingEvaluatorTest {
    @Test
    fun identicalQualifiedBodyHasPerfectIndependentFramingEvidence() {
        val pose = observation(standardLandmarks())

        val evidence = PoseFramingEvaluator().evaluate(pose, pose)

        assertEquals(FramingEvidenceStatus.EVALUATED, evidence.status)
        assertEquals(17, evidence.sharedLandmarkCount)
        assertEquals(1.0, evidence.centerSimilarity, 0.0)
        assertEquals(1.0, evidence.scaleSimilarity, 0.0)
        assertEquals(1.0, evidence.framingScore, 0.0)
    }

    @Test
    fun translationAndScaleRemainSeparateAndLowerComponentWins() {
        val referenceLandmarks = standardLandmarks()
        val reference = observation(referenceLandmarks)
        val translated = observation(referenceLandmarks.map { it.copy(x = it.x + 0.1) })
        val scaled = observation(referenceLandmarks.map { landmark ->
            landmark.copy(
                x = 0.44 + (landmark.x - 0.44) * 0.5,
                y = 0.45 + (landmark.y - 0.45) * 0.5,
            )
        })
        val evaluator = PoseFramingEvaluator()

        val translation = evaluator.evaluate(reference, translated)
        val scale = evaluator.evaluate(reference, scaled)

        assertEquals(0.8, translation.centerSimilarity, 1e-12)
        assertEquals(1.0, translation.scaleSimilarity, 1e-12)
        assertEquals(0.8, translation.framingScore, 1e-12)
        assertEquals(1.0, scale.centerSimilarity, 1e-12)
        assertEquals(0.5, scale.scaleSimilarity, 1e-12)
        assertEquals(0.5, scale.framingScore, 1e-12)
    }

    @Test
    fun insufficientSharedBodyAndPersonCountFailClosedWithoutPayload() {
        val reference = observation(standardLandmarks())
        val lowConfidence = observation(
            standardLandmarks().mapIndexed { index, landmark ->
                if (index < 5) landmark.copy(visibility = 0.24, presence = 0.24) else landmark
            },
        )
        val noPerson = PoseObservation(
            landmarks = emptyList(),
            monotonicTimestampNanos = 0L,
            detectedPersonCount = 0,
        )
        val evaluator = PoseFramingEvaluator()

        val insufficient = evaluator.evaluate(reference, lowConfidence)
        val absent = evaluator.evaluate(reference, noPerson)

        assertEquals(FramingEvidenceStatus.INSUFFICIENT_BODY_EVIDENCE, insufficient.status)
        assertEquals(12, insufficient.sharedLandmarkCount)
        assertEquals(0.0, insufficient.framingScore, 0.0)
        assertEquals(FramingEvidenceStatus.INVALID_PERSON_COUNT, absent.status)
        assertEquals(0, absent.sharedLandmarkCount)
        assertEquals(0.0, absent.framingScore, 0.0)
        assertEquals(
            setOf(
                "status",
                "sharedLandmarkCount",
                "centerSimilarity",
                "scaleSimilarity",
                "framingScore",
            ),
            FramingEvidence::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .mapTo(linkedSetOf()) { it.name },
        )
        assertTrue(
            FramingEvidence::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .all { field ->
                    Modifier.isFinal(field.modifiers) &&
                        !Iterable::class.java.isAssignableFrom(field.type) &&
                        !Map::class.java.isAssignableFrom(field.type) &&
                        !field.type.isArray
                },
        )
    }

    @Test
    fun invalidReferenceMissingTorsoAndDegenerateBodyEachFailClosed() {
        val landmarks = standardLandmarks()
        val evaluator = PoseFramingEvaluator()
        val invalidReference = evaluator.evaluate(
            observation(emptyList(), detectedPersonCount = 0),
            observation(landmarks),
        )
        val invalidBodyReference = evaluator.evaluate(
            observation(
                landmarks.filterNot {
                    it.type == PoseLandmark.LEFT_KNEE || it.type == PoseLandmark.LEFT_ANKLE
                },
            ),
            observation(landmarks),
        )
        val undercoveredReferenceTypes = setOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
        )
        val undercoveredReference = evaluator.evaluate(
            observation(landmarks.filter { it.type in undercoveredReferenceTypes }),
            observation(landmarks),
        )
        val missingTorso = evaluator.evaluate(
            observation(landmarks),
            observation(landmarks.filterNot { it.type == PoseLandmark.LEFT_SHOULDER }),
        )
        val degenerate = evaluator.evaluate(
            observation(landmarks),
            observation(landmarks.map { it.copy(x = 0.5, y = 0.5) }),
        )

        assertEquals(FramingEvidenceStatus.INVALID_REFERENCE, invalidReference.status)
        assertEquals(0.0, invalidReference.framingScore, 0.0)
        assertEquals(FramingEvidenceStatus.INVALID_REFERENCE, invalidBodyReference.status)
        assertEquals(0.0, invalidBodyReference.framingScore, 0.0)
        assertEquals(FramingEvidenceStatus.INVALID_REFERENCE, undercoveredReference.status)
        assertEquals(0.0, undercoveredReference.framingScore, 0.0)
        assertEquals(FramingEvidenceStatus.INSUFFICIENT_BODY_EVIDENCE, missingTorso.status)
        assertEquals(16, missingTorso.sharedLandmarkCount)
        assertEquals(0.0, missingTorso.framingScore, 0.0)
        assertEquals(FramingEvidenceStatus.DEGENERATE_BODY_EXTENT, degenerate.status)
        assertEquals(17, degenerate.sharedLandmarkCount)
        assertEquals(0.0, degenerate.framingScore, 0.0)
    }

    @Test
    fun missingBothKneesAndAnklesCannotPassOnCountAloneOrShrinkReferenceExtent() {
        val landmarks = standardLandmarks()
        val missingLowerLegs = setOf(
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE,
        )

        val evidence = PoseFramingEvaluator().evaluate(
            observation(landmarks),
            observation(landmarks.filterNot { it.type in missingLowerLegs }),
        )

        assertEquals(13, evidence.sharedLandmarkCount)
        assertEquals(FramingEvidenceStatus.INSUFFICIENT_BODY_EVIDENCE, evidence.status)
        assertEquals(0.0, evidence.framingScore, 0.0)
    }

    @Test
    fun confidenceBoundaryIsInclusiveAndPolicyRejectsUnsafeValues() {
        val boundary = standardLandmarks().map { it.copy(visibility = 0.25, presence = 0.25) }
        val evidence = PoseFramingEvaluator().evaluate(observation(boundary), observation(boundary))

        assertEquals(FramingEvidenceStatus.EVALUATED, evidence.status)
        assertEquals(1.0, evidence.framingScore, 0.0)

        listOf(-0.1, 1.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    FramingPolicy(invalid, 13, 0.5)
                }
            }
        listOf(0, 3, 8, 18).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                FramingPolicy(0.25, invalid, 0.5)
            }
        }
        listOf(0.0, -1.0, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    FramingPolicy(0.25, 13, invalid)
                }
            }
    }

    private fun observation(
        landmarks: List<Landmark>,
        detectedPersonCount: Int = 1,
    ) = PoseObservation(
        landmarks = landmarks,
        monotonicTimestampNanos = 0L,
        detectedPersonCount = detectedPersonCount,
        imageSize = PoseImageSize(1000, 1000),
    )

    private fun standardLandmarks(): List<Landmark> = MOVENET_TYPES.mapIndexed { index, type ->
        val column = index % 5
        val row = index / 5
        Landmark(
            type = type,
            x = 0.2 + column * 0.12,
            y = 0.15 + row * 0.2,
            z = 0.0,
            visibility = 0.9,
            presence = 0.9,
        )
    }

    private companion object {
        val MOVENET_TYPES = listOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_EYE,
            PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_EAR,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE,
        )
    }
}
