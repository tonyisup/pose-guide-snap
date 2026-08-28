package com.tonyisup.poseguidesnap.domain.session

import com.tonyisup.poseguidesnap.domain.model.MatchResult

sealed interface ShootEvent {
    val eventTimestampNanos: Long

    data class PreparationCompleted(
        override val eventTimestampNanos: Long,
    ) : ShootEvent {
        init {
            require(eventTimestampNanos >= 0L)
        }
    }

    data class FrameObserved(
        val matchResult: MatchResult,
        val frameTimestampNanos: Long,
        val receivedTimestampNanos: Long,
    ) : ShootEvent {
        override val eventTimestampNanos: Long = receivedTimestampNanos

        init {
            require(frameTimestampNanos >= 0L) { "frame timestamp must be nonnegative" }
            require(receivedTimestampNanos >= 0L) { "received timestamp must be nonnegative" }
            require(receivedTimestampNanos >= frameTimestampNanos) {
                "received timestamp cannot precede frame timestamp"
            }
        }
    }

    data class PauseRequested(override val eventTimestampNanos: Long) : ShootEvent {
        init {
            require(eventTimestampNanos >= 0L)
        }
    }

    data class ResumeRequested(override val eventTimestampNanos: Long) : ShootEvent {
        init {
            require(eventTimestampNanos >= 0L)
        }
    }

    data class AutomaticCaptureRequested(override val eventTimestampNanos: Long) : ShootEvent {
        init {
            require(eventTimestampNanos >= 0L)
        }
    }

    data class ManualCaptureRequested(override val eventTimestampNanos: Long) : ShootEvent {
        init {
            require(eventTimestampNanos >= 0L)
        }
    }

    @ConsistentCopyVisibility
    data class PrivateCaptureDurabilityConfirmed private constructor(
        val token: CaptureToken,
        val outputs: List<PrivateOutputIdentity>,
        override val eventTimestampNanos: Long,
    ) : ShootEvent {
        constructor(
            token: CaptureToken,
            outputs: Iterable<PrivateOutputIdentity>,
            eventTimestampNanos: Long,
        ) : this(token, immutableListSnapshot(outputs), eventTimestampNanos)

        init {
            require(eventTimestampNanos >= 0L)
        }
    }

    data class CaptureFailureCleanupConfirmed(
        val token: CaptureToken,
        override val eventTimestampNanos: Long,
    ) : ShootEvent {
        init {
            require(eventTimestampNanos >= 0L)
        }
    }

    data class CaptureFailureReconciliationRequired(
        val token: CaptureToken,
        val reason: String,
        override val eventTimestampNanos: Long,
    ) : ShootEvent {
        init {
            require(reason.isNotBlank()) { "reconciliation reason must not be blank" }
            require(eventTimestampNanos >= 0L)
        }
    }

    data class CaptureConfirmedAndAdvanced(
        val token: CaptureToken,
        override val eventTimestampNanos: Long,
    ) : ShootEvent {
        init {
            require(eventTimestampNanos >= 0L)
        }
    }

    data class ExportStatusChanged(
        val token: CaptureToken,
        val status: ExportStatus,
        override val eventTimestampNanos: Long,
    ) : ShootEvent {
        init {
            require(eventTimestampNanos >= 0L)
        }
    }
}

enum class ExportStatus {
    PENDING,
    EXPORTED,
    FAILED,
}
