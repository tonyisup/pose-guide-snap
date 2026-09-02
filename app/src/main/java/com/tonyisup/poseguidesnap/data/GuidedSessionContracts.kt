package com.tonyisup.poseguidesnap.data

import java.util.Collections

enum class GuidedSessionLifecycle {
    ACTIVE,
    COMPLETED,
}

enum class GuidedCaptureAttemptState {
    REGISTERED,
    CAPTURING,
    CONFIRMED,
}

enum class GuidedCaptureTrigger {
    MANUAL,
    AUTOMATIC,
}

enum class GuidedExportState {
    PENDING,
    CLAIMED,
    CREATE_STARTED,
    URI_KNOWN,
    EXPORTED,
    AMBIGUOUS,
    CANCELLED,
}

data class GuidedBlockingAttemptSummary(
    val attemptNumber: Long,
    val poseIndex: Int,
    val state: GuidedCaptureAttemptState,
    val deletionGeneration: Long,
    val commandToken: String,
    val poseId: String,
    val trigger: GuidedCaptureTrigger,
    val reconciliationRequired: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(isValidGuidedCaptureToken(commandToken)) { "blocking command token is invalid" }
        require(isSafeGuidedOwnershipIdentity(poseId)) { "blocking pose ID is invalid" }
        require(attemptNumber >= 0L) { "blocking attempt number must be nonnegative" }
        require(poseIndex >= 0) { "blocking pose index must be nonnegative" }
        require(state != GuidedCaptureAttemptState.CONFIRMED) {
            "confirmed attempts cannot block bootstrap"
        }
        require(deletionGeneration >= 0L) { "blocking generation must be nonnegative" }
        require(createdAtEpochMillis >= 0L) { "blocking created timestamp must be nonnegative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "blocking updated timestamp must not precede creation"
        }
    }

    override fun toString(): String = "GuidedBlockingAttemptSummary(redacted)"
}

class GuidedSessionSnapshot(
    val sessionId: String,
    val shootId: String,
    val lifecycle: GuidedSessionLifecycle,
    orderedPoseIds: Iterable<String>,
    val poseCount: Int,
    val currentPoseIndex: Int,
    val nextAttemptNumber: Long,
    val deletionGeneration: Long,
    val attemptCount: Int,
    val confirmedAttemptCount: Int,
    appliedReceiptTokens: Iterable<String>,
    val unresolvedExportCount: Int,
    val blockingAttempt: GuidedBlockingAttemptSummary?,
) {
    val orderedPoseIds: List<String> = immutableBootstrapList(orderedPoseIds)
    val appliedReceiptTokens: List<String> = immutableBootstrapList(appliedReceiptTokens)

    init {
        require(isSafeGuidedOwnershipIdentity(sessionId)) { "session ID must be safe" }
        require(isSafeGuidedOwnershipIdentity(shootId)) { "shoot ID must be safe" }
        require(poseCount > 0) { "pose count must be positive" }
        require(this.orderedPoseIds.size == poseCount) { "ordered pose IDs must match pose count" }
        require(this.orderedPoseIds.distinct().size == poseCount) {
            "ordered pose IDs must be unique"
        }
        require(this.orderedPoseIds.all(::isSafeGuidedOwnershipIdentity)) {
            "ordered pose IDs must be safe"
        }
        require(currentPoseIndex in 0 until poseCount) { "current pose must be in bounds" }
        require(nextAttemptNumber >= 0L) { "next attempt number must be nonnegative" }
        require(deletionGeneration >= 0L) { "deletion generation must be nonnegative" }
        require(attemptCount >= 0) { "attempt count must be nonnegative" }
        require(confirmedAttemptCount in 0..attemptCount) {
            "confirmed attempt count must be in bounds"
        }
        require(this.appliedReceiptTokens.size == confirmedAttemptCount) {
            "receipt tokens must match confirmed attempts"
        }
        require(this.appliedReceiptTokens.distinct().size == confirmedAttemptCount) {
            "receipt tokens must be unique"
        }
        require(this.appliedReceiptTokens.all(::isValidGuidedCaptureToken)) {
            "receipt tokens must be safe"
        }
        require(unresolvedExportCount >= 0) { "unresolved export count must be nonnegative" }
        require(attemptCount == confirmedAttemptCount + if (blockingAttempt == null) 0 else 1) {
            "attempt count must match confirmed and blocking authority"
        }
    }

    fun copy(
        sessionId: String = this.sessionId,
        shootId: String = this.shootId,
        lifecycle: GuidedSessionLifecycle = this.lifecycle,
        orderedPoseIds: Iterable<String> = this.orderedPoseIds,
        poseCount: Int = this.poseCount,
        currentPoseIndex: Int = this.currentPoseIndex,
        nextAttemptNumber: Long = this.nextAttemptNumber,
        deletionGeneration: Long = this.deletionGeneration,
        attemptCount: Int = this.attemptCount,
        confirmedAttemptCount: Int = this.confirmedAttemptCount,
        appliedReceiptTokens: Iterable<String> = this.appliedReceiptTokens,
        unresolvedExportCount: Int = this.unresolvedExportCount,
        blockingAttempt: GuidedBlockingAttemptSummary? = this.blockingAttempt,
    ): GuidedSessionSnapshot = GuidedSessionSnapshot(
        sessionId,
        shootId,
        lifecycle,
        orderedPoseIds,
        poseCount,
        currentPoseIndex,
        nextAttemptNumber,
        deletionGeneration,
        attemptCount,
        confirmedAttemptCount,
        appliedReceiptTokens,
        unresolvedExportCount,
        blockingAttempt,
    )

    override fun equals(other: Any?): Boolean =
        other is GuidedSessionSnapshot &&
            sessionId == other.sessionId &&
            shootId == other.shootId &&
            lifecycle == other.lifecycle &&
            orderedPoseIds == other.orderedPoseIds &&
            poseCount == other.poseCount &&
            currentPoseIndex == other.currentPoseIndex &&
            nextAttemptNumber == other.nextAttemptNumber &&
            deletionGeneration == other.deletionGeneration &&
            attemptCount == other.attemptCount &&
            confirmedAttemptCount == other.confirmedAttemptCount &&
            appliedReceiptTokens == other.appliedReceiptTokens &&
            unresolvedExportCount == other.unresolvedExportCount &&
            blockingAttempt == other.blockingAttempt

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + shootId.hashCode()
        result = 31 * result + lifecycle.hashCode()
        result = 31 * result + orderedPoseIds.hashCode()
        result = 31 * result + poseCount
        result = 31 * result + currentPoseIndex
        result = 31 * result + nextAttemptNumber.hashCode()
        result = 31 * result + deletionGeneration.hashCode()
        result = 31 * result + attemptCount
        result = 31 * result + confirmedAttemptCount
        result = 31 * result + appliedReceiptTokens.hashCode()
        result = 31 * result + unresolvedExportCount
        result = 31 * result + (blockingAttempt?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "GuidedSessionSnapshot(redacted)"
}

sealed interface GuidedSessionBootstrapResult {
    data class Ready(val snapshot: GuidedSessionSnapshot) : GuidedSessionBootstrapResult {
        init {
            require(snapshot.lifecycle == GuidedSessionLifecycle.ACTIVE)
            require(snapshot.blockingAttempt == null)
        }

        override fun toString(): String = "GuidedSessionBootstrapResult.Ready(redacted)"
    }

    data class Completed(val snapshot: GuidedSessionSnapshot) : GuidedSessionBootstrapResult {
        init {
            require(snapshot.lifecycle == GuidedSessionLifecycle.COMPLETED)
            require(snapshot.blockingAttempt == null)
        }

        override fun toString(): String = "GuidedSessionBootstrapResult.Completed(redacted)"
    }

    data class ReconciliationRequired(val snapshot: GuidedSessionSnapshot) :
        GuidedSessionBootstrapResult {
        init {
            require(snapshot.lifecycle == GuidedSessionLifecycle.ACTIVE)
            require(snapshot.blockingAttempt != null)
        }

        override fun toString(): String =
            "GuidedSessionBootstrapResult.ReconciliationRequired(redacted)"
    }

    data object UnknownSession : GuidedSessionBootstrapResult {
        override fun toString(): String = "GuidedSessionBootstrapResult.UnknownSession"
    }

    data class Rejected(val reason: GuidedSessionBootstrapRejectionReason) :
        GuidedSessionBootstrapResult {
        override fun toString(): String =
            "GuidedSessionBootstrapResult.Rejected(reason=${reason.name})"
    }
}

enum class GuidedSessionBootstrapRejectionReason {
    INVALID_REQUEST,
    ORPHANED_AUTHORITY,
    INVALID_SHOOT_AUTHORITY,
    INVALID_SESSION_AUTHORITY,
    INVALID_POSE_AUTHORITY,
    INVALID_ATTEMPT_AUTHORITY,
    INVALID_PRIVATE_OUTPUT_AUTHORITY,
    INVALID_RECEIPT_AUTHORITY,
    INVALID_OUTBOX_AUTHORITY,
    INVALID_EXPORT_AUTHORITY,
    UNSUPPORTED_LIFECYCLE,
    AUTHORITY_INCONSISTENT,
    AUTHORITY_UNAVAILABLE,
}

class GuidedSessionBootstrapRows(
    val shoot: GuidedShootAuthorityRow?,
    val session: GuidedSessionAuthorityRow?,
    poses: Iterable<GuidedPoseAuthorityRow> = emptyList(),
    attempts: Iterable<GuidedAttemptAuthorityRow> = emptyList(),
    privateOutputs: Iterable<GuidedPrivateOutputAuthorityRow> = emptyList(),
    receipts: Iterable<GuidedReceiptAuthorityRow> = emptyList(),
    outboxes: Iterable<GuidedOutboxAuthorityRow> = emptyList(),
    exportOutputs: Iterable<GuidedExportOutputAuthorityRow> = emptyList(),
) {
    val poses: List<GuidedPoseAuthorityRow> = immutableBootstrapList(poses)
    val attempts: List<GuidedAttemptAuthorityRow> = immutableBootstrapList(attempts)
    val privateOutputs: List<GuidedPrivateOutputAuthorityRow> = immutableBootstrapList(privateOutputs)
    val receipts: List<GuidedReceiptAuthorityRow> = immutableBootstrapList(receipts)
    val outboxes: List<GuidedOutboxAuthorityRow> = immutableBootstrapList(outboxes)
    val exportOutputs: List<GuidedExportOutputAuthorityRow> = immutableBootstrapList(exportOutputs)

    override fun toString(): String = "GuidedSessionBootstrapRows(redacted)"
}

data class GuidedShootAuthorityRow(
    val shootId: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lifecycleState: String,
    val deletionGeneration: Long,
) {
    override fun toString(): String = "GuidedShootAuthorityRow(redacted)"
}

data class GuidedSessionAuthorityRow(
    val sessionId: String,
    val shootId: String,
    val currentPoseIndex: Int,
    val nextAttemptNumber: Long,
    val lifecycleState: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    override fun toString(): String = "GuidedSessionAuthorityRow(redacted)"
}

data class GuidedPoseAuthorityRow(
    val shootId: String,
    val poseIndex: Int,
    val poseId: String,
    val label: String,
    val referenceAssetPath: String?,
    val mirrorAllowed: Boolean,
    val validationState: String,
    val detectorMetadata: String?,
    val modelMetadata: String?,
    val preprocessingMetadata: String?,
    val landmarkPayload: String?,
    val coordinateMetadata: String?,
) {
    override fun toString(): String = "GuidedPoseAuthorityRow(redacted)"
}

data class GuidedAttemptAuthorityRow(
    val commandToken: String,
    val sessionId: String,
    val poseId: String,
    val poseIndex: Int,
    val attemptNumber: Long,
    val triggerType: String,
    val lifecycleState: String,
    val reconciliationRequired: Boolean,
    val capturedDeletionGeneration: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val confirmedAtEpochMillis: Long?,
) {
    override fun toString(): String = "GuidedAttemptAuthorityRow(redacted)"
}

data class GuidedPrivateOutputAuthorityRow(
    val commandToken: String,
    val burstOrdinal: Int,
    val relativePath: String,
    val byteCount: Long,
    val durabilityState: String,
    val capturedAtEpochMillis: Long,
    val integrityMetadata: String?,
) {
    override fun toString(): String = "GuidedPrivateOutputAuthorityRow(redacted)"
}

data class GuidedReceiptAuthorityRow(
    val commandToken: String,
    val fromPoseIndex: Int,
    val toPoseIndex: Int?,
    val appliedDeletionGeneration: Long,
    val appliedAtEpochMillis: Long,
) {
    override fun toString(): String = "GuidedReceiptAuthorityRow(redacted)"
}

data class GuidedOutboxAuthorityRow(
    val commandToken: String,
    val lifecycleState: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val retryMetadata: String?,
) {
    override fun toString(): String = "GuidedOutboxAuthorityRow(redacted)"
}

data class GuidedExportOutputAuthorityRow(
    val commandToken: String,
    val burstOrdinal: Int,
    val targetCollectionUri: String,
    val targetVolume: String,
    val intendedDisplayName: String,
    val intendedRelativePath: String,
    val intendedMimeType: String,
    val lifecycleState: String,
    val claimToken: String?,
    val mediaUriString: String?,
    val ambiguityState: String,
    val deletionGeneration: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    override fun toString(): String = "GuidedExportOutputAuthorityRow(redacted)"
}

private fun <T> immutableBootstrapList(values: Iterable<T>): List<T> =
    Collections.unmodifiableList(values.toList())

private fun isSafeGuidedOwnershipIdentity(value: String): Boolean =
    ReferenceImportPolicy.validateOwnershipIdentity(value)

private fun isValidGuidedCaptureToken(value: String): Boolean = value.isNotBlank()
