package com.tonyisup.poseguidesnap.camera

import android.content.Context
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Rear-only CameraX lifecycle owner.
 *
 * The camera-frame-copy executor runs CameraX proxy copying and always releases each proxy. The
 * camera-blocking-inference executor serializes MoveNet through its bounded keep-latest engine.
 * Neither executor is shared with UI work, and neither permits an unbounded frame collection.
 */
class CameraXController private constructor(
    context: Context,
    private val onFrame: (AnalyzedCameraFrame) -> Unit,
    private val onState: (CameraControllerState) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : CameraController {
    private data class OwnedUseCases(
        val preview: Preview,
        val analysis: ImageAnalysis,
        val imageCapture: ImageCapture,
    )

    private val lock = Any()
    private val applicationContext = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(applicationContext)
    private val frameCopyExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "camera-frame-copy")
    }
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "camera-blocking-inference")
    }
    private val engine = MoveNetFrameEngine.create(
        context = applicationContext,
        executor = inferenceExecutor,
        onResult = ::dispatchFrame,
        onFailure = ::reportFailure,
    )
    private val analyzer = MoveNetImageAnalyzer(
        engine = engine,
        onFailure = ::reportFailure,
    )

    @Volatile
    private var currentState = CameraControllerState(CameraControllerStatus.IDLE)
    private var closed = false
    private var bindingRequestId = 0L
    private var requestedRotation = Surface.ROTATION_0
    private var provider: ProcessCameraProvider? = null
    private var ownedUseCases: OwnedUseCases? = null

    override val state: CameraControllerState
        get() = currentState

    override fun bind(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        viewPort: ViewPort,
        targetRotation: Int,
    ) {
        validateRotation(targetRotation)
        val requestId = synchronized(lock) {
            check(!closed) { "Camera controller is closed" }
            requestedRotation = targetRotation
            bindingRequestId += 1
            bindingRequestId
        }
        transition(CameraControllerStatus.BINDING)

        val providerFuture = try {
            ProcessCameraProvider.getInstance(applicationContext)
        } catch (failure: Throwable) {
            handleBindingFailure(requestId, failure)
            return
        }

        try {
            providerFuture.addListener({
                if (isClosed()) return@addListener
                if (!isCurrentBinding(requestId)) return@addListener
                val resolvedProvider = try {
                    providerFuture.get()
                } catch (failure: Throwable) {
                    handleBindingFailure(requestId, failure)
                    return@addListener
                }
                bindResolved(
                    requestId = requestId,
                    resolvedProvider = resolvedProvider,
                    lifecycleOwner = lifecycleOwner,
                    surfaceProvider = surfaceProvider,
                    viewPort = viewPort,
                    targetRotation = currentRequestedRotation(),
                )
            }, mainExecutor)
        } catch (failure: Throwable) {
            handleBindingFailure(requestId, failure)
        }
    }

    override fun updateRotation(targetRotation: Int) {
        validateRotation(targetRotation)
        synchronized(lock) {
            check(!closed) { "Camera controller is closed" }
            requestedRotation = targetRotation
        }
        dispatchOnMain {
            if (isClosed()) return@dispatchOnMain
            val owned = synchronized(lock) { ownedUseCases } ?: return@dispatchOnMain
            owned.preview.targetRotation = targetRotation
            owned.analysis.targetRotation = targetRotation
            owned.imageCapture.targetRotation = targetRotation
        }
    }

    internal fun requireImageCapture(): ImageCapture = synchronized(lock) {
        check(!closed && currentState.status == CameraControllerStatus.READY) {
            "Image capture is unavailable before the controller is ready"
        }
        requireNotNull(ownedUseCases).imageCapture
    }

    /** Privacy-safe aggregate cadence counters for device acceptance; retains no frame evidence. */
    internal fun cadenceSnapshot(): AnalysisCadenceGate.Snapshot = analyzer.cadenceSnapshot()

    override fun close() {
        val closedState = synchronized(lock) {
            if (closed) return
            closed = true
            bindingRequestId += 1
            CameraControllerState(CameraControllerStatus.CLOSED).also { currentState = it }
        }
        dispatchState(closedState)

        engine.close()
        inferenceExecutor.shutdown()
        mainExecutor.execute {
            clearOwnedUseCases(currentProvider(), shutdownFrameExecutor = true)
        }
    }

    private fun clearOwnedUseCases(provider: ProcessCameraProvider) =
        clearOwnedUseCases(provider, shutdownFrameExecutor = false)

    private fun clearOwnedUseCases(
        provider: ProcessCameraProvider?,
        shutdownFrameExecutor: Boolean,
    ) {
        val owned = synchronized(lock) {
            ownedUseCases.also { ownedUseCases = null }
        }
        if (owned != null) {
            owned.analysis.clearAnalyzer()
            if (provider != null) {
                provider.unbind(owned.preview, owned.analysis, owned.imageCapture)
            }
        }
        if (shutdownFrameExecutor) {
            frameCopyExecutor.shutdown()
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun isCurrentBinding(requestId: Long): Boolean = synchronized(lock) {
        !closed && bindingRequestId == requestId
    }

    private fun currentRequestedRotation(): Int = synchronized(lock) { requestedRotation }

    private fun currentProvider(): ProcessCameraProvider? = synchronized(lock) { provider }

    private fun bindResolved(
        requestId: Long,
        resolvedProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        viewPort: ViewPort,
        targetRotation: Int,
    ) {
        if (isClosed()) return
        if (!isCurrentBinding(requestId)) return

        clearOwnedUseCases(resolvedProvider)

        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .build()
        analysis.setAnalyzer(frameCopyExecutor, analyzer)
        val imageCapture = ImageCapture.Builder()
            .setTargetRotation(targetRotation)
            .build()
        preview.setSurfaceProvider(surfaceProvider)

        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(analysis)
            .addUseCase(imageCapture)
            .setViewPort(viewPort)
            .build()

        if (isClosed() || !isCurrentBinding(requestId)) {
            analysis.clearAnalyzer()
            return
        }

        try {
            resolvedProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                useCaseGroup,
            )
        } catch (failure: Throwable) {
            analysis.clearAnalyzer()
            handleBindingFailure(requestId, failure)
            return
        }

        val retained = synchronized(lock) {
            if (closed || bindingRequestId != requestId) {
                false
            } else {
                provider = resolvedProvider
                ownedUseCases = OwnedUseCases(preview, analysis, imageCapture)
                true
            }
        }
        if (!retained) {
            analysis.clearAnalyzer()
            resolvedProvider.unbind(preview, analysis, imageCapture)
            return
        }
        transition(CameraControllerStatus.READY)
    }

    private fun handleBindingFailure(requestId: Long, failure: Throwable) {
        if (!isCurrentBinding(requestId)) return
        transition(CameraControllerStatus.FAILED)
        reportFailure(failure)
    }

    private fun validateRotation(targetRotation: Int) {
        require(
            targetRotation == Surface.ROTATION_0 ||
                targetRotation == Surface.ROTATION_90 ||
                targetRotation == Surface.ROTATION_180 ||
                targetRotation == Surface.ROTATION_270,
        ) { "targetRotation must be a Surface rotation constant" }
    }

    private fun transition(status: CameraControllerStatus) {
        val nextState = synchronized(lock) {
            if (closed) return
            CameraControllerState(status).also { currentState = it }
        }
        dispatchState(nextState)
    }

    private fun dispatchFrame(frame: AnalyzedCameraFrame) {
        dispatchOnMain {
            if (isClosed()) return@dispatchOnMain
            invokeSafely { onFrame(frame) }
        }
    }

    private fun dispatchState(state: CameraControllerState) {
        dispatchOnMain { invokeSafely { onState(state) } }
    }

    private fun reportFailure(failure: Throwable) {
        dispatchOnMain { invokeSafely { onFailure(failure) } }
    }

    private fun dispatchOnMain(callback: () -> Unit) {
        try {
            mainExecutor.execute(callback)
        } catch (_: Throwable) {
            // Callback dispatch cannot become camera lifecycle control flow.
        }
    }

    private fun invokeSafely(callback: () -> Unit) {
        try {
            callback()
        } catch (_: Throwable) {
            // Injected observers cannot become camera lifecycle control flow.
        }
    }

    companion object {
        @JvmStatic
        fun create(
            context: Context,
            onFrame: (AnalyzedCameraFrame) -> Unit,
            onState: (CameraControllerState) -> Unit,
            onFailure: (Throwable) -> Unit,
        ): CameraXController = CameraXController(
            context = context,
            onFrame = onFrame,
            onState = onState,
            onFailure = onFailure,
        )
    }
}
