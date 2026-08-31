package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.DeleteExactForCleanupResult
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetEvidence
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetStore
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetVerificationResult
import com.tonyisup.poseguidesnap.data.PendingReferenceImport
import com.tonyisup.poseguidesnap.data.ReferenceAssetIdentity
import com.tonyisup.poseguidesnap.data.ReferenceImportFailureSettlement
import com.tonyisup.poseguidesnap.data.ReferenceImportFileAdvanceRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportFileFailureCode
import com.tonyisup.poseguidesnap.data.ReferenceImportFileJournalResult
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationStage
import com.tonyisup.poseguidesnap.data.ReferenceImportFileReconciliationRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportFileReconciliationResolutionRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportLifecycle
import com.tonyisup.poseguidesnap.data.ReferenceImportSettlementResult
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import com.tonyisup.poseguidesnap.data.RenameExactToQuarantineResult
import com.tonyisup.poseguidesnap.data.RoomReferenceImportFileJournal
import com.tonyisup.poseguidesnap.data.RoomReferenceImportRepository

/** Exact-token logical authority used only to match and settle startup recovery work. */
interface ReferenceImportRecoveryAuthorityPort {
    fun findExactIntent(importToken: ReferenceImportToken): PendingReferenceImport?

    fun settleFailure(
        importToken: ReferenceImportToken,
        settlement: ReferenceImportFailureSettlement,
        settledAtEpochMillis: Long,
    ): ReferenceImportSettlementResult
}

/** Persisted file ledger is the sole startup enumeration and retry authority. */
interface ReferenceImportRecoveryJournalPort {
    fun findRetryableOperations(): List<ReferenceImportFileOperationSnapshot>

    fun snapshot(importToken: ReferenceImportToken): ReferenceImportFileOperationSnapshot?

    fun advance(request: ReferenceImportFileAdvanceRequest): ReferenceImportFileJournalResult

    fun markReconciliationRequired(
        request: ReferenceImportFileReconciliationRequest,
    ): ReferenceImportFileJournalResult

    fun clearReconciliationRequired(
        request: ReferenceImportFileReconciliationResolutionRequest,
    ): ReferenceImportFileJournalResult
}

/** Exact staged filesystem effects; no scan, provider read, or process-local capability is exposed. */
interface ReferenceImportRecoveryAssetPort {
    fun deleteExactForCleanup(
        identity: ReferenceAssetIdentity,
        operation: ReferenceImportFileOperationSnapshot,
    ): DeleteExactForCleanupResult

    fun syncAndVerifyCleaned(
        identity: ReferenceAssetIdentity,
    ): JournaledReferenceAssetVerificationResult

    fun renameExactToQuarantine(
        identity: ReferenceAssetIdentity,
        operation: ReferenceImportFileOperationSnapshot,
        evidence: JournaledReferenceAssetEvidence,
    ): RenameExactToQuarantineResult

    fun syncAndVerifyQuarantined(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): JournaledReferenceAssetVerificationResult
}

/** Caller-owned wall-clock values for one recovery operation; no clock or arithmetic is hidden. */
class ReferenceImportRecoveryTimeline(
    val cleanupRequiredAtEpochMillis: Long,
    val cleanupPendingSyncAtEpochMillis: Long,
    val cleanedDurableAtEpochMillis: Long,
    val quarantineRequiredAtEpochMillis: Long,
    val quarantinePendingSyncAtEpochMillis: Long,
    val quarantineDurableAtEpochMillis: Long,
    val reconciliationMarkedAtEpochMillis: Long,
    val logicalSettlementAtEpochMillis: Long,
) {
    private val orderedTimestamps = listOf(
        cleanupRequiredAtEpochMillis,
        cleanupPendingSyncAtEpochMillis,
        cleanedDurableAtEpochMillis,
        quarantineRequiredAtEpochMillis,
        quarantinePendingSyncAtEpochMillis,
        quarantineDurableAtEpochMillis,
        reconciliationMarkedAtEpochMillis,
        logicalSettlementAtEpochMillis,
    )

    init {
        require(orderedTimestamps.first() >= 0L && orderedTimestamps.zipWithNext().all { (first, second) ->
            first < second
        }) { "reference import recovery timestamps must be nonnegative and strictly increasing" }
    }

    internal fun isStrictlyNewerThan(updatedAtEpochMillis: Long): Boolean =
        orderedTimestamps.all { timestamp -> timestamp > updatedAtEpochMillis }

    override fun toString(): String = "ReferenceImportRecoveryTimeline(redacted)"
}

/** Privacy-safe aggregate; no token, path, URI, content evidence, or raw failure is retained. */
data class ReferenceImportStartupReconciliationReport(
    val examinedCount: Int,
    val cleanedCount: Int,
    val quarantinedCount: Int,
    val outstandingCount: Int,
    val settlementFailureCount: Int,
    val ledgerReadFailed: Boolean,
) {
    init {
        listOf(
            examinedCount,
            cleanedCount,
            quarantinedCount,
            outstandingCount,
            settlementFailureCount,
        ).forEach { count -> require(count >= 0) { "reconciliation counts must be nonnegative" } }
        require(cleanedCount + quarantinedCount + outstandingCount <= examinedCount) {
            "reconciliation outcome counts cannot exceed examined operations"
        }
        require(settlementFailureCount <= outstandingCount) {
            "settlement failures must remain outstanding"
        }
    }

    override fun toString(): String = "ReferenceImportStartupReconciliationReport(redacted)"
}

/**
 * Resumes interrupted reference imports solely from their persisted file-operation stage.
 * Startup never analyzes, commits, scans, reopens a provider, or reconstructs authority by name.
 */
class ReferenceImportStartupReconciler(
    private val authority: ReferenceImportRecoveryAuthorityPort,
    private val journal: ReferenceImportRecoveryJournalPort,
    private val assets: ReferenceImportRecoveryAssetPort,
) {
    fun reconcile(
        timelineForOperation: (ReferenceImportFileOperationSnapshot) -> ReferenceImportRecoveryTimeline,
    ): ReferenceImportStartupReconciliationReport {
        val operations = try {
            journal.findRetryableOperations().toList()
        } catch (_: Exception) {
            return report(ledgerReadFailed = true)
        }

        var cleaned = 0
        var quarantined = 0
        var outstanding = 0
        var settlementFailures = 0

        operations.forEach { operation ->
            val timeline = try {
                timelineForOperation(operation)
            } catch (_: Exception) {
                outstanding += 1
                return@forEach
            }
            if (!timeline.isStrictlyNewerThan(operation.updatedAtEpochMillis)) {
                outstanding += 1
                return@forEach
            }

            val intent = try {
                authority.findExactIntent(operation.importToken)
            } catch (_: Exception) {
                null
            }
            if (intent == null || !intent.matches(operation)) {
                markCurrent(operation, timeline, ReferenceImportFileFailureCode.STATE_MISMATCH)
                outstanding += 1
                return@forEach
            }

            if (intent.lifecycle.isTerminal) {
                if (!intent.isCoherentTerminalFor(operation.stage)) {
                    markCurrent(operation, timeline, ReferenceImportFileFailureCode.STATE_MISMATCH)
                    outstanding += 1
                    return@forEach
                }
                if (intent.lifecycle == ReferenceImportLifecycle.COMMITTED) {
                    if (operation.reconciliationRequired) {
                        outstanding += 1
                    }
                    return@forEach
                }
            }

            val outcome = when (operation.stage) {
                ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
                ReferenceImportFileOperationStage.WRITING_TEMP,
                ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
                ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC,
                ReferenceImportFileOperationStage.CLEANED_DURABLE,
                -> recoverCleanup(operation, timeline)

                ReferenceImportFileOperationStage.TEMP_SYNCED,
                ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
                ReferenceImportFileOperationStage.FINAL_DURABLE,
                ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
                ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
                ReferenceImportFileOperationStage.QUARANTINE_DURABLE,
                -> recoverQuarantine(operation, timeline)
            }

            when (outcome) {
                RecoveryOutcome.CLEANED -> cleaned += 1
                RecoveryOutcome.QUARANTINED -> quarantined += 1
                RecoveryOutcome.OUTSTANDING -> outstanding += 1
                RecoveryOutcome.SETTLEMENT_FAILED -> {
                    outstanding += 1
                    settlementFailures += 1
                }
            }
        }

        return ReferenceImportStartupReconciliationReport(
            examinedCount = operations.size,
            cleanedCount = cleaned,
            quarantinedCount = quarantined,
            outstandingCount = outstanding,
            settlementFailureCount = settlementFailures,
            ledgerReadFailed = false,
        )
    }

    private fun recoverCleanup(
        initial: ReferenceImportFileOperationSnapshot,
        timeline: ReferenceImportRecoveryTimeline,
    ): RecoveryOutcome {
        val identity = ReferenceAssetIdentity(initial.importToken)
        var current = initial

        if (current.stage == ReferenceImportFileOperationStage.EXPECTING_RESERVATION ||
            current.stage == ReferenceImportFileOperationStage.WRITING_TEMP
        ) {
            current = advance(
                source = current,
                target = ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
                transitionedAtEpochMillis = timeline.cleanupRequiredAtEpochMillis,
                byteCount = current.byteCount,
                sha256 = current.sha256,
                timeline = timeline,
                failureCode = ReferenceImportFileFailureCode.STATE_MISMATCH,
            ) ?: return RecoveryOutcome.OUTSTANDING
        }

        if (current.stage == ReferenceImportFileOperationStage.CLEANUP_REQUIRED) {
            val effectSource = if (initial.stage == ReferenceImportFileOperationStage.CLEANUP_REQUIRED) {
                current
            } else {
                initial
            }
            val deletion = try {
                assets.deleteExactForCleanup(identity, effectSource)
            } catch (_: Exception) {
                null
            }
            if (deletion !is DeleteExactForCleanupResult.Deleted) {
                val code = (deletion as? DeleteExactForCleanupResult.Ambiguous)?.code
                    ?: ReferenceImportFileFailureCode.DELETE_FAILED
                markCurrent(current, timeline, code)
                return RecoveryOutcome.OUTSTANDING
            }
            current = advance(
                source = current,
                target = ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC,
                transitionedAtEpochMillis = timeline.cleanupPendingSyncAtEpochMillis,
                byteCount = current.byteCount,
                sha256 = current.sha256,
                timeline = timeline,
                failureCode = ReferenceImportFileFailureCode.DELETE_FAILED,
            ) ?: return RecoveryOutcome.OUTSTANDING
        }

        if (current.stage == ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC) {
            if (initial.stage == ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC) {
                val retryDeletion = try {
                    assets.deleteExactForCleanup(identity, current)
                } catch (_: Exception) {
                    null
                }
                if (retryDeletion is DeleteExactForCleanupResult.Ambiguous &&
                    retryDeletion.code != ReferenceImportFileFailureCode.STATE_MISMATCH
                ) {
                    markCurrent(current, timeline, retryDeletion.code)
                    return RecoveryOutcome.OUTSTANDING
                }
            }
            val verification = try {
                assets.syncAndVerifyCleaned(identity)
            } catch (_: Exception) {
                null
            }
            if (verification !is JournaledReferenceAssetVerificationResult.Verified) {
                val code = (verification as? JournaledReferenceAssetVerificationResult.Failure)?.code
                    ?: ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED
                markCurrent(current, timeline, code)
                return RecoveryOutcome.OUTSTANDING
            }
            current = advance(
                source = current,
                target = ReferenceImportFileOperationStage.CLEANED_DURABLE,
                transitionedAtEpochMillis = timeline.cleanedDurableAtEpochMillis,
                byteCount = null,
                sha256 = null,
                timeline = timeline,
                failureCode = ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
            ) ?: return RecoveryOutcome.OUTSTANDING
        }

        if (current.stage != ReferenceImportFileOperationStage.CLEANED_DURABLE) {
            markCurrent(current, timeline, ReferenceImportFileFailureCode.STATE_MISMATCH)
            return RecoveryOutcome.OUTSTANDING
        }
        return settle(
            current,
            timeline,
            ReferenceImportFailureSettlement.CLEANED,
            RecoveryOutcome.CLEANED,
        )
    }

    private fun recoverQuarantine(
        initial: ReferenceImportFileOperationSnapshot,
        timeline: ReferenceImportRecoveryTimeline,
    ): RecoveryOutcome {
        val identity = ReferenceAssetIdentity(initial.importToken)
        val evidence = initial.toEvidence()
        var current = initial

        if (current.stage == ReferenceImportFileOperationStage.TEMP_SYNCED ||
            current.stage == ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC ||
            current.stage == ReferenceImportFileOperationStage.FINAL_DURABLE
        ) {
            current = advance(
                source = current,
                target = ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
                transitionedAtEpochMillis = timeline.quarantineRequiredAtEpochMillis,
                byteCount = evidence.byteCount,
                sha256 = evidence.sha256,
                timeline = timeline,
                failureCode = ReferenceImportFileFailureCode.STATE_MISMATCH,
            ) ?: return RecoveryOutcome.OUTSTANDING
        }

        if (current.stage == ReferenceImportFileOperationStage.QUARANTINE_REQUIRED) {
            val effectSource = if (initial.stage == ReferenceImportFileOperationStage.QUARANTINE_REQUIRED) {
                current
            } else {
                initial
            }
            val renamed = try {
                assets.renameExactToQuarantine(identity, effectSource, evidence)
            } catch (_: Exception) {
                null
            }
            if (renamed !is RenameExactToQuarantineResult.Moved &&
                renamed !is RenameExactToQuarantineResult.AlreadyMoved
            ) {
                val code = (renamed as? RenameExactToQuarantineResult.Ambiguous)?.code
                    ?: ReferenceImportFileFailureCode.RENAME_FAILED
                markCurrent(current, timeline, code)
                return RecoveryOutcome.OUTSTANDING
            }
            current = advance(
                source = current,
                target = ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
                transitionedAtEpochMillis = timeline.quarantinePendingSyncAtEpochMillis,
                byteCount = evidence.byteCount,
                sha256 = evidence.sha256,
                timeline = timeline,
                failureCode = ReferenceImportFileFailureCode.RENAME_FAILED,
            ) ?: return RecoveryOutcome.OUTSTANDING
        }

        if (current.stage == ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC) {
            if (initial.stage == ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC) {
                val retryRename = try {
                    assets.renameExactToQuarantine(identity, current, evidence)
                } catch (_: Exception) {
                    null
                }
                if (retryRename is RenameExactToQuarantineResult.Ambiguous &&
                    retryRename.code != ReferenceImportFileFailureCode.STATE_MISMATCH
                ) {
                    markCurrent(current, timeline, retryRename.code)
                    return RecoveryOutcome.OUTSTANDING
                }
            }
            val verification = try {
                assets.syncAndVerifyQuarantined(identity, evidence)
            } catch (_: Exception) {
                null
            }
            if (verification !is JournaledReferenceAssetVerificationResult.Verified) {
                val code = (verification as? JournaledReferenceAssetVerificationResult.Failure)?.code
                    ?: ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED
                markCurrent(current, timeline, code)
                return RecoveryOutcome.OUTSTANDING
            }
            current = advance(
                source = current,
                target = ReferenceImportFileOperationStage.QUARANTINE_DURABLE,
                transitionedAtEpochMillis = timeline.quarantineDurableAtEpochMillis,
                byteCount = evidence.byteCount,
                sha256 = evidence.sha256,
                timeline = timeline,
                failureCode = ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
            ) ?: return RecoveryOutcome.OUTSTANDING
        }

        if (current.stage != ReferenceImportFileOperationStage.QUARANTINE_DURABLE) {
            markCurrent(current, timeline, ReferenceImportFileFailureCode.STATE_MISMATCH)
            return RecoveryOutcome.OUTSTANDING
        }
        return settle(
            current,
            timeline,
            ReferenceImportFailureSettlement.QUARANTINED,
            RecoveryOutcome.QUARANTINED,
        )
    }

    private fun settle(
        durable: ReferenceImportFileOperationSnapshot,
        timeline: ReferenceImportRecoveryTimeline,
        settlement: ReferenceImportFailureSettlement,
        success: RecoveryOutcome,
    ): RecoveryOutcome {
        val result = try {
            authority.settleFailure(
                durable.importToken,
                settlement,
                timeline.logicalSettlementAtEpochMillis,
            )
        } catch (_: Exception) {
            null
        }
        if (result !== ReferenceImportSettlementResult.Settled &&
            result !== ReferenceImportSettlementResult.AlreadySettled
        ) {
            markCurrent(durable, timeline, ReferenceImportFileFailureCode.STATE_MISMATCH)
            return RecoveryOutcome.SETTLEMENT_FAILED
        }
        if (durable.reconciliationRequired) {
            val cleared = try {
                journal.clearReconciliationRequired(
                    ReferenceImportFileReconciliationResolutionRequest(
                        importToken = durable.importToken,
                        expectedStage = durable.stage,
                        expectedUpdatedAtEpochMillis = durable.updatedAtEpochMillis,
                        resolvedAtEpochMillis = timeline.logicalSettlementAtEpochMillis,
                    ),
                )
            } catch (_: Exception) {
                null
            }
            if (cleared !is ReferenceImportFileJournalResult.Applied &&
                cleared !is ReferenceImportFileJournalResult.Idempotent
            ) {
                return RecoveryOutcome.OUTSTANDING
            }
        }
        return success
    }

    private fun advance(
        source: ReferenceImportFileOperationSnapshot,
        target: ReferenceImportFileOperationStage,
        transitionedAtEpochMillis: Long,
        byteCount: Long?,
        sha256: String?,
        timeline: ReferenceImportRecoveryTimeline,
        failureCode: ReferenceImportFileFailureCode,
    ): ReferenceImportFileOperationSnapshot? {
        val request = ReferenceImportFileAdvanceRequest(
            importToken = source.importToken,
            expectedStage = source.stage,
            expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
            targetStage = target,
            byteCount = byteCount,
            sha256 = sha256,
            transitionedAtEpochMillis = transitionedAtEpochMillis,
        )
        val result = try {
            journal.advance(request)
        } catch (_: Exception) {
            null
        }
        val advanced = when (result) {
            is ReferenceImportFileJournalResult.Applied -> result.snapshot
            is ReferenceImportFileJournalResult.Idempotent -> result.snapshot
            else -> null
        }
        if (advanced == null || !advanced.matches(request)) {
            markCurrent(source, timeline, failureCode)
            return null
        }
        return advanced
    }

    private fun markCurrent(
        fallback: ReferenceImportFileOperationSnapshot,
        timeline: ReferenceImportRecoveryTimeline,
        failureCode: ReferenceImportFileFailureCode,
    ) {
        val current = try {
            journal.snapshot(fallback.importToken)
        } catch (_: Exception) {
            null
        } ?: fallback
        if (current.importToken != fallback.importToken ||
            timeline.reconciliationMarkedAtEpochMillis <= current.updatedAtEpochMillis
        ) {
            return
        }
        try {
            journal.markReconciliationRequired(
                ReferenceImportFileReconciliationRequest(
                    importToken = current.importToken,
                    expectedStage = current.stage,
                    expectedUpdatedAtEpochMillis = current.updatedAtEpochMillis,
                    failureCode = failureCode,
                    markedAtEpochMillis = timeline.reconciliationMarkedAtEpochMillis,
                ),
            )
        } catch (_: Exception) {
            // The operation remains retryable by its persisted stage even if flag persistence fails.
        }
    }

    private fun report(ledgerReadFailed: Boolean) = ReferenceImportStartupReconciliationReport(
        examinedCount = 0,
        cleanedCount = 0,
        quarantinedCount = 0,
        outstandingCount = 0,
        settlementFailureCount = 0,
        ledgerReadFailed = ledgerReadFailed,
    )

    private enum class RecoveryOutcome {
        CLEANED,
        QUARANTINED,
        OUTSTANDING,
        SETTLEMENT_FAILED,
    }
}

private fun PendingReferenceImport.matches(operation: ReferenceImportFileOperationSnapshot): Boolean =
    importToken == operation.importToken &&
        relativeAssetPath == operation.paths.relativeAssetPath &&
        createdAtEpochMillis == operation.createdAtEpochMillis

private val ReferenceImportLifecycle.isTerminal: Boolean
    get() = when (this) {
        ReferenceImportLifecycle.PREPARING,
        ReferenceImportLifecycle.ASSET_READY,
        -> false
        ReferenceImportLifecycle.COMMITTED,
        ReferenceImportLifecycle.REJECTED_CLEANED,
        ReferenceImportLifecycle.REJECTED_QUARANTINED,
        -> true
    }

private fun PendingReferenceImport.isCoherentTerminalFor(
    stage: ReferenceImportFileOperationStage,
): Boolean = when (lifecycle) {
    ReferenceImportLifecycle.COMMITTED -> stage == ReferenceImportFileOperationStage.FINAL_DURABLE
    ReferenceImportLifecycle.REJECTED_CLEANED -> stage == ReferenceImportFileOperationStage.CLEANED_DURABLE
    ReferenceImportLifecycle.REJECTED_QUARANTINED ->
        stage == ReferenceImportFileOperationStage.QUARANTINE_DURABLE
    ReferenceImportLifecycle.PREPARING,
    ReferenceImportLifecycle.ASSET_READY,
    -> false
}

private fun ReferenceImportFileOperationSnapshot.toEvidence(): JournaledReferenceAssetEvidence =
    JournaledReferenceAssetEvidence(requireNotNull(byteCount), requireNotNull(sha256))

private fun ReferenceImportFileOperationSnapshot.matches(
    request: ReferenceImportFileAdvanceRequest,
): Boolean =
    importToken == request.importToken &&
        stage == request.targetStage &&
        byteCount == request.byteCount &&
        sha256 == request.sha256 &&
        lastFailureCode == null &&
        !reconciliationRequired &&
        updatedAtEpochMillis == request.transitionedAtEpochMillis

internal class RoomReferenceImportRecoveryAuthorityAdapter(
    private val repository: RoomReferenceImportRepository,
) : ReferenceImportRecoveryAuthorityPort {
    override fun findExactIntent(importToken: ReferenceImportToken): PendingReferenceImport? =
        repository.findExactImportForRecovery(importToken)

    override fun settleFailure(
        importToken: ReferenceImportToken,
        settlement: ReferenceImportFailureSettlement,
        settledAtEpochMillis: Long,
    ): ReferenceImportSettlementResult =
        repository.settleFailure(importToken, settlement, settledAtEpochMillis)
}

internal class RoomReferenceImportRecoveryJournalAdapter(
    private val journal: RoomReferenceImportFileJournal,
) : ReferenceImportRecoveryJournalPort {
    override fun findRetryableOperations(): List<ReferenceImportFileOperationSnapshot> =
        journal.findRetryableOperations()

    override fun snapshot(importToken: ReferenceImportToken): ReferenceImportFileOperationSnapshot? =
        journal.snapshot(importToken)

    override fun advance(request: ReferenceImportFileAdvanceRequest): ReferenceImportFileJournalResult =
        journal.advance(request)

    override fun markReconciliationRequired(
        request: ReferenceImportFileReconciliationRequest,
    ): ReferenceImportFileJournalResult = journal.markReconciliationRequired(request)

    override fun clearReconciliationRequired(
        request: ReferenceImportFileReconciliationResolutionRequest,
    ): ReferenceImportFileJournalResult = journal.clearReconciliationRequired(request)
}

internal class JournaledReferenceAssetRecoveryAdapter(
    private val store: JournaledReferenceAssetStore,
) : ReferenceImportRecoveryAssetPort {
    override fun deleteExactForCleanup(
        identity: ReferenceAssetIdentity,
        operation: ReferenceImportFileOperationSnapshot,
    ): DeleteExactForCleanupResult {
        val evidence = operation.evidenceOrNull()
        val candidates = when (operation.stage) {
            ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
            ReferenceImportFileOperationStage.WRITING_TEMP,
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            ReferenceImportFileOperationStage.FINAL_DURABLE,
            ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
            ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
            -> listOf(operation.stage)
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
            ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC,
            -> if (evidence == null) {
                listOf(ReferenceImportFileOperationStage.WRITING_TEMP)
            } else {
                listOf(
                    ReferenceImportFileOperationStage.TEMP_SYNCED,
                    ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
                    ReferenceImportFileOperationStage.FINAL_DURABLE,
                    ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
                    ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
                )
            }
            else -> emptyList()
        }
        return firstNonStateMismatch(candidates) { sourceStage ->
            store.deleteExactForCleanup(identity, sourceStage, evidence)
        } ?: DeleteExactForCleanupResult.Ambiguous(ReferenceImportFileFailureCode.STATE_MISMATCH)
    }

    override fun syncAndVerifyCleaned(
        identity: ReferenceAssetIdentity,
    ): JournaledReferenceAssetVerificationResult = store.syncAndVerifyCleaned(identity)

    override fun renameExactToQuarantine(
        identity: ReferenceAssetIdentity,
        operation: ReferenceImportFileOperationSnapshot,
        evidence: JournaledReferenceAssetEvidence,
    ): RenameExactToQuarantineResult {
        val candidates = when (operation.stage) {
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
            ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
            -> listOf(
                ReferenceImportFileOperationStage.TEMP_SYNCED,
                ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            )
            ReferenceImportFileOperationStage.FINAL_DURABLE ->
                listOf(ReferenceImportFileOperationStage.FINAL_DURABLE)
            else -> emptyList()
        }
        return firstNonStateMismatch(candidates) { sourceStage ->
            store.renameExactToQuarantine(identity, sourceStage, evidence)
        } ?: RenameExactToQuarantineResult.Ambiguous(ReferenceImportFileFailureCode.STATE_MISMATCH)
    }

    override fun syncAndVerifyQuarantined(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): JournaledReferenceAssetVerificationResult = store.syncAndVerifyQuarantined(identity, evidence)

    private fun <T> firstNonStateMismatch(
        candidates: List<ReferenceImportFileOperationStage>,
        action: (ReferenceImportFileOperationStage) -> T,
    ): T? where T : Any = candidates.asSequence()
        .map(action)
        .firstOrNull { result ->
            when (result) {
                is DeleteExactForCleanupResult.Ambiguous ->
                    result.code != ReferenceImportFileFailureCode.STATE_MISMATCH
                is RenameExactToQuarantineResult.Ambiguous ->
                    result.code != ReferenceImportFileFailureCode.STATE_MISMATCH
                else -> true
            }
        }
}

private fun ReferenceImportFileOperationSnapshot.evidenceOrNull(): JournaledReferenceAssetEvidence? =
    if (byteCount != null && sha256 != null) JournaledReferenceAssetEvidence(byteCount, sha256) else null
