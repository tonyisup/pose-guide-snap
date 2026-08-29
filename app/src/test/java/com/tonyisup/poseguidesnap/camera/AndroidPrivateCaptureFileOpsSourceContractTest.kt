package com.tonyisup.poseguidesnap.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPrivateCaptureFileOpsSourceContractTest {
    @Test
    fun androidAdapterUsesOwnedReservationIdentityAtomicRenameAndVerifiedUnlink() {
        val source = productionSource()

        listOf(
            "OsConstants.O_CREAT",
            "OsConstants.O_EXCL",
            "OsConstants.O_WRONLY",
            "OsConstants.O_CLOEXEC",
            "Os.fstat(descriptor)",
            "Os.lstat(path.toString())",
            "OsConstants.S_ISREG",
            "stat.st_size == 0L",
            "Os.rename(tempPath.toString(), finalPath.toString())",
            "Os.remove(finalPath.toString())",
            "Os.fsync(descriptor)",
            "Os.close(descriptor)",
            "withExclusiveMutation",
            "catch (failure: ErrnoException)",
            "failure.errno == OsConstants.EEXIST",
            "PrivateCaptureReservationConflict",
            "PrivateCaptureReservationMayExist",
        ).forEach { marker ->
            assertTrue("Missing Android publisher marker: $marker", marker in source)
        }
        listOf(
            "Files.createLink",
            "Os.link",
            "createLink(",
            "Files.move",
            "REPLACE_EXISTING",
            "renameTo",
            "finalPath.toFile().delete",
            "android.util.Log",
            "System.currentTimeMillis",
            "System.nanoTime",
            "java.net",
            "System.loadLibrary",
            "external fun",
            "renameat2",
        ).forEach { forbidden ->
            assertFalse("Forbidden Android publisher marker: $forbidden", forbidden in source)
        }
        assertEquals(1, Regex("Os\\.rename\\(").findAll(source).count())
        assertEquals(1, Regex("Os\\.remove\\(finalPath\\.toString\\(\\)\\)").findAll(source).count())
        val replaceStart = source.indexOf("override fun replaceOwnedReservation")
        val deleteStart = source.indexOf("override fun deleteOwnedReservation")
        assertTrue(replaceStart >= 0)
        assertTrue(deleteStart > replaceStart)
        val replaceMethod = source.substring(replaceStart, deleteStart)
        assertTrue("Os.rename(" in replaceMethod)
        assertTrue(replaceMethod.indexOf("requireOwnedZeroByteReservation") < replaceMethod.indexOf("Os.rename("))
        assertTrue(replaceMethod.indexOf("withExclusiveMutation") < replaceMethod.indexOf("requireOwnedZeroByteReservation"))
        val reserveStart = source.indexOf("override fun reserve")
        val createStart = source.indexOf("override fun createNew")
        assertTrue(reserveStart >= 0)
        assertTrue(createStart > reserveStart)
        val reserveMethod = source.substring(reserveStart, createStart)
        assertTrue(reserveMethod.indexOf("withExclusiveMutation") < reserveMethod.indexOf("Os.open("))
        assertTrue(reserveMethod.indexOf("created = true") < reserveMethod.indexOf("Os.fstat(descriptor)"))
        val deleteMethod = source.substring(deleteStart, source.indexOf("override fun syncDirectory"))
        assertTrue(deleteMethod.indexOf("withExclusiveMutation") < deleteMethod.indexOf("requireOwnedZeroByteReservation"))
    }

    private fun productionSource(): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
        return root.resolve(SOURCE_PATH).readText()
    }

    private companion object {
        const val SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/camera/AndroidPrivateCaptureFileOps.kt"
    }
}
