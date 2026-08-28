package com.tonyisup.poseguidesnap.domain.session

import java.util.Collections

internal fun <T> immutableListSnapshot(values: Iterable<T>): List<T> =
    Collections.unmodifiableList(ArrayList<T>().apply { addAll(values) })

internal fun <T> immutableSetSnapshot(values: Iterable<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet<T>().apply { addAll(values) })

enum class CaptureTrigger {
    AUTOMATIC,
    MANUAL,
}

@JvmInline
value class CaptureToken(val value: String) {
    init {
        require(value.isNotBlank()) { "capture token must not be blank" }
    }
}

data class PrivateOutputIdentity(
    val token: CaptureToken,
    val ordinal: Int,
) {
    init {
        require(ordinal in 0..2) { "output ordinal must be in 0..2" }
    }
}

@ConsistentCopyVisibility
data class CaptureAttempt private constructor(
    val token: CaptureToken,
    val trigger: CaptureTrigger,
    val poseId: String,
    val poseIndex: Int,
    val attemptNumber: Long,
    val outputs: List<PrivateOutputIdentity>,
) {
    init {
        require(poseId.isNotBlank()) { "poseId must not be blank" }
        require(poseIndex >= 0) { "poseIndex must be nonnegative" }
        require(attemptNumber >= 0L) { "attemptNumber must be nonnegative" }
        require(outputs == (0..2).map { PrivateOutputIdentity(token, it) }) {
            "capture attempt must own exactly output ordinals 0, 1, and 2"
        }
    }

    internal companion object {
        fun create(
            token: CaptureToken,
            trigger: CaptureTrigger,
            poseId: String,
            poseIndex: Int,
            attemptNumber: Long,
        ): CaptureAttempt = CaptureAttempt(
            token = token,
            trigger = trigger,
            poseId = poseId,
            poseIndex = poseIndex,
            attemptNumber = attemptNumber,
            outputs = immutableListSnapshot((0..2).map { PrivateOutputIdentity(token, it) }),
        )
    }
}

enum class ResumePhase {
    PREPARING,
    SEARCHING_FOR_PERSON,
    FRAMING,
    COACHING,
}

enum class ShootFailureDisposition {
    RECOVERABLE,
    TERMINAL,
}

sealed interface ShootMode {
    data object Preparing : ShootMode
    data object SearchingForPerson : ShootMode
    data object Framing : ShootMode
    data object Coaching : ShootMode

    data class LockCandidate(val sinceNanos: Long) : ShootMode {
        init {
            require(sinceNanos >= 0L) { "lock candidate timestamp must be nonnegative" }
        }
    }

    data class Locked(val releaseCandidateSinceNanos: Long? = null) : ShootMode {
        init {
            require(releaseCandidateSinceNanos == null || releaseCandidateSinceNanos >= 0L) {
                "release candidate timestamp must be nonnegative"
            }
        }
    }

    data class Paused(val resumePhase: ResumePhase) : ShootMode
    data class Capturing(val attempt: CaptureAttempt, val pauseAfter: Boolean = false) : ShootMode
    data class ConfirmingAndAdvancing(
        val attempt: CaptureAttempt,
        val pauseAfter: Boolean = false,
    ) : ShootMode

    data object Completed : ShootMode

    data class Failed(
        val disposition: ShootFailureDisposition,
        val reason: String,
        val token: CaptureToken? = null,
    ) : ShootMode {
        init {
            require(reason.isNotBlank()) { "failure reason must not be blank" }
        }
    }
}

@ConsistentCopyVisibility
data class ShootState private constructor(
    val sessionId: String,
    val poseIds: List<String>,
    val currentPoseIndex: Int,
    val mode: ShootMode,
    val nextAttemptNumber: Long,
    val appliedReceiptTokens: Set<CaptureToken>,
    val lastConfirmedAtNanos: Long?,
    val lastReducerTimestampNanos: Long?,
) {
    companion object {
        fun initial(sessionId: String, poseIds: Iterable<String>): ShootState = restore(
            sessionId = sessionId,
            poseIds = poseIds,
        )

        internal fun restore(
            sessionId: String,
            poseIds: Iterable<String>,
            currentPoseIndex: Int = 0,
            mode: ShootMode = ShootMode.Preparing,
            nextAttemptNumber: Long = 0L,
            appliedReceiptTokens: Iterable<CaptureToken> = emptyList(),
            lastConfirmedAtNanos: Long? = null,
            lastReducerTimestampNanos: Long? = null,
        ): ShootState = ShootState(
            sessionId = sessionId,
            poseIds = immutableListSnapshot(poseIds),
            currentPoseIndex = currentPoseIndex,
            mode = mode,
            nextAttemptNumber = nextAttemptNumber,
            appliedReceiptTokens = immutableSetSnapshot(appliedReceiptTokens),
            lastConfirmedAtNanos = lastConfirmedAtNanos,
            lastReducerTimestampNanos = lastReducerTimestampNanos,
        )
    }

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(poseIds.size in 3..20) { "a shoot must contain between 3 and 20 poses" }
        require(poseIds.all { it.isNotBlank() }) { "pose IDs must not be blank" }
        require(poseIds.distinct().size == poseIds.size) { "pose IDs must be unique" }
        require(currentPoseIndex in poseIds.indices) { "currentPoseIndex must identify a pose" }
        require(nextAttemptNumber >= 0L) { "nextAttemptNumber must be nonnegative" }
        require(lastConfirmedAtNanos == null || lastConfirmedAtNanos >= 0L) {
            "last confirmation timestamp must be nonnegative"
        }
        require(lastReducerTimestampNanos == null || lastReducerTimestampNanos >= 0L) {
            "last reducer timestamp must be nonnegative"
        }
        require(lastConfirmedAtNanos == null || lastReducerTimestampNanos != null) {
            "confirmation evidence requires reducer timestamp evidence"
        }
        require(
            lastConfirmedAtNanos == null ||
                lastReducerTimestampNanos == null ||
                lastConfirmedAtNanos <= lastReducerTimestampNanos,
        ) { "confirmation timestamp cannot be after reducer timestamp" }
        require(mode !is ShootMode.Completed || currentPoseIndex == poseIds.lastIndex) {
            "completed shoot must remain on its final pose"
        }
        val inFlight = when (mode) {
            is ShootMode.Capturing -> mode.attempt
            is ShootMode.ConfirmingAndAdvancing -> mode.attempt
            else -> null
        }
        require(
            inFlight == null ||
                (inFlight.poseIndex == currentPoseIndex && inFlight.poseId == poseIds[currentPoseIndex]),
        ) { "in-flight attempt must belong to the current pose" }
        require(inFlight == null || inFlight.attemptNumber < nextAttemptNumber) {
            "in-flight attempt number must already be consumed"
        }
        require(inFlight == null || inFlight.token !in appliedReceiptTokens) {
            "an applied receipt token cannot remain in flight"
        }
    }

    val currentPoseId: String
        get() = poseIds[currentPoseIndex]

    internal fun evolve(
        currentPoseIndex: Int = this.currentPoseIndex,
        mode: ShootMode = this.mode,
        nextAttemptNumber: Long = this.nextAttemptNumber,
        appliedReceiptTokens: Iterable<CaptureToken> = this.appliedReceiptTokens,
        lastConfirmedAtNanos: Long? = this.lastConfirmedAtNanos,
        lastReducerTimestampNanos: Long? = this.lastReducerTimestampNanos,
    ): ShootState = ShootState(
        sessionId = sessionId,
        poseIds = poseIds,
        currentPoseIndex = currentPoseIndex,
        mode = mode,
        nextAttemptNumber = nextAttemptNumber,
        appliedReceiptTokens = immutableSetSnapshot(appliedReceiptTokens),
        lastConfirmedAtNanos = lastConfirmedAtNanos,
        lastReducerTimestampNanos = lastReducerTimestampNanos,
    )
}
