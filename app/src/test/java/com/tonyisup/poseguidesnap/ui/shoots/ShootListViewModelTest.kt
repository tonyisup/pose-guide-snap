package com.tonyisup.poseguidesnap.ui.shoots

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShootListViewModelTest {
    private val reducer = ShootListReducer()
    private val item = ShootListItem("safe-shoot", "Morning poses", 3, "Active")

    @Test
    fun snapshotsProduceEmptyAndImmutableContentWithoutExposingIdentity() {
        val empty = reducer.snapshotChanged(ShootListUiState.Loading, emptyList())
        assertTrue(empty is ShootListUiState.Empty)

        val source = arrayListOf(item)
        val content = reducer.snapshotChanged(empty, source) as ShootListUiState.Content
        source.clear()
        assertEquals(1, content.data.items.size)
        assertFalse(content.toString().contains("safe-shoot"))
        assertFalse(content.data.toString().contains("safe-shoot"))
        assertFalse(content.data.items.toString().contains("safe-shoot"))
        assertFalse(item.toString().contains("safe-shoot"))
        try {
            @Suppress("UNCHECKED_CAST")
            (content.data.items as MutableList<ShootListItem>).clear()
            fail("list must be immutable")
        } catch (_: UnsupportedOperationException) {
            Unit
        }
    }

    @Test
    fun createTransitionsCoverPendingSuccessRejectionFailureAndStaleCompletion() {
        val empty = reducer.snapshotChanged(ShootListUiState.Loading, emptyList())
        val pending = reducer.beginCreate(empty, 1L, "New shoot") as ShootListUiState.Empty
        assertTrue(pending.data.createStatus is ShootListCreateStatus.Pending)

        val succeeded = reducer.createCompleted(pending, 1L, ShootListCreateOutcome.Created)
            as ShootListUiState.Empty
        assertSame(ShootListCreateStatus.Succeeded, succeeded.data.createStatus)

        val rejectedPending = reducer.beginCreate(succeeded, 2L, "Another")
        val rejected = reducer.createCompleted(rejectedPending, 2L, ShootListCreateOutcome.Rejected)
            as ShootListUiState.Empty
        assertSame(ShootListCreateStatus.Rejected, rejected.data.createStatus)

        val failedPending = reducer.beginCreate(rejected, 3L, "Again")
        val failed = reducer.createCompleted(failedPending, 3L, ShootListCreateOutcome.Unavailable)
            as ShootListUiState.Empty
        assertSame(ShootListCreateStatus.Unavailable, failed.data.createStatus)

        val active = reducer.beginCreate(failed, 4L, "Current") as ShootListUiState.Empty
        val stale = reducer.createCompleted(active, 3L, ShootListCreateOutcome.Created)
        assertSame(active, stale)
    }

    @Test
    fun pagedReducerAccumulatesExactOrderedUniqueItemsWithoutAnArbitraryGlobalCap() {
        val all = (0..500).map(::itemAt)
        var state: ShootListUiState = reducer.firstPageChanged(
            ShootListUiState.Loading,
            ShootListPage(all.take(50), hasMore = true),
        )
        for (pageIndex in 1..9) {
            state = reducer.pageLoaded(
                reducer.beginLoadMore(state),
                ShootListPage(all.drop(pageIndex * 50).take(50), hasMore = true),
            )
        }
        state = reducer.pageLoaded(
            reducer.beginLoadMore(state),
            ShootListPage(all.drop(500), hasMore = false),
        )

        val content = state as ShootListUiState.Content
        assertEquals(501, content.data.items.size)
        assertEquals(all.map { it.shootId }, content.data.items.map { it.shootId })
        assertEquals(501, content.data.items.map { it.shootId }.distinct().size)
        assertFalse(content.data.hasMore)
        assertSame(ShootListPageStatus.Idle, content.data.pageStatus)
    }

    @Test
    fun loadMoreFailurePreservesLoadedItemsAndCanBeRetriedAtTheSameOffset() {
        val first = reducer.firstPageChanged(
            ShootListUiState.Loading,
            ShootListPage((0 until 50).map(::itemAt), hasMore = true),
        )
        val loading = reducer.beginLoadMore(first) as ShootListUiState.Content
        assertSame(ShootListPageStatus.LoadingMore, loading.data.pageStatus)

        val failed = reducer.pageFailed(loading) as ShootListUiState.Content
        assertEquals(50, failed.data.items.size)
        assertTrue(failed.data.hasMore)
        assertSame(ShootListPageStatus.Unavailable, failed.data.pageStatus)

        val retrying = reducer.beginLoadMore(failed) as ShootListUiState.Content
        assertSame(ShootListPageStatus.LoadingMore, retrying.data.pageStatus)
    }

    @Test
    fun viewModelBrowsesAll501ShootsByExplicitPagesAndRefreshResetsToFirstPage() {
        val all = (0..500).map(::itemAt)
        val workflow = PagedWorkflow(all)
        val viewModel = ShootListViewModel(
            workflow = workflow,
            dispatcher = UnconfinedTestDispatcher(),
        )

        assertEquals(50, loaded(viewModel).items.size)
        repeat(10) { viewModel.loadMore() }
        assertEquals(501, loaded(viewModel).items.size)
        assertFalse(loaded(viewModel).hasMore)
        assertEquals((0..500).map { "shoot-$it" }, loaded(viewModel).items.map { it.shootId })
        assertEquals((0..10).map { it * 50 }, workflow.offsets)

        viewModel.retryObservation()
        assertEquals(50, loaded(viewModel).items.size)
        assertTrue(loaded(viewModel).hasMore)
        assertEquals(0, workflow.offsets.last())
    }

    @Test
    fun downstreamPageReductionFailureBecomesGenericUnavailableInsteadOfCoroutineDeath() {
        val duplicate = ShootListPage(listOf(item, item), hasMore = false)
        val viewModel = ShootListViewModel(
            workflow = FakeWorkflow(observation = flowOf(duplicate)),
            dispatcher = UnconfinedTestDispatcher(),
        )

        assertSame(ShootListUiState.Unavailable, viewModel.state.value)
    }

    @Test
    fun viewModelMapsObservationCreateAndLoadMoreFailuresToGenericStates() {
        val unavailable = ShootListViewModel(
            workflow = FakeWorkflow(observation = flow { throw IllegalStateException("private") }),
            dispatcher = UnconfinedTestDispatcher(),
        )
        assertSame(ShootListUiState.Unavailable, unavailable.state.value)

        val createFailure = ShootListViewModel(
            workflow = FakeWorkflow(
                observation = flowOf(ShootListPage(emptyList(), hasMore = false)),
                create = { throw IllegalStateException("private") },
            ),
            dispatcher = UnconfinedTestDispatcher(),
        )
        createFailure.createShoot("Safe")
        val createState = createFailure.state.value as ShootListUiState.Empty
        assertSame(ShootListCreateStatus.Unavailable, createState.data.createStatus)
        assertFalse(createState.toString().contains("private"))

        var request = 0
        val loadFailure = ShootListViewModel(
            workflow = FakeWorkflow(observationFactory = { _, _ ->
                request += 1
                if (request == 1) {
                    flowOf(ShootListPage(listOf(item), hasMore = true))
                } else {
                    flow { throw IllegalStateException("private page") }
                }
            }),
            dispatcher = UnconfinedTestDispatcher(),
        )
        loadFailure.loadMore()
        val pageState = loadFailure.state.value as ShootListUiState.Content
        assertEquals(1, pageState.data.items.size)
        assertSame(ShootListPageStatus.Unavailable, pageState.data.pageStatus)
    }

    @Test
    fun retrySuppressesAStaleNonCooperativeCreateCompletion() {
        val workflow = PausedCreateWorkflow()
        val viewModel = ShootListViewModel(
            workflow = workflow,
            dispatcher = UnconfinedTestDispatcher(),
        )

        viewModel.createShoot("Safe")
        assertTrue(
            ((viewModel.state.value as ShootListUiState.Empty).data.createStatus) is
                ShootListCreateStatus.Pending,
        )
        viewModel.retryObservation()
        workflow.complete(ShootListCreateOutcome.Created)

        val state = viewModel.state.value as ShootListUiState.Empty
        assertSame(ShootListCreateStatus.Idle, state.data.createStatus)
    }

    @Test
    fun observationGenerationOverflowIsTerminalAgainstNoncooperativeMaxGenerationCollector() =
        runTest {
            val lateSnapshotRelease = CompletableDeferred<Unit>()
            val lateFailureRelease = CompletableDeferred<Unit>()
            val noncooperativeFlow = object : Flow<ShootListPage> {
                override suspend fun collect(collector: FlowCollector<ShootListPage>) {
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        withContext(NonCancellable) {
                            lateSnapshotRelease.await()
                            collector.emit(ShootListPage(listOf(item), hasMore = false))
                            lateFailureRelease.await()
                            throw IllegalStateException("private")
                        }
                    }
                }
            }
            val viewModel = ShootListViewModel(
                workflow = FakeWorkflow(observation = noncooperativeFlow),
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            runCurrent()
            val generationField = viewModel.javaClass
                .getDeclaredField("observationGeneration")
                .apply { isAccessible = true }
            generationField.setLong(viewModel, Long.MAX_VALUE - 1L)

            viewModel.retryObservation()
            runCurrent()
            assertEquals(Long.MAX_VALUE, generationField.getLong(viewModel))
            viewModel.retryObservation()
            runCurrent()
            assertSame(ShootListUiState.Unavailable, viewModel.state.value)

            lateSnapshotRelease.complete(Unit)
            runCurrent()
            try {
                assertSame(ShootListUiState.Unavailable, viewModel.state.value)
            } finally {
                lateFailureRelease.complete(Unit)
                runCurrent()
            }

            assertSame(ShootListUiState.Unavailable, viewModel.state.value)
            assertEquals(Long.MAX_VALUE, generationField.getLong(viewModel))
        }

    @Test
    fun deferredCloseOwnerWaitsForEveryLeaseRejectsNewWorkAndClosesExactlyOnce() {
        var closeCount = 0
        val owner = DeferredCloseAuthority { closeCount += 1 }
        val first = owner.tryAcquire()
        val second = owner.tryAcquire()
        assertTrue(first != null)
        assertTrue(second != null)

        owner.close()
        owner.close()
        assertEquals(0, closeCount)
        assertNull(owner.tryAcquire())
        first!!.close()
        first.close()
        assertEquals(0, closeCount)
        second!!.close()
        second.close()
        assertEquals(1, closeCount)
    }

    @Test
    fun noncooperativeSynchronousCreateDefersCloseUntilRepositoryReturns() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        var databaseCloseCount = 0
        val owner = DeferredCloseAuthority { databaseCloseCount += 1 }
        val repository = object : ShootListRepositoryPort {
            override fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage> =
                flowOf(ShootListPage(emptyList(), hasMore = false))

            override fun createShoot(
                shootId: String,
                name: String,
                createdAtEpochMillis: Long,
            ): ShootListCreateOutcome {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return ShootListCreateOutcome.Created
            }
        }
        val workflow = RoomShootListWorkflow(
            repository = repository,
            authority = owner,
            createDispatcher = dispatcher,
            identityProvider = { "new-shoot" },
            wallClockProvider = { 1L },
        )
        try {
            val create = launch(dispatcher) { workflow.createShoot("Safe") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            workflow.close()
            assertEquals(0, databaseCloseCount)
            assertSame(ShootListCreateOutcome.Unavailable, workflow.createShoot("Denied"))

            release.countDown()
            create.join()
            assertEquals(1, databaseCloseCount)
            workflow.close()
            assertEquals(1, databaseCloseCount)
        } finally {
            release.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun noncooperativeObservationDefersCloseUntilCollectionFinallyReleasesLease() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var databaseCloseCount = 0
        val owner = DeferredCloseAuthority { databaseCloseCount += 1 }
        val repository = object : ShootListRepositoryPort {
            override fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage> = flow {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { release.await() }
                    throw CancellationException("released")
                }
            }

            override fun createShoot(
                shootId: String,
                name: String,
                createdAtEpochMillis: Long,
            ): ShootListCreateOutcome = ShootListCreateOutcome.Created
        }
        val workflow = RoomShootListWorkflow(repository, owner, UnconfinedTestDispatcher())
        val collecting = launch(UnconfinedTestDispatcher()) {
            workflow.observeShootPage(50, 0).collect {}
        }
        entered.await()

        collecting.cancel()
        workflow.close()
        assertEquals(0, databaseCloseCount)
        assertTrue(workflow.observeShootPage(50, 0).failsBeforeEmission())

        release.complete(Unit)
        collecting.join()
        assertEquals(1, databaseCloseCount)
    }

    @Test
    fun onClearedCancelsPendingWorkAndRequestsAuthorityCloseExactlyOnce() {
        val workflow = PausedCreateWorkflow()
        var closeCount = 0
        val viewModel = ShootListViewModel(
            workflow = workflow,
            dispatcher = UnconfinedTestDispatcher(),
            closeAuthority = { closeCount += 1 },
        )
        viewModel.createShoot("Safe")
        val pendingState = viewModel.state.value

        invokeOnCleared(viewModel)
        invokeOnCleared(viewModel)
        workflow.complete(ShootListCreateOutcome.Created)

        assertSame(pendingState, viewModel.state.value)
        assertEquals(1, closeCount)
    }

    @Test(expected = CancellationException::class)
    fun workflowCancellationPropagates() {
        kotlinx.coroutines.test.runTest {
            val workflow = FakeWorkflow(
                observation = flowOf(ShootListPage(emptyList(), hasMore = false)),
                create = { throw CancellationException("cancel") },
            )
            workflow.createShoot("Safe")
        }
    }

    private suspend fun Flow<ShootListPage>.failsBeforeEmission(): Boolean = try {
        collect { fail("closed authority must not emit") }
        false
    } catch (_: ShootListAuthorityUnavailableException) {
        true
    }

    private fun loaded(viewModel: ShootListViewModel): ShootListData =
        (viewModel.state.value as ShootListUiState.Loaded).data

    private fun itemAt(index: Int): ShootListItem =
        ShootListItem("shoot-$index", "Shoot $index", index % 20, "Active")

    private fun invokeOnCleared(viewModel: ShootListViewModel) {
        viewModel.javaClass.getDeclaredMethod("onCleared").apply {
            isAccessible = true
        }.invoke(viewModel)
    }

    private class PagedWorkflow(private val all: List<ShootListItem>) : ShootListWorkflowPort {
        val offsets = mutableListOf<Int>()

        override fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage> {
            offsets += offset
            return flowOf(
                ShootListPage(
                    items = all.drop(offset).take(limit),
                    hasMore = offset + limit < all.size,
                ),
            )
        }

        override suspend fun createShoot(trimmedName: String): ShootListCreateOutcome =
            ShootListCreateOutcome.Created
    }

    private class PausedCreateWorkflow : ShootListWorkflowPort {
        private var continuation: Continuation<ShootListCreateOutcome>? = null

        override fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage> =
            flowOf(ShootListPage(emptyList(), hasMore = false))

        override suspend fun createShoot(trimmedName: String): ShootListCreateOutcome =
            suspendCoroutine { continuation = it }

        fun complete(outcome: ShootListCreateOutcome) {
            requireNotNull(continuation).resume(outcome)
            continuation = null
        }
    }

    private class FakeWorkflow(
        private val observation: Flow<ShootListPage>? = null,
        private val observationFactory: ((Int, Int) -> Flow<ShootListPage>)? = null,
        private val create: suspend (String) -> ShootListCreateOutcome = {
            ShootListCreateOutcome.Created
        },
    ) : ShootListWorkflowPort {
        override fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage> =
            observationFactory?.invoke(limit, offset) ?: requireNotNull(observation)

        override suspend fun createShoot(trimmedName: String): ShootListCreateOutcome =
            create(trimmedName)
    }
}
