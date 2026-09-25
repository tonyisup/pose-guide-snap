package com.tonyisup.poseguidesnap.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiSourceContractTest {
    @Test
    fun mainActivityPassesItselfAsLifecycleOwnerWithoutAnActivityCast() {
        val main = mainSource().substringAfter("class MainActivity")

        assertOrdered(
            main,
            ": ComponentActivity()",
            "setContent",
            "App(lifecycleOwner = this@MainActivity)",
        )
        assertFalse("Compose UI must not cast a context to an activity", " as LifecycleOwner" in appSource())
        assertFalse("Compose UI must not cast a context to an activity", " as ComponentActivity" in appSource())
    }

    @Test
    fun permissionGateUsesRememberedRequestPermissionAndResumeRecheck() {
        val permissionGate = appSlice("private fun CameraPermissionGate(", "internal fun CameraPermissionScreen(")

        assertOrdered(
            permissionGate,
            "mutableStateOf(isCameraPermissionGranted(context))",
            "rememberLauncherForActivityResult(",
            "ActivityResultContracts.RequestPermission()",
            "LifecycleEventObserver",
            "Lifecycle.Event.ON_RESUME",
            "ContextCompat.checkSelfPermission(",
            "lifecycleOwner.lifecycle.addObserver",
            "lifecycleOwner.lifecycle.removeObserver",
        )
        assertTrue("Permission must be requested only through a labeled callback", "onAllowCamera = {" in permissionGate)
        assertTrue("Permission callback must launch CAMERA only", "cameraPermissionLauncher.launch(Manifest.permission.CAMERA)" in permissionGate)
        assertEquals(1, appSource().countOccurrences("cameraPermissionLauncher.launch("))
    }

    @Test
    fun deniedPermissionExplainsCameraNeedAndOffersReadableRetryAction() {
        val screen = appSlice("internal fun CameraPermissionScreen(", "private fun LiveCameraScreen(")

        assertOrdered(
            screen,
            "Pose Guide Snap",
            "Live camera is needed",
            "Button(",
            "onClick = if (recovery == CameraPermissionRecovery.SETTINGS) onOpenSettings else onAllowCamera",
            "heightIn(min = 48.dp)",
            "recovery.actionLabel",
        )
        assertTrue("Permission action needs semantics", "Permission action:" in screen)
    }

    @Test
    fun grantedScreenOwnsOneControllerAndAttachesOneRouteCaptureWriter() {
        val screen = appSlice("private fun LiveCameraScreen(", "private fun CameraPreview(")

        assertOrdered(
            screen,
            "val applicationContext = context.applicationContext",
            "remember(applicationContext)",
            "CameraXController.create(",
            "context = applicationContext",
            "onFrame = { analyzedFrame ->",
            "hasRecoverableFailure = false",
            "val captureWriter = remember(controller, mainExecutor)",
            "CameraXJournaledStillCaptureWriter(",
            "DisposableEffect(controller, owner, captureWriter)",
            "owner.attachWriter(captureWriter)",
            "onDispose {",
            "owner.detachWriter(captureWriter)",
            "controller.close()",
        )
        assertEquals(1, screen.countOccurrences("CameraXController.create("))
        assertEquals(1, screen.countOccurrences("CameraXJournaledStillCaptureWriter("))
        assertFalse("Controller must not retain an Activity context", "context = context" in screen)
    }

    @Test
    fun previewUsesExactFillCenterViewportAndPositiveSizeBindKey() {
        val preview = appSlice("private fun CameraPreview(", "private fun PoseOverlay(")

        assertOrdered(
            preview,
            "PreviewView(context).apply",
            "scaleType = PreviewView.ScaleType.FILL_CENTER",
            "onSizeChanged",
            "if (previewSize.width <= 0 || previewSize.height <= 0)",
            "PreviewBindKey(",
            "width = previewSize.width",
            "height = previewSize.height",
            "rotation = displayRotation",
            "if (lastBindKey == bindKey)",
            "ViewPort.Builder(",
            "Rational(bindKey.width, bindKey.height)",
            "bindKey.rotation",
            ".setScaleType(ViewPort.FILL_CENTER)",
            ".build()",
            "controller.bind(",
            "lifecycleOwner = lifecycleOwner",
            "surfaceProvider = preview.surfaceProvider",
            "viewPort = viewPort",
            "targetRotation = bindKey.rotation",
            "lastBindKey = bindKey",
        )
        assertTrue("Preview and overlay must share exact bounds", preview.countOccurrences(".matchParentSize()") >= 2)
    }

    @Test
    fun controllerStatusAndGenericFailureAreRenderedTruthfully() {
        val screen = appSlice("private fun LiveCameraScreen(", "private fun CameraPreview(")
        val panel = appSlice("private fun StatusPanel(", "private fun DiagnosticText(")

        listOf("IDLE", "BINDING", "READY", "FAILED", "CLOSED").forEach { state ->
            assertTrue("Missing camera state $state", "CameraControllerStatus.$state" in appSource() || state in diagnosticsSource())
        }
        assertOrdered(
            screen,
            "onFailure = {",
            "hasRecoverableFailure = true",
            "LiveCameraDiagnostics.from(",
            "reference = guidedState.reference",
            "hasRecoverableFailure = hasRecoverableFailure",
        )
        assertOrdered(
            panel,
            "diagnostics.cameraLabel",
            "diagnostics.personLabel",
            "diagnostics.landmarkLabel",
            "diagnostics.referenceLabel",
            "diagnostics.framingLabel",
            "diagnostics.coverageLabel",
            "diagnostics.angularLabel",
            "diagnostics.positionalLabel",
            "diagnostics.overallLabel",
            "diagnostics.mirrorLabel",
            "diagnostics.captureLockLabel",
        )
        assertTrue("Recovery action must be shown when diagnostics provides it", "diagnostics.recoverableActionText?.let" in panel)
    }

    @Test
    fun overlayDrawsPersistedReferenceThenLiveThroughTheirOwnFillCenterTransforms() {
        val overlay = appSlice("private fun PoseOverlay(", "private fun StatusPanel(")

        assertOrdered(
            overlay,
            "val referenceLandmarks = reference?.landmarks.orEmpty()",
            "Canvas(",
            "val previewSize = PixelSize(size.width.toDouble(), size.height.toDouble())",
            "reference?.let",
            "landmarks = referenceLandmarks",
            "PixelSize(it.imageSize.width.toDouble(), it.imageSize.height.toDouble())",
            "lineColor = WarmAccent.copy(alpha = 0.38f)",
            "pointRadius = 6.dp.toPx()",
            "frame?.let",
            "landmarks = liveLandmarks",
            "it.coordinateTransform.uprightContentPixelSize",
            "pointColor = WarmOffWhite",
            "pointRadius = 5.dp.toPx()",
        )
        assertEquals(2, overlay.countOccurrences("PreviewFillCenterTransform("))
        assertTrue(
            "Semantics must distinguish the selected ghost from live landmarks",
            "Pose overlay: reference ${'$'}{referenceLandmarks.size} landmarks; live ${'$'}{liveLandmarks.size} landmarks" in overlay,
        )
        assertFalse("Rear preview overlay must not be mirrored", "scale(-1" in overlay)
        assertFalse("Rear preview overlay must not request mirrored coordinates", "mirroredHorizontally = true" in overlay)
        listOf("visibility >", "presence >", "filter(", "coerceIn(").forEach { forbidden ->
            assertFalse("Overlay must not introduce identity filtering or clamping: $forbidden", forbidden in overlay)
        }
    }

    @Test
    fun manualControlsRenderTheCurrentPoseWithAccessibleCaptureAndStopActions() {
        val screen = appSlice("private fun LiveCameraScreen(", "private fun CameraPreview(")
        val controls = guidedCameraScreenSource()

        assertTrue("Guided controls must occupy the bottom of the preview", ".align(Alignment.BottomCenter)" in screen)
        assertOrdered(
            screen,
            "GuidedCameraControls(",
            "state = guidedState",
            "onCapture = owner::manualCapture",
            "onStop = owner::stop",
        )
        listOf(
            "Guided capture controls, pose",
            "Current reference:",
            "Capture status:",
            "Capture three photos",
            "Stop guided session",
            "heightIn(min = 52.dp)",
        ).forEach { marker -> assertTrue("Missing guided control marker: $marker", marker in controls) }
    }

    @Test
    fun semanticsIdentifyAllRequiredLiveUiEvidence() {
        val ui = appSource() + guidedCameraScreenSource()

        listOf(
            "Title: Pose Guide Snap",
            "contentDescription = diagnostics.cameraLabel",
            "contentDescription = diagnostics.personLabel",
            "contentDescription = diagnostics.landmarkLabel",
            "Permission action:",
            "Pose overlay:",
            "Guided capture controls, pose",
            "Current reference:",
            "Capture status:",
            "Capture three photos",
            "Stop guided session",
            "contentDescription = diagnostics.coverageLabel",
            "contentDescription = diagnostics.angularLabel",
            "contentDescription = diagnostics.positionalLabel",
            "contentDescription = diagnostics.overallLabel",
            "contentDescription = diagnostics.captureLockLabel",
        ).forEach { marker -> assertTrue("Missing semantics marker: $marker", marker in ui) }
    }

    @Test
    fun composableUiKeepsPersistenceLoggingAndNetworkAuthorityOutOfComposition() {
        val production = listOf(
            mainSource(),
            appSource(),
            diagnosticsSource(),
            bundledReferenceSource(),
            guidedCameraScreenSource(),
        ).joinToString("\n")

        assertFalse("Stale inactive-reference copy must be removed", "Reference match: not active" in production)
        assertFalse("Task 10 UI must not expose matcher lock authority", "eligibleForLock" in production)
        assertFalse("Bundled reference must not depend on Android Context", "android.content.Context" in bundledReferenceSource())
        assertFalse("Bundled reference must not run a detector", "MoveNetPoseDetector" in bundledReferenceSource())
        listOf(
            "takePicture(",
            "CaptureSession",
            "ShootReducer",
            "java.io.",
            "java.nio.file",
            "kotlin.io.",
            "android.util.Log",
            "Log.",
            "System.nanoTime",
            "System.currentTimeMillis",
            "INTERNET",
            "ACCESS_NETWORK_STATE",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "RECORD_AUDIO",
            "ACCESS_FINE_LOCATION",
            "ACCESS_COARSE_LOCATION",
        ).forEach { forbidden ->
            assertFalse("Forbidden manual-slice UI surface: $forbidden", forbidden in production)
        }
    }

    private fun appSlice(start: String, end: String): String {
        val source = appSource()
        assertTrue("Missing bounded slice start: $start", start in source)
        assertTrue("Missing bounded slice end: $end", end in source)
        return source.substringAfter(start).substringBefore(end)
    }

    private fun assertOrdered(source: String, vararg markers: String) {
        markers.forEach { marker ->
            assertTrue("Required ordered marker is missing: $marker", marker in source)
        }
        val indices = markers.map(source::indexOf)
        indices.zipWithNext().forEachIndexed { index, (first, second) ->
            assertTrue(
                "Markers are out of order: ${markers[index]} then ${markers[index + 1]}",
                first < second,
            )
        }
    }

    private fun String.countOccurrences(needle: String): Int = windowed(needle.length).count { it == needle }

    private fun mainSource(): String = projectRoot().resolve(MAIN_SOURCE_PATH).readText()
    private fun appSource(): String = projectRoot().resolve(APP_SOURCE_PATH).readText()
    private fun diagnosticsSource(): String = projectRoot().resolve(DIAGNOSTICS_SOURCE_PATH).readText()
    private fun bundledReferenceSource(): String =
        projectRoot().resolve(BUNDLED_REFERENCE_SOURCE_PATH).readText()
    private fun guidedCameraScreenSource(): String =
        projectRoot().resolve(GUIDED_CAMERA_SCREEN_SOURCE_PATH).readText()

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private companion object {
        const val MAIN_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/MainActivity.kt"
        const val APP_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/App.kt"
        const val DIAGNOSTICS_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/LiveCameraDiagnostics.kt"
        const val BUNDLED_REFERENCE_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/BundledMeditationReference.kt"
        const val GUIDED_CAMERA_SCREEN_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/camera/GuidedCameraScreen.kt"
    }
}
