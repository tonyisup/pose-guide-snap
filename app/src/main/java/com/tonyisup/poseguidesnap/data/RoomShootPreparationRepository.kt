package com.tonyisup.poseguidesnap.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.db.ShootEntity
import com.tonyisup.poseguidesnap.data.db.ShootPreparationEditorRow
import com.tonyisup.poseguidesnap.data.db.ShootPreparationImportWorkRow
import com.tonyisup.poseguidesnap.data.db.ShootPreparationPoseRow
import com.tonyisup.poseguidesnap.data.db.ShootPreparationShootRow
import com.tonyisup.poseguidesnap.data.db.ShootPoseEntity
import com.tonyisup.poseguidesnap.data.db.ShootSessionEntity
import com.tonyisup.poseguidesnap.domain.model.Shoot
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.Callable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomShootPreparationRepository(
    private val database: AppDatabase,
) {
    private val dao = database.shootPreparationDao()

    fun createShoot(
        shootId: String,
        name: String,
        createdAtEpochMillis: Long,
    ): ShootCreateResult {
        if (!shootId.isValidCreateId()) {
            return rejected(ShootCreateRejectionReason.INVALID_ID)
        }
        val trimmedName = name.trim()
        if (!trimmedName.isSafeProjectionText()) {
            return rejected(ShootCreateRejectionReason.INVALID_NAME)
        }
        if (createdAtEpochMillis < 0L) {
            return rejected(ShootCreateRejectionReason.INVALID_TIMESTAMP)
        }

        return try {
            inTransaction {
                createShootInTransaction(
                    shootId = shootId,
                    trimmedName = trimmedName,
                    createdAtEpochMillis = createdAtEpochMillis,
                )
            }
        } catch (_: SQLiteConstraintException) {
            classifyCreateCollision(shootId, trimmedName, createdAtEpochMillis)
        } catch (_: Exception) {
            rejected(ShootCreateRejectionReason.AUTHORITY_INCONSISTENT)
        }
    }

    fun observeShoots(): Flow<List<ShootSummary>> =
        dao.observeShoots().map { rows ->
            immutableList(rows.map { row -> row.toValidatedSummary() })
        }

    fun observeShootPage(limit: Int, offset: Int): Flow<ShootSummaryPage> {
        val request = ShootPageRequest(limit, offset)
        return dao.observeShootPage(request.queryLimit, request.offset).map { rows ->
            projectShootSummaryPage(
                rows = rows,
                request = request,
                mapRow = { row -> row.toValidatedSummary() },
            )
        }
    }

    fun observeShootEditor(shootId: String): Flow<ShootEditorSnapshot?> =
        dao.observeEditorRows(shootId).map { rows ->
            toEditorSnapshot(rows)
        }

    fun reorderValidatedReferences(
        shootId: String,
        orderedPoseIds: List<String>,
        reorderedAtEpochMillis: Long,
    ): ShootReorderResult {
        val validation = try {
            ShootReorderPolicy.validateRequest(
                shootId = shootId,
                orderedPoseIds = orderedPoseIds,
                reorderedAtEpochMillis = reorderedAtEpochMillis,
            )
        } catch (_: RuntimeException) {
            return ShootReorderResult.AuthorityInconsistent
        }
        return when (validation) {
            is ShootReorderRequestValidation.Invalid -> validation.result
            is ShootReorderRequestValidation.Valid -> try {
                inTransaction { reorderValidatedReferencesInTransaction(validation) }
            } catch (_: SQLiteConstraintException) {
                classifyReorderRace(validation)
            } catch (_: SQLiteException) {
                classifyReorderRace(validation)
            } catch (_: ShootReorderCasException) {
                classifyReorderRace(validation)
            } catch (_: Exception) {
                ShootReorderResult.AuthorityInconsistent
            }
        }
    }

    fun startShoot(
        shootId: String,
        sessionId: String,
        startedAtEpochMillis: Long,
    ): ShootStartResult {
        val validation = try {
            ShootStartPolicy.validateRequest(shootId, sessionId, startedAtEpochMillis)
        } catch (_: RuntimeException) {
            return ShootStartResult.AuthorityInconsistent
        }
        return when (validation) {
            is ShootStartRequestValidation.Invalid -> validation.result
            is ShootStartRequestValidation.Valid -> try {
                inTransaction { startShootInTransaction(validation) }
            } catch (_: SQLiteConstraintException) {
                classifyStartRace(validation)
            } catch (_: SQLiteException) {
                classifyStartRace(validation)
            } catch (_: ShootStartWriteException) {
                classifyStartRace(validation)
            } catch (_: Exception) {
                ShootStartResult.AuthorityInconsistent
            }
        }
    }

    private fun startShootInTransaction(
        request: ShootStartRequestValidation.Valid,
    ): ShootStartResult {
        val preparation = prepareStartInCurrentTransaction(request)
        if (preparation is ShootStartPreparation.Closed) return preparation.result
        preparation as ShootStartPreparation.Ready

        dao.insertSession(preparation.expectedSession)
        val inserted = dao.findSession(request.sessionId)
        if (
            inserted != preparation.expectedSession ||
            dao.countActiveSessions(request.shootId) != 1
        ) {
            throw ShootStartWriteException()
        }
        return ShootStartResult.Started
    }

    private fun prepareStartInCurrentTransaction(
        request: ShootStartRequestValidation.Valid,
    ): ShootStartPreparation {
        val exactSession = dao.findSession(request.sessionId)?.toStartSnapshot()
        when (ShootStartPolicy.classifySessionIdentity(request, exactSession)) {
            ShootStartIdentityDecision.ABSENT,
            ShootStartIdentityDecision.OWNED,
            -> Unit
            ShootStartIdentityDecision.SESSION_IDENTITY_CONFLICT ->
                return ShootStartPreparation.Closed(ShootStartResult.SessionIdentityConflict)
            ShootStartIdentityDecision.AUTHORITY_INCONSISTENT ->
                throw ShootPreparationProjectionException()
        }

        val shoot = dao.findShoot(request.shootId)
            ?: return ShootStartPreparation.Closed(ShootStartResult.UnknownShoot)
        if (shoot.shootId != request.shootId) throw ShootPreparationProjectionException()
        when (shoot.requireCoherentProjection()) {
            ShootPreparationLifecycle.DELETING ->
                return ShootStartPreparation.Closed(ShootStartResult.ShootDeleting)
            ShootPreparationLifecycle.ACTIVE -> Unit
        }

        when (RoomReferenceImportRepository(database).inspectGlobalImportWorkInCurrentTransaction()) {
            GlobalReferenceImportWorkState.CLEAR -> Unit
            GlobalReferenceImportWorkState.IN_PROGRESS,
            GlobalReferenceImportWorkState.RECONCILIATION_REQUIRED,
            -> return ShootStartPreparation.Closed(ShootStartResult.UnresolvedImportWork)
        }

        val poses = dao.findAllPoseEntitiesInOrder(request.shootId)
        if (
            poses.size.toLong() != shoot.acceptedReferenceCount ||
            poses.size.toLong() != shoot.totalReferenceCount ||
            poses.map(ShootPoseEntity::poseId).distinct().size != poses.size ||
            poses.anyIndexed { index, pose ->
                !pose.hasCoherentValidatedPoseAuthority(request.shootId, index)
            }
        ) {
            throw ShootPreparationProjectionException()
        }
        when (ShootStartPolicy.classifyPlaylistCardinality(poses.size.toLong())) {
            ShootStartPlaylistDecision.ELIGIBLE -> Unit
            ShootStartPlaylistDecision.INELIGIBLE_TOO_FEW -> return ShootStartPreparation.Closed(
                ShootStartResult.IneligiblePlaylist(
                    ShootStartIneligibleReason.TOO_FEW_VALIDATED_REFERENCES,
                ),
            )
            ShootStartPlaylistDecision.AUTHORITY_INCONSISTENT ->
                throw ShootPreparationProjectionException()
        }

        val activeSessionCount = dao.countActiveSessions(request.shootId)
        return when (
            ShootStartPolicy.classifySession(request, exactSession, activeSessionCount)
        ) {
            ShootStartSessionDecision.ELIGIBLE -> ShootStartPreparation.Ready(
                ShootSessionEntity(
                    sessionId = request.sessionId,
                    shootId = request.shootId,
                    currentPoseIndex = 0,
                    nextAttemptNumber = 0L,
                    lifecycleState = ACTIVE,
                    createdAtEpochMillis = request.startedAtEpochMillis,
                    updatedAtEpochMillis = request.startedAtEpochMillis,
                ),
            )
            ShootStartSessionDecision.ALREADY_STARTED ->
                ShootStartPreparation.Closed(ShootStartResult.AlreadyStarted)
            ShootStartSessionDecision.ACTIVE_SESSION_CONFLICT ->
                ShootStartPreparation.Closed(ShootStartResult.ActiveSessionConflict)
            ShootStartSessionDecision.SESSION_IDENTITY_CONFLICT ->
                ShootStartPreparation.Closed(ShootStartResult.SessionIdentityConflict)
            ShootStartSessionDecision.STALE_OR_CONFLICTING_REPLAY ->
                ShootStartPreparation.Closed(ShootStartResult.StaleOrConflictingReplay)
            ShootStartSessionDecision.AUTHORITY_INCONSISTENT ->
                throw ShootPreparationProjectionException()
        }
    }

    private fun classifyStartRace(
        request: ShootStartRequestValidation.Valid,
    ): ShootStartResult =
        try {
            inTransaction {
                when (val preparation = prepareStartInCurrentTransaction(request)) {
                    is ShootStartPreparation.Closed -> preparation.result
                    is ShootStartPreparation.Ready -> ShootStartResult.AuthorityInconsistent
                }
            }
        } catch (_: Exception) {
            ShootStartResult.AuthorityInconsistent
        }

    private fun reorderValidatedReferencesInTransaction(
        request: ShootReorderRequestValidation.Valid,
    ): ShootReorderResult {
        val preparation = prepareReorderInCurrentTransaction(request)
        if (preparation is ShootReorderPreparation.Closed) return preparation.result
        preparation as ShootReorderPreparation.Ready

        preparation.poses.forEach { pose ->
            requireReorderCas(
                dao.compareAndSetPoseIndex(
                    shootId = request.shootId,
                    poseId = pose.poseId,
                    expectedPoseIndex = pose.poseIndex,
                    targetPoseIndex = -(pose.poseIndex + 1),
                ),
            )
        }
        val originalIndices = preparation.poses
            .associate { pose -> pose.poseId to pose.poseIndex }
        request.orderedPoseIds.forEachIndexed { targetIndex, poseId ->
            val originalIndex = originalIndices[poseId] ?: throw ShootReorderCasException()
            requireReorderCas(
                dao.compareAndSetPoseIndex(
                    shootId = request.shootId,
                    poseId = poseId,
                    expectedPoseIndex = -(originalIndex + 1),
                    targetPoseIndex = targetIndex,
                ),
            )
        }
        requireReorderCas(
            dao.compareAndSetShootUpdatedAt(
                shootId = request.shootId,
                expectedDeletionGeneration = preparation.shoot.deletionGeneration,
                expectedUpdatedAtEpochMillis = preparation.shoot.updatedAtEpochMillis,
                reorderedAtEpochMillis = request.reorderedAtEpochMillis,
            ),
        )

        val expectedById = preparation.poses.associateBy(ShootPoseEntity::poseId)
        val finalPoses = dao.findAllPoseEntitiesInOrder(request.shootId)
        if (
            finalPoses.size != preparation.poses.size ||
            finalPoses.map(ShootPoseEntity::poseId) != request.orderedPoseIds ||
            finalPoses.anyIndexed { index, pose ->
                pose.poseIndex != index || pose != expectedById[pose.poseId]?.copy(poseIndex = index)
            }
        ) {
            throw ShootReorderCasException()
        }
        val finalShoot = dao.findShoot(request.shootId) ?: throw ShootReorderCasException()
        finalShoot.requireCoherentProjection()
        if (
            finalShoot != preparation.shoot.copy(
                updatedAtEpochMillis = request.reorderedAtEpochMillis,
            )
        ) {
            throw ShootReorderCasException()
        }
        return ShootReorderResult.Reordered
    }

    private fun prepareReorderInCurrentTransaction(
        request: ShootReorderRequestValidation.Valid,
    ): ShootReorderPreparation {
        val shoot = dao.findShoot(request.shootId)
            ?: return ShootReorderPreparation.Closed(ShootReorderResult.UnknownShoot)
        if (shoot.shootId != request.shootId) throw ShootPreparationProjectionException()
        when (shoot.requireCoherentProjection()) {
            ShootPreparationLifecycle.DELETING ->
                return ShootReorderPreparation.Closed(ShootReorderResult.ShootDeleting)
            ShootPreparationLifecycle.ACTIVE -> Unit
        }

        val activeSessionCount = dao.countActiveSessions(request.shootId)
        if (activeSessionCount !in 0..1) throw ShootPreparationProjectionException()
        if (activeSessionCount == 1) {
            return ShootReorderPreparation.Closed(ShootReorderResult.ActiveSession)
        }
        if (
            RoomReferenceImportRepository(database).inspectGlobalImportWorkInCurrentTransaction() !=
            GlobalReferenceImportWorkState.CLEAR
        ) {
            return ShootReorderPreparation.Closed(ShootReorderResult.UnresolvedImportWork)
        }

        val poses = dao.findAllPoseEntitiesInOrder(request.shootId)
        if (
            poses.size !in 2..Shoot.MAX_REFERENCE_POSES ||
            poses.size.toLong() != shoot.acceptedReferenceCount ||
            poses.size.toLong() != shoot.totalReferenceCount ||
            poses.map(ShootPoseEntity::poseId).distinct().size != poses.size ||
            poses.anyIndexed { index, pose ->
                !pose.hasCoherentValidatedPoseAuthority(request.shootId, index)
            }
        ) {
            throw ShootPreparationProjectionException()
        }

        return when (
            ShootReorderPolicy.classifyValidatedOrder(
                currentPoseIds = poses.map(ShootPoseEntity::poseId),
                orderedPoseIds = request.orderedPoseIds,
                persistedUpdatedAtEpochMillis = shoot.updatedAtEpochMillis,
                reorderedAtEpochMillis = request.reorderedAtEpochMillis,
            )
        ) {
            ShootReorderOrderDecision.MUTATE -> ShootReorderPreparation.Ready(shoot, poses)
            ShootReorderOrderDecision.ALREADY_ORDERED ->
                ShootReorderPreparation.Closed(ShootReorderResult.AlreadyOrdered)
            ShootReorderOrderDecision.INVALID_ORDER -> ShootReorderPreparation.Closed(
                ShootReorderResult.InvalidRequest(ShootReorderInvalidReason.ORDER_MISMATCH),
            )
            ShootReorderOrderDecision.STALE_TIMESTAMP ->
                ShootReorderPreparation.Closed(ShootReorderResult.StaleTimestamp)
            ShootReorderOrderDecision.AUTHORITY_INCONSISTENT ->
                throw ShootPreparationProjectionException()
        }
    }

    private fun classifyReorderRace(
        request: ShootReorderRequestValidation.Valid,
    ): ShootReorderResult =
        try {
            inTransaction {
                when (val preparation = prepareReorderInCurrentTransaction(request)) {
                    is ShootReorderPreparation.Closed -> preparation.result
                    is ShootReorderPreparation.Ready -> ShootReorderResult.AuthorityInconsistent
                }
            }
        } catch (_: Exception) {
            ShootReorderResult.AuthorityInconsistent
        }

    private fun requireReorderCas(updatedRowCount: Int) {
        if (updatedRowCount != 1) throw ShootReorderCasException()
    }

    private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
        forEachIndexed { index, value -> if (predicate(index, value)) return true }
        return false
    }

    override fun toString(): String = "RoomShootPreparationRepository(redacted)"

    private fun createShootInTransaction(
        shootId: String,
        trimmedName: String,
        createdAtEpochMillis: Long,
    ): ShootCreateResult {
        dao.findShoot(shootId)?.let { existing ->
            return classifyExistingShoot(existing, shootId, trimmedName, createdAtEpochMillis)
        }

        val expected = ShootEntity(
            shootId = shootId,
            name = trimmedName,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
            lifecycleState = ACTIVE,
            deletionGeneration = 0L,
        )
        dao.insertShoot(expected)

        val inserted = dao.findShoot(shootId)
            ?: throw ShootPreparationProjectionException()
        inserted.requireCoherentProjection()
        if (
            inserted.shootId != expected.shootId ||
            inserted.name != expected.name ||
            inserted.createdAtEpochMillis != expected.createdAtEpochMillis ||
            inserted.updatedAtEpochMillis != expected.updatedAtEpochMillis ||
            inserted.lifecycleState != expected.lifecycleState ||
            inserted.deletionGeneration != expected.deletionGeneration ||
            inserted.acceptedReferenceCount != 0L ||
            inserted.totalReferenceCount != 0L
        ) {
            throw ShootPreparationProjectionException()
        }
        return ShootCreateResult.Created(inserted.toValidatedSummary())
    }

    private fun classifyCreateCollision(
        shootId: String,
        trimmedName: String,
        createdAtEpochMillis: Long,
    ): ShootCreateResult =
        try {
            inTransaction {
                val existing = dao.findShoot(shootId)
                    ?: throw ShootPreparationProjectionException()
                classifyExistingShoot(existing, shootId, trimmedName, createdAtEpochMillis)
            }
        } catch (_: Exception) {
            rejected(ShootCreateRejectionReason.AUTHORITY_INCONSISTENT)
        }

    private fun classifyExistingShoot(
        existing: ShootPreparationShootRow,
        shootId: String,
        trimmedName: String,
        createdAtEpochMillis: Long,
    ): ShootCreateResult {
        val lifecycle = existing.requireCoherentProjection()
        if (existing.shootId != shootId) {
            throw ShootPreparationProjectionException()
        }
        if (
            lifecycle == ShootPreparationLifecycle.ACTIVE &&
            existing.name == trimmedName &&
            existing.createdAtEpochMillis == createdAtEpochMillis
        ) {
            return ShootCreateResult.AlreadyExists(existing.toValidatedSummary())
        }
        return rejected(ShootCreateRejectionReason.ID_CONFLICT)
    }

    private fun toEditorSnapshot(
        rows: List<ShootPreparationEditorRow>,
    ): ShootEditorSnapshot? {
        if (rows.isEmpty()) {
            return null
        }
        val first = rows.first()
        val shoot = ShootPreparationShootRow(
            shootId = first.shootId,
            name = first.shootName,
            createdAtEpochMillis = first.shootCreatedAtEpochMillis,
            updatedAtEpochMillis = first.shootUpdatedAtEpochMillis,
            lifecycleState = first.shootLifecycleState,
            deletionGeneration = first.shootDeletionGeneration,
            acceptedReferenceCount = first.acceptedReferenceCount,
            totalReferenceCount = first.totalReferenceCount,
        )
        if (rows.any { row -> !row.matchesShoot(shoot) }) {
            throw ShootPreparationProjectionException()
        }

        val poses = rows.mapNotNull { row -> row.toPoseRowOrNull() }
            .groupBy(ShootPreparationPoseRow::poseId)
            .map { (_, duplicates) ->
                duplicates.distinct().singleOrNull()
                    ?: throw ShootPreparationProjectionException()
            }
            .sortedBy(ShootPreparationPoseRow::poseIndex)
        val work = rows.mapNotNull { row -> row.toImportWorkRowOrNull() }
            .groupBy(ShootPreparationImportWorkRow::importToken)
            .map { (_, duplicates) ->
                duplicates.distinct().singleOrNull()
                    ?: throw ShootPreparationProjectionException()
            }
            .sortedWith(
                compareBy<ShootPreparationImportWorkRow> { item -> item.intentCreatedAtEpochMillis }
                    .thenBy(ShootPreparationImportWorkRow::importToken),
            )

        val lifecycle = shoot.requireCoherentProjection()
        val references = poses.mapIndexed { expectedIndex, pose ->
            pose.toValidatedReference(expectedIndex)
        }
        if (
            references.size > Shoot.MAX_REFERENCE_POSES ||
            references.map(ValidatedReferenceSummary::poseId).distinct().size != references.size ||
            references.size.toLong() != shoot.acceptedReferenceCount
        ) {
            throw ShootPreparationProjectionException()
        }
        val importWork = work.map { item -> item.toValidatedImportWork() }
        if (importWork.size > Shoot.MAX_REFERENCE_POSES) {
            throw ShootPreparationProjectionException()
        }
        val snapshotUpdatedAt = importWork.fold(shoot.updatedAtEpochMillis) { latest, item ->
            maxOf(latest, item.updatedAtEpochMillis)
        }

        return projectionChecked {
            ShootEditorSnapshot(
                shootId = shoot.shootId,
                name = shoot.name,
                lifecycle = lifecycle,
                validatedReferences = references,
                importWork = importWork,
                updatedAtEpochMillis = snapshotUpdatedAt,
            )
        }
    }

    private fun ShootPreparationEditorRow.matchesShoot(shoot: ShootPreparationShootRow): Boolean =
        shootId == shoot.shootId &&
            shootName == shoot.name &&
            shootCreatedAtEpochMillis == shoot.createdAtEpochMillis &&
            shootUpdatedAtEpochMillis == shoot.updatedAtEpochMillis &&
            shootLifecycleState == shoot.lifecycleState &&
            shootDeletionGeneration == shoot.deletionGeneration &&
            acceptedReferenceCount == shoot.acceptedReferenceCount &&
            totalReferenceCount == shoot.totalReferenceCount

    private fun ShootPreparationEditorRow.toPoseRowOrNull(): ShootPreparationPoseRow? {
        if (poseId == null) {
            if (
                poseIndex != null || poseLabel != null || poseMirrorAllowed != null ||
                poseValidationState != null
            ) {
                throw ShootPreparationProjectionException()
            }
            return null
        }
        return ShootPreparationPoseRow(
            poseId = poseId,
            poseIndex = poseIndex ?: throw ShootPreparationProjectionException(),
            label = poseLabel ?: throw ShootPreparationProjectionException(),
            mirrorAllowed = poseMirrorAllowed ?: throw ShootPreparationProjectionException(),
            validationState = poseValidationState ?: throw ShootPreparationProjectionException(),
        )
    }

    private fun ShootPreparationEditorRow.toImportWorkRowOrNull(): ShootPreparationImportWorkRow? {
        if (importToken == null) {
            if (
                intentPoseId != null || intentRelativeAssetPath != null || intentLifecycleState != null ||
                intentCreatedAtEpochMillis != null || intentUpdatedAtEpochMillis != null ||
                assetReadyAtEpochMillis != null || terminalAtEpochMillis != null ||
                fileRelativeAssetPath != null || fileRelativeTempPath != null ||
                fileRelativeQuarantinePath != null || fileStage != null || byteCount != null ||
                sha256 != null || lastFailureCode != null || reconciliationRequired != null ||
                fileCreatedAtEpochMillis != null || fileUpdatedAtEpochMillis != null
            ) {
                throw ShootPreparationProjectionException()
            }
            return null
        }
        return ShootPreparationImportWorkRow(
            importToken = importToken,
            poseId = intentPoseId ?: throw ShootPreparationProjectionException(),
            intentRelativeAssetPath = intentRelativeAssetPath
                ?: throw ShootPreparationProjectionException(),
            intentLifecycleState = intentLifecycleState
                ?: throw ShootPreparationProjectionException(),
            intentCreatedAtEpochMillis = intentCreatedAtEpochMillis
                ?: throw ShootPreparationProjectionException(),
            intentUpdatedAtEpochMillis = intentUpdatedAtEpochMillis
                ?: throw ShootPreparationProjectionException(),
            assetReadyAtEpochMillis = assetReadyAtEpochMillis,
            terminalAtEpochMillis = terminalAtEpochMillis,
            fileRelativeAssetPath = fileRelativeAssetPath
                ?: throw ShootPreparationProjectionException(),
            fileRelativeTempPath = fileRelativeTempPath
                ?: throw ShootPreparationProjectionException(),
            fileRelativeQuarantinePath = fileRelativeQuarantinePath
                ?: throw ShootPreparationProjectionException(),
            fileStage = fileStage ?: throw ShootPreparationProjectionException(),
            byteCount = byteCount,
            sha256 = sha256,
            lastFailureCode = lastFailureCode,
            reconciliationRequired = reconciliationRequired
                ?: throw ShootPreparationProjectionException(),
            fileCreatedAtEpochMillis = fileCreatedAtEpochMillis
                ?: throw ShootPreparationProjectionException(),
            fileUpdatedAtEpochMillis = fileUpdatedAtEpochMillis
                ?: throw ShootPreparationProjectionException(),
        )
    }

    private fun ShootPreparationShootRow.requireCoherentProjection(): ShootPreparationLifecycle {
        if (
            !shootId.isSafeNormalizedPersistedText() ||
            !name.isSafeNormalizedPersistedText() ||
            createdAtEpochMillis < 0L ||
            updatedAtEpochMillis < createdAtEpochMillis ||
            deletionGeneration < 0L ||
            acceptedReferenceCount !in 0L..MAX_REFERENCE_COUNT ||
            totalReferenceCount !in 0L..MAX_REFERENCE_COUNT ||
            acceptedReferenceCount != totalReferenceCount
        ) {
            throw ShootPreparationProjectionException()
        }
        return when (lifecycleState) {
            ACTIVE -> ShootPreparationLifecycle.ACTIVE
            DELETING -> ShootPreparationLifecycle.DELETING
            else -> throw ShootPreparationProjectionException()
        }
    }

    private fun ShootPreparationShootRow.toValidatedSummary(): ShootSummary {
        val lifecycle = requireCoherentProjection()
        return projectionChecked {
            ShootSummary(
                shootId = shootId,
                name = name,
                validatedReferenceCount = acceptedReferenceCount.toInt(),
                lifecycle = lifecycle,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        }
    }

    private fun ShootPreparationPoseRow.toValidatedReference(
        expectedIndex: Int,
    ): ValidatedReferenceSummary {
        if (
            validationState !in ACCEPTED_VALIDATION_STATES ||
            poseIndex != expectedIndex ||
            !poseId.isSafeProjectionText() ||
            !label.isSafeProjectionText()
        ) {
            throw ShootPreparationProjectionException()
        }
        return projectionChecked {
            ValidatedReferenceSummary(
                poseId = poseId,
                poseIndex = poseIndex,
                label = label,
                mirrorAllowed = mirrorAllowed,
            )
        }
    }

    private fun ShootPoseEntity.hasCoherentValidatedPoseAuthority(
        expectedShootId: String,
        expectedIndex: Int,
    ): Boolean {
        if (
            shootId != expectedShootId ||
            poseIndex != expectedIndex ||
            !ReferenceImportPolicy.validateOwnershipIdentity(poseId) ||
            !label.isSafeProjectionText()
        ) {
            return false
        }
        return when (validationState) {
            VALID ->
                referenceAssetPath == null &&
                    detectorMetadata == null &&
                    modelMetadata == null &&
                    preprocessingMetadata == null &&
                    landmarkPayload == null &&
                    coordinateMetadata == null
            VALIDATED ->
                referenceAssetPath != null &&
                    VALIDATED_REFERENCE_ASSET_PATH.matches(referenceAssetPath) &&
                    detectorMetadata.isSafeAuthorityEvidence() &&
                    modelMetadata.isSafeAuthorityEvidence() &&
                    preprocessingMetadata.isSafeAuthorityEvidence() &&
                    landmarkPayload.isSafeLandmarkAuthorityEvidence() &&
                    coordinateMetadata.isSafeAuthorityEvidence()
            else -> false
        }
    }

    private fun String?.isSafeAuthorityEvidence(): Boolean =
        this != null && isNotBlank() && '\u0000' !in this &&
            !URI_SCHEME.containsMatchIn(this)

    private fun String?.isSafeLandmarkAuthorityEvidence(): Boolean =
        isSafeAuthorityEvidence() && requireNotNull(this).startsWith(LANDMARK_PAYLOAD_PREFIX) &&
            length > LANDMARK_PAYLOAD_PREFIX.length

    private fun ShootSessionEntity.toStartSnapshot(): ShootStartSessionSnapshot =
        ShootStartSessionSnapshot(
            sessionId = sessionId,
            shootId = shootId,
            currentPoseIndex = currentPoseIndex,
            nextAttemptNumber = nextAttemptNumber,
            lifecycleState = lifecycleState,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun ShootPreparationImportWorkRow.toValidatedImportWork(): ImportWorkSummary {
        val token = try {
            ReferenceImportToken(importToken)
        } catch (_: IllegalArgumentException) {
            throw ShootPreparationProjectionException()
        }
        val expectedPaths = try {
            ReferenceImportFileOperationPaths.forToken(token)
        } catch (_: IllegalArgumentException) {
            throw ShootPreparationProjectionException()
        }
        val stage = try {
            ReferenceImportFileOperationStage.valueOf(fileStage)
        } catch (_: IllegalArgumentException) {
            throw ShootPreparationProjectionException()
        }
        val failureCode = try {
            lastFailureCode?.let(ReferenceImportFileFailureCode::valueOf)
        } catch (_: IllegalArgumentException) {
            throw ShootPreparationProjectionException()
        }
        try {
            ReferenceImportFileOperationSnapshot(
                importToken = token,
                paths = expectedPaths,
                stage = stage,
                byteCount = byteCount,
                sha256 = sha256,
                lastFailureCode = failureCode,
                reconciliationRequired = reconciliationRequired,
                createdAtEpochMillis = fileCreatedAtEpochMillis,
                updatedAtEpochMillis = fileUpdatedAtEpochMillis,
            )
        } catch (_: IllegalArgumentException) {
            throw ShootPreparationProjectionException()
        }
        if (
            !poseId.isSafeProjectionText() ||
            !intentRelativeAssetPath.isSafeProjectionText() ||
            intentRelativeAssetPath != expectedPaths.relativeAssetPath ||
            fileRelativeAssetPath != expectedPaths.relativeAssetPath ||
            fileRelativeTempPath != expectedPaths.relativeTempPath ||
            fileRelativeQuarantinePath != expectedPaths.relativeQuarantinePath ||
            fileCreatedAtEpochMillis != intentCreatedAtEpochMillis ||
            intentCreatedAtEpochMillis < 0L ||
            intentUpdatedAtEpochMillis < intentCreatedAtEpochMillis ||
            !hasCoherentIntentTimeline()
        ) {
            throw ShootPreparationProjectionException()
        }

        val status = when (intentLifecycleState) {
            PREPARING,
            ASSET_READY,
            -> if (reconciliationRequired) {
                ImportWorkStatus.RECONCILIATION_REQUIRED
            } else {
                ImportWorkStatus.IN_PROGRESS
            }
            REJECTED_QUARANTINED -> {
                if (stage != ReferenceImportFileOperationStage.QUARANTINE_DURABLE) {
                    throw ShootPreparationProjectionException()
                }
                if (reconciliationRequired) {
                    ImportWorkStatus.RECONCILIATION_REQUIRED
                } else {
                    ImportWorkStatus.REJECTED_QUARANTINED
                }
            }
            else -> throw ShootPreparationProjectionException()
        }
        return projectionChecked {
            ImportWorkSummary(
                status = status,
                createdAtEpochMillis = intentCreatedAtEpochMillis,
                updatedAtEpochMillis = maxOf(
                    intentUpdatedAtEpochMillis,
                    fileUpdatedAtEpochMillis,
                ),
            )
        }
    }

    private fun ShootPreparationImportWorkRow.hasCoherentIntentTimeline(): Boolean =
        when (intentLifecycleState) {
            PREPARING ->
                intentUpdatedAtEpochMillis == intentCreatedAtEpochMillis &&
                    assetReadyAtEpochMillis == null &&
                    terminalAtEpochMillis == null
            ASSET_READY ->
                assetReadyAtEpochMillis != null &&
                    assetReadyAtEpochMillis == intentUpdatedAtEpochMillis &&
                    terminalAtEpochMillis == null
            REJECTED_QUARANTINED ->
                terminalAtEpochMillis != null &&
                    terminalAtEpochMillis == intentUpdatedAtEpochMillis &&
                    (assetReadyAtEpochMillis == null ||
                        (assetReadyAtEpochMillis >= intentCreatedAtEpochMillis &&
                            assetReadyAtEpochMillis <= terminalAtEpochMillis))
            else -> false
        }

    private fun String.isValidCreateId(): Boolean =
        this == trim() && isSafeProjectionText()

    private fun String.isSafeNormalizedPersistedText(): Boolean =
        this == trim() && isSafeProjectionText()

    private fun String.isSafeProjectionText(): Boolean =
        isNotBlank() && '\u0000' !in this && !contains(PROVIDER_URI_PREFIX, ignoreCase = true)

    private inline fun <T> projectionChecked(block: () -> T): T =
        try {
            block()
        } catch (_: RuntimeException) {
            throw ShootPreparationProjectionException()
        }

    private fun <T> inTransaction(block: () -> T): T =
        database.runInTransaction(Callable(block))

    private fun rejected(reason: ShootCreateRejectionReason): ShootCreateResult =
        ShootCreateResult.Rejected(reason)

    private fun <T> immutableList(values: Iterable<T>): List<T> =
        Collections.unmodifiableList(ArrayList<T>().apply { addAll(values) })

    private class ShootPreparationProjectionException : IllegalStateException() {
        override fun toString(): String = "ShootPreparationProjectionException"
    }

    private class ShootReorderCasException : RuntimeException() {
        override fun toString(): String = "ShootReorderCasException"
    }

    private class ShootStartWriteException : RuntimeException() {
        override fun toString(): String = "ShootStartWriteException"
    }

    private sealed interface ShootStartPreparation {
        data class Ready(val expectedSession: ShootSessionEntity) : ShootStartPreparation

        data class Closed(val result: ShootStartResult) : ShootStartPreparation
    }

    private sealed interface ShootReorderPreparation {
        data class Ready(
            val shoot: ShootPreparationShootRow,
            val poses: List<ShootPoseEntity>,
        ) : ShootReorderPreparation

        data class Closed(val result: ShootReorderResult) : ShootReorderPreparation
    }

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val DELETING = "DELETING"
        const val PREPARING = "PREPARING"
        const val ASSET_READY = "ASSET_READY"
        const val VALID = "VALID"
        const val VALIDATED = "VALIDATED"
        const val REJECTED_QUARANTINED = "REJECTED_QUARANTINED"
        const val PROVIDER_URI_PREFIX = "content://"
        const val LANDMARK_PAYLOAD_PREFIX = "v1|"
        val MAX_REFERENCE_COUNT = Shoot.MAX_REFERENCE_POSES.toLong()
        val ACCEPTED_VALIDATION_STATES = setOf(VALID, VALIDATED)
        val VALIDATED_REFERENCE_ASSET_PATH =
            Regex("reference-assets/assets/[0-9a-f]{64}\\.asset")
        val URI_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*://")
    }
}
