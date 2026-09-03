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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun registerCaptureAttemptAtomicallySeedsExactlyThreeCaptureFileOperations() {
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
        assertEquals(
            listOf(0, 1, 2).map { ordinal ->
                ExpectedRegistrationJournalRow(
                    burstOrdinal = ordinal,
                    pathsMatch = true,
                    stage = "EXPECTING_RESERVATION",
                    byteCountPresent = false,
                    sha256Present = false,
                    capturedAtPresent = false,
                    failurePresent = false,
                    reconciliationRequired = false,
                    createdAtEpochMillis = 100L,
                    updatedAtEpochMillis = 100L,
                )
            },
            sqlite.registrationJournal("happy-token"),
        )
    }

    @Test
    fun registrationRejectsDistinctIllFormedUtf16TokensWithoutMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val malformedTokens = listOf(
            charArrayOf(0xD800.toChar()).concatToString(),
            charArrayOf(0xDC00.toChar()).concatToString(),
        )
        assertFalse(malformedTokens[0] == malformedTokens[1])
        val before = sqlite.registrationAuthorityCounts()

        malformedTokens.forEach { malformedToken ->
            assertEquals(
                AttemptRegistrationResult.Rejected(
                    AttemptRegistrationRejectionReason.INVALID_COMMAND_TOKEN_ENCODING,
                ),
                repository().registerCaptureAttempt(
                    SESSION_ID,
                    command(rawToken = malformedToken),
                    recordedAtEpochMillis = 10L,
                ),
            )
            assertEquals(before, sqlite.registrationAuthorityCounts())
        }
    }

    @Test
    fun registrationRejectsBackwardAndAcceptsEqualOwningSessionTimestamp() {
        assertRegistrationTimestampRejectedWithoutMutation(
            rawToken = "backward-session-registration-token",
            shootUpdatedAtEpochMillis = 99L,
            sessionUpdatedAtEpochMillis = 100L,
        )
        assertRegistrationTimestampRejectedWithoutMutation(
            rawToken = "backward-shoot-registration-token",
            shootUpdatedAtEpochMillis = 100L,
            sessionUpdatedAtEpochMillis = 99L,
        )

        closeDatabase()
        context.deleteRoomTestDatabase(databaseName)
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(
            sqlite,
            shootUpdatedAtEpochMillis = 100L,
            sessionUpdatedAtEpochMillis = 100L,
        )

        assertEquals(
            AttemptRegistrationResult.Registered,
            repository().registerCaptureAttempt(
                SESSION_ID,
                command(rawToken = "equal-registration-token"),
                recordedAtEpochMillis = 100L,
            ),
        )
        assertEquals(1L, sqlite.sessionCounter())
        assertEquals(3, sqlite.journalCount())
    }

    @Test
    fun journalInsertFailureRollsBackAttemptRowsAndCounter() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        sqlite.execSQL(
            """
            CREATE TRIGGER test_force_registration_journal_insert_failure
            BEFORE INSERT ON capture_file_operations
            FOR EACH ROW
            WHEN NEW.burst_ordinal = 1
            BEGIN
                SELECT RAISE(ABORT, 'test journal insert failure');
            END
            """.trimIndent(),
        )

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.JOURNAL_AUTHORITY_INVALID,
            ),
            repository().registerCaptureAttempt(
                SESSION_ID,
                command(rawToken = "journal-rollback-token"),
                recordedAtEpochMillis = 10L,
            ),
        )
        assertEquals(
            RegistrationAuthorityCounts(
                sessionCounter = 0L,
                attemptCount = 0,
                journalCount = 0,
            ),
            sqlite.registrationAuthorityCounts(),
        )
    }

    @Test
    fun concurrentDuplicateRegistrationSeedsOneCompleteJournal() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val primaryRepository = repository()
        val secondaryDatabase = AppDatabase.create(context, databaseName)
        val repositories = listOf(
            primaryRepository,
            RoomShootRepository(secondaryDatabase),
        )
        val captureCommand = command(rawToken = "concurrent-registration-token")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = repositories.map { repository ->
                executor.submit<AttemptRegistrationResult> {
                    ready.countDown()
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repository.registerCaptureAttempt(
                        SESSION_ID,
                        captureCommand,
                        recordedAtEpochMillis = 10L,
                    )
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { future -> future.get(10, TimeUnit.SECONDS) }

            assertEquals(
                mapOf(
                    AttemptRegistrationResult.Registered to 1,
                    AttemptRegistrationResult.AlreadyRegistered to 1,
                ),
                results.groupingBy { it }.eachCount(),
            )
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            secondaryDatabase.close()
        }
        assertEquals(1L, sqlite.sessionCounter())
        assertEquals(1, sqlite.attemptCount())
        assertEquals(3, sqlite.journalCount())
        assertEquals(
            listOf(0, 1, 2),
            sqlite.registrationJournal("concurrent-registration-token")
                .map(ExpectedRegistrationJournalRow::burstOrdinal),
        )
        assertTrue(
            sqlite.registrationJournal("concurrent-registration-token")
                .all { row -> row.pathsMatch && row.stage == "EXPECTING_RESERVATION" },
        )
    }

    @Test
    fun registrationReplayRejectsMissingPartialOrConflictingJournal() {
        assertCoherentCapturingRegistrationReplayIsIdempotent()
        assertCorruptedRegistrationReplayRejected("missing-journal-token") { sqlite, token ->
            sqlite.execSQL(
                "DELETE FROM capture_file_operations WHERE command_token = ?",
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("partial-journal-token") { sqlite, token ->
            sqlite.execSQL(
                "DELETE FROM capture_file_operations WHERE command_token = ? AND burst_ordinal = 2",
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("extra-journal-token") { sqlite, token ->
            sqlite.dropCaptureFileOperationValidationTriggers()
            sqlite.execSQL(
                """
                INSERT INTO capture_file_operations
                    (command_token, burst_ordinal, relative_final_path, relative_temp_path,
                     relative_quarantine_path, stage, byte_count, sha256,
                     captured_at_epoch_millis, last_failure_code, reconciliation_required,
                     created_at_epoch_millis, updated_at_epoch_millis)
                SELECT command_token, 3, relative_final_path, relative_temp_path,
                       relative_quarantine_path, stage, byte_count, sha256,
                       captured_at_epoch_millis, last_failure_code, reconciliation_required,
                       created_at_epoch_millis, updated_at_epoch_millis
                FROM capture_file_operations
                WHERE command_token = ? AND burst_ordinal = 0
                """.trimIndent(),
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("conflicting-journal-token") { sqlite, token ->
            sqlite.dropCaptureFileOperationValidationTriggers()
            sqlite.execSQL(
                """
                UPDATE capture_file_operations
                SET relative_final_path = 'capture-candidates/conflicting.jpg'
                WHERE command_token = ? AND burst_ordinal = 1
                """.trimIndent(),
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("malformed-journal-token") { sqlite, token ->
            sqlite.dropCaptureFileOperationValidationTriggers()
            sqlite.execSQL(
                """
                UPDATE capture_file_operations
                SET stage = 'CORRUPTED_ENUM'
                WHERE command_token = ? AND burst_ordinal = 1
                """.trimIndent(),
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("unknown-lifecycle-token") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET lifecycle_state = 'CORRUPTED_ENUM' WHERE command_token = ?",
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("reconciliation-required-token") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET reconciliation_required = 1 WHERE command_token = ?",
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("confirmed-with-journal-token") { sqlite, token ->
            sqlite.execSQL(
                """
                UPDATE capture_attempts
                SET lifecycle_state = 'CONFIRMED',
                    updated_at_epoch_millis = 11,
                    confirmed_at_epoch_millis = 11
                WHERE command_token = ?
                """.trimIndent(),
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("backward-attempt-clock-token") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET updated_at_epoch_millis = 9 WHERE command_token = ?",
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("contradictory-confirmation-token") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET confirmed_at_epoch_millis = 10 WHERE command_token = ?",
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("negative-generation-token") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET captured_deletion_generation = -1 WHERE command_token = ?",
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("negative-registration-clock-token") { sqlite, token ->
            sqlite.dropCaptureFileOperationValidationTriggers()
            sqlite.execSQL(
                """
                UPDATE capture_attempts
                SET created_at_epoch_millis = -1,
                    updated_at_epoch_millis = -1
                WHERE command_token = ?
                """.trimIndent(),
                arrayOf(token),
            )
            sqlite.execSQL(
                """
                UPDATE capture_file_operations
                SET created_at_epoch_millis = -1,
                    updated_at_epoch_millis = -1
                WHERE command_token = ?
                """.trimIndent(),
                arrayOf(token),
            )
        }
        assertCorruptedRegistrationReplayRejected("real-pose-index-token") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET pose_index = 0.5 WHERE command_token = ?",
                arrayOf(token),
            )
        }
    }

    @Test
    fun registrationReplayRejectsByteEquivalentBlobTokenJournalDuplicateWithoutMutation() {
        assertCorruptedRegistrationReplayRejected("blob-token-journal-duplicate") { sqlite, token ->
            sqlite.insertByteEquivalentBlobTokenJournalDuplicate(token)
        }
    }

    private fun assertCoherentCapturingRegistrationReplayIsIdempotent() {
        closeDatabase()
        context.deleteRoomTestDatabase(databaseName)
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val captureCommand = command("capturing-registration-replay-token")
        val repository = repository()
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, captureCommand, 10L),
        )
        assertEquals(
            CaptureAttemptStartResult.Started,
            repository.markCaptureAttemptStarted(SESSION_ID, captureCommand.token, 20L),
        )
        val before = sqlite.registrationAuthoritySnapshot(captureCommand.token.value)

        assertEquals(
            AttemptRegistrationResult.AlreadyRegistered,
            repository.registerCaptureAttempt(SESSION_ID, captureCommand, 999L),
        )
        assertEquals(before, sqlite.registrationAuthoritySnapshot(captureCommand.token.value))
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
    fun markCaptureAttemptStartedAtomicallyTransitionsRegisteredAttempt() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val token = CaptureToken("start-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )
        val before = sqlite.captureStartAuthoritySnapshot(token.value)

        assertEquals(
            CaptureAttemptStartResult.Started,
            repository.markCaptureAttemptStarted(SESSION_ID, token, 20L),
        )
        assertOnlyAttemptStartChanged(
            before = before,
            after = sqlite.captureStartAuthoritySnapshot(token.value),
            startedAtEpochMillis = 20L,
        )
    }

    @Test
    fun logicalStartThenDeletionCanBothCommitBeforeAnyFileEffectAdmission() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val token = CaptureToken("logical-start-then-deletion-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )
        assertEquals(
            CaptureAttemptStartResult.Started,
            repository.markCaptureAttemptStarted(SESSION_ID, token, 20L),
        )
        val afterStart = sqlite.captureStartAuthoritySnapshot(token.value)

        assertEquals(
            BeginShootDeletionResult.Began(
                generation = 1L,
                cancelledOutputCount = 0,
                cancelledOutboxCount = 0,
                retainedOutputCount = 0,
            ),
            repository.beginShootDeletion(SHOOT_ID, 30L),
        )
        val afterDeletion = sqlite.captureStartAuthoritySnapshot(token.value)

        assertEquals(
            listOf(
                listOf("text", "'DELETING'", "integer", "1", "integer", "30"),
            ),
            sqlite.typedQuotedRows(
                table = "shoots",
                columns = listOf(
                    "lifecycle_state",
                    "deletion_generation",
                    "updated_at_epoch_millis",
                ),
                whereClause = "shoot_id = ?",
                bindArgs = arrayOf(SHOOT_ID),
                orderBy = "shoot_id",
            ),
        )
        assertEquals(
            AttemptSnapshot(
                sessionId = SESSION_ID,
                poseId = POSE_ID,
                poseIndex = 0,
                attemptNumber = 0L,
                triggerType = "MANUAL",
                lifecycleState = "CAPTURING",
                capturedDeletionGeneration = 0L,
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 20L,
                confirmedAtEpochMillis = null,
            ),
            sqlite.attempt(token.value),
        )
        assertEquals(
            listOf(0, 1, 2).map { ordinal ->
                ExpectedRegistrationJournalRow(
                    burstOrdinal = ordinal,
                    pathsMatch = true,
                    stage = "EXPECTING_RESERVATION",
                    byteCountPresent = false,
                    sha256Present = false,
                    capturedAtPresent = false,
                    failurePresent = false,
                    reconciliationRequired = false,
                    createdAtEpochMillis = 10L,
                    updatedAtEpochMillis = 10L,
                )
            },
            sqlite.registrationJournal(token.value),
        )
        assertTrue(afterDeletion.privateOutputRows.isEmpty())
        assertTrue(afterDeletion.confirmationReceiptRows.isEmpty())
        assertTrue(afterDeletion.exportOutboxRows.isEmpty())
        assertTrue(afterDeletion.exportOutputRows.isEmpty())
        assertOnlyOwningShootDeletionChanged(
            before = afterStart,
            after = afterDeletion,
            deletionGeneration = 1L,
            requestedAtEpochMillis = 30L,
        )
    }

    @Test
    fun markCaptureAttemptStartedRejectsMissingPartialOrCorruptInitialJournal() {
        captureStartAttemptAndJournalOnlyCorruptionCases().forEach { corruption ->
            assertFreshCaptureStartCorruptionRejected(corruption)
        }
        freshOnlyCaptureStartAuthorityCorruptionCases().forEach { corruption ->
            assertFreshCaptureStartCorruptionRejected(corruption)
        }

        val rawToken = "start-missing-current-pose-token"
        val sqlite = resetDatabaseWithRegisteredAttempt(rawToken)
        sqlite.execSQL(
            "DELETE FROM shoot_poses WHERE shoot_id = ? AND pose_index = 0",
            arrayOf(SHOOT_ID),
        )
        val before = sqlite.captureStartAuthoritySnapshot(rawToken)

        assertEquals(
            CaptureAttemptStartResult.Rejected(CaptureAttemptStartRejectionReason.STALE_POSE),
            repository().markCaptureAttemptStarted(
                SESSION_ID,
                CaptureToken(rawToken),
                100L,
            ),
        )
        assertEquals(before, sqlite.captureStartAuthoritySnapshot(rawToken))
    }

    @Test
    fun freshAndCapturingReplayRejectByteEquivalentBlobTokenJournalDuplicateWithoutMutation() {
        val corruption = CaptureStartCorruptionCase(
            "blob-token-journal-duplicate",
        ) { sqlite, token ->
            sqlite.insertByteEquivalentBlobTokenJournalDuplicate(token)
        }

        assertFreshCaptureStartCorruptionRejected(corruption)
        assertCapturingReplayCorruptionRejected(corruption)
    }

    @Test
    fun freshCaptureStartRejectsMalformedOrDuplicateByteCorrelatedPoseAuthorityWithoutMutation() {
        listOf(
            CaptureStartCorruptionCase("blob-current-pose-shoot-id") { sqlite, _ ->
                sqlite.withForeignKeysDisabled {
                    execSQL(
                        "UPDATE shoot_poses SET shoot_id = CAST(shoot_id AS BLOB) " +
                            "WHERE shoot_id = ? AND pose_index = 0",
                        arrayOf(SHOOT_ID),
                    )
                }
            },
            CaptureStartCorruptionCase("blob-current-pose-index") { sqlite, _ ->
                sqlite.withForeignKeysDisabled {
                    execSQL(
                        "UPDATE shoot_poses SET pose_index = CAST(pose_index AS BLOB) " +
                            "WHERE shoot_id = ? AND pose_index = 0",
                        arrayOf(SHOOT_ID),
                    )
                }
            },
            CaptureStartCorruptionCase("blob-key-current-pose-duplicate") { sqlite, _ ->
                sqlite.withForeignKeysDisabled {
                    execSQL(
                        """
                        INSERT INTO shoot_poses
                            (shoot_id, pose_index, pose_id, label, reference_asset_path,
                             mirror_allowed, validation_state, detector_metadata, model_metadata,
                             preprocessing_metadata, landmark_payload, coordinate_metadata)
                        SELECT CAST(shoot_id AS BLOB), CAST(pose_index AS BLOB), pose_id, label,
                               reference_asset_path, mirror_allowed, validation_state,
                               detector_metadata, model_metadata, preprocessing_metadata,
                               landmark_payload, coordinate_metadata
                        FROM shoot_poses
                        WHERE shoot_id = ? AND pose_index = 0
                        """.trimIndent(),
                        arrayOf(SHOOT_ID),
                    )
                }
            },
        ).forEach(::assertFreshCaptureStartCorruptionRejected)
    }

    @Test
    fun freshCaptureStartRejectsByteEquivalentBlobOwnerDuplicatesWithoutMutation() {
        listOf(
            CaptureStartCorruptionCase("blob-key-owning-session-duplicate") { sqlite, _ ->
                sqlite.execSQL("DROP TRIGGER IF EXISTS trigger_shoot_sessions_one_active_insert")
                sqlite.withForeignKeysDisabled {
                    execSQL(
                        """
                        INSERT INTO shoot_sessions
                            (session_id, shoot_id, current_pose_index, next_attempt_number,
                             lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis)
                        SELECT CAST(session_id AS BLOB), shoot_id, current_pose_index,
                               next_attempt_number, lifecycle_state, created_at_epoch_millis,
                               updated_at_epoch_millis
                        FROM shoot_sessions
                        WHERE session_id = ?
                        """.trimIndent(),
                        arrayOf(SESSION_ID),
                    )
                }
            },
            CaptureStartCorruptionCase("blob-key-owning-shoot-duplicate") { sqlite, _ ->
                sqlite.withForeignKeysDisabled {
                    execSQL(
                        """
                        INSERT INTO shoots
                            (shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis,
                             lifecycle_state, deletion_generation)
                        SELECT CAST(shoot_id AS BLOB), name, created_at_epoch_millis,
                               updated_at_epoch_millis, lifecycle_state, deletion_generation
                        FROM shoots
                        WHERE shoot_id = ?
                        """.trimIndent(),
                        arrayOf(SHOOT_ID),
                    )
                }
            },
        ).forEach(::assertFreshCaptureStartCorruptionRejected)
    }

    @Test
    fun markCaptureAttemptStartedEnforcesNondecreasingTimestamp() {
        assertFreshCaptureStartTimestampRejected(
            suffix = "below-attempt-and-journal",
            startedAtEpochMillis = 9L,
        ) { _, _ -> Unit }
        assertFreshCaptureStartTimestampRejected(
            suffix = "below-newer-shoot",
            startedAtEpochMillis = 29L,
        ) { sqlite, _ ->
            sqlite.execSQL(
                "UPDATE shoots SET updated_at_epoch_millis = 30 WHERE shoot_id = ?",
                arrayOf(SHOOT_ID),
            )
        }
        assertFreshCaptureStartTimestampRejected(
            suffix = "below-newer-session",
            startedAtEpochMillis = 39L,
        ) { sqlite, _ ->
            sqlite.execSQL(
                "UPDATE shoot_sessions SET updated_at_epoch_millis = 40 WHERE session_id = ?",
                arrayOf(SESSION_ID),
            )
        }

        val sqlite = resetDatabaseWithRegisteredAttempt(
            rawToken = "start-equal-complete-maximum-token",
            recordedAtEpochMillis = 50L,
        )
        sqlite.execSQL(
            "UPDATE shoots SET updated_at_epoch_millis = 50 WHERE shoot_id = ?",
            arrayOf(SHOOT_ID),
        )
        val before = sqlite.captureStartAuthoritySnapshot("start-equal-complete-maximum-token")

        assertEquals(
            CaptureAttemptStartResult.Started,
            repository().markCaptureAttemptStarted(
                SESSION_ID,
                CaptureToken("start-equal-complete-maximum-token"),
                50L,
            ),
        )
        assertOnlyAttemptStartChanged(
            before = before,
            after = sqlite.captureStartAuthoritySnapshot("start-equal-complete-maximum-token"),
            startedAtEpochMillis = 50L,
        )
    }

    @Test
    fun deletionFirstBlocksLogicalStartWithoutMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val token = CaptureToken("deletion-first-logical-start-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )
        assertEquals(
            BeginShootDeletionResult.Began(
                generation = 1L,
                cancelledOutputCount = 0,
                cancelledOutboxCount = 0,
                retainedOutputCount = 0,
            ),
            repository.beginShootDeletion(SHOOT_ID, 20L),
        )
        val afterDeletion = sqlite.captureStartAuthoritySnapshot(token.value)

        assertEquals(
            CaptureAttemptStartResult.BlockedByDeletion,
            repository.markCaptureAttemptStarted(SESSION_ID, token, 30L),
        )
        assertEquals(afterDeletion, sqlite.captureStartAuthoritySnapshot(token.value))
    }

    @Test
    fun alreadyStartedRequiresCoherentJournal() {
        var sqlite = resetDatabaseWithRegisteredAttempt("coherent-start-replay-token")
        var repository = repository()
        val coherentToken = CaptureToken("coherent-start-replay-token")
        assertEquals(
            CaptureAttemptStartResult.Started,
            repository.markCaptureAttemptStarted(SESSION_ID, coherentToken, 20L),
        )
        val coherent = sqlite.captureStartAuthoritySnapshot(coherentToken.value)

        assertEquals(
            CaptureAttemptStartResult.Rejected(
                CaptureAttemptStartRejectionReason.INVALID_TIMESTAMP,
            ),
            repository.markCaptureAttemptStarted(SESSION_ID, coherentToken, 19L),
        )
        assertEquals(coherent, sqlite.captureStartAuthoritySnapshot(coherentToken.value))
        assertEquals(
            CaptureAttemptStartResult.AlreadyStarted,
            repository.markCaptureAttemptStarted(SESSION_ID, coherentToken, 20L),
        )
        assertEquals(coherent, sqlite.captureStartAuthoritySnapshot(coherentToken.value))
        assertEquals(
            CaptureAttemptStartResult.AlreadyStarted,
            repository.markCaptureAttemptStarted(SESSION_ID, coherentToken, 999L),
        )
        assertEquals(coherent, sqlite.captureStartAuthoritySnapshot(coherentToken.value))

        captureStartAttemptAndJournalOnlyCorruptionCases().forEach { corruption ->
            assertCapturingReplayCorruptionRejected(corruption)
        }

        sqlite = resetDatabaseWithRegisteredAttempt("deleting-coherent-start-replay-token")
        repository = repository()
        val deletingToken = CaptureToken("deleting-coherent-start-replay-token")
        assertEquals(
            CaptureAttemptStartResult.Started,
            repository.markCaptureAttemptStarted(SESSION_ID, deletingToken, 20L),
        )
        assertEquals(
            BeginShootDeletionResult.Began(1L, 0, 0, 0),
            repository.beginShootDeletion(SHOOT_ID, 30L),
        )
        sqlite.execSQL(
            """
            INSERT INTO shoot_poses
                (shoot_id, pose_index, pose_id, label, reference_asset_path, mirror_allowed,
                 validation_state, detector_metadata, model_metadata, preprocessing_metadata)
            VALUES (?, 1, 'pose-after-deletion', 'Pose after deletion', NULL, 0,
                    'VALID', NULL, NULL, NULL)
            """.trimIndent(),
            arrayOf(SHOOT_ID),
        )
        sqlite.execSQL(
            """
            UPDATE shoot_sessions
            SET current_pose_index = 1, lifecycle_state = 'COMPLETED',
                updated_at_epoch_millis = 30
            WHERE session_id = ?
            """.trimIndent(),
            arrayOf(SESSION_ID),
        )
        val afterDurableDeletion = sqlite.captureStartAuthoritySnapshot(deletingToken.value)
        assertEquals(
            CaptureAttemptStartResult.AlreadyStarted,
            repository.markCaptureAttemptStarted(SESSION_ID, deletingToken, 30L),
        )
        assertEquals(
            afterDurableDeletion,
            sqlite.captureStartAuthoritySnapshot(deletingToken.value),
        )
    }

    @Test
    fun markCaptureAttemptStartedReturnsCasFailedWhenCompareAndSetUpdatesZeroRows() {
        val sqlite = resetDatabaseWithRegisteredAttempt("start-cas-zero-row-token")
        sqlite.execSQL(
            """
            CREATE TRIGGER test_force_capture_start_cas_zero_rows
            BEFORE UPDATE OF lifecycle_state ON capture_attempts
            FOR EACH ROW
            WHEN OLD.lifecycle_state = 'REGISTERED' AND NEW.lifecycle_state = 'CAPTURING'
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent(),
        )
        val before = sqlite.captureStartAuthoritySnapshot("start-cas-zero-row-token")

        assertEquals(
            CaptureAttemptStartResult.Rejected(CaptureAttemptStartRejectionReason.CAS_FAILED),
            repository().markCaptureAttemptStarted(
                SESSION_ID,
                CaptureToken("start-cas-zero-row-token"),
                20L,
            ),
        )
        assertEquals(before, sqlite.captureStartAuthoritySnapshot("start-cas-zero-row-token"))
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
        val before = sqlite.captureStartAuthoritySnapshot(token.value)

        assertEquals(
            CaptureAttemptStartResult.Rejected(
                CaptureAttemptStartRejectionReason.INACTIVE_SESSION,
            ),
            repository.markCaptureAttemptStarted(SESSION_ID, token, 20L),
        )
        assertEquals(before, sqlite.captureStartAuthoritySnapshot(token.value))
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
        val before = sqlite.captureStartAuthoritySnapshot(token.value)

        assertEquals(
            CaptureAttemptStartResult.Rejected(CaptureAttemptStartRejectionReason.STALE_POSE),
            repository.markCaptureAttemptStarted(SESSION_ID, token, 20L),
        )
        assertEquals(before, sqlite.captureStartAuthoritySnapshot(token.value))
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
        val before = sqlite.captureStartAuthoritySnapshot(token.value)

        assertEquals(
            CaptureAttemptStartResult.Rejected(CaptureAttemptStartRejectionReason.STALE_POSE),
            repository.markCaptureAttemptStarted(SESSION_ID, token, 20L),
        )
        assertEquals(before, sqlite.captureStartAuthoritySnapshot(token.value))
    }

    @Test
    fun unknownTokenAndConflictingSessionFailClosedWithoutMutation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val repository = repository()
        val token = CaptureToken("start-rejection-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(SESSION_ID, command(token.value), 10L),
        )

        var before = sqlite.captureStartAuthoritySnapshot("unknown-start-token")
        assertEquals(
            CaptureAttemptStartResult.Rejected(
                CaptureAttemptStartRejectionReason.UNKNOWN_ATTEMPT,
            ),
            repository.markCaptureAttemptStarted(
                SESSION_ID,
                CaptureToken("unknown-start-token"),
                20L,
            ),
        )
        assertEquals(before, sqlite.captureStartAuthoritySnapshot("unknown-start-token"))
        before = sqlite.captureStartAuthoritySnapshot(token.value)
        assertEquals(
            CaptureAttemptStartResult.Rejected(
                CaptureAttemptStartRejectionReason.TOKEN_SESSION_CONFLICT,
            ),
            repository.markCaptureAttemptStarted("different-session", token, 20L),
        )
        assertEquals(before, sqlite.captureStartAuthoritySnapshot(token.value))

        val wrongStateToken = "start-wrong-state-token"
        val wrongStateSqlite = resetDatabaseWithRegisteredAttempt(wrongStateToken)
        wrongStateSqlite.execSQL(
            """
            UPDATE capture_attempts
            SET lifecycle_state = 'CONFIRMED',
                updated_at_epoch_millis = 20,
                confirmed_at_epoch_millis = 20
            WHERE command_token = ?
            """.trimIndent(),
            arrayOf(wrongStateToken),
        )
        val wrongStateBefore = wrongStateSqlite.captureStartAuthoritySnapshot(wrongStateToken)

        assertEquals(
            CaptureAttemptStartResult.Rejected(CaptureAttemptStartRejectionReason.WRONG_STATE),
            repository().markCaptureAttemptStarted(
                SESSION_ID,
                CaptureToken(wrongStateToken),
                30L,
            ),
        )
        assertEquals(
            wrongStateBefore,
            wrongStateSqlite.captureStartAuthoritySnapshot(wrongStateToken),
        )
    }

    @Test
    fun registrationAndCaptureAttemptStartStatePersistAcrossReopen() {
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
            CaptureAttemptStartResult.Started,
            repository().markCaptureAttemptStarted(SESSION_ID, token, 20L),
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
            CaptureAttemptStartResult.Started,
            firstRepository.markCaptureAttemptStarted(SESSION_ID, capture.token, 20L),
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

    private fun resetDatabaseWithRegisteredAttempt(
        rawToken: String,
        recordedAtEpochMillis: Long = 10L,
    ): SupportSQLiteDatabase {
        closeDatabase()
        context.deleteRoomTestDatabase(databaseName)
        assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository().registerCaptureAttempt(
                SESSION_ID,
                command(rawToken),
                recordedAtEpochMillis,
            ),
        )
        return sqlite
    }

    private fun assertFreshCaptureStartCorruptionRejected(
        corruption: CaptureStartCorruptionCase,
    ) {
        val rawToken = "start-${corruption.suffix}-token"
        val sqlite = resetDatabaseWithRegisteredAttempt(rawToken)
        corruption.corrupt(sqlite, rawToken)
        val before = sqlite.captureStartAuthoritySnapshot(rawToken)

        assertEquals(
            CaptureAttemptStartResult.Rejected(
                CaptureAttemptStartRejectionReason.JOURNAL_AUTHORITY_INVALID,
            ),
            repository().markCaptureAttemptStarted(
                SESSION_ID,
                CaptureToken(rawToken),
                100L,
            ),
        )
        assertEquals(before, sqlite.captureStartAuthoritySnapshot(rawToken))
    }

    private fun assertCapturingReplayCorruptionRejected(
        corruption: CaptureStartCorruptionCase,
    ) {
        val rawToken = "start-${corruption.suffix}-token"
        val sqlite = resetDatabaseWithRegisteredAttempt(rawToken)
        assertEquals(
            CaptureAttemptStartResult.Started,
            repository().markCaptureAttemptStarted(SESSION_ID, CaptureToken(rawToken), 20L),
        )
        corruption.corrupt(sqlite, rawToken)
        val before = sqlite.captureStartAuthoritySnapshot(rawToken)

        assertEquals(
            CaptureAttemptStartResult.Rejected(
                CaptureAttemptStartRejectionReason.JOURNAL_AUTHORITY_INVALID,
            ),
            repository().markCaptureAttemptStarted(
                SESSION_ID,
                CaptureToken(rawToken),
                100L,
            ),
        )
        assertEquals(before, sqlite.captureStartAuthoritySnapshot(rawToken))
    }

    private fun assertFreshCaptureStartTimestampRejected(
        suffix: String,
        startedAtEpochMillis: Long,
        prepare: (SupportSQLiteDatabase, String) -> Unit,
    ) {
        val rawToken = "start-$suffix-token"
        val sqlite = resetDatabaseWithRegisteredAttempt(rawToken)
        prepare(sqlite, rawToken)
        val before = sqlite.captureStartAuthoritySnapshot(rawToken)

        assertEquals(
            CaptureAttemptStartResult.Rejected(
                CaptureAttemptStartRejectionReason.INVALID_TIMESTAMP,
            ),
            repository().markCaptureAttemptStarted(
                SESSION_ID,
                CaptureToken(rawToken),
                startedAtEpochMillis,
            ),
        )
        assertEquals(before, sqlite.captureStartAuthoritySnapshot(rawToken))
    }

    private fun captureStartAttemptAndJournalOnlyCorruptionCases():
        List<CaptureStartCorruptionCase> = listOf(
        CaptureStartCorruptionCase("zero-journal-rows") { sqlite, token ->
            sqlite.execSQL(
                "DELETE FROM capture_file_operations WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("two-journal-rows") { sqlite, token ->
            sqlite.execSQL(
                "DELETE FROM capture_file_operations WHERE command_token = ? AND burst_ordinal = 2",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("extra-journal-row") { sqlite, token ->
            // Production ordinal triggers reject 3, so this isolated corrupt fixture drops them.
            sqlite.dropCaptureFileOperationValidationTriggers()
            sqlite.execSQL(
                """
                INSERT INTO capture_file_operations
                    (command_token, burst_ordinal, relative_final_path, relative_temp_path,
                     relative_quarantine_path, stage, byte_count, sha256,
                     captured_at_epoch_millis, last_failure_code, reconciliation_required,
                     created_at_epoch_millis, updated_at_epoch_millis)
                SELECT command_token, 3, relative_final_path, relative_temp_path,
                       relative_quarantine_path, stage, byte_count, sha256,
                       captured_at_epoch_millis, last_failure_code, reconciliation_required,
                       created_at_epoch_millis, updated_at_epoch_millis
                FROM capture_file_operations
                WHERE command_token = ? AND burst_ordinal = 0
                """.trimIndent(),
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("noninteger-ordinal") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "burst_ordinal = 0.5")
        },
        CaptureStartCorruptionCase("out-of-range-ordinal") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "burst_ordinal = 3")
        },
        CaptureStartCorruptionCase("altered-final-path") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "relative_final_path = 'corrupt/final.jpg'")
        },
        CaptureStartCorruptionCase("altered-temp-path") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "relative_temp_path = 'corrupt/temp.part'")
        },
        CaptureStartCorruptionCase("altered-quarantine-path") { sqlite, token ->
            sqlite.mutateInitialJournal(
                token,
                "relative_quarantine_path = 'corrupt/quarantine.part'",
            )
        },
        CaptureStartCorruptionCase("known-noninitial-stage") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "stage = 'WRITING_TEMP'")
        },
        CaptureStartCorruptionCase("unknown-stage") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "stage = 'UNKNOWN_STAGE'")
        },
        CaptureStartCorruptionCase("nontext-stage") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "stage = X'01'")
        },
        CaptureStartCorruptionCase("unexpected-evidence") { sqlite, token ->
            sqlite.mutateInitialJournal(
                token,
                "byte_count = 1, sha256 = '${"a".repeat(64)}', captured_at_epoch_millis = 10",
            )
        },
        CaptureStartCorruptionCase("failure-reconciliation") { sqlite, token ->
            sqlite.mutateInitialJournal(
                token,
                "last_failure_code = 'WRITE_FAILED', reconciliation_required = 1",
            )
        },
        CaptureStartCorruptionCase("noninteger-journal-reconciliation") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "reconciliation_required = 0.5")
        },
        CaptureStartCorruptionCase("journal-created-clock-storage") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "created_at_epoch_millis = X'01'")
        },
        CaptureStartCorruptionCase("journal-updated-clock-storage") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "updated_at_epoch_millis = X'01'")
        },
        CaptureStartCorruptionCase("negative-journal-clocks") { sqlite, token ->
            sqlite.mutateInitialJournal(
                token,
                "created_at_epoch_millis = -1, updated_at_epoch_millis = -1",
            )
        },
        CaptureStartCorruptionCase("backward-journal-clock") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "updated_at_epoch_millis = 9")
        },
        CaptureStartCorruptionCase("detached-journal-clock") { sqlite, token ->
            sqlite.mutateInitialJournal(token, "updated_at_epoch_millis = 11")
        },
        CaptureStartCorruptionCase("unknown-trigger") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET trigger_type = 'UNKNOWN_TRIGGER' WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("nontext-trigger") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET trigger_type = X'01' WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("unknown-lifecycle") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET lifecycle_state = 'UNKNOWN_STATE' WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("nontext-lifecycle") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET lifecycle_state = X'01' WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("noninteger-generation") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET captured_deletion_generation = 0.5 " +
                    "WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("negative-generation") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET captured_deletion_generation = -1 " +
                    "WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("attempt-created-clock-storage") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET created_at_epoch_millis = X'01' " +
                    "WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("attempt-updated-clock-storage") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET updated_at_epoch_millis = X'01' " +
                    "WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("negative-attempt-clock") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET created_at_epoch_millis = -1 WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("backward-attempt-clock") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET updated_at_epoch_millis = 9 WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("detached-attempt-clock") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET created_at_epoch_millis = 9 WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("attempt-reconciliation") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET reconciliation_required = 1 WHERE command_token = ?",
                arrayOf(token),
            )
        },
        CaptureStartCorruptionCase("unexpected-confirmation-evidence") { sqlite, token ->
            sqlite.execSQL(
                "UPDATE capture_attempts SET confirmed_at_epoch_millis = 10 WHERE command_token = ?",
                arrayOf(token),
            )
        },
    )

    private fun freshOnlyCaptureStartAuthorityCorruptionCases():
        List<CaptureStartCorruptionCase> = listOf(
        CaptureStartCorruptionCase("missing-owning-session") { sqlite, _ ->
            sqlite.withForeignKeysDisabled {
                execSQL(
                    "DELETE FROM shoot_sessions WHERE session_id = ?",
                    arrayOf(SESSION_ID),
                )
            }
        },
        CaptureStartCorruptionCase("missing-owning-shoot") { sqlite, _ ->
            sqlite.withForeignKeysDisabled {
                execSQL(
                    "UPDATE shoot_sessions SET shoot_id = 'missing-shoot' WHERE session_id = ?",
                    arrayOf(SESSION_ID),
                )
            }
        },
        CaptureStartCorruptionCase("nontext-shoot-lifecycle") { sqlite, _ ->
            sqlite.execSQL(
                "UPDATE shoots SET lifecycle_state = X'01' WHERE shoot_id = ?",
                arrayOf(SHOOT_ID),
            )
        },
        CaptureStartCorruptionCase("nontext-session-lifecycle") { sqlite, _ ->
            sqlite.execSQL(
                "UPDATE shoot_sessions SET lifecycle_state = X'01' WHERE session_id = ?",
                arrayOf(SESSION_ID),
            )
        },
        CaptureStartCorruptionCase("noninteger-shoot-generation") { sqlite, _ ->
            sqlite.execSQL(
                "UPDATE shoots SET deletion_generation = 0.5 WHERE shoot_id = ?",
                arrayOf(SHOOT_ID),
            )
        },
        CaptureStartCorruptionCase("negative-shoot-generation") { sqlite, _ ->
            sqlite.execSQL(
                "UPDATE shoots SET deletion_generation = -1 WHERE shoot_id = ?",
                arrayOf(SHOOT_ID),
            )
        },
        CaptureStartCorruptionCase("shoot-clock-storage") { sqlite, _ ->
            sqlite.execSQL(
                "UPDATE shoots SET updated_at_epoch_millis = X'01' WHERE shoot_id = ?",
                arrayOf(SHOOT_ID),
            )
        },
        CaptureStartCorruptionCase("session-clock-storage") { sqlite, _ ->
            sqlite.execSQL(
                "UPDATE shoot_sessions SET updated_at_epoch_millis = X'01' WHERE session_id = ?",
                arrayOf(SESSION_ID),
            )
        },
    )

    private fun SupportSQLiteDatabase.mutateInitialJournal(
        rawToken: String,
        setClause: String,
        burstOrdinal: Int = 0,
        dropValidationTriggers: Boolean = true,
    ) {
        if (dropValidationTriggers) dropCaptureFileOperationValidationTriggers()
        execSQL(
            "UPDATE capture_file_operations SET $setClause " +
                "WHERE command_token = ? AND burst_ordinal = ?",
            arrayOf<Any>(rawToken, burstOrdinal),
        )
    }

    private fun SupportSQLiteDatabase.insertByteEquivalentBlobTokenJournalDuplicate(
        rawToken: String,
    ) {
        dropCaptureFileOperationValidationTriggers()
        execSQL(
            """
            INSERT INTO capture_file_operations
                (command_token, burst_ordinal, relative_final_path, relative_temp_path,
                 relative_quarantine_path, stage, byte_count, sha256,
                 captured_at_epoch_millis, last_failure_code, reconciliation_required,
                 created_at_epoch_millis, updated_at_epoch_millis)
            SELECT CAST(command_token AS BLOB), 3, relative_final_path, relative_temp_path,
                   relative_quarantine_path, stage, byte_count, sha256,
                   captured_at_epoch_millis, last_failure_code, reconciliation_required,
                   created_at_epoch_millis, updated_at_epoch_millis
            FROM capture_file_operations
            WHERE command_token = ? AND burst_ordinal = 0
            """.trimIndent(),
            arrayOf(rawToken),
        )
    }

    private inline fun SupportSQLiteDatabase.withForeignKeysDisabled(
        mutation: SupportSQLiteDatabase.() -> Unit,
    ) {
        execSQL("PRAGMA foreign_keys = OFF")
        try {
            mutation()
        } finally {
            execSQL("PRAGMA foreign_keys = ON")
        }
    }

    private fun assertCorruptedRegistrationReplayRejected(
        rawToken: String,
        corrupt: (SupportSQLiteDatabase, String) -> Unit,
    ) {
        closeDatabase()
        context.deleteRoomTestDatabase(databaseName)
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(sqlite)
        val captureCommand = command(rawToken)
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository().registerCaptureAttempt(SESSION_ID, captureCommand, 10L),
        )
        corrupt(sqlite, rawToken)
        val before = sqlite.registrationAuthoritySnapshot(rawToken)

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.JOURNAL_AUTHORITY_INVALID,
            ),
            repository().registerCaptureAttempt(SESSION_ID, captureCommand, 999L),
        )
        assertEquals(before, sqlite.registrationAuthoritySnapshot(rawToken))
    }

    private fun assertRegistrationTimestampRejectedWithoutMutation(
        rawToken: String,
        shootUpdatedAtEpochMillis: Long,
        sessionUpdatedAtEpochMillis: Long,
    ) {
        closeDatabase()
        context.deleteRoomTestDatabase(databaseName)
        val sqlite = openDatabase().openHelper.writableDatabase
        seedActiveSession(
            sqlite,
            shootUpdatedAtEpochMillis = shootUpdatedAtEpochMillis,
            sessionUpdatedAtEpochMillis = sessionUpdatedAtEpochMillis,
        )
        val before = sqlite.registrationAuthorityCounts()

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.INVALID_TIMESTAMP,
            ),
            repository().registerCaptureAttempt(
                SESSION_ID,
                command(rawToken = rawToken),
                recordedAtEpochMillis = 99L,
            ),
        )
        assertEquals(before, sqlite.registrationAuthorityCounts())
    }

    private fun seedActiveSession(
        sqlite: SupportSQLiteDatabase,
        currentPoseIndex: Int = 0,
        currentPoseId: String = POSE_ID,
        nextAttemptNumber: Long = 0L,
        shootLifecycle: String = "ACTIVE",
        sessionLifecycle: String = "ACTIVE",
        deletionGeneration: Long = 0L,
        shootUpdatedAtEpochMillis: Long = 1L,
        sessionUpdatedAtEpochMillis: Long = 1L,
    ) {
        sqlite.execSQL(
            """
            INSERT INTO shoots
                (shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis,
                 lifecycle_state, deletion_generation)
            VALUES (?, ?, 1, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                SHOOT_ID,
                "Test shoot",
                shootUpdatedAtEpochMillis,
                shootLifecycle,
                deletionGeneration,
            ),
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
            VALUES (?, ?, ?, ?, ?, 1, ?)
            """.trimIndent(),
            arrayOf<Any>(
                SESSION_ID,
                SHOOT_ID,
                currentPoseIndex,
                nextAttemptNumber,
                sessionLifecycle,
                sessionUpdatedAtEpochMillis,
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

    private fun SupportSQLiteDatabase.journalCount(): Int =
        query("SELECT COUNT(*) FROM capture_file_operations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.registrationAuthorityCounts(): RegistrationAuthorityCounts =
        RegistrationAuthorityCounts(
            sessionCounter = sessionCounter(),
            attemptCount = attemptCount(),
            journalCount = journalCount(),
        )

    private fun SupportSQLiteDatabase.captureStartAuthoritySnapshot(
        rawToken: String,
    ): CaptureStartAuthoritySnapshot = CaptureStartAuthoritySnapshot(
        owningShootRows = typedQuotedRows(
            table = "shoots",
            columns = listOf(
                "shoot_id",
                "name",
                "created_at_epoch_millis",
                "updated_at_epoch_millis",
                "lifecycle_state",
                "deletion_generation",
            ),
            whereClause =
                "EXISTS (SELECT 1 FROM shoot_sessions AS owning_session " +
                    "JOIN capture_attempts AS owned_attempt " +
                    "ON CAST(owned_attempt.session_id AS BLOB) = " +
                    "CAST(owning_session.session_id AS BLOB) " +
                    "WHERE CAST(owned_attempt.command_token AS BLOB) = CAST(? AS BLOB) " +
                    "AND CAST(shoots.shoot_id AS BLOB) = " +
                    "CAST(owning_session.shoot_id AS BLOB))",
            bindArgs = arrayOf(rawToken),
            orderBy = "shoot_id",
        ),
        owningSessionRows = typedQuotedRows(
            table = "shoot_sessions",
            columns = listOf(
                "session_id",
                "shoot_id",
                "current_pose_index",
                "next_attempt_number",
                "lifecycle_state",
                "created_at_epoch_millis",
                "updated_at_epoch_millis",
            ),
            whereClause =
                "EXISTS (SELECT 1 FROM capture_attempts AS owned_attempt " +
                    "WHERE CAST(owned_attempt.command_token AS BLOB) = CAST(? AS BLOB) " +
                    "AND CAST(shoot_sessions.session_id AS BLOB) = " +
                    "CAST(owned_attempt.session_id AS BLOB))",
            bindArgs = arrayOf(rawToken),
            orderBy = "session_id",
        ),
        currentPoseRows = typedQuotedRows(
            table = "shoot_poses",
            columns = listOf(
                "shoot_id",
                "pose_index",
                "pose_id",
                "label",
                "reference_asset_path",
                "mirror_allowed",
                "validation_state",
                "detector_metadata",
                "model_metadata",
                "preprocessing_metadata",
                "landmark_payload",
                "coordinate_metadata",
            ),
            whereClause =
                "EXISTS (SELECT 1 FROM shoot_sessions AS owning_session " +
                    "JOIN capture_attempts AS owned_attempt " +
                    "ON CAST(owned_attempt.session_id AS BLOB) = " +
                    "CAST(owning_session.session_id AS BLOB) " +
                    "WHERE CAST(owned_attempt.command_token AS BLOB) = CAST(? AS BLOB) " +
                    "AND CAST(shoot_poses.shoot_id AS BLOB) = " +
                    "CAST(owning_session.shoot_id AS BLOB) " +
                    "AND CAST(shoot_poses.pose_index AS BLOB) = " +
                    "CAST(owning_session.current_pose_index AS BLOB))",
            bindArgs = arrayOf(rawToken),
            orderBy = "shoot_id, pose_index",
        ),
        attemptRows = typedQuotedRows(
            table = "capture_attempts",
            columns = listOf(
                "command_token",
                "session_id",
                "pose_id",
                "pose_index",
                "attempt_number",
                "trigger_type",
                "lifecycle_state",
                "reconciliation_required",
                "captured_deletion_generation",
                "created_at_epoch_millis",
                "updated_at_epoch_millis",
                "confirmed_at_epoch_millis",
            ),
            whereClause = "CAST(command_token AS BLOB) = CAST(? AS BLOB)",
            bindArgs = arrayOf(rawToken),
            orderBy = "command_token",
        ),
        journalRows = typedQuotedRows(
            table = "capture_file_operations",
            columns = listOf(
                "command_token",
                "burst_ordinal",
                "relative_final_path",
                "relative_temp_path",
                "relative_quarantine_path",
                "stage",
                "byte_count",
                "sha256",
                "captured_at_epoch_millis",
                "last_failure_code",
                "reconciliation_required",
                "created_at_epoch_millis",
                "updated_at_epoch_millis",
            ),
            whereClause = "CAST(command_token AS BLOB) = CAST(? AS BLOB)",
            bindArgs = arrayOf(rawToken),
            orderBy = "burst_ordinal, rowid",
        ),
        privateOutputRows = typedQuotedRows(
            table = "private_capture_outputs",
            columns = listOf(
                "command_token",
                "burst_ordinal",
                "relative_path",
                "byte_count",
                "durability_state",
                "captured_at_epoch_millis",
                "integrity_metadata",
            ),
            whereClause = "command_token = ?",
            bindArgs = arrayOf(rawToken),
            orderBy = "burst_ordinal, rowid",
        ),
        confirmationReceiptRows = typedQuotedRows(
            table = "capture_confirmation_receipts",
            columns = listOf(
                "command_token",
                "from_pose_index",
                "to_pose_index",
                "applied_deletion_generation",
                "applied_at_epoch_millis",
            ),
            whereClause = "command_token = ?",
            bindArgs = arrayOf(rawToken),
            orderBy = "command_token",
        ),
        exportOutboxRows = typedQuotedRows(
            table = "capture_export_outboxes",
            columns = listOf(
                "command_token",
                "lifecycle_state",
                "created_at_epoch_millis",
                "updated_at_epoch_millis",
                "retry_metadata",
            ),
            whereClause = "command_token = ?",
            bindArgs = arrayOf(rawToken),
            orderBy = "command_token",
        ),
        exportOutputRows = typedQuotedRows(
            table = "capture_export_outputs",
            columns = listOf(
                "command_token",
                "burst_ordinal",
                "target_collection_uri",
                "target_volume",
                "intended_display_name",
                "intended_relative_path",
                "intended_mime_type",
                "lifecycle_state",
                "claim_token",
                "media_uri_string",
                "ambiguity_state",
                "deletion_generation",
                "created_at_epoch_millis",
                "updated_at_epoch_millis",
            ),
            whereClause = "command_token = ?",
            bindArgs = arrayOf(rawToken),
            orderBy = "burst_ordinal, rowid",
        ),
    )

    private fun SupportSQLiteDatabase.typedQuotedRows(
        table: String,
        columns: List<String>,
        whereClause: String,
        bindArgs: Array<out Any?>,
        orderBy: String,
    ): List<List<String>> {
        val projection = columns.joinToString(", ") { column ->
            "typeof(`$column`), quote(`$column`)"
        }
        return query(
            "SELECT $projection FROM `$table` WHERE $whereClause ORDER BY $orderBy",
            bindArgs,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(List(cursor.columnCount) { index -> cursor.getString(index) })
                }
            }
        }
    }

    private fun assertOnlyAttemptStartChanged(
        before: CaptureStartAuthoritySnapshot,
        after: CaptureStartAuthoritySnapshot,
        startedAtEpochMillis: Long,
    ) {
        assertEquals(1, before.attemptRows.size)
        val expectedAttempt = before.attemptRows.single().toMutableList()
        assertEquals("text", expectedAttempt[12])
        assertEquals("'REGISTERED'", expectedAttempt[13])
        assertEquals("integer", expectedAttempt[20])
        expectedAttempt[13] = "'CAPTURING'"
        expectedAttempt[21] = startedAtEpochMillis.toString()
        assertEquals(
            before.copy(attemptRows = listOf(expectedAttempt)),
            after,
        )
    }

    private fun assertOnlyOwningShootDeletionChanged(
        before: CaptureStartAuthoritySnapshot,
        after: CaptureStartAuthoritySnapshot,
        deletionGeneration: Long,
        requestedAtEpochMillis: Long,
    ) {
        assertEquals(1, before.owningShootRows.size)
        val expectedShoot = before.owningShootRows.single().toMutableList()
        assertEquals("integer", expectedShoot[6])
        assertEquals("1", expectedShoot[7])
        assertEquals("text", expectedShoot[8])
        assertEquals("'ACTIVE'", expectedShoot[9])
        assertEquals("integer", expectedShoot[10])
        assertEquals((deletionGeneration - 1L).toString(), expectedShoot[11])
        expectedShoot[7] = requestedAtEpochMillis.toString()
        expectedShoot[9] = "'DELETING'"
        expectedShoot[11] = deletionGeneration.toString()
        assertEquals(
            before.copy(owningShootRows = listOf(expectedShoot)),
            after,
        )
    }

    private fun SupportSQLiteDatabase.registrationAuthoritySnapshot(
        rawToken: String,
    ): RegistrationAuthoritySnapshot = RegistrationAuthoritySnapshot(
        sessionRows = rawSqlRows(
            """
            SELECT typeof(next_attempt_number), quote(next_attempt_number),
                   typeof(updated_at_epoch_millis), quote(updated_at_epoch_millis)
            FROM shoot_sessions
            WHERE session_id = ?
            """.trimIndent(),
            rawToken = SESSION_ID,
        ),
        attemptRows = rawSqlRows(
            """
            SELECT typeof(command_token), quote(command_token),
                   typeof(session_id), quote(session_id),
                   typeof(pose_id), quote(pose_id),
                   typeof(pose_index), quote(pose_index),
                   typeof(attempt_number), quote(attempt_number),
                   typeof(trigger_type), quote(trigger_type),
                   typeof(lifecycle_state), quote(lifecycle_state),
                   typeof(reconciliation_required), quote(reconciliation_required),
                   typeof(captured_deletion_generation), quote(captured_deletion_generation),
                   typeof(created_at_epoch_millis), quote(created_at_epoch_millis),
                   typeof(updated_at_epoch_millis), quote(updated_at_epoch_millis),
                   typeof(confirmed_at_epoch_millis), quote(confirmed_at_epoch_millis)
            FROM capture_attempts
            WHERE CAST(command_token AS BLOB) = CAST(? AS BLOB)
            """.trimIndent(),
            rawToken = rawToken,
        ),
        journalRows = rawSqlRows(
            """
            SELECT typeof(command_token), quote(command_token),
                   typeof(burst_ordinal), quote(burst_ordinal),
                   typeof(relative_final_path), quote(relative_final_path),
                   typeof(relative_temp_path), quote(relative_temp_path),
                   typeof(relative_quarantine_path), quote(relative_quarantine_path),
                   typeof(stage), quote(stage),
                   typeof(byte_count), quote(byte_count),
                   typeof(sha256), quote(sha256),
                   typeof(captured_at_epoch_millis), quote(captured_at_epoch_millis),
                   typeof(last_failure_code), quote(last_failure_code),
                   typeof(reconciliation_required), quote(reconciliation_required),
                   typeof(created_at_epoch_millis), quote(created_at_epoch_millis),
                   typeof(updated_at_epoch_millis), quote(updated_at_epoch_millis)
            FROM capture_file_operations
            WHERE CAST(command_token AS BLOB) = CAST(? AS BLOB)
            ORDER BY burst_ordinal
            """.trimIndent(),
            rawToken = rawToken,
        ),
    )

    private fun SupportSQLiteDatabase.rawSqlRows(
        sql: String,
        rawToken: String,
    ): List<List<String>> = query(sql, arrayOf(rawToken)).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(List(cursor.columnCount) { index -> cursor.getString(index) })
            }
        }
    }

    private fun SupportSQLiteDatabase.registrationJournal(
        rawToken: String,
    ): List<ExpectedRegistrationJournalRow> =
        query(
            """
            SELECT burst_ordinal, relative_final_path, relative_temp_path,
                   relative_quarantine_path, stage, byte_count, sha256,
                   captured_at_epoch_millis, last_failure_code, reconciliation_required,
                   created_at_epoch_millis, updated_at_epoch_millis
            FROM capture_file_operations
            WHERE command_token = ?
            ORDER BY burst_ordinal
            """.trimIndent(),
            arrayOf(rawToken),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val ordinal = cursor.getInt(0)
                    val expectedPaths = if (ordinal in 0..2) {
                        CaptureFileOperationPaths.forIdentity(
                            PrivateOutputIdentity(CaptureToken(rawToken), ordinal),
                        )
                    } else {
                        null
                    }
                    add(
                        ExpectedRegistrationJournalRow(
                            burstOrdinal = ordinal,
                            pathsMatch = expectedPaths != null &&
                                cursor.getString(1) == expectedPaths.relativeFinalPath &&
                                cursor.getString(2) == expectedPaths.relativeTempPath &&
                                cursor.getString(3) == expectedPaths.relativeQuarantinePath,
                            stage = cursor.getString(4),
                            byteCountPresent = !cursor.isNull(5),
                            sha256Present = !cursor.isNull(6),
                            capturedAtPresent = !cursor.isNull(7),
                            failurePresent = !cursor.isNull(8),
                            reconciliationRequired = cursor.getInt(9) == 1,
                            createdAtEpochMillis = cursor.getLong(10),
                            updatedAtEpochMillis = cursor.getLong(11),
                        ),
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.dropCaptureFileOperationValidationTriggers() {
        listOf(
            "trigger_capture_file_operations_burst_ordinal_insert",
            "trigger_capture_file_operations_burst_ordinal_update",
            "trigger_capture_file_operations_state_insert",
            "trigger_capture_file_operations_state_update",
        ).forEach { trigger -> execSQL("DROP TRIGGER IF EXISTS `$trigger`") }
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

    private data class RegistrationAuthorityCounts(
        val sessionCounter: Long,
        val attemptCount: Int,
        val journalCount: Int,
    )

    private data class RegistrationAuthoritySnapshot(
        val sessionRows: List<List<String>>,
        val attemptRows: List<List<String>>,
        val journalRows: List<List<String>>,
    )

    private data class CaptureStartAuthoritySnapshot(
        val owningShootRows: List<List<String>>,
        val owningSessionRows: List<List<String>>,
        val currentPoseRows: List<List<String>>,
        val attemptRows: List<List<String>>,
        val journalRows: List<List<String>>,
        val privateOutputRows: List<List<String>>,
        val confirmationReceiptRows: List<List<String>>,
        val exportOutboxRows: List<List<String>>,
        val exportOutputRows: List<List<String>>,
    )

    private data class CaptureStartCorruptionCase(
        val suffix: String,
        val corrupt: (SupportSQLiteDatabase, String) -> Unit,
    )

    private data class ExpectedRegistrationJournalRow(
        val burstOrdinal: Int,
        val pathsMatch: Boolean,
        val stage: String,
        val byteCountPresent: Boolean,
        val sha256Present: Boolean,
        val capturedAtPresent: Boolean,
        val failurePresent: Boolean,
        val reconciliationRequired: Boolean,
        val createdAtEpochMillis: Long,
        val updatedAtEpochMillis: Long,
    )

    companion object {
        private const val SHOOT_ID = "shoot-1"
        private const val SECOND_SHOOT_ID = "shoot-2"
        private const val SESSION_ID = "session-1"
        private const val POSE_ID = "pose-0"
    }
}
