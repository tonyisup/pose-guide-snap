package com.tonyisup.poseguidesnap.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface CaptureAttemptDao {
    @Query("SELECT * FROM capture_attempts WHERE command_token = :commandToken")
    fun findAttemptByToken(commandToken: String): CaptureAttemptEntity?

    @Query(
        """
        SELECT * FROM capture_attempts
        WHERE session_id = :sessionId AND attempt_number = :attemptNumber
        """,
    )
    fun findAttemptBySessionAndNumber(
        sessionId: String,
        attemptNumber: Long,
    ): CaptureAttemptEntity?

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

    @Insert
    fun insertAttempt(attempt: CaptureAttemptEntity)

    @Query(
        """
        UPDATE shoot_sessions
        SET next_attempt_number = :nextAttemptNumber,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE session_id = :sessionId
          AND current_pose_index = :poseIndex
          AND next_attempt_number = :expectedAttemptNumber
          AND lifecycle_state = 'ACTIVE'
          AND :expectedDeletionGeneration >= 0
          AND EXISTS (
              SELECT 1
              FROM shoots AS owning_shoot
              WHERE owning_shoot.shoot_id = shoot_sessions.shoot_id
                AND owning_shoot.lifecycle_state = 'ACTIVE'
                AND owning_shoot.deletion_generation >= 0
                AND owning_shoot.deletion_generation = :expectedDeletionGeneration
          )
        """,
    )
    fun advanceSessionAttemptCounter(
        sessionId: String,
        poseIndex: Int,
        expectedAttemptNumber: Long,
        nextAttemptNumber: Long,
        expectedDeletionGeneration: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT CASE
            WHEN COUNT(*) = 0 THEN 0
            WHEN COUNT(*) != 1 THEN 1
            WHEN SUM(
                CASE WHEN
                    typeof(candidate.command_token) = 'text'
                    AND length(trim(candidate.command_token)) > 0
                    AND candidate.command_token = :commandToken
                    AND typeof(candidate.session_id) = 'text'
                    AND length(trim(candidate.session_id)) > 0
                THEN 1 ELSE 0 END
            ) != 1 THEN 1
            WHEN SUM(
                CASE WHEN candidate.session_id = :sessionId THEN 1 ELSE 0 END
            ) != 1 THEN 2
            WHEN SUM(
                CASE WHEN
                    typeof(candidate.lifecycle_state) = 'text'
                    AND candidate.lifecycle_state = 'REGISTERED'
                THEN 1 ELSE 0 END
            ) = 1 THEN 3
            WHEN SUM(
                CASE WHEN
                    typeof(candidate.lifecycle_state) = 'text'
                    AND candidate.lifecycle_state = 'CAPTURING'
                THEN 1 ELSE 0 END
            ) = 1 THEN 4
            WHEN SUM(
                CASE WHEN
                    typeof(candidate.lifecycle_state) = 'text'
                    AND candidate.lifecycle_state = 'CONFIRMED'
                THEN 1 ELSE 0 END
            ) = 1 THEN 5
            ELSE 1
        END
        FROM capture_attempts AS candidate
        WHERE candidate.command_token = :commandToken
           OR (
               typeof(candidate.command_token) = 'blob'
               AND candidate.command_token = CAST(:commandToken AS BLOB)
           )
        """,
    )
    fun classifyCaptureStartCandidate(
        sessionId: String,
        commandToken: String,
    ): Int

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
                    AND attempt.lifecycle_state = 'REGISTERED'
                    AND typeof(attempt.reconciliation_required) = 'integer'
                    AND attempt.reconciliation_required = 0
                    AND typeof(attempt.captured_deletion_generation) = 'integer'
                    AND attempt.captured_deletion_generation >= 0
                    AND typeof(attempt.created_at_epoch_millis) = 'integer'
                    AND attempt.created_at_epoch_millis >= 0
                    AND typeof(attempt.updated_at_epoch_millis) = 'integer'
                    AND attempt.updated_at_epoch_millis = attempt.created_at_epoch_millis
                    AND typeof(attempt.confirmed_at_epoch_millis) = 'null'
                THEN 1 ELSE 0 END
            ) != 1 THEN 0
            WHEN SUM(
                CASE WHEN
                    typeof(owning_session.session_id) = 'text'
                    AND length(trim(owning_session.session_id)) > 0
                    AND owning_session.session_id = attempt.session_id
                    AND owning_session.session_id = :sessionId
                    AND typeof(owning_session.shoot_id) = 'text'
                    AND length(trim(owning_session.shoot_id)) > 0
                    AND typeof(owning_session.current_pose_index) = 'integer'
                    AND owning_session.current_pose_index >= 0
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
                    AND typeof(owning_shoot.lifecycle_state) = 'text'
                    AND owning_shoot.lifecycle_state IN ('ACTIVE', 'DELETING')
                    AND typeof(owning_shoot.deletion_generation) = 'integer'
                    AND owning_shoot.deletion_generation >= 0
                    AND typeof(owning_shoot.created_at_epoch_millis) = 'integer'
                    AND owning_shoot.created_at_epoch_millis >= 0
                    AND typeof(owning_shoot.updated_at_epoch_millis) = 'integer'
                    AND owning_shoot.updated_at_epoch_millis >=
                        owning_shoot.created_at_epoch_millis
                THEN 1 ELSE 0 END
            ) != 1 THEN 0
            WHEN SUM(
                CASE WHEN
                    owning_shoot.lifecycle_state != 'ACTIVE'
                    OR owning_shoot.deletion_generation !=
                        attempt.captured_deletion_generation
                THEN 1 ELSE 0 END
            ) = 1 THEN 1
            WHEN SUM(
                CASE WHEN owning_session.lifecycle_state != 'ACTIVE' THEN 1 ELSE 0 END
            ) = 1 THEN 2
            WHEN SUM(
                CASE WHEN current_pose.rowid IS NULL THEN 1 ELSE 0 END
            ) = 1 THEN 3
            WHEN SUM(
                CASE WHEN
                    typeof(current_pose.shoot_id) != 'text'
                    OR length(trim(current_pose.shoot_id)) = 0
                    OR current_pose.shoot_id != owning_session.shoot_id
                    OR typeof(current_pose.pose_index) != 'integer'
                    OR current_pose.pose_index < 0
                    OR typeof(current_pose.pose_id) != 'text'
                    OR length(trim(current_pose.pose_id)) = 0
                THEN 1 ELSE 0 END
            ) = 1 THEN 0
            WHEN SUM(
                CASE WHEN
                    current_pose.pose_index != attempt.pose_index
                    OR current_pose.pose_id != attempt.pose_id
                THEN 1 ELSE 0 END
            ) = 1 THEN 3
            WHEN SUM(
                CASE WHEN
                    :startedAtEpochMillis < attempt.created_at_epoch_millis
                    OR :startedAtEpochMillis < attempt.updated_at_epoch_millis
                    OR :startedAtEpochMillis < owning_session.created_at_epoch_millis
                    OR :startedAtEpochMillis < owning_session.updated_at_epoch_millis
                    OR :startedAtEpochMillis < owning_shoot.created_at_epoch_millis
                    OR :startedAtEpochMillis < owning_shoot.updated_at_epoch_millis
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
    fun classifyRegisteredCaptureStartOwner(
        sessionId: String,
        commandToken: String,
        startedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE capture_attempts
        SET lifecycle_state = 'CAPTURING',
            updated_at_epoch_millis = :startedAtEpochMillis
        WHERE command_token = :commandToken
          AND typeof(command_token) = 'text'
          AND length(trim(command_token)) > 0
          AND typeof(session_id) = 'text'
          AND length(trim(session_id)) > 0
          AND session_id = :sessionId
          AND typeof(pose_id) = 'text'
          AND length(trim(pose_id)) > 0
          AND typeof(pose_index) = 'integer'
          AND pose_index >= 0
          AND typeof(attempt_number) = 'integer'
          AND attempt_number >= 0
          AND typeof(trigger_type) = 'text'
          AND trigger_type IN ('MANUAL', 'AUTOMATIC')
          AND typeof(lifecycle_state) = 'text'
          AND lifecycle_state = 'REGISTERED'
          AND typeof(reconciliation_required) = 'integer'
          AND reconciliation_required = 0
          AND typeof(captured_deletion_generation) = 'integer'
          AND captured_deletion_generation >= 0
          AND typeof(created_at_epoch_millis) = 'integer'
          AND created_at_epoch_millis >= 0
          AND typeof(updated_at_epoch_millis) = 'integer'
          AND updated_at_epoch_millis = created_at_epoch_millis
          AND typeof(confirmed_at_epoch_millis) = 'null'
          AND :startedAtEpochMillis >= created_at_epoch_millis
          AND :startedAtEpochMillis >= updated_at_epoch_millis
          AND (
              SELECT CASE WHEN
                  COUNT(*) = 1
                  AND SUM(
                      CASE WHEN
                          typeof(owning_session.session_id) = 'text'
                          AND length(trim(owning_session.session_id)) > 0
                          AND owning_session.session_id = capture_attempts.session_id
                          AND owning_session.session_id = :sessionId
                          AND typeof(owning_session.shoot_id) = 'text'
                          AND length(trim(owning_session.shoot_id)) > 0
                          AND typeof(owning_session.current_pose_index) = 'integer'
                          AND owning_session.current_pose_index >= 0
                          AND typeof(owning_session.lifecycle_state) = 'text'
                          AND owning_session.lifecycle_state = 'ACTIVE'
                          AND typeof(owning_session.created_at_epoch_millis) = 'integer'
                          AND owning_session.created_at_epoch_millis >= 0
                          AND typeof(owning_session.updated_at_epoch_millis) = 'integer'
                          AND owning_session.updated_at_epoch_millis >=
                              owning_session.created_at_epoch_millis
                          AND :startedAtEpochMillis >=
                              owning_session.created_at_epoch_millis
                          AND :startedAtEpochMillis >=
                              owning_session.updated_at_epoch_millis
                          AND typeof(owning_shoot.shoot_id) = 'text'
                          AND length(trim(owning_shoot.shoot_id)) > 0
                          AND owning_shoot.shoot_id = owning_session.shoot_id
                          AND typeof(owning_shoot.lifecycle_state) = 'text'
                          AND owning_shoot.lifecycle_state = 'ACTIVE'
                          AND typeof(owning_shoot.deletion_generation) = 'integer'
                          AND owning_shoot.deletion_generation >= 0
                          AND owning_shoot.deletion_generation =
                              capture_attempts.captured_deletion_generation
                          AND typeof(owning_shoot.created_at_epoch_millis) = 'integer'
                          AND owning_shoot.created_at_epoch_millis >= 0
                          AND typeof(owning_shoot.updated_at_epoch_millis) = 'integer'
                          AND owning_shoot.updated_at_epoch_millis >=
                              owning_shoot.created_at_epoch_millis
                          AND :startedAtEpochMillis >=
                              owning_shoot.created_at_epoch_millis
                          AND :startedAtEpochMillis >=
                              owning_shoot.updated_at_epoch_millis
                          AND typeof(current_pose.shoot_id) = 'text'
                          AND length(trim(current_pose.shoot_id)) > 0
                          AND current_pose.shoot_id = owning_session.shoot_id
                          AND typeof(current_pose.pose_index) = 'integer'
                          AND current_pose.pose_index >= 0
                          AND current_pose.pose_index = capture_attempts.pose_index
                          AND typeof(current_pose.pose_id) = 'text'
                          AND length(trim(current_pose.pose_id)) > 0
                          AND current_pose.pose_id = capture_attempts.pose_id
                      THEN 1 ELSE 0 END
                  ) = 1
              THEN 1 ELSE 0 END
              FROM shoot_sessions AS owning_session
              LEFT JOIN shoots AS owning_shoot
                ON CAST(owning_shoot.shoot_id AS BLOB) =
                    CAST(owning_session.shoot_id AS BLOB)
              LEFT JOIN shoot_poses AS current_pose
                ON CAST(current_pose.shoot_id AS BLOB) =
                    CAST(owning_session.shoot_id AS BLOB)
               AND CAST(current_pose.pose_index AS BLOB) =
                   CAST(owning_session.current_pose_index AS BLOB)
              WHERE CAST(owning_session.session_id AS BLOB) =
                  CAST(capture_attempts.session_id AS BLOB)
          ) = 1
          AND (
              SELECT CASE WHEN
                  COUNT(*) = 3
                  AND SUM(CASE WHEN journal.burst_ordinal = 0 THEN 1 ELSE 0 END) = 1
                  AND SUM(CASE WHEN journal.burst_ordinal = 1 THEN 1 ELSE 0 END) = 1
                  AND SUM(CASE WHEN journal.burst_ordinal = 2 THEN 1 ELSE 0 END) = 1
                  AND SUM(
                      CASE WHEN
                          typeof(journal.command_token) = 'text'
                          AND journal.command_token = capture_attempts.command_token
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
                              capture_attempts.created_at_epoch_millis
                          AND journal.updated_at_epoch_millis =
                              capture_attempts.created_at_epoch_millis
                          AND :startedAtEpochMillis >= journal.created_at_epoch_millis
                          AND :startedAtEpochMillis >= journal.updated_at_epoch_millis
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
                  CAST(capture_attempts.command_token AS BLOB)
          ) = 1
        """,
    )
    fun markCaptureAttemptStarted(
        sessionId: String,
        commandToken: String,
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
