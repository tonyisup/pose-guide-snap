package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.model.Shoot
import java.util.Collections

enum class ShootCreateRejectionReason {
    INVALID_ID,
    INVALID_NAME,
    INVALID_TIMESTAMP,
    ID_CONFLICT,
    AUTHORITY_INCONSISTENT,
}

sealed interface ShootCreateResult {
    data class Created(val summary: ShootSummary) : ShootCreateResult {
        override fun toString(): String = "ShootCreateResult.Created(redacted)"
    }

    data class AlreadyExists(val summary: ShootSummary) : ShootCreateResult {
        override fun toString(): String = "ShootCreateResult.AlreadyExists(redacted)"
    }

    data class Rejected(val reason: ShootCreateRejectionReason) : ShootCreateResult {
        override fun toString(): String = "ShootCreateResult.Rejected(redacted)"
    }
}

enum class ShootPreparationLifecycle {
    ACTIVE,
    DELETING,
}

class ShootSummary(
    val shootId: String,
    val name: String,
    val validatedReferenceCount: Int,
    val lifecycle: ShootPreparationLifecycle,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireProjectionText(shootId, "shoot ID")
        requireProjectionText(name, "shoot name")
        require(validatedReferenceCount in 0..Shoot.MAX_REFERENCE_POSES) {
            "validated reference count must be within preparation bounds"
        }
        require(updatedAtEpochMillis >= 0L) { "shoot summary timestamp must be nonnegative" }
    }

    override fun toString(): String = "ShootSummary(redacted)"
}

class ValidatedReferenceSummary(
    val poseId: String,
    val poseIndex: Int,
    val label: String,
    val mirrorAllowed: Boolean,
) {
    init {
        requireProjectionText(poseId, "pose ID")
        require(poseIndex >= 0) { "pose index must be nonnegative" }
        requireProjectionText(label, "pose label")
    }

    override fun toString(): String = "ValidatedReferenceSummary(redacted)"
}

enum class ImportWorkStatus {
    IN_PROGRESS,
    NEEDS_ATTENTION,
}

class ImportWorkSummary(
    val status: ImportWorkStatus,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis) {
            "import work timestamps must be ordered and nonnegative"
        }
    }

    override fun toString(): String = "ImportWorkSummary(redacted)"
}

class ShootEditorSnapshot(
    val shootId: String,
    val name: String,
    val lifecycle: ShootPreparationLifecycle,
    validatedReferences: Iterable<ValidatedReferenceSummary>,
    importWork: Iterable<ImportWorkSummary>,
    val updatedAtEpochMillis: Long,
) {
    val validatedReferences: List<ValidatedReferenceSummary> =
        immutableProjectionList(validatedReferences)
    val importWork: List<ImportWorkSummary> = immutableProjectionList(importWork)

    init {
        requireProjectionText(shootId, "shoot ID")
        requireProjectionText(name, "shoot name")
        require(this.validatedReferences.size <= Shoot.MAX_REFERENCE_POSES) {
            "validated references exceed preparation bounds"
        }
        require(
            this.validatedReferences.map(ValidatedReferenceSummary::poseId).distinct().size ==
                this.validatedReferences.size,
        ) {
            "validated reference pose IDs must be unique"
        }
        require(this.validatedReferences.indices.all { index ->
            this.validatedReferences[index].poseIndex == index
        }) { "validated references must have exact contiguous order" }
        require(updatedAtEpochMillis >= 0L) { "editor timestamp must be nonnegative" }
        require(this.importWork.all { work ->
            updatedAtEpochMillis >= work.createdAtEpochMillis &&
                updatedAtEpochMillis >= work.updatedAtEpochMillis
        }) { "editor timestamp must not precede import work" }
    }

    override fun toString(): String = "ShootEditorSnapshot(redacted)"
}

private fun requireProjectionText(value: String, field: String) {
    require(
        value.isNotBlank() &&
            '\u0000' !in value &&
            !value.contains("content://", ignoreCase = true),
    ) { "$field must be nonblank, NUL-free, and provider-URI-free" }
}

private fun <T> immutableProjectionList(values: Iterable<T>): List<T> =
    Collections.unmodifiableList(ArrayList<T>().apply { addAll(values) })
