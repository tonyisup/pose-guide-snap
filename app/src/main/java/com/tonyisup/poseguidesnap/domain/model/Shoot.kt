package com.tonyisup.poseguidesnap.domain.model

/** Ordered shoot definition. Runtime session/reducer state is intentionally separate. */
@ConsistentCopyVisibility
data class Shoot private constructor(
    val id: String,
    val name: String,
    val referencePoses: List<ReferencePose>,
    val createdAtEpochMillis: Long,
) {
    constructor(
        id: String,
        name: String,
        referencePoses: Iterable<ReferencePose>,
        createdAtEpochMillis: Long,
    ) : this(
        id = id,
        name = name,
        referencePoses = immutableList(referencePoses),
        createdAtEpochMillis = createdAtEpochMillis,
    )

    init {
        requireNonBlank(id, "id")
        requireNonBlank(name, "name")
        require(referencePoses.size in MIN_REFERENCE_POSES..MAX_REFERENCE_POSES) {
            "referencePoses must contain $MIN_REFERENCE_POSES..$MAX_REFERENCE_POSES entries"
        }
        require(referencePoses.map(ReferencePose::id).distinct().size == referencePoses.size) {
            "reference pose IDs must be unique"
        }
        require(createdAtEpochMillis >= 0) { "createdAtEpochMillis must be nonnegative" }
    }

    companion object {
        const val MIN_REFERENCE_POSES = 3
        const val MAX_REFERENCE_POSES = 20
    }
}
