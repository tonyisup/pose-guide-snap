package com.tonyisup.poseguidesnap.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.domain.session.CaptureAttempt
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.CaptureTrigger
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureAttemptSettlementRepositoryAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "capture_attempt_settlement_${UUID.randomUUID()}.db"
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
    fun cleanedFailureSettlesAtomicallyAndAllowsNextAttempt() {
        val repository = prepareAttempt(started = true)
        cleanEveryOrdinal()

        assertEquals(
            CaptureAttemptSettlementResult.FailedCleaned,
            repository.settleCaptureAttemptFailure(SESSION_ID, TOKEN_0, 60L),
        )
        assertEquals(
            CaptureAttemptSettlementResult.AlreadyFailedCleaned,
            repository.settleCaptureAttemptFailure(SESSION_ID, TOKEN_0, 60L),
        )
        val bootstrap = repository.loadGuidedSessionBootstrap(SESSION_ID)
        assertTrue(bootstrap is GuidedSessionBootstrapResult.Ready)
        bootstrap as GuidedSessionBootstrapResult.Ready
        assertEquals(1, bootstrap.snapshot.failedAttemptCount)
        assertEquals(0, bootstrap.snapshot.confirmedAttemptCount)
        assertEquals(0L, journalCount(TOKEN_0))

        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(
                SESSION_ID,
                command(TOKEN_1, attemptNumber = 1L),
                70L,
            ),
        )
        assertEquals(
            CaptureAttemptSettlementResult.AlreadyFailedCleaned,
            repository.settleCaptureAttemptFailure(SESSION_ID, TOKEN_0, 60L),
        )
    }

    @Test
    fun unfinishedFailureBecomesBlockingReconciliationAcrossReopen() {
        val repository = prepareAttempt(started = true)
        advance(
            identity = PrivateOutputIdentity(TOKEN_0, 0),
            target = CaptureFileOperationStage.WRITING_TEMP,
            at = 21L,
        )

        assertEquals(
            CaptureAttemptSettlementResult.ReconciliationRequired,
            repository.settleCaptureAttemptFailure(SESSION_ID, TOKEN_0, 30L),
        )
        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.CAPTURE_FILE_EFFECT_IN_FLIGHT,
            ),
            repository.beginShootDeletion(SHOOT_ID, 30L),
        )
        database?.close()
        database = AppDatabase.create(context, databaseName)
        val reopened = RoomShootRepository(requireNotNull(database))
        assertEquals(
            CaptureAttemptSettlementResult.AlreadyReconciliationRequired,
            reopened.settleCaptureAttemptFailure(SESSION_ID, TOKEN_0, 30L),
        )
        val bootstrap = reopened.loadGuidedSessionBootstrap(SESSION_ID)
        assertTrue(bootstrap is GuidedSessionBootstrapResult.ReconciliationRequired)
        bootstrap as GuidedSessionBootstrapResult.ReconciliationRequired
        assertEquals(
            GuidedCaptureAttemptState.RECONCILIATION_REQUIRED,
            bootstrap.snapshot.blockingAttempt?.state,
        )
        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.UNRESOLVED_ATTEMPT,
            ),
            reopened.registerCaptureAttempt(
                SESSION_ID,
                command(TOKEN_1, attemptNumber = 1L),
                40L,
            ),
        )
    }

    @Test
    fun finalDurableFailureSettlementDefersToConfirmationWithoutMutation() {
        val repository = prepareAttempt(started = true)
        finalizeEveryOrdinal()
        val before = journalRows(TOKEN_0)

        assertEquals(
            CaptureAttemptSettlementResult.ReadyToConfirm,
            repository.settleCaptureAttemptFailure(SESSION_ID, TOKEN_0, 60L),
        )
        assertEquals(before, journalRows(TOKEN_0))
        assertEquals("CAPTURING", attemptState(TOKEN_0))
    }

    @Test
    fun faultAfterSettlementJournalDeleteRollsBackAttemptJournalAndSessionClock() {
        prepareAttempt(started = false)
        val faulting = RoomShootRepository(
            requireNotNull(database),
            {},
            {},
            { throw SettlementAfterJournalDeleteTestException() },
        )

        try {
            faulting.settleCaptureAttemptFailure(SESSION_ID, TOKEN_0, 20L)
            throw AssertionError("expected injected settlement fault")
        } catch (_: SettlementAfterJournalDeleteTestException) {
            // The transaction must restore every row changed before the injected fault.
        }
        assertEquals("REGISTERED", attemptState(TOKEN_0))
        assertEquals(3L, journalCount(TOKEN_0))
        assertEquals(10L, sessionUpdatedAt())
    }

    @Test
    fun failedCleanedHistoryRemainsValidDeletionAuthority() {
        val repository = prepareAttempt(started = false)
        assertEquals(
            CaptureAttemptSettlementResult.FailedCleaned,
            repository.settleCaptureAttemptFailure(SESSION_ID, TOKEN_0, 20L),
        )

        assertEquals(
            BeginShootDeletionResult.Began(1L, 0, 0, 0),
            repository.beginShootDeletion(SHOOT_ID, 20L),
        )
    }

    @Test
    fun concurrentCleanSettlementHasOneAppliedWinnerAndOneValidatedReplay() {
        prepareAttempt(started = false)
        val first = requireNotNull(database)
        val second = AppDatabase.create(context, databaseName)
        val barrier = CyclicBarrier(3)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).map { connection ->
                executor.submit<CaptureAttemptSettlementResult> {
                    barrier.await(10L, TimeUnit.SECONDS)
                    RoomShootRepository(connection)
                        .settleCaptureAttemptFailure(SESSION_ID, TOKEN_0, 20L)
                }
            }
            barrier.await(10L, TimeUnit.SECONDS)
            val results = futures.map { it.get(30L, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == CaptureAttemptSettlementResult.FailedCleaned })
            assertEquals(
                1,
                results.count {
                    it == CaptureAttemptSettlementResult.AlreadyFailedCleaned
                },
            )
            assertEquals("FAILED_CLEANED", attemptState(TOKEN_0))
            assertEquals(0L, journalCount(TOKEN_0))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS))
            second.close()
        }
    }

    private fun prepareAttempt(started: Boolean): RoomShootRepository {
        val database = AppDatabase.create(context, databaseName).also { this.database = it }
        seedActiveSession(database.openHelper.writableDatabase)
        val repository = RoomShootRepository(database)
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(TOKEN_0, 0L), 10L),
        )
        if (started) {
            assertEquals(
                CaptureAttemptStartResult.Started,
                repository.markCaptureAttemptStarted(SESSION_ID, TOKEN_0, 20L),
            )
        }
        return repository
    }

    private fun cleanEveryOrdinal() {
        repeat(3) { ordinal ->
            val identity = PrivateOutputIdentity(TOKEN_0, ordinal)
            advance(identity, CaptureFileOperationStage.WRITING_TEMP, 21L + ordinal * 10L)
            advance(identity, CaptureFileOperationStage.CLEANUP_REQUIRED, 22L + ordinal * 10L)
            advance(identity, CaptureFileOperationStage.CLEANUP_PENDING_SYNC, 23L + ordinal * 10L)
            advance(identity, CaptureFileOperationStage.CLEANED_DURABLE, 24L + ordinal * 10L)
        }
    }

    private fun finalizeEveryOrdinal() {
        repeat(3) { ordinal ->
            val identity = PrivateOutputIdentity(TOKEN_0, ordinal)
            val base = 21L + ordinal * 10L
            advance(identity, CaptureFileOperationStage.WRITING_TEMP, base)
            advance(
                identity,
                CaptureFileOperationStage.TEMP_SYNCED,
                base + 1L,
                attachEvidence = true,
            )
            advance(identity, CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC, base + 2L)
            advance(identity, CaptureFileOperationStage.FINAL_DURABLE, base + 3L)
        }
    }

    private fun advance(
        identity: PrivateOutputIdentity,
        target: CaptureFileOperationStage,
        at: Long,
        attachEvidence: Boolean = false,
    ) {
        val journal = RoomCaptureFileJournal(requireNotNull(database))
        val source = requireNotNull(journal.snapshot(identity))
        val result = journal.advance(
            CaptureFileAdvanceRequest(
                identity = identity,
                expectedStage = source.stage,
                expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
                targetStage = target,
                byteCount = if (attachEvidence) 100L + identity.ordinal else source.byteCount,
                sha256 = if (attachEvidence) SHA else source.sha256,
                capturedAtEpochMillis = if (attachEvidence) at else source.capturedAtEpochMillis,
                transitionedAtEpochMillis = at,
            ),
        )
        assertTrue(result is CaptureFileJournalResult.Applied)
    }

    private fun command(token: CaptureToken, attemptNumber: Long) =
        ShootEffect.CaptureCommand(
            CaptureAttempt.create(
                token,
                CaptureTrigger.MANUAL,
                POSE_ID,
                0,
                attemptNumber,
            ),
        )

    private fun journalCount(token: CaptureToken): Long = scalarLong(
        "SELECT COUNT(*) FROM capture_file_operations WHERE command_token = ?",
        token.value,
    )

    private fun journalRows(token: CaptureToken): List<String> =
        requireNotNull(database).openHelper.writableDatabase.query(
            "SELECT burst_ordinal, stage, updated_at_epoch_millis " +
                "FROM capture_file_operations WHERE command_token = ? ORDER BY burst_ordinal",
            arrayOf<Any>(token.value),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add("${cursor.getInt(0)}:${cursor.getString(1)}:${cursor.getLong(2)}")
                }
            }
        }

    private fun attemptState(token: CaptureToken): String =
        requireNotNull(database).openHelper.writableDatabase.query(
            "SELECT lifecycle_state FROM capture_attempts WHERE command_token = ?",
            arrayOf<Any>(token.value),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun sessionUpdatedAt(): Long = scalarLong(
        "SELECT updated_at_epoch_millis FROM shoot_sessions WHERE session_id = ?",
        SESSION_ID,
    )

    private fun scalarLong(sql: String, argument: String): Long =
        requireNotNull(database).openHelper.writableDatabase.query(
            sql,
            arrayOf<Any>(argument),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun seedActiveSession(sqlite: SupportSQLiteDatabase) {
        sqlite.execSQL(
            "INSERT INTO shoots (shoot_id, name, created_at_epoch_millis, " +
                "updated_at_epoch_millis, lifecycle_state, deletion_generation) " +
                "VALUES (?, 'Settlement test', 1, 1, 'ACTIVE', 0)",
            arrayOf<Any>(SHOOT_ID),
        )
        repeat(3) { poseIndex ->
            sqlite.execSQL(
                "INSERT INTO shoot_poses (shoot_id, pose_index, pose_id, label, " +
                    "reference_asset_path, mirror_allowed, validation_state, detector_metadata, " +
                    "model_metadata, preprocessing_metadata) " +
                    "VALUES (?, ?, ?, 'Pose', NULL, 0, 'VALID', NULL, NULL, NULL)",
                arrayOf<Any>(SHOOT_ID, poseIndex, "settlement-pose-$poseIndex"),
            )
        }
        sqlite.execSQL(
            "INSERT INTO shoot_sessions (session_id, shoot_id, current_pose_index, " +
                "next_attempt_number, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis) VALUES (?, ?, 0, 0, 'ACTIVE', 1, 1)",
            arrayOf<Any>(SESSION_ID, SHOOT_ID),
        )
    }

    private class SettlementAfterJournalDeleteTestException : RuntimeException()

    private companion object {
        const val SHOOT_ID = "settlement-shoot"
        const val SESSION_ID = "settlement-session"
        const val POSE_ID = "settlement-pose-0"
        val TOKEN_0 = CaptureToken("settlement-token-0")
        val TOKEN_1 = CaptureToken("settlement-token-1")
        val SHA = "cd".repeat(32)
    }
}
