package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.pose.movenet.MoveNetArtifactContract
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMoveNetReferenceAnalyzerContractTest {
    @Test
    fun artifactContractPinsExactReviewedModelRuntimeAdapterAndPreprocessing() {
        assertEquals("movenet_multipose_lightning_float16_v1.tflite", MoveNetArtifactContract.MODEL_ASSET_PATH)
        assertEquals("MoveNet MultiPose Lightning float16", MoveNetArtifactContract.MODEL_NAME)
        assertEquals("1", MoveNetArtifactContract.MODEL_VERSION)
        assertEquals(
            "d4489f89e6bd6777a8b9a1a16189832131f84ff90d82fae729e670b84d7948dd",
            MoveNetArtifactContract.MODEL_SHA_256,
        )
        assertEquals("LiteRT", MoveNetArtifactContract.RUNTIME_NAME)
        assertEquals("1.4.2", MoveNetArtifactContract.RUNTIME_VERSION)
        assertEquals("MoveNetPoseDetector+MoveNetResultMapper", MoveNetArtifactContract.ADAPTER_NAME)
        assertEquals("1", MoveNetArtifactContract.ADAPTER_VERSION)
        assertEquals("ImageDecoder+letterbox", MoveNetArtifactContract.PREPROCESSING_NAME)
        assertEquals("1", MoveNetArtifactContract.PREPROCESSING_VERSION)
        assertEquals(256, MoveNetArtifactContract.INPUT_SIZE)
    }

    @Test
    fun pathContractAcceptsOnlyExactConfinedExistingAssetShape() {
        withAssetTree { root, asset ->
            assertEquals(asset, ReferenceAnalysisAssetContract.resolveExisting(root, SAFE_PATH))

            listOf(
                "reference-assets/assets/${"A".repeat(64)}.asset",
                "reference-assets/assets/${"a".repeat(63)}.asset",
                "reference-assets/assets/${"a".repeat(64)}.png",
                "reference-assets/quarantine/${"a".repeat(64)}.asset",
                "reference-assets/assets/../${"a".repeat(64)}.asset",
                "/reference-assets/assets/${"a".repeat(64)}.asset",
            ).forEach { invalid ->
                assertClosedFailure(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_VALIDATION) {
                    ReferenceAnalysisAssetContract.resolveExisting(root, invalid)
                }
            }
        }
    }

    @Test
    fun pathContractRejectsSymlinkTraversalAndSymlinkLeaf() {
        val root = Files.createTempDirectory("reference-analysis-root")
        val outside = Files.createTempDirectory("reference-analysis-outside")
        try {
            val outsideAssets = Files.createDirectories(outside.resolve("assets"))
            Files.write(outsideAssets.resolve(SAFE_FILE_NAME), byteArrayOf(1))
            Files.createSymbolicLink(root.resolve("reference-assets"), outside)
            assertClosedFailure(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_VALIDATION) {
                ReferenceAnalysisAssetContract.resolveExisting(root, SAFE_PATH)
            }
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }

        val leafRoot = Files.createTempDirectory("reference-analysis-leaf-root")
        val leafOutside = Files.createTempFile("reference-analysis-leaf", ".asset")
        try {
            val assets = Files.createDirectories(leafRoot.resolve("reference-assets/assets"))
            Files.createSymbolicLink(assets.resolve(SAFE_FILE_NAME), leafOutside)
            assertClosedFailure(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_VALIDATION) {
                ReferenceAnalysisAssetContract.resolveExisting(leafRoot, SAFE_PATH)
            }
        } finally {
            leafRoot.toFile().deleteRecursively()
            Files.deleteIfExists(leafOutside)
        }
    }

    @Test
    fun decodeBoundsRejectInvalidHeadersAndDownsampleBeforeAllocation() {
        listOf(
            0 to 1,
            1 to 0,
            -1 to 1,
            1 to -1,
        ).forEach { (width, height) ->
            assertClosedFailure(AndroidMoveNetReferenceAnalyzerFailureStage.SOURCE_GEOMETRY) {
                ReferenceDecodeBounds.geometry(width, height)
            }
        }

        val cap = AndroidMoveNetReferenceAnalyzer.MAX_SOURCE_IMAGE_PIXELS
        assertClosedFailure(AndroidMoveNetReferenceAnalyzerFailureStage.SOURCE_GEOMETRY) {
            ReferenceDecodeBounds.geometry(cap.toInt(), 2)
        }

        val landscape = ReferenceDecodeBounds.geometry(8_000, 4_000)
        assertEquals(8_000, landscape.sourceWidth)
        assertEquals(4_000, landscape.sourceHeight)
        assertEquals(AndroidMoveNetReferenceAnalyzer.MAX_DECODED_IMAGE_DIMENSION, landscape.decodedWidth)
        assertEquals(AndroidMoveNetReferenceAnalyzer.MAX_DECODED_IMAGE_DIMENSION / 2, landscape.decodedHeight)

        val portrait = ReferenceDecodeBounds.geometry(2_000, 4_000)
        assertEquals(AndroidMoveNetReferenceAnalyzer.MAX_DECODED_IMAGE_DIMENSION / 2, portrait.decodedWidth)
        assertEquals(AndroidMoveNetReferenceAnalyzer.MAX_DECODED_IMAGE_DIMENSION, portrait.decodedHeight)

        val small = ReferenceDecodeBounds.geometry(1_024, 574)
        assertEquals(1_024, small.decodedWidth)
        assertEquals(574, small.decodedHeight)
    }

    @Test
    fun metadataIsExactDeterministicUriFreeAndContainsNoCalibrationClaim() {
        val metadata = ReferenceAnalysisMetadata.forGeometry(
            ReferenceDecodeGeometry(
                sourceWidth = 1_024,
                sourceHeight = 574,
                decodedWidth = 1_024,
                decodedHeight = 574,
            ),
        )

        assertEquals(
            "adapter=MoveNetPoseDetector+MoveNetResultMapper;adapterVersion=1",
            metadata.detector,
        )
        assertEquals(
            "model=MoveNet MultiPose Lightning float16;modelVersion=1;" +
                "sha256=d4489f89e6bd6777a8b9a1a16189832131f84ff90d82fae729e670b84d7948dd;" +
                "runtime=LiteRT;runtimeVersion=1.4.2",
            metadata.model,
        )
        assertEquals(
            "preprocessing=ImageDecoder+letterbox;preprocessingVersion=1;" +
                "source=1024x574;decoded=1024x574;target=256x256",
            metadata.preprocessing,
        )
        assertEquals(
            "space=normalized-upright-source;origin=top-left;x=right;y=down;z=0;" +
                "visibility=MoveNet-keypoint-score;presence=MoveNet-keypoint-score;" +
                "scoreSemantics=visibility-and-presence-are-aliases",
            metadata.coordinates,
        )
        listOf(metadata.detector, metadata.model, metadata.preprocessing, metadata.coordinates)
            .forEach { value ->
                assertFalse(value.contains("content://", ignoreCase = true))
                assertFalse(value.contains("threshold", ignoreCase = true))
                assertFalse(value.contains("calibrat", ignoreCase = true))
            }
        assertEquals(metadata, ReferenceAnalysisMetadata.forGeometry(ReferenceDecodeBounds.geometry(1_024, 574)))
    }

    @Test
    fun productionSourceEnforcesBlockingOwnershipDecodeAndProvenanceContract() {
        val analyzer = productionSource(ANALYZER_SOURCE_PATH)
        val detector = productionSource(DETECTOR_SOURCE_PATH)

        listOf(
            "class AndroidMoveNetReferenceAnalyzer(",
            "noBackupFilesDirectory: File",
            "detectorMapperAdapter: MoveNetReferenceDetectorMapperAdapter",
            "override fun analyze(asset: DurableReferenceAnalyzerAsset): ReferenceAnalysisEvidence",
            "Looper.myLooper() == Looper.getMainLooper()",
            "ImageDecoder.createSource(ByteBuffer.wrap(privateBytes))",
            "decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE",
            "decoder.isMutableRequired = false",
            "decoder.setTargetSize(geometry.decodedWidth, geometry.decodedHeight)",
            "check(!decoded.isMutable)",
            "monotonicTimestampNanos = STATIC_REFERENCE_TIMESTAMP_NANOS",
            "const val STATIC_REFERENCE_TIMESTAMP_NANOS = 0L",
            "decoded.bitmap.recycle()",
            "ReferenceAnalysisEvidence(",
            "MessageDigest.getInstance(\"SHA-256\")",
            "OsConstants.O_NOFOLLOW",
            "withExclusiveReferenceAssetMutation",
        ).forEach { marker -> assertTrue("Missing analyzer source marker: $marker", marker in analyzer) }

        listOf(
            "content://",
            "Uri",
            "Log.",
            "printStackTrace",
            "detectorMapperAdapter.close(",
            "MoveNetMappingPolicy.DEFAULT_MINIMUM_PERSON_SCORE",
        ).forEach { forbidden ->
            assertFalse("Forbidden analyzer source marker: $forbidden", forbidden in analyzer)
        }

        assertTrue("Detector must use the shared model path", "MoveNetArtifactContract.MODEL_ASSET_PATH" in detector)
        assertTrue("Detector must use the shared input size", "MoveNetArtifactContract.INPUT_SIZE" in detector)
        assertFalse("Detector must not duplicate the model path literal", MoveNetArtifactContract.MODEL_ASSET_PATH in detector)
        assertFalse("Detector must not retain a private INPUT_SIZE literal", "private const val INPUT_SIZE" in detector)
    }

    private fun assertClosedFailure(
        stage: AndroidMoveNetReferenceAnalyzerFailureStage,
        block: () -> Unit,
    ) {
        val failure = assertThrows(AndroidMoveNetReferenceAnalyzerException::class.java, block)
        assertEquals(stage, failure.stage)
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains(File.separator))
        assertEquals("AndroidMoveNetReferenceAnalyzerException(stage=${stage.name})", failure.toString())
    }

    private fun withAssetTree(block: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("reference-analysis-contract")
        try {
            val assets = Files.createDirectories(root.resolve("reference-assets/assets"))
            val asset = Files.write(assets.resolve(SAFE_FILE_NAME), byteArrayOf(1, 2, 3))
            block(root, asset)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun productionSource(relativePath: String): String = projectRoot().resolve(relativePath).readText()

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
    }

    private companion object {
        const val SAFE_FILE_NAME =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.asset"
        const val SAFE_PATH = "reference-assets/assets/$SAFE_FILE_NAME"
        const val ANALYZER_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/importer/AndroidMoveNetReferenceAnalyzer.kt"
        const val DETECTOR_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/pose/movenet/MoveNetPoseDetector.kt"
    }
}
