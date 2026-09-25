package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.data.AttemptRegistrationResult
import com.tonyisup.poseguidesnap.data.CaptureAttemptSettlementResult
import com.tonyisup.poseguidesnap.data.CaptureAttemptStartResult
import com.tonyisup.poseguidesnap.data.CaptureConfirmationResult
import com.tonyisup.poseguidesnap.data.CaptureExportTarget
import com.tonyisup.poseguidesnap.data.CaptureFileAdvanceRequest
import com.tonyisup.poseguidesnap.data.CaptureFileJournalResult
import com.tonyisup.poseguidesnap.data.CaptureFileOperationPaths
import com.tonyisup.poseguidesnap.data.CaptureFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage
import com.tonyisup.poseguidesnap.data.CaptureFileTransitionPolicy
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import com.tonyisup.poseguidesnap.domain.session.ShootEvent
import com.tonyisup.poseguidesnap.domain.session.ShootReducer
import com.tonyisup.poseguidesnap.domain.session.ShootState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournaledThreePhotoCaptureCoordinatorTest {
    @Test
    fun manualCommandOrdersThreeExactAdmittedWritesBeforeDurabilityAndConfirmation() = withRoot { root ->
        val command = manualCommand()
        val eventLog = mutableListOf<String>()
        val authority = FakeAuthority(eventLog)
        val files = FakeFiles(root, log = eventLog)
        val writer = FakeWriter(log = eventLog)
        val coordinator = coordinator(authority, files, writer)
        var captureResult: JournaledCaptureResult? = null

        assertEquals(
            JournaledCaptureSubmission.ACCEPTED,
            coordinator.submit("session-1", command) { captureResult = it },
        )

        val durable = captureResult as JournaledCaptureResult.Durable
        assertEquals(command.token, durable.command.token)
        assertEquals(listOf(0, 1, 2), writer.writtenOrdinals)
        command.outputs.forEach { identity ->
            assertEquals(
                CaptureFileOperationStage.FINAL_DURABLE,
                authority.snapshots.getValue(identity).stage,
            )
        }
        assertOrderedPerOrdinal(eventLog, command)

        val confirmation = ShootEffect.ConfirmAndAdvanceCapture(
            token = command.token,
            poseId = command.poseId,
            poseIndex = command.poseIndex,
            outputs = command.outputs,
        )
        var confirmationResult: JournaledConfirmationResult? = null
        assertEquals(
            JournaledCaptureSubmission.ACCEPTED,
            coordinator.confirm(confirmation) { confirmationResult = it },
        )

        assertTrue(confirmationResult is JournaledConfirmationResult.Advanced)
        assertEquals(3, authority.confirmedTargets?.size)
        assertEquals(command.outputs, authority.confirmedTargets?.map(CaptureExportTarget::identity))
        assertTrue(authority.confirmedTargets.orEmpty().all { it.intendedMimeType == "image/jpeg" })
    }

    @Test
    fun cameraFailureSettlesAndRunsExactRecoveryBeforeReportingRetryable() = withRoot { root ->
        val authority = FakeAuthority().apply {
            settlementResult = CaptureAttemptSettlementResult.ReconciliationRequired
        }
        val recovery = FakeRecovery(CaptureAttemptRestartRecoveryResult.FailedCleaned)
        val writer = FakeWriter(failingOrdinal = 1)
        val coordinator = coordinator(authority, FakeFiles(root), writer, recovery)
        var result: JournaledCaptureResult? = null

        coordinator.submit("session-1", manualCommand()) { result = it }

        assertTrue(result is JournaledCaptureResult.FailedCleaned)
        assertEquals(listOf(0, 1), writer.writtenOrdinals)
        assertEquals(listOf("session-1"), recovery.sessions)
        assertEquals(1, authority.settlementCount)
    }

    @Test
    fun ambiguousClaimCannotInvokeCameraAndOutstandingRecoveryBlocksTheAttempt() = withRoot { root ->
        val command = manualCommand()
        val authority = FakeAuthority().apply {
            settlementResult = CaptureAttemptSettlementResult.ReconciliationRequired
        }
        val recovery = FakeRecovery(
            CaptureAttemptRestartRecoveryResult.Outstanding(
                CaptureAttemptRestartRecoveryReason.FILES_PRESENT_OR_AMBIGUOUS,
            ),
        )
        val files = FakeFiles(root, ambiguousClaimOrdinal = 0)
        val writer = FakeWriter()
        val coordinator = coordinator(authority, files, writer, recovery)
        var result: JournaledCaptureResult? = null

        coordinator.submit("session-1", command) { result = it }

        val blocked = result as JournaledCaptureResult.ReconciliationRequired
        assertEquals(command.token, blocked.token)
        assertEquals(JournaledCaptureFailureReason.RECOVERY_OUTSTANDING, blocked.reason)
        assertTrue(writer.writtenOrdinals.isEmpty())
        assertEquals(1, authority.settlementCount)
        assertEquals(listOf("session-1"), recovery.sessions)
    }

    @Test
    fun clockExhaustionAfterRegistrationStartsNoFileEffectAndFailsClosed() = withRoot { root ->
        val command = manualCommand()
        val authority = FakeAuthority()
        val files = FakeFiles(root)
        val writer = FakeWriter()
        var clockCalls = 0
        val coordinator = JournaledThreePhotoCaptureCoordinator(
            authority = authority,
            files = files,
            writer = writer,
            recovery = FakeRecovery(CaptureAttemptRestartRecoveryResult.NoWork),
            clock = CaptureOperationClock { floor ->
                clockCalls += 1
                if (clockCalls == 1) floor + 1L else null
            },
            executor = DIRECT_EXECUTOR,
        )
        var result: JournaledCaptureResult? = null

        coordinator.submit("session-1", command) { result = it }

        val blocked = result as JournaledCaptureResult.ReconciliationRequired
        assertEquals(JournaledCaptureFailureReason.CLOCK_EXHAUSTED, blocked.reason)
        assertTrue(files.log.isEmpty())
        assertTrue(writer.writtenOrdinals.isEmpty())
        assertFalse(authority.started)
    }

    @Test
    fun unexpectedAuthorityFailureCompletesAsBlockingInsteadOfStrandingTheOwner() = withRoot { root ->
        val command = manualCommand()
        val authority = FakeAuthority().apply { throwOnStart = true }
        val files = FakeFiles(root)
        val coordinator = coordinator(authority, files, FakeWriter())
        var result: JournaledCaptureResult? = null

        coordinator.submit("session-1", command) { result = it }

        val blocked = result as JournaledCaptureResult.ReconciliationRequired
        assertEquals(command.token, blocked.token)
        assertEquals(JournaledCaptureFailureReason.RECOVERY_OUTSTANDING, blocked.reason)
        assertTrue(files.log.isEmpty())
        assertEquals(
            JournaledCaptureSubmission.ACCEPTED,
            coordinator.submit("session-1", manualCommand()) {},
        )
    }

    @Test
    fun oneOwnerRejectsConcurrentAndPostCloseSubmissions() = withRoot { root ->
        val writer = DeferredWriter()
        val coordinator = coordinator(FakeAuthority(), FakeFiles(root), writer)
        val first = manualCommand()
        val second = manualCommand(sessionId = "session-2", poseId = "pose-b")

        assertEquals(
            JournaledCaptureSubmission.ACCEPTED,
            coordinator.submit("session-1", first) {},
        )
        assertNotNull(writer.callback)
        assertEquals(
            JournaledCaptureSubmission.REJECTED_BUSY,
            coordinator.submit("session-2", second) {},
        )

        coordinator.close()
        assertEquals(
            JournaledCaptureSubmission.REJECTED_CLOSED,
            coordinator.submit("session-2", second) {},
        )
    }

    private fun coordinator(
        authority: FakeAuthority,
        files: FakeFiles,
        writer: JournaledStillCaptureWriter,
        recovery: FakeRecovery = FakeRecovery(CaptureAttemptRestartRecoveryResult.NoWork),
    ) = JournaledThreePhotoCaptureCoordinator(
        authority = authority,
        files = files,
        writer = writer,
        recovery = recovery,
        clock = CaptureOperationClock { floor -> if (floor == Long.MAX_VALUE) null else floor + 1L },
        executor = DIRECT_EXECUTOR,
    )

    private fun manualCommand(
        sessionId: String = "session-1",
        poseId: String = "pose-a",
    ): ShootEffect.CaptureCommand {
        val reducer = ShootReducer()
        val prepared = reducer.reduce(
            ShootState.initial(sessionId, listOf(poseId, "pose-2", "pose-3")),
            ShootEvent.PreparationCompleted(0L),
        ).nextState
        return reducer.reduce(prepared, ShootEvent.ManualCaptureRequested(1L))
            .effects.single() as ShootEffect.CaptureCommand
    }

    private fun assertOrderedPerOrdinal(
        eventLog: List<String>,
        command: ShootEffect.CaptureCommand,
    ) {
        val expected = buildList {
            add("register")
            add("start")
            command.outputs.forEach { identity ->
                val suffix = identity.ordinal.toString()
                addAll(
                    listOf(
                        "advance:EXPECTING_RESERVATION:WRITING_TEMP:$suffix",
                        "claim:$suffix",
                        "write:$suffix",
                        "sync:$suffix",
                        "advance:WRITING_TEMP:TEMP_SYNCED:$suffix",
                        "advance:TEMP_SYNCED:FINAL_RENAME_PENDING_SYNC:$suffix",
                        "publish:$suffix",
                        "advance:FINAL_RENAME_PENDING_SYNC:FINAL_DURABLE:$suffix",
                    ),
                )
            }
        }
        var previousIndex = -1
        var previousMarker = "start of log"
        expected.forEach { marker ->
            val index = eventLog.indexOf(marker)
            assertTrue("Missing $marker", index >= 0)
            assertTrue("Out of order after $previousMarker: $marker", index > previousIndex)
            previousIndex = index
            previousMarker = marker
        }
    }

    private class FakeAuthority(
        val log: MutableList<String> = mutableListOf(),
    ) : JournaledCaptureAuthorityPort {
        val snapshots = linkedMapOf<PrivateOutputIdentity, CaptureFileOperationSnapshot>()
        var settlementResult: CaptureAttemptSettlementResult =
            CaptureAttemptSettlementResult.FailedCleaned
        var settlementCount = 0
        var started = false
        var throwOnStart = false
        var confirmedTargets: List<CaptureExportTarget>? = null

        override fun register(
            sessionId: String,
            command: ShootEffect.CaptureCommand,
            recordedAtEpochMillis: Long,
        ): AttemptRegistrationResult {
            log += "register"
            command.outputs.forEach { identity ->
                snapshots[identity] = snapshot(identity, recordedAtEpochMillis)
            }
            return AttemptRegistrationResult.Registered
        }

        override fun start(
            sessionId: String,
            token: CaptureToken,
            startedAtEpochMillis: Long,
        ): CaptureAttemptStartResult {
            log += "start"
            if (throwOnStart) {
                throwOnStart = false
                throw IllegalStateException("generated authority failure")
            }
            started = true
            return CaptureAttemptStartResult.Started
        }

        override fun snapshot(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot? =
            snapshots[identity]

        override fun advance(request: CaptureFileAdvanceRequest): CaptureFileJournalResult {
            log += "advance:${request.expectedStage}:${request.targetStage}:${request.identity.ordinal}"
            val current = snapshots[request.identity]
                ?: return CaptureFileJournalResult.Rejected(
                    com.tonyisup.poseguidesnap.data.CaptureFileJournalRejectionReason.UNKNOWN_OPERATION,
                )
            val result = CaptureFileTransitionPolicy.advance(current, request)
            if (result is CaptureFileJournalResult.Applied) snapshots[request.identity] = result.snapshot
            return result
        }

        override fun settleFailure(
            sessionId: String,
            token: CaptureToken,
            settledAtEpochMillis: Long,
        ): CaptureAttemptSettlementResult {
            log += "settle"
            settlementCount += 1
            return settlementResult
        }

        override fun confirm(
            command: ShootEffect.ConfirmAndAdvanceCapture,
            targets: List<CaptureExportTarget>,
            confirmedAtEpochMillis: Long,
        ): CaptureConfirmationResult {
            log += "confirm"
            confirmedTargets = targets
            return CaptureConfirmationResult.Applied
        }

        private fun snapshot(identity: PrivateOutputIdentity, createdAt: Long) =
            CaptureFileOperationSnapshot(
                identity = identity,
                paths = CaptureFileOperationPaths.forIdentity(identity),
                stage = CaptureFileOperationStage.EXPECTING_RESERVATION,
                byteCount = null,
                sha256 = null,
                capturedAtEpochMillis = null,
                lastFailureCode = null,
                reconciliationRequired = false,
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis = createdAt,
            )
    }

    private class FakeFiles(
        private val root: java.nio.file.Path,
        private val ambiguousClaimOrdinal: Int? = null,
        val log: MutableList<String> = mutableListOf(),
    ) : JournaledCaptureFilePort {
        override fun claim(snapshot: CaptureFileOperationSnapshot): CaptureFileClaimResult {
            log += "claim:${snapshot.identity.ordinal}"
            if (snapshot.identity.ordinal == ambiguousClaimOrdinal) {
                return CaptureFileClaimResult.Ambiguous(
                    com.tonyisup.poseguidesnap.data.CaptureFileFailureCode.RESERVATION_FAILED,
                )
            }
            val ordinal = snapshot.identity.ordinal
            val temp = root.resolve("temp-$ordinal.jpg").toFile()
            temp.createNewFile()
            return CaptureFileClaimResult.Claimed(
                JournaledCaptureWriteLease(
                    identity = snapshot.identity,
                    tempFile = temp,
                    finalPath = root.resolve("final-$ordinal.jpg"),
                    tempPath = temp.toPath(),
                    quarantinePath = root.resolve("quarantine-$ordinal"),
                    finalIdentity = object : PrivateCaptureReservationIdentity {},
                    tempIdentity = Any(),
                ),
            )
        }

        override fun sync(
            snapshot: CaptureFileOperationSnapshot,
            lease: JournaledCaptureWriteLease,
            capturedAtEpochMillis: Long,
        ): CaptureTempSyncResult {
            log += "sync:${snapshot.identity.ordinal}"
            return CaptureTempSyncResult.Synced(
                JournaledCaptureEvidence(
                    byteCount = lease.tempFile.length(),
                    sha256 = "a".repeat(64),
                    capturedAtEpochMillis = capturedAtEpochMillis,
                ),
            )
        }

        override fun publish(
            snapshot: CaptureFileOperationSnapshot,
            lease: JournaledCaptureWriteLease,
            evidence: JournaledCaptureEvidence,
        ): CaptureFinalPublicationResult {
            log += "publish:${snapshot.identity.ordinal}"
            return CaptureFinalPublicationResult.Published(evidence)
        }
    }

    private class FakeWriter(
        private val failingOrdinal: Int? = null,
        val log: MutableList<String> = mutableListOf(),
    ) : JournaledStillCaptureWriter {
        val writtenOrdinals = mutableListOf<Int>()

        override fun write(tempFile: File, callback: JournaledStillCaptureWriter.Callback) {
            val ordinal = tempFile.name.removePrefix("temp-").substringBefore('.').toInt()
            writtenOrdinals += ordinal
            log += "write:$ordinal"
            if (ordinal == failingOrdinal) {
                callback.onError()
            } else {
                tempFile.writeBytes(byteArrayOf(ordinal.toByte(), 1))
                callback.onImageSaved()
            }
        }
    }

    private class DeferredWriter : JournaledStillCaptureWriter {
        var callback: JournaledStillCaptureWriter.Callback? = null

        override fun write(tempFile: File, callback: JournaledStillCaptureWriter.Callback) {
            this.callback = callback
        }
    }

    private class FakeRecovery(
        private val result: CaptureAttemptRestartRecoveryResult,
    ) : JournaledCaptureRecoveryPort {
        val sessions = mutableListOf<String>()

        override fun reconcile(sessionId: String): CaptureAttemptRestartRecoveryResult {
            sessions += sessionId
            return result
        }
    }

    private inline fun withRoot(block: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("journaled-coordinator")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        val DIRECT_EXECUTOR = Executor(Runnable::run)
    }
}
