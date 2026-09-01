package com.tonyisup.poseguidesnap.ui.editor

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShootEditorDestinationSourceContractTest {
    @Test
    fun validEditorBranchCreatesRetainedOwnerAndRendersDestinationWhileNullFailsClosed() {
        val navigation = source(NAVIGATION_PATH)
        val editorBranch = bounded(
            navigation,
            "composable(EDITOR_ROUTE)",
            "composable(STARTED_ROUTE)",
        )
        val nullBranch = bounded(editorBranch, "if (target == null)", "} else {")
        val validBranch = editorBranch.substringAfter("} else {")

        listOf("FailClosedToList", "navController::popBackStack").forEach { marker ->
            assertTrue("null editor target must fail closed: $marker", marker in nullBranch)
        }
        listOf("createShootEditorProductionOwner", "ViewModelProvider", "ShootEditorDestination(")
            .forEach { forbidden ->
                assertFalse("null editor target must not construct editor resources: $forbidden", forbidden in nullBranch)
            }

        listOf(
            "EditorNavigationTargetOwner(capabilities.consumeEditor())",
            "EDITOR_TARGET_OWNER_KEY",
        ).forEach { marker ->
            assertTrue("editor route must retain navigation authority: $marker", marker in editorBranch)
        }
        listOf(
            "viewModelFactory",
            "createShootEditorProductionOwner(applicationContext, target.shootId)",
            "ViewModelProvider(backStackEntry, editorFactory)",
            "EDITOR_OWNER_KEY",
            "ShootEditorDestination(",
        ).forEach { marker ->
            assertTrue("valid editor target must create/render retained owner: $marker", marker in validBranch)
        }
    }

    @Test
    fun routesAndViewModelIdentityAreConstantAndCarryNoRawArguments() {
        val navigation = source(NAVIGATION_PATH)

        listOf(
            "private const val LIST_ROUTE = \"shoot-list\"",
            "private const val EDITOR_ROUTE = \"playlist-editor\"",
            "private const val STARTED_ROUTE = \"started-session\"",
            "private const val EDITOR_OWNER_KEY",
            "private const val EDITOR_TARGET_OWNER_KEY",
            "private const val STARTED_TARGET_OWNER_KEY",
        ).forEach { marker -> assertTrue("missing constant identity marker: $marker", marker in navigation) }
        listOf(
            "{shootId}",
            "?shootId",
            "navArgument",
            "SavedStateHandle",
            "deepLink",
            "EDITOR_OWNER_KEY +",
        ).forEach { forbidden ->
            assertFalse("navigation identity must not carry a raw argument: $forbidden", forbidden in navigation)
        }
        assertFalse(
            "owner key must not contain the target identity",
            Regex("EDITOR_OWNER_KEY\\s*[+]", RegexOption.MULTILINE).containsMatchIn(navigation),
        )
    }

    @Test
    fun photoPickerUsesImageOnlyRetainedCorrelationAndNoPermissionApi() {
        val destination = sourceOrEmpty(DESTINATION_PATH)
        val owner = source(OWNER_PATH)

        listOf(
            "rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia())",
            "PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)",
            "owner.retainPickerRequest(effect.operationId, effect.launch)",
            "owner.onPhotoPickerCallback(callbackUri)",
            "owner.onPhotoPickerLaunchFailed(effect.operationId)",
            "LaunchedEffect(owner)",
            "repeatOnLifecycle(Lifecycle.State.STARTED)",
        ).forEach { marker -> assertTrue("missing picker marker: $marker", marker in destination) }
        listOf(
            "private var pendingPicker: RetainedPickerRequest?",
            "viewModelScope.launch",
            "pickerCoordinator.handle(uri, pending.launch)",
            "onReferencePickerResult(pending.operationId, result)",
        ).forEach { marker -> assertTrue("missing retained owner marker: $marker", marker in owner) }

        listOf(
            "mutableStateOf<Uri",
            "mutableStateOf<PendingPicker",
            "rememberSaveable",
            "SavedStateHandle",
            "READ_MEDIA",
            "READ_EXTERNAL_STORAGE",
            "requestPermissions",
            "rememberPermissionState",
            "navController",
            "callbackScope",
            "owner.pickerCoordinator.handle",
            "Log.",
        ).forEach { forbidden ->
            assertFalse("picker destination must not retain/request sensitive state: $forbidden", forbidden in destination)
        }
        assertFalse("destination must not declare a Uri field/type", "android.net.Uri" in destination)
    }

    @Test
    fun effectsAreExhaustiveAndStartedNavigationRequiresCapabilityThenInclusivePop() {
        val destination = sourceOrEmpty(DESTINATION_PATH)
        val navigation = source(NAVIGATION_PATH)

        listOf(
            "is ShootEditorEffect.LaunchPhotoPicker",
            "is ShootEditorEffect.NavigateToStartedSession",
        ).forEach { marker -> assertTrue("effect handling must be exhaustive: $marker", marker in destination) }

        val navigationCallback = bounded(
            navigation,
            "onNavigateToStartedSession = { handle ->",
            "},\n                )",
        )
        val select = navigationCallback.indexOf("capabilities.selectStartedSession(handle)")
        val navigate = navigationCallback.indexOf("navController.navigate(STARTED_ROUTE)")
        assertTrue("started capability selection marker missing", select >= 0)
        assertTrue("constant started navigation marker missing", navigate >= 0)
        assertTrue("started route must follow accepted capability selection", select < navigate)
        listOf(
            "popUpTo(EDITOR_ROUTE)",
            "inclusive = true",
        ).forEach { marker -> assertTrue("editor must be inclusively removed: $marker", marker in navigationCallback) }
    }

    @Test
    fun cameraGateStaysOutsideListAndEditorAndOwnerIsNeverCompositionClosed() {
        val app = source(APP_PATH)
        val navigation = source(NAVIGATION_PATH)
        val destination = sourceOrEmpty(DESTINATION_PATH)
        val listBranch = bounded(navigation, "composable(LIST_ROUTE)", "composable(EDITOR_ROUTE)")
        val editorBranch = bounded(navigation, "composable(EDITOR_ROUTE)", "composable(STARTED_ROUTE)")
        val startedDestination = bounded(
            app,
            "internal fun StartedSessionCameraDestination",
            "private fun CameraPermissionGate",
        )

        assertFalse("list must not call camera gate", "CameraPermissionGate" in listBranch)
        assertFalse("editor must not call camera gate", "CameraPermissionGate" in editorBranch)
        assertFalse("editor destination must not construct camera", "CameraPermissionGate" in destination)
        assertTrue(
            "started destination remains sole camera gate caller",
            "CameraPermissionGate(lifecycleOwner = lifecycleOwner)" in startedDestination,
        )
        listOf("DisposableEffect", "owner.close(", "pickerCoordinator.close(")
            .forEach { forbidden ->
                assertFalse("composition must not close retained owner: $forbidden", forbidden in destination + navigation)
            }
    }

    private fun bounded(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex.coerceAtLeast(0) + start.length)
        assertTrue("Missing bounded source start: $start", startIndex >= 0)
        assertTrue("Missing bounded source end: $end", endIndex >= 0)
        assertTrue("Invalid bounded source order", startIndex < endIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun source(path: String): String = projectRoot().resolve(path).readText()

    private fun sourceOrEmpty(path: String): String =
        projectRoot().resolve(path).takeIf(File::isFile)?.readText().orEmpty()

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private companion object {
        const val APP_PATH = "app/src/main/java/com/tonyisup/poseguidesnap/ui/App.kt"
        const val NAVIGATION_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/navigation/AppNavHost.kt"
        const val DESTINATION_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorDestination.kt"
        const val OWNER_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorProductionFactory.kt"
    }
}
