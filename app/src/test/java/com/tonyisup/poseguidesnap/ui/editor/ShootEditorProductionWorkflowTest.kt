package com.tonyisup.poseguidesnap.ui.editor

import android.net.Task11bTestUri
import com.tonyisup.poseguidesnap.data.ActiveGuidedSessionRejectionReason
import com.tonyisup.poseguidesnap.data.ActiveGuidedSessionResult
import com.tonyisup.poseguidesnap.data.ImportWorkStatus
import com.tonyisup.poseguidesnap.data.ImportWorkSummary
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import com.tonyisup.poseguidesnap.data.ShootEditorSnapshot
import com.tonyisup.poseguidesnap.data.ShootPreparationLifecycle
import com.tonyisup.poseguidesnap.data.ShootReorderResult
import com.tonyisup.poseguidesnap.data.ShootStartIneligibleReason
import com.tonyisup.poseguidesnap.data.ShootStartInvalidReason
import com.tonyisup.poseguidesnap.data.ShootStartResult
import com.tonyisup.poseguidesnap.data.ValidatedReferenceSummary
import com.tonyisup.poseguidesnap.importer.ReferenceImportAllocationBlockReason
import com.tonyisup.poseguidesnap.importer.ReferenceImportAllocationResult
import com.tonyisup.poseguidesnap.importer.ReferenceImportAllocationRequest
import com.tonyisup.poseguidesnap.importer.ReferenceImportLedgerTimeline
import com.tonyisup.poseguidesnap.importer.ReferenceImportOutcome
import com.tonyisup.poseguidesnap.importer.ReferenceImportOutcomeStatus
import com.tonyisup.poseguidesnap.importer.ReferenceImportRetryAction
import com.tonyisup.poseguidesnap.importer.ReferencePickerImportDraft
import com.tonyisup.poseguidesnap.importer.ReferencePickerResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShootEditorProductionWorkflowTest {
    @Test
    fun snapshotProjectionContainsOnlySafeDisplayFieldsAndRejectsWrongShoot() = runTest {
        val repository = FakeRoomPort(snapshot = snapshot())
        val workflow = workflow(repository = repository)

        val display = requireNotNull(workflow.observeEditorSnapshot(SHOOT_ID).first())

        assertEquals("Studio", display.name)
        assertEquals(ShootPreparationLifecycle.ACTIVE, display.lifecycle)
        assertEquals(listOf("pose-a", "pose-b", "pose-c"), display.references.map { it.poseId })
        assertEquals(listOf(ImportWorkStatus.RECONCILIATION_REQUIRED), display.importWorkStatuses)
        assertFalse(display.toString().contains("9876"))
        repository.snapshot = snapshot(shootId = "other-shoot")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { workflow.observeEditorSnapshot(SHOOT_ID).first() }
        }
    }

    @Test
    fun terminalImportHistoryIsOmittedWhileCurrentBlockingWorkIsPreserved() = runTest {
        val terminalHistory = (0 until 100).map { index ->
            ImportWorkSummary(
                ImportWorkStatus.REJECTED_QUARANTINED,
                index.toLong(),
                index.toLong() + 1L,
            )
        }
        val repository = FakeRoomPort(snapshot(importWork = terminalHistory))
        val workflow = workflow(repository = repository)

        val withoutBlocker = requireNotNull(workflow.observeEditorSnapshot(SHOOT_ID).first())
        assertTrue(withoutBlocker.importWorkStatuses.isEmpty())

        val blocker = ImportWorkSummary(ImportWorkStatus.RECONCILIATION_REQUIRED, 200L, 201L)
        repository.snapshot = snapshot(importWork = terminalHistory + blocker)
        val withBlocker = requireNotNull(workflow.observeEditorSnapshot(SHOOT_ID).first())
        assertEquals(
            listOf(ImportWorkStatus.RECONCILIATION_REQUIRED),
            withBlocker.importWorkStatuses,
        )
    }

    @Test
    fun activeProjectionQueriesExactAuthorityAndMapsExactOrNoneWithoutIdentityLeak() = runTest {
        val activeSessions = FakeActiveSessionPort(ActiveGuidedSessionResult.Exact("session-exact"))
        val workflow = workflow(activeSessions = activeSessions)

        val exact = requireNotNull(workflow.observeEditorSnapshot(SHOOT_ID).first())
        activeSessions.result = ActiveGuidedSessionResult.None
        val none = requireNotNull(workflow.observeEditorSnapshot(SHOOT_ID).first())

        assertTrue(exact.hasResumableSession)
        assertFalse(none.hasResumableSession)
        assertEquals(listOf(SHOOT_ID, SHOOT_ID), activeSessions.shootIds)
        assertFalse(exact.toString().contains("session-exact"))
    }

    @Test
    fun nullAndDeletingProjectionSkipDiscoveryWhileDeletingStillRendersFalse() = runTest {
        val activeSessions = FakeActiveSessionPort(ActiveGuidedSessionResult.Exact("session-unused"))
        val nullDisplay = workflow(
            repository = FakeRoomPort(null),
            activeSessions = activeSessions,
        ).observeEditorSnapshot(SHOOT_ID).first()
        val deletingDisplay = workflow(
            repository = FakeRoomPort(snapshot(lifecycle = ShootPreparationLifecycle.DELETING)),
            activeSessions = activeSessions,
        ).observeEditorSnapshot(SHOOT_ID).first()

        assertNull(nullDisplay)
        assertEquals(ShootPreparationLifecycle.DELETING, requireNotNull(deletingDisplay).lifecycle)
        assertFalse(deletingDisplay.hasResumableSession)
        assertEquals(0, activeSessions.shootIds.size)
    }

    @Test
    fun activeProjectionRejectsUnknownOrRejectedDiscoveryInsteadOfLyingFalse() {
        val cases = listOf(
            ActiveGuidedSessionResult.UnknownShoot,
            ActiveGuidedSessionResult.Rejected(ActiveGuidedSessionRejectionReason.INVALID_REQUEST),
            ActiveGuidedSessionResult.Rejected(ActiveGuidedSessionRejectionReason.AUTHORITY_INCONSISTENT),
            ActiveGuidedSessionResult.Rejected(ActiveGuidedSessionRejectionReason.AUTHORITY_UNAVAILABLE),
        )

        cases.forEach { result ->
            val failure = assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    workflow(activeSessions = FakeActiveSessionPort(result))
                        .observeEditorSnapshot(SHOOT_ID)
                        .first()
                }
            }
            assertFalse(failure.toString().contains(SHOOT_ID))
        }
    }

    @Test
    fun resumeFreshlyMapsExactStaleAndEveryClosedRejectionReason() = runTest {
        val activeSessions = FakeActiveSessionPort(ActiveGuidedSessionResult.Exact("session-fresh"))
        val workflow = workflow(activeSessions = activeSessions)

        val exact = workflow.resume(SHOOT_ID) as ShootEditorResumeOutcome.Resumable
        assertEquals("session-fresh", exact.handle.navigationKey)

        listOf(ActiveGuidedSessionResult.None, ActiveGuidedSessionResult.UnknownShoot).forEach { result ->
            activeSessions.result = result
            assertSame(ShootEditorResumeOutcome.Stale, workflow.resume(SHOOT_ID))
        }
        val rejectionCases = mapOf(
            ActiveGuidedSessionRejectionReason.INVALID_REQUEST to
                ShootEditorResumeRejectionReason.INVALID_REQUEST,
            ActiveGuidedSessionRejectionReason.AUTHORITY_INCONSISTENT to
                ShootEditorResumeRejectionReason.AUTHORITY_INCONSISTENT,
            ActiveGuidedSessionRejectionReason.AUTHORITY_UNAVAILABLE to
                ShootEditorResumeRejectionReason.AUTHORITY_UNAVAILABLE,
        )
        assertEquals(ActiveGuidedSessionRejectionReason.entries.toSet(), rejectionCases.keys)
        rejectionCases.forEach { (reason, expected) ->
            activeSessions.result = ActiveGuidedSessionResult.Rejected(reason)
            val rejected = workflow.resume(SHOOT_ID) as ShootEditorResumeOutcome.Rejected
            assertSame(expected, rejected.reason)
        }
        assertEquals(6, activeSessions.shootIds.size)
    }

    @Test
    fun resumeContainsRuntimeFailureButPropagatesCancellationWithoutRawLeak() = runTest {
        val marker = "raw-resume-secret content://private/resume"
        val activeSessions = FakeActiveSessionPort(ActiveGuidedSessionResult.None)
        val workflow = workflow(activeSessions = activeSessions)

        activeSessions.failure = IllegalStateException(marker)
        val unavailable = workflow.resume(SHOOT_ID) as ShootEditorResumeOutcome.Rejected
        assertSame(ShootEditorResumeRejectionReason.AUTHORITY_UNAVAILABLE, unavailable.reason)
        assertFalse(unavailable.toString().contains(marker))

        val cancellation = CancellationException(marker)
        activeSessions.failure = cancellation
        val propagated = assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { workflow.resume(SHOOT_ID) }
        }
        assertSame(cancellation, propagated)
    }

    @Test
    fun allocationCapabilityIsFieldlessBoundedAndConsumedExactlyOnce() = runTest {
        val registry = ShootEditorPickerRegistry()
        val application = FakeApplication(ReferenceImportAllocationResult.Ready(draft()))
        val authority = DeferredShootEditorResourceAuthority({}, registry::invalidate)
        val workflow = workflow(application = application, registry = registry, authority = authority)
        val handlerCalls = AtomicInteger()
        val receivedUri = AtomicReference<android.net.Uri?>()
        val coordinator = ShootEditorPickerCoordinator(
            registry = registry,
            handler = ShootEditorPickerHandlerPort { uri, receivedDraft ->
                handlerCalls.incrementAndGet()
                receivedUri.set(uri)
                assertSame(application.readyDraft, receivedDraft)
                ReferencePickerResult.InvalidSelection
            },
            authority = authority,
        )

        val first = workflow.allocateImport(SHOOT_ID, "Side") as ShootEditorImportAllocationOutcome.Ready
        assertEquals(0, first.launch.javaClass.declaredFields.size)
        val second = workflow.allocateImport(SHOOT_ID, "Side") as ShootEditorImportAllocationOutcome.Ready
        assertNotSame(first.launch, second.launch)
        val uri = Task11bTestUri.from("content://picker/item")

        assertSame(ReferencePickerResult.Cancelled, coordinator.handle(uri, first.launch))
        assertEquals(0, handlerCalls.get())
        assertSame(ReferencePickerResult.InvalidSelection, coordinator.handle(uri, second.launch))
        assertSame(uri, receivedUri.get())
        assertSame(ReferencePickerResult.Cancelled, coordinator.handle(uri, second.launch))
        assertEquals(1, handlerCalls.get())
        assertNull(ShootEditorPickerCoordinator::class.java.declaredFields.firstOrNull {
            android.net.Uri::class.java.isAssignableFrom(it.type)
        })
    }

    @Test
    fun terminalRegistryInvalidationRejectsLateDraftReplacementAndRetainsNothing() {
        val registry = ShootEditorPickerRegistry()
        val original = registry.replace(draft())
        registry.invalidate()

        assertNull(registry.consume(original))
        assertThrows(IllegalStateException::class.java) {
            registry.replace(draft())
        }
        val retainedDraft = ShootEditorPickerRegistry::class.java
            .getDeclaredField("authorizedDraft")
            .apply { isAccessible = true }
            .get(registry)
        assertNull(retainedDraft)
    }

    @Test
    fun allocationCompletingAfterCloseCannotReauthorizeOrRetainItsDraft() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val registry = ShootEditorPickerRegistry()
        val authority = DeferredShootEditorResourceAuthority(
            closeResources = closeCount::incrementAndGet,
            invalidatePicker = registry::invalidate,
        )
        val application = object : ShootEditorImportApplicationPort {
            override fun allocate(request: ReferenceImportAllocationRequest): ReferenceImportAllocationResult {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "controlled allocation release timed out" }
                return ReferenceImportAllocationResult.Ready(draft())
            }

            override fun classify(result: ReferencePickerResult): ReferenceImportOutcome =
                ReferenceImportOutcome(
                    ReferenceImportOutcomeStatus.AUTHORITY_UNAVAILABLE,
                    ReferenceImportRetryAction.RETRY_ALLOCATION,
                )
        }
        val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "editor-allocation-test")
        }
        val dispatcher = worker.asCoroutineDispatcher()
        val caller = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "editor-allocation-caller")
        }
        try {
            val workflow = workflow(
                application = application,
                registry = registry,
                authority = authority,
                dispatcher = dispatcher,
            )
            val result = caller.submit<ShootEditorImportAllocationOutcome> {
                kotlinx.coroutines.runBlocking { workflow.allocateImport(SHOOT_ID, "Side") }
            }
            assertTrue("allocation must enter", entered.await(5, TimeUnit.SECONDS))

            authority.close()
            assertEquals(0, closeCount.get())
            release.countDown()

            val outcome = result.get(5, TimeUnit.SECONDS)
            assertTrue(outcome is ShootEditorImportAllocationOutcome.Blocked)
            outcome as ShootEditorImportAllocationOutcome.Blocked
            assertEquals(ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE, outcome.reason)
            assertEquals(1, closeCount.get())
            val retainedDraft = ShootEditorPickerRegistry::class.java
                .getDeclaredField("authorizedDraft")
                .apply { isAccessible = true }
                .get(registry)
            assertNull(retainedDraft)
        } finally {
            release.countDown()
            dispatcher.close()
            worker.shutdownNow()
            caller.shutdownNow()
        }
    }

    @Test
    fun closeInvalidatesPickerAndDefersPhysicalCloseUntilNoncooperativeLeaseReturns() = runTest {
        val closes = AtomicInteger()
        val registry = ShootEditorPickerRegistry()
        val authority = DeferredShootEditorResourceAuthority(closes::incrementAndGet, registry::invalidate)
        val application = FakeApplication(ReferenceImportAllocationResult.Ready(draft()))
        val workflow = workflow(application = application, registry = registry, authority = authority)
        val coordinator = ShootEditorPickerCoordinator(
            registry,
            ShootEditorPickerHandlerPort { _, _ -> error("provider must not run") },
            authority,
        )
        val launch = (workflow.allocateImport(SHOOT_ID, "Side") as ShootEditorImportAllocationOutcome.Ready).launch
        val lease = requireNotNull(authority.tryAcquire())

        authority.close()

        assertEquals(0, closes.get())
        assertNull(authority.tryAcquire())
        assertSame(ReferencePickerResult.Cancelled, coordinator.handle(null, launch))
        lease.close()
        lease.close()
        authority.close()
        assertEquals(1, closes.get())
    }

    @Test
    fun realWorkflowLeaseDefersCloseUntilBlockingRepositoryWorkReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val repositoryCalls = AtomicInteger()
        val registry = ShootEditorPickerRegistry()
        val authority = DeferredShootEditorResourceAuthority(
            closeResources = closeCount::incrementAndGet,
            invalidatePicker = registry::invalidate,
        )
        val repository = object : ShootEditorRoomPort {
            override fun observeShootEditor(shootId: String): Flow<ShootEditorSnapshot?> =
                flowOf(snapshot())

            override fun reorderValidatedReferences(
                shootId: String,
                orderedPoseIds: List<String>,
                reorderedAtEpochMillis: Long,
            ): ShootReorderResult {
                repositoryCalls.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "controlled repository release timed out" }
                return ShootReorderResult.Reordered
            }

            override fun startShoot(
                shootId: String,
                sessionId: String,
                startedAtEpochMillis: Long,
            ): ShootStartResult = ShootStartResult.AuthorityInconsistent
        }
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "editor-lease-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val callerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "editor-lease-caller")
        }
        try {
            val workflow = workflow(
                repository = repository,
                registry = registry,
                authority = authority,
                dispatcher = dispatcher,
            )
            val inFlight = callerExecutor.submit<ShootReorderResult> {
                kotlinx.coroutines.runBlocking {
                    workflow.reorder(SHOOT_ID, listOf("pose-c", "pose-b", "pose-a"))
                }
            }
            assertTrue("repository work must enter", entered.await(5, TimeUnit.SECONDS))

            authority.close()

            assertEquals(0, closeCount.get())
            assertNull(authority.tryAcquire())
            assertSame(
                ShootReorderResult.AuthorityInconsistent,
                kotlinx.coroutines.runBlocking {
                    workflow.reorder(SHOOT_ID, listOf("pose-a", "pose-b", "pose-c"))
                },
            )
            assertEquals(1, repositoryCalls.get())

            release.countDown()
            assertSame(ShootReorderResult.Reordered, inFlight.get(5, TimeUnit.SECONDS))
            assertEquals(1, closeCount.get())
            authority.close()
            assertEquals(1, closeCount.get())
        } finally {
            release.countDown()
            dispatcher.close()
            executor.shutdownNow()
            callerExecutor.shutdownNow()
        }
    }

    @Test
    fun startEffectReplayReusesExactSessionIdentityAndReturnsResumableHandle() = runTest {
        val repository = FakeRoomPort(snapshot())
        val suppliedSessionIds = ArrayDeque(listOf("session-exact", "session-wrong"))
        val suppliedStartTimes = ArrayDeque(listOf(111L, 222L))
        val workflow = workflow(
            repository = repository,
            wallClock = suppliedStartTimes::removeFirst,
            sessionId = suppliedSessionIds::removeFirst,
        )

        repository.startResult = ShootStartResult.Started
        val started = workflow.start(SHOOT_ID) as ShootEditorStartOutcome.Started
        repository.startResult = ShootStartResult.AlreadyStarted
        val replayed = workflow.start(SHOOT_ID) as ShootEditorStartOutcome.Resumable

        assertEquals("session-exact", started.handle.navigationKey)
        assertEquals(started.handle.navigationKey, replayed.handle.navigationKey)
        assertEquals(listOf("session-exact", "session-exact"), repository.startSessionIds)
        assertEquals(listOf(111L, 111L), repository.startTimes)
        assertEquals(listOf("session-wrong"), suppliedSessionIds.toList())
        assertEquals(listOf(222L), suppliedStartTimes.toList())
    }

    @Test
    fun startIdentityIsReallocatedOnlyAfterProvenGlobalIdentityConflict() = runTest {
        val repository = FakeRoomPort(snapshot())
        val suppliedSessionIds = ArrayDeque(listOf("session-a", "session-b", "session-wrong"))
        val suppliedStartTimes = ArrayDeque(listOf(101L, 202L, 303L))
        val workflow = workflow(
            repository = repository,
            wallClock = suppliedStartTimes::removeFirst,
            sessionId = suppliedSessionIds::removeFirst,
        )

        repository.startResult = ShootStartResult.ActiveSessionConflict
        assertEquals(
            ShootEditorStartRejectionReason.ACTIVE_SESSION_CONFLICT,
            (workflow.start(SHOOT_ID) as ShootEditorStartOutcome.Rejected).reason,
        )
        repository.startResult = ShootStartResult.StaleOrConflictingReplay
        assertEquals(
            ShootEditorStartRejectionReason.STALE_OR_CONFLICTING_REPLAY,
            (workflow.start(SHOOT_ID) as ShootEditorStartOutcome.Rejected).reason,
        )
        repository.startResult = ShootStartResult.SessionIdentityConflict
        assertEquals(
            ShootEditorStartRejectionReason.SESSION_IDENTITY_CONFLICT,
            (workflow.start(SHOOT_ID) as ShootEditorStartOutcome.Rejected).reason,
        )
        repository.startResult = ShootStartResult.Started
        assertEquals(
            "session-b",
            (workflow.start(SHOOT_ID) as ShootEditorStartOutcome.Started).handle.navigationKey,
        )

        assertEquals(
            listOf("session-a", "session-a", "session-a", "session-b"),
            repository.startSessionIds,
        )
        assertEquals(listOf(101L, 101L, 101L, 202L), repository.startTimes)
        assertEquals(listOf("session-wrong"), suppliedSessionIds.toList())
        assertEquals(listOf(303L), suppliedStartTimes.toList())
    }

    @Test
    fun invalidOrThrowingStartIdentityProviderFailsClosedWithoutRepositoryCallOrLeak() = runTest {
        val marker = "raw-provider-secret content://private/start"
        val invalidRepository = FakeRoomPort(snapshot())
        val invalid = workflow(
            repository = invalidRepository,
            sessionId = { marker },
        ).start(SHOOT_ID) as ShootEditorStartOutcome.Rejected

        val throwingRepository = FakeRoomPort(snapshot())
        val unavailable = workflow(
            repository = throwingRepository,
            sessionId = { throw IllegalStateException(marker) },
        ).start(SHOOT_ID) as ShootEditorStartOutcome.Rejected

        assertEquals(ShootEditorStartRejectionReason.INVALID_REQUEST, invalid.reason)
        assertEquals(ShootEditorStartRejectionReason.AUTHORITY_UNAVAILABLE, unavailable.reason)
        assertTrue(invalidRepository.startSessionIds.isEmpty())
        assertTrue(throwingRepository.startSessionIds.isEmpty())
        assertFalse(invalid.toString().contains(marker))
        assertFalse(unavailable.toString().contains(marker))
    }

    @Test
    fun reorderAndStartUseInjectedAuthoritiesOffCallerThreadAndMapEveryStartResult() {
        val caller = Thread.currentThread()
        val repository = FakeRoomPort(snapshot())
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "editor-production-io") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val sessionIds = ArrayDeque(listOf("session-a", "session-b", "session-c", "session-d", "session-e", "session-f", "session-g", "session-h", "session-i", "session-j", "session-k"))
            val workflow = workflow(
                repository = repository,
                dispatcher = dispatcher,
                wallClock = { 444L },
                sessionId = { sessionIds.removeFirst() },
            )
            kotlinx.coroutines.runBlocking {
                assertSame(ShootReorderResult.Reordered, workflow.reorder(SHOOT_ID, listOf("pose-c", "pose-b", "pose-a")))
            }
            assertEquals(444L, repository.reorderTime)
            assertNotSame(caller, repository.callThread)

            val cases = listOf(
                StartCase(ShootStartResult.Started, ShootEditorStartOutcome.Started::class.java),
                StartCase(ShootStartResult.AlreadyStarted, ShootEditorStartOutcome.Resumable::class.java),
                StartCase(ShootStartResult.InvalidRequest(ShootStartInvalidReason.INVALID_SESSION_ID), ShootEditorStartOutcome.Rejected::class.java, ShootEditorStartRejectionReason.INVALID_REQUEST),
                StartCase(ShootStartResult.UnknownShoot, ShootEditorStartOutcome.Rejected::class.java, ShootEditorStartRejectionReason.UNKNOWN_SHOOT),
                StartCase(ShootStartResult.ShootDeleting, ShootEditorStartOutcome.Rejected::class.java, ShootEditorStartRejectionReason.SHOOT_DELETING),
                StartCase(ShootStartResult.IneligiblePlaylist(ShootStartIneligibleReason.TOO_FEW_VALIDATED_REFERENCES), ShootEditorStartOutcome.Rejected::class.java, ShootEditorStartRejectionReason.INELIGIBLE_PLAYLIST),
                StartCase(ShootStartResult.UnresolvedImportWork, ShootEditorStartOutcome.Rejected::class.java, ShootEditorStartRejectionReason.UNRESOLVED_IMPORT_WORK),
                StartCase(ShootStartResult.ActiveSessionConflict, ShootEditorStartOutcome.Rejected::class.java, ShootEditorStartRejectionReason.ACTIVE_SESSION_CONFLICT),
                StartCase(ShootStartResult.SessionIdentityConflict, ShootEditorStartOutcome.Rejected::class.java, ShootEditorStartRejectionReason.SESSION_IDENTITY_CONFLICT),
                StartCase(ShootStartResult.StaleOrConflictingReplay, ShootEditorStartOutcome.Rejected::class.java, ShootEditorStartRejectionReason.STALE_OR_CONFLICTING_REPLAY),
                StartCase(ShootStartResult.AuthorityInconsistent, ShootEditorStartOutcome.Rejected::class.java, ShootEditorStartRejectionReason.AUTHORITY_INCONSISTENT),
            )
            cases.forEach { case ->
                repository.startResult = case.repositoryResult
                val outcome = kotlinx.coroutines.runBlocking { workflow.start(SHOOT_ID) }
                assertTrue(case.expectedType.isInstance(outcome))
                if (case.expectedReason != null) {
                    assertEquals(
                        case.expectedReason,
                        (outcome as ShootEditorStartOutcome.Rejected).reason,
                    )
                }
                assertEquals(444L, repository.startTime)
                assertNotSame(caller, repository.callThread)
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun workflow(
        repository: ShootEditorRoomPort = FakeRoomPort(snapshot()),
        activeSessions: ShootEditorActiveSessionPort = ShootEditorActiveSessionPort {
            ActiveGuidedSessionResult.None
        },
        application: ShootEditorImportApplicationPort = FakeApplication(
            ReferenceImportAllocationResult.Blocked(
                ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
            ),
        ),
        registry: ShootEditorPickerRegistry = ShootEditorPickerRegistry(),
        authority: ShootEditorResourceAuthority = DeferredShootEditorResourceAuthority({}, registry::invalidate),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = UnconfinedTestDispatcher(),
        wallClock: () -> Long = { 123L },
        sessionId: () -> String = { "session-safe" },
    ) = RoomShootEditorWorkflow(
        repository = repository,
        activeSessions = activeSessions,
        imports = application,
        pickerRegistry = registry,
        authority = authority,
        blockingDispatcher = dispatcher,
        wallClockProvider = wallClock,
        sessionIdProvider = sessionId,
    )

    private class FakeActiveSessionPort(
        var result: ActiveGuidedSessionResult,
    ) : ShootEditorActiveSessionPort {
        val shootIds = mutableListOf<String>()
        var failure: RuntimeException? = null

        override fun findActiveGuidedSession(shootId: String): ActiveGuidedSessionResult {
            shootIds += shootId
            failure?.let { throw it }
            return result
        }
    }

    private class FakeRoomPort(var snapshot: ShootEditorSnapshot?) : ShootEditorRoomPort {
        var reorderTime: Long? = null
        var startTime: Long? = null
        var callThread: Thread? = null
        var startResult: ShootStartResult = ShootStartResult.Started
        val startSessionIds = mutableListOf<String>()
        val startTimes = mutableListOf<Long>()

        override fun observeShootEditor(shootId: String): Flow<ShootEditorSnapshot?> = flowOf(snapshot)
        override fun reorderValidatedReferences(shootId: String, orderedPoseIds: List<String>, reorderedAtEpochMillis: Long): ShootReorderResult {
            callThread = Thread.currentThread(); reorderTime = reorderedAtEpochMillis
            return ShootReorderResult.Reordered
        }
        override fun startShoot(shootId: String, sessionId: String, startedAtEpochMillis: Long): ShootStartResult {
            callThread = Thread.currentThread(); startTime = startedAtEpochMillis
            startSessionIds += sessionId
            startTimes += startedAtEpochMillis
            return startResult
        }
    }

    private class FakeApplication(var allocation: ReferenceImportAllocationResult) : ShootEditorImportApplicationPort {
        var readyDraft: ReferencePickerImportDraft? = (allocation as? ReferenceImportAllocationResult.Ready)?.draft
        override fun allocate(request: ReferenceImportAllocationRequest): ReferenceImportAllocationResult {
            readyDraft = (allocation as? ReferenceImportAllocationResult.Ready)?.draft
            return allocation
        }
        override fun classify(result: ReferencePickerResult): ReferenceImportOutcome =
            ReferenceImportOutcome(ReferenceImportOutcomeStatus.CANCELLED, ReferenceImportRetryAction.NONE)
    }

    private class StartCase(
        val repositoryResult: ShootStartResult,
        val expectedType: Class<out ShootEditorStartOutcome>,
        val expectedReason: ShootEditorStartRejectionReason? = null,
    )

    private fun snapshot(
        shootId: String = SHOOT_ID,
        importWork: List<ImportWorkSummary> = listOf(
            ImportWorkSummary(ImportWorkStatus.RECONCILIATION_REQUIRED, 900L, 901L),
        ),
        lifecycle: ShootPreparationLifecycle = ShootPreparationLifecycle.ACTIVE,
    ) = ShootEditorSnapshot(
        shootId = shootId,
        name = "Studio",
        lifecycle = lifecycle,
        validatedReferences = listOf(
            ValidatedReferenceSummary("pose-a", 0, "Front", true),
            ValidatedReferenceSummary("pose-b", 1, "Side", false),
            ValidatedReferenceSummary("pose-c", 2, "Back", true),
        ),
        importWork = importWork,
        updatedAtEpochMillis = 9876L,
    )

    private fun draft() = ReferencePickerImportDraft(
        ReferenceImportToken("token-safe"), SHOOT_ID, "pose-safe", "Side", true,
        ReferenceImportLedgerTimeline(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
    )

    private companion object { const val SHOOT_ID = "shoot-safe" }
}
