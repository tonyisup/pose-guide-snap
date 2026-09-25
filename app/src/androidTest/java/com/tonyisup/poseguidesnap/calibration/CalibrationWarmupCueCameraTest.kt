package com.tonyisup.poseguidesnap.calibration

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.MainActivity
import com.tonyisup.poseguidesnap.camera.CameraControllerStatus
import com.tonyisup.poseguidesnap.camera.CameraXController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit hardware check of the requested cue; no pose report or photo is retained. */
@RunWith(AndroidJUnit4::class)
class CalibrationWarmupCueCameraTest {
    @Test
    fun rearLightBlinksAtWarmupStartAndIsOffBeforeMeasurement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Pixel 6", Build.MODEL)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA),
        )
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        lateinit var preview: PreviewView
        instrumentation.runOnMainSync {
            preview = PreviewView(activity)
            activity.setContentView(preview)
        }
        val ready = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val controller = CameraXController.create(
            context = context.applicationContext,
            onFrame = {},
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
                preview.post {
                    val viewPort = preview.viewPort
                    if (viewPort == null) {
                        failed.set(true)
                        ready.countDown()
                    } else {
                        controller.bind(activity, preview.surfaceProvider, viewPort, preview.display.rotation)
                    }
                }
            }
            assertTrue("Rear camera did not become ready", ready.await(30, TimeUnit.SECONDS))
            assertFalse("Rear camera failed", failed.get())
            val appliedStates = mutableListOf<Boolean>()
            var startedAt = 0L
            var offAfterMs = 0L
            CalibrationWarmupCue(
                setTorchEnabled = { enabled ->
                    controller.setTorchEnabled(enabled).get(5, TimeUnit.SECONDS)
                    appliedStates += enabled
                    if (!enabled) offAfterMs = SystemClock.elapsedRealtime() - startedAt
                },
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                sleepMs = SystemClock::sleep,
            ).run(15_000) { startedAt = it }
            val warmupElapsedMs = SystemClock.elapsedRealtime() - startedAt

            assertEquals(listOf(true, false), appliedStates)
            assertTrue(offAfterMs >= CalibrationWarmupCue.BLINK_DURATION_MS)
            assertTrue("Light must be off before measurement", offAfterMs < 15_000)
            assertTrue("Settling period must last at least 15 seconds", warmupElapsedMs >= 15_000)
            assertFalse("Rear camera failed during cue", failed.get())
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "flash-cue on=1 off=1 offAfterMs=$offAfterMs warmupElapsedMs=$warmupElapsedMs",
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
