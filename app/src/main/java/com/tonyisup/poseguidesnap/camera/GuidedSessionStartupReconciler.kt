package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.data.CaptureConfirmationResult
import com.tonyisup.poseguidesnap.data.CaptureExportTarget
import com.tonyisup.poseguidesnap.data.CaptureFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage
import com.tonyisup.poseguidesnap.data.GuidedCaptureAttemptState
import com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapResult
import com.tonyisup.poseguidesnap.data.RoomCaptureFileJournal
import com.tonyisup.poseguidesnap.data.RoomShootRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect

enum class GuidedSessionStartupRecoveryResult {
    SETTLED,
    OUTSTANDING,
}

internal interface ReadyCaptureConfirmationPort {
    fun bootstrap(sessionId: String): GuidedSessionBootstrapResult
    fun snapshot(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot?

    fun confirm(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        targets: List<CaptureExportTarget>,
        confirmedAtEpochMillis: Long,
    ): CaptureConfirmationResult
}

/** Completes cleanup or an already durable confirmation before camera admission is reconsidered. */
class GuidedSessionStartupReconciler internal constructor(
    private val recovery: JournaledCaptureRecoveryPort,
    private val confirmation: ReadyCaptureConfirmationPort,
    private val clock: CaptureOperationClock,
    private val exportTargets: CaptureExportTargetFactory =
        CaptureExportTargetFactory(::defaultCaptureExportTargets),
) {
    constructor(
        attemptRecovery: CaptureAttemptStartupReconciler,
        repository: RoomShootRepository,
        database: AppDatabase,
        clock: CaptureOperationClock,
    ) : this(
        recovery = JournaledCaptureRecoveryPort(attemptRecovery::reconcile),
        confirmation = RoomReadyCaptureConfirmationAdapter(repository, database),
        clock = clock,
    )

    fun reconcile(sessionId: String): GuidedSessionStartupRecoveryResult {
        if (sessionId.isBlank()) return GuidedSessionStartupRecoveryResult.OUTSTANDING
        return when (recovery.reconcile(sessionId)) {
            CaptureAttemptRestartRecoveryResult.NoWork,
            CaptureAttemptRestartRecoveryResult.FailedCleaned,
            -> GuidedSessionStartupRecoveryResult.SETTLED
            CaptureAttemptRestartRecoveryResult.ReadyToConfirm -> confirmReady(sessionId)
            is CaptureAttemptRestartRecoveryResult.Outstanding ->
                GuidedSessionStartupRecoveryResult.OUTSTANDING
        }
    }

    private fun confirmReady(sessionId: String): GuidedSessionStartupRecoveryResult {
        val bootstrap = confirmation.bootstrap(sessionId)
            as? GuidedSessionBootstrapResult.ReconciliationRequired
            ?: return GuidedSessionStartupRecoveryResult.OUTSTANDING
        val attempt = bootstrap.snapshot.blockingAttempt
            ?: return GuidedSessionStartupRecoveryResult.OUTSTANDING
        if (
            attempt.state != GuidedCaptureAttemptState.CAPTURING ||
            attempt.poseIndex != bootstrap.snapshot.currentPoseIndex ||
            attempt.poseId != bootstrap.snapshot.orderedPoseIds[attempt.poseIndex]
        ) {
            return GuidedSessionStartupRecoveryResult.OUTSTANDING
        }
        val token = try {
            CaptureToken(attempt.commandToken)
        } catch (_: IllegalArgumentException) {
            return GuidedSessionStartupRecoveryResult.OUTSTANDING
        }
        val outputs = (0..2).map { ordinal -> PrivateOutputIdentity(token, ordinal) }
        val snapshots = outputs.map(confirmation::snapshot)
        if (
            snapshots.any { it == null } ||
            snapshots.filterNotNull().any {
                it.stage != CaptureFileOperationStage.FINAL_DURABLE ||
                    it.reconciliationRequired
            }
        ) {
            return GuidedSessionStartupRecoveryResult.OUTSTANDING
        }
        val floor = maxOf(
            attempt.updatedAtEpochMillis,
            snapshots.filterNotNull().maxOf(CaptureFileOperationSnapshot::updatedAtEpochMillis),
        )
        val confirmedAt = try {
            clock.nextAfter(floor)?.takeIf { it >= 0L && it > floor }
        } catch (_: Throwable) {
            null
        } ?: return GuidedSessionStartupRecoveryResult.OUTSTANDING
        val command = ShootEffect.ConfirmAndAdvanceCapture(
            token = token,
            poseId = attempt.poseId,
            poseIndex = attempt.poseIndex,
            outputs = outputs,
        )
        val result = try {
            confirmation.confirm(command, exportTargets.targets(command), confirmedAt)
        } catch (_: RuntimeException) {
            return GuidedSessionStartupRecoveryResult.OUTSTANDING
        }
        return when (result) {
            CaptureConfirmationResult.Applied,
            CaptureConfirmationResult.AlreadyApplied,
            -> GuidedSessionStartupRecoveryResult.SETTLED
            CaptureConfirmationResult.BlockedByDeletion,
            is CaptureConfirmationResult.Rejected,
            -> GuidedSessionStartupRecoveryResult.OUTSTANDING
        }
    }
}

internal class RoomReadyCaptureConfirmationAdapter(
    private val repository: RoomShootRepository,
    database: AppDatabase,
) : ReadyCaptureConfirmationPort {
    private val journal = RoomCaptureFileJournal(database)

    override fun bootstrap(sessionId: String): GuidedSessionBootstrapResult =
        repository.loadGuidedSessionBootstrap(sessionId)

    override fun snapshot(identity: PrivateOutputIdentity): CaptureFileOperationSnapshot? =
        journal.snapshot(identity)

    override fun confirm(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        targets: List<CaptureExportTarget>,
        confirmedAtEpochMillis: Long,
    ): CaptureConfirmationResult =
        repository.confirmAndAdvance(command, targets, confirmedAtEpochMillis)
}

internal class SystemCaptureOperationClock(
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) : CaptureOperationClock, CaptureRecoveryClock {
    private var lastIssued = -1L

    @Synchronized
    override fun nextAfter(floor: Long): Long? {
        if (floor == Long.MAX_VALUE || lastIssued == Long.MAX_VALUE) return null
        val minimum = maxOf(floor, lastIssued) + 1L
        val wall = try {
            wallClockMillis()
        } catch (_: RuntimeException) {
            return null
        }
        if (wall < 0L) return null
        return maxOf(minimum, wall).also { lastIssued = it }
    }
}
