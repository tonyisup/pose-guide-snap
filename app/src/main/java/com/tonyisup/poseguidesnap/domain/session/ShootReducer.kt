package com.tonyisup.poseguidesnap.domain.session

import com.tonyisup.poseguidesnap.domain.model.MatchGateFailure

/** Injected monotonic timing policy. Development defaults are deliberately uncalibrated. */
data class ShootTimingPolicy(
    val acquireDwellNanos: Long,
    val releaseHysteresisNanos: Long,
    val cooldownNanos: Long,
    val maxFrameAgeNanos: Long,
) {
    init {
        require(acquireDwellNanos >= 0L) { "acquire dwell must be nonnegative" }
        require(releaseHysteresisNanos >= 0L) { "release hysteresis must be nonnegative" }
        require(cooldownNanos >= 0L) { "cooldown must be nonnegative" }
        require(maxFrameAgeNanos >= 0L) { "maximum frame age must be nonnegative" }
    }

    companion object {
        fun uncalibratedDevelopmentDefaults(): ShootTimingPolicy = ShootTimingPolicy(
            acquireDwellNanos = 500_000_000L,
            releaseHysteresisNanos = 200_000_000L,
            cooldownNanos = 1_000_000_000L,
            maxFrameAgeNanos = 250_000_000L,
        )
    }
}

class ShootReducer(
    val policy: ShootTimingPolicy = ShootTimingPolicy.uncalibratedDevelopmentDefaults(),
) {
    fun reduce(state: ShootState, event: ShootEvent): ShootTransition {
        require(
            state.lastReducerTimestampNanos == null ||
                event.eventTimestampNanos >= state.lastReducerTimestampNanos,
        ) { "event timestamp cannot move backwards" }

        return when (event) {
            is ShootEvent.PreparationCompleted -> preparationCompleted(state, event)
            is ShootEvent.FrameObserved -> frameObserved(state, event)
            is ShootEvent.PauseRequested -> pause(state, event.eventTimestampNanos)
            is ShootEvent.ResumeRequested -> resume(state, event.eventTimestampNanos)
            is ShootEvent.AutomaticCaptureRequested -> requestCapture(
                state,
                CaptureTrigger.AUTOMATIC,
                event.eventTimestampNanos,
            )
            is ShootEvent.ManualCaptureRequested -> requestCapture(
                state,
                CaptureTrigger.MANUAL,
                event.eventTimestampNanos,
            )
            is ShootEvent.PrivateCaptureDurabilityConfirmed -> durabilityConfirmed(state, event)
            is ShootEvent.CaptureFailureCleanupConfirmed -> cleanupConfirmed(state, event)
            is ShootEvent.CaptureFailureReconciliationRequired -> reconciliationRequired(state, event)
            is ShootEvent.CaptureConfirmedAndAdvanced -> confirmedAndAdvanced(state, event)
            is ShootEvent.ExportStatusChanged -> exportStatusChanged(state, event)
        }
    }

    private fun preparationCompleted(
        state: ShootState,
        event: ShootEvent.PreparationCompleted,
    ): ShootTransition = transition(
        state,
        event.eventTimestampNanos,
        mode = if (state.mode is ShootMode.Preparing) {
            ShootMode.SearchingForPerson
        } else {
            state.mode
        },
    )

    private fun frameObserved(
        state: ShootState,
        event: ShootEvent.FrameObserved,
    ): ShootTransition {
        val age = event.receivedTimestampNanos - event.frameTimestampNanos
        if (age > policy.maxFrameAgeNanos) {
            return transition(
                state,
                event.eventTimestampNanos,
                effects = listOf(
                    ShootEffect.StaleFrameIgnored(
                        event.frameTimestampNanos,
                        event.receivedTimestampNanos,
                    ),
                ),
            )
        }

        val match = event.matchResult
        val now = event.eventTimestampNanos
        val nextMode = when (val mode = state.mode) {
            ShootMode.SearchingForPerson,
            ShootMode.Framing,
            ShootMode.Coaching,
            -> if (match.eligibleForLock) {
                if (policy.acquireDwellNanos == 0L) {
                    ShootMode.Locked()
                } else {
                    ShootMode.LockCandidate(now)
                }
            } else {
                phaseFor(match.gateFailures)
            }
            is ShootMode.LockCandidate -> if (!match.eligibleForLock) {
                phaseFor(match.gateFailures)
            } else if (elapsedAtLeast(now, mode.sinceNanos, policy.acquireDwellNanos)) {
                ShootMode.Locked()
            } else {
                mode
            }
            is ShootMode.Locked -> lockedFrameMode(mode, match.eligibleForLock, match.gateFailures, now)
            else -> mode
        }
        return transition(state, now, mode = nextMode)
    }

    private fun lockedFrameMode(
        mode: ShootMode.Locked,
        eligible: Boolean,
        failures: Set<MatchGateFailure>,
        now: Long,
    ): ShootMode {
        if (eligible) return ShootMode.Locked()
        if (policy.releaseHysteresisNanos == 0L) return phaseFor(failures)
        val since = mode.releaseCandidateSinceNanos ?: return ShootMode.Locked(now)
        return if (elapsedAtLeast(now, since, policy.releaseHysteresisNanos)) {
            phaseFor(failures)
        } else {
            mode
        }
    }

    private fun pause(state: ShootState, now: Long): ShootTransition {
        val nextMode = when (val mode = state.mode) {
            ShootMode.Preparing -> ShootMode.Paused(ResumePhase.PREPARING)
            ShootMode.SearchingForPerson -> ShootMode.Paused(ResumePhase.SEARCHING_FOR_PERSON)
            ShootMode.Framing -> ShootMode.Paused(ResumePhase.FRAMING)
            ShootMode.Coaching -> ShootMode.Paused(ResumePhase.COACHING)
            is ShootMode.LockCandidate,
            is ShootMode.Locked,
            -> ShootMode.Paused(ResumePhase.COACHING)
            is ShootMode.Capturing -> mode.copy(pauseAfter = true)
            is ShootMode.ConfirmingAndAdvancing -> mode.copy(pauseAfter = true)
            else -> mode
        }
        val newlyPaused = nextMode != state.mode
        return transition(
            state,
            now,
            mode = nextMode,
            effects = if (newlyPaused) listOf(ShootEffect.CancelPendingCoaching) else emptyList(),
        )
    }

    private fun resume(state: ShootState, now: Long): ShootTransition {
        val nextMode = when (val mode = state.mode) {
            is ShootMode.Paused -> when (mode.resumePhase) {
                ResumePhase.PREPARING -> ShootMode.Preparing
                ResumePhase.SEARCHING_FOR_PERSON -> ShootMode.SearchingForPerson
                ResumePhase.FRAMING -> ShootMode.Framing
                ResumePhase.COACHING -> ShootMode.Coaching
            }
            is ShootMode.Capturing -> mode.copy(pauseAfter = false)
            is ShootMode.ConfirmingAndAdvancing -> mode.copy(pauseAfter = false)
            else -> mode
        }
        return transition(state, now, mode = nextMode)
    }

    private fun requestCapture(
        state: ShootState,
        trigger: CaptureTrigger,
        now: Long,
    ): ShootTransition {
        val modeAllowsCapture = when (trigger) {
            CaptureTrigger.AUTOMATIC -> state.mode is ShootMode.Locked
            CaptureTrigger.MANUAL -> state.mode.isPreparedPreCaptureMode()
        }
        if (!modeAllowsCapture) {
            val reason = if (trigger == CaptureTrigger.AUTOMATIC) {
                ProtocolRejectionReason.AUTOMATIC_NOT_LOCKED
            } else {
                ProtocolRejectionReason.INVALID_PHASE
            }
            return rejected(
                state,
                now,
                if (trigger == CaptureTrigger.AUTOMATIC) {
                    ProtocolEventKind.AUTOMATIC_CAPTURE_REQUEST
                } else {
                    ProtocolEventKind.MANUAL_CAPTURE_REQUEST
                },
                reason,
            )
        }

        state.lastConfirmedAtNanos?.let { confirmedAt ->
            val elapsed = now - confirmedAt
            if (elapsed < policy.cooldownNanos) {
                return transition(
                    state,
                    now,
                    effects = listOf(
                        ShootEffect.CaptureSuppressedByCooldown(
                            trigger,
                            policy.cooldownNanos - elapsed,
                        ),
                    ),
                )
            }
        }

        if (state.nextAttemptNumber == Long.MAX_VALUE) {
            return rejected(
                state,
                now,
                if (trigger == CaptureTrigger.AUTOMATIC) {
                    ProtocolEventKind.AUTOMATIC_CAPTURE_REQUEST
                } else {
                    ProtocolEventKind.MANUAL_CAPTURE_REQUEST
                },
                ProtocolRejectionReason.COUNTER_EXHAUSTED,
            )
        }

        val attempt = CaptureAttempt.create(
            token = captureToken(state.sessionId, state.currentPoseId, state.nextAttemptNumber),
            trigger = trigger,
            poseId = state.currentPoseId,
            poseIndex = state.currentPoseIndex,
            attemptNumber = state.nextAttemptNumber,
        )
        return transition(
            state,
            now,
            mode = ShootMode.Capturing(attempt),
            nextAttemptNumber = state.nextAttemptNumber + 1L,
            effects = listOf(ShootEffect.CaptureCommand(attempt)),
        )
    }

    private fun durabilityConfirmed(
        state: ShootState,
        event: ShootEvent.PrivateCaptureDurabilityConfirmed,
    ): ShootTransition {
        duplicateRejection(state, event.token, event.eventTimestampNanos, ProtocolEventKind.DURABILITY_CONFIRMATION)
            ?.let { return it }
        val capturing = state.mode as? ShootMode.Capturing
            ?: return rejected(
                state,
                event.eventTimestampNanos,
                ProtocolEventKind.DURABILITY_CONFIRMATION,
                ProtocolRejectionReason.STALE_TOKEN,
                event.token,
            )
        if (capturing.attempt.token != event.token) {
            return rejected(
                state,
                event.eventTimestampNanos,
                ProtocolEventKind.DURABILITY_CONFIRMATION,
                ProtocolRejectionReason.STALE_TOKEN,
                event.token,
            )
        }
        if (event.outputs != capturing.attempt.outputs) {
            return rejected(
                state,
                event.eventTimestampNanos,
                ProtocolEventKind.DURABILITY_CONFIRMATION,
                ProtocolRejectionReason.INVALID_OUTPUTS,
                event.token,
            )
        }
        return transition(
            state,
            event.eventTimestampNanos,
            mode = ShootMode.ConfirmingAndAdvancing(capturing.attempt, capturing.pauseAfter),
            effects = listOf(
                ShootEffect.ConfirmAndAdvanceCapture(
                    capturing.attempt.token,
                    capturing.attempt.poseId,
                    capturing.attempt.poseIndex,
                    capturing.attempt.outputs,
                ),
            ),
        )
    }

    private fun cleanupConfirmed(
        state: ShootState,
        event: ShootEvent.CaptureFailureCleanupConfirmed,
    ): ShootTransition {
        duplicateRejection(state, event.token, event.eventTimestampNanos, ProtocolEventKind.CLEANUP_CONFIRMATION)
            ?.let { return it }
        val inFlight = state.mode.inFlight()
        if (inFlight == null || inFlight.attempt.token != event.token) {
            return rejected(
                state,
                event.eventTimestampNanos,
                ProtocolEventKind.CLEANUP_CONFIRMATION,
                ProtocolRejectionReason.STALE_TOKEN,
                event.token,
            )
        }
        return transition(
            state,
            event.eventTimestampNanos,
            mode = if (inFlight.pauseAfter) {
                ShootMode.Paused(ResumePhase.COACHING)
            } else {
                ShootMode.Coaching
            },
            effects = listOf(ShootEffect.CaptureFailureRecovered(event.token)),
        )
    }

    private fun reconciliationRequired(
        state: ShootState,
        event: ShootEvent.CaptureFailureReconciliationRequired,
    ): ShootTransition {
        duplicateRejection(
            state,
            event.token,
            event.eventTimestampNanos,
            ProtocolEventKind.RECONCILIATION_REQUIRED,
        )?.let { return it }
        val inFlight = state.mode.inFlight()
        if (inFlight == null || inFlight.attempt.token != event.token) {
            return rejected(
                state,
                event.eventTimestampNanos,
                ProtocolEventKind.RECONCILIATION_REQUIRED,
                ProtocolRejectionReason.STALE_TOKEN,
                event.token,
            )
        }
        return transition(
            state,
            event.eventTimestampNanos,
            mode = ShootMode.Failed(
                ShootFailureDisposition.TERMINAL,
                event.reason,
                event.token,
            ),
            effects = listOf(
                ShootEffect.CaptureReconciliationRequired(event.token, event.reason),
            ),
        )
    }

    private fun confirmedAndAdvanced(
        state: ShootState,
        event: ShootEvent.CaptureConfirmedAndAdvanced,
    ): ShootTransition {
        duplicateRejection(state, event.token, event.eventTimestampNanos, ProtocolEventKind.ADVANCEMENT_RECEIPT)
            ?.let { return it }
        val confirming = state.mode as? ShootMode.ConfirmingAndAdvancing
        if (confirming == null || confirming.attempt.token != event.token) {
            return rejected(
                state,
                event.eventTimestampNanos,
                ProtocolEventKind.ADVANCEMENT_RECEIPT,
                ProtocolRejectionReason.STALE_TOKEN,
                event.token,
            )
        }

        val receipts = LinkedHashSet(state.appliedReceiptTokens).apply { add(event.token) }
        val finalPose = state.currentPoseIndex == state.poseIds.lastIndex
        val nextIndex = if (finalPose) state.currentPoseIndex else state.currentPoseIndex + 1
        val nextMode = when {
            finalPose -> ShootMode.Completed
            confirming.pauseAfter -> ShootMode.Paused(ResumePhase.SEARCHING_FOR_PERSON)
            else -> ShootMode.SearchingForPerson
        }
        val effects = ArrayList<ShootEffect>().apply {
            add(
                ShootEffect.PoseAdvanced(
                    event.token,
                    state.currentPoseIndex,
                    if (finalPose) null else nextIndex,
                ),
            )
            if (finalPose) add(ShootEffect.ShootCompleted(event.token))
        }
        return transition(
            state,
            event.eventTimestampNanos,
            currentPoseIndex = nextIndex,
            mode = nextMode,
            appliedReceiptTokens = receipts,
            lastConfirmedAtNanos = event.eventTimestampNanos,
            effects = effects,
        )
    }

    private fun exportStatusChanged(
        state: ShootState,
        event: ShootEvent.ExportStatusChanged,
    ): ShootTransition = if (event.token in state.appliedReceiptTokens) {
        transition(state, event.eventTimestampNanos)
    } else {
        rejected(
            state,
            event.eventTimestampNanos,
            ProtocolEventKind.EXPORT_STATUS,
            ProtocolRejectionReason.STALE_TOKEN,
            event.token,
        )
    }

    private fun duplicateRejection(
        state: ShootState,
        token: CaptureToken,
        now: Long,
        kind: ProtocolEventKind,
    ): ShootTransition? = if (token in state.appliedReceiptTokens) {
        rejected(
            state,
            now,
            kind,
            ProtocolRejectionReason.DUPLICATE_RECEIPT,
            token,
        )
    } else {
        null
    }

    private fun rejected(
        state: ShootState,
        now: Long,
        kind: ProtocolEventKind,
        reason: ProtocolRejectionReason,
        token: CaptureToken? = null,
    ): ShootTransition = transition(
        state,
        now,
        effects = listOf(ShootEffect.ProtocolEventRejected(kind, reason, token)),
    )

    private fun transition(
        state: ShootState,
        now: Long,
        currentPoseIndex: Int = state.currentPoseIndex,
        mode: ShootMode = state.mode,
        nextAttemptNumber: Long = state.nextAttemptNumber,
        appliedReceiptTokens: Iterable<CaptureToken> = state.appliedReceiptTokens,
        lastConfirmedAtNanos: Long? = state.lastConfirmedAtNanos,
        effects: Iterable<ShootEffect> = emptyList(),
    ): ShootTransition = ShootTransition(
        state.evolve(
            currentPoseIndex = currentPoseIndex,
            mode = mode,
            nextAttemptNumber = nextAttemptNumber,
            appliedReceiptTokens = appliedReceiptTokens,
            lastConfirmedAtNanos = lastConfirmedAtNanos,
            lastReducerTimestampNanos = now,
        ),
        effects,
    )
}

private data class InFlightAttempt(
    val attempt: CaptureAttempt,
    val pauseAfter: Boolean,
)

private fun ShootMode.inFlight(): InFlightAttempt? = when (this) {
    is ShootMode.Capturing -> InFlightAttempt(attempt, pauseAfter)
    is ShootMode.ConfirmingAndAdvancing -> InFlightAttempt(attempt, pauseAfter)
    else -> null
}

private fun ShootMode.isPreparedPreCaptureMode(): Boolean = when (this) {
    ShootMode.SearchingForPerson,
    ShootMode.Framing,
    ShootMode.Coaching,
    is ShootMode.LockCandidate,
    is ShootMode.Locked,
    -> true
    else -> false
}

private fun phaseFor(failures: Set<MatchGateFailure>): ShootMode = when {
    MatchGateFailure.NO_PERSON in failures || MatchGateFailure.MULTIPLE_PEOPLE in failures ->
        ShootMode.SearchingForPerson
    MatchGateFailure.INSUFFICIENT_LANDMARK_COVERAGE in failures ||
        MatchGateFailure.POOR_FRAMING in failures -> ShootMode.Framing
    else -> ShootMode.Coaching
}

private fun elapsedAtLeast(now: Long, since: Long, duration: Long): Boolean {
    require(now >= since) { "event timestamp cannot precede mode timing evidence" }
    return now - since >= duration
}

private fun captureToken(
    sessionId: String,
    poseId: String,
    attemptNumber: Long,
): CaptureToken = CaptureToken(
    "s${sessionId.length}:$sessionId" +
        "p${poseId.length}:$poseId" +
        "a$attemptNumber",
)
