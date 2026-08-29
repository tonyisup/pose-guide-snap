package com.tonyisup.poseguidesnap.camera

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Runs blocking frame work on a supplied executor while retaining only the latest pending frame.
 *
 * The processor never owns or shuts down [executor]. Every submitted frame is released by calling
 * [AutoCloseable.close] exactly once, including frames rejected or replaced before processing.
 */
class KeepLatestFrameProcessor<F : AutoCloseable, R>(
    private val executor: Executor,
    private val process: (F) -> R,
    private val onResult: (R) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    enum class SubmissionOutcome {
        STARTED,
        QUEUED,
        REPLACED,
        REJECTED_CLOSED,
        REJECTED_EXECUTOR,
    }

    private val lock = Any()
    private var running = false
    private var closed = false
    private var pending: F? = null

    fun submit(frame: F): SubmissionOutcome {
        var frameToRelease: F? = null
        var shouldSchedule = false
        val outcome = synchronized(lock) {
            when {
                closed -> {
                    frameToRelease = frame
                    SubmissionOutcome.REJECTED_CLOSED
                }

                !running -> {
                    running = true
                    shouldSchedule = true
                    SubmissionOutcome.STARTED
                }

                pending == null -> {
                    pending = frame
                    SubmissionOutcome.QUEUED
                }

                else -> {
                    frameToRelease = pending
                    pending = frame
                    SubmissionOutcome.REPLACED
                }
            }
        }

        frameToRelease?.let(::releaseSafely)
        if (!shouldSchedule) return outcome

        return try {
            executor.execute { drain(frame) }
            outcome
        } catch (failure: RejectedExecutionException) {
            val abandonedPending = synchronized(lock) {
                running = false
                pending.also { pending = null }
            }
            releaseSafely(frame)
            abandonedPending?.let(::releaseSafely)
            reportFailureSafely(failure)
            SubmissionOutcome.REJECTED_EXECUTOR
        }
    }

    override fun close() {
        val frameToRelease = synchronized(lock) {
            if (closed) {
                null
            } else {
                closed = true
                pending.also { pending = null }
            }
        }
        frameToRelease?.let(::releaseSafely)
    }

    private fun drain(initialFrame: F) {
        var current: F? = initialFrame
        while (current != null) {
            val frame = current
            val shouldProcess = synchronized(lock) { !closed }

            if (shouldProcess) processSafely(frame)
            releaseSafely(frame)

            current = synchronized(lock) {
                pending.also { next ->
                    pending = null
                    if (next == null) running = false
                }
            }
        }
    }

    private fun processSafely(frame: F) {
        val result = try {
            process(frame)
        } catch (failure: Throwable) {
            reportFailureSafely(failure)
            return
        }

        try {
            onResult(result)
        } catch (failure: Throwable) {
            reportFailureSafely(failure)
        }
    }

    private fun releaseSafely(frame: F) {
        try {
            frame.close()
        } catch (failure: Throwable) {
            reportFailureSafely(failure)
        }
    }

    private fun reportFailureSafely(failure: Throwable) {
        try {
            onFailure(failure)
        } catch (_: Throwable) {
            // A failure observer cannot become worker control flow.
        }
    }
}
