package com.tonyisup.poseguidesnap.pose.movenet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor

/** Immutable detector result; neither this type nor its members expose mutable tensor arrays. */
class MoveNetDetection(
    val rawOutput: MoveNetRawOutput,
    val letterbox: MoveNetLetterboxGeometry,
)

/**
 * Blocking direct-LiteRT adapter for the bundled MoveNet MultiPose model.
 *
 * Inference is CPU-only with XNNPACK enabled. The caller must provide an upright [Bitmap], must call
 * [detectUpright] off the UI thread, and retains ownership of that bitmap. Scheduling, keep-latest
 * behavior, camera frames, and timestamps deliberately belong to later integration layers.
 */
class MoveNetPoseDetector private constructor(
    private val resources: DetectorResources,
) : AutoCloseable {
    private var closed = false

    /**
     * Performs one synchronous inference on an upright caller-owned bitmap.
     *
     * This method is synchronized because a LiteRT [Interpreter] is not thread-safe. Invoke it off
     * the UI thread. No clock, executor, frame queue, threshold, person count, or mapping policy is
     * owned by this detector.
     */
    @Synchronized
    fun detectUpright(bitmap: Bitmap): MoveNetDetection {
        check(!closed) { "MoveNetPoseDetector is closed" }
        require(!bitmap.isRecycled) { "bitmap must not be recycled" }

        val letterbox = MoveNetLetterboxGeometry(
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            targetSize = MoveNetArtifactContract.INPUT_SIZE,
        )
        val target = Bitmap.createBitmap(
            MoveNetArtifactContract.INPUT_SIZE,
            MoveNetArtifactContract.INPUT_SIZE,
            Bitmap.Config.ARGB_8888,
        )
        try {
            val canvas = Canvas(target)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(
                bitmap,
                null,
                Rect(
                    letterbox.padLeft,
                    letterbox.padTop,
                    letterbox.padLeft + letterbox.resizedWidth,
                    letterbox.padTop + letterbox.resizedHeight,
                ),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )

            val input = packRgbUint8(target, MoveNetArtifactContract.INPUT_SIZE)
            val output = Array(BATCH_SIZE) {
                Array(PERSON_SLOT_COUNT) { FloatArray(VALUES_PER_PERSON_SLOT) }
            }
            resources.interpreter.run(input, output)

            return MoveNetDetection(
                rawOutput = MoveNetRawOutput(output[0].asIterable()),
                letterbox = letterbox,
            )
        } finally {
            target.recycle()
        }
    }

    /** Closes the interpreter exactly once; repeated calls are no-ops. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        resources.interpreter.close()
    }

    private fun packRgbUint8(bitmap: Bitmap, size: Int): ByteBuffer {
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val input = ByteBuffer.allocateDirect(pixels.size * RGB_CHANNEL_COUNT)
            .order(ByteOrder.nativeOrder())
        pixels.forEach { pixel ->
            input.put(Color.red(pixel).toByte())
            input.put(Color.green(pixel).toByte())
            input.put(Color.blue(pixel).toByte())
        }
        input.rewind()
        return input
    }

    private class DetectorResources private constructor(
        @Suppress("unused")
        private val modelBuffer: ByteBuffer,
        val interpreter: Interpreter,
    ) {
        companion object {
            fun create(
                context: Context,
            ): DetectorResources {
                val modelBytes = context.assets.open(MoveNetArtifactContract.MODEL_ASSET_PATH).use {
                    it.readBytes()
                }
                val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                    .order(ByteOrder.nativeOrder())
                    .apply {
                        put(modelBytes)
                        rewind()
                    }
                val options = Interpreter.Options()
                    .setNumThreads(NUM_THREADS)
                    .setUseXNNPACK(true)

                var interpreter: Interpreter? = null
                try {
                    interpreter = Interpreter(modelBuffer, options)
                    check(interpreter.inputTensorCount == EXPECTED_INPUT_TENSOR_COUNT) {
                        "MoveNet model must expose exactly $EXPECTED_INPUT_TENSOR_COUNT input tensor, " +
                            "but exposed ${interpreter.inputTensorCount}"
                    }
                    check(interpreter.outputTensorCount == EXPECTED_OUTPUT_TENSOR_COUNT) {
                        "MoveNet model must expose exactly $EXPECTED_OUTPUT_TENSOR_COUNT output tensor, " +
                            "but exposed ${interpreter.outputTensorCount}"
                    }

                    val expectedInputShape = intArrayOf(
                        BATCH_SIZE,
                        MoveNetArtifactContract.INPUT_SIZE,
                        MoveNetArtifactContract.INPUT_SIZE,
                        RGB_CHANNEL_COUNT,
                    )
                    interpreter.resizeInput(INPUT_TENSOR_INDEX, expectedInputShape)
                    interpreter.allocateTensors()

                    validateTensor(
                        label = "input",
                        tensor = interpreter.getInputTensor(INPUT_TENSOR_INDEX),
                        expectedType = DataType.UINT8,
                        expectedShape = expectedInputShape,
                    )
                    validateTensor(
                        label = "output",
                        tensor = interpreter.getOutputTensor(OUTPUT_TENSOR_INDEX),
                        expectedType = DataType.FLOAT32,
                        expectedShape = intArrayOf(
                            BATCH_SIZE,
                            PERSON_SLOT_COUNT,
                            VALUES_PER_PERSON_SLOT,
                        ),
                    )
                    return DetectorResources(modelBuffer, interpreter)
                } catch (failure: Throwable) {
                    interpreter?.let { created -> runCatching { created.close() } }
                    throw failure
                }
            }

            private fun validateTensor(
                label: String,
                tensor: Tensor,
                expectedType: DataType,
                expectedShape: IntArray,
            ) {
                check(tensor.dataType() == expectedType) {
                    "MoveNet $label tensor must use $expectedType, but used ${tensor.dataType()}"
                }
                val actualShape = tensor.shape()
                check(actualShape.contentEquals(expectedShape)) {
                    "MoveNet $label tensor must have allocated shape " +
                        "${expectedShape.contentToString()}, but had ${actualShape.contentToString()}"
                }
            }
        }
    }

    companion object {
        fun create(context: Context): MoveNetPoseDetector =
            MoveNetPoseDetector(DetectorResources.create(context))

        private const val NUM_THREADS = 1
        const val BATCH_SIZE = 1
        const val RGB_CHANNEL_COUNT = 3
        const val PERSON_SLOT_COUNT = 6
        const val VALUES_PER_PERSON_SLOT = 56
        const val EXPECTED_INPUT_TENSOR_COUNT = 1
        const val EXPECTED_OUTPUT_TENSOR_COUNT = 1
        const val INPUT_TENSOR_INDEX = 0
        const val OUTPUT_TENSOR_INDEX = 0
    }
}
