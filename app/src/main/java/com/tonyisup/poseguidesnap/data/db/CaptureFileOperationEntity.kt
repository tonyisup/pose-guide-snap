package com.tonyisup.poseguidesnap.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.tonyisup.poseguidesnap.data.CaptureFileFailureCode
import com.tonyisup.poseguidesnap.data.CaptureFileOperationPaths
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage
import com.tonyisup.poseguidesnap.data.hasValidCaptureFileOperationEvidence
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity

@Entity(
    tableName = "capture_file_operations",
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
            value = ["stage"],
            name = "index_capture_file_operations_stage",
        ),
        Index(
            value = ["reconciliation_required"],
            name = "index_capture_file_operations_reconciliation_required",
        ),
    ],
)
data class CaptureFileOperationEntity(
    @ColumnInfo(name = "command_token")
    val commandToken: String,
    @ColumnInfo(name = "burst_ordinal")
    val burstOrdinal: Int,
    @ColumnInfo(name = "relative_final_path")
    val relativeFinalPath: String,
    @ColumnInfo(name = "relative_temp_path")
    val relativeTempPath: String,
    @ColumnInfo(name = "relative_quarantine_path")
    val relativeQuarantinePath: String,
    @ColumnInfo(name = "stage")
    val stage: CaptureFileOperationStage,
    @ColumnInfo(name = "byte_count")
    val byteCount: Long?,
    @ColumnInfo(name = "sha256")
    val sha256: String?,
    @ColumnInfo(name = "captured_at_epoch_millis")
    val capturedAtEpochMillis: Long?,
    @ColumnInfo(name = "last_failure_code")
    val lastFailureCode: CaptureFileFailureCode?,
    @ColumnInfo(name = "reconciliation_required")
    val reconciliationRequired: Boolean,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
) {
    init {
        val expectedPaths = CaptureFileOperationPaths.forIdentity(
            PrivateOutputIdentity(CaptureToken(commandToken), burstOrdinal),
        )
        require(
            relativeFinalPath == expectedPaths.relativeFinalPath &&
                relativeTempPath == expectedPaths.relativeTempPath &&
                relativeQuarantinePath == expectedPaths.relativeQuarantinePath,
        ) { "capture file operation paths must match their deterministic identity" }
        require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis) {
            "capture file operation timestamps must be ordered and nonnegative"
        }
        require(
            capturedAtEpochMillis == null ||
                capturedAtEpochMillis in createdAtEpochMillis..updatedAtEpochMillis,
        ) { "capture file operation capture timestamp must be within journal timestamps" }
        require(hasValidCaptureFileOperationEvidence(stage, byteCount, sha256, capturedAtEpochMillis)) {
            "capture file operation evidence does not match its stage"
        }
        require(reconciliationRequired == (lastFailureCode != null)) {
            "capture file operation reconciliation state must be complete"
        }
    }

    override fun toString(): String = "CaptureFileOperationEntity(redacted)"
}
