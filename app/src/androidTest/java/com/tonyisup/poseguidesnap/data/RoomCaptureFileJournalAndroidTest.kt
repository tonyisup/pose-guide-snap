package com.tonyisup.poseguidesnap.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.db.AuthorityOrdinalTriggers
import com.tonyisup.poseguidesnap.data.db.CaptureFileOperationStateTriggers
import com.tonyisup.poseguidesnap.domain.session.CaptureAttempt
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.CaptureTrigger
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCaptureFileJournalAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "capture_file_journal_android_test_${UUID.randomUUID()}.db"
        context.deleteRoomTestDatabase(databaseName)
        assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteRoomTestDatabase(databaseName)
        assertFalse(context.databaseList().contains(databaseName))
        assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
    }

    @Test
    fun captureFileJournalAdvancesLegalTransitionsAndIsIdempotent() {
        prepareStartedAttempt()
        val journal = RoomCaptureFileJournal(requireNotNull(database))
        val initial = requireNotNull(journal.snapshot(identity()))
        val admit = advance(initial, CaptureFileOperationStage.WRITING_TEMP, 20L)
        assertEquals(
            CaptureFileJournalResult.Idempotent(admit),
            journal.advance(request(initial, CaptureFileOperationStage.WRITING_TEMP, 20L)),
        )

        val failed = journal.markReconciliationRequired(
            CaptureFileReconciliationRequest(
                identity = identity(),
                expectedStage = admit.stage,
                expectedUpdatedAtEpochMillis = admit.updatedAtEpochMillis,
                failureCode = CaptureFileFailureCode.WRITE_FAILED,
                markedAtEpochMillis = 21L,
            ),
        ) as CaptureFileJournalResult.Applied
        assertTrue(failed.snapshot.reconciliationRequired)
        val repository = RoomShootRepository(requireNotNull(database))
        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.CAPTURE_FILE_EFFECT_IN_FLIGHT,
            ),
            repository.beginShootDeletion(SHOOT_ID, 21L),
        )
        assertEquals(failed.snapshot, journal.snapshot(identity()))
        val cleared = journal.clearReconciliationRequired(
            CaptureFileReconciliationResolutionRequest(
                identity = identity(),
                expectedStage = failed.snapshot.stage,
                expectedUpdatedAtEpochMillis = failed.snapshot.updatedAtEpochMillis,
                resolvedAtEpochMillis = 22L,
            ),
        ) as CaptureFileJournalResult.Applied
        assertFalse(cleared.snapshot.reconciliationRequired)

        val syncedRequest = request(
            cleared.snapshot,
            CaptureFileOperationStage.TEMP_SYNCED,
            30L,
            capturedAtEpochMillis = 20L,
        )
        val synced = (journal.advance(syncedRequest) as CaptureFileJournalResult.Applied).snapshot
        assertEquals(CaptureFileJournalResult.Idempotent(synced), journal.advance(syncedRequest))
        assertEquals(7L, synced.byteCount)
        assertEquals(SHA, synced.sha256)
        assertEquals(20L, synced.capturedAtEpochMillis)
    }

    @Test
    fun captureFileJournalRejectsInvalidStorageAndCausalTimestampsWithoutMutation() {
        prepareStartedAttempt()
        val journal = RoomCaptureFileJournal(requireNotNull(database))
        val initial = requireNotNull(journal.snapshot(identity()))
        assertEquals(
            CaptureFileJournalResult.Rejected(CaptureFileJournalRejectionReason.UNKNOWN_OPERATION),
            journal.advance(
                request(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)
                    .copy(
                        identity = PrivateOutputIdentity(
                            CaptureToken("unknown-capture-file-journal-token"),
                            0,
                        ),
                    ),
            ),
        )
        val invalid = listOf(
            request(initial, CaptureFileOperationStage.WRITING_TEMP, 19L) to
                CaptureFileJournalRejectionReason.INVALID_TIMESTAMP,
            request(initial, CaptureFileOperationStage.FINAL_DURABLE, 21L) to
                CaptureFileJournalRejectionReason.ILLEGAL_TRANSITION,
            request(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)
                .copy(expectedUpdatedAtEpochMillis = 9L) to
                CaptureFileJournalRejectionReason.STALE_SNAPSHOT,
        )
        invalid.forEach { (candidate, reason) ->
            assertEquals(CaptureFileJournalResult.Rejected(reason), journal.advance(candidate))
            assertEquals(initial, journal.snapshot(identity()))
        }
        assertEquals(
            CaptureFileJournalResult.Rejected(CaptureFileJournalRejectionReason.INVALID_EVIDENCE),
            journal.advance(
                request(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)
                    .copy(byteCount = 7L, sha256 = SHA, capturedAtEpochMillis = 19L),
            ),
        )
        assertEquals(initial, journal.snapshot(identity()))

        val sqlite = requireNotNull(database).openHelper.writableDatabase
        sqlite.execSQL(
            "UPDATE capture_attempts SET trigger_type = 'CORRUPT' WHERE command_token = ?",
            arrayOf<Any>(TOKEN.value),
        )
        assertEquals(
            CaptureFileJournalResult.Rejected(
                CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID,
            ),
            journal.advance(request(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)),
        )
        assertEquals(initial, journal.snapshot(identity()))
        sqlite.execSQL(
            "UPDATE capture_attempts SET trigger_type = 'MANUAL' WHERE command_token = ?",
            arrayOf<Any>(TOKEN.value),
        )
        sqlite.execSQL(
            "UPDATE capture_attempts SET lifecycle_state = 'REGISTERED', " +
                "updated_at_epoch_millis = created_at_epoch_millis WHERE command_token = ?",
            arrayOf<Any>(TOKEN.value),
        )
        assertEquals(
            CaptureFileJournalResult.Rejected(
                CaptureFileJournalRejectionReason.WRONG_ATTEMPT_STATE,
            ),
            journal.advance(request(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)),
        )
        assertEquals(initial, journal.snapshot(identity()))
        sqlite.execSQL(
            "UPDATE capture_attempts SET lifecycle_state = 'CAPTURING', " +
                "updated_at_epoch_millis = 20 WHERE command_token = ?",
            arrayOf<Any>(TOKEN.value),
        )

        sqlite.execSQL("DROP TRIGGER IF EXISTS `trigger_capture_file_operations_state_insert`")
        sqlite.execSQL("DROP TRIGGER IF EXISTS `trigger_capture_file_operations_state_update`")
        sqlite.execSQL(
            "DROP TRIGGER IF EXISTS `trigger_capture_file_operations_burst_ordinal_insert`",
        )
        sqlite.execSQL(
            "DROP TRIGGER IF EXISTS `trigger_capture_file_operations_burst_ordinal_update`",
        )
        try {
            sqlite.execSQL(
                "UPDATE capture_file_operations SET stage = 'WRITING_TEMP', " +
                    "updated_at_epoch_millis = 15 " +
                    "WHERE command_token = ? AND burst_ordinal = 0",
                arrayOf<Any>(TOKEN.value),
            )
            val preAuthorizationSource = requireNotNull(journal.snapshot(identity()))
            assertEquals(
                CaptureFileJournalResult.Rejected(
                    CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID,
                ),
                journal.advance(
                    request(
                        preAuthorizationSource,
                        CaptureFileOperationStage.TEMP_SYNCED,
                        21L,
                        capturedAtEpochMillis = 20L,
                    ),
                ),
            )
            assertEquals(preAuthorizationSource, journal.snapshot(identity()))
            sqlite.execSQL(
                "UPDATE capture_file_operations SET stage = 'EXPECTING_RESERVATION', " +
                    "updated_at_epoch_millis = 10 " +
                    "WHERE command_token = ? AND burst_ordinal = 0",
                arrayOf<Any>(TOKEN.value),
            )
            val coercibleCorruptions = listOf(
                "UPDATE capture_file_operations SET relative_final_path = " +
                    "CAST(relative_final_path AS BLOB) WHERE command_token = ? AND burst_ordinal = 0" to
                    "UPDATE capture_file_operations SET relative_final_path = " +
                    "CAST(relative_final_path AS TEXT) WHERE command_token = ? AND burst_ordinal = 0",
                "UPDATE capture_file_operations SET updated_at_epoch_millis = 10.5 " +
                    "WHERE command_token = ? AND burst_ordinal = 0" to
                    "UPDATE capture_file_operations SET updated_at_epoch_millis = 10 " +
                    "WHERE command_token = ? AND burst_ordinal = 0",
                "UPDATE capture_file_operations SET reconciliation_required = 2 " +
                    "WHERE command_token = ? AND burst_ordinal = 0" to
                    "UPDATE capture_file_operations SET reconciliation_required = 0 " +
                    "WHERE command_token = ? AND burst_ordinal = 0",
            )
            coercibleCorruptions.forEach { (corrupt, restore) ->
                sqlite.execSQL(corrupt, arrayOf<Any>(TOKEN.value))
                assertEquals(
                    CaptureFileJournalResult.Rejected(
                        CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID,
                    ),
                    journal.advance(request(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)),
                )
                assertEquals(
                    "EXPECTING_RESERVATION",
                    sqlite.query(
                        "SELECT stage FROM capture_file_operations " +
                            "WHERE command_token = ? AND burst_ordinal = 0",
                        arrayOf<Any>(TOKEN.value),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getString(0)
                    },
                )
                sqlite.execSQL(restore, arrayOf<Any>(TOKEN.value))
            }
            sqlite.execSQL(
                """
                INSERT INTO capture_file_operations
                    (command_token, burst_ordinal, relative_final_path, relative_temp_path,
                     relative_quarantine_path, stage, byte_count, sha256,
                     captured_at_epoch_millis, last_failure_code, reconciliation_required,
                     created_at_epoch_millis, updated_at_epoch_millis)
                SELECT command_token, CAST('0' AS BLOB), relative_final_path, relative_temp_path,
                       relative_quarantine_path, stage, byte_count, sha256,
                       captured_at_epoch_millis, last_failure_code, reconciliation_required,
                       created_at_epoch_millis, updated_at_epoch_millis
                FROM capture_file_operations
                WHERE command_token = ? AND burst_ordinal = 0
                """.trimIndent(),
                arrayOf<Any>(TOKEN.value),
            )
            assertEquals(
                CaptureFileJournalResult.Rejected(
                    CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID,
                ),
                journal.advance(request(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)),
            )
            sqlite.execSQL(
                "DELETE FROM capture_file_operations WHERE command_token = ? " +
                    "AND typeof(burst_ordinal) = 'blob'",
                arrayOf<Any>(TOKEN.value),
            )
            sqlite.execSQL(
                "UPDATE capture_file_operations SET stage = 'CORRUPT' " +
                    "WHERE command_token = ? AND burst_ordinal = 0",
                arrayOf<Any>(TOKEN.value),
            )
        } finally {
            AuthorityOrdinalTriggers.install(sqlite)
            CaptureFileOperationStateTriggers.install(sqlite)
        }
        assertEquals(
            CaptureFileJournalResult.Rejected(
                CaptureFileJournalRejectionReason.PERSISTED_STATE_INVALID,
            ),
            journal.advance(request(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)),
        )
        assertThrows(IllegalArgumentException::class.java) { journal.snapshot(identity()) }
        assertEquals(
            "CORRUPT",
            sqlite.query(
                "SELECT stage FROM capture_file_operations " +
                    "WHERE command_token = ? AND burst_ordinal = 0",
                arrayOf<Any>(TOKEN.value),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            },
        )
    }

    @Test
    fun captureFileJournalCasHasOneWinnerAcrossWalConnections() {
        prepareStartedAttempt()
        val firstDatabase = requireNotNull(database)
        val secondDatabase = AppDatabase.create(context, databaseName)
        try {
            val initial = requireNotNull(RoomCaptureFileJournal(firstDatabase).snapshot(identity()))
            val candidate = request(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)
            val executor = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CyclicBarrier(3)
            try {
                val futures = listOf(firstDatabase, secondDatabase).map { connection ->
                    executor.submit<CaptureFileJournalResult> {
                        ready.countDown()
                        start.await(10L, TimeUnit.SECONDS)
                        RoomCaptureFileJournal(connection).advance(candidate)
                    }
                }
                assertTrue(ready.await(10L, TimeUnit.SECONDS))
                start.await(10L, TimeUnit.SECONDS)
                val results = futures.map { future -> future.get(30L, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it is CaptureFileJournalResult.Applied })
                assertEquals(1, results.count { it is CaptureFileJournalResult.Idempotent })
                val persisted = requireNotNull(
                    RoomCaptureFileJournal(firstDatabase).snapshot(identity()),
                )
                assertEquals(CaptureFileOperationStage.WRITING_TEMP, persisted.stage)
                assertEquals(21L, persisted.updatedAtEpochMillis)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS))
            }
        } finally {
            secondDatabase.close()
        }
    }

    @Test
    fun captureFileJournalPersistsAcrossReopen() {
        prepareStartedAttempt()
        val initialJournal = RoomCaptureFileJournal(requireNotNull(database))
        val initial = requireNotNull(initialJournal.snapshot(identity()))
        val admitted = advance(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)
        database?.close()
        database = AppDatabase.create(context, databaseName)
        val reopened = RoomCaptureFileJournal(requireNotNull(database))
        assertEquals(admitted, reopened.snapshot(identity()))
        val synced = advance(admitted, CaptureFileOperationStage.TEMP_SYNCED, 30L, 24L, reopened)
        assertEquals(synced, reopened.snapshot(identity()))
    }

    @Test
    fun captureFileJournalDeletionInterlockSerializesAdmissionAndSettlement() {
        prepareStartedAttempt()
        val database = requireNotNull(database)
        val journal = RoomCaptureFileJournal(database)
        val repository = RoomShootRepository(database)
        val initial = requireNotNull(journal.snapshot(identity()))
        val admitted = advance(initial, CaptureFileOperationStage.WRITING_TEMP, 21L)
        assertEquals(
            BeginShootDeletionResult.Rejected(BeginShootDeletionRejectionReason.CAPTURE_FILE_EFFECT_IN_FLIGHT),
            repository.beginShootDeletion(SHOOT_ID, 21L),
        )
        val settled = advance(admitted, CaptureFileOperationStage.TEMP_SYNCED, 30L, 24L, journal)
        assertEquals(CaptureFileOperationStage.TEMP_SYNCED, settled.stage)
        assertEquals(
            BeginShootDeletionResult.Began(1L, 0, 0, 0),
            repository.beginShootDeletion(SHOOT_ID, 30L),
        )
        val second = requireNotNull(journal.snapshot(identity(1)))
        assertEquals(
            CaptureFileJournalResult.BlockedByDeletion,
            journal.advance(request(second, CaptureFileOperationStage.WRITING_TEMP, 31L)),
        )
        assertEquals(second, journal.snapshot(identity(1)))
    }

    private fun prepareStartedAttempt() {
        val database = AppDatabase.create(context, databaseName).also { this.database = it }
        seedActiveSession(database.openHelper.writableDatabase)
        val repository = RoomShootRepository(database)
        assertEquals(AttemptRegistrationResult.Registered, repository.registerCaptureAttempt(SESSION_ID, command(), 10L))
        assertEquals(CaptureAttemptStartResult.Started, repository.markCaptureAttemptStarted(SESSION_ID, TOKEN, 20L))
    }

    private fun advance(
        source: CaptureFileOperationSnapshot,
        target: CaptureFileOperationStage,
        atEpochMillis: Long,
        capturedAtEpochMillis: Long? = null,
        journal: RoomCaptureFileJournal = RoomCaptureFileJournal(requireNotNull(database)),
    ): CaptureFileOperationSnapshot {
        val result = journal.advance(request(source, target, atEpochMillis, capturedAtEpochMillis))
        assertTrue(result is CaptureFileJournalResult.Applied)
        return (result as CaptureFileJournalResult.Applied).snapshot
    }

    private fun request(
        source: CaptureFileOperationSnapshot,
        target: CaptureFileOperationStage,
        atEpochMillis: Long,
        capturedAtEpochMillis: Long? = null,
    ): CaptureFileAdvanceRequest {
        val attach = target == CaptureFileOperationStage.TEMP_SYNCED
        return CaptureFileAdvanceRequest(
            identity = source.identity,
            expectedStage = source.stage,
            expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
            targetStage = target,
            byteCount = if (attach) 7L else source.byteCount,
            sha256 = if (attach) SHA else source.sha256,
            capturedAtEpochMillis = if (attach) capturedAtEpochMillis else source.capturedAtEpochMillis,
            transitionedAtEpochMillis = atEpochMillis,
        )
    }

    private fun command(): ShootEffect.CaptureCommand = ShootEffect.CaptureCommand(
        CaptureAttempt.create(TOKEN, CaptureTrigger.MANUAL, POSE_ID, 0, 0L),
    )

    private fun identity(ordinal: Int = 0) = PrivateOutputIdentity(TOKEN, ordinal)

    private fun seedActiveSession(sqlite: SupportSQLiteDatabase) {
        sqlite.execSQL(
            "INSERT INTO shoots (shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis, lifecycle_state, deletion_generation) VALUES (?, 'Journal test', 1, 1, 'ACTIVE', 0)",
            arrayOf<Any>(SHOOT_ID),
        )
        sqlite.execSQL(
            "INSERT INTO shoot_poses (shoot_id, pose_index, pose_id, label, reference_asset_path, mirror_allowed, validation_state, detector_metadata, model_metadata, preprocessing_metadata) VALUES (?, 0, ?, 'Pose', NULL, 0, 'VALID', NULL, NULL, NULL)",
            arrayOf<Any>(SHOOT_ID, POSE_ID),
        )
        sqlite.execSQL(
            "INSERT INTO shoot_sessions (session_id, shoot_id, current_pose_index, next_attempt_number, lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis) VALUES (?, ?, 0, 0, 'ACTIVE', 1, 1)",
            arrayOf<Any>(SESSION_ID, SHOOT_ID),
        )
    }

    private companion object {
        const val SHOOT_ID = "journal-shoot"
        const val SESSION_ID = "journal-session"
        const val POSE_ID = "journal-pose"
        val TOKEN = CaptureToken("capture-file-journal-token")
        val SHA = "ab".repeat(32)
    }
}
