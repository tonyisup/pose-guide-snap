package com.tonyisup.poseguidesnap.ui.editor

import android.net.Uri
import com.tonyisup.poseguidesnap.data.ActiveGuidedSessionRejectionReason
import com.tonyisup.poseguidesnap.data.ActiveGuidedSessionResult
import com.tonyisup.poseguidesnap.data.ImportWorkStatus
import com.tonyisup.poseguidesnap.data.RoomShootPreparationRepository
import com.tonyisup.poseguidesnap.data.RoomShootRepository
import com.tonyisup.poseguidesnap.data.ShootEditorSnapshot
import com.tonyisup.poseguidesnap.data.ShootReorderResult
import com.tonyisup.poseguidesnap.data.ShootStartResult
import com.tonyisup.poseguidesnap.importer.ReferenceImportAllocationRequest
import com.tonyisup.poseguidesnap.importer.ReferenceImportAllocationResult
import com.tonyisup.poseguidesnap.importer.ReferenceImportApplicationService
import com.tonyisup.poseguidesnap.importer.ReferenceImportOutcome
import com.tonyisup.poseguidesnap.importer.ReferencePickerImportDraft
import com.tonyisup.poseguidesnap.importer.ReferencePickerResult
import com.tonyisup.poseguidesnap.importer.ReferencePickerResultHandler
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal interface ShootEditorRoomPort {
    fun observeShootEditor(shootId: String): Flow<ShootEditorSnapshot?>

    fun reorderValidatedReferences(
        shootId: String,
        orderedPoseIds: List<String>,
        reorderedAtEpochMillis: Long,
    ): ShootReorderResult

    fun startShoot(
        shootId: String,
        sessionId: String,
        startedAtEpochMillis: Long,
    ): ShootStartResult
}

internal class RoomShootEditorAdapter(
    private val repository: RoomShootPreparationRepository,
) : ShootEditorRoomPort {
    override fun observeShootEditor(shootId: String): Flow<ShootEditorSnapshot?> =
        repository.observeShootEditor(shootId)

    override fun reorderValidatedReferences(
        shootId: String,
        orderedPoseIds: List<String>,
        reorderedAtEpochMillis: Long,
    ): ShootReorderResult = repository.reorderValidatedReferences(
        shootId,
        orderedPoseIds,
        reorderedAtEpochMillis,
    )

    override fun startShoot(
        shootId: String,
        sessionId: String,
        startedAtEpochMillis: Long,
    ): ShootStartResult = repository.startShoot(shootId, sessionId, startedAtEpochMillis)
}

internal fun interface ShootEditorActiveSessionPort {
    fun findActiveGuidedSession(shootId: String): ActiveGuidedSessionResult
}

internal class RoomShootEditorActiveSessionAdapter(
    private val repository: RoomShootRepository,
) : ShootEditorActiveSessionPort {
    override fun findActiveGuidedSession(shootId: String): ActiveGuidedSessionResult =
        repository.findActiveGuidedSession(shootId)
}

internal interface ShootEditorImportApplicationPort {
    fun allocate(request: ReferenceImportAllocationRequest): ReferenceImportAllocationResult
    fun classify(result: ReferencePickerResult): ReferenceImportOutcome
}

internal class ShootEditorImportApplicationAdapter(
    private val service: ReferenceImportApplicationService,
) : ShootEditorImportApplicationPort {
    override fun allocate(request: ReferenceImportAllocationRequest): ReferenceImportAllocationResult =
        service.allocate(request)

    override fun classify(result: ReferencePickerResult): ReferenceImportOutcome = service.classify(result)
}

internal fun interface ShootEditorResourceLease : AutoCloseable {
    override fun close()
}

internal interface ShootEditorResourceAuthority {
    fun tryAcquire(): ShootEditorResourceLease?
    fun close()
}

/** Denies new work immediately and physically closes only after every acquired lease returns. */
internal class DeferredShootEditorResourceAuthority(
    private val closeResources: () -> Unit,
    private val invalidatePicker: () -> Unit,
) : ShootEditorResourceAuthority {
    private val lock = Any()
    private var leaseCount = 0
    private var closing = false
    private var closed = false
    private var invalidated = false

    override fun tryAcquire(): ShootEditorResourceLease? = synchronized(lock) {
        if (closing) return@synchronized null
        leaseCount += 1
        Lease()
    }

    override fun close() {
        val actions = synchronized(lock) {
            val shouldInvalidate = !invalidated
            invalidated = true
            closing = true
            CloseActions(shouldInvalidate, claimPhysicalCloseLocked())
        }
        if (actions.invalidate) invalidatePicker()
        if (actions.physicalClose) closeResources()
    }

    private fun release() {
        val physicalClose = synchronized(lock) {
            check(leaseCount > 0) { "editor resource lease underflow" }
            leaseCount -= 1
            claimPhysicalCloseLocked()
        }
        if (physicalClose) closeResources()
    }

    private fun claimPhysicalCloseLocked(): Boolean {
        if (!closing || closed || leaseCount != 0) return false
        closed = true
        return true
    }

    private inner class Lease : ShootEditorResourceLease {
        private var released = false
        override fun close() {
            val releaseNow = synchronized(lock) {
                if (released) false else true.also { released = true }
            }
            if (releaseNow) release()
        }
    }

    private data class CloseActions(val invalidate: Boolean, val physicalClose: Boolean)
}

/** One fieldless object is minted per authorization; all sensitive mapping stays in the registry. */
private class ProductionShootEditorPickerLaunch : ShootEditorPickerLaunch()

internal class ShootEditorPickerRegistry {
    private val lock = Any()
    private var authorizedLaunch: ShootEditorPickerLaunch? = null
    private var authorizedDraft: ReferencePickerImportDraft? = null
    private var invalidated = false

    fun replace(draft: ReferencePickerImportDraft): ShootEditorPickerLaunch = synchronized(lock) {
        if (invalidated) throw ShootEditorPickerRegistryClosedException()
        ProductionShootEditorPickerLaunch().also { launch ->
            authorizedLaunch = launch
            authorizedDraft = draft
        }
    }

    fun consume(launch: ShootEditorPickerLaunch): ReferencePickerImportDraft? = synchronized(lock) {
        if (authorizedLaunch !== launch) return@synchronized null
        val draft = authorizedDraft
        authorizedLaunch = null
        authorizedDraft = null
        draft
    }

    fun invalidate() {
        synchronized(lock) {
            invalidated = true
            authorizedLaunch = null
            authorizedDraft = null
        }
    }

    override fun toString(): String = "ShootEditorPickerRegistry(redacted)"
}

private class ShootEditorPickerRegistryClosedException : IllegalStateException(
    "editor picker registry unavailable",
)

internal fun interface ShootEditorPickerHandlerPort {
    suspend fun handle(uri: Uri?, draft: ReferencePickerImportDraft): ReferencePickerResult
}

internal class ReferencePickerResultHandlerAdapter(
    private val handler: ReferencePickerResultHandler,
) : ShootEditorPickerHandlerPort {
    override suspend fun handle(uri: Uri?, draft: ReferencePickerImportDraft): ReferencePickerResult =
        handler.handle(uri, draft)
}

/** The only editor API allowed to receive the immediate Android picker callback URI. */
internal class ShootEditorPickerCoordinator(
    private val registry: ShootEditorPickerRegistry,
    private val handler: ShootEditorPickerHandlerPort,
    private val authority: ShootEditorResourceAuthority,
) {
    suspend fun handle(uri: Uri?, launch: ShootEditorPickerLaunch): ReferencePickerResult {
        val lease = authority.tryAcquire() ?: return ReferencePickerResult.Cancelled
        try {
            val draft = registry.consume(launch) ?: return ReferencePickerResult.Cancelled
            return handler.handle(uri, draft)
        } finally {
            lease.close()
        }
    }

    override fun toString(): String = "ShootEditorPickerCoordinator(redacted)"
}

internal class RoomShootEditorWorkflow(
    private val repository: ShootEditorRoomPort,
    private val activeSessions: ShootEditorActiveSessionPort,
    private val imports: ShootEditorImportApplicationPort,
    private val pickerRegistry: ShootEditorPickerRegistry,
    private val authority: ShootEditorResourceAuthority,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val wallClockProvider: () -> Long = System::currentTimeMillis,
    private val sessionIdProvider: () -> String = { UUID.randomUUID().toString() },
) : ShootEditorWorkflowPort {
    private val startIdentityLock = Any()
    private var startIdentity: StartIdentity? = null

    override fun observeEditorSnapshot(shootId: String): Flow<ShootEditorDisplaySnapshot?> = flow {
        val lease = authority.tryAcquire() ?: throw ShootEditorAuthorityUnavailableException()
        try {
            repository.observeShootEditor(shootId).collect { snapshot ->
                if (snapshot == null) {
                    emit(null)
                } else if (snapshot.lifecycle == com.tonyisup.poseguidesnap.data.ShootPreparationLifecycle.DELETING) {
                    emit(snapshot.toDisplaySnapshot(shootId, false))
                } else {
                    val hasResumableSession = when (activeSessions.findActiveGuidedSession(shootId)) {
                        is ActiveGuidedSessionResult.Exact -> true
                        ActiveGuidedSessionResult.None -> false
                        ActiveGuidedSessionResult.UnknownShoot,
                        is ActiveGuidedSessionResult.Rejected,
                        -> throw ShootEditorProjectionUnavailableException()
                    }
                    emit(snapshot.toDisplaySnapshot(shootId, hasResumableSession))
                }
            }
        } finally {
            lease.close()
        }
    }.flowOn(blockingDispatcher)

    override suspend fun allocateImport(
        shootId: String,
        label: String,
    ): ShootEditorImportAllocationOutcome = withContext(blockingDispatcher) {
        val lease = authority.tryAcquire() ?: return@withContext allocationUnavailable()
        try {
            when (val result = imports.allocate(ReferenceImportAllocationRequest(shootId, label))) {
                is ReferenceImportAllocationResult.Ready -> try {
                    ShootEditorImportAllocationOutcome.Ready(pickerRegistry.replace(result.draft))
                } catch (_: ShootEditorPickerRegistryClosedException) {
                    allocationUnavailable()
                }
                is ReferenceImportAllocationResult.Blocked -> ShootEditorImportAllocationOutcome.Blocked(
                    result.reason,
                    result.retryAction,
                )
            }
        } finally {
            lease.close()
        }
    }

    override fun classifyPickerResult(result: ReferencePickerResult): ReferenceImportOutcome =
        imports.classify(result)

    override suspend fun reorder(
        shootId: String,
        orderedPoseIds: List<String>,
    ): ShootReorderResult = withContext(blockingDispatcher) {
        val lease = authority.tryAcquire() ?: return@withContext ShootReorderResult.AuthorityInconsistent
        try {
            repository.reorderValidatedReferences(shootId, orderedPoseIds, wallClockProvider())
        } finally {
            lease.close()
        }
    }

    override suspend fun start(shootId: String): ShootEditorStartOutcome = withContext(blockingDispatcher) {
        val lease = authority.tryAcquire() ?: return@withContext rejected(
            ShootEditorStartRejectionReason.AUTHORITY_UNAVAILABLE,
        )
        try {
            val identity = try {
                startIdentity()
            } catch (_: IllegalArgumentException) {
                return@withContext rejected(ShootEditorStartRejectionReason.INVALID_REQUEST)
            } catch (_: Exception) {
                return@withContext rejected(ShootEditorStartRejectionReason.AUTHORITY_UNAVAILABLE)
            }
            val handle = StartedSessionHandle(identity.sessionId)
            when (
                val result = repository.startShoot(
                    shootId,
                    identity.sessionId,
                    identity.startedAtEpochMillis,
                )
            ) {
                ShootStartResult.Started -> ShootEditorStartOutcome.Started(handle)
                ShootStartResult.AlreadyStarted -> ShootEditorStartOutcome.Resumable(handle)
                is ShootStartResult.InvalidRequest -> rejected(ShootEditorStartRejectionReason.INVALID_REQUEST)
                ShootStartResult.UnknownShoot -> rejected(ShootEditorStartRejectionReason.UNKNOWN_SHOOT)
                ShootStartResult.ShootDeleting -> rejected(ShootEditorStartRejectionReason.SHOOT_DELETING)
                is ShootStartResult.IneligiblePlaylist -> rejected(
                    ShootEditorStartRejectionReason.INELIGIBLE_PLAYLIST,
                )
                ShootStartResult.UnresolvedImportWork -> rejected(
                    ShootEditorStartRejectionReason.UNRESOLVED_IMPORT_WORK,
                )
                ShootStartResult.ActiveSessionConflict -> rejected(
                    ShootEditorStartRejectionReason.ACTIVE_SESSION_CONFLICT,
                )
                ShootStartResult.SessionIdentityConflict -> {
                    clearStartIdentity(identity)
                    rejected(ShootEditorStartRejectionReason.SESSION_IDENTITY_CONFLICT)
                }
                ShootStartResult.StaleOrConflictingReplay -> rejected(
                    ShootEditorStartRejectionReason.STALE_OR_CONFLICTING_REPLAY,
                )
                ShootStartResult.AuthorityInconsistent -> rejected(
                    ShootEditorStartRejectionReason.AUTHORITY_INCONSISTENT,
                )
            }
        } finally {
            lease.close()
        }
    }

    override suspend fun resume(shootId: String): ShootEditorResumeOutcome = withContext(blockingDispatcher) {
        val lease = authority.tryAcquire() ?: return@withContext ShootEditorResumeOutcome.Rejected(
            ShootEditorResumeRejectionReason.AUTHORITY_UNAVAILABLE,
        )
        try {
            try {
                when (val result = activeSessions.findActiveGuidedSession(shootId)) {
                    is ActiveGuidedSessionResult.Exact ->
                        ShootEditorResumeOutcome.Resumable(StartedSessionHandle(result.sessionId))
                    ActiveGuidedSessionResult.None,
                    ActiveGuidedSessionResult.UnknownShoot,
                    -> ShootEditorResumeOutcome.Stale
                    is ActiveGuidedSessionResult.Rejected -> ShootEditorResumeOutcome.Rejected(
                        when (result.reason) {
                            ActiveGuidedSessionRejectionReason.INVALID_REQUEST ->
                                ShootEditorResumeRejectionReason.INVALID_REQUEST
                            ActiveGuidedSessionRejectionReason.AUTHORITY_INCONSISTENT ->
                                ShootEditorResumeRejectionReason.AUTHORITY_INCONSISTENT
                            ActiveGuidedSessionRejectionReason.AUTHORITY_UNAVAILABLE ->
                                ShootEditorResumeRejectionReason.AUTHORITY_UNAVAILABLE
                        },
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                ShootEditorResumeOutcome.Rejected(ShootEditorResumeRejectionReason.AUTHORITY_UNAVAILABLE)
            }
        } finally {
            lease.close()
        }
    }

    override fun toString(): String = "RoomShootEditorWorkflow(redacted)"

    private fun startIdentity(): StartIdentity = synchronized(startIdentityLock) {
        startIdentity ?: run {
            val sessionId = sessionIdProvider()
            StartedSessionHandle(sessionId)
            val startedAtEpochMillis = wallClockProvider()
            require(startedAtEpochMillis >= 0L) { "start time must be nonnegative" }
            StartIdentity(sessionId, startedAtEpochMillis).also { startIdentity = it }
        }
    }

    private fun clearStartIdentity(identity: StartIdentity) {
        synchronized(startIdentityLock) {
            if (startIdentity === identity) startIdentity = null
        }
    }

    private fun ShootEditorSnapshot.toDisplaySnapshot(
        expectedShootId: String,
        hasResumableSession: Boolean,
    ): ShootEditorDisplaySnapshot {
        require(shootId == expectedShootId) { "editor projection identity mismatch" }
        return ShootEditorDisplaySnapshot(
            name = name,
            lifecycle = lifecycle,
            references = validatedReferences.map { reference ->
                ShootEditorReferenceItem(
                    poseId = reference.poseId,
                    poseIndex = reference.poseIndex,
                    label = reference.label,
                    mirrorAllowed = reference.mirrorAllowed,
                )
            },
            importWorkStatuses = importWork.asSequence()
                .map { work -> work.status }
                .filter { status ->
                    status == ImportWorkStatus.IN_PROGRESS ||
                        status == ImportWorkStatus.RECONCILIATION_REQUIRED
                }
                .asIterable(),
            hasResumableSession = hasResumableSession,
        )
    }

    private fun allocationUnavailable() = ShootEditorImportAllocationOutcome.Blocked(
        com.tonyisup.poseguidesnap.importer.ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
        com.tonyisup.poseguidesnap.importer.ReferenceImportRetryAction.RETRY_ALLOCATION,
    )

    private fun rejected(reason: ShootEditorStartRejectionReason) =
        ShootEditorStartOutcome.Rejected(reason)

    private class StartIdentity(
        val sessionId: String,
        val startedAtEpochMillis: Long,
    ) {
        override fun toString(): String = "StartIdentity(redacted)"
    }
}

private class ShootEditorProjectionUnavailableException : IllegalStateException(
    "editor projection unavailable",
)

private class ShootEditorAuthorityUnavailableException : IllegalStateException(
    "editor authority unavailable",
)
