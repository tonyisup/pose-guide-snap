package com.tonyisup.poseguidesnap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalibrationCollectionRequestTest {
    @Test
    fun exactAuthorizedPositiveRequestIsAccepted() {
        val request = request()

        assertEquals(DerivedCalibrationSequence.AUTHORIZATION, request.authorization)
        assertEquals("pixel6-public-meditation-a", request.datasetId)
        assertEquals("positive-centered-a", request.sequenceId)
        assertEquals(CalibrationFixtureClass.POSITIVE, request.fixtureClass)
        assertEquals("centered-match", request.caseClass)
        assertEquals(3_000, request.warmupMs)
        assertEquals(5_000, request.durationMs)
        assertEquals("calibration-sequence-v3.json", CalibrationCollectionRequest.OUTPUT_FILENAME)
    }

    @Test
    fun missingOrDifferentAuthorizationFailsClosed() {
        listOf(null, "", "public-synthetic", "team-authorized-derived").forEach { authorization ->
            assertThrows(IllegalArgumentException::class.java) {
                request(authorization = authorization)
            }
        }
    }

    @Test
    fun fixtureAndIdentifierInputsAreClosed() {
        listOf(null, "", "POSITIVE", "unknown").forEach { fixture ->
            assertThrows(IllegalArgumentException::class.java) {
                request(fixtureClass = fixture)
            }
        }
        listOf(null, "", "Has Spaces", "path/value", "a".repeat(65)).forEach { identifier ->
            assertThrows(IllegalArgumentException::class.java) {
                request(sequenceId = identifier)
            }
        }
    }

    @Test
    fun timingInputsAreRequiredIntegersInsideBounds() {
        listOf(null, "", "2999", "60001", "3.0").forEach { warmup ->
            assertThrows(IllegalArgumentException::class.java) {
                request(warmupMs = warmup)
            }
        }
        listOf(null, "", "4999", "30001", "5s").forEach { duration ->
            assertThrows(IllegalArgumentException::class.java) {
                request(durationMs = duration)
            }
        }

        assertEquals(15_000, request(warmupMs = "15000").warmupMs)
        assertEquals(30_000, request(warmupMs = "30000").warmupMs)
        assertEquals(60_000, request(warmupMs = "60000").warmupMs)
        assertEquals(30_000, request(durationMs = "30000").durationMs)
    }

    @Test
    fun detectorGateGroundTruthUsesClosedSinglePersonAndEmptySceneLabels() {
        CalibrationCollectionRequest.requireDetectorGateGroundTruth(
            request(
                fixtureClass = "positive",
                caseClass = CalibrationCollectionRequest.DETECTOR_POSITIVE_CASE_CLASS,
            ),
        )
        CalibrationCollectionRequest.requireDetectorGateGroundTruth(
            request(
                fixtureClass = "negative",
                caseClass = CalibrationCollectionRequest.DETECTOR_NEGATIVE_CASE_CLASS,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCollectionRequest.requireDetectorGateGroundTruth(
                request(fixtureClass = "positive", caseClass = "centered-match"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibrationCollectionRequest.requireDetectorGateGroundTruth(
                request(fixtureClass = "negative", caseClass = "wrong-pose"),
            )
        }
    }

    private fun request(
        authorization: String? = DerivedCalibrationSequence.AUTHORIZATION,
        datasetId: String? = "pixel6-public-meditation-a",
        sequenceId: String? = "positive-centered-a",
        fixtureClass: String? = "positive",
        caseClass: String? = "centered-match",
        warmupMs: String? = "3000",
        durationMs: String? = "5000",
    ) = CalibrationCollectionRequest.fromRaw(
        authorization = authorization,
        datasetId = datasetId,
        sequenceId = sequenceId,
        fixtureClass = fixtureClass,
        caseClass = caseClass,
        warmupMs = warmupMs,
        durationMs = durationMs,
    )
}
