package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.data.AttemptRegistrationResult
import com.tonyisup.poseguidesnap.data.CaptureAttemptSettlementResult
import com.tonyisup.poseguidesnap.data.CaptureAttemptStartResult
import com.tonyisup.poseguidesnap.data.CaptureConfirmationResult
import com.tonyisup.poseguidesnap.data.CaptureExportTarget
import com.tonyisup.poseguidesnap.data.CaptureFileAdvanceRequest
import com.tonyisup.poseguidesnap.data.CaptureFileJournalResult
import com.tonyisup.poseguidesnap.data.CaptureFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage
import com.tonyisup.poseguidesnap.data.RoomCaptureFileJournal
import com.tonyisup.poseguidesnap.data.RoomShootRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executor

enum class JournaledCaptureSubmission {
    ACCEPTED,
    REJECTED_BUSY,
    REJECTED_CLOSED,
}

enum class JournaledCaptureFailureReason {
    REGISTRATION_REJECTED,
    START_REJECTED,
    JOURNAL_REJECTED,
    FILE_EFFECT_AMBIGUOUS,
    CAMERA_WRITE_FAILED,
    CLOCK_EXHAUSTED,
    RECOVERY_OUTSTANDING,
    CONFIRMATION_REJECTED,
}

sealed interface JournaledCaptureResult {
    class Durable(val command: ShootEffect.CaptureCommand) : JournaledCaptureResult {
        override fun toString(): String = "JournaledCaptureResult.Durable(redacted)"
    }

    class FailedCleaned(val token: CaptureToken) : JournaledCaptureResult {
        override fun toString(): String = "JournaledCaptureResult.FailedCleaned(redacted)"
    }

    class ReconciliationRequired(
        val token: CaptureToken,
        val reason: JournaledCaptureFailureReason,
    ) : JournaledCaptureResult {
        override fun toString(): String =
            "JournaledCaptureResult.ReconciliationRequired(reason=${reason.name})"
    }
}

sealed interface JournaledConfirmationResult {
    class Advanced(val token: CaptureToken) : JournaledConfirmationResult {
        override fun toString(): String = "JournaledConfirmationResult.Advanced(redacted)"
    }

    class ReconciliationRequired(
        val token: CaptureToken,
        val reason: JournaledCaptureFailureReason,
    ) : JournaledConfirmationResult {
        override fun toString(): String =
            "JournaledConfirmationResult.ReconciliationRequired(reason=${reason.name})"
    }
}

fun interface CaptureOperationClock {
    /** Returns a nonnegative epoch millisecond strictly greater than [floor], or null. */
    fun nextAfter(floor: Long): Long?
}

internal interface JournaledStillCaptureWriter {
    fun write(tempFile: File, callback: Callback)

    interface Callback {
        fun onImageSaved()
        fun onError()
    }
}

internal interface JournaledCaptureAuthorityPort {
    fun register(
        sessionId: String,
        command: ShootEffect.CaptureCommand,
        recordedAtEpochMillis: Long,
    ): AttemptRegistrationResult

    fun start(
        sessionId: String,
        token: CaptureToken,
        startedAtEpochMillis: Long,
    ): CaptureAttemptStartResult

    fun snapshot(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot?
    fun advance(request: CaptureFileAdvanceRequest): CaptureFileJournalResult

    fun settleFailure(
        sessionId: String,
        token: CaptureToken,
        settledAtEpochMillis: Long,
    ): CaptureAttemptSettlementResult

    fun confirm(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        targets: List<CaptureExportTarget>,
        confirmedAtEpochMillis: Long,
    ): CaptureConfirmationResult
}

internal interface JournaledCaptureFilePort {
    fun claim(snapshot: CaptureFileOperationSnapshot): CaptureFileClaimResult

    fun sync(
        snapshot: CaptureFileOperationSnapshot,
        lease: JournaledCaptureWriteLease,
        capturedAtEpochMillis: Long,
    ): CaptureTempSyncResult

    fun publish(
        snapshot: CaptureFileOperationSnapshot,
        lease: JournaledCaptureWriteLease,
        evidence: JournaledCaptureEvidence,
    ): CaptureFinalPublicationResult
}

internal fun interface JournaledCaptureRecoveryPort {
    fun reconcile(sessionId: String): CaptureAttemptRestartRecoveryResult
}

internal fun interface CaptureExportTargetFactory {
    fun targets(command: ShootEffect.ConfirmAndAdvanceCapture): List<CaptureExportTarget>
}

/**
 * Serializes one reducer-owned three-photo command through Room admission and exact private files.
 * Every physical effect follows its matching durable journal transition.
 */
class JournaledThreePhotoCaptureCoordinator internal constructor(
    private val authority: JournaledCaptureAuthorityPort,
    private val files: JournaledCaptureFilePort,
    private val writer: JournaledStillCaptureWriter,
    private val recovery: JournaledCaptureRecoveryPort,
    private val clock: CaptureOperationClock,
    private val executor: Executor,
    private val exportTargets: CaptureExportTargetFactory =
        CaptureExportTargetFactory(::defaultCaptureExportTargets),
) : AutoCloseable {
    private enum class Phase {
        STARTING,
        WRITING,
        SETTLING,
    }

    private class ActiveCapture(
        val sessionId: String,
        val command: ShootEffect.CaptureCommand,
        val callback: (JournaledCaptureResult) -> Unit,
    ) {
        var phase = Phase.STARTING
        var registered = false
        var completedOrdinalCount = 0
        var latestClock = -1L
        var activeLease: JournaledCaptureWriteLease? = null
        var writerCallbackClaimed = false
    }

    private val lock = Any()
    private var closed = false
    private var active: ActiveCapture? = null
    private var confirmationActive = false

    fun submit(
        sessionId: String,
        command: ShootEffect.CaptureCommand,
        callback: (JournaledCaptureResult) -> Unit,
    ): JournaledCaptureSubmission {
        require(sessionId.isNotBlank()) { "session ID must not be blank" }
        require(command.outputs == (0..2).map { PrivateOutputIdentity(command.token, it) }) {
            "capture command must own ordered output ordinals zero through two"
        }
        val capture = synchronized(lock) {
            when {
                closed -> null
                active != null || confirmationActive -> null
                else -> ActiveCapture(sessionId, command, callback).also { active = it }
            }
        }
        if (capture == null) {
            return synchronized(lock) {
                if (closed) JournaledCaptureSubmission.REJECTED_CLOSED
                else JournaledCaptureSubmission.REJECTED_BUSY
            }
        }
        if (!dispatchCapture(capture) { start(capture) }) {
            finish(
                capture,
                JournaledCaptureResult.ReconciliationRequired(
                    command.token,
                    JournaledCaptureFailureReason.RECOVERY_OUTSTANDING,
                ),
            )
        }
        return JournaledCaptureSubmission.ACCEPTED
    }

    fun confirm(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        callback: (JournaledConfirmationResult) -> Unit,
    ): JournaledCaptureSubmission {
        val accepted = synchronized(lock) {
            when {
                closed -> false
                active != null || confirmationActive -> false
                else -> true.also { confirmationActive = true }
            }
        }
        if (!accepted) {
            return synchronized(lock) {
                if (closed) JournaledCaptureSubmission.REJECTED_CLOSED
                else JournaledCaptureSubmission.REJECTED_BUSY
            }
        }
        if (!dispatch { confirmOnExecutor(command, callback) }) {
            synchronized(lock) { confirmationActive = false }
            invokeSafely {
                callback(
                    JournaledConfirmationResult.ReconciliationRequired(
                        command.token,
                        JournaledCaptureFailureReason.RECOVERY_OUTSTANDING,
                    ),
                )
            }
        }
        return JournaledCaptureSubmission.ACCEPTED
    }

    override fun close() {
        synchronized(lock) { closed = true }
    }

    private fun start(capture: ActiveCapture) {
        if (!owns(capture, Phase.STARTING)) return
        val recordedAt = nextClock(capture, -1L) ?: return fail(
            capture,
            JournaledCaptureFailureReason.CLOCK_EXHAUSTED,
        )
        when (authority.register(capture.sessionId, capture.command, recordedAt)) {
            AttemptRegistrationResult.Registered,
            AttemptRegistrationResult.AlreadyRegistered,
            -> capture.registered = true
            is AttemptRegistrationResult.Rejected -> return finish(
                capture,
                JournaledCaptureResult.ReconciliationRequired(
                    capture.command.token,
                    JournaledCaptureFailureReason.REGISTRATION_REJECTED,
                ),
            )
        }

        val startedAt = nextClock(capture, recordedAt) ?: return fail(
            capture,
            JournaledCaptureFailureReason.CLOCK_EXHAUSTED,
        )
        when (authority.start(capture.sessionId, capture.command.token, startedAt)) {
            CaptureAttemptStartResult.Started,
            CaptureAttemptStartResult.AlreadyStarted,
            -> captureNext(capture)
            CaptureAttemptStartResult.BlockedByDeletion,
            is CaptureAttemptStartResult.Rejected,
            -> fail(capture, JournaledCaptureFailureReason.START_REJECTED)
        }
    }

    private fun captureNext(capture: ActiveCapture) {
        if (!owns(capture)) return
        if (capture.completedOrdinalCount == 3) {
            finish(capture, JournaledCaptureResult.Durable(capture.command))
            return
        }
        val identity = capture.command.outputs[capture.completedOrdinalCount]
        val initial = authority.snapshot(identity)
            ?: return fail(capture, JournaledCaptureFailureReason.JOURNAL_REJECTED)
        if (initial.stage != CaptureFileOperationStage.EXPECTING_RESERVATION) {
            return fail(capture, JournaledCaptureFailureReason.JOURNAL_REJECTED)
        }
        val writing = advance(
            capture = capture,
            snapshot = initial,
            target = CaptureFileOperationStage.WRITING_TEMP,
        ) ?: return fail(capture, JournaledCaptureFailureReason.JOURNAL_REJECTED)
        val claim = files.claim(writing)
        val lease = (claim as? CaptureFileClaimResult.Claimed)?.lease
            ?: return fail(capture, JournaledCaptureFailureReason.FILE_EFFECT_AMBIGUOUS)

        synchronized(lock) {
            if (active !== capture) return
            capture.phase = Phase.WRITING
            capture.activeLease = lease
            capture.writerCallbackClaimed = false
        }
        try {
            writer.write(
                lease.tempFile,
                object : JournaledStillCaptureWriter.Callback {
                    override fun onImageSaved() {
                        writerCompleted(capture, lease, succeeded = true)
                    }

                    override fun onError() {
                        writerCompleted(capture, lease, succeeded = false)
                    }
                },
            )
        } catch (_: Throwable) {
            writerCompleted(capture, lease, succeeded = false)
        }
    }

    private fun writerCompleted(
        capture: ActiveCapture,
        lease: JournaledCaptureWriteLease,
        succeeded: Boolean,
    ) {
        val claimed = synchronized(lock) {
            if (
                active !== capture ||
                capture.phase != Phase.WRITING ||
                capture.activeLease !== lease ||
                capture.writerCallbackClaimed
            ) {
                false
            } else {
                capture.writerCallbackClaimed = true
                true
            }
        }
        if (!claimed) return
        if (!dispatchCapture(capture) {
                if (succeeded) writerSucceeded(capture, lease)
                else fail(capture, JournaledCaptureFailureReason.CAMERA_WRITE_FAILED)
            }
        ) {
            fail(capture, JournaledCaptureFailureReason.RECOVERY_OUTSTANDING)
        }
    }

    private fun writerSucceeded(
        capture: ActiveCapture,
        lease: JournaledCaptureWriteLease,
    ) {
        if (!owns(capture, Phase.WRITING)) return
        val writing = authority.snapshot(lease.identity)
            ?: return fail(capture, JournaledCaptureFailureReason.JOURNAL_REJECTED)
        if (writing.stage != CaptureFileOperationStage.WRITING_TEMP) {
            return fail(capture, JournaledCaptureFailureReason.JOURNAL_REJECTED)
        }
        val capturedAt = nextClock(capture, writing.updatedAtEpochMillis)
            ?: return fail(capture, JournaledCaptureFailureReason.CLOCK_EXHAUSTED)
        val sync = files.sync(writing, lease, capturedAt)
        val evidence = (sync as? CaptureTempSyncResult.Synced)?.evidence
            ?: return fail(capture, JournaledCaptureFailureReason.FILE_EFFECT_AMBIGUOUS)
        val synced = advance(
            capture = capture,
            snapshot = writing,
            target = CaptureFileOperationStage.TEMP_SYNCED,
            evidence = evidence,
        ) ?: return fail(capture, JournaledCaptureFailureReason.JOURNAL_REJECTED)
        val pending = advance(
            capture = capture,
            snapshot = synced,
            target = CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            evidence = evidence,
        ) ?: return fail(capture, JournaledCaptureFailureReason.JOURNAL_REJECTED)
        val publication = files.publish(pending, lease, evidence)
        if (publication !is CaptureFinalPublicationResult.Published) {
            return fail(capture, JournaledCaptureFailureReason.FILE_EFFECT_AMBIGUOUS)
        }
        advance(
            capture = capture,
            snapshot = pending,
            target = CaptureFileOperationStage.FINAL_DURABLE,
            evidence = evidence,
        ) ?: return fail(capture, JournaledCaptureFailureReason.JOURNAL_REJECTED)

        synchronized(lock) {
            if (active !== capture) return
            capture.completedOrdinalCount += 1
            capture.phase = Phase.STARTING
            capture.activeLease = null
        }
        captureNext(capture)
    }

    private fun advance(
        capture: ActiveCapture,
        snapshot: CaptureFileOperationSnapshot,
        target: CaptureFileOperationStage,
        evidence: JournaledCaptureEvidence? = null,
    ): CaptureFileOperationSnapshot? {
        val transitionedAt = nextClock(capture, snapshot.updatedAtEpochMillis) ?: return null
        val result = authority.advance(
            CaptureFileAdvanceRequest(
                identity = snapshot.identity,
                expectedStage = snapshot.stage,
                expectedUpdatedAtEpochMillis = snapshot.updatedAtEpochMillis,
                targetStage = target,
                byteCount = evidence?.byteCount,
                sha256 = evidence?.sha256,
                capturedAtEpochMillis = evidence?.capturedAtEpochMillis,
                transitionedAtEpochMillis = transitionedAt,
            ),
        )
        return when (result) {
            is CaptureFileJournalResult.Applied -> result.snapshot
            is CaptureFileJournalResult.Idempotent -> result.snapshot
            CaptureFileJournalResult.BlockedByDeletion,
            is CaptureFileJournalResult.Rejected,
            -> null
        }
    }

    private fun fail(capture: ActiveCapture, reason: JournaledCaptureFailureReason) {
        val canSettle = synchronized(lock) {
            if (active !== capture || capture.phase == Phase.SETTLING) {
                false
            } else {
                capture.phase = Phase.SETTLING
                true
            }
        }
        if (!canSettle) return
        if (!capture.registered) {
            finish(
                capture,
                JournaledCaptureResult.ReconciliationRequired(capture.command.token, reason),
            )
            return
        }

        val floor = capture.command.outputs.mapNotNull(authority::snapshot)
            .maxOfOrNull(CaptureFileOperationSnapshot::updatedAtEpochMillis)
            ?.let { maxOf(it, capture.latestClock) }
            ?: capture.latestClock
        val settledAt = nextClock(capture, floor)
        if (settledAt == null) {
            finish(
                capture,
                JournaledCaptureResult.ReconciliationRequired(
                    capture.command.token,
                    JournaledCaptureFailureReason.CLOCK_EXHAUSTED,
                ),
            )
            return
        }
        when (authority.settleFailure(capture.sessionId, capture.command.token, settledAt)) {
            CaptureAttemptSettlementResult.FailedCleaned,
            CaptureAttemptSettlementResult.AlreadyFailedCleaned,
            -> finish(capture, JournaledCaptureResult.FailedCleaned(capture.command.token))

            CaptureAttemptSettlementResult.ReadyToConfirm ->
                finish(capture, JournaledCaptureResult.Durable(capture.command))

            CaptureAttemptSettlementResult.ReconciliationRequired,
            CaptureAttemptSettlementResult.AlreadyReconciliationRequired,
            -> recoverFailure(capture, reason)

            CaptureAttemptSettlementResult.BlockedByDeletion,
            is CaptureAttemptSettlementResult.Rejected,
            -> finish(
                capture,
                JournaledCaptureResult.ReconciliationRequired(capture.command.token, reason),
            )
        }
    }

    private fun recoverFailure(
        capture: ActiveCapture,
        originalReason: JournaledCaptureFailureReason,
    ) {
        when (recovery.reconcile(capture.sessionId)) {
            CaptureAttemptRestartRecoveryResult.FailedCleaned ->
                finish(capture, JournaledCaptureResult.FailedCleaned(capture.command.token))
            CaptureAttemptRestartRecoveryResult.ReadyToConfirm ->
                finish(capture, JournaledCaptureResult.Durable(capture.command))
            CaptureAttemptRestartRecoveryResult.NoWork -> finish(
                capture,
                JournaledCaptureResult.ReconciliationRequired(
                    capture.command.token,
                    JournaledCaptureFailureReason.RECOVERY_OUTSTANDING,
                ),
            )
            is CaptureAttemptRestartRecoveryResult.Outstanding -> finish(
                capture,
                JournaledCaptureResult.ReconciliationRequired(
                    capture.command.token,
                    if (originalReason == JournaledCaptureFailureReason.CLOCK_EXHAUSTED) {
                        originalReason
                    } else {
                        JournaledCaptureFailureReason.RECOVERY_OUTSTANDING
                    },
                ),
            )
        }
    }

    private fun confirmOnExecutor(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        callback: (JournaledConfirmationResult) -> Unit,
    ) {
        val result = try {
            val snapshots = command.outputs.map(authority::snapshot)
            if (
                snapshots.any { it == null } ||
                snapshots.filterNotNull().any {
                    it.stage != CaptureFileOperationStage.FINAL_DURABLE ||
                        it.reconciliationRequired
                }
            ) {
                reconciliation(command, JournaledCaptureFailureReason.JOURNAL_REJECTED)
            } else {
                val floor = snapshots.filterNotNull()
                    .maxOf(CaptureFileOperationSnapshot::updatedAtEpochMillis)
                val confirmedAt = safeNextAfter(floor)
                if (confirmedAt == null) {
                    reconciliation(command, JournaledCaptureFailureReason.CLOCK_EXHAUSTED)
                } else {
                    val targets = exportTargets.targets(command)
                    when (authority.confirm(command, targets, confirmedAt)) {
                        CaptureConfirmationResult.Applied,
                        CaptureConfirmationResult.AlreadyApplied,
                        -> JournaledConfirmationResult.Advanced(command.token)
                        CaptureConfirmationResult.BlockedByDeletion,
                        is CaptureConfirmationResult.Rejected,
                        -> reconciliation(
                            command,
                            JournaledCaptureFailureReason.CONFIRMATION_REJECTED,
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            reconciliation(command, JournaledCaptureFailureReason.CONFIRMATION_REJECTED)
        }
        synchronized(lock) { confirmationActive = false }
        invokeSafely { callback(result) }
    }

    private fun reconciliation(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        reason: JournaledCaptureFailureReason,
    ) = JournaledConfirmationResult.ReconciliationRequired(command.token, reason)

    private fun nextClock(capture: ActiveCapture, floor: Long): Long? {
        val next = safeNextAfter(maxOf(floor, capture.latestClock)) ?: return null
        capture.latestClock = next
        return next
    }

    private fun safeNextAfter(floor: Long): Long? = try {
        clock.nextAfter(floor)?.takeIf { it >= 0L && it > floor }
    } catch (_: Throwable) {
        null
    }

    private fun owns(capture: ActiveCapture, phase: Phase? = null): Boolean = synchronized(lock) {
        active === capture && (phase == null || capture.phase == phase)
    }

    private fun finish(capture: ActiveCapture, result: JournaledCaptureResult) {
        val callback = synchronized(lock) {
            if (active !== capture) return
            active = null
            capture.callback
        }
        invokeSafely { callback(result) }
    }

    private fun dispatch(block: () -> Unit): Boolean = try {
        executor.execute(block)
        true
    } catch (_: Throwable) {
        false
    }

    private fun dispatchCapture(capture: ActiveCapture, block: () -> Unit): Boolean =
        dispatch {
            try {
                block()
            } catch (_: Throwable) {
                finish(
                    capture,
                    JournaledCaptureResult.ReconciliationRequired(
                        capture.command.token,
                        JournaledCaptureFailureReason.RECOVERY_OUTSTANDING,
                    ),
                )
            }
        }

    private fun invokeSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Observers cannot become capture authority or control flow.
        }
    }
}

internal class RoomJournaledCaptureAuthorityAdapter(
    private val repository: RoomShootRepository,
    database: AppDatabase,
) : JournaledCaptureAuthorityPort {
    private val journal = RoomCaptureFileJournal(database)

    override fun register(
        sessionId: String,
        command: ShootEffect.CaptureCommand,
        recordedAtEpochMillis: Long,
    ): AttemptRegistrationResult =
        repository.registerCaptureAttempt(sessionId, command, recordedAtEpochMillis)

    override fun start(
        sessionId: String,
        token: CaptureToken,
        startedAtEpochMillis: Long,
    ): CaptureAttemptStartResult =
        repository.markCaptureAttemptStarted(sessionId, token, startedAtEpochMillis)

    override fun snapshot(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot? =
        journal.snapshot(identity)

    override fun advance(request: CaptureFileAdvanceRequest): CaptureFileJournalResult =
        journal.advance(request)

    override fun settleFailure(
        sessionId: String,
        token: CaptureToken,
        settledAtEpochMillis: Long,
    ): CaptureAttemptSettlementResult =
        repository.settleCaptureAttemptFailure(sessionId, token, settledAtEpochMillis)

    override fun confirm(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        targets: List<CaptureExportTarget>,
        confirmedAtEpochMillis: Long,
    ): CaptureConfirmationResult =
        repository.confirmAndAdvance(command, targets, confirmedAtEpochMillis)
}

internal class JournaledCaptureFileAdapter(
    private val store: JournaledPrivateCaptureStore,
) : JournaledCaptureFilePort {
    override fun claim(snapshot: CaptureFileOperationSnapshot): CaptureFileClaimResult =
        store.claimForWrite(snapshot)

    override fun sync(
        snapshot: CaptureFileOperationSnapshot,
        lease: JournaledCaptureWriteLease,
        capturedAtEpochMillis: Long,
    ): CaptureTempSyncResult = store.syncCapturedTemp(snapshot, lease, capturedAtEpochMillis)

    override fun publish(
        snapshot: CaptureFileOperationSnapshot,
        lease: JournaledCaptureWriteLease,
        evidence: JournaledCaptureEvidence,
    ): CaptureFinalPublicationResult = store.publishFinal(snapshot, lease, evidence)
}

internal fun defaultCaptureExportTargets(
    command: ShootEffect.ConfirmAndAdvanceCapture,
): List<CaptureExportTarget> {
    val tokenDigest = MessageDigest.getInstance("SHA-256")
        .digest(command.token.value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return command.outputs.map { identity ->
        CaptureExportTarget(
            identity = identity,
            targetCollectionUri = "content://media/external_primary/images/media",
            targetVolume = "external_primary",
            intendedDisplayName = "pose-guide-${tokenDigest.take(20)}-${identity.ordinal}.jpg",
            intendedRelativePath = "Pictures/Pose Guide Snap/",
            intendedMimeType = "image/jpeg",
        )
    }
}
