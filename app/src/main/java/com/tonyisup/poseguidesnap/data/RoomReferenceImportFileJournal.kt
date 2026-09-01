package com.tonyisup.poseguidesnap.data

import android.database.sqlite.SQLiteConstraintException
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.db.ReferenceImportFileOperationEntity
import java.util.concurrent.Callable

class RoomReferenceImportFileJournal(
    private val database: AppDatabase,
) {
    private val dao = database.referenceImportFileOperationDao()
    private val intentDao = database.referenceImportDao()

    fun snapshot(importToken: ReferenceImportToken): ReferenceImportFileOperationSnapshot? =
        try {
            dao.findOperation(importToken.value)?.toSnapshot()
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("reference import file operation state is invalid")
        }

    fun findRetryableOperationsPage(
        afterCreatedAtEpochMillis: Long?,
        afterImportToken: ReferenceImportToken?,
        limit: Int,
    ): List<ReferenceImportFileOperationSnapshot> {
        require((afterCreatedAtEpochMillis == null) == (afterImportToken == null)) {
            "reference import recovery cursor must be complete"
        }
        require(limit in 1..MAX_RECOVERY_PAGE_SIZE) {
            "reference import recovery page size is out of bounds"
        }
        return try {
            inTransaction {
                if (intentDao.hasIntentWithoutFileOperation()) {
                    throw IllegalStateException("reference import file authority is inconsistent")
                }
                dao.findRetryableOperations(
                    afterCreatedAtEpochMillis = afterCreatedAtEpochMillis,
                    afterImportToken = afterImportToken?.value,
                    limit = limit,
                ).map(ReferenceImportFileOperationEntity::toSnapshot)
            }
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("reference import file operation state is invalid")
        }
    }

    fun advance(request: ReferenceImportFileAdvanceRequest): ReferenceImportFileJournalResult {
        validateAdvanceRequest(request)?.let { return rejected(it) }
        return try {
            inTransaction { advanceInTransaction(request) }
        } catch (_: SQLiteConstraintException) {
            classifyAdvanceAfterRace(request)
        } catch (_: ReferenceImportFileJournalCasFailedException) {
            classifyAdvanceAfterRace(request)
        } catch (_: IllegalArgumentException) {
            rejected(ReferenceImportFileJournalRejectionReason.PERSISTED_STATE_INVALID)
        }
    }

    fun markReconciliationRequired(
        request: ReferenceImportFileReconciliationRequest,
    ): ReferenceImportFileJournalResult {
        if (
            request.expectedUpdatedAtEpochMillis < 0L ||
            request.markedAtEpochMillis <= request.expectedUpdatedAtEpochMillis
        ) {
            return rejected(ReferenceImportFileJournalRejectionReason.INVALID_TIMESTAMP)
        }
        return try {
            inTransaction { markReconciliationRequiredInTransaction(request) }
        } catch (_: SQLiteConstraintException) {
            classifyReconciliationAfterRace(request)
        } catch (_: ReferenceImportFileJournalCasFailedException) {
            classifyReconciliationAfterRace(request)
        } catch (_: IllegalArgumentException) {
            rejected(ReferenceImportFileJournalRejectionReason.PERSISTED_STATE_INVALID)
        }
    }

    fun clearReconciliationRequired(
        request: ReferenceImportFileReconciliationResolutionRequest,
    ): ReferenceImportFileJournalResult {
        if (
            request.expectedUpdatedAtEpochMillis < 0L ||
            request.resolvedAtEpochMillis <= request.expectedUpdatedAtEpochMillis
        ) {
            return rejected(ReferenceImportFileJournalRejectionReason.INVALID_TIMESTAMP)
        }
        return try {
            inTransaction { clearReconciliationRequiredInTransaction(request) }
        } catch (_: SQLiteConstraintException) {
            classifyReconciliationResolutionAfterRace(request)
        } catch (_: ReferenceImportFileJournalCasFailedException) {
            classifyReconciliationResolutionAfterRace(request)
        } catch (_: IllegalArgumentException) {
            rejected(ReferenceImportFileJournalRejectionReason.PERSISTED_STATE_INVALID)
        }
    }

    private fun advanceInTransaction(
        request: ReferenceImportFileAdvanceRequest,
    ): ReferenceImportFileJournalResult {
        val current = dao.findOperation(request.importToken.value)?.toSnapshot()
            ?: return rejected(ReferenceImportFileJournalRejectionReason.UNKNOWN_OPERATION)
        if (current.matchesAdvanceTarget(request)) {
            return ReferenceImportFileJournalResult.Idempotent(current)
        }
        if (
            current.stage != request.expectedStage ||
            current.updatedAtEpochMillis != request.expectedUpdatedAtEpochMillis
        ) {
            return rejected(current.staleOrContradictory(request.expectedStage))
        }

        val decision = ReferenceImportFileTransitionPolicy.advance(current, request)
        if (decision !is ReferenceImportFileJournalResult.Applied) {
            return decision
        }
        val target = decision.snapshot
        if (
            dao.compareAndSetOperation(
                importToken = request.importToken.value,
                expectedStage = request.expectedStage,
                expectedUpdatedAtEpochMillis = request.expectedUpdatedAtEpochMillis,
                targetStage = target.stage,
                targetByteCount = target.byteCount,
                targetSha256 = target.sha256,
                targetFailureCode = target.lastFailureCode,
                targetReconciliationRequired = target.reconciliationRequired,
                targetUpdatedAtEpochMillis = target.updatedAtEpochMillis,
            ) != 1
        ) {
            throw ReferenceImportFileJournalCasFailedException()
        }
        val persisted = dao.findOperation(request.importToken.value)?.toSnapshot()
            ?: return rejected(ReferenceImportFileJournalRejectionReason.CONTRADICTORY_STATE)
        return if (persisted == target) {
            ReferenceImportFileJournalResult.Applied(persisted)
        } else {
            rejected(ReferenceImportFileJournalRejectionReason.CONTRADICTORY_STATE)
        }
    }

    private fun markReconciliationRequiredInTransaction(
        request: ReferenceImportFileReconciliationRequest,
    ): ReferenceImportFileJournalResult {
        val current = dao.findOperation(request.importToken.value)?.toSnapshot()
            ?: return rejected(ReferenceImportFileJournalRejectionReason.UNKNOWN_OPERATION)
        if (current.matchesReconciliationTarget(request)) {
            return ReferenceImportFileJournalResult.Idempotent(current)
        }
        if (
            current.stage != request.expectedStage ||
            current.updatedAtEpochMillis != request.expectedUpdatedAtEpochMillis
        ) {
            return rejected(current.staleOrContradictory(request.expectedStage))
        }

        val target = current.copy(
            lastFailureCode = request.failureCode,
            reconciliationRequired = true,
            updatedAtEpochMillis = request.markedAtEpochMillis,
        )
        if (
            dao.compareAndSetOperation(
                importToken = request.importToken.value,
                expectedStage = request.expectedStage,
                expectedUpdatedAtEpochMillis = request.expectedUpdatedAtEpochMillis,
                targetStage = target.stage,
                targetByteCount = target.byteCount,
                targetSha256 = target.sha256,
                targetFailureCode = target.lastFailureCode,
                targetReconciliationRequired = target.reconciliationRequired,
                targetUpdatedAtEpochMillis = target.updatedAtEpochMillis,
            ) != 1
        ) {
            throw ReferenceImportFileJournalCasFailedException()
        }
        val persisted = dao.findOperation(request.importToken.value)?.toSnapshot()
            ?: return rejected(ReferenceImportFileJournalRejectionReason.CONTRADICTORY_STATE)
        return if (persisted == target) {
            ReferenceImportFileJournalResult.Applied(persisted)
        } else {
            rejected(ReferenceImportFileJournalRejectionReason.CONTRADICTORY_STATE)
        }
    }

    private fun clearReconciliationRequiredInTransaction(
        request: ReferenceImportFileReconciliationResolutionRequest,
    ): ReferenceImportFileJournalResult {
        val current = dao.findOperation(request.importToken.value)?.toSnapshot()
            ?: return rejected(ReferenceImportFileJournalRejectionReason.UNKNOWN_OPERATION)
        if (current.matchesReconciliationResolutionTarget(request)) {
            return ReferenceImportFileJournalResult.Idempotent(current)
        }
        if (
            current.stage != request.expectedStage ||
            current.updatedAtEpochMillis != request.expectedUpdatedAtEpochMillis
        ) {
            return rejected(current.staleOrContradictory(request.expectedStage))
        }
        if (!current.reconciliationRequired || current.lastFailureCode == null) {
            return rejected(ReferenceImportFileJournalRejectionReason.CONTRADICTORY_STATE)
        }

        val target = current.copy(
            lastFailureCode = null,
            reconciliationRequired = false,
            updatedAtEpochMillis = request.resolvedAtEpochMillis,
        )
        if (
            dao.compareAndSetOperation(
                importToken = request.importToken.value,
                expectedStage = request.expectedStage,
                expectedUpdatedAtEpochMillis = request.expectedUpdatedAtEpochMillis,
                targetStage = target.stage,
                targetByteCount = target.byteCount,
                targetSha256 = target.sha256,
                targetFailureCode = null,
                targetReconciliationRequired = false,
                targetUpdatedAtEpochMillis = target.updatedAtEpochMillis,
            ) != 1
        ) {
            throw ReferenceImportFileJournalCasFailedException()
        }
        val persisted = dao.findOperation(request.importToken.value)?.toSnapshot()
            ?: return rejected(ReferenceImportFileJournalRejectionReason.CONTRADICTORY_STATE)
        return if (persisted == target) {
            ReferenceImportFileJournalResult.Applied(persisted)
        } else {
            rejected(ReferenceImportFileJournalRejectionReason.CONTRADICTORY_STATE)
        }
    }

    private fun classifyAdvanceAfterRace(
        request: ReferenceImportFileAdvanceRequest,
    ): ReferenceImportFileJournalResult =
        classifyPersistedState(
            importToken = request.importToken,
            expectedStage = request.expectedStage,
            matchesTarget = { snapshot -> snapshot.matchesAdvanceTarget(request) },
        )

    private fun classifyReconciliationAfterRace(
        request: ReferenceImportFileReconciliationRequest,
    ): ReferenceImportFileJournalResult =
        classifyPersistedState(
            importToken = request.importToken,
            expectedStage = request.expectedStage,
            matchesTarget = { snapshot -> snapshot.matchesReconciliationTarget(request) },
        )

    private fun classifyReconciliationResolutionAfterRace(
        request: ReferenceImportFileReconciliationResolutionRequest,
    ): ReferenceImportFileJournalResult =
        classifyPersistedState(
            importToken = request.importToken,
            expectedStage = request.expectedStage,
            matchesTarget = { snapshot -> snapshot.matchesReconciliationResolutionTarget(request) },
        )

    private fun classifyPersistedState(
        importToken: ReferenceImportToken,
        expectedStage: ReferenceImportFileOperationStage,
        matchesTarget: (ReferenceImportFileOperationSnapshot) -> Boolean,
    ): ReferenceImportFileJournalResult =
        try {
            inTransaction {
                val persisted = dao.findOperation(importToken.value)?.toSnapshot()
                    ?: return@inTransaction rejected(
                        ReferenceImportFileJournalRejectionReason.UNKNOWN_OPERATION,
                    )
                when {
                    matchesTarget(persisted) -> ReferenceImportFileJournalResult.Idempotent(persisted)
                    persisted.stage == expectedStage -> rejected(
                        ReferenceImportFileJournalRejectionReason.STALE_SNAPSHOT,
                    )
                    else -> rejected(ReferenceImportFileJournalRejectionReason.CONTRADICTORY_STATE)
                }
            }
        } catch (_: IllegalArgumentException) {
            rejected(ReferenceImportFileJournalRejectionReason.PERSISTED_STATE_INVALID)
        }

    private fun validateAdvanceRequest(
        request: ReferenceImportFileAdvanceRequest,
    ): ReferenceImportFileJournalRejectionReason? =
        when {
            request.expectedUpdatedAtEpochMillis < 0L ||
                request.transitionedAtEpochMillis <= request.expectedUpdatedAtEpochMillis ->
                ReferenceImportFileJournalRejectionReason.INVALID_TIMESTAMP
            !ReferenceImportFileTransitionPolicy.isLegalTransition(
                request.expectedStage,
                request.targetStage,
            ) -> ReferenceImportFileJournalRejectionReason.ILLEGAL_TRANSITION
            !hasValidReferenceImportFileOperationEvidence(
                request.targetStage,
                request.byteCount,
                request.sha256,
            ) -> ReferenceImportFileJournalRejectionReason.EVIDENCE_MISMATCH
            else -> null
        }

    private fun <T> inTransaction(block: () -> T): T =
        database.runInTransaction(Callable { block() })

    private fun rejected(reason: ReferenceImportFileJournalRejectionReason) =
        ReferenceImportFileJournalResult.Rejected(reason)
}

private const val MAX_RECOVERY_PAGE_SIZE = 20

private fun ReferenceImportFileOperationEntity.toSnapshot(): ReferenceImportFileOperationSnapshot {
    val token = ReferenceImportToken(importToken)
    return ReferenceImportFileOperationSnapshot(
        importToken = token,
        paths = ReferenceImportFileOperationPaths.forToken(token),
        stage = stage,
        byteCount = byteCount,
        sha256 = sha256,
        lastFailureCode = lastFailureCode,
        reconciliationRequired = reconciliationRequired,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    ).also { snapshot ->
        require(relativeAssetPath == snapshot.paths.relativeAssetPath)
        require(relativeTempPath == snapshot.paths.relativeTempPath)
        require(relativeQuarantinePath == snapshot.paths.relativeQuarantinePath)
    }
}

private fun ReferenceImportFileOperationSnapshot.matchesAdvanceTarget(
    request: ReferenceImportFileAdvanceRequest,
): Boolean =
    importToken == request.importToken &&
        stage == request.targetStage &&
        byteCount == request.byteCount &&
        sha256 == request.sha256 &&
        lastFailureCode == null &&
        !reconciliationRequired &&
        updatedAtEpochMillis == request.transitionedAtEpochMillis

private fun ReferenceImportFileOperationSnapshot.matchesReconciliationTarget(
    request: ReferenceImportFileReconciliationRequest,
): Boolean =
    importToken == request.importToken &&
        stage == request.expectedStage &&
        lastFailureCode == request.failureCode &&
        reconciliationRequired &&
        updatedAtEpochMillis == request.markedAtEpochMillis

private fun ReferenceImportFileOperationSnapshot.matchesReconciliationResolutionTarget(
    request: ReferenceImportFileReconciliationResolutionRequest,
): Boolean =
    importToken == request.importToken &&
        stage == request.expectedStage &&
        lastFailureCode == null &&
        !reconciliationRequired &&
        updatedAtEpochMillis == request.resolvedAtEpochMillis

private fun ReferenceImportFileOperationSnapshot.staleOrContradictory(
    expectedStage: ReferenceImportFileOperationStage,
): ReferenceImportFileJournalRejectionReason =
    if (stage == expectedStage) {
        ReferenceImportFileJournalRejectionReason.STALE_SNAPSHOT
    } else {
        ReferenceImportFileJournalRejectionReason.CONTRADICTORY_STATE
    }

private class ReferenceImportFileJournalCasFailedException : RuntimeException()
