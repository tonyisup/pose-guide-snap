package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.ClaimReferenceAssetFilesResult
import com.tonyisup.poseguidesnap.data.DeleteExactForCleanupResult
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetEvidence
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetStore
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetVerificationResult
import com.tonyisup.poseguidesnap.data.ReferenceAssetByteSource
import com.tonyisup.poseguidesnap.data.ReferenceAssetIdentity
import com.tonyisup.poseguidesnap.data.ReferenceImportAssetPath
import com.tonyisup.poseguidesnap.data.ReferenceImportAssetReadyResult
import com.tonyisup.poseguidesnap.data.ReferenceImportCommitResult
import com.tonyisup.poseguidesnap.data.ReferenceImportEvidence
import com.tonyisup.poseguidesnap.data.ReferenceImportFailureSettlement
import com.tonyisup.poseguidesnap.data.ReferenceImportFileAdvanceRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportFileFailureCode
import com.tonyisup.poseguidesnap.data.ReferenceImportFileJournalResult
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationStage
import com.tonyisup.poseguidesnap.data.ReferenceImportFileReconciliationRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportReservation
import com.tonyisup.poseguidesnap.data.ReferenceImportReserveRejectionReason
import com.tonyisup.poseguidesnap.data.ReferenceImportReserveResult
import com.tonyisup.poseguidesnap.data.ReferenceImportRestartCleanedResult
import com.tonyisup.poseguidesnap.data.ReferenceImportSettlementResult
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import com.tonyisup.poseguidesnap.data.ReferenceLandmarkPayload
import com.tonyisup.poseguidesnap.data.RenameExactToQuarantineResult
import com.tonyisup.poseguidesnap.data.RenameSyncedTempResult
import com.tonyisup.poseguidesnap.data.RoomReferenceImportFileJournal
import com.tonyisup.poseguidesnap.data.RoomReferenceImportRepository
import com.tonyisup.poseguidesnap.data.WriteAndSyncTempResult
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import java.util.ArrayList
import java.util.Collections
import kotlin.math.min

/** Narrow logical authority; it deliberately excludes reconciliation reads and every DAO surface. */
interface ReferenceImportAuthorityPort {
    fun reserve(
        reservation: ReferenceImportReservation,
        reservedAtEpochMillis: Long,
    ): ReferenceImportReserveResult

    fun restartCleaned(
        reservation: ReferenceImportReservation,
        reservedAtEpochMillis: Long,
    ): ReferenceImportRestartCleanedResult

    fun markAssetReady(
        importToken: ReferenceImportToken,
        relativeAssetPath: String,
        assetReadyAtEpochMillis: Long,
    ): ReferenceImportAssetReadyResult

    fun commit(
        evidence: ReferenceImportEvidence,
        committedAtEpochMillis: Long,
    ): ReferenceImportCommitResult

    fun settleFailure(
        importToken: ReferenceImportToken,
        settlement: ReferenceImportFailureSettlement,
        settledAtEpochMillis: Long,
    ): ReferenceImportSettlementResult
}

/** Narrow persisted file-ledger authority used by the live import coordinator. */
interface ReferenceImportFileJournalPort {
    fun snapshot(importToken: ReferenceImportToken): ReferenceImportFileOperationSnapshot?

    fun advance(request: ReferenceImportFileAdvanceRequest): ReferenceImportFileJournalResult

    fun markReconciliationRequired(
        request: ReferenceImportFileReconciliationRequest,
    ): ReferenceImportFileJournalResult
}

/** Narrow staged filesystem effects; no process-local ownership handle leaves the store. */
interface JournaledReferenceImportAssetPort {
    fun claimReservationAndTemp(identity: ReferenceAssetIdentity): ClaimReferenceAssetFilesResult

    fun writeAndSyncClaimedTemp(
        identity: ReferenceAssetIdentity,
        source: ReferenceAssetByteSource,
    ): WriteAndSyncTempResult

    fun renameSyncedTemp(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): RenameSyncedTempResult

    fun syncAndVerifyFinal(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): JournaledReferenceAssetVerificationResult

    fun deleteExactForCleanup(
        identity: ReferenceAssetIdentity,
        sourceStage: ReferenceImportFileOperationStage,
        evidence: JournaledReferenceAssetEvidence? = null,
    ): DeleteExactForCleanupResult

    fun syncAndVerifyCleaned(identity: ReferenceAssetIdentity): JournaledReferenceAssetVerificationResult

    fun renameExactToQuarantine(
        identity: ReferenceAssetIdentity,
        sourceStage: ReferenceImportFileOperationStage,
        evidence: JournaledReferenceAssetEvidence,
    ): RenameExactToQuarantineResult

    fun syncAndVerifyQuarantined(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): JournaledReferenceAssetVerificationResult
}

/** Durable, redacted analyzer input. It contains no process-local ownership capability. */
class DurableReferenceAnalyzerAsset(
    val safeRelativePath: String,
    val byteCount: Long,
    val sha256: String,
) {
    init {
        require(EXACT_ASSET_PATH.matches(safeRelativePath)) {
            "durable reference analyzer path must be an exact safe relative asset path"
        }
        require(byteCount > 0L) { "durable reference analyzer byte count must be positive" }
        require(EXACT_SHA256.matches(sha256)) {
            "durable reference analyzer digest must be canonical"
        }
    }

    override fun toString(): String = "DurableReferenceAnalyzerAsset(redacted)"

    private companion object {
        val EXACT_ASSET_PATH = Regex("reference-assets/assets/[0-9a-f]{64}\\.asset")
        val EXACT_SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** An analyzer can observe only durable path/count/hash evidence. */
fun interface ReferenceImportAnalyzerPort {
    fun analyze(asset: DurableReferenceAnalyzerAsset): ReferenceAnalysisEvidence
}

/**
 * Immutable analyzer output with exact provenance strings.
 *
 * Zero-person output has no selected landmarks. Positive person counts include one immutable,
 * identity-unique selected-person landmark snapshot; the coordinator rejects counts other than one.
 */
class ReferenceAnalysisEvidence(
    val detectedPersonCount: Int,
    landmarks: Iterable<Landmark>,
    val detectorMetadata: String,
    val modelMetadata: String,
    val preprocessingMetadata: String,
    val coordinateMetadata: String,
) {
    val landmarks: List<Landmark> = Collections.unmodifiableList(ArrayList<Landmark>().apply {
        addAll(landmarks)
    })
    internal val canonicalLandmarkPayload: ReferenceLandmarkPayload?

    init {
        require(detectedPersonCount >= 0) { "detected person count must be nonnegative" }
        if (detectedPersonCount == 0) {
            require(this.landmarks.isEmpty()) { "zero-person analysis cannot include selected landmarks" }
            canonicalLandmarkPayload = null
        } else {
            require(this.landmarks.isNotEmpty()) { "detected-person analysis must include landmarks" }
            canonicalLandmarkPayload = ReferenceLandmarkPayload.from(this.landmarks)
        }
        mapOf(
            "detector metadata" to detectorMetadata,
            "model metadata" to modelMetadata,
            "preprocessing metadata" to preprocessingMetadata,
            "coordinate metadata" to coordinateMetadata,
        ).forEach { (name, metadata) ->
            require(metadata.isNotBlank() && !metadata.contains("content://", ignoreCase = true)) {
                "$name must be nonblank and URI-free"
            }
        }
    }

    override fun toString(): String = "ReferenceAnalysisEvidence(redacted)"
}

/** Every wall-clock value is injected by the caller; no coordinator clock or arithmetic is allowed. */
class ReferenceImportLedgerTimeline(
    val reservedAtEpochMillis: Long,
    val writingTempAtEpochMillis: Long,
    val tempSyncedAtEpochMillis: Long,
    val finalRenamePendingSyncAtEpochMillis: Long,
    val finalDurableAtEpochMillis: Long,
    val assetReadyAtEpochMillis: Long,
    val committedAtEpochMillis: Long,
    val cleanupRequiredAtEpochMillis: Long,
    val cleanupPendingSyncAtEpochMillis: Long,
    val cleanedDurableAtEpochMillis: Long,
    val quarantineRequiredAtEpochMillis: Long,
    val quarantinePendingSyncAtEpochMillis: Long,
    val quarantineDurableAtEpochMillis: Long,
    val reconciliationMarkedAtEpochMillis: Long,
    val failureSettledAtEpochMillis: Long,
) {
    init {
        val timestamps = listOf(
            reservedAtEpochMillis,
            writingTempAtEpochMillis,
            tempSyncedAtEpochMillis,
            finalRenamePendingSyncAtEpochMillis,
            finalDurableAtEpochMillis,
            assetReadyAtEpochMillis,
            committedAtEpochMillis,
            cleanupRequiredAtEpochMillis,
            cleanupPendingSyncAtEpochMillis,
            cleanedDurableAtEpochMillis,
            quarantineRequiredAtEpochMillis,
            quarantinePendingSyncAtEpochMillis,
            quarantineDurableAtEpochMillis,
            reconciliationMarkedAtEpochMillis,
            failureSettledAtEpochMillis,
        )
        require(timestamps.first() >= 0L && timestamps.zipWithNext().all { (first, second) ->
            first < second
        }) { "reference import ledger timestamps must be nonnegative and strictly increasing" }
    }

    override fun toString(): String = "ReferenceImportLedgerTimeline(redacted)"
}

class ReferencePoseImportRequest(
    val importToken: ReferenceImportToken,
    val shootId: String,
    val poseId: String,
    val poseIndex: Int,
    val label: String,
    val mirrorAllowed: Boolean,
    val restartCleanedImport: Boolean,
    val timeline: ReferenceImportLedgerTimeline,
    val source: ReferenceAssetByteSource,
) {
    init {
        require(label.isNotBlank() && !label.contains("content://", ignoreCase = true)) {
            "reference label must be nonblank and URI-free"
        }
    }

    override fun toString(): String = "ReferencePoseImportRequest(redacted)"
}

internal object DevelopmentReferenceImportValidationPolicy {
    const val MINIMUM_LANDMARK_CONFIDENCE: Double = 0.25
    const val MINIMUM_MOVENET_LANDMARK_COUNT: Int = 13

    private val torsoAnchors = setOf(
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_HIP,
    )

    private val moveNetIdentities = setOf(
        PoseLandmark.NOSE,
        PoseLandmark.LEFT_EYE,
        PoseLandmark.RIGHT_EYE,
        PoseLandmark.LEFT_EAR,
        PoseLandmark.RIGHT_EAR,
        PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_ELBOW,
        PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST,
        PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_KNEE,
        PoseLandmark.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE,
        PoseLandmark.RIGHT_ANKLE,
    )

    fun rejectionFor(evidence: ReferenceAnalysisEvidence): ReferencePoseImportRejectionReason? {
        if (evidence.detectedPersonCount == 0) return ReferencePoseImportRejectionReason.NO_PERSON
        if (evidence.detectedPersonCount != 1) return ReferencePoseImportRejectionReason.MULTIPLE_PEOPLE

        val acceptedIdentities = evidence.landmarks.asSequence()
            .filter { landmark ->
                min(landmark.visibility, landmark.presence) >= MINIMUM_LANDMARK_CONFIDENCE
            }
            .map(Landmark::type)
            .toSet()
        if (!acceptedIdentities.containsAll(torsoAnchors)) {
            return ReferencePoseImportRejectionReason.LOW_COVERAGE
        }
        if (acceptedIdentities.count(moveNetIdentities::contains) < MINIMUM_MOVENET_LANDMARK_COUNT) {
            return ReferencePoseImportRejectionReason.LOW_COVERAGE
        }
        return null
    }
}

enum class ReferencePoseImportRejectionReason {
    PUBLICATION_FAILED,
    ASSET_READY_REJECTED,
    ANALYZER_FAILED,
    NO_PERSON,
    MULTIPLE_PEOPLE,
    LOW_COVERAGE,
    COMMIT_REJECTED,
    COMMIT_BLOCKED,
}

sealed interface ReferencePoseImportResult {
    class Succeeded(
        val poseId: String,
        val poseIndex: Int,
    ) : ReferencePoseImportResult {
        override fun toString(): String = "ReferencePoseImportResult.Succeeded(redacted)"
    }

    class ReserveRejected(
        val reason: ReferenceImportReserveRejectionReason,
    ) : ReferencePoseImportResult {
        override fun toString(): String =
            "ReferencePoseImportResult.ReserveRejected(reason=${reason.name})"
    }

    class Rejected(
        val reason: ReferencePoseImportRejectionReason,
        val settlement: ReferenceImportFailureSettlement,
    ) : ReferencePoseImportResult {
        override fun toString(): String =
            "ReferencePoseImportResult.Rejected(reason=${reason.name}, settlement=${settlement.name})"
    }

    data object ReconciliationRequired : ReferencePoseImportResult {
        override fun toString(): String = "ReferencePoseImportResult.ReconciliationRequired(redacted)"
    }
}

/** The only live import coordinator: every filesystem effect is preceded/followed by its ledger stage. */
class JournaledReferencePoseImporter(
    private val authority: ReferenceImportAuthorityPort,
    private val journal: ReferenceImportFileJournalPort,
    private val assets: JournaledReferenceImportAssetPort,
    private val analyzer: ReferenceImportAnalyzerPort,
) {
    fun importReference(request: ReferencePoseImportRequest): ReferencePoseImportResult {
        val timeline = request.timeline
        val expectedPath = ReferenceImportAssetPath.forToken(request.importToken)
        val reservation = ReferenceImportReservation(
            importToken = request.importToken,
            shootId = request.shootId,
            poseId = request.poseId,
            poseIndex = request.poseIndex,
            relativeAssetPath = expectedPath,
        )
        val reserveResult = try {
            authority.reserve(reservation, timeline.reservedAtEpochMillis)
        } catch (_: Exception) {
            return ReferencePoseImportResult.ReconciliationRequired
        }
        when (reserveResult) {
            ReferenceImportReserveResult.Reserved -> Unit
            ReferenceImportReserveResult.AlreadyCommitted ->
                return ReferencePoseImportResult.Succeeded(request.poseId, request.poseIndex)
            ReferenceImportReserveResult.ExistingWorkRequiresReconciliation -> {
                if (!request.restartCleanedImport) {
                    return ReferencePoseImportResult.ReconciliationRequired
                }
                val restart = try {
                    authority.restartCleaned(reservation, timeline.reservedAtEpochMillis)
                } catch (_: Exception) {
                    null
                }
                if (restart !== ReferenceImportRestartCleanedResult.Restarted) {
                    return ReferencePoseImportResult.ReconciliationRequired
                }
            }
            is ReferenceImportReserveResult.Rejected ->
                return ReferencePoseImportResult.ReserveRejected(reserveResult.reason)
        }

        val initial = try {
            journal.snapshot(request.importToken)
        } catch (_: Exception) {
            null
        } ?: return ReferencePoseImportResult.ReconciliationRequired
        if (!initial.isExactInitial(request.importToken, expectedPath, timeline.reservedAtEpochMillis)) {
            markReconciliation(initial, timeline, ReferenceImportFileFailureCode.STATE_MISMATCH)
            return ReferencePoseImportResult.ReconciliationRequired
        }

        val identity = ReferenceAssetIdentity(request.importToken)
        val claim = try {
            assets.claimReservationAndTemp(identity)
        } catch (_: Exception) {
            markReconciliation(initial, timeline, ReferenceImportFileFailureCode.RESERVATION_FAILED)
            return ReferencePoseImportResult.ReconciliationRequired
        }
        if (claim is ClaimReferenceAssetFilesResult.Ambiguous) {
            return if (claim.cleanupRequired) {
                cleanupAndSettle(
                    request,
                    identity,
                    initial,
                    ReferencePoseImportRejectionReason.PUBLICATION_FAILED,
                )
            } else {
                markReconciliation(initial, timeline, claim.code)
                ReferencePoseImportResult.ReconciliationRequired
            }
        }

        val writing = advanceOrReconcile(
            initial,
            ReferenceImportFileOperationStage.WRITING_TEMP,
            timeline.writingTempAtEpochMillis,
            null,
            ReferenceImportFileFailureCode.STATE_MISMATCH,
            timeline,
        ) ?: return ReferencePoseImportResult.ReconciliationRequired

        val writeResult = try {
            assets.writeAndSyncClaimedTemp(identity, request.source)
        } catch (_: Exception) {
            markReconciliation(writing, timeline, ReferenceImportFileFailureCode.WRITE_FAILED)
            return ReferencePoseImportResult.ReconciliationRequired
        }
        if (writeResult is WriteAndSyncTempResult.Failure) {
            return if (writeResult.cleanupRequired) {
                cleanupAndSettle(
                    request,
                    identity,
                    writing,
                    ReferencePoseImportRejectionReason.PUBLICATION_FAILED,
                )
            } else {
                markReconciliation(writing, timeline, writeResult.code)
                ReferencePoseImportResult.ReconciliationRequired
            }
        }
        writeResult as WriteAndSyncTempResult.TempSynced
        val evidence = writeResult.evidence
        val tempSynced = advanceOrReconcile(
            writing,
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            timeline.tempSyncedAtEpochMillis,
            evidence,
            ReferenceImportFileFailureCode.FILE_SYNC_FAILED,
            timeline,
        ) ?: return ReferencePoseImportResult.ReconciliationRequired

        val renameResult = try {
            assets.renameSyncedTemp(identity, evidence)
        } catch (_: Exception) {
            null
        }
        if (renameResult !is RenameSyncedTempResult.Renamed &&
            renameResult !is RenameSyncedTempResult.AlreadyRenamed
        ) {
            val code = (renameResult as? RenameSyncedTempResult.Ambiguous)?.code
                ?: ReferenceImportFileFailureCode.RENAME_FAILED
            markReconciliation(tempSynced, timeline, code)
            return ReferencePoseImportResult.ReconciliationRequired
        }
        val renamePending = advanceOrReconcile(
            tempSynced,
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            timeline.finalRenamePendingSyncAtEpochMillis,
            evidence,
            ReferenceImportFileFailureCode.RENAME_FAILED,
            timeline,
        ) ?: return ReferencePoseImportResult.ReconciliationRequired

        val finalVerification = try {
            assets.syncAndVerifyFinal(identity, evidence)
        } catch (_: Exception) {
            null
        }
        if (finalVerification !is JournaledReferenceAssetVerificationResult.Verified) {
            val code = (finalVerification as? JournaledReferenceAssetVerificationResult.Failure)?.code
                ?: ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED
            markReconciliation(renamePending, timeline, code)
            return ReferencePoseImportResult.ReconciliationRequired
        }
        val finalDurable = advanceOrReconcile(
            renamePending,
            ReferenceImportFileOperationStage.FINAL_DURABLE,
            timeline.finalDurableAtEpochMillis,
            evidence,
            ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
            timeline,
        ) ?: return ReferencePoseImportResult.ReconciliationRequired

        val assetReady = try {
            authority.markAssetReady(request.importToken, expectedPath, timeline.assetReadyAtEpochMillis)
        } catch (_: Exception) {
            null
        }
        if (assetReady !== ReferenceImportAssetReadyResult.MarkedAssetReady &&
            assetReady !== ReferenceImportAssetReadyResult.AlreadyAssetReady
        ) {
            return quarantineAndSettle(
                request,
                identity,
                finalDurable,
                evidence,
                ReferencePoseImportRejectionReason.ASSET_READY_REJECTED,
            )
        }

        val analyzerAsset = try {
            DurableReferenceAnalyzerAsset(expectedPath, evidence.byteCount, evidence.sha256)
        } catch (_: Exception) {
            return quarantineAndSettle(
                request,
                identity,
                finalDurable,
                evidence,
                ReferencePoseImportRejectionReason.ANALYZER_FAILED,
            )
        }
        val analysis = try {
            analyzer.analyze(analyzerAsset)
        } catch (_: Exception) {
            return quarantineAndSettle(
                request,
                identity,
                finalDurable,
                evidence,
                ReferencePoseImportRejectionReason.ANALYZER_FAILED,
            )
        }
        DevelopmentReferenceImportValidationPolicy.rejectionFor(analysis)?.let { reason ->
            return quarantineAndSettle(request, identity, finalDurable, evidence, reason)
        }

        val logicalEvidence = try {
            ReferenceImportEvidence(
                importToken = request.importToken,
                shootId = request.shootId,
                poseId = request.poseId,
                poseIndex = request.poseIndex,
                label = request.label,
                relativeAssetPath = expectedPath,
                mirrorAllowed = request.mirrorAllowed,
                landmarkPayload = requireNotNull(analysis.canonicalLandmarkPayload),
                detectorMetadata = analysis.detectorMetadata,
                modelMetadata = analysis.modelMetadata,
                preprocessingMetadata = analysis.preprocessingMetadata,
                coordinateMetadata = analysis.coordinateMetadata,
            )
        } catch (_: Exception) {
            return quarantineAndSettle(
                request,
                identity,
                finalDurable,
                evidence,
                ReferencePoseImportRejectionReason.ANALYZER_FAILED,
            )
        }

        val commit = try {
            authority.commit(logicalEvidence, timeline.committedAtEpochMillis)
        } catch (_: Exception) {
            null
        }
        return when (commit) {
            ReferenceImportCommitResult.Committed,
            ReferenceImportCommitResult.AlreadyCommitted,
            -> ReferencePoseImportResult.Succeeded(request.poseId, request.poseIndex)
            ReferenceImportCommitResult.BlockedByDeletion -> quarantineAndSettle(
                request,
                identity,
                finalDurable,
                evidence,
                ReferencePoseImportRejectionReason.COMMIT_BLOCKED,
            )
            else -> quarantineAndSettle(
                request,
                identity,
                finalDurable,
                evidence,
                ReferencePoseImportRejectionReason.COMMIT_REJECTED,
            )
        }
    }

    private fun cleanupAndSettle(
        request: ReferencePoseImportRequest,
        identity: ReferenceAssetIdentity,
        source: ReferenceImportFileOperationSnapshot,
        reason: ReferencePoseImportRejectionReason,
    ): ReferencePoseImportResult {
        val timeline = request.timeline
        val evidence = source.toEvidenceOrNull()
        val required = advanceOrReconcile(
            source,
            ReferenceImportFileOperationStage.CLEANUP_REQUIRED,
            timeline.cleanupRequiredAtEpochMillis,
            evidence,
            ReferenceImportFileFailureCode.STATE_MISMATCH,
            timeline,
        ) ?: return ReferencePoseImportResult.ReconciliationRequired

        val deletion = try {
            assets.deleteExactForCleanup(identity, source.stage, evidence)
        } catch (_: Exception) {
            null
        }
        if (deletion !is DeleteExactForCleanupResult.Deleted) {
            val code = (deletion as? DeleteExactForCleanupResult.Ambiguous)?.code
                ?: ReferenceImportFileFailureCode.DELETE_FAILED
            markReconciliation(required, timeline, code)
            return ReferencePoseImportResult.ReconciliationRequired
        }
        val pending = advanceOrReconcile(
            required,
            ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC,
            timeline.cleanupPendingSyncAtEpochMillis,
            evidence,
            ReferenceImportFileFailureCode.DELETE_FAILED,
            timeline,
        ) ?: return ReferencePoseImportResult.ReconciliationRequired

        val verified = try {
            assets.syncAndVerifyCleaned(identity)
        } catch (_: Exception) {
            null
        }
        if (verified !is JournaledReferenceAssetVerificationResult.Verified) {
            val code = (verified as? JournaledReferenceAssetVerificationResult.Failure)?.code
                ?: ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED
            markReconciliation(pending, timeline, code)
            return ReferencePoseImportResult.ReconciliationRequired
        }
        val durable = advanceOrReconcile(
            pending,
            ReferenceImportFileOperationStage.CLEANED_DURABLE,
            timeline.cleanedDurableAtEpochMillis,
            null,
            ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
            timeline,
        ) ?: return ReferencePoseImportResult.ReconciliationRequired

        return settleDurableFailure(
            request,
            durable,
            ReferenceImportFailureSettlement.CLEANED,
            reason,
        )
    }

    private fun quarantineAndSettle(
        request: ReferencePoseImportRequest,
        identity: ReferenceAssetIdentity,
        source: ReferenceImportFileOperationSnapshot,
        evidence: JournaledReferenceAssetEvidence,
        reason: ReferencePoseImportRejectionReason,
    ): ReferencePoseImportResult {
        val timeline = request.timeline
        val required = advanceOrReconcile(
            source,
            ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
            timeline.quarantineRequiredAtEpochMillis,
            evidence,
            ReferenceImportFileFailureCode.STATE_MISMATCH,
            timeline,
        ) ?: return ReferencePoseImportResult.ReconciliationRequired

        val renamed = try {
            assets.renameExactToQuarantine(identity, source.stage, evidence)
        } catch (_: Exception) {
            null
        }
        if (renamed !is RenameExactToQuarantineResult.Moved &&
            renamed !is RenameExactToQuarantineResult.AlreadyMoved
        ) {
            val code = (renamed as? RenameExactToQuarantineResult.Ambiguous)?.code
                ?: ReferenceImportFileFailureCode.RENAME_FAILED
            markReconciliation(required, timeline, code)
            return ReferencePoseImportResult.ReconciliationRequired
        }
        val pending = advanceOrReconcile(
            required,
            ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC,
            timeline.quarantinePendingSyncAtEpochMillis,
            evidence,
            ReferenceImportFileFailureCode.RENAME_FAILED,
            timeline,
        ) ?: return ReferencePoseImportResult.ReconciliationRequired

        val verified = try {
            assets.syncAndVerifyQuarantined(identity, evidence)
        } catch (_: Exception) {
            null
        }
        if (verified !is JournaledReferenceAssetVerificationResult.Verified) {
            val code = (verified as? JournaledReferenceAssetVerificationResult.Failure)?.code
                ?: ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED
            markReconciliation(pending, timeline, code)
            return ReferencePoseImportResult.ReconciliationRequired
        }
        val durable = advanceOrReconcile(
            pending,
            ReferenceImportFileOperationStage.QUARANTINE_DURABLE,
            timeline.quarantineDurableAtEpochMillis,
            evidence,
            ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
            timeline,
        ) ?: return ReferencePoseImportResult.ReconciliationRequired

        return settleDurableFailure(
            request,
            durable,
            ReferenceImportFailureSettlement.QUARANTINED,
            reason,
        )
    }

    private fun settleDurableFailure(
        request: ReferencePoseImportRequest,
        durable: ReferenceImportFileOperationSnapshot,
        settlement: ReferenceImportFailureSettlement,
        reason: ReferencePoseImportRejectionReason,
    ): ReferencePoseImportResult {
        val result = try {
            authority.settleFailure(
                request.importToken,
                settlement,
                request.timeline.failureSettledAtEpochMillis,
            )
        } catch (_: Exception) {
            null
        }
        if (result !== ReferenceImportSettlementResult.Settled &&
            result !== ReferenceImportSettlementResult.AlreadySettled
        ) {
            markReconciliation(durable, request.timeline, ReferenceImportFileFailureCode.STATE_MISMATCH)
            return ReferencePoseImportResult.ReconciliationRequired
        }
        return ReferencePoseImportResult.Rejected(reason, settlement)
    }

    private fun advanceOrReconcile(
        source: ReferenceImportFileOperationSnapshot,
        target: ReferenceImportFileOperationStage,
        transitionedAtEpochMillis: Long,
        evidence: JournaledReferenceAssetEvidence?,
        failureCode: ReferenceImportFileFailureCode,
        timeline: ReferenceImportLedgerTimeline,
    ): ReferenceImportFileOperationSnapshot? {
        val request = ReferenceImportFileAdvanceRequest(
            importToken = source.importToken,
            expectedStage = source.stage,
            expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
            targetStage = target,
            byteCount = evidence?.byteCount,
            sha256 = evidence?.sha256,
            transitionedAtEpochMillis = transitionedAtEpochMillis,
        )
        val result = try {
            journal.advance(request)
        } catch (_: Exception) {
            null
        }
        val snapshot = when (result) {
            is ReferenceImportFileJournalResult.Applied -> result.snapshot
            is ReferenceImportFileJournalResult.Idempotent -> result.snapshot
            else -> null
        }
        if (snapshot == null || !snapshot.matchesAdvance(request)) {
            markReconciliation(source, timeline, failureCode)
            return null
        }
        return snapshot
    }

    private fun markReconciliation(
        source: ReferenceImportFileOperationSnapshot,
        timeline: ReferenceImportLedgerTimeline,
        failureCode: ReferenceImportFileFailureCode,
    ) {
        try {
            journal.markReconciliationRequired(
                ReferenceImportFileReconciliationRequest(
                    importToken = source.importToken,
                    expectedStage = source.stage,
                    expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
                    failureCode = failureCode,
                    markedAtEpochMillis = timeline.reconciliationMarkedAtEpochMillis,
                ),
            )
        } catch (_: Exception) {
            // The closed result remains reconciliation-required even if persisting the retry flag fails.
        }
    }
}

private fun ReferenceImportFileOperationSnapshot.isExactInitial(
    token: ReferenceImportToken,
    expectedPath: String,
    reservedAtEpochMillis: Long,
): Boolean =
    importToken == token &&
        paths.relativeAssetPath == expectedPath &&
        stage == ReferenceImportFileOperationStage.EXPECTING_RESERVATION &&
        byteCount == null &&
        sha256 == null &&
        !reconciliationRequired &&
        lastFailureCode == null &&
        createdAtEpochMillis == reservedAtEpochMillis &&
        updatedAtEpochMillis == reservedAtEpochMillis

private fun ReferenceImportFileOperationSnapshot.matchesAdvance(
    request: ReferenceImportFileAdvanceRequest,
): Boolean =
    importToken == request.importToken &&
        stage == request.targetStage &&
        byteCount == request.byteCount &&
        sha256 == request.sha256 &&
        !reconciliationRequired &&
        lastFailureCode == null &&
        updatedAtEpochMillis == request.transitionedAtEpochMillis

private fun ReferenceImportFileOperationSnapshot.toEvidenceOrNull(): JournaledReferenceAssetEvidence? =
    if (byteCount != null && sha256 != null) JournaledReferenceAssetEvidence(byteCount, sha256) else null

internal class RoomReferenceImportAuthorityAdapter(
    private val repository: RoomReferenceImportRepository,
) : ReferenceImportAuthorityPort {
    override fun reserve(
        reservation: ReferenceImportReservation,
        reservedAtEpochMillis: Long,
    ): ReferenceImportReserveResult = repository.reserveImport(reservation, reservedAtEpochMillis)

    override fun restartCleaned(
        reservation: ReferenceImportReservation,
        reservedAtEpochMillis: Long,
    ): ReferenceImportRestartCleanedResult =
        repository.restartCleanedImport(reservation, reservedAtEpochMillis)

    override fun markAssetReady(
        importToken: ReferenceImportToken,
        relativeAssetPath: String,
        assetReadyAtEpochMillis: Long,
    ): ReferenceImportAssetReadyResult =
        repository.markAssetReady(importToken, relativeAssetPath, assetReadyAtEpochMillis)

    override fun commit(
        evidence: ReferenceImportEvidence,
        committedAtEpochMillis: Long,
    ): ReferenceImportCommitResult = repository.commitImport(evidence, committedAtEpochMillis)

    override fun settleFailure(
        importToken: ReferenceImportToken,
        settlement: ReferenceImportFailureSettlement,
        settledAtEpochMillis: Long,
    ): ReferenceImportSettlementResult =
        repository.settleFailure(importToken, settlement, settledAtEpochMillis)
}

internal class RoomReferenceImportFileJournalAdapter(
    private val journal: RoomReferenceImportFileJournal,
) : ReferenceImportFileJournalPort {
    override fun snapshot(importToken: ReferenceImportToken): ReferenceImportFileOperationSnapshot? =
        journal.snapshot(importToken)

    override fun advance(request: ReferenceImportFileAdvanceRequest): ReferenceImportFileJournalResult =
        journal.advance(request)

    override fun markReconciliationRequired(
        request: ReferenceImportFileReconciliationRequest,
    ): ReferenceImportFileJournalResult = journal.markReconciliationRequired(request)
}

internal class JournaledReferenceAssetStoreAdapter(
    private val store: JournaledReferenceAssetStore,
) : JournaledReferenceImportAssetPort {
    override fun claimReservationAndTemp(identity: ReferenceAssetIdentity): ClaimReferenceAssetFilesResult =
        store.claimReservationAndTemp(identity)

    override fun writeAndSyncClaimedTemp(
        identity: ReferenceAssetIdentity,
        source: ReferenceAssetByteSource,
    ): WriteAndSyncTempResult = store.writeAndSyncClaimedTemp(identity, source)

    override fun renameSyncedTemp(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): RenameSyncedTempResult = store.renameSyncedTemp(identity, evidence)

    override fun syncAndVerifyFinal(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): JournaledReferenceAssetVerificationResult = store.syncAndVerifyFinal(identity, evidence)

    override fun deleteExactForCleanup(
        identity: ReferenceAssetIdentity,
        sourceStage: ReferenceImportFileOperationStage,
        evidence: JournaledReferenceAssetEvidence?,
    ): DeleteExactForCleanupResult = store.deleteExactForCleanup(identity, sourceStage, evidence)

    override fun syncAndVerifyCleaned(identity: ReferenceAssetIdentity): JournaledReferenceAssetVerificationResult =
        store.syncAndVerifyCleaned(identity)

    override fun renameExactToQuarantine(
        identity: ReferenceAssetIdentity,
        sourceStage: ReferenceImportFileOperationStage,
        evidence: JournaledReferenceAssetEvidence,
    ): RenameExactToQuarantineResult = store.renameExactToQuarantine(identity, sourceStage, evidence)

    override fun syncAndVerifyQuarantined(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): JournaledReferenceAssetVerificationResult = store.syncAndVerifyQuarantined(identity, evidence)
}
