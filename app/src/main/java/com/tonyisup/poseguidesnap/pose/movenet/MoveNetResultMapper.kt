package com.tonyisup.poseguidesnap.pose.movenet

import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import kotlin.math.floor
import kotlin.math.min

/** Immutable snapshot of one MoveNet MultiPose output tensor after removing its batch dimension. */
class MoveNetRawOutput(slots: Iterable<FloatArray>) {
    private val values: List<FloatArray> = slots.map(FloatArray::copyOf)

    init {
        require(values.size == PERSON_SLOT_COUNT) {
            "MoveNet output must contain exactly $PERSON_SLOT_COUNT person slots"
        }
        require(values.all { it.size == VALUES_PER_SLOT }) {
            "Each MoveNet person slot must contain exactly $VALUES_PER_SLOT values"
        }
    }

    internal fun value(slotIndex: Int, valueIndex: Int): Float = values[slotIndex][valueIndex]

    internal companion object {
        const val PERSON_SLOT_COUNT = 6
        const val VALUES_PER_SLOT = 56
    }
}

/** Adapter-local person acceptance policy; the default score remains explicitly uncalibrated. */
class MoveNetMappingPolicy(
    val minimumPersonScore: Double = DEFAULT_MINIMUM_PERSON_SCORE,
) {
    init {
        require(minimumPersonScore.isFinite() && minimumPersonScore in 0.0..1.0) {
            "minimumPersonScore must be finite and in [0, 1]"
        }
    }

    companion object {
        const val DEFAULT_MINIMUM_PERSON_SCORE = 0.25
    }
}

/** Deterministic square-letterbox geometry for an upright source image. */
class MoveNetLetterboxGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetSize: Int = DEFAULT_TARGET_SIZE,
) {
    val scale: Double
    val resizedWidth: Int
    val resizedHeight: Int
    val padLeft: Int
    val padTop: Int
    val padRight: Int
    val padBottom: Int

    init {
        require(sourceWidth > 0) { "sourceWidth must be positive" }
        require(sourceHeight > 0) { "sourceHeight must be positive" }
        require(targetSize > 0 && targetSize % 32 == 0) {
            "targetSize must be positive and a multiple of 32"
        }

        scale = min(targetSize.toDouble() / sourceWidth, targetSize.toDouble() / sourceHeight)
        resizedWidth = halfUp(sourceWidth * scale).coerceIn(1, targetSize)
        resizedHeight = halfUp(sourceHeight * scale).coerceIn(1, targetSize)

        val horizontalPadding = targetSize - resizedWidth
        val verticalPadding = targetSize - resizedHeight
        padLeft = horizontalPadding / 2
        padRight = horizontalPadding - padLeft
        padTop = verticalPadding / 2
        padBottom = verticalPadding - padTop
    }

    /** Unpads a normalized model-input x coordinate into normalized upright-source space. */
    fun unpadX(inputX: Double): Double =
        (inputX * targetSize - padLeft) / resizedWidth

    /** Unpads a normalized model-input y coordinate into normalized upright-source space. */
    fun unpadY(inputY: Double): Double =
        (inputY * targetSize - padTop) / resizedHeight

    private companion object {
        const val DEFAULT_TARGET_SIZE = 256

        fun halfUp(value: Double): Int = floor(value + 0.5).toInt()
    }
}

/**
 * Pure adapter from MoveNet MultiPose output into the detector-independent observation domain.
 *
 * Rotation and mirroring are deliberately outside this mapper: callers must letterbox an upright
 * image, while mirror-candidate evaluation remains a separate canonicalization concern.
 */
class MoveNetResultMapper(
    val policy: MoveNetMappingPolicy = MoveNetMappingPolicy(),
) {
    fun map(
        rawOutput: MoveNetRawOutput,
        letterbox: MoveNetLetterboxGeometry,
        monotonicTimestampNanos: Long,
    ): PoseObservation {
        require(monotonicTimestampNanos >= 0) {
            "monotonicTimestampNanos must be nonnegative"
        }

        val acceptedSlots = (0 until MoveNetRawOutput.PERSON_SLOT_COUNT).filter { slotIndex ->
            val score = rawOutput.value(slotIndex, INSTANCE_SCORE_INDEX)
            score.isFinite() &&
                score >= policy.minimumPersonScore &&
                score <= 1.0f
        }
        if (acceptedSlots.isEmpty()) {
            return PoseObservation(
                landmarks = emptyList(),
                monotonicTimestampNanos = monotonicTimestampNanos,
                detectedPersonCount = 0,
            )
        }

        val selectedSlot = acceptedSlots.reduce { strongestSlot, candidateSlot ->
            if (
                rawOutput.value(candidateSlot, INSTANCE_SCORE_INDEX) >
                rawOutput.value(strongestSlot, INSTANCE_SCORE_INDEX)
            ) {
                candidateSlot
            } else {
                strongestSlot
            }
        }
        val landmarks = COCO_IDENTITIES.mapIndexedNotNull { keypointIndex, identity ->
            val valueOffset = keypointIndex * VALUES_PER_KEYPOINT
            val inputY = rawOutput.value(selectedSlot, valueOffset)
            val inputX = rawOutput.value(selectedSlot, valueOffset + 1)
            val score = rawOutput.value(selectedSlot, valueOffset + 2)
            if (
                !inputY.isFinite() ||
                !inputX.isFinite() ||
                !score.isFinite() ||
                score !in 0.0f..1.0f
            ) {
                return@mapIndexedNotNull null
            }

            val sourceX = letterbox.unpadX(inputX.toDouble())
            val sourceY = letterbox.unpadY(inputY.toDouble())
            if (!sourceX.isFinite() || !sourceY.isFinite() || sourceX !in 0.0..1.0 || sourceY !in 0.0..1.0) {
                return@mapIndexedNotNull null
            }

            Landmark(
                type = identity,
                x = sourceX,
                y = sourceY,
                z = 0.0,
                // MoveNet exposes one keypoint score. These are aliases, not independent probabilities.
                visibility = score.toDouble(),
                presence = score.toDouble(),
            )
        }
        check(landmarks.isNotEmpty()) {
            "Accepted MoveNet person contained no representable keypoints"
        }

        return PoseObservation(
            landmarks = landmarks,
            monotonicTimestampNanos = monotonicTimestampNanos,
            detectedPersonCount = acceptedSlots.size,
        )
    }

    private companion object {
        const val VALUES_PER_KEYPOINT = 3
        const val INSTANCE_SCORE_INDEX = 55

        val COCO_IDENTITIES = listOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_EYE,
            PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_EAR,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE,
        )
    }
}
