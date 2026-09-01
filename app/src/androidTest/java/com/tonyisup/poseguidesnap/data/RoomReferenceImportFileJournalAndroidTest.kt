package com.tonyisup.poseguidesnap.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomReferenceImportFileJournalAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "reference_import_file_journal_${UUID.randomUUID()}.db"
        context.deleteRoomTestDatabase(databaseName)
        database = AppDatabase.create(context, databaseName)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteRoomTestDatabase(databaseName)
        assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
    }

    @Test
    fun roomJournalPermitsEveryAndOnlyTheClosedTransitionMatrix() {
        val legalEdges = setOf(
            edge("EXPECTING_RESERVATION", "WRITING_TEMP"),
            edge("WRITING_TEMP", "TEMP_SYNCED"),
            edge("TEMP_SYNCED", "FINAL_RENAME_PENDING_SYNC"),
            edge("FINAL_RENAME_PENDING_SYNC", "FINAL_DURABLE"),
            edge("TEMP_SYNCED", "QUARANTINE_REQUIRED"),
            edge("FINAL_RENAME_PENDING_SYNC", "QUARANTINE_REQUIRED"),
            edge("FINAL_DURABLE", "QUARANTINE_REQUIRED"),
            edge("QUARANTINE_REQUIRED", "QUARANTINE_PENDING_SYNC"),
            edge("QUARANTINE_PENDING_SYNC", "QUARANTINE_DURABLE"),
            edge("EXPECTING_RESERVATION", "CLEANUP_REQUIRED"),
            edge("WRITING_TEMP", "CLEANUP_REQUIRED"),
            edge("TEMP_SYNCED", "CLEANUP_REQUIRED"),
            edge("FINAL_RENAME_PENDING_SYNC", "CLEANUP_REQUIRED"),
            edge("FINAL_DURABLE", "CLEANUP_REQUIRED"),
            edge("QUARANTINE_REQUIRED", "CLEANUP_REQUIRED"),
            edge("QUARANTINE_PENDING_SYNC", "CLEANUP_REQUIRED"),
            edge("CLEANUP_REQUIRED", "CLEANUP_PENDING_SYNC"),
            edge("CLEANUP_PENDING_SYNC", "CLEANED_DURABLE"),
        )
        val journal = journal()

        ReferenceImportFileOperationStage.entries.forEach { sourceStage ->
            ReferenceImportFileOperationStage.entries.forEach { targetStage ->
                val source = seedOperation(sourceStage)
                val result = journal.advance(request(source, targetStage))
                val actualEdge = sourceStage to targetStage
                if (actualEdge in legalEdges) {
                    assertTrue("expected legal $actualEdge but was $result", result is ReferenceImportFileJournalResult.Applied)
                } else {
                    assertEquals(
                        "expected forbidden skip/reversal $actualEdge",
                        ReferenceImportFileJournalResult.Rejected(
                            ReferenceImportFileJournalRejectionReason.ILLEGAL_TRANSITION,
                        ),
                        result,
                    )
                }
            }
        }
    }

    @Test
    fun roomJournalPreservesSyncedEvidenceUntilCleanedDurableClearsIt() {
        val journal = journal()
        val synced = seedOperation(ReferenceImportFileOperationStage.TEMP_SYNCED)
        val cleanupRequired = applied(
            journal.advance(request(synced, ReferenceImportFileOperationStage.CLEANUP_REQUIRED)),
        )
        val cleanupPending = applied(
            journal.advance(
                request(cleanupRequired, ReferenceImportFileOperationStage.CLEANUP_PENDING_SYNC),
            ),
        )
        assertEquals(7L, cleanupPending.byteCount)
        assertEquals(VALID_SHA, cleanupPending.sha256)

        val cleaned = applied(
            journal.advance(
                request(cleanupPending, ReferenceImportFileOperationStage.CLEANED_DURABLE),
            ),
        )
        assertEquals(null, cleaned.byteCount)
        assertEquals(null, cleaned.sha256)

        val unsynced = seedOperation(ReferenceImportFileOperationStage.WRITING_TEMP)
        val unsyncedCleanup = applied(
            journal.advance(request(unsynced, ReferenceImportFileOperationStage.CLEANUP_REQUIRED)),
        )
        assertEquals(null, unsyncedCleanup.byteCount)
        assertEquals(null, unsyncedCleanup.sha256)
    }

    @Test
    fun reconciliationFlagIsRetryableAtTheSameStageAndSuccessfulAdvanceClearsIt() {
        val journal = journal()
        val source = seedOperation(ReferenceImportFileOperationStage.TEMP_SYNCED)
        val marked = applied(
            journal.markReconciliationRequired(
                ReferenceImportFileReconciliationRequest(
                    importToken = source.importToken,
                    expectedStage = source.stage,
                    expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
                    failureCode = ReferenceImportFileFailureCode.STATE_MISMATCH,
                    markedAtEpochMillis = source.updatedAtEpochMillis + 1L,
                ),
            ),
        )
        assertEquals(source.stage, marked.stage)
        assertTrue(marked.reconciliationRequired)
        assertEquals(ReferenceImportFileFailureCode.STATE_MISMATCH, marked.lastFailureCode)
        assertTrue(journal.findRetryableOperationsPage(null, null, 20).contains(marked))

        val advanced = applied(
            journal.advance(
                request(marked, ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC),
            ),
        )
        assertFalse(advanced.reconciliationRequired)
        assertEquals(null, advanced.lastFailureCode)
        assertEquals(source.byteCount, advanced.byteCount)
        assertEquals(source.sha256, advanced.sha256)
    }

    @Test
    fun durableLogicalSettlementCanClearAFlaggedTerminalFileStageByExactCas() {
        val journal = journal()
        val source = seedOperation(ReferenceImportFileOperationStage.QUARANTINE_DURABLE)
        val marked = applied(
            journal.markReconciliationRequired(
                ReferenceImportFileReconciliationRequest(
                    importToken = source.importToken,
                    expectedStage = source.stage,
                    expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
                    failureCode = ReferenceImportFileFailureCode.STATE_MISMATCH,
                    markedAtEpochMillis = source.updatedAtEpochMillis + 1L,
                ),
            ),
        )

        val cleared = applied(
            journal.clearReconciliationRequired(
                ReferenceImportFileReconciliationResolutionRequest(
                    importToken = marked.importToken,
                    expectedStage = marked.stage,
                    expectedUpdatedAtEpochMillis = marked.updatedAtEpochMillis,
                    resolvedAtEpochMillis = marked.updatedAtEpochMillis + 1L,
                ),
            ),
        )

        assertEquals(source.stage, cleared.stage)
        assertEquals(source.byteCount, cleared.byteCount)
        assertEquals(source.sha256, cleared.sha256)
        assertFalse(cleared.reconciliationRequired)
        assertEquals(null, cleared.lastFailureCode)
    }

    @Test
    fun staleCasExactReplayAndContradictoryReplayAreClassifiedFromPersistedState() {
        val journal = journal()
        val source = seedOperation(ReferenceImportFileOperationStage.TEMP_SYNCED)
        val request = request(source, ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC)

        val first = journal.advance(request)
        assertTrue(first is ReferenceImportFileJournalResult.Applied)
        assertTrue(journal.advance(request) is ReferenceImportFileJournalResult.Idempotent)
        assertEquals(
            ReferenceImportFileJournalResult.Rejected(
                ReferenceImportFileJournalRejectionReason.CONTRADICTORY_STATE,
            ),
            journal.advance(request.copy(transitionedAtEpochMillis = request.transitionedAtEpochMillis + 1L)),
        )

        val staleSource = seedOperation(ReferenceImportFileOperationStage.WRITING_TEMP)
        applied(
            journal.markReconciliationRequired(
                ReferenceImportFileReconciliationRequest(
                    importToken = staleSource.importToken,
                    expectedStage = staleSource.stage,
                    expectedUpdatedAtEpochMillis = staleSource.updatedAtEpochMillis,
                    failureCode = ReferenceImportFileFailureCode.WRITE_FAILED,
                    markedAtEpochMillis = staleSource.updatedAtEpochMillis + 1L,
                ),
            ),
        )
        assertEquals(
            ReferenceImportFileJournalResult.Rejected(
                ReferenceImportFileJournalRejectionReason.STALE_SNAPSHOT,
            ),
            journal.advance(
                request(staleSource, ReferenceImportFileOperationStage.TEMP_SYNCED),
            ),
        )
    }

    @Test
    fun concurrentDifferentLegalTransitionsHaveExactlyOneAppliedWinner() {
        val journal = journal()
        val source = seedOperation(ReferenceImportFileOperationStage.TEMP_SYNCED)
        val requests = listOf(
            request(source, ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC),
            request(source, ReferenceImportFileOperationStage.QUARANTINE_REQUIRED),
        )
        val ready = CountDownLatch(requests.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(requests.size)
        try {
            val futures = requests.map { candidate ->
                executor.submit<ReferenceImportFileJournalResult> {
                    ready.countDown()
                    start.await()
                    journal.advance(candidate)
                }
            }
            ready.await()
            start.countDown()
            val results = futures.map { future -> future.get() }

            assertEquals(1, results.count { it is ReferenceImportFileJournalResult.Applied })
            assertEquals(1, results.count { it is ReferenceImportFileJournalResult.Rejected })
            assertTrue(
                journal.snapshot(source.importToken)?.stage in
                    setOf(
                        ReferenceImportFileOperationStage.FINAL_RENAME_PENDING_SYNC,
                        ReferenceImportFileOperationStage.QUARANTINE_REQUIRED,
                    ),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun retryableQueryIsCreationTokenOrderedAndAllPublicValuesAreRedacted() {
        val journal = journal()
        val later = seedOperation(
            stage = ReferenceImportFileOperationStage.WRITING_TEMP,
            createdAtEpochMillis = 30L,
        )
        val firstSameTime = seedOperation(
            stage = ReferenceImportFileOperationStage.WRITING_TEMP,
            createdAtEpochMillis = 10L,
            token = "00000000-0000-0000-0000-000000000002",
        )
        val secondSameTime = seedOperation(
            stage = ReferenceImportFileOperationStage.WRITING_TEMP,
            createdAtEpochMillis = 10L,
            token = "00000000-0000-0000-0000-000000000003",
        )
        val retryable = journal.findRetryableOperationsPage(null, null, 20)
        assertEquals(
            listOf(firstSameTime.importToken, secondSameTime.importToken, later.importToken),
            retryable.map(ReferenceImportFileOperationSnapshot::importToken),
        )

        val sensitiveToken = later.importToken.value
        val request = request(later, ReferenceImportFileOperationStage.TEMP_SYNCED)
        val values = listOf<Any>(
            later,
            request,
            ReferenceImportFileReconciliationRequest(
                later.importToken,
                later.stage,
                later.updatedAtEpochMillis,
                ReferenceImportFileFailureCode.WRITE_FAILED,
                later.updatedAtEpochMillis + 1L,
            ),
            ReferenceImportFileJournalResult.Applied(later),
            ReferenceImportFileJournalResult.Idempotent(later),
        )
        values.forEach { value ->
            val rendered = value.toString()
            assertFalse(rendered, rendered.contains(sensitiveToken))
            assertFalse(rendered, rendered.contains(VALID_SHA))
            assertFalse(rendered, rendered.contains("reference-assets/"))
        }
    }

    @Test
    fun retryableQueryPaginatesTwentyThenOneWithoutOverlap() {
        val journal = journal()
        (0..20).forEach { index ->
            seedOperation(
                stage = ReferenceImportFileOperationStage.WRITING_TEMP,
                createdAtEpochMillis = index.toLong() + 1L,
                token = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
            )
        }

        val first = journal.findRetryableOperationsPage(null, null, 20)
        val last = first.last()
        val second = journal.findRetryableOperationsPage(
            last.createdAtEpochMillis,
            last.importToken,
            20,
        )

        assertEquals(20, first.size)
        assertEquals(1, second.size)
        assertTrue(first.map { it.importToken }.toSet().intersect(second.map { it.importToken }.toSet()).isEmpty())
        assertEquals(21L, second.single().createdAtEpochMillis)
    }

    @Test
    fun retryablePageFailsClosedWhenAValidIntentIsMissingItsRequiredFileOperation() {
        val sqlite = database.openHelper.writableDatabase
        val shootId = "missing-ledger-journal-shoot"
        val token = ReferenceImportToken("missing-ledger-journal-token")
        val paths = ReferenceImportFileOperationPaths.forToken(token)
        seedShoot(sqlite, shootId)
        sqlite.execSQL(
            "INSERT INTO reference_import_intents (import_token, shoot_id, pose_id, " +
                "relative_asset_path, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis, asset_ready_at_epoch_millis, terminal_at_epoch_millis) " +
                "VALUES (?, ?, 'missing-ledger-journal-pose', ?, 'PREPARING', 10, 10, NULL, NULL)",
            arrayOf<Any>(token.value, shootId, paths.relativeAssetPath),
        )

        assertThrows(IllegalStateException::class.java) {
            journal().findRetryableOperationsPage(null, null, 20)
        }
        assertEquals(1, sqlite.rowCount("reference_import_intents"))
        assertEquals(0, sqlite.rowCount("reference_import_file_operations"))
    }

    private fun journal() = RoomReferenceImportFileJournal(database)

    private fun seedOperation(
        stage: ReferenceImportFileOperationStage,
        createdAtEpochMillis: Long = 10L,
        token: String = UUID.randomUUID().toString(),
    ): ReferenceImportFileOperationSnapshot {
        val sqlite = database.openHelper.writableDatabase
        val shootId = UUID.randomUUID().toString()
        val poseId = UUID.randomUUID().toString()
        seedShoot(sqlite, shootId)
        val importToken = ReferenceImportToken(token)
        val paths = ReferenceImportFileOperationPaths.forToken(importToken)
        sqlite.execSQL(
            "INSERT INTO reference_import_intents (import_token, shoot_id, pose_id, " +
                "relative_asset_path, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis, asset_ready_at_epoch_millis, terminal_at_epoch_millis) " +
                "VALUES (?, ?, ?, ?, 'PREPARING', ?, ?, NULL, NULL)",
            arrayOf<Any>(
                token,
                shootId,
                poseId,
                paths.relativeAssetPath,
                createdAtEpochMillis,
                createdAtEpochMillis,
            ),
        )
        sqlite.execSQL(
            "INSERT INTO reference_import_file_operations (import_token, relative_asset_path, " +
                "relative_temp_path, relative_quarantine_path, stage, byte_count, sha256, " +
                "last_failure_code, reconciliation_required, created_at_epoch_millis, " +
                "updated_at_epoch_millis) VALUES (?, ?, ?, ?, 'EXPECTING_RESERVATION', NULL, " +
                "NULL, NULL, 0, ?, ?)",
            arrayOf<Any>(
                token,
                paths.relativeAssetPath,
                paths.relativeTempPath,
                paths.relativeQuarantinePath,
                createdAtEpochMillis,
                createdAtEpochMillis,
            ),
        )
        val (byteCount, sha256) = when (stage) {
            ReferenceImportFileOperationStage.EXPECTING_RESERVATION,
            ReferenceImportFileOperationStage.WRITING_TEMP,
            ReferenceImportFileOperationStage.CLEANED_DURABLE,
            -> null to null
            else -> 7L to VALID_SHA
        }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE reference_import_file_operations " +
                "SET stage = ?, byte_count = ?, sha256 = ?, last_failure_code = NULL, " +
                "reconciliation_required = 0, updated_at_epoch_millis = ? " +
                "WHERE import_token = ?",
            arrayOf<Any?>(stage.name, byteCount, sha256, createdAtEpochMillis + 10L, token),
        )
        return requireNotNull(journal().snapshot(importToken))
    }

    private fun request(
        source: ReferenceImportFileOperationSnapshot,
        target: ReferenceImportFileOperationStage,
    ): ReferenceImportFileAdvanceRequest {
        val (byteCount, sha256) = when (target) {
            ReferenceImportFileOperationStage.WRITING_TEMP,
            ReferenceImportFileOperationStage.CLEANED_DURABLE,
            -> null to null
            ReferenceImportFileOperationStage.TEMP_SYNCED -> 7L to VALID_SHA
            else -> source.byteCount to source.sha256
        }
        return ReferenceImportFileAdvanceRequest(
            importToken = source.importToken,
            expectedStage = source.stage,
            expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
            targetStage = target,
            byteCount = byteCount,
            sha256 = sha256,
            transitionedAtEpochMillis = source.updatedAtEpochMillis + 1L,
        )
    }

    private fun applied(result: ReferenceImportFileJournalResult): ReferenceImportFileOperationSnapshot =
        (result as ReferenceImportFileJournalResult.Applied).snapshot

    private fun edge(source: String, target: String) =
        ReferenceImportFileOperationStage.valueOf(source) to
            ReferenceImportFileOperationStage.valueOf(target)

    private fun seedShoot(sqlite: SupportSQLiteDatabase, shootId: String) {
        sqlite.execSQL(
            "INSERT INTO shoots (shoot_id, name, created_at_epoch_millis, " +
                "updated_at_epoch_millis, lifecycle_state, deletion_generation) " +
                "VALUES (?, 'Journal test', 1, 1, 'ACTIVE', 0)",
            arrayOf<Any>(shootId),
        )
    }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        val VALID_SHA: String = "ab".repeat(32)
    }
}
