package com.tonyisup.poseguidesnap.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tonyisup.poseguidesnap.ui.StartedSessionCameraDestination

internal class StartedSessionStatusPresentation(
    val heading: String,
    val guidance: String,
    val canRetry: Boolean,
    val showProgress: Boolean,
) {
    init {
        require(heading.isNotBlank() && heading.length <= MAX_PRESENTATION_TEXT_LENGTH)
        require(guidance.isNotBlank() && guidance.length <= MAX_PRESENTATION_TEXT_LENGTH)
    }

    override fun toString(): String = "StartedSessionStatusPresentation(redacted)"
}

internal fun startedSessionAuthorizesCamera(
    state: StartedSessionBootstrapState,
): Boolean = state is StartedSessionBootstrapState.Ready

internal fun startedSessionStatusPresentation(
    state: StartedSessionBootstrapState,
): StartedSessionStatusPresentation? = when (state) {
    StartedSessionBootstrapState.Loading -> StartedSessionStatusPresentation(
        heading = "Loading session",
        guidance = "Checking session readiness before opening the camera.",
        canRetry = false,
        showProgress = true,
    )
    is StartedSessionBootstrapState.Ready -> null
    StartedSessionBootstrapState.Completed -> StartedSessionStatusPresentation(
        heading = "Session complete",
        guidance = "This session is complete. Use Back to return to your shoots.",
        canRetry = false,
        showProgress = false,
    )
    StartedSessionBootstrapState.ReconciliationRequired -> StartedSessionStatusPresentation(
        heading = "Session needs repair",
        guidance = "Camera access is blocked until this session is repaired. Use Back to return to your shoots.",
        canRetry = false,
        showProgress = false,
    )
    StartedSessionBootstrapState.Missing -> StartedSessionStatusPresentation(
        heading = "Session not found",
        guidance = "This session is unavailable. Use Back to return to your shoots.",
        canRetry = false,
        showProgress = false,
    )
    is StartedSessionBootstrapState.Unavailable -> StartedSessionStatusPresentation(
        heading = "Session unavailable",
        guidance = if (state.canRetry) {
            "Session details could not be loaded. Retry or use Back to return to your shoots."
        } else {
            "Session details could not be loaded. Use Back to return to your shoots."
        },
        canRetry = state.canRetry,
        showProgress = false,
    )
}

@Composable
internal fun StartedSessionDestination(
    owner: StartedSessionBootstrapViewModel,
    lifecycleOwner: LifecycleOwner,
    onBack: () -> Unit,
) {
    val state by owner.state.collectAsStateWithLifecycle()
    StartedSessionScreen(
        state = state,
        onRetry = owner::retry,
        onBack = onBack,
        cameraContent = { StartedSessionCameraDestination(lifecycleOwner) },
    )
}

@Composable
internal fun StartedSessionScreen(
    state: StartedSessionBootstrapState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    cameraContent: @Composable () -> Unit,
) {
    if (startedSessionAuthorizesCamera(state)) {
        cameraContent()
        return
    }

    val presentation = requireNotNull(startedSessionStatusPresentation(state))
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .testTag(STARTED_SESSION_STATUS_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(
            onClick = onBack,
            modifier = Modifier
                .heightIn(min = MIN_TOUCH_TARGET)
                .testTag(STARTED_SESSION_BACK_TAG),
        ) {
            Text("Back")
        }
        Text(
            text = presentation.heading,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (presentation.showProgress) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics {
                        contentDescription = "Loading session"
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    },
                )
                Text(presentation.guidance)
            }
        } else {
            Text(presentation.guidance)
        }
        if (presentation.canRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .testTag(STARTED_SESSION_RETRY_TAG),
            ) {
                Text("Retry")
            }
        }
    }
}

internal const val STARTED_SESSION_STATUS_TAG = "started-session-status"
internal const val STARTED_SESSION_BACK_TAG = "started-session-back"
internal const val STARTED_SESSION_RETRY_TAG = "started-session-retry"
private const val MAX_PRESENTATION_TEXT_LENGTH = 200
private val MIN_TOUCH_TARGET = 48.dp
