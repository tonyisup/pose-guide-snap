package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.data.CaptureConfirmationResult
import com.tonyisup.poseguidesnap.data.CaptureExportTarget
import com.tonyisup.poseguidesnap.data.CaptureFileOperationPaths
import com.tonyisup.poseguidesnap.data.CaptureFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage
import com.tonyisup.poseguidesnap.data.GuidedBlockingAttemptSummary
import com.tonyisup.poseguidesnap.data.GuidedCaptureAttemptState
import com.tonyisup.poseguidesnap.data.GuidedCaptureTrigger
import com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapResult
import com.tonyisup.poseguidesnap.data.GuidedSessionLifecycle
import com.tonyisup.poseguidesnap.data.GuidedSessionSnapshot
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedSessionStartupReconcilerTest {
    @Test
    fun threeFinalDurableRowsAreConfirmedBeforeStartupSettles() {
        val port = FakeConfirmationPort()
        val reconciler = GuidedSessionStartupReconciler(
            recovery = JournaledCaptureRecoveryPort {
                CaptureAttemptRestartRecoveryResult.ReadyToConfirm
            },
            confirmation = port,
            clock = CaptureOperationClock { floor -> floor + 1L },
        )

        assertEquals(
            GuidedSessionStartupRecoveryResult.SETTLED,
            reconciler.reconcile(SESSION_ID),
        )
        assertEquals(TOKEN, port.confirmedCommand?.token)
        assertEquals(listOf(0, 1, 2), port.targets?.map { it.identity.ordinal })
        assertEquals(8L, port.confirmedAt)
    }

    @Test
    fun outstandingCleanupNeverAttemptsConfirmation() {
        val port = FakeConfirmationPort()
        val reconciler = GuidedSessionStartupReconciler(
            recovery = JournaledCaptureRecoveryPort {
                CaptureAttemptRestartRecoveryResult.Outstanding(
                    CaptureAttemptRestartRecoveryReason.CLEANUP_FAILED,
                )
            },
            confirmation = port,
            clock = CaptureOperationClock { floor -> floor + 1L },
        )

        assertEquals(
            GuidedSessionStartupRecoveryResult.OUTSTANDING,
            reconciler.reconcile(SESSION_ID),
        )
        assertEquals(null, port.confirmedCommand)
    }

    @Test
    fun nonFinalOrRejectedConfirmationRemainsBlocking() {
        val port = FakeConfirmationPort().apply {
            snapshots[PrivateOutputIdentity(TOKEN, 2)] = operation(
                PrivateOutputIdentity(TOKEN, 2),
                CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            )
        }
        val reconciler = GuidedSessionStartupReconciler(
            recovery = JournaledCaptureRecoveryPort {
                CaptureAttemptRestartRecoveryResult.ReadyToConfirm
            },
            confirmation = port,
            clock = CaptureOperationClock { floor -> floor + 1L },
        )
        assertEquals(
            GuidedSessionStartupRecoveryResult.OUTSTANDING,
            reconciler.reconcile(SESSION_ID),
        )
        assertEquals(null, port.confirmedCommand)

        port.snapshots[PrivateOutputIdentity(TOKEN, 2)] = operation(
            PrivateOutputIdentity(TOKEN, 2),
            CaptureFileOperationStage.FINAL_DURABLE,
        )
        port.confirmationResult = CaptureConfirmationResult.BlockedByDeletion
        assertEquals(
            GuidedSessionStartupRecoveryResult.OUTSTANDING,
            reconciler.reconcile(SESSION_ID),
        )
        assertTrue(port.confirmedCommand != null)
    }

    @Test
    fun systemClockSurvivesWallRollbackAndFailsClosedAtOverflow() {
        val clock = SystemCaptureOperationClock { 3L }

        assertEquals(10L, clock.nextAfter(9L))
        assertEquals(11L, clock.nextAfter(4L))
        assertEquals(null, clock.nextAfter(Long.MAX_VALUE))
    }

    private class FakeConfirmationPort : ReadyCaptureConfirmationPort {
        val snapshots = (0..2).associateTo(linkedMapOf()) { ordinal ->
            val identity = PrivateOutputIdentity(TOKEN, ordinal)
            identity to operation(identity, CaptureFileOperationStage.FINAL_DURABLE)
        }
        var confirmationResult: CaptureConfirmationResult = CaptureConfirmationResult.Applied
        var confirmedCommand: ShootEffect.ConfirmAndAdvanceCapture? = null
        var targets: List<CaptureExportTarget>? = null
        var confirmedAt: Long? = null

        override fun bootstrap(sessionId: String): GuidedSessionBootstrapResult =
            GuidedSessionBootstrapResult.ReconciliationRequired(blockedSnapshot())

        override fun snapshot(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot? =
            snapshots[identity]

        override fun confirm(
            command: ShootEffect.ConfirmAndAdvanceCapture,
            targets: List<CaptureExportTarget>,
            confirmedAtEpochMillis: Long,
        ): CaptureConfirmationResult {
            confirmedCommand = command
            this.targets = targets
            confirmedAt = confirmedAtEpochMillis
            return confirmationResult
        }
    }

    private companion object {
        const val SESSION_ID = "session-safe"
        val TOKEN = CaptureToken("s12:session-safep6:pose-0a0")

        fun blockedSnapshot() = GuidedSessionSnapshot(
            sessionId = SESSION_ID,
            shootId = "shoot-safe",
            lifecycle = GuidedSessionLifecycle.ACTIVE,
            orderedPoseIds = listOf("pose-0", "pose-1", "pose-2"),
            poseCount = 3,
            currentPoseIndex = 0,
            nextAttemptNumber = 1L,
            deletionGeneration = 0L,
            attemptCount = 1,
            confirmedAttemptCount = 0,
            appliedReceiptTokens = emptyList(),
            unresolvedExportCount = 0,
            blockingAttempt = GuidedBlockingAttemptSummary(
                attemptNumber = 0L,
                poseIndex = 0,
                state = GuidedCaptureAttemptState.CAPTURING,
                deletionGeneration = 0L,
                commandToken = TOKEN.value,
                poseId = "pose-0",
                trigger = GuidedCaptureTrigger.MANUAL,
                reconciliationRequired = false,
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 2L,
            ),
        )

        fun operation(
            identity: PrivateOutputIdentity,
            stage: CaptureFileOperationStage,
        ) = CaptureFileOperationSnapshot(
            identity = identity,
            paths = CaptureFileOperationPaths.forIdentity(identity),
            stage = stage,
            byteCount = 2L,
            sha256 = "a".repeat(64),
            capturedAtEpochMillis = 4L,
            lastFailureCode = null,
            reconciliationRequired = false,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 7L,
        )
    }
}
