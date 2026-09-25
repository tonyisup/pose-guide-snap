package com.tonyisup.poseguidesnap.ui.camera

import androidx.lifecycle.ViewModel
import com.tonyisup.poseguidesnap.camera.JournaledCaptureResult
import com.tonyisup.poseguidesnap.camera.JournaledCaptureSubmission
import com.tonyisup.poseguidesnap.camera.JournaledConfirmationResult
import com.tonyisup.poseguidesnap.camera.JournaledStillCaptureWriter
import com.tonyisup.poseguidesnap.data.GuidedCurrentReferenceResult
import com.tonyisup.poseguidesnap.data.GuidedReferenceSnapshot
import com.tonyisup.poseguidesnap.data.GuidedSessionLifecycle
import com.tonyisup.poseguidesnap.data.GuidedSessionSnapshot
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuidedCameraViewModelTest {
    @Test
    fun manualCaptureUsesReducerThenDurabilityThenAtomicAdvancement() = runTest {
        val workflow = FakeWorkflow(
            references = ArrayDeque(
                listOf(
                    GuidedCurrentReferenceResult.Ready(reference("pose-0", "First pose")),
                    GuidedCurrentReferenceResult.Ready(reference("pose-1", "Second pose")),
                ),
            ),
        )
        val viewModel = viewModel(workflow, snapshot())
        runCurrent()
        viewModel.setCameraReady(true)
        assertTrue(viewModel.state.value.captureEnabled)

        viewModel.manualCapture()
        runCurrent()
        val command = requireNotNull(workflow.captureCommand)
        assertEquals(GuidedCameraPhase.CAPTURING, viewModel.state.value.phase)
        assertEquals(listOf(0, 1, 2), command.outputs.map { it.ordinal })

        workflow.captureCallback?.invoke(JournaledCaptureResult.Durable(command))
        runCurrent()
        val confirmation = requireNotNull(workflow.confirmationCommand)
        assertEquals(GuidedCameraPhase.CONFIRMING, viewModel.state.value.phase)
        assertEquals(command.token, confirmation.token)

        workflow.confirmationCallback?.invoke(
            JournaledConfirmationResult.Advanced(command.token),
        )
        runCurrent()

        assertEquals(GuidedCameraPhase.READY, viewModel.state.value.phase)
        assertEquals(2, viewModel.state.value.currentPoseNumber)
        assertEquals("pose-1", viewModel.state.value.reference?.poseId)
        assertEquals(listOf(SESSION_ID, SESSION_ID), workflow.loadedSessions)
    }

    @Test
    fun stopDuringAcceptedCaptureWaitsForCleanFailureBeforeRouteExit() = runTest {
        val workflow = FakeWorkflow(reference("pose-0", "First pose"))
        val viewModel = viewModel(workflow, snapshot())
        runCurrent()
        viewModel.setCameraReady(true)
        viewModel.manualCapture()
        runCurrent()
        val command = requireNotNull(workflow.captureCommand)

        viewModel.stop()
        assertEquals(GuidedCameraPhase.STOPPING, viewModel.state.value.phase)
        assertFalse(viewModel.state.value.stopEnabled)

        workflow.captureCallback?.invoke(JournaledCaptureResult.FailedCleaned(command.token))
        runCurrent()

        assertEquals(GuidedCameraPhase.STOPPED, viewModel.state.value.phase)
        assertFalse(viewModel.state.value.captureEnabled)
    }

    @Test
    fun idleStopIsImmediateAndAmbiguousCaptureRemainsVisiblyBlocking() = runTest {
        val idleWorkflow = FakeWorkflow(reference("pose-0", "First pose"))
        val idle = viewModel(idleWorkflow, snapshot())
        runCurrent()
        idle.stop()
        assertEquals(GuidedCameraPhase.STOPPED, idle.state.value.phase)

        val blockedWorkflow = FakeWorkflow(reference("pose-0", "First pose"))
        val blocked = viewModel(blockedWorkflow, snapshot())
        runCurrent()
        blocked.setCameraReady(true)
        blocked.manualCapture()
        runCurrent()
        val command = requireNotNull(blockedWorkflow.captureCommand)
        blockedWorkflow.captureCallback?.invoke(
            JournaledCaptureResult.ReconciliationRequired(
                command.token,
                com.tonyisup.poseguidesnap.camera.JournaledCaptureFailureReason
                    .RECOVERY_OUTSTANDING,
            ),
        )
        runCurrent()

        assertEquals(GuidedCameraPhase.NEEDS_REPAIR, blocked.state.value.phase)
        assertFalse(blocked.state.value.captureEnabled)
    }

    @Test
    fun finalPoseConfirmationCompletesWithoutLoadingAnotherReference() = runTest {
        val workflow = FakeWorkflow(reference("pose-2", "Last pose"))
        val viewModel = viewModel(workflow, snapshot(currentPoseIndex = 2))
        runCurrent()
        viewModel.setCameraReady(true)
        viewModel.manualCapture()
        runCurrent()
        val command = requireNotNull(workflow.captureCommand)
        workflow.captureCallback?.invoke(JournaledCaptureResult.Durable(command))
        runCurrent()
        workflow.confirmationCallback?.invoke(JournaledConfirmationResult.Advanced(command.token))
        runCurrent()

        assertEquals(GuidedCameraPhase.COMPLETED, viewModel.state.value.phase)
        assertEquals(3, viewModel.state.value.currentPoseNumber)
        assertEquals(1, workflow.loadedSessions.size)
    }

    @Test
    fun clearDetachesWriterAndClosesWorkflowOnce() = runTest {
        val workflow = FakeWorkflow(reference("pose-0", "First pose"))
        val viewModel = viewModel(workflow, snapshot())
        runCurrent()
        val writer = object : JournaledStillCaptureWriter {
            override fun write(
                tempFile: File,
                callback: JournaledStillCaptureWriter.Callback,
            ) = Unit
        }
        viewModel.attachWriter(writer)

        invokeOnCleared(viewModel)
        invokeOnCleared(viewModel)

        assertEquals(listOf(writer), workflow.detachedWriters)
        assertEquals(1, workflow.closeCount)
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        workflow: FakeWorkflow,
        snapshot: GuidedSessionSnapshot,
    ) = GuidedCameraViewModel(
        snapshot = snapshot,
        workflow = workflow,
        eventClockNanos = IncreasingClock()::next,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private class FakeWorkflow(
        private val references: ArrayDeque<GuidedCurrentReferenceResult>,
    ) : GuidedCaptureWorkflowPort {
        constructor(reference: GuidedReferenceSnapshot) : this(
            ArrayDeque(listOf(GuidedCurrentReferenceResult.Ready(reference))),
        )

        val loadedSessions = mutableListOf<String>()
        val detachedWriters = mutableListOf<JournaledStillCaptureWriter>()
        var captureCommand: ShootEffect.CaptureCommand? = null
        var captureCallback: ((JournaledCaptureResult) -> Unit)? = null
        var confirmationCommand: ShootEffect.ConfirmAndAdvanceCapture? = null
        var confirmationCallback: ((JournaledConfirmationResult) -> Unit)? = null
        var closeCount = 0

        override suspend fun loadCurrentReference(sessionId: String): GuidedCurrentReferenceResult {
            loadedSessions += sessionId
            return references.removeFirst()
        }

        override fun attachWriter(writer: JournaledStillCaptureWriter) = Unit

        override fun detachWriter(writer: JournaledStillCaptureWriter) {
            detachedWriters += writer
        }

        override fun capture(
            sessionId: String,
            command: ShootEffect.CaptureCommand,
            callback: (JournaledCaptureResult) -> Unit,
        ): JournaledCaptureSubmission {
            captureCommand = command
            captureCallback = callback
            return JournaledCaptureSubmission.ACCEPTED
        }

        override fun confirm(
            command: ShootEffect.ConfirmAndAdvanceCapture,
            callback: (JournaledConfirmationResult) -> Unit,
        ): JournaledCaptureSubmission {
            confirmationCommand = command
            confirmationCallback = callback
            return JournaledCaptureSubmission.ACCEPTED
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class IncreasingClock {
        private var value = 0L
        fun next(): Long = value++
    }

    private fun snapshot(currentPoseIndex: Int = 0): GuidedSessionSnapshot {
        val receipts = (0 until currentPoseIndex).map { index ->
            "s12:${SESSION_ID}p6:pose-${index}a$index"
        }
        return GuidedSessionSnapshot(
            sessionId = SESSION_ID,
            shootId = "shoot-safe",
            lifecycle = GuidedSessionLifecycle.ACTIVE,
            orderedPoseIds = listOf("pose-0", "pose-1", "pose-2"),
            poseCount = 3,
            currentPoseIndex = currentPoseIndex,
            nextAttemptNumber = currentPoseIndex.toLong(),
            deletionGeneration = 0L,
            attemptCount = currentPoseIndex,
            confirmedAttemptCount = currentPoseIndex,
            appliedReceiptTokens = receipts,
            unresolvedExportCount = currentPoseIndex * 3,
            blockingAttempt = null,
        )
    }

    private fun reference(poseId: String, label: String) = GuidedReferenceSnapshot(
        poseId = poseId,
        label = label,
        relativeAssetPath = "reference-assets/assets/${"a".repeat(64)}.asset",
        mirrorAllowed = false,
        landmarks = listOf(
            Landmark(PoseLandmark.NOSE, 0.5, 0.2, 0.0, 0.9, 0.9),
        ),
        imageSize = PoseImageSize(1920, 1080),
    )

    private fun invokeOnCleared(viewModel: ViewModel) {
        viewModel.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }
            .invoke(viewModel)
    }

    private companion object {
        const val SESSION_ID = "session-safe"
    }
}
