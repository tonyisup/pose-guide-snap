package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.data.CaptureFileFailureCode
import com.tonyisup.poseguidesnap.data.CaptureFileOperationPaths
import com.tonyisup.poseguidesnap.data.CaptureFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournaledPrivateCaptureStoreTest {
    @Test
    fun journaledClaimSyncAndPublicationUsesExactNoClobberIdentity() = withRoot { root ->
        val identity = identity(1)
        val store = JournaledPrivateCaptureStore(root.toFile())
        val claim = store.claimForWrite(snapshot(identity, CaptureFileOperationStage.WRITING_TEMP, 2))
            as CaptureFileClaimResult.Claimed
        val bytes = "exact-journaled-candidate".toByteArray()
        claim.lease.tempFile.writeBytes(bytes)

        val synced = store.syncCapturedTemp(
            snapshot = snapshot(identity, CaptureFileOperationStage.WRITING_TEMP, 2),
            lease = claim.lease,
            capturedAtEpochMillis = 3,
        ) as CaptureTempSyncResult.Synced
        val pending = snapshot(
            identity = identity,
            stage = CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            updatedAt = 5,
            evidence = synced.evidence,
        )

        assertEquals(
            CaptureFinalPublicationResult.Published(synced.evidence)::class,
            store.publishFinal(pending, claim.lease, synced.evidence)::class,
        )
        val paths = CaptureFileOperationPaths.forIdentity(identity)
        val final = root.resolve(paths.relativeFinalPath)
        val temp = root.resolve(paths.relativeTempPath)
        assertArrayEquals(bytes, final.readBytes())
        assertFalse(Files.exists(temp, LinkOption.NOFOLLOW_LINKS))

        val replayClaim = store.claimForWrite(
            snapshot(identity, CaptureFileOperationStage.WRITING_TEMP, 6),
        )
        assertTrue(replayClaim is CaptureFileClaimResult.Ambiguous)
        assertArrayEquals(bytes, final.readBytes())
        assertFalse(store.toString().contains(identity.token.value))
    }

    @Test
    fun cleanupDeletesOnlyExactAdmittedFilesAndPreservesSibling() = withRoot { root ->
        val identity = identity(0)
        val paths = CaptureFileOperationPaths.forIdentity(identity)
        val final = root.resolve(paths.relativeFinalPath)
        val temp = root.resolve(paths.relativeTempPath)
        val unrelated = root.resolve("capture-candidates/unrelated.jpg")
        Files.createDirectories(final.parent)
        final.writeBytes(byteArrayOf())
        temp.writeBytes("interrupted-camera-write".toByteArray())
        unrelated.writeBytes("keep".toByteArray())

        val result = JournaledPrivateCaptureStore(root.toFile()).cleanupExact(
            snapshot(identity, CaptureFileOperationStage.CLEANUP_PENDING_SYNC, 4),
        )

        assertEquals(CaptureExactCleanupResult.Cleaned, result)
        assertFalse(Files.exists(final, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(temp, LinkOption.NOFOLLOW_LINKS))
        assertArrayEquals("keep".toByteArray(), unrelated.readBytes())
    }

    @Test
    fun cleanupRetainsUnexpectedPositiveFinalWithoutEvidence() = withRoot { root ->
        val identity = identity(2)
        val paths = CaptureFileOperationPaths.forIdentity(identity)
        val final = root.resolve(paths.relativeFinalPath)
        Files.createDirectories(final.parent)
        val unexpected = "unproven-final".toByteArray()
        final.writeBytes(unexpected)

        val result = JournaledPrivateCaptureStore(root.toFile()).cleanupExact(
            snapshot(identity, CaptureFileOperationStage.CLEANUP_PENDING_SYNC, 4),
        ) as CaptureExactCleanupResult.Ambiguous

        assertEquals(CaptureFileFailureCode.EVIDENCE_MISMATCH, result.failureCode)
        assertArrayEquals(unexpected, final.readBytes())
    }

    @Test
    fun cleanupRetriesAnAlreadyFlaggedPendingDeletion() = withRoot { root ->
        val identity = identity(0)
        val paths = CaptureFileOperationPaths.forIdentity(identity)
        val temp = root.resolve(paths.relativeTempPath)
        Files.createDirectories(temp.parent)
        temp.writeBytes("retry-pending-cleanup".toByteArray())

        val result = JournaledPrivateCaptureStore(root.toFile()).cleanupExact(
            snapshot(identity, CaptureFileOperationStage.CLEANUP_PENDING_SYNC, 4).copy(
                lastFailureCode = CaptureFileFailureCode.DELETE_FAILED,
                reconciliationRequired = true,
            ),
        )

        assertEquals(CaptureExactCleanupResult.Cleaned, result)
        assertFalse(Files.exists(temp, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun journalFreeObservationChecksOnlyThreeDeterministicIdentities() = withRoot { root ->
        val identities = (0..2).map(::identity)
        val store = JournaledPrivateCaptureStore(root.toFile())
        val unrelated = root.resolve("capture-candidates/unrelated.jpg")
        unrelated.writeBytes(byteArrayOf(1))

        assertEquals(
            JournalFreeCapturePathObservation.ALL_ABSENT,
            store.observeJournalFreeAttempt(identities),
        )

        val exact = root.resolve(CaptureFileOperationPaths.forIdentity(identities[1]).relativeTempPath)
        exact.writeBytes(byteArrayOf())
        assertEquals(
            JournalFreeCapturePathObservation.PRESENT_OR_AMBIGUOUS,
            store.observeJournalFreeAttempt(identities),
        )
        assertTrue(Files.exists(unrelated))
    }

    private fun snapshot(
        identity: PrivateOutputIdentity,
        stage: CaptureFileOperationStage,
        updatedAt: Long,
        evidence: JournaledCaptureEvidence? = null,
    ) = CaptureFileOperationSnapshot(
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

    private fun identity(ordinal: Int) =
        PrivateOutputIdentity(CaptureToken("journaled-store-token"), ordinal)

    private inline fun withRoot(block: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("journaled-private-capture-store")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
