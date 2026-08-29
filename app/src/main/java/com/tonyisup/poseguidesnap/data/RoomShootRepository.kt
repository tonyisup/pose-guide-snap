package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.db.CaptureAttemptEntity
import com.tonyisup.poseguidesnap.data.db.CaptureConfirmationReceiptEntity
import com.tonyisup.poseguidesnap.data.db.CaptureExportOutboxEntity
import com.tonyisup.poseguidesnap.data.db.CaptureExportOutputEntity
import com.tonyisup.poseguidesnap.data.db.DuplicateReceiptAuthority
import com.tonyisup.poseguidesnap.data.db.PrivateCaptureOutputEntity
import com.tonyisup.poseguidesnap.data.db.SessionReceiptStep
import com.tonyisup.poseguidesnap.data.db.ShootEntity
import com.tonyisup.poseguidesnap.data.db.ShootSessionEntity
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.CaptureTrigger
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.util.concurrent.Callable

sealed interface AttemptRegistrationResult {
    data object Registered : AttemptRegistrationResult
    data object AlreadyRegistered : AttemptRegistrationResult
    data class Rejected(val reason: AttemptRegistrationRejectionReason) : AttemptRegistrationResult
}

enum class AttemptRegistrationRejectionReason {
    INVALID_SESSION_ID,
    INVALID_TIMESTAMP,
    INVALID_COMMAND_OUTPUTS,
    COUNTER_EXHAUSTED,
    UNKNOWN_SESSION,
    INACTIVE_SESSION,
    BLOCKED_BY_DELETION,
    STALE_POSE,
    STALE_ATTEMPT_NUMBER,
    FUTURE_ATTEMPT_NUMBER,
    TOKEN_CONFLICT,
    ATTEMPT_NUMBER_CONFLICT,
    COUNTER_CAS_FAILED,
}

sealed interface CaptureStartAuthorizationResult {
    data object Started : CaptureStartAuthorizationResult
    data object AlreadyStarted : CaptureStartAuthorizationResult
    data object BlockedByDeletion : CaptureStartAuthorizationResult
    data class Rejected(val reason: CaptureStartRejectionReason) : CaptureStartAuthorizationResult
}

enum class CaptureStartRejectionReason {
    INVALID_SESSION_ID,
    INVALID_TIMESTAMP,
    UNKNOWN_ATTEMPT,
    TOKEN_SESSION_CONFLICT,
    WRONG_STATE,
    INACTIVE_SESSION,
    STALE_POSE,
    CAS_FAILED,
}

internal object CaptureAttemptRegistrationPolicy {
    fun validate(
        sessionId: String,
        command: ShootEffect.CaptureCommand,
        recordedAtEpochMillis: Long,
    ): AttemptRegistrationRejectionReason? = when {
        sessionId.isBlank() -> AttemptRegistrationRejectionReason.INVALID_SESSION_ID
        recordedAtEpochMillis < 0L -> AttemptRegistrationRejectionReason.INVALID_TIMESTAMP
        command.outputs != (0..2).map { ordinal ->
            PrivateOutputIdentity(command.token, ordinal)
        } -> AttemptRegistrationRejectionReason.INVALID_COMMAND_OUTPUTS
        command.attemptNumber == Long.MAX_VALUE ->
            AttemptRegistrationRejectionReason.COUNTER_EXHAUSTED
        else -> null
    }
}

internal object CaptureStartAuthorizationPolicy {
    fun validate(
        sessionId: String,
        authorizedAtEpochMillis: Long,
    ): CaptureStartRejectionReason? = when {
        sessionId.isBlank() -> CaptureStartRejectionReason.INVALID_SESSION_ID
        authorizedAtEpochMillis < 0L -> CaptureStartRejectionReason.INVALID_TIMESTAMP
        else -> null
    }
}

class RoomShootRepository(
    database: AppDatabase,
) {
    private val database = database
    private val captureAttemptDao = database.captureAttemptDao()
    private val captureConfirmationDao = database.captureConfirmationDao()
    private val deletionExportDao = database.deletionExportDao()

    fun registerCaptureAttempt(
        sessionId: String,
        command: ShootEffect.CaptureCommand,
        recordedAtEpochMillis: Long,
    ): AttemptRegistrationResult {
        CaptureAttemptRegistrationPolicy.validate(sessionId, command, recordedAtEpochMillis)
            ?.let { reason -> return AttemptRegistrationResult.Rejected(reason) }

        return try {
            database.runInTransaction(
                Callable {
                    registerCaptureAttemptInTransaction(
                        sessionId = sessionId,
                        command = command,
                        recordedAtEpochMillis = recordedAtEpochMillis,
                    )
                },
            )
        } catch (_: CounterCasFailedException) {
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.COUNTER_CAS_FAILED,
            )
        }
    }

    fun authorizeCaptureStart(
        sessionId: String,
        token: CaptureToken,
        authorizedAtEpochMillis: Long,
    ): CaptureStartAuthorizationResult {
        CaptureStartAuthorizationPolicy.validate(sessionId, authorizedAtEpochMillis)
            ?.let { reason -> return CaptureStartAuthorizationResult.Rejected(reason) }

        val affectedRows = captureAttemptDao.authorizeCaptureStart(
            sessionId = sessionId,
            commandToken = token.value,
            authorizedAtEpochMillis = authorizedAtEpochMillis,
        )
        if (affectedRows == 1) {
            return CaptureStartAuthorizationResult.Started
        }
        check(affectedRows == 0) { "capture start compare-and-set affected an invalid row count" }

        return database.runInTransaction(
            Callable { classifyFailedCaptureStart(sessionId, token) },
        )
    }

    fun beginShootDeletion(
        shootId: String,
        requestedAtEpochMillis: Long,
    ): BeginShootDeletionResult {
        BeginShootDeletionPolicy.validate(shootId, requestedAtEpochMillis)
            ?.let { reason -> return BeginShootDeletionResult.Rejected(reason) }

        return try {
            database.runInTransaction(
                Callable {
                    beginShootDeletionInTransaction(
                        shootId = shootId,
                        requestedAtEpochMillis = requestedAtEpochMillis,
                    )
                },
            )
        } catch (_: DeletionCasFailedException) {
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.TRANSACTION_CAS_FAILED,
            )
        } catch (_: DeletionAuthorityInconsistentException) {
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.AUTHORITY_INCONSISTENT,
            )
        }
    }

    fun claimExportOutput(
        identity: PrivateOutputIdentity,
        claimToken: ExportClaimToken,
        claimedAtEpochMillis: Long,
    ): ExportOutputClaimResult {
        ExportOutputClaimPolicy.validate(claimedAtEpochMillis)
            ?.let { reason -> return ExportOutputClaimResult.Rejected(reason) }

        return try {
            database.runInTransaction(
                Callable {
                    claimExportOutputInTransaction(
                        identity = identity,
                        claimToken = claimToken,
                        claimedAtEpochMillis = claimedAtEpochMillis,
                    )
                },
            )
        } catch (_: ExportClaimCasFailedException) {
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.TRANSACTION_CAS_FAILED,
            )
        } catch (_: ExportClaimAuthorityInconsistentException) {
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.AUTHORITY_INCONSISTENT,
            )
        }
    }

    fun confirmAndAdvance(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        privateOutputs: List<DurablePrivateOutput>,
        exportTargets: List<CaptureExportTarget>,
        confirmedAtEpochMillis: Long,
    ): CaptureConfirmationResult {
        val privateOutputsSnapshot = privateOutputs.toList()
        val exportTargetsSnapshot = exportTargets.toList()
        CaptureConfirmationPolicy.validate(
            command = command,
            privateOutputs = privateOutputsSnapshot,
            exportTargets = exportTargetsSnapshot,
            confirmedAtEpochMillis = confirmedAtEpochMillis,
        )?.let { reason -> return CaptureConfirmationResult.Rejected(reason) }

        return try {
            database.runInTransaction(
                Callable {
                    confirmAndAdvanceInTransaction(
                        command = command,
                        privateOutputs = privateOutputsSnapshot,
                        exportTargets = exportTargetsSnapshot,
                        confirmedAtEpochMillis = confirmedAtEpochMillis,
                    )
                },
            )
        } catch (_: ConfirmationCasFailedException) {
            CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.TRANSACTION_CAS_FAILED,
            )
        } catch (_: ConfirmationCardinalityException) {
            CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.TRANSACTION_CARDINALITY_FAILURE,
            )
        }
    }

    private fun claimExportOutputInTransaction(
        identity: PrivateOutputIdentity,
        claimToken: ExportClaimToken,
        claimedAtEpochMillis: Long,
    ): ExportOutputClaimResult {
        deletionExportDao.findOutputByClaimToken(claimToken.value)?.let { owned ->
            if (
                owned.commandToken != identity.token.value ||
                owned.burstOrdinal != identity.ordinal
            ) {
                return ExportOutputClaimResult.Rejected(
                    ExportOutputClaimRejectionReason.CLAIM_TOKEN_CONFLICT,
                )
            }
            val authority = loadClaimAuthority(identity, owned)
            val replayStage = owned.exportAuthorityStage()
                ?: throw ExportClaimAuthorityInconsistentException()
            if (owned.claimToken != claimToken.value) {
                throw ExportClaimAuthorityInconsistentException()
            }
            validateClaimAuthority(authority)
            return ExportOutputClaimResult.IdempotentReplay(replayStage)
        }

        val output = deletionExportDao.findOutput(identity.token.value, identity.ordinal)
            ?: return ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.UNKNOWN_OUTPUT,
            )
        val authority = loadClaimAuthority(identity, output)
        validateClaimAuthority(authority)
        if (output.claimToken != null) {
            return ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.OWNED_BY_DIFFERENT_CLAIM,
            )
        }
        if (
            authority.shoot.lifecycleState != ACTIVE ||
            authority.shoot.deletionGeneration != output.deletionGeneration
        ) {
            return ExportOutputClaimResult.BlockedByDeletion
        }
        if (
            authority.outbox.lifecycleState != PENDING ||
            !output.isUntouchedPendingExport()
        ) {
            return ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.NOT_CLAIMABLE,
            )
        }

        val shootId = authority.shoot.shootId
        val shootBefore = authority.shoot
        val sessionsBefore = deletionExportDao.findSessionsForShoot(shootId)
        val attemptsBefore = deletionExportDao.findAttemptsForShoot(shootId)
        val privateOutputsBefore = deletionExportDao.findPrivateOutputsForShoot(shootId)
        val receiptsBefore = deletionExportDao.findExportReceiptsForShoot(shootId)
        val outboxesBefore = deletionExportDao.findOutboxesForShoot(shootId)
        val outputsBefore = deletionExportDao.findOutputsForShoot(shootId)
        if (
            deletionExportDao.claimOutput(
                commandToken = identity.token.value,
                burstOrdinal = identity.ordinal,
                claimToken = claimToken.value,
                claimedAtEpochMillis = claimedAtEpochMillis,
            ) != 1
        ) {
            throw ExportClaimCasFailedException()
        }

        val expectedOutput = output.copy(
            lifecycleState = CLAIMED,
            claimToken = claimToken.value,
            updatedAtEpochMillis = claimedAtEpochMillis,
        )
        val expectedOutputs = outputsBefore.map { candidate ->
            if (
                candidate.commandToken == output.commandToken &&
                candidate.burstOrdinal == output.burstOrdinal
            ) {
                expectedOutput
            } else {
                candidate
            }
        }
        if (
            deletionExportDao.findShoot(shootId) != shootBefore ||
            deletionExportDao.findSessionsForShoot(shootId) != sessionsBefore ||
            deletionExportDao.findAttemptsForShoot(shootId) != attemptsBefore ||
            deletionExportDao.findPrivateOutputsForShoot(shootId) != privateOutputsBefore ||
            deletionExportDao.findExportReceiptsForShoot(shootId) != receiptsBefore ||
            deletionExportDao.findOutboxesForShoot(shootId) != outboxesBefore ||
            deletionExportDao.findOutputsForShoot(shootId) != expectedOutputs
        ) {
            throw ExportClaimAuthorityInconsistentException()
        }

        return ExportOutputClaimResult.Acquired(
            expectedOutput.toExportOutputClaim(identity, claimToken),
        )
    }

    private fun loadClaimAuthority(
        identity: PrivateOutputIdentity,
        output: CaptureExportOutputEntity,
    ): ClaimAuthority {
        val outbox = deletionExportDao.findOutbox(output.commandToken)
            ?: throw ExportClaimAuthorityInconsistentException()
        val receipt = deletionExportDao.findReceipt(output.commandToken)
            ?: throw ExportClaimAuthorityInconsistentException()
        val attempt = deletionExportDao.findAttempt(output.commandToken)
            ?: throw ExportClaimAuthorityInconsistentException()
        val session = deletionExportDao.findSession(attempt.sessionId)
            ?: throw ExportClaimAuthorityInconsistentException()
        val shoot = deletionExportDao.findShoot(session.shootId)
            ?: throw ExportClaimAuthorityInconsistentException()
        if (
            output.commandToken != identity.token.value ||
            output.burstOrdinal != identity.ordinal ||
            outbox.commandToken != output.commandToken ||
            receipt.commandToken != output.commandToken ||
            attempt.commandToken != output.commandToken
        ) {
            throw ExportClaimAuthorityInconsistentException()
        }
        if (output.deletionGeneration < 0L) {
            throw ExportClaimAuthorityInconsistentException()
        }
        try {
            CaptureExportTarget(
                identity = identity,
                targetCollectionUri = output.targetCollectionUri,
                targetVolume = output.targetVolume,
                intendedDisplayName = output.intendedDisplayName,
                intendedRelativePath = output.intendedRelativePath,
                intendedMimeType = output.intendedMimeType,
            )
        } catch (_: IllegalArgumentException) {
            throw ExportClaimAuthorityInconsistentException()
        }
        return ClaimAuthority(
            output = output,
            outbox = outbox,
            receipt = receipt,
            attempt = attempt,
            session = session,
            shoot = shoot,
        )
    }

    private fun validateClaimAuthority(authority: ClaimAuthority) {
        if (
            authority.attempt.lifecycleState != CONFIRMED ||
            authority.attempt.reconciliationRequired ||
            authority.attempt.confirmedAtEpochMillis == null ||
            authority.receipt.appliedDeletionGeneration !=
                authority.attempt.capturedDeletionGeneration ||
            authority.output.deletionGeneration != authority.receipt.appliedDeletionGeneration ||
            authority.receipt.appliedAtEpochMillis != authority.attempt.confirmedAtEpochMillis ||
            authority.outbox.createdAtEpochMillis != authority.receipt.appliedAtEpochMillis ||
            authority.output.createdAtEpochMillis != authority.receipt.appliedAtEpochMillis
        ) {
            throw ExportClaimAuthorityInconsistentException()
        }
    }

    private fun CaptureExportOutputEntity.exportAuthorityStage(): ExportAuthorityStage? =
        when (lifecycleState) {
            CLAIMED -> if (claimToken != null && mediaUriString == null && ambiguityState == NONE) {
                ExportAuthorityStage.CLAIMED
            } else {
                null
            }
            CREATE_STARTED ->
                if (claimToken != null && mediaUriString == null && ambiguityState == NONE) {
                    ExportAuthorityStage.CREATE_STARTED
                } else {
                    null
                }
            URI_KNOWN ->
                if (
                    claimToken != null &&
                    !mediaUriString.isNullOrBlank() &&
                    mediaUriString.startsWith("content://") &&
                    ambiguityState == NONE
                ) {
                    ExportAuthorityStage.URI_KNOWN
                } else {
                    null
                }
            EXPORTED ->
                if (
                    claimToken != null &&
                    !mediaUriString.isNullOrBlank() &&
                    mediaUriString.startsWith("content://") &&
                    ambiguityState == NONE
                ) {
                    ExportAuthorityStage.EXPORTED
                } else {
                    null
                }
            AMBIGUOUS ->
                if (claimToken != null && ambiguityState != NONE) {
                    ExportAuthorityStage.AMBIGUOUS
                } else {
                    null
                }
            else -> null
        }

    private fun CaptureExportOutputEntity.toExportOutputClaim(
        identity: PrivateOutputIdentity,
        claimToken: ExportClaimToken,
    ): ExportOutputClaim = ExportOutputClaim(
        identity = identity,
        claimToken = claimToken,
        deletionGeneration = deletionGeneration,
        targetCollectionUri = targetCollectionUri,
        targetVolume = targetVolume,
        intendedDisplayName = intendedDisplayName,
        intendedRelativePath = intendedRelativePath,
        intendedMimeType = intendedMimeType,
    )

    private fun beginShootDeletionInTransaction(
        shootId: String,
        requestedAtEpochMillis: Long,
    ): BeginShootDeletionResult {
        val shoot = deletionExportDao.findShoot(shootId)
            ?: return BeginShootDeletionResult.UnknownShoot
        if (shoot.deletionGeneration < 0L) {
            throw DeletionAuthorityInconsistentException()
        }
        if (shoot.lifecycleState == DELETING) {
            return BeginShootDeletionResult.AlreadyDeleting(shoot.deletionGeneration)
        }
        if (shoot.lifecycleState != ACTIVE) {
            return BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.UNSUPPORTED_SHOOT_STATE,
            )
        }
        if (shoot.deletionGeneration == Long.MAX_VALUE) {
            return BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.GENERATION_EXHAUSTED,
            )
        }

        val sessionsBefore = deletionExportDao.findSessionsForShoot(shootId)
        val attemptsBefore = deletionExportDao.findAttemptsForShoot(shootId)
        val privateOutputsBefore = deletionExportDao.findPrivateOutputsForShoot(shootId)
        val exportAttemptsBefore = deletionExportDao.findExportAttemptsForShoot(shootId)
        val receiptsBefore = deletionExportDao.findExportReceiptsForShoot(shootId)
        val outboxesBefore = deletionExportDao.findOutboxesForShoot(shootId)
        val outputsBefore = deletionExportDao.findOutputsForShoot(shootId)
        validateDeletionAuthority(
            previousGeneration = shoot.deletionGeneration,
            attempts = exportAttemptsBefore,
            receipts = receiptsBefore,
            outboxes = outboxesBefore,
            outputs = outputsBefore,
        )

        val cancellableOutputKeys = outputsBefore
            .filter { output -> output.isUntouchedPendingExport() }
            .mapTo(linkedSetOf()) { output -> output.commandToken to output.burstOrdinal }
        val outputsByToken = outputsBefore.groupBy(CaptureExportOutputEntity::commandToken)
        val cancelledOutboxTokens = outboxesBefore
            .filter { outbox ->
                outbox.lifecycleState == PENDING &&
                    outputsByToken.getValue(outbox.commandToken).all { output ->
                        output.lifecycleState == CANCELLED ||
                            (output.commandToken to output.burstOrdinal) in cancellableOutputKeys
                    }
            }
            .mapTo(linkedSetOf(), CaptureExportOutboxEntity::commandToken)

        if (
            deletionExportDao.beginDeletion(
                shootId = shootId,
                previousGeneration = shoot.deletionGeneration,
                requestedAtEpochMillis = requestedAtEpochMillis,
            ) != 1
        ) {
            throw DeletionCasFailedException()
        }
        if (
            deletionExportDao.cancelUntouchedOutputs(
                shootId = shootId,
                previousGeneration = shoot.deletionGeneration,
                requestedAtEpochMillis = requestedAtEpochMillis,
            ) != cancellableOutputKeys.size
        ) {
            throw DeletionAuthorityInconsistentException()
        }
        if (
            deletionExportDao.cancelFullyCancelledOutboxes(
                shootId = shootId,
                requestedAtEpochMillis = requestedAtEpochMillis,
            ) != cancelledOutboxTokens.size
        ) {
            throw DeletionAuthorityInconsistentException()
        }

        val expectedShoot = shoot.copy(
            lifecycleState = DELETING,
            deletionGeneration = shoot.deletionGeneration + 1L,
            updatedAtEpochMillis = requestedAtEpochMillis,
        )
        val expectedOutputs = outputsBefore.map { output ->
            if ((output.commandToken to output.burstOrdinal) in cancellableOutputKeys) {
                output.copy(
                    lifecycleState = CANCELLED,
                    updatedAtEpochMillis = requestedAtEpochMillis,
                )
            } else {
                output
            }
        }
        val expectedOutboxes = outboxesBefore.map { outbox ->
            if (outbox.commandToken in cancelledOutboxTokens) {
                outbox.copy(
                    lifecycleState = CANCELLED,
                    updatedAtEpochMillis = requestedAtEpochMillis,
                )
            } else {
                outbox
            }
        }
        if (
            deletionExportDao.findShoot(shootId) != expectedShoot ||
            deletionExportDao.findSessionsForShoot(shootId) != sessionsBefore ||
            deletionExportDao.findAttemptsForShoot(shootId) != attemptsBefore ||
            deletionExportDao.findPrivateOutputsForShoot(shootId) != privateOutputsBefore ||
            deletionExportDao.findExportAttemptsForShoot(shootId) != exportAttemptsBefore ||
            deletionExportDao.findExportReceiptsForShoot(shootId) != receiptsBefore ||
            deletionExportDao.findOutboxesForShoot(shootId) != expectedOutboxes ||
            deletionExportDao.findOutputsForShoot(shootId) != expectedOutputs
        ) {
            throw DeletionAuthorityInconsistentException()
        }

        return BeginShootDeletionResult.Began(
            generation = expectedShoot.deletionGeneration,
            cancelledOutputCount = cancellableOutputKeys.size,
            cancelledOutboxCount = cancelledOutboxTokens.size,
            retainedOutputCount = outputsBefore.size - cancellableOutputKeys.size,
        )
    }

    private fun validateDeletionAuthority(
        previousGeneration: Long,
        attempts: List<CaptureAttemptEntity>,
        receipts: List<CaptureConfirmationReceiptEntity>,
        outboxes: List<CaptureExportOutboxEntity>,
        outputs: List<CaptureExportOutputEntity>,
    ) {
        val outboxTokens = outboxes.mapTo(linkedSetOf(), CaptureExportOutboxEntity::commandToken)
        val attemptsByToken = attempts.associateBy(CaptureAttemptEntity::commandToken)
        val receiptsByToken = receipts.associateBy(CaptureConfirmationReceiptEntity::commandToken)
        val outputsByToken = outputs.groupBy(CaptureExportOutputEntity::commandToken)
        if (
            attemptsByToken.size != attempts.size ||
            receiptsByToken.size != receipts.size ||
            attemptsByToken.keys != outboxTokens ||
            receiptsByToken.keys != outboxTokens ||
            outputsByToken.keys != outboxTokens
        ) {
            throw DeletionAuthorityInconsistentException()
        }

        outboxes.forEach { outbox ->
            val attempt = attemptsByToken.getValue(outbox.commandToken)
            val receipt = receiptsByToken.getValue(outbox.commandToken)
            val ownedOutputs = outputsByToken.getValue(outbox.commandToken)
            if (
                attempt.lifecycleState != CONFIRMED ||
                attempt.reconciliationRequired ||
                attempt.confirmedAtEpochMillis == null ||
                attempt.capturedDeletionGeneration != previousGeneration ||
                receipt.appliedDeletionGeneration != previousGeneration ||
                receipt.appliedAtEpochMillis != attempt.confirmedAtEpochMillis ||
                outbox.createdAtEpochMillis != receipt.appliedAtEpochMillis ||
                ownedOutputs.size != BURST_OUTPUT_COUNT ||
                ownedOutputs.map(CaptureExportOutputEntity::burstOrdinal) != listOf(0, 1, 2) ||
                ownedOutputs.any { output ->
                    output.deletionGeneration != previousGeneration ||
                        output.createdAtEpochMillis != receipt.appliedAtEpochMillis
                }
            ) {
                throw DeletionAuthorityInconsistentException()
            }
        }
    }

    private fun CaptureExportOutputEntity.isUntouchedPendingExport(): Boolean =
        lifecycleState == PENDING &&
            claimToken == null &&
            mediaUriString == null &&
            ambiguityState == NONE

    private fun confirmAndAdvanceInTransaction(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        privateOutputs: List<DurablePrivateOutput>,
        exportTargets: List<CaptureExportTarget>,
        confirmedAtEpochMillis: Long,
    ): CaptureConfirmationResult {
        val commandToken = command.token.value
        captureConfirmationDao.findReceipt(commandToken)?.let { receipt ->
            return classifyDuplicateConfirmation(
                command = command,
                privateOutputs = privateOutputs,
                exportTargets = exportTargets,
                receipt = receipt,
            )
        }

        val attempt = captureConfirmationDao.findAttempt(commandToken)
            ?: return CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.UNKNOWN_ATTEMPT,
            )
        if (!attempt.matches(command)) {
            return CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.TOKEN_POSE_CONFLICT,
            )
        }
        if (
            attempt.lifecycleState != CAPTURING ||
            attempt.reconciliationRequired ||
            attempt.confirmedAtEpochMillis != null
        ) {
            return CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.WRONG_ATTEMPT_STATE,
            )
        }
        check(attempt.capturedDeletionGeneration >= 0L) {
            "capture confirmation deletion generation is invalid"
        }

        val session = captureConfirmationDao.findSession(attempt.sessionId)
            ?: throw IllegalStateException("capture confirmation has no owning session")
        val shoot = captureConfirmationDao.findShoot(session.shootId)
            ?: throw IllegalStateException("capture confirmation has no owning shoot")
        check(shoot.deletionGeneration >= 0L) {
            "capture confirmation shoot deletion generation is invalid"
        }
        if (
            shoot.lifecycleState != ACTIVE ||
            shoot.deletionGeneration != attempt.capturedDeletionGeneration
        ) {
            return CaptureConfirmationResult.BlockedByDeletion
        }
        if (session.lifecycleState != ACTIVE) {
            return CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.INACTIVE_SESSION,
            )
        }
        if (
            session.currentPoseIndex != command.poseIndex ||
            session.currentPoseIndex != attempt.poseIndex
        ) {
            return CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.STALE_POSE,
            )
        }

        val currentPose = captureConfirmationDao.findPose(
            shootId = session.shootId,
            poseIndex = session.currentPoseIndex,
        ) ?: throw IllegalStateException("capture confirmation has no current pose")
        if (currentPose.poseId != command.poseId || currentPose.poseId != attempt.poseId) {
            return CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.STALE_POSE,
            )
        }

        val remainingPoseCount = captureConfirmationDao.countPosesAfter(
            shootId = session.shootId,
            poseIndex = session.currentPoseIndex,
        )
        val toPoseIndex = if (remainingPoseCount == 0) {
            null
        } else {
            check(session.currentPoseIndex < Int.MAX_VALUE) {
                "capture confirmation pose index cannot advance"
            }
            val expectedNextPoseIndex = session.currentPoseIndex + 1
            captureConfirmationDao.findPose(session.shootId, expectedNextPoseIndex)
                ?: throw IllegalStateException("capture confirmation pose sequence has a gap")
            expectedNextPoseIndex
        }

        captureConfirmationDao.insertPrivateOutputs(
            privateOutputs.map { output ->
                PrivateCaptureOutputEntity(
                    commandToken = commandToken,
                    burstOrdinal = output.identity.ordinal,
                    relativePath = output.relativePath,
                    byteCount = output.byteCount,
                    durabilityState = DURABLE,
                    capturedAtEpochMillis = output.capturedAtEpochMillis,
                    integrityMetadata = output.integrityMetadata,
                )
            },
        )

        val confirmedAttemptRows = captureConfirmationDao.confirmAttempt(
            commandToken = commandToken,
            sessionId = attempt.sessionId,
            poseId = attempt.poseId,
            poseIndex = attempt.poseIndex,
            attemptNumber = attempt.attemptNumber,
            triggerType = attempt.triggerType,
            capturedDeletionGeneration = attempt.capturedDeletionGeneration,
            confirmedAtEpochMillis = confirmedAtEpochMillis,
        )
        if (confirmedAttemptRows != 1) {
            throw ConfirmationCasFailedException()
        }

        val advancedSessionRows = captureConfirmationDao.advanceSession(
            sessionId = session.sessionId,
            shootId = session.shootId,
            expectedPoseIndex = session.currentPoseIndex,
            newPoseIndex = toPoseIndex ?: session.currentPoseIndex,
            newLifecycleState = if (toPoseIndex == null) COMPLETED else ACTIVE,
            expectedDeletionGeneration = attempt.capturedDeletionGeneration,
            confirmedAtEpochMillis = confirmedAtEpochMillis,
        )
        if (advancedSessionRows != 1) {
            throw ConfirmationCasFailedException()
        }

        captureConfirmationDao.insertReceipt(
            CaptureConfirmationReceiptEntity(
                commandToken = commandToken,
                fromPoseIndex = session.currentPoseIndex,
                toPoseIndex = toPoseIndex,
                appliedDeletionGeneration = attempt.capturedDeletionGeneration,
                appliedAtEpochMillis = confirmedAtEpochMillis,
            ),
        )
        captureConfirmationDao.insertOutbox(
            CaptureExportOutboxEntity(
                commandToken = commandToken,
                lifecycleState = PENDING,
                createdAtEpochMillis = confirmedAtEpochMillis,
                updatedAtEpochMillis = confirmedAtEpochMillis,
                retryMetadata = null,
            ),
        )
        captureConfirmationDao.insertExportOutputs(
            exportTargets.map { target ->
                CaptureExportOutputEntity(
                    commandToken = commandToken,
                    burstOrdinal = target.identity.ordinal,
                    targetCollectionUri = target.targetCollectionUri,
                    targetVolume = target.targetVolume,
                    intendedDisplayName = target.intendedDisplayName,
                    intendedRelativePath = target.intendedRelativePath,
                    intendedMimeType = target.intendedMimeType,
                    lifecycleState = PENDING,
                    claimToken = null,
                    mediaUriString = null,
                    ambiguityState = NONE,
                    deletionGeneration = attempt.capturedDeletionGeneration,
                    createdAtEpochMillis = confirmedAtEpochMillis,
                    updatedAtEpochMillis = confirmedAtEpochMillis,
                )
            },
        )

        if (
            captureConfirmationDao.countPrivateOutputs(commandToken) != BURST_OUTPUT_COUNT ||
            captureConfirmationDao.countReceipts(commandToken) != 1 ||
            captureConfirmationDao.countOutboxes(commandToken) != 1 ||
            captureConfirmationDao.countExportOutputs(commandToken) != BURST_OUTPUT_COUNT
        ) {
            throw ConfirmationCardinalityException()
        }
        return CaptureConfirmationResult.Applied
    }

    private fun classifyDuplicateConfirmation(
        command: ShootEffect.ConfirmAndAdvanceCapture,
        privateOutputs: List<DurablePrivateOutput>,
        exportTargets: List<CaptureExportTarget>,
        receipt: DuplicateReceiptAuthority,
    ): CaptureConfirmationResult {
        val attempt = captureConfirmationDao.findAttempt(command.token.value)
            ?: throw IllegalStateException("capture confirmation receipt has no attempt")
        if (!attempt.matches(command)) {
            return CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.TOKEN_POSE_CONFLICT,
            )
        }
        check(attempt.lifecycleState == CONFIRMED) {
            "capture confirmation receipt has an unconfirmed attempt"
        }
        check(attempt.capturedDeletionGeneration >= 0L) {
            "capture confirmation attempt deletion generation is invalid"
        }
        check(!attempt.reconciliationRequired) {
            "capture confirmation attempt requires reconciliation"
        }
        check(attempt.attemptNumber >= 0L) {
            "capture confirmation attempt number is invalid"
        }
        check(CaptureTrigger.entries.any { trigger -> trigger.name == attempt.triggerType }) {
            "capture confirmation attempt trigger is invalid"
        }
        check(
            attempt.confirmedAtEpochMillis != null &&
                attempt.updatedAtEpochMillis == attempt.confirmedAtEpochMillis,
        ) {
            "capture confirmation attempt timestamps are inconsistent"
        }

        val session = captureConfirmationDao.findSession(attempt.sessionId)
            ?: throw IllegalStateException("capture confirmation attempt has no session")
        val shoot = captureConfirmationDao.findShoot(session.shootId)
            ?: throw IllegalStateException("capture confirmation session has no shoot")
        val confirmedPose = captureConfirmationDao.findPose(shoot.shootId, attempt.poseIndex)
            ?: throw IllegalStateException("capture confirmation attempt has no pose")
        check(confirmedPose.poseId == attempt.poseId) {
            "capture confirmation attempt pose identity is inconsistent"
        }
        check(receipt.fromPoseIndex == attempt.poseIndex) {
            "capture confirmation receipt source pose is inconsistent"
        }
        check(receipt.appliedDeletionGeneration == attempt.capturedDeletionGeneration) {
            "capture confirmation receipt deletion generation is inconsistent"
        }
        check(receipt.appliedDeletionGeneration >= 0L) {
            "capture confirmation receipt deletion generation is invalid"
        }
        check(receipt.appliedAtEpochMillis == attempt.confirmedAtEpochMillis) {
            "capture confirmation receipt timestamp is inconsistent"
        }

        val poseCountAfterReceipt = captureConfirmationDao.countPosesAfter(
            shootId = shoot.shootId,
            poseIndex = receipt.fromPoseIndex,
        )
        if (receipt.toPoseIndex == null) {
            check(poseCountAfterReceipt == 0) {
                "capture confirmation final receipt has remaining poses"
            }
        } else {
            check(
                receipt.fromPoseIndex < Int.MAX_VALUE &&
                    receipt.toPoseIndex == receipt.fromPoseIndex + 1,
            ) {
                "capture confirmation receipt destination is inconsistent"
            }
            check(captureConfirmationDao.findPose(shoot.shootId, receipt.toPoseIndex) != null) {
                "capture confirmation receipt destination pose is missing"
            }
        }
        validateDuplicateReceiptSessionCoherence(
            receipt = receipt,
            sessionId = session.sessionId,
            shootId = shoot.shootId,
            currentPoseIndex = session.currentPoseIndex,
            sessionLifecycleState = session.lifecycleState,
        )

        val persistedPrivateOutputs = captureConfirmationDao.findPrivateOutputs(
            attempt.commandToken,
        )
        check(persistedPrivateOutputs.size == BURST_OUTPUT_COUNT) {
            "capture confirmation private output cardinality is inconsistent"
        }
        val persistedPrivateDtos = persistedPrivateOutputs.mapIndexed { ordinal, output ->
            check(
                output.commandToken == attempt.commandToken &&
                    output.burstOrdinal == ordinal &&
                    output.durabilityState == DURABLE,
            ) {
                "capture confirmation private output authority is inconsistent"
            }
            DurablePrivateOutput(
                identity = PrivateOutputIdentity(command.token, output.burstOrdinal),
                relativePath = output.relativePath,
                byteCount = output.byteCount,
                capturedAtEpochMillis = output.capturedAtEpochMillis,
                integrityMetadata = output.integrityMetadata,
            )
        }
        if (persistedPrivateDtos != privateOutputs) {
            return CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.INVALID_PRIVATE_OUTPUTS,
            )
        }

        val outbox = captureConfirmationDao.findOutbox(attempt.commandToken)
            ?: throw IllegalStateException("capture confirmation has no export outbox")
        check(
            outbox.commandToken == attempt.commandToken &&
                outbox.createdAtEpochMillis == receipt.appliedAtEpochMillis,
        ) {
            "capture confirmation export outbox authority is inconsistent"
        }
        val persistedExportOutputs = captureConfirmationDao.findExportOutputs(
            attempt.commandToken,
        )
        check(persistedExportOutputs.size == BURST_OUTPUT_COUNT) {
            "capture confirmation export output cardinality is inconsistent"
        }
        val persistedExportTargets = persistedExportOutputs.mapIndexed { ordinal, output ->
            check(
                output.commandToken == attempt.commandToken &&
                    output.burstOrdinal == ordinal &&
                    output.deletionGeneration == receipt.appliedDeletionGeneration &&
                    output.createdAtEpochMillis == receipt.appliedAtEpochMillis,
            ) {
                "capture confirmation export output authority is inconsistent"
            }
            CaptureExportTarget(
                identity = PrivateOutputIdentity(command.token, output.burstOrdinal),
                targetCollectionUri = output.targetCollectionUri,
                targetVolume = output.targetVolume,
                intendedDisplayName = output.intendedDisplayName,
                intendedRelativePath = output.intendedRelativePath,
                intendedMimeType = output.intendedMimeType,
            )
        }
        if (persistedExportTargets != exportTargets) {
            return CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.INVALID_EXPORT_TARGETS,
            )
        }
        return CaptureConfirmationResult.AlreadyApplied
    }

    private fun validateDuplicateReceiptSessionCoherence(
        receipt: DuplicateReceiptAuthority,
        sessionId: String,
        shootId: String,
        currentPoseIndex: Int,
        sessionLifecycleState: String,
    ) {
        val stepsByFromPose = LinkedHashMap<Int, SessionReceiptStep>()
        captureConfirmationDao.findReceiptStepsForSession(sessionId).forEach { step ->
            check(step.attemptLifecycleState == CONFIRMED) {
                "capture confirmation receipt chain has an unconfirmed attempt"
            }
            check(step.fromPoseIndex == step.attemptPoseIndex) {
                "capture confirmation receipt chain ownership is inconsistent"
            }
            check(stepsByFromPose.put(step.fromPoseIndex, step) == null) {
                "capture confirmation receipt chain has duplicate source ownership"
            }
            if (step.toPoseIndex == null) {
                check(captureConfirmationDao.countPosesAfter(shootId, step.fromPoseIndex) == 0) {
                    "capture confirmation receipt chain has an incoherent final step"
                }
            } else {
                check(
                    step.fromPoseIndex < Int.MAX_VALUE &&
                        step.toPoseIndex == step.fromPoseIndex + 1,
                ) {
                    "capture confirmation receipt chain destination is inconsistent"
                }
                check(captureConfirmationDao.findPose(shootId, step.toPoseIndex) != null) {
                    "capture confirmation receipt chain destination pose is missing"
                }
            }
        }

        val originalStep = stepsByFromPose[receipt.fromPoseIndex]
        check(originalStep != null && originalStep.toPoseIndex == receipt.toPoseIndex) {
            "capture confirmation receipt chain is inconsistent with the original receipt"
        }
        if (receipt.toPoseIndex == null) {
            check(
                currentPoseIndex == receipt.fromPoseIndex &&
                    sessionLifecycleState == COMPLETED,
            ) {
                "capture confirmation final receipt session is inconsistent"
            }
            check(stepsByFromPose.keys.none { fromPoseIndex -> fromPoseIndex > currentPoseIndex }) {
                "capture confirmation receipt chain advances beyond the session"
            }
            return
        }

        check(currentPoseIndex >= receipt.toPoseIndex) {
            "capture confirmation receipt session is unreachable"
        }
        check(sessionLifecycleState == ACTIVE || sessionLifecycleState == COMPLETED) {
            "capture confirmation receipt session state is inconsistent"
        }
        check(stepsByFromPose.keys.none { fromPoseIndex -> fromPoseIndex > currentPoseIndex }) {
            "capture confirmation receipt chain advances beyond the session"
        }

        var nextPoseIndex = receipt.toPoseIndex
        while (nextPoseIndex < currentPoseIndex) {
            val step = stepsByFromPose[nextPoseIndex]
                ?: throw IllegalStateException("capture confirmation receipt chain is not contiguous")
            check(step.toPoseIndex == nextPoseIndex + 1) {
                "capture confirmation receipt chain does not advance contiguously"
            }
            nextPoseIndex += 1
        }

        val currentStep = stepsByFromPose[currentPoseIndex]
        if (sessionLifecycleState == ACTIVE) {
            check(currentStep == null) {
                "capture confirmation active session has a receipt at its current pose"
            }
        } else {
            check(currentStep != null && currentStep.toPoseIndex == null) {
                "capture confirmation completed session has no final receipt"
            }
        }
    }

    private fun CaptureAttemptEntity.matches(
        command: ShootEffect.ConfirmAndAdvanceCapture,
    ): Boolean =
        commandToken == command.token.value &&
            poseId == command.poseId &&
            poseIndex == command.poseIndex

    private fun registerCaptureAttemptInTransaction(
        sessionId: String,
        command: ShootEffect.CaptureCommand,
        recordedAtEpochMillis: Long,
    ): AttemptRegistrationResult {
        captureAttemptDao.findAttemptByToken(command.token.value)?.let { existing ->
            return if (existing.matches(sessionId, command)) {
                AttemptRegistrationResult.AlreadyRegistered
            } else {
                AttemptRegistrationResult.Rejected(
                    AttemptRegistrationRejectionReason.TOKEN_CONFLICT,
                )
            }
        }

        if (
            captureAttemptDao.findAttemptBySessionAndNumber(
                sessionId,
                command.attemptNumber,
            ) != null
        ) {
            return AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.ATTEMPT_NUMBER_CONFLICT,
            )
        }

        val session = captureAttemptDao.findSession(sessionId)
            ?: return AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.UNKNOWN_SESSION,
            )
        val shoot = captureAttemptDao.findShoot(session.shootId)
            ?: throw IllegalStateException("capture authority has no owning shoot")
        check(shoot.deletionGeneration >= 0L) {
            "capture authority deletion generation is invalid"
        }

        if (shoot.lifecycleState != ACTIVE) {
            return AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.BLOCKED_BY_DELETION,
            )
        }
        if (session.lifecycleState != ACTIVE) {
            return AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.INACTIVE_SESSION,
            )
        }

        val currentPose = captureAttemptDao.findPose(session.shootId, session.currentPoseIndex)
        if (
            session.currentPoseIndex != command.poseIndex ||
            currentPose == null ||
            currentPose.poseId != command.poseId
        ) {
            return AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.STALE_POSE,
            )
        }

        if (command.attemptNumber < session.nextAttemptNumber) {
            return AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.STALE_ATTEMPT_NUMBER,
            )
        }
        if (command.attemptNumber > session.nextAttemptNumber) {
            return AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.FUTURE_ATTEMPT_NUMBER,
            )
        }

        captureAttemptDao.insertAttempt(
            CaptureAttemptEntity(
                commandToken = command.token.value,
                sessionId = sessionId,
                poseId = command.poseId,
                poseIndex = command.poseIndex,
                attemptNumber = command.attemptNumber,
                triggerType = command.trigger.name,
                lifecycleState = REGISTERED,
                reconciliationRequired = false,
                capturedDeletionGeneration = shoot.deletionGeneration,
                createdAtEpochMillis = recordedAtEpochMillis,
                updatedAtEpochMillis = recordedAtEpochMillis,
                confirmedAtEpochMillis = null,
            ),
        )

        val affectedRows = captureAttemptDao.advanceSessionAttemptCounter(
            sessionId = sessionId,
            poseIndex = command.poseIndex,
            expectedAttemptNumber = command.attemptNumber,
            nextAttemptNumber = command.attemptNumber + 1L,
            expectedDeletionGeneration = shoot.deletionGeneration,
            updatedAtEpochMillis = recordedAtEpochMillis,
        )
        if (affectedRows != 1) {
            throw CounterCasFailedException()
        }
        return AttemptRegistrationResult.Registered
    }

    private fun classifyFailedCaptureStart(
        sessionId: String,
        token: CaptureToken,
    ): CaptureStartAuthorizationResult {
        val attempt = captureAttemptDao.findAttemptByToken(token.value)
            ?: return CaptureStartAuthorizationResult.Rejected(
                CaptureStartRejectionReason.UNKNOWN_ATTEMPT,
            )
        if (attempt.sessionId != sessionId) {
            return CaptureStartAuthorizationResult.Rejected(
                CaptureStartRejectionReason.TOKEN_SESSION_CONFLICT,
            )
        }
        check(attempt.capturedDeletionGeneration >= 0L) {
            "capture start deletion generation is invalid"
        }
        if (attempt.lifecycleState == CAPTURING) {
            return CaptureStartAuthorizationResult.AlreadyStarted
        }
        if (attempt.lifecycleState != REGISTERED) {
            return CaptureStartAuthorizationResult.Rejected(
                CaptureStartRejectionReason.WRONG_STATE,
            )
        }

        val session = captureAttemptDao.findSession(attempt.sessionId)
            ?: throw IllegalStateException("capture authority has no owning session")
        val shoot = captureAttemptDao.findShoot(session.shootId)
            ?: throw IllegalStateException("capture authority has no owning shoot")
        check(shoot.deletionGeneration >= 0L) {
            "capture start shoot deletion generation is invalid"
        }
        if (
            shoot.lifecycleState != ACTIVE ||
            shoot.deletionGeneration != attempt.capturedDeletionGeneration
        ) {
            return CaptureStartAuthorizationResult.BlockedByDeletion
        }
        if (session.lifecycleState != ACTIVE) {
            return CaptureStartAuthorizationResult.Rejected(
                CaptureStartRejectionReason.INACTIVE_SESSION,
            )
        }

        val currentPose = captureAttemptDao.findPose(session.shootId, session.currentPoseIndex)
        if (
            session.currentPoseIndex != attempt.poseIndex ||
            currentPose == null ||
            currentPose.poseId != attempt.poseId
        ) {
            return CaptureStartAuthorizationResult.Rejected(
                CaptureStartRejectionReason.STALE_POSE,
            )
        }
        return CaptureStartAuthorizationResult.Rejected(CaptureStartRejectionReason.CAS_FAILED)
    }

    private fun CaptureAttemptEntity.matches(
        sessionId: String,
        command: ShootEffect.CaptureCommand,
    ): Boolean =
        this.sessionId == sessionId &&
            poseId == command.poseId &&
            poseIndex == command.poseIndex &&
            attemptNumber == command.attemptNumber &&
            triggerType == command.trigger.name

    private class ClaimAuthority(
        val output: CaptureExportOutputEntity,
        val outbox: CaptureExportOutboxEntity,
        val receipt: CaptureConfirmationReceiptEntity,
        val attempt: CaptureAttemptEntity,
        val session: ShootSessionEntity,
        val shoot: ShootEntity,
    )

    private class ExportClaimCasFailedException :
        RuntimeException("export claim compare-and-set failed")

    private class ExportClaimAuthorityInconsistentException :
        RuntimeException("export claim authority is inconsistent")

    private class CounterCasFailedException :
        RuntimeException("capture attempt counter compare-and-set failed")

    private class ConfirmationCasFailedException :
        RuntimeException("capture confirmation compare-and-set failed")

    private class ConfirmationCardinalityException :
        RuntimeException("capture confirmation cardinality assertion failed")

    private class DeletionCasFailedException :
        RuntimeException("shoot deletion compare-and-set failed")

    private class DeletionAuthorityInconsistentException :
        RuntimeException("shoot deletion authority is inconsistent")

    private companion object {
        const val BURST_OUTPUT_COUNT = 3
        const val ACTIVE = "ACTIVE"
        const val DELETING = "DELETING"
        const val COMPLETED = "COMPLETED"
        const val REGISTERED = "REGISTERED"
        const val CAPTURING = "CAPTURING"
        const val CONFIRMED = "CONFIRMED"
        const val DURABLE = "DURABLE"
        const val PENDING = "PENDING"
        const val CLAIMED = "CLAIMED"
        const val CREATE_STARTED = "CREATE_STARTED"
        const val URI_KNOWN = "URI_KNOWN"
        const val EXPORTED = "EXPORTED"
        const val AMBIGUOUS = "AMBIGUOUS"
        const val CANCELLED = "CANCELLED"
        const val NONE = "NONE"
    }
}
