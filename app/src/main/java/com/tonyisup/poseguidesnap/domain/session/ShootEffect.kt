package com.tonyisup.poseguidesnap.domain.session

sealed interface ShootEffect {
    @ConsistentCopyVisibility
    data class CaptureCommand private constructor(
        val token: CaptureToken,
        val trigger: CaptureTrigger,
        val poseId: String,
        val poseIndex: Int,
        val attemptNumber: Long,
        val outputs: List<PrivateOutputIdentity>,
    ) : ShootEffect {
        internal constructor(attempt: CaptureAttempt) : this(
            token = attempt.token,
            trigger = attempt.trigger,
            poseId = attempt.poseId,
            poseIndex = attempt.poseIndex,
            attemptNumber = attempt.attemptNumber,
            outputs = immutableListSnapshot(attempt.outputs),
        )
    }

    @ConsistentCopyVisibility
    data class ConfirmAndAdvanceCapture private constructor(
        val token: CaptureToken,
        val poseId: String,
        val poseIndex: Int,
        val outputs: List<PrivateOutputIdentity>,
    ) : ShootEffect {
        constructor(
            token: CaptureToken,
            poseId: String,
            poseIndex: Int,
            outputs: Iterable<PrivateOutputIdentity>,
        ) : this(token, poseId, poseIndex, immutableListSnapshot(outputs))

        init {
            require(poseId.isNotBlank())
            require(poseIndex >= 0)
            require(outputs == (0..2).map { PrivateOutputIdentity(token, it) })
        }
    }

    data class PoseAdvanced(
        val token: CaptureToken,
        val fromPoseIndex: Int,
        val toPoseIndex: Int?,
    ) : ShootEffect {
        init {
            require(fromPoseIndex >= 0) { "source pose index must be nonnegative" }
            require(toPoseIndex == null || toPoseIndex == fromPoseIndex + 1) {
                "destination pose index must be the immediately following pose"
            }
        }
    }

    data class ShootCompleted(val token: CaptureToken) : ShootEffect
    data object CancelPendingCoaching : ShootEffect

    data class StaleFrameIgnored(
        val frameTimestampNanos: Long,
        val receivedTimestampNanos: Long,
    ) : ShootEffect {
        init {
            require(frameTimestampNanos >= 0L) { "frame timestamp must be nonnegative" }
            require(receivedTimestampNanos >= frameTimestampNanos) {
                "received timestamp cannot precede frame timestamp"
            }
        }
    }

    data class CaptureSuppressedByCooldown(
        val trigger: CaptureTrigger,
        val remainingNanos: Long,
    ) : ShootEffect {
        init {
            require(remainingNanos > 0L) { "remaining cooldown must be positive" }
        }
    }

    data class CaptureFailureRecovered(val token: CaptureToken) : ShootEffect

    data class CaptureReconciliationRequired(
        val token: CaptureToken,
        val reason: String,
    ) : ShootEffect {
        init {
            require(reason.isNotBlank()) { "reconciliation reason must not be blank" }
        }
    }

    data class ProtocolEventRejected(
        val eventKind: ProtocolEventKind,
        val reason: ProtocolRejectionReason,
        val token: CaptureToken? = null,
    ) : ShootEffect
}

enum class ProtocolEventKind {
    AUTOMATIC_CAPTURE_REQUEST,
    MANUAL_CAPTURE_REQUEST,
    DURABILITY_CONFIRMATION,
    CLEANUP_CONFIRMATION,
    RECONCILIATION_REQUIRED,
    ADVANCEMENT_RECEIPT,
    EXPORT_STATUS,
}

enum class ProtocolRejectionReason {
    INVALID_PHASE,
    AUTOMATIC_NOT_LOCKED,
    COUNTER_EXHAUSTED,
    INVALID_OUTPUTS,
    DUPLICATE_RECEIPT,
    STALE_TOKEN,
}

@ConsistentCopyVisibility
data class ShootTransition private constructor(
    val nextState: ShootState,
    val effects: List<ShootEffect>,
) {
    constructor(nextState: ShootState, effects: Iterable<ShootEffect> = emptyList()) : this(
        nextState,
        immutableListSnapshot(effects),
    )
}
