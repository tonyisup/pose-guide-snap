package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.match.PoseCanonicalizationResult
import com.tonyisup.poseguidesnap.domain.match.PoseCanonicalizer
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import kotlin.math.abs
import kotlin.math.hypot

internal enum class ArmJoint(val landmark: PoseLandmark, val isLeft: Boolean) {
    LEFT_ELBOW(PoseLandmark.LEFT_ELBOW, true),
    LEFT_WRIST(PoseLandmark.LEFT_WRIST, true),
    RIGHT_ELBOW(PoseLandmark.RIGHT_ELBOW, false),
    RIGHT_WRIST(PoseLandmark.RIGHT_WRIST, false),
}

internal enum class ArmDirection { LEFT, RIGHT, UP, DOWN }

/** Transient signed target-minus-live displacement; never serialize or log coordinates. */
internal class ArmJointOffset(val dx: Double, val dy: Double) {
    val error: Double get() = hypot(dx, dy)
    val direction: ArmDirection get() = if (abs(dx) >= abs(dy)) {
        // Facing the unmirrored rear lens: image-right is the participant's left.
        if (dx > 0) ArmDirection.LEFT else ArmDirection.RIGHT
    } else {
        if (dy > 0) ArmDirection.DOWN else ArmDirection.UP
    }
    override fun toString() = "ArmJointOffset(redacted)"
}

/** Transient per-joint evidence. Scalar diagnostic ranges remain separately bounded. */
internal class ArmPoseFeedback(offsets: Map<ArmJoint, ArmJointOffset>) {
    private val offsets = offsets.toMap()
    val leftElbowError: Double get() = offset(ArmJoint.LEFT_ELBOW).error
    val leftWristError: Double get() = offset(ArmJoint.LEFT_WRIST).error
    val rightElbowError: Double get() = offset(ArmJoint.RIGHT_ELBOW).error
    val rightWristError: Double get() = offset(ArmJoint.RIGHT_WRIST).error
    val correction: SpokenAlignmentCue? get() = correctionFor(ArmJoint.entries.maxBy { offset(it).error })

    fun offset(joint: ArmJoint): ArmJointOffset = offsets.getValue(joint)
    fun matches(joint: ArmJoint): Boolean = offset(joint).error <= MAX_ARM_ERROR
    fun correctionFor(joint: ArmJoint): SpokenAlignmentCue? = if (matches(joint)) null else when (joint) {
        // Wrists target the participant's own knee; elbows target the reference pose.
        // Projected proximity cannot verify contact or palm orientation.
        ArmJoint.LEFT_WRIST -> SpokenAlignmentCue.LEFT_HAND_ON_KNEE
        ArmJoint.RIGHT_WRIST -> SpokenAlignmentCue.RIGHT_HAND_ON_KNEE
        else -> SpokenAlignmentCue.entries.first {
            it.armJoint == joint && it.armDirection == offset(joint).direction
        }
    }

    override fun toString() = "ArmPoseFeedback(redacted)"

    companion object {
        // Debug coaching only: reference elbow error or same-side wrist-to-knee distance.
        // This radius is not a physical-contact detector or a whole-pose match gate.
        const val MAX_ARM_ERROR = 0.25
    }
}

/**
 * Coaching for the bundled seated meditation reference only. Compare elbows with the reference
 * and wrists with the participant's own knees, matching the spoken resting instruction.
 * Live landmarks retain their anatomical identities; mirror the reference, never the spoken side.
 */
internal class CalibrationArmPoseGuide {
    private val canonicalizer = PoseCanonicalizer(0.25, 1e-9)
    private val reference = BundledMeditationReference.observation
    private val targets = listOf(false, true).map { mirror ->
        (canonicalizer.canonicalize(reference, mirror) as PoseCanonicalizationResult.Success).features
    }

    fun evaluate(observed: PoseObservation): ArmPoseFeedback? {
        if (observed.detectedPersonCount != 1 ||
            abs(observed.imageSize.aspectRatio / reference.imageSize.aspectRatio - 1) > 0.01) return null
        val live = (canonicalizer.canonicalize(observed) as? PoseCanonicalizationResult.Success)
            ?.features ?: return null
        if (REQUIRED_POINTS.any { it !in live.points }) return null
        return targets.map { target ->
            ArmPoseFeedback(ArmJoint.entries.associateWith { joint ->
                val actual = live.points.getValue(joint.landmark)
                val expected = when (joint) {
                    ArmJoint.LEFT_WRIST -> live.points.getValue(PoseLandmark.LEFT_KNEE)
                    ArmJoint.RIGHT_WRIST -> live.points.getValue(PoseLandmark.RIGHT_KNEE)
                    else -> target.points.getValue(joint.landmark)
                }
                ArmJointOffset(expected.x - actual.x, expected.y - actual.y)
            })
        // Wrist-to-knee distance is identical for both reference choices and must not mask
        // which reference better explains the elbows when a wrist is far from its knee.
        }.minBy { maxOf(it.leftElbowError, it.rightElbowError) }
    }

    companion object {
        private val REQUIRED_POINTS = setOf(PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE)
    }
}
