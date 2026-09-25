package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.data.CaptureFileFailureCode
import com.tonyisup.poseguidesnap.data.CaptureFileOperationPaths
import com.tonyisup.poseguidesnap.data.CaptureFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

class JournaledCaptureEvidence(
    val byteCount: Long,
    val sha256: String,
    val capturedAtEpochMillis: Long,
) {
    init {
        require(byteCount > 0L) { "capture evidence byte count must be positive" }
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "capture evidence digest must be canonical"
        }
        require(capturedAtEpochMillis >= 0L) { "capture evidence timestamp must be nonnegative" }
    }

    override fun equals(other: Any?): Boolean =
        other is JournaledCaptureEvidence &&
            byteCount == other.byteCount &&
            sha256 == other.sha256 &&
            capturedAtEpochMillis == other.capturedAtEpochMillis

    override fun hashCode(): Int =
        31 * (31 * byteCount.hashCode() + sha256.hashCode()) + capturedAtEpochMillis.hashCode()

    override fun toString(): String = "JournaledCaptureEvidence(redacted)"
}

sealed interface CaptureFileClaimResult {
    class Claimed internal constructor(val lease: JournaledCaptureWriteLease) :
        CaptureFileClaimResult {
        override fun toString(): String = "CaptureFileClaimResult.Claimed(redacted)"
    }

    class Ambiguous(val failureCode: CaptureFileFailureCode) : CaptureFileClaimResult {
        override fun toString(): String =
            "CaptureFileClaimResult.Ambiguous(failureCode=${failureCode.name})"
    }
}

sealed interface CaptureTempSyncResult {
    class Synced(val evidence: JournaledCaptureEvidence) : CaptureTempSyncResult {
        override fun toString(): String = "CaptureTempSyncResult.Synced(redacted)"
    }

    class Ambiguous(val failureCode: CaptureFileFailureCode) : CaptureTempSyncResult {
        override fun toString(): String =
            "CaptureTempSyncResult.Ambiguous(failureCode=${failureCode.name})"
    }
}

sealed interface CaptureFinalPublicationResult {
    class Published(val evidence: JournaledCaptureEvidence) : CaptureFinalPublicationResult {
        override fun toString(): String = "CaptureFinalPublicationResult.Published(redacted)"
    }

    class Ambiguous(
        val failureCode: CaptureFileFailureCode,
        val finalMayExist: Boolean,
    ) : CaptureFinalPublicationResult {
        override fun toString(): String =
            "CaptureFinalPublicationResult.Ambiguous(failureCode=${failureCode.name}, " +
                "finalMayExist=$finalMayExist)"
    }
}

sealed interface CaptureExactCleanupResult {
    data object Cleaned : CaptureExactCleanupResult

    class Ambiguous(val failureCode: CaptureFileFailureCode) : CaptureExactCleanupResult {
        override fun toString(): String =
            "CaptureExactCleanupResult.Ambiguous(failureCode=${failureCode.name})"
    }
}

enum class JournalFreeCapturePathObservation {
    ALL_ABSENT,
    PRESENT_OR_AMBIGUOUS,
    UNAVAILABLE,
}

class JournaledCaptureWriteLease internal constructor(
    val identity: PrivateOutputIdentity,
    val tempFile: File,
    internal val finalPath: Path,
    internal val tempPath: Path,
    internal val quarantinePath: Path,
    internal val finalIdentity: PrivateCaptureReservationIdentity,
    internal val tempIdentity: Any,
) {
    override fun toString(): String =
        "JournaledCaptureWriteLease(ordinal=${identity.ordinal}, redacted)"
}

/**
 * Exact app-private capture-file effects. Room admission and settlement stay outside this class.
 */
class JournaledPrivateCaptureStore internal constructor(
    noBackupFilesDirectory: File,
    private val fileOps: PrivateCaptureFileOps,
) {
    constructor(noBackupFilesDirectory: File) : this(
        noBackupFilesDirectory = noBackupFilesDirectory,
        fileOps = NioPrivateCaptureFileOps,
    )

    private val root: Path
    private val candidates: Path
    private val quarantine: Path

    init {
        try {
            val requestedRoot = noBackupFilesDirectory.toPath().toAbsolutePath().normalize()
            Files.createDirectories(requestedRoot)
            checkSafeDirectory(requestedRoot)
            root = requestedRoot.toRealPath()
            candidates = prepareNamespace(root, CANDIDATE_DIRECTORY)
            quarantine = prepareNamespace(root, QUARANTINE_DIRECTORY)
        } catch (_: Exception) {
            throw IllegalArgumentException("private capture namespaces rejected")
        }
    }

    fun claimForWrite(snapshot: CaptureFileOperationSnapshot): CaptureFileClaimResult =
        withExclusiveMutation {
            if (!isWritableClaim(snapshot)) {
                return@withExclusiveMutation claimAmbiguous(CaptureFileFailureCode.STATE_MISMATCH)
            }
            val exact = exactPaths(snapshot.identity, snapshot.paths)
                ?: return@withExclusiveMutation claimAmbiguous(
                    CaptureFileFailureCode.STATE_MISMATCH,
                )
            if (!namespacesAreSafe() || exact.observeAnyPresent()) {
                return@withExclusiveMutation claimAmbiguous(CaptureFileFailureCode.STATE_MISMATCH)
            }

            try {
                val finalIdentity = fileOps.reserve(exact.final)
                fileOps.createNew(exact.temp)
                fileOps.syncFile(exact.temp)
                val tempIdentity = requireRegularIdentity(exact.temp, expectedByteCount = 0L)
                fileOps.syncDirectory(candidates)
                CaptureFileClaimResult.Claimed(
                    JournaledCaptureWriteLease(
                        identity = snapshot.identity,
                        tempFile = exact.temp.toFile(),
                        finalPath = exact.final,
                        tempPath = exact.temp,
                        quarantinePath = exact.quarantine,
                        finalIdentity = finalIdentity,
                        tempIdentity = tempIdentity,
                    ),
                )
            } catch (_: PrivateCaptureReservationConflict) {
                claimAmbiguous(CaptureFileFailureCode.STATE_MISMATCH)
            } catch (_: PrivateCaptureReservationMayExist) {
                claimAmbiguous(CaptureFileFailureCode.RESERVATION_FAILED)
            } catch (_: Exception) {
                claimAmbiguous(CaptureFileFailureCode.RESERVATION_FAILED)
            }
        }

    fun syncCapturedTemp(
        snapshot: CaptureFileOperationSnapshot,
        lease: JournaledCaptureWriteLease,
        capturedAtEpochMillis: Long,
    ): CaptureTempSyncResult = withExclusiveMutation {
        if (
            snapshot.stage != CaptureFileOperationStage.WRITING_TEMP ||
            snapshot.identity != lease.identity ||
            snapshot.byteCount != null ||
            snapshot.sha256 != null ||
            snapshot.capturedAtEpochMillis != null ||
            snapshot.reconciliationRequired ||
            capturedAtEpochMillis < snapshot.createdAtEpochMillis ||
            exactPaths(snapshot.identity, snapshot.paths)?.matches(lease) != true ||
            !namespacesAreSafe()
        ) {
            return@withExclusiveMutation tempAmbiguous(CaptureFileFailureCode.STATE_MISMATCH)
        }

        try {
            val tempAttributes = requireIdentity(lease.tempPath, lease.tempIdentity)
            if (tempAttributes.size() <= 0L) {
                return@withExclusiveMutation tempAmbiguous(CaptureFileFailureCode.WRITE_FAILED)
            }
            fileOps.syncFile(lease.tempPath)
            val digest = sha256(lease.tempPath, lease.tempIdentity, tempAttributes.size())
            val verified = requireIdentity(
                lease.tempPath,
                lease.tempIdentity,
                expectedByteCount = tempAttributes.size(),
            )
            if (verified.size() != tempAttributes.size()) {
                return@withExclusiveMutation tempAmbiguous(CaptureFileFailureCode.EVIDENCE_MISMATCH)
            }
            CaptureTempSyncResult.Synced(
                JournaledCaptureEvidence(
                    byteCount = verified.size(),
                    sha256 = digest,
                    capturedAtEpochMillis = capturedAtEpochMillis,
                ),
            )
        } catch (_: Exception) {
            tempAmbiguous(CaptureFileFailureCode.FILE_SYNC_FAILED)
        }
    }

    fun publishFinal(
        snapshot: CaptureFileOperationSnapshot,
        lease: JournaledCaptureWriteLease,
        evidence: JournaledCaptureEvidence,
    ): CaptureFinalPublicationResult = withExclusiveMutation {
        if (
            snapshot.stage != CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC ||
            snapshot.identity != lease.identity ||
            snapshot.byteCount != evidence.byteCount ||
            snapshot.sha256 != evidence.sha256 ||
            snapshot.capturedAtEpochMillis != evidence.capturedAtEpochMillis ||
            snapshot.reconciliationRequired ||
            exactPaths(snapshot.identity, snapshot.paths)?.matches(lease) != true ||
            !namespacesAreSafe()
        ) {
            return@withExclusiveMutation publicationAmbiguous(
                CaptureFileFailureCode.STATE_MISMATCH,
                false,
            )
        }

        var renamed = false
        try {
            verifyEvidence(lease.tempPath, lease.tempIdentity, evidence)
            fileOps.replaceOwnedReservation(
                tempPath = lease.tempPath,
                finalPath = lease.finalPath,
                identity = lease.finalIdentity,
            )
            renamed = true
            fileOps.syncDirectory(candidates)
            val finalAttributes = readRegularAttributes(lease.finalPath)
            verifyEvidence(
                lease.finalPath,
                requireNotNull(finalAttributes.fileKey()),
                evidence,
            )
            if (Files.exists(lease.tempPath, LinkOption.NOFOLLOW_LINKS)) {
                return@withExclusiveMutation publicationAmbiguous(
                    CaptureFileFailureCode.STATE_MISMATCH,
                    true,
                )
            }
            CaptureFinalPublicationResult.Published(evidence)
        } catch (_: Exception) {
            publicationAmbiguous(
                if (renamed) {
                    CaptureFileFailureCode.DIRECTORY_SYNC_FAILED
                } else {
                    CaptureFileFailureCode.RENAME_FAILED
                },
                finalMayExist = renamed,
            )
        }
    }

    /** Deletes only exact journal paths after `CLEANUP_PENDING_SYNC` has admitted the effect. */
    fun cleanupExact(snapshot: CaptureFileOperationSnapshot): CaptureExactCleanupResult =
        withExclusiveMutation {
            if (
                snapshot.stage != CaptureFileOperationStage.CLEANUP_PENDING_SYNC ||
                !namespacesAreSafe()
            ) {
                return@withExclusiveMutation cleanupAmbiguous(
                    CaptureFileFailureCode.STATE_MISMATCH,
                )
            }
            val exact = exactPaths(snapshot.identity, snapshot.paths)
                ?: return@withExclusiveMutation cleanupAmbiguous(
                    CaptureFileFailureCode.STATE_MISMATCH,
                )
            val observations = listOf(
                exact.final to RecoveryPathKind.FINAL,
                exact.temp to RecoveryPathKind.TEMP,
                exact.quarantine to RecoveryPathKind.QUARANTINE,
            ).map { (path, kind) -> Triple(path, kind, observe(path)) }

            if (observations.any { it.third is ExactFileObservation.Unsafe }) {
                return@withExclusiveMutation cleanupAmbiguous(
                    CaptureFileFailureCode.STATE_MISMATCH,
                )
            }
            val evidence = snapshot.captureEvidenceOrNull()
            val evidenceMismatch = observations.any { (path, kind, observation) ->
                when (observation) {
                    ExactFileObservation.Absent -> false
                    is ExactFileObservation.Regular -> when {
                        observation.byteCount == 0L -> false
                        evidence != null -> !matchesEvidence(path, observation.identity, evidence)
                        kind == RecoveryPathKind.TEMP -> false
                        else -> true
                    }
                    ExactFileObservation.Unsafe -> true
                }
            }
            if (evidenceMismatch) {
                return@withExclusiveMutation cleanupAmbiguous(
                    CaptureFileFailureCode.EVIDENCE_MISMATCH,
                )
            }

            try {
                var candidateChanged = false
                var quarantineChanged = false
                observations.forEach { (path, kind, observation) ->
                    if (observation is ExactFileObservation.Regular) {
                        if (!fileOps.deleteIfExists(path)) {
                            throw IllegalStateException("observed capture file disappeared")
                        }
                        if (kind == RecoveryPathKind.QUARANTINE) {
                            quarantineChanged = true
                        } else {
                            candidateChanged = true
                        }
                    }
                }
                if (candidateChanged) fileOps.syncDirectory(candidates)
                if (quarantineChanged) fileOps.syncDirectory(quarantine)
                if (observations.any { Files.exists(it.first, LinkOption.NOFOLLOW_LINKS) }) {
                    cleanupAmbiguous(CaptureFileFailureCode.DELETE_FAILED)
                } else {
                    CaptureExactCleanupResult.Cleaned
                }
            } catch (_: Exception) {
                cleanupAmbiguous(CaptureFileFailureCode.DELETE_FAILED)
            }
        }

    /** Bounded legacy check: exactly three deterministic identities and no directory scan. */
    fun observeJournalFreeAttempt(
        identities: List<PrivateOutputIdentity>,
    ): JournalFreeCapturePathObservation = withExclusiveMutation {
        if (
            identities.size != 3 ||
            identities != (0..2).map { ordinal ->
                PrivateOutputIdentity(identities.first().token, ordinal)
            } ||
            !namespacesAreSafe()
        ) {
            return@withExclusiveMutation JournalFreeCapturePathObservation.UNAVAILABLE
        }
        try {
            val anyPresent = identities.any { identity ->
                val exact = exactPaths(identity, CaptureFileOperationPaths.forIdentity(identity))
                    ?: return@withExclusiveMutation JournalFreeCapturePathObservation.UNAVAILABLE
                exact.observeAnyPresent()
            }
            if (anyPresent) {
                JournalFreeCapturePathObservation.PRESENT_OR_AMBIGUOUS
            } else {
                JournalFreeCapturePathObservation.ALL_ABSENT
            }
        } catch (_: Exception) {
            JournalFreeCapturePathObservation.UNAVAILABLE
        }
    }

    private fun namespacesAreSafe(): Boolean = try {
        checkSafeDirectory(root)
        checkSafeDirectory(candidates)
        checkSafeDirectory(quarantine)
        candidates.toRealPath().parent == root && quarantine.toRealPath().parent == root
    } catch (_: Exception) {
        false
    }

    private fun exactPaths(
        identity: PrivateOutputIdentity,
        persisted: CaptureFileOperationPaths,
    ): ExactCapturePaths? {
        if (persisted != CaptureFileOperationPaths.forIdentity(identity)) return null
        val finalPath = confined(persisted.relativeFinalPath, candidates) ?: return null
        val tempPath = confined(persisted.relativeTempPath, candidates) ?: return null
        val quarantinePath = confined(persisted.relativeQuarantinePath, quarantine) ?: return null
        return ExactCapturePaths(finalPath, tempPath, quarantinePath)
    }

    private fun confined(relative: String, expectedParent: Path): Path? {
        val child = root.resolve(relative).normalize()
        return child.takeIf { it.startsWith(root) && it.parent == expectedParent }
    }

    private fun isWritableClaim(snapshot: CaptureFileOperationSnapshot): Boolean =
        snapshot.stage == CaptureFileOperationStage.WRITING_TEMP &&
            snapshot.byteCount == null &&
            snapshot.sha256 == null &&
            snapshot.capturedAtEpochMillis == null &&
            !snapshot.reconciliationRequired

    private fun ExactCapturePaths.observeAnyPresent(): Boolean =
        listOf(final, temp, quarantine).any { path ->
            observe(path) !is ExactFileObservation.Absent
        }

    private fun ExactCapturePaths.matches(lease: JournaledCaptureWriteLease): Boolean =
        final == lease.finalPath && temp == lease.tempPath && quarantine == lease.quarantinePath

    private fun CaptureFileOperationSnapshot.captureEvidenceOrNull(): JournaledCaptureEvidence? =
        if (byteCount != null && sha256 != null && capturedAtEpochMillis != null) {
            JournaledCaptureEvidence(byteCount, sha256, capturedAtEpochMillis)
        } else {
            null
        }

    private fun verifyEvidence(path: Path, identity: Any, evidence: JournaledCaptureEvidence) {
        val attributes = requireIdentity(path, identity, expectedByteCount = evidence.byteCount)
        check(attributes.size() == evidence.byteCount)
        check(sha256(path, identity, evidence.byteCount) == evidence.sha256)
    }

    private fun matchesEvidence(path: Path, identity: Any, evidence: JournaledCaptureEvidence): Boolean =
        try {
            verifyEvidence(path, identity, evidence)
            true
        } catch (_: Exception) {
            false
        }

    private fun observe(path: Path): ExactFileObservation = try {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            ExactFileObservation.Absent
        } else if (Files.isSymbolicLink(path)) {
            ExactFileObservation.Unsafe
        } else {
            val attributes = readRegularAttributes(path)
            ExactFileObservation.Regular(requireNotNull(attributes.fileKey()), attributes.size())
        }
    } catch (_: Exception) {
        ExactFileObservation.Unsafe
    }

    private fun requireRegularIdentity(path: Path, expectedByteCount: Long? = null): Any {
        val attributes = readRegularAttributes(path)
        if (expectedByteCount != null) check(attributes.size() == expectedByteCount)
        return requireNotNull(attributes.fileKey())
    }

    private fun requireIdentity(
        path: Path,
        expectedIdentity: Any,
        expectedByteCount: Long? = null,
    ): BasicFileAttributes {
        val attributes = readRegularAttributes(path)
        check(attributes.fileKey() == expectedIdentity)
        if (expectedByteCount != null) check(attributes.size() == expectedByteCount)
        return attributes
    }

    private fun readRegularAttributes(path: Path): BasicFileAttributes {
        check(!Files.isSymbolicLink(path))
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        check(attributes.isRegularFile)
        return attributes
    }

    private fun sha256(path: Path, identity: Any, expectedByteCount: Long): String {
        requireIdentity(path, identity, expectedByteCount)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                count += read.toLong()
            }
        }
        check(count == expectedByteCount)
        requireIdentity(path, identity, expectedByteCount)
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private data class ExactCapturePaths(
        val final: Path,
        val temp: Path,
        val quarantine: Path,
    )

    private sealed interface ExactFileObservation {
        data object Absent : ExactFileObservation
        data object Unsafe : ExactFileObservation
        data class Regular(val identity: Any, val byteCount: Long) : ExactFileObservation
    }

    private enum class RecoveryPathKind {
        FINAL,
        TEMP,
        QUARANTINE,
    }

    private companion object {
        const val CANDIDATE_DIRECTORY = "capture-candidates"
        const val QUARANTINE_DIRECTORY = "capture-quarantine"
        const val STREAM_BUFFER_BYTES = 16 * 1024

        fun prepareNamespace(root: Path, name: String): Path {
            val path = root.resolve(name).normalize()
            check(path.parent == root)
            Files.createDirectories(path)
            checkSafeDirectory(path)
            return path.toRealPath()
        }

        fun checkSafeDirectory(path: Path) {
            check(!Files.isSymbolicLink(path))
            check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        }

        fun claimAmbiguous(code: CaptureFileFailureCode) =
            CaptureFileClaimResult.Ambiguous(code)

        fun tempAmbiguous(code: CaptureFileFailureCode) =
            CaptureTempSyncResult.Ambiguous(code)

        fun publicationAmbiguous(code: CaptureFileFailureCode, finalMayExist: Boolean) =
            CaptureFinalPublicationResult.Ambiguous(code, finalMayExist)

        fun cleanupAmbiguous(code: CaptureFileFailureCode) =
            CaptureExactCleanupResult.Ambiguous(code)
    }
}
