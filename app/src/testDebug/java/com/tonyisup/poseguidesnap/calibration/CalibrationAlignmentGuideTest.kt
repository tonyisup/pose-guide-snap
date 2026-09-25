package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.camera.NormalizedPoint
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationAlignmentGuideTest {
    private val guide = CalibrationAlignmentGuide()
    private val reference = BundledMeditationReference.observation

    @Test
    fun identicalFramingIsAlignedAndSmallerCenteredBodyNeedsCloser() {
        assertEquals(AlignmentCue.ALIGNED, guide.evaluate(reference).cue)
        val smaller = scaled(0.6)
        assertEquals(AlignmentCue.CLOSER, guide.evaluate(smaller).cue)
    }

    @Test
    fun largerCompleteBodyNeedsFartherEvenWhenNotExactlyCentered() {
        // A complete body nearly filling the normalized square has a larger diagonal than target.
        val left = guide.targetBounds.left
        val top = guide.targetBounds.top
        val larger = reference.copyForTest(landmarks = reference.landmarks.map {
            it.copy(
                x = 0.01 + 0.98 * (it.x - left) / (guide.targetBounds.right - left),
                y = 0.01 + 0.98 * (it.y - top) / (guide.targetBounds.bottom - top),
            )
        })
        // First fix translation; scale must not mask it.
        assertEquals(AlignmentCue.CENTER, guide.evaluate(larger).cue)
        // Keep the large diagonal but move the horizontal center within the acquisition radius.
        val centeredLarger = larger.copyForTest(landmarks = larger.landmarks.map { it.copy(x = it.x * 0.9) })
        assertEquals(AlignmentCue.FARTHER, guide.evaluate(centeredLarger).cue)
    }

    @Test
    fun translationUsesAnArrowTowardTargetAndDoesNotInventBodyLeftOrRight() {
        val shifted = scaled(0.6).let { pose ->
            pose.copyForTest(landmarks = pose.landmarks.map { it.copy(x = it.x + 0.18) })
        }
        val feedback = guide.evaluate(shifted)
        assertEquals(AlignmentCue.CENTER, feedback.cue)
        assertTrue(feedback.observedBounds!!.center.x > guide.targetBounds.center.x)
        assertEquals(guide.targetBounds.center.y, feedback.observedBounds.center.y, 1e-12)
    }

    @Test
    fun missingLowConfidenceOrDegenerateBodyCannotGenerateSizeAdvice() {
        val partial = reference.copyForTest(landmarks = reference.landmarks.filter { it.type != PoseLandmark.LEFT_ANKLE })
        val lowConfidence = reference.copyForTest(landmarks = reference.landmarks.map {
            if (it.type == PoseLandmark.LEFT_ANKLE) it.copy(presence = 0.24) else it
        })
        val degenerate = reference.copyForTest(landmarks = reference.landmarks.map { it.copy(x = 0.5, y = 0.5) })
        for (pose in listOf(partial, lowConfidence, degenerate)) {
            val feedback = guide.evaluate(pose)
            assertEquals(AlignmentCue.INCOMPLETE, feedback.cue)
            assertNull(feedback.observedBounds)
        }
    }

    @Test
    fun absentOrMultiplePeopleClearAllParticipantGeometry() {
        val absent = PoseObservation(emptyList(), 0, 0, reference.imageSize)
        val multiple = reference.copyForTest(detectedPersonCount = 2)
        assertEquals(AlignmentCue.NO_PERSON, guide.evaluate(absent).cue)
        assertEquals(AlignmentCue.MULTIPLE_PEOPLE, guide.evaluate(multiple).cue)
        assertNull(guide.evaluate(absent).observedBounds)
        assertNull(guide.evaluate(multiple).observedBounds)
    }

    @Test
    fun wrongCropAspectFailsClosedAndSmallCameraRoundingIsAllowed() {
        val portrait = reference.copyForTest(imageSize = PoseImageSize(574, 1024))
        assertEquals(AlignmentCue.GEOMETRY_MISMATCH, guide.evaluate(portrait).cue)
        assertNull(guide.evaluate(portrait).observedBounds)
        assertEquals(AlignmentCue.ALIGNED, guide.evaluate(reference.copyForTest(imageSize = PoseImageSize(640, 359))).cue)
    }

    @Test
    fun referenceAndLiveUseSameVisibleCropWithoutStretchingInBothOrientations() {
        for (available in listOf(PoseImageSize(1080, 1800), PoseImageSize(2100, 700))) {
            val viewport = guide.fittedPreviewSize(available.width, available.height)
            assertTrue(viewport.width <= available.width && viewport.height <= available.height)
            assertTrue(guide.compatibleAspect(viewport, reference.imageSize))
            val center = guide.previewPoint(NormalizedPoint(0.5, 0.5), reference.imageSize, viewport)
            assertEquals(viewport.width / 2.0, center.x, 1e-10)
            assertEquals(viewport.height / 2.0, center.y, 1e-10)
            val target = guide.previewPoint(guide.targetBounds.center, reference.imageSize, viewport)
            val live = guide.previewPoint(guide.evaluate(reference).observedBounds!!.center, reference.imageSize, viewport)
            assertEquals(target, live)
            // Rear preview must preserve horizontal order; rotation is already applied by analysis.
            val left = guide.previewPoint(NormalizedPoint(0.1, 0.5), reference.imageSize, viewport)
            assertTrue(left.x < center.x)
        }
    }

    @Test
    fun transientFeedbackDoesNotPrintCoordinates() {
        val feedback = guide.evaluate(reference)
        assertFalse(feedback.toString().contains(reference.landmarks.first().x.toString()))
        assertEquals("AlignmentBounds(redacted)", feedback.observedBounds.toString())
    }

    private fun PoseObservation.copyForTest(
        landmarks: List<Landmark> = this.landmarks,
        detectedPersonCount: Int = this.detectedPersonCount,
        imageSize: PoseImageSize = this.imageSize,
    ) = PoseObservation(landmarks, monotonicTimestampNanos, detectedPersonCount, imageSize)

    private fun scaled(scale: Double): PoseObservation = reference.copyForTest(landmarks = reference.landmarks.map {
        it.copy(
            x = guide.targetBounds.center.x + (it.x - guide.targetBounds.center.x) * scale,
            y = guide.targetBounds.center.y + (it.y - guide.targetBounds.center.y) * scale,
        )
    })
}
