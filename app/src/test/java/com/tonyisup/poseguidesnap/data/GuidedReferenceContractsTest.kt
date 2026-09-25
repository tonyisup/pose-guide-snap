package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedReferenceContractsTest {
    @Test
    fun canonicalPersistedLandmarksRoundTripInStableIdentityOrder() {
        val landmarks = listOf(
            landmark(PoseLandmark.RIGHT_WRIST, 0.8),
            landmark(PoseLandmark.NOSE, 0.2),
            landmark(PoseLandmark.LEFT_SHOULDER, 0.4),
        )
        val payload = ReferenceLandmarkPayload.from(landmarks).value

        val decoded = requireNotNull(decodeReferenceLandmarks(payload))

        assertEquals(
            listOf(PoseLandmark.NOSE, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_WRIST),
            decoded.map(Landmark::type),
        )
        assertEquals(payload, ReferenceLandmarkPayload.from(decoded).value)
    }

    @Test
    fun malformedDuplicateNoncanonicalAndNonfiniteLandmarksFailClosed() {
        val canonical = ReferenceLandmarkPayload.from(
            listOf(landmark(PoseLandmark.NOSE, 0.2)),
        ).value
        listOf(
            canonical.removePrefix("v1|"),
            "$canonical;${canonical.removePrefix("v1|")}",
            "v1|NOSE,0.20,0.2,0.0,0.9,0.9",
            "v1|NOSE,NaN,0.2,0.0,0.9,0.9",
            "v1|UNKNOWN,0.2,0.2,0.0,0.9,0.9",
        ).forEach { payload -> assertNull(decodeReferenceLandmarks(payload)) }
    }

    @Test
    fun decodedImageSizeComesFromTheActualInferenceDimensions() {
        val metadata =
            "preprocessing=fit-center-bilinear-zero-pad-rgb-float32-uint8-range-v1;" +
                "preprocessingVersion=1;source=4032x3024;decoded=1920x1440;target=256x256"

        val size = requireNotNull(decodeReferenceImageSize(metadata))

        assertEquals(1920, size.width)
        assertEquals(1440, size.height)
    }

    @Test
    fun missingDuplicateOverflowOrNoncanonicalDimensionsFailClosed() {
        listOf(
            "source=10x10;target=256x256",
            "decoded=10x10;decoded=11x11",
            "decoded=01x10",
            "decoded=8193x10",
            "decoded=10x0",
            "decoded=999999999999999999999x10",
        ).forEach { metadata -> assertNull(decodeReferenceImageSize(metadata)) }
    }

    @Test
    fun reconstructedReferenceSurfaceIsRedacted() {
        val reference = GuidedReferenceSnapshot(
            poseId = "pose-safe",
            label = "Private pose label",
            relativeAssetPath = "reference-assets/assets/${"a".repeat(64)}.asset",
            mirrorAllowed = true,
            landmarks = listOf(landmark(PoseLandmark.NOSE, 0.2)),
            imageSize = com.tonyisup.poseguidesnap.domain.model.PoseImageSize(1920, 1080),
        )

        assertEquals("GuidedReferenceSnapshot(redacted)", reference.toString())
        assertTrue(reference.landmarks.javaClass.name.contains("Unmodifiable"))
    }

    private fun landmark(type: PoseLandmark, x: Double) = Landmark(
        type = type,
        x = x,
        y = 0.3,
        z = 0.0,
        visibility = 0.9,
        presence = 0.9,
    )
}
