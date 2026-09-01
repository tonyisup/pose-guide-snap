package com.tonyisup.poseguidesnap.ui.shoots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonyisup.poseguidesnap.data.RoomShootPreparationRepository
import com.tonyisup.poseguidesnap.data.ShootCreateRejectionReason
import com.tonyisup.poseguidesnap.data.ShootCreateResult
import com.tonyisup.poseguidesnap.data.ShootPreparationLifecycle
import com.tonyisup.poseguidesnap.data.ShootSummary
import java.util.AbstractList
import java.util.ArrayList
import java.util.RandomAccess
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHOOT_LIST_PAGE_SIZE = 50
private const val MAX_SHOOT_NAME_LENGTH = 200

private class RedactedShootList<E>(source: Iterable<E>) : AbstractList<E>(), RandomAccess {
    private val values = ArrayList<E>().apply { addAll(source) }

    override val size: Int
        get() = values.size

    override fun get(index: Int): E = values[index]

    override fun toString(): String = "RedactedShootList(redacted)"
}

internal class ShootListItem(
    internal val shootId: String,
    val name: String,
    val referenceCount: Int,
    val lifecycleText: String,
) {
    init {
        require(shootId.isSafeOpaqueIdentity()) { "shoot list identity must be safe" }
        require(name.isSafeDisplayText()) { "shoot list name must be safe" }
        require(referenceCount in 0..20) { "reference count must be bounded" }
        require(lifecycleText == "Active" || lifecycleText == "Deleting") {
            "lifecycle text must be bounded"
        }
    }

    override fun toString(): String = "ShootListItem(redacted)"
}

internal class ShootListPage(
    items: Iterable<ShootListItem>,
    val hasMore: Boolean,
) {
    val items: List<ShootListItem> = RedactedShootList(items)

    override fun toString(): String = "ShootListPage(redacted)"
}

internal sealed interface ShootListCreateStatus {
    data object Idle : ShootListCreateStatus {
        override fun toString(): String = "ShootListCreateStatus.Idle(redacted)"
    }

    class Pending internal constructor(internal val operation: Long) : ShootListCreateStatus {
        init {
            require(operation > 0L) { "create operation must be positive" }
        }

        override fun toString(): String = "ShootListCreateStatus.Pending(redacted)"
    }

    data object Succeeded : ShootListCreateStatus {
        override fun toString(): String = "ShootListCreateStatus.Succeeded(redacted)"
    }

    data object Rejected : ShootListCreateStatus {
        override fun toString(): String = "ShootListCreateStatus.Rejected(redacted)"
    }

    data object Unavailable : ShootListCreateStatus {
        override fun toString(): String = "ShootListCreateStatus.Unavailable(redacted)"
    }
}

internal sealed interface ShootListPageStatus {
    data object Idle : ShootListPageStatus
    data object LoadingMore : ShootListPageStatus
    data object Unavailable : ShootListPageStatus
}

internal class ShootListData(
    items: Iterable<ShootListItem>,
    val hasMore: Boolean = false,
    val pageStatus: ShootListPageStatus = ShootListPageStatus.Idle,
    val createStatus: ShootListCreateStatus = ShootListCreateStatus.Idle,
) {
    val items: List<ShootListItem> = RedactedShootList(items)

    override fun toString(): String = "ShootListData(redacted)"
}

internal sealed interface ShootListUiState {
    data object Loading : ShootListUiState {
        override fun toString(): String = "ShootListUiState.Loading(redacted)"
    }

    data object Unavailable : ShootListUiState {
        override fun toString(): String = "ShootListUiState.Unavailable(redacted)"
    }

    sealed interface Loaded : ShootListUiState {
        val data: ShootListData
    }

    class Empty(override val data: ShootListData) : Loaded {
        override fun toString(): String = "ShootListUiState.Empty(redacted)"
    }

    class Content(override val data: ShootListData) : Loaded {
        override fun toString(): String = "ShootListUiState.Content(redacted)"
    }
}

internal sealed interface ShootListCreateOutcome {
    data object Created : ShootListCreateOutcome
    data object Rejected : ShootListCreateOutcome
    data object Unavailable : ShootListCreateOutcome
}

/** UI-safe application boundary. Room entities and allocation providers stay behind this port. */
internal interface ShootListWorkflowPort {
    fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage>

    suspend fun createShoot(trimmedName: String): ShootListCreateOutcome
}

internal interface OwnedShootListWorkflow : ShootListWorkflowPort {
    fun close()
}

internal fun interface ShootListAuthorityLease : AutoCloseable {
    override fun close()
}

internal interface ShootListAuthority {
    fun tryAcquire(): ShootListAuthorityLease?
    fun close()
}

internal class DeferredCloseAuthority(
    private val closeAuthority: () -> Unit,
) : ShootListAuthority {
    private val lock = Any()
    private var leaseCount = 0
    private var closing = false
    private var closed = false

    override fun tryAcquire(): ShootListAuthorityLease? = synchronized(lock) {
        if (closing) return@synchronized null
        leaseCount += 1
        Lease()
    }

    override fun close() {
        val closeNow = synchronized(lock) {
            closing = true
            claimCloseIfReady()
        }
        if (closeNow) closeAuthority()
    }

    private fun release() {
        val closeNow = synchronized(lock) {
            check(leaseCount > 0) { "authority lease underflow" }
            leaseCount -= 1
            claimCloseIfReady()
        }
        if (closeNow) closeAuthority()
    }

    private fun claimCloseIfReady(): Boolean {
        if (!closing || closed || leaseCount != 0) return false
        closed = true
        return true
    }

    private inner class Lease : ShootListAuthorityLease {
        private var released = false

        override fun close() {
            val shouldRelease = synchronized(lock) {
                if (released) false else true.also { released = true }
            }
            if (shouldRelease) release()
        }
    }
}

internal class ShootListAuthorityUnavailableException : IllegalStateException("shoot authority unavailable")

internal interface ShootListRepositoryPort {
    fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage>

    fun createShoot(
        shootId: String,
        name: String,
        createdAtEpochMillis: Long,
    ): ShootListCreateOutcome
}

internal class RoomShootListRepositoryAdapter(
    private val repository: RoomShootPreparationRepository,
) : ShootListRepositoryPort {
    override fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage> =
        repository.observeShootPage(limit, offset).map { page ->
            ShootListPage(page.items.map(::toItem), page.hasMore)
        }

    override fun createShoot(
        shootId: String,
        name: String,
        createdAtEpochMillis: Long,
    ): ShootListCreateOutcome = when (
        val result = repository.createShoot(shootId, name, createdAtEpochMillis)
    ) {
        is ShootCreateResult.Created -> ShootListCreateOutcome.Created
        is ShootCreateResult.AlreadyExists -> ShootListCreateOutcome.Unavailable
        is ShootCreateResult.Rejected -> when (result.reason) {
            ShootCreateRejectionReason.INVALID_NAME -> ShootListCreateOutcome.Rejected
            else -> ShootListCreateOutcome.Unavailable
        }
    }

    private fun toItem(summary: ShootSummary): ShootListItem =
        ShootListItem(
            shootId = summary.shootId,
            name = summary.name,
            referenceCount = summary.validatedReferenceCount,
            lifecycleText = when (summary.lifecycle) {
                ShootPreparationLifecycle.ACTIVE -> "Active"
                ShootPreparationLifecycle.DELETING -> "Deleting"
            },
        )
}

internal class RoomShootListWorkflow(
    private val repository: ShootListRepositoryPort,
    private val authority: ShootListAuthority,
    private val createDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val identityProvider: () -> String = { UUID.randomUUID().toString() },
    private val wallClockProvider: () -> Long = { System.currentTimeMillis() },
) : OwnedShootListWorkflow {
    override fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage> = flow {
        val lease = authority.tryAcquire() ?: throw ShootListAuthorityUnavailableException()
        try {
            repository.observeShootPage(limit, offset).collect { page -> emit(page) }
        } finally {
            lease.close()
        }
    }

    override suspend fun createShoot(trimmedName: String): ShootListCreateOutcome =
        withContext(createDispatcher) {
            if (trimmedName != trimmedName.trim() || !trimmedName.isSafeDisplayText()) {
                return@withContext ShootListCreateOutcome.Rejected
            }
            val lease = authority.tryAcquire() ?: return@withContext ShootListCreateOutcome.Unavailable
            try {
                repository.createShoot(
                    shootId = identityProvider(),
                    name = trimmedName,
                    createdAtEpochMillis = wallClockProvider(),
                )
            } finally {
                lease.close()
            }
        }

    override fun close() {
        authority.close()
    }

    override fun toString(): String = "RoomShootListWorkflow(redacted)"
}

internal class ShootListReducer {
    fun snapshotChanged(
        current: ShootListUiState,
        items: List<ShootListItem>,
    ): ShootListUiState = firstPageChanged(current, ShootListPage(items, hasMore = false))

    fun firstPageChanged(
        current: ShootListUiState,
        page: ShootListPage,
    ): ShootListUiState {
        requireCoherentPage(page)
        val createStatus = (current as? ShootListUiState.Loaded)?.data?.createStatus
            ?: ShootListCreateStatus.Idle
        return loadedState(
            ShootListData(
                items = page.items,
                hasMore = page.hasMore,
                createStatus = createStatus,
            ),
        )
    }

    fun observationFailed(): ShootListUiState = ShootListUiState.Unavailable

    fun beginLoadMore(current: ShootListUiState): ShootListUiState {
        val loaded = current as? ShootListUiState.Loaded ?: return current
        if (!loaded.data.hasMore || loaded.data.pageStatus == ShootListPageStatus.LoadingMore) {
            return current
        }
        return loadedState(
            ShootListData(
                items = loaded.data.items,
                hasMore = true,
                pageStatus = ShootListPageStatus.LoadingMore,
                createStatus = loaded.data.createStatus,
            ),
        )
    }

    fun pageLoaded(current: ShootListUiState, page: ShootListPage): ShootListUiState {
        val loaded = current as? ShootListUiState.Loaded ?: return current
        if (loaded.data.pageStatus != ShootListPageStatus.LoadingMore) return current
        requireCoherentPage(page)
        val existingIds = loaded.data.items.mapTo(HashSet(), ShootListItem::shootId)
        val appended = page.items.filter { item -> existingIds.add(item.shootId) }
        return loadedState(
            ShootListData(
                items = loaded.data.items + appended,
                hasMore = page.hasMore,
                createStatus = loaded.data.createStatus,
            ),
        )
    }

    fun pageFailed(current: ShootListUiState): ShootListUiState {
        val loaded = current as? ShootListUiState.Loaded ?: return current
        if (loaded.data.pageStatus != ShootListPageStatus.LoadingMore) return current
        return loadedState(
            ShootListData(
                items = loaded.data.items,
                hasMore = loaded.data.hasMore,
                pageStatus = ShootListPageStatus.Unavailable,
                createStatus = loaded.data.createStatus,
            ),
        )
    }

    fun beginCreate(
        current: ShootListUiState,
        operation: Long,
        trimmedName: String,
    ): ShootListUiState {
        val loaded = current as? ShootListUiState.Loaded ?: return current
        if (loaded.data.createStatus is ShootListCreateStatus.Pending) return current
        val status = if (
            operation > 0L &&
            trimmedName == trimmedName.trim() &&
            trimmedName.isSafeDisplayText()
        ) {
            ShootListCreateStatus.Pending(operation)
        } else {
            ShootListCreateStatus.Rejected
        }
        return loadedState(loaded.data.copy(createStatus = status))
    }

    fun createCompleted(
        current: ShootListUiState,
        operation: Long,
        outcome: ShootListCreateOutcome,
    ): ShootListUiState {
        val loaded = current as? ShootListUiState.Loaded ?: return current
        val pending = loaded.data.createStatus as? ShootListCreateStatus.Pending ?: return current
        if (pending.operation != operation) return current
        val status = when (outcome) {
            ShootListCreateOutcome.Created -> ShootListCreateStatus.Succeeded
            ShootListCreateOutcome.Rejected -> ShootListCreateStatus.Rejected
            ShootListCreateOutcome.Unavailable -> ShootListCreateStatus.Unavailable
        }
        return loadedState(loaded.data.copy(createStatus = status))
    }

    private fun requireCoherentPage(page: ShootListPage) {
        require(!page.hasMore || page.items.isNotEmpty()) { "nonterminal shoot page must not be empty" }
        require(page.items.map(ShootListItem::shootId).distinct().size == page.items.size) {
            "shoot page identities must be unique"
        }
    }

    private fun ShootListData.copy(
        createStatus: ShootListCreateStatus = this.createStatus,
    ): ShootListData = ShootListData(
        items = items,
        hasMore = hasMore,
        pageStatus = pageStatus,
        createStatus = createStatus,
    )

    private fun loadedState(data: ShootListData): ShootListUiState =
        if (data.items.isEmpty()) ShootListUiState.Empty(data) else ShootListUiState.Content(data)
}

internal class ShootListViewModel(
    private val workflow: ShootListWorkflowPort,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    closeAuthority: () -> Unit = {},
) : ViewModel() {
    private val reducer = ShootListReducer()
    private val lock = Any()
    private val _state = MutableStateFlow<ShootListUiState>(ShootListUiState.Loading)
    private var observationJob: Job? = null
    private var pageJob: Job? = null
    private var createJob: Job? = null
    private var observationGeneration = 0L
    private var observationGenerationExhausted = false
    private var nextOperation = 0L
    private var disposed = false
    private var closeAuthority: (() -> Unit)? = closeAuthority

    val state: StateFlow<ShootListUiState> = _state.asStateFlow()

    init {
        observe()
    }

    fun retryObservation() {
        observe()
    }

    fun loadMore() {
        val job = synchronized(lock) {
            if (disposed) return
            val loaded = _state.value as? ShootListUiState.Loaded ?: return
            val begun = reducer.beginLoadMore(loaded)
            if (begun === loaded) return
            _state.value = begun
            val generation = observationGeneration
            val offset = loaded.data.items.size
            viewModelScope.launch(dispatcher, start = CoroutineStart.LAZY) {
                try {
                    val page = workflow.observeShootPage(SHOOT_LIST_PAGE_SIZE, offset).first()
                    applyObservation(generation) { current -> reducer.pageLoaded(current, page) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    applyObservation(generation) { current -> reducer.pageFailed(current) }
                }
            }.also { nextJob -> pageJob = nextJob }
        }
        job.start()
    }

    fun createShoot(trimmedName: String) {
        val job = synchronized(lock) {
            if (disposed || nextOperation == Long.MAX_VALUE) return
            nextOperation += 1L
            val operation = nextOperation
            val generation = observationGeneration
            val begun = reducer.beginCreate(_state.value, operation, trimmedName)
            _state.value = begun
            if (begun.currentPendingOperation() != operation) return
            val request = CreateRequest(generation, operation, trimmedName)
            viewModelScope.launch(dispatcher, start = CoroutineStart.LAZY) {
                val outcome = try {
                    workflow.createShoot(request.trimmedName)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    ShootListCreateOutcome.Unavailable
                }
                synchronized(lock) {
                    if (
                        !disposed &&
                        !observationGenerationExhausted &&
                        observationGeneration == request.observationGeneration
                    ) {
                        _state.value =
                            reducer.createCompleted(_state.value, request.operation, outcome)
                    }
                }
            }.also { job -> createJob = job }
        }
        job.start()
    }

    override fun onCleared() {
        val authority = synchronized(lock) {
            if (disposed) {
                null
            } else {
                disposed = true
                observationJob?.cancel()
                observationJob = null
                pageJob?.cancel()
                pageJob = null
                createJob?.cancel()
                createJob = null
                closeAuthority.also { closeAuthority = null }
            }
        }
        try {
            authority?.invoke()
        } finally {
            super.onCleared()
        }
    }

    override fun toString(): String = "ShootListViewModel(redacted)"

    private fun observe() {
        val job = synchronized(lock) {
            if (disposed) return
            observationJob?.cancel()
            pageJob?.cancel()
            pageJob = null
            createJob?.cancel()
            createJob = null
            if (observationGenerationExhausted || observationGeneration == Long.MAX_VALUE) {
                observationGenerationExhausted = true
                observationJob = null
                _state.value = ShootListUiState.Unavailable
                null
            } else {
                observationGeneration += 1L
                val generation = observationGeneration
                _state.value = ShootListUiState.Loading
                viewModelScope.launch(dispatcher, start = CoroutineStart.LAZY) {
                    try {
                        val source = workflow.observeShootPage(SHOOT_LIST_PAGE_SIZE, 0)
                        source.collect { page ->
                            applyObservation(generation) { current ->
                                reducer.firstPageChanged(current, page)
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        applyObservation(generation) { reducer.observationFailed() }
                    }
                }.also { nextJob -> observationJob = nextJob }
            }
        } ?: return
        job.start()
    }

    private fun applyObservation(
        generation: Long,
        update: (ShootListUiState) -> ShootListUiState,
    ) {
        synchronized(lock) {
            if (
                !disposed &&
                !observationGenerationExhausted &&
                observationGeneration == generation
            ) {
                _state.value = update(_state.value)
            }
        }
    }

    private data class CreateRequest(
        val observationGeneration: Long,
        val operation: Long,
        val trimmedName: String,
    )
}

private fun ShootListUiState.currentPendingOperation(): Long? =
    (this as? ShootListUiState.Loaded)?.data?.createStatus
        ?.let { it as? ShootListCreateStatus.Pending }
        ?.operation

private fun String.isSafeOpaqueIdentity(): Boolean =
    isNotEmpty() && this != "." && this != ".." && all { character ->
        character.isLetterOrDigit() || character == '_' || character == '-' || character == '.'
    }

private fun String.isSafeDisplayText(): Boolean =
    isNotBlank() && length <= MAX_SHOOT_NAME_LENGTH &&
        !contains("content://", ignoreCase = true) && none(Char::isISOControl)
