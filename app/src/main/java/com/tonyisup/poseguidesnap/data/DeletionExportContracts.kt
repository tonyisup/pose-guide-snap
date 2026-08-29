package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity

sealed interface BeginShootDeletionResult {
    data class Began(
        val generation: Long,
        val cancelledOutputCount: Int,
        val cancelledOutboxCount: Int,
        val retainedOutputCount: Int,
    ) : BeginShootDeletionResult {
        init {
            require(generation >= 0L) { "deletion generation must be nonnegative" }
            require(cancelledOutputCount >= 0) { "cancelled output count must be nonnegative" }
            require(cancelledOutboxCount >= 0) { "cancelled outbox count must be nonnegative" }
            require(retainedOutputCount >= 0) { "retained output count must be nonnegative" }
        }

        override fun toString(): String = "BeginShootDeletionResult.Began"
    }

    data class AlreadyDeleting(val generation: Long) : BeginShootDeletionResult {
        init {
            require(generation >= 0L) { "deletion generation must be nonnegative" }
        }

        override fun toString(): String = "BeginShootDeletionResult.AlreadyDeleting"
    }

    data object UnknownShoot : BeginShootDeletionResult {
        override fun toString(): String = "BeginShootDeletionResult.UnknownShoot"
    }

    data class Rejected(val reason: BeginShootDeletionRejectionReason) :
        BeginShootDeletionResult {
        override fun toString(): String =
            "BeginShootDeletionResult.Rejected(reason=${reason.name})"
    }
}

enum class BeginShootDeletionRejectionReason {
    INVALID_SHOOT_ID,
    INVALID_TIMESTAMP,
    GENERATION_EXHAUSTED,
    UNSUPPORTED_SHOOT_STATE,
    AUTHORITY_INCONSISTENT,
    TRANSACTION_CAS_FAILED,
}

internal object BeginShootDeletionPolicy {
    fun validate(
        shootId: String,
        requestedAtEpochMillis: Long,
    ): BeginShootDeletionRejectionReason? = when {
        shootId.isBlank() -> BeginShootDeletionRejectionReason.INVALID_SHOOT_ID
        requestedAtEpochMillis < 0L -> BeginShootDeletionRejectionReason.INVALID_TIMESTAMP
        else -> null
    }
}

@JvmInline
value class ExportClaimToken(val value: String) {
    init {
        require(value.isNotBlank()) { "export claim token must be nonblank" }
    }

    override fun toString(): String = "ExportClaimToken(redacted)"
}

enum class ExportAuthorityStage {
    CLAIMED,
    CREATE_STARTED,
    URI_KNOWN,
    EXPORTED,
    AMBIGUOUS,
}

data class ExportOutputClaim(
    val identity: PrivateOutputIdentity,
    val claimToken: ExportClaimToken,
    val deletionGeneration: Long,
    val targetCollectionUri: String,
    val targetVolume: String,
    val intendedDisplayName: String,
    val intendedRelativePath: String,
    val intendedMimeType: String,
) {
    init {
        require(deletionGeneration >= 0L) {
            "export claim deletion generation must be nonnegative"
        }
        CaptureExportTarget(
            identity = identity,
            targetCollectionUri = targetCollectionUri,
            targetVolume = targetVolume,
            intendedDisplayName = intendedDisplayName,
            intendedRelativePath = intendedRelativePath,
            intendedMimeType = intendedMimeType,
        )
    }

    override fun toString(): String = "ExportOutputClaim(redacted)"
}

sealed interface ExportOutputClaimResult {
    data class Acquired(val claim: ExportOutputClaim) : ExportOutputClaimResult {
        override fun toString(): String = "ExportOutputClaimResult.Acquired"
    }

    data class IdempotentReplay(val stage: ExportAuthorityStage) : ExportOutputClaimResult {
        override fun toString(): String =
            "ExportOutputClaimResult.IdempotentReplay(stage=${stage.name})"
    }

    data object BlockedByDeletion : ExportOutputClaimResult {
        override fun toString(): String = "ExportOutputClaimResult.BlockedByDeletion"
    }

    data class Rejected(val reason: ExportOutputClaimRejectionReason) :
        ExportOutputClaimResult {
        override fun toString(): String =
            "ExportOutputClaimResult.Rejected(reason=${reason.name})"
    }
}

/** Only a fresh PENDING-to-CLAIMED acquisition authorizes an external create. */
val ExportOutputClaimResult.grantsFreshExternalCreateAuthority: Boolean
    get() = this is ExportOutputClaimResult.Acquired

enum class ExportOutputClaimRejectionReason {
    INVALID_TIMESTAMP,
    UNKNOWN_OUTPUT,
    CLAIM_TOKEN_CONFLICT,
    OWNED_BY_DIFFERENT_CLAIM,
    NOT_CLAIMABLE,
    AUTHORITY_INCONSISTENT,
    TRANSACTION_CAS_FAILED,
}

internal object ExportOutputClaimPolicy {
    fun validate(claimedAtEpochMillis: Long): ExportOutputClaimRejectionReason? =
        if (claimedAtEpochMillis < 0L) {
            ExportOutputClaimRejectionReason.INVALID_TIMESTAMP
        } else {
            null
        }
}
