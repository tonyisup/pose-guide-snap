package com.tonyisup.poseguidesnap.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tonyisup.poseguidesnap.data.ImportWorkStatus
import com.tonyisup.poseguidesnap.data.ShootPreparationLifecycle
import com.tonyisup.poseguidesnap.importer.ReferenceImportAllocationBlockReason
import com.tonyisup.poseguidesnap.importer.ReferenceImportRetryAction
import java.util.ArrayList
import java.util.Collections
import kotlin.math.abs

private const val MAX_LABEL_LENGTH = 200
private const val MAX_REFERENCES = 20
private val MIN_TOUCH_TARGET = 48.dp

internal class ShootEditorFeedbackText(
    val status: String,
    val guidance: String,
) {
    init {
        require(status.isNotBlank() && status.length <= 200)
        require(guidance.isNotBlank() && guidance.length <= 200)
    }

    override fun toString(): String = "ShootEditorFeedbackText(redacted)"
}

internal class ShootEditorReferenceText(
    val position: String,
    val label: String,
    val validation: String,
    val mirrorPolicy: String,
) {
    override fun toString(): String = "ShootEditorReferenceText(redacted)"
}

internal fun shootEditorEligibilityMessage(
    eligibility: ShootEditorStartEligibility,
): String = when (eligibility) {
    ShootEditorStartEligibility.ELIGIBLE ->
        "Ready to start with at least 3 validated reference poses."
    ShootEditorStartEligibility.TOO_FEW_REFERENCES ->
        "Add at least 3 reference poses before starting."
    ShootEditorStartEligibility.SHOOT_DELETING ->
        "This shoot is being deleted and cannot be started."
    ShootEditorStartEligibility.UNRESOLVED_IMPORT_WORK ->
        "This shoot needs import repair that is not available in this version. Use Back, then create a new shoot."
    ShootEditorStartEligibility.OPERATION_IN_PROGRESS ->
        "Wait for the current operation to finish before starting."
    ShootEditorStartEligibility.UNAVAILABLE ->
        "Start is unavailable right now. Try again later."
}

internal fun shootEditorFeedbackText(
    feedback: ShootEditorFeedback,
): ShootEditorFeedbackText {
    val status = when (feedback.code) {
        ShootEditorFeedbackCode.SOURCE_UNAVAILABLE ->
            "Shoot updates are temporarily unavailable."
        ShootEditorFeedbackCode.IMPORT_ALLOCATION_BLOCKED ->
            allocationBlockedStatus(feedback.allocationBlockReason)
        ShootEditorFeedbackCode.IMPORT_CANCELLED ->
            "Photo selection cancelled."
        ShootEditorFeedbackCode.IMPORT_INVALID_SELECTION ->
            "That selection cannot be used as a reference photo."
        ShootEditorFeedbackCode.IMPORT_SUCCEEDED ->
            "Reference photo added."
        ShootEditorFeedbackCode.IMPORT_VALIDATION_REJECTED ->
            "The selected photo did not pass reference validation."
        ShootEditorFeedbackCode.IMPORT_TERMINAL_REJECTED ->
            "The reference photo was rejected and was not added."
        ShootEditorFeedbackCode.IMPORT_RETRYABLE_FAILURE ->
            "The reference photo could not be added right now."
        ShootEditorFeedbackCode.RECONCILIATION_REQUIRED ->
            "Reference photo cleanup is required before continuing."
        ShootEditorFeedbackCode.REORDER_SAVED ->
            "Reference order saved."
        ShootEditorFeedbackCode.REORDER_UNCHANGED ->
            "The reference order was already up to date."
        ShootEditorFeedbackCode.REORDER_INVALID ->
            "That reference order could not be used."
        ShootEditorFeedbackCode.REORDER_BLOCKED ->
            "Reference order cannot be changed right now."
        ShootEditorFeedbackCode.REORDER_FAILED ->
            "Reference order could not be saved right now."
        ShootEditorFeedbackCode.START_INELIGIBLE ->
            "This shoot is not ready to start."
        ShootEditorFeedbackCode.START_CONFLICT ->
            "Another shoot session is already active."
        ShootEditorFeedbackCode.START_FAILED ->
            "The shoot could not be started right now."
    }
    val guidance = retryGuidance(feedback.retryAction) ?: when (feedback.code) {
        ShootEditorFeedbackCode.SOURCE_UNAVAILABLE ->
            "Use Retry to load the latest shoot details."
        ShootEditorFeedbackCode.IMPORT_ALLOCATION_BLOCKED ->
            allocationBlockedGuidance(feedback.allocationBlockReason)
        ShootEditorFeedbackCode.IMPORT_CANCELLED ->
            "Your label was kept. Choose a photo when you're ready."
        ShootEditorFeedbackCode.IMPORT_INVALID_SELECTION,
        ShootEditorFeedbackCode.IMPORT_VALIDATION_REJECTED,
        ShootEditorFeedbackCode.IMPORT_TERMINAL_REJECTED,
        -> "Choose a new photo to try again."
        ShootEditorFeedbackCode.IMPORT_SUCCEEDED ->
            "You can add another reference photo or continue."
        ShootEditorFeedbackCode.IMPORT_RETRYABLE_FAILURE ->
            "Try again later."
        ShootEditorFeedbackCode.RECONCILIATION_REQUIRED ->
            "Use Back, then create a new shoot; import repair is not available in this version."
        ShootEditorFeedbackCode.REORDER_SAVED ->
            "The displayed positions will update from the saved shoot."
        ShootEditorFeedbackCode.REORDER_UNCHANGED ->
            "No further action is needed."
        ShootEditorFeedbackCode.REORDER_INVALID ->
            "Wait for the list to refresh, then try the move again."
        ShootEditorFeedbackCode.REORDER_BLOCKED ->
            "Finish the current operation, then try the move again."
        ShootEditorFeedbackCode.REORDER_FAILED ->
            "Try moving the reference again later."
        ShootEditorFeedbackCode.START_INELIGIBLE ->
            "Review the start requirements below."
        ShootEditorFeedbackCode.START_CONFLICT ->
            "Finish the active session before starting this shoot."
        ShootEditorFeedbackCode.START_FAILED ->
            "Review the requirements and try again later."
    }
    return ShootEditorFeedbackText(status, guidance)
}

private fun retryGuidance(retryAction: ReferenceImportRetryAction): String? = when (retryAction) {
    ReferenceImportRetryAction.NONE -> null
    ReferenceImportRetryAction.RETRY_ALLOCATION ->
        "Retry adding the reference photo."
    ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY ->
        "Use Back, then create a new shoot; import repair is not available in this version."
    ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT ->
        "Choose a new photo to try again."
}

private fun allocationBlockedStatus(
    reason: ReferenceImportAllocationBlockReason?,
): String = when (reason) {
    ReferenceImportAllocationBlockReason.INVALID_REQUEST ->
        "The reference photo request was not accepted."
    ReferenceImportAllocationBlockReason.UNKNOWN_SHOOT ->
        "This shoot is no longer available."
    ReferenceImportAllocationBlockReason.SHOOT_DELETING ->
        "Photos cannot be added while this shoot is being deleted."
    ReferenceImportAllocationBlockReason.PLAYLIST_FULL ->
        "This shoot already has 20 reference poses."
    ReferenceImportAllocationBlockReason.ACTIVE_SESSION ->
        "Photos cannot be added while a shoot session is active."
    ReferenceImportAllocationBlockReason.IMPORT_IN_PROGRESS ->
        "Another reference photo is still being added."
    ReferenceImportAllocationBlockReason.RECONCILIATION_REQUIRED ->
        "Import cleanup is required before another photo can be added."
    ReferenceImportAllocationBlockReason.IDENTITY_UNAVAILABLE ->
        "A new reference photo cannot be prepared right now."
    ReferenceImportAllocationBlockReason.AUTHORITY_INCONSISTENT ->
        "Reference photo records need cleanup before continuing."
    ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE ->
        "Reference photo storage is temporarily unavailable."
    null -> "A reference photo cannot be added right now."
}

private fun allocationBlockedGuidance(
    reason: ReferenceImportAllocationBlockReason?,
): String = when (reason) {
    ReferenceImportAllocationBlockReason.INVALID_REQUEST ->
        "Check the label and retry adding the reference photo."
    ReferenceImportAllocationBlockReason.UNKNOWN_SHOOT ->
        "Go back to the shoot list and choose an available shoot."
    ReferenceImportAllocationBlockReason.SHOOT_DELETING ->
        "Go back to the shoot list."
    ReferenceImportAllocationBlockReason.PLAYLIST_FULL ->
        "Start with the existing references or go back."
    ReferenceImportAllocationBlockReason.ACTIVE_SESSION ->
        "Finish the active session, then retry adding the photo."
    ReferenceImportAllocationBlockReason.IMPORT_IN_PROGRESS ->
        "Wait for the current import, then retry adding the photo."
    ReferenceImportAllocationBlockReason.RECONCILIATION_REQUIRED,
    ReferenceImportAllocationBlockReason.AUTHORITY_INCONSISTENT,
    -> "Use Back, then create a new shoot; import repair is not available in this version."
    ReferenceImportAllocationBlockReason.IDENTITY_UNAVAILABLE,
    ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
    null,
    -> "Try again later."
}

internal fun shootEditorReferenceText(
    reference: ShootEditorReferenceItem,
): ShootEditorReferenceText = ShootEditorReferenceText(
    position = "Reference ${reference.poseIndex + 1}",
    label = reference.label,
    validation = "Validation: Validated",
    mirrorPolicy = if (reference.mirrorAllowed) "Mirror allowed" else "Mirror not allowed",
)

internal fun shootEditorReferenceLabelError(label: String): String? = when {
    label.length > MAX_LABEL_LENGTH -> "Use 200 characters or fewer."
    label.isBlank() -> "Enter a reference label."
    label.contains("content://", ignoreCase = true) ->
        "Use a label only; provider addresses are not allowed."
    label.any(Char::isISOControl) -> "Remove control characters from the label."
    else -> null
}

internal fun shootEditorMovedPoseOrder(
    references: List<ShootEditorReferenceItem>,
    fromIndex: Int,
    toIndex: Int,
): List<String>? {
    if (fromIndex !in references.indices || toIndex !in references.indices) return null
    if (fromIndex == toIndex || abs(fromIndex - toIndex) != 1) return null

    val moved = references.mapTo(ArrayList(references.size), ShootEditorReferenceItem::poseId)
    Collections.swap(moved, fromIndex, toIndex)
    return Collections.unmodifiableList(moved)
}

@Composable
internal fun ShootEditorScreen(
    state: ShootEditorUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRequestImport: (String) -> Unit,
    onRequestReorder: (List<String>) -> Unit,
    onRequestStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var label by rememberSaveable { mutableStateOf("") }
    val feedback = (state as? ShootEditorUiState.Loaded)?.data?.feedback
    LaunchedEffect(feedback) {
        if (feedback?.code == ShootEditorFeedbackCode.IMPORT_SUCCEEDED) label = ""
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "page-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET),
                ) {
                    Text("Back")
                }
                Text(
                    text = "Shoot editor",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        when (state) {
            ShootEditorUiState.Loading -> item(key = "loading") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Loading shoot"
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        },
                    )
                    Text("Loading shoot")
                }
            }
            ShootEditorUiState.Missing -> item(key = "missing") {
                StatusCard(
                    title = "Shoot not found",
                    body = "This shoot may have been removed. Go back to choose another shoot.",
                )
            }
            is ShootEditorUiState.Unavailable -> {
                item(key = "unavailable") {
                    StatusCard(
                        title = "Shoot unavailable",
                        body = "Shoot details could not be loaded. Try again or go back.",
                    )
                }
                if (state.canRetry) item(key = "retry") {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MIN_TOUCH_TARGET),
                    ) {
                        Text("Retry")
                    }
                }
            }
            is ShootEditorUiState.Loaded -> {
                val data = state.data
                val snapshot = data.snapshot
                val references = snapshot.references
                val operationPending = state is ShootEditorUiState.AllocatingImport ||
                    state is ShootEditorUiState.Importing ||
                    state is ShootEditorUiState.Reordering ||
                    state is ShootEditorUiState.Starting
                val deleting = snapshot.lifecycle == ShootPreparationLifecycle.DELETING
                val full = references.size >= MAX_REFERENCES
                val unresolvedImport = data.localReconciliationRequired ||
                    snapshot.importWorkStatuses.any { work ->
                        work == ImportWorkStatus.IN_PROGRESS ||
                            work == ImportWorkStatus.RECONCILIATION_REQUIRED
                    }

                item(key = "shoot-summary") {
                    ShootSummary(
                        name = snapshot.name,
                        referenceCount = references.size,
                        lifecycle = snapshot.lifecycle,
                    )
                }
                operationStatus(state)?.let { status ->
                    item(key = "operation-status") { PoliteStatus(status) }
                }
                if (ImportWorkStatus.IN_PROGRESS in snapshot.importWorkStatuses) {
                    item(key = "import-in-progress") {
                        PoliteStatus(
                            "Reference photo import in progress. Starting and adding another photo are temporarily blocked.",
                        )
                    }
                }
                if (
                    data.localReconciliationRequired ||
                    ImportWorkStatus.RECONCILIATION_REQUIRED in snapshot.importWorkStatuses
                ) {
                    item(key = "reconciliation-required") {
                        PoliteStatus(
                            "This shoot needs import repair that is not available in this version. Use Back, then create a new shoot.",
                        )
                    }
                }
                data.feedback?.let { currentFeedback ->
                    item(key = "feedback") {
                        val text = shootEditorFeedbackText(currentFeedback)
                        PoliteStatus("${text.status} ${text.guidance}")
                    }
                }
                item(key = "add-reference") {
                    AddReferenceForm(
                        label = label,
                        onLabelChange = { candidate -> label = candidate },
                        onAdd = { onRequestImport(label.trim()) },
                        operationPending = operationPending,
                        deleting = deleting,
                        full = full,
                        unresolvedImport = unresolvedImport,
                    )
                }
                if (references.isEmpty()) {
                    item(key = "empty-references") {
                        Text("No reference poses yet. Add at least 3 to start a shoot.")
                    }
                } else {
                    itemsIndexed(
                        items = references,
                        key = { _, reference -> reference.poseId },
                    ) { index, reference ->
                        ReferenceRow(
                            reference = reference,
                            canMoveUp = index > 0 && !operationPending && !deleting,
                            canMoveDown = index < references.lastIndex && !operationPending && !deleting,
                            onMoveUp = {
                                shootEditorMovedPoseOrder(references, index, index - 1)
                                    ?.let(onRequestReorder)
                            },
                            onMoveDown = {
                                shootEditorMovedPoseOrder(references, index, index + 1)
                                    ?.let(onRequestReorder)
                            },
                        )
                    }
                }
                item(key = "start") {
                    StartSection(
                        eligibility = state.startEligibility,
                        onStart = onRequestStart,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShootSummary(
    name: String,
    referenceCount: Int,
    lifecycle: ShootPreparationLifecycle,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            Text("$referenceCount reference poses")
            Text("Capacity: $referenceCount of $MAX_REFERENCES reference poses")
            Text(
                when (lifecycle) {
                    ShootPreparationLifecycle.ACTIVE -> "Active"
                    ShootPreparationLifecycle.DELETING -> "Being deleted"
                },
            )
        }
    }
}

@Composable
private fun AddReferenceForm(
    label: String,
    onLabelChange: (String) -> Unit,
    onAdd: () -> Unit,
    operationPending: Boolean,
    deleting: Boolean,
    full: Boolean,
    unresolvedImport: Boolean,
) {
    val labelError = shootEditorReferenceLabelError(label)
    val addEnabled = labelError == null &&
        !operationPending && !deleting && !full && !unresolvedImport
    val explanation = when {
        deleting -> "Reference photos cannot be added while this shoot is being deleted."
        full -> "This shoot has reached its 20-reference capacity."
        operationPending -> "Wait for the current operation to finish before adding a photo."
        unresolvedImport ->
            "Use Back, then create a new shoot; import repair is not available in this version."
        labelError != null -> labelError
        else -> "Ready to choose a reference photo."
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Add a reference", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = label,
                onValueChange = onLabelChange,
                label = { Text("Reference label") },
                isError = labelError != null,
                supportingText = {
                    Text(labelError ?: "${label.length} of $MAX_LABEL_LENGTH characters")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(explanation, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onAdd,
                enabled = addEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TOUCH_TARGET),
            ) {
                Text("Add reference photo")
            }
        }
    }
}

@Composable
private fun ReferenceRow(
    reference: ShootEditorReferenceItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val text = shootEditorReferenceText(reference)
    val label = text.label
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text.position, style = MaterialTheme.typography.labelLarge)
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(text.validation)
            Text(text.mirrorPolicy)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MIN_TOUCH_TARGET)
                        .semantics { contentDescription = "Move $label up" },
                ) {
                    Text("Move up")
                }
                Button(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MIN_TOUCH_TARGET)
                        .semantics { contentDescription = "Move $label down" },
                ) {
                    Text("Move down")
                }
            }
        }
    }
}

@Composable
private fun StartSection(
    eligibility: ShootEditorStartEligibility,
    onStart: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Start", style = MaterialTheme.typography.titleMedium)
            Text(shootEditorEligibilityMessage(eligibility))
            Button(
                onClick = onStart,
                enabled = eligibility == ShootEditorStartEligibility.ELIGIBLE,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TOUCH_TARGET),
            ) {
                Text("Start shoot")
            }
        }
    }
}

@Composable
private fun PoliteStatus(message: String) {
    Text(
        text = message,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun StatusCard(title: String, body: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body)
        }
    }
}

private fun operationStatus(state: ShootEditorUiState.Loaded): String? = when (state) {
    is ShootEditorUiState.AllocatingImport -> "Preparing to add a reference photo."
    is ShootEditorUiState.Importing -> "Reference photo selection and import are in progress."
    is ShootEditorUiState.Reordering -> "Saving the reference order."
    is ShootEditorUiState.Starting -> "Starting the shoot."
    is ShootEditorUiState.Empty,
    is ShootEditorUiState.Content,
    -> null
}
