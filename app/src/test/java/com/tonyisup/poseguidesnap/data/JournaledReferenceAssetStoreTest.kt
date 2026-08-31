package com.tonyisup.poseguidesnap.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import com.tonyisup.poseguidesnap.importer.JournaledReferenceAssetRecoveryAdapter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JournaledReferenceAssetStoreTest {
    @Test
    fun splitClaimThenWriteMakesWritingStageMeanBothFilesWereAdmitted() {
        val token = "split-claim-write"
        val bytes = "split-journal-compatible-bytes".toByteArray()
        val ops = StagedFakeReferenceAssetFileOps()
        val store = store(ops)

        assertSame(
            ClaimReferenceAssetFilesResult.Claimed,
            store.claimReservationAndTemp(identity(token)),
        )
        assertArrayEquals(ByteArray(0), ops.bytesAt(finalPath(token)))
        assertArrayEquals(ByteArray(0), ops.bytesAt(tempPath(token)))
        assertFalse(ops.events.any { it.startsWith("open-owned:") })

        val written = store.writeAndSyncClaimedTemp(identity(token), source(bytes))
        assertTrue(written is WriteAndSyncTempResult.TempSynced)
        written as WriteAndSyncTempResult.TempSynced
        assertEquals(evidence(bytes), written.evidence)
        assertArrayEquals(bytes, ops.bytesAt(tempPath(token)))
    }

    @Test
    fun claimCollisionNeverAuthorizesWritingOrDeletionOfPreexistingTemp() {
        val token = "split-foreign-temp"
        val foreign = "foreign-temp".toByteArray()
        val ops = StagedFakeReferenceAssetFileOps().apply {
            precreate(tempPath(token), foreign)
        }
        val store = store(ops)

        val claim = store.claimReservationAndTemp(identity(token))
        assertTrue(claim is ClaimReferenceAssetFilesResult.Ambiguous)
        claim as ClaimReferenceAssetFilesResult.Ambiguous
        assertEquals(ReferenceImportFileFailureCode.STATE_MISMATCH, claim.code)
        assertTrue(claim.cleanupRequired)
        assertArrayEquals(foreign, ops.bytesAt(tempPath(token)))
        assertFalse(ops.events.any { it.startsWith("delete:") || it.startsWith("open-owned:") })

        val cleanup = store.deleteExactForCleanup(
            identity(token),
            ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
        )
        assertTrue(cleanup is DeleteExactForCleanupResult.Ambiguous)
        assertArrayEquals(foreign, ops.bytesAt(tempPath(token)))
        assertFalse(ops.events.any { it.startsWith("delete:") })
    }

    @Test
    fun writeAndSyncTempReservesExactNamesBoundsWritesSyncsAndReturnsEvidence() {
        val ops = StagedFakeReferenceAssetFileOps()
        val bytes = "journal-compatible-bytes".toByteArray()

        val result = store(ops).writeAndSyncTemp(identity("write-success"), source(bytes))

        assertTrue(result is WriteAndSyncTempResult.TempSynced)
        result as WriteAndSyncTempResult.TempSynced
        assertEquals(JournaledReferenceAssetEvidence(bytes.size.toLong(), sha256(bytes)), result.evidence)
        assertArrayEquals(ByteArray(0), ops.bytesAt(finalPath("write-success")))
        assertArrayEquals(bytes, ops.bytesAt(tempPath("write-success")))
        assertEquals(
            listOf(
                "prepare-directories",
                "reserve:${finalPath("write-success").fileName}",
                "create-temp:${tempPath("write-success").fileName}",
                "open-owned:${tempPath("write-success").fileName}",
                "sync-file:${tempPath("write-success").fileName}",
                "observe:${tempPath("write-success").fileName}",
                "hash:${tempPath("write-success").fileName}",
            ),
            ops.events,
        )
        assertFalse(ops.events.any { it.startsWith("delete:") || it.startsWith("sync-directory:") })
    }

    @Test
    fun writeCollisionNeverClobbersForeignFinalOrTemp() {
        val finalOps = StagedFakeReferenceAssetFileOps()
        finalOps.precreate(finalPath("foreign-final"), "foreign-final".toByteArray())

        val finalResult = store(finalOps).writeAndSyncTemp(
            identity("foreign-final"),
            source("candidate".toByteArray()),
        )

        assertWriteFailure(finalResult, ReferenceImportFileFailureCode.STATE_MISMATCH, false)
        assertArrayEquals("foreign-final".toByteArray(), finalOps.bytesAt(finalPath("foreign-final")))
        assertFalse(finalOps.exists(tempPath("foreign-final")))

        val tempOps = StagedFakeReferenceAssetFileOps()
        tempOps.precreate(tempPath("foreign-temp"), "foreign-temp".toByteArray())

        val tempResult = store(tempOps).writeAndSyncTemp(
            identity("foreign-temp"),
            source("candidate".toByteArray()),
        )

        assertWriteFailure(tempResult, ReferenceImportFileFailureCode.STATE_MISMATCH, true)
        assertArrayEquals(ByteArray(0), tempOps.bytesAt(finalPath("foreign-temp")))
        assertArrayEquals("foreign-temp".toByteArray(), tempOps.bytesAt(tempPath("foreign-temp")))
        assertFalse(tempOps.events.any { it.startsWith("delete:") })
    }

    @Test
    fun emptyOversizeAndSourceFailureLeaveExactPartialFilesForCoordinatorCleanup() {
        val emptyOps = StagedFakeReferenceAssetFileOps()
        val empty = store(emptyOps).writeAndSyncTemp(identity("empty"), source(ByteArray(0)))
        assertWriteFailure(empty, ReferenceImportFileFailureCode.WRITE_FAILED, true)
        assertArrayEquals(ByteArray(0), emptyOps.bytesAt(finalPath("empty")))
        assertArrayEquals(ByteArray(0), emptyOps.bytesAt(tempPath("empty")))

        val oversizeOps = StagedFakeReferenceAssetFileOps()
        val oversize = store(oversizeOps, maxEncodedBytes = 3L).writeAndSyncTemp(
            identity("oversize"),
            source(byteArrayOf(1, 2, 3, 4, 5)),
        )
        assertWriteFailure(oversize, ReferenceImportFileFailureCode.WRITE_FAILED, true)
        assertArrayEquals(byteArrayOf(1, 2, 3), oversizeOps.bytesAt(tempPath("oversize")))
        assertEquals(3, oversizeOps.maximumObservedByteCount)

        val sourceOps = StagedFakeReferenceAssetFileOps()
        val secret = "content://private/source"
        val failingSource = ReferenceAssetByteSource {
            object : InputStream() {
                private var readCount = 0
                override fun read(): Int = error("bulk read expected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (readCount++ > 0) throw IOException(secret)
                    buffer[offset] = 0x2a
                    return 1
                }
            }
        }
        val sourceFailure = store(sourceOps).writeAndSyncTemp(identity("source-failure"), failingSource)
        assertWriteFailure(sourceFailure, ReferenceImportFileFailureCode.WRITE_FAILED, true)
        assertArrayEquals(byteArrayOf(0x2a), sourceOps.bytesAt(tempPath("source-failure")))
        assertFalse(sourceFailure.toString().contains(secret))

        listOf(emptyOps, oversizeOps, sourceOps).forEach { ops ->
            assertFalse(ops.events.any { it.startsWith("delete:") || it.contains("quarantine") })
        }
    }

    @Test
    fun tempSyncOrReobservationFailureNeverClaimsCleaned() {
        val syncOps = StagedFakeReferenceAssetFileOps().apply { failNext(StagedFailurePoint.SYNC_FILE) }
        val syncResult = store(syncOps).writeAndSyncTemp(identity("sync-failure"), source(byteArrayOf(1)))
        assertWriteFailure(syncResult, ReferenceImportFileFailureCode.FILE_SYNC_FAILED, true)
        assertTrue(syncOps.exists(tempPath("sync-failure")))

        val observeOps = StagedFakeReferenceAssetFileOps().apply { failNext(StagedFailurePoint.OBSERVE) }
        val observeResult = store(observeOps).writeAndSyncTemp(
            identity("observe-failure"),
            source(byteArrayOf(1)),
        )
        assertWriteFailure(observeResult, ReferenceImportFileFailureCode.EVIDENCE_MISMATCH, true)
        assertTrue(observeOps.exists(tempPath("observe-failure")))
        assertFalse(observeResult.toString().contains("CLEANED"))
    }

    @Test
    fun renameSyncedTempVerifiesReservationAndExactEvidenceWithoutDirectorySync() {
        val ops = StagedFakeReferenceAssetFileOps()
        val token = "rename-success"
        val bytes = "synced-temp".toByteArray()
        ops.precreate(finalPath(token), ByteArray(0))
        ops.precreate(tempPath(token), bytes, synced = true)

        val result = store(ops).renameSyncedTemp(identity(token), evidence(bytes))

        assertSame(RenameSyncedTempResult.Renamed, result)
        assertArrayEquals(bytes, ops.bytesAt(finalPath(token)))
        assertFalse(ops.exists(tempPath(token)))
        assertFalse(ops.events.any { it.startsWith("sync-directory:") })
    }

    @Test
    fun renameRejectsHashMismatchNonregularAndForeignReservationWithoutMutation() {
        val hashOps = stagedRenameShape("rename-hash", "actual".toByteArray())
        val hashResult = store(hashOps).renameSyncedTemp(
            identity("rename-hash"),
            evidence("different".toByteArray()),
        )
        assertAmbiguous(hashResult, ReferenceImportFileFailureCode.EVIDENCE_MISMATCH)
        assertTrue(hashOps.exists(tempPath("rename-hash")))

        val nonregularOps = StagedFakeReferenceAssetFileOps()
        nonregularOps.precreate(finalPath("rename-nonregular"), ByteArray(0))
        nonregularOps.precreateNonregular(tempPath("rename-nonregular"))
        val nonregular = store(nonregularOps).renameSyncedTemp(
            identity("rename-nonregular"),
            evidence(byteArrayOf(1)),
        )
        assertAmbiguous(nonregular, ReferenceImportFileFailureCode.STATE_MISMATCH)
        assertFalse(nonregularOps.events.any { it.startsWith("replace:") })

        val reservationOps = stagedRenameShape("rename-reservation", byteArrayOf(1))
        reservationOps.replaceWithForeign(finalPath("rename-reservation"), byteArrayOf(9))
        val reservation = store(reservationOps).renameSyncedTemp(
            identity("rename-reservation"),
            evidence(byteArrayOf(1)),
        )
        assertAmbiguous(reservation, ReferenceImportFileFailureCode.STATE_MISMATCH)
        assertArrayEquals(byteArrayOf(9), reservationOps.bytesAt(finalPath("rename-reservation")))
        assertArrayEquals(byteArrayOf(1), reservationOps.bytesAt(tempPath("rename-reservation")))
    }

    @Test
    fun renameAfterMoveFailureIsAmbiguousAndRetryReportsAlreadyRenamed() {
        val token = "rename-ambiguous"
        val bytes = "renamed-before-error".toByteArray()
        val ops = stagedRenameShape(token, bytes).apply {
            failNext(StagedFailurePoint.RENAME_AFTER_MOVE)
        }
        val store = store(ops)

        val first = store.renameSyncedTemp(identity(token), evidence(bytes))
        val retry = store.renameSyncedTemp(identity(token), evidence(bytes))

        assertAmbiguous(first, ReferenceImportFileFailureCode.RENAME_FAILED)
        assertSame(RenameSyncedTempResult.AlreadyRenamed, retry)
        assertArrayEquals(bytes, ops.bytesAt(finalPath(token)))
        assertFalse(ops.exists(tempPath(token)))
    }

    @Test
    fun syncAndVerifyFinalSyncsAssetsThenRequiresExactFinalAndAbsentTemp() {
        val token = "verify-final"
        val bytes = "final".toByteArray()
        val ops = StagedFakeReferenceAssetFileOps()
        ops.precreate(finalPath(token), bytes, synced = true)

        val verified = store(ops).syncAndVerifyFinal(identity(token), evidence(bytes))

        assertSame(JournaledReferenceAssetVerificationResult.Verified, verified)
        assertEquals("sync-directory:assets", ops.events[1])

        ops.precreate(tempPath(token), byteArrayOf(1))
        val contradictory = store(ops).syncAndVerifyFinal(identity(token), evidence(bytes))
        assertVerificationFailure(contradictory, ReferenceImportFileFailureCode.STATE_MISMATCH)

        val syncOps = StagedFakeReferenceAssetFileOps().apply {
            precreate(finalPath("verify-sync-failure"), bytes, synced = true)
            failNext(StagedFailurePoint.DIRECTORY_SYNC)
        }
        val syncFailure = store(syncOps).syncAndVerifyFinal(
            identity("verify-sync-failure"),
            evidence(bytes),
        )
        assertVerificationFailure(syncFailure, ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED)
    }

    @Test
    fun writingTempCleanupDeletesPartialRegularTempAndZeroReservationWithoutHashOrSync() {
        val token = "cleanup-partial"
        val ops = StagedFakeReferenceAssetFileOps()
        ops.precreate(finalPath(token), ByteArray(0))
        ops.precreate(tempPath(token), "dirty-partial".toByteArray())

        val result = store(ops).deleteExactForCleanup(
            identity(token),
            ReferenceImportFileOperationStage.WRITING_TEMP,
        )

        assertSame(DeleteExactForCleanupResult.Deleted, result)
        assertFalse(ops.exists(finalPath(token)))
        assertFalse(ops.exists(tempPath(token)))
        assertFalse(ops.events.any { it.startsWith("hash:") || it.startsWith("sync-directory:") })
    }

    @Test
    fun syncedCleanupRequiresEvidenceBeforeDeletingAnyNonemptySource() {
        val token = "cleanup-synced"
        val bytes = "synced".toByteArray()
        val ops = stagedRenameShape(token, bytes)

        val mismatch = store(ops).deleteExactForCleanup(
            identity(token),
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            evidence("wrong!".toByteArray()),
        )

        assertCleanupAmbiguous(mismatch, ReferenceImportFileFailureCode.EVIDENCE_MISMATCH)
        assertArrayEquals(ByteArray(0), ops.bytesAt(finalPath(token)))
        assertArrayEquals(bytes, ops.bytesAt(tempPath(token)))
        assertFalse(ops.events.any { it.startsWith("delete:") })

        val deleted = store(ops).deleteExactForCleanup(
            identity(token),
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            evidence(bytes),
        )
        assertSame(DeleteExactForCleanupResult.Deleted, deleted)
        assertFalse(ops.exists(finalPath(token)))
        assertFalse(ops.exists(tempPath(token)))
    }

    @Test
    fun cleanupRejectsForeignQuarantineAndNonregularBeforeAnyDelete() {
        val token = "cleanup-foreign-quarantine"
        val bytes = "synced".toByteArray()
        val quarantineOps = stagedRenameShape(token, bytes)
        quarantineOps.precreate(quarantinePath(token), "foreign".toByteArray())

        val collision = store(quarantineOps).deleteExactForCleanup(
            identity(token),
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            evidence(bytes),
        )

        assertCleanupAmbiguous(collision, ReferenceImportFileFailureCode.STATE_MISMATCH)
        assertFalse(quarantineOps.events.any { it.startsWith("delete:") })
        assertArrayEquals("foreign".toByteArray(), quarantineOps.bytesAt(quarantinePath(token)))

        val nonregularOps = StagedFakeReferenceAssetFileOps()
        nonregularOps.precreateNonregular(tempPath("cleanup-nonregular"))
        val nonregular = store(nonregularOps).deleteExactForCleanup(
            identity("cleanup-nonregular"),
            ReferenceImportFileOperationStage.WRITING_TEMP,
        )
        assertCleanupAmbiguous(nonregular, ReferenceImportFileFailureCode.STATE_MISMATCH)
        assertFalse(nonregularOps.events.any { it.startsWith("delete:") })
    }

    @Test
    fun cleanupPendingSyncSyncsBothDirectoriesAndReobservesAllThreeExactPaths() {
        val ops = StagedFakeReferenceAssetFileOps()
        val token = "verify-cleaned"

        val result = store(ops).syncAndVerifyCleaned(identity(token))

        assertSame(JournaledReferenceAssetVerificationResult.Verified, result)
        assertEquals(
            listOf(
                "prepare-directories",
                "sync-directory:assets",
                "sync-directory:quarantine",
                "observe:${finalPath(token).fileName}",
                "observe:${tempPath(token).fileName}",
                "observe:${quarantinePath(token).fileName}",
            ),
            ops.events,
        )

        val foreignOps = StagedFakeReferenceAssetFileOps()
        foreignOps.precreate(quarantinePath(token), byteArrayOf(1))
        assertVerificationFailure(
            store(foreignOps).syncAndVerifyCleaned(identity(token)),
            ReferenceImportFileFailureCode.STATE_MISMATCH,
        )
    }

    @Test
    fun quarantineTempSyncsAndVerifiesSourceThenMovesWithoutClobberOrDirectorySync() {
        val token = "quarantine-temp"
        val bytes = "retain-me".toByteArray()
        val ops = stagedRenameShape(token, bytes)

        val result = store(ops).renameExactToQuarantine(
            identity(token),
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            evidence(bytes),
        )

        assertSame(RenameExactToQuarantineResult.Moved, result)
        assertFalse(ops.exists(finalPath(token)))
        assertFalse(ops.exists(tempPath(token)))
        assertArrayEquals(bytes, ops.bytesAt(quarantinePath(token)))
        val syncIndex = ops.events.indexOf("sync-file:${tempPath(token).fileName}")
        val replaceIndex = ops.events.indexOf(
            "replace:${tempPath(token).fileName}->${quarantinePath(token).fileName}",
        )
        assertTrue(syncIndex >= 0 && replaceIndex > syncIndex)
        assertFalse(ops.events.any { it.startsWith("sync-directory:") })
    }

    @Test
    fun quarantineFinalSupportsPendingAndDurableStagesButRejectsOtherStages() {
        listOf(
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            ReferenceImportFileOperationStage.FINAL_DURABLE,
        ).forEachIndexed { index, stage ->
            val token = "quarantine-final-$index"
            val bytes = "final-$index".toByteArray()
            val ops = StagedFakeReferenceAssetFileOps()
            ops.precreate(finalPath(token), bytes, synced = true)
            assertSame(
                RenameExactToQuarantineResult.Moved,
                store(ops).renameExactToQuarantine(identity(token), stage, evidence(bytes)),
            )
            assertArrayEquals(bytes, ops.bytesAt(quarantinePath(token)))
        }

        val invalidOps = StagedFakeReferenceAssetFileOps()
        val invalid = store(invalidOps).renameExactToQuarantine(
            identity("invalid-quarantine-stage"),
            ReferenceImportFileOperationStage.WRITING_TEMP,
            evidence(byteArrayOf(1)),
        )
        assertQuarantineAmbiguous(invalid, ReferenceImportFileFailureCode.STATE_MISMATCH)
        assertFalse(invalidOps.events.any { it.startsWith("reserve:") || it.startsWith("replace:") })
    }

    @Test
    fun quarantineCollisionHashMismatchAndNonregularNeverClobber() {
        val token = "quarantine-collision"
        val bytes = "source".toByteArray()
        val collisionOps = stagedRenameShape(token, bytes)
        collisionOps.precreate(quarantinePath(token), "foreign-quarantine".toByteArray())
        val collision = store(collisionOps).renameExactToQuarantine(
            identity(token),
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            evidence(bytes),
        )
        assertQuarantineAmbiguous(collision, ReferenceImportFileFailureCode.STATE_MISMATCH)
        assertArrayEquals(bytes, collisionOps.bytesAt(tempPath(token)))
        assertArrayEquals("foreign-quarantine".toByteArray(), collisionOps.bytesAt(quarantinePath(token)))

        val hashOps = stagedRenameShape("quarantine-hash", bytes)
        val hash = store(hashOps).renameExactToQuarantine(
            identity("quarantine-hash"),
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            evidence("wrong!".toByteArray()),
        )
        assertQuarantineAmbiguous(hash, ReferenceImportFileFailureCode.EVIDENCE_MISMATCH)
        assertFalse(hashOps.events.any { it.startsWith("reserve:") })

        val nonregularOps = StagedFakeReferenceAssetFileOps()
        nonregularOps.precreateNonregular(finalPath("quarantine-nonregular"))
        val nonregular = store(nonregularOps).renameExactToQuarantine(
            identity("quarantine-nonregular"),
            ReferenceImportFileOperationStage.FINAL_DURABLE,
            evidence(byteArrayOf(1)),
        )
        assertQuarantineAmbiguous(nonregular, ReferenceImportFileFailureCode.STATE_MISMATCH)
    }

    @Test
    fun quarantineRenameAmbiguityRetriesAsAlreadyMovedAfterExactEvidenceVerification() {
        val token = "quarantine-retry"
        val bytes = "already-moved".toByteArray()
        val ops = stagedRenameShape(token, bytes).apply {
            failNext(StagedFailurePoint.RENAME_AFTER_MOVE)
        }
        val store = store(ops)

        val first = store.renameExactToQuarantine(
            identity(token),
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            evidence(bytes),
        )
        val retry = store.renameExactToQuarantine(
            identity(token),
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            evidence(bytes),
        )

        assertQuarantineAmbiguous(first, ReferenceImportFileFailureCode.RENAME_FAILED)
        assertSame(RenameExactToQuarantineResult.AlreadyMoved, retry)
        assertArrayEquals(bytes, ops.bytesAt(quarantinePath(token)))
    }

    @Test
    fun quarantinePendingSyncClosesDirtyByteWindowBySyncingBothDirectoriesAndVerifyingEvidence() {
        val token = "verify-quarantine"
        val bytes = "durable-quarantine".toByteArray()
        val ops = StagedFakeReferenceAssetFileOps()
        ops.precreate(quarantinePath(token), bytes, synced = true)

        val verified = store(ops).syncAndVerifyQuarantined(identity(token), evidence(bytes))

        assertSame(JournaledReferenceAssetVerificationResult.Verified, verified)
        assertEquals(listOf("sync-directory:assets", "sync-directory:quarantine"), ops.events.slice(1..2))

        val mismatchOps = StagedFakeReferenceAssetFileOps()
        mismatchOps.precreate(quarantinePath(token), "other".toByteArray(), synced = true)
        assertVerificationFailure(
            store(mismatchOps).syncAndVerifyQuarantined(identity(token), evidence(bytes)),
            ReferenceImportFileFailureCode.EVIDENCE_MISMATCH,
        )

        val syncOps = StagedFakeReferenceAssetFileOps().apply {
            precreate(quarantinePath(token), bytes, synced = true)
            failNext(StagedFailurePoint.DIRECTORY_SYNC)
        }
        assertVerificationFailure(
            store(syncOps).syncAndVerifyQuarantined(identity(token), evidence(bytes)),
            ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
        )
    }

    @Test
    fun recoveryAdapterAcceptsBothRollbackShapesAndRetriesPendingEffects() {
        val renamedToken = "recovery-temp-synced-after-rename"
        val renamedBytes = "renamed-before-ledger-advance".toByteArray()
        val renamedOps = stagedRenameShape(renamedToken, renamedBytes)
        val renamedStore = store(renamedOps)
        assertSame(
            RenameSyncedTempResult.Renamed,
            renamedStore.renameSyncedTemp(identity(renamedToken), evidence(renamedBytes)),
        )
        assertSame(
            RenameExactToQuarantineResult.Moved,
            JournaledReferenceAssetRecoveryAdapter(renamedStore).renameExactToQuarantine(
                identity(renamedToken),
                operation(renamedToken, ReferenceImportFileOperationStage.TEMP_SYNCED, renamedBytes),
                evidence(renamedBytes),
            ),
        )

        val rolledBackToken = "recovery-final-pending-rolled-back"
        val rolledBackBytes = "temp-restored-after-crash".toByteArray()
        val rolledBackOps = stagedRenameShape(rolledBackToken, rolledBackBytes)
        assertSame(
            RenameExactToQuarantineResult.Moved,
            JournaledReferenceAssetRecoveryAdapter(store(rolledBackOps)).renameExactToQuarantine(
                identity(rolledBackToken),
                operation(
                    rolledBackToken,
                    ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
                    rolledBackBytes,
                ),
                evidence(rolledBackBytes),
            ),
        )

        val cleanupToken = "recovery-cleanup-pending-reappeared"
        val cleanupOps = stagedRenameShape(cleanupToken, "dirty-reappeared".toByteArray())
        assertSame(
            DeleteExactForCleanupResult.Deleted,
            JournaledReferenceAssetRecoveryAdapter(store(cleanupOps)).deleteExactForCleanup(
                identity(cleanupToken),
                operation(cleanupToken, ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC),
            ),
        )

        val quarantineToken = "recovery-quarantine-pending-reappeared"
        val quarantineBytes = "synced-source-reappeared".toByteArray()
        val quarantineOps = stagedRenameShape(quarantineToken, quarantineBytes)
        assertSame(
            RenameExactToQuarantineResult.Moved,
            JournaledReferenceAssetRecoveryAdapter(store(quarantineOps)).renameExactToQuarantine(
                identity(quarantineToken),
                operation(
                    quarantineToken,
                    ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
                    quarantineBytes,
                ),
                evidence(quarantineBytes),
            ),
        )
    }

    @Test
    fun allPublicResultsAndEvidenceRemainPathTokenAndHashRedacted() {
        val rawToken = "raw-secret-token"
        val rawPath = root.toString()
        val digest = "ab".repeat(32)
        val values = listOf(
            JournaledReferenceAssetEvidence(1L, digest),
            WriteAndSyncTempResult.TempSynced(JournaledReferenceAssetEvidence(1L, digest)),
            WriteAndSyncTempResult.Failure(ReferenceImportFileFailureCode.WRITE_FAILED, true),
            RenameSyncedTempResult.Ambiguous(ReferenceImportFileFailureCode.RENAME_FAILED),
            DeleteExactForCleanupResult.Ambiguous(ReferenceImportFileFailureCode.DELETE_FAILED),
            RenameExactToQuarantineResult.Ambiguous(ReferenceImportFileFailureCode.RENAME_FAILED),
            JournaledReferenceAssetVerificationResult.Failure(
                ReferenceImportFileFailureCode.EVIDENCE_MISMATCH,
            ),
        )

        values.forEach { value ->
            val rendered = value.toString()
            assertFalse(rendered.contains(rawToken))
            assertFalse(rendered.contains(rawPath))
            assertFalse(rendered.contains(digest))
        }
    }

    private fun stagedRenameShape(token: String, bytes: ByteArray): StagedFakeReferenceAssetFileOps =
        StagedFakeReferenceAssetFileOps().apply {
            precreate(finalPath(token), ByteArray(0))
            precreate(tempPath(token), bytes, synced = true)
        }

    private fun store(
        ops: StagedFakeReferenceAssetFileOps,
        maxEncodedBytes: Long = 64L,
    ) = JournaledReferenceAssetStore(root, ops, maxEncodedBytes)

    private fun identity(token: String) = ReferenceAssetIdentity(ReferenceImportToken(token))
    private fun source(bytes: ByteArray) = ReferenceAssetByteSource { ByteArrayInputStream(bytes) }
    private fun evidence(bytes: ByteArray) = JournaledReferenceAssetEvidence(bytes.size.toLong(), sha256(bytes))

    private fun operation(
        token: String,
        stage: ReferenceImportFileOperationStage,
        bytes: ByteArray? = null,
    ) = ReferenceImportFileOperationSnapshot(
        importToken = ReferenceImportToken(token),
        paths = paths(token),
        stage = stage,
        byteCount = bytes?.size?.toLong(),
        sha256 = bytes?.let(::sha256),
        lastFailureCode = null,
        reconciliationRequired = false,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )

    private fun finalPath(token: String): Path = root.resolve(paths(token).relativeAssetPath)
    private fun tempPath(token: String): Path = root.resolve(paths(token).relativeTempPath)
    private fun quarantinePath(token: String): Path = root.resolve(paths(token).relativeQuarantinePath)
    private fun paths(token: String) = ReferenceImportFileOperationPaths.forToken(ReferenceImportToken(token))

    private fun assertWriteFailure(
        result: WriteAndSyncTempResult,
        code: ReferenceImportFileFailureCode,
        cleanupRequired: Boolean,
    ) {
        assertTrue(result is WriteAndSyncTempResult.Failure)
        result as WriteAndSyncTempResult.Failure
        assertEquals(code, result.code)
        assertEquals(cleanupRequired, result.cleanupRequired)
    }

    private fun assertAmbiguous(
        result: RenameSyncedTempResult,
        code: ReferenceImportFileFailureCode,
    ) {
        assertTrue(result is RenameSyncedTempResult.Ambiguous)
        result as RenameSyncedTempResult.Ambiguous
        assertEquals(code, result.code)
    }

    private fun assertCleanupAmbiguous(
        result: DeleteExactForCleanupResult,
        code: ReferenceImportFileFailureCode,
    ) {
        assertTrue(result is DeleteExactForCleanupResult.Ambiguous)
        result as DeleteExactForCleanupResult.Ambiguous
        assertEquals(code, result.code)
    }

    private fun assertQuarantineAmbiguous(
        result: RenameExactToQuarantineResult,
        code: ReferenceImportFileFailureCode,
    ) {
        assertTrue(result is RenameExactToQuarantineResult.Ambiguous)
        result as RenameExactToQuarantineResult.Ambiguous
        assertEquals(code, result.code)
    }

    private fun assertVerificationFailure(
        result: JournaledReferenceAssetVerificationResult,
        code: ReferenceImportFileFailureCode,
    ) {
        assertTrue(result is JournaledReferenceAssetVerificationResult.Failure)
        result as JournaledReferenceAssetVerificationResult.Failure
        assertEquals(code, result.code)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        val root: Path = Paths.get("/safe/no-backup-journaled").toAbsolutePath().normalize()
    }
}

private enum class StagedFailurePoint {
    SYNC_FILE,
    DIRECTORY_SYNC,
    OBSERVE,
    HASH,
    RENAME_BEFORE_MOVE,
    RENAME_AFTER_MOVE,
    DELETE,
}

private class StagedFakeReferenceAssetFileOps : ReferenceAssetFileOps {
    private data class Identity(val serial: Int) : ReferenceAssetFileIdentity
    private data class FakeFile(
        val identity: Identity,
        var bytes: ByteArray = ByteArray(0),
        val regular: Boolean = true,
        var synced: Boolean = false,
    )

    val events = mutableListOf<String>()
    var maximumObservedByteCount = 0
        private set
    private val files = linkedMapOf<Path, FakeFile>()
    private val failures = mutableMapOf<StagedFailurePoint, Int>()
    private var nextSerial = 1

    override fun prepareDirectories(noBackupRoot: Path): ReferenceAssetDirectories {
        events += "prepare-directories"
        return ReferenceAssetDirectories(
            noBackupRoot.resolve("reference-assets/assets").normalize(),
            noBackupRoot.resolve("reference-assets/quarantine").normalize(),
        )
    }

    override fun reserveEmpty(path: Path): ReferenceAssetFileIdentity {
        events += "reserve:${path.fileName}"
        if (files.containsKey(path)) throw ReferenceAssetReservationConflict()
        return Identity(nextSerial++).also { files[path] = FakeFile(it, synced = true) }
    }

    override fun createTemp(path: Path): ReferenceAssetFileIdentity {
        events += "create-temp:${path.fileName}"
        if (files.containsKey(path)) throw ReferenceAssetTempConflict()
        return Identity(nextSerial++).also { files[path] = FakeFile(it) }
    }

    override fun openOwnedForWrite(path: Path, identity: ReferenceAssetFileIdentity): OutputStream {
        events += "open-owned:${path.fileName}"
        val file = requireOwned(path, identity)
        return object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                super.write(value)
                publishPartial()
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                publishPartial()
            }

            override fun close() {
                publishPartial()
                super.close()
            }

            private fun publishPartial() {
                file.bytes = toByteArray()
                maximumObservedByteCount = maxOf(maximumObservedByteCount, size())
            }
        }
    }

    override fun syncOwnedFile(path: Path, identity: ReferenceAssetFileIdentity) {
        events += "sync-file:${path.fileName}"
        val file = requireOwned(path, identity)
        if (consumeFailure(StagedFailurePoint.SYNC_FILE)) throw ReferenceAssetOperationFailed()
        file.synced = true
    }

    override fun ownedRegularByteCount(path: Path, identity: ReferenceAssetFileIdentity): Long? {
        val file = requireOwned(path, identity)
        return if (file.regular) file.bytes.size.toLong() else null
    }

    override fun replaceOwnedReservation(
        sourcePath: Path,
        sourceIdentity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
        destinationPath: Path,
        reservationIdentity: ReferenceAssetFileIdentity,
    ) {
        events += "replace:${sourcePath.fileName}->${destinationPath.fileName}"
        val source = requireOwned(sourcePath, sourceIdentity)
        val reservation = requireOwned(destinationPath, reservationIdentity)
        if (!source.regular || source.bytes.size.toLong() != expectedByteCount ||
            !reservation.regular || reservation.bytes.isNotEmpty()
        ) {
            throw ReferenceAssetOwnershipMismatch()
        }
        if (consumeFailure(StagedFailurePoint.RENAME_BEFORE_MOVE)) throw ReferenceAssetOperationFailed()
        files.remove(sourcePath)
        files[destinationPath] = source
        if (consumeFailure(StagedFailurePoint.RENAME_AFTER_MOVE)) throw ReferenceAssetOperationFailed()
    }

    override fun deleteOwnedFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
    ) {
        events += "delete:${path.fileName}"
        val file = requireOwned(path, identity)
        if (!file.regular || file.bytes.size.toLong() != expectedByteCount) {
            throw ReferenceAssetOwnershipMismatch()
        }
        if (consumeFailure(StagedFailurePoint.DELETE)) throw ReferenceAssetOperationFailed()
        files.remove(path)
    }

    override fun syncDirectory(directory: Path) {
        events += "sync-directory:${directory.fileName}"
        if (consumeFailure(StagedFailurePoint.DIRECTORY_SYNC)) throw ReferenceAssetOperationFailed()
    }

    override fun observeRegularFile(path: Path): ReferenceAssetFileObservation {
        events += "observe:${path.fileName}"
        if (consumeFailure(StagedFailurePoint.OBSERVE)) throw ReferenceAssetOperationFailed()
        val file = files[path] ?: return ReferenceAssetFileObservation.Absent
        if (!file.regular) throw ReferenceAssetOwnershipMismatch()
        return ReferenceAssetFileObservation.Regular(file.identity, file.bytes.size.toLong())
    }

    override fun sha256OwnedFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
    ): String {
        events += "hash:${path.fileName}"
        if (consumeFailure(StagedFailurePoint.HASH)) throw ReferenceAssetOperationFailed()
        val file = requireOwned(path, identity)
        if (!file.regular || file.bytes.size.toLong() != expectedByteCount) {
            throw ReferenceAssetOwnershipMismatch()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(file.bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun precreate(path: Path, bytes: ByteArray, synced: Boolean = false) {
        files[path] = FakeFile(Identity(nextSerial++), bytes.copyOf(), synced = synced)
    }

    fun precreateNonregular(path: Path) {
        files[path] = FakeFile(Identity(nextSerial++), regular = false)
    }

    fun replaceWithForeign(path: Path, bytes: ByteArray) {
        precreate(path, bytes)
    }

    fun failNext(point: StagedFailurePoint) {
        failures[point] = failures.getOrDefault(point, 0) + 1
    }

    fun exists(path: Path): Boolean = files.containsKey(path)
    fun bytesAt(path: Path): ByteArray = requireNotNull(files[path]).bytes.copyOf()

    private fun requireOwned(path: Path, identity: ReferenceAssetFileIdentity): FakeFile {
        val file = files[path] ?: throw ReferenceAssetOwnershipMismatch()
        if (file.identity != identity) throw ReferenceAssetOwnershipMismatch()
        return file
    }

    private fun consumeFailure(point: StagedFailurePoint): Boolean {
        val remaining = failures.getOrDefault(point, 0)
        if (remaining == 0) return false
        failures[point] = remaining - 1
        return true
    }
}
