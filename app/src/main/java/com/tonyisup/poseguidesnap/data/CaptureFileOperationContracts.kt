package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val INVALID_CAPTURE_FILE_TOKEN_ENCODING_MESSAGE =
    "capture file operation token encoding is invalid"

enum class CaptureFileOperationStage {
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

enum class CaptureFileFailureCode {
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
data class CaptureFileOperationPaths private constructor(
    val relativeFinalPath: String,
    val relativeTempPath: String,
    val relativeQuarantinePath: String,
) {
    override fun toString(): String = "CaptureFileOperationPaths(redacted)"

    companion object {
        fun forIdentity(identity: PrivateOutputIdentity): CaptureFileOperationPaths {
            require(isWellFormedUtf16(identity.token.value)) {
                INVALID_CAPTURE_FILE_TOKEN_ENCODING_MESSAGE
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.token.value.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val paths = CaptureFileOperationPaths(
                relativeFinalPath = "capture-candidates/$digest-${identity.ordinal}.jpg",
                relativeTempPath = "capture-candidates/.$digest-${identity.ordinal}.jpg.pending",
                relativeQuarantinePath = "capture-quarantine/$digest-${identity.ordinal}.quarantined",
            )
            require(
                isNormalizedPrivateRelativePath(paths.relativeFinalPath) &&
                    isNormalizedPrivateRelativePath(paths.relativeTempPath) &&
                    isNormalizedPrivateRelativePath(paths.relativeQuarantinePath),
            ) { "capture file operation path is invalid" }
            return paths
        }
    }
}

data class CaptureFileOperationSnapshot(
    val identity: PrivateOutputIdentity,
    val paths: CaptureFileOperationPaths,
    val stage: CaptureFileOperationStage,
    val byteCount: Long?,
    val sha256: String?,
    val capturedAtEpochMillis: Long?,
    val lastFailureCode: CaptureFileFailureCode?,
    val reconciliationRequired: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(paths == CaptureFileOperationPaths.forIdentity(identity)) {
            "capture file operation paths must match their deterministic identity"
        }
        require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis) {
            "capture file operation timestamps must be ordered and nonnegative"
        }
        require(
            capturedAtEpochMillis == null ||
                capturedAtEpochMillis in createdAtEpochMillis..updatedAtEpochMillis,
        ) { "capture file operation capture timestamp must be within journal timestamps" }
        require(hasValidCaptureFileOperationEvidence(stage, byteCount, sha256, capturedAtEpochMillis)) {
            "capture file operation evidence does not match its stage"
        }
        require(reconciliationRequired == (lastFailureCode != null)) {
            "capture file operation reconciliation state must be complete"
        }
    }

    override fun toString(): String = "CaptureFileOperationSnapshot(redacted)"
}

data class CaptureFileAdvanceRequest(
    val identity: PrivateOutputIdentity,
    val expectedStage: CaptureFileOperationStage,
    val expectedUpdatedAtEpochMillis: Long,
    val targetStage: CaptureFileOperationStage,
    val byteCount: Long?,
    val sha256: String?,
    val capturedAtEpochMillis: Long?,
    val transitionedAtEpochMillis: Long,
) {
    override fun toString(): String = "CaptureFileAdvanceRequest(redacted)"
}

data class CaptureFileReconciliationRequest(
    val identity: PrivateOutputIdentity,
    val expectedStage: CaptureFileOperationStage,
    val expectedUpdatedAtEpochMillis: Long,
    val failureCode: CaptureFileFailureCode,
    val markedAtEpochMillis: Long,
) {
    override fun toString(): String = "CaptureFileReconciliationRequest(redacted)"
}

data class CaptureFileReconciliationResolutionRequest(
    val identity: PrivateOutputIdentity,
    val expectedStage: CaptureFileOperationStage,
    val expectedUpdatedAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long,
) {
    override fun toString(): String = "CaptureFileReconciliationResolutionRequest(redacted)"
}

enum class CaptureFileJournalRejectionReason {
    INVALID_TIMESTAMP,
    INVALID_EVIDENCE,
    UNKNOWN_OPERATION,
    WRONG_ATTEMPT_STATE,
    ILLEGAL_TRANSITION,
    STALE_SNAPSHOT,
    CONTRADICTORY_STATE,
    PERSISTED_STATE_INVALID,
}

sealed interface CaptureFileJournalResult {
    data class Applied(val snapshot: CaptureFileOperationSnapshot) : CaptureFileJournalResult {
        override fun toString(): String = "CaptureFileJournalResult.Applied(redacted)"
    }

    data class Idempotent(val snapshot: CaptureFileOperationSnapshot) : CaptureFileJournalResult {
        override fun toString(): String = "CaptureFileJournalResult.Idempotent(redacted)"
    }

    data object BlockedByDeletion : CaptureFileJournalResult {
        override fun toString(): String = "CaptureFileJournalResult.BlockedByDeletion"
    }

    data class Rejected(val reason: CaptureFileJournalRejectionReason) : CaptureFileJournalResult {
        override fun toString(): String = "CaptureFileJournalResult.Rejected(reason=${reason.name})"
    }
}

internal fun isWellFormedUtf16(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            Character.isHighSurrogate(character) -> {
                if (
                    index + 1 >= value.length ||
                    !Character.isLowSurrogate(value[index + 1])
                ) {
                    return false
                }
                index += 2
            }
            Character.isLowSurrogate(character) -> return false
            else -> index += 1
        }
    }
    return true
}

internal fun hasValidCaptureFileOperationEvidence(
    stage: CaptureFileOperationStage,
    byteCount: Long?,
    sha256: String?,
    capturedAtEpochMillis: Long?,
): Boolean {
    val presentCount = listOf(byteCount, sha256, capturedAtEpochMillis).count { it != null }
    if (presentCount != 0 && presentCount != 3) return false
    val hasEvidence = presentCount == 3
    if (
        hasEvidence &&
        (byteCount!! <= 0L || capturedAtEpochMillis!! < 0L || !sha256.isCanonicalSha256())
    ) {
        return false
    }

    return when (stage) {
        CaptureFileOperationStage.EXPECTING_RESERVATION,
        CaptureFileOperationStage.WRITING_TEMP,
        CaptureFileOperationStage.CLEANED_DURABLE,
        -> !hasEvidence

        CaptureFileOperationStage.CLEANUP_REQUIRED,
        CaptureFileOperationStage.CLEANUP_PENDING_SYNC,
        -> true

        CaptureFileOperationStage.TEMP_SYNCED,
        CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
        CaptureFileOperationStage.FINAL_DURABLE,
        CaptureFileOperationStage.QUARANTINE_REQUIRED,
        CaptureFileOperationStage.QUARANTINE_PENDING_SYNC,
        CaptureFileOperationStage.QUARANTINE_DURABLE,
        -> hasEvidence
    }
}

private fun String?.isCanonicalSha256(): Boolean =
    this != null && length == 64 && all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }
