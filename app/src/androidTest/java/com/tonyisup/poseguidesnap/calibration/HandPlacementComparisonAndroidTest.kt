package com.tonyisup.poseguidesnap.calibration

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit two-position diagnostic. No pose match labels, files, photographs or capture calls. */
@RunWith(AndroidJUnit4::class)
class HandPlacementComparisonAndroidTest {
    @Test
    fun collectOneAuthorizedHandsOnKneesAndLapComparison() {
        val arguments = InstrumentationRegistry.getArguments()
        val request = HandPlacementComparisonRequest.fromRaw(
            arguments.getString("calibrationAuthorization"), arguments.getString("comparisonId"),
        ) // Validate before accessing camera, speech or an activity.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Pixel 6", Build.MODEL)
        assertEquals("oriole", Build.DEVICE)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val probe = CalibrationHandPlacementProbe()
        val ready = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        var screen: CalibrationGuideScreen? = null
        var previewReadiness: CalibrationPreviewReadiness? = null
        var speech: CalibrationSpeechOutput? = null
        var controller: CameraXController? = null
        var completed = false
        try {
            instrumentation.runOnMainSync {
                screen = CalibrationGuideScreen(activity)
                speech = CalibrationSpeechOutput(activity)
            }
            val view = checkNotNull(screen)
            val voice = checkNotNull(speech)
            val camera = CameraXController.create(
                context = context.applicationContext,
                onFrame = { analyzed ->
                    view.update(analyzed.poseObservation, 0)
                    view.status.text = "Hand-position comparison. Follow the spoken instructions."
                    probe.observe(view.feedback, analyzed.sourceMonotonicTimestampNanos / 1_000_000,
                        SystemClock.elapsedRealtime())
                },
                onState = { state ->
                    if (state.status == CameraControllerStatus.READY) ready.countDown()
                    if (state.status == CameraControllerStatus.FAILED) {
                        failed.set(true)
                        ready.countDown()
                    }
                },
                onFailure = { failed.set(true); ready.countDown() },
            )
            controller = camera
            instrumentation.runOnMainSync {
                previewReadiness = CalibrationPreviewReadiness(view.preview) { viewPort, rotation ->
                    camera.bind(activity, view.preview.surfaceProvider, viewPort, rotation)
                }
                activity.setContentView(view.root)
            }
            assertTrue("Rear camera did not become ready", ready.await(30, TimeUnit.SECONDS))
            check(!failed.get()) { "Comparison camera unavailable" }
            voice.initialize()
            val flash = CalibrationWarmupCue(
                setTorchEnabled = { camera.setTorchEnabled(it).get(5, TimeUnit.SECONDS) },
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                sleepMs = SystemClock::sleep,
            )
            CalibrationHandPlacementComparisonProtocol(
                probe = probe,
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                sleepMs = SystemClock::sleep,
                sayAndAwait = voice::sayAndAwait,
                blink = { flash.blink {} },
                ensureHealthy = {
                    check(!failed.get()) { "Comparison camera unavailable" }
                    voice.verifyAvailable()
                },
            ).run()
            completed = true
        } catch (failure: Throwable) {
            runCatching { speech?.sayAndAwait("Comparison stopped. You can relax.") }
            throw failure
        } finally {
            probe.close()
            try {
                instrumentation.runOnMainSync {
                    try {
                        previewReadiness?.close()
                    } finally {
                        try { controller?.close() } finally {
                            try { speech?.close() } finally {
                                try { screen?.close() } finally { activity.finish() }
                            }
                        }
                    }
                }
            } finally {
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "hand comparison id=${request.id} " +
                        "result=${if (completed) "COMPLETE" else "STOPPED"} " +
                        "labels=participant_confirmation_pending wristTarget=same_side_knee")
                })
                probe.summaries().forEach { summary ->
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("stream", "hand comparison $summary")
                    })
                }
            }
        }
    }
}
