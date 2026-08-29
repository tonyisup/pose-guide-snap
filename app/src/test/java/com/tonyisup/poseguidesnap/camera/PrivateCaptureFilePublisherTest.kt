package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCaptureFilePublisherTest {
    @Test
    fun completeOutputSetAcceptsOnlyExactOrderedOrdinalsForOneToken() {
        val token = CaptureToken("capture-A")
        requireCompletePrivateOutputSet((0..2).map { PrivateOutputIdentity(token, it) })

        listOf(
            listOf(PrivateOutputIdentity(token, 0), PrivateOutputIdentity(token, 1)),
            listOf(
                PrivateOutputIdentity(token, 0),
                PrivateOutputIdentity(token, 1),
                PrivateOutputIdentity(token, 1),
            ),
            listOf(
                PrivateOutputIdentity(token, 1),
                PrivateOutputIdentity(token, 0),
                PrivateOutputIdentity(token, 2),
            ),
            listOf(
                PrivateOutputIdentity(token, 0),
                PrivateOutputIdentity(CaptureToken("capture-B"), 1),
                PrivateOutputIdentity(token, 2),
            ),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                requireCompletePrivateOutputSet(invalid)
            }
        }
    }

    @Test
    fun safeNameIsDeterministicHashedOrdinalBoundAndCannotEscapeRoot() = withRoot { root ->
        val rawToken = "../private/name"
        val identity = PrivateOutputIdentity(CaptureToken(rawToken), 2)
        val first = PrivateCaptureFilePublisher(root).prepare(identity)
        val expectedFinalName = "${sha256(rawToken)}-2.jpg"

        try {
            assertEquals(".$expectedFinalName.pending", first.tempFile.name)
            assertEquals(root.canonicalFile, requireNotNull(first.tempFile.parentFile).canonicalFile)
            assertFalse(first.tempFile.path.contains(rawToken))
            assertFalse(first.toString().contains(rawToken))
            assertFalse(first.toString().contains(root.path))
            assertTrue(Regex("\\.[0-9a-f]{64}-2\\.jpg\\.pending").matches(first.tempFile.name))
            assertTrue(root.resolve(expectedFinalName).isFile)
            assertEquals(0L, root.resolve(expectedFinalName).length())
        } finally {
            first.close()
        }

        val second = PrivateCaptureFilePublisher(root).prepare(identity)
        try {
            assertEquals(first.tempFile.canonicalFile, second.tempFile.canonicalFile)
            assertNotSame(first, second)
        } finally {
            second.close()
        }
    }

    @Test
    fun publisherCreatesAbsentRootAndRejectsSymlinkOrNonDirectoryRoot() {
        val parent = Files.createTempDirectory("private-publisher-root").toFile()
        try {
            val absent = parent.resolve("created")
            val prepared = PrivateCaptureFilePublisher(absent).prepare(identity(0))
            assertTrue(absent.isDirectory)
            prepared.close()

            val ordinaryFile = parent.resolve("not-a-directory").apply { writeText("fixed") }
            val fileFailure = assertThrows(IllegalArgumentException::class.java) {
                PrivateCaptureFilePublisher(ordinaryFile)
            }
            assertFalse(fileFailure.toString().contains(ordinaryFile.path))

            val target = parent.resolve("real-root").apply { mkdir() }
            val symlink = parent.resolve("linked-root").toPath()
            Files.createSymbolicLink(symlink, target.toPath())
            val symlinkFailure = assertThrows(IllegalArgumentException::class.java) {
                PrivateCaptureFilePublisher(symlink.toFile())
            }
            assertFalse(symlinkFailure.toString().contains(symlink.toString()))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun publishOrdersReservationTempSyncVerifiedRenameAndDirectorySync() = withRoot { root ->
        val events = mutableListOf<String>()
        val ops = RecordingFileOps(events)
        val identity = identity(1)
        val prepared = PrivateCaptureFilePublisher(root, ops).prepare(identity)
        val bytes = "exact-private-bytes".toByteArray(StandardCharsets.UTF_8)
        prepared.tempFile.writeBytes(bytes)
        events += "external-bytes"

        val published = prepared.publish()

        assertEquals(
            listOf(
                "reserve-final",
                "create-temp",
                "external-bytes",
                "sync-temp",
                "verify+rename-owned-reservation",
                "sync-directory",
            ),
            events,
        )
        assertSame(identity, published.identity)
        assertEquals(bytes.size.toLong(), published.byteCount)
        assertEquals(root.resolve(finalName(identity)).canonicalFile, published.finalFile.canonicalFile)
        assertArrayEquals(bytes, published.finalFile.readBytes())
        assertFalse(prepared.tempFile.exists())
        assertFalse(published.toString().contains(identity.token.value))
        assertFalse(published.toString().contains(root.path))
    }

    @Test
    fun preexistingFinalCollisionRequiresReconciliationLeavesExactBytesAndNeverClaimsTemp() = withRoot { root ->
        val identity = identity(0)
        val finalFile = root.resolve(finalName(identity)).apply { writeBytes(byteArrayOf(4, 5, 6)) }

        val failure = assertThrows(PrivateCaptureReconciliationRequired::class.java) {
            PrivateCaptureFilePublisher(root).prepare(identity)
        }

        assertSame(identity, failure.identity)
        assertFalse(failure.reservationReplacedByCandidate)
        assertFalse(failure.directorySyncedAfterPublication)
        assertArrayEquals(byteArrayOf(4, 5, 6), finalFile.readBytes())
        assertFalse(root.resolve(".${finalFile.name}.pending").exists())
    }

    @Test
    fun reserveFailureAfterCreateRequiresReconciliationAndPreservesZeroReservation() = withRoot { root ->
        val rawToken = "ambiguous/reservation-secret"
        val identity = PrivateOutputIdentity(CaptureToken(rawToken), 1)
        val events = mutableListOf<String>()

        val failure = assertThrows(PrivateCaptureReconciliationRequired::class.java) {
            PrivateCaptureFilePublisher(
                root,
                RecordingFileOps(events, FailurePoint.RESERVE_AFTER_CREATE),
            ).prepare(identity)
        }

        assertSame(identity, failure.identity)
        assertFalse(failure.reservationReplacedByCandidate)
        assertFalse(failure.directorySyncedAfterPublication)
        assertFalse(failure.toString().contains(rawToken))
        assertFalse(failure.toString().contains(root.path))
        val reservation = root.resolve(finalName(identity))
        assertTrue(reservation.isFile)
        assertEquals(0L, reservation.length())
        assertFalse(root.resolve(".${reservation.name}.pending").exists())
        assertEquals(listOf("reserve-final"), events)
    }

    @Test
    fun definiteReserveFailureBeforeCreateRemainsOrdinaryPrepareFailure() = withRoot { root ->
        val identity = identity(1)
        val events = mutableListOf<String>()

        val failure = assertThrows(PrivateCapturePublicationFailed::class.java) {
            PrivateCaptureFilePublisher(
                root,
                RecordingFileOps(events, FailurePoint.RESERVE_BEFORE_CREATE),
            ).prepare(identity)
        }

        assertEquals(PrivateCaptureFailureStage.PREPARE, failure.stage)
        assertFalse(root.resolve(finalName(identity)).exists())
        assertFalse(root.resolve(".${finalName(identity)}.pending").exists())
        assertEquals(listOf("reserve-final"), events)
    }

    @Test
    fun tempCollisionAfterReservationRemovesOnlyOwnedReservationAndSyncsDirectory() = withRoot { root ->
        val identity = identity(0)
        val temp = root.resolve(".${finalName(identity)}.pending").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val events = mutableListOf<String>()

        val failure = assertThrows(PrivateCapturePublicationFailed::class.java) {
            PrivateCaptureFilePublisher(root, RecordingFileOps(events)).prepare(identity)
        }

        assertEquals(PrivateCaptureFailureStage.PREPARE, failure.stage)
        assertEquals(
            listOf("reserve-final", "create-temp", "delete-owned-reservation", "sync-directory"),
            events,
        )
        assertArrayEquals(byteArrayOf(9, 8, 7), temp.readBytes())
        assertFalse(root.resolve(finalName(identity)).exists())
    }

    @Test
    fun tempCollisionReservationDeleteFailureRetainsSafeRetryableCleanupOwner() = withRoot { root ->
        val rawToken = "prepare-cleanup/private-token"
        val identity = PrivateOutputIdentity(CaptureToken(rawToken), 0)
        val foreignTempBytes = "foreign-temp-exact".toByteArray(StandardCharsets.UTF_8)
        val temp = root.resolve(".${finalName(identity)}.pending").apply { writeBytes(foreignTempBytes) }
        val events = mutableListOf<String>()

        val failure = assertThrows(PrivateCaptureFilePublisherException::class.java) {
            PrivateCaptureFilePublisher(
                root,
                RecordingFileOps(events, FailurePoint.DELETE_RESERVATION),
            ).prepare(identity)
        }

        assertEquals("PrivateCapturePreparationCleanupRequired", failure.javaClass.simpleName)
        assertSame(identity, failure.javaClass.getMethod("getIdentity").invoke(failure))
        assertEquals(
            PrivateCaptureFailureStage.CLEANUP_RESERVATION,
            failure.javaClass.getMethod("getStage").invoke(failure),
        )
        assertEquals(null, failure.cause)
        assertFalse(failure.toString().contains(rawToken))
        assertFalse(failure.toString().contains(root.path))
        assertArrayEquals(foreignTempBytes, temp.readBytes())
        assertTrue(root.resolve(finalName(identity)).isFile)
        assertEquals(0L, root.resolve(finalName(identity)).length())

        val cleanupOwner = preparationCleanupOwner(failure)
        assertSafeCleanupOwner(cleanupOwner, identity)
        cleanupOwner.close()
        cleanupOwner.close()

        assertArrayEquals(foreignTempBytes, temp.readBytes())
        assertFalse(root.resolve(finalName(identity)).exists())
        assertEquals(2, events.count { it == "delete-owned-reservation" })
        assertEquals(1, events.count { it == "sync-directory" })
    }

    @Test
    fun tempCollisionDirectorySyncFailureRetryOnlySyncsDirectory() = withRoot { root ->
        val identity = identity(1)
        val foreignTempBytes = byteArrayOf(7, 1, 7, 1)
        val temp = root.resolve(".${finalName(identity)}.pending").apply { writeBytes(foreignTempBytes) }
        val events = mutableListOf<String>()

        val failure = assertThrows(PrivateCaptureFilePublisherException::class.java) {
            PrivateCaptureFilePublisher(
                root,
                RecordingFileOps(events, FailurePoint.DIRECTORY_SYNC),
            ).prepare(identity)
        }

        assertEquals("PrivateCapturePreparationCleanupRequired", failure.javaClass.simpleName)
        assertEquals(
            PrivateCaptureFailureStage.SYNC_DIRECTORY_AFTER_CLEANUP,
            failure.javaClass.getMethod("getStage").invoke(failure),
        )
        assertFalse(root.resolve(finalName(identity)).exists())
        assertArrayEquals(foreignTempBytes, temp.readBytes())

        val cleanupOwner = preparationCleanupOwner(failure)
        cleanupOwner.close()
        cleanupOwner.close()

        assertArrayEquals(foreignTempBytes, temp.readBytes())
        assertEquals(1, events.count { it == "delete-owned-reservation" })
        assertEquals(2, events.count { it == "sync-directory" })
    }

    @Test
    fun tempCollisionCleanupNeverDeletesMismatchedReservation() = withRoot { root ->
        val identity = identity(0)
        val temp = root.resolve(".${finalName(identity)}.pending").apply { writeText("foreign-temp") }
        val foreignFinalBytes = "foreign-final".toByteArray(StandardCharsets.UTF_8)
        val ops = RecordingFileOps(
            events = mutableListOf(),
            replaceReservationAfterTempCollisionWith = foreignFinalBytes,
        )

        val failure = assertThrows(PrivateCaptureFilePublisherException::class.java) {
            PrivateCaptureFilePublisher(root, ops).prepare(identity)
        }

        assertEquals("PrivateCapturePreparationCleanupRequired", failure.javaClass.simpleName)
        assertArrayEquals(foreignFinalBytes, root.resolve(finalName(identity)).readBytes())
        assertEquals("foreign-temp", temp.readText())
        assertThrows(PrivateCaptureCleanupFailed::class.java) {
            preparationCleanupOwner(failure).close()
        }
        assertArrayEquals(foreignFinalBytes, root.resolve(finalName(identity)).readBytes())
        assertEquals("foreign-temp", temp.readText())
    }

    @Test
    fun emptyAndSymlinkTempsFailThenCloseRemovesTempAndOwnedReservation() = withRoot { root ->
        val emptyIdentity = identity(0)
        val empty = PrivateCaptureFilePublisher(root).prepare(emptyIdentity)
        val emptyFailure = assertThrows(PrivateCapturePublicationFailed::class.java) { empty.publish() }
        assertEquals(PrivateCaptureFailureStage.VALIDATE_TEMP, emptyFailure.stage)
        assertTrue(root.resolve(finalName(emptyIdentity)).isFile)
        assertEquals(0L, root.resolve(finalName(emptyIdentity)).length())
        empty.close()
        assertFalse(empty.tempFile.exists())
        assertFalse(root.resolve(finalName(emptyIdentity)).exists())

        val linkedIdentity = identity(1)
        val linked = PrivateCaptureFilePublisher(root).prepare(linkedIdentity)
        val target = root.resolve("external-writer-target").apply { writeText("nonempty") }
        Files.delete(linked.tempFile.toPath())
        Files.createSymbolicLink(linked.tempFile.toPath(), target.toPath())
        val linkFailure = assertThrows(PrivateCapturePublicationFailed::class.java) { linked.publish() }
        assertEquals(PrivateCaptureFailureStage.VALIDATE_TEMP, linkFailure.stage)
        linked.close()
        assertFalse(linked.tempFile.exists())
        assertFalse(root.resolve(finalName(linkedIdentity)).exists())
        assertEquals("nonempty", target.readText())
    }

    @Test
    fun syncTempFailureLeavesReservationAndTempUntilCloseRemovesBoth() = withRoot { root ->
        assertPreAtomicFailure(root, FailurePoint.SYNC_TEMP, PrivateCaptureFailureStage.SYNC_TEMP)
    }

    @Test
    fun renameFailureLeavesReservationAndTempUntilCloseRemovesBoth() = withRoot { root ->
        assertPreAtomicFailure(
            root,
            FailurePoint.REPLACE_OWNED_RESERVATION,
            PrivateCaptureFailureStage.REPLACE_OWNED_RESERVATION,
        )
    }

    @Test
    fun ownershipMismatchBeforeRenameFailsClosedAndCleanupNeverDeletesForeignFinal() = withRoot { root ->
        val rawToken = "ownership/private-token"
        val identity = PrivateOutputIdentity(CaptureToken(rawToken), 0)
        val prepared = PrivateCaptureFilePublisher(root).prepare(identity)
        prepared.tempFile.writeText("candidate")
        val final = root.resolve(finalName(identity))
        Files.delete(final.toPath())
        val foreignBytes = "foreign-final".toByteArray(StandardCharsets.UTF_8)
        final.writeBytes(foreignBytes)

        val failure = assertThrows(PrivateCaptureReconciliationRequired::class.java) { prepared.publish() }
        assertSame(identity, failure.identity)
        assertFalse(failure.reservationReplacedByCandidate)
        assertFalse(failure.directorySyncedAfterPublication)
        assertFalse(failure.toString().contains(rawToken))
        assertFalse(failure.toString().contains(root.path))
        assertArrayEquals(foreignBytes, final.readBytes())
        assertTrue(prepared.tempFile.exists())

        val cleanup = assertThrows(PrivateCaptureCleanupFailed::class.java) { prepared.close() }
        assertEquals(PrivateCaptureFailureStage.CLEANUP_RESERVATION, cleanup.stage)
        assertFalse(prepared.tempFile.exists())
        assertArrayEquals(foreignBytes, final.readBytes())
        assertThrows(PrivateCaptureCleanupFailed::class.java) { prepared.close() }
        assertArrayEquals(foreignBytes, final.readBytes())
    }

    @Test
    fun postRenameDirectorySyncFailureRetainsCandidateAndRequiresExactReconciliation() = withRoot { root ->
        val events = mutableListOf<String>()
        val ops = RecordingFileOps(events, FailurePoint.DIRECTORY_SYNC)
        val rawToken = "post-rename/secret"
        val identity = PrivateOutputIdentity(CaptureToken(rawToken), 1)
        val bytes = "candidate-exact".toByteArray(StandardCharsets.UTF_8)
        val prepared = PrivateCaptureFilePublisher(root, ops).prepare(identity)
        prepared.tempFile.writeBytes(bytes)

        val failure = assertThrows(PrivateCaptureReconciliationRequired::class.java) { prepared.publish() }

        assertSame(identity, failure.identity)
        assertTrue(failure.reservationReplacedByCandidate)
        assertFalse(failure.directorySyncedAfterPublication)
        assertFalse(failure.toString().contains(rawToken))
        assertFalse(failure.toString().contains(root.path))
        val final = root.resolve(finalName(identity))
        assertArrayEquals(bytes, final.readBytes())
        assertFalse(prepared.tempFile.exists())
        val eventsBeforeClose = events.toList()
        assertSame(failure, assertThrows(PrivateCaptureReconciliationRequired::class.java) { prepared.publish() })
        prepared.close()
        prepared.close()
        assertEquals(eventsBeforeClose, events)
        assertArrayEquals(bytes, final.readBytes())
    }

    @Test
    fun tempCleanupFailureIsObservableAndRetryableBeforeReservationCleanup() = withRoot { root ->
        val events = mutableListOf<String>()
        val identity = identity(0)
        val prepared = PrivateCaptureFilePublisher(root, RecordingFileOps(events, FailurePoint.DELETE_TEMP))
            .prepare(identity)
        prepared.tempFile.writeText("candidate")

        val failure = assertThrows(PrivateCaptureCleanupFailed::class.java) { prepared.close() }
        assertEquals(PrivateCaptureFailureStage.CLEANUP_TEMP, failure.stage)
        assertTrue(prepared.tempFile.exists())
        assertTrue(root.resolve(finalName(identity)).exists())

        prepared.close()
        prepared.close()
        assertFalse(prepared.tempFile.exists())
        assertFalse(root.resolve(finalName(identity)).exists())
        assertEquals(2, events.count { it == "delete-temp" })
        assertEquals(1, events.count { it == "delete-owned-reservation" })
        assertEquals(1, events.count { it == "sync-directory" })
    }

    @Test
    fun reservationCleanupFailureIsObservableAndRetryableWithoutDeletingCandidate() = withRoot { root ->
        val events = mutableListOf<String>()
        val identity = identity(0)
        val prepared = PrivateCaptureFilePublisher(
            root,
            RecordingFileOps(events, FailurePoint.DELETE_RESERVATION),
        ).prepare(identity)
        prepared.tempFile.writeText("candidate")

        val failure = assertThrows(PrivateCaptureCleanupFailed::class.java) { prepared.close() }
        assertEquals(PrivateCaptureFailureStage.CLEANUP_RESERVATION, failure.stage)
        assertFalse(prepared.tempFile.exists())
        assertEquals(0L, root.resolve(finalName(identity)).length())

        prepared.close()
        prepared.close()
        assertFalse(root.resolve(finalName(identity)).exists())
        assertEquals(1, events.count { it == "delete-temp" })
        assertEquals(2, events.count { it == "delete-owned-reservation" })
        assertEquals(1, events.count { it == "sync-directory" })
    }

    @Test
    fun cleanupDirectorySyncFailureRetriesOnlyDirectorySync() = withRoot { root ->
        val events = mutableListOf<String>()
        val identity = identity(0)
        val prepared = PrivateCaptureFilePublisher(root, RecordingFileOps(events, FailurePoint.DIRECTORY_SYNC))
            .prepare(identity)
        prepared.tempFile.writeText("candidate")

        val failure = assertThrows(PrivateCaptureCleanupFailed::class.java) { prepared.close() }
        assertEquals(PrivateCaptureFailureStage.SYNC_DIRECTORY_AFTER_CLEANUP, failure.stage)
        assertFalse(prepared.tempFile.exists())
        assertFalse(root.resolve(finalName(identity)).exists())

        prepared.close()
        prepared.close()
        assertEquals(1, events.count { it == "delete-temp" })
        assertEquals(1, events.count { it == "delete-owned-reservation" })
        assertEquals(2, events.count { it == "sync-directory" })
    }

    @Test
    fun zeroByteCrashReservationRequiresReconciliationAndIsPreserved() = withRoot { root ->
        val identity = identity(2)
        val final = root.resolve(finalName(identity)).apply { createNewFile() }

        val failure = assertThrows(PrivateCaptureReconciliationRequired::class.java) {
            PrivateCaptureFilePublisher(root).prepare(identity)
        }

        assertSame(identity, failure.identity)
        assertFalse(failure.reservationReplacedByCandidate)
        assertFalse(failure.directorySyncedAfterPublication)
        assertEquals(0L, final.length())
        assertFalse(root.resolve(".${final.name}.pending").exists())
    }

    @Test
    fun separateFileOpsInstancesShareGuardAcrossVerifiedRenameAndCompetingReserve() = withRoot { root ->
        val identity = identity(0)
        val verifiedInsideGuard = CountDownLatch(1)
        val allowRename = CountDownLatch(1)
        val competingReserveAttempted = CountDownLatch(1)
        val firstOps = GuardedPausingFileOps(
            verifiedInsideGuard = verifiedInsideGuard,
            allowRename = allowRename,
        )
        val secondOps = GuardedPausingFileOps(
            reserveAttempted = competingReserveAttempted,
        )
        val first = PrivateCaptureFilePublisher(root, firstOps).prepare(identity)
        val candidateBytes = "thread-a-exact-candidate".toByteArray(StandardCharsets.UTF_8)
        first.tempFile.writeBytes(candidateBytes)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val publishedFuture = executor.submit<PublishedPrivateOutput> { first.publish() }
            assertTrue(verifiedInsideGuard.await(5, TimeUnit.SECONDS))

            val collisionFuture = executor.submit<Throwable?> {
                try {
                    PrivateCaptureFilePublisher(root, secondOps).prepare(identity)
                    null
                } catch (failure: Throwable) {
                    failure
                }
            }
            assertTrue(competingReserveAttempted.await(5, TimeUnit.SECONDS))
            assertFalse("competing supported mutation must block inside the shared guard", collisionFuture.isDone)

            allowRename.countDown()
            val published = publishedFuture.get(5, TimeUnit.SECONDS)
            val collision = collisionFuture.get(5, TimeUnit.SECONDS)

            assertTrue(collision is PrivateCaptureReconciliationRequired)
            assertArrayEquals(candidateBytes, published.finalFile.readBytes())
            assertFalse(first.tempFile.exists())
            assertFalse(root.resolve(".${published.finalFile.name}.pending").exists())
        } finally {
            allowRename.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun publishIsSynchronizedIdempotentAndResultFieldsAreFinal() = withRoot { root ->
        val identity = identity(2)
        val prepared = PrivateCaptureFilePublisher(root).prepare(identity)
        prepared.tempFile.writeText("stable")

        val first = prepared.publish()
        val second = prepared.publish()

        assertSame(first, second)
        assertArrayEquals("stable".toByteArray(), first.finalFile.readBytes())
        assertTrue(
            PublishedPrivateOutput::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .all { Modifier.isFinal(it.modifiers) },
        )
        val publishMethod = PreparedPrivateOutput::class.java.getDeclaredMethod("publish")
        val closeMethod = PreparedPrivateOutput::class.java.getDeclaredMethod("close")
        assertTrue(Modifier.isSynchronized(publishMethod.modifiers))
        assertTrue(Modifier.isSynchronized(closeMethod.modifiers))
        prepared.close()
        assertTrue(first.finalFile.exists())
    }

    @Test
    fun nioSourceUsesSingleMoveOnlyInsideVerifiedOwnedReservationMethodAndNoHardLinks() {
        val source = productionSource()
        listOf(
            "Files.createLink",
            "createLink(",
            "Os.link",
            "renameTo(",
            ".delete()",
            "deleteRecursively",
            "android.",
            "androidx.",
            "CameraX",
            "ImageCapture",
            "android.util.Log",
            "System.currentTimeMillis",
            "System.nanoTime",
            "java.time",
            "kotlin.time",
            "UUID",
            "Random",
            "java.net",
            "http://",
            "https://",
            "kotlinx.coroutines",
            "System.loadLibrary",
            "external fun",
            "renameat2",
        ).forEach { forbidden ->
            assertFalse("Forbidden publisher source token: $forbidden", forbidden in source)
        }
        listOf(
            "StandardOpenOption.CREATE_NEW",
            "BasicFileAttributes::class.java",
            ".fileKey()",
            "attributes.isRegularFile",
            "attributes.size() == 0L",
            "StandardCopyOption.ATOMIC_MOVE",
            "StandardCopyOption.REPLACE_EXISTING",
            "Files.delete(finalPath)",
            "LinkOption.NOFOLLOW_LINKS",
            "MessageDigest.getInstance(\"SHA-256\")",
            "PrivateCaptureReservationConflict",
            "PrivateCaptureReservationMayExist",
            "internal interface PrivateCaptureCleanupOwner : AutoCloseable",
            "PrivateCapturePreparationCleanupRequired",
            "catch (_: FileAlreadyExistsException)",
            "internal fun <T> withExclusiveMutation",
            "private val monitor = Any()",
            "synchronized(monitor, block)",
            "capture-candidates directory is exclusively owned",
            "all supported in-process mutation",
            "code that bypasses this ownership boundary",
        ).forEach { required ->
            assertTrue("Missing publisher source marker: $required", required in source)
        }
        assertEquals(1, Regex("Files\\.move\\(").findAll(source).count())
        assertEquals(1, Regex("StandardCopyOption\\.REPLACE_EXISTING").findAll(source).count())
        val verifiedMethod = source.substring(
            source.indexOf("override fun replaceOwnedReservation").also { assertTrue(it >= 0) },
            source.indexOf("override fun deleteOwnedReservation").also { assertTrue(it >= 0) },
        )
        assertTrue("Files.move(" in verifiedMethod)
        assertTrue(verifiedMethod.indexOf("requireOwnedZeroByteReservation") < verifiedMethod.indexOf("Files.move("))
        assertTrue(verifiedMethod.indexOf("withExclusiveMutation") < verifiedMethod.indexOf("requireOwnedZeroByteReservation"))
        val reserveMethod = source.substring(
            source.indexOf("override fun reserve").also { assertTrue(it >= 0) },
            source.indexOf("override fun createNew").also { assertTrue(it >= 0) },
        )
        assertTrue("withExclusiveMutation" in reserveMethod)
        assertTrue(reserveMethod.indexOf("created = true") < reserveMethod.indexOf("channel.force(true)"))
        assertTrue(reserveMethod.indexOf("catch (_: FileAlreadyExistsException)") < reserveMethod.indexOf("PrivateCaptureReservationConflict"))
        val deleteMethod = source.substring(
            source.indexOf("override fun deleteOwnedReservation").also { assertTrue(it >= 0) },
            source.indexOf("override fun syncDirectory").also { assertTrue(it >= 0) },
        )
        assertTrue(deleteMethod.indexOf("withExclusiveMutation") < deleteMethod.indexOf("requireOwnedZeroByteReservation"))
        val prepareMethod = source.substring(
            source.indexOf("fun prepare(identity: PrivateOutputIdentity)").also { assertTrue(it >= 0) },
            source.indexOf("class PreparedPrivateOutput").also { assertTrue(it >= 0) },
        )
        assertFalse(
            "failed temp CREATE_NEW must never delete or truncate the temp",
            "deleteIfExists(tempPath)" in prepareMethod,
        )
    }

    private fun preparationCleanupOwner(failure: Throwable): AutoCloseable {
        val ownerField = failure.javaClass.declaredFields.single {
            AutoCloseable::class.java.isAssignableFrom(it.type)
        }
        ownerField.isAccessible = true
        return ownerField.get(failure) as AutoCloseable
    }

    private fun assertSafeCleanupOwner(
        cleanupOwner: AutoCloseable,
        expectedIdentity: PrivateOutputIdentity,
    ) {
        val ownerContract = Class.forName(
            "com.tonyisup.poseguidesnap.camera.PrivateCaptureCleanupOwner",
        )
        assertTrue(ownerContract.isInstance(cleanupOwner))
        assertTrue(AutoCloseable::class.java.isAssignableFrom(ownerContract))
        assertSame(expectedIdentity, ownerContract.getMethod("getIdentity").invoke(cleanupOwner))
        val exposedMethods = cleanupOwner.javaClass.methods.filterNot {
            it.declaringClass == Any::class.java || it.declaringClass == Object::class.java
        }
        assertTrue(
            exposedMethods.none { method ->
                method.returnType == File::class.java ||
                    method.returnType == Path::class.java ||
                    method.parameterTypes.any { it == File::class.java || it == Path::class.java }
            },
        )
        assertFalse(cleanupOwner.toString().contains(expectedIdentity.token.value))
    }

    private fun assertPreAtomicFailure(
        root: File,
        failurePoint: FailurePoint,
        expectedStage: PrivateCaptureFailureStage,
    ) {
        val identity = identity(0)
        val ops = RecordingFileOps(mutableListOf(), failurePoint)
        val prepared = PrivateCaptureFilePublisher(root, ops).prepare(identity)
        prepared.tempFile.writeText("candidate")

        val failure = assertThrows(PrivateCapturePublicationFailed::class.java) { prepared.publish() }
        assertEquals(expectedStage, failure.stage)
        assertEquals(0L, root.resolve(finalName(identity)).length())
        assertTrue(prepared.tempFile.exists())

        prepared.close()
        assertFalse(prepared.tempFile.exists())
        assertFalse(root.resolve(finalName(identity)).exists())
    }

    private fun identity(ordinal: Int): PrivateOutputIdentity =
        PrivateOutputIdentity(CaptureToken("capture-token"), ordinal)

    private fun finalName(identity: PrivateOutputIdentity): String =
        "${sha256(identity.token.value)}-${identity.ordinal}.jpg"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("private-capture-publisher").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun productionSource(): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
        return root.resolve(SOURCE_PATH).readText()
    }

    private enum class FailurePoint {
        RESERVE_BEFORE_CREATE,
        RESERVE_AFTER_CREATE,
        SYNC_TEMP,
        REPLACE_OWNED_RESERVATION,
        DELETE_TEMP,
        DELETE_RESERVATION,
        DIRECTORY_SYNC,
    }

    private data class TestReservationIdentity(val fileKey: Any) : PrivateCaptureReservationIdentity

    private class GuardedPausingFileOps(
        private val verifiedInsideGuard: CountDownLatch? = null,
        private val allowRename: CountDownLatch? = null,
        private val reserveAttempted: CountDownLatch? = null,
    ) : PrivateCaptureFileOps {
        override fun reserve(finalPath: Path): PrivateCaptureReservationIdentity {
            reserveAttempted?.countDown()
            return withExclusiveMutation {
                var created = false
                try {
                    FileChannel.open(finalPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                        created = true
                        it.force(true)
                    }
                    TestReservationIdentity(requireNotNull(attributes(finalPath).fileKey()))
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    throw PrivateCaptureReservationConflict()
                } catch (failure: Exception) {
                    if (created) throw PrivateCaptureReservationMayExist()
                    throw failure
                }
            }
        }

        override fun createNew(path: Path) = withExclusiveMutation {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { }
        }

        override fun regularFileByteCount(path: Path): Long? {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
            return attributes(path).size()
        }

        override fun syncFile(path: Path) {
            FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        }

        override fun replaceOwnedReservation(
            tempPath: Path,
            finalPath: Path,
            identity: PrivateCaptureReservationIdentity,
        ) = withExclusiveMutation {
            requireOwnedZeroByteReservation(finalPath, identity)
            verifiedInsideGuard?.countDown()
            allowRename?.let { assertTrue(it.await(5, TimeUnit.SECONDS)) }
            Files.move(
                tempPath,
                finalPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Unit
        }

        override fun deleteOwnedReservation(
            finalPath: Path,
            identity: PrivateCaptureReservationIdentity,
        ) = withExclusiveMutation {
            requireOwnedZeroByteReservation(finalPath, identity)
            Files.delete(finalPath)
        }

        override fun syncDirectory(directory: Path) {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }

        override fun deleteIfExists(path: Path): Boolean = withExclusiveMutation {
            Files.deleteIfExists(path)
        }

        private fun requireOwnedZeroByteReservation(
            path: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            val expected = identity as? TestReservationIdentity
                ?: throw PrivateCaptureReservationOwnershipMismatch()
            val actual = try {
                attributes(path)
            } catch (_: Exception) {
                throw PrivateCaptureReservationOwnershipMismatch()
            }
            if (!actual.isRegularFile || actual.size() != 0L || actual.fileKey() != expected.fileKey) {
                throw PrivateCaptureReservationOwnershipMismatch()
            }
        }

        private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    }

    private class RecordingFileOps(
        private val events: MutableList<String>,
        private val failurePoint: FailurePoint? = null,
        private val replaceReservationAfterTempCollisionWith: ByteArray? = null,
    ) : PrivateCaptureFileOps {
        private var failureConsumed = false
        private var reservedFinal: Path? = null

        override fun reserve(finalPath: Path): PrivateCaptureReservationIdentity {
            events += "reserve-final"
            failOnce(FailurePoint.RESERVE_BEFORE_CREATE)
            var created = false
            try {
                FileChannel.open(finalPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                    created = true
                    it.force(true)
                    failOnce(FailurePoint.RESERVE_AFTER_CREATE)
                }
                reservedFinal = finalPath
                return TestReservationIdentity(requireNotNull(attributes(finalPath).fileKey()))
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                throw PrivateCaptureReservationConflict()
            } catch (failure: Exception) {
                if (created) throw PrivateCaptureReservationMayExist()
                throw failure
            }
        }

        override fun createNew(path: Path) {
            events += "create-temp"
            try {
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { }
            } catch (failure: Exception) {
                replaceReservationAfterTempCollisionWith?.let { foreignBytes ->
                    val finalPath = requireNotNull(reservedFinal)
                    Files.delete(finalPath)
                    Files.write(finalPath, foreignBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                throw failure
            }
        }

        override fun regularFileByteCount(path: Path): Long? {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
            return attributes(path).size()
        }

        override fun syncFile(path: Path) {
            events += "sync-temp"
            failOnce(FailurePoint.SYNC_TEMP)
            FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        }

        override fun replaceOwnedReservation(
            tempPath: Path,
            finalPath: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            events += "verify+rename-owned-reservation"
            failOnce(FailurePoint.REPLACE_OWNED_RESERVATION)
            requireOwnedZeroByteReservation(finalPath, identity)
            Files.move(
                tempPath,
                finalPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        override fun deleteOwnedReservation(finalPath: Path, identity: PrivateCaptureReservationIdentity) {
            events += "delete-owned-reservation"
            failOnce(FailurePoint.DELETE_RESERVATION)
            requireOwnedZeroByteReservation(finalPath, identity)
            Files.delete(finalPath)
        }

        override fun syncDirectory(directory: Path) {
            events += "sync-directory"
            failOnce(FailurePoint.DIRECTORY_SYNC)
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }

        override fun deleteIfExists(path: Path): Boolean {
            events += "delete-temp"
            failOnce(FailurePoint.DELETE_TEMP)
            return Files.deleteIfExists(path)
        }

        private fun requireOwnedZeroByteReservation(
            path: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            val expected = identity as? TestReservationIdentity
                ?: throw PrivateCaptureReservationOwnershipMismatch()
            val actual = try {
                attributes(path)
            } catch (_: Exception) {
                throw PrivateCaptureReservationOwnershipMismatch()
            }
            if (!actual.isRegularFile || actual.size() != 0L || actual.fileKey() != expected.fileKey) {
                throw PrivateCaptureReservationOwnershipMismatch()
            }
        }

        private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )

        private fun failOnce(point: FailurePoint) {
            if (!failureConsumed && failurePoint == point) {
                failureConsumed = true
                throw IOException("injected-$point-private-path")
            }
        }
    }

    private companion object {
        const val SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/camera/PrivateCaptureFilePublisher.kt"
    }
}
