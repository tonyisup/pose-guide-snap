package com.tonyisup.poseguidesnap.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonyisup.poseguidesnap.data.ImportWorkStatus
import com.tonyisup.poseguidesnap.data.ShootPreparationLifecycle
import com.tonyisup.poseguidesnap.data.ShootReorderResult
import com.tonyisup.poseguidesnap.importer.ReferenceImportAllocationBlockReason
import com.tonyisup.poseguidesnap.importer.ReferenceImportOutcome
import com.tonyisup.poseguidesnap.importer.ReferenceImportOutcomeStatus
import com.tonyisup.poseguidesnap.importer.ReferenceImportRetryAction
import com.tonyisup.poseguidesnap.importer.ReferencePickerResult
import java.util.AbstractList
import java.util.ArrayList
import java.util.Collections
import java.util.RandomAccess
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private const val MAX_DISPLAY_ITEMS = 20

private class RedactedImmutableList<E>(
    source: Iterable<E>,
    maxSize: Int,
) : AbstractList<E>(), RandomAccess {
    private val values = ArrayList<E>()

    init {
        require(maxSize >= 0) { "projection list bound must be nonnegative" }
        val iterator = source.iterator()
        while (iterator.hasNext()) {
            if (values.size == maxSize) {
                iterator.next()
                throw IllegalArgumentException("projection list exceeds its bounded maximum")
            }
            values.add(iterator.next())
        }
    }

    override val size: Int
        get() = values.size

    override fun get(index: Int): E = values[index]

    override fun toString(): String = "RedactedImmutableList(redacted)"
}

internal class ShootEditorReferenceItem(
    val poseId: String,
    val poseIndex: Int,
    val label: String,
    val mirrorAllowed: Boolean,
) {
    init {
        require(poseId.isSafeOpaqueIdentity()) { "reference pose ID must be safe" }
        require(poseIndex >= 0) { "reference pose index must be nonnegative" }
        require(label.isSafeDisplayText()) { "reference label must be safe display text" }
    }

    override fun toString(): String = "ShootEditorReferenceItem(redacted)"
}

internal class ShootEditorDisplaySnapshot(
    val name: String,
    val lifecycle: ShootPreparationLifecycle,
    references: Iterable<ShootEditorReferenceItem>,
    importWorkStatuses: Iterable<ImportWorkStatus>,
    val hasResumableSession: Boolean = false,
) {
    val references: List<ShootEditorReferenceItem> =
        RedactedImmutableList(references, MAX_DISPLAY_ITEMS)
    val importWorkStatuses: List<ImportWorkStatus> =
        RedactedImmutableList(importWorkStatuses, MAX_DISPLAY_ITEMS)

    init {
        require(name.isSafeDisplayText()) { "shoot display name must be safe display text" }
        require(this.references.size <= 20) { "shoot display references exceed the bounded maximum" }
        require(
            this.references.map(ShootEditorReferenceItem::poseId).distinct().size ==
                this.references.size,
        ) {
            "shoot display reference IDs must be unique"
        }
        require(this.references.indices.all { index -> this.references[index].poseIndex == index }) {
            "shoot display reference indices must be unique and contiguous"
        }
        require(lifecycle != ShootPreparationLifecycle.DELETING || !hasResumableSession) {
            "deleting shoot cannot expose a resumable-session hint"
        }
    }

    fun withoutResumableSession(): ShootEditorDisplaySnapshot = ShootEditorDisplaySnapshot(
        name = name,
        lifecycle = lifecycle,
        references = references,
        importWorkStatuses = importWorkStatuses,
        hasResumableSession = false,
    )

    override fun toString(): String = "ShootEditorDisplaySnapshot(redacted)"
}

internal abstract class ShootEditorPickerLaunch internal constructor() {
    final override fun toString(): String = "ShootEditorPickerLaunch(redacted)"
}

internal sealed interface ShootEditorImportAllocationOutcome {
    class Ready(val launch: ShootEditorPickerLaunch) : ShootEditorImportAllocationOutcome {
        override fun toString(): String =
            "ShootEditorImportAllocationOutcome.Ready(redacted)"
    }

    class Blocked(
        val reason: ReferenceImportAllocationBlockReason,
        val retryAction: ReferenceImportRetryAction,
    ) : ShootEditorImportAllocationOutcome {
        override fun toString(): String =
            "ShootEditorImportAllocationOutcome.Blocked(" +
                "reason=${reason.name}, retryAction=${retryAction.name}, redacted)"
    }
}

/** UI-safe application boundary. Identity and time allocation remain behind this port. */
internal interface ShootEditorWorkflowPort {
    fun observeEditorSnapshot(shootId: String): Flow<ShootEditorDisplaySnapshot?>

    suspend fun allocateImport(shootId: String, label: String): ShootEditorImportAllocationOutcome

    fun classifyPickerResult(result: ReferencePickerResult): ReferenceImportOutcome

    suspend fun reorder(shootId: String, orderedPoseIds: List<String>): ShootReorderResult

    suspend fun start(shootId: String): ShootEditorStartOutcome

    suspend fun resume(shootId: String): ShootEditorResumeOutcome
}

internal class StartedSessionHandle(val navigationKey: String) {
    init {
        require(navigationKey.isSafeOpaqueIdentity()) {
            "started session handle must be a safe opaque identity"
        }
    }

    override fun toString(): String = "StartedSessionHandle(redacted)"
}

internal enum class ShootEditorStartRejectionReason {
    INVALID_REQUEST,
    UNKNOWN_SHOOT,
    SHOOT_DELETING,
    INELIGIBLE_PLAYLIST,
    UNRESOLVED_IMPORT_WORK,
    ACTIVE_SESSION_CONFLICT,
    SESSION_IDENTITY_CONFLICT,
    STALE_OR_CONFLICTING_REPLAY,
    AUTHORITY_INCONSISTENT,
    AUTHORITY_UNAVAILABLE,
}

internal sealed interface ShootEditorStartOutcome {
    class Started(val handle: StartedSessionHandle) : ShootEditorStartOutcome {
        override fun toString(): String = "ShootEditorStartOutcome.Started(redacted)"
    }

    class Resumable(val handle: StartedSessionHandle) : ShootEditorStartOutcome {
        override fun toString(): String = "ShootEditorStartOutcome.Resumable(redacted)"
    }

    class Rejected(val reason: ShootEditorStartRejectionReason) : ShootEditorStartOutcome {
        override fun toString(): String =
            "ShootEditorStartOutcome.Rejected(reason=${reason.name})"
    }
}

internal enum class ShootEditorResumeRejectionReason {
    INVALID_REQUEST,
    AUTHORITY_INCONSISTENT,
    AUTHORITY_UNAVAILABLE,
}

internal sealed interface ShootEditorResumeOutcome {
    class Resumable(val handle: StartedSessionHandle) : ShootEditorResumeOutcome {
        override fun toString(): String = "ShootEditorResumeOutcome.Resumable(redacted)"
    }

    data object Stale : ShootEditorResumeOutcome {
        override fun toString(): String = "ShootEditorResumeOutcome.Stale"
    }

    class Rejected(val reason: ShootEditorResumeRejectionReason) : ShootEditorResumeOutcome {
        override fun toString(): String =
            "ShootEditorResumeOutcome.Rejected(reason=${reason.name})"
    }
}

internal data class ShootEditorOperationId(
    val shootId: String,
    val generation: Long,
) {
    init {
        require(shootId.isSafeOpaqueIdentity()) { "operation shoot ID must be safe" }
        require(generation > 0L) { "operation generation must be positive" }
    }

    override fun toString(): String = "ShootEditorOperationId(redacted)"
}

internal enum class ShootEditorStartEligibility {
    ELIGIBLE,
    TOO_FEW_REFERENCES,
    SHOOT_DELETING,
    UNRESOLVED_IMPORT_WORK,
    ACTIVE_SESSION,
    OPERATION_IN_PROGRESS,
    UNAVAILABLE,
}

internal enum class ShootEditorFeedbackCode {
    SOURCE_UNAVAILABLE,
    IMPORT_ALLOCATION_BLOCKED,
    IMPORT_CANCELLED,
    IMPORT_INVALID_SELECTION,
    IMPORT_SUCCEEDED,
    IMPORT_VALIDATION_REJECTED,
    IMPORT_TERMINAL_REJECTED,
    IMPORT_RETRYABLE_FAILURE,
    RECONCILIATION_REQUIRED,
    REORDER_SAVED,
    REORDER_UNCHANGED,
    REORDER_INVALID,
    REORDER_BLOCKED,
    REORDER_FAILED,
    START_INELIGIBLE,
    START_CONFLICT,
    START_FAILED,
    RESUME_STALE,
    RESUME_FAILED,
}

internal class ShootEditorFeedback(
    val code: ShootEditorFeedbackCode,
    val retryAction: ReferenceImportRetryAction = ReferenceImportRetryAction.NONE,
    val allocationBlockReason: ReferenceImportAllocationBlockReason? = null,
) {
    override fun toString(): String =
        "ShootEditorFeedback(code=${code.name}, retryAction=${retryAction.name}, redacted)"
}

internal class ShootEditorLoadedData(
    val snapshot: ShootEditorDisplaySnapshot,
    val feedback: ShootEditorFeedback? = null,
    val localReconciliationRequired: Boolean = false,
) {
    fun withSnapshot(snapshot: ShootEditorDisplaySnapshot): ShootEditorLoadedData =
        ShootEditorLoadedData(
            snapshot = snapshot,
            feedback = feedback,
            localReconciliationRequired =
                localReconciliationRequired && snapshot.hasBlockingImportWork(),
        )

    fun withFeedback(
        feedback: ShootEditorFeedback?,
        localReconciliationRequired: Boolean = this.localReconciliationRequired,
    ): ShootEditorLoadedData =
        ShootEditorLoadedData(snapshot, feedback, localReconciliationRequired)

    override fun toString(): String = "ShootEditorLoadedData(redacted)"
}

internal sealed interface ShootEditorUiState {
    val startEligibility: ShootEditorStartEligibility

    data object Loading : ShootEditorUiState {
        override val startEligibility: ShootEditorStartEligibility =
            ShootEditorStartEligibility.UNAVAILABLE
        override fun toString(): String = "ShootEditorUiState.Loading(redacted)"
    }

    data object Missing : ShootEditorUiState {
        override val startEligibility: ShootEditorStartEligibility =
            ShootEditorStartEligibility.UNAVAILABLE
        override fun toString(): String = "ShootEditorUiState.Missing(redacted)"
    }

    class Unavailable(val canRetry: Boolean = true) : ShootEditorUiState {
        override val startEligibility: ShootEditorStartEligibility =
            ShootEditorStartEligibility.UNAVAILABLE
        override fun toString(): String = "ShootEditorUiState.Unavailable(redacted)"
    }

    sealed interface Loaded : ShootEditorUiState {
        val data: ShootEditorLoadedData
    }

    class Empty(override val data: ShootEditorLoadedData) : Loaded {
        override val startEligibility: ShootEditorStartEligibility
            get() = data.idleStartEligibility()
        override fun toString(): String = "ShootEditorUiState.Empty(redacted)"
    }

    class Content(override val data: ShootEditorLoadedData) : Loaded {
        override val startEligibility: ShootEditorStartEligibility
            get() = data.idleStartEligibility()
        override fun toString(): String = "ShootEditorUiState.Content(redacted)"
    }

    class AllocatingImport(
        override val data: ShootEditorLoadedData,
        val operationId: ShootEditorOperationId,
    ) : Loaded {
        override val startEligibility: ShootEditorStartEligibility =
            ShootEditorStartEligibility.OPERATION_IN_PROGRESS
        override fun toString(): String = "ShootEditorUiState.AllocatingImport(redacted)"
    }

    class Importing(
        override val data: ShootEditorLoadedData,
        val operationId: ShootEditorOperationId,
    ) : Loaded {
        override val startEligibility: ShootEditorStartEligibility =
            ShootEditorStartEligibility.OPERATION_IN_PROGRESS
        override fun toString(): String = "ShootEditorUiState.Importing(redacted)"
    }

    class Reordering(
        override val data: ShootEditorLoadedData,
        val operationId: ShootEditorOperationId,
    ) : Loaded {
        override val startEligibility: ShootEditorStartEligibility =
            ShootEditorStartEligibility.OPERATION_IN_PROGRESS
        override fun toString(): String = "ShootEditorUiState.Reordering(redacted)"
    }

    class Starting(
        override val data: ShootEditorLoadedData,
        val operationId: ShootEditorOperationId,
    ) : Loaded {
        override val startEligibility: ShootEditorStartEligibility =
            ShootEditorStartEligibility.OPERATION_IN_PROGRESS
        override fun toString(): String = "ShootEditorUiState.Starting(redacted)"
    }

    class Resuming(
        override val data: ShootEditorLoadedData,
        val operationId: ShootEditorOperationId,
    ) : Loaded {
        override val startEligibility: ShootEditorStartEligibility =
            ShootEditorStartEligibility.OPERATION_IN_PROGRESS
        override fun toString(): String = "ShootEditorUiState.Resuming(redacted)"
    }
}

internal enum class ShootEditorNavigationOrigin {
    FRESH_START,
    RESUME,
}

internal sealed interface ShootEditorEffect {
    class LaunchPhotoPicker(
        val operationId: ShootEditorOperationId,
        val launch: ShootEditorPickerLaunch,
    ) : ShootEditorEffect {
        override fun toString(): String = "ShootEditorEffect.LaunchPhotoPicker(redacted)"
    }

    class NavigateToStartedSession(
        val handle: StartedSessionHandle,
        val origin: ShootEditorNavigationOrigin,
    ) : ShootEditorEffect {
        override fun toString(): String =
            "ShootEditorEffect.NavigateToStartedSession(redacted)"
    }
}

internal class ShootEditorTransition(
    val state: ShootEditorUiState,
    effects: Iterable<ShootEditorEffect> = emptyList(),
) {
    val effects: List<ShootEditorEffect> =
        Collections.unmodifiableList(ArrayList<ShootEditorEffect>().apply { addAll(effects) })

    override fun toString(): String = "ShootEditorTransition(redacted)"
}

/** Pure reducer for authoritative snapshots and bounded editor operations. */
internal class ShootEditorReducer(private val shootId: String) {
    init {
        require(shootId.isSafeOpaqueIdentity()) { "editor shoot ID must be safe" }
    }

    fun snapshotChanged(
        current: ShootEditorUiState,
        snapshot: ShootEditorDisplaySnapshot?,
    ): ShootEditorTransition {
        if (snapshot == null) return ShootEditorTransition(ShootEditorUiState.Missing)

        val data = when (current) {
            is ShootEditorUiState.Loaded -> current.data.withSnapshot(snapshot)
            else -> ShootEditorLoadedData(snapshot)
        }
        val state = when (current) {
            is ShootEditorUiState.AllocatingImport ->
                ShootEditorUiState.AllocatingImport(data, current.operationId)
            is ShootEditorUiState.Importing -> ShootEditorUiState.Importing(data, current.operationId)
            is ShootEditorUiState.Reordering -> ShootEditorUiState.Reordering(data, current.operationId)
            is ShootEditorUiState.Starting -> ShootEditorUiState.Starting(data, current.operationId)
            is ShootEditorUiState.Resuming -> if (snapshot.hasResumableSession) {
                ShootEditorUiState.Resuming(data, current.operationId)
            } else {
                idleState(data)
            }
            else -> idleState(data)
        }
        return ShootEditorTransition(state)
    }

    fun observationFailed(current: ShootEditorUiState): ShootEditorTransition =
        ShootEditorTransition(ShootEditorUiState.Unavailable())

    fun beginImportAllocation(
        current: ShootEditorUiState,
        operationId: ShootEditorOperationId,
    ): ShootEditorTransition {
        val data = current.idleDataOrNull() ?: return noOp(current)
        if (!operationId.belongsTo(shootId)) return noOp(current)
        return ShootEditorTransition(ShootEditorUiState.AllocatingImport(data, operationId))
    }

    fun importAllocationCompleted(
        current: ShootEditorUiState,
        operationId: ShootEditorOperationId,
        result: ShootEditorImportAllocationOutcome,
    ): ShootEditorTransition {
        if (
            current !is ShootEditorUiState.AllocatingImport ||
            !operationId.belongsTo(shootId) ||
            current.operationId != operationId
        ) {
            return noOp(current)
        }
        return when (result) {
            is ShootEditorImportAllocationOutcome.Ready -> ShootEditorTransition(
                ShootEditorUiState.Importing(current.data, operationId),
                listOf(ShootEditorEffect.LaunchPhotoPicker(operationId, result.launch)),
            )
            is ShootEditorImportAllocationOutcome.Blocked -> {
                val feedback = ShootEditorFeedback(
                    code = ShootEditorFeedbackCode.IMPORT_ALLOCATION_BLOCKED,
                    retryAction = result.retryAction,
                    allocationBlockReason = result.reason,
                )
                ShootEditorTransition(idleState(current.data.withFeedback(feedback)))
            }
        }
    }

    fun pickerCompleted(
        current: ShootEditorUiState,
        operationId: ShootEditorOperationId,
        outcome: ReferenceImportOutcome,
    ): ShootEditorTransition {
        if (
            current !is ShootEditorUiState.Importing ||
            !operationId.belongsTo(shootId) ||
            current.operationId != operationId
        ) {
            return noOp(current)
        }
        val reconciliationRequired =
            outcome.retryAction == ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY ||
                outcome.status == ReferenceImportOutcomeStatus.RECONCILIATION_REQUIRED ||
                outcome.status == ReferenceImportOutcomeStatus.RESERVE_REJECTED_UNRESOLVED_IMPORT
        val feedbackCode = if (reconciliationRequired) {
            ShootEditorFeedbackCode.RECONCILIATION_REQUIRED
        } else when (outcome.status) {
            ReferenceImportOutcomeStatus.CANCELLED -> ShootEditorFeedbackCode.IMPORT_CANCELLED
            ReferenceImportOutcomeStatus.INVALID_SELECTION ->
                ShootEditorFeedbackCode.IMPORT_INVALID_SELECTION
            ReferenceImportOutcomeStatus.SUCCEEDED -> ShootEditorFeedbackCode.IMPORT_SUCCEEDED
            ReferenceImportOutcomeStatus.VALIDATION_REJECTED ->
                ShootEditorFeedbackCode.IMPORT_VALIDATION_REJECTED
            ReferenceImportOutcomeStatus.TERMINAL_REJECTED,
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_PLAYLIST_FULL,
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_UNKNOWN_SHOOT,
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_SHOOT_DELETING,
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_IDENTITY,
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_AUTHORITY,
            -> ShootEditorFeedbackCode.IMPORT_TERMINAL_REJECTED
            ReferenceImportOutcomeStatus.AUTHORITY_UNAVAILABLE,
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_ACTIVE_SESSION,
            -> ShootEditorFeedbackCode.IMPORT_RETRYABLE_FAILURE
            ReferenceImportOutcomeStatus.RECONCILIATION_REQUIRED,
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_UNRESOLVED_IMPORT,
            -> ShootEditorFeedbackCode.IMPORT_RETRYABLE_FAILURE
        }
        val feedback = ShootEditorFeedback(
            code = feedbackCode,
            retryAction = outcome.retryAction,
        )
        return ShootEditorTransition(
            idleState(current.data.withFeedback(feedback, reconciliationRequired)),
        )
    }

    fun beginReorder(
        current: ShootEditorUiState,
        operationId: ShootEditorOperationId,
        orderedPoseIds: List<String>,
    ): ShootEditorTransition {
        val data = current.idleDataOrNull() ?: return noOp(current)
        if (!operationId.belongsTo(shootId)) return noOp(current)

        val currentPoseIds = data.snapshot.references.map { reference -> reference.poseId }
        val orderIsComplete =
            orderedPoseIds.size == currentPoseIds.size &&
                orderedPoseIds.toSet().size == orderedPoseIds.size &&
                orderedPoseIds.toSet() == currentPoseIds.toSet()
        if (!orderIsComplete) {
            return ShootEditorTransition(
                idleState(
                    data.withFeedback(ShootEditorFeedback(ShootEditorFeedbackCode.REORDER_INVALID)),
                ),
            )
        }
        return ShootEditorTransition(ShootEditorUiState.Reordering(data, operationId))
    }

    fun reorderCompleted(
        current: ShootEditorUiState,
        operationId: ShootEditorOperationId,
        result: ShootReorderResult,
    ): ShootEditorTransition {
        if (
            current !is ShootEditorUiState.Reordering ||
            !operationId.belongsTo(shootId) ||
            current.operationId != operationId
        ) {
            return noOp(current)
        }
        val feedbackCode = when (result) {
            ShootReorderResult.Reordered -> ShootEditorFeedbackCode.REORDER_SAVED
            ShootReorderResult.AlreadyOrdered -> ShootEditorFeedbackCode.REORDER_UNCHANGED
            is ShootReorderResult.InvalidRequest -> ShootEditorFeedbackCode.REORDER_INVALID
            ShootReorderResult.ShootDeleting,
            ShootReorderResult.ActiveSession,
            ShootReorderResult.UnresolvedImportWork,
            -> ShootEditorFeedbackCode.REORDER_BLOCKED
            ShootReorderResult.UnknownShoot,
            ShootReorderResult.StaleTimestamp,
            ShootReorderResult.AuthorityInconsistent,
            -> ShootEditorFeedbackCode.REORDER_FAILED
        }
        return ShootEditorTransition(
            idleState(current.data.withFeedback(ShootEditorFeedback(feedbackCode))),
        )
    }

    fun beginStart(
        current: ShootEditorUiState,
        operationId: ShootEditorOperationId,
    ): ShootEditorTransition {
        val data = current.idleDataOrNull() ?: return noOp(current)
        if (!operationId.belongsTo(shootId)) return noOp(current)
        if (current.startEligibility != ShootEditorStartEligibility.ELIGIBLE) {
            return ShootEditorTransition(
                idleState(
                    data.withFeedback(ShootEditorFeedback(ShootEditorFeedbackCode.START_INELIGIBLE)),
                ),
            )
        }
        return ShootEditorTransition(ShootEditorUiState.Starting(data, operationId))
    }

    fun startCompleted(
        current: ShootEditorUiState,
        operationId: ShootEditorOperationId,
        outcome: ShootEditorStartOutcome,
    ): ShootEditorTransition {
        if (
            current !is ShootEditorUiState.Starting ||
            !operationId.belongsTo(shootId) ||
            current.operationId != operationId
        ) {
            return noOp(current)
        }
        return when (outcome) {
            is ShootEditorStartOutcome.Started -> ShootEditorTransition(
                idleState(current.data.withFeedback(null)),
                listOf(
                    ShootEditorEffect.NavigateToStartedSession(
                        handle = outcome.handle,
                        origin = ShootEditorNavigationOrigin.FRESH_START,
                    ),
                ),
            )
            is ShootEditorStartOutcome.Resumable -> ShootEditorTransition(
                idleState(current.data.withFeedback(null)),
                listOf(
                    ShootEditorEffect.NavigateToStartedSession(
                        handle = outcome.handle,
                        origin = ShootEditorNavigationOrigin.FRESH_START,
                    ),
                ),
            )
            is ShootEditorStartOutcome.Rejected -> {
                val feedbackCode = when (outcome.reason) {
                    ShootEditorStartRejectionReason.ACTIVE_SESSION_CONFLICT,
                    ShootEditorStartRejectionReason.SESSION_IDENTITY_CONFLICT,
                    ShootEditorStartRejectionReason.STALE_OR_CONFLICTING_REPLAY,
                    -> ShootEditorFeedbackCode.START_CONFLICT
                    else -> ShootEditorFeedbackCode.START_FAILED
                }
                ShootEditorTransition(
                    idleState(current.data.withFeedback(ShootEditorFeedback(feedbackCode))),
                )
            }
        }
    }

    fun resumeCompleted(
        current: ShootEditorUiState,
        operationId: ShootEditorOperationId,
        outcome: ShootEditorResumeOutcome,
    ): ShootEditorTransition {
        if (current !is ShootEditorUiState.Resuming ||
            !operationId.belongsTo(shootId) || current.operationId != operationId
        ) return noOp(current)
        return when (outcome) {
            is ShootEditorResumeOutcome.Resumable -> ShootEditorTransition(
                idleState(current.data.withFeedback(null)),
                listOf(
                    ShootEditorEffect.NavigateToStartedSession(
                        handle = outcome.handle,
                        origin = ShootEditorNavigationOrigin.RESUME,
                    ),
                ),
            )
            ShootEditorResumeOutcome.Stale -> ShootEditorTransition(
                idleState(
                    ShootEditorLoadedData(
                        current.data.snapshot.withoutResumableSession(),
                        ShootEditorFeedback(ShootEditorFeedbackCode.RESUME_STALE),
                        current.data.localReconciliationRequired,
                    ),
                ),
            )
            is ShootEditorResumeOutcome.Rejected -> ShootEditorTransition(
                idleState(current.data.withFeedback(ShootEditorFeedback(ShootEditorFeedbackCode.RESUME_FAILED))),
            )
        }
    }

    fun beginResume(
        current: ShootEditorUiState,
        operationId: ShootEditorOperationId,
    ): ShootEditorTransition {
        val data = current.idleDataOrNull() ?: return noOp(current)
        if (!operationId.belongsTo(shootId)) return noOp(current)
        return if (data.snapshot.hasResumableSession) {
            ShootEditorTransition(ShootEditorUiState.Resuming(data, operationId))
        } else {
            ShootEditorTransition(
                idleState(data.withFeedback(ShootEditorFeedback(ShootEditorFeedbackCode.RESUME_STALE))),
            )
        }
    }

    override fun toString(): String = "ShootEditorReducer(redacted)"

    private fun noOp(current: ShootEditorUiState): ShootEditorTransition =
        ShootEditorTransition(current)

    private fun ShootEditorOperationId.belongsTo(expectedShootId: String): Boolean =
        shootId == expectedShootId

    private fun ShootEditorUiState.idleDataOrNull(): ShootEditorLoadedData? = when (this) {
        is ShootEditorUiState.Empty -> data
        is ShootEditorUiState.Content -> data
        else -> null
    }

    private fun idleState(data: ShootEditorLoadedData): ShootEditorUiState =
        if (data.snapshot.references.isEmpty()) {
            ShootEditorUiState.Empty(data)
        } else {
            ShootEditorUiState.Content(data)
        }
}

internal fun classifyPickerResultSafely(
    classify: () -> ReferenceImportOutcome,
): ReferenceImportOutcome = try {
    classify()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    ReferenceImportOutcome(
        ReferenceImportOutcomeStatus.AUTHORITY_UNAVAILABLE,
        ReferenceImportRetryAction.RETRY_ALLOCATION,
    )
}

internal open class ShootEditorViewModel(
    private val shootId: String,
    private val workflow: ShootEditorWorkflowPort,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    closeAuthority: () -> Unit = {},
) : ViewModel() {
    private val reducer = ShootEditorReducer(shootId)
    private val generations = AtomicLong(0L)
    private val transitionLock = Any()
    private val _state = MutableStateFlow<ShootEditorUiState>(ShootEditorUiState.Loading)
    private val effectChannel = Channel<ShootEditorEffectEnvelope>(capacity = EFFECT_CAPACITY)
    private var observationJob: Job? = null
    private var observationGeneration = 0L
    private var observationGenerationExhausted = false
    private var nextEffectToken = 0L
    private var pendingEffectToken: Long? = null
    private var pendingEffect: ShootEditorEffect? = null
    private var cleared = false
    private var closeAuthority: (() -> Unit)? = closeAuthority

    val state: StateFlow<ShootEditorUiState> = _state.asStateFlow()
    val effects: Flow<ShootEditorEffect> = effectChannel.receiveAsFlow().mapNotNull { envelope ->
        synchronized(transitionLock) {
            if (
                pendingEffectToken == envelope.token &&
                envelope.effect.isStillValidFor(_state.value)
            ) {
                pendingEffectToken = null
                pendingEffect = null
                envelope.effect
            } else {
                if (pendingEffectToken == envelope.token) {
                    invalidatePendingEffect()
                }
                null
            }
        }
    }

    init {
        observe()
    }

    fun retryObservation() {
        observe()
    }

    fun requestImport(label: String) {
        val operationId = nextOperationId() ?: return
        viewModelScope.launch(dispatcher) {
            val begun = applyTransition { current ->
                reducer.beginImportAllocation(current, operationId)
            }
            if (
                begun !is ShootEditorUiState.AllocatingImport ||
                begun.operationId != operationId
            ) {
                return@launch
            }
            val result = try {
                workflow.allocateImport(shootId, label)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ShootEditorImportAllocationOutcome.Blocked(
                    ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                    ReferenceImportRetryAction.RETRY_ALLOCATION,
                )
            }
            applyTransition { current ->
                reducer.importAllocationCompleted(current, operationId, result)
            }
        }
    }

    fun onReferencePickerResult(
        operationId: ShootEditorOperationId,
        result: ReferencePickerResult,
    ) {
        viewModelScope.launch(dispatcher) {
            val importing = synchronized(transitionLock) { _state.value }
            if (
                importing !is ShootEditorUiState.Importing ||
                importing.operationId != operationId ||
                operationId.shootId != shootId
            ) {
                return@launch
            }
            val outcome = classifyPickerResultSafely {
                workflow.classifyPickerResult(result)
            }
            applyTransition { current ->
                reducer.pickerCompleted(current, operationId, outcome)
            }
        }
    }

    fun requestReorder(orderedPoseIds: List<String>) {
        val immutableOrder = Collections.unmodifiableList(ArrayList(orderedPoseIds))
        val operationId = nextOperationId() ?: return
        viewModelScope.launch(dispatcher) {
            val begun = applyTransition { current ->
                reducer.beginReorder(current, operationId, immutableOrder)
            }
            if (begun !is ShootEditorUiState.Reordering || begun.operationId != operationId) {
                return@launch
            }
            val result = try {
                workflow.reorder(shootId, immutableOrder)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ShootReorderResult.AuthorityInconsistent
            }
            applyTransition { current ->
                reducer.reorderCompleted(current, operationId, result)
            }
        }
    }

    fun requestStart() {
        val operationId = nextOperationId() ?: return
        viewModelScope.launch(dispatcher) {
            val begun = applyTransition { current -> reducer.beginStart(current, operationId) }
            if (begun !is ShootEditorUiState.Starting || begun.operationId != operationId) {
                return@launch
            }
            val outcome = try {
                workflow.start(shootId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ShootEditorStartOutcome.Rejected(
                    ShootEditorStartRejectionReason.AUTHORITY_UNAVAILABLE,
                )
            }
            applyTransition { current -> reducer.startCompleted(current, operationId, outcome) }
        }
    }

    fun requestResume() {
        val operationId = nextOperationId() ?: return
        viewModelScope.launch(dispatcher) {
            val begun = applyTransition { current -> reducer.beginResume(current, operationId) }
            if (begun !is ShootEditorUiState.Resuming || begun.operationId != operationId) return@launch
            val outcome = try {
                workflow.resume(shootId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ShootEditorResumeOutcome.Rejected(ShootEditorResumeRejectionReason.AUTHORITY_UNAVAILABLE)
            }
            applyTransition { current -> reducer.resumeCompleted(current, operationId, outcome) }
        }
    }

    override fun toString(): String = "ShootEditorViewModel(redacted)"

    override fun onCleared() {
        val close = synchronized(transitionLock) {
            if (cleared) {
                null
            } else {
                cleared = true
                observationJob?.cancel()
                observationJob = null
                invalidatePendingEffect()
                closeAuthority.also { closeAuthority = null }
            }
        }
        viewModelScope.cancel()
        try {
            close?.invoke()
        } finally {
            super.onCleared()
        }
    }

    private fun observe() {
        val nextJob = synchronized(transitionLock) {
            if (cleared) return
            observationJob?.cancel()
            invalidatePendingEffect()
            if (observationGenerationExhausted || observationGeneration == Long.MAX_VALUE) {
                observationGenerationExhausted = true
                observationJob = null
                _state.value = ShootEditorUiState.Unavailable()
                null
            } else {
                observationGeneration += 1L
                val generation = observationGeneration
                _state.value = ShootEditorUiState.Loading
                viewModelScope.launch(dispatcher, start = CoroutineStart.LAZY) {
                    val source = try {
                        workflow.observeEditorSnapshot(shootId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        applyObservationTransition(generation, reducer::observationFailed)
                        return@launch
                    }
                    source
                        .catch { error ->
                            if (error is CancellationException) throw error
                            applyObservationTransition(generation, reducer::observationFailed)
                        }
                        .collect { snapshot ->
                            applyObservationTransition(generation) { current ->
                                reducer.snapshotChanged(current, snapshot)
                            }
                        }
                }.also { job -> observationJob = job }
            }
        } ?: return
        nextJob.start()
    }

    private suspend fun applyObservationTransition(
        generation: Long,
        reduce: (ShootEditorUiState) -> ShootEditorTransition,
    ): ShootEditorUiState = synchronized(transitionLock) {
        if (cleared || observationGenerationExhausted || observationGeneration != generation) {
            return _state.value
        }
        applyTransitionLocked(reduce)
    }

    private suspend fun applyTransition(
        reduce: (ShootEditorUiState) -> ShootEditorTransition,
    ): ShootEditorUiState = synchronized(transitionLock) {
        applyTransitionLocked(reduce)
    }

    private fun applyTransitionLocked(
        reduce: (ShootEditorUiState) -> ShootEditorTransition,
    ): ShootEditorUiState {
        if (cleared) return _state.value
        val transition = reduce(_state.value)
        if (transition.effects.size > 1) {
            invalidatePendingEffect()
            _state.value = ShootEditorUiState.Unavailable()
            return _state.value
        }

        val effect = transition.effects.singleOrNull()
        if (effect == null) {
            _state.value = transition.state
            if (pendingEffect?.isStillValidFor(transition.state) != true) {
                invalidatePendingEffect()
            }
            return transition.state
        }

        if (nextEffectToken == Long.MAX_VALUE) {
            invalidatePendingEffect()
            _state.value = ShootEditorUiState.Unavailable()
            return _state.value
        }
        val token = nextEffectToken + 1L
        nextEffectToken = token
        pendingEffectToken = token
        pendingEffect = effect
        if (effectChannel.trySend(ShootEditorEffectEnvelope(token, effect)).isFailure) {
            invalidatePendingEffect()
            _state.value = ShootEditorUiState.Unavailable()
            return _state.value
        }
        _state.value = transition.state
        return _state.value
    }

    private fun invalidatePendingEffect() {
        pendingEffectToken = null
        pendingEffect = null
    }

    private fun nextOperationId(): ShootEditorOperationId? {
        if (synchronized(transitionLock) { cleared }) return null
        while (true) {
            val current = generations.get()
            if (current == Long.MAX_VALUE) return null
            val next = current + 1L
            if (generations.compareAndSet(current, next)) {
                return ShootEditorOperationId(shootId, next)
            }
        }
    }

    private companion object {
        const val EFFECT_CAPACITY = 8
    }
}

private class ShootEditorEffectEnvelope(
    val token: Long,
    val effect: ShootEditorEffect,
)

private fun ShootEditorEffect.isStillValidFor(state: ShootEditorUiState): Boolean = when (this) {
    is ShootEditorEffect.LaunchPhotoPicker ->
        state is ShootEditorUiState.Importing && state.operationId == operationId
    is ShootEditorEffect.NavigateToStartedSession -> {
        val data = when (state) {
            is ShootEditorUiState.Empty -> state.data
            is ShootEditorUiState.Content -> state.data
            else -> null
        }
        data != null && data.feedback == null && when (origin) {
            ShootEditorNavigationOrigin.FRESH_START -> true
            ShootEditorNavigationOrigin.RESUME -> data.snapshot.hasResumableSession
        }
    }
}

private fun ShootEditorLoadedData.idleStartEligibility(): ShootEditorStartEligibility = when {
    snapshot.lifecycle != ShootPreparationLifecycle.ACTIVE ->
        ShootEditorStartEligibility.SHOOT_DELETING
    localReconciliationRequired || snapshot.hasBlockingImportWork() ->
        ShootEditorStartEligibility.UNRESOLVED_IMPORT_WORK
    snapshot.hasResumableSession -> ShootEditorStartEligibility.ACTIVE_SESSION
    snapshot.references.size !in 3..20 ->
        ShootEditorStartEligibility.TOO_FEW_REFERENCES
    else -> ShootEditorStartEligibility.ELIGIBLE
}

private fun ShootEditorDisplaySnapshot.hasBlockingImportWork(): Boolean =
    importWorkStatuses.any { status ->
        status == ImportWorkStatus.IN_PROGRESS ||
            status == ImportWorkStatus.RECONCILIATION_REQUIRED
    }

private fun String.isSafeOpaqueIdentity(): Boolean =
    isNotEmpty() &&
        this != "." &&
        this != ".." &&
        all { character ->
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '_' ||
                character == '-' ||
                character == '.'
        }

private fun String.isSafeDisplayText(): Boolean =
    isNotBlank() &&
        length <= 200 &&
        !contains("content://", ignoreCase = true) &&
        none(Char::isISOControl)
