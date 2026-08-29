package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal fun requireCompletePrivateOutputSet(identities: List<PrivateOutputIdentity>) {
    require(identities.size == 3) { "private output set must contain exactly three identities" }
    val token = identities[0].token
    require(identities == (0..2).map { ordinal -> PrivateOutputIdentity(token, ordinal) }) {
        "private output set must contain ordered ordinals zero through two for one token"
    }
}

class PrivateCaptureFilePublisher private constructor(
    private val rootPath: Path,
    private val fileOps: PrivateCaptureFileOps,
) {
    constructor(rootDirectory: File) : this(
        rootPath = canonicalPrivateCaptureRoot(rootDirectory),
        fileOps = NioPrivateCaptureFileOps,
    )

    internal constructor(
        rootDirectory: File,
        fileOps: PrivateCaptureFileOps,
    ) : this(
        rootPath = canonicalPrivateCaptureRoot(rootDirectory),
        fileOps = fileOps,
    )

    fun prepare(identity: PrivateOutputIdentity): PreparedPrivateOutput {
        requireSafeRoot(rootPath)
        val finalName = safePrivateOutputName(identity)
        val finalPath = confinedChild(rootPath, finalName)
        val tempPath = confinedChild(rootPath, ".$finalName.pending")
        val reservationIdentity = try {
            fileOps.reserve(finalPath)
        } catch (_: PrivateCaptureReservationConflict) {
            throw PrivateCaptureReconciliationRequired(identity, false, false)
        } catch (_: PrivateCaptureReservationMayExist) {
            throw PrivateCaptureReconciliationRequired(identity, false, false)
        } catch (_: Exception) {
            throw PrivateCapturePublicationFailed(identity, PrivateCaptureFailureStage.PREPARE)
        }

        try {
            fileOps.createNew(tempPath)
        } catch (_: Exception) {
            val cleanupOwner = PrepareReservationCleanupOwner(
                identity = identity,
                finalPath = finalPath,
                rootPath = rootPath,
                reservationIdentity = reservationIdentity,
                fileOps = fileOps,
            )
            try {
                cleanupOwner.close()
            } catch (failure: PrivateCaptureCleanupFailed) {
                throw PrivateCapturePreparationCleanupRequired(
                    identity = identity,
                    stage = failure.stage,
                    cleanupOwner = cleanupOwner,
                )
            }
            throw PrivateCapturePublicationFailed(identity, PrivateCaptureFailureStage.PREPARE)
        }

        return PreparedPrivateOutput(
            identity = identity,
            tempFile = tempPath.toFile(),
            finalFile = finalPath.toFile(),
            rootPath = rootPath,
            reservationIdentity = reservationIdentity,
            fileOps = fileOps,
        )
    }
}

internal interface PrivateCaptureCleanupOwner : AutoCloseable {
    val identity: PrivateOutputIdentity
}

private class PrepareReservationCleanupOwner(
    override val identity: PrivateOutputIdentity,
    private val finalPath: Path,
    private val rootPath: Path,
    private val reservationIdentity: PrivateCaptureReservationIdentity,
    private val fileOps: PrivateCaptureFileOps,
) : PrivateCaptureCleanupOwner {
    private var reservationCleanupComplete = false
    private var cleanupDirectorySyncPending = false
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        if (!reservationCleanupComplete) {
            try {
                fileOps.deleteOwnedReservation(finalPath, reservationIdentity)
                reservationCleanupComplete = true
                cleanupDirectorySyncPending = true
            } catch (_: Exception) {
                throw PrivateCaptureCleanupFailed(
                    identity,
                    PrivateCaptureFailureStage.CLEANUP_RESERVATION,
                )
            }
        }
        if (cleanupDirectorySyncPending) {
            try {
                fileOps.syncDirectory(rootPath)
                cleanupDirectorySyncPending = false
            } catch (_: Exception) {
                throw PrivateCaptureCleanupFailed(
                    identity,
                    PrivateCaptureFailureStage.SYNC_DIRECTORY_AFTER_CLEANUP,
                )
            }
        }
        closed = true
    }

    override fun toString(): String =
        "PrepareReservationCleanupOwner(ordinal=${identity.ordinal})"
}

class PreparedPrivateOutput internal constructor(
    override val identity: PrivateOutputIdentity,
    val tempFile: File,
    private val finalFile: File,
    private val rootPath: Path,
    private val reservationIdentity: PrivateCaptureReservationIdentity,
    private val fileOps: PrivateCaptureFileOps,
) : PrivateCaptureCleanupOwner {
    private var publishedOutput: PublishedPrivateOutput? = null
    private var reconciliationFailure: PrivateCaptureReconciliationRequired? = null
    private var reservationReplacedByCandidate = false
    private var tempCleanupComplete = false
    private var reservationCleanupComplete = false
    private var cleanupDirectorySyncPending = false
    private var closed = false

    @Synchronized
    fun publish(): PublishedPrivateOutput {
        publishedOutput?.let { return it }
        reconciliationFailure?.let { throw it }
        if (closed) {
            throw PrivateCapturePublicationFailed(identity, PrivateCaptureFailureStage.CLOSED)
        }

        val tempPath = tempFile.toPath()
        val finalPath = finalFile.toPath()
        val byteCount = try {
            fileOps.regularFileByteCount(tempPath)
        } catch (_: Exception) {
            throw PrivateCapturePublicationFailed(identity, PrivateCaptureFailureStage.VALIDATE_TEMP)
        }
        if (byteCount == null || byteCount <= 0L) {
            throw PrivateCapturePublicationFailed(identity, PrivateCaptureFailureStage.VALIDATE_TEMP)
        }

        try {
            fileOps.syncFile(tempPath)
        } catch (_: Exception) {
            throw PrivateCapturePublicationFailed(identity, PrivateCaptureFailureStage.SYNC_TEMP)
        }

        try {
            fileOps.replaceOwnedReservation(tempPath, finalPath, reservationIdentity)
        } catch (_: PrivateCaptureReservationOwnershipMismatch) {
            throw reconciliationRequired(
                reservationReplacedByCandidate = false,
                directorySyncedAfterPublication = false,
            )
        } catch (_: Exception) {
            throw PrivateCapturePublicationFailed(
                identity,
                PrivateCaptureFailureStage.REPLACE_OWNED_RESERVATION,
            )
        }
        reservationReplacedByCandidate = true

        try {
            fileOps.syncDirectory(rootPath)
        } catch (_: Exception) {
            throw reconciliationRequired(
                reservationReplacedByCandidate = true,
                directorySyncedAfterPublication = false,
            )
        }

        return PublishedPrivateOutput(
            identity = identity,
            finalFile = finalFile,
            byteCount = byteCount,
        ).also { publishedOutput = it }
    }

    @Synchronized
    override fun close() {
        if (closed || publishedOutput != null) return
        if (reservationReplacedByCandidate) {
            closed = true
            return
        }
        if (cleanupDirectorySyncPending) {
            syncDirectoryAfterCleanup()
            closed = true
            return
        }

        if (!tempCleanupComplete) {
            try {
                fileOps.deleteIfExists(tempFile.toPath())
                tempCleanupComplete = true
            } catch (_: Exception) {
                throw PrivateCaptureCleanupFailed(identity, PrivateCaptureFailureStage.CLEANUP_TEMP)
            }
        }

        if (!reservationCleanupComplete) {
            try {
                fileOps.deleteOwnedReservation(finalFile.toPath(), reservationIdentity)
                reservationCleanupComplete = true
            } catch (_: Exception) {
                throw PrivateCaptureCleanupFailed(
                    identity,
                    PrivateCaptureFailureStage.CLEANUP_RESERVATION,
                )
            }
        }

        cleanupDirectorySyncPending = true
        syncDirectoryAfterCleanup()
        closed = true
    }

    private fun reconciliationRequired(
        reservationReplacedByCandidate: Boolean,
        directorySyncedAfterPublication: Boolean,
    ): PrivateCaptureReconciliationRequired = PrivateCaptureReconciliationRequired(
        identity = identity,
        reservationReplacedByCandidate = reservationReplacedByCandidate,
        directorySyncedAfterPublication = directorySyncedAfterPublication,
    ).also { reconciliationFailure = it }

    private fun syncDirectoryAfterCleanup() {
        try {
            fileOps.syncDirectory(rootPath)
            cleanupDirectorySyncPending = false
        } catch (_: Exception) {
            throw PrivateCaptureCleanupFailed(
                identity,
                PrivateCaptureFailureStage.SYNC_DIRECTORY_AFTER_CLEANUP,
            )
        }
    }

    override fun toString(): String =
        "PreparedPrivateOutput(ordinal=${identity.ordinal}, tempFile=${tempFile.name})"
}

class PublishedPrivateOutput internal constructor(
    val identity: PrivateOutputIdentity,
    val finalFile: File,
    val byteCount: Long,
) {
    init {
        require(byteCount > 0L) { "published byte count must be positive" }
    }

    override fun toString(): String =
        "PublishedPrivateOutput(ordinal=${identity.ordinal}, finalFile=${finalFile.name}, byteCount=$byteCount)"
}

enum class PrivateCaptureFailureStage {
    PREPARE,
    VALIDATE_TEMP,
    SYNC_TEMP,
    REPLACE_OWNED_RESERVATION,
    CLEANUP_TEMP,
    CLEANUP_RESERVATION,
    SYNC_DIRECTORY_AFTER_CLEANUP,
    CLOSED,
}

open class PrivateCaptureFilePublisherException internal constructor(
    message: String,
) : IllegalStateException(message)

class PrivateCaptureReconciliationRequired internal constructor(
    val identity: PrivateOutputIdentity,
    val reservationReplacedByCandidate: Boolean,
    val directorySyncedAfterPublication: Boolean,
) : PrivateCaptureFilePublisherException("private capture reconciliation required")

class PrivateCapturePublicationFailed internal constructor(
    val identity: PrivateOutputIdentity,
    val stage: PrivateCaptureFailureStage,
) : PrivateCaptureFilePublisherException("private capture publication failed at ${stage.name.lowercase()}")

class PrivateCaptureCleanupFailed internal constructor(
    val identity: PrivateOutputIdentity,
    val stage: PrivateCaptureFailureStage,
) : PrivateCaptureFilePublisherException("private capture cleanup failed at ${stage.name.lowercase()}")

internal class PrivateCapturePreparationCleanupRequired(
    val identity: PrivateOutputIdentity,
    val stage: PrivateCaptureFailureStage,
    val cleanupOwner: PrivateCaptureCleanupOwner,
) : PrivateCaptureFilePublisherException(
    "private capture preparation cleanup required at ${stage.name.lowercase()}",
)

internal interface PrivateCaptureReservationIdentity

internal class PrivateCaptureReservationOwnershipMismatch :
    IllegalStateException("private capture reservation ownership mismatch")

internal class PrivateCaptureReservationConflict :
    IllegalStateException("private capture reservation conflict")

internal class PrivateCaptureReservationMayExist :
    IllegalStateException("private capture reservation may exist")

private object ProcessWidePrivateCaptureMutationGuard {
    private val monitor = Any()

    fun <T> mutate(block: () -> T): T = synchronized(monitor, block)
}

internal fun <T> withExclusiveMutation(block: () -> T): T =
    ProcessWidePrivateCaptureMutationGuard.mutate(block)

/**
 * Operations within the dedicated private capture root.
 *
 * The capture-candidates directory is exclusively owned by this publisher and its future
 * reconciler. Every implementation must route all supported in-process mutation through
 * [withExclusiveMutation]. This process-wide guard makes owned-reservation verification and its
 * following rename or removal indivisible against those supported mutations. It does not protect
 * against same-UID or other code that bypasses this ownership boundary.
 */
internal interface PrivateCaptureFileOps {
    fun reserve(finalPath: Path): PrivateCaptureReservationIdentity
    fun createNew(path: Path)
    fun regularFileByteCount(path: Path): Long?
    fun syncFile(path: Path)
    fun replaceOwnedReservation(
        tempPath: Path,
        finalPath: Path,
        identity: PrivateCaptureReservationIdentity,
    )
    fun deleteOwnedReservation(finalPath: Path, identity: PrivateCaptureReservationIdentity)
    fun syncDirectory(directory: Path)
    fun deleteIfExists(path: Path): Boolean
}

private data class NioPrivateCaptureReservationIdentity(val fileKey: Any) :
    PrivateCaptureReservationIdentity

private object NioPrivateCaptureFileOps : PrivateCaptureFileOps {
    override fun reserve(finalPath: Path): PrivateCaptureReservationIdentity = withExclusiveMutation {
        var created = false
        try {
            FileChannel.open(
                finalPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                created = true
                channel.force(true)
            }
            val attributes = attributes(finalPath)
            if (!attributes.isRegularFile || attributes.size() != 0L) {
                throw PrivateCaptureReservationOwnershipMismatch()
            }
            NioPrivateCaptureReservationIdentity(requireNotNull(attributes.fileKey()))
        } catch (_: FileAlreadyExistsException) {
            if (created) throw PrivateCaptureReservationMayExist()
            throw PrivateCaptureReservationConflict()
        } catch (failure: Exception) {
            if (created) throw PrivateCaptureReservationMayExist()
            throw failure
        }
    }

    override fun createNew(path: Path) = withExclusiveMutation {
        FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { }
    }

    override fun regularFileByteCount(path: Path): Long? {
        if (Files.isSymbolicLink(path)) return null
        val attributes = attributes(path)
        return if (attributes.isRegularFile) attributes.size() else null
    }

    override fun syncFile(path: Path) {
        FileChannel.open(
            path,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel -> channel.force(true) }
    }

    override fun replaceOwnedReservation(
        tempPath: Path,
        finalPath: Path,
        identity: PrivateCaptureReservationIdentity,
    ) = withExclusiveMutation {
        requireOwnedZeroByteReservation(finalPath, identity)
        Files.move(
            tempPath,
            finalPath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        Unit
    }

    override fun deleteOwnedReservation(
        finalPath: Path,
        identity: PrivateCaptureReservationIdentity,
    ) = withExclusiveMutation {
        requireOwnedZeroByteReservation(finalPath, identity)
        Files.delete(finalPath)
    }

    override fun syncDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { channel -> channel.force(true) }
    }

    override fun deleteIfExists(path: Path): Boolean = withExclusiveMutation {
        Files.deleteIfExists(path)
    }

    private fun requireOwnedZeroByteReservation(
        path: Path,
        identity: PrivateCaptureReservationIdentity,
    ) {
        val expected = identity as? NioPrivateCaptureReservationIdentity
            ?: throw PrivateCaptureReservationOwnershipMismatch()
        val attributes = try {
            attributes(path)
        } catch (_: Exception) {
            throw PrivateCaptureReservationOwnershipMismatch()
        }
        val isOwnedReservation = attributes.isRegularFile &&
            attributes.size() == 0L &&
            attributes.fileKey() != null &&
            attributes.fileKey() == expected.fileKey
        if (!isOwnedReservation) throw PrivateCaptureReservationOwnershipMismatch()
    }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )
}

private fun canonicalPrivateCaptureRoot(rootDirectory: File): Path {
    val absolutePath = rootDirectory.toPath().toAbsolutePath().normalize()
    try {
        if (Files.isSymbolicLink(absolutePath)) throw IllegalArgumentException()
        Files.createDirectories(absolutePath)
        requireSafeRoot(absolutePath)
        return absolutePath.toRealPath()
    } catch (_: Exception) {
        throw IllegalArgumentException("private capture root rejected")
    }
}

private fun requireSafeRoot(rootPath: Path) {
    try {
        if (Files.isSymbolicLink(rootPath) || !Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException()
        }
    } catch (_: Exception) {
        throw IllegalArgumentException("private capture root rejected")
    }
}

private fun confinedChild(rootPath: Path, fileName: String): Path {
    val child = rootPath.resolve(fileName).normalize()
    if (child.parent != rootPath) throw IllegalArgumentException("private capture path rejected")
    return child
}

private fun safePrivateOutputName(identity: PrivateOutputIdentity): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(identity.token.value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "$digest-${identity.ordinal}.jpg"
}
