package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.db.CaptureFileOperationEntity
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import java.util.concurrent.Callable

/** Room authority for capture-file stage changes. It performs no physical file operation. */
class RoomCaptureFileJournal internal constructor(
    database: AppDatabase,
    private val beforeMutationTransaction: () -> Unit,
    private val allowReconciliationAttempt: Boolean = false,
) {
    constructor(database: AppDatabase) : this(database, {})

    private val database = database
    private val operations = database.captureFileOperationDao()

    fun snapshot(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot? =
        database.runInTransaction(Callable { loadExact(identity) })

    fun advance(request: CaptureFileAdvanceRequest): CaptureFileJournalResult {
        validateAdvanceRequest(request)?.let { return rejected(it) }
        return mutate(
            identity = request.identity,
            expectedStage = request.expectedStage,
            expectedUpdatedAtEpochMillis = request.expectedUpdatedAtEpochMillis,
            targetMatches = { it.matchesAdvanceTarget(request) },
            targetAuthority = { request.transitionedAtEpochMillis to request.capturedAtEpochMillis },
        ) { current -> CaptureFileTransitionPolicy.advance(current, request) }
    }

    fun markReconciliationRequired(
        request: CaptureFileReconciliationRequest,
    ): CaptureFileJournalResult {
        if (
            request.expectedUpdatedAtEpochMillis < 0L ||
            request.markedAtEpochMillis <= request.expectedUpdatedAtEpochMillis
        ) {
            return rejected(CaptureFileJournalRejectionReason.INVALID_TIMESTAMP)
        }
        return mutate(
            identity = request.identity,
            expectedStage = request.expectedStage,
            expectedUpdatedAtEpochMillis = request.expectedUpdatedAtEpochMillis,
            targetMatches = { it.matchesReconciliationTarget(request) },
            targetAuthority = { current ->
                request.markedAtEpochMillis to current.capturedAtEpochMillis
            },
        ) { current ->
            CaptureFileJournalResult.Applied(
                current.copy(
                    lastFailureCode = request.failureCode,
                    reconciliationRequired = true,
                    updatedAtEpochMillis = request.markedAtEpochMillis,
                ),
            )
        }
    }

    fun clearReconciliationRequired(
        request: CaptureFileReconciliationResolutionRequest,
    ): CaptureFileJournalResult {
        if (
            request.expectedUpdatedAtEpochMillis < 0L ||
            request.resolvedAtEpochMillis <= request.expectedUpdatedAtEpochMillis
        ) {
            return rejected(CaptureFileJournalRejectionReason.INVALID_TIMESTAMP)
        }
        return mutate(
            identity = request.identity,
            expectedStage = request.expectedStage,
            expectedUpdatedAtEpochMillis = request.expectedUpdatedAtEpochMillis,
            targetMatches = { it.matchesReconciliationResolutionTarget(request) },
            targetAuthority = { current ->
                request.resolvedAtEpochMillis to current.capturedAtEpochMillis
            },
        ) { current ->
            if (!current.reconciliationRequired || current.lastFailureCode == null) {
                rejected(CaptureFileJournalRejectionReason.CONTRADICTORY_STATE)
            } else {
                CaptureFileJournalResult.Applied(
                    current.copy(
                        lastFailureCode = null,
                        reconciliationRequired = false,
                        updatedAtEpochMillis = request.resolvedAtEpochMillis,
                    ),
                )
            }
        }
    }

    private fun mutate(
        identity: PrivateOutputIdentity,
        expectedStage: CaptureFileOperationStage,
        expectedUpdatedAtEpochMillis: Long,
        targetMatches: (CaptureFileOperationSnapshot) -> Boolean,
        targetAuthority: (CaptureFileOperationSnapshot) -> Pair<Long, Long?>,
        transition: (CaptureFileOperationSnapshot) -> CaptureFileJournalResult,
    ): CaptureFileJournalResult = try {
        beforeMutationTransaction()
        database.runInTransaction(
            Callable {
                val current = loadExact(identity)
                    ?: return@Callable rejected(CaptureFileJournalRejectionReason.UNKNOWN_OPERATION)
                val (targetUpdatedAtEpochMillis, targetCapturedAtEpochMillis) =
                    targetAuthority(current)
                val ownerClassification = classifyOwner(
                    operation = current,
                    targetUpdatedAtEpochMillis = targetUpdatedAtEpochMillis,
                    targetCapturedAtEpochMillis = targetCapturedAtEpochMillis,
                )
                if (ownerClassification == OwnerClassification.INVALID) {
                    return@Callable rejected(
                        CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID,
                    )
                }
                if (targetMatches(current)) {
                    return@Callable CaptureFileJournalResult.Idempotent(current)
                }
                if (
                    current.stage != expectedStage ||
                    current.updatedAtEpochMillis != expectedUpdatedAtEpochMillis
                ) {
                    return@Callable rejected(current.staleOrContradictory(expectedStage))
                }
                val decision = transition(current)
                if (decision !is CaptureFileJournalResult.Applied) return@Callable decision
                val target = decision.snapshot
                require(target.updatedAtEpochMillis == targetUpdatedAtEpochMillis)
                require(target.capturedAtEpochMillis == targetCapturedAtEpochMillis)
                when (ownerClassification) {
                    OwnerClassification.BLOCKED_BY_DELETION ->
                        return@Callable CaptureFileJournalResult.BlockedByDeletion
                    OwnerClassification.INVALID ->
                        return@Callable rejected(CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID)
                    OwnerClassification.WRONG_ATTEMPT_STATE ->
                        return@Callable rejected(CaptureFileJournalRejectionReason.WRONG_ATTEMPT_STATE)
                    OwnerClassification.INVALID_TIMESTAMP ->
                        return@Callable rejected(CaptureFileJournalRejectionReason.INVALID_TIMESTAMP)
                    OwnerClassification.INVALID_EVIDENCE ->
                        return@Callable rejected(CaptureFileJournalRejectionReason.INVALID_EVIDENCE)
                    OwnerClassification.READY -> Unit
                }
                if (
                    operations.compareAndSetOperation(
                        commandToken = identity.token.value,
                        burstOrdinal = identity.ordinal,
                        expectedStage = current.stage,
                        expectedUpdatedAtEpochMillis = current.updatedAtEpochMillis,
                        targetStage = target.stage,
                        targetByteCount = target.byteCount,
                        targetSha256 = target.sha256,
                        targetCapturedAtEpochMillis = target.capturedAtEpochMillis,
                        targetFailureCode = target.lastFailureCode,
                        targetReconciliationRequired = target.reconciliationRequired,
                        targetUpdatedAtEpochMillis = target.updatedAtEpochMillis,
                        allowReconciliationAttempt = allowReconciliationAttempt,
                    ) != 1
                ) {
                    throw CaptureFileJournalCasFailedException()
                }
                val persisted = loadExact(identity)
                    ?: return@Callable rejected(CaptureFileJournalRejectionReason.CONTRADICTORY_STATE)
                if (persisted == target) {
                    CaptureFileJournalResult.Applied(persisted)
                } else {
                    rejected(CaptureFileJournalRejectionReason.CONTRADICTORY_STATE)
                }
            },
        )
    } catch (_: IllegalArgumentException) {
        rejected(CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID)
    } catch (_: CaptureFileJournalCasFailedException) {
        classifyAfterRace(identity, expectedStage, targetMatches, targetAuthority)
    }

    private fun classifyAfterRace(
        identity: PrivateOutputIdentity,
        expectedStage: CaptureFileOperationStage,
        targetMatches: (CaptureFileOperationSnapshot) -> Boolean,
        targetAuthority: (CaptureFileOperationSnapshot) -> Pair<Long, Long?>,
    ): CaptureFileJournalResult = try {
        database.runInTransaction(
            Callable {
                val persisted = loadExact(identity)
                    ?: return@Callable rejected(CaptureFileJournalRejectionReason.UNKNOWN_OPERATION)
                val (targetUpdatedAtEpochMillis, targetCapturedAtEpochMillis) =
                    targetAuthority(persisted)
                val ownerClassification = classifyOwner(
                    operation = persisted,
                    targetUpdatedAtEpochMillis = targetUpdatedAtEpochMillis,
                    targetCapturedAtEpochMillis = targetCapturedAtEpochMillis,
                )
                if (ownerClassification == OwnerClassification.INVALID) {
                    return@Callable rejected(
                        CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID,
                    )
                }
                if (targetMatches(persisted)) {
                    return@Callable CaptureFileJournalResult.Idempotent(persisted)
                }
                when (ownerClassification) {
                    OwnerClassification.BLOCKED_BY_DELETION ->
                        CaptureFileJournalResult.BlockedByDeletion
                    OwnerClassification.INVALID ->
                        rejected(CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID)
                    OwnerClassification.WRONG_ATTEMPT_STATE ->
                        rejected(CaptureFileJournalRejectionReason.WRONG_ATTEMPT_STATE)
                    OwnerClassification.INVALID_TIMESTAMP ->
                        rejected(CaptureFileJournalRejectionReason.INVALID_TIMESTAMP)
                    OwnerClassification.INVALID_EVIDENCE ->
                        rejected(CaptureFileJournalRejectionReason.INVALID_EVIDENCE)
                    OwnerClassification.READY -> when {
                        persisted.stage == expectedStage ->
                            rejected(CaptureFileJournalRejectionReason.STALE_SNAPSHOT)
                        else -> rejected(CaptureFileJournalRejectionReason.CONTRADICTORY_STATE)
                    }
                }
            },
        )
    } catch (_: IllegalArgumentException) {
        rejected(CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID)
    }

    private fun loadExact(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot? {
        val paths = CaptureFileOperationPaths.forIdentity(identity)
        when (
            operations.classifyOperationStorage(
                commandToken = identity.token.value,
                burstOrdinal = identity.ordinal,
                relativeFinalPath = paths.relativeFinalPath,
                relativeTempPath = paths.relativeTempPath,
                relativeQuarantinePath = paths.relativeQuarantinePath,
            )
        ) {
            OPERATION_ABSENT -> return null
            OPERATION_VALID -> Unit
            else -> throw IllegalArgumentException(PERSISTED_OPERATION_INVALID_MESSAGE)
        }
        val candidates = operations.findOperationCandidates(identity.token.value, identity.ordinal)
        require(candidates.size == 1) { "capture file operation identity is ambiguous" }
        return candidates.single().toSnapshot().also { require(it.identity == identity) }
    }

    private fun classifyOwner(
        operation: CaptureFileOperationSnapshot,
        targetUpdatedAtEpochMillis: Long,
        targetCapturedAtEpochMillis: Long?,
    ): OwnerClassification = when (
        operations.classifyMutationOwner(
            commandToken = operation.identity.token.value,
            operationCreatedAtEpochMillis = operation.createdAtEpochMillis,
            operationStage = operation.stage.name,
            operationFailureCode = operation.lastFailureCode?.name,
            operationUpdatedAtEpochMillis = operation.updatedAtEpochMillis,
            operationCapturedAtEpochMillis = operation.capturedAtEpochMillis,
            targetUpdatedAtEpochMillis = targetUpdatedAtEpochMillis,
            targetCapturedAtEpochMillis = targetCapturedAtEpochMillis,
            allowReconciliationAttempt = allowReconciliationAttempt,
        )
    ) {
        OWNER_INVALID -> OwnerClassification.INVALID
        OWNER_BLOCKED_BY_DELETION -> OwnerClassification.BLOCKED_BY_DELETION
        OWNER_WRONG_ATTEMPT_STATE -> OwnerClassification.WRONG_ATTEMPT_STATE
        OWNER_INVALID_TIMESTAMP -> OwnerClassification.INVALID_TIMESTAMP
        OWNER_INVALID_EVIDENCE -> OwnerClassification.INVALID_EVIDENCE
        OWNER_READY -> OwnerClassification.READY
        else -> OwnerClassification.INVALID
    }

    private fun validateAdvanceRequest(
        request: CaptureFileAdvanceRequest,
    ): CaptureFileJournalRejectionReason? = when {
        allowReconciliationAttempt && request.targetStage !in RECOVERY_CLEANUP_STAGES ->
            CaptureFileJournalRejectionReason.ILLEGAL_TRANSITION
        request.expectedUpdatedAtEpochMillis < 0L ||
            request.transitionedAtEpochMillis <= request.expectedUpdatedAtEpochMillis ->
            CaptureFileJournalRejectionReason.INVALID_TIMESTAMP
        !CaptureFileTransitionPolicy.isLegalTransition(request.expectedStage, request.targetStage) ->
            CaptureFileJournalRejectionReason.ILLEGAL_TRANSITION
        !hasValidCaptureFileOperationEvidence(
            request.targetStage,
            request.byteCount,
            request.sha256,
            request.capturedAtEpochMillis,
        ) -> CaptureFileJournalRejectionReason.INVALID_EVIDENCE
        else -> null
    }

    private fun rejected(reason: CaptureFileJournalRejectionReason) =
        CaptureFileJournalResult.Rejected(reason)

    private enum class OwnerClassification {
        READY,
        BLOCKED_BY_DELETION,
        WRONG_ATTEMPT_STATE,
        INVALID_TIMESTAMP,
        INVALID_EVIDENCE,
        INVALID,
    }

    private companion object {
        const val OPERATION_ABSENT = 0
        const val OPERATION_VALID = 1
        const val PERSISTED_OPERATION_INVALID_MESSAGE = "capture file operation authority is invalid"
        const val OWNER_INVALID = 0
        const val OWNER_BLOCKED_BY_DELETION = 1
        const val OWNER_WRONG_ATTEMPT_STATE = 2
        const val OWNER_INVALID_TIMESTAMP = 3
        const val OWNER_INVALID_EVIDENCE = 4
        const val OWNER_READY = 5
        val RECOVERY_CLEANUP_STAGES = setOf(
            CaptureFileOperationStage.CLEANUP_REQUIRED,
            CaptureFileOperationStage.CLEANUP_PENDING_SYNC,
            CaptureFileOperationStage.CLEANED_DURABLE,
        )
    }
}

/** Recovery-only Room authority. It cannot admit a new write, rename, or quarantine effect. */
internal class RoomCaptureFileRecoveryJournal(database: AppDatabase) {
    private val delegate = RoomCaptureFileJournal(
        database = database,
        beforeMutationTransaction = {},
        allowReconciliationAttempt = true,
    )

    fun snapshot(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot? =
        delegate.snapshot(identity)

    fun advanceCleanup(request: CaptureFileAdvanceRequest): CaptureFileJournalResult =
        delegate.advance(request)
}

private fun CaptureFileOperationEntity.toSnapshot(): CaptureFileOperationSnapshot {
    val identity = PrivateOutputIdentity(CaptureToken(commandToken), burstOrdinal)
    return CaptureFileOperationSnapshot(
        identity = identity,
        paths = CaptureFileOperationPaths.forIdentity(identity),
        stage = stage,
        byteCount = byteCount,
        sha256 = sha256,
        capturedAtEpochMillis = capturedAtEpochMillis,
        lastFailureCode = lastFailureCode,
        reconciliationRequired = reconciliationRequired,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    ).also { snapshot ->
        require(relativeFinalPath == snapshot.paths.relativeFinalPath)
        require(relativeTempPath == snapshot.paths.relativeTempPath)
        require(relativeQuarantinePath == snapshot.paths.relativeQuarantinePath)
    }
}

private fun CaptureFileOperationSnapshot.matchesAdvanceTarget(request: CaptureFileAdvanceRequest): Boolean =
    identity == request.identity &&
        stage == request.targetStage &&
        byteCount == request.byteCount &&
        sha256 == request.sha256 &&
        capturedAtEpochMillis == request.capturedAtEpochMillis &&
        lastFailureCode == null &&
        !reconciliationRequired &&
        updatedAtEpochMillis == request.transitionedAtEpochMillis

private fun CaptureFileOperationSnapshot.matchesReconciliationTarget(
    request: CaptureFileReconciliationRequest,
): Boolean = identity == request.identity &&
    stage == request.expectedStage &&
    lastFailureCode == request.failureCode &&
    reconciliationRequired &&
    updatedAtEpochMillis == request.markedAtEpochMillis

private fun CaptureFileOperationSnapshot.matchesReconciliationResolutionTarget(
    request: CaptureFileReconciliationResolutionRequest,
): Boolean = identity == request.identity &&
    stage == request.expectedStage &&
    lastFailureCode == null &&
    !reconciliationRequired &&
    updatedAtEpochMillis == request.resolvedAtEpochMillis

private fun CaptureFileOperationSnapshot.staleOrContradictory(
    expectedStage: CaptureFileOperationStage,
): CaptureFileJournalRejectionReason = if (stage == expectedStage) {
    CaptureFileJournalRejectionReason.STALE_SNAPSHOT
} else {
    CaptureFileJournalRejectionReason.CONTRADICTORY_STATE
}

private class CaptureFileJournalCasFailedException : RuntimeException()
