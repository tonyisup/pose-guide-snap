package com.tonyisup.poseguidesnap.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPermissionRecoveryFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun permanentDenialOpensSettingsInsteadOfRepeatingPermissionRequest() {
        val recovery = mutableStateOf(CameraPermissionRecovery.INITIAL)
        val requests = AtomicInteger()
        val settings = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                CameraPermissionScreen(
                    recovery = recovery.value,
                    onAllowCamera = { requests.incrementAndGet() },
                    onOpenSettings = { settings.incrementAndGet() },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Permission action: Allow camera").performScrollTo().performClick()
        composeRule.runOnIdle { recovery.value = CameraPermissionRecovery.DENIED }
        composeRule.onNodeWithContentDescription("Permission action: Try camera permission again").performScrollTo().performClick()
        composeRule.runOnIdle { recovery.value = CameraPermissionRecovery.SETTINGS }
        composeRule.onNodeWithContentDescription("Permission action: Open app settings").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(2, requests.get())
            assertEquals(1, settings.get())
        }
    }
}
