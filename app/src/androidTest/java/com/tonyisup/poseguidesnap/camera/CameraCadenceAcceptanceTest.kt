package com.tonyisup.poseguidesnap.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel acceptance for privacy-safe aggregate cadence behavior; retains no frame evidence. */
@RunWith(AndroidJUnit4::class)
class CameraCadenceAcceptanceTest {
    @Test
    fun liveCameraSkipsFramesBeforeConversionAtFixedTenHertzBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA),
        )
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val previewReference = java.util.concurrent.atomic.AtomicReference<PreviewView>()
        instrumentation.runOnMainSync {
            previewReference.set(
                PreviewView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
        }
        val preview = requireNotNull(previewReference.get())
        val ready = CountDownLatch(1)
        val resultsNeeded = 20
        val results = CountDownLatch(resultsNeeded)
        val resultCount = AtomicInteger()
        val firstResultNanos = AtomicLong()
        val lastResultNanos = AtomicLong()
        val failed = AtomicBoolean(false)
        val controller = CameraXController.create(
            context = context.applicationContext,
            onFrame = {
                val now = SystemClock.elapsedRealtimeNanos()
                if (resultCount.getAndIncrement() == 0) firstResultNanos.set(now)
                lastResultNanos.set(now)
                results.countDown()
            },
            onState = { state ->
                if (state.status == CameraControllerStatus.READY) ready.countDown()
                if (state.status == CameraControllerStatus.FAILED) {
                    failed.set(true)
                    ready.countDown()
                }
            },
            onFailure = {
                failed.set(true)
                ready.countDown()
            },
        )

        try {
            instrumentation.runOnMainSync {
                activity.setContentView(preview)
                preview.post {
                    val viewPort = preview.viewPort
                    if (viewPort == null) {
                        failed.set(true)
                        ready.countDown()
                    } else {
                        controller.bind(
                            lifecycleOwner = activity,
                            surfaceProvider = preview.surfaceProvider,
                            viewPort = viewPort,
                            targetRotation = preview.display.rotation,
                        )
                    }
                }
            }
            assertTrue("Rear camera did not bind", ready.await(30, TimeUnit.SECONDS))
            assertFalse("Rear camera failed", failed.get())
            assertTrue("Timed out waiting for analyzed results", results.await(30, TimeUnit.SECONDS))

            val snapshot = controller.cadenceSnapshot()
            assertTrue(snapshot.received > snapshot.accepted)
            assertTrue(snapshot.accepted >= resultsNeeded)
            assertTrue(snapshot.skippedTooSoon > 0L)
            assertEquals(
                snapshot.received,
                snapshot.accepted + snapshot.skippedTooSoon + snapshot.skippedStale,
            )
            val elapsedNanos = lastResultNanos.get() - firstResultNanos.get()
            val resultRateHz = if (elapsedNanos > 0L) {
                (resultCount.get() - 1).toDouble() * 1_000_000_000.0 / elapsedNanos.toDouble()
            } else {
                0.0
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "cadence received=${snapshot.received} accepted=${snapshot.accepted} " +
                            "tooSoon=${snapshot.skippedTooSoon} stale=${snapshot.skippedStale} " +
                            "resultRateHz=${"%.2f".format(java.util.Locale.US, resultRateHz)}",
                    )
                },
            )
        } finally {
            instrumentation.runOnMainSync {
                controller.close()
                activity.finish()
            }
        }
    }
}
