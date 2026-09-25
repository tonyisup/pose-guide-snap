package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.match.FramingPolicy
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.abs

/** Fixed local phrases, interpreted for a participant facing the unmirrored rear camera. */
internal enum class SpokenAlignmentCue(
    val text: String,
    val armJoint: ArmJoint? = null,
    val armDirection: ArmDirection? = null,
) {
    GOOD("Good."),
    LEFT_HAND_ON_KNEE("Rest your left hand on your left knee, palm up. Let your arm relax.", ArmJoint.LEFT_WRIST),
    LEFT_HAND_UNCONFIRMED("If your left hand is already resting on your left knee, keep it there. I can't confirm its position yet.", ArmJoint.LEFT_WRIST),
    LEFT_ELBOW_UP("Keep your left hand resting on your left knee. Raise your left elbow, then pause.", ArmJoint.LEFT_ELBOW, ArmDirection.UP),
    LEFT_ELBOW_DOWN("Keep your left hand resting on your left knee. Lower your left elbow, then pause.", ArmJoint.LEFT_ELBOW, ArmDirection.DOWN),
    LEFT_ELBOW_LEFT("Keep your left hand resting on your left knee. Move your left elbow to your left, then pause.", ArmJoint.LEFT_ELBOW, ArmDirection.LEFT),
    LEFT_ELBOW_RIGHT("Keep your left hand resting on your left knee. Move your left elbow to your right, then pause.", ArmJoint.LEFT_ELBOW, ArmDirection.RIGHT),
    RIGHT_HAND_ON_KNEE("Rest your right hand on your right knee, palm up. Let your arm relax.", ArmJoint.RIGHT_WRIST),
    RIGHT_HAND_UNCONFIRMED("If your right hand is already resting on your right knee, keep it there. I can't confirm its position yet.", ArmJoint.RIGHT_WRIST),
    RIGHT_ELBOW_UP("Keep your right hand resting on your right knee. Raise your right elbow, then pause.", ArmJoint.RIGHT_ELBOW, ArmDirection.UP),
    RIGHT_ELBOW_DOWN("Keep your right hand resting on your right knee. Lower your right elbow, then pause.", ArmJoint.RIGHT_ELBOW, ArmDirection.DOWN),
    RIGHT_ELBOW_LEFT("Keep your right hand resting on your right knee. Move your right elbow to your left, then pause.", ArmJoint.RIGHT_ELBOW, ArmDirection.LEFT),
    RIGHT_ELBOW_RIGHT("Keep your right hand resting on your right knee. Move your right elbow to your right, then pause.", ArmJoint.RIGHT_ELBOW, ArmDirection.RIGHT),
    FULL_BODY("I need to see your whole body. Keep your face, arms and legs in view."),
    ONE_PERSON("Only one person should be in view."),
    RIGHT("Facing the camera, move a little to your right."),
    LEFT("Facing the camera, move a little to your left."),
    TILT_DOWN("Tilt the phone down slightly, then return to your pose."),
    TILT_UP("Tilt the phone up slightly, then return to your pose."),
    CLOSER("Move a little closer to the camera."),
    FARTHER("Move a little farther from the camera."),
    ALIGNED("Your framing is aligned."),
    RESTART("Camera framing changed. Stop and reset the phone."),
}

internal enum class CalibrationPreparationDecision { WAITING, READY, TIMED_OUT }

internal enum class CalibrationPreparationBlocker {
    DEADLINE, INITIAL_ALLOWANCE, SPEECH, QUIET_PAUSE, TRACKING,
    UNSETTLED, ARM_POSITION, FRAMING, UNCONFIRMED_ADJUSTMENT,
}

/** Closed reason flags only: no coordinates, paths, trajectories or transcript. */
internal class CalibrationPreparationStatus(
    val decision: CalibrationPreparationDecision,
    blockers: Set<CalibrationPreparationBlocker>,
) {
    val blockers = blockers.toSet()
    fun summary(): String = "result=${decision.name} blockers=" +
        CalibrationPreparationBlocker.entries.filter { it in blockers }
            .joinToString(",") { it.name }.ifEmpty { "none" }
}

/** No backlog: observe motion independently of target error and require a settled response. */
internal class CalibrationSpokenGuidance {
    private val targetBounds = CalibrationAlignmentGuide().targetBounds
    private val target = targetBounds.center
    private val minimumScore = CALIBRATION_ALIGNMENT_TARGET_SCORE
    // Equal per-axis error budgets fit inside the existing radial center tolerance.
    private val axisTolerance = (1 - minimumScore) *
        FramingPolicy.developmentDefaults().centerErrorAtZeroSimilarity / sqrt(2.0)
    private var requested: SpokenAlignmentCue? = null
    private var satisfiedSince: Long? = null
    private var lastFrameAt: Long? = null
    private var candidate: SpokenAlignmentCue? = null
    private var candidateSince = 0L
    private var awaitingCompletion = false
    private var completedAt: Long? = null
    private var deadlineMs = Long.MAX_VALUE
    private var earliestHoldAtMs = Long.MIN_VALUE
    private var completeBodyEvidence = false
    private var armsReady = false
    private var framingReady = false
    private var announcedAligned = false
    private val instructedHands = mutableSetOf<ArmJoint>()
    private val explainedHands = mutableSetOf<ArmJoint>()
    private val settling = CalibrationSettlingTracker()
    private var armDiagnostics = CalibrationArmConfirmationDiagnostics()

    fun start(deadlineMs: Long, earliestHoldAtMs: Long = Long.MIN_VALUE) {
        require(earliestHoldAtMs < deadlineMs)
        this.deadlineMs = deadlineMs
        this.earliestHoldAtMs = earliestHoldAtMs
        completeBodyEvidence = false
        armsReady = false
        framingReady = false
        announcedAligned = false
        instructedHands.clear()
        explainedHands.clear()
        settling.reset()
        armDiagnostics = CalibrationArmConfirmationDiagnostics()
        candidate = null
        requested = null
        satisfiedSince = null
        lastFrameAt = null
        completedAt = null
        awaitingCompletion = true // The introductory announcement also needs time to finish.
    }

    fun speechCompleted(nowMs: Long, cue: SpokenAlignmentCue? = null) {
        awaitingCompletion = false
        completedAt = nowMs
        settling.reset() // Stillness must be observed after speech, not while it is being spoken.
        if (cue?.armJoint != null) {
            armDiagnostics.speechCompleted(deadlineMs - nowMs)
        }
    }

    fun armConfirmationSummary(): String = armDiagnostics.summary()

    fun quietRemainingMs(nowMs: Long): Long =
        completedAt?.let { (8_000L - (nowMs - it)).coerceAtLeast(0L) } ?: 0L

    fun preparationStatus(nowMs: Long): CalibrationPreparationStatus {
        val blockers = buildSet {
            if (nowMs >= deadlineMs) add(CalibrationPreparationBlocker.DEADLINE)
            if (nowMs < earliestHoldAtMs) add(CalibrationPreparationBlocker.INITIAL_ALLOWANCE)
            if (awaitingCompletion) add(CalibrationPreparationBlocker.SPEECH)
            if (quietRemainingMs(nowMs) > 0) add(CalibrationPreparationBlocker.QUIET_PAUSE)
            if (!completeBodyEvidence || lastFrameAt?.let { nowMs - it in 0..750 } != true) {
                add(CalibrationPreparationBlocker.TRACKING)
            } else {
                if (!settling.isSettled(nowMs)) add(CalibrationPreparationBlocker.UNSETTLED)
                if (!armsReady) add(CalibrationPreparationBlocker.ARM_POSITION)
                if (!framingReady) add(CalibrationPreparationBlocker.FRAMING)
            }
            if (requested != null) add(CalibrationPreparationBlocker.UNCONFIRMED_ADJUSTMENT)
        }
        val decision = when {
            CalibrationPreparationBlocker.DEADLINE in blockers -> CalibrationPreparationDecision.TIMED_OUT
            blockers.isEmpty() -> CalibrationPreparationDecision.READY
            else -> CalibrationPreparationDecision.WAITING
        }
        return CalibrationPreparationStatus(decision, blockers)
    }

    fun preparationDecision(nowMs: Long): CalibrationPreparationDecision = preparationStatus(nowMs).decision

    fun clearMotionEvidence() = settling.reset()

    fun next(feedback: AlignmentFeedback, nowMs: Long, busy: Boolean): SpokenAlignmentCue? {
        val cue = cueFor(feedback)
        if (busy || awaitingCompletion) settling.reset() else settling.record(feedback.motionSample, nowMs)
        val settled = settling.isSettled(nowMs)
        completeBodyEvidence = feedback.armPose != null && feedback.motionSample != null
        armsReady = feedback.armPose != null && feedback.armPose.correction == null
        framingReady = feedback.cue == AlignmentCue.ALIGNED
        if (!completeBodyEvidence || !armsReady || !framingReady) announcedAligned = false
        val gap = lastFrameAt?.let { nowMs - it !in 0..750 } == true
        if (gap) {
            satisfiedSince = null
            candidate = null
        }
        lastFrameAt = nowMs
        if (busy || awaitingCompletion || !adjustmentSatisfied(feedback)) {
            satisfiedSince = null
        } else if (satisfiedSince == null) {
            satisfiedSince = nowMs
        }
        recordArmConfirmation(feedback, nowMs, busy, gap)
        if (!busy && !awaitingCompletion && settled && nowMs < deadlineMs &&
            satisfiedSince?.let { nowMs - it >= 1_000L } == true) {
            requested?.armJoint?.let {
                instructedHands.remove(it)
                explainedHands.remove(it)
            }
            requested = null // Acknowledge each requested adjustment only once.
            satisfiedSince = null
            awaitingCompletion = true
            settling.reset()
            return SpokenAlignmentCue.GOOD
        }
        if (cue != candidate) {
            candidate = cue
            candidateSince = nowMs
        }
        if (cue == null || busy || awaitingCompletion || nowMs - candidateSince < 1_000L) return null
        if (quietRemainingMs(nowMs) > 0 || deadlineMs - nowMs <= 10_000L) return null
        // Recovery prompts remain possible when motion cannot be measured (e.g. body out of view).
        if (cue !in setOf(SpokenAlignmentCue.FULL_BODY, SpokenAlignmentCue.ONE_PERSON,
                SpokenAlignmentCue.RESTART) && !settled) return null
        if (cue == SpokenAlignmentCue.ALIGNED && announcedAligned) return null
        val spoken = when (cue) {
            SpokenAlignmentCue.LEFT_HAND_ON_KNEE, SpokenAlignmentCue.RIGHT_HAND_ON_KNEE -> {
                val joint = checkNotNull(cue.armJoint)
                if (instructedHands.add(joint)) cue else {
                    // A repeated command gives no new action if the hand is already supported.
                    // Explain the uncertainty once, keep observing, and retain the pending check.
                    if (!explainedHands.add(joint)) return null
                    if (joint.isLeft) SpokenAlignmentCue.LEFT_HAND_UNCONFIRMED
                    else SpokenAlignmentCue.RIGHT_HAND_UNCONFIRMED
                }
            }
            else -> cue
        }
        awaitingCompletion = true
        requested = spoken.takeUnless { it == SpokenAlignmentCue.ALIGNED }
        if (cue == SpokenAlignmentCue.ALIGNED) announcedAligned = true
        satisfiedSince = null
        settling.reset()
        return spoken
    }

    private fun recordArmConfirmation(feedback: AlignmentFeedback, nowMs: Long, busy: Boolean, gap: Boolean) {
        val joint = requested?.armJoint ?: return
        val arms = feedback.armPose
        val stableMs = satisfiedSince?.let { nowMs - it } ?: 0L
        val state = when {
            nowMs >= deadlineMs -> ArmConfirmationState.EXPIRED
            busy || awaitingCompletion -> ArmConfirmationState.SPEAKING
            arms == null || feedback.observedBounds == null || feedback.cue !in setOf(
                AlignmentCue.CENTER, AlignmentCue.CLOSER, AlignmentCue.FARTHER, AlignmentCue.ALIGNED) ->
                ArmConfirmationState.UNAVAILABLE
            feedback.motionSample == null -> ArmConfirmationState.UNAVAILABLE
            !settling.isSettled(nowMs) -> ArmConfirmationState.UNSETTLED
            !adjustmentSatisfied(feedback) -> ArmConfirmationState.MISMATCH
            stableMs < 1_000L -> ArmConfirmationState.SETTLING
            else -> ArmConfirmationState.READY
        }
        armDiagnostics.record(state,
            if (joint.isLeft) arms?.leftElbowError else arms?.rightElbowError,
            if (joint.isLeft) arms?.leftWristError else arms?.rightWristError,
            arms?.offset(joint)?.error, stableMs, gap)
    }

    private fun adjustmentSatisfied(feedback: AlignmentFeedback): Boolean {
        // Only the guide's complete, single-person, compatible-geometry evidence can confirm work.
        if (feedback.cue !in setOf(AlignmentCue.CENTER, AlignmentCue.CLOSER,
                AlignmentCue.FARTHER, AlignmentCue.ALIGNED)) return false
        val bounds = feedback.observedBounds ?: return false
        requested?.armJoint?.let { return feedback.armPose?.matches(it) == true }
        return when (requested) {
            SpokenAlignmentCue.RIGHT, SpokenAlignmentCue.LEFT ->
                abs(bounds.center.x - target.x) <= axisTolerance
            SpokenAlignmentCue.TILT_DOWN, SpokenAlignmentCue.TILT_UP ->
                abs(bounds.center.y - target.y) <= axisTolerance
            SpokenAlignmentCue.CLOSER, SpokenAlignmentCue.FARTHER ->
                min(bounds.diagonal / targetBounds.diagonal,
                    targetBounds.diagonal / bounds.diagonal) >= minimumScore
            SpokenAlignmentCue.FULL_BODY, SpokenAlignmentCue.ONE_PERSON -> true
            else -> false
        }
    }

    internal fun cueFor(feedback: AlignmentFeedback): SpokenAlignmentCue? {
        // Full-body/geometry problems come first. Correct pose before fine framing: arms move bounds.
        if (feedback.observedBounds != null && feedback.cue in setOf(
                AlignmentCue.CENTER, AlignmentCue.CLOSER, AlignmentCue.FARTHER, AlignmentCue.ALIGNED)) {
            feedback.armPose?.let { arms ->
                // Keep the requested joint in focus; recompute direction after overshoot or axis changes.
                requested?.armJoint?.let { joint -> arms.correctionFor(joint)?.let { return it } }
                arms.correction?.let { return it }
            }
        }
        return when (feedback.cue) {
            AlignmentCue.WAITING -> null
            AlignmentCue.NO_PERSON, AlignmentCue.INCOMPLETE -> SpokenAlignmentCue.FULL_BODY
            AlignmentCue.MULTIPLE_PEOPLE -> SpokenAlignmentCue.ONE_PERSON
            AlignmentCue.GEOMETRY_MISMATCH -> SpokenAlignmentCue.RESTART
            AlignmentCue.CLOSER -> SpokenAlignmentCue.CLOSER
            AlignmentCue.FARTHER -> SpokenAlignmentCue.FARTHER
            AlignmentCue.ALIGNED -> SpokenAlignmentCue.ALIGNED
            AlignmentCue.CENTER -> feedback.observedBounds?.center?.let { live ->
                val dx = target.x - live.x
                val dy = target.y - live.y
                if (abs(dx) >= abs(dy)) {
                    // Facing an unmirrored rear camera, the participant's right is image-left.
                    if (dx < 0) SpokenAlignmentCue.RIGHT else SpokenAlignmentCue.LEFT
                } else {
                    // Tilting down moves the body upward within the image, and vice versa.
                    if (dy < 0) SpokenAlignmentCue.TILT_DOWN else SpokenAlignmentCue.TILT_UP
                }
            }
        }
    }
}

/** A presence-only preflight cannot admit a full-body collection. Stale readiness expires. */
internal class GuidedBodyReadiness {
    private var consecutive = 0
    private var lastFrameAt: Long? = null

    @Synchronized
    fun record(fullBodyEvaluated: Boolean, nowMs: Long) {
        if (lastFrameAt?.let { nowMs - it !in 0..750 } == true) consecutive = 0
        consecutive = if (fullBodyEvaluated) (consecutive + 1).coerceAtMost(5) else 0
        lastFrameAt = nowMs
    }

    @Synchronized
    fun isReady(nowMs: Long): Boolean =
        consecutive >= 5 && lastFrameAt?.let { nowMs - it in 0..750 } == true
}
