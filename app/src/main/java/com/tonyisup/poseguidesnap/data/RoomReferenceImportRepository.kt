package com.tonyisup.poseguidesnap.data

import android.database.sqlite.SQLiteConstraintException
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.db.ReferenceImportFileOperationEntity
import com.tonyisup.poseguidesnap.data.db.ReferenceImportIntentEntity
import com.tonyisup.poseguidesnap.data.db.ShootPoseEntity
import com.tonyisup.poseguidesnap.domain.model.Shoot
import java.util.concurrent.Callable

class RoomReferenceImportRepository(
    private val database: AppDatabase,
) {
    private val dao = database.referenceImportDao()
    private val fileOperationDao = database.referenceImportFileOperationDao()

    internal fun inspectGlobalImportWorkInCurrentTransaction(): GlobalReferenceImportWorkState {
        requireGlobalFileAuthorityCoherent()

        val reconciliationRequired =
            fileOperationDao.findReconciliationRequiredOperations(limit = 1).singleOrNull()
        if (reconciliationRequired != null) {
            val intent = dao.findIntent(reconciliationRequired.importToken)
                ?: throw ReferenceImportAuthorityInconsistentException()
            if (!intent.hasCoherentAuthority() || !reconciliationRequired.matches(intent)) {
                throw ReferenceImportAuthorityInconsistentException()
            }
            return GlobalReferenceImportWorkState.RECONCILIATION_REQUIRED
        }

        val retryable = fileOperationDao.findRetryableOperations(
            afterCreatedAtEpochMillis = null,
            afterImportToken = null,
            limit = 1,
        ).singleOrNull()
        if (retryable != null) {
            val intent = dao.findIntent(retryable.importToken)
                ?: throw ReferenceImportAuthorityInconsistentException()
            if (!intent.hasCoherentAuthority() || !retryable.matches(intent)) {
                throw ReferenceImportAuthorityInconsistentException()
            }
            return when (intent.lifecycleState) {
                PREPARING,
                ASSET_READY,
                -> GlobalReferenceImportWorkState.IN_PROGRESS
                REJECTED_CLEANED,
                REJECTED_QUARANTINED,
                -> GlobalReferenceImportWorkState.RECONCILIATION_REQUIRED
                COMMITTED -> throw ReferenceImportAuthorityInconsistentException()
                else -> throw ReferenceImportAuthorityInconsistentException()
            }
        }
        if (dao.hasAnyNonterminalIntents()) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        return GlobalReferenceImportWorkState.CLEAR
    }

    fun checkImportAdmission(shootId: String): ReferenceImportAdmissionCheckResult {
        if (!ReferenceImportPolicy.validateOwnershipIdentity(shootId)) {
            return ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.UNKNOWN_SHOOT,
            )
        }
        return try {
            inTransaction { checkImportAdmissionInTransaction(shootId) }
        } catch (_: IllegalArgumentException) {
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.AUTHORITY_INCONSISTENT,
            )
        } catch (_: ReferenceImportAuthorityInconsistentException) {
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.AUTHORITY_INCONSISTENT,
            )
        }
    }

    fun reserveImport(
        reservation: ReferenceImportReservation,
        reservedAtEpochMillis: Long,
    ): ReferenceImportReserveResult {
        if (!ReferenceImportPolicy.validateTimestamp(reservedAtEpochMillis)) {
            return ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.INVALID_TIMESTAMP,
            )
        }

        return try {
            inTransaction { reserveImportInTransaction(reservation, reservedAtEpochMillis) }
        } catch (_: SQLiteConstraintException) {
            classifyReservationConstraint(reservation, reservedAtEpochMillis)
        } catch (_: ReferenceImportCasFailedException) {
            classifyReservationConstraint(reservation, reservedAtEpochMillis)
        } catch (_: IllegalArgumentException) {
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            )
        } catch (_: ReferenceImportAuthorityInconsistentException) {
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            )
        }
    }

    fun markAssetReady(
        importToken: ReferenceImportToken,
        relativeAssetPath: String,
        assetReadyAtEpochMillis: Long,
    ): ReferenceImportAssetReadyResult {
        if (!ReferenceImportPolicy.validateTimestamp(assetReadyAtEpochMillis)) {
            return ReferenceImportAssetReadyResult.Rejected(
                ReferenceImportAssetReadyRejectionReason.INVALID_TIMESTAMP,
            )
        }

        return try {
            inTransaction {
                markAssetReadyInTransaction(
                    importToken = importToken,
                    relativeAssetPath = relativeAssetPath,
                    assetReadyAtEpochMillis = assetReadyAtEpochMillis,
                )
            }
        } catch (_: ReferenceImportCasFailedException) {
            ReferenceImportAssetReadyResult.Rejected(
                ReferenceImportAssetReadyRejectionReason.TRANSACTION_CAS_FAILED,
            )
        } catch (_: ReferenceImportAuthorityInconsistentException) {
            ReferenceImportAssetReadyResult.Rejected(
                ReferenceImportAssetReadyRejectionReason.TRANSACTION_CAS_FAILED,
            )
        }
    }

    fun commitImport(
        evidence: ReferenceImportEvidence,
        committedAtEpochMillis: Long,
    ): ReferenceImportCommitResult {
        if (!ReferenceImportPolicy.validateTimestamp(committedAtEpochMillis)) {
            return ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.INVALID_TIMESTAMP,
            )
        }

        return try {
            inTransaction { commitImportInTransaction(evidence, committedAtEpochMillis) }
        } catch (_: SQLiteConstraintException) {
            classifyCommitConstraint(evidence, committedAtEpochMillis)
        } catch (_: ReferenceImportCasFailedException) {
            ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.TRANSACTION_CAS_FAILED,
            )
        } catch (_: ReferenceImportAuthorityInconsistentException) {
            ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.AUTHORITY_INCONSISTENT,
            )
        }
    }

    fun settleFailure(
        importToken: ReferenceImportToken,
        settlement: ReferenceImportFailureSettlement,
        settledAtEpochMillis: Long,
    ): ReferenceImportSettlementResult {
        if (!ReferenceImportPolicy.validateTimestamp(settledAtEpochMillis)) {
            return ReferenceImportSettlementResult.Rejected(
                ReferenceImportSettlementRejectionReason.INVALID_TIMESTAMP,
            )
        }

        return try {
            inTransaction {
                settleFailureInTransaction(importToken, settlement, settledAtEpochMillis)
            }
        } catch (_: ReferenceImportCasFailedException) {
            ReferenceImportSettlementResult.Rejected(
                ReferenceImportSettlementRejectionReason.TRANSACTION_CAS_FAILED,
            )
        } catch (_: ReferenceImportAuthorityInconsistentException) {
            ReferenceImportSettlementResult.Rejected(
                ReferenceImportSettlementRejectionReason.TRANSACTION_CAS_FAILED,
            )
        }
    }

    fun findExactImportForRecovery(
        importToken: ReferenceImportToken,
    ): PendingReferenceImport? =
        try {
            inTransaction {
                val intent = dao.findIntent(importToken.value) ?: return@inTransaction null
                if (!intent.hasCoherentAuthority()) {
                    throw ReferenceImportAuthorityInconsistentException()
                }
                val fileOperation = requireCoherentFileOperation(intent)
                when (intent.lifecycleState) {
                    COMMITTED -> {
                        if (!fileOperation.authorizesLogicalGate(
                                intent,
                                ReferenceImportFileOperationStage.FINAL_DURABLE,
                            )
                        ) {
                            throw ReferenceImportAuthorityInconsistentException()
                        }
                        val pose = dao.findPoseById(intent.shootId, intent.poseId)
                            ?: throw ReferenceImportAuthorityInconsistentException()
                        if (!pose.matchesCommittedIntent(intent)) {
                            throw ReferenceImportAuthorityInconsistentException()
                        }
                    }
                    PREPARING,
                    ASSET_READY,
                    REJECTED_CLEANED,
                    REJECTED_QUARANTINED,
                    -> if (hasPoseIdConflict(intent.shootId, intent.poseId)) {
                        throw ReferenceImportAuthorityInconsistentException()
                    }
                    else -> throw ReferenceImportAuthorityInconsistentException()
                }
                intent.toPendingReferenceImport()
            }
        } catch (_: ReferenceImportAuthorityInconsistentException) {
            throw IllegalStateException("reference import authority is inconsistent")
        }

    private fun checkImportAdmissionInTransaction(
        shootId: String,
    ): ReferenceImportAdmissionCheckResult {
        val shoot = dao.findShoot(shootId)
            ?: return admissionBlocked(ReferenceImportAdmissionCheckBlockReason.UNKNOWN_SHOOT)
        if (shoot.deletionGeneration < 0L) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        when (shoot.lifecycleState) {
            ACTIVE -> Unit
            "DELETING" -> return admissionBlocked(
                ReferenceImportAdmissionCheckBlockReason.SHOOT_DELETING,
            )
            else -> throw ReferenceImportAuthorityInconsistentException()
        }

        val activeSessionCount = dao.countActiveSessions(shootId)
        if (activeSessionCount !in 0..1) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        if (activeSessionCount == 1) {
            return admissionBlocked(ReferenceImportAdmissionCheckBlockReason.ACTIVE_SESSION)
        }
        val globalWork = inspectGlobalImportWorkInCurrentTransaction()
        if (!hasReservationCapacity(shootId)) {
            return admissionBlocked(ReferenceImportAdmissionCheckBlockReason.PLAYLIST_FULL)
        }

        when (globalWork) {
            GlobalReferenceImportWorkState.CLEAR -> Unit
            GlobalReferenceImportWorkState.IN_PROGRESS -> return admissionBlocked(
                ReferenceImportAdmissionCheckBlockReason.IMPORT_IN_PROGRESS,
            )
            GlobalReferenceImportWorkState.RECONCILIATION_REQUIRED -> return admissionBlocked(
                ReferenceImportAdmissionCheckBlockReason.RECONCILIATION_REQUIRED,
            )
        }

        return ReferenceImportAdmissionCheckResult.Allowed
    }

    private fun admissionBlocked(
        reason: ReferenceImportAdmissionCheckBlockReason,
    ): ReferenceImportAdmissionCheckResult.Blocked =
        ReferenceImportAdmissionCheckResult.Blocked(reason)

    private fun reserveImportInTransaction(
        reservation: ReferenceImportReservation,
        reservedAtEpochMillis: Long,
    ): ReferenceImportReserveResult {
        dao.findIntent(reservation.importToken.value)?.let { existing ->
            return classifyExistingReservation(existing, reservation, reservedAtEpochMillis)
        }

        val shoot = dao.findShoot(reservation.shootId)
            ?: return ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.UNKNOWN_SHOOT,
            )
        if (shoot.deletionGeneration < 0L) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        when (shoot.lifecycleState) {
            ACTIVE -> Unit
            DELETING -> return ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.SHOOT_NOT_ACTIVE,
            )
            else -> throw ReferenceImportAuthorityInconsistentException()
        }

        classifyPoseReservationConflict(reservation)?.let { return it }
        classifyIntentReservationConflict(reservation)?.let { return it }

        val activeSessionCount = dao.countActiveSessions(reservation.shootId)
        if (activeSessionCount !in 0..1) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        if (activeSessionCount == 1) {
            return ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.ACTIVE_SESSION,
            )
        }

        requireGlobalFileAuthorityCoherent()

        if (!hasReservationCapacity(reservation.shootId)) {
            return ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.PLAYLIST_FULL,
            )
        }
        if (
            dao.hasAnyNonterminalIntents() ||
            fileOperationDao.findRetryableOperations(
                afterCreatedAtEpochMillis = null,
                afterImportToken = null,
                limit = 1,
            ).isNotEmpty()
        ) {
            return ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.UNRESOLVED_IMPORT_WORK,
            )
        }

        val expectedIntent = ReferenceImportIntentEntity(
            importToken = reservation.importToken.value,
            shootId = reservation.shootId,
            poseId = reservation.poseId,
            relativeAssetPath = reservation.relativeAssetPath,
            lifecycleState = PREPARING,
            createdAtEpochMillis = reservedAtEpochMillis,
            updatedAtEpochMillis = reservedAtEpochMillis,
            assetReadyAtEpochMillis = null,
            terminalAtEpochMillis = null,
        )
        val paths = ReferenceImportFileOperationPaths.forToken(reservation.importToken)
        val expectedFileOperation = ReferenceImportFileOperationEntity(
            importToken = reservation.importToken.value,
            relativeAssetPath = paths.relativeAssetPath,
            relativeTempPath = paths.relativeTempPath,
            relativeQuarantinePath = paths.relativeQuarantinePath,
            stage = ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
            byteCount = null,
            sha256 = null,
            lastFailureCode = null,
            reconciliationRequired = false,
            createdAtEpochMillis = reservedAtEpochMillis,
            updatedAtEpochMillis = reservedAtEpochMillis,
        )
        dao.insertIntent(expectedIntent)
        fileOperationDao.insertInitialOperation(expectedFileOperation)

        val insertedIntent = dao.findIntent(reservation.importToken.value)
        val insertedFileOperation = fileOperationDao.findOperation(reservation.importToken.value)
        if (
            insertedIntent != expectedIntent ||
            insertedFileOperation != expectedFileOperation ||
            !insertedIntent.hasCoherentAuthority() ||
            !insertedFileOperation.matches(insertedIntent)
        ) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        return ReferenceImportReserveResult.Reserved
    }

    private fun classifyReservationConstraint(
        reservation: ReferenceImportReservation,
        reservedAtEpochMillis: Long,
    ): ReferenceImportReserveResult =
        try {
            inTransaction {
                dao.findIntent(reservation.importToken.value)?.let { existing ->
                    return@inTransaction classifyExistingReservation(
                        existing = existing,
                        reservation = reservation,
                        reservedAtEpochMillis = reservedAtEpochMillis,
                    )
                }
                classifyPoseReservationConflict(reservation)
                    ?: classifyIntentReservationConflict(reservation)
                    ?: ReferenceImportReserveResult.Rejected(
                        ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
                    )
            }
        } catch (_: ReferenceImportCasFailedException) {
            ReferenceImportReserveResult.ExistingWorkRequiresReconciliation
        } catch (_: ReferenceImportAuthorityInconsistentException) {
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            )
        }

    private fun classifyExistingReservation(
        existing: ReferenceImportIntentEntity,
        reservation: ReferenceImportReservation,
        reservedAtEpochMillis: Long,
    ): ReferenceImportReserveResult {
        if (!existing.matches(reservation)) {
            return ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.TOKEN_CONFLICT,
            )
        }
        if (!existing.hasCoherentAuthority()) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        val fileOperation = requireCoherentFileOperation(existing)
        if (!fileOperation.matches(existing)) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        if (reservedAtEpochMillis < existing.updatedAtEpochMillis) {
            return ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.INVALID_TIMESTAMP,
            )
        }
        val shoot = dao.findShoot(existing.shootId)
            ?: throw ReferenceImportAuthorityInconsistentException()
        if (shoot.deletionGeneration < 0L) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        when (shoot.lifecycleState) {
            ACTIVE -> Unit
            DELETING -> return ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.SHOOT_NOT_ACTIVE,
            )
            else -> throw ReferenceImportAuthorityInconsistentException()
        }

        return when (existing.lifecycleState) {
            COMMITTED -> {
                if (!fileOperation.authorizesLogicalGate(
                        existing,
                        ReferenceImportFileOperationStage.FINAL_DURABLE,
                    )
                ) {
                    throw ReferenceImportAuthorityInconsistentException()
                }
                val pose = dao.findPoseById(existing.shootId, existing.poseId)
                    ?: throw ReferenceImportAuthorityInconsistentException()
                if (!pose.matchesCommittedIntent(existing)) {
                    throw ReferenceImportAuthorityInconsistentException()
                }
                ReferenceImportReserveResult.AlreadyCommitted(pose.poseIndex)
            }
            PREPARING,
            ASSET_READY,
            REJECTED_CLEANED,
            REJECTED_QUARANTINED,
            -> ReferenceImportReserveResult.ExistingWorkRequiresReconciliation
            else -> throw ReferenceImportAuthorityInconsistentException()
        }
    }

    private fun ShootPoseEntity.matchesCommittedIntent(
        intent: ReferenceImportIntentEntity,
    ): Boolean =
        shootId == intent.shootId &&
            poseId == intent.poseId &&
            referenceAssetPath == intent.relativeAssetPath &&
            validationState == VALIDATED &&
            !detectorMetadata.isNullOrBlank() &&
            !modelMetadata.isNullOrBlank() &&
            !preprocessingMetadata.isNullOrBlank() &&
            !landmarkPayload.isNullOrBlank() &&
            !coordinateMetadata.isNullOrBlank()

    private fun classifyPoseReservationConflict(
        reservation: ReferenceImportReservation,
    ): ReferenceImportReserveResult? {
        return if (dao.findPoseById(reservation.shootId, reservation.poseId) != null) {
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.POSE_ALREADY_EXISTS,
            )
        } else {
            null
        }
    }

    private fun classifyIntentReservationConflict(
        reservation: ReferenceImportReservation,
    ): ReferenceImportReserveResult? {
        return if (dao.findIntentByPoseId(reservation.shootId, reservation.poseId) != null) {
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.POSE_ID_CONFLICT,
            )
        } else {
            null
        }
    }

    private fun markAssetReadyInTransaction(
        importToken: ReferenceImportToken,
        relativeAssetPath: String,
        assetReadyAtEpochMillis: Long,
    ): ReferenceImportAssetReadyResult {
        val intent = dao.findIntent(importToken.value)
            ?: return ReferenceImportAssetReadyResult.Rejected(
                ReferenceImportAssetReadyRejectionReason.UNKNOWN_INTENT,
            )
        if (!intent.hasCoherentAuthority()) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        if (intent.relativeAssetPath != relativeAssetPath) {
            return ReferenceImportAssetReadyResult.Rejected(
                ReferenceImportAssetReadyRejectionReason.INTENT_CONFLICT,
            )
        }
        val fileOperation = requireCoherentFileOperation(intent)
        if (!fileOperation.authorizesLogicalGate(
                intent,
                ReferenceImportFileOperationStage.FINAL_DURABLE,
            )
        ) {
            return ReferenceImportAssetReadyResult.Rejected(
                ReferenceImportAssetReadyRejectionReason.WRONG_STATE,
            )
        }
        if (assetReadyAtEpochMillis < intent.updatedAtEpochMillis) {
            return ReferenceImportAssetReadyResult.Rejected(
                ReferenceImportAssetReadyRejectionReason.INVALID_TIMESTAMP,
            )
        }
        if (intent.lifecycleState == ASSET_READY) {
            return ReferenceImportAssetReadyResult.AlreadyAssetReady
        }
        if (intent.lifecycleState != PREPARING) {
            return ReferenceImportAssetReadyResult.Rejected(
                ReferenceImportAssetReadyRejectionReason.WRONG_STATE,
            )
        }

        if (
            dao.markAssetReady(
                importToken = importToken.value,
                relativeAssetPath = relativeAssetPath,
                expectedUpdatedAtEpochMillis = intent.updatedAtEpochMillis,
                assetReadyAtEpochMillis = assetReadyAtEpochMillis,
            ) != 1
        ) {
            throw ReferenceImportCasFailedException()
        }
        val expected = intent.copy(
            lifecycleState = ASSET_READY,
            updatedAtEpochMillis = assetReadyAtEpochMillis,
            assetReadyAtEpochMillis = assetReadyAtEpochMillis,
        )
        if (dao.findIntent(importToken.value) != expected || !expected.hasCoherentAuthority()) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        return ReferenceImportAssetReadyResult.MarkedAssetReady
    }

    private fun commitImportInTransaction(
        evidence: ReferenceImportEvidence,
        committedAtEpochMillis: Long,
    ): ReferenceImportCommitResult {
        val intent = dao.findIntent(evidence.importToken.value)
            ?: return ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.UNKNOWN_INTENT,
            )
        if (!intent.hasCoherentAuthority()) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        if (!intent.matches(evidence)) {
            return ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.INTENT_CONFLICT,
            )
        }
        val fileOperation = requireCoherentFileOperation(intent)
        if (!fileOperation.authorizesLogicalGate(
                intent,
                ReferenceImportFileOperationStage.FINAL_DURABLE,
            )
        ) {
            return ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.WRONG_STATE,
            )
        }
        if (committedAtEpochMillis < intent.updatedAtEpochMillis) {
            return ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.INVALID_TIMESTAMP,
            )
        }
        if (intent.lifecycleState == COMMITTED) {
            return classifyCommittedReplay(intent, evidence)
        }
        if (intent.lifecycleState != ASSET_READY) {
            return ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.WRONG_STATE,
            )
        }

        val shoot = dao.findShoot(intent.shootId)
            ?: throw ReferenceImportAuthorityInconsistentException()
        if (shoot.deletionGeneration < 0L) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        if (shoot.lifecycleState != ACTIVE) {
            return ReferenceImportCommitResult.BlockedByDeletion
        }
        if (dao.countActiveSessions(intent.shootId) != 0) {
            return ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.ACTIVE_SESSION,
            )
        }
        classifyCommitPoseConflict(evidence)?.let { return it }
        val assignedPoseIndex = requireContiguousValidatedAppendIndex(intent.shootId)

        if (
            dao.markCommitted(
                importToken = evidence.importToken.value,
                shootId = evidence.shootId,
                poseId = evidence.poseId,
                relativeAssetPath = evidence.relativeAssetPath,
                expectedUpdatedAtEpochMillis = intent.updatedAtEpochMillis,
                committedAtEpochMillis = committedAtEpochMillis,
            ) != 1
        ) {
            throw ReferenceImportCasFailedException()
        }
        val pose = evidence.toValidatedPose(assignedPoseIndex)
        dao.insertPose(pose)

        val expectedIntent = intent.copy(
            lifecycleState = COMMITTED,
            updatedAtEpochMillis = committedAtEpochMillis,
            terminalAtEpochMillis = committedAtEpochMillis,
        )
        if (
            dao.findIntent(evidence.importToken.value) != expectedIntent ||
            dao.findPoseById(evidence.shootId, evidence.poseId) != pose ||
            dao.findPoseByIndex(evidence.shootId, assignedPoseIndex) != pose
        ) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        return ReferenceImportCommitResult.Committed(assignedPoseIndex)
    }

    private fun classifyCommitConstraint(
        evidence: ReferenceImportEvidence,
        committedAtEpochMillis: Long,
    ): ReferenceImportCommitResult =
        try {
            inTransaction {
                val intent = dao.findIntent(evidence.importToken.value)
                    ?: return@inTransaction ReferenceImportCommitResult.Rejected(
                        ReferenceImportCommitRejectionReason.UNKNOWN_INTENT,
                    )
                if (!intent.hasCoherentAuthority()) {
                    throw ReferenceImportAuthorityInconsistentException()
                }
                if (!intent.matches(evidence)) {
                    return@inTransaction ReferenceImportCommitResult.Rejected(
                        ReferenceImportCommitRejectionReason.INTENT_CONFLICT,
                    )
                }
                val fileOperation = requireCoherentFileOperation(intent)
                if (!fileOperation.authorizesLogicalGate(
                        intent,
                        ReferenceImportFileOperationStage.FINAL_DURABLE,
                    )
                ) {
                    return@inTransaction ReferenceImportCommitResult.Rejected(
                        ReferenceImportCommitRejectionReason.WRONG_STATE,
                    )
                }
                if (committedAtEpochMillis < intent.updatedAtEpochMillis) {
                    return@inTransaction ReferenceImportCommitResult.Rejected(
                        ReferenceImportCommitRejectionReason.INVALID_TIMESTAMP,
                    )
                }
                if (intent.lifecycleState == COMMITTED) {
                    return@inTransaction classifyCommittedReplay(intent, evidence)
                }
                classifyCommitPoseConflict(evidence)
                    ?: ReferenceImportCommitResult.Rejected(
                        ReferenceImportCommitRejectionReason.AUTHORITY_INCONSISTENT,
                    )
            }
        } catch (_: ReferenceImportAuthorityInconsistentException) {
            ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.AUTHORITY_INCONSISTENT,
            )
        }

    private fun classifyCommittedReplay(
        intent: ReferenceImportIntentEntity,
        evidence: ReferenceImportEvidence,
    ): ReferenceImportCommitResult {
        if (intent.lifecycleState != COMMITTED || !intent.hasCoherentAuthority()) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        val pose = dao.findPoseById(evidence.shootId, evidence.poseId)
            ?: throw ReferenceImportAuthorityInconsistentException()
        return if (pose.matches(evidence)) {
            ReferenceImportCommitResult.AlreadyCommitted(pose.poseIndex)
        } else {
            ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.EVIDENCE_CONFLICT,
            )
        }
    }

    private fun classifyCommitPoseConflict(
        evidence: ReferenceImportEvidence,
    ): ReferenceImportCommitResult? {
        return if (dao.findPoseById(evidence.shootId, evidence.poseId) != null) {
            ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.POSE_ID_CONFLICT,
            )
        } else {
            null
        }
    }

    private fun settleFailureInTransaction(
        importToken: ReferenceImportToken,
        settlement: ReferenceImportFailureSettlement,
        settledAtEpochMillis: Long,
    ): ReferenceImportSettlementResult {
        val intent = dao.findIntent(importToken.value)
            ?: return ReferenceImportSettlementResult.Rejected(
                ReferenceImportSettlementRejectionReason.UNKNOWN_INTENT,
            )
        if (!intent.hasCoherentAuthority()) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        if (settledAtEpochMillis < intent.updatedAtEpochMillis) {
            return ReferenceImportSettlementResult.Rejected(
                ReferenceImportSettlementRejectionReason.INVALID_TIMESTAMP,
            )
        }
        if (intent.lifecycleState == COMMITTED) {
            return ReferenceImportSettlementResult.Rejected(
                ReferenceImportSettlementRejectionReason.COMMITTED_INTENT,
            )
        }

        val requiredFileStage = when (settlement) {
            ReferenceImportFailureSettlement.CLEANED ->
                ReferenceImportFileOperationStage.CLEANED_DURABLE
            ReferenceImportFailureSettlement.QUARANTINED ->
                ReferenceImportFileOperationStage.QUARANTINE_DURABLE
        }
        val fileOperation = requireCoherentFileOperation(intent)
        if (!fileOperation.authorizesLogicalGate(
                intent,
                requiredFileStage,
                allowReconciliationFlag = true,
            )
        ) {
            return ReferenceImportSettlementResult.Rejected(
                ReferenceImportSettlementRejectionReason.SETTLEMENT_CONFLICT,
            )
        }

        val targetState = settlement.lifecycleState
        if (intent.lifecycleState in TERMINAL_REJECTION_STATES) {
            return if (intent.lifecycleState == targetState) {
                ReferenceImportSettlementResult.AlreadySettled
            } else {
                ReferenceImportSettlementResult.Rejected(
                    ReferenceImportSettlementRejectionReason.SETTLEMENT_CONFLICT,
                )
            }
        }
        if (intent.lifecycleState != PREPARING && intent.lifecycleState != ASSET_READY) {
            return ReferenceImportSettlementResult.Rejected(
                ReferenceImportSettlementRejectionReason.SETTLEMENT_CONFLICT,
            )
        }
        if (hasPoseIdConflict(intent.shootId, intent.poseId)) {
            return ReferenceImportSettlementResult.Rejected(
                ReferenceImportSettlementRejectionReason.ACTIVE_POSE_EXISTS,
            )
        }

        if (
            dao.settleIntent(
                importToken = importToken.value,
                expectedLifecycleState = intent.lifecycleState,
                expectedUpdatedAtEpochMillis = intent.updatedAtEpochMillis,
                settledLifecycleState = targetState,
                settledAtEpochMillis = settledAtEpochMillis,
            ) != 1
        ) {
            throw ReferenceImportCasFailedException()
        }
        val expected = intent.copy(
            lifecycleState = targetState,
            updatedAtEpochMillis = settledAtEpochMillis,
            terminalAtEpochMillis = settledAtEpochMillis,
        )
        if (dao.findIntent(importToken.value) != expected || !expected.hasCoherentAuthority()) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        return ReferenceImportSettlementResult.Settled
    }

    private fun hasReservationCapacity(shootId: String): Boolean {
        val validatedPoseCount = requireExactValidatedPlaylistCount(shootId)
        val nonterminalIntentCount = dao.countNonterminalIntents(shootId)
        if (
            validatedPoseCount !in 0L..MAX_REFERENCE_COUNT ||
            nonterminalIntentCount !in 0L..MAX_REFERENCE_COUNT
        ) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        val reservedReferenceCount = validatedPoseCount + nonterminalIntentCount
        if (reservedReferenceCount !in 0L..MAX_REFERENCE_COUNT) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        return reservedReferenceCount < MAX_REFERENCE_COUNT
    }

    private fun requireExactValidatedPlaylistCount(shootId: String): Long {
        val poses = dao.findPosesInOrderForAdmission(shootId, Shoot.MAX_REFERENCE_POSES + 1)
        if (
            poses.size > Shoot.MAX_REFERENCE_POSES ||
            poses.any { pose -> pose.validationState !in ACCEPTED_VALIDATION_STATES } ||
            poses.map(ShootPoseEntity::poseIndex) != poses.indices.toList()
        ) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        return poses.size.toLong()
    }

    private fun requireGlobalFileAuthorityCoherent() {
        if (dao.hasIntentWithoutFileOperation()) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        var afterCreatedAtEpochMillis: Long? = null
        var afterImportToken: String? = null
        do {
            val page = fileOperationDao.findAuthorityPage(
                afterCreatedAtEpochMillis = afterCreatedAtEpochMillis,
                afterImportToken = afterImportToken,
                limit = AUTHORITY_PAGE_SIZE,
            )
            page.forEach { operation ->
                val intent = dao.findIntent(operation.importToken)
                    ?: throw ReferenceImportAuthorityInconsistentException()
                if (
                    !intent.hasCoherentAuthority() ||
                    !operation.matches(intent) ||
                    operation.toValidatedSnapshotOrNull() == null
                ) {
                    throw ReferenceImportAuthorityInconsistentException()
                }
            }
            val last = page.lastOrNull()
            afterCreatedAtEpochMillis = last?.createdAtEpochMillis
            afterImportToken = last?.importToken
        } while (page.size == AUTHORITY_PAGE_SIZE)
    }

    private fun requireContiguousValidatedAppendIndex(shootId: String): Int {
        val poses = dao.findPosesInOrder(shootId)
        if (
            poses.size >= Shoot.MAX_REFERENCE_POSES ||
            poses.any { pose -> pose.validationState !in ACCEPTED_VALIDATION_STATES } ||
            poses.map(ShootPoseEntity::poseIndex) != poses.indices.toList()
        ) {
            throw ReferenceImportAuthorityInconsistentException()
        }
        return poses.size
    }

    private fun hasPoseIdConflict(shootId: String, poseId: String): Boolean =
        dao.findPoseById(shootId, poseId) != null

    private fun ReferenceImportIntentEntity.matches(
        reservation: ReferenceImportReservation,
    ): Boolean =
        importToken == reservation.importToken.value &&
            shootId == reservation.shootId &&
            poseId == reservation.poseId &&
            relativeAssetPath == reservation.relativeAssetPath

    private fun requireCoherentFileOperation(
        intent: ReferenceImportIntentEntity,
    ): ReferenceImportFileOperationEntity =
        fileOperationDao.findOperation(intent.importToken)
            ?.takeIf { operation -> operation.matches(intent) }
            ?: throw ReferenceImportAuthorityInconsistentException()

    private fun ReferenceImportFileOperationEntity.matches(
        intent: ReferenceImportIntentEntity,
    ): Boolean =
        importToken == intent.importToken &&
            relativeAssetPath == intent.relativeAssetPath &&
            createdAtEpochMillis == intent.createdAtEpochMillis

    private fun ReferenceImportFileOperationEntity.toValidatedSnapshotOrNull():
        ReferenceImportFileOperationSnapshot? =
        try {
            val token = ReferenceImportToken(importToken)
            val expectedPaths = ReferenceImportFileOperationPaths.forToken(token)
            if (
                relativeAssetPath != expectedPaths.relativeAssetPath ||
                relativeTempPath != expectedPaths.relativeTempPath ||
                relativeQuarantinePath != expectedPaths.relativeQuarantinePath
            ) {
                null
            } else {
                ReferenceImportFileOperationSnapshot(
                    importToken = token,
                    paths = expectedPaths,
                    stage = stage,
                    byteCount = byteCount,
                    sha256 = sha256,
                    lastFailureCode = lastFailureCode,
                    reconciliationRequired = reconciliationRequired,
                    createdAtEpochMillis = createdAtEpochMillis,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun ReferenceImportFileOperationEntity.authorizesLogicalGate(
        intent: ReferenceImportIntentEntity,
        requiredStage: ReferenceImportFileOperationStage,
        allowReconciliationFlag: Boolean = false,
    ): Boolean {
        val reconciliationStateIsValid = if (allowReconciliationFlag) {
            reconciliationRequired == (lastFailureCode != null)
        } else {
            !reconciliationRequired && lastFailureCode == null
        }
        if (!matches(intent) || stage != requiredStage || !reconciliationStateIsValid) {
            return false
        }
        val expectedPaths = try {
            ReferenceImportFileOperationPaths.forToken(ReferenceImportToken(importToken))
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (
            relativeAssetPath != expectedPaths.relativeAssetPath ||
            relativeTempPath != expectedPaths.relativeTempPath ||
            relativeQuarantinePath != expectedPaths.relativeQuarantinePath
        ) {
            return false
        }
        return when (requiredStage) {
            ReferenceImportFileOperationStage.CLEANED_DURABLE -> byteCount == null && sha256 == null
            ReferenceImportFileOperationStage.FINAL_DURABLE,
            ReferenceImportFileOperationStage.QUARANTINE_DURABLE,
            -> byteCount != null && byteCount > 0L && sha256 != null &&
                sha256.length == 64 && sha256.all { character ->
                    character in '0'..'9' || character in 'a'..'f'
                }
            else -> false
        }
    }

    private fun ReferenceImportIntentEntity.matches(evidence: ReferenceImportEvidence): Boolean =
        importToken == evidence.importToken.value &&
            shootId == evidence.shootId &&
            poseId == evidence.poseId &&
            relativeAssetPath == evidence.relativeAssetPath

    private fun ReferenceImportIntentEntity.hasCoherentAuthority(): Boolean {
        if (
            createdAtEpochMillis < 0L ||
            updatedAtEpochMillis < createdAtEpochMillis
        ) {
            return false
        }
        val token = try {
            ReferenceImportToken(importToken)
        } catch (_: IllegalArgumentException) {
            return false
        }
        try {
            ReferenceImportReservation(
                importToken = token,
                shootId = shootId,
                poseId = poseId,
                relativeAssetPath = relativeAssetPath,
            )
        } catch (_: IllegalArgumentException) {
            return false
        }

        return when (lifecycleState) {
            PREPARING ->
                updatedAtEpochMillis == createdAtEpochMillis &&
                    assetReadyAtEpochMillis == null &&
                    terminalAtEpochMillis == null
            ASSET_READY ->
                assetReadyAtEpochMillis != null &&
                    assetReadyAtEpochMillis == updatedAtEpochMillis &&
                    terminalAtEpochMillis == null
            COMMITTED ->
                assetReadyAtEpochMillis != null &&
                    terminalAtEpochMillis != null &&
                    assetReadyAtEpochMillis >= createdAtEpochMillis &&
                    terminalAtEpochMillis == updatedAtEpochMillis &&
                    terminalAtEpochMillis >= assetReadyAtEpochMillis
            in TERMINAL_REJECTION_STATES ->
                terminalAtEpochMillis != null &&
                    terminalAtEpochMillis == updatedAtEpochMillis &&
                    (assetReadyAtEpochMillis == null ||
                        (assetReadyAtEpochMillis >= createdAtEpochMillis &&
                            assetReadyAtEpochMillis <= terminalAtEpochMillis))
            else -> false
        }
    }

    private fun ReferenceImportIntentEntity.toToken(): ReferenceImportToken =
        try {
            ReferenceImportToken(importToken)
        } catch (_: IllegalArgumentException) {
            throw ReferenceImportAuthorityInconsistentException()
        }

    private fun ReferenceImportIntentEntity.toPendingReferenceImport(): PendingReferenceImport =
        PendingReferenceImport(
            importToken = toToken(),
            shootId = shootId,
            poseId = poseId,
            relativeAssetPath = relativeAssetPath,
            lifecycle = when (lifecycleState) {
                PREPARING -> ReferenceImportLifecycle.PREPARING
                ASSET_READY -> ReferenceImportLifecycle.ASSET_READY
                COMMITTED -> ReferenceImportLifecycle.COMMITTED
                REJECTED_CLEANED -> ReferenceImportLifecycle.REJECTED_CLEANED
                REJECTED_QUARANTINED -> ReferenceImportLifecycle.REJECTED_QUARANTINED
                else -> throw ReferenceImportAuthorityInconsistentException()
            },
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun ReferenceImportEvidence.toValidatedPose(poseIndex: Int): ShootPoseEntity = ShootPoseEntity(
        shootId = shootId,
        poseIndex = poseIndex,
        poseId = poseId,
        label = label,
        referenceAssetPath = relativeAssetPath,
        mirrorAllowed = mirrorAllowed,
        validationState = VALIDATED,
        detectorMetadata = detectorMetadata,
        modelMetadata = modelMetadata,
        preprocessingMetadata = preprocessingMetadata,
        landmarkPayload = landmarkPayload.value,
        coordinateMetadata = coordinateMetadata,
    )

    private fun ShootPoseEntity.matches(evidence: ReferenceImportEvidence): Boolean =
        this == evidence.toValidatedPose(poseIndex)

    private fun <T> inTransaction(block: () -> T): T =
        database.runInTransaction(Callable(block))

    private val ReferenceImportFailureSettlement.lifecycleState: String
        get() = when (this) {
            ReferenceImportFailureSettlement.CLEANED -> REJECTED_CLEANED
            ReferenceImportFailureSettlement.QUARANTINED -> REJECTED_QUARANTINED
        }

    private class ReferenceImportCasFailedException :
        RuntimeException("reference import compare-and-set failed")

    private class ReferenceImportAuthorityInconsistentException :
        RuntimeException("reference import authority is inconsistent")

    private companion object {
        val MAX_REFERENCE_COUNT = Shoot.MAX_REFERENCE_POSES.toLong()
        const val AUTHORITY_PAGE_SIZE = 20
        const val ACTIVE = "ACTIVE"
        const val DELETING = "DELETING"
        const val PREPARING = "PREPARING"
        const val ASSET_READY = "ASSET_READY"
        const val COMMITTED = "COMMITTED"
        const val REJECTED_CLEANED = "REJECTED_CLEANED"
        const val REJECTED_QUARANTINED = "REJECTED_QUARANTINED"
        const val LEGACY_VALID = "VALID"
        const val VALIDATED = "VALIDATED"
        val ACCEPTED_VALIDATION_STATES = setOf(LEGACY_VALID, VALIDATED)
        val TERMINAL_REJECTION_STATES = setOf(
            REJECTED_CLEANED,
            REJECTED_QUARANTINED,
        )
    }
}

internal enum class GlobalReferenceImportWorkState {
    CLEAR,
    IN_PROGRESS,
    RECONCILIATION_REQUIRED,
}
