package com.tonyisup.poseguidesnap.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import com.tonyisup.poseguidesnap.data.ActiveGuidedSessionCandidateRows
import com.tonyisup.poseguidesnap.data.GuidedAttemptAuthorityRow
import com.tonyisup.poseguidesnap.data.GuidedCaptureFileOperationAuthorityRow
import com.tonyisup.poseguidesnap.data.GuidedExportOutputAuthorityRow
import com.tonyisup.poseguidesnap.data.GuidedOutboxAuthorityRow
import com.tonyisup.poseguidesnap.data.GuidedPoseAuthorityRow
import com.tonyisup.poseguidesnap.data.GuidedPrivateOutputAuthorityRow
import com.tonyisup.poseguidesnap.data.GuidedReceiptAuthorityRow
import com.tonyisup.poseguidesnap.data.GuidedSessionAuthorityRow
import com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRows
import com.tonyisup.poseguidesnap.data.GuidedShootAuthorityRow

@Dao
internal abstract class GuidedSessionDao {
    @Transaction
    open fun loadGuidedSessionBootstrap(sessionId: String): GuidedSessionBootstrapRows {
        val session = findSession(sessionId)
            ?: return GuidedSessionBootstrapRows(shoot = null, session = null)
        return GuidedSessionBootstrapRows(
            shoot = findOwningShoot(sessionId)?.toAuthorityRow(),
            session = session.toAuthorityRow(),
            poses = findOwningPoses(sessionId).map(ShootPoseEntity::toAuthorityRow),
            attempts = findAttempts(sessionId).map(CaptureAttemptEntity::toAuthorityRow),
            privateOutputs = findPrivateOutputs(sessionId)
                .map(PrivateCaptureOutputEntity::toAuthorityRow),
            receipts = findReceipts(sessionId)
                .map(CaptureConfirmationReceiptEntity::toAuthorityRow),
            outboxes = findOutboxes(sessionId).map(CaptureExportOutboxEntity::toAuthorityRow),
            exportOutputs = findExportOutputs(sessionId)
                .map(CaptureExportOutputEntity::toAuthorityRow),
            captureFileOperations = findCaptureFileOperations(sessionId)
                .map(CaptureFileOperationProjection::toAuthorityRow),
        )
    }

    @Transaction
    open fun findActiveSessionCandidates(shootId: String): ActiveGuidedSessionCandidateRows =
        ActiveGuidedSessionCandidateRows(
            shoot = findShoot(shootId)?.toAuthorityRow(),
            sessions = findSessionsForShoot(shootId).map(ShootSessionEntity::toAuthorityRow),
        )

    @Query("SELECT * FROM shoots WHERE shoot_id = :shootId")
    protected abstract fun findShoot(shootId: String): ShootEntity?

    @Query(
        """
        SELECT *
        FROM shoot_sessions
        WHERE shoot_id = :shootId
        ORDER BY session_id ASC
        """,
    )
    protected abstract fun findSessionsForShoot(shootId: String): List<ShootSessionEntity>

    @Query("SELECT * FROM shoot_sessions WHERE session_id = :sessionId")
    protected abstract fun findSession(sessionId: String): ShootSessionEntity?

    @Query(
        """
        SELECT shoot.*
        FROM shoots AS shoot
        INNER JOIN shoot_sessions AS session
            ON session.shoot_id = shoot.shoot_id
        WHERE session.session_id = :sessionId
        """,
    )
    protected abstract fun findOwningShoot(sessionId: String): ShootEntity?

    @Query(
        """
        SELECT pose.*
        FROM shoot_poses AS pose
        INNER JOIN shoot_sessions AS session
            ON session.shoot_id = pose.shoot_id
        WHERE session.session_id = :sessionId
        ORDER BY pose.pose_index ASC, pose.pose_id ASC
        """,
    )
    protected abstract fun findOwningPoses(sessionId: String): List<ShootPoseEntity>

    @Query(
        """
        SELECT *
        FROM capture_attempts
        WHERE session_id = :sessionId
        ORDER BY attempt_number ASC, command_token ASC
        """,
    )
    protected abstract fun findAttempts(sessionId: String): List<CaptureAttemptEntity>

    @SkipQueryVerification
    @Query(
        """
        SELECT
            CASE WHEN typeof(operation.command_token) = 'text' THEN operation.command_token ELSE '' END AS command_token,
            typeof(operation.command_token) AS command_token_storage_type,
            quote(operation.command_token) AS command_token_storage_quote,
            CASE WHEN typeof(operation.burst_ordinal) = 'integer' AND operation.burst_ordinal BETWEEN 0 AND 2 THEN operation.burst_ordinal ELSE 0 END AS burst_ordinal,
            typeof(operation.burst_ordinal) AS burst_ordinal_storage_type,
            quote(operation.burst_ordinal) AS burst_ordinal_storage_quote,
            CASE WHEN typeof(operation.relative_final_path) = 'text' THEN operation.relative_final_path ELSE '' END AS relative_final_path,
            typeof(operation.relative_final_path) AS relative_final_path_storage_type,
            quote(operation.relative_final_path) AS relative_final_path_storage_quote,
            CASE WHEN typeof(operation.relative_temp_path) = 'text' THEN operation.relative_temp_path ELSE '' END AS relative_temp_path,
            typeof(operation.relative_temp_path) AS relative_temp_path_storage_type,
            quote(operation.relative_temp_path) AS relative_temp_path_storage_quote,
            CASE WHEN typeof(operation.relative_quarantine_path) = 'text' THEN operation.relative_quarantine_path ELSE '' END AS relative_quarantine_path,
            typeof(operation.relative_quarantine_path) AS relative_quarantine_path_storage_type,
            quote(operation.relative_quarantine_path) AS relative_quarantine_path_storage_quote,
            CASE WHEN typeof(operation.stage) = 'text' THEN operation.stage ELSE '' END AS stage,
            typeof(operation.stage) AS stage_storage_type,
            quote(operation.stage) AS stage_storage_quote,
            CASE WHEN typeof(operation.byte_count) IN ('null', 'integer') THEN operation.byte_count ELSE NULL END AS byte_count,
            typeof(operation.byte_count) AS byte_count_storage_type,
            quote(operation.byte_count) AS byte_count_storage_quote,
            CASE WHEN typeof(operation.sha256) IN ('null', 'text') THEN operation.sha256 ELSE NULL END AS sha256,
            typeof(operation.sha256) AS sha256_storage_type,
            quote(operation.sha256) AS sha256_storage_quote,
            CASE WHEN typeof(operation.captured_at_epoch_millis) IN ('null', 'integer') THEN operation.captured_at_epoch_millis ELSE NULL END AS captured_at_epoch_millis,
            typeof(operation.captured_at_epoch_millis) AS captured_at_epoch_millis_storage_type,
            quote(operation.captured_at_epoch_millis) AS captured_at_epoch_millis_storage_quote,
            CASE WHEN typeof(operation.last_failure_code) IN ('null', 'text') THEN operation.last_failure_code ELSE NULL END AS last_failure_code,
            typeof(operation.last_failure_code) AS last_failure_code_storage_type,
            quote(operation.last_failure_code) AS last_failure_code_storage_quote,
            CASE WHEN typeof(operation.reconciliation_required) = 'integer' AND operation.reconciliation_required IN (0, 1) THEN operation.reconciliation_required ELSE 0 END AS reconciliation_required,
            typeof(operation.reconciliation_required) AS reconciliation_required_storage_type,
            quote(operation.reconciliation_required) AS reconciliation_required_storage_quote,
            CASE WHEN typeof(operation.created_at_epoch_millis) = 'integer' THEN operation.created_at_epoch_millis ELSE 0 END AS created_at_epoch_millis,
            typeof(operation.created_at_epoch_millis) AS created_at_epoch_millis_storage_type,
            quote(operation.created_at_epoch_millis) AS created_at_epoch_millis_storage_quote,
            CASE WHEN typeof(operation.updated_at_epoch_millis) = 'integer' THEN operation.updated_at_epoch_millis ELSE 0 END AS updated_at_epoch_millis,
            typeof(operation.updated_at_epoch_millis) AS updated_at_epoch_millis_storage_type,
            quote(operation.updated_at_epoch_millis) AS updated_at_epoch_millis_storage_quote,
            CASE WHEN (typeof(operation.command_token) = 'text' AND quote(operation.command_token) <> 'NULL') AND (typeof(operation.burst_ordinal) = 'integer' AND quote(operation.burst_ordinal) IN ('0', '1', '2')) AND (typeof(operation.relative_final_path) = 'text' AND quote(operation.relative_final_path) <> 'NULL') AND (typeof(operation.relative_temp_path) = 'text' AND quote(operation.relative_temp_path) <> 'NULL') AND (typeof(operation.relative_quarantine_path) = 'text' AND quote(operation.relative_quarantine_path) <> 'NULL') AND (typeof(operation.stage) = 'text' AND quote(operation.stage) <> 'NULL') AND ((typeof(operation.byte_count) = 'null' AND quote(operation.byte_count) = 'NULL') OR (typeof(operation.byte_count) = 'integer' AND quote(operation.byte_count) <> 'NULL')) AND ((typeof(operation.sha256) = 'null' AND quote(operation.sha256) = 'NULL') OR (typeof(operation.sha256) = 'text' AND quote(operation.sha256) <> 'NULL')) AND ((typeof(operation.captured_at_epoch_millis) = 'null' AND quote(operation.captured_at_epoch_millis) = 'NULL') OR (typeof(operation.captured_at_epoch_millis) = 'integer' AND quote(operation.captured_at_epoch_millis) <> 'NULL')) AND ((typeof(operation.last_failure_code) = 'null' AND quote(operation.last_failure_code) = 'NULL') OR (typeof(operation.last_failure_code) = 'text' AND quote(operation.last_failure_code) <> 'NULL')) AND (typeof(operation.reconciliation_required) = 'integer' AND quote(operation.reconciliation_required) IN ('0', '1')) AND (typeof(operation.created_at_epoch_millis) = 'integer' AND quote(operation.created_at_epoch_millis) <> 'NULL') AND (typeof(operation.updated_at_epoch_millis) = 'integer' AND quote(operation.updated_at_epoch_millis) <> 'NULL') THEN 1 ELSE 0 END AS has_canonical_storage
        FROM capture_file_operations AS operation
        INNER JOIN capture_attempts AS attempt
            ON CAST(operation.command_token AS BLOB) = CAST(attempt.command_token AS BLOB)
        WHERE CAST(attempt.session_id AS BLOB) = CAST(:sessionId AS BLOB)
        ORDER BY attempt.attempt_number ASC, operation.burst_ordinal ASC,
                 operation.command_token ASC
        """,
    )
    protected abstract fun findCaptureFileOperations(
        sessionId: String,
    ): List<CaptureFileOperationProjection>

    @Query(
        """
        SELECT private_output.*
        FROM private_capture_outputs AS private_output
        INNER JOIN capture_attempts AS attempt
            ON attempt.command_token = private_output.command_token
        WHERE attempt.session_id = :sessionId
        ORDER BY attempt.attempt_number ASC,
                 private_output.burst_ordinal ASC,
                 private_output.command_token ASC
        """,
    )
    protected abstract fun findPrivateOutputs(sessionId: String): List<PrivateCaptureOutputEntity>

    @Query(
        """
        SELECT receipt.*
        FROM capture_confirmation_receipts AS receipt
        INNER JOIN capture_attempts AS attempt
            ON attempt.command_token = receipt.command_token
        WHERE attempt.session_id = :sessionId
        ORDER BY receipt.from_pose_index ASC, receipt.command_token ASC
        """,
    )
    protected abstract fun findReceipts(
        sessionId: String,
    ): List<CaptureConfirmationReceiptEntity>

    @Query(
        """
        SELECT outbox.*
        FROM capture_export_outboxes AS outbox
        INNER JOIN capture_attempts AS attempt
            ON attempt.command_token = outbox.command_token
        WHERE attempt.session_id = :sessionId
        ORDER BY attempt.attempt_number ASC, outbox.command_token ASC
        """,
    )
    protected abstract fun findOutboxes(sessionId: String): List<CaptureExportOutboxEntity>

    @Query(
        """
        SELECT output.*
        FROM capture_export_outputs AS output
        INNER JOIN capture_attempts AS attempt
            ON attempt.command_token = output.command_token
        WHERE attempt.session_id = :sessionId
        ORDER BY attempt.attempt_number ASC,
                 output.burst_ordinal ASC,
                 output.command_token ASC
        """,
    )
    protected abstract fun findExportOutputs(sessionId: String): List<CaptureExportOutputEntity>
}

internal data class CaptureFileOperationProjection(
    @ColumnInfo(name = "command_token") val commandToken: String,
    @ColumnInfo(name = "command_token_storage_type") val commandTokenStorageType: String,
    @ColumnInfo(name = "command_token_storage_quote") val commandTokenStorageQuote: String,
    @ColumnInfo(name = "burst_ordinal") val burstOrdinal: Int,
    @ColumnInfo(name = "burst_ordinal_storage_type") val burstOrdinalStorageType: String,
    @ColumnInfo(name = "burst_ordinal_storage_quote") val burstOrdinalStorageQuote: String,
    @ColumnInfo(name = "relative_final_path") val relativeFinalPath: String,
    @ColumnInfo(name = "relative_final_path_storage_type") val relativeFinalPathStorageType: String,
    @ColumnInfo(name = "relative_final_path_storage_quote") val relativeFinalPathStorageQuote: String,
    @ColumnInfo(name = "relative_temp_path") val relativeTempPath: String,
    @ColumnInfo(name = "relative_temp_path_storage_type") val relativeTempPathStorageType: String,
    @ColumnInfo(name = "relative_temp_path_storage_quote") val relativeTempPathStorageQuote: String,
    @ColumnInfo(name = "relative_quarantine_path") val relativeQuarantinePath: String,
    @ColumnInfo(name = "relative_quarantine_path_storage_type")
    val relativeQuarantinePathStorageType: String,
    @ColumnInfo(name = "relative_quarantine_path_storage_quote")
    val relativeQuarantinePathStorageQuote: String,
    @ColumnInfo(name = "stage") val stage: String,
    @ColumnInfo(name = "stage_storage_type") val stageStorageType: String,
    @ColumnInfo(name = "stage_storage_quote") val stageStorageQuote: String,
    @ColumnInfo(name = "byte_count") val byteCount: Long?,
    @ColumnInfo(name = "byte_count_storage_type") val byteCountStorageType: String,
    @ColumnInfo(name = "byte_count_storage_quote") val byteCountStorageQuote: String,
    @ColumnInfo(name = "sha256") val sha256: String?,
    @ColumnInfo(name = "sha256_storage_type") val sha256StorageType: String,
    @ColumnInfo(name = "sha256_storage_quote") val sha256StorageQuote: String,
    @ColumnInfo(name = "captured_at_epoch_millis") val capturedAtEpochMillis: Long?,
    @ColumnInfo(name = "captured_at_epoch_millis_storage_type")
    val capturedAtEpochMillisStorageType: String,
    @ColumnInfo(name = "captured_at_epoch_millis_storage_quote")
    val capturedAtEpochMillisStorageQuote: String,
    @ColumnInfo(name = "last_failure_code") val lastFailureCode: String?,
    @ColumnInfo(name = "last_failure_code_storage_type") val lastFailureCodeStorageType: String,
    @ColumnInfo(name = "last_failure_code_storage_quote") val lastFailureCodeStorageQuote: String,
    @ColumnInfo(name = "reconciliation_required") val reconciliationRequired: Boolean,
    @ColumnInfo(name = "reconciliation_required_storage_type")
    val reconciliationRequiredStorageType: String,
    @ColumnInfo(name = "reconciliation_required_storage_quote")
    val reconciliationRequiredStorageQuote: String,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "created_at_epoch_millis_storage_type")
    val createdAtEpochMillisStorageType: String,
    @ColumnInfo(name = "created_at_epoch_millis_storage_quote")
    val createdAtEpochMillisStorageQuote: String,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis_storage_type")
    val updatedAtEpochMillisStorageType: String,
    @ColumnInfo(name = "updated_at_epoch_millis_storage_quote")
    val updatedAtEpochMillisStorageQuote: String,
    @ColumnInfo(name = "has_canonical_storage") val hasCanonicalStorage: Boolean,
) {
    override fun toString(): String = "CaptureFileOperationProjection(redacted)"
}

private fun CaptureFileOperationProjection.toAuthorityRow():
    GuidedCaptureFileOperationAuthorityRow =
    GuidedCaptureFileOperationAuthorityRow(
        commandToken = commandToken,
        burstOrdinal = burstOrdinal,
        relativeFinalPath = relativeFinalPath,
        relativeTempPath = relativeTempPath,
        relativeQuarantinePath = relativeQuarantinePath,
        stage = stage,
        byteCount = byteCount,
        sha256 = sha256,
        capturedAtEpochMillis = capturedAtEpochMillis,
        lastFailureCode = lastFailureCode,
        reconciliationRequired = reconciliationRequired,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        hasCanonicalStorage = hasCanonicalStorage,
    )

private fun ShootEntity.toAuthorityRow(): GuidedShootAuthorityRow = GuidedShootAuthorityRow(
    shootId = shootId,
    name = name,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    lifecycleState = lifecycleState,
    deletionGeneration = deletionGeneration,
)

private fun ShootSessionEntity.toAuthorityRow(): GuidedSessionAuthorityRow =
    GuidedSessionAuthorityRow(
        sessionId = sessionId,
        shootId = shootId,
        currentPoseIndex = currentPoseIndex,
        nextAttemptNumber = nextAttemptNumber,
        lifecycleState = lifecycleState,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

private fun ShootPoseEntity.toAuthorityRow(): GuidedPoseAuthorityRow = GuidedPoseAuthorityRow(
    shootId = shootId,
    poseIndex = poseIndex,
    poseId = poseId,
    label = label,
    referenceAssetPath = referenceAssetPath,
    mirrorAllowed = mirrorAllowed,
    validationState = validationState,
    detectorMetadata = detectorMetadata,
    modelMetadata = modelMetadata,
    preprocessingMetadata = preprocessingMetadata,
    landmarkPayload = landmarkPayload,
    coordinateMetadata = coordinateMetadata,
)

private fun CaptureAttemptEntity.toAuthorityRow(): GuidedAttemptAuthorityRow =
    GuidedAttemptAuthorityRow(
        commandToken = commandToken,
        sessionId = sessionId,
        poseId = poseId,
        poseIndex = poseIndex,
        attemptNumber = attemptNumber,
        triggerType = triggerType,
        lifecycleState = lifecycleState,
        reconciliationRequired = reconciliationRequired,
        capturedDeletionGeneration = capturedDeletionGeneration,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        confirmedAtEpochMillis = confirmedAtEpochMillis,
    )

private fun PrivateCaptureOutputEntity.toAuthorityRow(): GuidedPrivateOutputAuthorityRow =
    GuidedPrivateOutputAuthorityRow(
        commandToken = commandToken,
        burstOrdinal = burstOrdinal,
        relativePath = relativePath,
        byteCount = byteCount,
        durabilityState = durabilityState,
        capturedAtEpochMillis = capturedAtEpochMillis,
        integrityMetadata = integrityMetadata,
    )

private fun CaptureConfirmationReceiptEntity.toAuthorityRow(): GuidedReceiptAuthorityRow =
    GuidedReceiptAuthorityRow(
        commandToken = commandToken,
        fromPoseIndex = fromPoseIndex,
        toPoseIndex = toPoseIndex,
        appliedDeletionGeneration = appliedDeletionGeneration,
        appliedAtEpochMillis = appliedAtEpochMillis,
    )

private fun CaptureExportOutboxEntity.toAuthorityRow(): GuidedOutboxAuthorityRow =
    GuidedOutboxAuthorityRow(
        commandToken = commandToken,
        lifecycleState = lifecycleState,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        retryMetadata = retryMetadata,
    )

private fun CaptureExportOutputEntity.toAuthorityRow(): GuidedExportOutputAuthorityRow =
    GuidedExportOutputAuthorityRow(
        commandToken = commandToken,
        burstOrdinal = burstOrdinal,
        targetCollectionUri = targetCollectionUri,
        targetVolume = targetVolume,
        intendedDisplayName = intendedDisplayName,
        intendedRelativePath = intendedRelativePath,
        intendedMimeType = intendedMimeType,
        lifecycleState = lifecycleState,
        claimToken = claimToken,
        mediaUriString = mediaUriString,
        ambiguityState = ambiguityState,
        deletionGeneration = deletionGeneration,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
