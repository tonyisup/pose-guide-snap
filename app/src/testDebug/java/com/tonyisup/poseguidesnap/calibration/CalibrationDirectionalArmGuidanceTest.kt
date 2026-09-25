package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.domain.match.PoseCanonicalizer
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.*
import org.junit.Test

class CalibrationDirectionalArmGuidanceTest {
    private val reference = BundledMeditationReference.observation
    private val guide = CalibrationAlignmentGuide()

    @Test
    fun cueNamesTheMisplacedJointAndDirectionInsteadOfTheWholeArm() {
        val feedback = shifted(mapOf(PoseLandmark.LEFT_ELBOW to (0.0 to -0.3)))
        assertEquals("LEFT_ELBOW_DOWN", CalibrationSpokenGuidance().cueFor(feedback)?.name)
    }

    @Test
    fun fixingTheRequestedElbowEarnsGoodEvenWhileTheWristStillNeedsWork() {
        val speech = CalibrationSpokenGuidance()
        val bothWrong = shifted(mapOf(
            PoseLandmark.LEFT_ELBOW to (0.0 to -0.3),
            PoseLandmark.LEFT_WRIST to (0.0 to -0.2),
        ))
        for (now in 0L..500L step 500) assertNull(speech.next(bothWrong, now, false))
        assertNotNull(speech.next(bothWrong, 1_000, false))
        speech.speechCompleted(3_000)
        val elbowFixed = shifted(mapOf(PoseLandmark.LEFT_WRIST to (0.0 to -0.2)))
        for (now in 3_000L..3_500L step 500) assertNull(speech.next(elbowFixed, now, false))
        assertEquals(SpokenAlignmentCue.GOOD, speech.next(elbowFixed, 4_000, false))
        speech.speechCompleted(4_500)
        for (now in 4_500L..12_000L step 500) assertNull(speech.next(elbowFixed, now, false))
        assertEquals("LEFT_HAND_ON_KNEE", speech.next(elbowFixed, 12_500, false)?.name)
    }

    @Test
    fun allEightElbowDirectionsAreParticipantRelativeAndFollowingThemReducesError() {
        for (joint in listOf(ArmJoint.LEFT_ELBOW, ArmJoint.RIGHT_ELBOW)) {
            for ((delta, direction) in listOf(
                    (0.14 to 0.0) to ArmDirection.RIGHT,
                    (-0.14 to 0.0) to ArmDirection.LEFT,
                    (0.0 to 0.2) to ArmDirection.UP,
                    (0.0 to -0.2) to ArmDirection.DOWN)) {
                val before = shifted(mapOf(joint.landmark to delta)).armPose!!
                val cue = before.correction!!
                assertEquals(joint, cue.armJoint)
                assertEquals(direction, cue.armDirection)
                val move = when (direction) {
                    ArmDirection.LEFT -> 0.07 to 0.0
                    ArmDirection.RIGHT -> -0.07 to 0.0
                    ArmDirection.UP -> 0.0 to -0.1
                    ArmDirection.DOWN -> 0.0 to 0.1
                }
                val after = shifted(mapOf(joint.landmark to
                    (delta.first + move.first to delta.second + move.second))).armPose!!
                assertTrue("${cue.name} must move toward the target", after.offset(joint).error < before.offset(joint).error)
            }
        }
    }

    @Test
    fun mirrorChangesAnatomicalSideForTheSupportedHandInstruction() {
        val live = observation(mapOf(PoseLandmark.LEFT_WRIST to (0.14 to 0.0)))
        assertEquals(SpokenAlignmentCue.LEFT_HAND_ON_KNEE, guide.evaluate(live).armPose!!.correction)
        val mirrored = PoseObservation(live.landmarks.map {
            it.copy(type = PoseCanonicalizer.mirroredIdentity(it.type), x = 1 - it.x)
        }, 0, 1, reference.imageSize)
        assertEquals(SpokenAlignmentCue.RIGHT_HAND_ON_KNEE, guide.evaluate(mirrored).armPose!!.correction)
    }

    @Test
    fun overshootReversesAdviceWithoutSwitchingJointOrRepeatingObsoleteDirection() {
        val speech = CalibrationSpokenGuidance()
        val tooHigh = shifted(mapOf(PoseLandmark.LEFT_ELBOW to (0.0 to -0.2)))
        for (now in 0L..500L step 500) speech.next(tooHigh, now, false)
        assertEquals(SpokenAlignmentCue.LEFT_ELBOW_DOWN, speech.next(tooHigh, 1_000, false))
        speech.speechCompleted(3_000)
        val overshot = shifted(mapOf(
            PoseLandmark.LEFT_ELBOW to (0.0 to 0.2),
            PoseLandmark.RIGHT_ELBOW to (0.0 to -0.3),
        ))
        for (now in 3_000L..10_500L step 500) assertNull(speech.next(overshot, now, false))
        assertEquals(SpokenAlignmentCue.LEFT_ELBOW_UP, speech.next(overshot, 11_000, false))
    }

    @Test
    fun correctingOnlyOneAxisCannotEarnGoodWhileTheSameJointIsStillOutOfRange() {
        val speech = CalibrationSpokenGuidance()
        val wrong = shifted(mapOf(PoseLandmark.LEFT_ELBOW to (0.14 to -0.2)))
        for (now in 0L..500L step 500) speech.next(wrong, now, false)
        assertNotNull(speech.next(wrong, 1_000, false))
        speech.speechCompleted(3_000)
        val stillTooHigh = shifted(mapOf(PoseLandmark.LEFT_ELBOW to (0.0 to -0.2)))
        for (now in 3_000L..10_500L step 500) assertNull(speech.next(stillTooHigh, now, false))
        assertEquals(SpokenAlignmentCue.LEFT_ELBOW_DOWN, speech.next(stillTooHigh, 11_000, false))
    }

    @Test
    fun privateSignedOffsetsRemainRedactedAndJointToleranceIsUnchanged() {
        assertEquals("ArmJointOffset(redacted)", ArmJointOffset(-0.123456, 0.654321).toString())
        assertEquals(0.25, ArmPoseFeedback.MAX_ARM_ERROR, 0.0)
        assertTrue(ArmJointOffset(0.15, 0.2).error <= ArmPoseFeedback.MAX_ARM_ERROR)
        assertTrue(ArmJointOffset(0.2, 0.2).error > ArmPoseFeedback.MAX_ARM_ERROR)
    }

    private fun shifted(changes: Map<PoseLandmark, Pair<Double, Double>>): AlignmentFeedback =
        guide.evaluate(observation(changes))

    private fun observation(changes: Map<PoseLandmark, Pair<Double, Double>>) =
        PoseObservation(reference.landmarks.map {
            val delta = changes[it.type]
            if (delta == null) it else it.copy(x = it.x + delta.first, y = it.y + delta.second)
        }, 0, 1, reference.imageSize)
}
