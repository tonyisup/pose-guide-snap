package com.tonyisup.poseguidesnap.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

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

    // Value-insensitive residual-authority count for the Task 3D fail-closed confirmation guard;
    // deliberately scalar so malformed rows cannot be coerced or filtered during entity mapping.
    @Query(
        "SELECT COUNT(*) FROM capture_file_operations " +
            "WHERE CAST(command_token AS BLOB) = CAST(:commandToken AS BLOB)",
    )
    fun countOperationsForToken(commandToken: String): Long

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
