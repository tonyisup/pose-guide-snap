package com.tonyisup.poseguidesnap.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionRecoveryTest {
    @Test
    fun firstRequestRetryAndPermanentDenialHaveDifferentRecoveryActions() {
        assertEquals(CameraPermissionRecovery.INITIAL, cameraPermissionRecovery(false, false))
        assertEquals(CameraPermissionRecovery.DENIED, cameraPermissionRecovery(true, true))
        assertEquals(CameraPermissionRecovery.SETTINGS, cameraPermissionRecovery(true, false))
        // The platform's rationale takes precedence if local preference state is missing.
        assertEquals(CameraPermissionRecovery.DENIED, cameraPermissionRecovery(false, true))
    }
}
