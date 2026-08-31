package com.tonyisup.poseguidesnap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceImportFileTransitionPolicyTest {
    @Test
    fun snapshotsAndRequestsAreRedacted() {
        val rawToken = "sensitive-import-token"
        val sensitiveSha = VALID_SHA
        val snapshot = snapshot(
            stage = ReferenceImportFileOperationStage.TEMP_SYNCED,
            byteCount = 7L,
            sha256 = sensitiveSha,
            token = rawToken,
        )
        val advance = request(
            source = snapshot,
            target = ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
        )
        val reconciliation = ReferenceImportFileReconciliationRequest(
            importToken = ReferenceImportToken(rawToken),
            expectedStage = ReferenceImportFileOperationStage.TEMP_SYNCED,
            expectedUpdatedAtEpochMillis = 20L,
            failureCode = ReferenceImportFileFailureCode.STATE_MISMATCH,
            markedAtEpochMillis = 21L,
        )
        val resolution = ReferenceImportFileReconciliationResolutionRequest(
            importToken = ReferenceImportToken(rawToken),
            expectedStage = ReferenceImportFileOperationStage.TEMP_SYNCED,
            expectedUpdatedAtEpochMillis = 21L,
            resolvedAtEpochMillis = 22L,
        )

        listOf(snapshot, advance, reconciliation, resolution).forEach { value ->
            val rendered = value.toString()
            assertFalse(rendered, rendered.contains(rawToken))
            assertFalse(rendered, rendered.contains(sensitiveSha))
            assertFalse(rendered, rendered.contains("reference-assets/"))
            assertTrue(rendered, rendered.contains("redacted"))
        }
    }

    @Test
    fun closedTransitionTablePermitsEveryAndOnlyListedEdge() {
        val legalEdges = setOf(
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

        ReferenceImportFileOperationStage.entries.forEach { sourceStage ->
            ReferenceImportFileOperationStage.entries.forEach { targetStage ->
                val source = snapshotForStage(sourceStage)
                val decision = ReferenceImportFileTransitionPolicy.advance(
                    source,
                    request(source, targetStage),
                )
                val actualEdge = sourceStage to targetStage
                if (actualEdge in legalEdges) {
                    assertTrue("expected legal $actualEdge but was $decision", decision is ReferenceImportFileJournalResult.Applied)
                } else {
                    assertEquals(
                        "expected forbidden $actualEdge",
                        ReferenceImportFileJournalResult.Rejected(
                            ReferenceImportFileJournalRejectionReason.ILLEGAL_TRANSITION,
                        ),
                        decision,
                    )
                }
            }
        }
    }

    @Test
    fun syncedEvidenceIsPreservedThroughForwardQuarantineAndCleanupPendingStages() {
        val targets = listOf(
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
        )
        val source = snapshotForStage(ReferenceImportFileOperationStage.TEMP_SYNCED)

        targets.forEach { target ->
            val applied = ReferenceImportFileTransitionPolicy.advance(source, request(source, target))
                as ReferenceImportFileJournalResult.Applied
            assertEquals(source.byteCount, applied.snapshot.byteCount)
            assertEquals(source.sha256, applied.snapshot.sha256)
        }

        val cleanupRequired = (ReferenceImportFileTransitionPolicy.advance(
            source,
            request(source, ReferenceImportFileOperationStage.CLEANUP_REQUIRED),
        ) as ReferenceImportFileJournalResult.Applied).snapshot
        val cleanupPending = (ReferenceImportFileTransitionPolicy.advance(
            cleanupRequired,
            request(cleanupRequired, ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC),
        ) as ReferenceImportFileJournalResult.Applied).snapshot
        assertEquals(source.byteCount, cleanupPending.byteCount)
        assertEquals(source.sha256, cleanupPending.sha256)
    }

    @Test
    fun unsyncedCleanupHasNoEvidenceAndCleanedDurableClearsSyncedEvidence() {
        val unsynced = snapshotForStage(ReferenceImportFileOperationStage.WRITING_TEMP)
        val unsyncedCleanup = ReferenceImportFileTransitionPolicy.advance(
            unsynced,
            request(unsynced, ReferenceImportFileOperationStage.CLEANUP_REQUIRED),
        ) as ReferenceImportFileJournalResult.Applied
        assertEquals(null, unsyncedCleanup.snapshot.byteCount)
        assertEquals(null, unsyncedCleanup.snapshot.sha256)

        val syncedCleanupPending = snapshot(
            stage = ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC,
            byteCount = 7L,
            sha256 = VALID_SHA,
        )
        val cleaned = ReferenceImportFileTransitionPolicy.advance(
            syncedCleanupPending,
            request(syncedCleanupPending, ReferenceImportFileOperationStage.CLEANED_DURABLE),
        ) as ReferenceImportFileJournalResult.Applied
        assertEquals(null, cleaned.snapshot.byteCount)
        assertEquals(null, cleaned.snapshot.sha256)
    }

    @Test
    fun legalTransitionRejectsMissingChangedOrNoncanonicalEvidence() {
        val writing = snapshotForStage(ReferenceImportFileOperationStage.WRITING_TEMP)
        listOf(
            request(writing, ReferenceImportFileOperationStage.TEMP_SYNCED).copy(byteCount = null, sha256 = null),
            request(writing, ReferenceImportFileOperationStage.TEMP_SYNCED).copy(byteCount = 0L),
            request(writing, ReferenceImportFileOperationStage.TEMP_SYNCED).copy(sha256 = "AB".repeat(32)),
        ).forEach { candidate ->
            assertEquals(
                ReferenceImportFileJournalResult.Rejected(
                    ReferenceImportFileJournalRejectionReason.EVIDENCE_MISMATCH,
                ),
                ReferenceImportFileTransitionPolicy.advance(writing, candidate),
            )
        }

        val synced = snapshotForStage(ReferenceImportFileOperationStage.TEMP_SYNCED)
        assertEquals(
            ReferenceImportFileJournalResult.Rejected(
                ReferenceImportFileJournalRejectionReason.EVIDENCE_MISMATCH,
            ),
            ReferenceImportFileTransitionPolicy.advance(
                synced,
                request(synced, ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC)
                    .copy(sha256 = "cd".repeat(32)),
            ),
        )
    }

    @Test
    fun successfulRetryAdvanceClearsReconciliationFlagAndFailureWithoutBlockingStage() {
        val source = snapshot(
            stage = ReferenceImportFileOperationStage.TEMP_SYNCED,
            byteCount = 7L,
            sha256 = VALID_SHA,
            reconciliationRequired = true,
            failureCode = ReferenceImportFileFailureCode.STATE_MISMATCH,
        )

        val result = ReferenceImportFileTransitionPolicy.advance(
            source,
            request(source, ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC),
        ) as ReferenceImportFileJournalResult.Applied

        assertEquals(ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC, result.snapshot.stage)
        assertFalse(result.snapshot.reconciliationRequired)
        assertEquals(null, result.snapshot.lastFailureCode)
    }

    @Test
    fun transitionRequiresExactSourceIdentityAndStrictlyMonotonicInjectedTimestamp() {
        val source = snapshotForStage(ReferenceImportFileOperationStage.TEMP_SYNCED)
        val valid = request(source, ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC)
        val staleRequests = listOf(
            valid.copy(importToken = ReferenceImportToken("different-token")),
            valid.copy(expectedStage = ReferenceImportFileOperationStage.WRITING_TEMP),
            valid.copy(expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis - 1L),
        )
        staleRequests.forEach { candidate ->
            assertEquals(
                ReferenceImportFileJournalResult.Rejected(
                    ReferenceImportFileJournalRejectionReason.STALE_SNAPSHOT,
                ),
                ReferenceImportFileTransitionPolicy.advance(source, candidate),
            )
        }
        listOf(source.updatedAtEpochMillis, -1L).forEach { invalidTimestamp ->
            assertEquals(
                ReferenceImportFileJournalResult.Rejected(
                    ReferenceImportFileJournalRejectionReason.INVALID_TIMESTAMP,
                ),
                ReferenceImportFileTransitionPolicy.advance(
                    source,
                    valid.copy(transitionedAtEpochMillis = invalidTimestamp),
                ),
            )
        }
    }

    private fun request(
        source: ReferenceImportFileOperationSnapshot,
        target: ReferenceImportFileOperationStage,
    ): ReferenceImportFileAdvanceRequest {
        val (byteCount, sha256) = when (target) {
            ReferenceImportFileOperationStage.WRITING_TEMP,
            ReferenceImportFileOperationStage.CLEANED_DURABLE,
            -> null to null
            ReferenceImportFileOperationStage.TEMP_SYNCED -> 7L to VALID_SHA
            else -> source.byteCount to source.sha256
        }
        return ReferenceImportFileAdvanceRequest(
            importToken = source.importToken,
            expectedStage = source.stage,
            expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
            targetStage = target,
            byteCount = byteCount,
            sha256 = sha256,
            transitionedAtEpochMillis = source.updatedAtEpochMillis + 1L,
        )
    }

    private fun snapshotForStage(stage: ReferenceImportFileOperationStage):
        ReferenceImportFileOperationSnapshot =
        when (stage) {
            ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
            ReferenceImportFileOperationStage.WRITING_TEMP,
            ReferenceImportFileOperationStage.CLEANED_DURABLE,
            -> snapshot(stage = stage)
            else -> snapshot(stage = stage, byteCount = 7L, sha256 = VALID_SHA)
        }

    private fun snapshot(
        stage: ReferenceImportFileOperationStage,
        byteCount: Long? = null,
        sha256: String? = null,
        reconciliationRequired: Boolean = false,
        failureCode: ReferenceImportFileFailureCode? = null,
        token: String = "import-token",
    ): ReferenceImportFileOperationSnapshot {
        val importToken = ReferenceImportToken(token)
        return ReferenceImportFileOperationSnapshot(
            importToken = importToken,
            paths = ReferenceImportFileOperationPaths.forToken(importToken),
            stage = stage,
            byteCount = byteCount,
            sha256 = sha256,
            lastFailureCode = failureCode,
            reconciliationRequired = reconciliationRequired,
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
        )
    }

    private fun edge(source: String, target: String) =
        ReferenceImportFileOperationStage.valueOf(source) to
            ReferenceImportFileOperationStage.valueOf(target)

    private companion object {
        val VALID_SHA: String = "ab".repeat(32)
    }
}
