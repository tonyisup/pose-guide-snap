package com.tonyisup.poseguidesnap.importer

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.ClaimReferenceAssetFilesResult
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetStore
import com.tonyisup.poseguidesnap.data.ReferenceAssetByteSource
import com.tonyisup.poseguidesnap.data.ReferenceAssetIdentity
import com.tonyisup.poseguidesnap.data.ReferenceImportAssetPath
import com.tonyisup.poseguidesnap.data.ReferenceImportFileAdvanceRequest
import com.tonyisup.poseguidesnap.data.ReferenceImportFileJournalResult
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationSnapshot
import com.tonyisup.poseguidesnap.data.ReferenceImportFileOperationStage
import com.tonyisup.poseguidesnap.data.ReferenceImportReservation
import com.tonyisup.poseguidesnap.data.ReferenceImportReserveResult
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import com.tonyisup.poseguidesnap.data.RoomReferenceImportFileJournal
import com.tonyisup.poseguidesnap.data.RoomReferenceImportRepository
import com.tonyisup.poseguidesnap.data.WriteAndSyncTempResult
import com.tonyisup.poseguidesnap.data.deleteRoomTestDatabase
import com.tonyisup.poseguidesnap.data.roomTestDatabaseResidue
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Compile-only until the explicit Pixel 6 gate; all bytes and database names are generated. */
@RunWith(AndroidJUnit4::class)
class ReferenceImportStartupReconcilerAndroidTest {
    @Test
    fun forceCloseReopenResumesDirtyCleanupAndSyncedQuarantineFromRoomLedger() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "reference_import_recovery_android_test_${UUID.randomUUID()}.db"
        val root = context.noBackupFilesDir.resolve("reference-import-recovery-${UUID.randomUUID()}")
        context.deleteRoomTestDatabase(databaseName)
        assertTrue(root.mkdirs())
        var database: AppDatabase? = null

        try {
            database = AppDatabase.create(context, databaseName)
            val sqlite = database.openHelper.writableDatabase
            seedShoot(sqlite, DIRTY_SHOOT)
            seedShoot(sqlite, SYNCED_SHOOT)
            seedShoot(sqlite, RENAMED_SHOOT)
            val repository = RoomReferenceImportRepository(database)
            val journal = RoomReferenceImportFileJournal(database)
            val store = JournaledReferenceAssetStore(root)

            val dirty = reservation(DIRTY_TOKEN, DIRTY_SHOOT, "pose-dirty")
            val synced = reservation(SYNCED_TOKEN, SYNCED_SHOOT, "pose-synced")
            val renamed = reservation(RENAMED_TOKEN, RENAMED_SHOOT, "pose-renamed")
            assertEquals(ReferenceImportReserveResult.Reserved, repository.reserveImport(dirty, 10L))
            assertEquals(ReferenceImportReserveResult.Reserved, repository.reserveImport(synced, 20L))
            assertEquals(ReferenceImportReserveResult.Reserved, repository.reserveImport(renamed, 30L))

            val dirtyIdentity = ReferenceAssetIdentity(dirty.importToken)
            assertEquals(
                ClaimReferenceAssetFilesResult.Claimed,
                store.claimReservationAndTemp(dirtyIdentity),
            )
            val dirtyWriting = advance(
                journal,
                requireNotNull(journal.snapshot(dirty.importToken)),
                ReferenceImportFileOperationStage.WRITING_TEMP,
                11L,
            )
            val dirtyWrite = store.writeAndSyncClaimedTemp(
                dirtyIdentity,
                ReferenceAssetByteSource { failingAfterOneByteSource() },
            )
            assertTrue(dirtyWrite is WriteAndSyncTempResult.Failure)
            dirtyWrite as WriteAndSyncTempResult.Failure
            assertTrue(dirtyWrite.cleanupRequired)
            assertEquals(ReferenceImportFileOperationStage.WRITING_TEMP, dirtyWriting.stage)

            val syncedIdentity = ReferenceAssetIdentity(synced.importToken)
            assertEquals(
                ClaimReferenceAssetFilesResult.Claimed,
                store.claimReservationAndTemp(syncedIdentity),
            )
            val syncedWriting = advance(
                journal,
                requireNotNull(journal.snapshot(synced.importToken)),
                ReferenceImportFileOperationStage.WRITING_TEMP,
                21L,
            )
            val syncedWrite = store.writeAndSyncClaimedTemp(
                syncedIdentity,
                ReferenceAssetByteSource { ByteArrayInputStream(SYNCED_BYTES) },
            )
            assertTrue(syncedWrite is WriteAndSyncTempResult.TempSynced)
            syncedWrite as WriteAndSyncTempResult.TempSynced
            advance(
                journal,
                syncedWriting,
                ReferenceImportFileOperationStage.TEMP_SYNCED,
                22L,
                syncedWrite.evidence.byteCount,
                syncedWrite.evidence.sha256,
            )

            val renamedIdentity = ReferenceAssetIdentity(renamed.importToken)
            assertEquals(
                ClaimReferenceAssetFilesResult.Claimed,
                store.claimReservationAndTemp(renamedIdentity),
            )
            val renamedWriting = advance(
                journal,
                requireNotNull(journal.snapshot(renamed.importToken)),
                ReferenceImportFileOperationStage.WRITING_TEMP,
                31L,
            )
            val renamedWrite = store.writeAndSyncClaimedTemp(
                renamedIdentity,
                ReferenceAssetByteSource { ByteArrayInputStream(RENAMED_BYTES) },
            ) as WriteAndSyncTempResult.TempSynced
            advance(
                journal,
                renamedWriting,
                ReferenceImportFileOperationStage.TEMP_SYNCED,
                32L,
                renamedWrite.evidence.byteCount,
                renamedWrite.evidence.sha256,
            )
            assertEquals(
                com.tonyisup.poseguidesnap.data.RenameSyncedTempResult.Renamed,
                store.renameSyncedTemp(renamedIdentity, renamedWrite.evidence),
            )
            assertEquals(
                ReferenceImportFileOperationStage.TEMP_SYNCED,
                journal.snapshot(renamed.importToken)?.stage,
            )

            database.close()
            database = null

            database = AppDatabase.create(context, databaseName)
            val reopenedRepository = RoomReferenceImportRepository(database)
            val reopenedJournal = RoomReferenceImportFileJournal(database)
            val report = ReferenceImportStartupReconciler(
                authority = RoomReferenceImportRecoveryAuthorityAdapter(reopenedRepository),
                journal = RoomReferenceImportRecoveryJournalAdapter(reopenedJournal),
                assets = JournaledReferenceAssetRecoveryAdapter(JournaledReferenceAssetStore(root)),
            ).reconcile { recoveryTimeline() }

            assertEquals(3, report.examinedCount)
            assertEquals(1, report.cleanedCount)
            assertEquals(2, report.quarantinedCount)
            assertEquals(0, report.outstandingCount)
            assertEquals(0, report.settlementFailureCount)
            assertEquals("REJECTED_CLEANED", intentState(database.openHelper.writableDatabase, DIRTY_TOKEN))
            assertEquals("REJECTED_QUARANTINED", intentState(database.openHelper.writableDatabase, SYNCED_TOKEN))
            assertEquals("REJECTED_QUARANTINED", intentState(database.openHelper.writableDatabase, RENAMED_TOKEN))
            assertFalse(fileFor(root, dirty.importToken, "asset").exists())
            assertFalse(fileFor(root, dirty.importToken, "temp").exists())
            assertFalse(fileFor(root, dirty.importToken, "quarantine").exists())
            assertFalse(fileFor(root, synced.importToken, "asset").exists())
            assertFalse(fileFor(root, synced.importToken, "temp").exists())
            assertTrue(fileFor(root, synced.importToken, "quarantine").isFile)
            assertFalse(fileFor(root, renamed.importToken, "asset").exists())
            assertFalse(fileFor(root, renamed.importToken, "temp").exists())
            assertTrue(fileFor(root, renamed.importToken, "quarantine").isFile)

            database.close()
            database = null
            database = AppDatabase.create(context, databaseName)
            val secondReport = ReferenceImportStartupReconciler(
                authority = RoomReferenceImportRecoveryAuthorityAdapter(
                    RoomReferenceImportRepository(database),
                ),
                journal = RoomReferenceImportRecoveryJournalAdapter(
                    RoomReferenceImportFileJournal(database),
                ),
                assets = JournaledReferenceAssetRecoveryAdapter(JournaledReferenceAssetStore(root)),
            ).reconcile { secondRecoveryTimeline() }
            assertEquals(0, secondReport.examinedCount)
        } finally {
            database?.close()
            context.deleteRoomTestDatabase(databaseName)
            root.deleteRecursively()
            assertFalse(context.databaseList().contains(databaseName))
            assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
            assertFalse(root.exists())
        }
    }

    private fun advance(
        journal: RoomReferenceImportFileJournal,
        source: ReferenceImportFileOperationSnapshot,
        target: ReferenceImportFileOperationStage,
        atEpochMillis: Long,
        byteCount: Long? = null,
        sha256: String? = null,
    ): ReferenceImportFileOperationSnapshot {
        val result = journal.advance(
            ReferenceImportFileAdvanceRequest(
                importToken = source.importToken,
                expectedStage = source.stage,
                expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
                targetStage = target,
                byteCount = byteCount,
                sha256 = sha256,
                transitionedAtEpochMillis = atEpochMillis,
            ),
        )
        assertTrue(result is ReferenceImportFileJournalResult.Applied)
        return (result as ReferenceImportFileJournalResult.Applied).snapshot
    }

    private fun reservation(
        tokenValue: String,
        shootId: String,
        poseId: String,
    ): ReferenceImportReservation {
        val token = ReferenceImportToken(tokenValue)
        return ReferenceImportReservation(
            importToken = token,
            shootId = shootId,
            poseId = poseId,
            poseIndex = 0,
            relativeAssetPath = ReferenceImportAssetPath.forToken(token),
        )
    }

    private fun seedShoot(sqlite: SupportSQLiteDatabase, shootId: String) {
        sqlite.execSQL(
            "INSERT INTO shoots (shoot_id, name, created_at_epoch_millis, " +
                "updated_at_epoch_millis, lifecycle_state, deletion_generation) " +
                "VALUES (?, 'Recovery test', 1, 1, 'ACTIVE', 0)",
            arrayOf<Any>(shootId),
        )
    }

    private fun intentState(sqlite: SupportSQLiteDatabase, token: String): String =
        sqlite.query(
            "SELECT lifecycle_state FROM reference_import_intents WHERE import_token = ?",
            arrayOf(token),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun fileFor(root: java.io.File, token: ReferenceImportToken, kind: String): java.io.File {
        val assetName = ReferenceImportAssetPath.forToken(token).substringAfterLast('/')
        return when (kind) {
            "asset" -> root.resolve("reference-assets/assets/$assetName")
            "temp" -> root.resolve("reference-assets/assets/.$assetName.pending")
            "quarantine" -> root.resolve(
                "reference-assets/quarantine/${assetName.removeSuffix(".asset")}.quarantined",
            )
            else -> error("unknown generated test file kind")
        }
    }

    private fun failingAfterOneByteSource(): InputStream = object : InputStream() {
        private var first = true

        override fun read(): Int = error("bulk read expected")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!first) throw IOException("generated source failure")
            first = false
            buffer[offset] = 0x2a
            return 1
        }
    }

    private fun recoveryTimeline() = ReferenceImportRecoveryTimeline(
        cleanupRequiredAtEpochMillis = 101L,
        cleanupPendingSyncAtEpochMillis = 102L,
        cleanedDurableAtEpochMillis = 103L,
        quarantineRequiredAtEpochMillis = 104L,
        quarantinePendingSyncAtEpochMillis = 105L,
        quarantineDurableAtEpochMillis = 106L,
        reconciliationMarkedAtEpochMillis = 107L,
        logicalSettlementAtEpochMillis = 108L,
    )

    private fun secondRecoveryTimeline() = ReferenceImportRecoveryTimeline(
        cleanupRequiredAtEpochMillis = 201L,
        cleanupPendingSyncAtEpochMillis = 202L,
        cleanedDurableAtEpochMillis = 203L,
        quarantineRequiredAtEpochMillis = 204L,
        quarantinePendingSyncAtEpochMillis = 205L,
        quarantineDurableAtEpochMillis = 206L,
        reconciliationMarkedAtEpochMillis = 207L,
        logicalSettlementAtEpochMillis = 208L,
    )

    private companion object {
        const val DIRTY_TOKEN = "recovery-dirty-token"
        const val SYNCED_TOKEN = "recovery-synced-token"
        const val RENAMED_TOKEN = "recovery-renamed-token"
        const val DIRTY_SHOOT = "recovery-dirty-shoot"
        const val SYNCED_SHOOT = "recovery-synced-shoot"
        const val RENAMED_SHOOT = "recovery-renamed-shoot"
        val SYNCED_BYTES = "generated-synced-private-bytes".toByteArray()
        val RENAMED_BYTES = "generated-renamed-private-bytes".toByteArray()
    }
}
