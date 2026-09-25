package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFileTransitionPolicyTest {
    @Test
    fun closedTransitionTablePermitsEveryAndOnlyListedEdge() {
        val legal = setOf(
            edge("EXPECTING_RESERVATION", "WRITING_TEMP"),
            edge("WRITING_TEMP", "TEMP_SYNCED"),
            edge("TEMP_SYNCED", "FINAL_RENAME_PENDING_SYNC"),
            edge("FINAL_RENAME_PENDING_SYNC", "FINAL_DURABLE"),
            edge("TEMP_SYNCED", "QUARANTINE_REQUIRED"),
            edge("FINAL_RENAME_PENDING_SYNC", "QUARANTINE_REQUIRED"),
            edge("FINAL_DURABLE", "QUARANTINE_REQUIRED"),
            edge("QUARANTINE_REQUIRED", "QUARANTINE_PENDING_SYNC"),
            edge("QUARANTINE_PENDING_SYNC", "QUARANTINE_DURABLE"),
            edge("EXPECTING_RESERVATION", "CLEANUP_REQUIRED"),
            edge("WRITING_TEMP", "CLEANUP_REQUIRED"),
            edge("TEMP_SYNCED", "CLEANUP_REQUIRED"),
            edge("FINAL_RENAME_PENDING_SYNC", "CLEANUP_REQUIRED"),
            edge("FINAL_DURABLE", "CLEANUP_REQUIRED"),
            edge("QUARANTINE_REQUIRED", "CLEANUP_REQUIRED"),
            edge("QUARANTINE_PENDING_SYNC", "CLEANUP_REQUIRED"),
            edge("CLEANUP_REQUIRED", "CLEANUP_PENDING_SYNC"),
            edge("CLEANUP_PENDING_SYNC", "CLEANED_DURABLE"),
        )
        CaptureFileOperationStage.entries.forEach { sourceStage ->
            CaptureFileOperationStage.entries.forEach { targetStage ->
                val decision = CaptureFileTransitionPolicy.advance(
                    snapshot(sourceStage),
                    request(snapshot(sourceStage), targetStage),
                )
                if (sourceStage to targetStage in legal) {
                    assertTrue("expected $sourceStage to $targetStage", decision is CaptureFileJournalResult.Applied)
                } else {
                    assertEquals(
                        CaptureFileJournalResult.Rejected(CaptureFileJournalRejectionReason.ILLEGAL_TRANSITION),
                        decision,
                    )
                }
            }
        }
    }

    @Test
    fun evidenceIsAttachedOncePreservedAndClearedOnlyAfterCleanup() {
        val writing = applied(snapshot(), CaptureFileOperationStage.WRITING_TEMP)
        val synced = applied(writing, CaptureFileOperationStage.TEMP_SYNCED)
        assertEquals(7L, synced.byteCount)
        assertEquals(SHA, synced.sha256)
        assertEquals(21L, synced.capturedAtEpochMillis)
        val rename = applied(synced, CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC)
        val durable = applied(rename, CaptureFileOperationStage.FINAL_DURABLE)
        val cleanup = applied(durable, CaptureFileOperationStage.CLEANUP_REQUIRED)
        val pending = applied(cleanup, CaptureFileOperationStage.CLEANUP_PENDING_SYNC)
        val cleaned = applied(pending, CaptureFileOperationStage.CLEANED_DURABLE)
        assertEquals(null, cleaned.byteCount)
        assertEquals(null, cleaned.sha256)
        assertEquals(null, cleaned.capturedAtEpochMillis)
    }

    @Test
    fun unsyncedCleanupCarriesNoInventedEvidence() {
        val writing = applied(snapshot(), CaptureFileOperationStage.WRITING_TEMP)
        val cleanup = applied(writing, CaptureFileOperationStage.CLEANUP_REQUIRED)
        assertEquals(null, cleanup.byteCount)
        assertEquals(null, cleanup.sha256)
        assertEquals(null, cleanup.capturedAtEpochMillis)
    }

    @Test
    fun staleIdentityStageOrClockAndNonmonotonicTimeReject() {
        val source = snapshot(CaptureFileOperationStage.TEMP_SYNCED)
        val valid = request(source, CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC)
        listOf(
            valid.copy(identity = identity(1)),
            valid.copy(expectedStage = CaptureFileOperationStage.WRITING_TEMP),
            valid.copy(expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis - 1),
        ).forEach { candidate ->
            assertEquals(rejected(CaptureFileJournalRejectionReason.STALE_SNAPSHOT), CaptureFileTransitionPolicy.advance(source, candidate))
        }
        listOf(source.updatedAtEpochMillis, -1L).forEach { time ->
            assertEquals(
                rejected(CaptureFileJournalRejectionReason.INVALID_TIMESTAMP),
                CaptureFileTransitionPolicy.advance(source, valid.copy(transitionedAtEpochMillis = time)),
            )
        }
    }

    @Test
    fun newOrChangedEvidenceRejectsOutsideWritingSettlement() {
        val writing = snapshot(CaptureFileOperationStage.WRITING_TEMP)
        listOf(
            request(writing, CaptureFileOperationStage.TEMP_SYNCED).copy(byteCount = 0L),
            request(writing, CaptureFileOperationStage.TEMP_SYNCED).copy(sha256 = "AB".repeat(32)),
            request(writing, CaptureFileOperationStage.TEMP_SYNCED).copy(capturedAtEpochMillis = 31L),
        ).forEach { candidate ->
            assertEquals(rejected(CaptureFileJournalRejectionReason.INVALID_EVIDENCE), CaptureFileTransitionPolicy.advance(writing, candidate))
        }
        val synced = snapshot(CaptureFileOperationStage.TEMP_SYNCED)
        val changed = request(synced, CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC).copy(sha256 = "cd".repeat(32))
        assertEquals(rejected(CaptureFileJournalRejectionReason.INVALID_EVIDENCE), CaptureFileTransitionPolicy.advance(synced, changed))
    }

    @Test
    fun captureEvidenceMustFallWithinTheJournalTransitionWindow() {
        val writing = snapshot(CaptureFileOperationStage.WRITING_TEMP)
        val valid = request(writing, CaptureFileOperationStage.TEMP_SYNCED)
        assertEquals(
            rejected(CaptureFileJournalRejectionReason.INVALID_EVIDENCE),
            CaptureFileTransitionPolicy.advance(
                writing,
                valid.copy(capturedAtEpochMillis = writing.createdAtEpochMillis - 1L),
            ),
        )
        assertEquals(
            rejected(CaptureFileJournalRejectionReason.INVALID_EVIDENCE),
            CaptureFileTransitionPolicy.advance(
                writing,
                valid.copy(capturedAtEpochMillis = valid.transitionedAtEpochMillis + 1L),
            ),
        )
    }

    @Test
    fun deletionInterlockUsesExactlyTheFourExternalEffectAdmissionStages() {
        val admitted = setOf(
            CaptureFileOperationStage.WRITING_TEMP,
            CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            CaptureFileOperationStage.CLEANUP_PENDING_SYNC,
            CaptureFileOperationStage.QUARANTINE_PENDING_SYNC,
        )
        CaptureFileOperationStage.entries.forEach { stage ->
            assertEquals(stage in admitted, hasAdmittedCaptureFileEffect(listOf(stage)))
        }
        assertFalse(hasAdmittedCaptureFileEffect(emptyList()))
    }

    @Test
    fun successfulAdvanceClearsPriorFailureMarker() {
        val failed = snapshot(CaptureFileOperationStage.TEMP_SYNCED).copy(
            lastFailureCode = CaptureFileFailureCode.STATE_MISMATCH,
            reconciliationRequired = true,
        )
        val result = CaptureFileTransitionPolicy.advance(
            failed,
            request(failed, CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC),
        ) as CaptureFileJournalResult.Applied
        assertEquals(null, result.snapshot.lastFailureCode)
        assertFalse(result.snapshot.reconciliationRequired)
    }

    @Test
    fun contractsRedactTokenHashAndPaths() {
        val source = snapshot(CaptureFileOperationStage.TEMP_SYNCED)
        val values = listOf(
            source,
            request(source, CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC),
            CaptureFileReconciliationRequest(source.identity, source.stage, source.updatedAtEpochMillis, CaptureFileFailureCode.WRITE_FAILED, 31L),
            CaptureFileReconciliationResolutionRequest(source.identity, source.stage, source.updatedAtEpochMillis, 31L),
        )
        values.forEach { value ->
            val rendered = value.toString()
            assertFalse(rendered.contains(TOKEN))
            assertFalse(rendered.contains(SHA))
            assertFalse(rendered.contains("capture-candidates"))
        }
    }

    private fun applied(source: CaptureFileOperationSnapshot, target: CaptureFileOperationStage): CaptureFileOperationSnapshot =
        (CaptureFileTransitionPolicy.advance(source, request(source, target)) as CaptureFileJournalResult.Applied).snapshot

    private fun request(source: CaptureFileOperationSnapshot, target: CaptureFileOperationStage): CaptureFileAdvanceRequest {
        val hasEvidence = target !in setOf(CaptureFileOperationStage.WRITING_TEMP, CaptureFileOperationStage.CLEANED_DURABLE)
        val evidence = if (target == CaptureFileOperationStage.TEMP_SYNCED) Triple(7L, SHA, 21L) else Triple(source.byteCount, source.sha256, source.capturedAtEpochMillis)
        return CaptureFileAdvanceRequest(
            identity = source.identity,
            expectedStage = source.stage,
            expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
            targetStage = target,
            byteCount = if (hasEvidence) evidence.first else null,
            sha256 = if (hasEvidence) evidence.second else null,
            capturedAtEpochMillis = if (hasEvidence) evidence.third else null,
            transitionedAtEpochMillis = source.updatedAtEpochMillis + 10L,
        )
    }

    private fun snapshot(stage: CaptureFileOperationStage = CaptureFileOperationStage.EXPECTING_RESERVATION): CaptureFileOperationSnapshot {
        val hasEvidence = stage in setOf(
            CaptureFileOperationStage.TEMP_SYNCED,
            CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            CaptureFileOperationStage.FINAL_DURABLE,
            CaptureFileOperationStage.QUARANTINE_REQUIRED,
            CaptureFileOperationStage.QUARANTINE_PENDING_SYNC,
            CaptureFileOperationStage.QUARANTINE_DURABLE,
        )
        return CaptureFileOperationSnapshot(
            identity = identity(),
            paths = CaptureFileOperationPaths.forIdentity(identity()),
            stage = stage,
            byteCount = if (hasEvidence) 7L else null,
            sha256 = if (hasEvidence) SHA else null,
            capturedAtEpochMillis = if (hasEvidence) 11L else null,
            lastFailureCode = null,
            reconciliationRequired = false,
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
        )
    }

    private fun identity(ordinal: Int = 0) = PrivateOutputIdentity(CaptureToken(TOKEN), ordinal)
    private fun edge(source: String, target: String) = CaptureFileOperationStage.valueOf(source) to CaptureFileOperationStage.valueOf(target)
    private fun rejected(reason: CaptureFileJournalRejectionReason) = CaptureFileJournalResult.Rejected(reason)

    private companion object {
        const val TOKEN = "capture-policy-sensitive-token"
        val SHA = "ab".repeat(32)
    }
}
