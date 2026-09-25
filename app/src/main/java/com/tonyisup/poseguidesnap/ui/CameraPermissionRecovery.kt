package com.tonyisup.poseguidesnap.ui

internal enum class CameraPermissionRecovery(val actionLabel: String) {
    INITIAL("Allow camera"),
    DENIED("Try camera permission again"),
    SETTINGS("Open app settings"),
}

/** A prior request plus no available rationale needs a settings route, not an endless prompt. */
internal fun cameraPermissionRecovery(hasRequested: Boolean, shouldShowRationale: Boolean): CameraPermissionRecovery =
    when {
        shouldShowRationale -> CameraPermissionRecovery.DENIED
        hasRequested -> CameraPermissionRecovery.SETTINGS
        else -> CameraPermissionRecovery.INITIAL
    }
