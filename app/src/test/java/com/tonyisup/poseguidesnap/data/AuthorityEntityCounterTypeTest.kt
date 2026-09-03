package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.data.db.CaptureAttemptEntity
import com.tonyisup.poseguidesnap.data.db.CaptureConfirmationReceiptEntity
import com.tonyisup.poseguidesnap.data.db.CaptureExportOutboxEntity
import com.tonyisup.poseguidesnap.data.db.CaptureExportOutputEntity
import com.tonyisup.poseguidesnap.data.db.CaptureFileOperationEntity
import com.tonyisup.poseguidesnap.data.db.PrivateCaptureOutputEntity
import com.tonyisup.poseguidesnap.data.db.ReferenceImportFileOperationEntity
import com.tonyisup.poseguidesnap.data.db.ReferenceImportIntentEntity
import com.tonyisup.poseguidesnap.data.db.ShootEntity
import com.tonyisup.poseguidesnap.data.db.ShootPoseEntity
import com.tonyisup.poseguidesnap.data.db.ShootSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthorityEntityCounterTypeTest {
    @Test
    fun persistedNumericFieldsExposeTheCompleteV4GetterWidths() {
        val actual = mapOf(
            "ShootEntity.createdAtEpochMillis" to
                ShootEntity::class.java.getMethod("getCreatedAtEpochMillis").returnType,
            "ShootEntity.updatedAtEpochMillis" to
                ShootEntity::class.java.getMethod("getUpdatedAtEpochMillis").returnType,
            "ShootEntity.deletionGeneration" to
                ShootEntity::class.java.getMethod("getDeletionGeneration").returnType,
            "ShootPoseEntity.poseIndex" to
                ShootPoseEntity::class.java.getMethod("getPoseIndex").returnType,

            "ReferenceImportIntentEntity.createdAtEpochMillis" to
                ReferenceImportIntentEntity::class.java
                    .getMethod("getCreatedAtEpochMillis").returnType,
            "ReferenceImportIntentEntity.updatedAtEpochMillis" to
                ReferenceImportIntentEntity::class.java
                    .getMethod("getUpdatedAtEpochMillis").returnType,
            "ReferenceImportIntentEntity.assetReadyAtEpochMillis" to
                ReferenceImportIntentEntity::class.java
                    .getMethod("getAssetReadyAtEpochMillis").returnType,
            "ReferenceImportIntentEntity.terminalAtEpochMillis" to
                ReferenceImportIntentEntity::class.java
                    .getMethod("getTerminalAtEpochMillis").returnType,
            "ReferenceImportFileOperationEntity.byteCount" to
                ReferenceImportFileOperationEntity::class.java.getMethod("getByteCount").returnType,
            "ReferenceImportFileOperationEntity.createdAtEpochMillis" to
                ReferenceImportFileOperationEntity::class.java
                    .getMethod("getCreatedAtEpochMillis").returnType,
            "ReferenceImportFileOperationEntity.updatedAtEpochMillis" to
                ReferenceImportFileOperationEntity::class.java
                    .getMethod("getUpdatedAtEpochMillis").returnType,
            "CaptureFileOperationEntity.burstOrdinal" to
                CaptureFileOperationEntity::class.java.getMethod("getBurstOrdinal").returnType,
            "CaptureFileOperationEntity.byteCount" to
                CaptureFileOperationEntity::class.java.getMethod("getByteCount").returnType,
            "CaptureFileOperationEntity.capturedAtEpochMillis" to
                CaptureFileOperationEntity::class.java
                    .getMethod("getCapturedAtEpochMillis").returnType,
            "CaptureFileOperationEntity.createdAtEpochMillis" to
                CaptureFileOperationEntity::class.java
                    .getMethod("getCreatedAtEpochMillis").returnType,
            "CaptureFileOperationEntity.updatedAtEpochMillis" to
                CaptureFileOperationEntity::class.java
                    .getMethod("getUpdatedAtEpochMillis").returnType,
            "ShootSessionEntity.currentPoseIndex" to
                ShootSessionEntity::class.java.getMethod("getCurrentPoseIndex").returnType,
            "ShootSessionEntity.nextAttemptNumber" to
                ShootSessionEntity::class.java.getMethod("getNextAttemptNumber").returnType,
            "ShootSessionEntity.createdAtEpochMillis" to
                ShootSessionEntity::class.java.getMethod("getCreatedAtEpochMillis").returnType,
            "ShootSessionEntity.updatedAtEpochMillis" to
                ShootSessionEntity::class.java.getMethod("getUpdatedAtEpochMillis").returnType,
            "CaptureAttemptEntity.poseIndex" to
                CaptureAttemptEntity::class.java.getMethod("getPoseIndex").returnType,
            "CaptureAttemptEntity.attemptNumber" to
                CaptureAttemptEntity::class.java.getMethod("getAttemptNumber").returnType,
            "CaptureAttemptEntity.capturedDeletionGeneration" to
                CaptureAttemptEntity::class.java
                    .getMethod("getCapturedDeletionGeneration").returnType,
            "CaptureAttemptEntity.createdAtEpochMillis" to
                CaptureAttemptEntity::class.java.getMethod("getCreatedAtEpochMillis").returnType,
            "CaptureAttemptEntity.updatedAtEpochMillis" to
                CaptureAttemptEntity::class.java.getMethod("getUpdatedAtEpochMillis").returnType,
            "CaptureAttemptEntity.confirmedAtEpochMillis" to
                CaptureAttemptEntity::class.java.getMethod("getConfirmedAtEpochMillis").returnType,
            "PrivateCaptureOutputEntity.burstOrdinal" to
                PrivateCaptureOutputEntity::class.java.getMethod("getBurstOrdinal").returnType,
            "PrivateCaptureOutputEntity.byteCount" to
                PrivateCaptureOutputEntity::class.java.getMethod("getByteCount").returnType,
            "PrivateCaptureOutputEntity.capturedAtEpochMillis" to
                PrivateCaptureOutputEntity::class.java
                    .getMethod("getCapturedAtEpochMillis").returnType,
            "CaptureConfirmationReceiptEntity.fromPoseIndex" to
                CaptureConfirmationReceiptEntity::class.java.getMethod("getFromPoseIndex").returnType,
            "CaptureConfirmationReceiptEntity.toPoseIndex" to
                CaptureConfirmationReceiptEntity::class.java.getMethod("getToPoseIndex").returnType,
            "CaptureConfirmationReceiptEntity.appliedDeletionGeneration" to
                CaptureConfirmationReceiptEntity::class.java
                    .getMethod("getAppliedDeletionGeneration").returnType,
            "CaptureConfirmationReceiptEntity.appliedAtEpochMillis" to
                CaptureConfirmationReceiptEntity::class.java
                    .getMethod("getAppliedAtEpochMillis").returnType,
            "CaptureExportOutboxEntity.createdAtEpochMillis" to
                CaptureExportOutboxEntity::class.java.getMethod("getCreatedAtEpochMillis").returnType,
            "CaptureExportOutboxEntity.updatedAtEpochMillis" to
                CaptureExportOutboxEntity::class.java.getMethod("getUpdatedAtEpochMillis").returnType,
            "CaptureExportOutputEntity.burstOrdinal" to
                CaptureExportOutputEntity::class.java.getMethod("getBurstOrdinal").returnType,
            "CaptureExportOutputEntity.deletionGeneration" to
                CaptureExportOutputEntity::class.java.getMethod("getDeletionGeneration").returnType,
            "CaptureExportOutputEntity.createdAtEpochMillis" to
                CaptureExportOutputEntity::class.java.getMethod("getCreatedAtEpochMillis").returnType,
            "CaptureExportOutputEntity.updatedAtEpochMillis" to
                CaptureExportOutputEntity::class.java.getMethod("getUpdatedAtEpochMillis").returnType,
        )
        val primitiveLong = Long::class.javaPrimitiveType
        val boxedLong = Long::class.javaObjectType
        val primitiveInt = Int::class.javaPrimitiveType
        val boxedInt = Int::class.javaObjectType
        val expected = mapOf(
            "ShootEntity.createdAtEpochMillis" to primitiveLong,
            "ShootEntity.updatedAtEpochMillis" to primitiveLong,
            "ShootEntity.deletionGeneration" to primitiveLong,
            "ShootPoseEntity.poseIndex" to primitiveInt,

            "ReferenceImportIntentEntity.createdAtEpochMillis" to primitiveLong,
            "ReferenceImportIntentEntity.updatedAtEpochMillis" to primitiveLong,
            "ReferenceImportIntentEntity.assetReadyAtEpochMillis" to boxedLong,
            "ReferenceImportIntentEntity.terminalAtEpochMillis" to boxedLong,
            "ReferenceImportFileOperationEntity.byteCount" to boxedLong,
            "ReferenceImportFileOperationEntity.createdAtEpochMillis" to primitiveLong,
            "ReferenceImportFileOperationEntity.updatedAtEpochMillis" to primitiveLong,
            "CaptureFileOperationEntity.burstOrdinal" to primitiveInt,
            "CaptureFileOperationEntity.byteCount" to boxedLong,
            "CaptureFileOperationEntity.capturedAtEpochMillis" to boxedLong,
            "CaptureFileOperationEntity.createdAtEpochMillis" to primitiveLong,
            "CaptureFileOperationEntity.updatedAtEpochMillis" to primitiveLong,
            "ShootSessionEntity.currentPoseIndex" to primitiveInt,
            "ShootSessionEntity.nextAttemptNumber" to primitiveLong,
            "ShootSessionEntity.createdAtEpochMillis" to primitiveLong,
            "ShootSessionEntity.updatedAtEpochMillis" to primitiveLong,
            "CaptureAttemptEntity.poseIndex" to primitiveInt,
            "CaptureAttemptEntity.attemptNumber" to primitiveLong,
            "CaptureAttemptEntity.capturedDeletionGeneration" to primitiveLong,
            "CaptureAttemptEntity.createdAtEpochMillis" to primitiveLong,
            "CaptureAttemptEntity.updatedAtEpochMillis" to primitiveLong,
            "CaptureAttemptEntity.confirmedAtEpochMillis" to boxedLong,
            "PrivateCaptureOutputEntity.burstOrdinal" to primitiveInt,
            "PrivateCaptureOutputEntity.byteCount" to primitiveLong,
            "PrivateCaptureOutputEntity.capturedAtEpochMillis" to primitiveLong,
            "CaptureConfirmationReceiptEntity.fromPoseIndex" to primitiveInt,
            "CaptureConfirmationReceiptEntity.toPoseIndex" to boxedInt,
            "CaptureConfirmationReceiptEntity.appliedDeletionGeneration" to primitiveLong,
            "CaptureConfirmationReceiptEntity.appliedAtEpochMillis" to primitiveLong,
            "CaptureExportOutboxEntity.createdAtEpochMillis" to primitiveLong,
            "CaptureExportOutboxEntity.updatedAtEpochMillis" to primitiveLong,
            "CaptureExportOutputEntity.burstOrdinal" to primitiveInt,
            "CaptureExportOutputEntity.deletionGeneration" to primitiveLong,
            "CaptureExportOutputEntity.createdAtEpochMillis" to primitiveLong,
            "CaptureExportOutputEntity.updatedAtEpochMillis" to primitiveLong,
        )

        assertEquals(expected, actual)
    }
}
