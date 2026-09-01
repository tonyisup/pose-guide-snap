package com.tonyisup.poseguidesnap.ui.editor

import com.tonyisup.poseguidesnap.importer.ReferenceImportRetryAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShootEditorScreenTest {
    @Test
    fun eligibilityMessagesCoverEveryStateWithExplicitRecovery() {
        val expected = mapOf(
            ShootEditorStartEligibility.ELIGIBLE to
                "Ready to start with at least 3 validated reference poses.",
            ShootEditorStartEligibility.TOO_FEW_REFERENCES to
                "Add at least 3 reference poses before starting.",
            ShootEditorStartEligibility.SHOOT_DELETING to
                "This shoot is being deleted and cannot be started.",
            ShootEditorStartEligibility.UNRESOLVED_IMPORT_WORK to
                "This shoot needs import repair that is not available in this version. Use Back, then create a new shoot.",
            ShootEditorStartEligibility.OPERATION_IN_PROGRESS to
                "Wait for the current operation to finish before starting.",
            ShootEditorStartEligibility.UNAVAILABLE to
                "Start is unavailable right now. Try again later.",
        )

        assertEquals(ShootEditorStartEligibility.entries.toSet(), expected.keys)
        expected.forEach { (eligibility, message) ->
            assertEquals(message, shootEditorEligibilityMessage(eligibility))
            assertFalse(eligibility.name in message)
            assertTrue(message.length in 1..200)
        }
    }

    @Test
    fun feedbackTextCoversEveryCodeWithoutRawMarkers() {
        val presentations = ShootEditorFeedbackCode.entries.associateWith { code ->
            shootEditorFeedbackText(ShootEditorFeedback(code))
        }

        assertEquals(ShootEditorFeedbackCode.entries.toSet(), presentations.keys)
        presentations.forEach { (code, presentation) ->
            assertTrue("Missing status for $code", presentation.status.isNotBlank())
            assertTrue("Missing guidance for $code", presentation.guidance.isNotBlank())
            assertTrue(presentation.status.length <= 200)
            assertTrue(presentation.guidance.length <= 200)
            assertFalse(code.name in presentation.status)
            assertFalse(code.name in presentation.guidance)
            listOf("content://", "/private/", "token", "URI", "UNKNOWN_SHOOT").forEach { raw ->
                assertFalse("Feedback exposed raw marker $raw", raw in presentation.status)
                assertFalse("Guidance exposed raw marker $raw", raw in presentation.guidance)
                assertFalse("toString exposed raw marker $raw", raw in presentation.toString())
            }
        }
    }

    @Test
    fun feedbackDistinguishesCancellationValidationAndTerminalRejection() {
        val cancelled = feedback(ShootEditorFeedbackCode.IMPORT_CANCELLED)
        val validation = feedback(ShootEditorFeedbackCode.IMPORT_VALIDATION_REJECTED)
        val terminal = feedback(ShootEditorFeedbackCode.IMPORT_TERMINAL_REJECTED)

        assertEquals("Photo selection cancelled.", cancelled.status)
        assertFalse(cancelled.status.contains("error", ignoreCase = true))
        assertFalse(cancelled.status.contains("failed", ignoreCase = true))
        assertEquals("The selected photo did not pass reference validation.", validation.status)
        assertEquals("The reference photo was rejected and was not added.", terminal.status)
        assertFalse(validation.status == terminal.status)
    }

    @Test
    fun retryActionsProvideVisibleSpecificGuidance() {
        val expected = mapOf(
            ReferenceImportRetryAction.NONE to "Try again later.",
            ReferenceImportRetryAction.RETRY_ALLOCATION to
                "Retry adding the reference photo.",
            ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY to
                "Use Back, then create a new shoot; import repair is not available in this version.",
            ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT to
                "Choose a new photo to try again.",
        )

        expected.forEach { (retryAction, guidance) ->
            val presentation = shootEditorFeedbackText(
                ShootEditorFeedback(
                    code = ShootEditorFeedbackCode.IMPORT_RETRYABLE_FAILURE,
                    retryAction = retryAction,
                ),
            )
            assertEquals(guidance, presentation.guidance)
            assertFalse(retryAction.name in presentation.guidance)
        }
    }

    @Test
    fun labelValidationMatchesTheBackendBoundaryWithActionableErrors() {
        assertEquals("Enter a reference label.", shootEditorReferenceLabelError("   "))
        assertEquals(
            "Use a label only; provider addresses are not allowed.",
            shootEditorReferenceLabelError("content://private/reference"),
        )
        assertEquals(
            "Remove control characters from the label.",
            shootEditorReferenceLabelError("Side\u0000pose"),
        )
        assertEquals(
            "Use 200 characters or fewer.",
            shootEditorReferenceLabelError("a".repeat(201)),
        )
        assertNull(shootEditorReferenceLabelError("  Side pose  "))
    }

    @Test
    fun movedOrdersAreCompleteExactAndDoNotMutateInputs() {
        val references = references()
        val originalIds = references.map(ShootEditorReferenceItem::poseId)

        val movedUp = shootEditorMovedPoseOrder(references, fromIndex = 1, toIndex = 0)
        val movedDown = shootEditorMovedPoseOrder(references, fromIndex = 1, toIndex = 2)

        assertEquals(listOf("pose-b", "pose-a", "pose-c"), movedUp)
        assertEquals(listOf("pose-a", "pose-c", "pose-b"), movedDown)
        assertEquals(originalIds, references.map(ShootEditorReferenceItem::poseId))
        assertEquals(originalIds.toSet(), movedUp!!.toSet())
        assertEquals(originalIds.toSet(), movedDown!!.toSet())
        assertThrows(UnsupportedOperationException::class.java) {
            (movedUp as MutableList<String>).add("pose-d")
        }
    }

    @Test
    fun movedOrderReturnsNoOpForEdgesInvalidTargetsAndSamePosition() {
        val references = references()

        assertNull(shootEditorMovedPoseOrder(references, fromIndex = 0, toIndex = -1))
        assertNull(shootEditorMovedPoseOrder(references, fromIndex = 2, toIndex = 3))
        assertNull(shootEditorMovedPoseOrder(references, fromIndex = -1, toIndex = 0))
        assertNull(shootEditorMovedPoseOrder(references, fromIndex = 3, toIndex = 2))
        assertNull(shootEditorMovedPoseOrder(references, fromIndex = 1, toIndex = 1))
        assertNull(shootEditorMovedPoseOrder(references, fromIndex = 0, toIndex = 2))
    }

    @Test
    fun referenceTextNamesPositionValidationAndMirrorPolicyWithoutLeakingIdentity() {
        val mirrored = shootEditorReferenceText(references()[0])
        val fixed = shootEditorReferenceText(references()[1])

        assertEquals("Reference 1", mirrored.position)
        assertEquals("Side stretch", mirrored.label)
        assertEquals("Validation: Validated", mirrored.validation)
        assertEquals("Mirror allowed", mirrored.mirrorPolicy)
        assertEquals("Mirror not allowed", fixed.mirrorPolicy)
        listOf(mirrored, fixed).forEach { text ->
            assertFalse("pose-" in text.toString())
            assertFalse("Side stretch" in text.toString())
        }
    }

    @Test
    fun sourceContractPinsPureAccessibleScrollableScreenBoundary() {
        val sourceFile = projectRoot().resolve(SOURCE_PATH)
        assertTrue("ShootEditorScreen source must exist", sourceFile.isFile)
        val source = sourceFile.readText()
        assertTrue("Screen source is unexpectedly vacuous", source.length > 5_000)

        listOf(
            "fun ShootEditorScreen(",
            "state: ShootEditorUiState",
            "onBack: () -> Unit",
            "onRetry: () -> Unit",
            "onRequestImport: (String) -> Unit",
            "onRequestReorder: (List<String>) -> Unit",
            "onRequestStart: () -> Unit",
            ".statusBarsPadding()",
            ".navigationBarsPadding()",
            "heading()",
            "ProgressBarRangeInfo.Indeterminate",
            "Shoot not found",
            "Reference label",
            "singleLine = true",
            "onLabelChange = { candidate -> label = candidate }",
            "shootEditorReferenceLabelError(label)",
            "isError = labelError != null",
            "onRequestImport(label.trim())",
            "Add reference photo",
            "Validation: Validated",
            "Mirror allowed",
            "Mirror not allowed",
            "Move ${'$'}label up",
            "Move ${'$'}label down",
            "Start shoot",
            "liveRegion = LiveRegionMode.Polite",
            "heightIn(min = MIN_TOUCH_TARGET)",
            "LazyColumn(",
        ).forEach { marker -> assertTrue("Missing screen contract marker: $marker", marker in source) }
        assertEquals("Screen must use one coherent lazy list", 1, source.countOccurrences("LazyColumn("))
        assertFalse("Screen must not combine nested scrolling strategies", "verticalScroll(" in source)

        listOf(
            "import android.net.Uri",
            "import androidx.room",
            "import androidx.camera",
            "CameraController",
            "ShootEditorViewModel",
            "java.time.Clock",
            "java.util.UUID",
            "ReferencePickerImportDraft",
        ).forEach { forbidden ->
            assertFalse("Screen source contains forbidden marker $forbidden", forbidden in source)
        }
    }

    private fun feedback(code: ShootEditorFeedbackCode): ShootEditorFeedbackText =
        shootEditorFeedbackText(ShootEditorFeedback(code))

    private fun references(): List<ShootEditorReferenceItem> = listOf(
        ShootEditorReferenceItem("pose-a", 0, "Side stretch", true),
        ShootEditorReferenceItem("pose-b", 1, "Forward fold", false),
        ShootEditorReferenceItem("pose-c", 2, "Tree pose", true),
    )

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length).count { candidate -> candidate == needle }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private companion object {
        const val SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorScreen.kt"
    }
}
