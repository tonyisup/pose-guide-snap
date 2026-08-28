package com.tonyisup.poseguidesnap.domain.model

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet

/** Stable semantic identities matching the conventional 33-landmark pose topology. */
enum class PoseLandmark {
    NOSE,
    LEFT_EYE_INNER,
    LEFT_EYE,
    LEFT_EYE_OUTER,
    RIGHT_EYE_INNER,
    RIGHT_EYE,
    RIGHT_EYE_OUTER,
    LEFT_EAR,
    RIGHT_EAR,
    MOUTH_LEFT,
    MOUTH_RIGHT,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_WRIST,
    RIGHT_WRIST,
    LEFT_PINKY,
    RIGHT_PINKY,
    LEFT_INDEX,
    RIGHT_INDEX,
    LEFT_THUMB,
    RIGHT_THUMB,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE,
    LEFT_HEEL,
    RIGHT_HEEL,
    LEFT_FOOT_INDEX,
    RIGHT_FOOT_INDEX,
}

/** A detector-independent pose landmark in normalized image coordinates. */
data class Landmark(
    val type: PoseLandmark,
    val x: Double,
    val y: Double,
    /** Relative detector depth; signed values are valid. */
    val z: Double,
    val visibility: Double,
    val presence: Double,
) {
    init {
        requireNormalized(x, "x")
        requireNormalized(y, "y")
        requireFinite(z, "z")
        requireNormalized(visibility, "visibility")
        requireNormalized(presence, "presence")
    }
}

internal fun requireNormalized(value: Double, fieldName: String) {
    require(value.isFinite() && value in 0.0..1.0) {
        "$fieldName must be finite and in [0, 1], but was $value"
    }
}

internal fun requireFinite(value: Double, fieldName: String) {
    require(value.isFinite()) { "$fieldName must be finite, but was $value" }
}

internal fun requireNonBlank(value: String, fieldName: String) {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
}

internal fun <T> immutableList(values: Iterable<T>): List<T> {
    val snapshot = ArrayList<T>()
    snapshot.addAll(values)
    return Collections.unmodifiableList(snapshot)
}

internal fun <T> immutableSet(values: Iterable<T>): Set<T> {
    val snapshot = LinkedHashSet<T>()
    snapshot.addAll(values)
    return Collections.unmodifiableSet(snapshot)
}
