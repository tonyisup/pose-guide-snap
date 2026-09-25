package com.tonyisup.poseguidesnap.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

internal data class DeletionAuthorityClockRow(
    @ColumnInfo(name = "family_ordinal")
    val familyOrdinal: Int,
    @ColumnInfo(name = "primary_key")
    val primaryKey: String,
    @ColumnInfo(name = "primary_key_storage_type")
    val primaryKeyStorageType: String,
    @ColumnInfo(name = "secondary_key")
    val secondaryKey: Long?,
    @ColumnInfo(name = "secondary_key_storage_type")
    val secondaryKeyStorageType: String,
    @ColumnInfo(name = "owner_key")
    val ownerKey: String?,
    @ColumnInfo(name = "owner_key_storage_type")
    val ownerKeyStorageType: String,
    @ColumnInfo(name = "first_clock_value")
    val firstClockValue: Long,
    @ColumnInfo(name = "first_clock_storage_type")
    val firstClockStorageType: String,
    @ColumnInfo(name = "second_clock_value")
    val secondClockValue: Long?,
    @ColumnInfo(name = "second_clock_storage_type")
    val secondClockStorageType: String,
    @ColumnInfo(name = "third_clock_value")
    val thirdClockValue: Long?,
    @ColumnInfo(name = "third_clock_storage_type")
    val thirdClockStorageType: String,
) {
    override fun toString(): String = "DeletionAuthorityClockRow(redacted)"
}

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
        SELECT journal.*
        FROM capture_file_operations AS journal
        INNER JOIN capture_attempts AS attempt
            ON attempt.command_token = journal.command_token
        INNER JOIN shoot_sessions AS session
            ON session.session_id = attempt.session_id
        WHERE session.shoot_id = :shootId
        ORDER BY journal.command_token, journal.burst_ordinal
        """,
    )
    fun findJournalOperationsForShoot(shootId: String): List<CaptureFileOperationEntity>

    @Query(
        """
        SELECT CASE WHEN COUNT(*) = COALESCE(SUM(
            CASE WHEN
                typeof(attempt.command_token) = 'text'
                AND length(trim(attempt.command_token)) > 0
                AND typeof(attempt.session_id) = 'text'
                AND length(trim(attempt.session_id)) > 0
                AND typeof(attempt.pose_id) = 'text'
                AND length(trim(attempt.pose_id)) > 0
                AND typeof(attempt.pose_index) = 'integer'
                AND attempt.pose_index >= 0
                AND typeof(attempt.attempt_number) = 'integer'
                AND attempt.attempt_number >= 0
                AND typeof(attempt.trigger_type) = 'text'
                AND attempt.trigger_type IN ('MANUAL', 'AUTOMATIC')
                AND typeof(attempt.lifecycle_state) = 'text'
                AND attempt.lifecycle_state IN (
                    'REGISTERED', 'CAPTURING', 'FAILED_CLEANED',
                    'RECONCILIATION_REQUIRED', 'CONFIRMED'
                )
                AND typeof(attempt.reconciliation_required) = 'integer'
                AND attempt.reconciliation_required IN (0, 1)
                AND typeof(attempt.captured_deletion_generation) = 'integer'
                AND attempt.captured_deletion_generation >= 0
                AND typeof(attempt.created_at_epoch_millis) = 'integer'
                AND attempt.created_at_epoch_millis >= 0
                AND typeof(attempt.updated_at_epoch_millis) = 'integer'
                AND attempt.updated_at_epoch_millis >= attempt.created_at_epoch_millis
                AND (
                    (attempt.lifecycle_state = 'REGISTERED'
                        AND attempt.reconciliation_required = 0
                        AND typeof(attempt.confirmed_at_epoch_millis) = 'null'
                        AND attempt.updated_at_epoch_millis = attempt.created_at_epoch_millis)
                    OR
                    (attempt.lifecycle_state = 'CAPTURING'
                        AND attempt.reconciliation_required = 0
                        AND typeof(attempt.confirmed_at_epoch_millis) = 'null')
                    OR
                    (attempt.lifecycle_state = 'FAILED_CLEANED'
                        AND attempt.reconciliation_required = 0
                        AND typeof(attempt.confirmed_at_epoch_millis) = 'null')
                    OR
                    (attempt.lifecycle_state = 'RECONCILIATION_REQUIRED'
                        AND attempt.reconciliation_required = 1
                        AND typeof(attempt.confirmed_at_epoch_millis) = 'null')
                    OR
                    (attempt.lifecycle_state = 'CONFIRMED'
                        AND attempt.reconciliation_required = 0
                        AND typeof(attempt.confirmed_at_epoch_millis) = 'integer'
                        AND attempt.confirmed_at_epoch_millis =
                            attempt.updated_at_epoch_millis)
                )
            THEN 1 ELSE 0 END
        ), 0) THEN 1 ELSE 0 END
        FROM capture_attempts AS attempt
        INNER JOIN shoot_sessions AS session
          ON CAST(session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
        WHERE CAST(session.shoot_id AS BLOB) = CAST(:shootId AS BLOB)
        """,
    )
    fun hasValidCaptureAttemptStorageForShoot(shootId: String): Int

    @Query(
        """
        SELECT 0 AS family_ordinal,
               CASE WHEN typeof(shoot.shoot_id) = 'text' THEN shoot.shoot_id ELSE '' END
                   AS primary_key,
               typeof(shoot.shoot_id) AS primary_key_storage_type,
               CAST(NULL AS INTEGER) AS secondary_key,
               'absent' AS secondary_key_storage_type,
               CAST(NULL AS TEXT) AS owner_key,
               'absent' AS owner_key_storage_type,
               CASE WHEN typeof(shoot.created_at_epoch_millis) = 'integer'
                    THEN shoot.created_at_epoch_millis ELSE 0 END AS first_clock_value,
               typeof(shoot.created_at_epoch_millis) AS first_clock_storage_type,
               CASE WHEN typeof(shoot.updated_at_epoch_millis) = 'integer'
                    THEN shoot.updated_at_epoch_millis ELSE 0 END AS second_clock_value,
               typeof(shoot.updated_at_epoch_millis) AS second_clock_storage_type,
               CAST(NULL AS INTEGER) AS third_clock_value,
               'absent' AS third_clock_storage_type
        FROM shoots AS shoot
        WHERE CAST(shoot.shoot_id AS BLOB) = CAST(:shootId AS BLOB)
        UNION ALL
        SELECT 1,
               CASE WHEN typeof(session.session_id) = 'text' THEN session.session_id ELSE '' END,
               typeof(session.session_id),
               CAST(NULL AS INTEGER), 'absent',
               CASE WHEN typeof(session.shoot_id) = 'text' THEN session.shoot_id ELSE NULL END,
               typeof(session.shoot_id),
               CASE WHEN typeof(session.created_at_epoch_millis) = 'integer'
                    THEN session.created_at_epoch_millis ELSE 0 END,
               typeof(session.created_at_epoch_millis),
               CASE WHEN typeof(session.updated_at_epoch_millis) = 'integer'
                    THEN session.updated_at_epoch_millis ELSE 0 END,
               typeof(session.updated_at_epoch_millis),
               CAST(NULL AS INTEGER), 'absent'
        FROM shoot_sessions AS session
        WHERE CAST(session.shoot_id AS BLOB) = CAST(:shootId AS BLOB)
        UNION ALL
        SELECT 2,
               CASE WHEN typeof(attempt.command_token) = 'text' THEN attempt.command_token ELSE '' END,
               typeof(attempt.command_token),
               CAST(NULL AS INTEGER), 'absent',
               CASE WHEN typeof(attempt.session_id) = 'text' THEN attempt.session_id ELSE NULL END,
               typeof(attempt.session_id),
               CASE WHEN typeof(attempt.created_at_epoch_millis) = 'integer'
                    THEN attempt.created_at_epoch_millis ELSE 0 END,
               typeof(attempt.created_at_epoch_millis),
               CASE WHEN typeof(attempt.updated_at_epoch_millis) = 'integer'
                    THEN attempt.updated_at_epoch_millis ELSE 0 END,
               typeof(attempt.updated_at_epoch_millis),
               CASE WHEN typeof(attempt.confirmed_at_epoch_millis) IN ('integer', 'null')
                    THEN attempt.confirmed_at_epoch_millis ELSE NULL END,
               typeof(attempt.confirmed_at_epoch_millis)
        FROM capture_attempts AS attempt
        INNER JOIN shoot_sessions AS session
            ON CAST(session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
        WHERE CAST(session.shoot_id AS BLOB) = CAST(:shootId AS BLOB)
        UNION ALL
        SELECT 3,
               CASE WHEN typeof(private_output.command_token) = 'text'
                    THEN private_output.command_token ELSE '' END,
               typeof(private_output.command_token),
               CASE WHEN typeof(private_output.burst_ordinal) = 'integer'
                    THEN private_output.burst_ordinal ELSE NULL END,
               typeof(private_output.burst_ordinal),
               CASE WHEN typeof(attempt.command_token) = 'text'
                    THEN attempt.command_token ELSE NULL END,
               typeof(attempt.command_token),
               CASE WHEN typeof(private_output.captured_at_epoch_millis) = 'integer'
                    THEN private_output.captured_at_epoch_millis ELSE 0 END,
               typeof(private_output.captured_at_epoch_millis),
               CAST(NULL AS INTEGER), 'absent', CAST(NULL AS INTEGER), 'absent'
        FROM private_capture_outputs AS private_output
        INNER JOIN capture_attempts AS attempt
            ON CAST(attempt.command_token AS BLOB) = CAST(private_output.command_token AS BLOB)
        INNER JOIN shoot_sessions AS session
            ON CAST(session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
        WHERE CAST(session.shoot_id AS BLOB) = CAST(:shootId AS BLOB)
        UNION ALL
        SELECT 4,
               CASE WHEN typeof(receipt.command_token) = 'text' THEN receipt.command_token ELSE '' END,
               typeof(receipt.command_token),
               CAST(NULL AS INTEGER), 'absent',
               CASE WHEN typeof(attempt.command_token) = 'text'
                    THEN attempt.command_token ELSE NULL END,
               typeof(attempt.command_token),
               CASE WHEN typeof(receipt.applied_at_epoch_millis) = 'integer'
                    THEN receipt.applied_at_epoch_millis ELSE 0 END,
               typeof(receipt.applied_at_epoch_millis),
               CAST(NULL AS INTEGER), 'absent', CAST(NULL AS INTEGER), 'absent'
        FROM capture_confirmation_receipts AS receipt
        INNER JOIN capture_attempts AS attempt
            ON CAST(attempt.command_token AS BLOB) = CAST(receipt.command_token AS BLOB)
        INNER JOIN shoot_sessions AS session
            ON CAST(session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
        WHERE CAST(session.shoot_id AS BLOB) = CAST(:shootId AS BLOB)
        UNION ALL
        SELECT 5,
               CASE WHEN typeof(outbox.command_token) = 'text' THEN outbox.command_token ELSE '' END,
               typeof(outbox.command_token),
               CAST(NULL AS INTEGER), 'absent',
               CASE WHEN typeof(attempt.command_token) = 'text'
                    THEN attempt.command_token ELSE NULL END,
               typeof(attempt.command_token),
               CASE WHEN typeof(outbox.created_at_epoch_millis) = 'integer'
                    THEN outbox.created_at_epoch_millis ELSE 0 END,
               typeof(outbox.created_at_epoch_millis),
               CASE WHEN typeof(outbox.updated_at_epoch_millis) = 'integer'
                    THEN outbox.updated_at_epoch_millis ELSE 0 END,
               typeof(outbox.updated_at_epoch_millis),
               CAST(NULL AS INTEGER), 'absent'
        FROM capture_export_outboxes AS outbox
        INNER JOIN capture_attempts AS attempt
            ON CAST(attempt.command_token AS BLOB) = CAST(outbox.command_token AS BLOB)
        INNER JOIN shoot_sessions AS session
            ON CAST(session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
        WHERE CAST(session.shoot_id AS BLOB) = CAST(:shootId AS BLOB)
        UNION ALL
        SELECT 6,
               CASE WHEN typeof(output.command_token) = 'text' THEN output.command_token ELSE '' END,
               typeof(output.command_token),
               CASE WHEN typeof(output.burst_ordinal) = 'integer'
                    THEN output.burst_ordinal ELSE NULL END,
               typeof(output.burst_ordinal),
               CASE WHEN typeof(attempt.command_token) = 'text'
                    THEN attempt.command_token ELSE NULL END,
               typeof(attempt.command_token),
               CASE WHEN typeof(output.created_at_epoch_millis) = 'integer'
                    THEN output.created_at_epoch_millis ELSE 0 END,
               typeof(output.created_at_epoch_millis),
               CASE WHEN typeof(output.updated_at_epoch_millis) = 'integer'
                    THEN output.updated_at_epoch_millis ELSE 0 END,
               typeof(output.updated_at_epoch_millis),
               CAST(NULL AS INTEGER), 'absent'
        FROM capture_export_outputs AS output
        INNER JOIN capture_attempts AS attempt
            ON CAST(attempt.command_token AS BLOB) = CAST(output.command_token AS BLOB)
        INNER JOIN shoot_sessions AS session
            ON CAST(session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
        WHERE CAST(session.shoot_id AS BLOB) = CAST(:shootId AS BLOB)
        UNION ALL
        SELECT 7,
               CASE WHEN typeof(journal.command_token) = 'text' THEN journal.command_token ELSE '' END,
               typeof(journal.command_token),
               CASE WHEN typeof(journal.burst_ordinal) = 'integer'
                    THEN journal.burst_ordinal ELSE NULL END,
               typeof(journal.burst_ordinal),
               CASE WHEN typeof(attempt.command_token) = 'text'
                    THEN attempt.command_token ELSE NULL END,
               typeof(attempt.command_token),
               CASE WHEN typeof(journal.created_at_epoch_millis) = 'integer'
                    THEN journal.created_at_epoch_millis ELSE 0 END,
               typeof(journal.created_at_epoch_millis),
               CASE WHEN typeof(journal.updated_at_epoch_millis) = 'integer'
                    THEN journal.updated_at_epoch_millis ELSE 0 END,
               typeof(journal.updated_at_epoch_millis),
               CASE WHEN typeof(journal.captured_at_epoch_millis) IN ('integer', 'null')
                    THEN journal.captured_at_epoch_millis ELSE NULL END,
               typeof(journal.captured_at_epoch_millis)
        FROM capture_file_operations AS journal
        INNER JOIN capture_attempts AS attempt
            ON CAST(attempt.command_token AS BLOB) = CAST(journal.command_token AS BLOB)
        INNER JOIN shoot_sessions AS session
            ON CAST(session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
        WHERE CAST(session.shoot_id AS BLOB) = CAST(:shootId AS BLOB)
        ORDER BY family_ordinal, primary_key, secondary_key
        """,
    )
    // The CAST(... AS BLOB) correlation predicates above intentionally bypass every index:
    // byte-exact affinity-free matching is a security property of this projection, so that
    // type-aliased keys (e.g. BLOB bytes equal to a TEXT key) correlate here and surface as
    // storage-class evidence instead of silently vanishing from the deletion snapshot.
    fun findPreV4DeletionClockRowsForShoot(shootId: String): List<DeletionAuthorityClockRow>

    @Query(
        """
        SELECT CASE WHEN COUNT(*) = COALESCE(SUM(
            CASE WHEN
                typeof(journal.command_token) = 'text'
                AND length(trim(journal.command_token)) > 0
                AND typeof(journal.burst_ordinal) = 'integer'
                AND journal.burst_ordinal BETWEEN 0 AND 2
                AND typeof(journal.relative_final_path) = 'text'
                AND typeof(journal.relative_temp_path) = 'text'
                AND typeof(journal.relative_quarantine_path) = 'text'
                AND typeof(journal.stage) = 'text'
                AND journal.stage IN (
                    'EXPECTING_RESERVATION', 'WRITING_TEMP', 'TEMP_SYNCED',
                    'FINAL_RENAME_PENDING_SYNC', 'FINAL_DURABLE', 'CLEANUP_REQUIRED',
                    'CLEANUP_PENDING_SYNC', 'CLEANED_DURABLE', 'QUARANTINE_REQUIRED',
                    'QUARANTINE_PENDING_SYNC', 'QUARANTINE_DURABLE'
                )
                AND typeof(journal.byte_count) IN ('integer', 'null')
                AND typeof(journal.sha256) IN ('text', 'null')
                AND typeof(journal.captured_at_epoch_millis) IN ('integer', 'null')
                AND (
                    (journal.byte_count IS NULL
                        AND journal.sha256 IS NULL
                        AND journal.captured_at_epoch_millis IS NULL)
                    OR
                    (journal.byte_count > 0
                        AND length(journal.sha256) = 64
                        AND journal.sha256 NOT GLOB '*[^0-9a-f]*'
                        AND journal.captured_at_epoch_millis >= 0)
                )
                AND (
                    (journal.stage IN (
                        'EXPECTING_RESERVATION', 'WRITING_TEMP', 'CLEANED_DURABLE'
                    ) AND journal.byte_count IS NULL)
                    OR
                    (journal.stage IN (
                        'TEMP_SYNCED', 'FINAL_RENAME_PENDING_SYNC', 'FINAL_DURABLE',
                        'QUARANTINE_REQUIRED', 'QUARANTINE_PENDING_SYNC',
                        'QUARANTINE_DURABLE'
                    ) AND journal.byte_count IS NOT NULL)
                    OR journal.stage IN ('CLEANUP_REQUIRED', 'CLEANUP_PENDING_SYNC')
                )
                AND typeof(journal.last_failure_code) IN ('text', 'null')
                AND (
                    journal.last_failure_code IS NULL
                    OR journal.last_failure_code IN (
                        'RESERVATION_FAILED', 'WRITE_FAILED', 'FILE_SYNC_FAILED',
                        'RENAME_FAILED', 'DIRECTORY_SYNC_FAILED', 'DELETE_FAILED',
                        'STATE_MISMATCH', 'EVIDENCE_MISMATCH'
                    )
                )
                AND typeof(journal.reconciliation_required) = 'integer'
                AND journal.reconciliation_required IN (0, 1)
                AND journal.reconciliation_required =
                    (journal.last_failure_code IS NOT NULL)
                AND typeof(journal.created_at_epoch_millis) = 'integer'
                AND journal.created_at_epoch_millis >= 0
                AND typeof(journal.updated_at_epoch_millis) = 'integer'
                AND journal.updated_at_epoch_millis >= journal.created_at_epoch_millis
                AND (
                    journal.captured_at_epoch_millis IS NULL
                    OR journal.captured_at_epoch_millis BETWEEN
                        journal.created_at_epoch_millis AND journal.updated_at_epoch_millis
                )
            THEN 1 ELSE 0 END
        ), 0) THEN 1 ELSE 0 END
        FROM capture_file_operations AS journal
        INNER JOIN capture_attempts AS attempt
          ON CAST(attempt.command_token AS BLOB) = CAST(journal.command_token AS BLOB)
        INNER JOIN shoot_sessions AS session
          ON CAST(session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
        WHERE CAST(session.shoot_id AS BLOB) = CAST(:shootId AS BLOB)
        """,
    )
    fun hasValidCaptureFileOperationStorageForShoot(shootId: String): Int

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
          AND :requestedAtEpochMillis >= created_at_epoch_millis
          AND :requestedAtEpochMillis >= updated_at_epoch_millis
          AND NOT EXISTS (
              SELECT 1 FROM shoot_sessions AS session
              WHERE session.shoot_id = :shootId
                AND (
                    session.created_at_epoch_millis > :requestedAtEpochMillis
                    OR session.updated_at_epoch_millis > :requestedAtEpochMillis
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM capture_attempts AS attempt
              INNER JOIN shoot_sessions AS session ON session.session_id = attempt.session_id
              WHERE session.shoot_id = :shootId
                AND (
                    attempt.created_at_epoch_millis > :requestedAtEpochMillis
                    OR attempt.updated_at_epoch_millis > :requestedAtEpochMillis
                    OR attempt.confirmed_at_epoch_millis > :requestedAtEpochMillis
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM private_capture_outputs AS private_output
              INNER JOIN capture_attempts AS attempt
                ON attempt.command_token = private_output.command_token
              INNER JOIN shoot_sessions AS session ON session.session_id = attempt.session_id
              WHERE session.shoot_id = :shootId
                AND private_output.captured_at_epoch_millis > :requestedAtEpochMillis
          )
          AND NOT EXISTS (
              SELECT 1
              FROM capture_confirmation_receipts AS receipt
              INNER JOIN capture_attempts AS attempt ON attempt.command_token = receipt.command_token
              INNER JOIN shoot_sessions AS session ON session.session_id = attempt.session_id
              WHERE session.shoot_id = :shootId
                AND receipt.applied_at_epoch_millis > :requestedAtEpochMillis
          )
          AND NOT EXISTS (
              SELECT 1
              FROM capture_file_operations AS journal_clock
              INNER JOIN capture_attempts AS attempt
                ON attempt.command_token = journal_clock.command_token
              INNER JOIN shoot_sessions AS session ON session.session_id = attempt.session_id
              WHERE session.shoot_id = :shootId
                AND (
                    journal_clock.created_at_epoch_millis > :requestedAtEpochMillis
                    OR journal_clock.updated_at_epoch_millis > :requestedAtEpochMillis
                    OR journal_clock.captured_at_epoch_millis > :requestedAtEpochMillis
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM capture_export_outboxes AS outbox
              INNER JOIN capture_attempts AS attempt ON attempt.command_token = outbox.command_token
              INNER JOIN shoot_sessions AS session ON session.session_id = attempt.session_id
              WHERE session.shoot_id = :shootId
                AND (
                    outbox.created_at_epoch_millis > :requestedAtEpochMillis
                    OR outbox.updated_at_epoch_millis > :requestedAtEpochMillis
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM capture_export_outputs AS output
              INNER JOIN capture_attempts AS attempt ON attempt.command_token = output.command_token
              INNER JOIN shoot_sessions AS session ON session.session_id = attempt.session_id
              WHERE session.shoot_id = :shootId
                AND (
                    output.created_at_epoch_millis > :requestedAtEpochMillis
                    OR output.updated_at_epoch_millis > :requestedAtEpochMillis
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM capture_file_operations AS journal
              INNER JOIN capture_attempts AS attempt
                ON CAST(attempt.command_token AS BLOB) = CAST(journal.command_token AS BLOB)
              INNER JOIN shoot_sessions AS session
                ON CAST(session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
              WHERE session.shoot_id = :shootId
                AND journal.stage IN (
                    'WRITING_TEMP',
                    'FINAL_RENAME_PENDING_SYNC',
                    'CLEANUP_PENDING_SYNC',
                    'QUARANTINE_PENDING_SYNC'
                )
          )
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
          AND :requestedAtEpochMillis >= created_at_epoch_millis
          AND :requestedAtEpochMillis >= updated_at_epoch_millis
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
          AND :requestedAtEpochMillis >= created_at_epoch_millis
          AND :requestedAtEpochMillis >= updated_at_epoch_millis
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
