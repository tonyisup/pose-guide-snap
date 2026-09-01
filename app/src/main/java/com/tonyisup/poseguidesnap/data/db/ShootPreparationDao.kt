package com.tonyisup.poseguidesnap.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

internal data class ShootPreparationShootRow(
    @ColumnInfo(name = "shoot_id")
    val shootId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "deletion_generation")
    val deletionGeneration: Long,
    @ColumnInfo(name = "accepted_reference_count")
    val acceptedReferenceCount: Long,
    @ColumnInfo(name = "total_reference_count")
    val totalReferenceCount: Long,
) {
    override fun toString(): String = "ShootPreparationShootRow(redacted)"
}

internal data class ShootPreparationPoseRow(
    val poseId: String,
    val poseIndex: Int,
    val label: String,
    val mirrorAllowed: Boolean,
    val validationState: String,
) {
    override fun toString(): String = "ShootPreparationPoseRow(redacted)"
}

internal data class ShootPreparationImportWorkRow(
    val importToken: String,
    val poseId: String,
    val intentRelativeAssetPath: String,
    val intentLifecycleState: String,
    val intentCreatedAtEpochMillis: Long,
    val intentUpdatedAtEpochMillis: Long,
    val assetReadyAtEpochMillis: Long?,
    val terminalAtEpochMillis: Long?,
    val fileRelativeAssetPath: String,
    val fileRelativeTempPath: String,
    val fileRelativeQuarantinePath: String,
    val fileStage: String,
    val byteCount: Long?,
    val sha256: String?,
    val lastFailureCode: String?,
    val reconciliationRequired: Boolean,
    val fileCreatedAtEpochMillis: Long,
    val fileUpdatedAtEpochMillis: Long,
) {
    override fun toString(): String = "ShootPreparationImportWorkRow(redacted)"
}

internal data class ShootPreparationEditorRow(
    @ColumnInfo(name = "shoot_id")
    val shootId: String,
    @ColumnInfo(name = "shoot_name")
    val shootName: String,
    @ColumnInfo(name = "shoot_created_at_epoch_millis")
    val shootCreatedAtEpochMillis: Long,
    @ColumnInfo(name = "shoot_updated_at_epoch_millis")
    val shootUpdatedAtEpochMillis: Long,
    @ColumnInfo(name = "shoot_lifecycle_state")
    val shootLifecycleState: String,
    @ColumnInfo(name = "shoot_deletion_generation")
    val shootDeletionGeneration: Long,
    @ColumnInfo(name = "accepted_reference_count")
    val acceptedReferenceCount: Long,
    @ColumnInfo(name = "total_reference_count")
    val totalReferenceCount: Long,
    @ColumnInfo(name = "pose_id")
    val poseId: String?,
    @ColumnInfo(name = "pose_index")
    val poseIndex: Int?,
    @ColumnInfo(name = "pose_label")
    val poseLabel: String?,
    @ColumnInfo(name = "pose_mirror_allowed")
    val poseMirrorAllowed: Boolean?,
    @ColumnInfo(name = "pose_validation_state")
    val poseValidationState: String?,
    @ColumnInfo(name = "import_token")
    val importToken: String?,
    @ColumnInfo(name = "intent_pose_id")
    val intentPoseId: String?,
    @ColumnInfo(name = "intent_relative_asset_path")
    val intentRelativeAssetPath: String?,
    @ColumnInfo(name = "intent_lifecycle_state")
    val intentLifecycleState: String?,
    @ColumnInfo(name = "intent_created_at_epoch_millis")
    val intentCreatedAtEpochMillis: Long?,
    @ColumnInfo(name = "intent_updated_at_epoch_millis")
    val intentUpdatedAtEpochMillis: Long?,
    @ColumnInfo(name = "asset_ready_at_epoch_millis")
    val assetReadyAtEpochMillis: Long?,
    @ColumnInfo(name = "terminal_at_epoch_millis")
    val terminalAtEpochMillis: Long?,
    @ColumnInfo(name = "file_relative_asset_path")
    val fileRelativeAssetPath: String?,
    @ColumnInfo(name = "file_relative_temp_path")
    val fileRelativeTempPath: String?,
    @ColumnInfo(name = "file_relative_quarantine_path")
    val fileRelativeQuarantinePath: String?,
    @ColumnInfo(name = "file_stage")
    val fileStage: String?,
    @ColumnInfo(name = "byte_count")
    val byteCount: Long?,
    @ColumnInfo(name = "sha256")
    val sha256: String?,
    @ColumnInfo(name = "last_failure_code")
    val lastFailureCode: String?,
    @ColumnInfo(name = "reconciliation_required")
    val reconciliationRequired: Boolean?,
    @ColumnInfo(name = "file_created_at_epoch_millis")
    val fileCreatedAtEpochMillis: Long?,
    @ColumnInfo(name = "file_updated_at_epoch_millis")
    val fileUpdatedAtEpochMillis: Long?,
) {
    override fun toString(): String = "ShootPreparationEditorRow(redacted)"
}

@Dao
internal interface ShootPreparationDao {
    @Insert
    fun insertShoot(shoot: ShootEntity)

    @Query(
        """
        SELECT
            shoot.shoot_id,
            shoot.name,
            shoot.created_at_epoch_millis,
            shoot.updated_at_epoch_millis,
            shoot.lifecycle_state,
            shoot.deletion_generation,
            (
                SELECT COUNT(*) FROM shoot_poses AS accepted_pose
                WHERE accepted_pose.shoot_id = shoot.shoot_id
                  AND accepted_pose.validation_state IN ('VALID', 'VALIDATED')
            ) AS accepted_reference_count,
            (
                SELECT COUNT(*) FROM shoot_poses AS any_pose
                WHERE any_pose.shoot_id = shoot.shoot_id
            ) AS total_reference_count
        FROM shoots AS shoot
        ORDER BY shoot.updated_at_epoch_millis DESC, shoot.shoot_id ASC
        """,
    )
    fun observeShoots(): Flow<List<ShootPreparationShootRow>>

    @Query(
        """
        SELECT
            shoot.shoot_id,
            shoot.name,
            shoot.created_at_epoch_millis,
            shoot.updated_at_epoch_millis,
            shoot.lifecycle_state,
            shoot.deletion_generation,
            (
                SELECT COUNT(*) FROM shoot_poses AS accepted_pose
                WHERE accepted_pose.shoot_id = shoot.shoot_id
                  AND accepted_pose.validation_state IN ('VALID', 'VALIDATED')
            ) AS accepted_reference_count,
            (
                SELECT COUNT(*) FROM shoot_poses AS any_pose
                WHERE any_pose.shoot_id = shoot.shoot_id
            ) AS total_reference_count
        FROM shoots AS shoot
        WHERE shoot.shoot_id = :shootId
        """,
    )
    fun findShoot(shootId: String): ShootPreparationShootRow?

    @Query(
        """
        SELECT * FROM shoot_poses
        WHERE shoot_id = :shootId
        ORDER BY pose_index ASC
        """,
    )
    fun findAllPoseEntitiesInOrder(shootId: String): List<ShootPoseEntity>

    @Query(
        """
        SELECT COUNT(*) FROM shoot_sessions
        WHERE shoot_id = :shootId AND lifecycle_state = 'ACTIVE'
        """,
    )
    fun countActiveSessions(shootId: String): Int

    @Query(
        """
        UPDATE shoot_poses
        SET pose_index = :targetPoseIndex
        WHERE shoot_id = :shootId
          AND pose_id = :poseId
          AND pose_index = :expectedPoseIndex
          AND validation_state IN ('VALID', 'VALIDATED')
        """,
    )
    fun compareAndSetPoseIndex(
        shootId: String,
        poseId: String,
        expectedPoseIndex: Int,
        targetPoseIndex: Int,
    ): Int

    @Query(
        """
        UPDATE shoots
        SET updated_at_epoch_millis = :reorderedAtEpochMillis
        WHERE shoot_id = :shootId
          AND lifecycle_state = 'ACTIVE'
          AND deletion_generation = :expectedDeletionGeneration
          AND deletion_generation >= 0
          AND updated_at_epoch_millis = :expectedUpdatedAtEpochMillis
          AND :reorderedAtEpochMillis > updated_at_epoch_millis
        """,
    )
    fun compareAndSetShootUpdatedAt(
        shootId: String,
        expectedDeletionGeneration: Long,
        expectedUpdatedAtEpochMillis: Long,
        reorderedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        WITH relevant_intent AS (
            SELECT *
            FROM reference_import_intents
            WHERE shoot_id = :shootId
              AND lifecycle_state IN ('PREPARING', 'ASSET_READY', 'REJECTED_QUARANTINED')
            ORDER BY
                CASE
                    WHEN lifecycle_state IN ('PREPARING', 'ASSET_READY') THEN 0
                    ELSE 1
                END ASC,
                created_at_epoch_millis DESC,
                import_token DESC
            LIMIT 20
        )
        SELECT
            shoot.shoot_id AS shoot_id,
            shoot.name AS shoot_name,
            shoot.created_at_epoch_millis AS shoot_created_at_epoch_millis,
            shoot.updated_at_epoch_millis AS shoot_updated_at_epoch_millis,
            shoot.lifecycle_state AS shoot_lifecycle_state,
            shoot.deletion_generation AS shoot_deletion_generation,
            (
                SELECT COUNT(*) FROM shoot_poses AS accepted_pose
                WHERE accepted_pose.shoot_id = shoot.shoot_id
                  AND accepted_pose.validation_state IN ('VALID', 'VALIDATED')
            ) AS accepted_reference_count,
            (
                SELECT COUNT(*) FROM shoot_poses AS any_pose
                WHERE any_pose.shoot_id = shoot.shoot_id
            ) AS total_reference_count,
            pose.pose_id AS pose_id,
            pose.pose_index AS pose_index,
            pose.label AS pose_label,
            pose.mirror_allowed AS pose_mirror_allowed,
            pose.validation_state AS pose_validation_state,
            intent.import_token AS import_token,
            intent.pose_id AS intent_pose_id,
            intent.relative_asset_path AS intent_relative_asset_path,
            intent.lifecycle_state AS intent_lifecycle_state,
            intent.created_at_epoch_millis AS intent_created_at_epoch_millis,
            intent.updated_at_epoch_millis AS intent_updated_at_epoch_millis,
            intent.asset_ready_at_epoch_millis AS asset_ready_at_epoch_millis,
            intent.terminal_at_epoch_millis AS terminal_at_epoch_millis,
            file.relative_asset_path AS file_relative_asset_path,
            file.relative_temp_path AS file_relative_temp_path,
            file.relative_quarantine_path AS file_relative_quarantine_path,
            file.stage AS file_stage,
            file.byte_count AS byte_count,
            file.sha256 AS sha256,
            file.last_failure_code AS last_failure_code,
            file.reconciliation_required AS reconciliation_required,
            file.created_at_epoch_millis AS file_created_at_epoch_millis,
            file.updated_at_epoch_millis AS file_updated_at_epoch_millis
        FROM shoots AS shoot
        LEFT JOIN shoot_poses AS pose
            ON pose.shoot_id = shoot.shoot_id
           AND pose.validation_state IN ('VALID', 'VALIDATED')
        LEFT JOIN relevant_intent AS intent
            ON intent.shoot_id = shoot.shoot_id
        LEFT JOIN reference_import_file_operations AS file
            ON file.import_token = intent.import_token
        WHERE shoot.shoot_id = :shootId
        ORDER BY pose.pose_index ASC, intent.created_at_epoch_millis ASC, intent.import_token ASC
        """,
    )
    fun observeEditorRows(shootId: String): Flow<List<ShootPreparationEditorRow>>
}
