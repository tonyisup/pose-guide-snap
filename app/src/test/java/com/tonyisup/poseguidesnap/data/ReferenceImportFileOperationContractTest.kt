package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.data.db.ReferenceImportFileOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceImportFileOperationContractTest {
    @Test
    fun stageVocabularyIsClosedAndExact() {
        assertEquals(
            listOf(
                "EXPECTING_RESERVATION",
                "WRITING_TEMP",
                "TEMP_SYNCED",
                "FINAL_RENAME_PENDING_SYNC",
                "FINAL_DURABLE",
                "CLEANUP_REQUIRED",
                "CLEANUP_PENDING_SYNC",
                "CLEANED_DURABLE",
                "QUARANTINE_REQUIRED",
                "QUARANTINE_PENDING_SYNC",
                "QUARANTINE_DURABLE",
            ),
            ReferenceImportFileOperationStage.entries.map(Enum<*>::name),
        )
    }

    @Test
    fun exactPathsAreDerivedOnlyFromTheTokenDigest() {
        val token = ReferenceImportToken("import-token")
        val paths = ReferenceImportFileOperationPaths.forToken(token)
        val digest = "7bfec5ef341a225462a03b2b88c486b59c0ac31845783cedc24d008b17dc676e"

        assertEquals("reference-assets/assets/$digest.asset", paths.relativeAssetPath)
        assertEquals("reference-assets/assets/.$digest.asset.pending", paths.relativeTempPath)
        assertEquals("reference-assets/quarantine/$digest.quarantined", paths.relativeQuarantinePath)
        listOf(
            paths.relativeAssetPath,
            paths.relativeTempPath,
            paths.relativeQuarantinePath,
            paths.toString(),
        ).forEach { rendered ->
            assertFalse(rendered.contains(token.value))
            assertFalse(rendered.contains("content://"))
        }
        assertEquals("ReferenceImportFileOperationPaths(redacted)", paths.toString())
    }

    @Test
    fun stageEvidenceInvariantDistinguishesAbsentRequiredOptionalAndClearedEvidence() {
        val validSha256 = "ab".repeat(32)
        val evidenceRequired = setOf(
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            ReferenceImportFileOperationStage.FINAL_DURABLE,
            ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
            ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
            ReferenceImportFileOperationStage.QUARANTINE_DURABLE,
        )
        val evidenceOptional = setOf(
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
            ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC,
        )
        val evidenceForbidden = setOf(
            ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
            ReferenceImportFileOperationStage.WRITING_TEMP,
            ReferenceImportFileOperationStage.CLEANED_DURABLE,
        )

        evidenceRequired.forEach { stage ->
            assertTrue(stage.name, hasValidReferenceImportFileOperationEvidence(stage, 1L, validSha256))
            assertFalse(stage.name, hasValidReferenceImportFileOperationEvidence(stage, null, null))
        }
        evidenceOptional.forEach { stage ->
            assertTrue(stage.name, hasValidReferenceImportFileOperationEvidence(stage, null, null))
            assertTrue(stage.name, hasValidReferenceImportFileOperationEvidence(stage, 1L, validSha256))
        }
        evidenceForbidden.forEach { stage ->
            assertTrue(stage.name, hasValidReferenceImportFileOperationEvidence(stage, null, null))
            assertFalse(stage.name, hasValidReferenceImportFileOperationEvidence(stage, 1L, validSha256))
        }

        ReferenceImportFileOperationStage.entries.forEach { stage ->
            assertFalse(stage.name, hasValidReferenceImportFileOperationEvidence(stage, 1L, null))
            assertFalse(stage.name, hasValidReferenceImportFileOperationEvidence(stage, null, validSha256))
            assertFalse(stage.name, hasValidReferenceImportFileOperationEvidence(stage, 0L, validSha256))
            assertFalse(stage.name, hasValidReferenceImportFileOperationEvidence(stage, 1L, "AB".repeat(32)))
            assertFalse(stage.name, hasValidReferenceImportFileOperationEvidence(stage, 1L, "ab".repeat(31)))
        }
    }

    @Test
    fun reconciliationFlagIsOrthogonalToTheInitialNonterminalStage() {
        val paths = ReferenceImportFileOperationPaths.forToken(ReferenceImportToken("token"))
        val rows = listOf(false, true).map { reconciliationRequired ->
            ReferenceImportFileOperationEntity(
                importToken = "token",
                relativeAssetPath = paths.relativeAssetPath,
                relativeTempPath = paths.relativeTempPath,
                relativeQuarantinePath = paths.relativeQuarantinePath,
                stage = ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
                byteCount = null,
                sha256 = null,
                lastFailureCode = if (reconciliationRequired) {
                    ReferenceImportFileFailureCode.STATE_MISMATCH
                } else {
                    null
                },
                reconciliationRequired = reconciliationRequired,
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
            )
        }

        assertEquals(listOf(false, true), rows.map(ReferenceImportFileOperationEntity::reconciliationRequired))
        assertEquals(
            listOf(null, ReferenceImportFileFailureCode.STATE_MISMATCH),
            rows.map(ReferenceImportFileOperationEntity::lastFailureCode),
        )
        assertTrue(rows.all { row -> row.stage == ReferenceImportFileOperationStage.EXPECTING_RESERVATION })
        assertTrue(rows.all { row -> row.byteCount == null && row.sha256 == null })
    }

    @Test
    fun entityRejectsNonDeterministicPathsInvalidEvidenceAndInvalidTimestamps() {
        val paths = ReferenceImportFileOperationPaths.forToken(ReferenceImportToken("token"))
        fun row(
            relativeTempPath: String = paths.relativeTempPath,
            stage: ReferenceImportFileOperationStage =
                ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
            byteCount: Long? = null,
            sha256: String? = null,
            createdAtEpochMillis: Long = 1L,
            updatedAtEpochMillis: Long = 1L,
        ) = ReferenceImportFileOperationEntity(
            importToken = "token",
            relativeAssetPath = paths.relativeAssetPath,
            relativeTempPath = relativeTempPath,
            relativeQuarantinePath = paths.relativeQuarantinePath,
            stage = stage,
            byteCount = byteCount,
            sha256 = sha256,
            lastFailureCode = null,
            reconciliationRequired = false,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

        row()
        assertThrows(IllegalArgumentException::class.java) {
            row(relativeTempPath = "reference-assets/assets/.wrong.asset.pending")
        }
        assertThrows(IllegalArgumentException::class.java) {
            row(stage = ReferenceImportFileOperationStage.TEMP_SYNCED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            row(byteCount = 1L, sha256 = "ab".repeat(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            row(createdAtEpochMillis = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            row(createdAtEpochMillis = 2L, updatedAtEpochMillis = 1L)
        }
    }
}
