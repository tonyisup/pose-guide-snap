package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.ClaimReferenceAssetFilesResult
import com.tonyisup.poseguidesnap.data.DeleteExactForCleanupResult
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetEvidence
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetVerificationResult
import com.tonyisup.poseguidesnap.data.ReferenceAssetByteSource
import com.tonyisup.poseguidesnap.data.ReferenceAssetIdentity
import com.tonyisup.poseguidesnap.data.ReferenceImportAssetPath
import com.tonyisup.poseguidesnap.data.ReferenceImportAssetReadyResult
import com.tonyisup.poseguidesnap.data.ReferenceImportCommitResult
import com.tonyisup.poseguidesnap.data.ReferenceImportEvidence
import com.tonyisup.poseguidesnap.data.ReferenceImportFailureSettlement
import com.tonyisup.poseguidesnap.data.ReferenceImportFileAdvanceRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportFileFailureCode
import com.tonyisup.poseguidesnap.data.ReferenceImportFileJournalRejectionReason
import com.tonyisup.poseguidesnap.data.ReferenceImportFileJournalResult
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationPaths
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationStage
import com.tonyisup.poseguidesnap.data.ReferenceImportFileReconciliationRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportReservation
import com.tonyisup.poseguidesnap.data.ReferenceImportReserveResult
import com.tonyisup.poseguidesnap.data.ReferenceImportSettlementResult
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import com.tonyisup.poseguidesnap.data.RenameExactToQuarantineResult
import com.tonyisup.poseguidesnap.data.RenameSyncedTempResult
import com.tonyisup.poseguidesnap.data.WriteAndSyncTempResult
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JournaledReferencePoseImporterTest {
    @Test
    fun timelineRequiresEveryCallerOwnedTimestampToBeStrictlyIncreasing() {
        val values = (1L..15L).toList()
        timeline(values)

        values.indices.forEach { index ->
            val invalid = values.toMutableList()
            invalid[index] = if (index == 0) -1L else invalid[index - 1]
            assertThrows(IllegalArgumentException::class.java) { timeline(invalid) }
        }
    }

    @Test
    fun successUsesExactLedgerAndFilesystemOrderAndAnalyzesOnlyRedactedDurableEvidence() {
        val fixture = Fixture()

        val result = fixture.importer.importReference(fixture.request)

        assertTrue("result=$result events=${fixture.events}", result is ReferencePoseImportResult.Succeeded)
        assertEquals(
            listOf(
                "reserve@1",
                "snapshot",
                "claim",
                "journal:WRITING_TEMP@2",
                "write",
                "journal:TEMP_SYNCED@3",
                "rename",
                "journal:FINAL_RENAME_PENDING_SYNC@4",
                "sync-final",
                "journal:FINAL_DURABLE@5",
                "asset-ready@6",
                "analyze",
                "commit@7",
            ),
            fixture.events,
        )
        assertEquals(1, fixture.source.openCount)
        assertEquals(1, fixture.analyzer.callCount)
        val asset = requireNotNull(fixture.analyzer.lastAsset)
        assertEquals(ReferenceImportAssetPath.forToken(TOKEN), asset.safeRelativePath)
        assertEquals(EVIDENCE.byteCount, asset.byteCount)
        assertEquals(EVIDENCE.sha256, asset.sha256)
        assertEquals("DurableReferenceAnalyzerAsset(redacted)", asset.toString())
        assertTrue(asset.javaClass.declaredFields.none { it.type.name.contains("Ownership") })
        assertFalse(result.toString().contains(TOKEN.value))
    }

    @Test
    fun idempotentJournalRepliesAreAcceptedWithoutChangingTheRequiredOrder() {
        val fixture = Fixture(idempotentJournal = true)

        val result = fixture.importer.importReference(fixture.request)
        assertTrue("result=$result events=${fixture.events}", result is ReferencePoseImportResult.Succeeded)
        assertEquals(ReferenceImportFileOperationStage.FINAL_DURABLE, fixture.journal.snapshot.stage)
        assertEquals(1, fixture.source.openCount)
    }

    @Test
    fun everyPublicationSideEffectOrFollowingJournalFailureMarksCurrentStageForReconciliation() {
        val seams = listOf(
            "claim",
            "journal:WRITING_TEMP",
            "write-synced",
            "journal:TEMP_SYNCED",
            "rename",
            "journal:FINAL_RENAME_PENDING_SYNC",
            "sync-final",
            "journal:FINAL_DURABLE",
        )

        seams.forEach { seam ->
            val fixture = Fixture(failAt = seam)
            val result = fixture.importer.importReference(fixture.request)

            assertSame("seam=$seam", ReferencePoseImportResult.ReconciliationRequired, result)
            assertTrue("seam=$seam events=${fixture.events}", fixture.events.any { it == "reconcile@14" })
            assertTrue(fixture.authority.settlements.isEmpty())
            assertFalse(fixture.events.any { it.startsWith("settle:") })
        }
    }

    @Test
    fun unsyncedWriteFailureUsesDurableCleanupAndOnlyThenSettlesCleaned() {
        val fixture = Fixture(failAt = "write-unsynced")

        val result = fixture.importer.importReference(fixture.request)

        assertRejected(result, ReferencePoseImportRejectionReason.PUBLICATION_FAILED, ReferenceImportFailureSettlement.CLEANED)
        assertEquals(
            listOf(
                "reserve@1",
                "snapshot",
                "claim",
                "journal:WRITING_TEMP@2",
                "write",
                "journal:CLEANUP_REQUIRED@8",
                "delete:WRITING_TEMP",
                "journal:CLEANUP_PENDING_SYNC@9",
                "sync-cleaned",
                "journal:CLEANED_DURABLE@10",
                "settle:CLEANED@15",
            ),
            fixture.events,
        )
        assertFalse(fixture.events.any { "QUARANTINE" in it })
        assertEquals(1, fixture.source.openCount)
    }

    @Test
    fun analyzerValidationAndCommitFailuresUseSyncedQuarantineAndOnlyThenSettle() {
        listOf("analyze", "validation", "commit").forEach { seam ->
            val fixture = Fixture(failAt = seam)

            val result = fixture.importer.importReference(fixture.request)

            assertTrue("seam=$seam", result is ReferencePoseImportResult.Rejected)
            result as ReferencePoseImportResult.Rejected
            assertEquals(ReferenceImportFailureSettlement.QUARANTINED, result.settlement)
            assertEquals(
                listOf(
                    "journal:QUARANTINE_REQUIRED@11",
                    "quarantine:FINAL_DURABLE",
                    "journal:QUARANTINE_PENDING_SYNC@12",
                    "sync-quarantined",
                    "journal:QUARANTINE_DURABLE@13",
                    "settle:QUARANTINED@15",
                ),
                fixture.events.takeLast(6),
            )
            assertFalse(fixture.events.any { it.startsWith("delete:") })
            assertEquals(1, fixture.source.openCount)
        }
    }

    @Test
    fun failureAtEveryCleanupSeamNeverClaimsAFalseLogicalTerminalState() {
        listOf(
            "journal:CLEANUP_REQUIRED",
            "delete",
            "journal:CLEANUP_PENDING_SYNC",
            "sync-cleaned",
            "journal:CLEANED_DURABLE",
            "settle:CLEANED",
        ).forEach { seam ->
            val fixture = Fixture(failAt = "write-unsynced", failRecoveryAt = seam)

            assertSame("seam=$seam", ReferencePoseImportResult.ReconciliationRequired, fixture.importer.importReference(fixture.request))
            assertTrue("seam=$seam events=${fixture.events}", fixture.events.any { it == "reconcile@14" })
            assertTrue(fixture.authority.settlements.none { it == ReferenceImportFailureSettlement.QUARANTINED })
        }
    }

    @Test
    fun failureAtEveryQuarantineSeamNeverClaimsAFalseLogicalTerminalState() {
        listOf(
            "journal:QUARANTINE_REQUIRED",
            "quarantine",
            "journal:QUARANTINE_PENDING_SYNC",
            "sync-quarantined",
            "journal:QUARANTINE_DURABLE",
            "settle:QUARANTINED",
        ).forEach { seam ->
            val fixture = Fixture(failAt = "analyze", failRecoveryAt = seam)

            assertSame("seam=$seam", ReferencePoseImportResult.ReconciliationRequired, fixture.importer.importReference(fixture.request))
            assertTrue("seam=$seam events=${fixture.events}", fixture.events.any { it == "reconcile@14" })
            assertTrue(fixture.authority.settlements.none { it == ReferenceImportFailureSettlement.CLEANED })
        }
    }

    @Test
    fun existingWorkNeverClaimsFilesOrReopensProviderWhileExactCommittedReplayIsSuccess() {
        Fixture(reserveResult = ReferenceImportReserveResult.ExistingWorkRequiresReconciliation).apply {
            assertSame(ReferencePoseImportResult.ReconciliationRequired, importer.importReference(request))
            assertEquals(listOf("reserve@1"), events)
            assertEquals(0, source.openCount)
        }
        Fixture(reserveResult = ReferenceImportReserveResult.AlreadyCommitted(7)).apply {
            val result = importer.importReference(request)
            assertTrue(result is ReferencePoseImportResult.Succeeded)
            result as ReferencePoseImportResult.Succeeded
            assertEquals(7, result.poseIndex)
            assertEquals(listOf("reserve@1"), events)
            assertEquals(0, source.openCount)
        }
    }

    private class Fixture(
        failAt: String? = null,
        failRecoveryAt: String? = null,
        idempotentJournal: Boolean = false,
        reserveResult: ReferenceImportReserveResult = ReferenceImportReserveResult.Reserved,
    ) {
        val events = mutableListOf<String>()
        val source = CountingSource()
        val authority = FakeAuthority(events, reserveResult, failAt, failRecoveryAt)
        val journal = FakeJournal(events, failAt, failRecoveryAt, idempotentJournal)
        val assets = FakeAssets(events, failAt, failRecoveryAt)
        val analyzer = FakeAnalyzer(events, failAt)
        val importer = JournaledReferencePoseImporter(authority, journal, assets, analyzer)
        val request = request(source)
    }

    private class FakeAuthority(
        private val events: MutableList<String>,
        private val reserveResult: ReferenceImportReserveResult,
        private val failAt: String?,
        private val failRecoveryAt: String?,
    ) : ReferenceImportAuthorityPort {
        val settlements = mutableListOf<ReferenceImportFailureSettlement>()

        override fun reserve(
            reservation: ReferenceImportReservation,
            reservedAtEpochMillis: Long,
        ): ReferenceImportReserveResult {
            events += "reserve@$reservedAtEpochMillis"
            return reserveResult
        }


        override fun markAssetReady(
            importToken: ReferenceImportToken,
            relativeAssetPath: String,
            assetReadyAtEpochMillis: Long,
        ): ReferenceImportAssetReadyResult {
            events += "asset-ready@$assetReadyAtEpochMillis"
            return if (failAt == "asset-ready") {
                ReferenceImportAssetReadyResult.Rejected(
                    com.tonyisup.poseguidesnap.data.ReferenceImportAssetReadyRejectionReason.WRONG_STATE,
                )
            } else {
                ReferenceImportAssetReadyResult.MarkedAssetReady
            }
        }

        override fun commit(
            evidence: ReferenceImportEvidence,
            committedAtEpochMillis: Long,
        ): ReferenceImportCommitResult {
            events += "commit@$committedAtEpochMillis"
            return if (failAt == "commit") {
                ReferenceImportCommitResult.Rejected(
                    com.tonyisup.poseguidesnap.data.ReferenceImportCommitRejectionReason.ACTIVE_SESSION,
                )
            } else {
                ReferenceImportCommitResult.Committed(7)
            }
        }

        override fun settleFailure(
            importToken: ReferenceImportToken,
            settlement: ReferenceImportFailureSettlement,
            settledAtEpochMillis: Long,
        ): ReferenceImportSettlementResult {
            events += "settle:${settlement.name}@$settledAtEpochMillis"
            settlements += settlement
            if (failRecoveryAt == "settle:${settlement.name}") {
                return ReferenceImportSettlementResult.Rejected(
                    com.tonyisup.poseguidesnap.data.ReferenceImportSettlementRejectionReason.TRANSACTION_CAS_FAILED,
                )
            }
            return ReferenceImportSettlementResult.Settled
        }
    }

    private class FakeJournal(
        private val events: MutableList<String>,
        private val failAt: String?,
        private val failRecoveryAt: String?,
        private val idempotent: Boolean,
    ) : ReferenceImportFileJournalPort {
        var snapshot = initialSnapshot()

        override fun snapshot(importToken: ReferenceImportToken): ReferenceImportFileOperationSnapshot {
            events += "snapshot"
            return snapshot
        }

        override fun advance(request: ReferenceImportFileAdvanceRequest): ReferenceImportFileJournalResult {
            events += "journal:${request.targetStage.name}@${request.transitionedAtEpochMillis}"
            val key = "journal:${request.targetStage.name}"
            if (failAt == key || failRecoveryAt == key) {
                return ReferenceImportFileJournalResult.Rejected(
                    ReferenceImportFileJournalRejectionReason.STALE_SNAPSHOT,
                )
            }
            snapshot = snapshot.copy(
                stage = request.targetStage,
                byteCount = request.byteCount,
                sha256 = request.sha256,
                lastFailureCode = null,
                reconciliationRequired = false,
                updatedAtEpochMillis = request.transitionedAtEpochMillis,
            )
            return if (idempotent) {
                ReferenceImportFileJournalResult.Idempotent(snapshot)
            } else {
                ReferenceImportFileJournalResult.Applied(snapshot)
            }
        }

        override fun markReconciliationRequired(
            request: ReferenceImportFileReconciliationRequest,
        ): ReferenceImportFileJournalResult {
            events += "reconcile@${request.markedAtEpochMillis}"
            snapshot = snapshot.copy(
                lastFailureCode = request.failureCode,
                reconciliationRequired = true,
                updatedAtEpochMillis = request.markedAtEpochMillis,
            )
            return ReferenceImportFileJournalResult.Applied(snapshot)
        }
    }

    private class FakeAssets(
        private val events: MutableList<String>,
        private val failAt: String?,
        private val failRecoveryAt: String?,
    ) : JournaledReferenceImportAssetPort {
        override fun claimReservationAndTemp(identity: ReferenceAssetIdentity): ClaimReferenceAssetFilesResult {
            events += "claim"
            return if (failAt == "claim") {
                ClaimReferenceAssetFilesResult.Ambiguous(ReferenceImportFileFailureCode.RESERVATION_FAILED, false)
            } else {
                ClaimReferenceAssetFilesResult.Claimed
            }
        }

        override fun writeAndSyncClaimedTemp(
            identity: ReferenceAssetIdentity,
            source: ReferenceAssetByteSource,
        ): WriteAndSyncTempResult {
            events += "write"
            source.openStream().use { it.readBytes() }
            return when (failAt) {
                "write-unsynced" -> WriteAndSyncTempResult.Failure(
                    ReferenceImportFileFailureCode.WRITE_FAILED,
                    true,
                )
                "write-synced" -> WriteAndSyncTempResult.Failure(
                    ReferenceImportFileFailureCode.FILE_SYNC_FAILED,
                    false,
                )
                else -> WriteAndSyncTempResult.TempSynced(EVIDENCE)
            }
        }

        override fun renameSyncedTemp(
            identity: ReferenceAssetIdentity,
            evidence: JournaledReferenceAssetEvidence,
        ): RenameSyncedTempResult {
            events += "rename"
            return if (failAt == "rename") {
                RenameSyncedTempResult.Ambiguous(ReferenceImportFileFailureCode.RENAME_FAILED)
            } else {
                RenameSyncedTempResult.Renamed
            }
        }

        override fun syncAndVerifyFinal(
            identity: ReferenceAssetIdentity,
            evidence: JournaledReferenceAssetEvidence,
        ): JournaledReferenceAssetVerificationResult {
            events += "sync-final"
            return if (failAt == "sync-final") failure() else JournaledReferenceAssetVerificationResult.Verified
        }

        override fun deleteExactForCleanup(
            identity: ReferenceAssetIdentity,
            sourceStage: ReferenceImportFileOperationStage,
            evidence: JournaledReferenceAssetEvidence?,
        ): DeleteExactForCleanupResult {
            events += "delete:${sourceStage.name}"
            return if (failRecoveryAt == "delete") {
                DeleteExactForCleanupResult.Ambiguous(ReferenceImportFileFailureCode.DELETE_FAILED)
            } else {
                DeleteExactForCleanupResult.Deleted
            }
        }

        override fun syncAndVerifyCleaned(identity: ReferenceAssetIdentity): JournaledReferenceAssetVerificationResult {
            events += "sync-cleaned"
            return if (failRecoveryAt == "sync-cleaned") failure() else JournaledReferenceAssetVerificationResult.Verified
        }

        override fun renameExactToQuarantine(
            identity: ReferenceAssetIdentity,
            sourceStage: ReferenceImportFileOperationStage,
            evidence: JournaledReferenceAssetEvidence,
        ): RenameExactToQuarantineResult {
            events += "quarantine:${sourceStage.name}"
            return if (failRecoveryAt == "quarantine") {
                RenameExactToQuarantineResult.Ambiguous(ReferenceImportFileFailureCode.RENAME_FAILED)
            } else {
                RenameExactToQuarantineResult.Moved
            }
        }

        override fun syncAndVerifyQuarantined(
            identity: ReferenceAssetIdentity,
            evidence: JournaledReferenceAssetEvidence,
        ): JournaledReferenceAssetVerificationResult {
            events += "sync-quarantined"
            return if (failRecoveryAt == "sync-quarantined") failure() else JournaledReferenceAssetVerificationResult.Verified
        }

        private fun failure() = JournaledReferenceAssetVerificationResult.Failure(
            ReferenceImportFileFailureCode.STATE_MISMATCH,
        )
    }

    private class FakeAnalyzer(
        private val events: MutableList<String>,
        private val failAt: String?,
    ) : ReferenceImportAnalyzerPort {
        var callCount = 0
        var lastAsset: DurableReferenceAnalyzerAsset? = null

        override fun analyze(asset: DurableReferenceAnalyzerAsset): ReferenceAnalysisEvidence {
            events += "analyze"
            callCount += 1
            lastAsset = asset
            if (failAt == "analyze") throw IllegalStateException("private analyzer failure")
            return if (failAt == "validation") {
                ReferenceAnalysisEvidence(
                    detectedPersonCount = 0,
                    landmarks = emptyList(),
                    detectorMetadata = "detector",
                    modelMetadata = "model",
                    preprocessingMetadata = "preprocessing",
                    coordinateMetadata = "coordinates",
                )
            } else {
                analysis()
            }
        }
    }

    private class CountingSource : ReferenceAssetByteSource {
        var openCount = 0

        override fun openStream(): InputStream {
            openCount += 1
            check(openCount == 1) { "provider source reopened" }
            return ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }
    }

    private companion object {
        val TOKEN = ReferenceImportToken("journaled-import-token")
        val EVIDENCE = JournaledReferenceAssetEvidence(3L, "a".repeat(64))

        fun timeline(values: List<Long> = (1L..15L).toList()) = ReferenceImportLedgerTimeline(
            reservedAtEpochMillis = values[0],
            writingTempAtEpochMillis = values[1],
            tempSyncedAtEpochMillis = values[2],
            finalRenamePendingSyncAtEpochMillis = values[3],
            finalDurableAtEpochMillis = values[4],
            assetReadyAtEpochMillis = values[5],
            committedAtEpochMillis = values[6],
            cleanupRequiredAtEpochMillis = values[7],
            cleanupPendingSyncAtEpochMillis = values[8],
            cleanedDurableAtEpochMillis = values[9],
            quarantineRequiredAtEpochMillis = values[10],
            quarantinePendingSyncAtEpochMillis = values[11],
            quarantineDurableAtEpochMillis = values[12],
            reconciliationMarkedAtEpochMillis = values[13],
            failureSettledAtEpochMillis = values[14],
        )

        fun request(source: ReferenceAssetByteSource) = ReferencePoseImportRequest(
            importToken = TOKEN,
            shootId = "shoot-secret",
            poseId = "pose-secret",
            label = "label-secret",
            mirrorAllowed = true,
            timeline = timeline(),
            source = source,
        )

        fun initialSnapshot() = ReferenceImportFileOperationSnapshot(
            importToken = TOKEN,
            paths = ReferenceImportFileOperationPaths.forToken(TOKEN),
            stage = ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
            byteCount = null,
            sha256 = null,
            lastFailureCode = null,
            reconciliationRequired = false,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )

        fun analysis() = ReferenceAnalysisEvidence(
            detectedPersonCount = 1,
            landmarks = listOf(
                PoseLandmark.NOSE,
                PoseLandmark.LEFT_EYE,
                PoseLandmark.RIGHT_EYE,
                PoseLandmark.LEFT_EAR,
                PoseLandmark.RIGHT_EAR,
                PoseLandmark.LEFT_SHOULDER,
                PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_ELBOW,
                PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.LEFT_WRIST,
                PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_HIP,
                PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_KNEE,
                PoseLandmark.RIGHT_KNEE,
                PoseLandmark.LEFT_ANKLE,
                PoseLandmark.RIGHT_ANKLE,
            ).mapIndexed { index, type ->
                Landmark(type, 0.03 * index, 0.2, 0.0, 0.9, 0.9)
            },
            detectorMetadata = "detector",
            modelMetadata = "model",
            preprocessingMetadata = "preprocessing",
            coordinateMetadata = "coordinates",
        )

        fun assertRejected(
            result: ReferencePoseImportResult,
            reason: ReferencePoseImportRejectionReason,
            settlement: ReferenceImportFailureSettlement,
        ) {
            assertTrue(result is ReferencePoseImportResult.Rejected)
            result as ReferencePoseImportResult.Rejected
            assertEquals(reason, result.reason)
            assertEquals(settlement, result.settlement)
        }
    }
}
