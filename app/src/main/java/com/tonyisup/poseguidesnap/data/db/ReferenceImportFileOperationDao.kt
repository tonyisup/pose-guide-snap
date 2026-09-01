package com.tonyisup.poseguidesnap.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.tonyisup.poseguidesnap.data.ReferenceImportFileFailureCode
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationStage

@Dao
internal interface ReferenceImportFileOperationDao {
    @Query("SELECT * FROM reference_import_file_operations WHERE import_token = :importToken")
    fun findOperation(importToken: String): ReferenceImportFileOperationEntity?

    @Query(
        """
        SELECT file_operation.*
        FROM reference_import_file_operations AS file_operation
        INNER JOIN reference_import_intents AS intent
            ON intent.import_token = file_operation.import_token
        WHERE NOT (
            file_operation.reconciliation_required = 0
            AND file_operation.last_failure_code IS NULL
            AND (
                (file_operation.stage = 'FINAL_DURABLE' AND intent.lifecycle_state = 'COMMITTED')
                OR (
                    file_operation.stage = 'CLEANED_DURABLE'
                    AND intent.lifecycle_state = 'REJECTED_CLEANED'
                )
                OR (
                    file_operation.stage = 'QUARANTINE_DURABLE'
                    AND intent.lifecycle_state = 'REJECTED_QUARANTINED'
                )
            )
        )
          AND (
              :afterCreatedAtEpochMillis IS NULL
              OR file_operation.created_at_epoch_millis > :afterCreatedAtEpochMillis
              OR (
                  file_operation.created_at_epoch_millis = :afterCreatedAtEpochMillis
                  AND file_operation.import_token > :afterImportToken
              )
          )
        ORDER BY file_operation.created_at_epoch_millis, file_operation.import_token
        LIMIT :limit
        """,
    )
    fun findRetryableOperations(
        afterCreatedAtEpochMillis: Long?,
        afterImportToken: String?,
        limit: Int,
    ): List<ReferenceImportFileOperationEntity>

    @Query(
        """
        SELECT file_operation.*
        FROM reference_import_file_operations AS file_operation
        INNER JOIN reference_import_intents AS intent
            ON intent.import_token = file_operation.import_token
        WHERE file_operation.reconciliation_required = 1
        ORDER BY file_operation.created_at_epoch_millis, file_operation.import_token
        LIMIT :limit
        """,
    )
    fun findReconciliationRequiredOperations(limit: Int): List<ReferenceImportFileOperationEntity>

    @Query(
        """
        SELECT * FROM reference_import_file_operations
        WHERE :afterCreatedAtEpochMillis IS NULL
           OR created_at_epoch_millis > :afterCreatedAtEpochMillis
           OR (
               created_at_epoch_millis = :afterCreatedAtEpochMillis
               AND import_token > :afterImportToken
           )
        ORDER BY created_at_epoch_millis, import_token
        LIMIT :limit
        """,
    )
    fun findAuthorityPage(
        afterCreatedAtEpochMillis: Long?,
        afterImportToken: String?,
        limit: Int,
    ): List<ReferenceImportFileOperationEntity>

    @Insert
    fun insertInitialOperation(operation: ReferenceImportFileOperationEntity)

    @Query(
        """
        UPDATE reference_import_file_operations
        SET stage = 'EXPECTING_RESERVATION',
            byte_count = NULL,
            sha256 = NULL,
            last_failure_code = NULL,
            reconciliation_required = 0,
            created_at_epoch_millis = :reservedAtEpochMillis,
            updated_at_epoch_millis = :reservedAtEpochMillis
        WHERE import_token = :importToken
          AND stage = 'CLEANED_DURABLE'
          AND byte_count IS NULL
          AND sha256 IS NULL
          AND last_failure_code IS NULL
          AND reconciliation_required = 0
          AND created_at_epoch_millis >= 0
          AND updated_at_epoch_millis = :expectedUpdatedAtEpochMillis
          AND :reservedAtEpochMillis > updated_at_epoch_millis
        """,
    )
    fun resetCleanedOperation(
        importToken: String,
        expectedUpdatedAtEpochMillis: Long,
        reservedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE reference_import_file_operations
        SET stage = :targetStage,
            byte_count = :targetByteCount,
            sha256 = :targetSha256,
            last_failure_code = :targetFailureCode,
            reconciliation_required = :targetReconciliationRequired,
            updated_at_epoch_millis = :targetUpdatedAtEpochMillis
        WHERE import_token = :importToken
          AND stage = :expectedStage
          AND updated_at_epoch_millis = :expectedUpdatedAtEpochMillis
          AND :targetUpdatedAtEpochMillis > updated_at_epoch_millis
        """,
    )
    fun compareAndSetOperation(
        importToken: String,
        expectedStage: ReferenceImportFileOperationStage,
        expectedUpdatedAtEpochMillis: Long,
        targetStage: ReferenceImportFileOperationStage,
        targetByteCount: Long?,
        targetSha256: String?,
        targetFailureCode: ReferenceImportFileFailureCode?,
        targetReconciliationRequired: Boolean,
        targetUpdatedAtEpochMillis: Long,
    ): Int
}
