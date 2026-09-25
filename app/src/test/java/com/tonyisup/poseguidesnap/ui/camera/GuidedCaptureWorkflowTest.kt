package com.tonyisup.poseguidesnap.ui.camera

import com.tonyisup.poseguidesnap.camera.JournaledStillCaptureWriter
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedCaptureWorkflowTest {
    @Test
    fun resourceCloseWaitsForEveryLeaseAndRunsExactlyOnce() {
        var closeCount = 0
        val authority = GuidedCaptureResourceAuthority { closeCount += 1 }
        val first = requireNotNull(authority.tryAcquire())
        val second = requireNotNull(authority.tryAcquire())

        authority.close()
        authority.close()
        assertEquals(0, closeCount)
        assertNull(authority.tryAcquire())

        first.close()
        first.close()
        assertEquals(0, closeCount)
        second.close()
        second.close()
        assertEquals(1, closeCount)
    }

    @Test
    fun attachableWriterForwardsOnlyToTheExactCurrentlyAttachedWriter() {
        val temp = Files.createTempFile("attachable-capture-writer", ".jpg").toFile()
        try {
            val attachable = AttachableJournaledStillCaptureWriter()
            val first = RecordingWriter()
            val second = RecordingWriter()
            val missing = RecordingCallback()

            attachable.write(temp, missing)
            assertEquals(1, missing.errorCount)

            attachable.attach(first)
            attachable.detach(second)
            val forwarded = RecordingCallback()
            attachable.write(temp, forwarded)
            assertEquals(1, first.writeCount)
            assertEquals(1, forwarded.savedCount)

            attachable.attach(second)
            attachable.detach(first)
            attachable.write(temp, forwarded)
            assertEquals(1, first.writeCount)
            assertEquals(1, second.writeCount)
            assertEquals(2, forwarded.savedCount)

            attachable.detach(second)
            attachable.write(temp, forwarded)
            assertEquals(1, forwarded.errorCount)
        } finally {
            assertTrue(temp.delete() || !temp.exists())
            assertFalse(temp.exists())
        }
    }

    private class RecordingWriter : JournaledStillCaptureWriter {
        var writeCount = 0

        override fun write(tempFile: File, callback: JournaledStillCaptureWriter.Callback) {
            writeCount += 1
            callback.onImageSaved()
        }
    }

    private class RecordingCallback : JournaledStillCaptureWriter.Callback {
        var savedCount = 0
        var errorCount = 0

        override fun onImageSaved() {
            savedCount += 1
        }

        override fun onError() {
            errorCount += 1
        }
    }
}
