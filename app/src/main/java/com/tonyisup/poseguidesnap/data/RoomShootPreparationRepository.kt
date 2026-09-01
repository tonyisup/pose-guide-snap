package com.tonyisup.poseguidesnap.data

import android.database.sqlite.SQLiteConstraintException
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.db.ShootEntity
import com.tonyisup.poseguidesnap.data.db.ShootPreparationEditorRow
import com.tonyisup.poseguidesnap.data.db.ShootPreparationImportWorkRow
import com.tonyisup.poseguidesnap.data.db.ShootPreparationPoseRow
import com.tonyisup.poseguidesnap.data.db.ShootPreparationShootRow
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

    fun observeShootEditor(shootId: String): Flow<ShootEditorSnapshot?> =
        dao.observeEditorRows(shootId).map { rows ->
            toEditorSnapshot(rows)
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

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val DELETING = "DELETING"
        const val PREPARING = "PREPARING"
        const val ASSET_READY = "ASSET_READY"
        const val REJECTED_QUARANTINED = "REJECTED_QUARANTINED"
        const val PROVIDER_URI_PREFIX = "content://"
        val MAX_REFERENCE_COUNT = Shoot.MAX_REFERENCE_POSES.toLong()
        val ACCEPTED_VALIDATION_STATES = setOf("VALID", "VALIDATED")
    }
}
