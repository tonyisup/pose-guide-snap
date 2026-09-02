package com.tonyisup.poseguidesnap.ui.session

import com.tonyisup.poseguidesnap.data.GuidedBlockingAttemptSummary
import com.tonyisup.poseguidesnap.data.GuidedCaptureAttemptState
import com.tonyisup.poseguidesnap.data.GuidedCaptureTrigger
import com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason
import com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapResult
import com.tonyisup.poseguidesnap.data.GuidedSessionLifecycle
import com.tonyisup.poseguidesnap.data.GuidedSessionSnapshot
import com.tonyisup.poseguidesnap.ui.editor.StartedSessionHandle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartedSessionBootstrapWorkflowTest {
    @Test
    fun mapsEveryBootstrapResultWithExactIdentityAndRetryability() = runTest {
        val active = snapshot()
        val ready = load(GuidedSessionBootstrapResult.Ready(active))
        assertTrue(ready is StartedSessionBootstrapState.Ready)
        assertSame(active, (ready as StartedSessionBootstrapState.Ready).snapshot)

        assertSame(
            StartedSessionBootstrapState.Completed,
            load(GuidedSessionBootstrapResult.Completed(active.copy(lifecycle = GuidedSessionLifecycle.COMPLETED))),
        )
        assertSame(
            StartedSessionBootstrapState.ReconciliationRequired,
            load(GuidedSessionBootstrapResult.ReconciliationRequired(blockedSnapshot())),
        )
        assertSame(
            StartedSessionBootstrapState.Missing,
            load(GuidedSessionBootstrapResult.UnknownSession),
        )

        val rejectionCases = GuidedSessionBootstrapRejectionReason.entries.associateWith { reason ->
            if (reason == GuidedSessionBootstrapRejectionReason.AUTHORITY_UNAVAILABLE) {
                StartedSessionBootstrapState.Unavailable(canRetry = true)
            } else {
                StartedSessionBootstrapState.Unavailable(canRetry = false)
            }
        }
        assertEquals(GuidedSessionBootstrapRejectionReason.entries.toSet(), rejectionCases.keys)
        rejectionCases.forEach { (reason, expected) ->
            assertEquals(expected, load(GuidedSessionBootstrapResult.Rejected(reason)))
        }

        val mismatched = snapshot(sessionId = "session-other")
        listOf(
            GuidedSessionBootstrapResult.Ready(mismatched),
            GuidedSessionBootstrapResult.Completed(
                mismatched.copy(lifecycle = GuidedSessionLifecycle.COMPLETED),
            ),
            GuidedSessionBootstrapResult.ReconciliationRequired(
                blockedSnapshot(sessionId = "session-other"),
            ),
        ).forEach { result ->
            assertEquals(
                StartedSessionBootstrapState.Unavailable(canRetry = false),
                load(result),
            )
        }
    }

    @Test
    fun runtimeFailureIsRetryableAndRedactedWhileCancellationPropagates() = runTest {
        val marker = "raw-bootstrap-secret-content-private"
        val failureWorkflow = workflow(
            StartedSessionBootstrapRepositoryPort { throw IllegalStateException(marker) },
        )

        val unavailable = failureWorkflow.load(StartedSessionHandle(SESSION_ID))
        assertEquals(StartedSessionBootstrapState.Unavailable(canRetry = true), unavailable)
        assertFalse(unavailable.toString().contains(marker))
        assertFalse(failureWorkflow.toString().contains(marker))

        val cancellation = CancellationException(marker)
        val cancellationWorkflow = workflow(
            StartedSessionBootstrapRepositoryPort { throw cancellation },
        )
        val propagated = assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                cancellationWorkflow.load(StartedSessionHandle(SESSION_ID))
            }
        }
        assertSame(cancellation, propagated)
    }

    @Test
    fun statesEnforceReadyFactsAndRenderWithoutIdentityOrPayload() {
        val secret = "session-secret-123"
        val active = snapshot(sessionId = secret)
        val values = listOf(
            StartedSessionBootstrapState.Loading,
            StartedSessionBootstrapState.Ready(active),
            StartedSessionBootstrapState.Completed,
            StartedSessionBootstrapState.ReconciliationRequired,
            StartedSessionBootstrapState.Missing,
            StartedSessionBootstrapState.Unavailable(canRetry = true),
            StartedSessionBootstrapState.Unavailable(canRetry = false),
        )

        values.forEach { state ->
            assertFalse(state.toString().contains(secret))
            assertTrue(state.toString().length <= 96)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StartedSessionBootstrapState.Ready(
                active.copy(lifecycle = GuidedSessionLifecycle.COMPLETED),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StartedSessionBootstrapState.Ready(blockedSnapshot(sessionId = secret))
        }
        listOf(
            StartedSessionBootstrapState.Loading,
            StartedSessionBootstrapState.Completed,
            StartedSessionBootstrapState.ReconciliationRequired,
            StartedSessionBootstrapState.Missing,
            StartedSessionBootstrapState.Unavailable(canRetry = true),
        ).forEach { state ->
            assertTrue(
                state.javaClass.declaredFields.none {
                    GuidedSessionSnapshot::class.java.isAssignableFrom(it.type)
                },
            )
        }
    }

    @Test
    fun realNoncooperativeRepositoryLoadDefersExactlyOnceCloseAndDeniesNewWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val repositoryCalls = AtomicInteger()
        val receivedSessionId = AtomicReference<String>()
        val repository = StartedSessionBootstrapRepositoryPort { sessionId ->
            repositoryCalls.incrementAndGet()
            receivedSessionId.set(sessionId)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "controlled bootstrap release timed out" }
            GuidedSessionBootstrapResult.Ready(snapshot(sessionId))
        }
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "started-bootstrap-lease-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val caller = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "started-bootstrap-lease-caller")
        }
        val authority = StartedSessionResourceAuthority(closeCount::incrementAndGet)
        val workflow = RoomStartedSessionBootstrapWorkflow(repository, authority, dispatcher)
        try {
            val inFlight = caller.submit<StartedSessionBootstrapState> {
                kotlinx.coroutines.runBlocking {
                    workflow.load(StartedSessionHandle(SESSION_ID))
                }
            }
            assertTrue("repository load must enter", entered.await(5, TimeUnit.SECONDS))

            workflow.close()
            workflow.close()
            assertEquals(0, closeCount.get())
            assertNull(authority.tryAcquire())
            assertEquals(
                StartedSessionBootstrapState.Unavailable(canRetry = true),
                kotlinx.coroutines.runBlocking {
                    workflow.load(StartedSessionHandle(SESSION_ID))
                },
            )
            assertEquals(1, repositoryCalls.get())

            release.countDown()
            assertTrue(inFlight.get(5, TimeUnit.SECONDS) is StartedSessionBootstrapState.Ready)
            assertEquals(SESSION_ID, receivedSessionId.get())
            assertEquals(1, closeCount.get())
            workflow.close()
            assertEquals(1, closeCount.get())
        } finally {
            release.countDown()
            dispatcher.close()
            executor.shutdownNow()
            caller.shutdownNow()
        }
    }

    private fun workflow(
        repository: StartedSessionBootstrapRepositoryPort,
    ): RoomStartedSessionBootstrapWorkflow = RoomStartedSessionBootstrapWorkflow(
        repository = repository,
        authority = StartedSessionResourceAuthority {},
        blockingDispatcher = UnconfinedTestDispatcher(),
    )

    private suspend fun load(result: GuidedSessionBootstrapResult): StartedSessionBootstrapState =
        RoomStartedSessionBootstrapWorkflow(
            repository = StartedSessionBootstrapRepositoryPort { result },
            authority = StartedSessionResourceAuthority {},
            blockingDispatcher = UnconfinedTestDispatcher(),
        ).load(StartedSessionHandle(SESSION_ID))

    private fun snapshot(sessionId: String = SESSION_ID): GuidedSessionSnapshot =
        GuidedSessionSnapshot(
            sessionId = sessionId,
            shootId = "shoot-safe",
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

    private fun blockedSnapshot(sessionId: String = SESSION_ID): GuidedSessionSnapshot =
        snapshot(sessionId).copy(
            nextAttemptNumber = 1L,
            attemptCount = 1,
            blockingAttempt = GuidedBlockingAttemptSummary(
                attemptNumber = 0L,
                poseIndex = 0,
                state = GuidedCaptureAttemptState.REGISTERED,
                deletionGeneration = 0L,
                commandToken = "token-0",
                poseId = "pose-0",
                trigger = GuidedCaptureTrigger.MANUAL,
                reconciliationRequired = false,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
            ),
        )

    private companion object {
        const val SESSION_ID = "session-safe"
    }
}
