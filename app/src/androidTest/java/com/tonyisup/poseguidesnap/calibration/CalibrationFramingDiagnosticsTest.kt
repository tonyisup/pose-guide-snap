package com.tonyisup.poseguidesnap.calibration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonyisup.poseguidesnap.domain.match.FramingEvidence
import com.tonyisup.poseguidesnap.domain.match.FramingEvidenceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic scalar checks only: no activity, camera, model, or private device data. */
@RunWith(AndroidJUnit4::class)
class CalibrationFramingDiagnosticsTest {
    @Test
    fun equalCombinedScoresDistinguishCenterFromScaleFailure() {
        val offCenter = CalibrationFramingDiagnostics().apply { record(evaluated(0.34, 0.95)) }
        val wrongSize = CalibrationFramingDiagnostics().apply { record(evaluated(0.95, 0.34)) }

        assertEquals(
            "recordedFrames=1 evaluatedFrames=1 unavailableFrames=0 " +
                "minimumCenterSimilarity=0.34 maximumCenterSimilarity=0.34 " +
                "minimumScaleSimilarity=0.95 maximumScaleSimilarity=0.95",
            offCenter.summary(),
        )
        assertEquals(
            "recordedFrames=1 evaluatedFrames=1 unavailableFrames=0 " +
                "minimumCenterSimilarity=0.95 maximumCenterSimilarity=0.95 " +
                "minimumScaleSimilarity=0.34 maximumScaleSimilarity=0.34",
            wrongSize.summary(),
        )
        assertNotEquals(offCenter.summary(), wrongSize.summary())
    }

    @Test
    fun unavailableFramesDoNotMasqueradeAsZeroSimilarity() {
        val diagnostics = CalibrationFramingDiagnostics()
        assertEquals(
            "recordedFrames=0 evaluatedFrames=0 unavailableFrames=0 " +
                "minimumCenterSimilarity=unavailable maximumCenterSimilarity=unavailable " +
                "minimumScaleSimilarity=unavailable maximumScaleSimilarity=unavailable",
            diagnostics.summary(),
        )
        diagnostics.record(
            FramingEvidence(FramingEvidenceStatus.INVALID_PERSON_COUNT, 0, 0.0, 0.0, 0.0),
        )
        diagnostics.record(evaluated(0.8, 0.6))
        diagnostics.record(evaluated(0.7, 0.9))
        assertEquals(
            "recordedFrames=3 evaluatedFrames=2 unavailableFrames=1 " +
                "minimumCenterSimilarity=0.7 maximumCenterSimilarity=0.8 " +
                "minimumScaleSimilarity=0.6 maximumScaleSimilarity=0.9",
            diagnostics.summary(),
        )
    }

    @Test
    fun zeroIsAnEvaluatedScoreAndTheFrameCapRejectsFurtherWrites() {
        val diagnostics = CalibrationFramingDiagnostics()
        repeat(DerivedCalibrationSequence.MAX_FRAMES) { diagnostics.record(evaluated(0.0, 1.0)) }
        val before = diagnostics.summary()
        assertEquals(
            "recordedFrames=600 evaluatedFrames=600 unavailableFrames=0 " +
                "minimumCenterSimilarity=0.0 maximumCenterSimilarity=0.0 " +
                "minimumScaleSimilarity=1.0 maximumScaleSimilarity=1.0",
            before,
        )
        assertThrows(IllegalStateException::class.java) { diagnostics.record(evaluated(0.5, 0.5)) }
        assertEquals(before, diagnostics.summary())
    }

    private fun evaluated(center: Double, scale: Double) = FramingEvidence(
        status = FramingEvidenceStatus.EVALUATED,
        sharedLandmarkCount = 17,
        centerSimilarity = center,
        scaleSimilarity = scale,
        framingScore = minOf(center, scale),
    )
}
