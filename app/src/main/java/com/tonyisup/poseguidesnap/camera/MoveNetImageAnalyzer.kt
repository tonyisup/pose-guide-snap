package com.tonyisup.poseguidesnap.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetPoseDetector
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetResultMapper
import java.util.concurrent.Executor

/**
 * Owns one mutable, upright ARGB bitmap cropped from an owned full CameraX conversion.
 *
 * Construction is intentionally available only through [fromOwnedUprightBitmap]: that factory takes
 * ownership of its source bitmap on entry and always recycles it. This frame then owns only the
 * independent crop bitmap until [close].
 */
class UprightBitmapFrame private constructor(
    internal val bitmap: Bitmap,
    val monotonicTimestampNanos: Long,
    val coordinateTransform: FrameCoordinateTransform,
) : AutoCloseable {
    private var closed = false

    /** Recycles this frame's sole bitmap exactly once. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        bitmap.recycle()
    }

    companion object {
        /**
         * Consumes an owned full upright bitmap and returns an independent, same-size-as-crop copy.
         * The source is recycled on every return or failure path, including validation failures.
         */
        fun fromOwnedUprightBitmap(
            ownedBitmap: Bitmap,
            cropRect: Rect,
            rotationDegrees: Int,
            monotonicTimestampNanos: Long,
        ): UprightBitmapFrame {
            var cropCopy: Bitmap? = null
            try {
                require(!ownedBitmap.isRecycled) { "ownedBitmap must not be recycled" }
                require(rotationDegrees == 0) {
                    "CameraX output rotation must be applied before analysis"
                }
                require(monotonicTimestampNanos >= 0) {
                    "monotonicTimestampNanos must be nonnegative"
                }

                val fullWidth = ownedBitmap.width
                val fullHeight = ownedBitmap.height
                require(!cropRect.isEmpty) { "cropRect must be nonempty" }
                require(
                    cropRect.left >= 0 &&
                        cropRect.top >= 0 &&
                        cropRect.right <= fullWidth &&
                        cropRect.bottom <= fullHeight,
                ) {
                    "cropRect must be within the owned bitmap"
                }

                val copy = Bitmap.createBitmap(
                    cropRect.width(),
                    cropRect.height(),
                    Bitmap.Config.ARGB_8888,
                )
                cropCopy = copy
                Canvas(copy).drawBitmap(
                    ownedBitmap,
                    cropRect,
                    Rect(0, 0, cropRect.width(), cropRect.height()),
                    Paint(0),
                )

                val transform = FrameCoordinateTransform(
                    fullSize = PixelSize(fullWidth.toDouble(), fullHeight.toDouble()),
                    cropRect = PixelRect(
                        left = cropRect.left.toDouble(),
                        top = cropRect.top.toDouble(),
                        right = cropRect.right.toDouble(),
                        bottom = cropRect.bottom.toDouble(),
                    ),
                    rotationDegrees = 0,
                    mirroredHorizontally = false,
                )
                return UprightBitmapFrame(
                    bitmap = copy,
                    monotonicTimestampNanos = monotonicTimestampNanos,
                    coordinateTransform = transform,
                ).also { cropCopy = null }
            } finally {
                try {
                    cropCopy?.recycle()
                } finally {
                    ownedBitmap.recycle()
                }
            }
        }
    }
}

/** Immutable analysis output; no image, bitmap, or mutable model tensor is retained. */
data class AnalyzedCameraFrame(
    val poseObservation: PoseObservation,
    val coordinateTransform: FrameCoordinateTransform,
    val sourceMonotonicTimestampNanos: Long,
) {
    init {
        require(sourceMonotonicTimestampNanos >= 0) {
            "sourceMonotonicTimestampNanos must be nonnegative"
        }
    }
}

/**
 * Bounded blocking MoveNet engine backed by [KeepLatestFrameProcessor].
 *
 * [executor] remains caller-owned and must serialize tasks in submission order. [close] first stops
 * new/pending frame work, then appends detector closure to that same executor so any in-flight drain
 * finishes before the detector is closed. If that terminal task is rejected, the failure is reported
 * and the detector is deliberately left open rather than raced against in-flight inference.
 */
class MoveNetFrameEngine private constructor(
    private val detector: MoveNetPoseDetector,
    private val mapper: MoveNetResultMapper,
    private val executor: Executor,
    onResult: (AnalyzedCameraFrame) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val closeLock = Any()
    private var closed = false
    private val processor = KeepLatestFrameProcessor<UprightBitmapFrame, AnalyzedCameraFrame>(
        executor = executor,
        process = { frame ->
            val detection = detector.detectUpright(frame.bitmap)
            val observation = mapper.map(
                rawOutput = detection.rawOutput,
                letterbox = detection.letterbox,
                monotonicTimestampNanos = frame.monotonicTimestampNanos,
            )
            AnalyzedCameraFrame(
                poseObservation = observation,
                coordinateTransform = frame.coordinateTransform,
                sourceMonotonicTimestampNanos = frame.monotonicTimestampNanos,
            )
        },
        onResult = onResult,
        onFailure = onFailure,
    )

    /** Transfers [frame] ownership and returns the scheduler's exact outcome. */
    fun submit(frame: UprightBitmapFrame): KeepLatestFrameProcessor.SubmissionOutcome =
        processor.submit(frame)

    /** Stops frame work and serializes detector closure without taking ownership of the executor. */
    override fun close() {
        val shouldClose = synchronized(closeLock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!shouldClose) return

        processor.close()
        try {
            executor.execute {
                try {
                    detector.close()
                } catch (failure: Throwable) {
                    reportFailureSafely(failure)
                }
            }
        } catch (failure: Throwable) {
            reportFailureSafely(failure)
        }
    }

    private fun reportFailureSafely(failure: Throwable) {
        try {
            onFailure(failure)
        } catch (_: Throwable) {
            // A failure observer cannot become lifecycle control flow.
        }
    }

    companion object {
        fun create(
            context: Context,
            executor: Executor,
            onResult: (AnalyzedCameraFrame) -> Unit,
            onFailure: (Throwable) -> Unit,
        ): MoveNetFrameEngine = MoveNetFrameEngine(
            detector = MoveNetPoseDetector.create(context),
            mapper = MoveNetResultMapper(),
            executor = executor,
            onResult = onResult,
            onFailure = onFailure,
        )
    }
}

/**
 * CameraX boundary that copies each proxy into an owned upright frame and immediately releases it.
 * Model and executor lifecycles remain owned by [MoveNetFrameEngine].
 */
class MoveNetImageAnalyzer private constructor(
    private val engine: MoveNetFrameEngine,
    private val onFailure: (Throwable) -> Unit,
    private val cadenceGate: AnalysisCadenceGate,
) : ImageAnalysis.Analyzer {
    constructor(
        engine: MoveNetFrameEngine,
        onFailure: (Throwable) -> Unit,
    ) : this(
        engine = engine,
        onFailure = onFailure,
        cadenceGate = AnalysisCadenceGate(),
    )

    internal fun cadenceSnapshot(): AnalysisCadenceGate.Snapshot = cadenceGate.snapshot()

    override fun analyze(imageProxy: ImageProxy) {
        var ownedBitmap: Bitmap? = null
        var frame: UprightBitmapFrame? = null
        try {
            val monotonicTimestampNanos = imageProxy.imageInfo.timestamp
            when (cadenceGate.decide(monotonicTimestampNanos)) {
                AnalysisCadenceGate.Decision.ACCEPTED_FIRST,
                AnalysisCadenceGate.Decision.ACCEPTED_INTERVAL -> Unit
                AnalysisCadenceGate.Decision.SKIPPED_TOO_SOON,
                AnalysisCadenceGate.Decision.SKIPPED_STALE -> return
            }

            require(imageProxy.imageInfo.rotationDegrees == 0) {
                "CameraX must apply output rotation before analysis"
            }

            ownedBitmap = imageProxy.toBitmap()
            val bitmapTransferredToFactory = requireNotNull(ownedBitmap)
            ownedBitmap = null
            frame = UprightBitmapFrame.fromOwnedUprightBitmap(
                ownedBitmap = bitmapTransferredToFactory,
                cropRect = imageProxy.cropRect,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                monotonicTimestampNanos = monotonicTimestampNanos,
            )

            engine.submit(requireNotNull(frame))
            frame = null
        } catch (failure: Throwable) {
            reportFailureSafely(failure)
        } finally {
            frame?.let { unsubmittedFrame ->
                try {
                    unsubmittedFrame.close()
                } catch (failure: Throwable) {
                    reportFailureSafely(failure)
                }
            }
            ownedBitmap?.let { untransferredBitmap ->
                try {
                    untransferredBitmap.recycle()
                } catch (failure: Throwable) {
                    reportFailureSafely(failure)
                }
            }
            try {
                imageProxy.close()
            } catch (failure: Throwable) {
                reportFailureSafely(failure)
            }
        }
    }

    private fun reportFailureSafely(failure: Throwable) {
        try {
            onFailure(failure)
        } catch (_: Throwable) {
            // Analyzer cleanup and CameraX control flow cannot depend on observers.
        }
    }
}
