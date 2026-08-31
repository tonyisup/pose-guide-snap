package com.tonyisup.poseguidesnap.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.tonyisup.poseguidesnap.data.ReferenceImportFileFailureCode
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationPaths
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationStage
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import com.tonyisup.poseguidesnap.data.hasValidReferenceImportFileOperationEvidence

@Entity(
    tableName = "reference_import_file_operations",
    primaryKeys = ["import_token"],
    foreignKeys = [
        ForeignKey(
            entity = ReferenceImportIntentEntity::class,
            parentColumns = ["import_token"],
            childColumns = ["import_token"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["stage"],
            name = "index_reference_import_file_operations_stage",
        ),
        Index(
            value = ["reconciliation_required"],
            name = "index_reference_import_file_operations_reconciliation_required",
        ),
    ],
)
data class ReferenceImportFileOperationEntity(
    @ColumnInfo(name = "import_token")
    val importToken: String,
    @ColumnInfo(name = "relative_asset_path")
    val relativeAssetPath: String,
    @ColumnInfo(name = "relative_temp_path")
    val relativeTempPath: String,
    @ColumnInfo(name = "relative_quarantine_path")
    val relativeQuarantinePath: String,
    @ColumnInfo(name = "stage")
    val stage: ReferenceImportFileOperationStage,
    @ColumnInfo(name = "byte_count")
    val byteCount: Long?,
    @ColumnInfo(name = "sha256")
    val sha256: String?,
    @ColumnInfo(name = "last_failure_code")
    val lastFailureCode: ReferenceImportFileFailureCode?,
    @ColumnInfo(name = "reconciliation_required")
    val reconciliationRequired: Boolean,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
) {
    init {
        val expectedPaths = ReferenceImportFileOperationPaths.forToken(ReferenceImportToken(importToken))
        require(
            relativeAssetPath == expectedPaths.relativeAssetPath &&
                relativeTempPath == expectedPaths.relativeTempPath &&
                relativeQuarantinePath == expectedPaths.relativeQuarantinePath
        ) { "reference import file paths must exactly match their deterministic identity" }
        require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis) {
            "reference import file operation timestamps must be ordered and nonnegative"
        }
        require(hasValidReferenceImportFileOperationEvidence(stage, byteCount, sha256)) {
            "reference import file operation evidence does not match its stage"
        }
        require(reconciliationRequired == (lastFailureCode != null)) {
            "reference import file operation reconciliation state must be complete"
        }
    }

    override fun toString(): String = "ReferenceImportFileOperationEntity(redacted)"
}
