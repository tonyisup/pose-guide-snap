package com.tonyisup.poseguidesnap.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Comparator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceAssetStoreTest {
    @Test
    fun publicationStreamsHashesSyncsAndAtomicallyPublishesOwnedBytes() {
        val ops = FakeReferenceAssetFileOps()
        val store = store(ops)
        val rawToken = "opaque-secret-provider-token"
        val bytes = "encoded-reference-bytes".toByteArray(StandardCharsets.UTF_8)

        val published = store.publish(identity(rawToken), source(bytes))

        val expectedFinal = finalPath(rawToken)
        val expectedTemp = tempPath(rawToken)
        assertEquals(relativeFinalPath(rawToken), published.safeRelativePath)
        assertEquals(bytes.size.toLong(), published.byteCount)
        assertEquals(sha256(bytes), published.sha256)
        assertArrayEquals(bytes, ops.bytesAt(expectedFinal))
        assertFalse(ops.exists(expectedTemp))
        assertEquals(
            listOf(
                "prepare-directories",
                "reserve:${expectedFinal.fileName}",
                "create-temp:${expectedTemp.fileName}",
                "open-owned:${expectedTemp.fileName}",
                "sync-file:${expectedTemp.fileName}",
                "inspect:${expectedTemp.fileName}",
                "replace:${expectedTemp.fileName}->${expectedFinal.fileName}",
                "sync-directory:assets",
            ),
            ops.events,
        )
        assertTrue(published.ownershipIdentity.toString().contains("redacted"))
        assertFalse(published.toString().contains(rawToken))
        assertFalse(published.toString().contains(root.toString()))
    }

    @Test
    fun identityStringIsRedacted() {
        val rawToken = "opaque-secret-identity-token"

        val identity = identity(rawToken)

        assertFalse(identity.toString().contains(rawToken))
        assertEquals("ReferenceAssetIdentity(redacted)", identity.toString())
    }

    @Test
    fun emptyInputIsRejectedAndExactOwnedNamesAreCleaned() {
        val ops = FakeReferenceAssetFileOps()
        val rawToken = "opaque-secret-empty-token"

        val failure = assertThrows(ReferenceAssetPublicationFailed::class.java) {
            store(ops).publish(identity(rawToken), source(ByteArray(0)))
        }

        assertEquals(ReferenceAssetFailureStage.EMPTY_INPUT, failure.stage)
        assertFalse(ops.exists(finalPath(rawToken)))
        assertFalse(ops.exists(tempPath(rawToken)))
        assertTrue("delete:${tempPath(rawToken).fileName}" in ops.events)
        assertTrue("delete:${finalPath(rawToken).fileName}" in ops.events)
        assertEquals("sync-directory:assets", ops.events.last())
    }

    @Test
    fun encodedSizeLimitReadsAtMostOneBytePastBoundAndCleansOwnedNames() {
        val ops = FakeReferenceAssetFileOps()
        val opened = intArrayOf(0)
        val source = ReferenceAssetByteSource {
            opened[0] += 1
            object : InputStream() {
                var remaining = 7
                override fun read(): Int = if (remaining-- > 0) 0x41 else -1
            }
        }

        val failure = assertThrows(ReferenceAssetPublicationFailed::class.java) {
            store(ops, maxEncodedBytes = 5L).publish(identity("oversize-token"), source)
        }

        assertEquals(ReferenceAssetFailureStage.ENCODED_SIZE_LIMIT, failure.stage)
        assertEquals(1, opened[0])
        assertEquals(5, ops.maximumObservedByteCount)
        assertFalse(ops.exists(finalPath("oversize-token")))
        assertFalse(ops.exists(tempPath("oversize-token")))
    }

    @Test
    fun sourceFailureIsClosedSecretFreeAndCleansExactOwnedNames() {
        val ops = FakeReferenceAssetFileOps()
        val rawToken = "opaque-secret-source-token"
        val sourceSecret = "content://private/provider/42"
        val source = ReferenceAssetByteSource {
            object : InputStream() {
                var reads = 0
                override fun read(): Int {
                    throw AssertionError("bulk read expected")
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ == 1) throw IOException(sourceSecret)
                    buffer[offset] = 0x22
                    return 1
                }
            }
        }

        val failure = assertThrows(ReferenceAssetPublicationFailed::class.java) {
            store(ops).publish(identity(rawToken), source)
        }

        assertEquals(ReferenceAssetFailureStage.COPY_SOURCE, failure.stage)
        assertEquals(null, failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains(rawToken))
        assertFalse(failure.toString().contains(sourceSecret))
        assertFalse(ops.exists(finalPath(rawToken)))
        assertFalse(ops.exists(tempPath(rawToken)))
    }

    @Test
    fun existingFinalNeverClaimsTempAndPreservesForeignBytes() {
        val ops = FakeReferenceAssetFileOps()
        val rawToken = "existing-final"
        val foreignBytes = byteArrayOf(9, 8, 7)
        ops.precreate(finalPath(rawToken), foreignBytes)

        val failure = assertThrows(ReferenceAssetReconciliationRequired::class.java) {
            store(ops).publish(identity(rawToken), source(byteArrayOf(1, 2, 3)))
        }

        assertArrayEquals(foreignBytes, ops.bytesAt(finalPath(rawToken)))
        assertFalse(ops.events.any { it.startsWith("create-temp:") })
        assertFalse(failure.toString().contains(rawToken))
    }

    @Test
    fun tempCollisionPreservesForeignTempRemovesOnlyOwnedReservationAndRequiresReconciliation() {
        val ops = FakeReferenceAssetFileOps()
        val rawToken = "temp-collision"
        val foreignTempBytes = byteArrayOf(6, 5, 4)
        ops.precreate(tempPath(rawToken), foreignTempBytes)

        val failure = assertThrows(ReferenceAssetReconciliationRequired::class.java) {
            store(ops).publish(identity(rawToken), source(byteArrayOf(1)))
        }

        assertArrayEquals(foreignTempBytes, ops.bytesAt(tempPath(rawToken)))
        assertFalse(ops.exists(finalPath(rawToken)))
        assertEquals("sync-directory:assets", ops.events.last())
        assertFalse(failure.toString().contains(rawToken))
    }

    @Test
    fun ownershipMismatchBeforeRenamePreservesForeignFinalAndOwnedTempForReconciliation() {
        val ops = FakeReferenceAssetFileOps().apply {
            replaceFinalWithForeignBeforeRename = "foreign-final".toByteArray()
        }
        val rawToken = "opaque-secret-ownership-mismatch"

        val failure = assertThrows(ReferenceAssetReconciliationRequired::class.java) {
            store(ops).publish(identity(rawToken), source("candidate".toByteArray()))
        }

        assertArrayEquals("foreign-final".toByteArray(), ops.bytesAt(finalPath(rawToken)))
        assertArrayEquals("candidate".toByteArray(), ops.bytesAt(tempPath(rawToken)))
        assertFalse(failure.toString().contains(rawToken))
        assertFalse(ops.events.any { it.startsWith("delete:") })
    }

    @Test
    fun tempSyncFailureCleansOnlyExactOwnedTempAndReservation() {
        assertPreRenameFailure(
            failurePoint = FailurePoint.SYNC_FILE,
            expectedStage = ReferenceAssetFailureStage.SYNC_TEMP,
        )
    }

    @Test
    fun renameFailureCleansOnlyExactOwnedTempAndReservation() {
        assertPreRenameFailure(
            failurePoint = FailurePoint.RENAME,
            expectedStage = ReferenceAssetFailureStage.RENAME,
        )
    }

    @Test
    fun postRenameDirectorySyncFailureRetainsCandidateAndRequiresReconciliation() {
        val ops = FakeReferenceAssetFileOps().apply { failNext(FailurePoint.DIRECTORY_SYNC) }
        val rawToken = "opaque-secret-post-rename"
        val bytes = "durability-ambiguous".toByteArray()

        val failure = assertThrows(ReferenceAssetReconciliationRequired::class.java) {
            store(ops).publish(identity(rawToken), source(bytes))
        }

        assertArrayEquals(bytes, ops.bytesAt(finalPath(rawToken)))
        assertFalse(ops.exists(tempPath(rawToken)))
        assertFalse(ops.events.any { it.startsWith("delete:") })
        assertFalse(failure.toString().contains(rawToken))
    }

    @Test
    fun cleanupDeletesOnlyExactPublishedAssetAndIsIdempotentlyClosed() {
        val ops = FakeReferenceAssetFileOps()
        val store = store(ops)
        val rawToken = "cleanup-delete"
        val published = store.publish(identity(rawToken), source("asset".toByteArray()))
        val eventsBeforeCleanup = ops.events.size

        val first = store.cleanup(published)
        val eventsAfterCleanup = ops.events.toList()
        val second = store.cleanup(published)

        assertSame(ReferenceAssetCleanupResult.Cleaned, first)
        assertSame(first, second)
        assertFalse(ops.exists(finalPath(rawToken)))
        assertEquals(
            listOf("delete:${finalPath(rawToken).fileName}", "sync-directory:assets"),
            eventsAfterCleanup.drop(eventsBeforeCleanup),
        )
        assertEquals(eventsAfterCleanup, ops.events)
    }

    @Test
    fun cleanupAtomicallyQuarantinesWithoutClobberWhenExactDeleteFails() {
        val ops = FakeReferenceAssetFileOps()
        val store = store(ops)
        val rawToken = "cleanup-quarantine"
        val bytes = "published-asset".toByteArray()
        val published = store.publish(identity(rawToken), source(bytes))
        ops.failNext(FailurePoint.DELETE_NONEMPTY)

        val result = store.cleanup(published)

        assertTrue(result is ReferenceAssetCleanupResult.Quarantined)
        result as ReferenceAssetCleanupResult.Quarantined
        val expectedRelative = quarantineRelativePath(rawToken)
        val expectedQuarantine = root.resolve(expectedRelative)
        assertEquals(expectedRelative, result.safeRelativePath)
        assertFalse(ops.exists(finalPath(rawToken)))
        assertArrayEquals(bytes, ops.bytesAt(expectedQuarantine))
        assertTrue("reserve:${expectedQuarantine.fileName}" in ops.events)
        assertTrue(
            "replace:${finalPath(rawToken).fileName}->${expectedQuarantine.fileName}" in ops.events,
        )
        assertEquals(
            listOf("sync-directory:assets", "sync-directory:quarantine"),
            ops.events.takeLast(2),
        )
    }

    @Test
    fun cleanupOwnershipMismatchNeverDeletesOrQuarantinesForeignFinal() {
        val ops = FakeReferenceAssetFileOps()
        val store = store(ops)
        val rawToken = "cleanup-mismatch"
        val published = store.publish(identity(rawToken), source("asset".toByteArray()))
        val foreign = "foreign-replacement".toByteArray()
        ops.replaceWithForeign(finalPath(rawToken), foreign)
        val eventCount = ops.events.size

        val result = store.cleanup(published)

        assertSame(ReferenceAssetCleanupResult.ReconciliationRequired, result)
        assertArrayEquals(foreign, ops.bytesAt(finalPath(rawToken)))
        assertFalse(ops.events.drop(eventCount).any { it.startsWith("reserve:") })
        assertFalse(ops.events.drop(eventCount).any { it.startsWith("replace:") })
    }

    @Test
    fun quarantineCollisionNeverClobbersEitherFileAndRequiresReconciliation() {
        val ops = FakeReferenceAssetFileOps()
        val store = store(ops)
        val rawToken = "quarantine-collision"
        val bytes = "candidate".toByteArray()
        val published = store.publish(identity(rawToken), source(bytes))
        val quarantine = root.resolve(quarantineRelativePath(rawToken))
        val foreignQuarantine = "foreign-quarantine".toByteArray()
        ops.precreate(quarantine, foreignQuarantine)
        ops.failNext(FailurePoint.DELETE_NONEMPTY)

        val result = store.cleanup(published)

        assertSame(ReferenceAssetCleanupResult.ReconciliationRequired, result)
        assertArrayEquals(bytes, ops.bytesAt(finalPath(rawToken)))
        assertArrayEquals(foreignQuarantine, ops.bytesAt(quarantine))
    }

    @Test
    fun publicationFromAnotherStoreCannotGrantCleanupAuthority() {
        val ops = FakeReferenceAssetFileOps()
        val firstStore = store(ops)
        val publication = firstStore.publish(identity("cross-store"), source("asset".toByteArray()))
        val eventsBefore = ops.events.toList()

        val result = store(ops).cleanup(publication)

        assertSame(ReferenceAssetCleanupResult.ReconciliationRequired, result)
        assertEquals(eventsBefore + "prepare-directories", ops.events)
        assertTrue(ops.exists(finalPath("cross-store")))
    }

    @Test
    fun hashedNamesAndPreparedDirectoriesRemainConfinedAndRedacted() {
        val ops = FakeReferenceAssetFileOps()
        val rawToken = "opaque-secret-confinement-token"
        val published = store(ops).publish(identity(rawToken), source(byteArrayOf(3, 2, 1)))

        assertTrue(Regex("reference-assets/assets/[0-9a-f]{64}\\.asset").matches(published.safeRelativePath))
        assertFalse(published.safeRelativePath.contains(rawToken))
        assertTrue(ops.pathsTouched.all { path ->
            path.normalize().startsWith(root.resolve("reference-assets").normalize())
        })
        listOf(
            identity(rawToken),
            published,
            published.ownershipIdentity,
        ).forEach { value ->
            assertFalse(value.toString().contains(rawToken))
            assertFalse(value.toString().contains(root.toString()))
        }
        assertEquals(32L * 1024L * 1024L, MAX_ENCODED_REFERENCE_ASSET_BYTES)
    }

    @Test
    fun preparingRestartCleansAbsentAndZeroByteInterruptedShapes() {
        val cases = listOf(
            false to false,
            true to false,
            false to true,
            true to true,
        )

        cases.forEachIndexed { index, (hasFinalReservation, hasTemp) ->
            val rawToken = "restart-preparing-zero-$index"
            val ops = FakeReferenceAssetFileOps()
            if (hasFinalReservation) ops.precreate(finalPath(rawToken), ByteArray(0))
            if (hasTemp) ops.precreate(tempPath(rawToken), ByteArray(0))

            val result = store(ops).reconcilePending(
                identity(rawToken),
                ReferenceAssetPendingLifecycle.PREPARING,
            )

            assertSame(ReferenceAssetCleanupResult.Cleaned, result)
            assertFalse(ops.exists(finalPath(rawToken)))
            assertFalse(ops.exists(tempPath(rawToken)))
            assertFalse(result.toString().contains(rawToken))
        }
    }

    @Test
    fun preparingRestartQuarantinesNonemptyTempAndRemovesOptionalReservation() {
        listOf(false, true).forEachIndexed { index, hasFinalReservation ->
            val rawToken = "restart-preparing-temp-$index"
            val bytes = "untrusted-partial-$index".toByteArray()
            val ops = FakeReferenceAssetFileOps()
            if (hasFinalReservation) ops.precreate(finalPath(rawToken), ByteArray(0))
            ops.precreate(tempPath(rawToken), bytes)

            val result = store(ops).reconcilePending(
                identity(rawToken),
                ReferenceAssetPendingLifecycle.PREPARING,
            )

            assertTrue(result is ReferenceAssetCleanupResult.Quarantined)
            result as ReferenceAssetCleanupResult.Quarantined
            val expectedRelative = quarantineRelativePath(rawToken)
            assertEquals(expectedRelative, result.safeRelativePath)
            assertArrayEquals(bytes, ops.bytesAt(root.resolve(expectedRelative)))
            assertFalse(ops.exists(finalPath(rawToken)))
            assertFalse(ops.exists(tempPath(rawToken)))
            assertFalse(result.toString().contains(rawToken))
        }
    }

    @Test
    fun preparingRestartAdoptsPublishedFinalForExactOwnedCleanupOrQuarantine() {
        val bytes = "restart-published-final".toByteArray()

        FakeReferenceAssetFileOps().also { ops ->
            val rawToken = "restart-preparing-final-delete"
            ops.precreate(finalPath(rawToken), bytes)

            val result = store(ops).reconcilePending(
                identity(rawToken),
                ReferenceAssetPendingLifecycle.PREPARING,
            )

            assertSame(ReferenceAssetCleanupResult.Cleaned, result)
            assertFalse(ops.exists(finalPath(rawToken)))
        }

        FakeReferenceAssetFileOps().also { ops ->
            val rawToken = "restart-preparing-final-quarantine"
            ops.precreate(finalPath(rawToken), bytes)
            ops.failNext(FailurePoint.DELETE_NONEMPTY)

            val result = store(ops).reconcilePending(
                identity(rawToken),
                ReferenceAssetPendingLifecycle.PREPARING,
            )

            assertTrue(result is ReferenceAssetCleanupResult.Quarantined)
            val expected = root.resolve(quarantineRelativePath(rawToken))
            assertArrayEquals(bytes, ops.bytesAt(expected))
            assertFalse(ops.exists(finalPath(rawToken)))
        }
    }

    @Test
    fun contradictoryOrNonregularRestartShapesRequireReconciliationWithoutMutation() {
        val contradictoryToken = "restart-contradictory"
        val finalBytes = "published".toByteArray()
        val tempBytes = "pending".toByteArray()
        val contradictoryOps = FakeReferenceAssetFileOps().apply {
            precreate(finalPath(contradictoryToken), finalBytes)
            precreate(tempPath(contradictoryToken), tempBytes)
        }

        val contradictory = store(contradictoryOps).reconcilePending(
            identity(contradictoryToken),
            ReferenceAssetPendingLifecycle.PREPARING,
        )

        assertSame(ReferenceAssetCleanupResult.ReconciliationRequired, contradictory)
        assertArrayEquals(finalBytes, contradictoryOps.bytesAt(finalPath(contradictoryToken)))
        assertArrayEquals(tempBytes, contradictoryOps.bytesAt(tempPath(contradictoryToken)))
        assertFalse(contradictoryOps.events.any { it.startsWith("delete:") })
        assertFalse(contradictoryOps.events.any { it.startsWith("replace:") })

        val nonregularToken = "restart-nonregular"
        val nonregularOps = FakeReferenceAssetFileOps().apply {
            precreateNonregular(finalPath(nonregularToken))
        }

        val nonregular = store(nonregularOps).reconcilePending(
            identity(nonregularToken),
            ReferenceAssetPendingLifecycle.PREPARING,
        )

        assertSame(ReferenceAssetCleanupResult.ReconciliationRequired, nonregular)
        assertTrue(nonregularOps.exists(finalPath(nonregularToken)))
        assertFalse(nonregularOps.events.any { it.startsWith("delete:") })
        assertFalse(nonregularOps.events.any { it.startsWith("reserve:") })
    }

    @Test
    fun assetReadyRestartAcceptsOnlyAbsentOrPublishedFinalShape() {
        val absentToken = "restart-ready-absent"
        val absentOps = FakeReferenceAssetFileOps()
        assertSame(
            ReferenceAssetCleanupResult.Cleaned,
            store(absentOps).reconcilePending(
                identity(absentToken),
                ReferenceAssetPendingLifecycle.ASSET_READY,
            ),
        )

        val finalToken = "restart-ready-final"
        val finalOps = FakeReferenceAssetFileOps()
        finalOps.precreate(finalPath(finalToken), "ready-final".toByteArray())
        assertSame(
            ReferenceAssetCleanupResult.Cleaned,
            store(finalOps).reconcilePending(
                identity(finalToken),
                ReferenceAssetPendingLifecycle.ASSET_READY,
            ),
        )
        assertFalse(finalOps.exists(finalPath(finalToken)))

        listOf(
            "restart-ready-reservation" to Pair(ByteArray(0), null),
            "restart-ready-temp" to Pair(null, "pending".toByteArray()),
            "restart-ready-both" to Pair("final".toByteArray(), "pending".toByteArray()),
        ).forEach { (rawToken, shape) ->
            val ops = FakeReferenceAssetFileOps()
            shape.first?.let { ops.precreate(finalPath(rawToken), it) }
            shape.second?.let { ops.precreate(tempPath(rawToken), it) }

            val result = store(ops).reconcilePending(
                identity(rawToken),
                ReferenceAssetPendingLifecycle.ASSET_READY,
            )

            assertSame(ReferenceAssetCleanupResult.ReconciliationRequired, result)
            shape.first?.let { assertArrayEquals(it, ops.bytesAt(finalPath(rawToken))) }
            shape.second?.let { assertArrayEquals(it, ops.bytesAt(tempPath(rawToken))) }
            assertFalse(ops.events.any { it.startsWith("delete:") })
            assertFalse(ops.events.any { it.startsWith("replace:") })
        }
    }

    @Test
    fun restartRecognizesPreviouslyMovedDeterministicQuarantineAfterRoomSettlementFailure() {
        listOf(
            ReferenceAssetPendingLifecycle.PREPARING,
            ReferenceAssetPendingLifecycle.ASSET_READY,
        ).forEach { lifecycle ->
            val rawToken = "restart-existing-quarantine-${lifecycle.name.lowercase()}"
            val bytes = "quarantined-candidate".toByteArray()
            val ops = FakeReferenceAssetFileOps().apply {
                precreate(root.resolve(quarantineRelativePath(rawToken)), bytes)
            }

            val result = store(ops).reconcilePending(identity(rawToken), lifecycle)

            assertTrue(result is ReferenceAssetCleanupResult.Quarantined)
            result as ReferenceAssetCleanupResult.Quarantined
            assertEquals(quarantineRelativePath(rawToken), result.safeRelativePath)
            assertArrayEquals(bytes, ops.bytesAt(root.resolve(result.safeRelativePath)))
            assertFalse(ops.events.any { it.startsWith("delete:") })
            assertFalse(ops.events.any { it.startsWith("replace:") })
        }
    }

    @Test
    fun restartQuarantineCollisionAndFilesystemUncertaintyFailClosed() {
        val collisionToken = "restart-quarantine-collision"
        val pendingBytes = "pending-candidate".toByteArray()
        val foreignBytes = "foreign-quarantine".toByteArray()
        val collisionOps = FakeReferenceAssetFileOps().apply {
            precreate(tempPath(collisionToken), pendingBytes)
            precreate(
                root.resolve(quarantineRelativePath(collisionToken)),
                foreignBytes,
            )
        }

        val collision = store(collisionOps).reconcilePending(
            identity(collisionToken),
            ReferenceAssetPendingLifecycle.PREPARING,
        )

        assertSame(ReferenceAssetCleanupResult.ReconciliationRequired, collision)
        assertArrayEquals(pendingBytes, collisionOps.bytesAt(tempPath(collisionToken)))
        assertArrayEquals(
            foreignBytes,
            collisionOps.bytesAt(
                root.resolve(quarantineRelativePath(collisionToken)),
            ),
        )

        val inspectToken = "restart-inspection-uncertain"
        val inspectOps = FakeReferenceAssetFileOps().apply { failNext(FailurePoint.OBSERVE) }
        assertSame(
            ReferenceAssetCleanupResult.ReconciliationRequired,
            store(inspectOps).reconcilePending(
                identity(inspectToken),
                ReferenceAssetPendingLifecycle.PREPARING,
            ),
        )
        assertFalse(inspectOps.events.any { it.startsWith("delete:") })

        val hashToken = "restart-hash-uncertain"
        val hashOps = FakeReferenceAssetFileOps().apply {
            precreate(tempPath(hashToken), pendingBytes)
            failNext(FailurePoint.HASH)
        }
        assertSame(
            ReferenceAssetCleanupResult.ReconciliationRequired,
            store(hashOps).reconcilePending(
                identity(hashToken),
                ReferenceAssetPendingLifecycle.PREPARING,
            ),
        )
        assertArrayEquals(pendingBytes, hashOps.bytesAt(tempPath(hashToken)))

        val syncToken = "restart-sync-uncertain"
        val syncOps = FakeReferenceAssetFileOps().apply {
            precreate(finalPath(syncToken), "published".toByteArray())
            failNext(FailurePoint.DIRECTORY_SYNC)
        }
        assertSame(
            ReferenceAssetCleanupResult.ReconciliationRequired,
            store(syncOps).reconcilePending(
                identity(syncToken),
                ReferenceAssetPendingLifecycle.ASSET_READY,
            ),
        )
        assertFalse(syncOps.exists(finalPath(syncToken)))
        assertSame(
            ReferenceAssetCleanupResult.Cleaned,
            store(syncOps).reconcilePending(
                identity(syncToken),
                ReferenceAssetPendingLifecycle.ASSET_READY,
            ),
        )

        val quarantineSyncToken = "restart-quarantine-sync-retry"
        val quarantineSyncOps = FakeReferenceAssetFileOps().apply {
            precreate(tempPath(quarantineSyncToken), pendingBytes)
            failNext(FailurePoint.DIRECTORY_SYNC)
        }
        assertSame(
            ReferenceAssetCleanupResult.ReconciliationRequired,
            store(quarantineSyncOps).reconcilePending(
                identity(quarantineSyncToken),
                ReferenceAssetPendingLifecycle.PREPARING,
            ),
        )
        assertFalse(quarantineSyncOps.exists(tempPath(quarantineSyncToken)))
        val retry = store(quarantineSyncOps).reconcilePending(
            identity(quarantineSyncToken),
            ReferenceAssetPendingLifecycle.PREPARING,
        )
        assertTrue(retry is ReferenceAssetCleanupResult.Quarantined)
        retry as ReferenceAssetCleanupResult.Quarantined
        assertEquals(quarantineRelativePath(quarantineSyncToken), retry.safeRelativePath)
    }

    @Test
    fun explicitJvmNioFileOpsPublishAndCleanAnAppPrivateAsset() {
        val noBackupRoot = Files.createTempDirectory("reference-asset-store-test")
        try {
            val token = ReferenceImportToken("opaque-secret-jvm-ops-token")
            val store = ReferenceAssetStore(
                noBackupRoot,
                NioReferenceAssetFileOps,
                MAX_ENCODED_REFERENCE_ASSET_BYTES,
            )
            val bytes = "jvm-file-ops-asset".toByteArray(StandardCharsets.UTF_8)

            val published = store.publish(ReferenceAssetIdentity(token), source(bytes))
            val finalPath = noBackupRoot.resolve(published.safeRelativePath)

            assertEquals(ReferenceImportAssetPath.forToken(token), published.safeRelativePath)
            assertArrayEquals(bytes, Files.readAllBytes(finalPath))
            assertSame(ReferenceAssetCleanupResult.Cleaned, store.cleanup(published))
            assertFalse(Files.exists(finalPath))
        } finally {
            Files.walk(noBackupRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun assertPreRenameFailure(
        failurePoint: FailurePoint,
        expectedStage: ReferenceAssetFailureStage,
    ) {
        val ops = FakeReferenceAssetFileOps().apply { failNext(failurePoint) }
        val rawToken = "pre-rename-${failurePoint.name}"

        val failure = assertThrows(ReferenceAssetPublicationFailed::class.java) {
            store(ops).publish(identity(rawToken), source("candidate".toByteArray()))
        }

        assertEquals(expectedStage, failure.stage)
        assertFalse(ops.exists(finalPath(rawToken)))
        assertFalse(ops.exists(tempPath(rawToken)))
        assertEquals("sync-directory:assets", ops.events.last())
    }

    private fun store(
        ops: FakeReferenceAssetFileOps,
        maxEncodedBytes: Long = 64L,
    ): ReferenceAssetStore = ReferenceAssetStore(root, ops, maxEncodedBytes)

    private fun identity(rawToken: String): ReferenceAssetIdentity =
        ReferenceAssetIdentity(ReferenceImportToken(rawToken))

    private fun source(bytes: ByteArray): ReferenceAssetByteSource =
        ReferenceAssetByteSource { ByteArrayInputStream(bytes) }

    private fun finalPath(rawToken: String): Path =
        root.resolve("reference-assets/assets/${tokenDigest(rawToken)}.asset")

    private fun tempPath(rawToken: String): Path =
        root.resolve("reference-assets/assets/.${tokenDigest(rawToken)}.asset.pending")

    private fun relativeFinalPath(rawToken: String): String =
        "reference-assets/assets/${tokenDigest(rawToken)}.asset"

    private fun quarantineRelativePath(rawToken: String): String =
        "reference-assets/quarantine/${tokenDigest(rawToken)}.quarantined"

    private fun tokenDigest(rawToken: String): String =
        sha256(rawToken.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        val root: Path = Paths.get("/safe/no-backup").toAbsolutePath().normalize()
    }
}

private enum class FailurePoint {
    SYNC_FILE,
    RENAME,
    DIRECTORY_SYNC,
    DELETE_NONEMPTY,
    OBSERVE,
    HASH,
}

private class FakeReferenceAssetFileOps : ReferenceAssetFileOps {
    private data class Identity(val serial: Int) : ReferenceAssetFileIdentity
    private data class FakeFile(
        val identity: Identity,
        var bytes: ByteArray = ByteArray(0),
        val regular: Boolean = true,
    )

    val events = mutableListOf<String>()
    val pathsTouched = mutableListOf<Path>()
    var maximumObservedByteCount = 0
        private set
    var replaceFinalWithForeignBeforeRename: ByteArray? = null
    private val files = linkedMapOf<Path, FakeFile>()
    private val failures = mutableMapOf<FailurePoint, Int>()
    private var nextSerial = 1

    override fun prepareDirectories(noBackupRoot: Path): ReferenceAssetDirectories {
        events += "prepare-directories"
        val directories = ReferenceAssetDirectories(
            assets = noBackupRoot.resolve("reference-assets/assets").normalize(),
            quarantine = noBackupRoot.resolve("reference-assets/quarantine").normalize(),
        )
        pathsTouched.add(directories.assets)
        pathsTouched.add(directories.quarantine)
        return directories
    }

    override fun reserveEmpty(path: Path): ReferenceAssetFileIdentity {
        touch(path)
        events += "reserve:${path.fileName}"
        if (files.containsKey(path)) throw ReferenceAssetReservationConflict()
        return Identity(nextSerial++).also { files[path] = FakeFile(it) }
    }

    override fun createTemp(path: Path): ReferenceAssetFileIdentity {
        touch(path)
        events += "create-temp:${path.fileName}"
        if (files.containsKey(path)) throw ReferenceAssetTempConflict()
        return Identity(nextSerial++).also { files[path] = FakeFile(it) }
    }

    override fun openOwnedForWrite(path: Path, identity: ReferenceAssetFileIdentity): OutputStream {
        touch(path)
        events += "open-owned:${path.fileName}"
        val file = requireOwned(path, identity)
        return object : ByteArrayOutputStream() {
            override fun write(b: Int) {
                super.write(b)
                maximumObservedByteCount = maxOf(maximumObservedByteCount, size())
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len)
                maximumObservedByteCount = maxOf(maximumObservedByteCount, size())
            }

            override fun close() {
                file.bytes = toByteArray()
                super.close()
            }
        }
    }

    override fun syncOwnedFile(path: Path, identity: ReferenceAssetFileIdentity) {
        touch(path)
        events += "sync-file:${path.fileName}"
        requireOwned(path, identity)
        if (consumeFailure(FailurePoint.SYNC_FILE)) throw ReferenceAssetOperationFailed()
    }

    override fun ownedRegularByteCount(path: Path, identity: ReferenceAssetFileIdentity): Long? {
        touch(path)
        events += "inspect:${path.fileName}"
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
        touch(sourcePath)
        touch(destinationPath)
        events += "replace:${sourcePath.fileName}->${destinationPath.fileName}"
        val replacement = replaceFinalWithForeignBeforeRename
        if (replacement != null && sourcePath.fileName.toString().endsWith(".pending")) {
            replaceFinalWithForeignBeforeRename = null
            replaceWithForeign(destinationPath, replacement)
        }
        val source = requireOwned(sourcePath, sourceIdentity)
        val reservation = requireOwned(destinationPath, reservationIdentity)
        if (!source.regular || source.bytes.size.toLong() != expectedByteCount) {
            throw ReferenceAssetOwnershipMismatch()
        }
        if (!reservation.regular || reservation.bytes.isNotEmpty()) {
            throw ReferenceAssetOwnershipMismatch()
        }
        if (consumeFailure(FailurePoint.RENAME)) throw ReferenceAssetOperationFailed()
        files.remove(sourcePath)
        files[destinationPath] = source
    }

    override fun deleteOwnedFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
    ) {
        touch(path)
        events += "delete:${path.fileName}"
        val file = requireOwned(path, identity)
        if (file.bytes.size.toLong() != expectedByteCount) throw ReferenceAssetOwnershipMismatch()
        if (expectedByteCount > 0L && consumeFailure(FailurePoint.DELETE_NONEMPTY)) {
            throw ReferenceAssetOperationFailed()
        }
        files.remove(path)
    }

    override fun syncDirectory(directory: Path) {
        touch(directory)
        events += "sync-directory:${directory.fileName}"
        if (consumeFailure(FailurePoint.DIRECTORY_SYNC)) throw ReferenceAssetOperationFailed()
    }

    override fun observeRegularFile(path: Path): ReferenceAssetFileObservation {
        touch(path)
        events += "observe:${path.fileName}"
        if (consumeFailure(FailurePoint.OBSERVE)) throw ReferenceAssetOperationFailed()
        val file = files[path] ?: return ReferenceAssetFileObservation.Absent
        if (!file.regular) throw ReferenceAssetOwnershipMismatch()
        return ReferenceAssetFileObservation.Regular(file.identity, file.bytes.size.toLong())
    }

    override fun sha256OwnedFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
    ): String {
        touch(path)
        events += "hash:${path.fileName}"
        if (consumeFailure(FailurePoint.HASH)) throw ReferenceAssetOperationFailed()
        val file = requireOwned(path, identity)
        if (!file.regular || file.bytes.size.toLong() != expectedByteCount) {
            throw ReferenceAssetOwnershipMismatch()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(file.bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun failNext(point: FailurePoint) {
        failures[point] = failures.getOrDefault(point, 0) + 1
    }

    fun precreate(path: Path, bytes: ByteArray) {
        files[path] = FakeFile(Identity(nextSerial++), bytes.copyOf())
    }

    fun precreateNonregular(path: Path) {
        files[path] = FakeFile(Identity(nextSerial++), regular = false)
    }

    fun replaceWithForeign(path: Path, bytes: ByteArray) {
        files[path] = FakeFile(Identity(nextSerial++), bytes.copyOf())
    }

    fun exists(path: Path): Boolean = files.containsKey(path)

    fun bytesAt(path: Path): ByteArray = requireNotNull(files[path]).bytes.copyOf()

    private fun touch(path: Path) {
        pathsTouched.add(path)
    }

    private fun consumeFailure(point: FailurePoint): Boolean {
        val remaining = failures.getOrDefault(point, 0)
        if (remaining == 0) return false
        failures[point] = remaining - 1
        return true
    }

    private fun requireOwned(path: Path, identity: ReferenceAssetFileIdentity): FakeFile {
        val file = files[path] ?: throw ReferenceAssetOwnershipMismatch()
        if (file.identity != identity) throw ReferenceAssetOwnershipMismatch()
        return file
    }
}
