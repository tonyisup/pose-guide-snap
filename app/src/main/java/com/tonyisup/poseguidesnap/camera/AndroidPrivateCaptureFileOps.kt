package com.tonyisup.poseguidesnap.camera

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.nio.file.Path

/** Android libc-backed filesystem operations for app-private owned-reservation publication. */
internal object AndroidPrivateCaptureFileOps : PrivateCaptureFileOps {
    private data class AndroidReservationIdentity(
        val device: Long,
        val inode: Long,
    ) : PrivateCaptureReservationIdentity

    override fun reserve(finalPath: Path): PrivateCaptureReservationIdentity = withExclusiveMutation {
        var created = false
        val descriptor = try {
            Os.open(
                finalPath.toString(),
                OsConstants.O_CREAT or
                    OsConstants.O_EXCL or
                    OsConstants.O_WRONLY or
                    OsConstants.O_CLOEXEC,
                PRIVATE_FILE_MODE,
            )
        } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.EEXIST) {
                throw PrivateCaptureReservationConflict()
            }
            throw failure
        }
        created = true
        var identity: AndroidReservationIdentity? = null
        var operationFailure: Exception? = null
        try {
            val stat = Os.fstat(descriptor)
            if (!isRegularZeroByteFile(stat)) {
                throw PrivateCaptureReservationOwnershipMismatch()
            }
            Os.fsync(descriptor)
            identity = AndroidReservationIdentity(stat.st_dev, stat.st_ino)
        } catch (failure: Exception) {
            operationFailure = failure
        }
        try {
            Os.close(descriptor)
        } catch (failure: Exception) {
            if (operationFailure == null) operationFailure = failure
        }
        if (operationFailure != null && created) {
            throw PrivateCaptureReservationMayExist()
        }
        identity ?: throw PrivateCaptureReservationMayExist()
    }

    override fun createNew(path: Path) = withExclusiveMutation {
        val descriptor = Os.open(
            path.toString(),
            OsConstants.O_CREAT or
                OsConstants.O_EXCL or
                OsConstants.O_WRONLY or
                OsConstants.O_CLOEXEC,
            PRIVATE_FILE_MODE,
        )
        Os.close(descriptor)
    }

    override fun regularFileByteCount(path: Path): Long? {
        val stat = Os.lstat(path.toString())
        return if (OsConstants.S_ISREG(stat.st_mode)) stat.st_size else null
    }

    override fun syncFile(path: Path) {
        val descriptor = Os.open(
            path.toString(),
            OsConstants.O_WRONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    override fun replaceOwnedReservation(
        tempPath: Path,
        finalPath: Path,
        identity: PrivateCaptureReservationIdentity,
    ) = withExclusiveMutation {
        requireOwnedZeroByteReservation(finalPath, identity)
        Os.rename(tempPath.toString(), finalPath.toString())
    }

    override fun deleteOwnedReservation(
        finalPath: Path,
        identity: PrivateCaptureReservationIdentity,
    ) = withExclusiveMutation {
        requireOwnedZeroByteReservation(finalPath, identity)
        Os.remove(finalPath.toString())
    }

    override fun syncDirectory(directory: Path) {
        val descriptor = Os.open(
            directory.toString(),
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    override fun deleteIfExists(path: Path): Boolean = withExclusiveMutation {
        try {
            Os.remove(path.toString())
            true
        } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.ENOENT) false else throw failure
        }
    }

    private fun requireOwnedZeroByteReservation(
        path: Path,
        identity: PrivateCaptureReservationIdentity,
    ) {
        val expected = identity as? AndroidReservationIdentity
            ?: throw PrivateCaptureReservationOwnershipMismatch()
        val stat = try {
            Os.lstat(path.toString())
        } catch (_: Exception) {
            throw PrivateCaptureReservationOwnershipMismatch()
        }
        val isOwnedReservation = isRegularZeroByteFile(stat) &&
            stat.st_dev == expected.device &&
            stat.st_ino == expected.inode
        if (!isOwnedReservation) throw PrivateCaptureReservationOwnershipMismatch()
    }

    private fun isRegularZeroByteFile(stat: StructStat): Boolean =
        OsConstants.S_ISREG(stat.st_mode) && stat.st_size == 0L

    private const val PRIVATE_FILE_MODE = 0x180 // 0600
}

internal fun androidPrivateCaptureFilePublisher(rootDirectory: File): PrivateCaptureFilePublisher =
    PrivateCaptureFilePublisher(
        rootDirectory = rootDirectory,
        fileOps = AndroidPrivateCaptureFileOps,
    )
