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
    RECONCILIATION_REQUIRED,
    REJECTED_QUARANTINED,
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

enum class ShootReorderInvalidReason {
    INVALID_SHOOT_ID,
    INVALID_POSE_ID,
    INVALID_TIMESTAMP,
    INVALID_CARDINALITY,
    DUPLICATE_POSE_ID,
    ORDER_MISMATCH,
}

sealed interface ShootReorderResult {
    data object Reordered : ShootReorderResult {
        override fun toString(): String = "ShootReorderResult.Reordered"
    }

    data object AlreadyOrdered : ShootReorderResult {
        override fun toString(): String = "ShootReorderResult.AlreadyOrdered"
    }

    data class InvalidRequest(val reason: ShootReorderInvalidReason) : ShootReorderResult {
        override fun toString(): String =
            "ShootReorderResult.InvalidRequest(reason=${reason.name})"
    }

    data object UnknownShoot : ShootReorderResult {
        override fun toString(): String = "ShootReorderResult.UnknownShoot"
    }

    data object ShootDeleting : ShootReorderResult {
        override fun toString(): String = "ShootReorderResult.ShootDeleting"
    }

    data object ActiveSession : ShootReorderResult {
        override fun toString(): String = "ShootReorderResult.ActiveSession"
    }

    data object UnresolvedImportWork : ShootReorderResult {
        override fun toString(): String = "ShootReorderResult.UnresolvedImportWork"
    }

    data object StaleTimestamp : ShootReorderResult {
        override fun toString(): String = "ShootReorderResult.StaleTimestamp"
    }

    data object AuthorityInconsistent : ShootReorderResult {
        override fun toString(): String = "ShootReorderResult.AuthorityInconsistent"
    }
}

internal sealed interface ShootReorderRequestValidation {
    class Valid(
        val shootId: String,
        orderedPoseIds: Iterable<String>,
        val reorderedAtEpochMillis: Long,
    ) : ShootReorderRequestValidation {
        val orderedPoseIds: List<String> = immutableProjectionList(orderedPoseIds)

        override fun toString(): String = "ShootReorderRequestValidation.Valid(redacted)"
    }

    data class Invalid(val result: ShootReorderResult) : ShootReorderRequestValidation {
        override fun toString(): String = "ShootReorderRequestValidation.Invalid(redacted)"
    }
}

internal enum class ShootReorderOrderDecision {
    MUTATE,
    ALREADY_ORDERED,
    INVALID_ORDER,
    STALE_TIMESTAMP,
    AUTHORITY_INCONSISTENT,
}

internal object ShootReorderPolicy {
    fun validateRequest(
        shootId: String,
        orderedPoseIds: List<String>,
        reorderedAtEpochMillis: Long,
    ): ShootReorderRequestValidation {
        val poseIdSnapshot = ArrayList(orderedPoseIds)
        if (!ReferenceImportPolicy.validateOwnershipIdentity(shootId)) {
            return invalid(ShootReorderInvalidReason.INVALID_SHOOT_ID)
        }
        if (reorderedAtEpochMillis < 0L) {
            return invalid(ShootReorderInvalidReason.INVALID_TIMESTAMP)
        }
        if (poseIdSnapshot.size !in 2..Shoot.MAX_REFERENCE_POSES) {
            return invalid(ShootReorderInvalidReason.INVALID_CARDINALITY)
        }
        if (poseIdSnapshot.any { poseId ->
                !ReferenceImportPolicy.validateOwnershipIdentity(poseId)
            }
        ) {
            return invalid(ShootReorderInvalidReason.INVALID_POSE_ID)
        }
        if (poseIdSnapshot.distinct().size != poseIdSnapshot.size) {
            return invalid(ShootReorderInvalidReason.DUPLICATE_POSE_ID)
        }
        return ShootReorderRequestValidation.Valid(
            shootId = shootId,
            orderedPoseIds = poseIdSnapshot,
            reorderedAtEpochMillis = reorderedAtEpochMillis,
        )
    }

    fun classifyValidatedOrder(
        currentPoseIds: List<String>,
        orderedPoseIds: List<String>,
        persistedUpdatedAtEpochMillis: Long,
        reorderedAtEpochMillis: Long,
    ): ShootReorderOrderDecision {
        if (
            persistedUpdatedAtEpochMillis < 0L ||
            reorderedAtEpochMillis < 0L ||
            currentPoseIds.size !in 2..Shoot.MAX_REFERENCE_POSES ||
            currentPoseIds.any { poseId ->
                !ReferenceImportPolicy.validateOwnershipIdentity(poseId)
            } ||
            currentPoseIds.distinct().size != currentPoseIds.size
        ) {
            return ShootReorderOrderDecision.AUTHORITY_INCONSISTENT
        }
        if (
            orderedPoseIds.size != currentPoseIds.size ||
            orderedPoseIds.distinct().size != orderedPoseIds.size ||
            orderedPoseIds.toSet() != currentPoseIds.toSet()
        ) {
            return ShootReorderOrderDecision.INVALID_ORDER
        }
        if (orderedPoseIds == currentPoseIds) {
            return if (reorderedAtEpochMillis < persistedUpdatedAtEpochMillis) {
                ShootReorderOrderDecision.STALE_TIMESTAMP
            } else {
                ShootReorderOrderDecision.ALREADY_ORDERED
            }
        }
        return if (reorderedAtEpochMillis <= persistedUpdatedAtEpochMillis) {
            ShootReorderOrderDecision.STALE_TIMESTAMP
        } else {
            ShootReorderOrderDecision.MUTATE
        }
    }

    private fun invalid(reason: ShootReorderInvalidReason): ShootReorderRequestValidation =
        ShootReorderRequestValidation.Invalid(ShootReorderResult.InvalidRequest(reason))
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
