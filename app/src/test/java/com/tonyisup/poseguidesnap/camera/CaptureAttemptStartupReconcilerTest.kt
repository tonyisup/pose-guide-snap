package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.data.CaptureAttemptSettlementResult
import com.tonyisup.poseguidesnap.data.CaptureFileAdvanceRequest
import com.tonyisup.poseguidesnap.data.CaptureFileJournalResult
import com.tonyisup.poseguidesnap.data.CaptureFileOperationPaths
import com.tonyisup.poseguidesnap.data.CaptureFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage
import com.tonyisup.poseguidesnap.data.GuidedBlockingAttemptSummary
import com.tonyisup.poseguidesnap.data.GuidedCaptureAttemptState
import com.tonyisup.poseguidesnap.data.GuidedCaptureTrigger
import com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapResult
import com.tonyisup.poseguidesnap.data.GuidedSessionLifecycle
import com.tonyisup.poseguidesnap.data.GuidedSessionSnapshot
import com.tonyisup.poseguidesnap.data.JournalFreeCaptureAttemptCandidateResult
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAttemptStartupReconcilerTest {
    @Test
    fun aggregateReconciliationCleansEveryExactRowThenSettlesFailed() {
        val authority = FakeAuthority(
            bootstrap = blockingBootstrap(GuidedCaptureAttemptState.RECONCILIATION_REQUIRED),
            operations = mutableMapOf(
                identity(0) to operation(0, CaptureFileOperationStage.WRITING_TEMP, 10),
                identity(1) to operation(
                    1,
                    CaptureFileOperationStage.FINAL_DURABLE,
                    11,
                    evidence = evidence(),
                ),
                identity(2) to operation(2, CaptureFileOperationStage.CLEANUP_PENDING_SYNC, 12),
            ),
        )
        val files = FakeFiles()

        val result = reconciler(authority, files).reconcile(SESSION_ID)

        assertEquals(CaptureAttemptRestartRecoveryResult.FailedCleaned, result)
        assertEquals((0..2).map(::identity), files.cleaned)
        assertTrue(authority.operations.values.all {
            it.stage == CaptureFileOperationStage.CLEANED_DURABLE && it.byteCount == null
        })
        assertEquals(1, authority.settlementCalls)
        assertTrue(authority.transitions.none {
            it.targetStage in setOf(
                CaptureFileOperationStage.WRITING_TEMP,
                CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
                CaptureFileOperationStage.QUARANTINE_PENDING_SYNC,
            )
        })
    }

    @Test
    fun capturingFinalJournalDefersToConfirmationWithoutFileMutation() {
        val authority = FakeAuthority(
            bootstrap = blockingBootstrap(GuidedCaptureAttemptState.CAPTURING),
            operations = (0..2).associate { ordinal ->
                identity(ordinal) to
                    operation(ordinal, CaptureFileOperationStage.FINAL_DURABLE, 10, evidence())
            }.toMutableMap(),
            firstSettlement = CaptureAttemptSettlementResult.ReadyToConfirm,
        )
        val files = FakeFiles()

        assertEquals(
            CaptureAttemptRestartRecoveryResult.ReadyToConfirm,
            reconciler(authority, files).reconcile(SESSION_ID),
        )
        assertTrue(files.cleaned.isEmpty())
        assertTrue(authority.transitions.isEmpty())
    }

    @Test
    fun journalFreeAttemptSettlesOnlyAfterAllExactPathsAreAbsent() {
        val candidate = JournalFreeCaptureAttemptCandidateResult.Candidate(
            sessionId = SESSION_ID,
            token = TOKEN,
            authorityUpdatedAtEpochMillis = 30,
        )
        val absentAuthority = FakeAuthority(
            bootstrap = GuidedSessionBootstrapResult.Rejected(
                com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            journalFree = candidate,
        )
        val absentFiles = FakeFiles(JournalFreeCapturePathObservation.ALL_ABSENT)
        assertEquals(
            CaptureAttemptRestartRecoveryResult.FailedCleaned,
            reconciler(absentAuthority, absentFiles).reconcile(SESSION_ID),
        )
        assertEquals(1, absentAuthority.journalFreeSettlementCalls)

        val presentAuthority = FakeAuthority(
            bootstrap = absentAuthority.bootstrap,
            journalFree = candidate,
        )
        val present = reconciler(
            presentAuthority,
            FakeFiles(JournalFreeCapturePathObservation.PRESENT_OR_AMBIGUOUS),
        ).reconcile(SESSION_ID)
        assertTrue(present is CaptureAttemptRestartRecoveryResult.Outstanding)
        assertEquals(
            CaptureAttemptRestartRecoveryReason.FILES_PRESENT_OR_AMBIGUOUS,
            (present as CaptureAttemptRestartRecoveryResult.Outstanding).reason,
        )
        assertEquals(0, presentAuthority.journalFreeSettlementCalls)
    }

    @Test
    fun ambiguousCleanupRemainsBlockingWithoutClaimingSuccess() {
        val authority = FakeAuthority(
            bootstrap = blockingBootstrap(GuidedCaptureAttemptState.RECONCILIATION_REQUIRED),
            operations = (0..2).associate { ordinal ->
                identity(ordinal) to
                    operation(ordinal, CaptureFileOperationStage.CLEANUP_PENDING_SYNC, 10)
            }.toMutableMap(),
        )
        val files = FakeFiles(cleanupFailsAtOrdinal = 1)

        val result = reconciler(authority, files).reconcile(SESSION_ID)

        assertEquals(
            CaptureAttemptRestartRecoveryReason.CLEANUP_FAILED,
            (result as CaptureAttemptRestartRecoveryResult.Outstanding).reason,
        )
        assertEquals(0, authority.settlementCalls)
        assertEquals(CaptureFileOperationStage.CLEANED_DURABLE, authority.operations.getValue(identity(0)).stage)
        assertEquals(
            CaptureFileOperationStage.CLEANUP_PENDING_SYNC,
            authority.operations.getValue(identity(1)).stage,
        )
    }

    private fun reconciler(authority: FakeAuthority, files: FakeFiles) =
        CaptureAttemptStartupReconciler(
            authority = authority,
            files = files,
            clock = CaptureRecoveryClock { floor -> if (floor == Long.MAX_VALUE) null else floor + 1L },
        )

    private fun blockingBootstrap(state: GuidedCaptureAttemptState): GuidedSessionBootstrapResult {
        val blocking = GuidedBlockingAttemptSummary(
            attemptNumber = 0,
            poseIndex = 0,
            state = state,
            deletionGeneration = 0,
            commandToken = TOKEN.value,
            poseId = "pose-0",
            trigger = GuidedCaptureTrigger.MANUAL,
            reconciliationRequired = state == GuidedCaptureAttemptState.RECONCILIATION_REQUIRED,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 20,
        )
        return GuidedSessionBootstrapResult.ReconciliationRequired(
            GuidedSessionSnapshot(
                sessionId = SESSION_ID,
                shootId = "shoot-1",
                lifecycle = GuidedSessionLifecycle.ACTIVE,
                orderedPoseIds = listOf("pose-0", "pose-1", "pose-2"),
                poseCount = 3,
                currentPoseIndex = 0,
                nextAttemptNumber = 1,
                deletionGeneration = 0,
                attemptCount = 1,
                confirmedAttemptCount = 0,
                appliedReceiptTokens = emptyList(),
                unresolvedExportCount = 0,
                blockingAttempt = blocking,
            ),
        )
    }

    private fun operation(
        ordinal: Int,
        stage: CaptureFileOperationStage,
        updatedAt: Long,
        evidence: JournaledCaptureEvidence? = null,
    ): CaptureFileOperationSnapshot {
        val identity = identity(ordinal)
        return CaptureFileOperationSnapshot(
            identity = identity,
            paths = CaptureFileOperationPaths.forIdentity(identity),
            stage = stage,
            byteCount = evidence?.byteCount,
            sha256 = evidence?.sha256,
            capturedAtEpochMillis = evidence?.capturedAtEpochMillis,
            lastFailureCode = null,
            reconciliationRequired = false,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = updatedAt,
        )
    }

    private fun evidence() = JournaledCaptureEvidence(4, "a".repeat(64), 5)

    private fun identity(ordinal: Int) = PrivateOutputIdentity(TOKEN, ordinal)

    private class FakeFiles(
        private val journalFree: JournalFreeCapturePathObservation =
            JournalFreeCapturePathObservation.ALL_ABSENT,
        private val cleanupFailsAtOrdinal: Int? = null,
    ) : CaptureRecoveryFilePort {
        val cleaned = mutableListOf<PrivateOutputIdentity>()

        override fun cleanup(snapshot: CaptureFileOperationSnapshot): CaptureExactCleanupResult =
            if (snapshot.identity.ordinal == cleanupFailsAtOrdinal) {
                CaptureExactCleanupResult.Ambiguous(CaptureFileFailureCodeForTest)
            } else {
                cleaned += snapshot.identity
                CaptureExactCleanupResult.Cleaned
            }

        override fun observeJournalFree(
            identities: List<PrivateOutputIdentity>,
        ): JournalFreeCapturePathObservation = journalFree

        private companion object {
            val CaptureFileFailureCodeForTest =
                com.tonyisup.poseguidesnap.data.CaptureFileFailureCode.DELETE_FAILED
        }
    }

    private class FakeAuthority(
        val bootstrap: GuidedSessionBootstrapResult,
        val operations: MutableMap<PrivateOutputIdentity, CaptureFileOperationSnapshot> = mutableMapOf(),
        private val firstSettlement: CaptureAttemptSettlementResult? = null,
        private val journalFree: JournalFreeCaptureAttemptCandidateResult =
            JournalFreeCaptureAttemptCandidateResult.None,
    ) : CaptureRecoveryAuthorityPort {
        val transitions = mutableListOf<CaptureFileAdvanceRequest>()
        var settlementCalls = 0
        var journalFreeSettlementCalls = 0

        override fun bootstrap(sessionId: String): GuidedSessionBootstrapResult = bootstrap

        override fun settleFailure(
            sessionId: String,
            token: CaptureToken,
            settledAtEpochMillis: Long,
        ): CaptureAttemptSettlementResult {
            settlementCalls += 1
            firstSettlement?.let { if (settlementCalls == 1) return it }
            return if (operations.values.all {
                    it.stage == CaptureFileOperationStage.CLEANED_DURABLE
                }
            ) {
                CaptureAttemptSettlementResult.FailedCleaned
            } else {
                CaptureAttemptSettlementResult.AlreadyReconciliationRequired
            }
        }

        override fun journalFreeCandidate(
            sessionId: String,
        ): JournalFreeCaptureAttemptCandidateResult = journalFree

        override fun settleJournalFree(
            sessionId: String,
            token: CaptureToken,
            settledAtEpochMillis: Long,
        ): CaptureAttemptSettlementResult {
            journalFreeSettlementCalls += 1
            return CaptureAttemptSettlementResult.FailedCleaned
        }

        override fun operation(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot? =
            operations[identity]

        override fun advanceCleanup(request: CaptureFileAdvanceRequest): CaptureFileJournalResult {
            transitions += request
            val current = operations[request.identity]
                ?: return CaptureFileJournalResult.Rejected(
                    com.tonyisup.poseguidesnap.data.CaptureFileJournalRejectionReason.UNKNOWN_OPERATION,
                )
            val target = current.copy(
                stage = request.targetStage,
                byteCount = request.byteCount,
                sha256 = request.sha256,
                capturedAtEpochMillis = request.capturedAtEpochMillis,
                lastFailureCode = null,
                reconciliationRequired = false,
                updatedAtEpochMillis = request.transitionedAtEpochMillis,
            )
            operations[request.identity] = target
            return CaptureFileJournalResult.Applied(target)
        }
    }

    private companion object {
        const val SESSION_ID = "session-1"
        val TOKEN = CaptureToken("recovery-token")
    }
}
