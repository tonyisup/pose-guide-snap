package com.tonyisup.poseguidesnap.data

enum class ReferenceImportFileOperationStage {
    EXPECTING_RESERVATION,
    WRITING_TEMP,
    TEMP_SYNCED,
    FINAL_RENAME_PENDING_SYNC,
    FINAL_DURABLE,
    CLEANUP_REQUIRED,
    CLEANUP_PENDING_SYNC,
    CLEANED_DURABLE,
    QUARANTINE_REQUIRED,
    QUARANTINE_PENDING_SYNC,
    QUARANTINE_DURABLE,
}

enum class ReferenceImportFileFailureCode {
    RESERVATION_FAILED,
    WRITE_FAILED,
    FILE_SYNC_FAILED,
    RENAME_FAILED,
    DIRECTORY_SYNC_FAILED,
    DELETE_FAILED,
    STATE_MISMATCH,
    EVIDENCE_MISMATCH,
}

@ConsistentCopyVisibility
data class ReferenceImportFileOperationPaths private constructor(
    val relativeAssetPath: String,
    val relativeTempPath: String,
    val relativeQuarantinePath: String,
) {
    override fun toString(): String = "ReferenceImportFileOperationPaths(redacted)"

    companion object {
        fun forToken(importToken: ReferenceImportToken): ReferenceImportFileOperationPaths {
            val relativeAssetPath = ReferenceImportAssetPath.forToken(importToken)
            val fileName = relativeAssetPath.substringAfterLast('/')
            val digest = fileName.removeSuffix(".asset")
            return ReferenceImportFileOperationPaths(
                relativeAssetPath = relativeAssetPath,
                relativeTempPath = "reference-assets/assets/.$fileName.pending",
                relativeQuarantinePath = "reference-assets/quarantine/$digest.quarantined",
            )
        }
    }
}

data class ReferenceImportFileOperationSnapshot(
    val importToken: ReferenceImportToken,
    val paths: ReferenceImportFileOperationPaths,
    val stage: ReferenceImportFileOperationStage,
    val byteCount: Long?,
    val sha256: String?,
    val lastFailureCode: ReferenceImportFileFailureCode?,
    val reconciliationRequired: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(paths == ReferenceImportFileOperationPaths.forToken(importToken)) {
            "reference import file snapshot paths must match their deterministic identity"
        }
        require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis) {
            "reference import file snapshot timestamps must be ordered and nonnegative"
        }
        require(hasValidReferenceImportFileOperationEvidence(stage, byteCount, sha256)) {
            "reference import file snapshot evidence does not match its stage"
        }
        require(reconciliationRequired == (lastFailureCode != null)) {
            "reference import file snapshot reconciliation state must be complete"
        }
    }

    override fun toString(): String = "ReferenceImportFileOperationSnapshot(redacted)"
}

data class ReferenceImportFileAdvanceRequest(
    val importToken: ReferenceImportToken,
    val expectedStage: ReferenceImportFileOperationStage,
    val expectedUpdatedAtEpochMillis: Long,
    val targetStage: ReferenceImportFileOperationStage,
    val byteCount: Long?,
    val sha256: String?,
    val transitionedAtEpochMillis: Long,
) {
    override fun toString(): String = "ReferenceImportFileAdvanceRequest(redacted)"
}

data class ReferenceImportFileReconciliationRequest(
    val importToken: ReferenceImportToken,
    val expectedStage: ReferenceImportFileOperationStage,
    val expectedUpdatedAtEpochMillis: Long,
    val failureCode: ReferenceImportFileFailureCode,
    val markedAtEpochMillis: Long,
) {
    override fun toString(): String = "ReferenceImportFileReconciliationRequest(redacted)"
}

data class ReferenceImportFileReconciliationResolutionRequest(
    val importToken: ReferenceImportToken,
    val expectedStage: ReferenceImportFileOperationStage,
    val expectedUpdatedAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "ReferenceImportFileReconciliationResolutionRequest(redacted)"
}

enum class ReferenceImportFileJournalRejectionReason {
    INVALID_TIMESTAMP,
    UNKNOWN_OPERATION,
    ILLEGAL_TRANSITION,
    EVIDENCE_MISMATCH,
    STALE_SNAPSHOT,
    CONTRADICTORY_STATE,
    PERSISTED_STATE_INVALID,
}

sealed interface ReferenceImportFileJournalResult {
    data class Applied(val snapshot: ReferenceImportFileOperationSnapshot) :
        ReferenceImportFileJournalResult {
        override fun toString(): String = "ReferenceImportFileJournalResult.Applied(redacted)"
    }

    data class Idempotent(val snapshot: ReferenceImportFileOperationSnapshot) :
        ReferenceImportFileJournalResult {
        override fun toString(): String = "ReferenceImportFileJournalResult.Idempotent(redacted)"
    }

    data class Rejected(val reason: ReferenceImportFileJournalRejectionReason) :
        ReferenceImportFileJournalResult {
        override fun toString(): String =
            "ReferenceImportFileJournalResult.Rejected(reason=${reason.name})"
    }
}

internal object ReferenceImportFileTransitionPolicy {
    private val legalTransitions = setOf(
        ReferenceImportFileOperationStage.EXPECTING_RESERVATION to
            ReferenceImportFileOperationStage.WRITING_TEMP,
        ReferenceImportFileOperationStage.WRITING_TEMP to
            ReferenceImportFileOperationStage.TEMP_SYNCED,
        ReferenceImportFileOperationStage.TEMP_SYNCED to
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
        ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC to
            ReferenceImportFileOperationStage.FINAL_DURABLE,
        ReferenceImportFileOperationStage.TEMP_SYNCED to
            ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
        ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC to
            ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
        ReferenceImportFileOperationStage.FINAL_DURABLE to
            ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
        ReferenceImportFileOperationStage.QUARANTINE_REQUIRED to
            ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
        ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC to
            ReferenceImportFileOperationStage.QUARANTINE_DURABLE,
        ReferenceImportFileOperationStage.EXPECTING_RESERVATION to
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
        ReferenceImportFileOperationStage.WRITING_TEMP to
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
        ReferenceImportFileOperationStage.TEMP_SYNCED to
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
        ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC to
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
        ReferenceImportFileOperationStage.FINAL_DURABLE to
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
        ReferenceImportFileOperationStage.QUARANTINE_REQUIRED to
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
        ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC to
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
        ReferenceImportFileOperationStage.CLEANUP_REQUIRED to
            ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC,
        ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC to
            ReferenceImportFileOperationStage.CLEANED_DURABLE,
    )

    fun advance(
        source: ReferenceImportFileOperationSnapshot,
        request: ReferenceImportFileAdvanceRequest,
    ): ReferenceImportFileJournalResult {
        if (
            request.importToken != source.importToken ||
            request.expectedStage != source.stage ||
            request.expectedUpdatedAtEpochMillis != source.updatedAtEpochMillis
        ) {
            return rejected(ReferenceImportFileJournalRejectionReason.STALE_SNAPSHOT)
        }
        if (
            request.expectedUpdatedAtEpochMillis < 0L ||
            request.transitionedAtEpochMillis <= request.expectedUpdatedAtEpochMillis
        ) {
            return rejected(ReferenceImportFileJournalRejectionReason.INVALID_TIMESTAMP)
        }
        if ((source.stage to request.targetStage) !in legalTransitions) {
            return rejected(ReferenceImportFileJournalRejectionReason.ILLEGAL_TRANSITION)
        }

        val expectedEvidence = when (request.targetStage) {
            ReferenceImportFileOperationStage.WRITING_TEMP,
            ReferenceImportFileOperationStage.CLEANED_DURABLE,
            -> null to null
            ReferenceImportFileOperationStage.TEMP_SYNCED -> request.byteCount to request.sha256
            else -> source.byteCount to source.sha256
        }
        if (
            request.byteCount != expectedEvidence.first ||
            request.sha256 != expectedEvidence.second ||
            !hasValidReferenceImportFileOperationEvidence(
                request.targetStage,
                request.byteCount,
                request.sha256,
            )
        ) {
            return rejected(ReferenceImportFileJournalRejectionReason.EVIDENCE_MISMATCH)
        }

        return ReferenceImportFileJournalResult.Applied(
            source.copy(
                stage = request.targetStage,
                byteCount = request.byteCount,
                sha256 = request.sha256,
                lastFailureCode = null,
                reconciliationRequired = false,
                updatedAtEpochMillis = request.transitionedAtEpochMillis,
            ),
        )
    }

    fun isLegalTransition(
        sourceStage: ReferenceImportFileOperationStage,
        targetStage: ReferenceImportFileOperationStage,
    ): Boolean = (sourceStage to targetStage) in legalTransitions

    private fun rejected(reason: ReferenceImportFileJournalRejectionReason) =
        ReferenceImportFileJournalResult.Rejected(reason)
}

internal fun hasValidReferenceImportFileOperationEvidence(
    stage: ReferenceImportFileOperationStage,
    byteCount: Long?,
    sha256: String?,
): Boolean {
    val hasByteCount = byteCount != null
    val hasSha256 = sha256 != null
    if (hasByteCount != hasSha256) {
        return false
    }
    if (hasByteCount && (byteCount <= 0L || !sha256.isCanonicalSha256())) {
        return false
    }

    return when (stage) {
        // No complete, fsynced source identity exists at these stages. CLEANED_DURABLE
        // deliberately clears evidence after synced deletion and exact absence reobservation.
        ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
        ReferenceImportFileOperationStage.WRITING_TEMP,
        ReferenceImportFileOperationStage.CLEANED_DURABLE,
        -> !hasByteCount

        // Cleanup may be selected before or after TEMP_SYNCED, so evidence is optional as a
        // pair while exact source paths may still need verification or deletion.
        ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
        ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC,
        -> true

        // These stages retain a complete source identity and therefore require fsynced evidence.
        ReferenceImportFileOperationStage.TEMP_SYNCED,
        ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
        ReferenceImportFileOperationStage.FINAL_DURABLE,
        ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
        ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
        ReferenceImportFileOperationStage.QUARANTINE_DURABLE,
        -> hasByteCount
    }
}

private fun String?.isCanonicalSha256(): Boolean =
    this != null && length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }
