package com.tonyisup.poseguidesnap.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tonyisup.poseguidesnap.ui.editor.ShootEditorDestination
import com.tonyisup.poseguidesnap.ui.editor.ShootEditorProductionOwner
import com.tonyisup.poseguidesnap.ui.editor.StartedSessionHandle
import com.tonyisup.poseguidesnap.ui.editor.createShootEditorProductionOwner
import com.tonyisup.poseguidesnap.ui.session.StartedSessionBootstrapViewModel
import com.tonyisup.poseguidesnap.ui.session.StartedSessionDestination
import com.tonyisup.poseguidesnap.ui.session.createStartedSessionBootstrapViewModel
import com.tonyisup.poseguidesnap.ui.shoots.ShootListScreen
import com.tonyisup.poseguidesnap.ui.shoots.ShootListViewModel

private const val LIST_ROUTE = "shoot-list"
private const val EDITOR_ROUTE = "playlist-editor"
private const val STARTED_ROUTE = "started-session"
private const val EDITOR_OWNER_KEY = "shoot-editor-owner"
private const val EDITOR_TARGET_OWNER_KEY = "shoot-editor-target-owner"
private const val STARTED_TARGET_OWNER_KEY = "started-session-target-owner"
private const val STARTED_BOOTSTRAP_OWNER_KEY = "started-session-bootstrap-owner"

internal class EditorNavigationTarget internal constructor(internal val shootId: String) {
    override fun toString(): String = "EditorNavigationTarget(redacted)"
}

internal class StartedNavigationTarget internal constructor(
    internal val handle: StartedSessionHandle,
) {
    override fun toString(): String = "StartedNavigationTarget(redacted)"
}

internal class EditorNavigationTargetOwner(
    internal val target: EditorNavigationTarget?,
) : ViewModel() {
    override fun toString(): String = "EditorNavigationTargetOwner(redacted)"
}

internal class StartedNavigationTargetOwner(
    internal val target: StartedNavigationTarget?,
) : ViewModel() {
    override fun toString(): String = "StartedNavigationTargetOwner(redacted)"
}

internal class NavigationCapabilityRegistry {
    private var editorTarget: EditorNavigationTarget? = null
    private var startedTarget: StartedNavigationTarget? = null

    fun selectEditor(shootId: String): Boolean {
        if (editorTarget != null || !shootId.isSafeOpaqueIdentity()) return false
        editorTarget = EditorNavigationTarget(shootId)
        return true
    }

    fun consumeEditor(): EditorNavigationTarget? = editorTarget.also { editorTarget = null }

    fun selectStartedSession(handle: StartedSessionHandle): Boolean {
        if (startedTarget != null) return false
        startedTarget = StartedNavigationTarget(handle)
        return true
    }

    fun consumeStartedSession(): StartedNavigationTarget? =
        startedTarget.also { startedTarget = null }

    override fun toString(): String = "NavigationCapabilityRegistry(redacted)"
}

private fun String.isSafeOpaqueIdentity(): Boolean =
    isNotEmpty() && this != "." && this != ".." && all { character ->
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character == '_' || character == '-' || character == '.'
    }

@Composable
internal fun AppNavHost(lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val factory: ViewModelProvider.Factory = remember(applicationContext) {
        viewModelFactory {
            initializer {
                createShootListViewModel(applicationContext)
            }
        }
    }
    val shootListViewModel: ShootListViewModel = viewModel(factory = factory)
    val shootListState by shootListViewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val capabilities = remember { NavigationCapabilityRegistry() }

    NavHost(
        navController = navController,
        startDestination = LIST_ROUTE,
    ) {
        composable(LIST_ROUTE) {
            ShootListScreen(
                state = shootListState,
                onRetry = shootListViewModel::retryObservation,
                onCreate = shootListViewModel::createShoot,
                onLoadMore = shootListViewModel::loadMore,
                onOpen = { item ->
                    if (capabilities.selectEditor(item.shootId)) {
                        navController.navigate(EDITOR_ROUTE)
                    }
                },
            )
        }
        composable(EDITOR_ROUTE) { backStackEntry ->
            val targetFactory: ViewModelProvider.Factory = remember(backStackEntry, capabilities) {
                viewModelFactory {
                    initializer {
                        EditorNavigationTargetOwner(capabilities.consumeEditor())
                    }
                }
            }
            val targetOwner = remember(backStackEntry, targetFactory) {
                ViewModelProvider(backStackEntry, targetFactory)[
                    EDITOR_TARGET_OWNER_KEY,
                    EditorNavigationTargetOwner::class.java,
                ]
            }
            val target = targetOwner.target
            if (target == null) {
                FailClosedToList(navController::popBackStack)
            } else {
                val editorFactory: ViewModelProvider.Factory = remember(applicationContext, target) {
                    viewModelFactory {
                        initializer {
                            createShootEditorProductionOwner(applicationContext, target.shootId)
                        }
                    }
                }
                val owner = remember(backStackEntry, editorFactory) {
                    ViewModelProvider(backStackEntry, editorFactory)[
                        EDITOR_OWNER_KEY,
                        ShootEditorProductionOwner::class.java,
                    ]
                }
                ShootEditorDestination(
                    owner = owner,
                    onBack = navController::popBackStack,
                    onNavigateToStartedSession = { handle ->
                        if (capabilities.selectStartedSession(handle)) {
                            navController.navigate(STARTED_ROUTE) {
                                popUpTo(EDITOR_ROUTE) {
                                    inclusive = true
                                }
                            }
                        }
                    },
                )
            }
        }
        composable(STARTED_ROUTE) { backStackEntry ->
            val targetFactory: ViewModelProvider.Factory = remember(backStackEntry, capabilities) {
                viewModelFactory {
                    initializer {
                        StartedNavigationTargetOwner(capabilities.consumeStartedSession())
                    }
                }
            }
            val targetOwner = remember(backStackEntry, targetFactory) {
                ViewModelProvider(backStackEntry, targetFactory)[
                    STARTED_TARGET_OWNER_KEY,
                    StartedNavigationTargetOwner::class.java,
                ]
            }
            val target = targetOwner.target
            if (target == null) {
                FailClosedToList(navController::popBackStack)
            } else {
                val bootstrapFactory: ViewModelProvider.Factory = remember(applicationContext, target) {
                    viewModelFactory {
                        initializer {
                            createStartedSessionBootstrapViewModel(applicationContext, target.handle)
                        }
                    }
                }
                val owner = remember(backStackEntry, bootstrapFactory) {
                    ViewModelProvider(backStackEntry, bootstrapFactory)[
                        STARTED_BOOTSTRAP_OWNER_KEY,
                        StartedSessionBootstrapViewModel::class.java,
                    ]
                }
                StartedSessionDestination(
                    owner = owner,
                    lifecycleOwner = lifecycleOwner,
                    onBack = navController::popBackStack,
                )
            }
        }
    }
}

@Composable
private fun FailClosedToList(popBackStack: () -> Boolean) {
    LaunchedEffect(Unit) {
        popBackStack()
    }
    Text("Returning to shoots")
}
