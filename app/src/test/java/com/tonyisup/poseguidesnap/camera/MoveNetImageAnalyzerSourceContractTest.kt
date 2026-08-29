package com.tonyisup.poseguidesnap.camera

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveNetImageAnalyzerSourceContractTest {
    @Test
    fun ownedBitmapFactoryHasUnambiguousPrivateConstructionAndFinallyRecycling() {
        val source = productionSource()

        assertTrue(source.contains("class UprightBitmapFrame private constructor("))
        assertTrue(source.contains("fun fromOwnedUprightBitmap("))
        assertTrue(source.contains("ownedBitmap: Bitmap"))
        assertTrue(source.contains("finally"))
        assertTrue(source.contains("ownedBitmap.recycle()"))
        assertFalse(source.contains("TODO("))

        val sourceCallablePublicConstructors = UprightBitmapFrame::class.java.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertEquals(
            emptyList<java.lang.reflect.Constructor<*>>(),
            sourceCallablePublicConstructors,
        )
    }

    @Test
    fun analyzerCadenceDecisionAndSkippedReturnPrecedeBitmapCopyInsideProxyClosingTry() {
        val analyzer = productionSource().substringAfter("class MoveNetImageAnalyzer")
        val analyzeMethod = analyzer
            .substringAfter("override fun analyze(imageProxy: ImageProxy) {")
            .substringBefore("private fun reportFailureSafely")
        val tryIndex = analyzeMethod.indexOf("try {")
        val timestampIndex = analyzeMethod.indexOf(
            "val monotonicTimestampNanos = imageProxy.imageInfo.timestamp",
        )
        val decisionIndex = analyzeMethod.indexOf(
            "cadenceGate.decide(monotonicTimestampNanos)",
        )
        val skippedTooSoonIndex = analyzeMethod.indexOf(
            "AnalysisCadenceGate.Decision.SKIPPED_TOO_SOON",
        )
        val skippedReturnIndex = analyzeMethod.indexOf(
            "AnalysisCadenceGate.Decision.SKIPPED_STALE -> return",
        )
        val copyIndex = analyzeMethod.indexOf("imageProxy.toBitmap()")
        val factoryIndex = analyzeMethod.indexOf("UprightBitmapFrame.fromOwnedUprightBitmap(")
        val submitIndex = analyzeMethod.indexOf("engine.submit(")
        val finallyIndex = analyzeMethod.indexOf("finally")
        val proxyCloseIndex = analyzeMethod.indexOf("imageProxy.close()")

        listOf(
            tryIndex,
            timestampIndex,
            decisionIndex,
            skippedTooSoonIndex,
            skippedReturnIndex,
            copyIndex,
            factoryIndex,
            submitIndex,
            finallyIndex,
            proxyCloseIndex,
        ).forEach { index ->
            assertTrue("Required analyzer lifecycle marker is missing", index >= 0)
        }
        assertTrue(tryIndex < timestampIndex)
        assertTrue(timestampIndex < decisionIndex)
        assertTrue(decisionIndex < skippedTooSoonIndex)
        assertTrue(skippedTooSoonIndex < skippedReturnIndex)
        assertTrue(skippedReturnIndex < copyIndex)
        val cadenceSlice = analyzeMethod.substring(decisionIndex, copyIndex)
        assertFalse(cadenceSlice.contains("reportFailureSafely"))
        assertFalse(cadenceSlice.contains("onFailure"))
        assertTrue(copyIndex < factoryIndex)
        assertTrue(factoryIndex < submitIndex)
        assertTrue(submitIndex < finallyIndex)
        assertTrue(finallyIndex < proxyCloseIndex)
        assertFalse(analyzeMethod.contains("ImageProxy?"))
    }

    @Test
    fun analyzerOwnsFixedDefaultGateWithoutPublicCadenceOverride() {
        val analyzer = productionSource().substringAfter("class MoveNetImageAnalyzer")
        val publicConstructors = MoveNetImageAnalyzer::class.java.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

        assertEquals(1, publicConstructors.size)
        assertEquals(2, publicConstructors.single().parameterCount)
        assertTrue(analyzer.contains("AnalysisCadenceGate()"))
        assertTrue(
            analyzer.contains(
                "internal fun cadenceSnapshot(): AnalysisCadenceGate.Snapshot = cadenceGate.snapshot()",
            ),
        )
        assertFalse(analyzer.contains("minimumInterval"))
        assertFalse(analyzer.contains("MINIMUM_INTERVAL_NANOS"))
    }

    @Test
    fun analyzedResultRetainsNoBitmapOrImageProxy() {
        val source = productionSource()
        val resultSurface = source
            .substringAfter("data class AnalyzedCameraFrame(")
            .substringBefore("class MoveNetFrameEngine")

        assertTrue(resultSurface.contains("val poseObservation: PoseObservation"))
        assertTrue(resultSurface.contains("val coordinateTransform: FrameCoordinateTransform"))
        assertTrue(resultSurface.contains("val sourceMonotonicTimestampNanos: Long"))
        assertFalse(resultSurface.contains("Bitmap"))
        assertFalse(resultSurface.contains("ImageProxy"))
    }

    @Test
    fun adapterAvoidsLoggingFilesystemClockAndCameraLifecycleSurfaces() {
        val source = productionSource()
        listOf(
            "android.util.Log",
            "java.io.",
            "java.nio.file",
            "java.time.",
            "kotlin.io.",
            "System.nanoTime",
            "System.currentTimeMillis",
            "androidx.camera.lifecycle",
            "ProcessCameraProvider",
            "CameraProvider",
            "Preview",
            "ImageCapture",
            "takePicture",
        ).forEach { forbidden ->
            assertFalse("Forbidden adapter surface: $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun cameraCoreDependencyUsesTheExistingCatalogAliasExactlyOnce() {
        val dependencyLine = "implementation(libs.androidx.camera.core)"
        val buildScript = projectRoot().resolve("app/build.gradle.kts").readText()

        assertEquals(1, buildScript.lineSequence().count { it.trim() == dependencyLine })
    }

    private fun productionSource(): String = projectRoot().resolve(SOURCE_PATH).readText()

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private companion object {
        const val SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/camera/MoveNetImageAnalyzer.kt"
    }
}
