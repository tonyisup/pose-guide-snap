package com.tonyisup.poseguidesnap.ui.session

import com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapResult
import com.tonyisup.poseguidesnap.data.RoomShootRepository
import com.tonyisup.poseguidesnap.ui.editor.StartedSessionHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class RoomStartedSessionBootstrapRepositoryAdapter(
    private val repository: RoomShootRepository,
) : StartedSessionBootstrapRepositoryPort {
    override fun loadGuidedSessionBootstrap(sessionId: String): GuidedSessionBootstrapResult =
        repository.loadGuidedSessionBootstrap(sessionId)
}

internal fun interface StartedSessionResourceLease : AutoCloseable {
    override fun close()
}

internal class StartedSessionResourceAuthority(
    private val closeResource: () -> Unit,
) {
    private val lock = Any()
    private var leaseCount = 0
    private var closing = false
    private var closed = false

    fun tryAcquire(): StartedSessionResourceLease? = synchronized(lock) {
        if (closing) return@synchronized null
        leaseCount += 1
        Lease()
    }

    fun close() {
        val closeNow = synchronized(lock) {
            closing = true
            claimCloseLocked()
        }
        if (closeNow) closeResource()
    }

    private fun release() {
        val closeNow = synchronized(lock) {
            check(leaseCount > 0) { "started session resource lease underflow" }
            leaseCount -= 1
            claimCloseLocked()
        }
        if (closeNow) closeResource()
    }

    private fun claimCloseLocked(): Boolean {
        if (!closing || closed || leaseCount != 0) return false
        closed = true
        return true
    }

    private inner class Lease : StartedSessionResourceLease {
        private var released = false

        override fun close() {
            val releaseNow = synchronized(lock) {
                if (released) false else true.also { released = true }
            }
            if (releaseNow) release()
        }
    }

    override fun toString(): String = "StartedSessionResourceAuthority(redacted)"
}

internal class RoomStartedSessionBootstrapWorkflow(
    private val repository: StartedSessionBootstrapRepositoryPort,
    private val authority: StartedSessionResourceAuthority,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OwnedStartedSessionBootstrapWorkflow {
    override suspend fun load(handle: StartedSessionHandle): StartedSessionBootstrapState =
        withContext(blockingDispatcher) {
            val lease = authority.tryAcquire()
                ?: return@withContext StartedSessionBootstrapState.Unavailable(canRetry = true)
            try {
                val result = try {
                    repository.loadGuidedSessionBootstrap(handle.navigationKey)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: RuntimeException) {
                    return@withContext StartedSessionBootstrapState.Unavailable(canRetry = true)
                }
                mapBootstrapResult(handle, result)
            } finally {
                lease.close()
            }
        }

    override fun close() {
        authority.close()
    }

    override fun toString(): String = "RoomStartedSessionBootstrapWorkflow(redacted)"

    private fun mapBootstrapResult(
        handle: StartedSessionHandle,
        result: GuidedSessionBootstrapResult,
    ): StartedSessionBootstrapState = when (result) {
        is GuidedSessionBootstrapResult.Ready ->
            if (result.snapshot.sessionId == handle.navigationKey) {
                StartedSessionBootstrapState.Ready(result.snapshot)
            } else {
                StartedSessionBootstrapState.Unavailable(canRetry = false)
            }
        is GuidedSessionBootstrapResult.Completed ->
            if (result.snapshot.sessionId == handle.navigationKey) {
                StartedSessionBootstrapState.Completed
            } else {
                StartedSessionBootstrapState.Unavailable(canRetry = false)
            }
        is GuidedSessionBootstrapResult.ReconciliationRequired ->
            if (result.snapshot.sessionId == handle.navigationKey) {
                StartedSessionBootstrapState.ReconciliationRequired
            } else {
                StartedSessionBootstrapState.Unavailable(canRetry = false)
            }
        GuidedSessionBootstrapResult.UnknownSession -> StartedSessionBootstrapState.Missing
        is GuidedSessionBootstrapResult.Rejected -> when (result.reason) {
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.AUTHORITY_UNAVAILABLE ->
                StartedSessionBootstrapState.Unavailable(canRetry = true)
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.INVALID_REQUEST,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.ORPHANED_AUTHORITY,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.INVALID_SHOOT_AUTHORITY,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.INVALID_SESSION_AUTHORITY,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.INVALID_POSE_AUTHORITY,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.INVALID_CAPTURE_FILE_OPERATION_AUTHORITY,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.INVALID_PRIVATE_OUTPUT_AUTHORITY,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.INVALID_RECEIPT_AUTHORITY,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.INVALID_OUTBOX_AUTHORITY,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.UNSUPPORTED_LIFECYCLE,
            com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapRejectionReason.AUTHORITY_INCONSISTENT,
            -> StartedSessionBootstrapState.Unavailable(canRetry = false)
        }
    }
}
