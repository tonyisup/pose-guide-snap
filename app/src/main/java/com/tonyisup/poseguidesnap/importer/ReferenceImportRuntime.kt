package com.tonyisup.poseguidesnap.importer

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes complete imports and recovery across retained editor owners. Call on an IO worker.
 * The file-store guard protects individual mutations; this guard protects the entire protocol so
 * recovery cannot mistake a live import's intermediate journal stage for abandoned work.
 */
internal class ReferenceImportRuntime(
    private val importer: JournaledReferencePickerImporterPort,
    private val recovery: () -> ReferenceImportStartupReconciliationReport,
    private val executionLock: ReentrantLock = processImportLock,
) : JournaledReferencePickerImporterPort {
    override fun importReference(request: ReferencePoseImportRequest): ReferencePoseImportResult =
        executionLock.withLock { importer.importReference(request) }

    fun recover() {
        executionLock.withLock {
            val report = recovery()
            check(!report.ledgerReadFailed) { "Reference recovery is unavailable" }
            check(report.outstandingCount == 0) { "Reference recovery is incomplete" }
        }
    }

    companion object {
        private val processImportLock = ReentrantLock(true)
    }
}

/** Accounts for persisted future timestamps after clock rollback; overflow remains fail-closed. */
internal fun referenceRecoveryTimeline(afterEpochMillis: Long, nowEpochMillis: Long): ReferenceImportRecoveryTimeline {
    require(afterEpochMillis >= 0L && nowEpochMillis >= 0L) { "Recovery time is invalid" }
    val first = maxOf(Math.addExact(afterEpochMillis, 1L), nowEpochMillis)
    Math.addExact(first, 7L)
    return ReferenceImportRecoveryTimeline(
        first, first + 1L, first + 2L, first + 3L,
        first + 4L, first + 5L, first + 6L, first + 7L,
    )
}
