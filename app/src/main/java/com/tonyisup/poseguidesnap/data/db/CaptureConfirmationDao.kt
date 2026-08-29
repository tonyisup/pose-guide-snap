package com.tonyisup.poseguidesnap.data.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.Query

internal data class DuplicateReceiptAuthority(
    @ColumnInfo(name = "from_pose_index")
    val fromPoseIndex: Int,
    @ColumnInfo(name = "to_pose_index")
    val toPoseIndex: Int?,
    @ColumnInfo(name = "applied_deletion_generation")
    val appliedDeletionGeneration: Long,
    @ColumnInfo(name = "applied_at_epoch_millis")
    val appliedAtEpochMillis: Long,
)

internal data class SessionReceiptStep(
    @ColumnInfo(name = "from_pose_index")
    val fromPoseIndex: Int,
    @ColumnInfo(name = "to_pose_index")
    val toPoseIndex: Int?,
    @ColumnInfo(name = "attempt_pose_index")
    val attemptPoseIndex: Int,
    @ColumnInfo(name = "attempt_lifecycle_state")
    val attemptLifecycleState: String,
)

@Dao
internal interface CaptureConfirmationDao {
    @Query(
        """
        SELECT from_pose_index, to_pose_index,
               applied_deletion_generation, applied_at_epoch_millis
        FROM capture_confirmation_receipts
        WHERE command_token = :commandToken
        """,
    )
    fun findReceipt(commandToken: String): DuplicateReceiptAuthority?

    @Query(
        """
        SELECT receipt.from_pose_index,
               receipt.to_pose_index,
               attempt.pose_index AS attempt_pose_index,
               attempt.lifecycle_state AS attempt_lifecycle_state
        FROM capture_confirmation_receipts AS receipt
        INNER JOIN capture_attempts AS attempt
            ON attempt.command_token = receipt.command_token
        WHERE attempt.session_id = :sessionId
        ORDER BY receipt.from_pose_index, receipt.command_token
        """,
    )
    fun findReceiptStepsForSession(sessionId: String): List<SessionReceiptStep>

    @Query("SELECT * FROM capture_attempts WHERE command_token = :commandToken")
    fun findAttempt(commandToken: String): CaptureAttemptEntity?

    @Query("SELECT * FROM shoot_sessions WHERE session_id = :sessionId")
    fun findSession(sessionId: String): ShootSessionEntity?

    @Query("SELECT * FROM shoots WHERE shoot_id = :shootId")
    fun findShoot(shootId: String): ShootEntity?

    @Query(
        """
        SELECT * FROM shoot_poses
        WHERE shoot_id = :shootId AND pose_index = :poseIndex
        """,
    )
    fun findPose(
        shootId: String,
        poseIndex: Int,
    ): ShootPoseEntity?

    @Query(
        """
        SELECT COUNT(*) FROM shoot_poses
        WHERE shoot_id = :shootId AND pose_index > :poseIndex
        """,
    )
    fun countPosesAfter(
        shootId: String,
        poseIndex: Int,
    ): Int

    @Insert
    fun insertPrivateOutputs(outputs: List<PrivateCaptureOutputEntity>)

    @Query(
        """
        UPDATE capture_attempts
        SET lifecycle_state = 'CONFIRMED',
            updated_at_epoch_millis = :confirmedAtEpochMillis,
            confirmed_at_epoch_millis = :confirmedAtEpochMillis
        WHERE command_token = :commandToken
          AND session_id = :sessionId
          AND pose_id = :poseId
          AND pose_index = :poseIndex
          AND attempt_number = :attemptNumber
          AND trigger_type = :triggerType
          AND lifecycle_state = 'CAPTURING'
          AND reconciliation_required = 0
          AND confirmed_at_epoch_millis IS NULL
          AND :capturedDeletionGeneration >= 0
          AND captured_deletion_generation = :capturedDeletionGeneration
          AND EXISTS (
              SELECT 1
              FROM shoot_sessions AS owning_session
              INNER JOIN shoots AS owning_shoot
                  ON owning_shoot.shoot_id = owning_session.shoot_id
              WHERE owning_session.session_id = capture_attempts.session_id
                AND owning_shoot.lifecycle_state = 'ACTIVE'
                AND owning_shoot.deletion_generation >= 0
                AND owning_shoot.deletion_generation = :capturedDeletionGeneration
          )
        """,
    )
    fun confirmAttempt(
        commandToken: String,
        sessionId: String,
        poseId: String,
        poseIndex: Int,
        attemptNumber: Long,
        triggerType: String,
        capturedDeletionGeneration: Long,
        confirmedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE shoot_sessions
        SET current_pose_index = :newPoseIndex,
            lifecycle_state = :newLifecycleState,
            updated_at_epoch_millis = :confirmedAtEpochMillis
        WHERE session_id = :sessionId
          AND shoot_id = :shootId
          AND current_pose_index = :expectedPoseIndex
          AND lifecycle_state = 'ACTIVE'
          AND :expectedDeletionGeneration >= 0
          AND EXISTS (
              SELECT 1
              FROM shoots AS owning_shoot
              WHERE owning_shoot.shoot_id = :shootId
                AND owning_shoot.lifecycle_state = 'ACTIVE'
                AND owning_shoot.deletion_generation >= 0
                AND owning_shoot.deletion_generation = :expectedDeletionGeneration
          )
        """,
    )
    fun advanceSession(
        sessionId: String,
        shootId: String,
        expectedPoseIndex: Int,
        newPoseIndex: Int,
        newLifecycleState: String,
        expectedDeletionGeneration: Long,
        confirmedAtEpochMillis: Long,
    ): Int

    @Insert
    fun insertReceipt(receipt: CaptureConfirmationReceiptEntity)

    @Insert
    fun insertOutbox(outbox: CaptureExportOutboxEntity)

    @Insert
    fun insertExportOutputs(outputs: List<CaptureExportOutputEntity>)

    @Query(
        """
        SELECT * FROM private_capture_outputs
        WHERE command_token = :commandToken
        ORDER BY burst_ordinal
        """,
    )
    fun findPrivateOutputs(commandToken: String): List<PrivateCaptureOutputEntity>

    @Query("SELECT * FROM capture_export_outboxes WHERE command_token = :commandToken")
    fun findOutbox(commandToken: String): CaptureExportOutboxEntity?

    @Query(
        """
        SELECT * FROM capture_export_outputs
        WHERE command_token = :commandToken
        ORDER BY burst_ordinal
        """,
    )
    fun findExportOutputs(commandToken: String): List<CaptureExportOutputEntity>

    @Query("SELECT COUNT(*) FROM private_capture_outputs WHERE command_token = :commandToken")
    fun countPrivateOutputs(commandToken: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM capture_confirmation_receipts
        WHERE command_token = :commandToken
        """,
    )
    fun countReceipts(commandToken: String): Int

    @Query("SELECT COUNT(*) FROM capture_export_outboxes WHERE command_token = :commandToken")
    fun countOutboxes(commandToken: String): Int

    @Query("SELECT COUNT(*) FROM capture_export_outputs WHERE command_token = :commandToken")
    fun countExportOutputs(commandToken: String): Int
}
