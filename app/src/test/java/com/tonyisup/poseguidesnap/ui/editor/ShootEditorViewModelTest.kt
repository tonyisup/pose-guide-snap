package com.tonyisup.poseguidesnap.ui.editor

import com.tonyisup.poseguidesnap.data.ImportWorkStatus
import com.tonyisup.poseguidesnap.data.ShootPreparationLifecycle
import com.tonyisup.poseguidesnap.data.ShootReorderInvalidReason
import com.tonyisup.poseguidesnap.data.ShootReorderResult
import com.tonyisup.poseguidesnap.importer.ReferenceImportAllocationBlockReason
import com.tonyisup.poseguidesnap.importer.ReferenceImportOutcome
import com.tonyisup.poseguidesnap.importer.ReferenceImportOutcomeStatus
import com.tonyisup.poseguidesnap.importer.ReferenceImportRetryAction
import com.tonyisup.poseguidesnap.importer.ReferencePickerResult
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShootEditorViewModelTest {
    private val pickerLaunch = TestPickerLaunch()

    @Test
    fun initialLoadingThenMissingEmptyAndContentSnapshotsAreDistinct() = runTest {
        val workflow = FakeWorkflow()
        val viewModel = ShootEditorViewModel(SHOOT_ID, workflow, StandardTestDispatcher(testScheduler))
        assertSame(ShootEditorUiState.Loading, viewModel.state.value)

        workflow.snapshots.emit(null)
        runCurrent()
        assertSame(ShootEditorUiState.Missing, viewModel.state.value)

        workflow.snapshots.emit(snapshot(referenceCount = 0))
        runCurrent()
        assertTrue(viewModel.state.value is ShootEditorUiState.Empty)
        assertEquals(
            ShootEditorStartEligibility.TOO_FEW_REFERENCES,
            viewModel.state.value.startEligibility,
        )

        workflow.snapshots.emit(snapshot(referenceCount = 3))
        runCurrent()
        assertTrue(viewModel.state.value is ShootEditorUiState.Content)
        assertEquals(ShootEditorStartEligibility.ELIGIBLE, viewModel.state.value.startEligibility)
    }

    @Test
    fun displayProjectionOwnsImmutableRedactedListsAndCallerCollectionsCannotMutateIt() {
        val references = mutableListOf(reference(0), reference(1), reference(2))
        val workStatuses = mutableListOf(ImportWorkStatus.IN_PROGRESS)
        val snapshot = ShootEditorDisplaySnapshot(
            name = "private-shoot-name",
            lifecycle = ShootPreparationLifecycle.ACTIVE,
            references = references,
            importWorkStatuses = workStatuses,
        )
        val reducer = ShootEditorReducer(SHOOT_ID)

        val state = reducer.snapshotChanged(ShootEditorUiState.Loading, snapshot).state
        references.clear()
        workStatuses.clear()

        val loaded = state as ShootEditorUiState.Content
        assertEquals(3, loaded.data.snapshot.references.size)
        assertEquals(listOf(ImportWorkStatus.IN_PROGRESS), loaded.data.snapshot.importWorkStatuses)
        assertThrows(UnsupportedOperationException::class.java) {
            (loaded.data.snapshot.references as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (loaded.data.snapshot.importWorkStatuses as MutableList).clear()
        }
        listOf(
            snapshot,
            snapshot.references,
            snapshot.importWorkStatuses,
            snapshot.references.first(),
        ).forEach { value ->
            val rendered = value.toString()
            assertTrue(
                "${value::class.java.simpleName} must redact toString",
                "redacted" in rendered,
            )
            assertFalse(rendered.contains("private-shoot-name"))
            assertFalse(rendered.contains("private-label-0"))
        }
        assertEquals(
            setOf("name", "lifecycle", "references", "importWorkStatuses"),
            ShootEditorDisplaySnapshot::class.java.declaredFields
                .filterNot { field -> Modifier.isStatic(field.modifiers) }
                .map { field -> field.name }
                .toSet(),
        )
        assertEquals(
            setOf("poseId", "poseIndex", "label", "mirrorAllowed"),
            ShootEditorReferenceItem::class.java.declaredFields
                .filterNot { field -> Modifier.isStatic(field.modifiers) }
                .map { field -> field.name }
                .toSet(),
        )
    }

    @Test
    fun displayProjectionRejectsMalformedAuthorityBeforeEligibilityOrReorderDerivation() {
        listOf(
            "bad\u0000name",
            "content://private/shoot",
            "   ",
        ).forEach { invalidName ->
            assertThrows(IllegalArgumentException::class.java) {
                ShootEditorDisplaySnapshot(
                    name = invalidName,
                    lifecycle = ShootPreparationLifecycle.ACTIVE,
                    references = emptyList(),
                    importWorkStatuses = emptyList(),
                )
            }
        }
        listOf("bad\u0000label", "content://private/reference", "   ").forEach { invalidLabel ->
            assertThrows(IllegalArgumentException::class.java) {
                ShootEditorReferenceItem("pose-safe", 0, invalidLabel, false)
            }
        }
        val duplicateIds = listOf(
            ShootEditorReferenceItem("pose-0", 0, "label-0", false),
            ShootEditorReferenceItem("pose-0", 1, "label-1", false),
        )
        val duplicateIndices = listOf(
            ShootEditorReferenceItem("pose-0", 0, "label-0", false),
            ShootEditorReferenceItem("pose-1", 0, "label-1", false),
        )
        val gappedIndices = listOf(
            ShootEditorReferenceItem("pose-0", 0, "label-0", false),
            ShootEditorReferenceItem("pose-1", 2, "label-1", false),
        )
        listOf(duplicateIds, duplicateIndices, gappedIndices, (0..20).map(::reference))
            .forEach { malformed ->
                assertThrows(IllegalArgumentException::class.java) {
                    ShootEditorDisplaySnapshot(
                        name = "safe name",
                        lifecycle = ShootPreparationLifecycle.ACTIVE,
                        references = malformed,
                        importWorkStatuses = emptyList(),
                    )
                }
            }

        var referenceReads = 0
        val oversizedReferences = Iterable {
            object : Iterator<ShootEditorReferenceItem> {
                override fun hasNext(): Boolean = referenceReads < 1_000
                override fun next(): ShootEditorReferenceItem = reference(referenceReads++)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShootEditorDisplaySnapshot(
                name = "safe name",
                lifecycle = ShootPreparationLifecycle.ACTIVE,
                references = oversizedReferences,
                importWorkStatuses = emptyList(),
            )
        }
        assertTrue("reference projection copy must stop at max + 1", referenceReads <= 21)

        var importWorkReads = 0
        val oversizedImportWork = Iterable {
            object : Iterator<ImportWorkStatus> {
                override fun hasNext(): Boolean = importWorkReads < 1_000
                override fun next(): ImportWorkStatus {
                    importWorkReads += 1
                    return ImportWorkStatus.IN_PROGRESS
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShootEditorDisplaySnapshot(
                name = "safe name",
                lifecycle = ShootPreparationLifecycle.ACTIVE,
                references = emptyList(),
                importWorkStatuses = oversizedImportWork,
            )
        }
        assertTrue("import-work projection copy must stop at max + 1", importWorkReads <= 21)
    }

    @Test
    fun pickerLaunchIsOpaqueAndItsRenderingIsFinalAndRedacted() {
        val type = ShootEditorPickerLaunch::class.java
        assertTrue(type.declaredFields.none { field -> !Modifier.isStatic(field.modifiers) })
        assertEquals(listOf("toString"), type.declaredMethods.map { it.name }.sorted())
        assertTrue(Modifier.isFinal(type.getDeclaredMethod("toString").modifiers))
        assertEquals("ShootEditorPickerLaunch(redacted)", pickerLaunch.toString())
        assertTrue(
            TestPickerLaunch::class.java.declaredFields.none { field ->
                !Modifier.isStatic(field.modifiers)
            },
        )
        assertTrue(TestPickerLaunch::class.java.declaredMethods.isEmpty())
        val ready = ShootEditorImportAllocationOutcome.Ready(pickerLaunch)
        assertEquals("ShootEditorImportAllocationOutcome.Ready(redacted)", ready.toString())
        assertSame(pickerLaunch, ready.launch)
        assertTrue(
            ShootEditorImportAllocationOutcome.Blocked(
                ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
            ).toString().contains("redacted"),
        )
    }

    @Test
    fun snapshotUpdatesPreserveEachActiveOperationAndRecomputeItsVisibleData() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        val initial = loadedState(reducer, snapshot(referenceCount = 3))
        val updated = snapshot(referenceCount = 4)
        val cases = listOf(
            reducer.beginImportAllocation(initial, operation(1)).state,
            ShootEditorUiState.Importing(data(initial), operation(2)),
            reducer.beginReorder(initial, operation(3), poseIds(3).reversed()).state,
            reducer.beginStart(initial, operation(4)).state,
        )

        cases.forEach { active ->
            val next = reducer.snapshotChanged(active, updated).state
            assertEquals(active::class, next::class)
            assertEquals(4, (next as ShootEditorUiState.Loaded).data.snapshot.references.size)
            assertEquals(ShootEditorStartEligibility.OPERATION_IN_PROGRESS, next.startEligibility)
        }
    }

    @Test
    fun startEligibilityRequiresThreeToTwentyActiveValidatedReferencesAndNoUnresolvedWork() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        listOf(0, 2).forEach { count ->
            assertEquals(
                ShootEditorStartEligibility.TOO_FEW_REFERENCES,
                loadedState(reducer, snapshot(referenceCount = count)).startEligibility,
            )
        }
        listOf(3, 20).forEach { count ->
            assertEquals(
                ShootEditorStartEligibility.ELIGIBLE,
                loadedState(reducer, snapshot(referenceCount = count)).startEligibility,
            )
        }
        assertEquals(
            ShootEditorStartEligibility.SHOOT_DELETING,
            loadedState(
                reducer,
                snapshot(referenceCount = 3, lifecycle = ShootPreparationLifecycle.DELETING),
            ).startEligibility,
        )
        listOf(
            ImportWorkStatus.IN_PROGRESS,
            ImportWorkStatus.RECONCILIATION_REQUIRED,
        ).forEach { status ->
            assertEquals(
                ShootEditorStartEligibility.UNRESOLVED_IMPORT_WORK,
                loadedState(reducer, snapshot(referenceCount = 3, workStatus = status)).startEligibility,
            )
        }
        assertEquals(
            ShootEditorStartEligibility.ELIGIBLE,
            loadedState(
                reducer,
                snapshot(
                    referenceCount = 3,
                    workStatus = ImportWorkStatus.REJECTED_QUARANTINED,
                ),
            ).startEligibility,
        )
    }

    @Test
    fun readyImportAllocationBecomesImportingAndEmitsExactlyOnePickerEffect() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        val operation = operation(11)
        val pending = reducer.beginImportAllocation(
            loadedState(reducer, snapshot(referenceCount = 2)),
            operation,
        ).state
        assertTrue(pending is ShootEditorUiState.AllocatingImport)

        val transition = reducer.importAllocationCompleted(
            pending,
            operation,
            ShootEditorImportAllocationOutcome.Ready(pickerLaunch),
        )

        assertTrue(transition.state is ShootEditorUiState.Importing)
        assertEquals(1, transition.effects.size)
        val effect = transition.effects.single() as ShootEditorEffect.LaunchPhotoPicker
        assertSame(operation, effect.operationId)
        assertSame(pickerLaunch, effect.launch)
    }

    @Test
    fun blockedImportAllocationPreservesBoundedReasonAndActionWithoutPickerEffect() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        ReferenceImportAllocationBlockReason.entries.forEachIndexed { index, reason ->
            val retry = ReferenceImportRetryAction.entries[index % ReferenceImportRetryAction.entries.size]
            val operation = operation(index + 1L)
            val pending = reducer.beginImportAllocation(
                loadedState(reducer, snapshot(referenceCount = 2)),
                operation,
            ).state

            val transition = reducer.importAllocationCompleted(
                pending,
                operation,
                ShootEditorImportAllocationOutcome.Blocked(reason, retry),
            )

            assertTrue(transition.effects.isEmpty())
            val feedback = data(transition.state).feedback!!
            assertEquals(ShootEditorFeedbackCode.IMPORT_ALLOCATION_BLOCKED, feedback.code)
            assertSame(reason, feedback.allocationBlockReason)
            assertSame(retry, feedback.retryAction)
        }
    }

    @Test
    fun pickerOutcomesMapEveryRequiredFamilyAndOnlyReconciliationKeepsALocalBarrier() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        val cases = listOf(
            PickerCase(
                ReferenceImportOutcomeStatus.CANCELLED,
                ReferenceImportRetryAction.NONE,
                ShootEditorFeedbackCode.IMPORT_CANCELLED,
                false,
            ),
            PickerCase(
                ReferenceImportOutcomeStatus.INVALID_SELECTION,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
                ShootEditorFeedbackCode.IMPORT_INVALID_SELECTION,
                false,
            ),
            PickerCase(
                ReferenceImportOutcomeStatus.SUCCEEDED,
                ReferenceImportRetryAction.NONE,
                ShootEditorFeedbackCode.IMPORT_SUCCEEDED,
                false,
            ),
            PickerCase(
                ReferenceImportOutcomeStatus.VALIDATION_REJECTED,
                ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT,
                ShootEditorFeedbackCode.IMPORT_VALIDATION_REJECTED,
                false,
            ),
            PickerCase(
                ReferenceImportOutcomeStatus.TERMINAL_REJECTED,
                ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT,
                ShootEditorFeedbackCode.IMPORT_TERMINAL_REJECTED,
                false,
            ),
            PickerCase(
                ReferenceImportOutcomeStatus.AUTHORITY_UNAVAILABLE,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
                ShootEditorFeedbackCode.IMPORT_RETRYABLE_FAILURE,
                false,
            ),
            PickerCase(
                ReferenceImportOutcomeStatus.RECONCILIATION_REQUIRED,
                ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
                ShootEditorFeedbackCode.RECONCILIATION_REQUIRED,
                true,
            ),
            PickerCase(
                ReferenceImportOutcomeStatus.RESERVE_REJECTED_UNRESOLVED_IMPORT,
                ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
                ShootEditorFeedbackCode.RECONCILIATION_REQUIRED,
                true,
            ),
            PickerCase(
                ReferenceImportOutcomeStatus.RESERVE_REJECTED_AUTHORITY,
                ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
                ShootEditorFeedbackCode.RECONCILIATION_REQUIRED,
                true,
            ),
            PickerCase(
                ReferenceImportOutcomeStatus.RESERVE_REJECTED_ACTIVE_SESSION,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
                ShootEditorFeedbackCode.IMPORT_RETRYABLE_FAILURE,
                false,
            ),
        )

        cases.forEachIndexed { index, case ->
            val operation = operation(index + 1L)
            val importing = ShootEditorUiState.Importing(
                data(loadedState(reducer, snapshot(referenceCount = 3))),
                operation,
            )
            val transition = reducer.pickerCompleted(
                importing,
                operation,
                ReferenceImportOutcome(case.status, case.retryAction),
            )

            assertTrue(transition.effects.isEmpty())
            val loaded = data(transition.state)
            assertSame(case.feedback, loaded.feedback!!.code)
            assertEquals(case.barrier, loaded.localReconciliationRequired)
            assertEquals(
                if (case.barrier) {
                    ShootEditorStartEligibility.UNRESOLVED_IMPORT_WORK
                } else {
                    ShootEditorStartEligibility.ELIGIBLE
                },
                transition.state.startEligibility,
            )
        }
    }

    @Test
    fun everyTerminalReserveRejectionMapsToBoundedNonNavigatingFeedback() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        val statuses = listOf(
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_PLAYLIST_FULL,
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_UNKNOWN_SHOOT,
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_SHOOT_DELETING,
            ReferenceImportOutcomeStatus.RESERVE_REJECTED_IDENTITY,
        )
        statuses.forEachIndexed { index, status ->
            val operation = operation(index + 1L)
            val importing = ShootEditorUiState.Importing(
                data(loadedState(reducer, snapshot(referenceCount = 2))),
                operation,
            )
            val transition = reducer.pickerCompleted(
                importing,
                operation,
                ReferenceImportOutcome(status, ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT),
            )
            assertTrue(transition.effects.isEmpty())
            assertEquals(
                ShootEditorFeedbackCode.IMPORT_TERMINAL_REJECTED,
                data(transition.state).feedback!!.code,
            )
        }
    }

    @Test
    fun authoritativeSnapshotClearsTemporaryLocalReconciliationBarrierAfterResolution() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        val operation = operation(1)
        val importing = ShootEditorUiState.Importing(
            data(loadedState(reducer, snapshot(referenceCount = 3))),
            operation,
        )
        val blocked = reducer.pickerCompleted(
            importing,
            operation,
            ReferenceImportOutcome(
                ReferenceImportOutcomeStatus.RECONCILIATION_REQUIRED,
                ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
            ),
        ).state
        assertEquals(
            ShootEditorStartEligibility.UNRESOLVED_IMPORT_WORK,
            blocked.startEligibility,
        )

        val resolved = reducer.snapshotChanged(blocked, snapshot(referenceCount = 3)).state

        assertEquals(ShootEditorStartEligibility.ELIGIBLE, resolved.startEligibility)
        assertFalse(data(resolved).localReconciliationRequired)
    }

    @Test
    fun validCompleteReorderBecomesPendingAndResultsMapSuccessReplayInvalidFailureAndBarrier() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        val base = loadedState(reducer, snapshot(referenceCount = 3))
        val cases = listOf(
            ReorderCase(ShootReorderResult.Reordered, ShootEditorFeedbackCode.REORDER_SAVED),
            ReorderCase(ShootReorderResult.AlreadyOrdered, ShootEditorFeedbackCode.REORDER_UNCHANGED),
            ReorderCase(
                ShootReorderResult.InvalidRequest(ShootReorderInvalidReason.ORDER_MISMATCH),
                ShootEditorFeedbackCode.REORDER_INVALID,
            ),
            ReorderCase(ShootReorderResult.ShootDeleting, ShootEditorFeedbackCode.REORDER_BLOCKED),
            ReorderCase(ShootReorderResult.ActiveSession, ShootEditorFeedbackCode.REORDER_BLOCKED),
            ReorderCase(ShootReorderResult.UnresolvedImportWork, ShootEditorFeedbackCode.REORDER_BLOCKED),
            ReorderCase(ShootReorderResult.UnknownShoot, ShootEditorFeedbackCode.REORDER_FAILED),
            ReorderCase(ShootReorderResult.StaleTimestamp, ShootEditorFeedbackCode.REORDER_FAILED),
            ReorderCase(ShootReorderResult.AuthorityInconsistent, ShootEditorFeedbackCode.REORDER_FAILED),
        )

        cases.forEachIndexed { index, case ->
            val operation = operation(index + 1L)
            val pending = reducer.beginReorder(base, operation, poseIds(3).reversed()).state
            assertTrue(pending is ShootEditorUiState.Reordering)
            val transition = reducer.reorderCompleted(pending, operation, case.result)
            assertTrue(transition.effects.isEmpty())
            assertSame(case.feedback, data(transition.state).feedback!!.code)
        }
    }

    @Test
    fun incompleteDuplicateForeignOrBusyReorderFailsBeforePending() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        val base = loadedState(reducer, snapshot(referenceCount = 3))
        listOf(
            listOf(POSE_PREFIX + 0, POSE_PREFIX + 1),
            listOf(POSE_PREFIX + 0, POSE_PREFIX + 0, POSE_PREFIX + 2),
            listOf(POSE_PREFIX + 0, POSE_PREFIX + 1, "pose-foreign"),
        ).forEachIndexed { index, order ->
            val transition = reducer.beginReorder(base, operation(index + 1L), order)
            assertFalse(transition.state is ShootEditorUiState.Reordering)
            assertEquals(ShootEditorFeedbackCode.REORDER_INVALID, data(transition.state).feedback!!.code)
        }
        val busy = reducer.beginImportAllocation(base, operation(20)).state
        assertSame(busy, reducer.beginReorder(busy, operation(21), poseIds(3).reversed()).state)
    }

    @Test
    fun startIsDisabledForEveryIneligibleFamilyAndEligibleStartBecomesPending() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        val ineligible = listOf(
            snapshot(referenceCount = 2),
            snapshot(referenceCount = 3, lifecycle = ShootPreparationLifecycle.DELETING),
            snapshot(referenceCount = 3, workStatus = ImportWorkStatus.IN_PROGRESS),
            snapshot(referenceCount = 3, workStatus = ImportWorkStatus.RECONCILIATION_REQUIRED),
        )
        ineligible.forEachIndexed { index, snapshot ->
            val transition = reducer.beginStart(
                loadedState(reducer, snapshot),
                operation(index + 1L),
            )
            assertFalse(transition.state is ShootEditorUiState.Starting)
            assertEquals(ShootEditorFeedbackCode.START_INELIGIBLE, data(transition.state).feedback!!.code)
        }

        val pending = reducer.beginStart(
            loadedState(reducer, snapshot(referenceCount = 3)),
            operation(10),
        ).state
        assertTrue(pending is ShootEditorUiState.Starting)
    }

    @Test
    fun startedAndResumableOutcomesEachEmitOneNavigationWhileRejectionsRemainRecoverable() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        listOf<(StartedSessionHandle) -> ShootEditorStartOutcome>(
            ShootEditorStartOutcome::Started,
            ShootEditorStartOutcome::Resumable,
        ).forEachIndexed { index, factory ->
            val operation = operation(index + 1L)
            val pending = reducer.beginStart(
                loadedState(reducer, snapshot(referenceCount = 3)),
                operation,
            ).state
            val handle = StartedSessionHandle("navigation-secret-$index")
            val transition = reducer.startCompleted(pending, operation, factory(handle))
            assertEquals(1, transition.effects.size)
            val effect = transition.effects.single() as ShootEditorEffect.NavigateToStartedSession
            assertSame(handle, effect.handle)
            assertFalse(transition.state is ShootEditorUiState.Starting)
        }

        ShootEditorStartRejectionReason.entries.forEachIndexed { index, reason ->
            val operation = operation(index + 20L)
            val pending = reducer.beginStart(
                loadedState(reducer, snapshot(referenceCount = 3)),
                operation,
            ).state
            val transition = reducer.startCompleted(
                pending,
                operation,
                ShootEditorStartOutcome.Rejected(reason),
            )
            assertTrue(transition.effects.isEmpty())
            assertEquals(
                if (
                    reason == ShootEditorStartRejectionReason.ACTIVE_SESSION_CONFLICT ||
                    reason == ShootEditorStartRejectionReason.SESSION_IDENTITY_CONFLICT ||
                    reason == ShootEditorStartRejectionReason.STALE_OR_CONFLICTING_REPLAY
                ) {
                    ShootEditorFeedbackCode.START_CONFLICT
                } else {
                    ShootEditorFeedbackCode.START_FAILED
                },
                data(transition.state).feedback!!.code,
            )
        }
    }

    @Test
    fun staleGenerationAndPriorShootCompletionsAreIdentityNoOpsAcrossAllOperations() {
        val reducer = ShootEditorReducer(SHOOT_ID)
        val loaded = loadedState(reducer, snapshot(referenceCount = 3))
        val currentOperation = operation(9)
        val stale = listOf(operation(8), operation(9, "shoot-prior-secret"))

        stale.forEach { staleOperation ->
            val allocating = reducer.beginImportAllocation(loaded, currentOperation).state
            assertIdentityNoOp(
                allocating,
                reducer.importAllocationCompleted(
                    allocating,
                    staleOperation,
                    ShootEditorImportAllocationOutcome.Ready(pickerLaunch),
                ),
            )
            val importing = ShootEditorUiState.Importing(data(loaded), currentOperation)
            assertIdentityNoOp(
                importing,
                reducer.pickerCompleted(
                    importing,
                    staleOperation,
                    ReferenceImportOutcome(
                        ReferenceImportOutcomeStatus.SUCCEEDED,
                        ReferenceImportRetryAction.NONE,
                    ),
                ),
            )
            val reordering = reducer.beginReorder(
                loaded,
                currentOperation,
                poseIds(3).reversed(),
            ).state
            assertIdentityNoOp(
                reordering,
                reducer.reorderCompleted(reordering, staleOperation, ShootReorderResult.Reordered),
            )
            val starting = reducer.beginStart(loaded, currentOperation).state
            assertIdentityNoOp(
                starting,
                reducer.startCompleted(
                    starting,
                    staleOperation,
                    ShootEditorStartOutcome.Started(StartedSessionHandle("stale-navigation")),
                ),
            )
        }
    }

    @Test
    fun viewModelCallsUiSafePortAndDeliversPickerEffectOnceWithoutReplay() = runTest {
        val workflow = FakeWorkflow().apply {
            snapshots.emit(snapshot(referenceCount = 2))
            allocationResult = ShootEditorImportAllocationOutcome.Ready(pickerLaunch)
            classifiedOutcome = ReferenceImportOutcome(
                ReferenceImportOutcomeStatus.SUCCEEDED,
                ReferenceImportRetryAction.NONE,
            )
        }
        val viewModel = ShootEditorViewModel(SHOOT_ID, workflow, StandardTestDispatcher(testScheduler))
        runCurrent()
        val firstEffect = async { viewModel.effects.first() }

        viewModel.requestImport(LABEL)
        runCurrent()

        val picker = firstEffect.await() as ShootEditorEffect.LaunchPhotoPicker
        assertEquals(1, workflow.allocateCalls)
        assertEquals(SHOOT_ID, workflow.lastAllocateShootId)
        assertEquals(LABEL, workflow.lastLabel)
        assertTrue(viewModel.state.value is ShootEditorUiState.Importing)

        viewModel.onReferencePickerResult(picker.operationId, ReferencePickerResult.Cancelled)
        runCurrent()
        assertEquals(1, workflow.classifyCalls)
        assertEquals(ShootEditorFeedbackCode.IMPORT_SUCCEEDED, data(viewModel.state.value).feedback!!.code)

        val secondEffect = async { viewModel.effects.first() }
        runCurrent()
        assertFalse(secondEffect.isCompleted)
        secondEffect.cancel()
    }

    @Test
    fun stalePickerCallbackIsSuppressedBeforeClassificationOrEffects() = runTest {
        val workflow = FakeWorkflow().apply {
            snapshots.emit(snapshot(referenceCount = 2))
            allocationResult = ShootEditorImportAllocationOutcome.Ready(pickerLaunch)
        }
        val viewModel = ShootEditorViewModel(SHOOT_ID, workflow, StandardTestDispatcher(testScheduler))
        runCurrent()
        val effect = async { viewModel.effects.first() }
        viewModel.requestImport(LABEL)
        runCurrent()
        val picker = effect.await() as ShootEditorEffect.LaunchPhotoPicker

        viewModel.onReferencePickerResult(
            picker.operationId.copy(generation = picker.operationId.generation + 1L),
            ReferencePickerResult.Cancelled,
        )
        runCurrent()

        assertEquals(0, workflow.classifyCalls)
        assertTrue(viewModel.state.value is ShootEditorUiState.Importing)
    }

    @Test
    fun viewModelReorderAndStartUseOnlyRawShootAndOrderedPoseValues() = runTest {
        val workflow = FakeWorkflow().apply {
            snapshots.emit(snapshot(referenceCount = 3))
            reorderResult = ShootReorderResult.Reordered
            startOutcome = ShootEditorStartOutcome.Resumable(StartedSessionHandle("resume-secret"))
        }
        val viewModel = ShootEditorViewModel(SHOOT_ID, workflow, StandardTestDispatcher(testScheduler))
        runCurrent()

        viewModel.requestReorder(poseIds(3).reversed())
        runCurrent()
        assertEquals(1, workflow.reorderCalls)
        assertEquals(poseIds(3).reversed(), workflow.lastOrder)
        assertEquals(ShootEditorFeedbackCode.REORDER_SAVED, data(viewModel.state.value).feedback!!.code)

        val navigation = async { viewModel.effects.first() }
        viewModel.requestStart()
        runCurrent()
        assertEquals(1, workflow.startCalls)
        assertEquals(SHOOT_ID, workflow.lastStartShootId)
        assertTrue(navigation.await() is ShootEditorEffect.NavigateToStartedSession)
    }

    @Test
    fun sourceAndOperationExceptionsBecomeGenericRetryableStatesWithoutRawLeakage() = runTest {
        val marker = "raw-exception-secret content://private/path"
        val sourceFailure = FakeWorkflow().apply {
            snapshotFlow = flow { error(marker) }
        }
        val sourceViewModel = ShootEditorViewModel(
            SHOOT_ID,
            sourceFailure,
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertTrue(sourceViewModel.state.value is ShootEditorUiState.Unavailable)
        assertFalse(sourceViewModel.state.value.toString().contains(marker))

        val workflow = FakeWorkflow().apply {
            snapshots.emit(snapshot(referenceCount = 3))
            allocateFailure = IllegalStateException(marker)
        }
        val viewModel = ShootEditorViewModel(SHOOT_ID, workflow, StandardTestDispatcher(testScheduler))
        runCurrent()
        viewModel.requestImport(LABEL)
        runCurrent()
        val allocationFeedback = data(viewModel.state.value).feedback!!
        assertEquals(ShootEditorFeedbackCode.IMPORT_ALLOCATION_BLOCKED, allocationFeedback.code)
        assertSame(
            ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
            allocationFeedback.allocationBlockReason,
        )
        assertFalse(viewModel.state.value.toString().contains(marker))

        workflow.reorderFailure = IllegalStateException(marker)
        viewModel.requestReorder(poseIds(3).reversed())
        runCurrent()
        assertEquals(ShootEditorFeedbackCode.REORDER_FAILED, data(viewModel.state.value).feedback!!.code)

        workflow.startFailure = IllegalStateException(marker)
        viewModel.requestStart()
        runCurrent()
        assertEquals(ShootEditorFeedbackCode.START_FAILED, data(viewModel.state.value).feedback!!.code)
    }

    @Test
    fun delayedCompletionsAfterAuthoritativeMissingSnapshotCannotMutateOrNavigate() = runTest {
        val reorderDeferred = CompletableDeferred<ShootReorderResult>()
        val workflow = FakeWorkflow().apply {
            snapshots.emit(snapshot(referenceCount = 3))
            reorderBlock = reorderDeferred
        }
        val viewModel = ShootEditorViewModel(SHOOT_ID, workflow, StandardTestDispatcher(testScheduler))
        runCurrent()
        viewModel.requestReorder(poseIds(3).reversed())
        runCurrent()
        assertTrue(viewModel.state.value is ShootEditorUiState.Reordering)

        workflow.snapshots.emit(null)
        runCurrent()
        assertSame(ShootEditorUiState.Missing, viewModel.state.value)
        reorderDeferred.complete(ShootReorderResult.Reordered)
        runCurrent()
        assertSame(ShootEditorUiState.Missing, viewModel.state.value)
    }

    @Test
    fun saturatedEffectQueueFailsClosedAndAuthoritativeTerminationDropsEveryQueuedEffect() = runTest {
        val workflow = FakeWorkflow().apply {
            snapshots.emit(snapshot(referenceCount = 3))
            startOutcome = ShootEditorStartOutcome.Started(
                StartedSessionHandle("saturated-navigation"),
            )
        }
        val viewModel = ShootEditorViewModel(
            SHOOT_ID,
            workflow,
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        val observedStates = mutableListOf<ShootEditorUiState>()
        val stateCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { state -> observedStates += state }
        }

        repeat(8) {
            viewModel.requestStart()
            runCurrent()
            assertTrue(viewModel.state.value is ShootEditorUiState.Content)
        }
        val beforeSaturatedAttempt = observedStates.size
        viewModel.requestStart()
        runCurrent()

        assertEquals(9, workflow.startCalls)
        assertTrue(viewModel.state.value is ShootEditorUiState.Unavailable)
        assertTrue(
            observedStates.drop(beforeSaturatedAttempt).none { state ->
                state is ShootEditorUiState.Empty || state is ShootEditorUiState.Content
            },
        )

        workflow.snapshots.emit(null)
        runCurrent()
        assertSame(ShootEditorUiState.Missing, viewModel.state.value)

        val staleEffect = async { viewModel.effects.first() }
        runCurrent()
        assertFalse(staleEffect.isCompleted)
        staleEffect.cancel()
        stateCollector.cancel()
    }

    @Test
    fun retryObservationInvalidatesPendingEffectBookkeepingBeforeRestartingSource() = runTest {
        val workflow = FakeWorkflow().apply {
            snapshots.emit(snapshot(referenceCount = 3))
            startOutcome = ShootEditorStartOutcome.Started(
                StartedSessionHandle("retry-navigation"),
            )
        }
        val viewModel = ShootEditorViewModel(
            SHOOT_ID,
            workflow,
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        viewModel.requestStart()
        runCurrent()

        val tokenField = viewModel.javaClass.getDeclaredField("pendingEffectToken").apply {
            isAccessible = true
        }
        val effectField = viewModel.javaClass.getDeclaredField("pendingEffect").apply {
            isAccessible = true
        }
        assertTrue(tokenField.get(viewModel) != null)
        assertTrue(effectField.get(viewModel) != null)

        viewModel.retryObservation()

        assertEquals(null, tokenField.get(viewModel))
        assertEquals(null, effectField.get(viewModel))
    }

    @Test
    fun retryObservationRejectsLateEmissionFromCanceledGeneration() = runTest {
        val oldRelease = CompletableDeferred<Unit>()
        val oldFlow = object : Flow<ShootEditorDisplaySnapshot?> {
            override suspend fun collect(collector: FlowCollector<ShootEditorDisplaySnapshot?>) {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        oldRelease.await()
                        collector.emit(snapshot(referenceCount = 2))
                    }
                }
            }
        }
        val workflow = FakeWorkflow().apply { snapshotFlow = oldFlow }
        val viewModel = ShootEditorViewModel(
            SHOOT_ID,
            workflow,
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        workflow.snapshotFlow = flow { emit(snapshot(referenceCount = 4)) }
        viewModel.retryObservation()
        runCurrent()
        assertEquals(4, data(viewModel.state.value).snapshot.references.size)

        oldRelease.complete(Unit)
        runCurrent()

        assertEquals(4, data(viewModel.state.value).snapshot.references.size)
    }

    @Test
    fun observationGenerationOverflowIsTerminalAgainstNoncooperativeMaxGenerationCollector() = runTest {
        val lateRelease = CompletableDeferred<Unit>()
        val noncooperativeFlow = object : Flow<ShootEditorDisplaySnapshot?> {
            override suspend fun collect(collector: FlowCollector<ShootEditorDisplaySnapshot?>) {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        lateRelease.await()
                        collector.emit(snapshot(referenceCount = 3))
                    }
                }
            }
        }
        val workflow = FakeWorkflow()
        val viewModel = ShootEditorViewModel(
            SHOOT_ID,
            workflow,
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        val generationField = viewModel.javaClass.getDeclaredField("observationGeneration").apply {
            isAccessible = true
        }
        generationField.setLong(viewModel, Long.MAX_VALUE - 1L)
        workflow.snapshotFlow = noncooperativeFlow

        viewModel.retryObservation()
        runCurrent()
        assertEquals(Long.MAX_VALUE, generationField.getLong(viewModel))
        viewModel.retryObservation()
        runCurrent()
        assertTrue(viewModel.state.value is ShootEditorUiState.Unavailable)

        lateRelease.complete(Unit)
        runCurrent()

        assertTrue(viewModel.state.value is ShootEditorUiState.Unavailable)
        assertEquals(Long.MAX_VALUE, generationField.getLong(viewModel))
    }

    @Test
    fun synchronousObservationFactoryFailureBecomesUnavailableWithoutRawLeakage() = runTest {
        val marker = "factory-raw-secret content://private/factory"
        val workflow = FakeWorkflow().apply {
            observeFactoryFailure = IllegalStateException(marker)
        }

        val viewModel = ShootEditorViewModel(SHOOT_ID, workflow, StandardTestDispatcher(testScheduler))
        runCurrent()

        assertTrue(viewModel.state.value is ShootEditorUiState.Unavailable)
        assertFalse(viewModel.state.value.toString().contains(marker))
    }

    @Test
    fun synchronousObservationFactoryCancellationIsNotMappedToUnavailable() = runTest {
        val workflow = FakeWorkflow().apply {
            observeFactoryFailure = CancellationException("factory-cancellation-raw-secret")
        }

        val viewModel = ShootEditorViewModel(SHOOT_ID, workflow, StandardTestDispatcher(testScheduler))
        runCurrent()

        assertSame(ShootEditorUiState.Loading, viewModel.state.value)
    }

    @Test
    fun genericClassifierFailureMapsToBoundedUnavailableOutcomeWithoutRawLeakage() {
        val marker = "classifier-raw-secret content://private/classifier"

        val outcome = classifyPickerResultSafely { error(marker) }

        assertSame(ReferenceImportOutcomeStatus.AUTHORITY_UNAVAILABLE, outcome.status)
        assertSame(ReferenceImportRetryAction.RETRY_ALLOCATION, outcome.retryAction)
        assertFalse(outcome.toString().contains(marker))
    }

    @Test
    fun classifierCancellationPropagatesAndDoesNotMutateStateFeedbackOrEmitAnotherEffect() = runTest {
        val cancellation = CancellationException("classifier-raw-secret")
        val propagated = assertThrows(CancellationException::class.java) {
            classifyPickerResultSafely { throw cancellation }
        }
        assertSame(cancellation, propagated)

        val workflow = FakeWorkflow().apply {
            snapshots.emit(snapshot(referenceCount = 2))
            allocationResult = ShootEditorImportAllocationOutcome.Ready(pickerLaunch)
            classifyFailure = cancellation
        }
        val viewModel = ShootEditorViewModel(SHOOT_ID, workflow, StandardTestDispatcher(testScheduler))
        runCurrent()
        val launch = async { viewModel.effects.first() }
        viewModel.requestImport(LABEL)
        runCurrent()
        val picker = launch.await() as ShootEditorEffect.LaunchPhotoPicker
        val stateBefore = viewModel.state.value
        val nextEffect = async { viewModel.effects.first() }

        viewModel.onReferencePickerResult(picker.operationId, ReferencePickerResult.Cancelled)
        runCurrent()

        assertSame(stateBefore, viewModel.state.value)
        assertEquals(1, workflow.classifyCalls)
        assertFalse(nextEffect.isCompleted)
        nextEffect.cancel()
    }

    @Test
    fun sourceImportsAndBoundarySignaturesDoNotExposePersistenceOrImportAuthorityTypes() {
        val source = projectRoot().resolve(SOURCE_PATH).readText()
        val forbiddenTypes = listOf(
            "ShootEditorSnapshot",
            "ReferenceImportAllocationResult",
            "ReferencePickerImportDraft",
            "ReferenceImportLedgerTimeline",
            "ReferenceImportToken",
        )
        forbiddenTypes.forEach { forbidden ->
            assertFalse("ViewModel source exposes forbidden type $forbidden", forbidden in source)
        }

        val boundaryTypes = buildList {
            add(ShootEditorWorkflowPort::class.java)
            add(ShootEditorViewModel::class.java)
            add(ShootEditorDisplaySnapshot::class.java)
            add(ShootEditorReferenceItem::class.java)
            add(ShootEditorLoadedData::class.java)
            add(ShootEditorPickerLaunch::class.java)
            add(ShootEditorImportAllocationOutcome::class.java)
            addAll(ShootEditorImportAllocationOutcome::class.java.declaredClasses)
            add(ShootEditorUiState::class.java)
            addAll(ShootEditorUiState::class.java.declaredClasses)
            add(ShootEditorEffect::class.java)
            addAll(ShootEditorEffect::class.java.declaredClasses)
        }
        val signatures = boundaryTypes.flatMap { type ->
            buildList {
                type.declaredFields.filterNot { Modifier.isPrivate(it.modifiers) }
                    .forEach { add(it.genericType.typeName) }
                type.declaredMethods.filterNot { Modifier.isPrivate(it.modifiers) }.forEach { method ->
                    add(method.genericReturnType.typeName)
                    addAll(method.genericParameterTypes.map(java.lang.reflect.Type::getTypeName))
                }
            }
        }
        forbiddenTypes.forEach { forbidden ->
            assertTrue(
                "Boundary signatures expose forbidden type $forbidden: $signatures",
                signatures.none { signature -> forbidden in signature },
            )
        }
    }

    @Test
    fun stateEffectsContractsAndPortSignaturesAreRedactedImmutableAndFrameworkFree() {
        val secretValues = listOf(
            SHOOT_ID,
            "pose-secret-0",
            LABEL,
            TOKEN,
            "navigation-secret",
            "content://private/provider",
            "/private/path",
        )
        val operation = operation(1)
        val snapshot = snapshot(
            references = listOf(
                ShootEditorReferenceItem("pose-secret-0", 0, LABEL, true),
            ),
        )
        val data = ShootEditorLoadedData(snapshot)
        val handle = StartedSessionHandle("navigation-secret")
        val values = listOf(
            operation,
            data,
            ShootEditorUiState.Loading,
            ShootEditorUiState.Missing,
            ShootEditorUiState.Unavailable(),
            ShootEditorUiState.Empty(data),
            ShootEditorUiState.Content(data),
            ShootEditorUiState.AllocatingImport(data, operation),
            ShootEditorUiState.Importing(data, operation),
            ShootEditorUiState.Reordering(data, operation),
            ShootEditorUiState.Starting(data, operation),
            ShootEditorFeedback(ShootEditorFeedbackCode.IMPORT_TERMINAL_REJECTED),
            ShootEditorEffect.LaunchPhotoPicker(operation, pickerLaunch),
            ShootEditorEffect.NavigateToStartedSession(handle),
            ShootEditorStartOutcome.Started(handle),
            ShootEditorStartOutcome.Resumable(handle),
            ShootEditorTransition(ShootEditorUiState.Content(data)),
            ShootEditorReducer(SHOOT_ID),
            handle,
        )
        values.forEach { value ->
            val rendered = value.toString()
            secretValues.forEach { secret ->
                assertFalse("${value::class.java.simpleName} leaked $secret in $rendered", rendered.contains(secret))
            }
        }

        val transition = ShootEditorTransition(
            ShootEditorUiState.Content(data),
            listOf(ShootEditorEffect.NavigateToStartedSession(handle)),
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (transition.effects as MutableList).clear()
        }

        val publicTypes = listOf(
            ShootEditorWorkflowPort::class.java,
            ShootEditorViewModel::class.java,
            ShootEditorUiState::class.java,
            ShootEditorEffect::class.java,
            ShootEditorStartOutcome::class.java,
            StartedSessionHandle::class.java,
        )
        val typeNames = publicTypes.flatMap { type ->
            buildList {
                type.declaredFields.filterNot { Modifier.isPrivate(it.modifiers) }
                    .forEach { add(it.type.name) }
                type.declaredMethods.filterNot { Modifier.isPrivate(it.modifiers) }.forEach { method ->
                    add(method.returnType.name)
                    addAll(method.parameterTypes.map(Class<*>::getName))
                }
            }
        }
        listOf(
            "android.net.Uri",
            "android.content.ContentResolver",
            "androidx.room",
            "java.time.Clock",
            "java.util.UUID",
        ).forEach { forbidden ->
            assertTrue(typeNames.none { it.startsWith(forbidden) })
        }
        listOf(
            "content://private/session",
            "session/with/slash",
            "session\\with\\slash",
            "..",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                StartedSessionHandle(invalid)
            }
        }
        listOf("content://private/shoot", "shoot/with/slash", "..")
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    ShootEditorReducer(invalid)
                }
            }
        val source = projectRoot().resolve(SOURCE_PATH).readText()
        listOf(
            "android.net.Uri",
            "ContentResolver",
            "androidx.room",
            "System.currentTimeMillis",
            "System.nanoTime",
            "java.time.Clock",
            "java.util.UUID",
            "MediaStore",
            "TextToSpeech",
        ).forEach { forbidden ->
            assertFalse("ViewModel source contains forbidden marker $forbidden", forbidden in source)
        }
    }

    private fun assertIdentityNoOp(
        expectedState: ShootEditorUiState,
        transition: ShootEditorTransition,
    ) {
        assertSame(expectedState, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    private fun loadedState(
        reducer: ShootEditorReducer,
        snapshot: ShootEditorDisplaySnapshot,
    ): ShootEditorUiState = reducer.snapshotChanged(ShootEditorUiState.Loading, snapshot).state

    private fun data(state: ShootEditorUiState): ShootEditorLoadedData =
        (state as ShootEditorUiState.Loaded).data

    private fun operation(generation: Long, shootId: String = SHOOT_ID): ShootEditorOperationId =
        ShootEditorOperationId(shootId, generation)

    private fun poseIds(count: Int): List<String> =
        (0 until count).map { index -> POSE_PREFIX + index }

    private fun reference(index: Int): ShootEditorReferenceItem =
        ShootEditorReferenceItem(
            poseId = POSE_PREFIX + index,
            poseIndex = index,
            label = "private-label-$index",
            mirrorAllowed = index % 2 == 0,
        )

    private fun snapshot(
        referenceCount: Int = 0,
        references: Iterable<ShootEditorReferenceItem> =
            (0 until referenceCount).map(::reference),
        lifecycle: ShootPreparationLifecycle = ShootPreparationLifecycle.ACTIVE,
        workStatus: ImportWorkStatus? = null,
    ): ShootEditorDisplaySnapshot = ShootEditorDisplaySnapshot(
        name = "private-shoot-name",
        lifecycle = lifecycle,
        references = references,
        importWorkStatuses = workStatus?.let(::listOf) ?: emptyList(),
    )

    private fun projectRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Cannot locate project root")
        }
        return current
    }

    private data class PickerCase(
        val status: ReferenceImportOutcomeStatus,
        val retryAction: ReferenceImportRetryAction,
        val feedback: ShootEditorFeedbackCode,
        val barrier: Boolean,
    )

    private data class ReorderCase(
        val result: ShootReorderResult,
        val feedback: ShootEditorFeedbackCode,
    )

    private class TestPickerLaunch : ShootEditorPickerLaunch()

    private class FakeWorkflow : ShootEditorWorkflowPort {
        val snapshots = MutableSharedFlow<ShootEditorDisplaySnapshot?>(replay = 1)
        var snapshotFlow: Flow<ShootEditorDisplaySnapshot?> = snapshots
        var allocationResult: ShootEditorImportAllocationOutcome =
            ShootEditorImportAllocationOutcome.Blocked(
            ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
            ReferenceImportRetryAction.RETRY_ALLOCATION,
        )
        var classifiedOutcome = ReferenceImportOutcome(
            ReferenceImportOutcomeStatus.CANCELLED,
            ReferenceImportRetryAction.NONE,
        )
        var reorderResult: ShootReorderResult = ShootReorderResult.AuthorityInconsistent
        var startOutcome: ShootEditorStartOutcome = ShootEditorStartOutcome.Rejected(
            ShootEditorStartRejectionReason.AUTHORITY_UNAVAILABLE,
        )
        var allocateFailure: RuntimeException? = null
        var classifyFailure: CancellationException? = null
        var observeFactoryFailure: RuntimeException? = null
        var reorderFailure: RuntimeException? = null
        var startFailure: RuntimeException? = null
        var reorderBlock: CompletableDeferred<ShootReorderResult>? = null
        var allocateCalls = 0
        var classifyCalls = 0
        var reorderCalls = 0
        var startCalls = 0
        var lastAllocateShootId: String? = null
        var lastLabel: String? = null
        var lastOrder: List<String>? = null
        var lastStartShootId: String? = null

        override fun observeEditorSnapshot(shootId: String): Flow<ShootEditorDisplaySnapshot?> {
            observeFactoryFailure?.let { throw it }
            return snapshotFlow
        }

        override suspend fun allocateImport(
            shootId: String,
            label: String,
        ): ShootEditorImportAllocationOutcome {
            allocateCalls += 1
            lastAllocateShootId = shootId
            lastLabel = label
            allocateFailure?.let { throw it }
            return allocationResult
        }

        override fun classifyPickerResult(result: ReferencePickerResult): ReferenceImportOutcome {
            classifyCalls += 1
            classifyFailure?.let { throw it }
            return classifiedOutcome
        }

        override suspend fun reorder(
            shootId: String,
            orderedPoseIds: List<String>,
        ): ShootReorderResult {
            reorderCalls += 1
            lastOrder = orderedPoseIds
            reorderFailure?.let { throw it }
            return reorderBlock?.await() ?: reorderResult
        }

        override suspend fun start(shootId: String): ShootEditorStartOutcome {
            startCalls += 1
            lastStartShootId = shootId
            startFailure?.let { throw it }
            return startOutcome
        }
    }

    private companion object {
        const val SHOOT_ID = "shoot-private-secret"
        const val POSE_PREFIX = "pose-secret-"
        const val LABEL = "private-reference-label"
        const val TOKEN = "private-import-token"
        const val SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorViewModel.kt"
    }
}
