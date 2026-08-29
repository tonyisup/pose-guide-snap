package com.tonyisup.poseguidesnap.camera

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.util.Collections
import java.util.concurrent.Executor

enum class ThreePhotoCaptureSubmission {
    ACCEPTED,
    REJECTED_BUSY,
    REJECTED_CLOSED,
}

enum class ThreePhotoCleanupRetryOutcome {
    NO_CLEANUP_PENDING,
    CLEANED,
    STILL_PENDING,
}

enum class ThreePhotoCaptureFailureStage {
    PREPARE,
    WRITE,
    PUBLICATION,
    CLEANUP,
    RECONCILIATION_REQUIRED,
    CLOSED_WITH_PARTIAL,
}

class ThreePhotoCaptureSuccess internal constructor(
    val token: CaptureToken,
    outputs: Iterable<PublishedPrivateOutput>,
) {
    val outputs: List<PublishedPrivateOutput> =
        Collections.unmodifiableList(ArrayList<PublishedPrivateOutput>().apply { addAll(outputs) })

    override fun toString(): String = "ThreePhotoCaptureSuccess(outputCount=${outputs.size})"
}

class ThreePhotoCaptureFailure internal constructor(
    val token: CaptureToken,
    val failedIdentity: PrivateOutputIdentity,
    publishedOutputs: Iterable<PublishedPrivateOutput>,
    val finalMayExist: Boolean,
    val reconciliationRequired: Boolean,
    val cleanupPending: Boolean,
    val stage: ThreePhotoCaptureFailureStage,
) {
    val publishedOutputs: List<PublishedPrivateOutput> =
        Collections.unmodifiableList(ArrayList<PublishedPrivateOutput>().apply { addAll(publishedOutputs) })

    override fun toString(): String =
        "ThreePhotoCaptureFailure(failedOrdinal=${failedIdentity.ordinal}, " +
            "publishedCount=${publishedOutputs.size}, finalMayExist=$finalMayExist, " +
            "reconciliationRequired=$reconciliationRequired, cleanupPending=$cleanupPending, stage=$stage)"
}

internal interface StillCaptureWriter {
    fun write(prepared: PreparedPrivateOutput, callback: Callback)

    interface Callback {
        fun onImageSaved()
        fun onError()
    }
}

private class CameraXStillCaptureWriter(
    private val imageCapture: ImageCapture,
    private val executor: Executor,
) : StillCaptureWriter {
    override fun write(prepared: PreparedPrivateOutput, callback: StillCaptureWriter.Callback) {
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(prepared.tempFile).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    callback.onImageSaved()
                }

                override fun onError(exception: ImageCaptureException) {
                    callback.onError()
                }
            },
        )
    }
}

/**
 * Captures only the three private candidate files named by one reducer capture command.
 *
 * Rejected submissions start no work and invoke neither callback. Closing does not cancel a write
 * already handed to CameraX and never closes the caller-owned executor, controller, or use case.
 */
class CameraXThreePhotoCapture internal constructor(
    private val writer: StillCaptureWriter,
    private val publisher: PrivateCaptureFilePublisher,
) : AutoCloseable {
    private enum class CurrentPhase {
        WRITING,
        PUBLISHING,
        TERMINATING,
        CLEANUP_PENDING,
    }

    private class ActiveCapture(
        val command: ShootEffect.CaptureCommand,
        val onSuccess: (ThreePhotoCaptureSuccess) -> Unit,
        val onFailure: (ThreePhotoCaptureFailure) -> Unit,
    ) {
        val publishedOutputs = ArrayList<PublishedPrivateOutput>(3)
        var prepared: PreparedPrivateOutput? = null
        var pendingCleanupOwner: PrivateCaptureCleanupOwner? = null
        var phase: CurrentPhase? = null
        var terminalCallbackReported = false
    }

    private val lock = Any()
    private var closed = false
    private var active: ActiveCapture? = null

    constructor(
        imageCapture: ImageCapture,
        callbackExecutor: Executor,
        publisher: PrivateCaptureFilePublisher,
    ) : this(CameraXStillCaptureWriter(imageCapture, callbackExecutor), publisher)

    fun submit(
        command: ShootEffect.CaptureCommand,
        onSuccess: (ThreePhotoCaptureSuccess) -> Unit,
        onFailure: (ThreePhotoCaptureFailure) -> Unit,
    ): ThreePhotoCaptureSubmission {
        requireCompletePrivateOutputSet(command.outputs)
        require(command.outputs.all { it.token == command.token }) {
            "capture command outputs must belong to its token"
        }
        val accepted = synchronized(lock) {
            when {
                closed -> null
                active != null -> null
                else -> ActiveCapture(command, onSuccess, onFailure).also { active = it }
            }
        }
        if (accepted == null) {
            return synchronized(lock) {
                if (closed) {
                    ThreePhotoCaptureSubmission.REJECTED_CLOSED
                } else {
                    ThreePhotoCaptureSubmission.REJECTED_BUSY
                }
            }
        }

        startNext(accepted)
        return ThreePhotoCaptureSubmission.ACCEPTED
    }

    override fun close() {
        synchronized(lock) { closed = true }
        retryPendingCleanup()
    }

    fun retryPendingCleanup(): ThreePhotoCleanupRetryOutcome = synchronized(lock) {
        val capture = active ?: return@synchronized ThreePhotoCleanupRetryOutcome.NO_CLEANUP_PENDING
        val cleanupOwner = capture.pendingCleanupOwner
            ?: return@synchronized ThreePhotoCleanupRetryOutcome.NO_CLEANUP_PENDING
        if (capture.phase != CurrentPhase.CLEANUP_PENDING) {
            return@synchronized ThreePhotoCleanupRetryOutcome.NO_CLEANUP_PENDING
        }

        try {
            cleanupOwner.close()
        } catch (_: Throwable) {
            return@synchronized ThreePhotoCleanupRetryOutcome.STILL_PENDING
        }
        capture.pendingCleanupOwner = null
        capture.phase = null
        active = null
        ThreePhotoCleanupRetryOutcome.CLEANED
    }

    private fun startNext(capture: ActiveCapture) {
        var prepared: PreparedPrivateOutput? = null
        var prepareFailureStage: ThreePhotoCaptureFailureStage? = null
        var prepareFailureFinalMayExist = false
        var prepareFailureReconciliationRequired = false
        var prepareFailureCleanupPending = false
        var stoppedIdentity: PrivateOutputIdentity? = null
        synchronized(lock) {
            if (active !== capture) return
            val nextIdentity = capture.command.outputs[capture.publishedOutputs.size]
            if (closed) {
                stoppedIdentity = nextIdentity
            } else {
                try {
                    prepared = publisher.prepare(nextIdentity).also {
                        capture.prepared = it
                        capture.phase = CurrentPhase.WRITING
                    }
                } catch (failure: PrivateCapturePreparationCleanupRequired) {
                    capture.pendingCleanupOwner = failure.cleanupOwner
                    prepareFailureStage = ThreePhotoCaptureFailureStage.CLEANUP
                    prepareFailureFinalMayExist = true
                    prepareFailureCleanupPending = true
                    capture.phase = CurrentPhase.CLEANUP_PENDING
                } catch (_: PrivateCaptureReconciliationRequired) {
                    prepareFailureStage = ThreePhotoCaptureFailureStage.RECONCILIATION_REQUIRED
                    prepareFailureFinalMayExist = true
                    prepareFailureReconciliationRequired = true
                    capture.phase = CurrentPhase.TERMINATING
                } catch (_: Throwable) {
                    prepareFailureStage = ThreePhotoCaptureFailureStage.PREPARE
                    capture.phase = CurrentPhase.TERMINATING
                }
            }
        }

        stoppedIdentity?.let {
            finishFailure(
                capture = capture,
                failedIdentity = it,
                stage = ThreePhotoCaptureFailureStage.CLOSED_WITH_PARTIAL,
                finalMayExist = false,
                reconciliationRequired = false,
            )
            return
        }
        prepareFailureStage?.let { stage ->
            finishFailure(
                capture = capture,
                failedIdentity = capture.command.outputs[capture.publishedOutputs.size],
                stage = stage,
                finalMayExist = prepareFailureFinalMayExist,
                reconciliationRequired = prepareFailureReconciliationRequired,
                cleanupPending = prepareFailureCleanupPending,
            )
            return
        }

        val current = requireNotNull(prepared)
        try {
            writer.write(
                current,
                object : StillCaptureWriter.Callback {
                    override fun onImageSaved() {
                        writerSucceeded(capture, current)
                    }

                    override fun onError() {
                        writerFailed(capture, current)
                    }
                },
            )
        } catch (_: Throwable) {
            writerFailed(capture, current)
        }
    }

    private fun writerSucceeded(capture: ActiveCapture, prepared: PreparedPrivateOutput) {
        val ownsCurrent = synchronized(lock) {
            if (
                active !== capture ||
                capture.prepared !== prepared ||
                capture.phase != CurrentPhase.WRITING
            ) {
                false
            } else {
                capture.phase = CurrentPhase.PUBLISHING
                true
            }
        }
        if (!ownsCurrent) return

        val published = try {
            prepared.publish()
        } catch (_: PrivateCaptureReconciliationRequired) {
            failPrepared(
                capture,
                prepared,
                ThreePhotoCaptureFailureStage.RECONCILIATION_REQUIRED,
                finalMayExist = true,
                reconciliationRequired = true,
            )
            return
        } catch (_: Throwable) {
            failPrepared(
                capture,
                prepared,
                ThreePhotoCaptureFailureStage.PUBLICATION,
                finalMayExist = false,
                reconciliationRequired = false,
            )
            return
        }

        var shouldContinue = false
        var shouldSucceed = false
        var closedFailureIdentity: PrivateOutputIdentity? = null
        synchronized(lock) {
            if (active !== capture || capture.prepared !== prepared) return
            capture.publishedOutputs += published
            capture.prepared = null
            capture.phase = null
            when {
                capture.publishedOutputs.size == 3 -> shouldSucceed = true
                closed -> {
                    closedFailureIdentity = capture.command.outputs[capture.publishedOutputs.size]
                }
                else -> shouldContinue = true
            }
        }

        closedFailureIdentity?.let {
            finishFailure(
                capture,
                it,
                ThreePhotoCaptureFailureStage.CLOSED_WITH_PARTIAL,
                finalMayExist = false,
                reconciliationRequired = false,
            )
            return
        }
        if (shouldSucceed) {
            finishSuccess(capture)
        } else if (shouldContinue) {
            startNext(capture)
        }
    }

    private fun writerFailed(capture: ActiveCapture, prepared: PreparedPrivateOutput) {
        failPrepared(
            capture,
            prepared,
            ThreePhotoCaptureFailureStage.WRITE,
            finalMayExist = false,
            reconciliationRequired = false,
        )
    }

    private fun failPrepared(
        capture: ActiveCapture,
        prepared: PreparedPrivateOutput,
        intendedStage: ThreePhotoCaptureFailureStage,
        finalMayExist: Boolean,
        reconciliationRequired: Boolean,
    ) {
        val expectedPhase = if (intendedStage == ThreePhotoCaptureFailureStage.WRITE) {
            CurrentPhase.WRITING
        } else {
            CurrentPhase.PUBLISHING
        }
        val ownsCurrent = synchronized(lock) {
            if (
                active !== capture ||
                capture.prepared !== prepared ||
                capture.phase != expectedPhase
            ) {
                false
            } else {
                capture.phase = CurrentPhase.TERMINATING
                true
            }
        }
        if (!ownsCurrent) return

        var terminalStage = intendedStage
        var cleanupPending = false
        try {
            prepared.close()
        } catch (_: Throwable) {
            cleanupPending = true
            if (!reconciliationRequired) terminalStage = ThreePhotoCaptureFailureStage.CLEANUP
        }
        synchronized(lock) {
            if (active === capture && capture.prepared === prepared) {
                capture.prepared = null
                if (cleanupPending) capture.pendingCleanupOwner = prepared
            }
        }
        finishFailure(
            capture,
            prepared.identity,
            terminalStage,
            finalMayExist || cleanupPending,
            reconciliationRequired,
            cleanupPending,
        )
    }

    private fun finishSuccess(capture: ActiveCapture) {
        val result = ThreePhotoCaptureSuccess(capture.command.token, capture.publishedOutputs)
        val callback = synchronized(lock) {
            if (active !== capture) return
            active = null
            capture.onSuccess
        }
        invokeSafely { callback(result) }
    }

    private fun finishFailure(
        capture: ActiveCapture,
        failedIdentity: PrivateOutputIdentity,
        stage: ThreePhotoCaptureFailureStage,
        finalMayExist: Boolean,
        reconciliationRequired: Boolean,
        cleanupPending: Boolean = false,
    ) {
        val result = ThreePhotoCaptureFailure(
            token = capture.command.token,
            failedIdentity = failedIdentity,
            publishedOutputs = capture.publishedOutputs,
            finalMayExist = finalMayExist,
            reconciliationRequired = reconciliationRequired,
            cleanupPending = cleanupPending,
            stage = stage,
        )
        val callback = synchronized(lock) {
            if (active !== capture || capture.terminalCallbackReported) return
            capture.terminalCallbackReported = true
            if (cleanupPending) {
                capture.phase = CurrentPhase.CLEANUP_PENDING
            } else {
                active = null
            }
            capture.onFailure
        }
        invokeSafely { callback(result) }
    }

    private fun invokeSafely(callback: () -> Unit) {
        try {
            callback()
        } catch (_: Throwable) {
            // Caller callbacks cannot become capture control flow.
        }
    }

    companion object {
        @JvmStatic
        fun create(
            controller: CameraXController,
            context: Context,
            callbackExecutor: Executor,
        ): CameraXThreePhotoCapture = CameraXThreePhotoCapture(
            imageCapture = controller.requireImageCapture(),
            callbackExecutor = callbackExecutor,
            publisher = androidPrivateCaptureFilePublisher(
                context.applicationContext.noBackupFilesDir.resolve("capture-candidates"),
            ),
        )
    }
}
