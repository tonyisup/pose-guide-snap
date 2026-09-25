package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.camera.NormalizedPoint
import com.tonyisup.poseguidesnap.camera.PixelPoint
import com.tonyisup.poseguidesnap.camera.PixelSize
import com.tonyisup.poseguidesnap.camera.PreviewFillCenterTransform
import com.tonyisup.poseguidesnap.domain.match.FramingEvidenceStatus
import com.tonyisup.poseguidesnap.domain.match.FramingPolicy
import com.tonyisup.poseguidesnap.domain.match.PoseFramingEvaluator
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Composition target used only by the debug calibration coaching. The production lock policy no
 * longer gates on centre or scale similarity; this value exists so the coaching cues can still
 * steer a participant toward the reference framing when a run wants that.
 */
internal const val CALIBRATION_ALIGNMENT_TARGET_SCORE = 0.8

/** Debug display only. Never serialize or log transient participant geometry. */
internal class AlignmentBounds(landmarks: List<Landmark>) {
    val left = landmarks.minOf { it.x }
    val top = landmarks.minOf { it.y }
    val right = landmarks.maxOf { it.x }
    val bottom = landmarks.maxOf { it.y }
    val center = NormalizedPoint((left + right) / 2, (top + bottom) / 2)
    val diagonal = hypot(right - left, bottom - top)
    override fun toString() = "AlignmentBounds(redacted)"
}

internal enum class AlignmentCue(val text: String) {
    WAITING("Waiting for a fresh camera frame."),
    NO_PERSON("Keep your whole body inside the camera frame."),
    MULTIPLE_PEOPLE("Keep only one person in view."),
    INCOMPLETE("Keep your face, arms and legs visible."),
    GEOMETRY_MISMATCH("Camera framing changed. Restart the guided test."),
    CENTER("Move your outline toward the white cross in the picture."),
    CLOSER("Move closer to the camera. Keep your whole body visible."),
    FARTHER("Move farther from the camera. Keep the same pose."),
    ALIGNED("Framing aligned. Now match the pose shown above."),
}

internal class AlignmentFeedback(
    val cue: AlignmentCue,
    val observedBounds: AlignmentBounds? = null,
    val imageSize: PoseImageSize? = null,
    val armPose: ArmPoseFeedback? = null,
    val motionSample: CalibrationMotionSample? = null,
) {
    override fun toString() = "AlignmentFeedback(cue=$cue, redacted)"
}

/**
 * The collector uses the public reference's aspect ratio for both preview and analysis crop.
 * Guide coordinates use the SAME live crop transform; fitting the reference independently would
 * place the target somewhere other than the normalized coordinates scored by the framing gate.
 * This helper never changes match policy or claims that aligned framing is a pose match.
 */
internal class CalibrationAlignmentGuide {
    private val reference = BundledMeditationReference.observation
    private val requiredIdentities = reference.landmarks.map { it.type }.toSet()
    private val policy = FramingPolicy.developmentDefaults()
    private val evaluator = PoseFramingEvaluator(policy)
    private val armGuide = CalibrationArmPoseGuide()
    private val minimumScore = CALIBRATION_ALIGNMENT_TARGET_SCORE
    val targetBounds = AlignmentBounds(reference.landmarks)
    val referenceSize = reference.imageSize

    fun evaluate(observed: PoseObservation): AlignmentFeedback {
        if (!compatibleAspect(observed.imageSize, referenceSize)) {
            return AlignmentFeedback(AlignmentCue.GEOMETRY_MISMATCH)
        }
        if (observed.detectedPersonCount == 0) return AlignmentFeedback(AlignmentCue.NO_PERSON)
        if (observed.detectedPersonCount != 1) return AlignmentFeedback(AlignmentCue.MULTIPLE_PEOPLE)
        val qualified = observed.landmarks.filter {
            it.type in requiredIdentities && min(it.visibility, it.presence) >= policy.minimumLandmarkConfidence
        }
        // A partially detected body can appear smaller: do not prescribe moving closer from it.
        if (qualified.size != requiredIdentities.size) return AlignmentFeedback(AlignmentCue.INCOMPLETE)
        val evidence = evaluator.evaluate(reference, observed)
        if (evidence.status != FramingEvidenceStatus.EVALUATED) {
            return AlignmentFeedback(AlignmentCue.INCOMPLETE)
        }
        val bounds = AlignmentBounds(qualified)
        val cue = when {
            evidence.centerSimilarity < minimumScore -> AlignmentCue.CENTER
            evidence.scaleSimilarity >= minimumScore -> AlignmentCue.ALIGNED
            bounds.diagonal < targetBounds.diagonal -> AlignmentCue.CLOSER
            else -> AlignmentCue.FARTHER
        }
        return AlignmentFeedback(cue, bounds, observed.imageSize, armGuide.evaluate(observed),
            CalibrationMotionSample.from(qualified, observed.imageSize))
    }

    fun fittedPreviewSize(availableWidth: Int, availableHeight: Int): PoseImageSize {
        require(availableWidth > 0 && availableHeight > 0)
        val scale = min(
            availableWidth.toDouble() / referenceSize.width,
            availableHeight.toDouble() / referenceSize.height,
        )
        return PoseImageSize(
            (referenceSize.width * scale).roundToInt().coerceIn(1, availableWidth),
            (referenceSize.height * scale).roundToInt().coerceIn(1, availableHeight),
        )
    }

    fun previewPoint(point: NormalizedPoint, imageSize: PoseImageSize, viewport: PoseImageSize): PixelPoint =
        PreviewFillCenterTransform(
            PixelSize(imageSize.width.toDouble(), imageSize.height.toDouble()),
            PixelSize(viewport.width.toDouble(), viewport.height.toDouble()),
        ).contentToPreview(point)

    fun compatibleAspect(first: PoseImageSize, second: PoseImageSize): Boolean =
        abs(first.aspectRatio / second.aspectRatio - 1.0) <= 0.01
}
