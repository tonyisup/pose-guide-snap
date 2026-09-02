package com.tonyisup.poseguidesnap.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonyisup.poseguidesnap.data.ImportWorkStatus
import com.tonyisup.poseguidesnap.data.ShootPreparationLifecycle
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic-state Compose flow evidence. This test uses no database, picker, camera, or device I/O. */
@RunWith(AndroidJUnit4::class)
class ShootEditorFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingMissingAndUnavailableExposeRecoverySemantics() {
        val state = mutableStateOf<ShootEditorUiState>(ShootEditorUiState.Loading)
        val retries = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                screen(state.value, onRetry = retries::incrementAndGet)
            }
        }

        composeRule.onNodeWithText("Loading shoot").assertExists()
        composeRule.runOnIdle { state.value = ShootEditorUiState.Missing }
        composeRule.onNodeWithText("Shoot not found").assertExists()
        composeRule.runOnIdle { state.value = ShootEditorUiState.Unavailable(canRetry = true) }
        composeRule.onNodeWithText("Shoot unavailable").assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries.get()) }
    }

    @Test
    fun threeReferencesExposeHeadingDetailsMoveSemanticsAndCompleteReorder() {
        val reordered = AtomicReference<List<String>>()
        composeRule.setContent {
            MaterialTheme {
                screen(
                    state = loaded(references()),
                    onRequestReorder = { order -> reordered.set(order) },
                )
            }
        }

        composeRule.onNode(hasText("Shoot editor") and isHeading()).assertExists()
        composeRule.onNodeWithText("September session").assertExists()
        composeRule.onNodeWithText("3 reference poses").assertExists()
        listOf(
            Triple("Side stretch", "Validation: Validated", "Mirror allowed"),
            Triple("Forward fold", "Validation: Validated", "Mirror not allowed"),
            Triple("Tree pose", "Validation: Validated", "Mirror allowed"),
        ).forEachIndexed { index, (label, validation, mirrorPolicy) ->
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            val inExpectedRow = hasAnyAncestor(hasTestTag("reference-row-$index"))
            listOf(label, validation, mirrorPolicy).forEach { expectedText ->
                composeRule.onNode(
                    hasText(expectedText) and inExpectedRow,
                    useUnmergedTree = true,
                ).assertExists()
            }
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasContentDescription("Move Forward fold up"),
        )
        composeRule.onNodeWithContentDescription("Move Forward fold up")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("pose-b", "pose-a", "pose-c"), reordered.get())
        }
    }

    @Test
    fun startIsDisabledBelowThreeAndEnabledAtThreeWithClickCallback() {
        val state = mutableStateOf<ShootEditorUiState>(loaded(references().take(2)))
        val starts = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                screen(state.value, onRequestStart = starts::incrementAndGet)
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Start shoot"))
        composeRule.onNodeWithText("Start shoot").assertIsNotEnabled()
        composeRule.runOnIdle { state.value = loaded(references()) }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Start shoot"))
        composeRule.onNodeWithText("Start shoot")
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, starts.get()) }
    }

    @Test
    fun resumableSessionIsActionableAndBlockedWhileAnotherOperationIsActive() {
        val data = ShootEditorLoadedData(
            snapshot(
                references = references(),
                hasResumableSession = true,
            ),
        )
        val state = mutableStateOf<ShootEditorUiState>(ShootEditorUiState.Content(data))
        val resumes = AtomicInteger()
        composeRule.setContent {
            MaterialTheme {
                screen(
                    state = state.value,
                    onRequestResume = resumes::incrementAndGet,
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Resume session"))
        composeRule.onNodeWithText("Resume session")
            .assertHeightIsAtLeast(48.dp)
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, resumes.get()) }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Start shoot"))
        composeRule.onNodeWithText("Start shoot").assertIsNotEnabled()

        composeRule.runOnIdle {
            state.value = ShootEditorUiState.Reordering(
                data,
                ShootEditorOperationId("shoot-fixture", 1L),
            )
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Resume session"))
        composeRule.onNodeWithText("Resume session")
            .assertIsNotEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, resumes.get()) }
    }

    @Test
    fun importAndReconciliationStatusRemainVisible() {
        val operation = ShootEditorOperationId("shoot-fixture", 1L)
        val data = ShootEditorLoadedData(
            snapshot = snapshot(
                references = references(),
                importWorkStatuses = listOf(ImportWorkStatus.RECONCILIATION_REQUIRED),
            ),
            localReconciliationRequired = true,
        )
        composeRule.setContent {
            MaterialTheme {
                screen(ShootEditorUiState.Importing(data, operation))
            }
        }

        composeRule.onNodeWithText(
            "Reference photo selection and import are in progress.",
        ).performScrollTo().assertExists()
        composeRule.onNodeWithText(
            "This shoot needs import repair that is not available in this version. Use Back, then create a new shoot.",
        ).performScrollTo().assertExists()
    }

    private fun loaded(
        references: List<ShootEditorReferenceItem>,
    ): ShootEditorUiState = ShootEditorUiState.Content(
        ShootEditorLoadedData(snapshot(references)),
    )

    private fun snapshot(
        references: List<ShootEditorReferenceItem>,
        importWorkStatuses: List<ImportWorkStatus> = emptyList(),
        hasResumableSession: Boolean = false,
    ): ShootEditorDisplaySnapshot = ShootEditorDisplaySnapshot(
        name = "September session",
        lifecycle = ShootPreparationLifecycle.ACTIVE,
        references = references,
        importWorkStatuses = importWorkStatuses,
        hasResumableSession = hasResumableSession,
    )

    private fun references(): List<ShootEditorReferenceItem> = listOf(
        ShootEditorReferenceItem("pose-a", 0, "Side stretch", true),
        ShootEditorReferenceItem("pose-b", 1, "Forward fold", false),
        ShootEditorReferenceItem("pose-c", 2, "Tree pose", true),
    )

    @Composable
    private fun screen(
        state: ShootEditorUiState,
        onRetry: () -> Unit = {},
        onRequestReorder: (List<String>) -> Unit = {},
        onRequestStart: () -> Unit = {},
        onRequestResume: () -> Unit = {},
    ) {
        ShootEditorScreen(
            state = state,
            onBack = {},
            onRetry = onRetry,
            onRequestImport = {},
            onRequestReorder = onRequestReorder,
            onRequestStart = onRequestStart,
            onRequestResume = onRequestResume,
        )
    }
}
