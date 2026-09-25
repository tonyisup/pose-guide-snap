package com.tonyisup.poseguidesnap.ui.camera

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tonyisup.poseguidesnap.camera.JournaledCaptureResult
import com.tonyisup.poseguidesnap.camera.JournaledCaptureSubmission
import com.tonyisup.poseguidesnap.camera.JournaledConfirmationResult
import com.tonyisup.poseguidesnap.camera.JournaledStillCaptureWriter
import com.tonyisup.poseguidesnap.data.GuidedCurrentReferenceResult
import com.tonyisup.poseguidesnap.data.GuidedReferenceSnapshot
import com.tonyisup.poseguidesnap.data.GuidedSessionSnapshot
import com.tonyisup.poseguidesnap.domain.model.MatchGateFailure
import com.tonyisup.poseguidesnap.domain.model.MatchResult
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import com.tonyisup.poseguidesnap.domain.session.ShootEvent
import com.tonyisup.poseguidesnap.domain.session.ShootMode
import com.tonyisup.poseguidesnap.domain.session.ShootReducer
import com.tonyisup.poseguidesnap.domain.session.ShootState
import com.tonyisup.poseguidesnap.ui.BundledReferenceMatchEvidence
import com.tonyisup.poseguidesnap.ui.ReferenceMatchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal enum class GuidedCameraPhase {
    LOADING_REFERENCE,
    READY,
    CAPTURING,
    CONFIRMING,
    STOPPING,
    STOPPED,
    COMPLETED,
    NEEDS_REPAIR,
    UNAVAILABLE,
}

/** Reducer-derived progress toward an automatic lock, for the status line only. */
internal enum class GuidedMatchPhase {
    IDLE,
    SEARCHING,
    FRAMING,
    COACHING,
    LOCK_CANDIDATE,
    LOCKED,
}

internal class GuidedCameraUiState(
    val phase: GuidedCameraPhase,
    val currentPoseNumber: Int,
    val poseCount: Int,
    val reference: GuidedReferenceSnapshot?,
    val cameraReady: Boolean,
    val matchPhase: GuidedMatchPhase = GuidedMatchPhase.IDLE,
    val overallMatch: Double? = null,
    val lastCapture: List<Bitmap> = emptyList(),
) {
    val captureEnabled: Boolean
        get() = phase == GuidedCameraPhase.READY && cameraReady && reference != null

    val stopEnabled: Boolean
        get() = phase != GuidedCameraPhase.STOPPING && phase != GuidedCameraPhase.STOPPED

    override fun toString(): String =
        "GuidedCameraUiState(phase=${phase.name}, currentPoseNumber=$currentPoseNumber, " +
            "poseCount=$poseCount, cameraReady=$cameraReady, redacted)"
}

internal class GuidedCameraViewModel(
    snapshot: GuidedSessionSnapshot,
    private val workflow: GuidedCaptureWorkflowPort,
    private val reducer: ShootReducer = ShootReducer(),
    private val eventClockNanos: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val lock = Any()
    private var shootState = ShootState.restoreActive(
        sessionId = snapshot.sessionId,
        poseIds = snapshot.orderedPoseIds,
        currentPoseIndex = snapshot.currentPoseIndex,
        nextAttemptNumber = snapshot.nextAttemptNumber,
        appliedReceiptTokenValues = snapshot.appliedReceiptTokens,
    )
    private var stopRequested = false
    private var cleared = false
    private var cameraReady = false
    private var referenceJob: Job? = null
    private var attachedWriter: JournaledStillCaptureWriter? = null
    private var matchPhase = GuidedMatchPhase.IDLE
    private var overallMatch: Double? = null
    private var lastCapture: List<Bitmap> = emptyList()
    private val _state = MutableStateFlow(
        GuidedCameraUiState(
            phase = GuidedCameraPhase.LOADING_REFERENCE,
            currentPoseNumber = shootState.currentPoseIndex + 1,
            poseCount = shootState.poseIds.size,
            reference = null,
            cameraReady = false,
        ),
    )
    val state: StateFlow<GuidedCameraUiState> = _state

    init {
        loadReference()
    }

    fun attachWriter(writer: JournaledStillCaptureWriter) {
        synchronized(lock) {
            if (cleared) return
            attachedWriter?.let(workflow::detachWriter)
            attachedWriter = writer
            workflow.attachWriter(writer)
        }
    }

    fun detachWriter(writer: JournaledStillCaptureWriter) {
        synchronized(lock) {
            if (attachedWriter !== writer) return
            workflow.detachWriter(writer)
            attachedWriter = null
            setCameraReadyLocked(false)
        }
    }

    fun setCameraReady(ready: Boolean) {
        synchronized(lock) {
            if (cleared) return
            setCameraReadyLocked(ready)
        }
    }

    fun manualCapture() {
        val command = synchronized(lock) {
            if (cleared || !_state.value.captureEnabled || stopRequested) return
            val transition = reducer.reduce(
                shootState,
                ShootEvent.ManualCaptureRequested(nextEventTimeLocked()),
            )
            val effect = transition.effects.singleOrNull() as? ShootEffect.CaptureCommand
                ?: return
            shootState = transition.nextState
            publishLocked(GuidedCameraPhase.CAPTURING, _state.value.reference)
            effect
        }
        launchCapture(command)
    }

    /**
     * Feeds one analyzed camera frame through the selected reference's match evidence into the
     * shoot reducer. When the reducer reaches [ShootMode.Locked] this requests automatic capture,
     * which runs through exactly the same journaled path as [manualCapture].
     */
    fun observeFrame(observation: PoseObservation, frameTimestampNanos: Long) {
        val command = synchronized(lock) {
            if (cleared || stopRequested) return
            val current = _state.value
            if (current.phase != GuidedCameraPhase.READY || !cameraReady) return
            val reference = current.reference ?: return
            val evidence = BundledReferenceMatchEvidence.evaluate(reference, observation)
            val match = evidence.matchResult ?: failClosedMatch(evidence.status)
            val received = nextEventTimeLocked()
            // Camera sensor timestamps are only comparable with the event clock when both use the
            // same time base. Outside a short window, treat the frame as fresh and rely on the
            // analyzer's own keep-latest and stale-frame handling instead of the reducer's.
            val frame = if (frameTimestampNanos in (received - COMPARABLE_FRAME_CLOCK_WINDOW_NANOS)..received) {
                frameTimestampNanos
            } else {
                received
            }
            var transition = reducer.reduce(
                shootState,
                ShootEvent.FrameObserved(match, frame, received),
            )
            shootState = transition.nextState
            var effect: ShootEffect.CaptureCommand? = null
            if (shootState.mode is ShootMode.Locked) {
                transition = reducer.reduce(
                    shootState,
                    ShootEvent.AutomaticCaptureRequested(nextEventTimeLocked()),
                )
                shootState = transition.nextState
                effect = transition.effects.singleOrNull() as? ShootEffect.CaptureCommand
            }
            matchPhase = matchPhaseFor(shootState.mode)
            overallMatch = evidence.matchResult?.overallMatch
            publishLocked(
                if (effect != null) GuidedCameraPhase.CAPTURING else GuidedCameraPhase.READY,
                reference,
            )
            effect
        } ?: return
        launchCapture(command)
    }

    private fun launchCapture(command: ShootEffect.CaptureCommand) {
        val job = viewModelScope.launch(dispatcher, start = CoroutineStart.LAZY) {
            val submission = workflow.capture(shootState.sessionId, command) { result ->
                viewModelScope.launch(dispatcher) { handleCaptureResult(result) }
            }
            if (submission != JournaledCaptureSubmission.ACCEPTED) {
                handleCaptureSubmissionRejected(command)
            }
        }
        job.start()
    }

    fun stop() {
        synchronized(lock) {
            if (cleared || stopRequested || _state.value.phase == GuidedCameraPhase.STOPPED) return
            stopRequested = true
            val transition = reducer.reduce(
                shootState,
                ShootEvent.PauseRequested(nextEventTimeLocked()),
            )
            shootState = transition.nextState
            if (shootState.mode is ShootMode.Capturing ||
                shootState.mode is ShootMode.ConfirmingAndAdvancing
            ) {
                publishLocked(GuidedCameraPhase.STOPPING, _state.value.reference)
            } else {
                referenceJob?.cancel()
                publishLocked(GuidedCameraPhase.STOPPED, _state.value.reference)
            }
        }
    }

    private fun loadReference() {
        val job = viewModelScope.launch(dispatcher, start = CoroutineStart.LAZY) {
            val result = try {
                workflow.loadCurrentReference(shootState.sessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                GuidedCurrentReferenceResult.AuthorityInvalid
            }
            synchronized(lock) {
                if (cleared || stopRequested) return@synchronized
                referenceJob = null
                when (result) {
                    is GuidedCurrentReferenceResult.Ready -> if (
                        result.reference.poseId == shootState.currentPoseId
                    ) {
                        resetMatchLocked()
                        publishLocked(GuidedCameraPhase.READY, result.reference)
                    } else {
                        publishLocked(GuidedCameraPhase.UNAVAILABLE, null)
                    }
                    GuidedCurrentReferenceResult.Completed ->
                        publishLocked(GuidedCameraPhase.COMPLETED, null)
                    GuidedCurrentReferenceResult.ReconciliationRequired ->
                        publishLocked(GuidedCameraPhase.NEEDS_REPAIR, null)
                    GuidedCurrentReferenceResult.UnknownSession,
                    GuidedCurrentReferenceResult.AuthorityInvalid,
                    -> publishLocked(GuidedCameraPhase.UNAVAILABLE, null)
                }
            }
        }
        synchronized(lock) {
            if (cleared || stopRequested) return
            referenceJob?.cancel()
            referenceJob = job
        }
        job.start()
    }

    private fun handleCaptureResult(result: JournaledCaptureResult) {
        when (result) {
            is JournaledCaptureResult.Durable -> handleDurable(result.command)
            is JournaledCaptureResult.FailedCleaned -> synchronized(lock) {
                if (cleared) return
                val transition = reducer.reduce(
                    shootState,
                    ShootEvent.CaptureFailureCleanupConfirmed(
                        result.token,
                        nextEventTimeLocked(),
                    ),
                )
                shootState = transition.nextState
                resetMatchLocked()
                publishLocked(
                    if (stopRequested) GuidedCameraPhase.STOPPED else GuidedCameraPhase.READY,
                    _state.value.reference,
                )
            }
            is JournaledCaptureResult.ReconciliationRequired -> synchronized(lock) {
                if (cleared) return
                val transition = reducer.reduce(
                    shootState,
                    ShootEvent.CaptureFailureReconciliationRequired(
                        token = result.token,
                        reason = result.reason.name,
                        eventTimestampNanos = nextEventTimeLocked(),
                    ),
                )
                shootState = transition.nextState
                publishLocked(GuidedCameraPhase.NEEDS_REPAIR, _state.value.reference)
            }
        }
    }

    private fun handleDurable(command: ShootEffect.CaptureCommand) {
        val confirmation = synchronized(lock) {
            if (cleared) return
            val transition = reducer.reduce(
                shootState,
                ShootEvent.PrivateCaptureDurabilityConfirmed(
                    token = command.token,
                    outputs = command.outputs,
                    eventTimestampNanos = nextEventTimeLocked(),
                ),
            )
            val effect = transition.effects.singleOrNull()
                as? ShootEffect.ConfirmAndAdvanceCapture
                ?: return
            shootState = transition.nextState
            publishLocked(
                if (stopRequested) GuidedCameraPhase.STOPPING else GuidedCameraPhase.CONFIRMING,
                _state.value.reference,
            )
            effect
        }
        val submission = workflow.confirm(confirmation) { result ->
            viewModelScope.launch(dispatcher) { handleConfirmationResult(result) }
        }
        if (submission != JournaledCaptureSubmission.ACCEPTED) {
            handleConfirmationSubmissionRejected(confirmation)
        }
    }

    private fun handleConfirmationResult(result: JournaledConfirmationResult) {
        when (result) {
            is JournaledConfirmationResult.Advanced -> {
                val loadNext = synchronized(lock) {
                    if (cleared) return
                    val transition = reducer.reduce(
                        shootState,
                        ShootEvent.CaptureConfirmedAndAdvanced(
                            result.token,
                            nextEventTimeLocked(),
                        ),
                    )
                    shootState = transition.nextState
                    when {
                        shootState.mode is ShootMode.Completed -> {
                            publishLocked(GuidedCameraPhase.COMPLETED, null)
                            false
                        }
                        stopRequested -> {
                            publishLocked(GuidedCameraPhase.STOPPED, null)
                            false
                        }
                        else -> {
                            publishLocked(GuidedCameraPhase.LOADING_REFERENCE, null)
                            true
                        }
                    }
                }
                loadCaptureThumbnails(result.token)
                if (loadNext) loadReference()
            }
            is JournaledConfirmationResult.ReconciliationRequired -> synchronized(lock) {
                if (cleared) return
                val transition = reducer.reduce(
                    shootState,
                    ShootEvent.CaptureFailureReconciliationRequired(
                        result.token,
                        result.reason.name,
                        nextEventTimeLocked(),
                    ),
                )
                shootState = transition.nextState
                publishLocked(GuidedCameraPhase.NEEDS_REPAIR, _state.value.reference)
            }
        }
    }

    private fun handleCaptureSubmissionRejected(command: ShootEffect.CaptureCommand) {
        synchronized(lock) {
            if (cleared) return
            val transition = reducer.reduce(
                shootState,
                ShootEvent.CaptureFailureReconciliationRequired(
                    command.token,
                    "CAPTURE_SUBMISSION_REJECTED",
                    nextEventTimeLocked(),
                ),
            )
            shootState = transition.nextState
            publishLocked(GuidedCameraPhase.NEEDS_REPAIR, _state.value.reference)
        }
    }

    private fun handleConfirmationSubmissionRejected(
        command: ShootEffect.ConfirmAndAdvanceCapture,
    ) {
        synchronized(lock) {
            if (cleared) return
            val transition = reducer.reduce(
                shootState,
                ShootEvent.CaptureFailureReconciliationRequired(
                    command.token,
                    "CONFIRMATION_SUBMISSION_REJECTED",
                    nextEventTimeLocked(),
                ),
            )
            shootState = transition.nextState
            publishLocked(GuidedCameraPhase.NEEDS_REPAIR, _state.value.reference)
        }
    }

    private fun loadCaptureThumbnails(token: CaptureToken) {
        viewModelScope.launch(dispatcher) {
            val thumbnails = try {
                workflow.loadCaptureThumbnails(token)
            } catch (error: CancellationException) {
                throw error
            } catch (_: RuntimeException) {
                emptyList()
            }
            synchronized(lock) {
                if (cleared) return@launch
                lastCapture = thumbnails
                publishLocked(_state.value.phase, _state.value.reference)
            }
        }
    }

    private fun resetMatchLocked() {
        matchPhase = GuidedMatchPhase.IDLE
        overallMatch = null
    }

    private fun nextEventTimeLocked(): Long {
        val floor = shootState.lastReducerTimestampNanos ?: -1L
        val supplied = try {
            eventClockNanos()
        } catch (_: RuntimeException) {
            floor + 1L
        }
        return maxOf(0L, floor, supplied)
    }

    private fun setCameraReadyLocked(ready: Boolean) {
        cameraReady = ready
        publishLocked(_state.value.phase, _state.value.reference)
    }

    private fun publishLocked(
        phase: GuidedCameraPhase,
        reference: GuidedReferenceSnapshot?,
    ) {
        _state.value = GuidedCameraUiState(
            phase = phase,
            currentPoseNumber = shootState.currentPoseIndex + 1,
            poseCount = shootState.poseIds.size,
            reference = reference,
            cameraReady = cameraReady,
            matchPhase = matchPhase,
            overallMatch = overallMatch,
            lastCapture = lastCapture,
        )
    }

    override fun onCleared() {
        synchronized(lock) {
            if (cleared) return
            cleared = true
            referenceJob?.cancel()
            referenceJob = null
            attachedWriter?.let(workflow::detachWriter)
            attachedWriter = null
        }
        viewModelScope.cancel()
        workflow.close()
    }

    override fun toString(): String = "GuidedCameraViewModel(redacted)"

    private companion object {
        const val COMPARABLE_FRAME_CLOCK_WINDOW_NANOS = 2_000_000_000L
    }
}

private fun matchPhaseFor(mode: ShootMode): GuidedMatchPhase = when (mode) {
    ShootMode.SearchingForPerson -> GuidedMatchPhase.SEARCHING
    ShootMode.Framing -> GuidedMatchPhase.FRAMING
    ShootMode.Coaching -> GuidedMatchPhase.COACHING
    is ShootMode.LockCandidate -> GuidedMatchPhase.LOCK_CANDIDATE
    is ShootMode.Locked,
    is ShootMode.Capturing,
    is ShootMode.ConfirmingAndAdvancing,
    -> GuidedMatchPhase.LOCKED
    else -> GuidedMatchPhase.IDLE
}

/** A frame the evidence path could not evaluate must still move the reducer to a safe phase. */
private fun failClosedMatch(status: ReferenceMatchStatus): MatchResult {
    val failure = when (status) {
        ReferenceMatchStatus.MULTIPLE_PEOPLE -> MatchGateFailure.MULTIPLE_PEOPLE
        ReferenceMatchStatus.CANONICALIZATION_FAILED -> MatchGateFailure.INSUFFICIENT_LANDMARK_COVERAGE
        ReferenceMatchStatus.WAITING_FOR_REFERENCE,
        ReferenceMatchStatus.WAITING_FOR_FRAME,
        ReferenceMatchStatus.NO_PERSON,
        ReferenceMatchStatus.EVALUATED,
        -> MatchGateFailure.NO_PERSON
    }
    return MatchResult(
        landmarkCoverage = 0.0,
        framingScore = 0.0,
        angularSimilarity = 0.0,
        positionalSimilarity = 0.0,
        overallMatch = 0.0,
        gateFailures = setOf(failure),
        mirrorUsed = false,
        eligibleForLock = false,
    )
}
