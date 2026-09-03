package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect

data class DurablePrivateOutput(
    val identity: PrivateOutputIdentity,
    val relativePath: String,
    val byteCount: Long,
    val capturedAtEpochMillis: Long,
    val integrityMetadata: String? = null,
) {
    init {
        require(isNormalizedPrivateRelativePath(relativePath)) {
            "private output path must be a normalized relative path"
        }
        require(byteCount > 0L) { "private output byte count must be positive" }
        require(capturedAtEpochMillis >= 0L) {
            "private output capture timestamp must be nonnegative"
        }
        require(integrityMetadata == null || integrityMetadata.isNotBlank()) {
            "private output integrity metadata must be nonblank when present"
        }
    }

    override fun toString(): String = "DurablePrivateOutput(redacted)"
}

data class CaptureExportTarget(
    val identity: PrivateOutputIdentity,
    val targetCollectionUri: String,
    val targetVolume: String,
    val intendedDisplayName: String,
    val intendedRelativePath: String,
    val intendedMimeType: String,
) {
    init {
        require(isMediaStoreVolumeName(targetVolume)) {
            "export target volume must use MediaStore volume syntax"
        }
        require(isExactMediaStoreImageCollectionUri(targetCollectionUri, targetVolume)) {
            "export collection URI must exactly match target volume"
        }
        require(intendedDisplayName.isNotBlank()) { "export display name must be nonblank" }
        require(isNormalizedMediaRelativePath(intendedRelativePath)) {
            "export relative media path must be normalized"
        }
        require(intendedMimeType.isNotBlank()) { "export MIME type must be nonblank" }
    }

    override fun toString(): String = "CaptureExportTarget(redacted)"
}

sealed interface CaptureConfirmationResult {
    data object Applied : CaptureConfirmationResult {
        override fun toString(): String = "CaptureConfirmationResult.Applied"
    }

    data object AlreadyApplied : CaptureConfirmationResult {
        override fun toString(): String = "CaptureConfirmationResult.AlreadyApplied"
    }

    data object BlockedByDeletion : CaptureConfirmationResult {
        override fun toString(): String = "CaptureConfirmationResult.BlockedByDeletion"
    }

    data class Rejected(val reason: CaptureConfirmationRejectionReason) :
        CaptureConfirmationResult {
        override fun toString(): String = "CaptureConfirmationResult.Rejected(reason=${reason.name})"
    }
}

enum class CaptureConfirmationRejectionReason {
    INVALID_TIMESTAMP,
    INVALID_PRIVATE_OUTPUTS,
    INVALID_EXPORT_TARGETS,
    UNKNOWN_ATTEMPT,
    TOKEN_POSE_CONFLICT,
    WRONG_ATTEMPT_STATE,
    INACTIVE_SESSION,
    STALE_POSE,
    TRANSACTION_CAS_FAILED,
    TRANSACTION_CARDINALITY_FAILURE,
    JOURNAL_CONFIRMATION_NOT_AVAILABLE,
    JOURNAL_AUTHORITY_INVALID,
}

internal object CaptureConfirmationPolicy {
    fun validate(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        privateOutputs: List<DurablePrivateOutput>,
        exportTargets: List<CaptureExportTarget>,
        confirmedAtEpochMillis: Long,
    ): CaptureConfirmationRejectionReason? {
        if (confirmedAtEpochMillis < 0L) {
            return CaptureConfirmationRejectionReason.INVALID_TIMESTAMP
        }

        val expectedIdentities = (0..2).map { ordinal ->
            PrivateOutputIdentity(command.token, ordinal)
        }
        val privateIdentities = privateOutputs.map(DurablePrivateOutput::identity)
        if (privateIdentities != expectedIdentities) {
            return CaptureConfirmationRejectionReason.INVALID_PRIVATE_OUTPUTS
        }

        val exportIdentities = exportTargets.map(CaptureExportTarget::identity)
        if (exportIdentities != expectedIdentities || exportIdentities != privateIdentities) {
            return CaptureConfirmationRejectionReason.INVALID_EXPORT_TARGETS
        }
        return null
    }
}

internal fun isNormalizedPrivateRelativePath(value: String): Boolean {
    if (value.isBlank() || value.indexOf('\u0000') >= 0 || hasDrivePrefix(value)) return false
    if (value.startsWith('/') || value.startsWith('\\')) return false
    return value.split('/', '\\').all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".."
    }
}

private fun isNormalizedMediaRelativePath(value: String): Boolean {
    if (
        value.isBlank() ||
        value.indexOf('\u0000') >= 0 ||
        value.startsWith('/') ||
        value.startsWith('\\') ||
        value.indexOf('\\') >= 0 ||
        hasDrivePrefix(value) ||
        !value.endsWith('/') ||
        value.endsWith("//")
    ) {
        return false
    }
    val withoutTrailingSeparator = value.dropLast(1)
    return withoutTrailingSeparator.isNotEmpty() &&
        withoutTrailingSeparator.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".."
        }
}

private fun isMediaStoreVolumeName(value: String): Boolean =
    value.isNotEmpty() && value.all { character ->
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '_' ||
            character == '-'
    }

private fun isExactMediaStoreImageCollectionUri(value: String, volume: String): Boolean =
    value == "$MEDIA_STORE_URI_PREFIX$volume$MEDIA_STORE_IMAGES_SUFFIX"

private fun hasDrivePrefix(value: String): Boolean =
    value.length >= 2 && value[0].isLetter() && value[1] == ':'

private const val MEDIA_STORE_URI_PREFIX = "content://media/"
private const val MEDIA_STORE_IMAGES_SUFFIX = "/images/media"
