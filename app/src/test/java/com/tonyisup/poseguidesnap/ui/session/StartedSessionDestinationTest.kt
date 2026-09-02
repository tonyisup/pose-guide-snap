package com.tonyisup.poseguidesnap.ui.session

import com.tonyisup.poseguidesnap.data.GuidedSessionLifecycle
import com.tonyisup.poseguidesnap.data.GuidedSessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartedSessionDestinationTest {
    @Test
    fun onlyReadyAuthorizesCamera() {
        val ready = StartedSessionBootstrapState.Ready(snapshot())
        val nonReady = listOf(
            StartedSessionBootstrapState.Loading,
            StartedSessionBootstrapState.Completed,
            StartedSessionBootstrapState.ReconciliationRequired,
            StartedSessionBootstrapState.Missing,
            StartedSessionBootstrapState.Unavailable(canRetry = true),
            StartedSessionBootstrapState.Unavailable(canRetry = false),
        )

        assertTrue("Ready must authorize camera content", startedSessionAuthorizesCamera(ready))
        nonReady.forEach { state ->
            assertFalse("$state must not authorize camera content", startedSessionAuthorizesCamera(state))
        }
    }

    @Test
    fun everyNonReadyStateHasBoundedGenericPresentation() {
        val secret = "session-secret-private-content-uri"
        val cases = listOf(
            StartedSessionBootstrapState.Loading to Expected(
                heading = "Loading session",
                canRetry = false,
                showProgress = true,
            ),
            StartedSessionBootstrapState.Completed to Expected(
                heading = "Session complete",
                canRetry = false,
                showProgress = false,
            ),
            StartedSessionBootstrapState.ReconciliationRequired to Expected(
                heading = "Session needs repair",
                canRetry = false,
                showProgress = false,
            ),
            StartedSessionBootstrapState.Missing to Expected(
                heading = "Session not found",
                canRetry = false,
                showProgress = false,
            ),
            StartedSessionBootstrapState.Unavailable(canRetry = true) to Expected(
                heading = "Session unavailable",
                canRetry = true,
                showProgress = false,
            ),
            StartedSessionBootstrapState.Unavailable(canRetry = false) to Expected(
                heading = "Session unavailable",
                canRetry = false,
                showProgress = false,
            ),
        )

        cases.forEach { (state, expected) ->
            val presentation = requireNotNull(startedSessionStatusPresentation(state))
            assertEquals(expected.heading, presentation.heading)
            assertEquals(expected.canRetry, presentation.canRetry)
            assertEquals(expected.showProgress, presentation.showProgress)
            assertTrue(presentation.heading.length <= 200)
            assertTrue(presentation.guidance.length <= 200)
            assertFalse(presentation.heading.contains(secret))
            assertFalse(presentation.guidance.contains(secret))
            assertFalse(presentation.toString().contains(secret))
        }
        assertTrue(
            requireNotNull(
                startedSessionStatusPresentation(
                    StartedSessionBootstrapState.Completed,
                ),
            ).guidance.contains("Back"),
        )
        assertTrue(
            requireNotNull(
                startedSessionStatusPresentation(
                    StartedSessionBootstrapState.ReconciliationRequired,
                ),
            ).guidance.contains("Camera access is blocked"),
        )
        assertNull(
            startedSessionStatusPresentation(
                StartedSessionBootstrapState.Ready(snapshot(sessionId = secret)),
            ),
        )
    }

    private fun snapshot(sessionId: String = "session-safe") = GuidedSessionSnapshot(
        sessionId = sessionId,
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

    private data class Expected(
        val heading: String,
        val canRetry: Boolean,
        val showProgress: Boolean,
    )
}
