package com.tonyisup.poseguidesnap.data

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Path
import java.security.MessageDigest

/** Android libc-backed operations for app-private exact-owned reference-asset publication. */
internal object AndroidReferenceAssetFileOps : ReferenceAssetFileOps {
    private data class AndroidReferenceAssetFileIdentity(
        val device: Long,
        val inode: Long,
    ) : ReferenceAssetFileIdentity

    override fun prepareDirectories(noBackupRoot: Path): ReferenceAssetDirectories =
        withExclusiveReferenceAssetMutation {
            val root = noBackupRoot.toAbsolutePath().normalize()
            ensureDirectory(root)
            val referenceRoot = confinedChild(root, "reference-assets")
            ensureDirectory(referenceRoot)
            val assets = confinedChild(referenceRoot, "assets")
            val quarantine = confinedChild(referenceRoot, "quarantine")
            ensureDirectory(assets)
            ensureDirectory(quarantine)
            ReferenceAssetDirectories(assets = assets, quarantine = quarantine)
        }

    override fun reserveEmpty(path: Path): ReferenceAssetFileIdentity =
        withExclusiveReferenceAssetMutation {
            val descriptor = try {
                openNewPrivateFile(path)
            } catch (failure: ErrnoException) {
                if (failure.errno == OsConstants.EEXIST) {
                    throw ReferenceAssetReservationConflict()
                }
                throw ReferenceAssetOperationFailed()
            } catch (_: Exception) {
                throw ReferenceAssetOperationFailed()
            }
            val identity = inspectCreatedFile(descriptor, sync = true)
            if (identity == null) throw ReferenceAssetReservationMayExist()
            try {
                requireOwnedRegularFile(path, identity, 0L)
            } catch (_: Exception) {
                throw ReferenceAssetReservationMayExist()
            }
            identity
        }

    override fun createTemp(path: Path): ReferenceAssetFileIdentity =
        withExclusiveReferenceAssetMutation {
            val descriptor = try {
                openNewPrivateFile(path)
            } catch (failure: ErrnoException) {
                if (failure.errno == OsConstants.EEXIST) throw ReferenceAssetTempConflict()
                throw ReferenceAssetOperationFailed()
            } catch (_: Exception) {
                throw ReferenceAssetOperationFailed()
            }
            val identity = inspectCreatedFile(descriptor, sync = false)
            if (identity == null) throw ReferenceAssetTempMayExist()
            try {
                requireOwnedRegularFile(path, identity, 0L)
            } catch (_: Exception) {
                throw ReferenceAssetTempMayExist()
            }
            identity
        }

    override fun openOwnedForWrite(
        path: Path,
        identity: ReferenceAssetFileIdentity,
    ): OutputStream = withExclusiveReferenceAssetMutation {
        val descriptor = openVerifiedOwnedFile(
            path = path,
            identity = identity,
            expectedByteCount = 0L,
            flags = OsConstants.O_WRONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
        )
        try {
            FileOutputStream(descriptor)
        } catch (_: Exception) {
            closeQuietly(descriptor)
            throw ReferenceAssetOperationFailed()
        }
    }

    override fun syncOwnedFile(path: Path, identity: ReferenceAssetFileIdentity) =
        withExclusiveReferenceAssetMutation {
            val descriptor = openVerifiedOwnedFile(
                path = path,
                identity = identity,
                expectedByteCount = null,
                flags = OsConstants.O_WRONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            )
            syncAndClose(descriptor)
        }

    override fun ownedRegularByteCount(
        path: Path,
        identity: ReferenceAssetFileIdentity,
    ): Long? = withExclusiveReferenceAssetMutation {
        requireOwnedRegularFile(path, identity, expectedByteCount = null).st_size
    }

    override fun replaceOwnedReservation(
        sourcePath: Path,
        sourceIdentity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
        destinationPath: Path,
        reservationIdentity: ReferenceAssetFileIdentity,
    ) = withExclusiveReferenceAssetMutation {
        requireOwnedRegularFile(sourcePath, sourceIdentity, expectedByteCount)
        requireOwnedRegularFile(destinationPath, reservationIdentity, 0L)
        try {
            Os.rename(sourcePath.toString(), destinationPath.toString())
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
            Os.remove(path.toString())
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
    }

    override fun syncDirectory(directory: Path) = withExclusiveReferenceAssetMutation {
        val before = try {
            Os.lstat(directory.toString())
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
        if (!OsConstants.S_ISDIR(before.st_mode)) throw ReferenceAssetOperationFailed()
        val descriptor = try {
            Os.open(
                directory.toString(),
                OsConstants.O_RDONLY or
                    OsConstants.O_CLOEXEC or
                    OsConstants.O_NOFOLLOW,
                0,
            )
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
        try {
            val opened = Os.fstat(descriptor)
            if (!OsConstants.S_ISDIR(opened.st_mode) || !sameIdentity(before, opened)) {
                throw ReferenceAssetOperationFailed()
            }
            Os.fsync(descriptor)
        } catch (_: Exception) {
            closeQuietly(descriptor)
            throw ReferenceAssetOperationFailed()
        }
        try {
            Os.close(descriptor)
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
    }

    override fun observeRegularFile(path: Path): ReferenceAssetFileObservation =
        withExclusiveReferenceAssetMutation {
            val stat = try {
                Os.lstat(path.toString())
            } catch (failure: ErrnoException) {
                if (failure.errno == OsConstants.ENOENT) {
                    return@withExclusiveReferenceAssetMutation ReferenceAssetFileObservation.Absent
                }
                throw ReferenceAssetOperationFailed()
            } catch (_: Exception) {
                throw ReferenceAssetOperationFailed()
            }
            if (!OsConstants.S_ISREG(stat.st_mode)) throw ReferenceAssetOwnershipMismatch()
            ReferenceAssetFileObservation.Regular(
                identity = AndroidReferenceAssetFileIdentity(stat.st_dev, stat.st_ino),
                byteCount = stat.st_size,
            )
        }

    override fun sha256OwnedFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long,
    ): String = withExclusiveReferenceAssetMutation {
        val descriptor = openVerifiedOwnedFile(
            path = path,
            identity = identity,
            expectedByteCount = expectedByteCount,
            flags = OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
        )
        val digest = MessageDigest.getInstance("SHA-256")
        var observedByteCount = 0L
        try {
            FileInputStream(descriptor).use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    observedByteCount += read.toLong()
                }
            }
        } catch (_: Exception) {
            closeQuietly(descriptor)
            throw ReferenceAssetOperationFailed()
        }
        requireOwnedRegularFile(path, identity, expectedByteCount)
        if (observedByteCount != expectedByteCount) throw ReferenceAssetOwnershipMismatch()
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun openNewPrivateFile(path: Path): FileDescriptor = Os.open(
        path.toString(),
        OsConstants.O_CREAT or
            OsConstants.O_EXCL or
            OsConstants.O_WRONLY or
            OsConstants.O_CLOEXEC,
        PRIVATE_FILE_MODE,
    )

    private fun inspectCreatedFile(
        descriptor: FileDescriptor,
        sync: Boolean,
    ): AndroidReferenceAssetFileIdentity? {
        var identity: AndroidReferenceAssetFileIdentity? = null
        var failed = false
        try {
            val stat = Os.fstat(descriptor)
            if (!isRegularWithSize(stat, 0L)) {
                failed = true
            } else {
                if (sync) Os.fsync(descriptor)
                identity = AndroidReferenceAssetFileIdentity(stat.st_dev, stat.st_ino)
            }
        } catch (_: Exception) {
            failed = true
        }
        try {
            Os.close(descriptor)
        } catch (_: Exception) {
            failed = true
        }
        return if (failed) null else identity
    }

    private fun openVerifiedOwnedFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long?,
        flags: Int,
    ): FileDescriptor {
        val expected = identity as? AndroidReferenceAssetFileIdentity
            ?: throw ReferenceAssetOwnershipMismatch()
        val before = requireOwnedRegularFile(path, expected, expectedByteCount)
        val descriptor = try {
            Os.open(path.toString(), flags, 0)
        } catch (_: Exception) {
            throw ReferenceAssetOwnershipMismatch()
        }
        try {
            val opened = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(opened.st_mode) ||
                !sameIdentity(before, opened) ||
                opened.st_dev != expected.device ||
                opened.st_ino != expected.inode ||
                (expectedByteCount != null && opened.st_size != expectedByteCount)
            ) {
                throw ReferenceAssetOwnershipMismatch()
            }
        } catch (failure: ReferenceAssetOwnershipMismatch) {
            closeQuietly(descriptor)
            throw failure
        } catch (_: Exception) {
            closeQuietly(descriptor)
            throw ReferenceAssetOperationFailed()
        }
        return descriptor
    }

    private fun requireOwnedRegularFile(
        path: Path,
        identity: ReferenceAssetFileIdentity,
        expectedByteCount: Long?,
    ): StructStat {
        val expected = identity as? AndroidReferenceAssetFileIdentity
            ?: throw ReferenceAssetOwnershipMismatch()
        val stat = try {
            Os.lstat(path.toString())
        } catch (_: Exception) {
            throw ReferenceAssetOwnershipMismatch()
        }
        if (!OsConstants.S_ISREG(stat.st_mode) ||
            stat.st_dev != expected.device ||
            stat.st_ino != expected.inode ||
            (expectedByteCount != null && stat.st_size != expectedByteCount)
        ) {
            throw ReferenceAssetOwnershipMismatch()
        }
        return stat
    }

    private fun syncAndClose(descriptor: FileDescriptor) {
        var failed = false
        try {
            Os.fsync(descriptor)
        } catch (_: Exception) {
            failed = true
        }
        try {
            Os.close(descriptor)
        } catch (_: Exception) {
            failed = true
        }
        if (failed) throw ReferenceAssetOperationFailed()
    }

    private fun closeQuietly(descriptor: FileDescriptor) {
        try {
            Os.close(descriptor)
        } catch (_: Exception) {
            // The caller emits only a closed internal operation or ownership failure.
        }
    }

    private fun ensureDirectory(path: Path) {
        try {
            Os.mkdir(path.toString(), PRIVATE_DIRECTORY_MODE)
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.EEXIST) throw ReferenceAssetOperationFailed()
        }
        val stat = try {
            Os.lstat(path.toString())
        } catch (_: Exception) {
            throw ReferenceAssetOperationFailed()
        }
        if (!OsConstants.S_ISDIR(stat.st_mode)) throw ReferenceAssetOperationFailed()
    }

    private fun confinedChild(parent: Path, fileName: String): Path {
        val child = parent.resolve(fileName).normalize()
        if (child.parent != parent) throw ReferenceAssetOperationFailed()
        return child
    }

    private fun isRegularWithSize(stat: StructStat, expectedByteCount: Long): Boolean =
        OsConstants.S_ISREG(stat.st_mode) && stat.st_size == expectedByteCount

    private fun sameIdentity(first: StructStat, second: StructStat): Boolean =
        first.st_dev == second.st_dev && first.st_ino == second.st_ino

    private const val HASH_BUFFER_BYTES = 8 * 1024
    private const val PRIVATE_FILE_MODE = 0x180 // 0600
    private const val PRIVATE_DIRECTORY_MODE = 0x1c0 // 0700
}
