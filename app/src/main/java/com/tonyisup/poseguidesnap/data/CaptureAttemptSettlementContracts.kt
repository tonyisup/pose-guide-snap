package com.tonyisup.poseguidesnap.data

sealed interface CaptureAttemptSettlementResult {
    data object FailedCleaned : CaptureAttemptSettlementResult
    data object AlreadyFailedCleaned : CaptureAttemptSettlementResult
    data object ReadyToConfirm : CaptureAttemptSettlementResult
    data object ReconciliationRequired : CaptureAttemptSettlementResult
    data object AlreadyReconciliationRequired : CaptureAttemptSettlementResult
    data object BlockedByDeletion : CaptureAttemptSettlementResult
    data class Rejected(val reason: CaptureAttemptSettlementRejectionReason) :
        CaptureAttemptSettlementResult
}

enum class CaptureAttemptSettlementRejectionReason {
    INVALID_SESSION_ID,
    INVALID_TIMESTAMP,
    UNKNOWN_ATTEMPT,
    TOKEN_SESSION_CONFLICT,
    WRONG_STATE,
    STALE_POSE,
    IMMUTABLE_AUTHORITY_PRESENT,
    JOURNAL_AUTHORITY_INVALID,
    AUTHORITY_INCONSISTENT,
    CAS_FAILED,
}

internal object CaptureAttemptSettlementPolicy {
    fun validate(
        sessionId: String,
        settledAtEpochMillis: Long,
    ): CaptureAttemptSettlementRejectionReason? = when {
        sessionId.isBlank() -> CaptureAttemptSettlementRejectionReason.INVALID_SESSION_ID
        settledAtEpochMillis < 0L -> CaptureAttemptSettlementRejectionReason.INVALID_TIMESTAMP
        else -> null
    }
}
