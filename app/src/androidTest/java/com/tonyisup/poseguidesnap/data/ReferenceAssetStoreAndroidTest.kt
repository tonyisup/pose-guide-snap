package com.tonyisup.poseguidesnap.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Compile-only generated-byte acceptance; it reads no provider, user image, camera, or production path. */
@RunWith(AndroidJUnit4::class)
class ReferenceAssetStoreAndroidTest {
    @Test
    fun generatedBytesPublishExactlyCollisionCannotClobberAndCleanupLeavesNoFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.noBackupFilesDir
            .resolve("reference-asset-acceptance-${UUID.randomUUID()}")
            .apply { assertTrue(mkdirs()) }
        val rawToken = "generated-reference-${UUID.randomUUID()}"
        val identity = ReferenceAssetIdentity(ReferenceImportToken(rawToken))
        val bytes = ByteArray(4097) { index -> ((index * 31 + 17) and 0xff).toByte() }
        val store = ReferenceAssetStore(root)
        var finalFile = root

        try {
            val published = store.publish(identity, source(bytes))
            finalFile = root.resolve(published.safeRelativePath)
            assertTrue(finalFile.isFile)
            assertArrayEquals(bytes, finalFile.readBytes())

            assertThrows(ReferenceAssetReconciliationRequired::class.java) {
                store.publish(identity, source(byteArrayOf(9, 8, 7)))
            }
            assertArrayEquals(bytes, finalFile.readBytes())
            assertFalse(
                requireNotNull(finalFile.parentFile)
                    .resolve(".${finalFile.name}.pending")
                    .exists(),
            )

            assertSame(ReferenceAssetCleanupResult.Cleaned, store.cleanup(published))
            assertFalse(finalFile.exists())
            assertTrue(root.walkTopDown().none(File::isFile))
        } finally {
            root.deleteRecursively()
            assertFalse(root.exists())
        }
    }

    @Test
    fun generatedRestartFilesReconcileWithNewStoresWithoutClobberOrResidue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.noBackupFilesDir
            .resolve("reference-asset-restart-${UUID.randomUUID()}")
            .apply { assertTrue(mkdirs()) }
        val assets = root.resolve("reference-assets/assets").apply { assertTrue(mkdirs()) }
        val quarantine = root.resolve("reference-assets/quarantine").apply { assertTrue(mkdirs()) }

        try {
            val zeroToken = ReferenceImportToken("generated-zero-${UUID.randomUUID()}")
            val zeroFinal = finalFile(assets, zeroToken)
            val zeroTemp = tempFile(assets, zeroToken)
            assertTrue(zeroFinal.createNewFile())
            assertTrue(zeroTemp.createNewFile())
            assertSame(
                ReferenceAssetCleanupResult.Cleaned,
                ReferenceAssetStore(root).reconcilePending(
                    ReferenceAssetIdentity(zeroToken),
                    ReferenceAssetPendingLifecycle.PREPARING,
                ),
            )
            assertFalse(zeroFinal.exists())
            assertFalse(zeroTemp.exists())

            val pendingToken = ReferenceImportToken("generated-pending-${UUID.randomUUID()}")
            val pendingBytes = ByteArray(73) { index -> (index * 7).toByte() }
            val pendingTemp = tempFile(assets, pendingToken).apply { writeBytes(pendingBytes) }
            val pendingResult = ReferenceAssetStore(root).reconcilePending(
                ReferenceAssetIdentity(pendingToken),
                ReferenceAssetPendingLifecycle.PREPARING,
            )
            assertTrue(pendingResult is ReferenceAssetCleanupResult.Quarantined)
            pendingResult as ReferenceAssetCleanupResult.Quarantined
            val pendingQuarantine = root.resolve(pendingResult.safeRelativePath)
            assertArrayEquals(pendingBytes, pendingQuarantine.readBytes())
            assertFalse(pendingTemp.exists())
            assertTrue(pendingQuarantine.delete())

            val collisionToken = ReferenceImportToken("generated-collision-${UUID.randomUUID()}")
            val collisionPendingBytes = "generated-pending".toByteArray()
            val collisionForeignBytes = "generated-foreign-quarantine".toByteArray()
            val collisionTemp = tempFile(assets, collisionToken).apply {
                writeBytes(collisionPendingBytes)
            }
            val collisionQuarantine = quarantine.resolve(
                "${tokenDigest(collisionToken)}.quarantined",
            ).apply { writeBytes(collisionForeignBytes) }
            assertSame(
                ReferenceAssetCleanupResult.ReconciliationRequired,
                ReferenceAssetStore(root).reconcilePending(
                    ReferenceAssetIdentity(collisionToken),
                    ReferenceAssetPendingLifecycle.PREPARING,
                ),
            )
            assertArrayEquals(collisionPendingBytes, collisionTemp.readBytes())
            assertArrayEquals(collisionForeignBytes, collisionQuarantine.readBytes())
            assertTrue(collisionTemp.delete())
            assertTrue(collisionQuarantine.delete())

            val readyToken = ReferenceImportToken("generated-ready-${UUID.randomUUID()}")
            val readyFinal = finalFile(assets, readyToken).apply {
                writeBytes("generated-ready-bytes".toByteArray())
            }
            assertSame(
                ReferenceAssetCleanupResult.Cleaned,
                ReferenceAssetStore(root).reconcilePending(
                    ReferenceAssetIdentity(readyToken),
                    ReferenceAssetPendingLifecycle.ASSET_READY,
                ),
            )
            assertFalse(readyFinal.exists())
            assertTrue(root.walkTopDown().none(File::isFile))
        } finally {
            root.deleteRecursively()
            assertFalse(root.exists())
        }
    }

    private fun finalFile(assets: File, token: ReferenceImportToken): File =
        assets.resolve("${tokenDigest(token)}.asset")

    private fun tempFile(assets: File, token: ReferenceImportToken): File =
        assets.resolve(".${tokenDigest(token)}.asset.pending")

    private fun tokenDigest(token: ReferenceImportToken): String =
        ReferenceImportAssetPath.forToken(token)
            .substringAfterLast('/')
            .removeSuffix(".asset")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun source(bytes: ByteArray): ReferenceAssetByteSource =
        ReferenceAssetByteSource { ByteArrayInputStream(bytes) }
}
