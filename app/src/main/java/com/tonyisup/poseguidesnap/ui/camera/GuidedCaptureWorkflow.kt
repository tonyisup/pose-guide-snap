package com.tonyisup.poseguidesnap.ui.camera

import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.tonyisup.poseguidesnap.camera.CaptureAttemptStartupReconciler
import com.tonyisup.poseguidesnap.camera.JournaledCaptureFileAdapter
import com.tonyisup.poseguidesnap.camera.JournaledCaptureRecoveryPort
import com.tonyisup.poseguidesnap.camera.JournaledCaptureResult
import com.tonyisup.poseguidesnap.camera.JournaledCaptureSubmission
import com.tonyisup.poseguidesnap.camera.JournaledConfirmationResult
import com.tonyisup.poseguidesnap.camera.JournaledStillCaptureWriter
import com.tonyisup.poseguidesnap.camera.JournaledThreePhotoCaptureCoordinator
import com.tonyisup.poseguidesnap.camera.RoomJournaledCaptureAuthorityAdapter
import com.tonyisup.poseguidesnap.camera.SystemCaptureOperationClock
import com.tonyisup.poseguidesnap.camera.androidJournaledPrivateCaptureStore
import com.tonyisup.poseguidesnap.data.GuidedCurrentReferenceResult
import com.tonyisup.poseguidesnap.data.RoomShootRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface GuidedCaptureWorkflowPort : AutoCloseable {
    suspend fun loadCurrentReference(sessionId: String): GuidedCurrentReferenceResult
    fun attachWriter(writer: JournaledStillCaptureWriter)
    fun detachWriter(writer: JournaledStillCaptureWriter)

    fun capture(
        sessionId: String,
        command: ShootEffect.CaptureCommand,
        callback: (JournaledCaptureResult) -> Unit,
    ): JournaledCaptureSubmission

    fun confirm(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        callback: (JournaledConfirmationResult) -> Unit,
    ): JournaledCaptureSubmission
}

internal class AttachableJournaledStillCaptureWriter : JournaledStillCaptureWriter {
    private var attached: JournaledStillCaptureWriter? = null

    @Synchronized
    fun attach(writer: JournaledStillCaptureWriter) {
        attached = writer
    }

    @Synchronized
    fun detach(writer: JournaledStillCaptureWriter) {
        if (attached === writer) attached = null
    }

    override fun write(tempFile: File, callback: JournaledStillCaptureWriter.Callback) {
        val writer = synchronized(this) { attached }
        if (writer == null) callback.onError() else writer.write(tempFile, callback)
    }
}

internal class CameraXJournaledStillCaptureWriter(
    private val imageCapture: () -> ImageCapture,
    private val mainExecutor: Executor,
    private val callbackExecutor: Executor,
) : JournaledStillCaptureWriter {
    override fun write(tempFile: File, callback: JournaledStillCaptureWriter.Callback) {
        mainExecutor.execute {
            val output = try {
                FileOutputStream(tempFile, false)
            } catch (_: Throwable) {
                callback.onError()
                return@execute
            }
            val terminal = StreamTerminal(output, callback)
            try {
                imageCapture().takePicture(
                    ImageCapture.OutputFileOptions.Builder(output).build(),
                    callbackExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(
                            outputFileResults: ImageCapture.OutputFileResults,
                        ) {
                            terminal.complete(saved = true)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            terminal.complete(saved = false)
                        }
                    },
                )
            } catch (_: Throwable) {
                terminal.complete(saved = false)
            }
        }
    }

    private class StreamTerminal(
        private val output: FileOutputStream,
        private val callback: JournaledStillCaptureWriter.Callback,
    ) {
        private var completed = false

        fun complete(saved: Boolean) {
            val claimed = synchronized(this) {
                if (completed) false else true.also { completed = true }
            }
            if (!claimed) return
            val closed = try {
                output.close()
                true
            } catch (_: Throwable) {
                false
            }
            if (saved && closed) callback.onImageSaved() else callback.onError()
        }
    }
}

internal class GuidedCaptureResourceAuthority(
    private val closeResources: () -> Unit,
) {
    private var leases = 0
    private var closing = false
    private var closed = false

    @Synchronized
    fun tryAcquire(): AutoCloseable? {
        if (closing) return null
        leases += 1
        var released = false
        return AutoCloseable {
            val closeNow = synchronized(this) {
                if (released) {
                    false
                } else {
                    released = true
                    leases -= 1
                    claimClose()
                }
            }
            if (closeNow) closeResources()
        }
    }

    fun close() {
        val closeNow = synchronized(this) {
            closing = true
            claimClose()
        }
        if (closeNow) closeResources()
    }

    private fun claimClose(): Boolean {
        if (!closing || closed || leases != 0) return false
        closed = true
        return true
    }
}

internal class RoomGuidedCaptureWorkflow(
    private val repository: RoomShootRepository,
    private val writer: AttachableJournaledStillCaptureWriter,
    private val coordinator: JournaledThreePhotoCaptureCoordinator,
    private val resources: GuidedCaptureResourceAuthority,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GuidedCaptureWorkflowPort {
    override suspend fun loadCurrentReference(sessionId: String): GuidedCurrentReferenceResult {
        val lease = resources.tryAcquire() ?: return GuidedCurrentReferenceResult.AuthorityInvalid
        return try {
            withContext(blockingDispatcher) { repository.loadCurrentGuidedReference(sessionId) }
        } finally {
            lease.close()
        }
    }

    override fun attachWriter(writer: JournaledStillCaptureWriter) {
        this.writer.attach(writer)
    }

    override fun detachWriter(writer: JournaledStillCaptureWriter) {
        this.writer.detach(writer)
    }

    override fun capture(
        sessionId: String,
        command: ShootEffect.CaptureCommand,
        callback: (JournaledCaptureResult) -> Unit,
    ): JournaledCaptureSubmission {
        val lease = resources.tryAcquire() ?: return JournaledCaptureSubmission.REJECTED_CLOSED
        val submission = coordinator.submit(sessionId, command) { result ->
            try {
                callback(result)
            } finally {
                lease.close()
            }
        }
        if (submission != JournaledCaptureSubmission.ACCEPTED) lease.close()
        return submission
    }

    override fun confirm(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        callback: (JournaledConfirmationResult) -> Unit,
    ): JournaledCaptureSubmission {
        val lease = resources.tryAcquire() ?: return JournaledCaptureSubmission.REJECTED_CLOSED
        val submission = coordinator.confirm(command) { result ->
            try {
                callback(result)
            } finally {
                lease.close()
            }
        }
        if (submission != JournaledCaptureSubmission.ACCEPTED) lease.close()
        return submission
    }

    override fun close() {
        coordinator.close()
        resources.close()
    }
}

internal fun createRoomGuidedCaptureWorkflow(
    applicationContext: android.content.Context,
): RoomGuidedCaptureWorkflow {
    val database = AppDatabase.create(applicationContext)
    val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "guided-capture-authority")
    }
    var completed = false
    try {
        val repository = RoomShootRepository(database)
        val store = androidJournaledPrivateCaptureStore(applicationContext.noBackupFilesDir)
        val clock = SystemCaptureOperationClock()
        val writer = AttachableJournaledStillCaptureWriter()
        val attemptRecovery = CaptureAttemptStartupReconciler(
            repository = repository,
            database = database,
            store = store,
            clock = clock,
        )
        val coordinator = JournaledThreePhotoCaptureCoordinator(
            authority = RoomJournaledCaptureAuthorityAdapter(repository, database),
            files = JournaledCaptureFileAdapter(store),
            writer = writer,
            recovery = JournaledCaptureRecoveryPort(attemptRecovery::reconcile),
            clock = clock,
            executor = executor,
        )
        val resources = GuidedCaptureResourceAuthority {
            try {
                database.close()
            } finally {
                executor.shutdown()
            }
        }
        return RoomGuidedCaptureWorkflow(repository, writer, coordinator, resources).also {
            completed = true
        }
    } finally {
        if (!completed) {
            try {
                database.close()
            } finally {
                executor.shutdown()
            }
        }
    }
}
