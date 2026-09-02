package com.tonyisup.poseguidesnap.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect

@Composable
internal fun ShootEditorDestination(
    owner: ShootEditorProductionOwner,
    onBack: () -> Unit,
    onNavigateToStartedSession: (StartedSessionHandle) -> Unit,
) {
    val state by owner.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { callbackUri ->
        owner.onPhotoPickerCallback(callbackUri)
    }

    LaunchedEffect(owner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            owner.effects.collect { effect ->
                when (effect) {
                    is ShootEditorEffect.LaunchPhotoPicker -> {
                        if (owner.retainPickerRequest(effect.operationId, effect.launch)) {
                            try {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            } catch (_: RuntimeException) {
                                owner.onPhotoPickerLaunchFailed(effect.operationId)
                            }
                        }
                    }
                    is ShootEditorEffect.NavigateToStartedSession ->
                        onNavigateToStartedSession(effect.handle)
                }
            }
        }
    }

    ShootEditorScreen(
        state = state,
        onBack = onBack,
        onRetry = owner::retryObservation,
        onRequestImport = owner::requestImport,
        onRequestReorder = owner::requestReorder,
        onRequestStart = owner::requestStart,
        onRequestResume = owner::requestResume,
    )
}
