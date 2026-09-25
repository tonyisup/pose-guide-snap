package com.tonyisup.poseguidesnap.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage

@Dao
internal interface CaptureFileOperationDao {
    @Query(
        """
        SELECT * FROM capture_file_operations
        WHERE command_token = :commandToken
        ORDER BY burst_ordinal
        """,
    )
    fun findOperations(commandToken: String): List<CaptureFileOperationEntity>

    @Query(
        """
        SELECT * FROM capture_file_operations
        WHERE CAST(command_token AS BLOB) = CAST(:commandToken AS BLOB)
          AND CAST(burst_ordinal AS BLOB) = CAST(:burstOrdinal AS BLOB)
        """,
    )
    fun findOperationCandidates(
        commandToken: String,
        burstOrdinal: Int,
    ): List<CaptureFileOperationEntity>

    @Query(
        """
        SELECT CASE
            WHEN COUNT(*) = 0 THEN 0
            WHEN COUNT(*) != 1 THEN 2
            WHEN SUM(
                CASE WHEN
                    typeof(command_token) = 'text'
                    AND command_token = :commandToken
                    AND length(trim(command_token)) > 0
                    AND typeof(burst_ordinal) = 'integer'
                    AND burst_ordinal = :burstOrdinal
                    AND typeof(relative_final_path) = 'text'
                    AND relative_final_path = :relativeFinalPath
                    AND typeof(relative_temp_path) = 'text'
                    AND relative_temp_path = :relativeTempPath
                    AND typeof(relative_quarantine_path) = 'text'
                    AND relative_quarantine_path = :relativeQuarantinePath
                    AND typeof(stage) = 'text'
                    AND stage IN (
                        'EXPECTING_RESERVATION', 'WRITING_TEMP', 'TEMP_SYNCED',
                        'FINAL_RENAME_PENDING_SYNC', 'FINAL_DURABLE', 'CLEANUP_REQUIRED',
                        'CLEANUP_PENDING_SYNC', 'CLEANED_DURABLE', 'QUARANTINE_REQUIRED',
                        'QUARANTINE_PENDING_SYNC', 'QUARANTINE_DURABLE'
                    )
                    AND typeof(byte_count) IN ('integer', 'null')
                    AND typeof(sha256) IN ('text', 'null')
                    AND typeof(captured_at_epoch_millis) IN ('integer', 'null')
                    AND (
                        (byte_count IS NULL AND sha256 IS NULL AND captured_at_epoch_millis IS NULL)
                        OR
                        (byte_count > 0
                            AND length(sha256) = 64
                            AND sha256 NOT GLOB '*[^0-9a-f]*'
                            AND captured_at_epoch_millis >= 0)
                    )
                    AND (
                        (stage IN ('EXPECTING_RESERVATION', 'WRITING_TEMP', 'CLEANED_DURABLE')
                            AND byte_count IS NULL)
                        OR
                        (stage IN (
                            'TEMP_SYNCED', 'FINAL_RENAME_PENDING_SYNC', 'FINAL_DURABLE',
                            'QUARANTINE_REQUIRED', 'QUARANTINE_PENDING_SYNC',
                            'QUARANTINE_DURABLE'
                        ) AND byte_count IS NOT NULL)
                        OR stage IN ('CLEANUP_REQUIRED', 'CLEANUP_PENDING_SYNC')
                    )
                    AND typeof(last_failure_code) IN ('text', 'null')
                    AND (
                        last_failure_code IS NULL
                        OR last_failure_code IN (
                            'RESERVATION_FAILED', 'WRITE_FAILED', 'FILE_SYNC_FAILED',
                            'RENAME_FAILED', 'DIRECTORY_SYNC_FAILED', 'DELETE_FAILED',
                            'STATE_MISMATCH', 'EVIDENCE_MISMATCH'
                        )
                    )
                    AND typeof(reconciliation_required) = 'integer'
                    AND reconciliation_required IN (0, 1)
                    AND reconciliation_required = (last_failure_code IS NOT NULL)
                    AND typeof(created_at_epoch_millis) = 'integer'
                    AND created_at_epoch_millis >= 0
                    AND typeof(updated_at_epoch_millis) = 'integer'
                    AND updated_at_epoch_millis >= created_at_epoch_millis
                    AND (
                        captured_at_epoch_millis IS NULL
                        OR captured_at_epoch_millis BETWEEN
                            created_at_epoch_millis AND updated_at_epoch_millis
                    )
                THEN 1 ELSE 0 END
            ) = 1 THEN 1
            ELSE 2
        END
        FROM capture_file_operations
        WHERE CAST(command_token AS BLOB) = CAST(:commandToken AS BLOB)
          AND CAST(burst_ordinal AS BLOB) = CAST(:burstOrdinal AS BLOB)
        """,
    )
    fun classifyOperationStorage(
        commandToken: String,
        burstOrdinal: Int,
        relativeFinalPath: String,
        relativeTempPath: String,
        relativeQuarantinePath: String,
    ): Int

    /**
     * Scalar, storage-sensitive owner classification for every journal mutation. Keeping this
     * read separate from entity mapping prevents SQLite coercion from turning malformed authority
     * bytes into an apparently valid Kotlin object.
     */
    @Query(
        """
        SELECT CASE
            WHEN COUNT(*) != 1 THEN 0
            WHEN SUM(
                CASE WHEN
                    typeof(attempt.command_token) = 'text'
                    AND length(trim(attempt.command_token)) > 0
                    AND attempt.command_token = :commandToken
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
                    AND attempt.created_at_epoch_millis = :operationCreatedAtEpochMillis
                    AND typeof(attempt.updated_at_epoch_millis) = 'integer'
                    AND attempt.updated_at_epoch_millis >= attempt.created_at_epoch_millis
                    AND typeof(attempt.confirmed_at_epoch_millis) IN ('integer', 'null')
                    AND (
                        (attempt.lifecycle_state IN ('REGISTERED', 'CAPTURING', 'FAILED_CLEANED')
                            AND attempt.reconciliation_required = 0
                            AND attempt.confirmed_at_epoch_millis IS NULL)
                        OR
                        (attempt.lifecycle_state = 'RECONCILIATION_REQUIRED'
                            AND attempt.reconciliation_required = 1
                            AND attempt.confirmed_at_epoch_millis IS NULL)
                        OR
                        (attempt.lifecycle_state = 'CONFIRMED'
                            AND attempt.reconciliation_required = 0
                            AND attempt.confirmed_at_epoch_millis IS NOT NULL
                            AND attempt.confirmed_at_epoch_millis >= attempt.updated_at_epoch_millis)
                    )
                    AND typeof(owning_session.session_id) = 'text'
                    AND length(trim(owning_session.session_id)) > 0
                    AND owning_session.session_id = attempt.session_id
                    AND typeof(owning_session.shoot_id) = 'text'
                    AND length(trim(owning_session.shoot_id)) > 0
                    AND typeof(owning_session.current_pose_index) = 'integer'
                    AND owning_session.current_pose_index >= 0
                    AND typeof(owning_session.next_attempt_number) = 'integer'
                    AND owning_session.next_attempt_number > attempt.attempt_number
                    AND typeof(owning_session.lifecycle_state) = 'text'
                    AND owning_session.lifecycle_state IN ('ACTIVE', 'COMPLETED')
                    AND typeof(owning_session.created_at_epoch_millis) = 'integer'
                    AND owning_session.created_at_epoch_millis >= 0
                    AND typeof(owning_session.updated_at_epoch_millis) = 'integer'
                    AND owning_session.updated_at_epoch_millis >=
                        owning_session.created_at_epoch_millis
                    AND typeof(owning_shoot.shoot_id) = 'text'
                    AND length(trim(owning_shoot.shoot_id)) > 0
                    AND owning_shoot.shoot_id = owning_session.shoot_id
                    AND typeof(owning_shoot.name) = 'text'
                    AND typeof(owning_shoot.lifecycle_state) = 'text'
                    AND owning_shoot.lifecycle_state IN ('ACTIVE', 'DELETING')
                    AND typeof(owning_shoot.deletion_generation) = 'integer'
                    AND owning_shoot.deletion_generation >= 0
                    AND typeof(owning_shoot.created_at_epoch_millis) = 'integer'
                    AND owning_shoot.created_at_epoch_millis >= 0
                    AND typeof(owning_shoot.updated_at_epoch_millis) = 'integer'
                    AND owning_shoot.updated_at_epoch_millis >= owning_shoot.created_at_epoch_millis
                THEN 1 ELSE 0 END
            ) != 1 THEN 0
            WHEN SUM(
                CASE WHEN
                    current_pose.rowid IS NOT NULL
                    AND (
                        typeof(current_pose.shoot_id) != 'text'
                        OR length(trim(current_pose.shoot_id)) = 0
                        OR current_pose.shoot_id != owning_session.shoot_id
                        OR typeof(current_pose.pose_index) != 'integer'
                        OR current_pose.pose_index < 0
                        OR typeof(current_pose.pose_id) != 'text'
                        OR length(trim(current_pose.pose_id)) = 0
                    )
                THEN 1 ELSE 0 END
            ) != 0 THEN 0
            WHEN SUM(
                CASE WHEN
                    :allowReconciliationAttempt = 0 AND (
                        :operationStage != 'EXPECTING_RESERVATION'
                        OR :operationFailureCode IS NOT NULL
                        OR :operationUpdatedAtEpochMillis > :operationCreatedAtEpochMillis
                    )
                    AND (
                        :operationUpdatedAtEpochMillis <= :operationCreatedAtEpochMillis
                        OR :operationUpdatedAtEpochMillis < attempt.updated_at_epoch_millis
                        OR (
                            :operationCapturedAtEpochMillis IS NOT NULL
                            AND :operationCapturedAtEpochMillis < attempt.updated_at_epoch_millis
                        )
                    )
                THEN 1 ELSE 0 END
            ) != 0 THEN 0
            WHEN SUM(
                CASE WHEN
                    owning_shoot.lifecycle_state != 'ACTIVE'
                    OR owning_shoot.deletion_generation != attempt.captured_deletion_generation
                THEN 1 ELSE 0 END
            ) = 1 THEN 1
            WHEN SUM(
                CASE WHEN
                    NOT (
                        (attempt.lifecycle_state = 'CAPTURING'
                            AND attempt.reconciliation_required = 0)
                        OR
                        (:allowReconciliationAttempt = 1
                            AND attempt.lifecycle_state = 'RECONCILIATION_REQUIRED'
                            AND attempt.reconciliation_required = 1)
                    )
                    OR owning_session.lifecycle_state != 'ACTIVE'
                    OR current_pose.rowid IS NULL
                    OR current_pose.pose_index != attempt.pose_index
                    OR current_pose.pose_id != attempt.pose_id
                THEN 1 ELSE 0 END
            ) = 1 THEN 2
            WHEN SUM(
                CASE WHEN :targetUpdatedAtEpochMillis < attempt.updated_at_epoch_millis
                THEN 1 ELSE 0 END
            ) = 1 THEN 3
            WHEN SUM(
                CASE WHEN
                    :allowReconciliationAttempt = 0
                    AND
                    :targetCapturedAtEpochMillis IS NOT NULL
                    AND :targetCapturedAtEpochMillis < attempt.updated_at_epoch_millis
                THEN 1 ELSE 0 END
            ) = 1 THEN 4
            ELSE 5
        END
        FROM capture_attempts AS attempt
        LEFT JOIN shoot_sessions AS owning_session
          ON CAST(owning_session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
        LEFT JOIN shoots AS owning_shoot
          ON CAST(owning_shoot.shoot_id AS BLOB) = CAST(owning_session.shoot_id AS BLOB)
        LEFT JOIN shoot_poses AS current_pose
          ON CAST(current_pose.shoot_id AS BLOB) = CAST(owning_session.shoot_id AS BLOB)
         AND CAST(current_pose.pose_index AS BLOB) =
             CAST(owning_session.current_pose_index AS BLOB)
        WHERE CAST(attempt.command_token AS BLOB) = CAST(:commandToken AS BLOB)
        """,
    )
    fun classifyMutationOwner(
        commandToken: String,
        operationCreatedAtEpochMillis: Long,
        operationStage: String,
        operationFailureCode: String?,
        operationUpdatedAtEpochMillis: Long,
        operationCapturedAtEpochMillis: Long?,
        targetUpdatedAtEpochMillis: Long,
        targetCapturedAtEpochMillis: Long?,
        allowReconciliationAttempt: Boolean,
    ): Int

    @Query(
        """
        UPDATE capture_file_operations
        SET stage = :targetStage,
            byte_count = :targetByteCount,
            sha256 = :targetSha256,
            captured_at_epoch_millis = :targetCapturedAtEpochMillis,
            last_failure_code = :targetFailureCode,
            reconciliation_required = :targetReconciliationRequired,
            updated_at_epoch_millis = :targetUpdatedAtEpochMillis
        WHERE CAST(command_token AS BLOB) = CAST(:commandToken AS BLOB)
          AND burst_ordinal = :burstOrdinal
          AND typeof(command_token) = 'text'
          AND command_token = :commandToken
          AND typeof(burst_ordinal) = 'integer'
          AND typeof(created_at_epoch_millis) = 'integer'
          AND created_at_epoch_millis >= 0
          AND stage = :expectedStage
          AND updated_at_epoch_millis = :expectedUpdatedAtEpochMillis
          AND :targetUpdatedAtEpochMillis > :expectedUpdatedAtEpochMillis
          AND EXISTS (
              SELECT 1
              FROM capture_attempts AS attempt
              INNER JOIN shoot_sessions AS session
                ON CAST(session.session_id AS BLOB) = CAST(attempt.session_id AS BLOB)
              INNER JOIN shoots AS shoot
                ON CAST(shoot.shoot_id AS BLOB) = CAST(session.shoot_id AS BLOB)
              INNER JOIN shoot_poses AS pose
                ON CAST(pose.shoot_id AS BLOB) = CAST(shoot.shoot_id AS BLOB)
               AND pose.pose_index = session.current_pose_index
              WHERE CAST(attempt.command_token AS BLOB) = CAST(:commandToken AS BLOB)
                AND typeof(attempt.command_token) = 'text'
                AND attempt.command_token = :commandToken
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
                AND typeof(attempt.reconciliation_required) = 'integer'
                AND (
                    (attempt.lifecycle_state = 'CAPTURING'
                        AND attempt.reconciliation_required = 0)
                    OR
                    (:allowReconciliationAttempt = 1
                        AND attempt.lifecycle_state = 'RECONCILIATION_REQUIRED'
                        AND attempt.reconciliation_required = 1)
                )
                AND typeof(attempt.captured_deletion_generation) = 'integer'
                AND attempt.captured_deletion_generation >= 0
                AND typeof(attempt.created_at_epoch_millis) = 'integer'
                AND attempt.created_at_epoch_millis =
                    capture_file_operations.created_at_epoch_millis
                AND typeof(attempt.updated_at_epoch_millis) = 'integer'
                AND attempt.updated_at_epoch_millis >= attempt.created_at_epoch_millis
                AND typeof(attempt.confirmed_at_epoch_millis) = 'null'
                AND :targetUpdatedAtEpochMillis >= attempt.updated_at_epoch_millis
                AND (:allowReconciliationAttempt = 1 OR :targetCapturedAtEpochMillis IS NULL OR
                    :targetCapturedAtEpochMillis >= attempt.updated_at_epoch_millis)
                AND typeof(session.session_id) = 'text'
                AND length(trim(session.session_id)) > 0
                AND session.session_id = attempt.session_id
                AND typeof(session.shoot_id) = 'text'
                AND length(trim(session.shoot_id)) > 0
                AND typeof(session.current_pose_index) = 'integer'
                AND session.current_pose_index >= 0
                AND typeof(session.next_attempt_number) = 'integer'
                AND session.next_attempt_number > attempt.attempt_number
                AND typeof(session.lifecycle_state) = 'text'
                AND session.lifecycle_state = 'ACTIVE'
                AND typeof(session.created_at_epoch_millis) = 'integer'
                AND session.created_at_epoch_millis >= 0
                AND typeof(session.updated_at_epoch_millis) = 'integer'
                AND session.updated_at_epoch_millis >= session.created_at_epoch_millis
                AND typeof(pose.shoot_id) = 'text'
                AND pose.shoot_id = session.shoot_id
                AND typeof(pose.pose_index) = 'integer'
                AND pose.pose_index >= 0
                AND typeof(pose.pose_id) = 'text'
                AND length(trim(pose.pose_id)) > 0
                AND pose.pose_id = attempt.pose_id
                AND pose.pose_index = attempt.pose_index
                AND typeof(shoot.shoot_id) = 'text'
                AND shoot.shoot_id = session.shoot_id
                AND length(trim(shoot.shoot_id)) > 0
                AND typeof(shoot.name) = 'text'
                AND typeof(shoot.lifecycle_state) = 'text'
                AND shoot.lifecycle_state = 'ACTIVE'
                AND typeof(shoot.deletion_generation) = 'integer'
                AND shoot.deletion_generation >= 0
                AND shoot.deletion_generation = attempt.captured_deletion_generation
                AND typeof(shoot.created_at_epoch_millis) = 'integer'
                AND shoot.created_at_epoch_millis >= 0
                AND typeof(shoot.updated_at_epoch_millis) = 'integer'
                AND shoot.updated_at_epoch_millis >= shoot.created_at_epoch_millis
          )
        """,
    )
    fun compareAndSetOperation(
        commandToken: String,
        burstOrdinal: Int,
        expectedStage: CaptureFileOperationStage,
        expectedUpdatedAtEpochMillis: Long,
        targetStage: CaptureFileOperationStage,
        targetByteCount: Long?,
        targetSha256: String?,
        targetCapturedAtEpochMillis: Long?,
        targetFailureCode: com.tonyisup.poseguidesnap.data.CaptureFileFailureCode?,
        targetReconciliationRequired: Boolean,
        targetUpdatedAtEpochMillis: Long,
        allowReconciliationAttempt: Boolean,
    ): Int

    // Value-insensitive residual-authority count for the Task 3D fail-closed confirmation guard;
    // deliberately scalar so malformed rows cannot be coerced or filtered during entity mapping.
    @Query(
        "SELECT COUNT(*) FROM capture_file_operations " +
            "WHERE CAST(command_token AS BLOB) = CAST(:commandToken AS BLOB)",
    )
    fun countOperationsForToken(commandToken: String): Long

    @Query(
        """
        SELECT CASE
            WHEN COUNT(*) != 3 THEN 0
            WHEN SUM(CASE WHEN burst_ordinal = 0 THEN 1 ELSE 0 END) != 1 THEN 0
            WHEN SUM(CASE WHEN burst_ordinal = 1 THEN 1 ELSE 0 END) != 1 THEN 0
            WHEN SUM(CASE WHEN burst_ordinal = 2 THEN 1 ELSE 0 END) != 1 THEN 0
            WHEN SUM(
                CASE WHEN
                    typeof(command_token) = 'text'
                    AND command_token = :commandToken
                    AND length(trim(command_token)) > 0
                    AND typeof(burst_ordinal) = 'integer'
                    AND burst_ordinal IN (0, 1, 2)
                    AND typeof(relative_final_path) = 'text'
                    AND typeof(relative_temp_path) = 'text'
                    AND typeof(relative_quarantine_path) = 'text'
                    AND CASE burst_ordinal
                        WHEN 0 THEN
                            relative_final_path = :relativeFinalPath0
                            AND relative_temp_path = :relativeTempPath0
                            AND relative_quarantine_path = :relativeQuarantinePath0
                        WHEN 1 THEN
                            relative_final_path = :relativeFinalPath1
                            AND relative_temp_path = :relativeTempPath1
                            AND relative_quarantine_path = :relativeQuarantinePath1
                        WHEN 2 THEN
                            relative_final_path = :relativeFinalPath2
                            AND relative_temp_path = :relativeTempPath2
                            AND relative_quarantine_path = :relativeQuarantinePath2
                        ELSE 0
                    END
                    AND typeof(stage) = 'text'
                    AND stage = 'FINAL_DURABLE'
                    AND typeof(byte_count) = 'integer'
                    AND byte_count > 0
                    AND typeof(sha256) = 'text'
                    AND length(sha256) = 64
                    AND sha256 NOT GLOB '*[^0-9a-f]*'
                    AND typeof(captured_at_epoch_millis) = 'integer'
                    AND captured_at_epoch_millis >= :attemptUpdatedAtEpochMillis
                    AND typeof(last_failure_code) = 'null'
                    AND typeof(reconciliation_required) = 'integer'
                    AND reconciliation_required = 0
                    AND typeof(created_at_epoch_millis) = 'integer'
                    AND created_at_epoch_millis = :attemptCreatedAtEpochMillis
                    AND typeof(updated_at_epoch_millis) = 'integer'
                    AND updated_at_epoch_millis > created_at_epoch_millis
                    AND updated_at_epoch_millis >= :attemptUpdatedAtEpochMillis
                    AND captured_at_epoch_millis BETWEEN
                        created_at_epoch_millis AND updated_at_epoch_millis
                THEN 1 ELSE 0 END
            ) = 3 THEN 1
            ELSE 0
        END
        FROM capture_file_operations
        WHERE CAST(command_token AS BLOB) = CAST(:commandToken AS BLOB)
        """,
    )
    fun hasValidFinalDurableConfirmationJournal(
        commandToken: String,
        attemptCreatedAtEpochMillis: Long,
        attemptUpdatedAtEpochMillis: Long,
        relativeFinalPath0: String,
        relativeTempPath0: String,
        relativeQuarantinePath0: String,
        relativeFinalPath1: String,
        relativeTempPath1: String,
        relativeQuarantinePath1: String,
        relativeFinalPath2: String,
        relativeTempPath2: String,
        relativeQuarantinePath2: String,
    ): Int

    @Query(
        """
        DELETE FROM capture_file_operations
        WHERE CAST(command_token AS BLOB) = CAST(:commandToken AS BLOB)
        """,
    )
    fun deleteOperationsForConfirmedAttempt(commandToken: String): Int

    @Insert
    fun insertOperations(operations: List<CaptureFileOperationEntity>): List<Long>

    @Query(
        """
        SELECT CASE
            WHEN COUNT(*) = 0 THEN 0
            WHEN SUM(
                CASE WHEN
                    typeof(attempt.command_token) = 'text'
                    AND typeof(attempt.session_id) = 'text'
                    AND typeof(attempt.pose_id) = 'text'
                    AND typeof(attempt.pose_index) = 'integer'
                    AND typeof(attempt.attempt_number) = 'integer'
                    AND typeof(attempt.trigger_type) = 'text'
                THEN 1 ELSE 0 END
            ) != 1 THEN 3
            WHEN SUM(
                CASE WHEN
                    attempt.session_id = :sessionId
                    AND attempt.pose_id = :poseId
                    AND attempt.pose_index = :poseIndex
                    AND attempt.attempt_number = :attemptNumber
                    AND attempt.trigger_type = :triggerType
                THEN 1 ELSE 0 END
            ) != 1 THEN 1
            WHEN SUM(
                CASE WHEN
                    typeof(attempt.lifecycle_state) = 'text'
                    AND typeof(attempt.reconciliation_required) = 'integer'
                    AND typeof(attempt.captured_deletion_generation) = 'integer'
                    AND typeof(attempt.created_at_epoch_millis) = 'integer'
                    AND typeof(attempt.updated_at_epoch_millis) = 'integer'
                    AND typeof(attempt.confirmed_at_epoch_millis) = 'null'
                    AND attempt.lifecycle_state IN ('REGISTERED', 'CAPTURING')
                    AND attempt.reconciliation_required = 0
                    AND attempt.captured_deletion_generation >= 0
                    AND attempt.created_at_epoch_millis >= 0
                    AND attempt.updated_at_epoch_millis >= attempt.created_at_epoch_millis
                    AND (
                        attempt.lifecycle_state != 'REGISTERED'
                        OR attempt.updated_at_epoch_millis = attempt.created_at_epoch_millis
                    )
                    AND (
                        SELECT CASE WHEN
                            COUNT(*) = 3
                            AND SUM(CASE WHEN journal.burst_ordinal = 0 THEN 1 ELSE 0 END) = 1
                            AND SUM(CASE WHEN journal.burst_ordinal = 1 THEN 1 ELSE 0 END) = 1
                            AND SUM(CASE WHEN journal.burst_ordinal = 2 THEN 1 ELSE 0 END) = 1
                            AND SUM(
                                CASE WHEN
                                    typeof(journal.command_token) = 'text'
                                    AND typeof(journal.burst_ordinal) = 'integer'
                                    AND typeof(journal.relative_final_path) = 'text'
                                    AND typeof(journal.relative_temp_path) = 'text'
                                    AND typeof(journal.relative_quarantine_path) = 'text'
                                    AND typeof(journal.stage) = 'text'
                                    AND typeof(journal.byte_count) = 'null'
                                    AND typeof(journal.sha256) = 'null'
                                    AND typeof(journal.captured_at_epoch_millis) = 'null'
                                    AND typeof(journal.last_failure_code) = 'null'
                                    AND typeof(journal.reconciliation_required) = 'integer'
                                    AND typeof(journal.created_at_epoch_millis) = 'integer'
                                    AND typeof(journal.updated_at_epoch_millis) = 'integer'
                                    AND journal.stage = 'EXPECTING_RESERVATION'
                                    AND journal.reconciliation_required = 0
                                    AND journal.created_at_epoch_millis =
                                        attempt.created_at_epoch_millis
                                    AND journal.updated_at_epoch_millis =
                                        attempt.created_at_epoch_millis
                                    AND CASE journal.burst_ordinal
                                        WHEN 0 THEN
                                            journal.relative_final_path = :relativeFinalPath0
                                            AND journal.relative_temp_path = :relativeTempPath0
                                            AND journal.relative_quarantine_path =
                                                :relativeQuarantinePath0
                                        WHEN 1 THEN
                                            journal.relative_final_path = :relativeFinalPath1
                                            AND journal.relative_temp_path = :relativeTempPath1
                                            AND journal.relative_quarantine_path =
                                                :relativeQuarantinePath1
                                        WHEN 2 THEN
                                            journal.relative_final_path = :relativeFinalPath2
                                            AND journal.relative_temp_path = :relativeTempPath2
                                            AND journal.relative_quarantine_path =
                                                :relativeQuarantinePath2
                                        ELSE 0
                                    END
                                THEN 1 ELSE 0 END
                            ) = 3
                        THEN 1 ELSE 0 END
                        FROM capture_file_operations AS journal
                        WHERE CAST(journal.command_token AS BLOB) =
                            CAST(attempt.command_token AS BLOB)
                    ) = 1
                THEN 1 ELSE 0 END
            ) = 1 THEN 2
            ELSE 3
        END
        FROM capture_attempts AS attempt
        WHERE CAST(attempt.command_token AS BLOB) = CAST(:commandToken AS BLOB)
        """,
    )
    fun classifyRegistrationReplayAuthority(
        commandToken: String,
        sessionId: String,
        poseId: String,
        poseIndex: Int,
        attemptNumber: Long,
        triggerType: String,
        relativeFinalPath0: String,
        relativeTempPath0: String,
        relativeQuarantinePath0: String,
        relativeFinalPath1: String,
        relativeTempPath1: String,
        relativeQuarantinePath1: String,
        relativeFinalPath2: String,
        relativeTempPath2: String,
        relativeQuarantinePath2: String,
    ): Int

    @Query(
        """
        SELECT CASE
            WHEN COUNT(*) != 1 THEN 2
            WHEN SUM(
                CASE WHEN
                    typeof(attempt.command_token) = 'text'
                    AND length(trim(attempt.command_token)) > 0
                    AND attempt.command_token = :commandToken
                    AND typeof(attempt.session_id) = 'text'
                    AND length(trim(attempt.session_id)) > 0
                    AND attempt.session_id = :sessionId
                    AND typeof(attempt.pose_id) = 'text'
                    AND length(trim(attempt.pose_id)) > 0
                    AND typeof(attempt.pose_index) = 'integer'
                    AND attempt.pose_index >= 0
                    AND typeof(attempt.attempt_number) = 'integer'
                    AND attempt.attempt_number >= 0
                    AND typeof(attempt.trigger_type) = 'text'
                    AND attempt.trigger_type IN ('MANUAL', 'AUTOMATIC')
                    AND typeof(attempt.lifecycle_state) = 'text'
                    AND attempt.lifecycle_state = :expectedLifecycleState
                    AND :expectedLifecycleState IN ('REGISTERED', 'CAPTURING')
                    AND typeof(attempt.reconciliation_required) = 'integer'
                    AND attempt.reconciliation_required = 0
                    AND typeof(attempt.captured_deletion_generation) = 'integer'
                    AND attempt.captured_deletion_generation >= 0
                    AND typeof(attempt.created_at_epoch_millis) = 'integer'
                    AND attempt.created_at_epoch_millis >= 0
                    AND typeof(attempt.updated_at_epoch_millis) = 'integer'
                    AND attempt.updated_at_epoch_millis >=
                        attempt.created_at_epoch_millis
                    AND (
                        :expectedLifecycleState != 'REGISTERED'
                        OR attempt.updated_at_epoch_millis =
                            attempt.created_at_epoch_millis
                    )
                    AND typeof(attempt.confirmed_at_epoch_millis) = 'null'
                    AND (
                        SELECT CASE WHEN
                            COUNT(*) = 3
                            AND SUM(
                                CASE WHEN journal.burst_ordinal = 0 THEN 1 ELSE 0 END
                            ) = 1
                            AND SUM(
                                CASE WHEN journal.burst_ordinal = 1 THEN 1 ELSE 0 END
                            ) = 1
                            AND SUM(
                                CASE WHEN journal.burst_ordinal = 2 THEN 1 ELSE 0 END
                            ) = 1
                            AND SUM(
                                CASE WHEN
                                    typeof(journal.command_token) = 'text'
                                    AND journal.command_token = attempt.command_token
                                    AND typeof(journal.burst_ordinal) = 'integer'
                                    AND typeof(journal.relative_final_path) = 'text'
                                    AND typeof(journal.relative_temp_path) = 'text'
                                    AND typeof(journal.relative_quarantine_path) = 'text'
                                    AND typeof(journal.stage) = 'text'
                                    AND journal.stage = 'EXPECTING_RESERVATION'
                                    AND typeof(journal.byte_count) = 'null'
                                    AND typeof(journal.sha256) = 'null'
                                    AND typeof(journal.captured_at_epoch_millis) = 'null'
                                    AND typeof(journal.last_failure_code) = 'null'
                                    AND typeof(journal.reconciliation_required) = 'integer'
                                    AND journal.reconciliation_required = 0
                                    AND typeof(journal.created_at_epoch_millis) = 'integer'
                                    AND journal.created_at_epoch_millis >= 0
                                    AND typeof(journal.updated_at_epoch_millis) = 'integer'
                                    AND journal.updated_at_epoch_millis >=
                                        journal.created_at_epoch_millis
                                    AND journal.created_at_epoch_millis =
                                        attempt.created_at_epoch_millis
                                    AND journal.updated_at_epoch_millis =
                                        attempt.created_at_epoch_millis
                                    AND CASE journal.burst_ordinal
                                        WHEN 0 THEN
                                            journal.relative_final_path = :relativeFinalPath0
                                            AND journal.relative_temp_path = :relativeTempPath0
                                            AND journal.relative_quarantine_path =
                                                :relativeQuarantinePath0
                                        WHEN 1 THEN
                                            journal.relative_final_path = :relativeFinalPath1
                                            AND journal.relative_temp_path = :relativeTempPath1
                                            AND journal.relative_quarantine_path =
                                                :relativeQuarantinePath1
                                        WHEN 2 THEN
                                            journal.relative_final_path = :relativeFinalPath2
                                            AND journal.relative_temp_path = :relativeTempPath2
                                            AND journal.relative_quarantine_path =
                                                :relativeQuarantinePath2
                                        ELSE 0
                                    END
                                THEN 1 ELSE 0 END
                            ) = 3
                        THEN 1 ELSE 0 END
                        FROM capture_file_operations AS journal
                        WHERE CAST(journal.command_token AS BLOB) =
                            CAST(attempt.command_token AS BLOB)
                    ) = 1
                THEN 1 ELSE 0 END
            ) != 1 THEN 2
            WHEN SUM(
                CASE WHEN
                    :startedAtEpochMillis < attempt.updated_at_epoch_millis
                    OR EXISTS (
                        SELECT 1
                        FROM capture_file_operations AS journal_clock
                        WHERE CAST(journal_clock.command_token AS BLOB) =
                            CAST(attempt.command_token AS BLOB)
                          AND (
                              :startedAtEpochMillis < journal_clock.created_at_epoch_millis
                              OR :startedAtEpochMillis < journal_clock.updated_at_epoch_millis
                          )
                    )
                THEN 1 ELSE 0 END
            ) = 1 THEN 1
            ELSE 0
        END
        FROM capture_attempts AS attempt
        WHERE CAST(attempt.command_token AS BLOB) = CAST(:commandToken AS BLOB)
        """,
    )
    fun classifyCaptureStartInitialAuthority(
        commandToken: String,
        sessionId: String,
        expectedLifecycleState: String,
        startedAtEpochMillis: Long,
        relativeFinalPath0: String,
        relativeTempPath0: String,
        relativeQuarantinePath0: String,
        relativeFinalPath1: String,
        relativeTempPath1: String,
        relativeQuarantinePath1: String,
        relativeFinalPath2: String,
        relativeTempPath2: String,
        relativeQuarantinePath2: String,
    ): Int
}
