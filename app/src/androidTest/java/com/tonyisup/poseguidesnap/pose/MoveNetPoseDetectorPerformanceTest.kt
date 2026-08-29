package com.tonyisup.poseguidesnap.pose.movenet

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Generated-black, privacy-safe Pixel latency evidence for direct bundled MoveNet inference. */
@RunWith(AndroidJUnit4::class)
class MoveNetPoseDetectorPerformanceTest {
    @Test
    fun generatedBlackInferenceReportsBoundedLatencyWithoutRetainingFrameEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val detector = MoveNetPoseDetector.create(context)
        val mapper = MoveNetResultMapper()

        try {
            repeat(WARMUP_COUNT) { index ->
                val detection = detector.detectUpright(bitmap)
                assertEquals(
                    0,
                    mapper.map(detection.rawOutput, detection.letterbox, index.toLong()).detectedPersonCount,
                )
            }
            val durationsMillis = ArrayList<Double>(MEASURED_COUNT)
            repeat(MEASURED_COUNT) { index ->
                val started = SystemClock.elapsedRealtimeNanos()
                val detection = detector.detectUpright(bitmap)
                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                assertEquals(
                    0,
                    mapper.map(
                        detection.rawOutput,
                        detection.letterbox,
                        (WARMUP_COUNT + index).toLong(),
                    ).detectedPersonCount,
                )
                durationsMillis += elapsed.toDouble() / 1_000_000.0
            }
            val sorted = durationsMillis.sorted()
            val p50 = percentile(sorted, 0.50)
            val p95 = percentile(sorted, 0.95)
            val maximum = sorted.last()
            assertTrue("Generated-black p95 inference exceeded 250 ms", p95 <= 250.0)
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "MoveNet generated-black latency ms: " +
                            "p50=${format(p50)} p95=${format(p95)} max=${format(maximum)} " +
                            "samples=$MEASURED_COUNT",
                    )
                },
            )
        } finally {
            detector.close()
            bitmap.recycle()
        }
    }


    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        val index = kotlin.math.ceil(sorted.size * fraction).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

    private companion object {
        const val WARMUP_COUNT = 3
        const val MEASURED_COUNT = 25
    }
}
