package com.tonyisup.poseguidesnap.ui.session

import com.tonyisup.poseguidesnap.data.GuidedSessionLifecycle
import com.tonyisup.poseguidesnap.data.GuidedSessionSnapshot
import com.tonyisup.poseguidesnap.ui.editor.StartedSessionHandle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartedSessionBootstrapViewModelTest {
    @Test
    fun initialLoadingBecomesReady() = runTest {
        val ready = StartedSessionBootstrapState.Ready(snapshot())
        val viewModel = viewModel(StartedSessionBootstrapWorkflowPort { ready })

        assertSame(StartedSessionBootstrapState.Loading, viewModel.state.value)
        runCurrent()
        assertSame(ready, viewModel.state.value)
    }

    @Test
    fun retrySuppressesLateNoncooperativePriorCompletion() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = StartedSessionBootstrapState.Ready(snapshot(shootId = "shoot-first"))
        val second = StartedSessionBootstrapState.Ready(snapshot(shootId = "shoot-second"))
        var calls = 0
        val workflow = StartedSessionBootstrapWorkflowPort {
            calls += 1
            if (calls == 1) {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { release.await() }
                    first
                }
            } else {
                second
            }
        }
        val viewModel = viewModel(workflow)
        runCurrent()

        viewModel.retry()
        runCurrent()
        assertSame(second, viewModel.state.value)
        assertEquals(2, calls)

        release.complete(Unit)
        runCurrent()
        assertSame(second, viewModel.state.value)
    }

    @Test
    fun terminalStatesDoNotReloadButRetryableUnavailableDoes() = runTest {
        val terminal = listOf(
            StartedSessionBootstrapState.Ready(snapshot()),
            StartedSessionBootstrapState.Completed,
            StartedSessionBootstrapState.ReconciliationRequired,
            StartedSessionBootstrapState.Missing,
            StartedSessionBootstrapState.Unavailable(canRetry = false),
        )
        terminal.forEach { result ->
            var calls = 0
            val viewModel = viewModel(StartedSessionBootstrapWorkflowPort { calls += 1; result })
            runCurrent()
            viewModel.retry()
            runCurrent()
            assertSame(result, viewModel.state.value)
            assertEquals(1, calls)
        }

        var retryCalls = 0
        val retryable = viewModel(StartedSessionBootstrapWorkflowPort {
            retryCalls += 1
            if (retryCalls == 1) StartedSessionBootstrapState.Unavailable(true)
            else StartedSessionBootstrapState.Missing
        })
        runCurrent()
        retryable.retry()
        runCurrent()
        assertSame(StartedSessionBootstrapState.Missing, retryable.state.value)
        assertEquals(2, retryCalls)
    }

    @Test
    fun generationOverflowBlocksLateMaxCompletionAndClearCancelsAndClosesOnce() = runTest {
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val workflow = StartedSessionBootstrapWorkflowPort {
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                cancelled.complete(Unit)
                withContext(NonCancellable) { release.await() }
                StartedSessionBootstrapState.Ready(snapshot())
            }
        }
        val closes = AtomicInteger()
        val viewModel = viewModel(workflow, closes::incrementAndGet)
        runCurrent()
        val generation = viewModel.javaClass.getDeclaredField("loadGeneration").apply {
            isAccessible = true
        }
        generation.setLong(viewModel, Long.MAX_VALUE - 1L)

        viewModel.retry()
        runCurrent()
        assertEquals(Long.MAX_VALUE, generation.getLong(viewModel))
        viewModel.retry()
        assertEquals(StartedSessionBootstrapState.Unavailable(false), viewModel.state.value)
        release.complete(Unit)
        runCurrent()
        assertEquals(StartedSessionBootstrapState.Unavailable(false), viewModel.state.value)

        invokeOnCleared(viewModel)
        invokeOnCleared(viewModel)
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertEquals(1, closes.get())
        viewModel.retry()
        assertEquals(StartedSessionBootstrapState.Unavailable(false), viewModel.state.value)
    }

    private fun TestScope.viewModel(
        workflow: StartedSessionBootstrapWorkflowPort,
        close: () -> Unit = {},
    ): StartedSessionBootstrapViewModel = StartedSessionBootstrapViewModel(
        handle = StartedSessionHandle("session-safe"),
        workflow = workflow,
        dispatcher = StandardTestDispatcher(testScheduler),
        closeAuthority = close,
    )

    private fun snapshot(shootId: String = "shoot-safe") = GuidedSessionSnapshot(
        sessionId = "session-safe",
        shootId = shootId,
        lifecycle = GuidedSessionLifecycle.ACTIVE,
        orderedPoseIds = listOf("pose-0", "pose-1", "pose-2"),
        poseCount = 3,
        currentPoseIndex = 0,
        nextAttemptNumber = 0L,
        deletionGeneration = 0L,
        attemptCount = 0,
        confirmedAttemptCount = 0,
        appliedReceiptTokens = emptyList(),
        unresolvedExportCount = 0,
        blockingAttempt = null,
    )

    private fun invokeOnCleared(viewModel: StartedSessionBootstrapViewModel) {
        viewModel.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(viewModel)
    }
}
