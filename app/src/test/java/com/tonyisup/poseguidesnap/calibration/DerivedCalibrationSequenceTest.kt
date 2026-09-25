package com.tonyisup.poseguidesnap.calibration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedCalibrationSequenceTest {
    @Test
    fun exactDocumentMatchesClosedAnalyzerSchemaWithoutPrivatePayloadFields() {
        val document = DerivedCalibrationSequence.create(
            datasetId = "pixel6-public-meditation-a",
            sequenceId = "positive-centered-a",
            fixtureClass = CalibrationFixtureClass.POSITIVE,
            caseClass = "centered-match",
            frames = listOf(frame(0), frame(100, mirrorUsed = true)),
        )

        val root = Json.parseToJsonElement(document.toJson()).jsonObject
        assertEquals(
            setOf(
                "schemaVersion",
                "datasetId",
                "provenance",
                "authorization",
                "populationLimits",
                "containsPrivateImages",
                "sequences",
            ),
            root.keys,
        )
        assertEquals(3, root.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(
            DerivedCalibrationSequence.AUTHORIZATION,
            root.getValue("authorization").jsonPrimitive.content,
        )
        assertFalse(root.getValue("containsPrivateImages").jsonPrimitive.boolean)
        assertEquals(4, root.getValue("populationLimits").jsonArray.size)

        val sequence = root.getValue("sequences").jsonArray.single().jsonObject
        assertEquals(
            setOf("sequenceId", "fixtureClass", "caseClass", "frames"),
            sequence.keys,
        )
        assertEquals("positive", sequence.getValue("fixtureClass").jsonPrimitive.content)
        val frames = sequence.getValue("frames").jsonArray
        assertEquals(2, frames.size)
        assertEquals(100, frames[1].jsonObject.getValue("elapsedMs").jsonPrimitive.int)
        assertTrue(frames[1].jsonObject.getValue("mirrorUsed").jsonPrimitive.boolean)
        assertEquals(null, frames[0].jsonObject.getValue("inferenceLatencyMs").jsonPrimitive.contentOrNull)
        assertEquals(
            setOf(
                "elapsedMs",
                "detectedPersonCount",
                "evaluationStatus",
                "confidenceQualifiedLandmarkCount",
                "qualifiedTorsoAnchorCount",
                "maximumValidPersonScore",
                "maximumValidKeypointScore",
                "landmarkCoverage",
                "framingScore",
                "angularSimilarity",
                "positionalSimilarity",
                "overallMatch",
                "mirrorUsed",
                "inferenceLatencyMs",
                "cueEmitted",
                "captureCommands",
            ),
            frames[0].jsonObject.keys,
        )
        val serializedKeys = Regex("\\\"([^\\\"]+)\\\":")
            .findAll(document.toJson())
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(
            serializedKeys.intersect(
                setOf("image", "images", "landmarks", "path", "uri", "timestamp", "identity"),
            ).isEmpty(),
        )
        val analyzerContract = requireNotNull(
            javaClass.getResourceAsStream("/calibration/derived-collector-contract-v3.json"),
        ).bufferedReader().use { reader -> reader.readText().trim() }
        assertEquals(analyzerContract, document.toJson())
    }

    @Test
    fun frameValidatesEveryScalarBoundary() {
        val boundary = frame(
            elapsedMs = 0,
            landmarkCoverage = 0.0,
            framingScore = 1.0,
            inferenceLatencyMs = 0.0,
            captureCommands = 3,
        )
        assertEquals(0.0, boundary.landmarkCoverage, 0.0)
        assertEquals(1.0, boundary.framingScore, 0.0)
        assertEquals(0.8, boundary.maximumValidPersonScore ?: error("missing person score"), 0.0)
        assertEquals(0.9, boundary.maximumValidKeypointScore ?: error("missing keypoint score"), 0.0)

        listOf(-0.1, 1.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    frame(0, overallMatch = invalid)
                }
            }
        assertThrows(IllegalArgumentException::class.java) { frame(-1) }
        assertThrows(IllegalArgumentException::class.java) { frame(0, detectedPersonCount = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            frame(0, confidenceQualifiedLandmarkCount = 18)
        }
        assertThrows(IllegalArgumentException::class.java) {
            frame(0, confidenceQualifiedLandmarkCount = 3, qualifiedTorsoAnchorCount = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            frame(
                elapsedMs = 0,
                detectedPersonCount = 0,
                evaluationStatus = DerivedCalibrationEvaluationStatus.EVALUATED,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            frame(
                elapsedMs = 0,
                evaluationStatus = DerivedCalibrationEvaluationStatus.EVALUATED,
                qualifiedTorsoAnchorCount = 3,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            frame(
                elapsedMs = 0,
                detectedPersonCount = 0,
                evaluationStatus = DerivedCalibrationEvaluationStatus.NO_PERSON,
                confidenceQualifiedLandmarkCount = 1,
                qualifiedTorsoAnchorCount = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) { frame(0, inferenceLatencyMs = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { frame(0, captureCommands = 4) }
        listOf(-0.1, 1.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    frame(0, maximumValidPersonScore = invalid)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    frame(0, maximumValidKeypointScore = invalid)
                }
            }
        frame(0, maximumValidPersonScore = null, maximumValidKeypointScore = null)
    }

    @Test
    fun sequenceRejectsUnsafeIdentifiersTimeOrderAndUnboundedFrames() {
        val validFrames = listOf(frame(0), frame(100))
        listOf("", "UPPERCASE", "slash/value", "a".repeat(65)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                DerivedCalibrationSequence.create(
                    datasetId = invalid,
                    sequenceId = "sequence-a",
                    fixtureClass = CalibrationFixtureClass.NEGATIVE,
                    caseClass = "wrong-pose",
                    frames = validFrames,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            sequence(listOf(frame(0), frame(0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            sequence(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            sequence((0..DerivedCalibrationSequence.MAX_FRAMES).map(::frame))
        }
    }

    @Test
    fun constructionSnapshotsFramesAndSerializationIsDeterministic() {
        val mutable = mutableListOf(frame(0), frame(100))
        val document = sequence(mutable)
        val first = document.toJson()

        mutable.clear()

        assertEquals(2, document.frames.size)
        assertEquals(first, document.toJson())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (document.frames as MutableList<DerivedCalibrationFrame>).clear()
        }
        assertEquals(0.8, Json.parseToJsonElement(first).jsonObject
            .getValue("sequences").jsonArray.single().jsonObject
            .getValue("frames").jsonArray.first().jsonObject
            .getValue("overallMatch").jsonPrimitive.double, 0.0)
    }

    private fun sequence(frames: List<DerivedCalibrationFrame>) =
        DerivedCalibrationSequence.create(
            datasetId = "pixel6-public-meditation-a",
            sequenceId = "negative-wrong-pose-a",
            fixtureClass = CalibrationFixtureClass.NEGATIVE,
            caseClass = "wrong-pose",
            frames = frames,
        )

    private fun frame(
        elapsedMs: Int,
        detectedPersonCount: Int = 1,
        evaluationStatus: DerivedCalibrationEvaluationStatus =
            DerivedCalibrationEvaluationStatus.EVALUATED,
        confidenceQualifiedLandmarkCount: Int = 17,
        qualifiedTorsoAnchorCount: Int = 4,
        maximumValidPersonScore: Double? = 0.8,
        maximumValidKeypointScore: Double? = 0.9,
        landmarkCoverage: Double = 0.9,
        framingScore: Double = 0.85,
        angularSimilarity: Double = 0.75,
        positionalSimilarity: Double = 0.7,
        overallMatch: Double = 0.8,
        mirrorUsed: Boolean = false,
        inferenceLatencyMs: Double? = null,
        captureCommands: Int? = 0,
    ) = DerivedCalibrationFrame(
        elapsedMs = elapsedMs,
        detectedPersonCount = detectedPersonCount,
        evaluationStatus = evaluationStatus,
        confidenceQualifiedLandmarkCount = confidenceQualifiedLandmarkCount,
        qualifiedTorsoAnchorCount = qualifiedTorsoAnchorCount,
        maximumValidPersonScore = maximumValidPersonScore,
        maximumValidKeypointScore = maximumValidKeypointScore,
        landmarkCoverage = landmarkCoverage,
        framingScore = framingScore,
        angularSimilarity = angularSimilarity,
        positionalSimilarity = positionalSimilarity,
        overallMatch = overallMatch,
        mirrorUsed = mirrorUsed,
        inferenceLatencyMs = inferenceLatencyMs,
        cueEmitted = null,
        captureCommands = captureCommands,
    )
}
