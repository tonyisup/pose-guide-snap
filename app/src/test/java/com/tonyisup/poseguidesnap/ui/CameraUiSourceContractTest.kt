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
        val permissionGate = appSlice("private fun CameraPermissionGate(", "private fun CameraPermissionScreen(")

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
        val screen = appSlice("private fun CameraPermissionScreen(", "private fun LiveCameraScreen(")

        assertOrdered(
            screen,
            "Pose Guide Snap",
            "Live camera is needed",
            "Button(",
            "onClick = onAllowCamera",
            "heightIn(min = 48.dp)",
            "Allow camera",
        )
        assertTrue("Permission action needs semantics", "contentDescription = \"Permission action: Allow camera\"" in screen)
    }

    @Test
    fun grantedScreenRemembersOneApplicationContextControllerAndClosesItOnDispose() {
        val screen = appSlice("private fun LiveCameraScreen(", "private fun CameraPreview(")

        assertOrdered(
            screen,
            "val applicationContext = context.applicationContext",
            "remember(applicationContext)",
            "CameraXController.create(",
            "context = applicationContext",
            "onFrame = { analyzedFrame ->",
            "hasRecoverableFailure = false",
            "DisposableEffect(controller)",
            "onDispose { controller.close() }",
        )
        assertEquals(1, screen.countOccurrences("CameraXController.create("))
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
    fun overlayDrawsReferenceThenLiveThroughOneSharedFillCenterTransform() {
        val overlay = appSlice("private fun PoseOverlay(", "private fun StatusPanel(")

        assertOrdered(
            overlay,
            "Canvas(",
            "PreviewFillCenterTransform(",
            "frame?.coordinateTransform?.uprightContentPixelSize ?: reference.pixelSize",
            "PixelSize(size.width.toDouble(), size.height.toDouble())",
            "drawSkeleton(",
            "landmarks = reference.observation.landmarks",
            "lineColor = WarmAccent.copy(alpha = 0.38f)",
            "pointRadius = 6.dp.toPx()",
            "landmarks = liveLandmarks",
            "pointColor = WarmOffWhite",
            "pointRadius = 5.dp.toPx()",
        )
        assertEquals(1, overlay.countOccurrences("PreviewFillCenterTransform("))
        assertEquals(2, overlay.countOccurrences("transform = transform"))
        assertTrue(
            "Semantics must distinguish the fixed ghost from live landmarks",
            "Pose overlay: reference ${'$'}{reference.observation.landmarks.size} landmarks; live ${'$'}{liveLandmarks.size} landmarks" in overlay,
        )
        assertFalse("Rear preview overlay must not be mirrored", "scale(-1" in overlay)
        assertFalse("Rear preview overlay must not request mirrored coordinates", "mirroredHorizontally = true" in overlay)
        listOf("visibility >", "presence >", "filter(", "coerceIn(").forEach { forbidden ->
            assertFalse("Overlay must not introduce identity filtering or clamping: $forbidden", forbidden in overlay)
        }
    }

    @Test
    fun compactBundledReferenceCardRendersTheActualDrawableWithFitScaling() {
        val screen = appSlice("private fun LiveCameraScreen(", "private fun CameraPreview(")
        val card = appSlice("private fun BundledReferenceCard(", "private fun StatusPanel(")

        assertTrue("Reference card must occupy a bottom corner", ".align(Alignment.BottomEnd)" in screen)
        assertOrdered(
            card,
            "Image(",
            "painter = painterResource(R.drawable.meditation_pose)",
            "contentDescription = null",
            "contentScale = ContentScale.Fit",
            "Google AI Edge · CC BY 4.0",
        )
        assertTrue("Reference card must remain bounded", ".heightIn(max = 160.dp)" in card)
        assertTrue(
            "Reference card needs explicit semantics",
            "Bundled reference image: ${'$'}{BundledMeditationReference.label}" in card,
        )
    }

    @Test
    fun semanticsIdentifyAllRequiredLiveUiEvidence() {
        val app = appSource()

        listOf(
            "Title: Pose Guide Snap",
            "contentDescription = diagnostics.cameraLabel",
            "contentDescription = diagnostics.personLabel",
            "contentDescription = diagnostics.landmarkLabel",
            "Permission action: Allow camera",
            "Pose overlay:",
            "Bundled reference image:",
            "contentDescription = diagnostics.coverageLabel",
            "contentDescription = diagnostics.angularLabel",
            "contentDescription = diagnostics.positionalLabel",
            "contentDescription = diagnostics.overallLabel",
            "contentDescription = diagnostics.captureLockLabel",
        ).forEach { marker -> assertTrue("Missing semantics marker: $marker", marker in app) }
    }

    @Test
    fun uiContainsNoManualCapturePersistenceLoggingOrNetworkSurface() {
        val production = listOf(
            mainSource(),
            appSource(),
            diagnosticsSource(),
            bundledReferenceSource(),
        ).joinToString("\n")

        assertFalse("Stale inactive-reference copy must be removed", "Reference match: not active" in production)
        assertFalse("Task 10 UI must not expose matcher lock authority", "eligibleForLock" in production)
        assertFalse("Bundled reference must not depend on Android Context", "android.content.Context" in bundledReferenceSource())
        assertFalse("Bundled reference must not run a detector", "MoveNetPoseDetector" in bundledReferenceSource())
        listOf(
            "takePicture(",
            "ImageCapture",
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
    }
}
