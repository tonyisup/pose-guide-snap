package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationSpokenGuidanceTest {
    private val guidance = CalibrationSpokenGuidance()
    private val guide = CalibrationAlignmentGuide()
    private val reference = BundledMeditationReference.observation

    @Test
    fun rearCameraDirectionsUseParticipantsLeftAndRightWhileFacingLens() {
        assertEquals(SpokenAlignmentCue.RIGHT, guidance.cueFor(shifted(0.2, 0.0)))
        assertEquals(SpokenAlignmentCue.LEFT, guidance.cueFor(shifted(-0.2, 0.0)))
        assertEquals(SpokenAlignmentCue.TILT_DOWN, guidance.cueFor(shifted(0.0, 0.15)))
        assertEquals(SpokenAlignmentCue.TILT_UP, guidance.cueFor(shifted(0.0, -0.15)))
    }

    @Test
    fun incompleteEvidenceNeverGivesSizeOrDirectionAdvice() {
        for (cue in listOf(AlignmentCue.NO_PERSON, AlignmentCue.INCOMPLETE)) {
            assertEquals(SpokenAlignmentCue.FULL_BODY, guidance.cueFor(AlignmentFeedback(cue)))
        }
        assertNull(guidance.cueFor(AlignmentFeedback(AlignmentCue.WAITING)))
        assertNull(guidance.cueFor(AlignmentFeedback(AlignmentCue.CENTER)))
    }

    @Test
    fun adjustmentConfirmedOnceThenNextInstructionWaitsAfterGood() {
        requestRight()
        val corrected = shifted(0.0, 0.15)
        assertNull(guidance.next(corrected, 3_000, false))
        assertNull(guidance.next(corrected, 3_500, false))
        assertEquals(SpokenAlignmentCue.GOOD, guidance.next(corrected, 4_000, false))
        assertEquals("Good.", SpokenAlignmentCue.GOOD.text)
        guidance.speechCompleted(4_500)
        for (time in 4_500L..12_000L step 500) assertNull(guidance.next(corrected, time, false))
        assertEquals(SpokenAlignmentCue.TILT_DOWN, guidance.next(corrected, 12_500, false))
    }

    @Test
    fun priorityChangeOvershootAndLostEvidenceDoNotEarnGood() {
        requestRight()
        for (feedback in listOf(shifted(0.18, 0.25), shifted(-0.2, 0.0),
                AlignmentFeedback(AlignmentCue.INCOMPLETE),
                AlignmentFeedback(AlignmentCue.MULTIPLE_PEOPLE),
                AlignmentFeedback(AlignmentCue.GEOMETRY_MISMATCH))) {
            for (time in 3_000L..4_000L step 500) assertNull(guidance.next(feedback, time, false))
        }
    }

    @Test
    fun confirmationNeedsFreshContinuousEvidenceAfterInstructionFinishes() {
        requestRight()
        val corrected = shifted(0.0, 0.15)
        assertNull(guidance.next(corrected, 3_000, false))
        assertNull(guidance.next(corrected, 4_000, false)) // Gap resets confirmation.
        assertNull(guidance.next(AlignmentFeedback(AlignmentCue.INCOMPLETE), 4_500, false))
        assertNull(guidance.next(corrected, 5_000, false))
        assertNull(guidance.next(corrected, 5_500, true)) // Busy speech resets it too.
        assertNull(guidance.next(corrected, 6_000, false))
        assertNull(guidance.next(corrected, 6_500, false))
        assertEquals(SpokenAlignmentCue.GOOD, guidance.next(corrected, 7_000, false))
    }

    @Test
    fun lateConfirmationKeepsQuietTimeBeforeHoldAndDoesNotRepeat() {
        guidance.start(30_000)
        guidance.speechCompleted(0)
        val right = shifted(0.2, 0.0)
        for (time in 0L..7_500L step 500) assertNull(guidance.next(right, time, false))
        assertEquals(SpokenAlignmentCue.RIGHT, guidance.next(right, 8_000, false))
        guidance.speechCompleted(10_000)
        val corrected = shifted(0.0, 0.15)
        assertNull(guidance.next(corrected, 27_000, false))
        assertNull(guidance.next(corrected, 27_500, false))
        assertEquals(SpokenAlignmentCue.GOOD, guidance.next(corrected, 28_000, false))
        guidance.speechCompleted(29_000)
        assertEquals(7_000L, guidance.quietRemainingMs(30_000))
        for (time in 29_000L..40_000L step 500) assertNull(guidance.next(corrected, time, false))
        assertEquals(0L, guidance.quietRemainingMs(37_000))
    }

    @Test
    fun sizeAdjustmentNeedsCorrectScaleEvenWhenCenterAdviceTakesPriority() {
        val small = shifted(0.0, 0.0)
        guidance.next(small, 0, false)
        guidance.next(small, 500, false)
        assertEquals(SpokenAlignmentCue.CLOSER, guidance.next(small, 1_000, false))
        guidance.speechCompleted(2_000)
        for (time in 2_000L..3_000L step 500) assertNull(guidance.next(shifted(0.2, 0.0), time, false))
        val correctScale = shifted(0.2, 0.0, 1.0)
        assertNull(guidance.next(correctScale, 3_500, false))
        assertNull(guidance.next(correctScale, 4_000, false))
        assertEquals(SpokenAlignmentCue.GOOD, guidance.next(correctScale, 4_500, false))
    }

    @Test
    fun alignedWithoutAnAdjustmentDoesNotEarnGood() {
        val aligned = guide.evaluate(reference)
        guidance.next(aligned, 0, false)
        guidance.next(aligned, 500, false)
        assertEquals(SpokenAlignmentCue.ALIGNED, guidance.next(aligned, 1_000, false))
        guidance.speechCompleted(2_000)
        for (time in 2_000L..9_500L step 500) assertNull(guidance.next(aligned, time, false))
    }

    private fun requestRight() {
        val right = shifted(0.2, 0.0)
        assertNull(guidance.next(right, 0, false))
        assertNull(guidance.next(right, 500, false))
        assertEquals(SpokenAlignmentCue.RIGHT, guidance.next(right, 1_000, false))
        guidance.speechCompleted(3_000)
    }

    @Test
    fun onlyFiveConsecutiveFreshFullBodyFramesPermitCollection() {
        val readiness = GuidedBodyReadiness()
        repeat(4) { readiness.record(true, it * 100L) }
        assertFalse(readiness.isReady(400))
        readiness.record(false, 400)
        repeat(5) { readiness.record(true, 500 + it * 100L) }
        assertTrue(readiness.isReady(1000))
        assertFalse(readiness.isReady(1651))
        readiness.record(true, 1700)
        assertFalse(readiness.isReady(1700))
    }

    @Test
    fun personPresenceWithMissingBodyEvidenceNeverBecomesReady() {
        val readiness = GuidedBodyReadiness()
        repeat(100) { readiness.record(false, it * 100L) }
        assertFalse(readiness.isReady(9900))
    }

    private fun shifted(dx: Double, dy: Double, scale: Double = 0.5): AlignmentFeedback {
        val center = guide.targetBounds.center
        val landmarks = reference.landmarks.map {
            it.copy(x = center.x + (it.x - center.x) * scale + dx,
                y = center.y + (it.y - center.y) * scale + dy)
        }
        return guide.evaluate(PoseObservation(landmarks, 0, 1, reference.imageSize))
    }
}
