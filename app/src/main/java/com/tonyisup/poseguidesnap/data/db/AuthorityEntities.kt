package com.tonyisup.poseguidesnap.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "shoots",
    primaryKeys = ["shoot_id"],
    indices = [
        Index(
            value = ["lifecycle_state"],
            name = "index_shoots_lifecycle_state",
        ),
    ],
)
data class ShootEntity(
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
) {
    override fun toString(): String = "ShootEntity(redacted)"
}

@Entity(
    tableName = "shoot_poses",
    primaryKeys = ["shoot_id", "pose_index"],
    foreignKeys = [
        ForeignKey(
            entity = ShootEntity::class,
            parentColumns = ["shoot_id"],
            childColumns = ["shoot_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["shoot_id", "pose_id"],
            unique = true,
            name = "index_shoot_poses_shoot_id_pose_id",
        ),
    ],
)
data class ShootPoseEntity(
    @ColumnInfo(name = "shoot_id")
    val shootId: String,
    @ColumnInfo(name = "pose_index")
    val poseIndex: Int,
    @ColumnInfo(name = "pose_id")
    val poseId: String,
    @ColumnInfo(name = "label")
    val label: String,
    @ColumnInfo(name = "reference_asset_path")
    val referenceAssetPath: String?,
    @ColumnInfo(name = "mirror_allowed")
    val mirrorAllowed: Boolean,
    @ColumnInfo(name = "validation_state")
    val validationState: String,
    @ColumnInfo(name = "detector_metadata")
    val detectorMetadata: String?,
    @ColumnInfo(name = "model_metadata")
    val modelMetadata: String?,
    @ColumnInfo(name = "preprocessing_metadata")
    val preprocessingMetadata: String?,
    @ColumnInfo(name = "landmark_payload")
    val landmarkPayload: String? = null,
    @ColumnInfo(name = "coordinate_metadata")
    val coordinateMetadata: String? = null,
) {
    override fun toString(): String = "ShootPoseEntity(redacted)"
}

@Entity(
    tableName = "reference_import_intents",
    primaryKeys = ["import_token"],
    foreignKeys = [
        ForeignKey(
            entity = ShootEntity::class,
            parentColumns = ["shoot_id"],
            childColumns = ["shoot_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["shoot_id", "pose_id"],
            unique = true,
            name = "index_reference_import_intents_shoot_id_pose_id",
        ),
        Index(
            value = ["lifecycle_state"],
            name = "index_reference_import_intents_lifecycle_state",
        ),
    ],
)
data class ReferenceImportIntentEntity(
    @ColumnInfo(name = "import_token")
    val importToken: String,
    @ColumnInfo(name = "shoot_id")
    val shootId: String,
    @ColumnInfo(name = "pose_id")
    val poseId: String,
    @ColumnInfo(name = "relative_asset_path")
    val relativeAssetPath: String,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "asset_ready_at_epoch_millis")
    val assetReadyAtEpochMillis: Long?,
    @ColumnInfo(name = "terminal_at_epoch_millis")
    val terminalAtEpochMillis: Long?,
) {
    override fun toString(): String = "ReferenceImportIntentEntity(redacted)"
}

@Entity(
    tableName = "shoot_sessions",
    primaryKeys = ["session_id"],
    foreignKeys = [
        ForeignKey(
            entity = ShootEntity::class,
            parentColumns = ["shoot_id"],
            childColumns = ["shoot_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["shoot_id", "lifecycle_state"],
            name = "index_shoot_sessions_shoot_id_lifecycle_state",
        ),
    ],
)
data class ShootSessionEntity(
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "shoot_id")
    val shootId: String,
    @ColumnInfo(name = "current_pose_index")
    val currentPoseIndex: Int,
    @ColumnInfo(name = "next_attempt_number")
    val nextAttemptNumber: Long,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
) {
    override fun toString(): String = "ShootSessionEntity(redacted)"
}

@Entity(
    tableName = "capture_attempts",
    primaryKeys = ["command_token"],
    foreignKeys = [
        ForeignKey(
            entity = ShootSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["session_id", "attempt_number"],
            unique = true,
            name = "index_capture_attempts_session_id_attempt_number",
        ),
        Index(
            value = ["lifecycle_state"],
            name = "index_capture_attempts_lifecycle_state",
        ),
    ],
)
data class CaptureAttemptEntity(
    @ColumnInfo(name = "command_token")
    val commandToken: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "pose_id")
    val poseId: String,
    @ColumnInfo(name = "pose_index")
    val poseIndex: Int,
    @ColumnInfo(name = "attempt_number")
    val attemptNumber: Long,
    @ColumnInfo(name = "trigger_type")
    val triggerType: String,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "reconciliation_required")
    val reconciliationRequired: Boolean,
    @ColumnInfo(name = "captured_deletion_generation")
    val capturedDeletionGeneration: Long,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "confirmed_at_epoch_millis")
    val confirmedAtEpochMillis: Long?,
) {
    override fun toString(): String = "CaptureAttemptEntity(redacted)"
}

@Entity(
    tableName = "private_capture_outputs",
    primaryKeys = ["command_token", "burst_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = CaptureAttemptEntity::class,
            parentColumns = ["command_token"],
            childColumns = ["command_token"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["durability_state"],
            name = "index_private_capture_outputs_durability_state",
        ),
    ],
)
data class PrivateCaptureOutputEntity(
    @ColumnInfo(name = "command_token")
    val commandToken: String,
    @ColumnInfo(name = "burst_ordinal")
    val burstOrdinal: Int,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "byte_count")
    val byteCount: Long,
    @ColumnInfo(name = "durability_state")
    val durabilityState: String,
    @ColumnInfo(name = "captured_at_epoch_millis")
    val capturedAtEpochMillis: Long,
    @ColumnInfo(name = "integrity_metadata")
    val integrityMetadata: String?,
) {
    override fun toString(): String = "PrivateCaptureOutputEntity(redacted)"
}

@Entity(
    tableName = "capture_confirmation_receipts",
    primaryKeys = ["command_token"],
    foreignKeys = [
        ForeignKey(
            entity = CaptureAttemptEntity::class,
            parentColumns = ["command_token"],
            childColumns = ["command_token"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
)
data class CaptureConfirmationReceiptEntity(
    @ColumnInfo(name = "command_token")
    val commandToken: String,
    @ColumnInfo(name = "from_pose_index")
    val fromPoseIndex: Int,
    @ColumnInfo(name = "to_pose_index")
    val toPoseIndex: Int?,
    @ColumnInfo(name = "applied_deletion_generation")
    val appliedDeletionGeneration: Long,
    @ColumnInfo(name = "applied_at_epoch_millis")
    val appliedAtEpochMillis: Long,
) {
    override fun toString(): String = "CaptureConfirmationReceiptEntity(redacted)"
}

@Entity(
    tableName = "capture_export_outboxes",
    primaryKeys = ["command_token"],
    foreignKeys = [
        ForeignKey(
            entity = CaptureConfirmationReceiptEntity::class,
            parentColumns = ["command_token"],
            childColumns = ["command_token"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["lifecycle_state"],
            name = "index_capture_export_outboxes_lifecycle_state",
        ),
    ],
)
data class CaptureExportOutboxEntity(
    @ColumnInfo(name = "command_token")
    val commandToken: String,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "retry_metadata")
    val retryMetadata: String?,
) {
    override fun toString(): String = "CaptureExportOutboxEntity(redacted)"
}

@Entity(
    tableName = "capture_export_outputs",
    primaryKeys = ["command_token", "burst_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = CaptureExportOutboxEntity::class,
            parentColumns = ["command_token"],
            childColumns = ["command_token"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["claim_token"],
            unique = true,
            name = "index_capture_export_outputs_claim_token",
        ),
        Index(
            value = ["lifecycle_state"],
            name = "index_capture_export_outputs_lifecycle_state",
        ),
        Index(
            value = ["deletion_generation"],
            name = "index_capture_export_outputs_deletion_generation",
        ),
    ],
)
data class CaptureExportOutputEntity(
    @ColumnInfo(name = "command_token")
    val commandToken: String,
    @ColumnInfo(name = "burst_ordinal")
    val burstOrdinal: Int,
    @ColumnInfo(name = "target_collection_uri")
    val targetCollectionUri: String,
    @ColumnInfo(name = "target_volume")
    val targetVolume: String,
    @ColumnInfo(name = "intended_display_name")
    val intendedDisplayName: String,
    @ColumnInfo(name = "intended_relative_path")
    val intendedRelativePath: String,
    @ColumnInfo(name = "intended_mime_type")
    val intendedMimeType: String,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "claim_token")
    val claimToken: String?,
    @ColumnInfo(name = "media_uri_string")
    val mediaUriString: String?,
    @ColumnInfo(name = "ambiguity_state")
    val ambiguityState: String,
    @ColumnInfo(name = "deletion_generation")
    val deletionGeneration: Long,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
) {
    override fun toString(): String = "CaptureExportOutputEntity(redacted)"
}
