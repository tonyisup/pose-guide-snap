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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomShootRepositoryAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "capture_attempt_repository_android_test_${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun happyRegistrationPersistsRegisteredAuthorityAndAdvancesLongCounter() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite, nextAttemptNumber = 4L)

        val result = repository().registerCaptureAttempt(
            SESSION_ID,
            command(rawToken = "happy-token", attemptNumber = 4L),
            recordedAtEpochMillis = 100L,
        )

        assertEquals(AttemptRegistrationResult.Registered, result)
        assertEquals(5L, sqlite.sessionCounter())
        assertEquals(
            AttemptSnapshot(
                sessionId = SESSION_ID,
                poseId = POSE_ID,
                poseIndex = 0,
                attemptNumber = 4L,
                triggerType = "MANUAL",
                lifecycleState = "REGISTERED",
                capturedDeletionGeneration = 0L,
                createdAtEpochMillis = 100L,
                updatedAtEpochMillis = 100L,
                confirmedAtEpochMillis = null,
            ),
            sqlite.attempt("happy-token"),
        )
    }

    @Test
    fun exactDuplicateRegistrationIsIdempotentAndDoesNotIncrementCounterTwice() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val command = command(rawToken = "duplicate-token")
        val repository = repository()
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command, 10L),
        )

        assertEquals(
            AttemptRegistrationResult.AlreadyRegistered,
            repository.registerCaptureAttempt(SESSION_ID, command, 999L),
        )
        assertEquals(1L, sqlite.sessionCounter())
        assertEquals(10L, sqlite.attempt("duplicate-token")?.updatedAtEpochMillis)
        assertEquals(1, sqlite.attemptCount())
    }

    @Test
    fun sameTokenWithConflictingPayloadFailsClosed() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(
                SESSION_ID,
                command(rawToken = "conflicting-token", trigger = CaptureTrigger.MANUAL),
                10L,
            ),
        )

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.TOKEN_CONFLICT,
            ),
            repository.registerCaptureAttempt(
                SESSION_ID,
                command(rawToken = "conflicting-token", trigger = CaptureTrigger.AUTOMATIC),
                11L,
            ),
        )
        assertEquals(1L, sqlite.sessionCounter())
        assertEquals(1, sqlite.attemptCount())
    }

    @Test
    fun sameSessionAttemptNumberWithDifferentTokenFailsClosed() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(rawToken = "first-token"), 10L),
        )

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.ATTEMPT_NUMBER_CONFLICT,
            ),
            repository.registerCaptureAttempt(SESSION_ID, command(rawToken = "second-token"), 11L),
        )
        assertEquals(1L, sqlite.sessionCounter())
        assertEquals(1, sqlite.attemptCount())
    }

    @Test
    fun stalePoseIndexOrPoseIdentityIsRejectedWithoutMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite, currentPoseIndex = 1, currentPoseId = "pose-1")

        val result = repository().registerCaptureAttempt(
            SESSION_ID,
            command(rawToken = "stale-pose-token", poseId = POSE_ID, poseIndex = 0),
            10L,
        )

        assertEquals(
            AttemptRegistrationResult.Rejected(AttemptRegistrationRejectionReason.STALE_POSE),
            result,
        )
        assertEquals(0L, sqlite.sessionCounter())
        assertEquals(0, sqlite.attemptCount())
    }

    @Test
    fun staleAttemptNumberIsRejectedWithoutMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite, nextAttemptNumber = 5L)

        val result = repository().registerCaptureAttempt(
            SESSION_ID,
            command(rawToken = "stale-counter-token", attemptNumber = 4L),
            10L,
        )

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.STALE_ATTEMPT_NUMBER,
            ),
            result,
        )
        assertEquals(5L, sqlite.sessionCounter())
        assertEquals(0, sqlite.attemptCount())
    }

    @Test
    fun futureAttemptNumberIsRejectedWithoutMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite, nextAttemptNumber = 5L)

        val result = repository().registerCaptureAttempt(
            SESSION_ID,
            command(rawToken = "future-counter-token", attemptNumber = 6L),
            10L,
        )

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.FUTURE_ATTEMPT_NUMBER,
            ),
            result,
        )
        assertEquals(5L, sqlite.sessionCounter())
        assertEquals(0, sqlite.attemptCount())
    }

    @Test
    fun inactiveSessionIsRejectedWithoutMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite, sessionLifecycle = "COMPLETED")

        val result = repository().registerCaptureAttempt(
            SESSION_ID,
            command(rawToken = "inactive-session-token"),
            10L,
        )

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.INACTIVE_SESSION,
            ),
            result,
        )
        assertEquals(0L, sqlite.sessionCounter())
        assertEquals(0, sqlite.attemptCount())
    }

    @Test
    fun deletingShootBlocksRegistrationWithoutMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite, shootLifecycle = "DELETING", deletionGeneration = 1L)

        val result = repository().registerCaptureAttempt(
            SESSION_ID,
            command(rawToken = "deleting-shoot-token"),
            10L,
        )

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.BLOCKED_BY_DELETION,
            ),
            result,
        )
        assertEquals(0L, sqlite.sessionCounter())
        assertEquals(0, sqlite.attemptCount())
    }

    @Test
    fun negativeShootGenerationFailsLoudBeforeRegistrationMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite, deletionGeneration = -1L)

        val failure = assertThrows(IllegalStateException::class.java) {
            repository().registerCaptureAttempt(
                SESSION_ID,
                command(rawToken = "negative-registration-generation-token"),
                10L,
            )
        }

        assertEquals("capture authority deletion generation is invalid", failure.message)
        assertEquals(0L, sqlite.sessionCounter())
        assertEquals(0, sqlite.attemptCount())
    }

    @Test
    fun unknownSessionIsRejectedWithoutCreatingAuthority() {
        val sqlite = openDatabase().openHelper.writableDatabase

        val result = repository().registerCaptureAttempt(
            "missing-session",
            command(rawToken = "unknown-session-token"),
            10L,
        )

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.UNKNOWN_SESSION,
            ),
            result,
        )
        assertEquals(0, sqlite.attemptCount())
    }

    @Test
    fun longAttemptCounterAboveIntMaxValueRemainsExact() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val attemptNumber = Int.MAX_VALUE.toLong() + 1L
        seedActiveSession(sqlite, nextAttemptNumber = attemptNumber)

        val result = repository().registerCaptureAttempt(
            SESSION_ID,
            command(rawToken = "long-counter-token", attemptNumber = attemptNumber),
            10L,
        )

        assertEquals(AttemptRegistrationResult.Registered, result)
        assertEquals(attemptNumber + 1L, sqlite.sessionCounter())
        assertEquals(attemptNumber, sqlite.attempt("long-counter-token")?.attemptNumber)
    }

    @Test
    fun failedCounterCasRollsBackAttemptInsertAndTriggerMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite, nextAttemptNumber = 5L)
        sqlite.execSQL(
            """
            CREATE TRIGGER test_force_counter_cas_failure
            AFTER INSERT ON capture_attempts
            FOR EACH ROW
            BEGIN
                UPDATE shoot_sessions
                SET next_attempt_number = next_attempt_number + 1
                WHERE session_id = NEW.session_id;
            END
            """.trimIndent(),
        )

        val result = repository().registerCaptureAttempt(
            SESSION_ID,
            command(rawToken = "rollback-token", attemptNumber = 5L),
            10L,
        )

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.COUNTER_CAS_FAILED,
            ),
            result,
        )
        assertEquals(5L, sqlite.sessionCounter())
        assertEquals(0, sqlite.attemptCount())
    }

    @Test
    fun captureStartAuthorizationAtomicallyTransitionsRegisteredAttempt() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val token = CaptureToken("start-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )

        assertEquals(
            CaptureStartAuthorizationResult.Started,
            repository.authorizeCaptureStart(SESSION_ID, token, 20L),
        )
        assertEquals("CAPTURING", sqlite.attempt(token.value)?.lifecycleState)
        assertEquals(20L, sqlite.attempt(token.value)?.updatedAtEpochMillis)
    }

    @Test
    fun duplicateCaptureStartReturnsAlreadyStartedWithoutMutatingTimestamp() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val token = CaptureToken("duplicate-start-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )
        assertEquals(
            CaptureStartAuthorizationResult.Started,
            repository.authorizeCaptureStart(SESSION_ID, token, 20L),
        )
        sqlite.execSQL(
            "UPDATE shoots SET lifecycle_state = 'DELETING', deletion_generation = 1 " +
                "WHERE shoot_id = ?",
            arrayOf(SHOOT_ID),
        )

        assertEquals(
            CaptureStartAuthorizationResult.AlreadyStarted,
            repository.authorizeCaptureStart(SESSION_ID, token, 999L),
        )
        assertEquals(20L, sqlite.attempt(token.value)?.updatedAtEpochMillis)
    }

    @Test
    fun deletingLifecycleOrChangedGenerationBlocksCaptureStartAndLeavesRegistered() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val deletingToken = CaptureToken("start-deleting-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(deletingToken.value), 10L),
        )
        sqlite.execSQL("UPDATE shoots SET lifecycle_state = 'DELETING' WHERE shoot_id = ?", arrayOf(SHOOT_ID))

        assertEquals(
            CaptureStartAuthorizationResult.BlockedByDeletion,
            repository.authorizeCaptureStart(SESSION_ID, deletingToken, 20L),
        )
        assertEquals("REGISTERED", sqlite.attempt(deletingToken.value)?.lifecycleState)
        assertEquals(10L, sqlite.attempt(deletingToken.value)?.updatedAtEpochMillis)

        sqlite.execSQL(
            "UPDATE shoots SET lifecycle_state = 'ACTIVE', deletion_generation = 1 WHERE shoot_id = ?",
            arrayOf(SHOOT_ID),
        )
        assertEquals(
            CaptureStartAuthorizationResult.BlockedByDeletion,
            repository.authorizeCaptureStart(SESSION_ID, deletingToken, 30L),
        )
        assertEquals("REGISTERED", sqlite.attempt(deletingToken.value)?.lifecycleState)
        assertEquals(10L, sqlite.attempt(deletingToken.value)?.updatedAtEpochMillis)
    }

    @Test
    fun negativeCapturedGenerationFailsLoudBeforeCaptureStartMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite, deletionGeneration = -1L)
        val token = CaptureToken("negative-start-generation-token")
        sqlite.execSQL(
            """
            INSERT INTO capture_attempts
                (command_token, session_id, pose_id, pose_index, attempt_number, trigger_type,
                 lifecycle_state, reconciliation_required, captured_deletion_generation,
                 created_at_epoch_millis, updated_at_epoch_millis, confirmed_at_epoch_millis)
            VALUES (?, ?, ?, 0, 0, 'MANUAL', 'REGISTERED', 0, -1, 10, 10, NULL)
            """.trimIndent(),
            arrayOf<Any>(token.value, SESSION_ID, POSE_ID),
        )
        val before = sqlite.attempt(token.value)

        val failure = assertThrows(IllegalStateException::class.java) {
            repository().authorizeCaptureStart(SESSION_ID, token, 20L)
        }

        assertEquals("capture start deletion generation is invalid", failure.message)
        assertEquals(before, sqlite.attempt(token.value))
        assertEquals(0L, sqlite.sessionCounter())
    }

    @Test
    fun inactiveSessionBlocksCaptureStartAndLeavesRegisteredTimestampUnchanged() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val token = CaptureToken("start-inactive-session-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )
        sqlite.execSQL(
            "UPDATE shoot_sessions SET lifecycle_state = 'COMPLETED' WHERE session_id = ?",
            arrayOf(SESSION_ID),
        )

        assertEquals(
            CaptureStartAuthorizationResult.Rejected(
                CaptureStartRejectionReason.INACTIVE_SESSION,
            ),
            repository.authorizeCaptureStart(SESSION_ID, token, 20L),
        )
        assertEquals("REGISTERED", sqlite.attempt(token.value)?.lifecycleState)
        assertEquals(10L, sqlite.attempt(token.value)?.updatedAtEpochMillis)
    }

    @Test
    fun advancedSessionPoseIndexBlocksCaptureStartAndLeavesRegisteredTimestampUnchanged() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val token = CaptureToken("start-advanced-pose-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )
        sqlite.execSQL(
            """
            INSERT INTO shoot_poses
                (shoot_id, pose_index, pose_id, label, reference_asset_path, mirror_allowed,
                 validation_state, detector_metadata, model_metadata, preprocessing_metadata)
            VALUES (?, 1, 'pose-1', 'Next pose', NULL, 0, 'VALID', NULL, NULL, NULL)
            """.trimIndent(),
            arrayOf(SHOOT_ID),
        )
        sqlite.execSQL(
            "UPDATE shoot_sessions SET current_pose_index = 1 WHERE session_id = ?",
            arrayOf(SESSION_ID),
        )

        assertEquals(
            CaptureStartAuthorizationResult.Rejected(CaptureStartRejectionReason.STALE_POSE),
            repository.authorizeCaptureStart(SESSION_ID, token, 20L),
        )
        assertEquals("REGISTERED", sqlite.attempt(token.value)?.lifecycleState)
        assertEquals(10L, sqlite.attempt(token.value)?.updatedAtEpochMillis)
    }

    @Test
    fun changedCurrentPoseIdentityBlocksCaptureStartAndLeavesRegisteredTimestampUnchanged() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val token = CaptureToken("start-changed-pose-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )
        sqlite.execSQL(
            "UPDATE shoot_poses SET pose_id = 'replacement-pose' " +
                "WHERE shoot_id = ? AND pose_index = 0",
            arrayOf(SHOOT_ID),
        )

        assertEquals(
            CaptureStartAuthorizationResult.Rejected(CaptureStartRejectionReason.STALE_POSE),
            repository.authorizeCaptureStart(SESSION_ID, token, 20L),
        )
        assertEquals("REGISTERED", sqlite.attempt(token.value)?.lifecycleState)
        assertEquals(10L, sqlite.attempt(token.value)?.updatedAtEpochMillis)
    }

    @Test
    fun unknownTokenConflictingSessionAndWrongStateFailClosed() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val token = CaptureToken("start-rejection-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )

        assertEquals(
            CaptureStartAuthorizationResult.Rejected(
                CaptureStartRejectionReason.UNKNOWN_ATTEMPT,
            ),
            repository.authorizeCaptureStart(
                SESSION_ID,
                CaptureToken("unknown-start-token"),
                20L,
            ),
        )
        assertEquals(
            CaptureStartAuthorizationResult.Rejected(
                CaptureStartRejectionReason.TOKEN_SESSION_CONFLICT,
            ),
            repository.authorizeCaptureStart("different-session", token, 20L),
        )
        sqlite.execSQL(
            "UPDATE capture_attempts SET lifecycle_state = 'CONFIRMED' WHERE command_token = ?",
            arrayOf(token.value),
        )
        assertEquals(
            CaptureStartAuthorizationResult.Rejected(CaptureStartRejectionReason.WRONG_STATE),
            repository.authorizeCaptureStart(SESSION_ID, token, 20L),
        )
        assertEquals(10L, sqlite.attempt(token.value)?.updatedAtEpochMillis)
    }

    @Test
    fun registrationAndCaptureStartAuthorityPersistAcrossReopen() {
        var sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val token = CaptureToken("reopen-authority-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository().registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )
        closeDatabase()

        sqlite = openDatabase().openHelper.writableDatabase
        assertEquals("REGISTERED", sqlite.attempt(token.value)?.lifecycleState)
        assertEquals(1L, sqlite.sessionCounter())
        assertEquals(
            CaptureStartAuthorizationResult.Started,
            repository().authorizeCaptureStart(SESSION_ID, token, 20L),
        )
        closeDatabase()

        sqlite = openDatabase().openHelper.writableDatabase
        assertEquals("CAPTURING", sqlite.attempt(token.value)?.lifecycleState)
        assertEquals(20L, sqlite.attempt(token.value)?.updatedAtEpochMillis)
        assertEquals(1L, sqlite.sessionCounter())
    }

    @Test
    fun roomV3BootstrapSurvivesReopen() {
        val firstDatabase = openDatabase()
        val sqlite = firstDatabase.openHelper.writableDatabase
        seedGuidedBootstrapShoot(sqlite)
        assertEquals(
            ShootStartResult.Started,
            RoomShootPreparationRepository(firstDatabase).startShoot(
                shootId = SHOOT_ID,
                sessionId = SESSION_ID,
                startedAtEpochMillis = 1L,
            ),
        )
        val capture = command(rawToken = "bootstrap-reopen-token")
        val firstRepository = repository()
        assertEquals(
            AttemptRegistrationResult.Registered,
            firstRepository.registerCaptureAttempt(SESSION_ID, capture, 10L),
        )
        assertEquals(
            CaptureStartAuthorizationResult.Started,
            firstRepository.authorizeCaptureStart(SESSION_ID, capture.token, 20L),
        )
        val confirmation = ShootEffect.ConfirmAndAdvanceCapture(
            token = capture.token,
            poseId = capture.poseId,
            poseIndex = capture.poseIndex,
            outputs = capture.outputs,
        )
        assertEquals(
            CaptureConfirmationResult.Applied,
            firstRepository.confirmAndAdvance(
                command = confirmation,
                privateOutputs = bootstrapPrivateOutputs(capture.token),
                exportTargets = bootstrapExportTargets(capture.token),
                confirmedAtEpochMillis = 30L,
            ),
        )
        val expected = GuidedSessionBootstrapResult.Ready(
            GuidedSessionSnapshot(
                sessionId = SESSION_ID,
                shootId = SHOOT_ID,
                lifecycle = GuidedSessionLifecycle.ACTIVE,
                orderedPoseIds = listOf("pose-0", "pose-1", "pose-2"),
                poseCount = 3,
                currentPoseIndex = 1,
                nextAttemptNumber = 1L,
                deletionGeneration = 0L,
                attemptCount = 1,
                confirmedAttemptCount = 1,
                appliedReceiptTokens = listOf(capture.token.value),
                unresolvedExportCount = 3,
                blockingAttempt = null,
            ),
        )
        assertEquals(expected, firstRepository.loadGuidedSessionBootstrap(SESSION_ID))
        closeDatabase()

        val reopened = repository().loadGuidedSessionBootstrap(SESSION_ID)

        assertEquals(expected, reopened)
    }

    @Test
    fun activeSessionDiscoveryFindsExactSessionAcrossReopen() {
        val firstDatabase = openDatabase()
        val sqlite = firstDatabase.openHelper.writableDatabase
        seedGuidedBootstrapShoot(sqlite)
        sqlite.execSQL(
            """
            INSERT INTO shoots
                (shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis,
                 lifecycle_state, deletion_generation)
            VALUES (?, 'Idle discovery shoot', 1, 1, 'ACTIVE', 0)
            """.trimIndent(),
            arrayOf<Any>(SECOND_SHOOT_ID),
        )
        assertEquals(
            ShootStartResult.Started,
            RoomShootPreparationRepository(firstDatabase).startShoot(
                shootId = SHOOT_ID,
                sessionId = SESSION_ID,
                startedAtEpochMillis = 1L,
            ),
        )
        val expected = ActiveGuidedSessionResult.Exact(SESSION_ID)
        val firstRepository = repository()

        assertEquals(expected, firstRepository.findActiveGuidedSession(SHOOT_ID))
        assertEquals(
            ActiveGuidedSessionResult.None,
            firstRepository.findActiveGuidedSession(SECOND_SHOOT_ID),
        )
        assertEquals(
            ActiveGuidedSessionResult.UnknownShoot,
            firstRepository.findActiveGuidedSession("missing-shoot"),
        )
        closeDatabase()

        val reopenedRepository = repository()
        assertEquals(expected, reopenedRepository.findActiveGuidedSession(SHOOT_ID))
        assertEquals(
            ActiveGuidedSessionResult.None,
            reopenedRepository.findActiveGuidedSession(SECOND_SHOOT_ID),
        )
    }

    @Test
    fun activeSessionDiscoveryFailsClosedOnTriggerBypassedCorruption() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        sqlite.execSQL("DROP TRIGGER IF EXISTS trigger_shoot_sessions_one_active_insert")
        sqlite.execSQL("DROP TRIGGER IF EXISTS trigger_shoot_sessions_one_active_update")
        sqlite.execSQL(
            """
            INSERT INTO shoot_sessions
                (session_id, shoot_id, current_pose_index, next_attempt_number,
                 lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis)
            VALUES (?, ?, 0, 0, 'ACTIVE', 1, 1)
            """.trimIndent(),
            arrayOf<Any>("corrupt-second-session", SHOOT_ID),
        )

        assertEquals(
            ActiveGuidedSessionResult.Rejected(
                ActiveGuidedSessionRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            repository().findActiveGuidedSession(SHOOT_ID),
        )
    }

    private fun openDatabase(): AppDatabase =
        AppDatabase.create(context, databaseName).also { database = it }

    private fun closeDatabase() {
        database?.close()
        database = null
    }

    private fun repository(): RoomShootRepository = RoomShootRepository(
        database ?: openDatabase(),
    )

    private fun seedActiveSession(
        sqlite: SupportSQLiteDatabase,
        currentPoseIndex: Int = 0,
        currentPoseId: String = POSE_ID,
        nextAttemptNumber: Long = 0L,
        shootLifecycle: String = "ACTIVE",
        sessionLifecycle: String = "ACTIVE",
        deletionGeneration: Long = 0L,
    ) {
        sqlite.execSQL(
            """
            INSERT INTO shoots
                (shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis,
                 lifecycle_state, deletion_generation)
            VALUES (?, ?, 1, 1, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(SHOOT_ID, "Test shoot", shootLifecycle, deletionGeneration),
        )
        sqlite.execSQL(
            """
            INSERT INTO shoot_poses
                (shoot_id, pose_index, pose_id, label, reference_asset_path, mirror_allowed,
                 validation_state, detector_metadata, model_metadata, preprocessing_metadata)
            VALUES (?, ?, ?, ?, NULL, 0, 'VALID', NULL, NULL, NULL)
            """.trimIndent(),
            arrayOf<Any>(SHOOT_ID, currentPoseIndex, currentPoseId, "Test pose"),
        )
        sqlite.execSQL(
            """
            INSERT INTO shoot_sessions
                (session_id, shoot_id, current_pose_index, next_attempt_number,
                 lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis)
            VALUES (?, ?, ?, ?, ?, 1, 1)
            """.trimIndent(),
            arrayOf<Any>(
                SESSION_ID,
                SHOOT_ID,
                currentPoseIndex,
                nextAttemptNumber,
                sessionLifecycle,
            ),
        )
    }

    private fun seedGuidedBootstrapShoot(sqlite: SupportSQLiteDatabase) {
        sqlite.execSQL(
            """
            INSERT INTO shoots
                (shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis,
                 lifecycle_state, deletion_generation)
            VALUES (?, 'Bootstrap reopen shoot', 1, 1, 'ACTIVE', 0)
            """.trimIndent(),
            arrayOf<Any>(SHOOT_ID),
        )
        repeat(3) { poseIndex ->
            sqlite.execSQL(
                """
                INSERT INTO shoot_poses
                    (shoot_id, pose_index, pose_id, label, reference_asset_path,
                     mirror_allowed, validation_state, detector_metadata, model_metadata,
                     preprocessing_metadata, landmark_payload, coordinate_metadata)
                VALUES (?, ?, ?, ?, NULL, 0, 'VALID', NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
                arrayOf<Any>(
                    SHOOT_ID,
                    poseIndex,
                    "pose-$poseIndex",
                    "Bootstrap pose $poseIndex",
                ),
            )
        }
    }

    private fun bootstrapPrivateOutputs(token: CaptureToken): List<DurablePrivateOutput> =
        (0..2).map { ordinal ->
            DurablePrivateOutput(
                identity = PrivateOutputIdentity(token, ordinal),
                relativePath = "private/${token.value}/$ordinal.jpg",
                byteCount = 100L + ordinal,
                capturedAtEpochMillis = 21L + ordinal,
                integrityMetadata = null,
            )
        }

    private fun bootstrapExportTargets(token: CaptureToken): List<CaptureExportTarget> =
        (0..2).map { ordinal ->
            CaptureExportTarget(
                identity = PrivateOutputIdentity(token, ordinal),
                targetCollectionUri = "content://media/external_primary/images/media",
                targetVolume = "external_primary",
                intendedDisplayName = "${token.value}-$ordinal.jpg",
                intendedRelativePath = "Pictures/PoseGuideSnap/",
                intendedMimeType = "image/jpeg",
            )
        }

    private fun command(
        rawToken: String,
        trigger: CaptureTrigger = CaptureTrigger.MANUAL,
        poseId: String = POSE_ID,
        poseIndex: Int = 0,
        attemptNumber: Long = 0L,
    ): ShootEffect.CaptureCommand = ShootEffect.CaptureCommand(
        CaptureAttempt.create(
            token = CaptureToken(rawToken),
            trigger = trigger,
            poseId = poseId,
            poseIndex = poseIndex,
            attemptNumber = attemptNumber,
        ),
    )

    private fun SupportSQLiteDatabase.sessionCounter(): Long =
        query(
            "SELECT next_attempt_number FROM shoot_sessions WHERE session_id = ?",
            arrayOf(SESSION_ID),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.attemptCount(): Int =
        query("SELECT COUNT(*) FROM capture_attempts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.attempt(rawToken: String): AttemptSnapshot? =
        query(
            """
            SELECT session_id, pose_id, pose_index, attempt_number, trigger_type,
                   lifecycle_state, captured_deletion_generation, created_at_epoch_millis,
                   updated_at_epoch_millis, confirmed_at_epoch_millis
            FROM capture_attempts
            WHERE command_token = ?
            """.trimIndent(),
            arrayOf(rawToken),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                AttemptSnapshot(
                    sessionId = cursor.getString(0),
                    poseId = cursor.getString(1),
                    poseIndex = cursor.getInt(2),
                    attemptNumber = cursor.getLong(3),
                    triggerType = cursor.getString(4),
                    lifecycleState = cursor.getString(5),
                    capturedDeletionGeneration = cursor.getLong(6),
                    createdAtEpochMillis = cursor.getLong(7),
                    updatedAtEpochMillis = cursor.getLong(8),
                    confirmedAtEpochMillis = if (cursor.isNull(9)) null else cursor.getLong(9),
                )
            }
        }

    private data class AttemptSnapshot(
        val sessionId: String,
        val poseId: String,
        val poseIndex: Int,
        val attemptNumber: Long,
        val triggerType: String,
        val lifecycleState: String,
        val capturedDeletionGeneration: Long,
        val createdAtEpochMillis: Long,
        val updatedAtEpochMillis: Long,
        val confirmedAtEpochMillis: Long?,
    )

    companion object {
        private const val SHOOT_ID = "shoot-1"
        private const val SECOND_SHOOT_ID = "shoot-2"
        private const val SESSION_ID = "session-1"
        private const val POSE_ID = "pose-0"
    }
}
