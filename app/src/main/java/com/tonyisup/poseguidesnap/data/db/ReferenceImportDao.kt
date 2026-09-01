package com.tonyisup.poseguidesnap.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface ReferenceImportDao {
    @Query("SELECT * FROM reference_import_intents WHERE import_token = :importToken")
    fun findIntent(importToken: String): ReferenceImportIntentEntity?

    @Query(
        """
        SELECT * FROM reference_import_intents
        WHERE shoot_id = :shootId AND pose_id = :poseId
        """,
    )
    fun findIntentByPoseId(shootId: String, poseId: String): ReferenceImportIntentEntity?

    @Query("SELECT * FROM shoots WHERE shoot_id = :shootId")
    fun findShoot(shootId: String): ShootEntity?

    @Query(
        """
        SELECT * FROM shoot_poses
        WHERE shoot_id = :shootId AND pose_id = :poseId
        """,
    )
    fun findPoseById(shootId: String, poseId: String): ShootPoseEntity?

    @Query(
        """
        SELECT * FROM shoot_poses
        WHERE shoot_id = :shootId AND pose_index = :poseIndex
        """,
    )
    fun findPoseByIndex(shootId: String, poseIndex: Int): ShootPoseEntity?

    @Query(
        """
        SELECT * FROM shoot_poses
        WHERE shoot_id = :shootId
        ORDER BY pose_index ASC
        """,
    )
    fun findPosesInOrder(shootId: String): List<ShootPoseEntity>

    @Query(
        """
        SELECT * FROM shoot_poses
        WHERE shoot_id = :shootId
        ORDER BY pose_index ASC
        LIMIT :limit
        """,
    )
    fun findPosesInOrderForAdmission(shootId: String, limit: Int): List<ShootPoseEntity>

    @Query(
        """
        SELECT COUNT(*) FROM shoot_poses
        WHERE shoot_id = :shootId AND validation_state IN ('VALID', 'VALIDATED')
        """,
    )
    fun countAcceptedPoses(shootId: String): Long

    @Query(
        """
        SELECT COUNT(*) FROM reference_import_intents
        WHERE shoot_id = :shootId AND lifecycle_state IN ('PREPARING', 'ASSET_READY')
        """,
    )
    fun countNonterminalIntents(shootId: String): Long

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM reference_import_intents
            WHERE lifecycle_state IN ('PREPARING', 'ASSET_READY')
        )
        """,
    )
    fun hasAnyNonterminalIntents(): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM reference_import_intents AS intent
            LEFT JOIN reference_import_file_operations AS file_operation
                ON file_operation.import_token = intent.import_token
            WHERE file_operation.import_token IS NULL
        )
        """,
    )
    fun hasIntentWithoutFileOperation(): Boolean

    @Query(
        """
        SELECT COUNT(*) FROM shoot_sessions
        WHERE shoot_id = :shootId AND lifecycle_state = 'ACTIVE'
        """,
    )
    fun countActiveSessions(shootId: String): Int

    @Insert
    fun insertIntent(intent: ReferenceImportIntentEntity)

    @Insert
    fun insertPose(pose: ShootPoseEntity)

    @Query(
        """
        UPDATE reference_import_intents
        SET lifecycle_state = 'ASSET_READY',
            updated_at_epoch_millis = :assetReadyAtEpochMillis,
            asset_ready_at_epoch_millis = :assetReadyAtEpochMillis
        WHERE import_token = :importToken
          AND relative_asset_path = :relativeAssetPath
          AND lifecycle_state = 'PREPARING'
          AND created_at_epoch_millis >= 0
          AND updated_at_epoch_millis = :expectedUpdatedAtEpochMillis
          AND updated_at_epoch_millis = created_at_epoch_millis
          AND asset_ready_at_epoch_millis IS NULL
          AND terminal_at_epoch_millis IS NULL
          AND :assetReadyAtEpochMillis >= updated_at_epoch_millis
        """,
    )
    fun markAssetReady(
        importToken: String,
        relativeAssetPath: String,
        expectedUpdatedAtEpochMillis: Long,
        assetReadyAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE reference_import_intents
        SET lifecycle_state = 'COMMITTED',
            updated_at_epoch_millis = :committedAtEpochMillis,
            terminal_at_epoch_millis = :committedAtEpochMillis
        WHERE import_token = :importToken
          AND shoot_id = :shootId
          AND pose_id = :poseId
          AND relative_asset_path = :relativeAssetPath
          AND lifecycle_state = 'ASSET_READY'
          AND created_at_epoch_millis >= 0
          AND updated_at_epoch_millis = :expectedUpdatedAtEpochMillis
          AND asset_ready_at_epoch_millis = updated_at_epoch_millis
          AND terminal_at_epoch_millis IS NULL
          AND :committedAtEpochMillis >= updated_at_epoch_millis
          AND EXISTS (
              SELECT 1 FROM shoots AS owning_shoot
              WHERE owning_shoot.shoot_id = reference_import_intents.shoot_id
                AND owning_shoot.lifecycle_state = 'ACTIVE'
                AND owning_shoot.deletion_generation >= 0
          )
          AND NOT EXISTS (
              SELECT 1 FROM shoot_sessions AS active_session
              WHERE active_session.shoot_id = reference_import_intents.shoot_id
                AND active_session.lifecycle_state = 'ACTIVE'
          )
          AND NOT EXISTS (
              SELECT 1 FROM shoot_poses AS conflicting_pose
              WHERE conflicting_pose.shoot_id = reference_import_intents.shoot_id
                AND conflicting_pose.pose_id = reference_import_intents.pose_id
          )
        """,
    )
    fun markCommitted(
        importToken: String,
        shootId: String,
        poseId: String,
        relativeAssetPath: String,
        expectedUpdatedAtEpochMillis: Long,
        committedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE reference_import_intents
        SET lifecycle_state = :settledLifecycleState,
            updated_at_epoch_millis = :settledAtEpochMillis,
            terminal_at_epoch_millis = :settledAtEpochMillis
        WHERE import_token = :importToken
          AND lifecycle_state = :expectedLifecycleState
          AND lifecycle_state IN ('PREPARING', 'ASSET_READY')
          AND created_at_epoch_millis >= 0
          AND updated_at_epoch_millis = :expectedUpdatedAtEpochMillis
          AND terminal_at_epoch_millis IS NULL
          AND :settledAtEpochMillis >= updated_at_epoch_millis
          AND NOT EXISTS (
              SELECT 1 FROM shoot_poses AS active_pose
              WHERE active_pose.shoot_id = reference_import_intents.shoot_id
                AND active_pose.pose_id = reference_import_intents.pose_id
          )
        """,
    )
    fun settleIntent(
        importToken: String,
        expectedLifecycleState: String,
        expectedUpdatedAtEpochMillis: Long,
        settledLifecycleState: String,
        settledAtEpochMillis: Long,
    ): Int
}
