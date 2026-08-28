package com.tonyisup.poseguidesnap.domain.model

/** Identifies the detector and model that produced persisted reference landmarks. */
data class PoseDetectorMetadata(
    val detectorName: String,
    val modelName: String,
    val modelVersion: String,
) {
    init {
        requireNonBlank(detectorName, "detectorName")
        requireNonBlank(modelName, "modelName")
        requireNonBlank(modelVersion, "modelVersion")
    }
}

/** Immutable imported pose definition; image ownership remains outside the domain. */
@ConsistentCopyVisibility
data class ReferencePose private constructor(
    val id: String,
    val label: String,
    val importedAtEpochMillis: Long,
    val detectorMetadata: PoseDetectorMetadata,
    val mirrorMatchingAllowed: Boolean,
    val landmarks: List<Landmark>,
) {
    constructor(
        id: String,
        label: String,
        importedAtEpochMillis: Long,
        detectorMetadata: PoseDetectorMetadata,
        mirrorMatchingAllowed: Boolean,
        landmarks: Iterable<Landmark>,
    ) : this(
        id = id,
        label = label,
        importedAtEpochMillis = importedAtEpochMillis,
        detectorMetadata = detectorMetadata,
        mirrorMatchingAllowed = mirrorMatchingAllowed,
        landmarks = immutableList(landmarks),
    )

    init {
        requireNonBlank(id, "id")
        requireNonBlank(label, "label")
        require(importedAtEpochMillis >= 0) { "importedAtEpochMillis must be nonnegative" }
        require(landmarks.isNotEmpty()) { "reference landmarks must not be empty" }
        requireUniqueLandmarkTypes(landmarks)
    }
}
