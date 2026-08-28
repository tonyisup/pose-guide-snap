package com.tonyisup.poseguidesnap.pose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetPoseDetector
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetResultMapper
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compile-only checkpoint-G acceptance contract for direct LiteRT inference.
 *
 * This instrumentation class is intentionally NOT RUN at this checkpoint: it requires a later
 * authorized real-device acceptance pass. Compiling it proves the packaged 1/0/2 fixture contract
 * remains wired without claiming emulator, device, latency, or pose-estimation accuracy evidence.
 */
@RunWith(AndroidJUnit4::class)
class MoveNetPoseDetectorTest {
    @Test
    fun packagedModelAndFixtureDefineRepeatedOneZeroTwoPersonContract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val instrumentationContext = instrumentation.context

        // Read and verify the exact model bytes from the installed target package before creation.
        val packagedModel = targetContext.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
        assertEquals(MODEL_SIZE_BYTES, packagedModel.size)
        assertEquals(MODEL_SHA_256, sha256(packagedModel))

        // The retained licensed fixture belongs to the instrumentation APK, not the application APK.
        val fixtureBytes = instrumentationContext.assets.open(FIXTURE_ASSET_PATH).use { it.readBytes() }
        assertEquals(FIXTURE_SIZE_BYTES, fixtureBytes.size)
        assertEquals(FIXTURE_SHA_256, sha256(fixtureBytes))
        val fixture = requireNotNull(
            BitmapFactory.decodeByteArray(fixtureBytes, 0, fixtureBytes.size),
        ) { "Packaged upright pose fixture did not decode" }
        assertEquals(FIXTURE_WIDTH, fixture.width)
        assertEquals(FIXTURE_HEIGHT, fixture.height)

        try {
            val detector = MoveNetPoseDetector.create(targetContext)
            try {
                val mapper = MoveNetResultMapper()

                val firstDetection = detector.detectUpright(fixture)
                assertFalse("Detector must not recycle its caller-owned fixture", fixture.isRecycled)
                val first = mapper.map(
                    rawOutput = firstDetection.rawOutput,
                    letterbox = firstDetection.letterbox,
                    monotonicTimestampNanos = 1L,
                )
                assertSingleFixtureObservation(first.detectedPersonCount, first.landmarks.map { it.type })
                assertTrue(
                    first.landmarks.all { landmark ->
                        landmark.x in 0.0..1.0 &&
                            landmark.y in 0.0..1.0 &&
                            landmark.z == 0.0 &&
                            landmark.visibility in 0.0..1.0 &&
                            landmark.presence in 0.0..1.0
                    },
                )

                val repeatedDetection = detector.detectUpright(fixture)
                assertFalse("Repeated detection must preserve caller ownership", fixture.isRecycled)
                val repeated = mapper.map(
                    rawOutput = repeatedDetection.rawOutput,
                    letterbox = repeatedDetection.letterbox,
                    monotonicTimestampNanos = 2L,
                )
                assertSingleFixtureObservation(
                    repeated.detectedPersonCount,
                    repeated.landmarks.map { it.type },
                )
                assertEquals(first.landmarks.map { it.type }, repeated.landmarks.map { it.type })

                val black = Bitmap.createBitmap(fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
                try {
                    black.eraseColor(Color.BLACK)
                    val noPersonDetection = detector.detectUpright(black)
                    assertFalse("Detector must not recycle its caller-owned control", black.isRecycled)
                    val noPerson = mapper.map(
                        rawOutput = noPersonDetection.rawOutput,
                        letterbox = noPersonDetection.letterbox,
                        monotonicTimestampNanos = 3L,
                    )
                    assertEquals(0, noPerson.detectedPersonCount)
                    assertTrue(noPerson.landmarks.isEmpty())
                } finally {
                    black.recycle()
                }

                val sideBySide = Bitmap.createBitmap(
                    fixture.width,
                    fixture.height,
                    Bitmap.Config.ARGB_8888,
                )
                try {
                    val canvas = Canvas(sideBySide)
                    canvas.drawColor(Color.BLACK)
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    val midpoint = sideBySide.width / 2
                    canvas.drawBitmap(
                        fixture,
                        null,
                        Rect(0, 0, midpoint, sideBySide.height),
                        paint,
                    )
                    canvas.drawBitmap(
                        fixture,
                        null,
                        Rect(midpoint, 0, sideBySide.width, sideBySide.height),
                        paint,
                    )

                    val twoPersonDetection = detector.detectUpright(sideBySide)
                    assertFalse(
                        "Detector must not recycle its caller-owned composed control",
                        sideBySide.isRecycled,
                    )
                    val twoPeople = mapper.map(
                        rawOutput = twoPersonDetection.rawOutput,
                        letterbox = twoPersonDetection.letterbox,
                        monotonicTimestampNanos = 4L,
                    )
                    assertEquals(2, twoPeople.detectedPersonCount)
                } finally {
                    sideBySide.recycle()
                }
            } finally {
                detector.close()
                detector.close()
            }
            assertFalse("Closing detector must not recycle its caller-owned fixture", fixture.isRecycled)
        } finally {
            fixture.recycle()
        }
    }

    private fun assertSingleFixtureObservation(
        detectedPersonCount: Int,
        identities: List<PoseLandmark>,
    ) {
        assertEquals(1, detectedPersonCount)
        assertEquals(EXPECTED_COCO_IDENTITIES, identities)
        assertTrue(TORSO_ANCHORS.all(identities::contains))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val MODEL_ASSET_PATH = "movenet_multipose_lightning_float16_v1.tflite"
        const val MODEL_SIZE_BYTES = 9_585_276
        const val MODEL_SHA_256 =
            "d4489f89e6bd6777a8b9a1a16189832131f84ff90d82fae729e670b84d7948dd"

        const val FIXTURE_ASSET_PATH = "pose-fixtures/meditation_pose.png"
        const val FIXTURE_SIZE_BYTES = 1_031_392
        const val FIXTURE_WIDTH = 1024
        const val FIXTURE_HEIGHT = 574
        const val FIXTURE_SHA_256 =
            "e4b26bbe800988cd208a77b23a412109bb2b629e65ead9fa86c4c8a61998eedb"

        val EXPECTED_COCO_IDENTITIES = listOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_EYE,
            PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_EAR,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE,
        )
        val TORSO_ANCHORS = setOf(
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
        )
    }
}
