package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity

object GuidedSessionBootstrapMapper {
    fun map(rows: GuidedSessionBootstrapRows): GuidedSessionBootstrapResult {
        val hasConstituentRows = rows.shoot != null ||
            rows.poses.isNotEmpty() ||
            rows.attempts.isNotEmpty() ||
            rows.privateOutputs.isNotEmpty() ||
            rows.receipts.isNotEmpty() ||
            rows.outboxes.isNotEmpty() ||
            rows.exportOutputs.isNotEmpty() ||
            rows.captureFileOperations.isNotEmpty()
        val session = rows.session
        if (session == null) {
            return if (hasConstituentRows) {
                rejected(GuidedSessionBootstrapRejectionReason.ORPHANED_AUTHORITY)
            } else {
                GuidedSessionBootstrapResult.UnknownSession
            }
        }
        val shoot = rows.shoot
            ?: return rejected(GuidedSessionBootstrapRejectionReason.ORPHANED_AUTHORITY)

        if (shoot.lifecycleState != ACTIVE) {
            return rejected(GuidedSessionBootstrapRejectionReason.UNSUPPORTED_LIFECYCLE)
        }
        val lifecycle = when (session.lifecycleState) {
            ACTIVE -> GuidedSessionLifecycle.ACTIVE
            COMPLETED -> GuidedSessionLifecycle.COMPLETED
            else -> return rejected(GuidedSessionBootstrapRejectionReason.UNSUPPORTED_LIFECYCLE)
        }
        if (!shoot.hasCoherentShape()) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_SHOOT_AUTHORITY)
        }
        if (!session.hasCoherentShape(shoot.shootId)) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_SESSION_AUTHORITY)
        }
        if (session.nextAttemptNumber == Long.MAX_VALUE) {
            return rejected(GuidedSessionBootstrapRejectionReason.AUTHORITY_INCONSISTENT)
        }
        if (!rows.poses.haveCoherentShape(shoot.shootId)) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_POSE_AUTHORITY)
        }
        if (session.currentPoseIndex !in rows.poses.indices) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_SESSION_AUTHORITY)
        }
        if (
            lifecycle == GuidedSessionLifecycle.COMPLETED &&
            (session.currentPoseIndex != rows.poses.lastIndex ||
                session.nextAttemptNumber != rows.poses.size.toLong())
        ) {
            return rejected(GuidedSessionBootstrapRejectionReason.AUTHORITY_INCONSISTENT)
        }

        val attempts = rows.attempts
        if (!attempts.haveCoherentShape(session, shoot, rows.poses)) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY)
        }

        val attemptTokens = attempts.mapTo(linkedSetOf(), GuidedAttemptAuthorityRow::commandToken)
        if (rows.privateOutputs.any { it.commandToken !in attemptTokens }) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_PRIVATE_OUTPUT_AUTHORITY)
        }
        if (rows.receipts.any { it.commandToken !in attemptTokens }) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_RECEIPT_AUTHORITY)
        }
        if (rows.outboxes.any { it.commandToken !in attemptTokens }) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_OUTBOX_AUTHORITY)
        }
        if (rows.exportOutputs.any { it.commandToken !in attemptTokens }) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY)
        }
        if (rows.captureFileOperations.any { it.commandToken !in attemptTokens }) {
            return rejected(
                GuidedSessionBootstrapRejectionReason.INVALID_CAPTURE_FILE_OPERATION_AUTHORITY,
            )
        }

        val privateByToken = rows.privateOutputs.groupBy(GuidedPrivateOutputAuthorityRow::commandToken)
        val receiptsByToken = rows.receipts.groupBy(GuidedReceiptAuthorityRow::commandToken)
        val outboxesByToken = rows.outboxes.groupBy(GuidedOutboxAuthorityRow::commandToken)
        val exportsByToken = rows.exportOutputs.groupBy(GuidedExportOutputAuthorityRow::commandToken)
        val captureFilesByToken = rows.captureFileOperations
            .groupBy(GuidedCaptureFileOperationAuthorityRow::commandToken)
        var unresolvedExportCount = 0
        var confirmedAttemptCount = 0
        val blockingAttempts = mutableListOf<GuidedAttemptAuthorityRow>()

        attempts.forEach { attempt ->
            val privateOutputs = privateByToken[attempt.commandToken].orEmpty()
            val receipts = receiptsByToken[attempt.commandToken].orEmpty()
            val outboxes = outboxesByToken[attempt.commandToken].orEmpty()
            val exports = exportsByToken[attempt.commandToken].orEmpty()
            val captureFiles = captureFilesByToken[attempt.commandToken].orEmpty()
            if (attempt.lifecycleState != CONFIRMED) {
                val state = requireNotNull(attempt.stateOrNull())
                if (!captureFiles.haveCoherentCaptureFileOperationShape(attempt, state)) {
                    return rejected(
                        GuidedSessionBootstrapRejectionReason
                            .INVALID_CAPTURE_FILE_OPERATION_AUTHORITY,
                    )
                }
                blockingAttempts += attempt
                if (privateOutputs.isNotEmpty()) {
                    return rejected(
                        GuidedSessionBootstrapRejectionReason.INVALID_PRIVATE_OUTPUT_AUTHORITY,
                    )
                }
                if (receipts.isNotEmpty()) {
                    return rejected(GuidedSessionBootstrapRejectionReason.INVALID_RECEIPT_AUTHORITY)
                }
                if (outboxes.isNotEmpty()) {
                    return rejected(GuidedSessionBootstrapRejectionReason.INVALID_OUTBOX_AUTHORITY)
                }
                if (exports.isNotEmpty()) {
                    return rejected(GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY)
                }
                return@forEach
            }

            if (captureFiles.isNotEmpty()) {
                return rejected(
                    GuidedSessionBootstrapRejectionReason.INVALID_CAPTURE_FILE_OPERATION_AUTHORITY,
                )
            }

            val confirmedAt = attempt.confirmedAtEpochMillis!!
            if (!privateOutputs.haveCoherentPrivateOutputShape(attempt, confirmedAt)) {
                return rejected(
                    GuidedSessionBootstrapRejectionReason.INVALID_PRIVATE_OUTPUT_AUTHORITY,
                )
            }
            if (!receipts.haveCoherentShape(attempt, rows.poses.size, confirmedAt)) {
                return rejected(GuidedSessionBootstrapRejectionReason.INVALID_RECEIPT_AUTHORITY)
            }
            if (!outboxes.haveCoherentOutboxShape(attempt, confirmedAt)) {
                return rejected(GuidedSessionBootstrapRejectionReason.INVALID_OUTBOX_AUTHORITY)
            }
            val unresolvedForAttempt = exports.unresolvedCountOrNull(attempt, confirmedAt)
                ?: return rejected(GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY)
            unresolvedExportCount += unresolvedForAttempt
            confirmedAttemptCount += 1
        }

        if (blockingAttempts.size > 1) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY)
        }
        val blocking = blockingAttempts.singleOrNull()
        if (blocking != null && (
                blocking.attemptNumber != session.nextAttemptNumber - 1L ||
                    blocking.poseIndex != session.currentPoseIndex ||
                    blocking.capturedDeletionGeneration != shoot.deletionGeneration ||
                    confirmedAttemptCount != session.currentPoseIndex
                )
        ) {
            return rejected(GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY)
        }

        if (blocking == null) {
            when (lifecycle) {
                GuidedSessionLifecycle.ACTIVE -> if (
                    session.nextAttemptNumber != session.currentPoseIndex.toLong() ||
                    confirmedAttemptCount != session.currentPoseIndex
                ) {
                    return rejected(GuidedSessionBootstrapRejectionReason.AUTHORITY_INCONSISTENT)
                }
                GuidedSessionLifecycle.COMPLETED -> if (
                    session.currentPoseIndex != rows.poses.lastIndex ||
                    session.nextAttemptNumber != rows.poses.size.toLong() ||
                    confirmedAttemptCount != rows.poses.size
                ) {
                    return rejected(GuidedSessionBootstrapRejectionReason.AUTHORITY_INCONSISTENT)
                }
            }
        } else if (lifecycle != GuidedSessionLifecycle.ACTIVE) {
            return rejected(GuidedSessionBootstrapRejectionReason.AUTHORITY_INCONSISTENT)
        }

        val snapshot = GuidedSessionSnapshot(
            sessionId = session.sessionId,
            shootId = shoot.shootId,
            lifecycle = lifecycle,
            orderedPoseIds = rows.poses.map(GuidedPoseAuthorityRow::poseId),
            poseCount = rows.poses.size,
            currentPoseIndex = session.currentPoseIndex,
            nextAttemptNumber = session.nextAttemptNumber,
            deletionGeneration = shoot.deletionGeneration,
            attemptCount = attempts.size,
            confirmedAttemptCount = confirmedAttemptCount,
            appliedReceiptTokens = attempts
                .filter { it.lifecycleState == CONFIRMED }
                .map(GuidedAttemptAuthorityRow::commandToken),
            unresolvedExportCount = unresolvedExportCount,
            blockingAttempt = blocking?.toSummary(),
        )
        return when {
            blocking != null -> GuidedSessionBootstrapResult.ReconciliationRequired(snapshot)
            lifecycle == GuidedSessionLifecycle.COMPLETED ->
                GuidedSessionBootstrapResult.Completed(snapshot)
            else -> GuidedSessionBootstrapResult.Ready(snapshot)
        }
    }

    private fun GuidedShootAuthorityRow.hasCoherentShape(): Boolean =
        isSafeOwnershipIdentity(shootId) &&
            name.isNotBlank() &&
            createdAtEpochMillis >= 0L &&
            updatedAtEpochMillis >= createdAtEpochMillis &&
            deletionGeneration >= 0L

    private fun GuidedSessionAuthorityRow.hasCoherentShape(expectedShootId: String): Boolean =
        isSafeOwnershipIdentity(sessionId) &&
            shootId == expectedShootId &&
            currentPoseIndex >= 0 &&
            nextAttemptNumber >= 0L &&
            createdAtEpochMillis >= 0L &&
            updatedAtEpochMillis >= createdAtEpochMillis

    private fun List<GuidedPoseAuthorityRow>.haveCoherentShape(shootId: String): Boolean =
        size in MIN_POSE_COUNT..MAX_POSE_COUNT &&
            map(GuidedPoseAuthorityRow::poseId).distinct().size == size &&
            indices.all { index ->
                val pose = this[index]
                pose.shootId == shootId &&
                    pose.poseIndex == index &&
                    isSafeOwnershipIdentity(pose.poseId) &&
                    pose.label.isNotBlank() &&
                    pose.hasCoherentValidatedPayload()
            }

    private fun GuidedPoseAuthorityRow.hasCoherentValidatedPayload(): Boolean =
        when (validationState) {
            "VALID" ->
                referenceAssetPath == null &&
                    detectorMetadata == null &&
                    modelMetadata == null &&
                    preprocessingMetadata == null &&
                    landmarkPayload == null &&
                    coordinateMetadata == null
            "VALIDATED" ->
                referenceAssetPath != null &&
                    VALIDATED_REFERENCE_ASSET_PATH.matches(referenceAssetPath) &&
                    detectorMetadata.isSafeAuthorityEvidence() &&
                    modelMetadata.isSafeAuthorityEvidence() &&
                    preprocessingMetadata.isSafeAuthorityEvidence() &&
                    landmarkPayload.isSafeAuthorityEvidence() &&
                    requireNotNull(landmarkPayload).startsWith(LANDMARK_PAYLOAD_PREFIX) &&
                    landmarkPayload.length > LANDMARK_PAYLOAD_PREFIX.length &&
                    coordinateMetadata.isSafeAuthorityEvidence()
            else -> false
        }

    private fun String?.isSafeAuthorityEvidence(): Boolean =
        this != null && isNotBlank() && '\u0000' !in this && !URI_SCHEME.containsMatchIn(this)

    private fun List<GuidedAttemptAuthorityRow>.haveCoherentShape(
        session: GuidedSessionAuthorityRow,
        shoot: GuidedShootAuthorityRow,
        poses: List<GuidedPoseAuthorityRow>,
    ): Boolean {
        if (size.toLong() != session.nextAttemptNumber) return false
        if (map(GuidedAttemptAuthorityRow::commandToken).distinct().size != size) return false
        return indices.all { index ->
            val attempt = this[index]
            val state = attempt.stateOrNull() ?: return@all false
            attempt.attemptNumber == index.toLong() &&
                isValidCaptureToken(attempt.commandToken) &&
                attempt.sessionId == session.sessionId &&
                attempt.poseIndex in poses.indices &&
                attempt.poseId == poses[attempt.poseIndex].poseId &&
                attempt.triggerType in CAPTURE_TRIGGERS &&
                attempt.capturedDeletionGeneration == shoot.deletionGeneration &&
                attempt.createdAtEpochMillis >= 0L &&
                attempt.updatedAtEpochMillis >= attempt.createdAtEpochMillis &&
                when (state) {
                    GuidedCaptureAttemptState.CONFIRMED ->
                        !attempt.reconciliationRequired &&
                            attempt.confirmedAtEpochMillis != null &&
                            attempt.confirmedAtEpochMillis == attempt.updatedAtEpochMillis &&
                            attempt.poseIndex == index
                    GuidedCaptureAttemptState.REGISTERED ->
                        attempt.confirmedAtEpochMillis == null &&
                            attempt.updatedAtEpochMillis == attempt.createdAtEpochMillis
                    GuidedCaptureAttemptState.CAPTURING ->
                        attempt.confirmedAtEpochMillis == null
                }
        }
    }

    private fun List<GuidedCaptureFileOperationAuthorityRow>
        .haveCoherentCaptureFileOperationShape(
            attempt: GuidedAttemptAuthorityRow,
            attemptState: GuidedCaptureAttemptState,
        ): Boolean {
        if (size != BURST_SIZE || !isWellFormedUtf16(attempt.commandToken)) return false
        val captureToken = CaptureToken(attempt.commandToken)
        val rowsAreCoherent = indices.all { ordinal ->
            val operation = this[ordinal]
            val stage = CaptureFileOperationStage.entries
                .firstOrNull { it.name == operation.stage }
                ?: return@all false
            val failure = operation.lastFailureCode?.let { storedFailure ->
                CaptureFileFailureCode.entries.firstOrNull { it.name == storedFailure }
                    ?: return@all false
            }
            val expectedPaths = CaptureFileOperationPaths.forIdentity(
                PrivateOutputIdentity(captureToken, ordinal),
            )
            operation.hasCanonicalStorage &&
                operation.commandToken == attempt.commandToken &&
                operation.burstOrdinal == ordinal &&
                operation.relativeFinalPath == expectedPaths.relativeFinalPath &&
                operation.relativeTempPath == expectedPaths.relativeTempPath &&
                operation.relativeQuarantinePath == expectedPaths.relativeQuarantinePath &&
                operation.createdAtEpochMillis == attempt.createdAtEpochMillis &&
                operation.updatedAtEpochMillis in
                operation.createdAtEpochMillis..attempt.updatedAtEpochMillis &&
                (
                    operation.capturedAtEpochMillis == null ||
                        operation.capturedAtEpochMillis in
                        operation.createdAtEpochMillis..operation.updatedAtEpochMillis
                    ) &&
                hasValidCaptureFileOperationEvidence(
                    stage,
                    operation.byteCount,
                    operation.sha256,
                    operation.capturedAtEpochMillis,
                ) &&
                operation.reconciliationRequired == (failure != null) &&
                when (attemptState) {
                    GuidedCaptureAttemptState.REGISTERED ->
                        stage == CaptureFileOperationStage.EXPECTING_RESERVATION &&
                            failure == null &&
                            operation.updatedAtEpochMillis == operation.createdAtEpochMillis
                    GuidedCaptureAttemptState.CAPTURING -> {
                        val hasProgressed =
                            stage != CaptureFileOperationStage.EXPECTING_RESERVATION ||
                                failure != null ||
                                operation.updatedAtEpochMillis > operation.createdAtEpochMillis
                        !hasProgressed ||
                            (
                                operation.updatedAtEpochMillis > operation.createdAtEpochMillis &&
                                    operation.updatedAtEpochMillis == attempt.updatedAtEpochMillis
                                )
                    }
                    GuidedCaptureAttemptState.CONFIRMED -> false
                }
        }
        return rowsAreCoherent
    }

    private fun List<GuidedPrivateOutputAuthorityRow>.haveCoherentPrivateOutputShape(
        attempt: GuidedAttemptAuthorityRow,
        confirmedAt: Long,
    ): Boolean =
        size == BURST_SIZE && indices.all { ordinal ->
            val output = this[ordinal]
            output.commandToken == attempt.commandToken &&
                output.burstOrdinal == ordinal &&
                isNormalizedRelativePath(output.relativePath) &&
                output.byteCount > 0L &&
                output.durabilityState == DURABLE &&
                output.capturedAtEpochMillis >= 0L &&
                output.capturedAtEpochMillis <= confirmedAt &&
                (output.integrityMetadata == null || output.integrityMetadata.isNotBlank())
        }

    private fun List<GuidedReceiptAuthorityRow>.haveCoherentShape(
        attempt: GuidedAttemptAuthorityRow,
        poseCount: Int,
        confirmedAt: Long,
    ): Boolean {
        val receipt = singleOrNull() ?: return false
        val expectedDestination = if (attempt.poseIndex == poseCount - 1) {
            null
        } else {
            attempt.poseIndex + 1
        }
        return receipt.commandToken == attempt.commandToken &&
            receipt.fromPoseIndex == attempt.poseIndex &&
            receipt.toPoseIndex == expectedDestination &&
            receipt.appliedDeletionGeneration == attempt.capturedDeletionGeneration &&
            receipt.appliedAtEpochMillis == confirmedAt
    }

    private fun List<GuidedOutboxAuthorityRow>.haveCoherentOutboxShape(
        attempt: GuidedAttemptAuthorityRow,
        confirmedAt: Long,
    ): Boolean {
        val outbox = singleOrNull() ?: return false
        return outbox.commandToken == attempt.commandToken &&
            outbox.lifecycleState == PENDING &&
            outbox.createdAtEpochMillis == confirmedAt &&
            outbox.updatedAtEpochMillis >= outbox.createdAtEpochMillis &&
            (outbox.retryMetadata == null || outbox.retryMetadata.isNotBlank())
    }

    private fun List<GuidedExportOutputAuthorityRow>.unresolvedCountOrNull(
        attempt: GuidedAttemptAuthorityRow,
        confirmedAt: Long,
    ): Int? {
        if (size != BURST_SIZE) return null
        var unresolved = 0
        indices.forEach { ordinal ->
            val output = this[ordinal]
            val state = output.exportStateOrNull() ?: return null
            if (
                output.commandToken != attempt.commandToken ||
                output.burstOrdinal != ordinal ||
                !output.hasCoherentTarget() ||
                output.deletionGeneration != attempt.capturedDeletionGeneration ||
                output.createdAtEpochMillis != confirmedAt ||
                output.updatedAtEpochMillis < output.createdAtEpochMillis ||
                !output.hasCoherentStateFacts(state)
            ) {
                return null
            }
            if (state !in TERMINAL_EXPORT_STATES) unresolved += 1
        }
        return unresolved
    }

    private fun GuidedExportOutputAuthorityRow.hasCoherentTarget(): Boolean =
        isMediaStoreVolumeName(targetVolume) &&
            targetCollectionUri == "content://media/$targetVolume/images/media" &&
            intendedDisplayName.isNotBlank() &&
            isNormalizedMediaRelativePath(intendedRelativePath) &&
            intendedMimeType.isNotBlank()

    private fun GuidedExportOutputAuthorityRow.hasCoherentStateFacts(
        state: GuidedExportState,
    ): Boolean = when (state) {
        GuidedExportState.PENDING,
        GuidedExportState.CANCELLED,
        -> claimToken == null && mediaUriString == null && ambiguityState == NONE
        GuidedExportState.CLAIMED,
        GuidedExportState.CREATE_STARTED,
        -> !claimToken.isNullOrBlank() && mediaUriString == null && ambiguityState == NONE
        GuidedExportState.URI_KNOWN,
        GuidedExportState.EXPORTED,
        -> !claimToken.isNullOrBlank() &&
            !mediaUriString.isNullOrBlank() &&
            mediaUriString.startsWith(CONTENT_URI_PREFIX) &&
            ambiguityState == NONE
        GuidedExportState.AMBIGUOUS ->
            !claimToken.isNullOrBlank() && ambiguityState.isNotBlank() && ambiguityState != NONE
    }

    private fun GuidedAttemptAuthorityRow.stateOrNull(): GuidedCaptureAttemptState? =
        GuidedCaptureAttemptState.entries.firstOrNull { it.name == lifecycleState }

    private fun GuidedExportOutputAuthorityRow.exportStateOrNull(): GuidedExportState? =
        GuidedExportState.entries.firstOrNull { it.name == lifecycleState }

    private fun GuidedAttemptAuthorityRow.toSummary(): GuidedBlockingAttemptSummary =
        GuidedBlockingAttemptSummary(
            commandToken = commandToken,
            poseId = poseId,
            attemptNumber = attemptNumber,
            poseIndex = poseIndex,
            trigger = GuidedCaptureTrigger.valueOf(triggerType),
            state = requireNotNull(stateOrNull()),
            reconciliationRequired = reconciliationRequired,
            deletionGeneration = capturedDeletionGeneration,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun isSafeOwnershipIdentity(value: String): Boolean =
        ReferenceImportPolicy.validateOwnershipIdentity(value)

    private fun isValidCaptureToken(value: String): Boolean = value.isNotBlank()

    private fun isNormalizedRelativePath(value: String): Boolean =
        value.isNotBlank() &&
            !value.startsWith('/') &&
            !value.startsWith('\\') &&
            !hasDrivePrefix(value) &&
            value.indexOf('\u0000') < 0 &&
            value.split('/', '\\').all { it.isNotEmpty() && it != "." && it != ".." }

    private fun isNormalizedMediaRelativePath(value: String): Boolean =
        value.endsWith('/') &&
            !value.endsWith("//") &&
            '\\' !in value &&
            !hasDrivePrefix(value) &&
            isNormalizedRelativePath(value.dropLast(1))

    private fun isMediaStoreVolumeName(value: String): Boolean =
        value.isNotEmpty() && value.all { character ->
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '_' ||
                character == '-'
        }

    private fun hasDrivePrefix(value: String): Boolean =
        value.length >= 2 && value[0].isLetter() && value[1] == ':'

    private fun rejected(
        reason: GuidedSessionBootstrapRejectionReason,
    ): GuidedSessionBootstrapResult.Rejected = GuidedSessionBootstrapResult.Rejected(reason)

    private const val ACTIVE = "ACTIVE"
    private const val COMPLETED = "COMPLETED"
    private const val CONFIRMED = "CONFIRMED"
    private const val DURABLE = "DURABLE"
    private const val PENDING = "PENDING"
    private const val NONE = "NONE"
    private const val CONTENT_URI_PREFIX = "content://"
    private const val BURST_SIZE = 3
    private const val MIN_POSE_COUNT = 3
    private const val MAX_POSE_COUNT = 20
    private const val LANDMARK_PAYLOAD_PREFIX = "v1|"
    private val CAPTURE_TRIGGERS = setOf("MANUAL", "AUTOMATIC")
    private val VALIDATED_REFERENCE_ASSET_PATH =
        Regex("reference-assets/assets/[0-9a-f]{64}\\.asset")
    private val URI_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*://")
    private val TERMINAL_EXPORT_STATES = setOf(
        GuidedExportState.EXPORTED,
        GuidedExportState.CANCELLED,
    )
}
