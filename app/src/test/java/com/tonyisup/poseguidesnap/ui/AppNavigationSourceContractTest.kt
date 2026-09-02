package com.tonyisup.poseguidesnap.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationSourceContractTest {
    @Test
    fun appStartsAtShootListWithoutCallingCameraGate() {
        val app = source(APP_PATH)
        val appBody = bounded(app, "fun App(lifecycleOwner: LifecycleOwner)", "@Composable\ninternal fun StartedSessionCameraDestination")

        assertTrue("App must render AppNavHost", "AppNavHost(lifecycleOwner = lifecycleOwner)" in appBody)
        assertTrue(
            "App root must paint the declared dark background behind every route",
            "Surface(\n            modifier = Modifier.fillMaxSize(),\n            color = MaterialTheme.colorScheme.background" in appBody,
        )
        assertFalse("App root must not call the camera permission gate", "CameraPermissionGate(" in appBody)
    }

    @Test
    fun startedRouteRetainsBootstrapOwnerBeforeReadyOnlyDestinationCanReachCamera() {
        val app = source(APP_PATH)
        val navigation = source(NAVIGATION_PATH)
        val destination = sourceOrEmpty(STARTED_DESTINATION_PATH)
        val destinationWrapper = bounded(
            destination,
            "internal fun StartedSessionDestination(",
            "@Composable\ninternal fun StartedSessionScreen(",
        )
        val destinationScreen = bounded(
            destination,
            "internal fun StartedSessionScreen(",
            "internal const val STARTED_SESSION_STATUS_TAG",
        )
        val startedRoute = bounded(
            navigation,
            "composable(STARTED_ROUTE)",
            "@Composable\nprivate fun FailClosedToList",
        )

        listOf(
            "internal fun StartedSessionCameraDestination",
            "CameraPermissionGate(lifecycleOwner = lifecycleOwner)",
        ).forEach { marker -> assertTrue("Missing camera destination marker: $marker", marker in app) }
        listOf(
            "private const val LIST_ROUTE",
            "startDestination = LIST_ROUTE",
            "private const val STARTED_BOOTSTRAP_OWNER_KEY",
        ).forEach { marker ->
            assertTrue("Missing constant started-route marker: $marker", marker in navigation)
        }
        listOf(
            "StartedNavigationTargetOwner(capabilities.consumeStartedSession())",
            "STARTED_TARGET_OWNER_KEY",
        ).forEach { marker ->
            assertTrue("Missing retained started-route marker: $marker", marker in startedRoute)
        }

        val nullBranch = bounded(startedRoute, "if (target == null)", "} else {")
        val validBranch = startedRoute.substringAfter("} else {")
        listOf("FailClosedToList", "navController::popBackStack").forEach { marker ->
            assertTrue("null started target must fail closed: $marker", marker in nullBranch)
        }
        listOf(
            "createStartedSessionBootstrapViewModel",
            "bootstrapFactory",
            "StartedSessionBootstrapViewModel",
            "StartedSessionDestination(",
            "StartedSessionCameraDestination",
        ).forEach { forbidden ->
            assertFalse(
                "null started target must not construct bootstrap or camera resources: $forbidden",
                forbidden in nullBranch,
            )
        }

        listOf(
            "remember(applicationContext, target)",
            "viewModelFactory",
            "createStartedSessionBootstrapViewModel(applicationContext, target.handle)",
            "ViewModelProvider(backStackEntry, bootstrapFactory)",
            "STARTED_BOOTSTRAP_OWNER_KEY",
            "StartedSessionBootstrapViewModel::class.java",
            "StartedSessionDestination(",
            "owner = owner",
            "lifecycleOwner = lifecycleOwner",
            "onBack = navController::popBackStack",
        ).forEach { marker ->
            assertTrue("valid started target must retain bootstrap destination: $marker", marker in validBranch)
        }
        assertFalse(
            "AppNavHost must not directly reach the camera destination",
            "StartedSessionCameraDestination" in navigation,
        )

        listOf(
            "owner.state.collectAsStateWithLifecycle()",
            "StartedSessionScreen(",
            "onRetry = owner::retry",
            "StartedSessionCameraDestination(lifecycleOwner)",
        ).forEach { marker ->
            assertTrue("started wrapper must preserve retained camera chain: $marker", marker in destinationWrapper)
        }
        listOf(
            "startedSessionAuthorizesCamera(state)",
            "cameraContent()",
        ).forEach { marker ->
            assertTrue("started screen must preserve Ready-only camera gate: $marker", marker in destinationScreen)
        }
        val destinationBody = destination.substringAfter("internal class StartedSessionStatusPresentation")
        assertTrue(
            "production camera destination must appear exactly once as the injected screen callback",
            destinationBody.windowed("StartedSessionCameraDestination".length)
                .count { it == "StartedSessionCameraDestination" } == 1 &&
                "cameraContent = { StartedSessionCameraDestination(lifecycleOwner) }" in destinationWrapper,
        )

        val listDestination = bounded(navigation, "composable(LIST_ROUTE)", "composable(EDITOR_ROUTE)")
        assertFalse(
            "List destination must not construct camera permission launchers",
            "rememberLauncherForActivityResult" in listDestination,
        )
        assertFalse("List destination must not construct CameraX", "CameraXController" in listDestination)
    }

    @Test
    fun shootListContractsAreUiSafePaginatedLeasedAndRedacted() {
        val contracts = sourceOrEmpty(SHOOT_LIST_CONTRACT_PATH)

        listOf(
            "internal interface ShootListWorkflowPort",
            "internal sealed interface ShootListUiState",
            "internal class ShootListViewModel",
            "fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage>",
            "internal class DeferredCloseAuthority",
            "authority.tryAcquire()",
            "withContext(createDispatcher)",
            "UUID.randomUUID()",
            "System.currentTimeMillis()",
            "private var disposed = false",
            "override fun onCleared()",
            "override fun toString(): String = \"ShootListItem(redacted)\"",
        ).forEach { marker -> assertTrue("Missing shoot-list contract marker: $marker", marker in contracts) }
        assertFalse("No arbitrary global shoot-list cap is allowed", "MAX_SHOOT_LIST_ITEMS" in contracts)
        assertFalse("Compose screen must not import Room types", "RoomShootPreparationRepository" in sourceOrEmpty(SHOOT_LIST_SCREEN_PATH))
    }

    @Test
    fun shootListDeclaresAccessibleLoadingRetryCreateAndActionableRows() {
        val screen = sourceOrEmpty(SHOOT_LIST_SCREEN_PATH)

        listOf(
            "fun ShootListScreen(",
            "Your shoots",
            "Loading shoots",
            "Shoot list unavailable",
            "Retry",
            "label = { Text(\"Shoot name\") }",
            "heightIn(min = 48.dp)",
            "Create",
            "Open shoot",
            "references",
            "Load more shoots",
            "Loading more shoots",
            "Could not load more shoots",
            "onLoadMore",
        ).forEach { marker -> assertTrue("Missing shoot-list accessibility marker: $marker", marker in screen) }
    }

    @Test
    fun shootListDatabaseLifetimeIsOwnedByTheViewModelInitializer() {
        val navigation = source(NAVIGATION_PATH)
        val factory = bounded(
            navigation,
            "val factory: ViewModelProvider.Factory",
            "val shootListViewModel: ShootListViewModel",
        )

        listOf(
            "remember(applicationContext)",
            "initializer {",
            "createShootListViewModel(applicationContext)",
        ).forEach { marker ->
            assertTrue("ViewModel initializer must delegate ownership marker: $marker", marker in factory)
        }
        listOf("AppDatabase.create", "RoomShootPreparationRepository", "RoomShootListWorkflow", "database::close")
            .forEach { forbidden ->
                assertFalse("AppNavHost must not manually own database marker: $forbidden", forbidden in navigation)
            }
        assertFalse(
            "Composition must not dispose a retained ViewModel's database",
            "DisposableEffect(database)" in navigation,
        )
    }

    @Test
    fun shootListUsesLifecycleAwareCollectionAndEdgeToEdgeInsets() {
        val navigation = source(NAVIGATION_PATH)
        val screen = source(SHOOT_LIST_SCREEN_PATH)

        assertTrue(
            "Shoot list state must be collected with lifecycle awareness",
            "shootListViewModel.state.collectAsStateWithLifecycle()" in navigation,
        )
        assertFalse(
            "Shoot list state must not use lifecycle-unaware collection",
            "shootListViewModel.state.collectAsState()" in navigation,
        )
        assertTrue(
            "Shoot list root must account for edge-to-edge status bars",
            ".statusBarsPadding()" in screen,
        )
    }

    @Test
    fun shootListJobsAreAssignedBeforeTheyCanStart() {
        val contracts = source(SHOOT_LIST_CONTRACT_PATH)
        val create = bounded(contracts, "fun createShoot(trimmedName: String)", "override fun onCleared()")

        listOf(
            "viewModelScope.launch(dispatcher, start = CoroutineStart.LAZY)",
            "createJob = job",
            "job.start()",
        ).forEach { marker ->
            assertTrue("Create job must be coherently assigned before start: $marker", marker in create)
        }
        assertTrue(
            "Observation exhaustion must be terminal",
            "private var observationGenerationExhausted = false" in contracts,
        )
    }

    private fun bounded(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end)
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
        const val STARTED_DESTINATION_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/session/StartedSessionDestination.kt"
        const val SHOOT_LIST_SCREEN_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/shoots/ShootListScreen.kt"
        const val SHOOT_LIST_CONTRACT_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/shoots/ShootListViewModel.kt"
    }
}
