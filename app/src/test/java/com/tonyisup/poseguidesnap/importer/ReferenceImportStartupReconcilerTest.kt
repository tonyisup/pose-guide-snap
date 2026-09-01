package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.DeleteExactForCleanupResult
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetEvidence
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetVerificationResult
import com.tonyisup.poseguidesnap.data.PendingReferenceImport
import com.tonyisup.poseguidesnap.data.ReferenceAssetIdentity
import com.tonyisup.poseguidesnap.data.ReferenceImportAssetPath
import com.tonyisup.poseguidesnap.data.ReferenceImportFailureSettlement
import com.tonyisup.poseguidesnap.data.ReferenceImportFileAdvanceRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportFileFailureCode
import com.tonyisup.poseguidesnap.data.ReferenceImportFileJournalRejectionReason
import com.tonyisup.poseguidesnap.data.ReferenceImportFileJournalResult
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationPaths
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationStage
import com.tonyisup.poseguidesnap.data.ReferenceImportFileReconciliationRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportFileReconciliationResolutionRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportLifecycle
import com.tonyisup.poseguidesnap.data.ReferenceImportSettlementRejectionReason
import com.tonyisup.poseguidesnap.data.ReferenceImportSettlementResult
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import com.tonyisup.poseguidesnap.data.RenameExactToQuarantineResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceImportStartupReconcilerTest {
    @Test
    fun everyPersistedStageResumesOnlyItsRemainingLedgerDrivenRecoverySteps() {
        val expected = mapOf(
            ReferenceImportFileOperationStage.EXPECTING_RESERVATION to cleanupEvents("EXPECTING_RESERVATION", includeRequired = true, includeDelete = true, includeVerify = true),
            ReferenceImportFileOperationStage.WRITING_TEMP to cleanupEvents("WRITING_TEMP", includeRequired = true, includeDelete = true, includeVerify = true),
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED to cleanupEvents("CLEANUP_REQUIRED", includeRequired = false, includeDelete = true, includeVerify = true),
            ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC to listOf(
                "assets:delete:CLEANUP_PENDING_SYNC:no-evidence",
                "assets:verify-cleaned",
                "journal:CLEANED_DURABLE@103",
                "settle:CLEANED@108",
            ),
            ReferenceImportFileOperationStage.CLEANED_DURABLE to listOf("settle:CLEANED@108"),
            ReferenceImportFileOperationStage.TEMP_SYNCED to quarantineEvents("TEMP_SYNCED", includeRequired = true, includeRename = true, includeVerify = true),
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC to quarantineEvents("FINAL_RENAME_PENDING_SYNC", includeRequired = true, includeRename = true, includeVerify = true),
            ReferenceImportFileOperationStage.FINAL_DURABLE to quarantineEvents("FINAL_DURABLE", includeRequired = true, includeRename = true, includeVerify = true),
            ReferenceImportFileOperationStage.QUARANTINE_REQUIRED to quarantineEvents("QUARANTINE_REQUIRED", includeRequired = false, includeRename = true, includeVerify = true),
            ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC to listOf(
                "assets:quarantine:QUARANTINE_PENDING_SYNC:evidence",
                "assets:verify-quarantined",
                "journal:QUARANTINE_DURABLE@106",
                "settle:QUARANTINED@108",
            ),
            ReferenceImportFileOperationStage.QUARANTINE_DURABLE to listOf("settle:QUARANTINED@108"),
        )

        ReferenceImportFileOperationStage.entries.forEach { stage ->
            val fixture = Fixture(stage)

            val report = fixture.reconcile()

            assertEquals("stage=$stage", expected.getValue(stage), fixture.events)
            assertEquals("stage=$stage", 1, report.examinedCount)
            assertEquals("stage=$stage", 0, report.outstandingCount)
            assertFalse("stage=$stage", report.ledgerReadFailed)
        }
    }

    @Test
    fun startupReconciliationConsumesTwentyThenOneWithoutMaterializingAnUnboundedLedger() {
        val operations = (0..20).map { index ->
            val token = ReferenceImportToken("paged-recovery-${index.toString().padStart(2, '0')}")
            ReferenceImportFileOperationSnapshot(
                importToken = token,
                paths = ReferenceImportFileOperationPaths.forToken(token),
                stage = ReferenceImportFileOperationStage.FINAL_DURABLE,
                byteCount = 7L,
                sha256 = VALID_SHA,
                lastFailureCode = ReferenceImportFileFailureCode.STATE_MISMATCH,
                reconciliationRequired = true,
                createdAtEpochMillis = index.toLong() + 1L,
                updatedAtEpochMillis = index.toLong() + 1L,
            )
        }
        val pageSizes = mutableListOf<Int>()
        val cursors = mutableListOf<ReferenceImportRecoveryCursor?>()
        val journal = object : ReferenceImportRecoveryJournalPort {
            override fun findRetryableOperationsPage(
                after: ReferenceImportRecoveryCursor?,
                limit: Int,
            ): List<ReferenceImportFileOperationSnapshot> {
                cursors += after
                pageSizes += limit
                return operations.filter { operation ->
                    after == null ||
                        operation.createdAtEpochMillis > after.createdAtEpochMillis ||
                        (operation.createdAtEpochMillis == after.createdAtEpochMillis &&
                            operation.importToken.value > after.importToken.value)
                }.take(limit)
            }

            override fun snapshot(importToken: ReferenceImportToken) =
                error("committed recovery must not request a mutable snapshot")

            override fun advance(request: ReferenceImportFileAdvanceRequest) =
                error("committed recovery must not advance")

            override fun markReconciliationRequired(request: ReferenceImportFileReconciliationRequest) =
                error("committed recovery must not mark again")

            override fun clearReconciliationRequired(
                request: ReferenceImportFileReconciliationResolutionRequest,
            ) = error("committed recovery must not clear")
        }
        val authority = object : ReferenceImportRecoveryAuthorityPort {
            override fun findExactIntent(importToken: ReferenceImportToken): PendingReferenceImport {
                val operation = operations.single { it.importToken == importToken }
                return PendingReferenceImport(
                    importToken = importToken,
                    shootId = "paged-recovery-shoot",
                    poseId = "pose-${importToken.value}",
                    relativeAssetPath = operation.paths.relativeAssetPath,
                    lifecycle = ReferenceImportLifecycle.COMMITTED,
                    createdAtEpochMillis = operation.createdAtEpochMillis,
                    updatedAtEpochMillis = operation.updatedAtEpochMillis,
                )
            }

            override fun settleFailure(
                importToken: ReferenceImportToken,
                settlement: ReferenceImportFailureSettlement,
                settledAtEpochMillis: Long,
            ) = error("committed recovery must not settle rejection")
        }
        val reconciler = ReferenceImportStartupReconciler(
            authority = authority,
            journal = journal,
            assets = FakeAssets(mutableListOf()) { null },
        )

        val report = reconciler.reconcile { TIMELINE }

        assertEquals(21, report.examinedCount)
        assertEquals(21, report.outstandingCount)
        assertFalse(report.ledgerReadFailed)
        assertEquals(listOf(20, 20), pageSizes)
        assertEquals(null, cursors.first())
        assertEquals(20L, requireNotNull(cursors[1]).createdAtEpochMillis)
    }

    @Test
    fun dirtyWritingTempIsDeletedWhileFsyncedTempAndRenameAmbiguityAreQuarantined() {
        val writing = Fixture(ReferenceImportFileOperationStage.WRITING_TEMP)
        writing.reconcile()
        assertTrue(writing.events.contains("assets:delete:WRITING_TEMP:no-evidence"))
        assertTrue(writing.events.none { it.startsWith("assets:quarantine:") })

        val synced = Fixture(ReferenceImportFileOperationStage.TEMP_SYNCED)
        synced.reconcile()
        assertTrue(synced.events.contains("assets:quarantine:TEMP_SYNCED:evidence"))
        assertTrue(synced.events.none { it.startsWith("assets:delete:") })

        val renameAmbiguous = Fixture(ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC)
        renameAmbiguous.reconcile()
        assertTrue(
            renameAmbiguous.events.contains(
                "assets:quarantine:FINAL_RENAME_PENDING_SYNC:evidence",
            ),
        )
    }

    @Test
    fun aPriorReconciliationFlagDoesNotBlockAValidAdvance() {
        val fixture = Fixture(
            stage = ReferenceImportFileOperationStage.WRITING_TEMP,
            reconciliationRequired = true,
        )

        val report = fixture.reconcile()

        assertEquals(0, report.outstandingCount)
        assertEquals(ReferenceImportFileOperationStage.CLEANED_DURABLE, fixture.journal.current?.stage)
        assertFalse(requireNotNull(fixture.journal.current).reconciliationRequired)
        assertEquals(null, fixture.journal.current?.lastFailureCode)
    }

    @Test
    fun failuresAfterEveryCleanupBoundaryMarkTheCurrentPersistedStageAndLeaveLogicalIntentNonterminal() {
        val cases = listOf(
            FailureCase("advance:CLEANUP_REQUIRED", ReferenceImportFileOperationStage.WRITING_TEMP),
            FailureCase("assets:delete", ReferenceImportFileOperationStage.CLEANUP_REQUIRED),
            FailureCase("advance:CLEANUP_PENDING_SYNC", ReferenceImportFileOperationStage.CLEANUP_REQUIRED),
            FailureCase("assets:verify-cleaned", ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC),
            FailureCase("advance:CLEANED_DURABLE", ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC),
            FailureCase("settle", ReferenceImportFileOperationStage.CLEANED_DURABLE),
        )

        cases.forEach { case ->
            val fixture = Fixture(ReferenceImportFileOperationStage.WRITING_TEMP, failAt = case.seam)

            val report = fixture.reconcile()

            assertEquals("seam=${case.seam}", 1, report.outstandingCount)
            assertEquals("seam=${case.seam}", ReferenceImportLifecycle.PREPARING, fixture.authority.intent.lifecycle)
            assertEquals("seam=${case.seam}", case.expectedMarkedStage, fixture.journal.markedStage)
            assertEquals("seam=${case.seam}", 107L, fixture.journal.markedAt)
        }
    }

    @Test
    fun failuresAfterEveryQuarantineBoundaryMarkTheCurrentPersistedStageAndLeaveLogicalIntentNonterminal() {
        val cases = listOf(
            FailureCase("advance:QUARANTINE_REQUIRED", ReferenceImportFileOperationStage.TEMP_SYNCED),
            FailureCase("assets:quarantine", ReferenceImportFileOperationStage.QUARANTINE_REQUIRED),
            FailureCase("advance:QUARANTINE_PENDING_SYNC", ReferenceImportFileOperationStage.QUARANTINE_REQUIRED),
            FailureCase("assets:verify-quarantined", ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC),
            FailureCase("advance:QUARANTINE_DURABLE", ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC),
            FailureCase("settle", ReferenceImportFileOperationStage.QUARANTINE_DURABLE),
        )

        cases.forEach { case ->
            val fixture = Fixture(ReferenceImportFileOperationStage.TEMP_SYNCED, failAt = case.seam)

            val report = fixture.reconcile()

            assertEquals("seam=${case.seam}", 1, report.outstandingCount)
            assertEquals("seam=${case.seam}", ReferenceImportLifecycle.PREPARING, fixture.authority.intent.lifecycle)
            assertEquals("seam=${case.seam}", case.expectedMarkedStage, fixture.journal.markedStage)
            assertEquals("seam=${case.seam}", 107L, fixture.journal.markedAt)
        }
    }

    @Test
    fun pendingSyncStagesRetryTheirExactEffectBeforeDirectoryVerification() {
        val cleanup = Fixture(
            ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC,
            failAt = "assets:delete",
        )
        assertEquals(1, cleanup.reconcile().outstandingCount)
        assertEquals(
            ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC,
            cleanup.journal.markedStage,
        )
        assertFalse(cleanup.events.contains("assets:verify-cleaned"))

        val quarantine = Fixture(
            ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
            failAt = "assets:quarantine",
        )
        assertEquals(1, quarantine.reconcile().outstandingCount)
        assertEquals(
            ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
            quarantine.journal.markedStage,
        )
        assertFalse(quarantine.events.contains("assets:verify-quarantined"))
    }

    @Test
    fun durableFileOutcomeRetriesOnlyLogicalSettlementAndNextStartupIsNoOp() {
        val fixture = Fixture(
            stage = ReferenceImportFileOperationStage.QUARANTINE_DURABLE,
            failAt = "settle",
        )

        val first = fixture.reconcile()
        assertEquals(1, first.outstandingCount)
        assertEquals(listOf("settle:QUARANTINED@108", "journal:mark:QUARANTINE_DURABLE@107"), fixture.events)

        fixture.failAt = null
        fixture.events.clear()
        val second = fixture.reconciler.reconcile { RETRY_TIMELINE }
        assertEquals(1, second.quarantinedCount)
        assertEquals(
            listOf("settle:QUARANTINED@208", "journal:clear:QUARANTINE_DURABLE@208"),
            fixture.events,
        )

        fixture.events.clear()
        val third = fixture.reconcile()
        assertEquals(0, third.examinedCount)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun missingIntentTerminalMismatchAndFilesystemAmbiguityFailClosedAtCurrentStage() {
        val missing = Fixture(ReferenceImportFileOperationStage.WRITING_TEMP).apply {
            authority.missing = true
        }
        assertEquals(1, missing.reconcile().outstandingCount)
        assertEquals(ReferenceImportFileOperationStage.WRITING_TEMP, missing.journal.markedStage)
        assertTrue(missing.assets.calls.isEmpty())

        val terminalMismatch = Fixture(ReferenceImportFileOperationStage.CLEANED_DURABLE).apply {
            authority.intent = authority.intent.copy(lifecycle = ReferenceImportLifecycle.REJECTED_QUARANTINED)
        }
        assertEquals(1, terminalMismatch.reconcile().outstandingCount)
        assertEquals(ReferenceImportFileOperationStage.CLEANED_DURABLE, terminalMismatch.journal.markedStage)
        assertTrue(terminalMismatch.authority.settlements.isEmpty())

        val ambiguous = Fixture(
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            failAt = "assets:quarantine",
        )
        assertEquals(1, ambiguous.reconcile().outstandingCount)
        assertEquals(ReferenceImportFileOperationStage.QUARANTINE_REQUIRED, ambiguous.journal.markedStage)
    }

    @Test
    fun exactTokenLookupNeverAnalyzesCommitsReopensSourcesOrScansDirectories() {
        val fixture = Fixture(ReferenceImportFileOperationStage.FINAL_DURABLE)

        fixture.reconcile()

        assertEquals(listOf(fixture.token), fixture.authority.lookups)
        assertEquals(listOf(fixture.token), fixture.authority.settlementTokens)
        assertTrue(fixture.assets.tokens.isNotEmpty())
        assertTrue(fixture.assets.tokens.all { token -> token == fixture.token })
    }

    @Test
    fun coherentCommittedFinalAuthorityDoesNotAppearInRetryEnumeration() {
        val fixture = Fixture(ReferenceImportFileOperationStage.FINAL_DURABLE).apply {
            authority.intent = authority.intent.copy(lifecycle = ReferenceImportLifecycle.COMMITTED)
        }

        val report = fixture.reconcile()

        assertEquals(0, report.examinedCount)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun callerOwnedTimelineIsStrictIncreasingNewerThanLedgerAndRedacted() {
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceImportRecoveryTimeline(1L, 2L, 3L, 4L, 5L, 6L, 7L, 7L)
        }
        val fixture = Fixture(ReferenceImportFileOperationStage.WRITING_TEMP)
        val stale = ReferenceImportRecoveryTimeline(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)

        val report = fixture.reconciler.reconcile { stale }

        assertEquals(1, report.outstandingCount)
        assertTrue(fixture.events.isEmpty())
        val rendered = listOf<Any>(TIMELINE, report).joinToString()
        assertFalse(rendered.contains(fixture.token.value))
        assertFalse(rendered.contains(VALID_SHA))
        assertFalse(rendered.contains("reference-assets/"))
        assertEquals("ReferenceImportRecoveryTimeline(redacted)", TIMELINE.toString())
        assertEquals("ReferenceImportStartupReconciliationReport(redacted)", report.toString())
    }

    @Test
    fun ledgerReadFailureProducesNoFilesystemOrLogicalCalls() {
        val fixture = Fixture(ReferenceImportFileOperationStage.WRITING_TEMP).apply {
            journal.readFailure = true
        }

        val report = fixture.reconcile()

        assertTrue(report.ledgerReadFailed)
        assertEquals(0, report.examinedCount)
        assertEquals(0, report.cleanedCount)
        assertEquals(0, report.quarantinedCount)
        assertEquals(0, report.outstandingCount)
        assertEquals(0, report.settlementFailureCount)
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.authority.lookups.isEmpty())
        assertTrue(fixture.authority.settlements.isEmpty())
        assertTrue(fixture.assets.calls.isEmpty())
        assertEquals(null, fixture.journal.markedStage)
    }

    @Test
    fun concreteRoomJournalChecksIntentLedgerCoherenceInsidePageReadTransaction() {
        val source = projectRoot().resolve(ROOM_JOURNAL_SOURCE_PATH).readText()
        val functionStart = source.indexOf("fun findRetryableOperationsPage(")
        val functionEnd = source.indexOf("\n    fun advance(", startIndex = functionStart)
        assertTrue("Room journal page method is missing", functionStart >= 0)
        assertTrue("Room journal page method boundary is missing", functionEnd > functionStart)
        val pageMethod = source.substring(functionStart, functionEnd)
        val transaction = pageMethod.indexOf("inTransaction {")
        val coherenceCheck = pageMethod.indexOf("intentDao.hasIntentWithoutFileOperation()")
        val pageRead = pageMethod.indexOf("dao.findRetryableOperations(")

        assertTrue("Room journal must retain the intent DAO", "database.referenceImportDao()" in source)
        assertTrue("Room journal page read must run in one Room transaction", transaction >= 0)
        assertTrue("Missing-ledger coherence check is absent", coherenceCheck >= 0)
        assertTrue("Retryable page query is absent", pageRead >= 0)
        assertTrue(
            "Coherence must be checked after entering the transaction and before returning a page",
            transaction < coherenceCheck && coherenceCheck < pageRead,
        )
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
    }

    private data class FailureCase(
        val seam: String,
        val expectedMarkedStage: ReferenceImportFileOperationStage,
    )

    private class Fixture(
        stage: ReferenceImportFileOperationStage,
        reconciliationRequired: Boolean = false,
        var failAt: String? = null,
    ) {
        val token = ReferenceImportToken("secret-recovery-token")
        val events = mutableListOf<String>()
        val authority = FakeAuthority(token, events) { failAt }
        val journal = FakeJournal(snapshot(token, stage, reconciliationRequired), authority, events) { failAt }
        val assets = FakeAssets(events) { failAt }
        val reconciler = ReferenceImportStartupReconciler(authority, journal, assets)

        fun reconcile(): ReferenceImportStartupReconciliationReport = reconciler.reconcile { TIMELINE }
    }

    private class FakeAuthority(
        token: ReferenceImportToken,
        private val events: MutableList<String>,
        private val failAt: () -> String?,
    ) : ReferenceImportRecoveryAuthorityPort {
        var missing = false
        var intent = pending(token, ReferenceImportLifecycle.PREPARING)
        val lookups = mutableListOf<ReferenceImportToken>()
        val settlementTokens = mutableListOf<ReferenceImportToken>()
        val settlements = mutableListOf<ReferenceImportFailureSettlement>()

        override fun findExactIntent(importToken: ReferenceImportToken): PendingReferenceImport? {
            lookups += importToken
            return if (missing) null else intent
        }

        override fun settleFailure(
            importToken: ReferenceImportToken,
            settlement: ReferenceImportFailureSettlement,
            settledAtEpochMillis: Long,
        ): ReferenceImportSettlementResult {
            events += "settle:${settlement.name}@$settledAtEpochMillis"
            settlementTokens += importToken
            settlements += settlement
            if (failAt() == "settle") {
                return ReferenceImportSettlementResult.Rejected(
                    ReferenceImportSettlementRejectionReason.TRANSACTION_CAS_FAILED,
                )
            }
            intent = intent.copy(
                lifecycle = when (settlement) {
                    ReferenceImportFailureSettlement.CLEANED -> ReferenceImportLifecycle.REJECTED_CLEANED
                    ReferenceImportFailureSettlement.QUARANTINED -> ReferenceImportLifecycle.REJECTED_QUARANTINED
                },
                updatedAtEpochMillis = settledAtEpochMillis,
            )
            return ReferenceImportSettlementResult.Settled
        }
    }

    private class FakeJournal(
        var current: ReferenceImportFileOperationSnapshot?,
        private val authority: FakeAuthority,
        private val events: MutableList<String>,
        private val failAt: () -> String?,
    ) : ReferenceImportRecoveryJournalPort {
        var readFailure = false
        var markedStage: ReferenceImportFileOperationStage? = null
        var markedAt: Long? = null

        override fun findRetryableOperationsPage(
            after: ReferenceImportRecoveryCursor?,
            limit: Int,
        ): List<ReferenceImportFileOperationSnapshot> {
            if (readFailure) throw IllegalStateException("private ledger failure")
            require(limit in 1..20)
            val snapshot = current ?: return emptyList()
            if (
                after != null &&
                (snapshot.createdAtEpochMillis < after.createdAtEpochMillis ||
                    (snapshot.createdAtEpochMillis == after.createdAtEpochMillis &&
                        snapshot.importToken.value <= after.importToken.value))
            ) {
                return emptyList()
            }
            val lifecycle = authority.intent.lifecycle
            val terminalCoherent =
                (snapshot.stage == ReferenceImportFileOperationStage.FINAL_DURABLE && lifecycle == ReferenceImportLifecycle.COMMITTED) ||
                    (snapshot.stage == ReferenceImportFileOperationStage.CLEANED_DURABLE && lifecycle == ReferenceImportLifecycle.REJECTED_CLEANED) ||
                    (snapshot.stage == ReferenceImportFileOperationStage.QUARANTINE_DURABLE && lifecycle == ReferenceImportLifecycle.REJECTED_QUARANTINED)
            return if (
                terminalCoherent &&
                !snapshot.reconciliationRequired &&
                snapshot.lastFailureCode == null
            ) {
                emptyList()
            } else {
                listOf(snapshot)
            }
        }

        override fun snapshot(importToken: ReferenceImportToken): ReferenceImportFileOperationSnapshot? =
            current?.takeIf { snapshot -> snapshot.importToken == importToken }

        override fun advance(request: ReferenceImportFileAdvanceRequest): ReferenceImportFileJournalResult {
            events += "journal:${request.targetStage.name}@${request.transitionedAtEpochMillis}"
            if (failAt() == "advance:${request.targetStage.name}") {
                return ReferenceImportFileJournalResult.Rejected(
                    ReferenceImportFileJournalRejectionReason.STALE_SNAPSHOT,
                )
            }
            val source = requireNotNull(current)
            val target = source.copy(
                stage = request.targetStage,
                byteCount = request.byteCount,
                sha256 = request.sha256,
                lastFailureCode = null,
                reconciliationRequired = false,
                updatedAtEpochMillis = request.transitionedAtEpochMillis,
            )
            current = target
            return ReferenceImportFileJournalResult.Applied(target)
        }

        override fun markReconciliationRequired(
            request: ReferenceImportFileReconciliationRequest,
        ): ReferenceImportFileJournalResult {
            events += "journal:mark:${request.expectedStage.name}@${request.markedAtEpochMillis}"
            markedStage = request.expectedStage
            markedAt = request.markedAtEpochMillis
            val source = current
            if (source == null || source.stage != request.expectedStage || source.updatedAtEpochMillis != request.expectedUpdatedAtEpochMillis) {
                return ReferenceImportFileJournalResult.Rejected(
                    ReferenceImportFileJournalRejectionReason.STALE_SNAPSHOT,
                )
            }
            val target = source.copy(
                lastFailureCode = request.failureCode,
                reconciliationRequired = true,
                updatedAtEpochMillis = request.markedAtEpochMillis,
            )
            current = target
            return ReferenceImportFileJournalResult.Applied(target)
        }

        override fun clearReconciliationRequired(
            request: ReferenceImportFileReconciliationResolutionRequest,
        ): ReferenceImportFileJournalResult {
            events += "journal:clear:${request.expectedStage.name}@${request.resolvedAtEpochMillis}"
            val source = current
            if (
                source == null ||
                source.stage != request.expectedStage ||
                source.updatedAtEpochMillis != request.expectedUpdatedAtEpochMillis ||
                !source.reconciliationRequired ||
                source.lastFailureCode == null
            ) {
                return ReferenceImportFileJournalResult.Rejected(
                    ReferenceImportFileJournalRejectionReason.STALE_SNAPSHOT,
                )
            }
            val target = source.copy(
                lastFailureCode = null,
                reconciliationRequired = false,
                updatedAtEpochMillis = request.resolvedAtEpochMillis,
            )
            current = target
            return ReferenceImportFileJournalResult.Applied(target)
        }
    }

    private class FakeAssets(
        private val events: MutableList<String>,
        private val failAt: () -> String?,
    ) : ReferenceImportRecoveryAssetPort {
        val calls = mutableListOf<String>()
        val tokens = mutableListOf<ReferenceImportToken>()

        override fun deleteExactForCleanup(
            identity: ReferenceAssetIdentity,
            operation: ReferenceImportFileOperationSnapshot,
        ): DeleteExactForCleanupResult {
            val evidence = if (operation.byteCount == null) "no-evidence" else "evidence"
            val event = "assets:delete:${operation.stage.name}:$evidence"
            events += event
            calls += event
            tokens += identity.importToken
            return if (failAt() == "assets:delete") {
                DeleteExactForCleanupResult.Ambiguous(ReferenceImportFileFailureCode.DELETE_FAILED)
            } else {
                DeleteExactForCleanupResult.Deleted
            }
        }

        override fun syncAndVerifyCleaned(
            identity: ReferenceAssetIdentity,
        ): JournaledReferenceAssetVerificationResult {
            val event = "assets:verify-cleaned"
            events += event
            calls += event
            tokens += identity.importToken
            return if (failAt() == event) {
                JournaledReferenceAssetVerificationResult.Failure(
                    ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
                )
            } else {
                JournaledReferenceAssetVerificationResult.Verified
            }
        }

        override fun renameExactToQuarantine(
            identity: ReferenceAssetIdentity,
            operation: ReferenceImportFileOperationSnapshot,
            evidence: JournaledReferenceAssetEvidence,
        ): RenameExactToQuarantineResult {
            val event = "assets:quarantine:${operation.stage.name}:evidence"
            events += event
            calls += event
            tokens += identity.importToken
            return if (failAt() == "assets:quarantine") {
                RenameExactToQuarantineResult.Ambiguous(ReferenceImportFileFailureCode.RENAME_FAILED)
            } else {
                RenameExactToQuarantineResult.Moved
            }
        }

        override fun syncAndVerifyQuarantined(
            identity: ReferenceAssetIdentity,
            evidence: JournaledReferenceAssetEvidence,
        ): JournaledReferenceAssetVerificationResult {
            val event = "assets:verify-quarantined"
            events += event
            calls += event
            tokens += identity.importToken
            return if (failAt() == event) {
                JournaledReferenceAssetVerificationResult.Failure(
                    ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
                )
            } else {
                JournaledReferenceAssetVerificationResult.Verified
            }
        }
    }

    companion object {
        private const val ROOM_JOURNAL_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/data/RoomReferenceImportFileJournal.kt"
        private val VALID_SHA = "ab".repeat(32)
        private val TIMELINE = ReferenceImportRecoveryTimeline(
            cleanupRequiredAtEpochMillis = 101L,
            cleanupPendingSyncAtEpochMillis = 102L,
            cleanedDurableAtEpochMillis = 103L,
            quarantineRequiredAtEpochMillis = 104L,
            quarantinePendingSyncAtEpochMillis = 105L,
            quarantineDurableAtEpochMillis = 106L,
            reconciliationMarkedAtEpochMillis = 107L,
            logicalSettlementAtEpochMillis = 108L,
        )
        private val RETRY_TIMELINE = ReferenceImportRecoveryTimeline(
            cleanupRequiredAtEpochMillis = 201L,
            cleanupPendingSyncAtEpochMillis = 202L,
            cleanedDurableAtEpochMillis = 203L,
            quarantineRequiredAtEpochMillis = 204L,
            quarantinePendingSyncAtEpochMillis = 205L,
            quarantineDurableAtEpochMillis = 206L,
            reconciliationMarkedAtEpochMillis = 207L,
            logicalSettlementAtEpochMillis = 208L,
        )

        private fun snapshot(
            token: ReferenceImportToken,
            stage: ReferenceImportFileOperationStage,
            reconciliationRequired: Boolean,
        ): ReferenceImportFileOperationSnapshot {
            val hasEvidence = stage in setOf(
                ReferenceImportFileOperationStage.TEMP_SYNCED,
                ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
                ReferenceImportFileOperationStage.FINAL_DURABLE,
                ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
                ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
                ReferenceImportFileOperationStage.QUARANTINE_DURABLE,
            )
            return ReferenceImportFileOperationSnapshot(
                importToken = token,
                paths = ReferenceImportFileOperationPaths.forToken(token),
                stage = stage,
                byteCount = if (hasEvidence) 7L else null,
                sha256 = if (hasEvidence) VALID_SHA else null,
                lastFailureCode = if (reconciliationRequired) ReferenceImportFileFailureCode.STATE_MISMATCH else null,
                reconciliationRequired = reconciliationRequired,
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 100L,
            )
        }

        private fun pending(
            token: ReferenceImportToken,
            lifecycle: ReferenceImportLifecycle,
        ): PendingReferenceImport = PendingReferenceImport(
            importToken = token,
            shootId = "shoot-recovery",
            poseId = "pose-recovery",
            relativeAssetPath = ReferenceImportAssetPath.forToken(token),
            lifecycle = lifecycle,
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 10L,
        )

        private fun cleanupEvents(
            sourceStage: String,
            includeRequired: Boolean,
            includeDelete: Boolean,
            includeVerify: Boolean,
        ): List<String> = buildList {
            if (includeRequired) add("journal:CLEANUP_REQUIRED@101")
            if (includeDelete) {
                add("assets:delete:$sourceStage:no-evidence")
                add("journal:CLEANUP_PENDING_SYNC@102")
            }
            if (includeVerify) {
                add("assets:verify-cleaned")
                add("journal:CLEANED_DURABLE@103")
            }
            add("settle:CLEANED@108")
        }

        private fun quarantineEvents(
            sourceStage: String,
            includeRequired: Boolean,
            includeRename: Boolean,
            includeVerify: Boolean,
        ): List<String> = buildList {
            if (includeRequired) add("journal:QUARANTINE_REQUIRED@104")
            if (includeRename) {
                add("assets:quarantine:$sourceStage:evidence")
                add("journal:QUARANTINE_PENDING_SYNC@105")
            }
            if (includeVerify) {
                add("assets:verify-quarantined")
                add("journal:QUARANTINE_DURABLE@106")
            }
            add("settle:QUARANTINED@108")
        }
    }
}
