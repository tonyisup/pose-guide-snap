package com.tonyisup.poseguidesnap.camera

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.AttemptRegistrationResult
import com.tonyisup.poseguidesnap.data.CaptureAttemptSettlementResult
import com.tonyisup.poseguidesnap.data.CaptureAttemptStartResult
import com.tonyisup.poseguidesnap.data.CaptureFileAdvanceRequest
import com.tonyisup.poseguidesnap.data.CaptureFileFailureCode
import com.tonyisup.poseguidesnap.data.CaptureFileJournalResult
import com.tonyisup.poseguidesnap.data.CaptureFileOperationPaths
import com.tonyisup.poseguidesnap.data.CaptureFileOperationStage
import com.tonyisup.poseguidesnap.data.CaptureFileReconciliationRequest
import com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapResult
import com.tonyisup.poseguidesnap.data.RoomCaptureFileJournal
import com.tonyisup.poseguidesnap.data.RoomShootRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.deleteRoomTestDatabase
import com.tonyisup.poseguidesnap.data.roomTestDatabaseResidue
import com.tonyisup.poseguidesnap.domain.session.CaptureAttempt
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.CaptureTrigger
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureAttemptStartupReconcilerAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var privateRoot: File
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "capture_restart_recovery_${UUID.randomUUID()}.db"
        privateRoot = File(context.noBackupFilesDir, "capture-recovery-${UUID.randomUUID()}")
        context.deleteRoomTestDatabase(databaseName)
        assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
        assertFalse(privateRoot.exists())
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteRoomTestDatabase(databaseName)
        assertFalse(context.databaseList().contains(databaseName))
        assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
        if (privateRoot.exists()) {
            assertTrue(privateRoot.deleteRecursively())
        }
        assertFalse(privateRoot.exists())
    }

    @Test
    fun mixedJournalAndExactFilesRecoverAcrossDatabaseReopen() {
        val repository = prepareAttempt()
        val store = androidJournaledPrivateCaptureStore(privateRoot)
        val identity = PrivateOutputIdentity(TOKEN, 0)
        var operation = requireNotNull(RoomCaptureFileJournal(requireNotNull(database)).snapshot(identity))
        operation = advance(operation, CaptureFileOperationStage.WRITING_TEMP, 21L)
        val claim = store.claimForWrite(operation) as CaptureFileClaimResult.Claimed
        val capturedBytes = "restart-safe-candidate".toByteArray()
        claim.lease.tempFile.writeBytes(capturedBytes)
        val synced = store.syncCapturedTemp(operation, claim.lease, 22L)
            as CaptureTempSyncResult.Synced
        operation = advance(
            operation,
            CaptureFileOperationStage.TEMP_SYNCED,
            22L,
            synced.evidence,
        )
        assertEquals(CaptureFileOperationStage.TEMP_SYNCED, operation.stage)
        val flagged = RoomCaptureFileJournal(requireNotNull(database)).markReconciliationRequired(
            CaptureFileReconciliationRequest(
                identity = identity,
                expectedStage = operation.stage,
                expectedUpdatedAtEpochMillis = operation.updatedAtEpochMillis,
                failureCode = CaptureFileFailureCode.WRITE_FAILED,
                markedAtEpochMillis = 23L,
            ),
        )
        assertTrue(flagged is CaptureFileJournalResult.Applied)

        val unrelated = File(privateRoot, "capture-candidates/unrelated.jpg")
        val unrelatedBytes = "must-survive".toByteArray()
        unrelated.writeBytes(unrelatedBytes)
        assertEquals(
            CaptureAttemptSettlementResult.ReconciliationRequired,
            repository.settleCaptureAttemptFailure(SESSION_ID, TOKEN, 30L),
        )

        reopenDatabase()
        val reopened = RoomShootRepository(requireNotNull(database))
        val result = reconciler(reopened, store).reconcile(SESSION_ID)

        assertEquals(CaptureAttemptRestartRecoveryResult.FailedCleaned, result)
        assertArrayEquals(unrelatedBytes, unrelated.readBytes())
        assertExactPathsAbsent(identity)
        val bootstrap = reopened.loadGuidedSessionBootstrap(SESSION_ID)
        assertTrue(bootstrap is GuidedSessionBootstrapResult.Ready)
        bootstrap as GuidedSessionBootstrapResult.Ready
        assertEquals(1, bootstrap.snapshot.failedAttemptCount)
        assertEquals(0L, journalCount())
    }

    @Test
    fun journalFreeLegacyAttemptSettlesOnlyAfterExactAbsenceAcrossReopen() {
        prepareAttempt()
        requireNotNull(database).openHelper.writableDatabase.execSQL(
            "DELETE FROM capture_file_operations WHERE command_token = ?",
            arrayOf<Any>(TOKEN.value),
        )
        assertEquals(0L, journalCount())

        reopenDatabase()
        val reopened = RoomShootRepository(requireNotNull(database))
        val store = androidJournaledPrivateCaptureStore(privateRoot)
        val result = reconciler(reopened, store).reconcile(SESSION_ID)

        assertEquals(CaptureAttemptRestartRecoveryResult.FailedCleaned, result)
        val bootstrap = reopened.loadGuidedSessionBootstrap(SESSION_ID)
        assertTrue(bootstrap is GuidedSessionBootstrapResult.Ready)
        bootstrap as GuidedSessionBootstrapResult.Ready
        assertEquals(1, bootstrap.snapshot.failedAttemptCount)
        repeat(3) { ordinal ->
            assertExactPathsAbsent(PrivateOutputIdentity(TOKEN, ordinal))
        }
    }

    @Test
    fun journalFreeLegacyAttemptWithExactFileRemainsBlockingAndPreservesFile() {
        prepareAttempt()
        requireNotNull(database).openHelper.writableDatabase.execSQL(
            "DELETE FROM capture_file_operations WHERE command_token = ?",
            arrayOf<Any>(TOKEN.value),
        )
        val store = androidJournaledPrivateCaptureStore(privateRoot)
        val identity = PrivateOutputIdentity(TOKEN, 1)
        val exact = File(
            privateRoot,
            CaptureFileOperationPaths.forIdentity(identity).relativeTempPath,
        )
        val bytes = "unowned-legacy-file".toByteArray()
        exact.writeBytes(bytes)

        reopenDatabase()
        val result = reconciler(RoomShootRepository(requireNotNull(database)), store)
            .reconcile(SESSION_ID)

        assertTrue(result is CaptureAttemptRestartRecoveryResult.Outstanding)
        result as CaptureAttemptRestartRecoveryResult.Outstanding
        assertEquals(
            CaptureAttemptRestartRecoveryReason.FILES_PRESENT_OR_AMBIGUOUS,
            result.reason,
        )
        assertArrayEquals(bytes, exact.readBytes())
        assertEquals("CAPTURING", attemptState())
    }

    private fun prepareAttempt(): RoomShootRepository {
        val created = AppDatabase.create(context, databaseName).also { database = it }
        seedActiveSession(created.openHelper.writableDatabase)
        val repository = RoomShootRepository(created)
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(), 10L),
        )
        assertEquals(
            CaptureAttemptStartResult.Started,
            repository.markCaptureAttemptStarted(SESSION_ID, TOKEN, 20L),
        )
        return repository
    }

    private fun reopenDatabase() {
        database?.close()
        database = AppDatabase.create(context, databaseName)
    }

    private fun reconciler(
        repository: RoomShootRepository,
        store: JournaledPrivateCaptureStore,
    ) = CaptureAttemptStartupReconciler(
        repository = repository,
        database = requireNotNull(database),
        store = store,
        clock = CaptureRecoveryClock { floor ->
            if (floor == Long.MAX_VALUE) null else floor + 1L
        },
    )

    private fun advance(
        source: com.tonyisup.poseguidesnap.data.CaptureFileOperationSnapshot,
        target: CaptureFileOperationStage,
        at: Long,
        evidence: JournaledCaptureEvidence? = null,
    ): com.tonyisup.poseguidesnap.data.CaptureFileOperationSnapshot {
        val result = RoomCaptureFileJournal(requireNotNull(database)).advance(
            CaptureFileAdvanceRequest(
                identity = source.identity,
                expectedStage = source.stage,
                expectedUpdatedAtEpochMillis = source.updatedAtEpochMillis,
                targetStage = target,
                byteCount = evidence?.byteCount,
                sha256 = evidence?.sha256,
                capturedAtEpochMillis = evidence?.capturedAtEpochMillis,
                transitionedAtEpochMillis = at,
            ),
        )
        assertTrue(result is CaptureFileJournalResult.Applied)
        return (result as CaptureFileJournalResult.Applied).snapshot
    }

    private fun command() = ShootEffect.CaptureCommand(
        CaptureAttempt.create(
            TOKEN,
            CaptureTrigger.MANUAL,
            POSE_ID,
            0,
            0L,
        ),
    )

    private fun assertExactPathsAbsent(identity: PrivateOutputIdentity) {
        val paths = CaptureFileOperationPaths.forIdentity(identity)
        assertFalse(File(privateRoot, paths.relativeFinalPath).exists())
        assertFalse(File(privateRoot, paths.relativeTempPath).exists())
        assertFalse(File(privateRoot, paths.relativeQuarantinePath).exists())
    }

    private fun journalCount(): Long = requireNotNull(database).openHelper.writableDatabase.query(
        "SELECT COUNT(*) FROM capture_file_operations WHERE command_token = ?",
        arrayOf<Any>(TOKEN.value),
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun attemptState(): String = requireNotNull(database).openHelper.writableDatabase.query(
        "SELECT lifecycle_state FROM capture_attempts WHERE command_token = ?",
        arrayOf<Any>(TOKEN.value),
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun seedActiveSession(sqlite: SupportSQLiteDatabase) {
        sqlite.execSQL(
            "INSERT INTO shoots (shoot_id, name, created_at_epoch_millis, " +
                "updated_at_epoch_millis, lifecycle_state, deletion_generation) " +
                "VALUES (?, 'Recovery test', 1, 1, 'ACTIVE', 0)",
            arrayOf<Any>(SHOOT_ID),
        )
        repeat(3) { poseIndex ->
            sqlite.execSQL(
                "INSERT INTO shoot_poses (shoot_id, pose_index, pose_id, label, " +
                    "reference_asset_path, mirror_allowed, validation_state, detector_metadata, " +
                    "model_metadata, preprocessing_metadata) " +
                    "VALUES (?, ?, ?, 'Pose', NULL, 0, 'VALID', NULL, NULL, NULL)",
                arrayOf<Any>(SHOOT_ID, poseIndex, "recovery-pose-$poseIndex"),
            )
        }
        sqlite.execSQL(
            "INSERT INTO shoot_sessions (session_id, shoot_id, current_pose_index, " +
                "next_attempt_number, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis) VALUES (?, ?, 0, 0, 'ACTIVE', 1, 1)",
            arrayOf<Any>(SESSION_ID, SHOOT_ID),
        )
    }

    private companion object {
        const val SHOOT_ID = "recovery-shoot"
        const val SESSION_ID = "recovery-session"
        const val POSE_ID = "recovery-pose-0"
        val TOKEN = CaptureToken("restart-recovery-token")
    }
}
