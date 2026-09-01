package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.model.Landmark
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class ReferenceImportToken(val value: String) {
    init {
        require(value.isNotBlank() && !value.containsProviderUri() && '\u0000' !in value) {
            "reference import token must be nonblank and URI-free"
        }
    }

    override fun toString(): String = "ReferenceImportToken(redacted)"
}

@JvmInline
value class ReferenceLandmarkPayload private constructor(val value: String) {
    override fun toString(): String = "ReferenceLandmarkPayload(redacted)"

    companion object {
        fun from(landmarks: Iterable<Landmark>): ReferenceLandmarkPayload {
            val snapshot = landmarks.toList()
            require(snapshot.isNotEmpty()) { "reference landmarks must not be empty" }
            require(snapshot.map(Landmark::type).distinct().size == snapshot.size) {
                "reference landmark identities must be unique"
            }
            return ReferenceLandmarkPayload(
                snapshot.sortedBy { landmark -> landmark.type.ordinal }
                    .joinToString(prefix = "v1|", separator = ";") { landmark ->
                        listOf(
                            landmark.type.name,
                            landmark.x.toString(),
                            landmark.y.toString(),
                            landmark.z.toString(),
                            landmark.visibility.toString(),
                            landmark.presence.toString(),
                        ).joinToString(",")
                    },
            )
        }
    }
}

object ReferenceImportAssetPath {
    fun forToken(importToken: ReferenceImportToken): String =
        "reference-assets/assets/" +
            MessageDigest.getInstance("SHA-256")
                .digest(importToken.value.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) } +
            ".asset"
}

data class ReferenceImportReservation(
    val importToken: ReferenceImportToken,
    val shootId: String,
    val poseId: String,
    val relativeAssetPath: String,
) {
    init {
        requireOwnershipIdentity(shootId, poseId)
        require(
            relativeAssetPath == ReferenceImportAssetPath.forToken(importToken),
        ) { "reference asset path must exactly match its deterministic identity" }
    }

    override fun toString(): String = "ReferenceImportReservation(redacted)"
}

data class ReferenceImportEvidence(
    val importToken: ReferenceImportToken,
    val shootId: String,
    val poseId: String,
    val label: String,
    val relativeAssetPath: String,
    val mirrorAllowed: Boolean,
    val landmarkPayload: ReferenceLandmarkPayload,
    val detectorMetadata: String,
    val modelMetadata: String,
    val preprocessingMetadata: String,
    val coordinateMetadata: String,
) {
    init {
        requireOwnershipIdentity(shootId, poseId)
        require(label.isNotBlank() && !label.containsProviderUri()) {
            "reference label must be nonblank and URI-free"
        }
        require(
            relativeAssetPath == ReferenceImportAssetPath.forToken(importToken),
        ) { "reference asset path must exactly match its deterministic identity" }
        mapOf(
            "detector metadata" to detectorMetadata,
            "model metadata" to modelMetadata,
            "preprocessing metadata" to preprocessingMetadata,
            "coordinate metadata" to coordinateMetadata,
        ).forEach { (name, metadata) ->
            require(metadata.isNotBlank() && !metadata.containsProviderUri()) {
                "reference $name must be nonblank and URI-free"
            }
        }
    }

    override fun toString(): String = "ReferenceImportEvidence(redacted)"
}

enum class ReferenceImportLifecycle {
    PREPARING,
    ASSET_READY,
    COMMITTED,
    REJECTED_CLEANED,
    REJECTED_QUARANTINED,
}

enum class ReferenceImportFailureSettlement {
    CLEANED,
    QUARANTINED,
}

data class PendingReferenceImport(
    val importToken: ReferenceImportToken,
    val shootId: String,
    val poseId: String,
    val relativeAssetPath: String,
    val lifecycle: ReferenceImportLifecycle,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireOwnershipIdentity(shootId, poseId)
        require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis) {
            "pending reference import timestamps must be ordered and nonnegative"
        }
        require(
            relativeAssetPath == ReferenceImportAssetPath.forToken(importToken),
        ) { "pending reference asset path must exactly match its deterministic identity" }
    }

    override fun toString(): String = "PendingReferenceImport(redacted)"
}

sealed interface ReferenceImportReserveResult {
    data object Reserved : ReferenceImportReserveResult {
        override fun toString(): String = "ReferenceImportReserveResult.Reserved"
    }

    data class AlreadyCommitted(val poseIndex: Int) : ReferenceImportReserveResult {
        init {
            require(poseIndex >= 0) { "committed reference pose index must be nonnegative" }
        }

        override fun toString(): String = "ReferenceImportReserveResult.AlreadyCommitted(redacted)"
    }

    data object ExistingWorkRequiresReconciliation : ReferenceImportReserveResult {
        override fun toString(): String =
            "ReferenceImportReserveResult.ExistingWorkRequiresReconciliation"
    }

    data class Rejected(val reason: ReferenceImportReserveRejectionReason) :
        ReferenceImportReserveResult {
        override fun toString(): String =
            "ReferenceImportReserveResult.Rejected(reason=${reason.name})"
    }
}

enum class ReferenceImportReserveRejectionReason {
    INVALID_TIMESTAMP,
    UNKNOWN_SHOOT,
    SHOOT_NOT_ACTIVE,
    TOKEN_CONFLICT,
    POSE_ID_CONFLICT,
    POSE_ALREADY_EXISTS,
    PLAYLIST_FULL,
    ACTIVE_SESSION,
    UNRESOLVED_IMPORT_WORK,
    AUTHORITY_INCONSISTENT,
}

sealed interface ReferenceImportAdmissionCheckResult {
    data object Allowed : ReferenceImportAdmissionCheckResult {
        override fun toString(): String = "ReferenceImportAdmissionCheckResult.Allowed"
    }

    data class Blocked(val reason: ReferenceImportAdmissionCheckBlockReason) :
        ReferenceImportAdmissionCheckResult {
        override fun toString(): String =
            "ReferenceImportAdmissionCheckResult.Blocked(reason=${reason.name})"
    }
}

enum class ReferenceImportAdmissionCheckBlockReason {
    UNKNOWN_SHOOT,
    SHOOT_DELETING,
    PLAYLIST_FULL,
    ACTIVE_SESSION,
    IMPORT_IN_PROGRESS,
    RECONCILIATION_REQUIRED,
    AUTHORITY_INCONSISTENT,
}

sealed interface ReferenceImportAssetReadyResult {
    data object MarkedAssetReady : ReferenceImportAssetReadyResult {
        override fun toString(): String = "ReferenceImportAssetReadyResult.MarkedAssetReady"
    }

    data object AlreadyAssetReady : ReferenceImportAssetReadyResult {
        override fun toString(): String = "ReferenceImportAssetReadyResult.AlreadyAssetReady"
    }

    data class Rejected(val reason: ReferenceImportAssetReadyRejectionReason) :
        ReferenceImportAssetReadyResult {
        override fun toString(): String =
            "ReferenceImportAssetReadyResult.Rejected(reason=${reason.name})"
    }
}

enum class ReferenceImportAssetReadyRejectionReason {
    INVALID_TIMESTAMP,
    UNKNOWN_INTENT,
    INTENT_CONFLICT,
    WRONG_STATE,
    TRANSACTION_CAS_FAILED,
}

sealed interface ReferenceImportCommitResult {
    data class Committed(val poseIndex: Int) : ReferenceImportCommitResult {
        init {
            require(poseIndex >= 0) { "committed reference pose index must be nonnegative" }
        }

        override fun toString(): String = "ReferenceImportCommitResult.Committed(redacted)"
    }

    data class AlreadyCommitted(val poseIndex: Int) : ReferenceImportCommitResult {
        init {
            require(poseIndex >= 0) { "committed reference pose index must be nonnegative" }
        }

        override fun toString(): String = "ReferenceImportCommitResult.AlreadyCommitted(redacted)"
    }

    data object BlockedByDeletion : ReferenceImportCommitResult {
        override fun toString(): String = "ReferenceImportCommitResult.BlockedByDeletion"
    }

    data class Rejected(val reason: ReferenceImportCommitRejectionReason) :
        ReferenceImportCommitResult {
        override fun toString(): String =
            "ReferenceImportCommitResult.Rejected(reason=${reason.name})"
    }
}

enum class ReferenceImportCommitRejectionReason {
    INVALID_TIMESTAMP,
    UNKNOWN_INTENT,
    INTENT_CONFLICT,
    WRONG_STATE,
    ACTIVE_SESSION,
    POSE_ID_CONFLICT,
    EVIDENCE_CONFLICT,
    TRANSACTION_CAS_FAILED,
    AUTHORITY_INCONSISTENT,
}

sealed interface ReferenceImportSettlementResult {
    data object Settled : ReferenceImportSettlementResult {
        override fun toString(): String = "ReferenceImportSettlementResult.Settled"
    }

    data object AlreadySettled : ReferenceImportSettlementResult {
        override fun toString(): String = "ReferenceImportSettlementResult.AlreadySettled"
    }

    data class Rejected(val reason: ReferenceImportSettlementRejectionReason) :
        ReferenceImportSettlementResult {
        override fun toString(): String =
            "ReferenceImportSettlementResult.Rejected(reason=${reason.name})"
    }
}

enum class ReferenceImportSettlementRejectionReason {
    INVALID_TIMESTAMP,
    UNKNOWN_INTENT,
    COMMITTED_INTENT,
    SETTLEMENT_CONFLICT,
    ACTIVE_POSE_EXISTS,
    TRANSACTION_CAS_FAILED,
}

internal object ReferenceImportPolicy {
    fun validateTimestamp(timestampEpochMillis: Long): Boolean = timestampEpochMillis >= 0L

    fun validateOwnershipIdentity(value: String): Boolean = isSafeIdentitySegment(value)
}

private fun isSafeIdentitySegment(value: String): Boolean =
    value.isNotEmpty() &&
        value != "." &&
        value != ".." &&
        value.all { character ->
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '_' ||
                character == '-' ||
                character == '.'
        }

private fun requireOwnershipIdentity(shootId: String, poseId: String) {
    require(ReferenceImportPolicy.validateOwnershipIdentity(shootId)) {
        "reference shoot id must be a safe identity"
    }
    require(ReferenceImportPolicy.validateOwnershipIdentity(poseId)) {
        "reference pose id must be a safe identity"
    }
}

private fun String.containsProviderUri(): Boolean = contains("content://", ignoreCase = true)
