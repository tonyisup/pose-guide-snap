package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.data.CaptureAttemptSettlementResult
import com.tonyisup.poseguidesnap.data.CaptureFileAdvanceRequest
import com.tonyisup.poseguidesnap.data.CaptureFileJournalResult
import com.tonyisup.poseguidesnap.data.CaptureFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage
import com.tonyisup.poseguidesnap.data.GuidedCaptureAttemptState
import com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapResult
import com.tonyisup.poseguidesnap.data.JournalFreeCaptureAttemptCandidateResult
import com.tonyisup.poseguidesnap.data.RoomCaptureFileRecoveryJournal
import com.tonyisup.poseguidesnap.data.RoomShootRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity

sealed interface CaptureAttemptRestartRecoveryResult {
    data object NoWork : CaptureAttemptRestartRecoveryResult
    data object FailedCleaned : CaptureAttemptRestartRecoveryResult
    data object ReadyToConfirm : CaptureAttemptRestartRecoveryResult

    class Outstanding(val reason: CaptureAttemptRestartRecoveryReason) :
        CaptureAttemptRestartRecoveryResult {
        override fun toString(): String =
            "CaptureAttemptRestartRecoveryResult.Outstanding(reason=${reason.name})"
    }
}

enum class CaptureAttemptRestartRecoveryReason {
    AUTHORITY_INVALID,
    FILES_PRESENT_OR_AMBIGUOUS,
    FILESYSTEM_UNAVAILABLE,
    CLEANUP_FAILED,
    CLOCK_EXHAUSTED,
    SETTLEMENT_FAILED,
}

fun interface CaptureRecoveryClock {
    /** Returns a usable epoch millisecond strictly greater than [floor], or null at exhaustion. */
    fun nextAfter(floor: Long): Long?
}

internal interface CaptureRecoveryAuthorityPort {
    fun bootstrap(sessionId: String): GuidedSessionBootstrapResult
    fun settleFailure(
        sessionId: String,
        token: CaptureToken,
        settledAtEpochMillis: Long,
    ): CaptureAttemptSettlementResult

    fun journalFreeCandidate(sessionId: String): JournalFreeCaptureAttemptCandidateResult
    fun settleJournalFree(
        sessionId: String,
        token: CaptureToken,
        settledAtEpochMillis: Long,
    ): CaptureAttemptSettlementResult

    fun operation(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot?
    fun advanceCleanup(request: CaptureFileAdvanceRequest): CaptureFileJournalResult
}

internal interface CaptureRecoveryFilePort {
    fun cleanup(snapshot: CaptureFileOperationSnapshot): CaptureExactCleanupResult
    fun observeJournalFree(identities: List<PrivateOutputIdentity>): JournalFreeCapturePathObservation
}

/**
 * Reconciles one exact session. It never scans a directory and never admits capture/publication.
 */
class CaptureAttemptStartupReconciler internal constructor(
    private val authority: CaptureRecoveryAuthorityPort,
    private val files: CaptureRecoveryFilePort,
    private val clock: CaptureRecoveryClock,
) {
    constructor(
        repository: RoomShootRepository,
        database: AppDatabase,
        store: JournaledPrivateCaptureStore,
        clock: CaptureRecoveryClock,
    ) : this(
        authority = RoomCaptureRecoveryAuthorityAdapter(repository, database),
        files = JournaledCaptureRecoveryFileAdapter(store),
        clock = clock,
    )

    fun reconcile(sessionId: String): CaptureAttemptRestartRecoveryResult {
        if (sessionId.isBlank()) return outstanding(CaptureAttemptRestartRecoveryReason.AUTHORITY_INVALID)
        return when (val bootstrap = authority.bootstrap(sessionId)) {
            is GuidedSessionBootstrapResult.Ready,
            is GuidedSessionBootstrapResult.Completed,
            GuidedSessionBootstrapResult.UnknownSession,
            -> CaptureAttemptRestartRecoveryResult.NoWork

            is GuidedSessionBootstrapResult.ReconciliationRequired -> {
                val blocking = bootstrap.snapshot.blockingAttempt
                    ?: return outstanding(CaptureAttemptRestartRecoveryReason.AUTHORITY_INVALID)
                val token = try {
                    CaptureToken(blocking.commandToken)
                } catch (_: IllegalArgumentException) {
                    return outstanding(CaptureAttemptRestartRecoveryReason.AUTHORITY_INVALID)
                }
                when (blocking.state) {
                    GuidedCaptureAttemptState.REGISTERED,
                    GuidedCaptureAttemptState.CAPTURING,
                    -> settleBeforeCleanup(
                        sessionId = sessionId,
                        token = token,
                        floor = blocking.updatedAtEpochMillis,
                    )

                    GuidedCaptureAttemptState.RECONCILIATION_REQUIRED -> recoverCleanup(
                        sessionId = sessionId,
                        token = token,
                        floor = blocking.updatedAtEpochMillis,
                    )

                    GuidedCaptureAttemptState.FAILED_CLEANED,
                    GuidedCaptureAttemptState.CONFIRMED,
                    -> outstanding(CaptureAttemptRestartRecoveryReason.AUTHORITY_INVALID)
                }
            }

            is GuidedSessionBootstrapResult.Rejected -> reconcileJournalFree(sessionId)
        }
    }

    private fun settleBeforeCleanup(
        sessionId: String,
        token: CaptureToken,
        floor: Long,
    ): CaptureAttemptRestartRecoveryResult {
        val settledAt = clock.nextAfter(floor)
            ?: return outstanding(CaptureAttemptRestartRecoveryReason.CLOCK_EXHAUSTED)
        return when (authority.settleFailure(sessionId, token, settledAt)) {
            CaptureAttemptSettlementResult.FailedCleaned,
            CaptureAttemptSettlementResult.AlreadyFailedCleaned,
            -> CaptureAttemptRestartRecoveryResult.FailedCleaned

            CaptureAttemptSettlementResult.ReadyToConfirm ->
                CaptureAttemptRestartRecoveryResult.ReadyToConfirm

            CaptureAttemptSettlementResult.ReconciliationRequired,
            CaptureAttemptSettlementResult.AlreadyReconciliationRequired,
            -> recoverCleanup(sessionId, token, settledAt)

            CaptureAttemptSettlementResult.BlockedByDeletion,
            is CaptureAttemptSettlementResult.Rejected,
            -> outstanding(CaptureAttemptRestartRecoveryReason.SETTLEMENT_FAILED)
        }
    }

    private fun recoverCleanup(
        sessionId: String,
        token: CaptureToken,
        floor: Long,
    ): CaptureAttemptRestartRecoveryResult {
        var latestClock = floor
        for (ordinal in 0..2) {
            val identity = PrivateOutputIdentity(token, ordinal)
            var current = authority.operation(identity)
                ?: return outstanding(CaptureAttemptRestartRecoveryReason.AUTHORITY_INVALID)
            latestClock = maxOf(latestClock, current.updatedAtEpochMillis)
            if (current.stage == CaptureFileOperationStage.QUARANTINE_DURABLE) {
                return outstanding(CaptureAttemptRestartRecoveryReason.FILES_PRESENT_OR_AMBIGUOUS)
            }
            if (current.stage == CaptureFileOperationStage.CLEANED_DURABLE) continue

            if (current.stage != CaptureFileOperationStage.CLEANUP_REQUIRED &&
                current.stage != CaptureFileOperationStage.CLEANUP_PENDING_SYNC
            ) {
                current = advance(
                    current = current,
                    target = CaptureFileOperationStage.CLEANUP_REQUIRED,
                    floor = latestClock,
                ) ?: return outstanding(CaptureAttemptRestartRecoveryReason.AUTHORITY_INVALID)
                latestClock = current.updatedAtEpochMillis
            }
            if (current.stage == CaptureFileOperationStage.CLEANUP_REQUIRED) {
                current = advance(
                    current = current,
                    target = CaptureFileOperationStage.CLEANUP_PENDING_SYNC,
                    floor = latestClock,
                ) ?: return outstanding(CaptureAttemptRestartRecoveryReason.AUTHORITY_INVALID)
                latestClock = current.updatedAtEpochMillis
            }

            when (files.cleanup(current)) {
                CaptureExactCleanupResult.Cleaned -> Unit
                is CaptureExactCleanupResult.Ambiguous ->
                    return outstanding(CaptureAttemptRestartRecoveryReason.CLEANUP_FAILED)
            }
            current = advance(
                current = current,
                target = CaptureFileOperationStage.CLEANED_DURABLE,
                floor = latestClock,
                clearEvidence = true,
            ) ?: return outstanding(CaptureAttemptRestartRecoveryReason.AUTHORITY_INVALID)
            latestClock = current.updatedAtEpochMillis
        }

        val settledAt = clock.nextAfter(latestClock)
            ?: return outstanding(CaptureAttemptRestartRecoveryReason.CLOCK_EXHAUSTED)
        return when (authority.settleFailure(sessionId, token, settledAt)) {
            CaptureAttemptSettlementResult.FailedCleaned,
            CaptureAttemptSettlementResult.AlreadyFailedCleaned,
            -> CaptureAttemptRestartRecoveryResult.FailedCleaned
            CaptureAttemptSettlementResult.ReadyToConfirm ->
                CaptureAttemptRestartRecoveryResult.ReadyToConfirm
            else -> outstanding(CaptureAttemptRestartRecoveryReason.SETTLEMENT_FAILED)
        }
    }

    private fun advance(
        current: CaptureFileOperationSnapshot,
        target: CaptureFileOperationStage,
        floor: Long,
        clearEvidence: Boolean = false,
    ): CaptureFileOperationSnapshot? {
        val transitionedAt = clock.nextAfter(maxOf(floor, current.updatedAtEpochMillis)) ?: return null
        val result = authority.advanceCleanup(
            CaptureFileAdvanceRequest(
                identity = current.identity,
                expectedStage = current.stage,
                expectedUpdatedAtEpochMillis = current.updatedAtEpochMillis,
                targetStage = target,
                byteCount = if (clearEvidence) null else current.byteCount,
                sha256 = if (clearEvidence) null else current.sha256,
                capturedAtEpochMillis = if (clearEvidence) null else current.capturedAtEpochMillis,
                transitionedAtEpochMillis = transitionedAt,
            ),
        )
        return when (result) {
            is CaptureFileJournalResult.Applied -> result.snapshot
            is CaptureFileJournalResult.Idempotent -> result.snapshot
            CaptureFileJournalResult.BlockedByDeletion,
            is CaptureFileJournalResult.Rejected,
            -> null
        }
    }

    private fun reconcileJournalFree(sessionId: String): CaptureAttemptRestartRecoveryResult =
        when (val candidate = authority.journalFreeCandidate(sessionId)) {
            JournalFreeCaptureAttemptCandidateResult.None ->
                outstanding(CaptureAttemptRestartRecoveryReason.AUTHORITY_INVALID)
            JournalFreeCaptureAttemptCandidateResult.AuthorityInvalid ->
                outstanding(CaptureAttemptRestartRecoveryReason.AUTHORITY_INVALID)
            is JournalFreeCaptureAttemptCandidateResult.Candidate -> when (
                files.observeJournalFree(candidate.identities)
            ) {
                JournalFreeCapturePathObservation.ALL_ABSENT -> {
                    val settledAt = clock.nextAfter(candidate.authorityUpdatedAtEpochMillis)
                        ?: return outstanding(CaptureAttemptRestartRecoveryReason.CLOCK_EXHAUSTED)
                    when (authority.settleJournalFree(sessionId, candidate.token, settledAt)) {
                        CaptureAttemptSettlementResult.FailedCleaned,
                        CaptureAttemptSettlementResult.AlreadyFailedCleaned,
                        -> CaptureAttemptRestartRecoveryResult.FailedCleaned
                        else -> outstanding(CaptureAttemptRestartRecoveryReason.SETTLEMENT_FAILED)
                    }
                }
                JournalFreeCapturePathObservation.PRESENT_OR_AMBIGUOUS ->
                    outstanding(CaptureAttemptRestartRecoveryReason.FILES_PRESENT_OR_AMBIGUOUS)
                JournalFreeCapturePathObservation.UNAVAILABLE ->
                    outstanding(CaptureAttemptRestartRecoveryReason.FILESYSTEM_UNAVAILABLE)
            }
        }

    private fun outstanding(reason: CaptureAttemptRestartRecoveryReason) =
        CaptureAttemptRestartRecoveryResult.Outstanding(reason)
}

internal class RoomCaptureRecoveryAuthorityAdapter(
    private val repository: RoomShootRepository,
    database: AppDatabase,
) : CaptureRecoveryAuthorityPort {
    private val recoveryJournal = RoomCaptureFileRecoveryJournal(database)

    override fun bootstrap(sessionId: String): GuidedSessionBootstrapResult =
        repository.loadGuidedSessionBootstrap(sessionId)

    override fun settleFailure(
        sessionId: String,
        token: CaptureToken,
        settledAtEpochMillis: Long,
    ): CaptureAttemptSettlementResult =
        repository.settleCaptureAttemptFailure(sessionId, token, settledAtEpochMillis)

    override fun journalFreeCandidate(sessionId: String): JournalFreeCaptureAttemptCandidateResult =
        repository.findJournalFreeCaptureAttemptForRecovery(sessionId)

    override fun settleJournalFree(
        sessionId: String,
        token: CaptureToken,
        settledAtEpochMillis: Long,
    ): CaptureAttemptSettlementResult =
        repository.settleJournalFreeCaptureAttemptAfterAbsentFiles(
            sessionId,
            token,
            settledAtEpochMillis,
        )

    override fun operation(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot? =
        recoveryJournal.snapshot(identity)

    override fun advanceCleanup(request: CaptureFileAdvanceRequest): CaptureFileJournalResult =
        recoveryJournal.advanceCleanup(request)
}

internal class JournaledCaptureRecoveryFileAdapter(
    private val store: JournaledPrivateCaptureStore,
) : CaptureRecoveryFilePort {
    override fun cleanup(snapshot: CaptureFileOperationSnapshot): CaptureExactCleanupResult =
        store.cleanupExact(snapshot)

    override fun observeJournalFree(
        identities: List<PrivateOutputIdentity>,
    ): JournalFreeCapturePathObservation = store.observeJournalFreeAttempt(identities)
}
