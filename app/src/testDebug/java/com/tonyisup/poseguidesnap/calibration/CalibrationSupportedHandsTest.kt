package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.*
import org.junit.Test

class CalibrationSupportedHandsTest {
    private val reference = BundledMeditationReference.observation
    private val guide = CalibrationAlignmentGuide()

    @Test
    fun wristErrorsInEveryDirectionAskForTheSameSideKneeInsteadOfHovering() {
        for ((joint, side) in listOf(ArmJoint.LEFT_WRIST to "left", ArmJoint.RIGHT_WRIST to "right")) {
            val knee = reference.landmarks.first {
                it.type == if (joint.isLeft) PoseLandmark.LEFT_KNEE else PoseLandmark.RIGHT_KNEE
            }
            for ((dx, dy) in listOf(0.14 to 0.0, -0.14 to 0.0, 0.0 to 0.2, 0.0 to -0.2)) {
                val feedback = guide.evaluate(PoseObservation(reference.landmarks.map {
                    if (it.type == joint.landmark) it.copy(x = knee.x + dx, y = knee.y + dy) else it
                }, 0, 1, reference.imageSize))
                val cue = CalibrationSpokenGuidance().cueFor(feedback)!!
                assertEquals(joint, cue.armJoint)
                assertEquals("Rest your $side hand on your $side knee, palm up. Let your arm relax.", cue.text)
            }
        }
    }

    @Test
    fun handAtSameSideKneeFitsExistingPositionToleranceWithoutClaimingContact() {
        val knees = mapOf(PoseLandmark.LEFT_WRIST to PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_WRIST to PoseLandmark.RIGHT_KNEE)
        val live = PoseObservation(reference.landmarks.map { landmark ->
            val knee = knees[landmark.type]?.let { type -> reference.landmarks.first { it.type == type } }
            if (knee == null) landmark else landmark.copy(x = knee.x, y = knee.y)
        }, 0, 1, reference.imageSize)
        val arms = guide.evaluate(live).armPose!!
        assertTrue(arms.matches(ArmJoint.LEFT_WRIST))
        assertTrue(arms.matches(ArmJoint.RIGHT_WRIST))
        assertEquals(0.25, ArmPoseFeedback.MAX_ARM_ERROR, 0.0)
        // These are only projected locations; physical support and palm orientation are not inputs.
    }
}
