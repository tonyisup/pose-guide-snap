package com.tonyisup.poseguidesnap.ui

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.R
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetPoseDetector
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetResultMapper
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compile-only Task 10 parity contract. Parent acceptance runs this on the authorized Pixel; this
 * worker only assembles it and makes no emulator/device or runtime-accuracy claim.
 */
@RunWith(AndroidJUnit4::class)
class BundledMeditationReferenceAndroidTest {
    @Test
    fun packagedMainDrawableDetectsTheExactStaticBundledObservation() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val drawableBytes = targetContext.resources.openRawResource(R.drawable.meditation_pose).use {
            it.readBytes()
        }
        assertEquals(EXPECTED_SHA_256, sha256(drawableBytes))
        val fixture = requireNotNull(
            BitmapFactory.decodeByteArray(drawableBytes, 0, drawableBytes.size),
        ) { "Packaged main meditation drawable did not decode" }
        assertEquals(BundledMeditationReference.pixelSize.width.toInt(), fixture.width)
        assertEquals(BundledMeditationReference.pixelSize.height.toInt(), fixture.height)

        try {
            val detector = MoveNetPoseDetector.create(targetContext)
            try {
                val detection = detector.detectUpright(fixture)
                assertFalse("Detector must preserve caller-owned main drawable", fixture.isRecycled)
                val actual = MoveNetResultMapper().map(
                    rawOutput = detection.rawOutput,
                    letterbox = detection.letterbox,
                    monotonicTimestampNanos = 0L,
                )
                val expected = BundledMeditationReference.observation

                assertEquals(1, actual.detectedPersonCount)
                assertEquals(expected.detectedPersonCount, actual.detectedPersonCount)
                assertEquals(expected.monotonicTimestampNanos, actual.monotonicTimestampNanos)
                assertEquals(expected.landmarks.size, actual.landmarks.size)
                expected.landmarks.zip(actual.landmarks).forEach { (expectedLandmark, actualLandmark) ->
                    assertEquals(expectedLandmark.type, actualLandmark.type)
                    assertEquals(expectedLandmark.x, actualLandmark.x, TOLERANCE)
                    assertEquals(expectedLandmark.y, actualLandmark.y, TOLERANCE)
                    assertEquals(expectedLandmark.z, actualLandmark.z, TOLERANCE)
                    assertEquals(expectedLandmark.visibility, actualLandmark.visibility, TOLERANCE)
                    assertEquals(expectedLandmark.presence, actualLandmark.presence, TOLERANCE)
                }
            } finally {
                detector.close()
            }
        } finally {
            fixture.recycle()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TOLERANCE = 1e-6
        const val EXPECTED_SHA_256 =
            "e4b26bbe800988cd208a77b23a412109bb2b629e65ead9fa86c4c8a61998eedb"
    }
}
