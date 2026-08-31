package com.tonyisup.poseguidesnap.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

const val MAX_ENCODED_REFERENCE_ASSET_BYTES: Long = 32L * 1024L * 1024L

class ReferenceAssetIdentity(val importToken: ReferenceImportToken) {
    init {
        require(importToken.value.isNotBlank()) { "reference asset identity token must be nonblank" }
    }

    override fun toString(): String = "ReferenceAssetIdentity(redacted)"
}

fun interface ReferenceAssetByteSource {
    fun openStream(): InputStream
}

class PublishedReferenceAsset internal constructor(
    val safeRelativePath: String,
    val byteCount: Long,
    val sha256: String,
    val ownershipIdentity: ReferenceAssetOwnershipIdentity,
) {
    override fun toString(): String = "PublishedReferenceAsset(redacted)"
}

class ReferenceAssetOwnershipIdentity internal constructor(
    internal val storeIdentity: Any,
    internal val finalFileName: String,
    internal val fileIdentity: ReferenceAssetFileIdentity,
    internal val byteCount: Long,
    internal val contentSha256: String,
) {
    internal var closedResult: ReferenceAssetCleanupResult? = null

    override fun toString(): String = "ReferenceAssetOwnershipIdentity(redacted)"
}

sealed class ReferenceAssetCleanupResult {
    data object Cleaned : ReferenceAssetCleanupResult() {
        override fun toString(): String = "CLEANED"
    }

    class Quarantined(val safeRelativePath: String) : ReferenceAssetCleanupResult() {
        override fun toString(): String = "QUARANTINED"
    }

    data object ReconciliationRequired : ReferenceAssetCleanupResult() {
        override fun toString(): String = "RECONCILIATION_REQUIRED"
    }
}

enum class ReferenceAssetPendingLifecycle {
    PREPARING,
    ASSET_READY,
}

enum class ReferenceAssetFailureStage {
    PREPARE_DIRECTORIES,
    RESERVE_FINAL,
    CLAIM_TEMP,
    OPEN_SOURCE,
    COPY_SOURCE,
    EMPTY_INPUT,
    ENCODED_SIZE_LIMIT,
    SYNC_TEMP,
    VALIDATE_TEMP,
    RENAME,
}

open class ReferenceAssetStoreException internal constructor(message: String) :
    IllegalStateException(message)

class ReferenceAssetPublicationFailed internal constructor(
    val stage: ReferenceAssetFailureStage,
) : ReferenceAssetStoreException("reference asset publication failed at ${stage.name.lowercase()}")

class ReferenceAssetReconciliationRequired internal constructor() :
    ReferenceAssetStoreException("reference asset reconciliation required")

class ReferenceAssetStore internal constructor(
    private val noBackupRoot: Path,
    private val fileOps: ReferenceAssetFileOps,
    private val maxEncodedBytes: Long,
) {
    private val storeIdentity = Any()
    private val directories: ReferenceAssetDirectories

    init {
        require(maxEncodedBytes > 0L) { "reference asset size limit must be positive" }
        directories = try {
            fileOps.prepareDirectories(noBackupRoot)
        } catch (_: Exception) {
            throw ReferenceAssetPublicationFailed(ReferenceAssetFailureStage.PREPARE_DIRECTORIES)
        }
    }

    constructor(noBackupFilesDirectory: File) : this(
        noBackupFilesDirectory.toPath(),
        AndroidReferenceAssetFileOps,
        MAX_ENCODED_REFERENCE_ASSET_BYTES,
    )

    fun publish(
        identity: ReferenceAssetIdentity,
        source: ReferenceAssetByteSource,
    ): PublishedReferenceAsset = withExclusiveReferenceAssetMutation {
        val safeRelativePath = ReferenceImportAssetPath.forToken(identity.importToken)
        val finalFileName = safeRelativePath.substringAfterLast('/')
        val finalPath = confinedReferenceChild(directories.assets, finalFileName)
        val tempPath = confinedReferenceChild(directories.assets, ".$finalFileName.pending")
        var reservationIdentity: ReferenceAssetFileIdentity? = null
        var tempIdentity: ReferenceAssetFileIdentity? = null
        var byteCount = 0L
        var reservationReplaced = false
        var failureStage = ReferenceAssetFailureStage.RESERVE_FINAL
        val contentDigest = MessageDigest.getInstance("SHA-256")

        try {
            val claimedReservationIdentity = fileOps.reserveEmpty(finalPath)
            reservationIdentity = claimedReservationIdentity

            failureStage = ReferenceAssetFailureStage.CLAIM_TEMP
            val claimedTempIdentity = fileOps.createTemp(tempPath)
            tempIdentity = claimedTempIdentity

            failureStage = ReferenceAssetFailureStage.OPEN_SOURCE
            val input = source.openStream()
            failureStage = ReferenceAssetFailureStage.COPY_SOURCE
            input.use {
                fileOps.openOwnedForWrite(tempPath, claimedTempIdentity).use { output ->
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
                                contentDigest.update(buffer, 0, allowed)
                                byteCount += allowed.toLong()
                            }
                            throw ReferenceAssetPublicationFailed(
                                ReferenceAssetFailureStage.ENCODED_SIZE_LIMIT,
                            )
                        }
                        output.write(buffer, 0, read)
                        contentDigest.update(buffer, 0, read)
                        byteCount += read.toLong()
                    }
                }
            }

            if (byteCount == 0L) {
                throw ReferenceAssetPublicationFailed(ReferenceAssetFailureStage.EMPTY_INPUT)
            }

            failureStage = ReferenceAssetFailureStage.SYNC_TEMP
            fileOps.syncOwnedFile(tempPath, claimedTempIdentity)

            failureStage = ReferenceAssetFailureStage.VALIDATE_TEMP
            if (fileOps.ownedRegularByteCount(tempPath, claimedTempIdentity) != byteCount) {
                throw ReferenceAssetPublicationFailed(ReferenceAssetFailureStage.VALIDATE_TEMP)
            }

            failureStage = ReferenceAssetFailureStage.RENAME
            fileOps.replaceOwnedReservation(
                sourcePath = tempPath,
                sourceIdentity = claimedTempIdentity,
                expectedByteCount = byteCount,
                destinationPath = finalPath,
                reservationIdentity = claimedReservationIdentity,
            )
            reservationReplaced = true
            fileOps.syncDirectory(directories.assets)

            val contentSha256 = contentDigest.digest().toHex()
            PublishedReferenceAsset(
                safeRelativePath = safeRelativePath,
                byteCount = byteCount,
                sha256 = contentSha256,
                ownershipIdentity = ReferenceAssetOwnershipIdentity(
                    storeIdentity = storeIdentity,
                    finalFileName = finalFileName,
                    fileIdentity = claimedTempIdentity,
                    byteCount = byteCount,
                    contentSha256 = contentSha256,
                ),
            )
        } catch (_: ReferenceAssetReservationConflict) {
            throw ReferenceAssetReconciliationRequired()
        } catch (_: ReferenceAssetReservationMayExist) {
            throw ReferenceAssetReconciliationRequired()
        } catch (_: ReferenceAssetTempConflict) {
            cleanupOwnedReservationAfterTempClaimFailure(finalPath, reservationIdentity)
            throw ReferenceAssetReconciliationRequired()
        } catch (_: ReferenceAssetTempMayExist) {
            cleanupOwnedReservationAfterTempClaimFailure(finalPath, reservationIdentity)
            throw ReferenceAssetReconciliationRequired()
        } catch (_: ReferenceAssetOwnershipMismatch) {
            if (failureStage == ReferenceAssetFailureStage.CLAIM_TEMP) {
                cleanupOwnedReservationAfterTempClaimFailure(finalPath, reservationIdentity)
            }
            throw ReferenceAssetReconciliationRequired()
        } catch (failure: Exception) {
            if (reservationReplaced) throw ReferenceAssetReconciliationRequired()
            val publicationFailure = ReferenceAssetPublicationFailed(
                if (failure is ReferenceAssetPublicationFailed) failure.stage else failureStage,
            )
            val cleanupCertain = cleanupBeforePublicationFailure(
                tempPath = tempPath,
                tempIdentity = tempIdentity,
                tempByteCount = byteCount,
                finalPath = finalPath,
                reservationIdentity = reservationIdentity,
            )
            if (!cleanupCertain) throw ReferenceAssetReconciliationRequired()
            throw publicationFailure
        }
    }

    fun cleanup(publication: PublishedReferenceAsset): ReferenceAssetCleanupResult =
        withExclusiveReferenceAssetMutation {
            val ownership = publication.ownershipIdentity
            if (ownership.storeIdentity !== storeIdentity) {
                return@withExclusiveReferenceAssetMutation ReferenceAssetCleanupResult.ReconciliationRequired
            }
            ownership.closedResult?.let { return@withExclusiveReferenceAssetMutation it }
            val expectedRelativePath = "reference-assets/assets/${ownership.finalFileName}"
            if (publication.safeRelativePath != expectedRelativePath ||
                publication.byteCount != ownership.byteCount ||
                publication.sha256 != ownership.contentSha256
            ) {
                return@withExclusiveReferenceAssetMutation ReferenceAssetCleanupResult.ReconciliationRequired
            }

            val result = cleanupOwnedPublication(ownership)
            ownership.closedResult = result
            result
        }

    /**
     * Re-adopts only the two deterministic token paths after no-follow regular-file inspection.
     * The fresh identity returned by [ReferenceAssetFileOps.observeRegularFile] is authoritative
     * only because this protocol exclusively owns the app-private reference-assets directory and
     * all supported mutation is serialized by the process-wide guard.
     */
    fun reconcilePending(
        identity: ReferenceAssetIdentity,
        lifecycle: ReferenceAssetPendingLifecycle,
    ): ReferenceAssetCleanupResult = withExclusiveReferenceAssetMutation {
        val finalFileName = ReferenceImportAssetPath.forToken(identity.importToken).substringAfterLast('/')
        val tokenDigest = finalFileName.removeSuffix(".asset")
        val finalPath = confinedReferenceChild(directories.assets, finalFileName)
        val tempPath = confinedReferenceChild(directories.assets, ".$finalFileName.pending")
        val quarantineFileName = "$tokenDigest.quarantined"
        val quarantinePath = confinedReferenceChild(directories.quarantine, quarantineFileName)
        val finalObservation = try {
            fileOps.observeRegularFile(finalPath)
        } catch (_: Exception) {
            return@withExclusiveReferenceAssetMutation ReferenceAssetCleanupResult.ReconciliationRequired
        }
        val tempObservation = try {
            fileOps.observeRegularFile(tempPath)
        } catch (_: Exception) {
            return@withExclusiveReferenceAssetMutation ReferenceAssetCleanupResult.ReconciliationRequired
        }
        val quarantineObservation = try {
            fileOps.observeRegularFile(quarantinePath)
        } catch (_: Exception) {
            return@withExclusiveReferenceAssetMutation ReferenceAssetCleanupResult.ReconciliationRequired
        }

        if (quarantineObservation is ReferenceAssetFileObservation.Regular) {
            return@withExclusiveReferenceAssetMutation confirmExistingQuarantineDurable(
                finalPath = finalPath,
                tempPath = tempPath,
                quarantinePath = quarantinePath,
                quarantineFileName = quarantineFileName,
                observedQuarantine = quarantineObservation,
                initialFinal = finalObservation,
                initialTemp = tempObservation,
            )
        }

        when (lifecycle) {
            ReferenceAssetPendingLifecycle.PREPARING -> reconcilePreparing(
                tokenDigest = tokenDigest,
                finalPath = finalPath,
                finalObservation = finalObservation,
                tempPath = tempPath,
                tempObservation = tempObservation,
                quarantinePath = quarantinePath,
            )
            ReferenceAssetPendingLifecycle.ASSET_READY -> reconcileAssetReady(
                tokenDigest = tokenDigest,
                finalPath = finalPath,
                finalObservation = finalObservation,
                tempPath = tempPath,
                tempObservation = tempObservation,
                quarantinePath = quarantinePath,
            )
        }
    }

    private fun reconcilePreparing(
        tokenDigest: String,
        finalPath: Path,
        finalObservation: ReferenceAssetFileObservation,
        tempPath: Path,
        tempObservation: ReferenceAssetFileObservation,
        quarantinePath: Path,
    ): ReferenceAssetCleanupResult = when {
        finalObservation === ReferenceAssetFileObservation.Absent &&
            tempObservation === ReferenceAssetFileObservation.Absent ->
            confirmAbsentAssetsDurable(finalPath, tempPath, quarantinePath)

        finalObservation is ReferenceAssetFileObservation.Regular &&
            tempObservation === ReferenceAssetFileObservation.Absent -> {
            if (finalObservation.byteCount == 0L) {
                deleteRestartFiles(finalPath to finalObservation)
            } else {
                cleanupAdoptedNonemptyFile(finalPath, finalObservation, tokenDigest)
            }
        }

        finalObservation === ReferenceAssetFileObservation.Absent &&
            tempObservation is ReferenceAssetFileObservation.Regular -> {
            if (tempObservation.byteCount == 0L) {
                deleteRestartFiles(tempPath to tempObservation)
            } else {
                quarantineObservedFile(tempPath, tempObservation, tokenDigest)
            }
        }

        finalObservation is ReferenceAssetFileObservation.Regular &&
            tempObservation is ReferenceAssetFileObservation.Regular &&
            finalObservation.byteCount == 0L &&
            tempObservation.byteCount == 0L ->
            deleteRestartFiles(finalPath to finalObservation, tempPath to tempObservation)

        finalObservation is ReferenceAssetFileObservation.Regular &&
            tempObservation is ReferenceAssetFileObservation.Regular &&
            finalObservation.byteCount == 0L &&
            tempObservation.byteCount > 0L -> {
            val quarantine = quarantineObservedFile(tempPath, tempObservation, tokenDigest)
            if (quarantine !is ReferenceAssetCleanupResult.Quarantined) {
                ReferenceAssetCleanupResult.ReconciliationRequired
            } else {
                when (deleteRestartFiles(finalPath to finalObservation)) {
                    ReferenceAssetCleanupResult.Cleaned -> quarantine
                    else -> ReferenceAssetCleanupResult.ReconciliationRequired
                }
            }
        }

        else -> ReferenceAssetCleanupResult.ReconciliationRequired
    }

    private fun reconcileAssetReady(
        tokenDigest: String,
        finalPath: Path,
        finalObservation: ReferenceAssetFileObservation,
        tempPath: Path,
        tempObservation: ReferenceAssetFileObservation,
        quarantinePath: Path,
    ): ReferenceAssetCleanupResult = when {
        finalObservation === ReferenceAssetFileObservation.Absent &&
            tempObservation === ReferenceAssetFileObservation.Absent ->
            confirmAbsentAssetsDurable(finalPath, tempPath, quarantinePath)

        finalObservation is ReferenceAssetFileObservation.Regular &&
            finalObservation.byteCount > 0L &&
            tempObservation === ReferenceAssetFileObservation.Absent ->
            cleanupAdoptedNonemptyFile(finalPath, finalObservation, tokenDigest)

        else -> ReferenceAssetCleanupResult.ReconciliationRequired
    }

    private fun confirmAbsentAssetsDurable(
        finalPath: Path,
        tempPath: Path,
        quarantinePath: Path,
    ): ReferenceAssetCleanupResult {
        return try {
            fileOps.syncDirectory(directories.assets)
            fileOps.syncDirectory(directories.quarantine)
            val finalAfterSync = fileOps.observeRegularFile(finalPath)
            val tempAfterSync = fileOps.observeRegularFile(tempPath)
            val quarantineAfterSync = fileOps.observeRegularFile(quarantinePath)
            if (
                finalAfterSync === ReferenceAssetFileObservation.Absent &&
                tempAfterSync === ReferenceAssetFileObservation.Absent &&
                quarantineAfterSync === ReferenceAssetFileObservation.Absent
            ) {
                ReferenceAssetCleanupResult.Cleaned
            } else {
                ReferenceAssetCleanupResult.ReconciliationRequired
            }
        } catch (_: Exception) {
            ReferenceAssetCleanupResult.ReconciliationRequired
        }
    }

    private fun confirmExistingQuarantineDurable(
        finalPath: Path,
        tempPath: Path,
        quarantinePath: Path,
        quarantineFileName: String,
        observedQuarantine: ReferenceAssetFileObservation.Regular,
        initialFinal: ReferenceAssetFileObservation,
        initialTemp: ReferenceAssetFileObservation,
    ): ReferenceAssetCleanupResult {
        if (
            observedQuarantine.byteCount <= 0L ||
            initialFinal !== ReferenceAssetFileObservation.Absent ||
            initialTemp !== ReferenceAssetFileObservation.Absent
        ) {
            return ReferenceAssetCleanupResult.ReconciliationRequired
        }
        return try {
            fileOps.syncDirectory(directories.assets)
            fileOps.syncDirectory(directories.quarantine)
            val finalAfterSync = fileOps.observeRegularFile(finalPath)
            val tempAfterSync = fileOps.observeRegularFile(tempPath)
            val quarantineAfterSync = fileOps.observeRegularFile(quarantinePath)
            if (
                finalAfterSync === ReferenceAssetFileObservation.Absent &&
                tempAfterSync === ReferenceAssetFileObservation.Absent &&
                quarantineAfterSync is ReferenceAssetFileObservation.Regular &&
                quarantineAfterSync.byteCount == observedQuarantine.byteCount &&
                quarantineAfterSync.identity == observedQuarantine.identity
            ) {
                ReferenceAssetCleanupResult.Quarantined(
                    "reference-assets/quarantine/$quarantineFileName",
                )
            } else {
                ReferenceAssetCleanupResult.ReconciliationRequired
            }
        } catch (_: Exception) {
            ReferenceAssetCleanupResult.ReconciliationRequired
        }
    }

    private fun deleteRestartFiles(
        vararg files: Pair<Path, ReferenceAssetFileObservation.Regular>,
    ): ReferenceAssetCleanupResult {
        return try {
            files.forEach { (path, observed) ->
                fileOps.deleteOwnedFile(path, observed.identity, observed.byteCount)
            }
            fileOps.syncDirectory(directories.assets)
            ReferenceAssetCleanupResult.Cleaned
        } catch (_: Exception) {
            ReferenceAssetCleanupResult.ReconciliationRequired
        }
    }

    private fun cleanupAdoptedNonemptyFile(
        path: Path,
        observed: ReferenceAssetFileObservation.Regular,
        tokenDigest: String,
    ): ReferenceAssetCleanupResult {
        observedContentSha256(path, observed)
            ?: return ReferenceAssetCleanupResult.ReconciliationRequired
        try {
            fileOps.deleteOwnedFile(path, observed.identity, observed.byteCount)
        } catch (_: ReferenceAssetOwnershipMismatch) {
            return ReferenceAssetCleanupResult.ReconciliationRequired
        } catch (_: Exception) {
            return quarantineExactOwnedFile(
                sourcePath = path,
                sourceIdentity = observed.identity,
                byteCount = observed.byteCount,
                tokenDigest = tokenDigest,
            )
        }
        return try {
            fileOps.syncDirectory(directories.assets)
            ReferenceAssetCleanupResult.Cleaned
        } catch (_: Exception) {
            ReferenceAssetCleanupResult.ReconciliationRequired
        }
    }

    private fun quarantineObservedFile(
        path: Path,
        observed: ReferenceAssetFileObservation.Regular,
        tokenDigest: String,
    ): ReferenceAssetCleanupResult {
        observedContentSha256(path, observed)
            ?: return ReferenceAssetCleanupResult.ReconciliationRequired
        return quarantineExactOwnedFile(
            sourcePath = path,
            sourceIdentity = observed.identity,
            byteCount = observed.byteCount,
            tokenDigest = tokenDigest,
        )
    }

    private fun observedContentSha256(
        path: Path,
        observed: ReferenceAssetFileObservation.Regular,
    ): String? = try {
        fileOps.sha256OwnedFile(path, observed.identity, observed.byteCount)
            .takeIf { digest -> SHA256_HEX.matches(digest) }
    } catch (_: Exception) {
        null
    }

    private fun cleanupOwnedReservationAfterTempClaimFailure(
        finalPath: Path,
        reservationIdentity: ReferenceAssetFileIdentity?,
    ) {
        if (reservationIdentity == null) return
        try {
            fileOps.deleteOwnedFile(finalPath, reservationIdentity, 0L)
        } catch (_: Exception) {
            // The caller still reports reconciliation required; never inspect or mutate the temp.
        }
        try {
            fileOps.syncDirectory(directories.assets)
        } catch (_: Exception) {
            // The caller still reports reconciliation required.
        }
    }

    private fun cleanupBeforePublicationFailure(
        tempPath: Path,
        tempIdentity: ReferenceAssetFileIdentity?,
        tempByteCount: Long,
        finalPath: Path,
        reservationIdentity: ReferenceAssetFileIdentity?,
    ): Boolean {
        if (tempIdentity == null && reservationIdentity == null) return true
        var cleanupCertain = true
        if (tempIdentity != null) {
            try {
                fileOps.deleteOwnedFile(tempPath, tempIdentity, tempByteCount)
            } catch (_: Exception) {
                cleanupCertain = false
            }
        }
        if (reservationIdentity != null) {
            try {
                fileOps.deleteOwnedFile(finalPath, reservationIdentity, 0L)
            } catch (_: Exception) {
                cleanupCertain = false
            }
        }
        try {
            fileOps.syncDirectory(directories.assets)
        } catch (_: Exception) {
            cleanupCertain = false
        }
        return cleanupCertain
    }

    private fun cleanupOwnedPublication(
        ownership: ReferenceAssetOwnershipIdentity,
    ): ReferenceAssetCleanupResult {
        val finalPath = confinedReferenceChild(directories.assets, ownership.finalFileName)
        try {
            fileOps.deleteOwnedFile(finalPath, ownership.fileIdentity, ownership.byteCount)
        } catch (_: ReferenceAssetOwnershipMismatch) {
            return ReferenceAssetCleanupResult.ReconciliationRequired
        } catch (_: Exception) {
            return quarantineExactOwnedFile(
                sourcePath = finalPath,
                sourceIdentity = ownership.fileIdentity,
                byteCount = ownership.byteCount,
                tokenDigest = ownership.finalFileName.removeSuffix(".asset"),
            )
        }

        return try {
            fileOps.syncDirectory(directories.assets)
            ReferenceAssetCleanupResult.Cleaned
        } catch (_: Exception) {
            ReferenceAssetCleanupResult.ReconciliationRequired
        }
    }

    private fun quarantineExactOwnedFile(
        sourcePath: Path,
        sourceIdentity: ReferenceAssetFileIdentity,
        byteCount: Long,
        tokenDigest: String,
    ): ReferenceAssetCleanupResult {
        val quarantineFileName = "$tokenDigest.quarantined"
        val quarantinePath = confinedReferenceChild(directories.quarantine, quarantineFileName)
        val quarantineIdentity = try {
            fileOps.reserveEmpty(quarantinePath)
        } catch (_: Exception) {
            return ReferenceAssetCleanupResult.ReconciliationRequired
        }

        try {
            fileOps.replaceOwnedReservation(
                sourcePath = sourcePath,
                sourceIdentity = sourceIdentity,
                expectedByteCount = byteCount,
                destinationPath = quarantinePath,
                reservationIdentity = quarantineIdentity,
            )
        } catch (_: Exception) {
            bestEffortDeleteQuarantineReservation(quarantinePath, quarantineIdentity)
            return ReferenceAssetCleanupResult.ReconciliationRequired
        }

        var syncCertain = true
        try {
            fileOps.syncDirectory(directories.assets)
        } catch (_: Exception) {
            syncCertain = false
        }
        try {
            fileOps.syncDirectory(directories.quarantine)
        } catch (_: Exception) {
            syncCertain = false
        }
        if (!syncCertain) return ReferenceAssetCleanupResult.ReconciliationRequired

        return ReferenceAssetCleanupResult.Quarantined(
            "reference-assets/quarantine/$quarantineFileName",
        )
    }

    private fun bestEffortDeleteQuarantineReservation(
        quarantinePath: Path,
        quarantineIdentity: ReferenceAssetFileIdentity,
    ) {
        try {
            fileOps.deleteOwnedFile(quarantinePath, quarantineIdentity, 0L)
        } catch (_: Exception) {
            return
        }
        try {
            fileOps.syncDirectory(directories.quarantine)
        } catch (_: Exception) {
            // Reconciliation is already required; leave no raw failure attached.
        }
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 8 * 1024
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

internal data class ReferenceAssetDirectories(
    val assets: Path,
    val quarantine: Path,
)

internal interface ReferenceAssetFileIdentity

internal sealed interface ReferenceAssetFileObservation {
    data object Absent : ReferenceAssetFileObservation

    class Regular(
        val identity: ReferenceAssetFileIdentity,
        val byteCount: Long,
    ) : ReferenceAssetFileObservation
}

internal interface ReferenceAssetFileOps {
    fun prepareDirectories(noBackupRoot: Path): ReferenceAssetDirectories
    fun reserveEmpty(path: Path): ReferenceAssetFileIdentity
    fun createTemp(path: Path): ReferenceAssetFileIdentity
    fun openOwnedForWrite(path: Path, identity: ReferenceAssetFileIdentity): OutputStream
    fun syncOwnedFile(path: Path, identity: ReferenceAssetFileIdentity)
    fun ownedRegularByteCount(path: Path, identity: ReferenceAssetFileIdentity): Long?
    fun replaceOwnedReservation(
        sourcePath: Path,
        sourceIdentity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
        destinationPath: Path,
        reservationIdentity: ReferenceAssetFileIdentity,
    )
    fun deleteOwnedFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
    )
    fun syncDirectory(directory: Path)
    fun observeRegularFile(path: Path): ReferenceAssetFileObservation =
        throw ReferenceAssetOperationFailed()
    fun sha256OwnedFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
    ): String = throw ReferenceAssetOperationFailed()
}

internal class ReferenceAssetReservationConflict : IllegalStateException()
internal class ReferenceAssetReservationMayExist : IllegalStateException()
internal class ReferenceAssetTempConflict : IllegalStateException()
internal class ReferenceAssetTempMayExist : IllegalStateException()
internal class ReferenceAssetOwnershipMismatch : IllegalStateException()
internal class ReferenceAssetOperationFailed : IllegalStateException()

private object ProcessWideReferenceAssetMutationGuard {
    private val monitor = Any()

    fun <T> mutate(block: () -> T): T = synchronized(monitor, block)
}

internal fun <T> withExclusiveReferenceAssetMutation(block: () -> T): T =
    ProcessWideReferenceAssetMutationGuard.mutate(block)

private fun confinedReferenceChild(parent: Path, fileName: String): Path {
    val child = parent.resolve(fileName).normalize()
    if (child.parent != parent) throw IllegalArgumentException("reference asset path rejected")
    return child
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private data class NioReferenceAssetFileIdentity(val fileKey: Any) : ReferenceAssetFileIdentity

internal object NioReferenceAssetFileOps : ReferenceAssetFileOps {
    override fun prepareDirectories(noBackupRoot: Path): ReferenceAssetDirectories =
        withExclusiveReferenceAssetMutation {
            val root = noBackupRoot.toAbsolutePath().normalize()
            ensureDirectory(root)
            val referenceRoot = confinedReferenceChild(root, "reference-assets")
            ensureDirectory(referenceRoot)
            val assets = confinedReferenceChild(referenceRoot, "assets")
            val quarantine = confinedReferenceChild(referenceRoot, "quarantine")
            ensureDirectory(assets)
            ensureDirectory(quarantine)
            ReferenceAssetDirectories(assets = assets, quarantine = quarantine)
        }

    override fun reserveEmpty(path: Path): ReferenceAssetFileIdentity =
        withExclusiveReferenceAssetMutation {
            var created = false
            try {
                FileChannel.open(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    created = true
                    channel.force(true)
                }
                val attributes = readAttributes(path)
                if (!attributes.isRegularFile || attributes.size() != 0L) {
                    throw ReferenceAssetOwnershipMismatch()
                }
                NioReferenceAssetFileIdentity(requireNotNull(attributes.fileKey()))
            } catch (_: FileAlreadyExistsException) {
                if (created) throw ReferenceAssetReservationMayExist()
                throw ReferenceAssetReservationConflict()
            } catch (failure: ReferenceAssetOwnershipMismatch) {
                if (created) throw ReferenceAssetReservationMayExist()
                throw failure
            } catch (_: Exception) {
                if (created) throw ReferenceAssetReservationMayExist()
                throw ReferenceAssetOperationFailed()
            }
        }

    override fun createTemp(path: Path): ReferenceAssetFileIdentity =
        withExclusiveReferenceAssetMutation {
            var created = false
            try {
                FileChannel.open(
                    path,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { created = true }
                val attributes = readAttributes(path)
                if (!attributes.isRegularFile || attributes.size() != 0L) {
                    throw ReferenceAssetOwnershipMismatch()
                }
                NioReferenceAssetFileIdentity(requireNotNull(attributes.fileKey()))
            } catch (_: FileAlreadyExistsException) {
                if (created) throw ReferenceAssetTempMayExist()
                throw ReferenceAssetTempConflict()
            } catch (failure: ReferenceAssetOwnershipMismatch) {
                if (created) throw ReferenceAssetTempMayExist()
                throw failure
            } catch (_: Exception) {
                if (created) throw ReferenceAssetTempMayExist()
                throw ReferenceAssetOperationFailed()
            }
        }

    override fun openOwnedForWrite(
        path: Path,
        identity: ReferenceAssetFileIdentity,
    ): OutputStream = withExclusiveReferenceAssetMutation {
        requireOwnedRegularFile(path, identity, expectedByteCount = 0L)
        try {
            Channels.newOutputStream(
                FileChannel.open(
                    path,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ),
            )
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
    }

    override fun syncOwnedFile(path: Path, identity: ReferenceAssetFileIdentity) =
        withExclusiveReferenceAssetMutation {
            requireOwnedRegularFile(path, identity)
            try {
                FileChannel.open(
                    path,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel -> channel.force(true) }
            } catch (_: Exception) {
                throw ReferenceAssetOperationFailed()
            }
        }

    override fun ownedRegularByteCount(
        path: Path,
        identity: ReferenceAssetFileIdentity,
    ): Long? = withExclusiveReferenceAssetMutation {
        val attributes = requireOwnedAttributes(path, identity)
        if (attributes.isRegularFile) attributes.size() else null
    }

    override fun replaceOwnedReservation(
        sourcePath: Path,
        sourceIdentity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
        destinationPath: Path,
        reservationIdentity: ReferenceAssetFileIdentity,
    ) = withExclusiveReferenceAssetMutation {
        requireOwnedRegularFile(sourcePath, sourceIdentity, expectedByteCount)
        requireOwnedRegularFile(destinationPath, reservationIdentity, expectedByteCount = 0L)
        try {
            Files.move(
                sourcePath,
                destinationPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Unit
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
    }

    override fun deleteOwnedFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
    ) = withExclusiveReferenceAssetMutation {
        requireOwnedRegularFile(path, identity, expectedByteCount)
        try {
            Files.delete(path)
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
    }

    override fun syncDirectory(directory: Path) = withExclusiveReferenceAssetMutation {
        val attributes = try {
            readAttributes(directory)
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
        if (!attributes.isDirectory) throw ReferenceAssetOperationFailed()
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
    }

    override fun observeRegularFile(path: Path): ReferenceAssetFileObservation =
        withExclusiveReferenceAssetMutation {
            val attributes = try {
                readAttributes(path)
            } catch (_: NoSuchFileException) {
                return@withExclusiveReferenceAssetMutation ReferenceAssetFileObservation.Absent
            } catch (_: Exception) {
                throw ReferenceAssetOperationFailed()
            }
            val fileKey = attributes.fileKey()
            if (!attributes.isRegularFile || fileKey == null) {
                throw ReferenceAssetOwnershipMismatch()
            }
            ReferenceAssetFileObservation.Regular(
                identity = NioReferenceAssetFileIdentity(fileKey),
                byteCount = attributes.size(),
            )
        }

    override fun sha256OwnedFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
    ): String = withExclusiveReferenceAssetMutation {
        requireOwnedRegularFile(path, identity, expectedByteCount)
        val digest = MessageDigest.getInstance("SHA-256")
        var observedByteCount = 0L
        try {
            FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = java.nio.ByteBuffer.allocate(8 * 1024)
                while (true) {
                    val read = channel.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    observedByteCount += read.toLong()
                    buffer.flip()
                    digest.update(buffer)
                    buffer.clear()
                }
            }
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
        requireOwnedRegularFile(path, identity, expectedByteCount)
        if (observedByteCount != expectedByteCount) throw ReferenceAssetOwnershipMismatch()
        digest.digest().toHex()
    }

    private fun ensureDirectory(path: Path) {
        try {
            Files.createDirectory(path)
        } catch (_: FileAlreadyExistsException) {
            // Existing app-private directories are accepted only after no-follow validation below.
        }
        val attributes = readAttributes(path)
        if (!attributes.isDirectory) throw ReferenceAssetOperationFailed()
    }

    private fun requireOwnedRegularFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long? = null,
    ) {
        val attributes = requireOwnedAttributes(path, identity)
        if (!attributes.isRegularFile ||
            (expectedByteCount != null && attributes.size() != expectedByteCount)
        ) {
            throw ReferenceAssetOwnershipMismatch()
        }
    }

    private fun requireOwnedAttributes(
        path: Path,
        identity: ReferenceAssetFileIdentity,
    ): BasicFileAttributes {
        val expected = identity as? NioReferenceAssetFileIdentity
            ?: throw ReferenceAssetOwnershipMismatch()
        val attributes = try {
            readAttributes(path)
        } catch (_: Exception) {
            throw ReferenceAssetOwnershipMismatch()
        }
        if (attributes.fileKey() == null || attributes.fileKey() != expected.fileKey) {
            throw ReferenceAssetOwnershipMismatch()
        }
        return attributes
    }

    private fun readAttributes(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )
}
