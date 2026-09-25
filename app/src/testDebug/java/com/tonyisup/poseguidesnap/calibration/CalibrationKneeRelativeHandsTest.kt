package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.match.PoseCanonicalizer
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.*
import org.junit.Test

class CalibrationKneeRelativeHandsTest {
    private val reference = BundledMeditationReference.observation
    private val armGuide = CalibrationArmPoseGuide()
    private val alignment = CalibrationAlignmentGuide()
    private val knees = mapOf(ArmJoint.LEFT_WRIST to PoseLandmark.LEFT_KNEE,
        ArmJoint.RIGHT_WRIST to PoseLandmark.RIGHT_KNEE)

    @Test
    fun handsAtOwnKneesAreAcceptedWhenKneePositionsDifferFromReference() {
        val observation = movedKnees(handsFollow = true)
        for (mirrored in listOf(false, true)) {
            val live = if (!mirrored) observation else PoseObservation(observation.landmarks.map {
                it.copy(type = PoseCanonicalizer.mirroredIdentity(it.type), x = 1 - it.x)
            }, 0, 1, reference.imageSize)
            val feedback = armGuide.evaluate(live)!!
            for (joint in knees.keys) {
                assertEquals(0.0, feedback.offset(joint).error, 1e-9)
                assertTrue(feedback.matches(joint))
            }
            assertNull(feedback.correction)
        }
    }

    @Test
    fun oldReferenceWristPositionDoesNotPassWhenOwnKneeIsElsewhere() {
        val feedback = armGuide.evaluate(movedKnees(handsFollow = false))!!
        for (joint in knees.keys) assertFalse(feedback.matches(joint))
    }

    @Test
    fun aLargeWristErrorCannotHideTheCorrectMirroredElbowTargets() {
        val mirrored = PoseObservation(reference.landmarks.map {
            val point = it.copy(type = PoseCanonicalizer.mirroredIdentity(it.type), x = 1 - it.x)
            if (point.type == PoseLandmark.RIGHT_WRIST) point.copy(y = 0.05) else point
        }, 0, 1, reference.imageSize)
        val feedback = armGuide.evaluate(mirrored)!!
        assertEquals(0.0, feedback.leftElbowError, 1e-9)
        assertEquals(0.0, feedback.rightElbowError, 1e-9)
        assertEquals(SpokenAlignmentCue.RIGHT_HAND_ON_KNEE, feedback.correction)
    }

    @Test
    fun oppositeKneeAndMissingOrUnqualifiedOwnKneeCannotConfirmHandPlacement() {
        for ((joint, kneeType) in knees) {
            val otherKnee = reference.landmarks.first {
                it.type == knees.getValue(knees.keys.first { other -> other != joint })
            }
            val crossed = PoseObservation(reference.landmarks.map {
                if (it.type == joint.landmark) it.copy(x = otherKnee.x, y = otherKnee.y) else it
            }, 0, 1, reference.imageSize)
            assertFalse(armGuide.evaluate(crossed)!!.matches(joint))
            for (missing in listOf(false, true)) {
                val landmarks = if (missing) reference.landmarks.filter { it.type != kneeType }
                    else reference.landmarks.map {
                        if (it.type == kneeType) it.copy(visibility = 0.1) else it
                    }
                assertNull(armGuide.evaluate(PoseObservation(landmarks, 0, 1, reference.imageSize)))
            }
        }
    }

    @Test
    fun unresolvedHandGetsOneExplanationInsteadOfRepeatingTheCommand() {
        for (joint in knees.keys) {
            val speech = CalibrationSpokenGuidance()
            val wrong = wrongHand(joint)
            speech.start(60_000, 30_000)
            speech.speechCompleted(0)
            for (now in 0L..7_500L step 500) assertNull(speech.next(wrong, now, false))
            val first = speech.next(wrong, 8_000, false)!!
            assertEquals(joint, first.armJoint)
            speech.speechCompleted(12_000, first)
            for (now in 12_000L..19_500L step 500) assertNull(speech.next(wrong, now, false))
            val notice = speech.next(wrong, 20_000, false)!!
            val side = if (joint.isLeft) "left" else "right"
            assertEquals("If your $side hand is already resting on your $side knee, keep it there. I can't confirm its position yet.", notice.text)
            assertEquals(joint, notice.armJoint)
            speech.speechCompleted(25_000, notice)
            for (now in 25_000L..60_000L step 500) assertNull(speech.next(wrong, now, false))
            val terminal = speech.preparationStatus(60_000)
            assertEquals(CalibrationPreparationDecision.TIMED_OUT, terminal.decision)
            assertTrue(CalibrationPreparationBlocker.ARM_POSITION in terminal.blockers)
            assertTrue(CalibrationPreparationBlocker.UNCONFIRMED_ADJUSTMENT in terminal.blockers)

            // A new preparation session must not inherit the previous attempt's notice suppression.
            speech.start(120_000, 90_000)
            speech.speechCompleted(60_000)
            for (now in 60_000L..67_500L step 500) assertNull(speech.next(wrong, now, false))
            assertEquals(first, speech.next(wrong, 68_000, false))
        }
    }

    @Test
    fun explanationKeepsTheRequestedHandPendingUntilFreshSettledCorrection() {
        val speech = CalibrationSpokenGuidance()
        val wrong = wrongHand(ArmJoint.RIGHT_WRIST)
        speech.start(60_000, 30_000)
        speech.speechCompleted(0)
        for (now in 0L..7_500L step 500) speech.next(wrong, now, false)
        val first = speech.next(wrong, 8_000, false)!!
        speech.speechCompleted(12_000, first)
        for (now in 12_000L..19_500L step 500) speech.next(wrong, now, false)
        val notice = speech.next(wrong, 20_000, false)!!
        assertNotEquals(first, notice)
        speech.speechCompleted(25_000, notice)
        val corrected = alignment.evaluate(movedKnees(handsFollow = true))
        assertNull(speech.next(corrected, 25_000, false))
        assertNull(speech.next(corrected, 25_500, false))
        assertEquals(SpokenAlignmentCue.GOOD, speech.next(corrected, 26_000, false))
        speech.speechCompleted(27_000, SpokenAlignmentCue.GOOD)
        for (now in 27_000L..34_500L step 500) assertNull(speech.next(corrected, now, false))
        assertFalse(CalibrationPreparationBlocker.ARM_POSITION in speech.preparationStatus(34_500).blockers)
    }

    private fun wrongHand(joint: ArmJoint) = alignment.evaluate(PoseObservation(reference.landmarks.map {
        if (it.type == joint.landmark) it.copy(y = 0.2) else it
    }, 0, 1, reference.imageSize))

    private fun movedKnees(handsFollow: Boolean): PoseObservation {
        val shifted = reference.landmarks.map {
            when (it.type) {
                PoseLandmark.LEFT_KNEE -> it.copy(x = it.x + 0.08, y = it.y + 0.05)
                PoseLandmark.RIGHT_KNEE -> it.copy(x = it.x - 0.08, y = it.y + 0.05)
                else -> it
            }
        }
        return PoseObservation(shifted.map { point ->
            val kneeType = knees.entries.firstOrNull { it.key.landmark == point.type }?.value
            if (!handsFollow || kneeType == null) point else {
                val knee = shifted.first { it.type == kneeType }
                point.copy(x = knee.x, y = knee.y)
            }
        }, 0, 1, reference.imageSize)
    }
}
