package com.tonyisup.poseguidesnap.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Passing app-private filesystem acceptance without camera or private media. */
@RunWith(AndroidJUnit4::class)
class PrivateCaptureFilePublisherAndroidTest {
    @Test
    fun generatedBytesPublishByOwnedReservationRenameAndRepeatedTokenCannotClobber() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.noBackupFilesDir.resolve("publisher-reservation-acceptance").apply {
            deleteRecursively()
            assertTrue(mkdirs())
        }
        val identity = PrivateOutputIdentity(CaptureToken("android-reservation-publisher-v1"), 0)
        val bytes = "generated-non-image-reservation-bytes".toByteArray(StandardCharsets.UTF_8)
        val publisher = androidPrivateCaptureFilePublisher(root)
        val prepared = publisher.prepare(identity)
        var published: PublishedPrivateOutput? = null

        try {
            prepared.tempFile.writeBytes(bytes)
            published = try {
                prepared.publish()
            } catch (failure: PrivateCapturePublicationFailed) {
                fail("Android publisher failed at safe stage ${failure.stage}")
                null
            } catch (failure: PrivateCaptureReconciliationRequired) {
                fail(
                    "Android publisher requires reconciliation: " +
                        "replaced=${failure.reservationReplacedByCandidate}, " +
                        "directorySynced=${failure.directorySyncedAfterPublication}",
                )
                null
            }

            val exact = requireNotNull(published)
            assertTrue(exact.finalFile.isFile)
            assertArrayEquals(bytes, exact.finalFile.readBytes())
            assertFalse(prepared.tempFile.exists())
            prepared.close()
            assertArrayEquals(bytes, exact.finalFile.readBytes())

            val collision = try {
                publisher.prepare(identity)
                null
            } catch (failure: PrivateCaptureReconciliationRequired) {
                failure
            }
            assertFalse(requireNotNull(collision).reservationReplacedByCandidate)
            assertFalse(collision.directorySyncedAfterPublication)
            assertArrayEquals(bytes, exact.finalFile.readBytes())
            assertFalse(root.resolve(".${exact.finalFile.name}.pending").exists())
        } finally {
            try {
                prepared.close()
            } catch (_: PrivateCaptureFilePublisherException) {
                // The assertion path above retains the causal safe stage.
            }
            published?.finalFile?.delete()
            root.delete()
        }
    }

    @Test
    fun preexistingForeignFinalBlocksReservationAndRemainsByteExact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.noBackupFilesDir.resolve("publisher-foreign-final-acceptance").apply {
            deleteRecursively()
            assertTrue(mkdirs())
        }
        val identity = PrivateOutputIdentity(CaptureToken("android-foreign-final-v1"), 1)
        val foreignBytes = "foreign-final-exact".toByteArray(StandardCharsets.UTF_8)
        val final = root.resolve(finalName(identity)).apply { writeBytes(foreignBytes) }

        try {
            val prepared = try {
                androidPrivateCaptureFilePublisher(root).prepare(identity)
            } catch (failure: PrivateCaptureReconciliationRequired) {
                assertFalse(failure.reservationReplacedByCandidate)
                assertFalse(failure.directorySyncedAfterPublication)
                null
            }
            assertNull(prepared)
            assertArrayEquals(foreignBytes, final.readBytes())
            assertFalse(root.resolve(".${final.name}.pending").exists())
        } finally {
            final.delete()
            root.delete()
        }
    }

    private fun finalName(identity: PrivateOutputIdentity): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.token.value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$digest-${identity.ordinal}.jpg"
    }
}
