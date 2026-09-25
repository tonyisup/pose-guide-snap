package com.tonyisup.poseguidesnap.calibration

import android.content.Intent
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real window/layout lifecycle only. Never constructs a camera controller or opens a camera. */
@RunWith(AndroidJUnit4::class)
class CalibrationPreviewReadinessTest {
    @Test
    fun waitsPastEarlyPostAndBindsOnlyOnceAfterUsableLayout() {
        withPreview { preview ->
            val ready = CountDownLatch(1)
            var calls = 0
            lateinit var gate: CalibrationPreviewReadiness
            onMain {
                gate = CalibrationPreviewReadiness(preview) { _, rotation ->
                    assertTrue(preview.width > 0 && preview.height > 0)
                    assertEquals(preview.display.rotation, rotation)
                    calls++
                    ready.countDown()
                }
            }
            try {
                val earlyPost = CountDownLatch(1)
                onMain {
                    preview.post {
                        assertNull(preview.viewPort) // Reproduces the old binding precondition failure.
                        assertEquals(0, calls)
                        earlyPost.countDown()
                    }
                }
                assertTrue(earlyPost.await(5, TimeUnit.SECONDS))
                onMain { preview.layoutParams = FrameLayout.LayoutParams(320, 180) }
                assertTrue("Preview never became usable", ready.await(5, TimeUnit.SECONDS))
                onMain { preview.layoutParams = FrameLayout.LayoutParams(480, 270) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                onMain { assertEquals(1, calls) }
            } finally { onMain { gate.close() } }
        }
    }

    @Test
    fun cancelledWaitCannotBindWhenLayoutArrivesLater() {
        withPreview { preview ->
            var calls = 0
            onMain {
                val gate = CalibrationPreviewReadiness(preview) { _, _ -> calls++ }
                gate.close()
                preview.layoutParams = FrameLayout.LayoutParams(320, 180)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onMain { assertEquals(0, calls) }
        }
    }

    private fun withPreview(block: (PreviewView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        lateinit var preview: PreviewView
        try {
            onMain {
                preview = PreviewView(activity)
                activity.setContentView(FrameLayout(activity).apply {
                    addView(preview, FrameLayout.LayoutParams(0, 0))
                })
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertTrue(preview.isAttachedToWindow)
                assertNull(preview.viewPort)
            }
            block(preview)
        } finally { onMain { activity.finish() } }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
