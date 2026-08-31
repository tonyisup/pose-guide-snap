package com.tonyisup.poseguidesnap.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidReferenceAssetFileOpsSourceContractTest {
    @Test
    fun productionConstructorSelectsLibcAdapterWithExactOwnedPublicationPrimitives() {
        val adapter = productionSource(ADAPTER_SOURCE_PATH)
        val store = productionSource(STORE_SOURCE_PATH)
        val constructor = store
            .substringAfter("constructor(noBackupFilesDirectory: File) : this(")
            .substringBefore(")\n\n    fun publish")

        assertTrue("Production File constructor must select Android file ops", "AndroidReferenceAssetFileOps" in constructor)
        assertFalse("Production File constructor must not fall back to NIO", "NioReferenceAssetFileOps" in constructor)

        listOf(
            "internal object AndroidReferenceAssetFileOps : ReferenceAssetFileOps",
            "OsConstants.O_CREAT",
            "OsConstants.O_EXCL",
            "OsConstants.O_WRONLY",
            "OsConstants.O_CLOEXEC",
            "OsConstants.O_NOFOLLOW",
            "PRIVATE_FILE_MODE",
            "0x180",
            "Os.fstat(descriptor)",
            "Os.lstat(path.toString())",
            "stat.st_dev",
            "stat.st_ino",
            "OsConstants.S_ISREG(stat.st_mode)",
            "stat.st_size",
            "Os.fsync(descriptor)",
            "Os.rename(sourcePath.toString(), destinationPath.toString())",
            "Os.remove(path.toString())",
            "withExclusiveReferenceAssetMutation",
            "ReferenceAssetOwnershipMismatch",
            "ReferenceAssetOperationFailed",
        ).forEach { marker ->
            assertTrue("Missing Android reference-asset marker: $marker", marker in adapter)
        }

        listOf(
            "Files.createLink",
            "Os.link",
            "createLink(",
            "Files.move",
            "REPLACE_EXISTING",
            "renameTo(",
            "NioReferenceAssetFileOps",
            "BasicFileAttributes",
            ".fileKey()",
            "FileChannel",
        ).forEach { forbidden ->
            assertFalse("Forbidden Android reference-asset marker: $forbidden", forbidden in adapter)
        }

        assertEquals(1, Regex("Os\\.rename\\(").findAll(adapter).count())
        assertEquals(1, Regex("Os\\.remove\\(path\\.toString\\(\\)\\)").findAll(adapter).count())

        val replace = adapter
            .substringAfter("override fun replaceOwnedReservation(")
            .substringBefore("override fun deleteOwnedFile(")
        assertOrdered(
            replace,
            "withExclusiveReferenceAssetMutation",
            "requireOwnedRegularFile(sourcePath, sourceIdentity, expectedByteCount)",
            "requireOwnedRegularFile(destinationPath, reservationIdentity, 0L)",
            "Os.rename(sourcePath.toString(), destinationPath.toString())",
        )

        val delete = adapter
            .substringAfter("override fun deleteOwnedFile(")
            .substringBefore("override fun syncDirectory(")
        assertOrdered(
            delete,
            "withExclusiveReferenceAssetMutation",
            "requireOwnedRegularFile(path, identity, expectedByteCount)",
            "Os.remove(path.toString())",
        )
    }

    private fun productionSource(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
        return root.resolve(relativePath).readText()
    }

    private fun assertOrdered(source: String, vararg markers: String) {
        var previous = -1
        markers.forEach { marker ->
            val current = source.indexOf(marker)
            assertTrue("Missing ordered marker: $marker", current >= 0)
            assertTrue("Out-of-order marker: $marker", current > previous)
            previous = current
        }
    }

    private companion object {
        const val ADAPTER_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/data/AndroidReferenceAssetFileOps.kt"
        const val STORE_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/data/ReferenceAssetStore.kt"
    }
}
