package com.tonyisup.poseguidesnap.data

import java.io.File
import java.nio.file.Path
import java.security.MessageDigest

/** Positive, fsynced content evidence suitable for persistence in the file-operation ledger. */
class JournaledReferenceAssetEvidence(
    val byteCount: Long,
    val sha256: String,
) {
    init {
        require(byteCount > 0L) { "reference asset evidence byte count must be positive" }
        require(SHA256_HEX.matches(sha256)) { "reference asset evidence digest must be canonical" }
    }

    override fun equals(other: Any?): Boolean =
        other is JournaledReferenceAssetEvidence &&
            byteCount == other.byteCount &&
            sha256 == other.sha256

    override fun hashCode(): Int = 31 * byteCount.hashCode() + sha256.hashCode()

    override fun toString(): String = "JournaledReferenceAssetEvidence(redacted)"

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

sealed interface ClaimReferenceAssetFilesResult {
    data object Claimed : ClaimReferenceAssetFilesResult

    class Ambiguous(
        val code: ReferenceImportFileFailureCode,
        val cleanupRequired: Boolean,
    ) : ClaimReferenceAssetFilesResult {
        override fun toString(): String =
            "ClaimReferenceAssetFilesResult.Ambiguous(code=${code.name}, " +
                "cleanupRequired=$cleanupRequired)"
    }
}

sealed interface WriteAndSyncTempResult {
    class TempSynced(val evidence: JournaledReferenceAssetEvidence) : WriteAndSyncTempResult {
        override fun toString(): String = "WriteAndSyncTempResult.TempSynced(redacted)"
    }

    class Failure(
        val code: ReferenceImportFileFailureCode,
        val cleanupRequired: Boolean,
    ) : WriteAndSyncTempResult {
        override fun toString(): String =
            "WriteAndSyncTempResult.Failure(code=${code.name}, cleanupRequired=$cleanupRequired)"
    }
}

sealed interface RenameSyncedTempResult {
    data object Renamed : RenameSyncedTempResult
    data object AlreadyRenamed : RenameSyncedTempResult

    class Ambiguous(val code: ReferenceImportFileFailureCode) : RenameSyncedTempResult {
        override fun toString(): String = "RenameSyncedTempResult.Ambiguous(code=${code.name})"
    }
}

sealed interface DeleteExactForCleanupResult {
    data object Deleted : DeleteExactForCleanupResult

    class Ambiguous(val code: ReferenceImportFileFailureCode) : DeleteExactForCleanupResult {
        override fun toString(): String = "DeleteExactForCleanupResult.Ambiguous(code=${code.name})"
    }
}

sealed interface RenameExactToQuarantineResult {
    data object Moved : RenameExactToQuarantineResult
    data object AlreadyMoved : RenameExactToQuarantineResult

    class Ambiguous(val code: ReferenceImportFileFailureCode) : RenameExactToQuarantineResult {
        override fun toString(): String =
            "RenameExactToQuarantineResult.Ambiguous(code=${code.name})"
    }
}

sealed interface JournaledReferenceAssetVerificationResult {
    data object Verified : JournaledReferenceAssetVerificationResult

    class Failure(val code: ReferenceImportFileFailureCode) :
        JournaledReferenceAssetVerificationResult {
        override fun toString(): String =
            "JournaledReferenceAssetVerificationResult.Failure(code=${code.name})"
    }
}

/**
 * Filesystem-only staged effects for the persisted reference-import file ledger.
 * Room transition ordering is deliberately owned by the later coordinator.
 */
class JournaledReferenceAssetStore internal constructor(
    private val noBackupRoot: Path,
    private val fileOps: ReferenceAssetFileOps,
    private val maxEncodedBytes: Long,
) {
    private val directories: ReferenceAssetDirectories?

    init {
        require(maxEncodedBytes > 0L) { "reference asset size limit must be positive" }
        directories = try {
            fileOps.prepareDirectories(noBackupRoot)
        } catch (_: Exception) {
            null
        }
    }

    constructor(noBackupFilesDirectory: File) : this(
        noBackupFilesDirectory.toPath(),
        AndroidReferenceAssetFileOps,
        MAX_ENCODED_REFERENCE_ASSET_BYTES,
    )

    /**
     * Claims only the two empty deterministic files while the ledger is still
     * EXPECTING_RESERVATION. The coordinator may persist WRITING_TEMP only after Claimed.
     */
    fun claimReservationAndTemp(
        identity: ReferenceAssetIdentity,
    ): ClaimReferenceAssetFilesResult = withExclusiveReferenceAssetMutation {
        val exact = exactPaths(identity)
            ?: return@withExclusiveReferenceAssetMutation claimAmbiguous(
                ReferenceImportFileFailureCode.RESERVATION_FAILED,
                cleanupRequired = false,
            )
        try {
            fileOps.reserveEmpty(exact.asset)
        } catch (_: ReferenceAssetReservationConflict) {
            return@withExclusiveReferenceAssetMutation claimAmbiguous(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
                cleanupRequired = false,
            )
        } catch (_: Exception) {
            return@withExclusiveReferenceAssetMutation claimAmbiguous(
                ReferenceImportFileFailureCode.RESERVATION_FAILED,
                cleanupRequired = true,
            )
        }
        try {
            fileOps.createTemp(exact.temp)
        } catch (_: ReferenceAssetTempConflict) {
            return@withExclusiveReferenceAssetMutation claimAmbiguous(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
                cleanupRequired = true,
            )
        } catch (_: Exception) {
            return@withExclusiveReferenceAssetMutation claimAmbiguous(
                ReferenceImportFileFailureCode.WRITE_FAILED,
                cleanupRequired = true,
            )
        }
        ClaimReferenceAssetFilesResult.Claimed
    }

    /** Writes only previously claimed zero-byte files after WRITING_TEMP is durable in Room. */
    fun writeAndSyncClaimedTemp(
        identity: ReferenceAssetIdentity,
        source: ReferenceAssetByteSource,
    ): WriteAndSyncTempResult = withExclusiveReferenceAssetMutation {
        val exact = exactPaths(identity) ?: return@withExclusiveReferenceAssetMutation writeFailure(
            ReferenceImportFileFailureCode.STATE_MISMATCH,
            cleanupRequired = false,
        )
        val observations = observeAssetAndTemp(exact)
            ?: return@withExclusiveReferenceAssetMutation writeFailure(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
                cleanupRequired = true,
            )
        val reservation = observations.first
        val temp = observations.second
        if (
            reservation !is ReferenceAssetFileObservation.Regular ||
            reservation.byteCount != 0L ||
            temp !is ReferenceAssetFileObservation.Regular ||
            temp.byteCount != 0L
        ) {
            return@withExclusiveReferenceAssetMutation writeFailure(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
                cleanupRequired = false,
            )
        }

        var byteCount = 0L
        var failureCode = ReferenceImportFileFailureCode.WRITE_FAILED
        val streamDigest = MessageDigest.getInstance("SHA-256")
        try {
            source.openStream().use { input ->
                fileOps.openOwnedForWrite(exact.temp, temp.identity).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val remaining = maxEncodedBytes - byteCount
                        val requested = if (remaining < buffer.size.toLong()) {
                            remaining.toInt() + 1
                        } else {
                            buffer.size
                        }
                        val read = input.read(buffer, 0, requested)
                        if (read < 0) break
                        if (read == 0) continue
                        if (read.toLong() > remaining) {
                            if (remaining > 0L) {
                                val allowed = remaining.toInt()
                                output.write(buffer, 0, allowed)
                                streamDigest.update(buffer, 0, allowed)
                                byteCount += allowed.toLong()
                            }
                            return@withExclusiveReferenceAssetMutation writeFailure(
                                ReferenceImportFileFailureCode.WRITE_FAILED,
                                cleanupRequired = true,
                            )
                        }
                        output.write(buffer, 0, read)
                        streamDigest.update(buffer, 0, read)
                        byteCount += read.toLong()
                    }
                }
            }
            if (byteCount == 0L) {
                return@withExclusiveReferenceAssetMutation writeFailure(
                    ReferenceImportFileFailureCode.WRITE_FAILED,
                    cleanupRequired = true,
                )
            }

            failureCode = ReferenceImportFileFailureCode.FILE_SYNC_FAILED
            fileOps.syncOwnedFile(exact.temp, temp.identity)
            failureCode = ReferenceImportFileFailureCode.EVIDENCE_MISMATCH
            val observed = fileOps.observeRegularFile(exact.temp)
            if (
                observed !is ReferenceAssetFileObservation.Regular ||
                observed.identity != temp.identity ||
                observed.byteCount != byteCount
            ) {
                return@withExclusiveReferenceAssetMutation writeFailure(failureCode, true)
            }
            val persistedHash = fileOps.sha256OwnedFile(exact.temp, temp.identity, byteCount)
            val streamedHash = streamDigest.digest().toCanonicalHex()
            if (persistedHash != streamedHash || !SHA256_HEX.matches(persistedHash)) {
                return@withExclusiveReferenceAssetMutation writeFailure(failureCode, true)
            }
            WriteAndSyncTempResult.TempSynced(
                JournaledReferenceAssetEvidence(byteCount, persistedHash),
            )
        } catch (_: Exception) {
            writeFailure(failureCode, cleanupRequired = true)
        }
    }

    /** Compatibility helper; the persisted coordinator must call the split methods above. */
    fun writeAndSyncTemp(
        identity: ReferenceAssetIdentity,
        source: ReferenceAssetByteSource,
    ): WriteAndSyncTempResult = withExclusiveReferenceAssetMutation {
        val exact = exactPaths(identity) ?: return@withExclusiveReferenceAssetMutation writeFailure(
            ReferenceImportFileFailureCode.RESERVATION_FAILED,
            cleanupRequired = false,
        )
        var cleanupRequired = false
        var failureCode = ReferenceImportFileFailureCode.RESERVATION_FAILED
        var byteCount = 0L
        val streamDigest = MessageDigest.getInstance("SHA-256")

        try {
            // Any failure other than an explicit pre-create collision may have admitted the
            // deterministic reservation, so only that closed collision can clear this flag.
            cleanupRequired = true
            fileOps.reserveEmpty(exact.asset)
            failureCode = ReferenceImportFileFailureCode.WRITE_FAILED
            val tempIdentity = fileOps.createTemp(exact.temp)

            source.openStream().use { input ->
                fileOps.openOwnedForWrite(exact.temp, tempIdentity).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val remaining = maxEncodedBytes - byteCount
                        val requested = if (remaining < buffer.size.toLong()) {
                            remaining.toInt() + 1
                        } else {
                            buffer.size
                        }
                        val read = input.read(buffer, 0, requested)
                        if (read < 0) break
                        if (read == 0) continue
                        if (read.toLong() > remaining) {
                            if (remaining > 0L) {
                                val allowed = remaining.toInt()
                                output.write(buffer, 0, allowed)
                                streamDigest.update(buffer, 0, allowed)
                                byteCount += allowed.toLong()
                            }
                            return@withExclusiveReferenceAssetMutation writeFailure(
                                ReferenceImportFileFailureCode.WRITE_FAILED,
                                cleanupRequired = true,
                            )
                        }
                        output.write(buffer, 0, read)
                        streamDigest.update(buffer, 0, read)
                        byteCount += read.toLong()
                    }
                }
            }
            if (byteCount == 0L) {
                return@withExclusiveReferenceAssetMutation writeFailure(
                    ReferenceImportFileFailureCode.WRITE_FAILED,
                    cleanupRequired = true,
                )
            }

            failureCode = ReferenceImportFileFailureCode.FILE_SYNC_FAILED
            fileOps.syncOwnedFile(exact.temp, tempIdentity)

            failureCode = ReferenceImportFileFailureCode.EVIDENCE_MISMATCH
            val observed = fileOps.observeRegularFile(exact.temp)
            if (observed !is ReferenceAssetFileObservation.Regular ||
                observed.identity != tempIdentity ||
                observed.byteCount != byteCount
            ) {
                return@withExclusiveReferenceAssetMutation writeFailure(failureCode, true)
            }
            val persistedHash = fileOps.sha256OwnedFile(exact.temp, tempIdentity, byteCount)
            val streamedHash = streamDigest.digest().toCanonicalHex()
            if (persistedHash != streamedHash || !SHA256_HEX.matches(persistedHash)) {
                return@withExclusiveReferenceAssetMutation writeFailure(failureCode, true)
            }
            WriteAndSyncTempResult.TempSynced(
                JournaledReferenceAssetEvidence(byteCount, persistedHash),
            )
        } catch (_: ReferenceAssetReservationConflict) {
            writeFailure(ReferenceImportFileFailureCode.STATE_MISMATCH, cleanupRequired = false)
        } catch (_: ReferenceAssetReservationMayExist) {
            writeFailure(ReferenceImportFileFailureCode.RESERVATION_FAILED, cleanupRequired = true)
        } catch (_: ReferenceAssetTempConflict) {
            writeFailure(ReferenceImportFileFailureCode.STATE_MISMATCH, cleanupRequired = true)
        } catch (_: ReferenceAssetTempMayExist) {
            writeFailure(ReferenceImportFileFailureCode.WRITE_FAILED, cleanupRequired = true)
        } catch (_: Exception) {
            writeFailure(failureCode, cleanupRequired)
        }
    }

    fun renameSyncedTemp(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): RenameSyncedTempResult = withExclusiveReferenceAssetMutation {
        val exact = exactPaths(identity) ?: return@withExclusiveReferenceAssetMutation renameAmbiguous(
            ReferenceImportFileFailureCode.STATE_MISMATCH,
        )
        val observations = observeAssetAndTemp(exact)
            ?: return@withExclusiveReferenceAssetMutation renameAmbiguous(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        val final = observations.first
        val temp = observations.second

        if (final is ReferenceAssetFileObservation.Regular &&
            final.byteCount == evidence.byteCount &&
            temp === ReferenceAssetFileObservation.Absent
        ) {
            return@withExclusiveReferenceAssetMutation if (matchesEvidence(exact.asset, final, evidence)) {
                RenameSyncedTempResult.AlreadyRenamed
            } else {
                renameAmbiguous(ReferenceImportFileFailureCode.EVIDENCE_MISMATCH)
            }
        }
        if (final !is ReferenceAssetFileObservation.Regular ||
            final.byteCount != 0L ||
            temp !is ReferenceAssetFileObservation.Regular
        ) {
            return@withExclusiveReferenceAssetMutation renameAmbiguous(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        }
        if (!matchesEvidence(exact.temp, temp, evidence)) {
            return@withExclusiveReferenceAssetMutation renameAmbiguous(
                ReferenceImportFileFailureCode.EVIDENCE_MISMATCH,
            )
        }

        try {
            fileOps.replaceOwnedReservation(
                sourcePath = exact.temp,
                sourceIdentity = temp.identity,
                expectedByteCount = evidence.byteCount,
                destinationPath = exact.asset,
                reservationIdentity = final.identity,
            )
            RenameSyncedTempResult.Renamed
        } catch (_: Exception) {
            renameAmbiguous(ReferenceImportFileFailureCode.RENAME_FAILED)
        }
    }

    fun syncAndVerifyFinal(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): JournaledReferenceAssetVerificationResult = withExclusiveReferenceAssetMutation {
        val exact = exactPaths(identity) ?: return@withExclusiveReferenceAssetMutation verificationFailure(
            ReferenceImportFileFailureCode.STATE_MISMATCH,
        )
        try {
            fileOps.syncDirectory(exact.directories.assets)
        } catch (_: Exception) {
            return@withExclusiveReferenceAssetMutation verificationFailure(
                ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
            )
        }
        val observations = observeAssetAndTemp(exact)
            ?: return@withExclusiveReferenceAssetMutation verificationFailure(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        val final = observations.first
        if (observations.second !== ReferenceAssetFileObservation.Absent ||
            final !is ReferenceAssetFileObservation.Regular ||
            final.byteCount != evidence.byteCount
        ) {
            return@withExclusiveReferenceAssetMutation verificationFailure(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        }
        if (!matchesEvidence(exact.asset, final, evidence)) {
            verificationFailure(ReferenceImportFileFailureCode.EVIDENCE_MISMATCH)
        } else {
            JournaledReferenceAssetVerificationResult.Verified
        }
    }

    fun deleteExactForCleanup(
        identity: ReferenceAssetIdentity,
        sourceStage: ReferenceImportFileOperationStage,
        evidence: JournaledReferenceAssetEvidence? = null,
    ): DeleteExactForCleanupResult = withExclusiveReferenceAssetMutation {
        val exact = exactPaths(identity) ?: return@withExclusiveReferenceAssetMutation cleanupAmbiguous(
            ReferenceImportFileFailureCode.STATE_MISMATCH,
        )
        val observed = observeAll(exact)
            ?: return@withExclusiveReferenceAssetMutation cleanupAmbiguous(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        val asset = observed[0]
        val temp = observed[1]
        val quarantine = observed[2]
        if (quarantine is ReferenceAssetFileObservation.Regular &&
            sourceStage != ReferenceImportFileOperationStage.QUARANTINE_REQUIRED &&
            sourceStage != ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC
        ) {
            return@withExclusiveReferenceAssetMutation cleanupAmbiguous(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        }
        if (!cleanupShapeIsAuthorized(sourceStage, evidence, exact, asset, temp, quarantine)) {
            return@withExclusiveReferenceAssetMutation cleanupAmbiguous(
                if (containsNonempty(observed) && evidence != null) {
                    ReferenceImportFileFailureCode.EVIDENCE_MISMATCH
                } else {
                    ReferenceImportFileFailureCode.STATE_MISMATCH
                },
            )
        }

        var failed = false
        listOf(
            exact.asset to asset,
            exact.temp to temp,
            exact.quarantine to quarantine,
        ).forEach { (path, observation) ->
            if (observation is ReferenceAssetFileObservation.Regular) {
                try {
                    fileOps.deleteOwnedFile(path, observation.identity, observation.byteCount)
                } catch (_: Exception) {
                    failed = true
                }
            }
        }
        if (failed) {
            cleanupAmbiguous(ReferenceImportFileFailureCode.DELETE_FAILED)
        } else {
            DeleteExactForCleanupResult.Deleted
        }
    }

    fun syncAndVerifyCleaned(
        identity: ReferenceAssetIdentity,
    ): JournaledReferenceAssetVerificationResult = withExclusiveReferenceAssetMutation {
        val exact = exactPaths(identity) ?: return@withExclusiveReferenceAssetMutation verificationFailure(
            ReferenceImportFileFailureCode.STATE_MISMATCH,
        )
        if (!syncBothDirectories(exact)) {
            return@withExclusiveReferenceAssetMutation verificationFailure(
                ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
            )
        }
        val observed = observeAll(exact)
            ?: return@withExclusiveReferenceAssetMutation verificationFailure(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        if (observed.all { it === ReferenceAssetFileObservation.Absent }) {
            JournaledReferenceAssetVerificationResult.Verified
        } else {
            verificationFailure(ReferenceImportFileFailureCode.STATE_MISMATCH)
        }
    }

    fun renameExactToQuarantine(
        identity: ReferenceAssetIdentity,
        sourceStage: ReferenceImportFileOperationStage,
        evidence: JournaledReferenceAssetEvidence,
    ): RenameExactToQuarantineResult = withExclusiveReferenceAssetMutation {
        val exact = exactPaths(identity) ?: return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
            ReferenceImportFileFailureCode.STATE_MISMATCH,
        )
        val sourcePath = when (sourceStage) {
            ReferenceImportFileOperationStage.TEMP_SYNCED -> exact.temp
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            ReferenceImportFileOperationStage.FINAL_DURABLE,
            -> exact.asset
            else -> return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        }
        val observed = observeAll(exact)
            ?: return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        val asset = observed[0]
        val temp = observed[1]
        val quarantine = observed[2]
        val source = if (sourcePath == exact.temp) temp else asset

        if (quarantine is ReferenceAssetFileObservation.Regular &&
            quarantine.byteCount == evidence.byteCount &&
            source === ReferenceAssetFileObservation.Absent
        ) {
            if (!matchesEvidence(exact.quarantine, quarantine, evidence)) {
                return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                    ReferenceImportFileFailureCode.EVIDENCE_MISMATCH,
                )
            }
            if (sourcePath == exact.temp) {
                if (asset is ReferenceAssetFileObservation.Regular && asset.byteCount == 0L) {
                    try {
                        fileOps.deleteOwnedFile(exact.asset, asset.identity, 0L)
                    } catch (_: Exception) {
                        return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                            ReferenceImportFileFailureCode.DELETE_FAILED,
                        )
                    }
                } else if (asset !== ReferenceAssetFileObservation.Absent) {
                    return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                        ReferenceImportFileFailureCode.STATE_MISMATCH,
                    )
                }
            } else if (temp !== ReferenceAssetFileObservation.Absent) {
                return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                    ReferenceImportFileFailureCode.STATE_MISMATCH,
                )
            }
            return@withExclusiveReferenceAssetMutation RenameExactToQuarantineResult.AlreadyMoved
        }

        if (source !is ReferenceAssetFileObservation.Regular ||
            source.byteCount != evidence.byteCount ||
            (sourcePath == exact.temp &&
                (asset !is ReferenceAssetFileObservation.Regular || asset.byteCount != 0L)) ||
            (sourcePath == exact.asset && temp !== ReferenceAssetFileObservation.Absent)
        ) {
            return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        }
        val quarantineReservation = when (quarantine) {
            ReferenceAssetFileObservation.Absent -> null
            is ReferenceAssetFileObservation.Regular -> {
                if (quarantine.byteCount != 0L) {
                    return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                        ReferenceImportFileFailureCode.STATE_MISMATCH,
                    )
                }
                quarantine.identity
            }
        }
        if (!matchesEvidence(sourcePath, source, evidence)) {
            return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                ReferenceImportFileFailureCode.EVIDENCE_MISMATCH,
            )
        }
        try {
            fileOps.syncOwnedFile(sourcePath, source.identity)
        } catch (_: Exception) {
            return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                ReferenceImportFileFailureCode.FILE_SYNC_FAILED,
            )
        }
        if (!matchesEvidence(sourcePath, source, evidence)) {
            return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                ReferenceImportFileFailureCode.EVIDENCE_MISMATCH,
            )
        }

        val reservationIdentity = if (quarantineReservation != null) {
            quarantineReservation
        } else {
            try {
                fileOps.reserveEmpty(exact.quarantine)
            } catch (_: Exception) {
                return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                    ReferenceImportFileFailureCode.STATE_MISMATCH,
                )
            }
        }
        try {
            fileOps.replaceOwnedReservation(
                sourcePath = sourcePath,
                sourceIdentity = source.identity,
                expectedByteCount = evidence.byteCount,
                destinationPath = exact.quarantine,
                reservationIdentity = reservationIdentity,
            )
        } catch (_: Exception) {
            return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                ReferenceImportFileFailureCode.RENAME_FAILED,
            )
        }
        if (sourcePath == exact.temp) {
            asset as ReferenceAssetFileObservation.Regular
            try {
                fileOps.deleteOwnedFile(exact.asset, asset.identity, 0L)
            } catch (_: Exception) {
                return@withExclusiveReferenceAssetMutation quarantineAmbiguous(
                    ReferenceImportFileFailureCode.DELETE_FAILED,
                )
            }
        }
        RenameExactToQuarantineResult.Moved
    }

    fun syncAndVerifyQuarantined(
        identity: ReferenceAssetIdentity,
        evidence: JournaledReferenceAssetEvidence,
    ): JournaledReferenceAssetVerificationResult = withExclusiveReferenceAssetMutation {
        val exact = exactPaths(identity) ?: return@withExclusiveReferenceAssetMutation verificationFailure(
            ReferenceImportFileFailureCode.STATE_MISMATCH,
        )
        if (!syncBothDirectories(exact)) {
            return@withExclusiveReferenceAssetMutation verificationFailure(
                ReferenceImportFileFailureCode.DIRECTORY_SYNC_FAILED,
            )
        }
        val observed = observeAll(exact)
            ?: return@withExclusiveReferenceAssetMutation verificationFailure(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        val quarantine = observed[2]
        if (observed[0] !== ReferenceAssetFileObservation.Absent ||
            observed[1] !== ReferenceAssetFileObservation.Absent ||
            quarantine !is ReferenceAssetFileObservation.Regular
        ) {
            return@withExclusiveReferenceAssetMutation verificationFailure(
                ReferenceImportFileFailureCode.STATE_MISMATCH,
            )
        }
        if (quarantine.byteCount != evidence.byteCount) {
            return@withExclusiveReferenceAssetMutation verificationFailure(
                ReferenceImportFileFailureCode.EVIDENCE_MISMATCH,
            )
        }
        if (matchesEvidence(exact.quarantine, quarantine, evidence)) {
            JournaledReferenceAssetVerificationResult.Verified
        } else {
            verificationFailure(ReferenceImportFileFailureCode.EVIDENCE_MISMATCH)
        }
    }

    private fun cleanupShapeIsAuthorized(
        sourceStage: ReferenceImportFileOperationStage,
        evidence: JournaledReferenceAssetEvidence?,
        exact: ExactPaths,
        asset: ReferenceAssetFileObservation,
        temp: ReferenceAssetFileObservation,
        quarantine: ReferenceAssetFileObservation,
    ): Boolean {
        fun absent(observation: ReferenceAssetFileObservation) =
            observation === ReferenceAssetFileObservation.Absent
        fun zero(observation: ReferenceAssetFileObservation) =
            observation is ReferenceAssetFileObservation.Regular && observation.byteCount == 0L
        fun absentOrZero(observation: ReferenceAssetFileObservation) = absent(observation) || zero(observation)
        fun matches(path: Path, observation: ReferenceAssetFileObservation): Boolean =
            evidence != null && observation is ReferenceAssetFileObservation.Regular &&
                observation.byteCount == evidence.byteCount && matchesEvidence(path, observation, evidence)

        return when (sourceStage) {
            ReferenceImportFileOperationStage.EXPECTING_RESERVATION ->
                evidence == null && absentOrZero(asset) && absentOrZero(temp) && absent(quarantine)
            ReferenceImportFileOperationStage.WRITING_TEMP ->
                evidence == null && absentOrZero(asset) &&
                    (absent(temp) || temp is ReferenceAssetFileObservation.Regular) &&
                    absent(quarantine)
            ReferenceImportFileOperationStage.TEMP_SYNCED,
            ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            -> evidence != null && absent(quarantine) && (
                (matches(exact.temp, temp) && absentOrZero(asset)) ||
                    (matches(exact.asset, asset) && absent(temp))
                )
            ReferenceImportFileOperationStage.FINAL_DURABLE ->
                evidence != null && matches(exact.asset, asset) && absent(temp) && absent(quarantine)
            ReferenceImportFileOperationStage.QUARANTINE_REQUIRED -> evidence != null && (
                (matches(exact.temp, temp) && absentOrZero(asset) && absentOrZero(quarantine)) ||
                    (matches(exact.asset, asset) && absent(temp) && absentOrZero(quarantine)) ||
                    (matches(exact.quarantine, quarantine) && absent(temp) && absentOrZero(asset))
                )
            ReferenceImportFileOperationStage.QUARANTINE_PENDING_SYNC ->
                evidence != null && matches(exact.quarantine, quarantine) &&
                    absent(asset) && absent(temp)
            else -> false
        }
    }

    private fun observeAssetAndTemp(
        exact: ExactPaths,
    ): Pair<ReferenceAssetFileObservation, ReferenceAssetFileObservation>? = try {
        fileOps.observeRegularFile(exact.asset) to fileOps.observeRegularFile(exact.temp)
    } catch (_: Exception) {
        null
    }

    private fun observeAll(exact: ExactPaths): List<ReferenceAssetFileObservation>? = try {
        listOf(
            fileOps.observeRegularFile(exact.asset),
            fileOps.observeRegularFile(exact.temp),
            fileOps.observeRegularFile(exact.quarantine),
        )
    } catch (_: Exception) {
        null
    }

    private fun matchesEvidence(
        path: Path,
        observation: ReferenceAssetFileObservation.Regular,
        evidence: JournaledReferenceAssetEvidence,
    ): Boolean {
        if (observation.byteCount != evidence.byteCount) return false
        return try {
            fileOps.sha256OwnedFile(path, observation.identity, evidence.byteCount) == evidence.sha256
        } catch (_: Exception) {
            false
        }
    }

    private fun syncBothDirectories(exact: ExactPaths): Boolean = try {
        fileOps.syncDirectory(exact.directories.assets)
        fileOps.syncDirectory(exact.directories.quarantine)
        true
    } catch (_: Exception) {
        false
    }

    private fun exactPaths(identity: ReferenceAssetIdentity): ExactPaths? {
        val prepared = directories ?: return null
        val paths = ReferenceImportFileOperationPaths.forToken(identity.importToken)
        val assetName = paths.relativeAssetPath.removeExactPrefix(ASSET_PREFIX) ?: return null
        val tempName = paths.relativeTempPath.removeExactPrefix(ASSET_PREFIX) ?: return null
        val quarantineName = paths.relativeQuarantinePath.removeExactPrefix(QUARANTINE_PREFIX) ?: return null
        return try {
            ExactPaths(
                directories = prepared,
                asset = confinedChild(prepared.assets, assetName),
                temp = confinedChild(prepared.assets, tempName),
                quarantine = confinedChild(prepared.quarantine, quarantineName),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun confinedChild(parent: Path, fileName: String): Path {
        require(fileName.isNotEmpty() && '/' !in fileName && '\\' !in fileName)
        val child = parent.resolve(fileName).normalize()
        require(child.parent == parent)
        return child
    }

    private fun String.removeExactPrefix(prefix: String): String? =
        if (startsWith(prefix)) substring(prefix.length) else null

    private fun containsNonempty(observed: List<ReferenceAssetFileObservation>): Boolean =
        observed.any { it is ReferenceAssetFileObservation.Regular && it.byteCount > 0L }

    private fun claimAmbiguous(
        code: ReferenceImportFileFailureCode,
        cleanupRequired: Boolean,
    ) = ClaimReferenceAssetFilesResult.Ambiguous(code, cleanupRequired)

    private fun writeFailure(code: ReferenceImportFileFailureCode, cleanupRequired: Boolean) =
        WriteAndSyncTempResult.Failure(code, cleanupRequired)

    private fun renameAmbiguous(code: ReferenceImportFileFailureCode) =
        RenameSyncedTempResult.Ambiguous(code)

    private fun cleanupAmbiguous(code: ReferenceImportFileFailureCode) =
        DeleteExactForCleanupResult.Ambiguous(code)

    private fun quarantineAmbiguous(code: ReferenceImportFileFailureCode) =
        RenameExactToQuarantineResult.Ambiguous(code)

    private fun verificationFailure(code: ReferenceImportFileFailureCode) =
        JournaledReferenceAssetVerificationResult.Failure(code)

    private data class ExactPaths(
        val directories: ReferenceAssetDirectories,
        val asset: Path,
        val temp: Path,
        val quarantine: Path,
    )

    private companion object {
        const val STREAM_BUFFER_BYTES = 8 * 1024
        const val ASSET_PREFIX = "reference-assets/assets/"
        const val QUARANTINE_PREFIX = "reference-assets/quarantine/"
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

private fun ByteArray.toCanonicalHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
