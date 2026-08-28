package com.tonyisup.poseguidesnap.domain.session

import com.tonyisup.poseguidesnap.domain.model.MatchGateFailure
import com.tonyisup.poseguidesnap.domain.model.MatchResult
import java.util.Collections
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShootReducerTest {
    @Test
    fun preparationAndIneligibleFramesRouteToExactActivePhase() {
        val reducer = reducer()
        val prepared = reducer.reduce(state(), ShootEvent.PreparationCompleted(0L)).nextState
        assertEquals(ShootMode.SearchingForPerson, prepared.mode)

        val cases = listOf(
            MatchGateFailure.NO_PERSON to ShootMode.SearchingForPerson,
            MatchGateFailure.MULTIPLE_PEOPLE to ShootMode.SearchingForPerson,
            MatchGateFailure.INSUFFICIENT_LANDMARK_COVERAGE to ShootMode.Framing,
            MatchGateFailure.POOR_FRAMING to ShootMode.Framing,
            MatchGateFailure.ANGULAR_MISMATCH to ShootMode.Coaching,
            MatchGateFailure.POSITIONAL_MISMATCH to ShootMode.Coaching,
            MatchGateFailure.LOW_OVERALL_MATCH to ShootMode.Coaching,
        )
        cases.forEachIndexed { index, (failure, expected) ->
            val transition = reducer.reduce(
                prepared,
                frame(ineligible(failure), at = index.toLong()),
            )
            assertEquals("failure=$failure", expected, transition.nextState.mode)
        }
    }

    @Test
    fun eligibleFramesAcquireOnlyAtDwellEqualityAndIneligibleFrameResetsImmediately() {
        val reducer = reducer(acquire = 10L)
        val initial = state(mode = ShootMode.Coaching, lastReducer = 0L)

        val candidate = reducer.reduce(initial, frame(eligible(), at = 10L)).nextState
        assertEquals(ShootMode.LockCandidate(10L), candidate.mode)
        assertEquals(
            ShootMode.LockCandidate(10L),
            reducer.reduce(candidate, frame(eligible(), at = 19L)).nextState.mode,
        )
        assertEquals(
            ShootMode.Locked(),
            reducer.reduce(candidate, frame(eligible(), at = 20L)).nextState.mode,
        )
        assertEquals(
            ShootMode.Framing,
            reducer.reduce(candidate, frame(ineligible(MatchGateFailure.POOR_FRAMING), at = 11L))
                .nextState.mode,
        )
    }

    @Test
    fun zeroAcquireDwellLocksOnFirstEligibleFrame() {
        val transition = reducer(acquire = 0L).reduce(
            state(mode = ShootMode.SearchingForPerson),
            frame(eligible(), at = 0L),
        )
        assertEquals(ShootMode.Locked(), transition.nextState.mode)
    }

    @Test
    fun lockedFramesReleaseOnlyAtHysteresisEqualityAndRecoveryClearsCandidate() {
        val reducer = reducer(release = 10L)
        val initial = state(mode = ShootMode.Locked(), lastReducer = 0L)

        val releasing = reducer.reduce(
            initial,
            frame(ineligible(MatchGateFailure.LOW_OVERALL_MATCH), at = 10L),
        ).nextState
        assertEquals(ShootMode.Locked(10L), releasing.mode)
        assertEquals(
            ShootMode.Locked(10L),
            reducer.reduce(
                releasing,
                frame(ineligible(MatchGateFailure.LOW_OVERALL_MATCH), at = 19L),
            ).nextState.mode,
        )
        assertEquals(
            ShootMode.Coaching,
            reducer.reduce(
                releasing,
                frame(ineligible(MatchGateFailure.LOW_OVERALL_MATCH), at = 20L),
            ).nextState.mode,
        )
        assertEquals(
            ShootMode.Locked(),
            reducer.reduce(releasing, frame(eligible(), at = 15L)).nextState.mode,
        )
    }

    @Test
    fun zeroReleaseHysteresisReleasesOnFirstIneligibleFrame() {
        val transition = reducer(release = 0L).reduce(
            state(mode = ShootMode.Locked()),
            frame(ineligible(MatchGateFailure.NO_PERSON), at = 0L),
        )
        assertEquals(ShootMode.SearchingForPerson, transition.nextState.mode)
    }

    @Test
    fun staleFrameIsIgnoredWithoutChangingLockState() {
        val initial = state(mode = ShootMode.LockCandidate(0L), lastReducer = 0L)
        val transition = reducer(maxAge = 10L).reduce(
            initial,
            ShootEvent.FrameObserved(eligible(), frameTimestampNanos = 0L, receivedTimestampNanos = 11L),
        )

        assertEquals(ShootMode.LockCandidate(0L), transition.nextState.mode)
        assertEquals(11L, transition.nextState.lastReducerTimestampNanos)
        assertEquals(listOf(ShootEffect.StaleFrameIgnored(0L, 11L)), transition.effects)
    }

    @Test
    fun timestampValidationRejectsFutureFramesAndBackwardsReducerEvents() {
        assertThrows(IllegalArgumentException::class.java) {
            ShootEvent.FrameObserved(eligible(), frameTimestampNanos = 2L, receivedTimestampNanos = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            reducer().reduce(
                state(mode = ShootMode.Coaching, lastReducer = 2L),
                ShootEvent.ManualCaptureRequested(1L),
            )
        }
    }

    @Test
    fun elapsedChecksAreSafeAtLongMaxValue() {
        val locked = reducer(acquire = 1L, maxAge = Long.MAX_VALUE).reduce(
            state(mode = ShootMode.LockCandidate(Long.MAX_VALUE - 1L), lastReducer = Long.MAX_VALUE - 1L),
            frame(eligible(), at = Long.MAX_VALUE),
        )
        assertEquals(ShootMode.Locked(), locked.nextState.mode)

        val stale = reducer(maxAge = Long.MAX_VALUE - 1L).reduce(
            state(mode = ShootMode.Locked()),
            ShootEvent.FrameObserved(eligible(), 0L, Long.MAX_VALUE),
        )
        assertTrue(stale.effects.single() is ShootEffect.StaleFrameIgnored)
    }

    @Test
    fun pauseAndResumePreserveSafePreCapturePhasesAndClearLockProgress() {
        val reducer = reducer()
        val cases = listOf(
            ShootMode.SearchingForPerson to ResumePhase.SEARCHING_FOR_PERSON,
            ShootMode.Framing to ResumePhase.FRAMING,
            ShootMode.Coaching to ResumePhase.COACHING,
            ShootMode.LockCandidate(0L) to ResumePhase.COACHING,
            ShootMode.Locked() to ResumePhase.COACHING,
        )
        cases.forEach { (mode, resumePhase) ->
            val paused = reducer.reduce(state(mode = mode), ShootEvent.PauseRequested(0L))
            assertEquals(ShootMode.Paused(resumePhase), paused.nextState.mode)
            assertEquals(listOf(ShootEffect.CancelPendingCoaching), paused.effects)
            val resumed = reducer.reduce(paused.nextState, ShootEvent.ResumeRequested(1L)).nextState
            val expected = when (resumePhase) {
                ResumePhase.PREPARING -> ShootMode.Preparing
                ResumePhase.SEARCHING_FOR_PERSON -> ShootMode.SearchingForPerson
                ResumePhase.FRAMING -> ShootMode.Framing
                ResumePhase.COACHING -> ShootMode.Coaching
            }
            assertEquals(expected, resumed.mode)
        }
    }

    @Test
    fun pauseDuringPreparationIsRepresentedAndResumesPreparation() {
        val reducer = reducer()
        val initial = state(mode = ShootMode.Preparing)

        val paused = reducer.reduce(initial, ShootEvent.PauseRequested(0L))
        assertTrue(paused.nextState.mode is ShootMode.Paused)
        assertEquals(listOf(ShootEffect.CancelPendingCoaching), paused.effects)

        val resumed = reducer.reduce(paused.nextState, ShootEvent.ResumeRequested(1L))
        assertEquals(ShootMode.Preparing, resumed.nextState.mode)
    }

    @Test
    fun pauseDuringCaptureCannotCancelProtocolAndSuccessfulAdvanceLandsPaused() {
        val reducer = reducer()
        val capture = capture(reducer, state(mode = ShootMode.Locked()), automatic = true, at = 0L)
        val pausedCapture = reducer.reduce(capture.nextState, ShootEvent.PauseRequested(1L)).nextState
        assertTrue((pausedCapture.mode as ShootMode.Capturing).pauseAfter)

        val command = capture.command()
        val confirming = reducer.reduce(
            pausedCapture,
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, 2L),
        ).nextState
        assertTrue((confirming.mode as ShootMode.ConfirmingAndAdvancing).pauseAfter)

        val advanced = reducer.reduce(
            confirming,
            ShootEvent.CaptureConfirmedAndAdvanced(command.token, 3L),
        ).nextState
        assertEquals(1, advanced.currentPoseIndex)
        assertEquals(ShootMode.Paused(ResumePhase.SEARCHING_FOR_PERSON), advanced.mode)
    }

    @Test
    fun resumeDuringInFlightClearsPauseAfterWithoutDroppingAttempt() {
        val reducer = reducer()
        val capture = capture(reducer, state(mode = ShootMode.Coaching), automatic = false, at = 0L)
        val attempt = (capture.nextState.mode as ShootMode.Capturing).attempt
        val paused = reducer.reduce(capture.nextState, ShootEvent.PauseRequested(1L)).nextState
        val resumed = reducer.reduce(paused, ShootEvent.ResumeRequested(2L)).nextState

        assertEquals(ShootMode.Capturing(attempt, pauseAfter = false), resumed.mode)
    }

    @Test
    fun automaticCaptureRequiresLockedWhileManualAcceptsEveryPreparedActivePhase() {
        val reducer = reducer()
        val activeModes = listOf(
            ShootMode.SearchingForPerson,
            ShootMode.Framing,
            ShootMode.Coaching,
            ShootMode.LockCandidate(0L),
            ShootMode.Locked(),
        )
        activeModes.forEach { mode ->
            val automatic = reducer.reduce(state(mode = mode), ShootEvent.AutomaticCaptureRequested(0L))
            if (mode is ShootMode.Locked) {
                assertTrue(automatic.effects.single() is ShootEffect.CaptureCommand)
            } else {
                assertEquals(
                    ProtocolRejectionReason.AUTOMATIC_NOT_LOCKED,
                    (automatic.effects.single() as ShootEffect.ProtocolEventRejected).reason,
                )
            }
            assertTrue(
                reducer.reduce(state(mode = mode), ShootEvent.ManualCaptureRequested(0L))
                    .effects.single() is ShootEffect.CaptureCommand,
            )
        }
    }

    @Test
    fun manualCaptureIsRejectedFromUnpreparedPausedTerminalAndInFlightModes() {
        val reducer = reducer()
        val capture = capture(reducer, state(mode = ShootMode.Coaching), automatic = false, at = 0L)
        val command = capture.command()
        val confirming = reducer.reduce(
            capture.nextState,
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, 1L),
        ).nextState
        val invalidStates = listOf(
            state(mode = ShootMode.Preparing),
            state(mode = ShootMode.Paused(ResumePhase.COACHING)),
            state(mode = ShootMode.Completed, index = 2),
            state(
                mode = ShootMode.Failed(
                    ShootFailureDisposition.TERMINAL,
                    "reconciliation",
                    CaptureToken("old"),
                ),
            ),
            capture.nextState,
            confirming,
        )

        invalidStates.forEach { invalid ->
            val now = invalid.lastReducerTimestampNanos ?: 0L
            val transition = reducer.reduce(invalid, ShootEvent.ManualCaptureRequested(now))
            assertFalse(transition.effects.any { it is ShootEffect.CaptureCommand })
            assertEquals(
                ProtocolRejectionReason.INVALID_PHASE,
                (transition.effects.single() as ShootEffect.ProtocolEventRejected).reason,
            )
        }
    }

    @Test
    fun automaticAndManualShareCooldownWithEqualityAllowed() {
        val reducer = reducer(cooldown = 10L)
        listOf(true, false).forEach { automatic ->
            val initial = state(
                mode = ShootMode.Locked(),
                lastConfirmed = 100L,
                lastReducer = 100L,
            )
            val earlyEvent = if (automatic) {
                ShootEvent.AutomaticCaptureRequested(109L)
            } else {
                ShootEvent.ManualCaptureRequested(109L)
            }
            val early = reducer.reduce(initial, earlyEvent)
            assertEquals(
                ShootEffect.CaptureSuppressedByCooldown(
                    if (automatic) CaptureTrigger.AUTOMATIC else CaptureTrigger.MANUAL,
                    1L,
                ),
                early.effects.single(),
            )
            val equalityState = initial.evolve(lastReducerTimestampNanos = 109L)
            val equalityEvent = if (automatic) {
                ShootEvent.AutomaticCaptureRequested(110L)
            } else {
                ShootEvent.ManualCaptureRequested(110L)
            }
            assertTrue(reducer.reduce(equalityState, equalityEvent).effects.single() is ShootEffect.CaptureCommand)
        }
    }

    @Test
    fun automaticAndManualCommandsHaveOneUnifiedThreeOutputShape() {
        val reducer = reducer()
        val automatic = capture(reducer, state(mode = ShootMode.Locked()), automatic = true, at = 0L).command()
        val manual = capture(reducer, state(mode = ShootMode.Locked()), automatic = false, at = 0L).command()

        assertEquals(CaptureTrigger.AUTOMATIC, automatic.trigger)
        assertEquals(CaptureTrigger.MANUAL, manual.trigger)
        assertEquals(automatic.token, manual.token)
        assertEquals(automatic.poseId, manual.poseId)
        assertEquals(automatic.poseIndex, manual.poseIndex)
        assertEquals(automatic.attemptNumber, manual.attemptNumber)
        assertEquals((0..2).toList(), automatic.outputs.map { it.ordinal })
        assertTrue(automatic.outputs.all { it.token == automatic.token })
        assertEquals(automatic.outputs, manual.outputs)
    }

    @Test
    fun lengthPrefixedTokensAreDeterministicInjectiveAndAttemptsNeverReuse() {
        val reducer = reducer()
        val first = capture(
            reducer,
            state(sessionId = "a", poses = listOf("b:1:c", "x", "y"), mode = ShootMode.Coaching),
            automatic = false,
            at = 0L,
        ).command()
        val replay = capture(
            reducer,
            state(sessionId = "a", poses = listOf("b:1:c", "x", "y"), mode = ShootMode.Coaching),
            automatic = false,
            at = 0L,
        ).command()
        val delimiterCollisionCandidate = capture(
            reducer,
            state(sessionId = "a:b", poses = listOf("1:c", "x", "y"), mode = ShootMode.Coaching),
            automatic = false,
            at = 0L,
        ).command()
        assertEquals(first.token, replay.token)
        assertNotEquals(first.token, delimiterCollisionCandidate.token)

        val captured = capture(reducer, state(mode = ShootMode.Coaching), automatic = false, at = 0L)
        val recovered = reducer.reduce(
            captured.nextState,
            ShootEvent.CaptureFailureCleanupConfirmed(captured.command().token, 1L),
        ).nextState
        val retry = capture(reducer, recovered, automatic = false, at = 2L).command()
        assertEquals(0L, captured.command().attemptNumber)
        assertEquals(1L, retry.attemptNumber)
        assertNotEquals(captured.command().token, retry.token)
    }

    @Test
    fun exhaustedAttemptCounterFailsClosed() {
        val initial = state(
            mode = ShootMode.Locked(),
            nextAttempt = Long.MAX_VALUE,
        )
        val transition = reducer().reduce(initial, ShootEvent.AutomaticCaptureRequested(0L))

        assertEquals(initial.mode, transition.nextState.mode)
        assertEquals(Long.MAX_VALUE, transition.nextState.nextAttemptNumber)
        assertEquals(
            ProtocolRejectionReason.COUNTER_EXHAUSTED,
            (transition.effects.single() as ShootEffect.ProtocolEventRejected).reason,
        )
    }

    @Test
    fun durabilityRequiresExactCurrentTokenAndExactOrderedOutputs() {
        val reducer = reducer()
        val capture = capture(reducer, state(mode = ShootMode.Coaching), automatic = false, at = 0L)
        val command = capture.command()
        val invalidEvents = listOf(
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs.take(2), 1L),
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs.reversed(), 1L),
            ShootEvent.PrivateCaptureDurabilityConfirmed(CaptureToken("foreign"), command.outputs, 1L),
        )
        invalidEvents.forEach { event ->
            val transition = reducer.reduce(capture.nextState, event)
            assertTrue(transition.nextState.mode is ShootMode.Capturing)
            assertFalse(transition.effects.any { it is ShootEffect.ConfirmAndAdvanceCapture })
            assertTrue(transition.effects.single() is ShootEffect.ProtocolEventRejected)
        }

        val valid = reducer.reduce(
            capture.nextState,
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, 1L),
        )
        assertTrue(valid.nextState.mode is ShootMode.ConfirmingAndAdvancing)
        assertEquals(1, valid.effects.filterIsInstance<ShootEffect.ConfirmAndAdvanceCapture>().size)
    }

    @Test
    fun duplicateAndStaleDurabilityNeverEmitAnotherConfirmationIntent() {
        val reducer = reducer()
        val capture = capture(reducer, state(mode = ShootMode.Coaching), automatic = false, at = 0L)
        val command = capture.command()
        val confirming = reducer.reduce(
            capture.nextState,
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, 1L),
        ).nextState
        val repeated = reducer.reduce(
            confirming,
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, 2L),
        )
        assertEquals(
            ProtocolRejectionReason.STALE_TOKEN,
            (repeated.effects.single() as ShootEffect.ProtocolEventRejected).reason,
        )
        assertFalse(repeated.effects.any { it is ShootEffect.ConfirmAndAdvanceCapture })

        val advanced = reducer.reduce(
            confirming,
            ShootEvent.CaptureConfirmedAndAdvanced(command.token, 2L),
        ).nextState
        val duplicate = reducer.reduce(
            advanced,
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, 3L),
        )
        assertEquals(
            ProtocolRejectionReason.DUPLICATE_RECEIPT,
            (duplicate.effects.single() as ShootEffect.ProtocolEventRejected).reason,
        )
    }

    @Test
    fun cleanupRecoversFromCapturingOrConfirmingWithoutAdvanceAndConsumesAttempt() {
        listOf(false, true).forEach { afterDurability ->
            val reducer = reducer()
            val capture = capture(reducer, state(mode = ShootMode.Coaching), automatic = false, at = 0L)
            val command = capture.command()
            val protocolState = if (afterDurability) {
                reducer.reduce(
                    capture.nextState,
                    ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, 1L),
                ).nextState
            } else {
                capture.nextState
            }
            val cleanupAt = if (afterDurability) 2L else 1L
            val recovered = reducer.reduce(
                protocolState,
                ShootEvent.CaptureFailureCleanupConfirmed(command.token, cleanupAt),
            )
            assertEquals(0, recovered.nextState.currentPoseIndex)
            assertEquals(ShootMode.Coaching, recovered.nextState.mode)
            assertEquals(1L, recovered.nextState.nextAttemptNumber)
            assertEquals(ShootEffect.CaptureFailureRecovered(command.token), recovered.effects.single())

            val retry = capture(reducer, recovered.nextState, automatic = false, at = cleanupAt + 1L).command()
            assertNotEquals(command.token, retry.token)
        }
    }

    @Test
    fun cleanupAfterPausedInFlightLandsPausedCoaching() {
        val reducer = reducer()
        val capture = capture(reducer, state(mode = ShootMode.Coaching), automatic = false, at = 0L)
        val paused = reducer.reduce(capture.nextState, ShootEvent.PauseRequested(1L)).nextState
        val recovered = reducer.reduce(
            paused,
            ShootEvent.CaptureFailureCleanupConfirmed(capture.command().token, 2L),
        ).nextState
        assertEquals(ShootMode.Paused(ResumePhase.COACHING), recovered.mode)
        assertEquals(0, recovered.currentPoseIndex)
    }

    @Test
    fun reconciliationIsTerminalAndBlocksRecapture() {
        val reducer = reducer()
        val capture = capture(reducer, state(mode = ShootMode.Coaching), automatic = false, at = 0L)
        val command = capture.command()
        val failed = reducer.reduce(
            capture.nextState,
            ShootEvent.CaptureFailureReconciliationRequired(command.token, "ambiguous cleanup", 1L),
        )
        assertEquals(
            ShootMode.Failed(
                ShootFailureDisposition.TERMINAL,
                "ambiguous cleanup",
                command.token,
            ),
            failed.nextState.mode,
        )
        assertEquals(
            ShootEffect.CaptureReconciliationRequired(command.token, "ambiguous cleanup"),
            failed.effects.single(),
        )
        val recapture = reducer.reduce(failed.nextState, ShootEvent.ManualCaptureRequested(2L))
        assertFalse(recapture.effects.any { it is ShootEffect.CaptureCommand })
    }

    @Test
    fun exactConfirmationReceiptAdvancesOnceAndDuplicateOrStaleNeverAdvances() {
        val reducer = reducer()
        val confirming = confirming(reducer, state(mode = ShootMode.Coaching), 0L)
        val command = confirming.second
        val advanced = reducer.reduce(
            confirming.first,
            ShootEvent.CaptureConfirmedAndAdvanced(command.token, 2L),
        )
        assertEquals(1, advanced.nextState.currentPoseIndex)
        assertEquals(setOf(command.token), advanced.nextState.appliedReceiptTokens)
        assertEquals(2L, advanced.nextState.lastConfirmedAtNanos)
        assertEquals(1, advanced.effects.filterIsInstance<ShootEffect.PoseAdvanced>().size)

        val duplicate = reducer.reduce(
            advanced.nextState,
            ShootEvent.CaptureConfirmedAndAdvanced(command.token, 3L),
        )
        assertEquals(1, duplicate.nextState.currentPoseIndex)
        assertEquals(
            ProtocolRejectionReason.DUPLICATE_RECEIPT,
            (duplicate.effects.single() as ShootEffect.ProtocolEventRejected).reason,
        )
        val stale = reducer.reduce(
            duplicate.nextState,
            ShootEvent.CaptureConfirmedAndAdvanced(CaptureToken("unknown"), 4L),
        )
        assertEquals(1, stale.nextState.currentPoseIndex)
        assertEquals(
            ProtocolRejectionReason.STALE_TOKEN,
            (stale.effects.single() as ShootEffect.ProtocolEventRejected).reason,
        )
    }

    @Test
    fun appliedReceiptSetIsAnImmutableSnapshot() {
        val caller = linkedSetOf(CaptureToken("one"))
        val snapshot = state(applied = caller)
        caller.add(CaptureToken("two"))
        assertEquals(setOf(CaptureToken("one")), snapshot.appliedReceiptTokens)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.appliedReceiptTokens as MutableSet).add(CaptureToken("three"))
        }
    }

    @Test
    fun exportStatusBeforeAfterAndDuplicateCannotAdvanceOrRecapture() {
        val reducer = reducer()
        val unknown = reducer.reduce(
            state(mode = ShootMode.Coaching),
            ShootEvent.ExportStatusChanged(CaptureToken("unknown"), ExportStatus.PENDING, 0L),
        )
        assertEquals(0, unknown.nextState.currentPoseIndex)
        assertFalse(unknown.effects.any { it is ShootEffect.PoseAdvanced })

        val confirming = confirming(reducer, state(mode = ShootMode.Coaching), 1L)
        val command = confirming.second
        val advanced = reducer.reduce(
            confirming.first,
            ShootEvent.CaptureConfirmedAndAdvanced(command.token, 3L),
        ).nextState
        listOf(ExportStatus.PENDING, ExportStatus.EXPORTED, ExportStatus.EXPORTED).forEachIndexed { index, status ->
            val transition = reducer.reduce(
                advanced.evolve(lastReducerTimestampNanos = 3L + index),
                ShootEvent.ExportStatusChanged(command.token, status, 4L + index),
            )
            assertEquals(advanced.currentPoseIndex, transition.nextState.currentPoseIndex)
            assertEquals(advanced.mode, transition.nextState.mode)
            assertEquals(advanced.nextAttemptNumber, transition.nextState.nextAttemptNumber)
            assertEquals(advanced.appliedReceiptTokens, transition.nextState.appliedReceiptTokens)
            assertFalse(transition.effects.any { it is ShootEffect.PoseAdvanced || it is ShootEffect.CaptureCommand })
        }
    }

    @Test
    fun confirmationOfFinalPoseCompletesExactlyWithoutOutOfRangeIndex() {
        val reducer = reducer(cooldown = 0L)
        var current = state(mode = ShootMode.Coaching)
        repeat(2) { poseIndex ->
            val result = completeCurrentAttempt(reducer, current, poseIndex * 3L)
            current = result.nextState
            assertEquals(poseIndex + 1, current.currentPoseIndex)
            assertEquals(ShootMode.SearchingForPerson, current.mode)
        }
        val final = completeCurrentAttempt(
            reducer,
            current.evolve(mode = ShootMode.Coaching),
            6L,
        )
        assertEquals(2, final.nextState.currentPoseIndex)
        assertEquals(ShootMode.Completed, final.nextState.mode)
        assertEquals(3, final.nextState.appliedReceiptTokens.size)
        assertEquals(1, final.effects.filterIsInstance<ShootEffect.PoseAdvanced>().size)
        assertEquals(1, final.effects.filterIsInstance<ShootEffect.ShootCompleted>().size)
    }

    @Test
    fun seededPermutationsCannotAdvanceOneTokenAcrossTwoPoses() {
        val reducer = reducer(cooldown = 0L)
        val capture = capture(reducer, state(mode = ShootMode.Coaching), automatic = false, at = 0L)
        val command = capture.command()
        val tail = mutableListOf<ShootEvent>(
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, 1L),
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, 1L),
            ShootEvent.CaptureConfirmedAndAdvanced(command.token, 1L),
            ShootEvent.CaptureConfirmedAndAdvanced(command.token, 1L),
            ShootEvent.ExportStatusChanged(command.token, ExportStatus.EXPORTED, 1L),
            ShootEvent.ExportStatusChanged(command.token, ExportStatus.EXPORTED, 1L),
        )
        val random = Random(8_008L)
        repeat(100) {
            Collections.shuffle(tail, random)
            var current = capture.nextState
            var advances = 0
            tail.forEach { event ->
                val transition = reducer.reduce(current, event)
                advances += transition.effects.count { it is ShootEffect.PoseAdvanced }
                current = transition.nextState
            }
            assertTrue(current.currentPoseIndex in 0..1)
            assertTrue(advances <= 1)
            assertTrue(current.appliedReceiptTokens.count { it == command.token } <= 1)
        }
    }

    @Test
    fun statePolicyEventsAndValuesRejectInvalidInput() {
        listOf(-1L).forEach { negative ->
            assertThrows(IllegalArgumentException::class.java) { reducer(acquire = negative) }
            assertThrows(IllegalArgumentException::class.java) { reducer(release = negative) }
            assertThrows(IllegalArgumentException::class.java) { reducer(cooldown = negative) }
            assertThrows(IllegalArgumentException::class.java) { reducer(maxAge = negative) }
            assertThrows(IllegalArgumentException::class.java) { ShootEvent.PauseRequested(negative) }
        }
        assertThrows(IllegalArgumentException::class.java) { CaptureToken(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateOutputIdentity(CaptureToken("t"), -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateOutputIdentity(CaptureToken("t"), 3)
        }
        assertThrows(IllegalArgumentException::class.java) { state(sessionId = " ") }
        assertThrows(IllegalArgumentException::class.java) { state(poses = listOf("a", "b")) }
        assertThrows(IllegalArgumentException::class.java) {
            state(poses = listOf("a", "a", "b"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            state(mode = ShootMode.Completed, index = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            state(lastConfirmed = 1L, lastReducer = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShootEffect.PoseAdvanced(CaptureToken("t"), -1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShootEffect.PoseAdvanced(CaptureToken("t"), 0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShootEffect.StaleFrameIgnored(2L, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShootEffect.CaptureSuppressedByCooldown(CaptureTrigger.MANUAL, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShootEffect.CaptureReconciliationRequired(CaptureToken("t"), " ")
        }

        val appliedToken = CaptureToken("applied-in-flight")
        val appliedAttempt = CaptureAttempt.create(
            token = appliedToken,
            trigger = CaptureTrigger.MANUAL,
            poseId = "pose-0",
            poseIndex = 0,
            attemptNumber = 0L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            state(
                mode = ShootMode.Capturing(appliedAttempt),
                nextAttempt = 1L,
                applied = setOf(appliedToken),
            )
        }
    }

    @Test
    fun stateEventsAndEffectsOwnCallerCollections() {
        val poses = mutableListOf("a", "b", "c")
        val shoot = state(poses = poses)
        poses[0] = "changed"
        assertEquals(listOf("a", "b", "c"), shoot.poseIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (shoot.poseIds as MutableList)[0] = "changed"
        }

        val capture = capture(reducer(), state(mode = ShootMode.Coaching), automatic = false, at = 0L)
        val command = capture.command()
        val callerOutputs = command.outputs.toMutableList()
        val event = ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, callerOutputs, 1L)
        callerOutputs.clear()
        assertEquals(3, event.outputs.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (event.outputs as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (command.outputs as MutableList).clear()
        }

        val effectOutputs = command.outputs.toMutableList()
        val confirmationEffect = ShootEffect.ConfirmAndAdvanceCapture(
            command.token,
            command.poseId,
            command.poseIndex,
            effectOutputs,
        )
        effectOutputs.clear()
        assertEquals(3, confirmationEffect.outputs.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (confirmationEffect.outputs as MutableList).clear()
        }

        val callerEffects = mutableListOf<ShootEffect>(ShootEffect.CancelPendingCoaching)
        val transition = ShootTransition(shoot, callerEffects)
        callerEffects.clear()
        assertEquals(listOf(ShootEffect.CancelPendingCoaching), transition.effects)
        assertThrows(UnsupportedOperationException::class.java) {
            (transition.effects as MutableList).clear()
        }
    }

    @Test
    fun repeatedTransitionIsEqualAndEachInputEmitsAtMostOneAuthorityEffect() {
        val reducer = reducer(acquire = 0L)
        val initial = state(mode = ShootMode.Locked())
        val event = ShootEvent.AutomaticCaptureRequested(0L)
        val first = reducer.reduce(initial, event)
        val replay = reducer.reduce(initial, event)
        assertEquals(first, replay)

        val command = first.command()
        val transitions = listOf(
            first,
            reducer.reduce(
                first.nextState,
                ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, 1L),
            ),
        )
        val confirmingState = transitions.last().nextState
        val advanced = reducer.reduce(
            confirmingState,
            ShootEvent.CaptureConfirmedAndAdvanced(command.token, 2L),
        )
        (transitions + advanced).forEach { transition ->
            assertTrue(transition.effects.count { it is ShootEffect.CaptureCommand } <= 1)
            assertTrue(transition.effects.count { it is ShootEffect.ConfirmAndAdvanceCapture } <= 1)
            assertTrue(transition.effects.count { it is ShootEffect.PoseAdvanced } <= 1)
        }
    }

    private fun reducer(
        acquire: Long = 10L,
        release: Long = 10L,
        cooldown: Long = 0L,
        maxAge: Long = 100L,
    ): ShootReducer = ShootReducer(
        ShootTimingPolicy(acquire, release, cooldown, maxAge),
    )

    private fun state(
        sessionId: String = "session",
        poses: Iterable<String> = listOf("pose-0", "pose-1", "pose-2"),
        index: Int = 0,
        mode: ShootMode = ShootMode.Preparing,
        nextAttempt: Long = 0L,
        applied: Iterable<CaptureToken> = emptyList(),
        lastConfirmed: Long? = null,
        lastReducer: Long? = null,
    ): ShootState = ShootState.restore(
        sessionId = sessionId,
        poseIds = poses,
        currentPoseIndex = index,
        mode = mode,
        nextAttemptNumber = nextAttempt,
        appliedReceiptTokens = applied,
        lastConfirmedAtNanos = lastConfirmed,
        lastReducerTimestampNanos = lastReducer,
    )

    private fun eligible(): MatchResult = result(emptySet(), eligible = true)

    private fun ineligible(failure: MatchGateFailure): MatchResult = result(setOf(failure), eligible = false)

    private fun result(
        failures: Set<MatchGateFailure>,
        eligible: Boolean,
    ): MatchResult = MatchResult(
        landmarkCoverage = 1.0,
        framingScore = 1.0,
        angularSimilarity = 1.0,
        positionalSimilarity = 1.0,
        overallMatch = 1.0,
        gateFailures = failures,
        mirrorUsed = false,
        eligibleForLock = eligible,
    )

    private fun frame(result: MatchResult, at: Long): ShootEvent.FrameObserved =
        ShootEvent.FrameObserved(result, frameTimestampNanos = at, receivedTimestampNanos = at)

    private fun capture(
        reducer: ShootReducer,
        state: ShootState,
        automatic: Boolean,
        at: Long,
    ): ShootTransition = reducer.reduce(
        state,
        if (automatic) {
            ShootEvent.AutomaticCaptureRequested(at)
        } else {
            ShootEvent.ManualCaptureRequested(at)
        },
    )

    private fun ShootTransition.command(): ShootEffect.CaptureCommand =
        effects.single { it is ShootEffect.CaptureCommand } as ShootEffect.CaptureCommand

    private fun confirming(
        reducer: ShootReducer,
        state: ShootState,
        at: Long,
    ): Pair<ShootState, ShootEffect.CaptureCommand> {
        val capture = capture(reducer, state, automatic = false, at = at)
        val command = capture.command()
        val confirming = reducer.reduce(
            capture.nextState,
            ShootEvent.PrivateCaptureDurabilityConfirmed(command.token, command.outputs, at + 1L),
        )
        return confirming.nextState to command
    }

    private fun completeCurrentAttempt(
        reducer: ShootReducer,
        state: ShootState,
        at: Long,
    ): ShootTransition {
        val (confirmingState, command) = confirming(reducer, state, at)
        return reducer.reduce(
            confirmingState,
            ShootEvent.CaptureConfirmedAndAdvanced(command.token, at + 2L),
        )
    }
}
