package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.data.db.CaptureFileOperationEntity
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFileOperationContractTest {
    @Test
    fun wellFormedUtf16AcceptsBmpSupplementaryUnicodeAndPathLikeTokens() {
        val supplementary = String(Character.toChars(0x1F4F8))
        val values = listOf(
            "capture-token",
            "摄影-token",
            "token-$supplementary",
            "../opaque/path-like/token.jpg",
            "folder\\opaque:token",
        )

        values.forEach { value ->
            assertTrue(isWellFormedUtf16(value))
            val paths = CaptureFileOperationPaths.forIdentity(
                PrivateOutputIdentity(CaptureToken(value), 1),
            )
            assertTrue(isNormalizedPrivateRelativePath(paths.relativeFinalPath))
            assertTrue(isNormalizedPrivateRelativePath(paths.relativeTempPath))
            assertTrue(isNormalizedPrivateRelativePath(paths.relativeQuarantinePath))
        }
    }

    @Test
    fun distinctLoneSurrogatesRejectBeforeHashingWithoutValueLeak() {
        val loneHigh = charArrayOf(0xD800.toChar()).concatToString()
        val loneLow = charArrayOf(0xDC00.toChar()).concatToString()
        assertNotEquals(loneHigh, loneLow)
        assertFalse(isWellFormedUtf16(loneHigh))
        assertFalse(isWellFormedUtf16(loneLow))

        listOf(loneHigh, loneLow).forEach { malformed ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                CaptureFileOperationPaths.forIdentity(
                    PrivateOutputIdentity(CaptureToken(malformed), 0),
                )
            }
            assertEquals(INVALID_CAPTURE_FILE_TOKEN_ENCODING_MESSAGE, failure.message)
            assertFalse(failure.toString().contains(malformed))
            assertFalse(failure.stackTraceToString().contains(malformed))
        }
    }

    @Test
    fun deterministicPathsAreStableDistinctAndNormalized() {
        val identity = PrivateOutputIdentity(CaptureToken("capture-token"), 2)
        val digest = "7c0f245b7ae3cf9de505d21d9ef00f4aae7ab8464456f088363812ab3c6fa602"
        val first = CaptureFileOperationPaths.forIdentity(identity)
        val second = CaptureFileOperationPaths.forIdentity(identity)

        assertEquals(first, second)
        assertEquals("capture-candidates/$digest-2.jpg", first.relativeFinalPath)
        assertEquals("capture-candidates/.$digest-2.jpg.pending", first.relativeTempPath)
        assertEquals("capture-quarantine/$digest-2.quarantined", first.relativeQuarantinePath)
        assertEquals(3, setOf(first.relativeFinalPath, first.relativeTempPath, first.relativeQuarantinePath).size)
        listOf(first.relativeFinalPath, first.relativeTempPath, first.relativeQuarantinePath).forEach { path ->
            assertTrue(isNormalizedPrivateRelativePath(path))
            assertFalse(path.contains(identity.token.value))
            assertEquals(path, path.replace("//", "/"))
        }
    }

    @Test
    fun publicContractsRenderWithoutTokenOrPaths() {
        val rawToken = "<SECRET:capture-token>"
        val identity = PrivateOutputIdentity(CaptureToken(rawToken), 1)
        val paths = CaptureFileOperationPaths.forIdentity(identity)
        val snapshot = CaptureFileOperationSnapshot(
            identity = identity,
            paths = paths,
            stage = CaptureFileOperationStage.TEMP_SYNCED,
            byteCount = 10L,
            sha256 = "ab".repeat(32),
            capturedAtEpochMillis = 11L,
            lastFailureCode = CaptureFileFailureCode.STATE_MISMATCH,
            reconciliationRequired = true,
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 12L,
        )
        val values = listOf(
            paths,
            snapshot,
            CaptureFileAdvanceRequest(
                identity,
                CaptureFileOperationStage.TEMP_SYNCED,
                12L,
                CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
                10L,
                "ab".repeat(32),
                11L,
                13L,
            ),
            CaptureFileReconciliationRequest(
                identity,
                CaptureFileOperationStage.TEMP_SYNCED,
                12L,
                CaptureFileFailureCode.STATE_MISMATCH,
                13L,
            ),
            CaptureFileReconciliationResolutionRequest(
                identity,
                CaptureFileOperationStage.TEMP_SYNCED,
                12L,
                13L,
            ),
            CaptureFileJournalResult.Applied(snapshot),
            CaptureFileJournalResult.Idempotent(snapshot),
            CaptureFileJournalResult.BlockedByDeletion,
            CaptureFileJournalResult.Rejected(CaptureFileJournalRejectionReason.INVALID_EVIDENCE),
        )
        val sensitive = listOf(
            rawToken,
            paths.relativeFinalPath,
            paths.relativeTempPath,
            paths.relativeQuarantinePath,
            "ab".repeat(32),
        )

        values.forEach { value ->
            val rendered = value.toString()
            sensitive.forEach { marker -> assertFalse(rendered.contains(marker)) }
        }
    }

    @Test
    fun stageFailureAndEvidenceContractsAreClosedAndExact() {
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
            CaptureFileOperationStage.entries.map(Enum<*>::name),
        )
        assertEquals(
            listOf(
                "RESERVATION_FAILED",
                "WRITE_FAILED",
                "FILE_SYNC_FAILED",
                "RENAME_FAILED",
                "DIRECTORY_SYNC_FAILED",
                "DELETE_FAILED",
                "STATE_MISMATCH",
                "EVIDENCE_MISMATCH",
            ),
            CaptureFileFailureCode.entries.map(Enum<*>::name),
        )

        val validSha256 = "ab".repeat(32)
        val evidenceRequired = setOf(
            CaptureFileOperationStage.TEMP_SYNCED,
            CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            CaptureFileOperationStage.FINAL_DURABLE,
            CaptureFileOperationStage.QUARANTINE_REQUIRED,
            CaptureFileOperationStage.QUARANTINE_PENDING_SYNC,
            CaptureFileOperationStage.QUARANTINE_DURABLE,
        )
        val evidenceOptional = setOf(
            CaptureFileOperationStage.CLEANUP_REQUIRED,
            CaptureFileOperationStage.CLEANUP_PENDING_SYNC,
        )
        val evidenceForbidden = CaptureFileOperationStage.entries.toSet() -
            evidenceRequired - evidenceOptional

        CaptureFileOperationStage.entries.forEach { stage ->
            assertEquals(
                stage.name,
                stage in evidenceOptional || stage in evidenceForbidden,
                hasValidCaptureFileOperationEvidence(stage, null, null, null),
            )
            assertEquals(
                stage.name,
                stage in evidenceOptional || stage in evidenceRequired,
                hasValidCaptureFileOperationEvidence(stage, 1L, validSha256, 2L),
            )
            assertFalse(stage.name, hasValidCaptureFileOperationEvidence(stage, 1L, null, 2L))
            assertFalse(stage.name, hasValidCaptureFileOperationEvidence(stage, null, validSha256, 2L))
            assertFalse(stage.name, hasValidCaptureFileOperationEvidence(stage, 1L, validSha256, null))
            assertFalse(stage.name, hasValidCaptureFileOperationEvidence(stage, 0L, validSha256, 2L))
            assertFalse(stage.name, hasValidCaptureFileOperationEvidence(stage, 1L, "AB".repeat(32), 2L))
            assertFalse(stage.name, hasValidCaptureFileOperationEvidence(stage, 1L, "ab".repeat(31), 2L))
            assertFalse(stage.name, hasValidCaptureFileOperationEvidence(stage, 1L, validSha256, -1L))
        }
    }

    @Test
    fun entityRejectsMismatchedPathsEvidenceReconciliationAndTimestamps() {
        val identity = PrivateOutputIdentity(CaptureToken("entity-token"), 1)
        val paths = CaptureFileOperationPaths.forIdentity(identity)
        fun row(
            finalPath: String = paths.relativeFinalPath,
            stage: CaptureFileOperationStage = CaptureFileOperationStage.EXPECTING_RESERVATION,
            byteCount: Long? = null,
            sha256: String? = null,
            capturedAt: Long? = null,
            failure: CaptureFileFailureCode? = null,
            reconciliation: Boolean = failure != null,
            createdAt: Long = 1L,
            updatedAt: Long = 1L,
        ) = CaptureFileOperationEntity(
            commandToken = identity.token.value,
            burstOrdinal = identity.ordinal,
            relativeFinalPath = finalPath,
            relativeTempPath = paths.relativeTempPath,
            relativeQuarantinePath = paths.relativeQuarantinePath,
            stage = stage,
            byteCount = byteCount,
            sha256 = sha256,
            capturedAtEpochMillis = capturedAt,
            lastFailureCode = failure,
            reconciliationRequired = reconciliation,
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = updatedAt,
        )

        row()
        assertThrows(IllegalArgumentException::class.java) { row(finalPath = "capture-candidates/wrong.jpg") }
        assertThrows(IllegalArgumentException::class.java) { row(stage = CaptureFileOperationStage.TEMP_SYNCED) }
        assertThrows(IllegalArgumentException::class.java) { row(reconciliation = true) }
        assertThrows(IllegalArgumentException::class.java) { row(createdAt = -1L) }
        assertThrows(IllegalArgumentException::class.java) { row(createdAt = 2L, updatedAt = 1L) }
        assertThrows(IllegalArgumentException::class.java) {
            row(
                stage = CaptureFileOperationStage.TEMP_SYNCED,
                byteCount = 1L,
                sha256 = "ab".repeat(32),
                capturedAt = 0L,
                createdAt = 1L,
                updatedAt = 2L,
            )
        }
    }
}
