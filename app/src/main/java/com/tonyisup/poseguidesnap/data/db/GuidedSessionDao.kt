package com.tonyisup.poseguidesnap.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.tonyisup.poseguidesnap.data.GuidedAttemptAuthorityRow
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
        )
    }

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
