package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.match.PoseCanonicalizer
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.*
import org.junit.Test

class CalibrationArmPoseGuideTest {
    private val reference = BundledMeditationReference.observation
    private val armGuide = CalibrationArmPoseGuide()
    private val guide = CalibrationAlignmentGuide()

    @Test
    fun referenceAndAllowedMirrorNeedNoArmCorrection() {
        assertNull(armGuide.evaluate(reference)!!.correction)
        val mirrored = observation(reference.landmarks.map {
            it.copy(type = PoseCanonicalizer.mirroredIdentity(it.type), x = 1 - it.x)
        })
        assertNull(armGuide.evaluate(mirrored)!!.correction)
    }

    @Test
    fun movingWholeBodyOrCameraDistanceDoesNotLookLikeAnArmMismatch() {
        val moved = observation(reference.landmarks.map { it.copy(x = it.x * 0.5 + 0.3, y = it.y * 0.5 + 0.2) })
        assertNull(armGuide.evaluate(moved)!!.correction)
    }

    @Test
    fun wrongWristAndWrongElbowEachProduceAnatomicalSideInstructions() {
        for ((point, cue) in listOf(
                PoseLandmark.LEFT_WRIST to SpokenAlignmentCue.LEFT_HAND_ON_KNEE,
                PoseLandmark.LEFT_ELBOW to SpokenAlignmentCue.LEFT_ELBOW_DOWN,
                PoseLandmark.RIGHT_WRIST to SpokenAlignmentCue.RIGHT_HAND_ON_KNEE,
                PoseLandmark.RIGHT_ELBOW to SpokenAlignmentCue.RIGHT_ELBOW_DOWN)) {
            val wrong = changed(point) { it.copy(y = it.y - 0.3) }
            assertEquals(cue, armGuide.evaluate(wrong)!!.correction)
        }
    }

    @Test
    fun mirrorDoesNotSwapTheParticipantsSpokenSide() {
        val wrong = changed(PoseLandmark.LEFT_WRIST) { it.copy(y = 0.2) }
        val mirrored = observation(wrong.landmarks.map {
            it.copy(type = PoseCanonicalizer.mirroredIdentity(it.type), x = 1 - it.x)
        })
        assertEquals(SpokenAlignmentCue.RIGHT_HAND_ON_KNEE, armGuide.evaluate(mirrored)!!.correction)
    }

    @Test
    fun bodyEvidenceAndGeometryFailuresSuppressArmAdvice() {
        assertNull(armGuide.evaluate(PoseObservation(emptyList(), 0, 0, reference.imageSize)))
        assertNull(armGuide.evaluate(PoseObservation(reference.landmarks, 0, 2, reference.imageSize)))
        assertNull(armGuide.evaluate(PoseObservation(reference.landmarks, 0, 1, PoseImageSize(574, 1024))))
        for (point in listOf(PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_HIP)) {
            val hidden = changed(point) { it.copy(visibility = 0.1) }
            assertNull(armGuide.evaluate(hidden))
            assertEquals(SpokenAlignmentCue.FULL_BODY, CalibrationSpokenGuidance().cueFor(guide.evaluate(hidden)))
        }
    }

    @Test
    fun visibleArmMismatchTakesPriorityEvenWhenFramingIsOff() {
        val wrong = changed(PoseLandmark.LEFT_WRIST) { it.copy(y = 0.2) }
        val offCenter = observation(wrong.landmarks.map { it.copy(x = it.x * 0.5 + 0.4, y = it.y * 0.5) })
        val feedback = guide.evaluate(offCenter)
        assertEquals(AlignmentCue.CENTER, feedback.cue)
        assertEquals(SpokenAlignmentCue.LEFT_HAND_ON_KNEE, CalibrationSpokenGuidance().cueFor(feedback))
    }

    @Test
    fun fixingOtherJointCannotEarnGoodForRequestedJoint() {
        val speech = CalibrationSpokenGuidance()
        val wrong = guide.evaluate(changed(PoseLandmark.LEFT_WRIST) { it.copy(y = 0.2) })
        assertNull(speech.next(wrong, 0, false))
        assertNull(speech.next(wrong, 500, false))
        assertEquals(SpokenAlignmentCue.LEFT_HAND_ON_KNEE, speech.next(wrong, 1_000, false))
        speech.speechCompleted(3_000)
        for (now in 3_000L..4_000L step 500) assertNull(speech.next(wrong, now, false))
        // Only the requested left arm is fixed; the right arm now needs correction.
        val correctedLeft = guide.evaluate(changed(PoseLandmark.RIGHT_WRIST) { it.copy(y = 0.2) })
        assertNull(speech.next(correctedLeft, 4_500, false))
        assertNull(speech.next(correctedLeft, 5_000, false))
        assertEquals(SpokenAlignmentCue.GOOD, speech.next(correctedLeft, 5_500, false))
        speech.speechCompleted(6_000)
        for (now in 6_000L..13_500L step 500) assertNull(speech.next(correctedLeft, now, false))
        assertEquals(SpokenAlignmentCue.RIGHT_HAND_ON_KNEE, speech.next(correctedLeft, 14_000, false))
    }

    @Test
    fun requestedJointRemainsTheFocusUntilCorrected() {
        val speech = CalibrationSpokenGuidance()
        val leftWrong = guide.evaluate(changed(PoseLandmark.LEFT_WRIST) { it.copy(y = 0.3) })
        for (now in 0L..500L step 500) assertNull(speech.next(leftWrong, now, false))
        assertEquals(SpokenAlignmentCue.LEFT_HAND_ON_KNEE, speech.next(leftWrong, 1_000, false))
        speech.speechCompleted(3_000)
        val bothWrong = guide.evaluate(observation(reference.landmarks.map {
            when (it.type) {
                PoseLandmark.LEFT_WRIST -> it.copy(y = 0.3)
                PoseLandmark.RIGHT_WRIST -> it.copy(y = 0.05)
                else -> it
            }
        }))
        assertEquals(SpokenAlignmentCue.RIGHT_HAND_ON_KNEE, bothWrong.armPose!!.correction)
        for (now in 3_000L..10_500L step 500) assertNull(speech.next(bothWrong, now, false))
        assertEquals(SpokenAlignmentCue.LEFT_HAND_UNCONFIRMED, speech.next(bothWrong, 11_000, false))
    }

    private fun changed(type: PoseLandmark, change: (Landmark) -> Landmark): PoseObservation =
        observation(reference.landmarks.map { if (it.type == type) change(it) else it })

    private fun observation(landmarks: List<Landmark>) = PoseObservation(landmarks, 0, 1, reference.imageSize)
}
