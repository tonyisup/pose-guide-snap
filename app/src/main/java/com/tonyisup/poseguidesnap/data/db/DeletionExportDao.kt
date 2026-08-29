package com.tonyisup.poseguidesnap.data.db

import androidx.room.Dao
import androidx.room.Query

@Dao
internal interface DeletionExportDao {
    @Query("SELECT * FROM shoots WHERE shoot_id = :shootId")
    fun findShoot(shootId: String): ShootEntity?

    @Query(
        """
        SELECT * FROM shoot_sessions
        WHERE shoot_id = :shootId
        ORDER BY session_id
        """,
    )
    fun findSessionsForShoot(shootId: String): List<ShootSessionEntity>

    @Query(
        """
        SELECT attempt.*
        FROM capture_attempts AS attempt
        INNER JOIN shoot_sessions AS session
            ON session.session_id = attempt.session_id
        WHERE session.shoot_id = :shootId
        ORDER BY attempt.command_token
        """,
    )
    fun findAttemptsForShoot(shootId: String): List<CaptureAttemptEntity>

    @Query(
        """
        SELECT private_output.*
        FROM private_capture_outputs AS private_output
        INNER JOIN capture_attempts AS attempt
            ON attempt.command_token = private_output.command_token
        INNER JOIN shoot_sessions AS session
            ON session.session_id = attempt.session_id
        WHERE session.shoot_id = :shootId
        ORDER BY private_output.command_token, private_output.burst_ordinal
        """,
    )
    fun findPrivateOutputsForShoot(shootId: String): List<PrivateCaptureOutputEntity>

    @Query(
        """
        SELECT DISTINCT attempt.*
        FROM capture_attempts AS attempt
        INNER JOIN shoot_sessions AS session
            ON session.session_id = attempt.session_id
        INNER JOIN capture_export_outboxes AS outbox
            ON outbox.command_token = attempt.command_token
        WHERE session.shoot_id = :shootId
        ORDER BY attempt.command_token
        """,
    )
    fun findExportAttemptsForShoot(shootId: String): List<CaptureAttemptEntity>

    @Query(
        """
        SELECT receipt.*
        FROM capture_confirmation_receipts AS receipt
        INNER JOIN capture_attempts AS attempt
            ON attempt.command_token = receipt.command_token
        INNER JOIN shoot_sessions AS session
            ON session.session_id = attempt.session_id
        WHERE session.shoot_id = :shootId
        ORDER BY receipt.command_token
        """,
    )
    fun findExportReceiptsForShoot(shootId: String): List<CaptureConfirmationReceiptEntity>

    @Query(
        """
        SELECT outbox.*
        FROM capture_export_outboxes AS outbox
        INNER JOIN capture_attempts AS attempt
            ON attempt.command_token = outbox.command_token
        INNER JOIN shoot_sessions AS session
            ON session.session_id = attempt.session_id
        WHERE session.shoot_id = :shootId
        ORDER BY outbox.command_token
        """,
    )
    fun findOutboxesForShoot(shootId: String): List<CaptureExportOutboxEntity>

    @Query(
        """
        SELECT output.*
        FROM capture_export_outputs AS output
        INNER JOIN capture_attempts AS attempt
            ON attempt.command_token = output.command_token
        INNER JOIN shoot_sessions AS session
            ON session.session_id = attempt.session_id
        WHERE session.shoot_id = :shootId
        ORDER BY output.command_token, output.burst_ordinal
        """,
    )
    fun findOutputsForShoot(shootId: String): List<CaptureExportOutputEntity>

    @Query(
        """
        SELECT * FROM capture_export_outputs
        WHERE command_token = :commandToken AND burst_ordinal = :burstOrdinal
        """,
    )
    fun findOutput(
        commandToken: String,
        burstOrdinal: Int,
    ): CaptureExportOutputEntity?

    @Query("SELECT * FROM capture_export_outputs WHERE claim_token = :claimToken")
    fun findOutputByClaimToken(claimToken: String): CaptureExportOutputEntity?

    @Query("SELECT * FROM capture_export_outboxes WHERE command_token = :commandToken")
    fun findOutbox(commandToken: String): CaptureExportOutboxEntity?

    @Query("SELECT * FROM capture_confirmation_receipts WHERE command_token = :commandToken")
    fun findReceipt(commandToken: String): CaptureConfirmationReceiptEntity?

    @Query("SELECT * FROM capture_attempts WHERE command_token = :commandToken")
    fun findAttempt(commandToken: String): CaptureAttemptEntity?

    @Query("SELECT * FROM shoot_sessions WHERE session_id = :sessionId")
    fun findSession(sessionId: String): ShootSessionEntity?

    @Query(
        """
        UPDATE capture_export_outputs
        SET lifecycle_state = 'CLAIMED',
            claim_token = :claimToken,
            updated_at_epoch_millis = :claimedAtEpochMillis
        WHERE command_token = :commandToken
          AND burst_ordinal = :burstOrdinal
          AND lifecycle_state = 'PENDING'
          AND claim_token IS NULL
          AND media_uri_string IS NULL
          AND ambiguity_state = 'NONE'
          AND EXISTS (
              SELECT 1
              FROM capture_export_outboxes AS outbox
              INNER JOIN capture_confirmation_receipts AS receipt
                  ON receipt.command_token = outbox.command_token
              INNER JOIN capture_attempts AS attempt
                  ON attempt.command_token = receipt.command_token
              INNER JOIN shoot_sessions AS session
                  ON session.session_id = attempt.session_id
              INNER JOIN shoots AS shoot
                  ON shoot.shoot_id = session.shoot_id
              WHERE outbox.command_token = capture_export_outputs.command_token
                AND outbox.lifecycle_state = 'PENDING'
                AND attempt.lifecycle_state = 'CONFIRMED'
                AND attempt.reconciliation_required = 0
                AND receipt.applied_deletion_generation =
                    capture_export_outputs.deletion_generation
                AND attempt.captured_deletion_generation =
                    capture_export_outputs.deletion_generation
                AND shoot.lifecycle_state = 'ACTIVE'
                AND shoot.deletion_generation = capture_export_outputs.deletion_generation
          )
        """,
    )
    fun claimOutput(
        commandToken: String,
        burstOrdinal: Int,
        claimToken: String,
        claimedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE shoots
        SET lifecycle_state = 'DELETING',
            deletion_generation = deletion_generation + 1,
            updated_at_epoch_millis = :requestedAtEpochMillis
        WHERE shoot_id = :shootId
          AND lifecycle_state = 'ACTIVE'
          AND deletion_generation >= 0
          AND deletion_generation = :previousGeneration
        """,
    )
    fun beginDeletion(
        shootId: String,
        previousGeneration: Long,
        requestedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE capture_export_outputs
        SET lifecycle_state = 'CANCELLED',
            updated_at_epoch_millis = :requestedAtEpochMillis
        WHERE lifecycle_state = 'PENDING'
          AND claim_token IS NULL
          AND media_uri_string IS NULL
          AND ambiguity_state = 'NONE'
          AND deletion_generation = :previousGeneration
          AND command_token IN (
              SELECT outbox.command_token
              FROM capture_export_outboxes AS outbox
              INNER JOIN capture_confirmation_receipts AS receipt
                  ON receipt.command_token = outbox.command_token
              INNER JOIN capture_attempts AS attempt
                  ON attempt.command_token = receipt.command_token
              INNER JOIN shoot_sessions AS session
                  ON session.session_id = attempt.session_id
              WHERE session.shoot_id = :shootId
                AND attempt.lifecycle_state = 'CONFIRMED'
                AND attempt.captured_deletion_generation = :previousGeneration
                AND receipt.applied_deletion_generation = :previousGeneration
          )
        """,
    )
    fun cancelUntouchedOutputs(
        shootId: String,
        previousGeneration: Long,
        requestedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE capture_export_outboxes
        SET lifecycle_state = 'CANCELLED',
            updated_at_epoch_millis = :requestedAtEpochMillis
        WHERE lifecycle_state = 'PENDING'
          AND command_token IN (
              SELECT attempt.command_token
              FROM capture_attempts AS attempt
              INNER JOIN shoot_sessions AS session
                  ON session.session_id = attempt.session_id
              WHERE session.shoot_id = :shootId
          )
          AND 3 = (
              SELECT COUNT(*)
              FROM capture_export_outputs AS output
              WHERE output.command_token = capture_export_outboxes.command_token
          )
          AND NOT EXISTS (
              SELECT 1
              FROM capture_export_outputs AS output
              WHERE output.command_token = capture_export_outboxes.command_token
                AND output.lifecycle_state != 'CANCELLED'
          )
        """,
    )
    fun cancelFullyCancelledOutboxes(
        shootId: String,
        requestedAtEpochMillis: Long,
    ): Int
}
