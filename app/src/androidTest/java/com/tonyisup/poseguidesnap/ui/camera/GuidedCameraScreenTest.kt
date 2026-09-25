package com.tonyisup.poseguidesnap.ui.camera

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonyisup.poseguidesnap.data.GuidedReferenceSnapshot
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuidedCameraScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readyControlsExposeCurrentPoseAndInvokeManualCaptureAndStop() {
        val captures = AtomicInteger()
        val stops = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                GuidedCameraControls(
                    state = state(GuidedCameraPhase.READY, cameraReady = true),
                    onCapture = captures::incrementAndGet,
                    onStop = stops::incrementAndGet,
                )
            }
        }

        composeRule.onNodeWithTag(GUIDED_CAMERA_CONTROLS_TAG).assertExists()
        composeRule.onNodeWithText("Pose 1 of 3").assertExists()
        composeRule.onNodeWithContentDescription("Current reference: First pose").assertExists()
        composeRule.onNodeWithContentDescription("Capture three photos")
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithContentDescription("Stop guided session")
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, captures.get())
            assertEquals(1, stops.get())
        }
    }

    @Test
    fun captureRequiresReadyCameraAndStoppingDisablesBothActions() {
        val state = androidx.compose.runtime.mutableStateOf(
            state(GuidedCameraPhase.READY, cameraReady = false),
        )
        composeRule.setContent {
            MaterialTheme {
                GuidedCameraControls(state.value, onCapture = {}, onStop = {})
            }
        }

        composeRule.onNodeWithText("Preparing the camera").assertExists()
        composeRule.onNodeWithTag(GUIDED_CAPTURE_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(GUIDED_STOP_BUTTON_TAG).assertIsEnabled()

        composeRule.runOnIdle {
            state.value = state(GuidedCameraPhase.STOPPING, cameraReady = true)
        }
        composeRule.onNodeWithText("Finishing the current capture before stopping").assertExists()
        composeRule.onNodeWithTag(GUIDED_CAPTURE_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(GUIDED_STOP_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithText("Stopping…").assertExists()
    }

    private fun state(
        phase: GuidedCameraPhase,
        cameraReady: Boolean,
    ) = GuidedCameraUiState(
        phase = phase,
        currentPoseNumber = 1,
        poseCount = 3,
        reference = reference(),
        cameraReady = cameraReady,
    )

    private fun reference() = GuidedReferenceSnapshot(
        poseId = "pose-0",
        label = "First pose",
        relativeAssetPath = "reference-assets/assets/${"a".repeat(64)}.asset",
        mirrorAllowed = false,
        landmarks = listOf(
            Landmark(PoseLandmark.NOSE, 0.5, 0.2, 0.0, 0.9, 0.9),
        ),
        imageSize = PoseImageSize(1920, 1080),
    )
}
