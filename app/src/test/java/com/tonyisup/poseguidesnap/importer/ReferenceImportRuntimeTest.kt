package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.ReferenceAssetByteSource
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceImportRuntimeTest {
    @Test
    fun recoveryWaitsForWholeLiveImportAcrossDifferentRuntimeOwners() {
        val importing = CountDownLatch(1)
        val finishImport = CountDownLatch(1)
        val recoveryRequested = CountDownLatch(1)
        val recoveryEntered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val firstOwner = ReferenceImportRuntime(
            importer = {
                importing.countDown()
                check(finishImport.await(3, TimeUnit.SECONDS))
                ReferencePoseImportResult.ReconciliationRequired
            },
            recovery = { clearReport() },
        )
        val nextOwner = ReferenceImportRuntime(
            importer = { error("not used") },
            recovery = { recoveryEntered.countDown(); clearReport() },
        )
        try {
            val import = pool.submit<ReferencePoseImportResult> { firstOwner.importReference(request()) }
            assertTrue(importing.await(3, TimeUnit.SECONDS))
            val repair = pool.submit { recoveryRequested.countDown(); nextOwner.recover() }
            assertTrue(recoveryRequested.await(3, TimeUnit.SECONDS))
            assertFalse(recoveryEntered.await(100, TimeUnit.MILLISECONDS))
            finishImport.countDown()
            import.get(3, TimeUnit.SECONDS)
            repair.get(3, TimeUnit.SECONDS)
            assertEquals(0L, recoveryEntered.count)
        } finally {
            finishImport.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun ledgerReadFailureIsVisibleAndReleasesLockForRetry() {
        var failed = true
        val runtime = ReferenceImportRuntime(
            importer = { error("not used") },
            recovery = { clearReport().copy(ledgerReadFailed = failed) },
        )
        assertThrows(IllegalStateException::class.java) { runtime.recover() }
        failed = false
        runtime.recover()
    }

    @Test
    fun incompleteGlobalRecoveryBlocksEveryEditorUntilRetrySucceeds() {
        var report = ReferenceImportStartupReconciliationReport(1, 0, 0, 1, 0, false)
        val ownerOfAnotherShoot = ReferenceImportRuntime(
            importer = { error("no new import is allowed before recovery") },
            recovery = { report },
        )
        assertThrows(IllegalStateException::class.java) { ownerOfAnotherShoot.recover() }
        report = ReferenceImportStartupReconciliationReport(1, 1, 0, 0, 0, false)
        ownerOfAnotherShoot.recover()
    }

    @Test
    fun recoveryClockFollowsPersistedTimeAndFailsClosedAtOverflow() {
        val rolledBack = referenceRecoveryTimeline(200L, 100L)
        assertEquals(201L, rolledBack.cleanupRequiredAtEpochMillis)
        assertEquals(208L, rolledBack.logicalSettlementAtEpochMillis)
        assertEquals(500L, referenceRecoveryTimeline(200L, 500L).cleanupRequiredAtEpochMillis)
        assertThrows(ArithmeticException::class.java) { referenceRecoveryTimeline(Long.MAX_VALUE, 1L) }
        assertThrows(ArithmeticException::class.java) { referenceRecoveryTimeline(Long.MAX_VALUE - 4L, 1L) }
        assertThrows(IllegalArgumentException::class.java) { referenceRecoveryTimeline(1L, -1L) }
    }

    private fun clearReport() = ReferenceImportStartupReconciliationReport(0, 0, 0, 0, 0, false)

    private fun request() = ReferencePoseImportRequest(
        importToken = ReferenceImportToken("runtime-test"),
        shootId = "shoot-test",
        poseId = "pose-test",
        label = "Reference",
        mirrorAllowed = true,
        timeline = ProductionReferenceImportLedgerTimelineProvider(ReferenceImportRawTimeSource { 100L }).nextTimeline(),
        source = ReferenceAssetByteSource { ByteArrayInputStream(byteArrayOf(1)) },
    )
}
