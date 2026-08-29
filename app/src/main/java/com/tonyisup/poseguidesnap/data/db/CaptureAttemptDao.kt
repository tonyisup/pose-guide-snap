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
        UPDATE capture_attempts
        SET lifecycle_state = 'CAPTURING',
            updated_at_epoch_millis = :authorizedAtEpochMillis
        WHERE command_token = :commandToken
          AND session_id = :sessionId
          AND lifecycle_state = 'REGISTERED'
          AND EXISTS (
              SELECT 1
              FROM shoot_sessions AS owning_session
              JOIN shoots AS owning_shoot
                ON owning_shoot.shoot_id = owning_session.shoot_id
              JOIN shoot_poses AS current_pose
                ON current_pose.shoot_id = owning_session.shoot_id
               AND current_pose.pose_index = owning_session.current_pose_index
              WHERE owning_session.session_id = :sessionId
                AND owning_session.lifecycle_state = 'ACTIVE'
                AND owning_session.current_pose_index = capture_attempts.pose_index
                AND current_pose.pose_id = capture_attempts.pose_id
                AND owning_shoot.lifecycle_state = 'ACTIVE'
                AND capture_attempts.captured_deletion_generation >= 0
                AND owning_shoot.deletion_generation >= 0
                AND owning_shoot.deletion_generation =
                    capture_attempts.captured_deletion_generation
          )
        """,
    )
    fun authorizeCaptureStart(
        sessionId: String,
        commandToken: String,
        authorizedAtEpochMillis: Long,
    ): Int
}
