package com.tonyisup.poseguidesnap.ui.camera

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun GuidedCameraControls(
    state: GuidedCameraUiState,
    onCapture: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "Guided capture controls, pose ${state.currentPoseNumber} of ${state.poseCount}"
            }
            .testTag(GUIDED_CAMERA_CONTROLS_TAG),
        color = Color(0xED211D19),
        contentColor = Color(0xFFF6F0E6),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Pose ${state.currentPoseNumber} of ${state.poseCount}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            state.reference?.let { reference ->
                Text(
                    text = reference.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "Current reference: ${reference.label}"
                    },
                )
            }
            Text(
                text = guidedCameraStatusText(state),
                fontSize = 16.sp,
                modifier = Modifier.semantics {
                    contentDescription = "Capture status: ${guidedCameraStatusText(state)}"
                },
            )
            if (state.lastCapture.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.lastCapture.forEachIndexed { index, bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription =
                                "Last capture, photo ${index + 1} of ${state.lastCapture.size}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCapture,
                    enabled = state.captureEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .semantics { contentDescription = "Capture three photos" }
                        .testTag(GUIDED_CAPTURE_BUTTON_TAG),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE6B86A),
                        contentColor = Color(0xFF171411),
                    ),
                ) {
                    Text("Capture")
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = state.stopEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .semantics { contentDescription = "Stop guided session" }
                        .testTag(GUIDED_STOP_BUTTON_TAG),
                ) {
                    Text(if (state.phase == GuidedCameraPhase.STOPPING) "Stopping…" else "Stop")
                }
            }
        }
    }
}

internal fun guidedCameraStatusText(state: GuidedCameraUiState): String = when (state.phase) {
    GuidedCameraPhase.LOADING_REFERENCE -> "Loading the current reference"
    GuidedCameraPhase.READY -> when {
        !state.cameraReady -> "Preparing the camera"
        else -> when (state.matchPhase) {
            GuidedMatchPhase.IDLE -> "Ready: match the reference pose, or capture manually"
            GuidedMatchPhase.SEARCHING -> "Looking for you"
            GuidedMatchPhase.FRAMING -> "Get your whole body in the frame"
            GuidedMatchPhase.COACHING -> "Match the pose${matchPercent(state.overallMatch)}"
            GuidedMatchPhase.LOCK_CANDIDATE -> "Hold it${matchPercent(state.overallMatch)}"
            GuidedMatchPhase.LOCKED -> "Locked: capturing"
        }
    }
    GuidedCameraPhase.CAPTURING -> "Taking three photos"
    GuidedCameraPhase.CONFIRMING -> "Saving the capture"
    GuidedCameraPhase.STOPPING -> "Finishing the current capture before stopping"
    GuidedCameraPhase.STOPPED -> "Session stopped"
    GuidedCameraPhase.COMPLETED -> "Shoot complete"
    GuidedCameraPhase.NEEDS_REPAIR -> "Capture needs recovery before continuing"
    GuidedCameraPhase.UNAVAILABLE -> "Current reference is unavailable"
}

private fun matchPercent(overallMatch: Double?): String =
    overallMatch?.let { " (${(it * 100.0).roundToInt()}%)" } ?: ""

internal const val GUIDED_CAMERA_CONTROLS_TAG = "guided-camera-controls"
internal const val GUIDED_CAPTURE_BUTTON_TAG = "guided-capture-button"
internal const val GUIDED_STOP_BUTTON_TAG = "guided-stop-button"
