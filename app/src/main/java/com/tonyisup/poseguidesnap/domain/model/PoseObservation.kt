package com.tonyisup.poseguidesnap.domain.model

/** Landmark output for one analyzed frame, timestamped by the owning monotonic clock. */
@ConsistentCopyVisibility
data class PoseObservation private constructor(
    val landmarks: List<Landmark>,
    val monotonicTimestampNanos: Long,
    val detectedPersonCount: Int,
) {
    constructor(
        landmarks: Iterable<Landmark>,
        monotonicTimestampNanos: Long,
        detectedPersonCount: Int,
    ) : this(
        landmarks = immutableList(landmarks),
        monotonicTimestampNanos = monotonicTimestampNanos,
        detectedPersonCount = detectedPersonCount,
    )

    init {
        require(monotonicTimestampNanos >= 0) {
            "monotonicTimestampNanos must be nonnegative"
        }
        require(detectedPersonCount >= 0) { "detectedPersonCount must be nonnegative" }
        require((detectedPersonCount == 0) == landmarks.isEmpty()) {
            "landmarks must be empty exactly when detectedPersonCount is zero"
        }
        requireUniqueLandmarkTypes(landmarks)
    }
}

internal fun requireUniqueLandmarkTypes(landmarks: List<Landmark>) {
    require(landmarks.map(Landmark::type).distinct().size == landmarks.size) {
        "landmark identities must be unique"
    }
}
