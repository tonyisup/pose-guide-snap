package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.*
import org.junit.Test

class CalibrationMovementGuidanceTest {
    private val guide = CalibrationAlignmentGuide()
    private val reference = BundledMeditationReference.observation
    private val leftKnee = reference.landmarks.first { it.type == PoseLandmark.LEFT_KNEE }

    @Test
    fun movingInsideAcceptedJointRadiusCannotEarnGoodUntilSettled() {
        val speech = requestedHand()
        for (now in 3_000L..5_900L step 100) {
            val feedback = wrist(if (now % 200 == 0L) 0.02 else -0.02)
            assertTrue(feedback.armPose!!.matches(ArmJoint.LEFT_WRIST))
            assertNull("Must not confirm a moving joint", speech.next(feedback, now, false))
        }
        val still = wrist(0.02)
        for (now in 6_000L..6_900L step 100) assertNull(speech.next(still, now, false))
        assertEquals(SpokenAlignmentCue.GOOD, speech.next(still, 7_000, false))
    }

    @Test
    fun elapsedQuietTimerCannotInterruptOngoingAdjustment() {
        val speech = requestedHand()
        for (now in 3_000L..13_900L step 100) {
            assertNull("Eight seconds alone must not allow another direction",
                speech.next(wrist(if (now % 200 == 0L) 0.17 else 0.2), now, false))
        }
        val stillWrong = wrist(0.17)
        for (now in 14_000L..14_900L step 100) assertNull(speech.next(stillWrong, now, false))
        assertEquals(SpokenAlignmentCue.LEFT_HAND_UNCONFIRMED, speech.next(stillWrong, 15_000, false))
    }

    private fun requestedHand() = CalibrationSpokenGuidance().also {
        it.start(60_000)
        it.speechCompleted(-8_000)
        for (now in 0L..900L step 100) assertNull(it.next(wrist(0.2), now, false))
        assertEquals(SpokenAlignmentCue.LEFT_HAND_ON_KNEE, it.next(wrist(0.2), 1_000, false))
        it.speechCompleted(3_000, SpokenAlignmentCue.LEFT_HAND_ON_KNEE)
    }

    private fun wrist(dy: Double) = guide.evaluate(PoseObservation(reference.landmarks.map {
        if (it.type == PoseLandmark.LEFT_WRIST) it.copy(x = leftKnee.x, y = leftKnee.y + dy) else it
    }, 0, 1, reference.imageSize))
}
