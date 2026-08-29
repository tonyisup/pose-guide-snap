package com.tonyisup.poseguidesnap.camera

import androidx.camera.core.Preview
import androidx.camera.core.ViewPort
import androidx.lifecycle.LifecycleOwner

/** Narrow lifecycle boundary for this app's rear CameraX pipeline. */
interface CameraController : AutoCloseable {
    val state: CameraControllerState

    fun bind(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        viewPort: ViewPort,
        targetRotation: Int,
    )

    fun updateRotation(targetRotation: Int)
}

enum class CameraControllerStatus {
    IDLE,
    BINDING,
    READY,
    FAILED,
    CLOSED,
}

/** Immutable lifecycle state with no exception or diagnostic payload. */
data class CameraControllerState(
    val status: CameraControllerStatus,
)
