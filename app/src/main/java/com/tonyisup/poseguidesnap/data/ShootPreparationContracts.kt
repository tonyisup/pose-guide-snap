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

class ShootSummaryPage(
    items: Iterable<ShootSummary>,
    val hasMore: Boolean,
) {
    val items: List<ShootSummary> = immutableProjectionList(items)

    override fun toString(): String = "ShootSummaryPage(redacted)"
}

internal class ShootPageRequest(
    val limit: Int,
    val offset: Int,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "shoot page limit must be within bounds" }
        require(offset >= 0) { "shoot page offset must be nonnegative" }
    }

    val queryLimit: Int = limit + 1

    internal companion object {
        const val MAX_LIMIT = 100
    }
}

internal fun <R> projectShootSummaryPage(
    rows: List<R>,
    request: ShootPageRequest,
    mapRow: (R) -> ShootSummary,
): ShootSummaryPage {
    check(rows.size <= request.queryLimit) { "shoot page query exceeded its requested bound" }
    return ShootSummaryPage(
        items = rows.take(request.limit).map(mapRow),
        hasMore = rows.size > request.limit,
    )
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

enum class ShootStartInvalidReason {
    INVALID_SHOOT_ID,
    INVALID_SESSION_ID,
    INVALID_TIMESTAMP,
}

enum class ShootStartIneligibleReason {
    TOO_FEW_VALIDATED_REFERENCES,
}

sealed interface ShootStartResult {
    data object Started : ShootStartResult {
        override fun toString(): String = "ShootStartResult.Started"
    }

    data object AlreadyStarted : ShootStartResult {
        override fun toString(): String = "ShootStartResult.AlreadyStarted"
    }

    data class InvalidRequest(val reason: ShootStartInvalidReason) : ShootStartResult {
        override fun toString(): String =
            "ShootStartResult.InvalidRequest(reason=${reason.name})"
    }

    data object UnknownShoot : ShootStartResult {
        override fun toString(): String = "ShootStartResult.UnknownShoot"
    }

    data object ShootDeleting : ShootStartResult {
        override fun toString(): String = "ShootStartResult.ShootDeleting"
    }

    data class IneligiblePlaylist(val reason: ShootStartIneligibleReason) : ShootStartResult {
        override fun toString(): String =
            "ShootStartResult.IneligiblePlaylist(reason=${reason.name})"
    }

    data object UnresolvedImportWork : ShootStartResult {
        override fun toString(): String = "ShootStartResult.UnresolvedImportWork"
    }

    data object ActiveSessionConflict : ShootStartResult {
        override fun toString(): String = "ShootStartResult.ActiveSessionConflict"
    }

    data object SessionIdentityConflict : ShootStartResult {
        override fun toString(): String = "ShootStartResult.SessionIdentityConflict"
    }

    data object StaleOrConflictingReplay : ShootStartResult {
        override fun toString(): String = "ShootStartResult.StaleOrConflictingReplay"
    }

    data object AuthorityInconsistent : ShootStartResult {
        override fun toString(): String = "ShootStartResult.AuthorityInconsistent"
    }
}

internal sealed interface ShootStartRequestValidation {
    data class Valid(
        val shootId: String,
        val sessionId: String,
        val startedAtEpochMillis: Long,
    ) : ShootStartRequestValidation {
        override fun toString(): String = "ShootStartRequestValidation.Valid(redacted)"
    }

    data class Invalid(val result: ShootStartResult) : ShootStartRequestValidation {
        override fun toString(): String = "ShootStartRequestValidation.Invalid(redacted)"
    }
}

internal enum class ShootStartPlaylistDecision {
    ELIGIBLE,
    INELIGIBLE_TOO_FEW,
    AUTHORITY_INCONSISTENT,
}

internal enum class ShootStartIdentityDecision {
    ABSENT,
    OWNED,
    SESSION_IDENTITY_CONFLICT,
    AUTHORITY_INCONSISTENT,
}

internal enum class ShootStartSessionDecision {
    ELIGIBLE,
    ALREADY_STARTED,
    ACTIVE_SESSION_CONFLICT,
    SESSION_IDENTITY_CONFLICT,
    STALE_OR_CONFLICTING_REPLAY,
    AUTHORITY_INCONSISTENT,
}

internal data class ShootStartSessionSnapshot(
    val sessionId: String,
    val shootId: String,
    val currentPoseIndex: Int,
    val nextAttemptNumber: Long,
    val lifecycleState: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    override fun toString(): String = "ShootStartSessionSnapshot(redacted)"
}

internal object ShootStartPolicy {
    fun validateRequest(
        shootId: String,
        sessionId: String,
        startedAtEpochMillis: Long,
    ): ShootStartRequestValidation {
        if (!ReferenceImportPolicy.validateOwnershipIdentity(shootId)) {
            return invalid(ShootStartInvalidReason.INVALID_SHOOT_ID)
        }
        if (!ReferenceImportPolicy.validateOwnershipIdentity(sessionId)) {
            return invalid(ShootStartInvalidReason.INVALID_SESSION_ID)
        }
        if (startedAtEpochMillis < 0L) {
            return invalid(ShootStartInvalidReason.INVALID_TIMESTAMP)
        }
        return ShootStartRequestValidation.Valid(shootId, sessionId, startedAtEpochMillis)
    }

    fun classifyPlaylistCardinality(referenceCount: Long): ShootStartPlaylistDecision =
        when {
            referenceCount < 0L || referenceCount > Shoot.MAX_REFERENCE_POSES.toLong() ->
                ShootStartPlaylistDecision.AUTHORITY_INCONSISTENT
            referenceCount < Shoot.MIN_REFERENCE_POSES.toLong() ->
                ShootStartPlaylistDecision.INELIGIBLE_TOO_FEW
            else -> ShootStartPlaylistDecision.ELIGIBLE
        }

    fun classifySessionIdentity(
        request: ShootStartRequestValidation.Valid,
        exactSession: ShootStartSessionSnapshot?,
    ): ShootStartIdentityDecision {
        if (
            !ReferenceImportPolicy.validateOwnershipIdentity(request.shootId) ||
            !ReferenceImportPolicy.validateOwnershipIdentity(request.sessionId) ||
            request.startedAtEpochMillis < 0L
        ) {
            return ShootStartIdentityDecision.AUTHORITY_INCONSISTENT
        }
        if (exactSession == null) return ShootStartIdentityDecision.ABSENT
        if (!exactSession.hasCoherentAuthority()) {
            return ShootStartIdentityDecision.AUTHORITY_INCONSISTENT
        }
        if (exactSession.sessionId != request.sessionId) {
            return ShootStartIdentityDecision.AUTHORITY_INCONSISTENT
        }
        return if (exactSession.shootId == request.shootId) {
            ShootStartIdentityDecision.OWNED
        } else {
            ShootStartIdentityDecision.SESSION_IDENTITY_CONFLICT
        }
    }

    fun classifySession(
        request: ShootStartRequestValidation.Valid,
        exactSession: ShootStartSessionSnapshot?,
        activeSessionCount: Int,
    ): ShootStartSessionDecision {
        when (classifySessionIdentity(request, exactSession)) {
            ShootStartIdentityDecision.AUTHORITY_INCONSISTENT ->
                return ShootStartSessionDecision.AUTHORITY_INCONSISTENT
            ShootStartIdentityDecision.SESSION_IDENTITY_CONFLICT ->
                return ShootStartSessionDecision.SESSION_IDENTITY_CONFLICT
            ShootStartIdentityDecision.ABSENT,
            ShootStartIdentityDecision.OWNED,
            -> Unit
        }
        if (activeSessionCount !in 0..1) {
            return ShootStartSessionDecision.AUTHORITY_INCONSISTENT
        }
        if (exactSession == null) {
            return if (activeSessionCount == 0) {
                ShootStartSessionDecision.ELIGIBLE
            } else {
                ShootStartSessionDecision.ACTIVE_SESSION_CONFLICT
            }
        }
        if (exactSession.lifecycleState == ACTIVE_SESSION && activeSessionCount == 0) {
            return ShootStartSessionDecision.AUTHORITY_INCONSISTENT
        }
        return if (
            exactSession.currentPoseIndex == 0 &&
            exactSession.nextAttemptNumber == 0L &&
            exactSession.lifecycleState == ACTIVE_SESSION &&
            exactSession.createdAtEpochMillis == request.startedAtEpochMillis &&
            exactSession.updatedAtEpochMillis == request.startedAtEpochMillis
        ) {
            ShootStartSessionDecision.ALREADY_STARTED
        } else {
            ShootStartSessionDecision.STALE_OR_CONFLICTING_REPLAY
        }
    }

    private fun ShootStartSessionSnapshot.hasCoherentAuthority(): Boolean =
        ReferenceImportPolicy.validateOwnershipIdentity(sessionId) &&
            ReferenceImportPolicy.validateOwnershipIdentity(shootId) &&
            currentPoseIndex >= 0 &&
            nextAttemptNumber >= 0L &&
            lifecycleState in SESSION_LIFECYCLES &&
            createdAtEpochMillis >= 0L &&
            updatedAtEpochMillis >= createdAtEpochMillis

    private fun invalid(reason: ShootStartInvalidReason): ShootStartRequestValidation =
        ShootStartRequestValidation.Invalid(ShootStartResult.InvalidRequest(reason))

    private const val ACTIVE_SESSION = "ACTIVE"
    private val SESSION_LIFECYCLES = setOf(ACTIVE_SESSION, "COMPLETED")
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
