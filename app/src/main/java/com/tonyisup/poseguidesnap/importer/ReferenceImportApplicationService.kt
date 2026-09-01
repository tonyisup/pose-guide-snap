package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.ReferenceImportPolicy
import com.tonyisup.poseguidesnap.data.ReferenceImportReserveRejectionReason
import com.tonyisup.poseguidesnap.data.ReferenceImportToken

/** Raw caller values for one new reference-import allocation attempt. */
class ReferenceImportAllocationRequest(
    val shootId: String,
    val label: String,
) {
    override fun toString(): String = "ReferenceImportAllocationRequest(redacted)"
}

/** Checks Room-independent application admission before any identity or timeline is allocated. */
fun interface ReferenceImportAdmissionPort {
    fun check(shootId: String): ReferenceImportAdmissionResult
}

/** Allocates one opaque identity for one attempt. */
fun interface ReferenceImportTokenProvider {
    fun nextToken(): ReferenceImportToken
}

/** Allocates one pose identity for one attempt. */
fun interface ReferenceImportPoseIdProvider {
    fun nextPoseId(): String
}

/** Supplies all fifteen ordered ledger values for one attempt. */
fun interface ReferenceImportLedgerTimelineProvider {
    fun nextTimeline(): ReferenceImportLedgerTimeline
}

enum class ReferenceImportAdmissionBlockReason {
    UNKNOWN_SHOOT,
    SHOOT_DELETING,
    PLAYLIST_FULL,
    ACTIVE_SESSION,
    IMPORT_IN_PROGRESS,
    RECONCILIATION_REQUIRED,
    AUTHORITY_INCONSISTENT,
    ;

    override fun toString(): String = "ReferenceImportAdmissionBlockReason.$name"
}

sealed interface ReferenceImportAdmissionResult {
    data object Allowed : ReferenceImportAdmissionResult {
        override fun toString(): String = "ReferenceImportAdmissionResult.Allowed"
    }

    class Blocked(val reason: ReferenceImportAdmissionBlockReason) : ReferenceImportAdmissionResult {
        override fun toString(): String =
            "ReferenceImportAdmissionResult.Blocked(reason=${reason.name})"
    }
}

enum class ReferenceImportRetryAction {
    NONE,
    RETRY_ALLOCATION,
    RUN_RECONCILIATION_THEN_RETRY,
    ALLOCATE_NEW_ATTEMPT,
    ;

    override fun toString(): String = "ReferenceImportRetryAction.$name"
}

enum class ReferenceImportAllocationBlockReason {
    INVALID_REQUEST,
    UNKNOWN_SHOOT,
    SHOOT_DELETING,
    PLAYLIST_FULL,
    ACTIVE_SESSION,
    IMPORT_IN_PROGRESS,
    RECONCILIATION_REQUIRED,
    IDENTITY_UNAVAILABLE,
    AUTHORITY_INCONSISTENT,
    AUTHORITY_UNAVAILABLE,
    ;

    override fun toString(): String = "ReferenceImportAllocationBlockReason.$name"
}

sealed interface ReferenceImportAllocationResult {
    class Ready(val draft: ReferencePickerImportDraft) : ReferenceImportAllocationResult {
        override fun toString(): String = "ReferenceImportAllocationResult.Ready(redacted)"
    }

    class Blocked(
        val reason: ReferenceImportAllocationBlockReason,
        val retryAction: ReferenceImportRetryAction,
    ) : ReferenceImportAllocationResult {
        override fun toString(): String =
            "ReferenceImportAllocationResult.Blocked(" +
                "reason=${reason.name}, retryAction=${retryAction.name})"
    }
}

enum class ReferenceImportOutcomeStatus {
    CANCELLED,
    INVALID_SELECTION,
    SUCCEEDED,
    RESERVE_REJECTED_PLAYLIST_FULL,
    RESERVE_REJECTED_ACTIVE_SESSION,
    RESERVE_REJECTED_UNKNOWN_SHOOT,
    RESERVE_REJECTED_SHOOT_DELETING,
    RESERVE_REJECTED_UNRESOLVED_IMPORT,
    RESERVE_REJECTED_IDENTITY,
    RESERVE_REJECTED_AUTHORITY,
    VALIDATION_REJECTED,
    TERMINAL_REJECTED,
    RECONCILIATION_REQUIRED,
    AUTHORITY_UNAVAILABLE,
    ;

    override fun toString(): String = "ReferenceImportOutcomeStatus.$name"
}

/** Bounded editor-facing interpretation; no importer detail is retained. */
class ReferenceImportOutcome(
    val status: ReferenceImportOutcomeStatus,
    val retryAction: ReferenceImportRetryAction,
) {
    override fun toString(): String =
        "ReferenceImportOutcome(status=${status.name}, retryAction=${retryAction.name})"
}

/** Application boundary for allocating a picker draft and classifying its closed result. */
class ReferenceImportApplicationService(
    private val admission: ReferenceImportAdmissionPort,
    private val tokenProvider: ReferenceImportTokenProvider,
    private val poseIdProvider: ReferenceImportPoseIdProvider,
    private val timelineProvider: ReferenceImportLedgerTimelineProvider,
) {
    fun allocate(request: ReferenceImportAllocationRequest): ReferenceImportAllocationResult {
        val label = request.label.trim()
        if (
            !ReferenceImportPolicy.validateOwnershipIdentity(request.shootId) ||
            label.isEmpty() ||
            '\u0000' in label ||
            label.contains("content://", ignoreCase = true)
        ) {
            return blocked(
                ReferenceImportAllocationBlockReason.INVALID_REQUEST,
                ReferenceImportRetryAction.NONE,
            )
        }

        val admissionResult = try {
            admission.check(request.shootId)
        } catch (_: Exception) {
            return blocked(
                ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
            )
        }
        if (admissionResult is ReferenceImportAdmissionResult.Blocked) {
            return admissionResult.reason.toAllocationBlock()
        }

        val importToken = try {
            tokenProvider.nextToken()
        } catch (_: Exception) {
            return blocked(
                ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
            )
        }
        val poseId = try {
            poseIdProvider.nextPoseId()
        } catch (_: Exception) {
            return blocked(
                ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
            )
        }
        if (!ReferenceImportPolicy.validateOwnershipIdentity(poseId)) {
            return blocked(
                ReferenceImportAllocationBlockReason.IDENTITY_UNAVAILABLE,
                ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT,
            )
        }
        val timeline = try {
            timelineProvider.nextTimeline()
        } catch (_: Exception) {
            return blocked(
                ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
            )
        }

        return try {
            ReferenceImportAllocationResult.Ready(
                ReferencePickerImportDraft(
                    importToken = importToken,
                    shootId = request.shootId,
                    poseId = poseId,
                    label = label,
                    mirrorAllowed = true,
                    timeline = timeline,
                ),
            )
        } catch (_: IllegalArgumentException) {
            blocked(
                ReferenceImportAllocationBlockReason.IDENTITY_UNAVAILABLE,
                ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT,
            )
        }
    }

    fun classify(result: ReferencePickerResult): ReferenceImportOutcome = when (result) {
        ReferencePickerResult.Cancelled -> outcome(
            ReferenceImportOutcomeStatus.CANCELLED,
            ReferenceImportRetryAction.NONE,
        )
        ReferencePickerResult.InvalidSelection -> outcome(
            ReferenceImportOutcomeStatus.INVALID_SELECTION,
            ReferenceImportRetryAction.RETRY_ALLOCATION,
        )
        ReferencePickerResult.ReconciliationRequired -> reconciliationOutcome()
        is ReferencePickerResult.Completed -> classify(result.importResult)
    }

    override fun toString(): String = "ReferenceImportApplicationService(redacted)"

    private fun classify(result: ReferencePoseImportResult): ReferenceImportOutcome = when (result) {
        is ReferencePoseImportResult.Succeeded -> outcome(
            ReferenceImportOutcomeStatus.SUCCEEDED,
            ReferenceImportRetryAction.NONE,
        )
        is ReferencePoseImportResult.ReserveRejected -> classify(result.reason)
        is ReferencePoseImportResult.Rejected -> outcome(
            when (result.reason) {
                ReferencePoseImportRejectionReason.NO_PERSON,
                ReferencePoseImportRejectionReason.MULTIPLE_PEOPLE,
                ReferencePoseImportRejectionReason.LOW_COVERAGE,
                -> ReferenceImportOutcomeStatus.VALIDATION_REJECTED

                ReferencePoseImportRejectionReason.PUBLICATION_FAILED,
                ReferencePoseImportRejectionReason.ASSET_READY_REJECTED,
                ReferencePoseImportRejectionReason.ANALYZER_FAILED,
                ReferencePoseImportRejectionReason.COMMIT_REJECTED,
                ReferencePoseImportRejectionReason.COMMIT_BLOCKED,
                -> ReferenceImportOutcomeStatus.TERMINAL_REJECTED
            },
            ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT,
        )
        ReferencePoseImportResult.ReconciliationRequired -> reconciliationOutcome()
    }

    private fun classify(reason: ReferenceImportReserveRejectionReason): ReferenceImportOutcome =
        when (reason) {
            ReferenceImportReserveRejectionReason.PLAYLIST_FULL -> outcome(
                ReferenceImportOutcomeStatus.RESERVE_REJECTED_PLAYLIST_FULL,
                ReferenceImportRetryAction.NONE,
            )
            ReferenceImportReserveRejectionReason.ACTIVE_SESSION -> outcome(
                ReferenceImportOutcomeStatus.RESERVE_REJECTED_ACTIVE_SESSION,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
            )
            ReferenceImportReserveRejectionReason.UNKNOWN_SHOOT -> outcome(
                ReferenceImportOutcomeStatus.RESERVE_REJECTED_UNKNOWN_SHOOT,
                ReferenceImportRetryAction.NONE,
            )
            ReferenceImportReserveRejectionReason.SHOOT_NOT_ACTIVE -> outcome(
                ReferenceImportOutcomeStatus.RESERVE_REJECTED_SHOOT_DELETING,
                ReferenceImportRetryAction.NONE,
            )
            ReferenceImportReserveRejectionReason.UNRESOLVED_IMPORT_WORK -> outcome(
                ReferenceImportOutcomeStatus.RESERVE_REJECTED_UNRESOLVED_IMPORT,
                ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
            )
            ReferenceImportReserveRejectionReason.TOKEN_CONFLICT,
            ReferenceImportReserveRejectionReason.POSE_ID_CONFLICT,
            ReferenceImportReserveRejectionReason.POSE_ALREADY_EXISTS,
            -> outcome(
                ReferenceImportOutcomeStatus.RESERVE_REJECTED_IDENTITY,
                ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT,
            )
            ReferenceImportReserveRejectionReason.INVALID_TIMESTAMP,
            ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            -> outcome(
                ReferenceImportOutcomeStatus.RESERVE_REJECTED_AUTHORITY,
                ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
            )
        }

    private fun ReferenceImportAdmissionBlockReason.toAllocationBlock():
        ReferenceImportAllocationResult.Blocked = when (this) {
        ReferenceImportAdmissionBlockReason.UNKNOWN_SHOOT -> blocked(
            ReferenceImportAllocationBlockReason.UNKNOWN_SHOOT,
            ReferenceImportRetryAction.NONE,
        )
        ReferenceImportAdmissionBlockReason.SHOOT_DELETING -> blocked(
            ReferenceImportAllocationBlockReason.SHOOT_DELETING,
            ReferenceImportRetryAction.NONE,
        )
        ReferenceImportAdmissionBlockReason.PLAYLIST_FULL -> blocked(
            ReferenceImportAllocationBlockReason.PLAYLIST_FULL,
            ReferenceImportRetryAction.NONE,
        )
        ReferenceImportAdmissionBlockReason.ACTIVE_SESSION -> blocked(
            ReferenceImportAllocationBlockReason.ACTIVE_SESSION,
            ReferenceImportRetryAction.RETRY_ALLOCATION,
        )
        ReferenceImportAdmissionBlockReason.IMPORT_IN_PROGRESS -> blocked(
            ReferenceImportAllocationBlockReason.IMPORT_IN_PROGRESS,
            ReferenceImportRetryAction.RETRY_ALLOCATION,
        )
        ReferenceImportAdmissionBlockReason.RECONCILIATION_REQUIRED -> blocked(
            ReferenceImportAllocationBlockReason.RECONCILIATION_REQUIRED,
            ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
        )
        ReferenceImportAdmissionBlockReason.AUTHORITY_INCONSISTENT -> blocked(
            ReferenceImportAllocationBlockReason.AUTHORITY_INCONSISTENT,
            ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
        )
    }

    private fun reconciliationOutcome(): ReferenceImportOutcome = outcome(
        ReferenceImportOutcomeStatus.RECONCILIATION_REQUIRED,
        ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
    )

    private fun blocked(
        reason: ReferenceImportAllocationBlockReason,
        retryAction: ReferenceImportRetryAction,
    ): ReferenceImportAllocationResult.Blocked =
        ReferenceImportAllocationResult.Blocked(reason, retryAction)

    private fun outcome(
        status: ReferenceImportOutcomeStatus,
        retryAction: ReferenceImportRetryAction,
    ): ReferenceImportOutcome = ReferenceImportOutcome(status, retryAction)
}
