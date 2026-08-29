package com.tonyisup.poseguidesnap.camera

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraXControllerSourceContractTest {
    @Test
    fun controllerSurfaceExposesExplicitLifecycleRotationAndImmutableState() {
        assertEquals(
            listOf("BINDING", "CLOSED", "FAILED", "IDLE", "READY"),
            CameraControllerStatus.entries.map(Enum<*>::name).sorted(),
        )
        assertTrue(CameraController::class.java.isInterface)
        assertEquals(
            setOf("bind", "close", "getState", "updateRotation"),
            CameraController::class.java.methods.map { it.name }.toSet(),
        )
        assertTrue(
            CameraControllerState::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .all { Modifier.isFinal(it.modifiers) },
        )

        val create = CameraXController::class.java.declaredMethods.single {
            it.name == "create" && it.parameterCount == 4
        }
        assertTrue(Modifier.isPublic(create.modifiers))
        assertTrue(Modifier.isStatic(create.modifiers))
        assertEquals(CameraXController::class.java, create.returnType)
    }

    @Test
    fun factoryOwnsTwoPurposeNamedSingleThreadExecutorsAndMainMarshaledCallbacks() {
        val source = productionSource()

        assertEquals(2, source.countOccurrences("Executors.newSingleThreadExecutor"))
        listOf(
            "context.applicationContext",
            "camera-frame-copy",
            "camera-blocking-inference",
            "ContextCompat.getMainExecutor(applicationContext)",
            "MoveNetFrameEngine.create(",
            "MoveNetImageAnalyzer(",
            "mainExecutor.execute",
        ).forEach { marker ->
            assertTrue("Missing factory/lifecycle marker: $marker", marker in source)
        }
        assertTrue("Bounded keep-latest engine must remain the inference path", "MoveNetFrameEngine" in source)
    }

    @Test
    fun bindBuildsRearOnlySharedViewportGroupWithExactAnalysisConfiguration() {
        val bind = productionSource()
            .substringAfter("private fun bindResolved(")
            .substringBefore("private fun handleBindingFailure(")

        assertOrdered(
            bind,
            "if (isClosed()) return",
            "Preview.Builder()",
            "ImageAnalysis.Builder()",
            "setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)",
            "setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)",
            "setOutputImageRotationEnabled(true)",
            "setAnalyzer(frameCopyExecutor, analyzer)",
            "ImageCapture.Builder()",
            "UseCaseGroup.Builder()",
            ".addUseCase(preview)",
            ".addUseCase(analysis)",
            ".addUseCase(imageCapture)",
            ".setViewPort(viewPort)",
            "CameraSelector.DEFAULT_BACK_CAMERA",
            "CameraControllerStatus.READY",
        )
        assertEquals(3, bind.countOccurrences(".setTargetRotation(targetRotation)"))
        assertTrue("Preview surface must be supplied before binding", "preview.setSurfaceProvider(surfaceProvider)" in bind)
    }

    @Test
    fun providerIsAsynchronousAndRebindNeverUnbindsOtherCameraClients() {
        val source = productionSource()
        val bind = source.substringAfter("override fun bind(").substringBefore("override fun updateRotation(")
        val resolved = source.substringAfter("private fun bindResolved(").substringBefore("private fun handleBindingFailure(")

        assertOrdered(
            bind,
            "CameraControllerStatus.BINDING",
            "ProcessCameraProvider.getInstance(applicationContext)",
            "providerFuture.addListener",
            "if (isClosed()) return@addListener",
            "providerFuture.get()",
            "bindResolved(",
        )
        assertOrdered(
            resolved,
            "clearOwnedUseCases(resolvedProvider)",
            "resolvedProvider.bindToLifecycle(",
            "CameraControllerStatus.READY",
        )
        val bindingCall = resolved
            .substringAfter("resolvedProvider.bindToLifecycle(")
            .substringBefore(")")
        assertOrdered(
            bindingCall,
            "lifecycleOwner",
            "CameraSelector.DEFAULT_BACK_CAMERA",
            "useCaseGroup",
        )
        assertFalse("Controller must never disrupt unrelated camera clients", "unbindAll" in source)
    }

    @Test
    fun rotationUpdateValidatesSurfaceConstantsAndMutatesOwnedUseCasesWithoutRebind() {
        val source = productionSource()
        val validation = source.substringAfter("private fun validateRotation(").substringBefore("private fun transition(")
        val update = source.substringAfter("override fun updateRotation(").substringBefore("internal fun requireImageCapture(")

        listOf(
            "Surface.ROTATION_0",
            "Surface.ROTATION_90",
            "Surface.ROTATION_180",
            "Surface.ROTATION_270",
        ).forEach { marker -> assertTrue("Missing valid rotation: $marker", marker in validation) }
        listOf(
            "owned.preview.targetRotation = targetRotation",
            "owned.analysis.targetRotation = targetRotation",
            "owned.imageCapture.targetRotation = targetRotation",
        ).forEach { marker -> assertTrue("Missing rotation update: $marker", marker in update) }
        assertFalse("Rotation update must not rebind", "bindToLifecycle" in update)
    }

    @Test
    fun closeIsNonblockingOrderedAndLateProviderCompletionCannotBind() {
        val source = productionSource()
        val close = source.substringAfter("override fun close() {").substringBefore("private fun clearOwnedUseCases(")
        val cleanup = source.substringAfter("private fun clearOwnedUseCases(").substringBefore("private fun isClosed(")
        val bind = source.substringAfter("override fun bind(").substringBefore("override fun updateRotation(")

        assertOrdered(
            close,
            "CameraControllerStatus.CLOSED",
            "engine.close()",
            "inferenceExecutor.shutdown()",
            "mainExecutor.execute",
            "clearOwnedUseCases(",
        )
        assertOrdered(
            cleanup,
            "owned.analysis.clearAnalyzer()",
            "provider.unbind(",
            "frameCopyExecutor.shutdown()",
        )
        assertOrdered(
            bind,
            "providerFuture.addListener",
            "if (isClosed()) return@addListener",
            "bindResolved(",
        )
        listOf("awaitTermination", ".get() // blocking", "Thread.sleep", "runBlocking").forEach { forbidden ->
            assertFalse("Close must not wait: $forbidden", forbidden in close)
        }
    }

    @Test
    fun controllerContainsNoPolicyIoLoggingClockOrManualCaptureSurface() {
        val source = productionSource()
        listOf(
            "android.util.Log",
            "Log.",
            "java.io.",
            "java.nio.file",
            "kotlin.io.",
            "java.time.",
            "System.nanoTime",
            "System.currentTimeMillis",
            "takePicture(",
            "unbindAll",
            "TODO(",
        ).forEach { forbidden ->
            assertFalse("Forbidden controller surface: $forbidden", forbidden in source)
        }
    }

    @Test
    fun controllerExposesOnlyPrivacySafeCadenceCountersForDeviceAcceptance() {
        val source = productionSource()

        assertTrue(
            "Missing cadence snapshot boundary",
            "internal fun cadenceSnapshot(): AnalysisCadenceGate.Snapshot" in source,
        )
        assertTrue(
            "Controller must delegate to analyzer counters",
            "analyzer.cadenceSnapshot()" in source,
        )
    }

    @Test
    fun cameraXDependenciesUseExistingAliasesExactlyOnce() {
        val buildScript = projectRoot().resolve("app/build.gradle.kts").readText()
        listOf(
            "implementation(libs.androidx.camera.camera2)",
            "implementation(libs.androidx.camera.lifecycle)",
            "implementation(libs.androidx.camera.view)",
        ).forEach { dependencyLine ->
            assertEquals(1, buildScript.lineSequence().count { it.trim() == dependencyLine })
        }
    }

    private fun assertOrdered(source: String, vararg markers: String) {
        val indices = markers.map { marker ->
            source.indexOf(marker).also { index ->
                assertTrue("Required ordered marker is missing: $marker", index >= 0)
            }
        }
        indices.zipWithNext().forEachIndexed { index, (first, second) ->
            assertTrue(
                "Markers are out of order: ${markers[index]} then ${markers[index + 1]}",
                first < second,
            )
        }
    }

    private fun String.countOccurrences(needle: String): Int = windowed(needle.length).count { it == needle }

    private fun productionSource(): String = projectRoot().resolve(SOURCE_PATH).readText()

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private companion object {
        const val SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/camera/CameraXController.kt"
    }
}
