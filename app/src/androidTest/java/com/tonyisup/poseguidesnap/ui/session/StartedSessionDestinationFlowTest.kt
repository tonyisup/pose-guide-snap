package com.tonyisup.poseguidesnap.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonyisup.poseguidesnap.data.GuidedSessionLifecycle
import com.tonyisup.poseguidesnap.data.GuidedSessionSnapshot
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartedSessionDestinationFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyNonReadyStateBlocksCameraAndRendersAccessibleStatus() {
        val state = mutableStateOf<StartedSessionBootstrapState>(StartedSessionBootstrapState.Loading)
        composeRule.setContent {
            MaterialTheme {
                StartedSessionScreen(
                    state = state.value,
                    onRetry = {},
                    onBack = {},
                    cameraContent = { Text(FAKE_CAMERA) },
                )
            }
        }

        val cases = listOf(
            StartedSessionBootstrapState.Loading to "Loading session",
            StartedSessionBootstrapState.Completed to "Session complete",
            StartedSessionBootstrapState.ReconciliationRequired to "Session needs repair",
            StartedSessionBootstrapState.Missing to "Session not found",
            StartedSessionBootstrapState.Unavailable(canRetry = true) to "Session unavailable",
            StartedSessionBootstrapState.Unavailable(canRetry = false) to "Session unavailable",
        )

        cases.forEach { (nextState, heading) ->
            composeRule.runOnIdle { state.value = nextState }
            composeRule.waitForIdle()

            composeRule.onNodeWithText(FAKE_CAMERA).assertDoesNotExist()
            composeRule.onNode(hasText(heading) and isHeading()).assertExists()
            composeRule.onNodeWithTag(STARTED_SESSION_STATUS_TAG).assertExists()
            composeRule.onNodeWithText("Back").assertHeightIsAtLeast(48.dp)
            if (nextState is StartedSessionBootstrapState.Unavailable && nextState.canRetry) {
                composeRule.onNodeWithText("Retry").assertHeightIsAtLeast(48.dp)
            } else {
                composeRule.onNodeWithText("Retry").assertDoesNotExist()
            }
        }

        composeRule.runOnIdle { state.value = StartedSessionBootstrapState.Loading }
        composeRule.onNodeWithContentDescription("Loading session").assertExists()
    }

    @Test
    fun readyIsTheOnlyStateThatInvokesCameraContent() {
        composeRule.setContent {
            MaterialTheme {
                StartedSessionScreen(
                    state = StartedSessionBootstrapState.Ready(snapshot()),
                    onRetry = {},
                    onBack = {},
                    cameraContent = { Text(FAKE_CAMERA) },
                )
            }
        }

        composeRule.onNodeWithText(FAKE_CAMERA).assertExists()
        composeRule.onNodeWithTag(STARTED_SESSION_STATUS_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Back").assertDoesNotExist()
    }

    @Test
    fun retryAndBackCallbacksRemainUserControlled() {
        val retries = AtomicInteger(0)
        val backs = AtomicInteger(0)
        composeRule.setContent {
            MaterialTheme {
                StartedSessionScreen(
                    state = StartedSessionBootstrapState.Unavailable(canRetry = true),
                    onRetry = retries::incrementAndGet,
                    onBack = backs::incrementAndGet,
                    cameraContent = { Text(FAKE_CAMERA) },
                )
            }
        }

        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Back").performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries.get())
            assertEquals(1, backs.get())
        }
    }

    @Test
    fun compactHeightAndLargeFontKeepRetryAndBackReachableByScrolling() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                MaterialTheme {
                    Box(
                        modifier = Modifier
                            .width(320.dp)
                            .height(180.dp),
                    ) {
                        StartedSessionScreen(
                            state = StartedSessionBootstrapState.Unavailable(canRetry = true),
                            onRetry = {},
                            onBack = {},
                            cameraContent = { Text(FAKE_CAMERA) },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Retry")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Back")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(FAKE_CAMERA).assertDoesNotExist()
    }

    private fun snapshot() = GuidedSessionSnapshot(
        sessionId = "session-safe",
        shootId = "shoot-safe",
        lifecycle = GuidedSessionLifecycle.ACTIVE,
        orderedPoseIds = listOf("pose-0", "pose-1", "pose-2"),
        poseCount = 3,
        currentPoseIndex = 0,
        nextAttemptNumber = 0L,
        deletionGeneration = 0L,
        attemptCount = 0,
        confirmedAttemptCount = 0,
        appliedReceiptTokens = emptyList(),
        unresolvedExportCount = 0,
        blockingAttempt = null,
    )

    private companion object {
        const val FAKE_CAMERA = "Fake camera content"
    }
}
