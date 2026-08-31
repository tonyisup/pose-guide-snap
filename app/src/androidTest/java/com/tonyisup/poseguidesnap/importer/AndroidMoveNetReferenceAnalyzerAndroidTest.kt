package com.tonyisup.poseguidesnap.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.PublishedReferenceAsset
import com.tonyisup.poseguidesnap.data.ReferenceAssetByteSource
import com.tonyisup.poseguidesnap.data.ReferenceAssetCleanupResult
import com.tonyisup.poseguidesnap.data.ReferenceAssetIdentity
import com.tonyisup.poseguidesnap.data.ReferenceAssetStore
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetPoseDetector
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetResultMapper
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compile-only Task 11B acceptance contract for app-private static-reference analysis.
 *
 * This class intentionally is not run by this task. It preserves the public instrumentation fixture,
 * copies it through the production store, dispatches blocking inference off-main, and removes the
 * exact owned publication afterward without reading any provider URI or device-owned image.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMoveNetReferenceAnalyzerAndroidTest {
    @Test
    fun copiedPublicFixtureAnalyzesWithExactProvenanceAndLeavesNoAssetResidue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtureAssets = instrumentation.context.assets
        val store = ReferenceAssetStore(context.noBackupFilesDir)
        val token = ReferenceImportToken("compile-only-${UUID.randomUUID()}")
        var publication: PublishedReferenceAsset? = null
        var detector: MoveNetPoseDetector? = null
        val executor = Executors.newSingleThreadExecutor()

        try {
            val published = store.publish(
                identity = ReferenceAssetIdentity(token),
                source = ReferenceAssetByteSource {
                    fixtureAssets.open(FIXTURE_ASSET_PATH)
                },
            )
            publication = published
            detector = MoveNetPoseDetector.create(context)
            val analyzer = AndroidMoveNetReferenceAnalyzer(
                noBackupFilesDirectory = context.noBackupFilesDir,
                detectorMapperAdapter = BlockingMoveNetReferenceDetectorMapperAdapter(
                    detector = detector,
                    mapper = MoveNetResultMapper(),
                ),
            )

            val durable = DurableReferenceAnalyzerAsset(
                safeRelativePath = published.safeRelativePath,
                byteCount = published.byteCount,
                sha256 = published.sha256,
            )
            val evidence = executor.submit<ReferenceAnalysisEvidence> {
                analyzer.analyze(durable)
            }.get(ANALYSIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertEquals(1, evidence.detectedPersonCount)
            assertTrue(evidence.landmarks.size >= 13)
            assertEquals(
                "adapter=MoveNetPoseDetector+MoveNetResultMapper;adapterVersion=1",
                evidence.detectorMetadata,
            )
            assertEquals(
                "model=MoveNet MultiPose Lightning float16;modelVersion=1;" +
                    "sha256=d4489f89e6bd6777a8b9a1a16189832131f84ff90d82fae729e670b84d7948dd;" +
                    "runtime=LiteRT;runtimeVersion=1.4.2",
                evidence.modelMetadata,
            )
            assertEquals(
                "preprocessing=ImageDecoder+letterbox;preprocessingVersion=1;" +
                    "source=1024x574;decoded=1024x574;target=256x256",
                evidence.preprocessingMetadata,
            )
            assertEquals(
                "space=normalized-upright-source;origin=top-left;x=right;y=down;z=0;" +
                    "visibility=MoveNet-keypoint-score;presence=MoveNet-keypoint-score;" +
                    "scoreSemantics=visibility-and-presence-are-aliases",
                evidence.coordinateMetadata,
            )
        } finally {
            executor.shutdownNow()
            detector?.close()
            publication?.let { published ->
                val assetFile = context.noBackupFilesDir.resolve(published.safeRelativePath)
                assertEquals(ReferenceAssetCleanupResult.Cleaned, store.cleanup(published))
                assertFalse(assetFile.exists())
            }
        }
    }

    private companion object {
        const val FIXTURE_ASSET_PATH = "pose-fixtures/meditation_pose.png"
        const val ANALYSIS_TIMEOUT_SECONDS = 30L
    }
}
