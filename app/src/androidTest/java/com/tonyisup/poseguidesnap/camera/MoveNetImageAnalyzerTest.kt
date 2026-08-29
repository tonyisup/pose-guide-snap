package com.tonyisup.poseguidesnap.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generated-bitmap checkpoint-C instrumentation contract for direct LiteRT frame analysis.
 *
 * This class ran 7/7 GREEN on the authorized Pixel 6 on 2026-08-28. It exercises no camera or user
 * media; live ImageProxy delivery, alignment, latency, and sustained resource behavior remain
 * separate Task 10 acceptance work.
 */
@RunWith(AndroidJUnit4::class)
class MoveNetImageAnalyzerTest {
    @Test
    fun fullCropMakesIndependentArgb8888PixelExactCopyAndReleasesOwnedSource() {
        val source = bitmap(
            width = 2,
            height = 2,
            pixels = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE),
        )

        val frame = UprightBitmapFrame.fromOwnedUprightBitmap(
            ownedBitmap = source,
            cropRect = Rect(0, 0, 2, 2),
            rotationDegrees = 0,
            monotonicTimestampNanos = 17L,
        )

        assertTrue(source.isRecycled)
        assertNotSame(source, frame.bitmap)
        assertEquals(Bitmap.Config.ARGB_8888, frame.bitmap.config)
        assertTrue(frame.bitmap.isMutable)
        assertEquals(2, frame.bitmap.width)
        assertEquals(2, frame.bitmap.height)
        assertEquals(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE),
            frame.bitmap.readPixels(),
        )
        assertEquals(17L, frame.monotonicTimestampNanos)
        assertEquals(0, frame.coordinateTransform.rotationDegrees)
        assertFalse(frame.coordinateTransform.mirroredHorizontally)

        frame.close()
        assertTrue(frame.bitmap.isRecycled)
        frame.close()
        assertTrue(frame.bitmap.isRecycled)
    }

    @Test
    fun subCropPreservesTopLeftOrientationAndOriginalFullFrameGeometry() {
        val source = bitmap(
            width = 3,
            height = 2,
            pixels = intArrayOf(
                Color.RED,
                Color.GREEN,
                Color.BLUE,
                Color.YELLOW,
                Color.CYAN,
                Color.MAGENTA,
            ),
        )

        val frame = UprightBitmapFrame.fromOwnedUprightBitmap(
            ownedBitmap = source,
            cropRect = Rect(1, 0, 3, 2),
            rotationDegrees = 0,
            monotonicTimestampNanos = 23L,
        )
        try {
            assertTrue(source.isRecycled)
            assertEquals(2, frame.bitmap.width)
            assertEquals(2, frame.bitmap.height)
            assertEquals(
                listOf(Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA),
                frame.bitmap.readPixels(),
            )
            assertEquals(3.0, frame.coordinateTransform.fullSize.width, 0.0)
            assertEquals(2.0, frame.coordinateTransform.fullSize.height, 0.0)
            assertEquals(1.0, frame.coordinateTransform.cropRect.left, 0.0)
            assertEquals(0.0, frame.coordinateTransform.cropRect.top, 0.0)
            assertEquals(3.0, frame.coordinateTransform.cropRect.right, 0.0)
            assertEquals(2.0, frame.coordinateTransform.cropRect.bottom, 0.0)
        } finally {
            frame.close()
        }
    }

    @Test
    fun invalidRotationRejectsAndReleasesOwnedSource() {
        assertRejectedAndRecycled(rotationDegrees = 90)
        assertRejectedAndRecycled(rotationDegrees = 180)
        assertRejectedAndRecycled(rotationDegrees = 270)
        assertRejectedAndRecycled(rotationDegrees = -1)
    }

    @Test
    fun invalidTimestampRejectsAndReleasesOwnedSource() {
        assertRejectedAndRecycled(monotonicTimestampNanos = -1L)
    }

    @Test
    fun emptyOrOutOfBoundsCropRejectsAndReleasesOwnedSource() {
        listOf(
            Rect(0, 0, 0, 1),
            Rect(0, 0, 1, 0),
            Rect(-1, 0, 1, 1),
            Rect(0, -1, 1, 1),
            Rect(0, 0, 3, 1),
            Rect(0, 0, 1, 3),
        ).forEach { crop -> assertRejectedAndRecycled(cropRect = crop) }
    }

    @Test
    fun alreadyRecycledInputRejectsAndRemainsRecycled() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        source.recycle()

        assertIllegalArgument {
            UprightBitmapFrame.fromOwnedUprightBitmap(
                ownedBitmap = source,
                cropRect = Rect(0, 0, 1, 1),
                rotationDegrees = 0,
                monotonicTimestampNanos = 0L,
            )
        }

        // Android exposes only recycled state, not a recycle-call counter; the owned input stays safe.
        assertTrue(source.isRecycled)
    }

    @Test
    fun blackGeneratedFrameFlowsThroughRealEngineAndIsReleased() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = Executors.newSingleThreadExecutor()
        val completion = CountDownLatch(1)
        val result = AtomicReference<AnalyzedCameraFrame>()
        val failure = AtomicReference<Throwable>()
        val engine = MoveNetFrameEngine.create(
            context = context,
            executor = executor,
            onResult = {
                result.set(it)
                completion.countDown()
            },
            onFailure = {
                failure.set(it)
                completion.countDown()
            },
        )
        val source = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val frame = UprightBitmapFrame.fromOwnedUprightBitmap(
            ownedBitmap = source,
            cropRect = Rect(0, 0, 16, 12),
            rotationDegrees = 0,
            monotonicTimestampNanos = 101L,
        )

        try {
            assertEquals(
                KeepLatestFrameProcessor.SubmissionOutcome.STARTED,
                engine.submit(frame),
            )
            assertTrue("Detector callback timed out", completion.await(30, TimeUnit.SECONDS))
            executor.submit { }.get(30, TimeUnit.SECONDS)

            failure.get()?.let { throw AssertionError("Unexpected detector failure", it) }
            val analyzed = requireNotNull(result.get())
            assertEquals(0, analyzed.poseObservation.detectedPersonCount)
            assertTrue(analyzed.poseObservation.landmarks.isEmpty())
            assertEquals(101L, analyzed.poseObservation.monotonicTimestampNanos)
            assertEquals(101L, analyzed.sourceMonotonicTimestampNanos)
            assertSame(frame.coordinateTransform, analyzed.coordinateTransform)
            assertTrue(frame.bitmap.isRecycled)

            engine.close()
            engine.close()
            executor.submit { }.get(30, TimeUnit.SECONDS)

            val rejectedSource = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val rejectedFrame = UprightBitmapFrame.fromOwnedUprightBitmap(
                ownedBitmap = rejectedSource,
                cropRect = Rect(0, 0, 1, 1),
                rotationDegrees = 0,
                monotonicTimestampNanos = 102L,
            )
            assertEquals(
                KeepLatestFrameProcessor.SubmissionOutcome.REJECTED_CLOSED,
                engine.submit(rejectedFrame),
            )
            assertTrue(rejectedFrame.bitmap.isRecycled)
        } finally {
            engine.close()
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun assertRejectedAndRecycled(
        cropRect: Rect = Rect(0, 0, 2, 2),
        rotationDegrees: Int = 0,
        monotonicTimestampNanos: Long = 0L,
    ) {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertIllegalArgument {
            UprightBitmapFrame.fromOwnedUprightBitmap(
                ownedBitmap = source,
                cropRect = cropRect,
                rotationDegrees = rotationDegrees,
                monotonicTimestampNanos = monotonicTimestampNanos,
            )
        }
        assertTrue(source.isRecycled)
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    private fun bitmap(width: Int, height: Int, pixels: IntArray): Bitmap =
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    private fun Bitmap.readPixels(): List<Int> {
        val result = IntArray(width * height)
        getPixels(result, 0, width, 0, 0, width, height)
        return result.toList()
    }
}
