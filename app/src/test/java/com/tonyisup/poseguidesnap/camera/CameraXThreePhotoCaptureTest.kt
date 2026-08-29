package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.domain.session.CaptureAttempt
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.CaptureTrigger
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CameraXThreePhotoCaptureTest {
    @Test
    fun acceptedCommandWritesPublishesAndCompletesStrictlyOneAtATimeInOrdinalOrder() = withHarness { harness ->
        val command = command("accepted/private-token")

        assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command))
        assertEquals(listOf(0), harness.writer.startedOrdinals)
        assertEquals(1, harness.writer.pendingCount)
        assertEquals(1, harness.writer.maxPendingCount)
        assertTrue(harness.successes.isEmpty())

        harness.writer.succeed("zero".bytes())
        assertEquals(listOf(0, 1), harness.writer.startedOrdinals)
        assertEquals(1, harness.writer.pendingCount)
        assertEquals(listOf(0), harness.finalOrdinals())
        assertTrue(harness.successes.isEmpty())

        harness.writer.succeed("one".bytes())
        assertEquals(listOf(0, 1, 2), harness.writer.startedOrdinals)
        assertEquals(1, harness.writer.pendingCount)
        assertEquals(listOf(0, 1), harness.finalOrdinals())
        assertTrue(harness.successes.isEmpty())

        harness.writer.succeed("two".bytes())

        assertEquals(0, harness.writer.pendingCount)
        assertEquals(1, harness.writer.maxPendingCount)
        assertEquals(1, harness.successes.size)
        assertTrue(harness.failures.isEmpty())
        val success = harness.successes.single()
        assertEquals(command.token, success.token)
        assertEquals(command.outputs, success.outputs.map { it.identity })
        assertEquals(listOf("zero", "one", "two"), success.outputs.map { it.finalFile.readText() })
        assertEquals(listOf(4L, 3L, 3L), success.outputs.map { it.byteCount })
        assertEquals(listOf(0, 1, 2), harness.finalOrdinals())
        assertTrue(harness.pendingFiles().isEmpty())
    }

    @Test
    fun synchronousWriterCallbacksCompleteAllThreeWithoutReentrancyLeakOrStrandedBusy() = withRoot { root ->
        val writer = ImmediateSuccessWriter()
        val publisher = PrivateCaptureFilePublisher(root)
        val capture = CameraXThreePhotoCapture(writer, publisher)
        val successes = mutableListOf<ThreePhotoCaptureSuccess>()
        val failures = mutableListOf<ThreePhotoCaptureFailure>()
        try {
            assertEquals(
                ThreePhotoCaptureSubmission.ACCEPTED,
                capture.submit(command("synchronous-callback"), successes::add, failures::add),
            )
            assertEquals(listOf(0, 1, 2), writer.startedOrdinals)
            assertEquals(listOf(0, 1, 2), successes.single().outputs.map { it.identity.ordinal })
            assertTrue(failures.isEmpty())
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".pending") })
            assertEquals(
                ThreePhotoCaptureSubmission.ACCEPTED,
                capture.submit(command("after-synchronous-callback"), successes::add, failures::add),
            )
        } finally {
            capture.close()
        }
    }

    @Test
    fun busySubmissionIsRejectedWithoutCallbacksOrWorkAndNextIsAcceptedAfterSuccess() = withHarness { harness ->
        val first = command("first")
        val rejectedSuccesses = mutableListOf<ThreePhotoCaptureSuccess>()
        val rejectedFailures = mutableListOf<ThreePhotoCaptureFailure>()
        assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(first))

        assertEquals(
            ThreePhotoCaptureSubmission.REJECTED_BUSY,
            harness.capture.submit(command("busy"), rejectedSuccesses::add, rejectedFailures::add),
        )
        assertTrue(rejectedSuccesses.isEmpty())
        assertTrue(rejectedFailures.isEmpty())
        assertEquals(listOf(0), harness.writer.startedOrdinals)

        repeat(3) { harness.writer.succeed(byteArrayOf((it + 1).toByte())) }
        assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command("after-success")))
        assertEquals(listOf(0, 1, 2, 0), harness.writer.startedOrdinals)
    }

    @Test
    fun synchronousWriterThrowAtEveryOrdinalCleansTempRetainsExactPartialFinalsAndReleasesBusy() {
        (0..2).forEach { failedOrdinal ->
            withHarness { harness ->
                harness.writer.throwOnOrdinal = failedOrdinal
                assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command("sync-$failedOrdinal")))
                repeat(failedOrdinal) { ordinal -> harness.writer.succeed("ok-$ordinal".bytes()) }

                val failure = harness.failures.single()
                assertFailure(
                    failure = failure,
                    failedOrdinal = failedOrdinal,
                    expectedPartialOrdinals = (0 until failedOrdinal).toList(),
                    expectedStage = ThreePhotoCaptureFailureStage.WRITE,
                )
                assertEquals((0 until failedOrdinal).toList(), harness.finalOrdinals())
                assertTrue(harness.pendingFiles().isEmpty())
                assertEquals(
                    ThreePhotoCaptureSubmission.ACCEPTED,
                    harness.submit(command("sync-next-$failedOrdinal")),
                )
            }
        }
    }

    @Test
    fun asynchronousWriterErrorAtEveryOrdinalCleansTempRetainsExactPartialFinalsAndStops() {
        (0..2).forEach { failedOrdinal ->
            withHarness { harness ->
                assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command("async-$failedOrdinal")))
                repeat(failedOrdinal) { ordinal -> harness.writer.succeed("ok-$ordinal".bytes()) }
                harness.writer.fail()

                assertFailure(
                    failure = harness.failures.single(),
                    failedOrdinal = failedOrdinal,
                    expectedPartialOrdinals = (0 until failedOrdinal).toList(),
                    expectedStage = ThreePhotoCaptureFailureStage.WRITE,
                )
                assertEquals((0..failedOrdinal).toList(), harness.writer.startedOrdinals)
                assertEquals((0 until failedOrdinal).toList(), harness.finalOrdinals())
                assertTrue(harness.pendingFiles().isEmpty())
                assertEquals(0, harness.writer.pendingCount)
            }
        }
    }

    @Test
    fun prepareCollisionRequiresReconciliationWithoutStartingWriterOrClobberingExistingFinal() = withHarness { harness ->
        val rawToken = "prepare-collision/private-token"
        val command = command(rawToken)
        val existing = publishDirectly(harness.publisher, command.outputs[0], "existing".bytes())

        assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command))

        assertTrue(harness.writer.startedOrdinals.isEmpty())
        assertArrayEquals("existing".bytes(), existing.finalFile.readBytes())
        val failure = harness.failures.single()
        assertFailure(
            failure = failure,
            failedOrdinal = 0,
            expectedPartialOrdinals = emptyList(),
            expectedStage = ThreePhotoCaptureFailureStage.RECONCILIATION_REQUIRED,
        )
        assertTrue(failure.finalMayExist)
        assertTrue(failure.reconciliationRequired)
        assertFalse(failure.cleanupPending)
        assertFalse(failure.toString().contains(rawToken))
        assertFalse(failure.toString().contains(harness.root.path))
        assertTrue(harness.pendingFiles().isEmpty())
    }

    @Test
    fun tempCollisionReservationDeleteFailureStaysBusyUntilRetryWithoutTouchingForeignTemp() = withRoot { root ->
        val ops = RetryCleanupFaultFileOps(RetryCleanupFault.DELETE_RESERVATION_ONCE)
        val harness = Harness(root, PrivateCaptureFilePublisher(root, ops), ManualWriter())
        val command = command("prepare-delete-once/private-token")
        val failedIdentity = command.outputs[0]
        val foreignBytes = "foreign-temp-delete".bytes()
        val foreignTemp = root.resolve(".${finalName(failedIdentity)}.pending").apply {
            writeBytes(foreignBytes)
        }
        try {
            assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command))

            val failure = harness.failures.single()
            assertFailure(
                failure,
                failedOrdinal = 0,
                expectedPartialOrdinals = emptyList(),
                expectedStage = ThreePhotoCaptureFailureStage.CLEANUP,
                expectedCleanupPending = true,
            )
            assertTrue(failure.finalMayExist)
            assertFalse(failure.reconciliationRequired)
            assertTrue(harness.writer.startedOrdinals.isEmpty())
            assertArrayEquals(foreignBytes, foreignTemp.readBytes())
            assertEquals(
                ThreePhotoCaptureSubmission.REJECTED_BUSY,
                harness.submit(command("blocked-by-prepare-delete-cleanup")),
            )

            assertEquals(ThreePhotoCleanupRetryOutcome.CLEANED, harness.capture.retryPendingCleanup())

            assertEquals(1, harness.failures.size)
            assertArrayEquals(foreignBytes, foreignTemp.readBytes())
            assertFalse(root.resolve(finalName(failedIdentity)).exists())
            assertEquals(2, ops.reservationDeleteAttempts)
            assertEquals(1, ops.directorySyncAttempts)
            assertEquals(
                ThreePhotoCaptureSubmission.ACCEPTED,
                harness.submit(command("accepted-after-prepare-delete-cleanup")),
            )
        } finally {
            harness.capture.close()
        }
    }

    @Test
    fun tempCollisionDirectorySyncFailureRetryDoesNotDeleteReservationAgain() = withRoot { root ->
        val ops = RetryCleanupFaultFileOps(RetryCleanupFault.SYNC_CLEANUP_DIRECTORY_ONCE)
        val harness = Harness(root, PrivateCaptureFilePublisher(root, ops), ManualWriter())
        val command = command("prepare-sync-once/private-token")
        val failedIdentity = command.outputs[0]
        val foreignBytes = byteArrayOf(4, 2, 4, 2)
        val foreignTemp = root.resolve(".${finalName(failedIdentity)}.pending").apply {
            writeBytes(foreignBytes)
        }
        try {
            assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command))

            val failure = harness.failures.single()
            assertFailure(
                failure,
                failedOrdinal = 0,
                expectedPartialOrdinals = emptyList(),
                expectedStage = ThreePhotoCaptureFailureStage.CLEANUP,
                expectedCleanupPending = true,
            )
            assertTrue(failure.finalMayExist)
            assertTrue(harness.writer.startedOrdinals.isEmpty())
            assertFalse(root.resolve(finalName(failedIdentity)).exists())
            assertArrayEquals(foreignBytes, foreignTemp.readBytes())
            assertEquals(1, ops.reservationDeleteAttempts)
            assertEquals(1, ops.directorySyncAttempts)

            assertEquals(ThreePhotoCleanupRetryOutcome.CLEANED, harness.capture.retryPendingCleanup())

            assertEquals(1, harness.failures.size)
            assertArrayEquals(foreignBytes, foreignTemp.readBytes())
            assertEquals(1, ops.reservationDeleteAttempts)
            assertEquals(2, ops.directorySyncAttempts)
            assertEquals(
                ThreePhotoCaptureSubmission.ACCEPTED,
                harness.submit(command("accepted-after-prepare-sync-cleanup")),
            )
        } finally {
            harness.capture.close()
        }
    }

    @Test
    fun persistentPrepareCleanupFailureRemainsOwnedAndRetryableAfterClose() = withRoot { root ->
        val ops = RetryCleanupFaultFileOps(RetryCleanupFault.DELETE_RESERVATION_ALWAYS)
        val harness = Harness(root, PrivateCaptureFilePublisher(root, ops), ManualWriter())
        val command = command("prepare-delete-always/private-token")
        val failedIdentity = command.outputs[0]
        val foreignBytes = "foreign-temp-persistent".bytes()
        val foreignTemp = root.resolve(".${finalName(failedIdentity)}.pending").apply {
            writeBytes(foreignBytes)
        }
        val rejectedSuccesses = mutableListOf<ThreePhotoCaptureSuccess>()
        val rejectedFailures = mutableListOf<ThreePhotoCaptureFailure>()
        try {
            assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command))
            assertEquals(1, harness.failures.size)
            assertTrue(harness.failures.single().cleanupPending)
            assertEquals(1, ops.reservationDeleteAttempts)

            harness.capture.close()

            assertEquals(2, ops.reservationDeleteAttempts)
            assertEquals(ThreePhotoCleanupRetryOutcome.STILL_PENDING, harness.capture.retryPendingCleanup())
            assertEquals(3, ops.reservationDeleteAttempts)
            assertEquals(1, harness.failures.size)
            assertTrue(harness.writer.startedOrdinals.isEmpty())
            assertArrayEquals(foreignBytes, foreignTemp.readBytes())
            assertTrue(root.resolve(finalName(failedIdentity)).isFile)
            assertEquals(
                ThreePhotoCaptureSubmission.REJECTED_CLOSED,
                harness.capture.submit(
                    command("closed-with-prepare-cleanup"),
                    rejectedSuccesses::add,
                    rejectedFailures::add,
                ),
            )
            assertTrue(rejectedSuccesses.isEmpty())
            assertTrue(rejectedFailures.isEmpty())
        } finally {
            harness.capture.close()
        }
    }

    @Test
    fun reservationOwnershipMismatchAtEachOrdinalRequiresReconciliationWithoutClobberingForeignFinal() {
        (0..2).forEach { failedOrdinal ->
            withHarness { harness ->
                val command = command("publication-$failedOrdinal")
                harness.submit(command)
                repeat(failedOrdinal) { ordinal -> harness.writer.succeed("ok-$ordinal".bytes()) }
                val foreignFinal = harness.root.resolve(finalName(command.outputs[failedOrdinal])).apply {
                    writeBytes("foreign-$failedOrdinal".bytes())
                }

                harness.writer.succeed("candidate-$failedOrdinal".bytes())

                val failure = harness.failures.single()
                assertFailure(
                    failure,
                    failedOrdinal,
                    (0 until failedOrdinal).toList(),
                    ThreePhotoCaptureFailureStage.RECONCILIATION_REQUIRED,
                    expectedCleanupPending = true,
                )
                assertTrue(failure.finalMayExist)
                assertTrue(failure.reconciliationRequired)
                assertArrayEquals("foreign-$failedOrdinal".bytes(), foreignFinal.readBytes())
                assertEquals(0, harness.writer.pendingCount)
                assertTrue(harness.pendingFiles().isEmpty())
                assertEquals(
                    ThreePhotoCleanupRetryOutcome.STILL_PENDING,
                    harness.capture.retryPendingCleanup(),
                )
                assertEquals(
                    ThreePhotoCaptureSubmission.REJECTED_BUSY,
                    harness.submit(command("blocked-by-reconciliation-$failedOrdinal")),
                )
                assertEquals(1, harness.failures.size)
            }
        }
    }

    @Test
    fun postLinkFailureMapsToReconciliationWithoutDeletingFinal() = withRoot { root ->
        val ops = FaultFileOps(Fault.FIRST_DIRECTORY_SYNC)
        val publisher = PrivateCaptureFilePublisher(root, ops)
        val writer = ManualWriter()
        val harness = Harness(root, publisher, writer)
        val command = command("reconciliation/private")
        try {
            harness.submit(command)
            writer.succeed("linked-but-ambiguous".bytes())

            val failure = harness.failures.single()
            assertFailure(
                failure,
                failedOrdinal = 0,
                expectedPartialOrdinals = emptyList(),
                expectedStage = ThreePhotoCaptureFailureStage.RECONCILIATION_REQUIRED,
            )
            assertTrue(failure.finalMayExist)
            assertTrue(failure.reconciliationRequired)
            val final = root.resolve(finalName(command.outputs[0]))
            assertArrayEquals("linked-but-ambiguous".bytes(), final.readBytes())
            assertTrue(harness.pendingFiles().isEmpty())
        } finally {
            harness.capture.close()
        }
    }

    @Test
    fun tempDeleteCleanupFailureRetainsOwnershipUntilExplicitRetryCleans() {
        assertRetryableCleanupRecovery(RetryCleanupFault.DELETE_TEMP_ONCE)
    }

    @Test
    fun ownedReservationDeleteCleanupFailureRetainsOwnershipUntilExplicitRetryCleans() {
        assertRetryableCleanupRecovery(RetryCleanupFault.DELETE_RESERVATION_ONCE)
    }

    @Test
    fun directorySyncCleanupFailureRetainsOwnershipUntilExplicitRetryCleans() {
        assertRetryableCleanupRecovery(RetryCleanupFault.SYNC_CLEANUP_DIRECTORY_ONCE)
    }

    @Test
    fun persistentCleanupFailureRemainsOwnedBusyAndRetryableAfterClose() = withRoot { root ->
        val ops = RetryCleanupFaultFileOps(RetryCleanupFault.DELETE_TEMP_ALWAYS)
        val harness = Harness(root, PrivateCaptureFilePublisher(root, ops), ManualWriter())
        val rejectedSuccesses = mutableListOf<ThreePhotoCaptureSuccess>()
        val rejectedFailures = mutableListOf<ThreePhotoCaptureFailure>()
        try {
            assertEquals(ThreePhotoCleanupRetryOutcome.NO_CLEANUP_PENDING, harness.capture.retryPendingCleanup())
            assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command("persistent-cleanup")))
            harness.writer.fail()

            val failure = harness.failures.single()
            assertFailure(
                failure,
                failedOrdinal = 0,
                expectedPartialOrdinals = emptyList(),
                expectedStage = ThreePhotoCaptureFailureStage.CLEANUP,
                expectedCleanupPending = true,
            )
            assertEquals(ThreePhotoCleanupRetryOutcome.STILL_PENDING, harness.capture.retryPendingCleanup())
            assertEquals(ThreePhotoCleanupRetryOutcome.STILL_PENDING, harness.capture.retryPendingCleanup())
            assertEquals(
                ThreePhotoCaptureSubmission.REJECTED_BUSY,
                harness.capture.submit(command("blocked-by-persistent-cleanup"), rejectedSuccesses::add, rejectedFailures::add),
            )
            assertEquals(1, harness.failures.size)
            assertTrue(rejectedSuccesses.isEmpty())
            assertTrue(rejectedFailures.isEmpty())

            harness.capture.close()

            assertTrue(ops.tempDeleteAttempts >= 4)
            assertEquals(ThreePhotoCleanupRetryOutcome.STILL_PENDING, harness.capture.retryPendingCleanup())
            assertEquals(1, harness.failures.size)
            assertEquals(
                ThreePhotoCaptureSubmission.REJECTED_CLOSED,
                harness.capture.submit(command("closed-with-persistent-cleanup"), rejectedSuccesses::add, rejectedFailures::add),
            )
        } finally {
            harness.capture.close()
        }
    }

    @Test
    fun concurrentCleanupRetriesAreSerializedToCleanedThenNoPending() = withRoot { root ->
        val ops = RetryCleanupFaultFileOps(RetryCleanupFault.BLOCK_SECOND_TEMP_DELETE)
        val harness = Harness(root, PrivateCaptureFilePublisher(root, ops), ManualWriter())
        val executor = Executors.newFixedThreadPool(2)
        val secondRetryStarted = CountDownLatch(1)
        try {
            assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command("concurrent-cleanup-retry")))
            harness.writer.fail()
            assertTrue(harness.failures.single().cleanupPending)

            val firstRetry = executor.submit<ThreePhotoCleanupRetryOutcome> {
                harness.capture.retryPendingCleanup()
            }
            assertTrue(ops.retryTempDeleteEntered.await(5, TimeUnit.SECONDS))
            val secondRetry = executor.submit<ThreePhotoCleanupRetryOutcome> {
                secondRetryStarted.countDown()
                harness.capture.retryPendingCleanup()
            }
            assertTrue(secondRetryStarted.await(5, TimeUnit.SECONDS))
            ops.allowRetryTempDelete.countDown()

            assertEquals(ThreePhotoCleanupRetryOutcome.CLEANED, firstRetry.get(5, TimeUnit.SECONDS))
            assertEquals(
                ThreePhotoCleanupRetryOutcome.NO_CLEANUP_PENDING,
                secondRetry.get(5, TimeUnit.SECONDS),
            )
            assertEquals(1, harness.failures.size)
            assertTrue(harness.pendingFiles().isEmpty())
            assertEquals(
                ThreePhotoCaptureSubmission.ACCEPTED,
                harness.submit(command("accepted-after-concurrent-cleanup-retry")),
            )
        } finally {
            ops.allowRetryTempDelete.countDown()
            executor.shutdownNow()
            harness.capture.close()
        }
    }

    @Test
    fun closeIdleIsIdempotentRejectsWithoutCallbacksAndDoesNotStartWork() = withHarness { harness ->
        harness.capture.close()
        harness.capture.close()
        val successes = mutableListOf<ThreePhotoCaptureSuccess>()
        val failures = mutableListOf<ThreePhotoCaptureFailure>()

        assertEquals(
            ThreePhotoCaptureSubmission.REJECTED_CLOSED,
            harness.capture.submit(command("closed"), successes::add, failures::add),
        )
        assertTrue(successes.isEmpty())
        assertTrue(failures.isEmpty())
        assertTrue(harness.writer.startedOrdinals.isEmpty())
    }

    @Test
    fun closeInFlightCannotCancelCurrentSuccessPublishesItThenStopsBeforeNext() = withHarness { harness ->
        val command = command("close-current-success")
        harness.submit(command)
        harness.capture.close()

        harness.writer.succeed("current".bytes())

        assertEquals(listOf(0), harness.writer.startedOrdinals)
        assertEquals(listOf(0), harness.finalOrdinals())
        assertTrue(harness.pendingFiles().isEmpty())
        assertFailure(
            harness.failures.single(),
            failedOrdinal = 1,
            expectedPartialOrdinals = listOf(0),
            expectedStage = ThreePhotoCaptureFailureStage.CLOSED_WITH_PARTIAL,
        )
        assertEquals(
            ThreePhotoCaptureSubmission.REJECTED_CLOSED,
            harness.submit(command("closed-after-current")),
        )
    }

    @Test
    fun closeDuringThirdPublicationCompletesExactThreeAsSuccess() = withRoot { root ->
        val ops = BlockingThirdDirectorySyncFileOps()
        val harness = Harness(root, PrivateCaptureFilePublisher(root, ops), ManualWriter())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val command = command("close-during-third-publication")
            assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command))
            harness.writer.succeed("zero".bytes())
            harness.writer.succeed("one".bytes())

            val thirdPublication = executor.submit { harness.writer.succeed("two".bytes()) }
            assertTrue(ops.thirdDirectorySyncEntered.await(5, TimeUnit.SECONDS))
            harness.capture.close()
            ops.allowThirdDirectorySync.countDown()
            thirdPublication.get(5, TimeUnit.SECONDS)

            assertEquals(1, harness.successes.size)
            assertTrue(harness.failures.isEmpty())
            assertEquals(command.outputs, harness.successes.single().outputs.map { it.identity })
            assertEquals(listOf(0, 1, 2), harness.finalOrdinals())
            assertEquals(
                ThreePhotoCaptureSubmission.REJECTED_CLOSED,
                harness.submit(command("closed-after-exact-three")),
            )
        } finally {
            ops.allowThirdDirectorySync.countDown()
            executor.shutdownNow()
            harness.capture.close()
        }
    }

    @Test
    fun closeInFlightCurrentErrorCleansAndReportsWriteFailure() = withHarness { harness ->
        harness.submit(command("close-current-error"))
        harness.capture.close()

        harness.writer.fail()

        assertFailure(
            harness.failures.single(),
            failedOrdinal = 0,
            expectedPartialOrdinals = emptyList(),
            expectedStage = ThreePhotoCaptureFailureStage.WRITE,
        )
        assertTrue(harness.pendingFiles().isEmpty())
        assertEquals(ThreePhotoCaptureSubmission.REJECTED_CLOSED, harness.submit(command("still-closed")))
    }

    @Test
    fun throwingSuccessAndFailureCallbacksAreContainedAfterBusyReservationReleased() = withHarness { harness ->
        assertEquals(
            ThreePhotoCaptureSubmission.ACCEPTED,
            harness.capture.submit(
                command("throw-success"),
                onSuccess = { throw AssertionError("callback/private") },
                onFailure = { fail("unexpected failure") },
            ),
        )
        repeat(3) { harness.writer.succeed(byteArrayOf(1)) }
        assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command("after-throw-success")))
        harness.writer.fail()

        assertEquals(
            ThreePhotoCaptureSubmission.ACCEPTED,
            harness.capture.submit(
                command("throw-failure"),
                onSuccess = { fail("unexpected success") },
                onFailure = { throw AssertionError("callback/private") },
            ),
        )
        harness.writer.fail()
        assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command("after-throw-failure")))
    }

    @Test
    fun successAndFailureSnapshotsAreUnmodifiableFinalAndSecretFree() = withHarness { harness ->
        val rawToken = "../raw/private/token"
        harness.submit(command(rawToken))
        repeat(3) { harness.writer.succeed("bytes-$it".bytes()) }
        val success = harness.successes.single()
        assertThrows(UnsupportedOperationException::class.java) {
            (success.outputs as MutableList).clear()
        }
        assertFalse(success.toString().contains(rawToken))
        assertFalse(success.toString().contains(harness.root.path))

        harness.submit(command("failure-$rawToken"))
        harness.writer.fail()
        val failure = harness.failures.single()
        assertThrows(UnsupportedOperationException::class.java) {
            (failure.publishedOutputs as MutableList).clear()
        }
        assertFalse(failure.toString().contains(rawToken))
        assertFalse(failure.toString().contains(harness.root.path))
        assertTrue(
            (ThreePhotoCaptureSuccess::class.java.declaredFields +
                ThreePhotoCaptureFailure::class.java.declaredFields)
                .filterNot { it.isSynthetic }
                .all { Modifier.isFinal(it.modifiers) },
        )
    }

    @Test
    fun sourceContractUsesExactCameraXTempWriteAndExcludesCoordinatorAndSideEffectCapabilities() {
        val source = productionSource()
        listOf(
            "ImageCapture.OutputFileOptions.Builder(prepared.tempFile).build()",
            "imageCapture.takePicture(",
            "controller.requireImageCapture()",
            "androidPrivateCaptureFilePublisher(",
            "context.applicationContext.noBackupFilesDir.resolve(\"capture-candidates\")",
            "requireCompletePrivateOutputSet(command.outputs)",
        ).forEach { required -> assertTrue("Missing source marker: $required", required in source) }
        listOf(
            "Room",
            "MediaStore",
            "ShootEvent",
            ".reduce(",
            "dispatch",
            "confirm",
            "advance",
            "deleteRecursively",
            "Files.delete(final",
            "finalFile.delete",
            "android.util.Log",
            "Log.",
            "System.currentTimeMillis",
            "System.nanoTime",
            "java.time",
            "UUID",
            "Random",
            "java.net",
            "http://",
            "https://",
            "androidx.compose",
            "android.widget",
            "android.view",
        ).forEach { forbidden -> assertFalse("Forbidden source token: $forbidden", forbidden in source) }
    }

    private fun assertFailure(
        failure: ThreePhotoCaptureFailure,
        failedOrdinal: Int,
        expectedPartialOrdinals: List<Int>,
        expectedStage: ThreePhotoCaptureFailureStage,
        expectedCleanupPending: Boolean = false,
    ) {
        assertEquals(failedOrdinal, failure.failedIdentity.ordinal)
        assertEquals(expectedPartialOrdinals, failure.publishedOutputs.map { it.identity.ordinal })
        assertEquals(expectedStage, failure.stage)
        assertEquals(expectedCleanupPending, failure.cleanupPending)
    }

    private fun assertRetryableCleanupRecovery(fault: RetryCleanupFault) = withRoot { root ->
        val ops = RetryCleanupFaultFileOps(fault)
        val harness = Harness(root, PrivateCaptureFilePublisher(root, ops), ManualWriter())
        val command = command("retry-cleanup-${fault.name.lowercase()}/private-token")
        val failedIdentity = command.outputs[1]
        val failedFinal = root.resolve(finalName(failedIdentity))
        val failedPending = root.resolve(".${finalName(failedIdentity)}.pending")
        val unrelated = root.resolve("unrelated.keep").apply { writeText("foreign") }
        val rejectedSuccesses = mutableListOf<ThreePhotoCaptureSuccess>()
        val rejectedFailures = mutableListOf<ThreePhotoCaptureFailure>()
        try {
            assertEquals(ThreePhotoCaptureSubmission.ACCEPTED, harness.submit(command))
            harness.writer.succeed("kept-partial".bytes())
            harness.writer.fail()

            val failure = harness.failures.single()
            assertFailure(
                failure,
                failedOrdinal = 1,
                expectedPartialOrdinals = listOf(0),
                expectedStage = ThreePhotoCaptureFailureStage.CLEANUP,
                expectedCleanupPending = true,
            )
            assertTrue(failure.finalMayExist)
            assertFalse(failure.reconciliationRequired)
            assertFalse(failure.toString().contains(command.token.value))
            assertFalse(failure.toString().contains(root.path))
            assertTrue(
                ThreePhotoCaptureFailure::class.java.declaredFields.none {
                    Throwable::class.java.isAssignableFrom(it.type)
                },
            )
            assertEquals("kept-partial", root.resolve(finalName(command.outputs[0])).readText())
            when (fault) {
                RetryCleanupFault.DELETE_TEMP_ONCE -> {
                    assertTrue(failedPending.isFile)
                    assertTrue(failedFinal.isFile)
                    assertEquals(0L, failedFinal.length())
                }
                RetryCleanupFault.DELETE_RESERVATION_ONCE -> {
                    assertFalse(failedPending.exists())
                    assertTrue(failedFinal.isFile)
                    assertEquals(0L, failedFinal.length())
                }
                RetryCleanupFault.SYNC_CLEANUP_DIRECTORY_ONCE -> {
                    assertFalse(failedPending.exists())
                    assertFalse(failedFinal.exists())
                }
                RetryCleanupFault.DELETE_TEMP_ALWAYS,
                RetryCleanupFault.DELETE_RESERVATION_ALWAYS,
                RetryCleanupFault.BLOCK_SECOND_TEMP_DELETE ->
                    error("persistent/concurrent fault has a dedicated test")
            }
            assertEquals(
                ThreePhotoCaptureSubmission.REJECTED_BUSY,
                harness.capture.submit(
                    command("blocked-${fault.name.lowercase()}"),
                    rejectedSuccesses::add,
                    rejectedFailures::add,
                ),
            )
            assertEquals(1, harness.failures.size)
            assertTrue(rejectedSuccesses.isEmpty())
            assertTrue(rejectedFailures.isEmpty())

            assertEquals(ThreePhotoCleanupRetryOutcome.CLEANED, harness.capture.retryPendingCleanup())

            assertEquals(1, harness.failures.size)
            assertFalse(failedPending.exists())
            assertFalse(failedFinal.exists())
            assertEquals("kept-partial", root.resolve(finalName(command.outputs[0])).readText())
            assertEquals("foreign", unrelated.readText())
            assertEquals(ThreePhotoCleanupRetryOutcome.NO_CLEANUP_PENDING, harness.capture.retryPendingCleanup())
            assertEquals(
                ThreePhotoCaptureSubmission.ACCEPTED,
                harness.submit(command("accepted-after-${fault.name.lowercase()}")),
            )
        } finally {
            harness.capture.close()
        }
    }

    private fun command(rawToken: String): ShootEffect.CaptureCommand {
        val attempt = CaptureAttempt.create(
            token = CaptureToken(rawToken),
            trigger = CaptureTrigger.MANUAL,
            poseId = "pose-0",
            poseIndex = 0,
            attemptNumber = 0,
        )
        return ShootEffect.CaptureCommand(attempt)
    }

    private fun publishDirectly(
        publisher: PrivateCaptureFilePublisher,
        identity: PrivateOutputIdentity,
        bytes: ByteArray,
    ): PublishedPrivateOutput = publisher.prepare(identity).let { prepared ->
        prepared.tempFile.writeBytes(bytes)
        prepared.publish()
    }

    private fun String.bytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    private fun finalName(identity: PrivateOutputIdentity): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.token.value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$digest-${identity.ordinal}.jpg"
    }

    private inline fun withHarness(block: (Harness) -> Unit) = withRoot { root ->
        val publisher = PrivateCaptureFilePublisher(root)
        val harness = Harness(root, publisher, ManualWriter())
        try {
            block(harness)
        } finally {
            harness.capture.close()
        }
    }

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("three-photo-capture").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class Harness(
        val root: File,
        val publisher: PrivateCaptureFilePublisher,
        val writer: ManualWriter,
    ) {
        val successes = mutableListOf<ThreePhotoCaptureSuccess>()
        val failures = mutableListOf<ThreePhotoCaptureFailure>()
        val capture = CameraXThreePhotoCapture(writer, publisher)

        fun submit(command: ShootEffect.CaptureCommand): ThreePhotoCaptureSubmission =
            capture.submit(command, successes::add, failures::add)

        fun finalOrdinals(): List<Int> = successes.flatMap { it.outputs }.map { it.identity.ordinal } +
            root.listFiles().orEmpty()
                .filter { Regex("[0-9a-f]{64}-[0-2]\\.jpg").matches(it.name) }
                .filter { it.length() > 0L }
                .map { it.name.substringAfterLast('-').substringBefore('.').toInt() }
                .filterNot { ordinal -> successes.flatMap { it.outputs }.any { it.identity.ordinal == ordinal } }
                .sorted()

        fun pendingFiles(): List<File> = root.listFiles().orEmpty().filter { it.name.endsWith(".pending") }
    }

    private class ImmediateSuccessWriter : StillCaptureWriter {
        val startedOrdinals = mutableListOf<Int>()

        override fun write(prepared: PreparedPrivateOutput, callback: StillCaptureWriter.Callback) {
            startedOrdinals += prepared.identity.ordinal
            prepared.tempFile.writeBytes(byteArrayOf((prepared.identity.ordinal + 1).toByte()))
            callback.onImageSaved()
        }
    }

    private class ManualWriter : StillCaptureWriter {
        private data class Pending(
            val prepared: PreparedPrivateOutput,
            val callback: StillCaptureWriter.Callback,
        )

        private val pending = ArrayDeque<Pending>()
        val startedOrdinals = mutableListOf<Int>()
        var maxPendingCount = 0
            private set
        var throwOnOrdinal: Int? = null

        val pendingCount: Int
            get() = pending.size

        override fun write(prepared: PreparedPrivateOutput, callback: StillCaptureWriter.Callback) {
            startedOrdinals += prepared.identity.ordinal
            if (throwOnOrdinal == prepared.identity.ordinal) {
                throw IOException("writer/private/path/${prepared.identity.ordinal}")
            }
            pending.addLast(Pending(prepared, callback))
            maxPendingCount = maxOf(maxPendingCount, pending.size)
        }

        fun succeed(bytes: ByteArray) {
            val current = pending.removeFirst()
            current.prepared.tempFile.writeBytes(bytes)
            current.callback.onImageSaved()
        }

        fun fail() {
            pending.removeFirst().callback.onError()
        }
    }

    private enum class Fault {
        FIRST_DIRECTORY_SYNC,
        DELETE_TEMP,
    }

    private data class FaultReservationIdentity(val fileKey: Any) :
        PrivateCaptureReservationIdentity

    private class FaultFileOps(private val fault: Fault) : PrivateCaptureFileOps {
        private var consumed = false
        private var directorySyncCount = 0

        override fun reserve(finalPath: Path): PrivateCaptureReservationIdentity {
            FileChannel.open(finalPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.force(true)
            }
            return FaultReservationIdentity(requireNotNull(attributes(finalPath).fileKey()))
        }

        override fun createNew(path: Path) {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { }
        }

        override fun regularFileByteCount(path: Path): Long? =
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                Files.size(path)
            } else {
                null
            }

        override fun syncFile(path: Path) {
            FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        }

        override fun replaceOwnedReservation(
            tempPath: Path,
            finalPath: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            requireOwnedReservation(finalPath, identity)
            Files.move(
                tempPath,
                finalPath,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }

        override fun deleteOwnedReservation(
            finalPath: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            requireOwnedReservation(finalPath, identity)
            Files.delete(finalPath)
        }

        override fun syncDirectory(directory: Path) {
            directorySyncCount += 1
            if (!consumed && fault == Fault.FIRST_DIRECTORY_SYNC && directorySyncCount == 1) {
                consumed = true
                throw IOException("sync/private/path")
            }
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }

        override fun deleteIfExists(path: Path): Boolean {
            if (!consumed && fault == Fault.DELETE_TEMP) {
                consumed = true
                throw IOException("delete/private/path")
            }
            return Files.deleteIfExists(path)
        }

        private fun requireOwnedReservation(
            path: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            val expected = identity as? FaultReservationIdentity
                ?: throw PrivateCaptureReservationOwnershipMismatch()
            val actual = attributes(path)
            if (!actual.isRegularFile || actual.size() != 0L || actual.fileKey() != expected.fileKey) {
                throw PrivateCaptureReservationOwnershipMismatch()
            }
        }

        private fun attributes(path: Path): java.nio.file.attribute.BasicFileAttributes =
            Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
    }

    private enum class RetryCleanupFault {
        DELETE_TEMP_ONCE,
        DELETE_RESERVATION_ONCE,
        SYNC_CLEANUP_DIRECTORY_ONCE,
        DELETE_TEMP_ALWAYS,
        DELETE_RESERVATION_ALWAYS,
        BLOCK_SECOND_TEMP_DELETE,
    }

    private class RetryCleanupFaultFileOps(
        private val fault: RetryCleanupFault,
    ) : PrivateCaptureFileOps {
        val retryTempDeleteEntered = CountDownLatch(1)
        val allowRetryTempDelete = CountDownLatch(1)
        private var consumed = false
        private var reservationDeleted = false
        var tempDeleteAttempts = 0
            private set
        var reservationDeleteAttempts = 0
            private set
        var directorySyncAttempts = 0
            private set

        override fun reserve(finalPath: Path): PrivateCaptureReservationIdentity {
            FileChannel.open(finalPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.force(true)
            }
            return FaultReservationIdentity(requireNotNull(attributes(finalPath).fileKey()))
        }

        override fun createNew(path: Path) {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { }
        }

        override fun regularFileByteCount(path: Path): Long? =
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                Files.size(path)
            } else {
                null
            }

        override fun syncFile(path: Path) {
            FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        }

        override fun replaceOwnedReservation(
            tempPath: Path,
            finalPath: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            requireOwnedReservation(finalPath, identity)
            Files.move(
                tempPath,
                finalPath,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }

        override fun deleteOwnedReservation(
            finalPath: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            reservationDeleteAttempts += 1
            requireOwnedReservation(finalPath, identity)
            if (fault == RetryCleanupFault.DELETE_RESERVATION_ALWAYS) {
                throw IOException("delete-reservation/private/path")
            }
            if (!consumed && fault == RetryCleanupFault.DELETE_RESERVATION_ONCE) {
                consumed = true
                throw IOException("delete-reservation/private/path")
            }
            Files.delete(finalPath)
            reservationDeleted = true
        }

        override fun syncDirectory(directory: Path) {
            directorySyncAttempts += 1
            if (
                reservationDeleted &&
                !consumed &&
                fault == RetryCleanupFault.SYNC_CLEANUP_DIRECTORY_ONCE
            ) {
                consumed = true
                throw IOException("sync-cleanup/private/path")
            }
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            reservationDeleted = false
        }

        override fun deleteIfExists(path: Path): Boolean {
            tempDeleteAttempts += 1
            if (fault == RetryCleanupFault.DELETE_TEMP_ALWAYS) {
                throw IOException("delete-temp/private/path")
            }
            if (
                fault == RetryCleanupFault.BLOCK_SECOND_TEMP_DELETE &&
                tempDeleteAttempts == 1
            ) {
                throw IOException("delete-temp/private/path")
            }
            if (
                fault == RetryCleanupFault.BLOCK_SECOND_TEMP_DELETE &&
                tempDeleteAttempts == 2
            ) {
                retryTempDeleteEntered.countDown()
                check(allowRetryTempDelete.await(5, TimeUnit.SECONDS))
            }
            if (!consumed && fault == RetryCleanupFault.DELETE_TEMP_ONCE) {
                consumed = true
                throw IOException("delete-temp/private/path")
            }
            return Files.deleteIfExists(path)
        }

        private fun requireOwnedReservation(
            path: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            val expected = identity as? FaultReservationIdentity
                ?: throw PrivateCaptureReservationOwnershipMismatch()
            val actual = attributes(path)
            if (!actual.isRegularFile || actual.size() != 0L || actual.fileKey() != expected.fileKey) {
                throw PrivateCaptureReservationOwnershipMismatch()
            }
        }

        private fun attributes(path: Path): java.nio.file.attribute.BasicFileAttributes =
            Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
    }

    private class BlockingThirdDirectorySyncFileOps : PrivateCaptureFileOps {
        val thirdDirectorySyncEntered = CountDownLatch(1)
        val allowThirdDirectorySync = CountDownLatch(1)
        private var directorySyncCount = 0

        override fun reserve(finalPath: Path): PrivateCaptureReservationIdentity {
            FileChannel.open(finalPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.force(true)
            }
            return FaultReservationIdentity(requireNotNull(attributes(finalPath).fileKey()))
        }

        override fun createNew(path: Path) {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { }
        }

        override fun regularFileByteCount(path: Path): Long? =
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                Files.size(path)
            } else {
                null
            }

        override fun syncFile(path: Path) {
            FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        }

        override fun replaceOwnedReservation(
            tempPath: Path,
            finalPath: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            requireOwnedReservation(finalPath, identity)
            Files.move(
                tempPath,
                finalPath,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }

        override fun deleteOwnedReservation(
            finalPath: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            requireOwnedReservation(finalPath, identity)
            Files.delete(finalPath)
        }

        override fun syncDirectory(directory: Path) {
            directorySyncCount += 1
            if (directorySyncCount == 3) {
                thirdDirectorySyncEntered.countDown()
                check(allowThirdDirectorySync.await(5, TimeUnit.SECONDS))
            }
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }

        override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

        private fun requireOwnedReservation(
            path: Path,
            identity: PrivateCaptureReservationIdentity,
        ) {
            val expected = identity as? FaultReservationIdentity
                ?: throw PrivateCaptureReservationOwnershipMismatch()
            val actual = attributes(path)
            if (!actual.isRegularFile || actual.size() != 0L || actual.fileKey() != expected.fileKey) {
                throw PrivateCaptureReservationOwnershipMismatch()
            }
        }

        private fun attributes(path: Path): java.nio.file.attribute.BasicFileAttributes =
            Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
    }

    private fun productionSource(): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
        return projectRoot.resolve(SOURCE_PATH).readText()
    }

    private companion object {
        const val SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/camera/CameraXThreePhotoCapture.kt"
    }
}
