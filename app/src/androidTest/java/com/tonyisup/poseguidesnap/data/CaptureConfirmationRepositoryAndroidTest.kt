package com.tonyisup.poseguidesnap.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.db.CaptureFileOperationStateTriggers
import com.tonyisup.poseguidesnap.domain.session.CaptureAttempt
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.CaptureTrigger
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.AbstractList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureConfirmationRepositoryAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "capture_confirmation_repository_android_test_${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        closeDatabase()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun confirmationDerivesOutputsFromFinalJournalAndConsumesRows() {
        val fixture = prepareCapturingAttempt("non-final-token", poseCount = 2)

        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )

        assertEquals(
            listOf(listOf(1L, "ACTIVE", CONFIRMED_AT)),
            fixture.sqlite.rows(
                "SELECT current_pose_index, lifecycle_state, updated_at_epoch_millis " +
                    "FROM shoot_sessions WHERE session_id = ?",
                SESSION_ID,
            ),
        )
        assertEquals(
            listOf(listOf("CONFIRMED", CONFIRMED_AT, CONFIRMED_AT)),
            fixture.sqlite.rows(
                "SELECT lifecycle_state, updated_at_epoch_millis, confirmed_at_epoch_millis " +
                    "FROM capture_attempts WHERE command_token = ?",
                fixture.command.token.value,
            ),
        )
        assertEquals(
            listOf(listOf(0L, 1L, DELETION_GENERATION, CONFIRMED_AT)),
            fixture.sqlite.rows(
                "SELECT from_pose_index, to_pose_index, applied_deletion_generation, " +
                    "applied_at_epoch_millis FROM capture_confirmation_receipts " +
                    "WHERE command_token = ?",
                fixture.command.token.value,
            ),
        )
        assertCommittedOutputs(fixture)

        val corruptions = listOf<Pair<String, (ConfirmationFixture) -> Unit>>(
            "missing ordinal" to { candidate ->
                candidate.sqlite.execSQL(
                    "DELETE FROM capture_file_operations " +
                        "WHERE command_token = ? AND burst_ordinal = 2",
                    arrayOf<Any>(candidate.command.token.value),
                )
            },
            "partial evidence" to { candidate ->
                candidate.mutateJournalWithoutStateTrigger {
                    execSQL(
                        "UPDATE capture_file_operations SET sha256 = NULL " +
                            "WHERE command_token = ? AND burst_ordinal = 0",
                        arrayOf<Any>(candidate.command.token.value),
                    )
                }
            },
            "non-final stage" to { candidate ->
                candidate.mutateJournalWithoutStateTrigger {
                    execSQL(
                        "UPDATE capture_file_operations SET stage = 'TEMP_SYNCED' " +
                            "WHERE command_token = ? AND burst_ordinal = 0",
                        arrayOf<Any>(candidate.command.token.value),
                    )
                }
            },
            "conflicting path" to { candidate ->
                candidate.mutateJournalWithoutStateTrigger {
                    execSQL(
                        "UPDATE capture_file_operations SET relative_final_path = " +
                            "'capture-candidates/conflict.jpg' " +
                            "WHERE command_token = ? AND burst_ordinal = 0",
                        arrayOf<Any>(candidate.command.token.value),
                    )
                }
            },
            "reconciliation marker" to { candidate ->
                candidate.mutateJournalWithoutStateTrigger {
                    execSQL(
                        "UPDATE capture_file_operations SET " +
                            "last_failure_code = 'STATE_MISMATCH', " +
                            "reconciliation_required = 1 " +
                            "WHERE command_token = ? AND burst_ordinal = 0",
                        arrayOf<Any>(candidate.command.token.value),
                    )
                }
            },
        )
        corruptions.forEachIndexed { index, (name, corrupt) ->
            resetDatabaseForNextScenario()
            val candidate = prepareCapturingAttempt("confirmation-invalid-$index", poseCount = 2)
            corrupt(candidate)
            val before = candidate.sqlite.journalAuthoritySnapshot()
            assertEquals(
                name,
                CaptureConfirmationResult.Rejected(
                    CaptureConfirmationRejectionReason.JOURNAL_AUTHORITY_INVALID,
                ),
                candidate.repository.confirmAndAdvance(
                    candidate.command,
                    candidate.exportTargets,
                    CONFIRMED_AT,
                ),
            )
            assertEquals(name, before, candidate.sqlite.journalAuthoritySnapshot())
            assertNoConfirmationRows(candidate)
        }
    }

    @Test
    fun confirmationRejectsBackwardAndAcceptsEqualBoundaryTimestamps() {
        data class ClockCase(
            val name: String,
            val mutate: (ConfirmationFixture) -> Unit,
        )

        val cases = buildList {
            add(
                ClockCase("attempt and dependent journal") { fixture ->
                    fixture.mutateJournalWithoutStateTrigger {
                        execSQL(
                            "UPDATE capture_file_operations SET " +
                                "captured_at_epoch_millis = 40, updated_at_epoch_millis = 40 " +
                                "WHERE command_token = ?",
                            arrayOf<Any>(fixture.command.token.value),
                        )
                    }
                    fixture.sqlite.execSQL(
                        "UPDATE capture_attempts SET updated_at_epoch_millis = 40 " +
                            "WHERE command_token = ?",
                        arrayOf<Any>(fixture.command.token.value),
                    )
                },
            )
            add(
                ClockCase("session") { fixture ->
                    fixture.sqlite.execSQL(
                        "UPDATE shoot_sessions SET updated_at_epoch_millis = 40 " +
                            "WHERE session_id = ?",
                        arrayOf<Any>(SESSION_ID),
                    )
                },
            )
            (0..2).forEach { ordinal ->
                add(
                    ClockCase("journal updated $ordinal") { fixture ->
                        fixture.mutateJournalWithoutStateTrigger {
                            execSQL(
                                "UPDATE capture_file_operations SET updated_at_epoch_millis = 40 " +
                                    "WHERE command_token = ? AND burst_ordinal = ?",
                                arrayOf<Any>(fixture.command.token.value, ordinal),
                            )
                        }
                    },
                )
                add(
                    ClockCase("journal captured $ordinal") { fixture ->
                        fixture.mutateJournalWithoutStateTrigger {
                            execSQL(
                                "UPDATE capture_file_operations SET " +
                                    "captured_at_epoch_millis = 40, updated_at_epoch_millis = 40 " +
                                    "WHERE command_token = ? AND burst_ordinal = ?",
                                arrayOf<Any>(fixture.command.token.value, ordinal),
                            )
                        }
                    },
                )
            }
        }

        cases.forEachIndexed { index, clockCase ->
            val fixture = prepareCapturingAttempt("clock-boundary-$index", poseCount = 2)
            clockCase.mutate(fixture)
            val before = fixture.sqlite.journalAuthoritySnapshot()
            assertEquals(
                clockCase.name,
                CaptureConfirmationResult.Rejected(
                    CaptureConfirmationRejectionReason.INVALID_TIMESTAMP,
                ),
                fixture.repository.confirmAndAdvance(
                    fixture.command,
                    fixture.exportTargets,
                    39L,
                ),
            )
            assertEquals(clockCase.name, before, fixture.sqlite.journalAuthoritySnapshot())
            assertEquals(
                clockCase.name,
                CaptureConfirmationResult.Applied,
                fixture.repository.confirmAndAdvance(
                    fixture.command,
                    fixture.exportTargets,
                    40L,
                ),
            )
            if (index != cases.lastIndex) resetDatabaseForNextScenario()
        }
    }

    @Test
    fun confirmationFaultAfterJournalDeleteRollsBackEverything() {
        val fixture = prepareCapturingAttempt("fault-after-journal-delete", poseCount = 2)
        val before = fixture.sqlite.journalAuthoritySnapshot()
        val faultingRepository = RoomShootRepository(
            requireNotNull(database),
            {},
            { throw ConfirmationAfterJournalDeleteTestException() },
        )

        try {
            faultingRepository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                CONFIRMED_AT,
            )
            throw AssertionError("expected injected post-journal-delete fault")
        } catch (_: ConfirmationAfterJournalDeleteTestException) {
            // Expected: Room rolls the journal deletion and every immutable write back together.
        }

        assertEquals(before, fixture.sqlite.journalAuthoritySnapshot())
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )

        resetDatabaseForNextScenario()
        val driftFixture = prepareCapturingAttempt("journal-postcondition-drift", poseCount = 2)
        driftFixture.sqlite.execSQL(
            "DROP TRIGGER IF EXISTS `trigger_capture_file_operations_state_update`",
        )
        driftFixture.sqlite.execSQL(
            """
            CREATE TRIGGER `test_confirmation_journal_postcondition_drift`
            AFTER INSERT ON `private_capture_outputs`
            FOR EACH ROW
            WHEN NEW.`burst_ordinal` = 2
            BEGIN
                UPDATE capture_file_operations
                SET relative_final_path = CAST(relative_final_path AS BLOB)
                WHERE command_token = NEW.command_token AND burst_ordinal = 0;
            END
            """.trimIndent(),
        )
        val beforeDrift = driftFixture.sqlite.journalAuthoritySnapshot()
        try {
            assertEquals(
                CaptureConfirmationResult.Rejected(
                    CaptureConfirmationRejectionReason.TRANSACTION_CARDINALITY_FAILURE,
                ),
                driftFixture.repository.confirmAndAdvance(
                    driftFixture.command,
                    driftFixture.exportTargets,
                    CONFIRMED_AT,
                ),
            )
            assertEquals(beforeDrift, driftFixture.sqlite.journalAuthoritySnapshot())
        } finally {
            driftFixture.sqlite.execSQL(
                "DROP TRIGGER IF EXISTS `test_confirmation_journal_postcondition_drift`",
            )
            CaptureFileOperationStateTriggers.install(driftFixture.sqlite)
        }
    }

    @Test
    fun confirmationDeletionAndDuplicateRacesHaveOneWinner() {
        val deletionFixture = prepareCapturingAttempt("confirmation-deletion-race", poseCount = 2)
        val deletionPeer = AppDatabase.create(context, databaseName)
        try {
            val results = runConcurrently(
                first = {
                    deletionFixture.repository.confirmAndAdvance(
                        deletionFixture.command,
                        deletionFixture.exportTargets,
                        CONFIRMED_AT,
                    )
                },
                second = {
                    RoomShootRepository(deletionPeer).beginShootDeletion(SHOOT_ID, CONFIRMED_AT)
                },
            )
            check(
                results.first == CaptureConfirmationResult.Applied ||
                    results.first == CaptureConfirmationResult.BlockedByDeletion,
            ) { "confirmation/deletion race returned an unexpected result" }
            if (results.first == CaptureConfirmationResult.Applied) {
                assertEquals(BeginShootDeletionResult.Began(8L, 3, 1, 0), results.second)
                assertEquals(
                    listOf(listOf("CONFIRMED", 3L, 1L, 1L, 3L, 0L)),
                    deletionFixture.sqlite.rows(
                        "SELECT attempt.lifecycle_state, " +
                            "(SELECT COUNT(*) FROM private_capture_outputs), " +
                            "(SELECT COUNT(*) FROM capture_confirmation_receipts), " +
                            "(SELECT COUNT(*) FROM capture_export_outboxes), " +
                            "(SELECT COUNT(*) FROM capture_export_outputs), " +
                            "(SELECT COUNT(*) FROM capture_file_operations) " +
                            "FROM capture_attempts AS attempt WHERE command_token = ?",
                        deletionFixture.command.token.value,
                    ),
                )
            } else {
                assertEquals(BeginShootDeletionResult.Began(8L, 0, 0, 0), results.second)
                assertNoConfirmationRows(deletionFixture)
            }
        } finally {
            deletionPeer.close()
        }

        resetDatabaseForNextScenario()
        val duplicateFixture = prepareCapturingAttempt("duplicate-confirmation-race", poseCount = 2)
        val duplicatePeer = AppDatabase.create(context, databaseName)
        try {
            val results = runConcurrently(
                first = {
                    duplicateFixture.repository.confirmAndAdvance(
                        duplicateFixture.command,
                        duplicateFixture.exportTargets,
                        CONFIRMED_AT,
                    )
                },
                second = {
                    RoomShootRepository(duplicatePeer).confirmAndAdvance(
                        duplicateFixture.command,
                        duplicateFixture.exportTargets,
                        CONFIRMED_AT,
                    )
                },
            )
            assertEquals(
                setOf(CaptureConfirmationResult.Applied, CaptureConfirmationResult.AlreadyApplied),
                setOf(results.first, results.second),
            )
            assertCommittedOutputs(duplicateFixture)
        } finally {
            duplicatePeer.close()
        }
    }

    @Test
    fun finalPoseConfirmationCompletesSessionWhileRetainingValidCurrentIndex() {
        val fixture = prepareCapturingAttempt("final-token", poseCount = 1)

        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )

        assertEquals(
            listOf(listOf(0L, "COMPLETED", CONFIRMED_AT)),
            fixture.sqlite.rows(
                "SELECT current_pose_index, lifecycle_state, updated_at_epoch_millis " +
                    "FROM shoot_sessions WHERE session_id = ?",
                SESSION_ID,
            ),
        )
        assertEquals(
            listOf(listOf(0L, null, DELETION_GENERATION, CONFIRMED_AT)),
            fixture.sqlite.rows(
                "SELECT from_pose_index, to_pose_index, applied_deletion_generation, " +
                    "applied_at_epoch_millis FROM capture_confirmation_receipts " +
                    "WHERE command_token = ?",
                fixture.command.token.value,
            ),
        )
        assertCommittedOutputs(fixture)
    }

    @Test
    fun exactDuplicateReturnsAlreadyAppliedWithoutChangingRowsOrTimestamps() {
        val fixture = prepareUnregisteredAttempt("duplicate-token", poseCount = 2)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            CaptureConfirmationResult.AlreadyApplied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun confirmationReplayRequiresNoResidualJournalAcrossReopen() {
        var fixture = prepareUnregisteredAttempt("reopen-token", poseCount = 2)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        val before = fixture.sqlite.authoritySnapshot()
        closeDatabase()

        val reopened = openDatabase()
        fixture = fixture.copy(
            sqlite = reopened.openHelper.writableDatabase,
            repository = RoomShootRepository(reopened),
        )
        assertEquals(
            CaptureConfirmationResult.AlreadyApplied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())

        fixture.sqlite.seedExpectingReservationJournalRows(
            commandToken = fixture.command.token.value,
            ordinals = listOf(0),
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
        )
        val withResidualAuthority = fixture.sqlite.journalAuthoritySnapshot()
        assertEquals(
            CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.JOURNAL_AUTHORITY_INVALID,
            ),
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(withResidualAuthority, fixture.sqlite.journalAuthoritySnapshot())
    }

    @Test
    fun duplicateFinalReceiptWithActiveSessionFailsLoudWithoutMutation() {
        val fixture = prepareUnregisteredAttempt("final-active-token", poseCount = 1)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture, finalPose = true)
        fixture.sqlite.execSQL(
            "UPDATE shoot_sessions SET lifecycle_state = 'ACTIVE' WHERE session_id = ?",
            arrayOf<Any>(SESSION_ID),
        )

        assertDuplicateFailsLoudWithoutMutation(
            fixture,
            "capture confirmation final receipt session is inconsistent",
        )
    }

    @Test
    fun duplicateNonFinalReceiptWithRewoundSessionFailsLoudWithoutMutation() {
        val fixture = prepareUnregisteredAttempt("rewound-token", poseCount = 2)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        fixture.sqlite.execSQL(
            "UPDATE shoot_sessions SET current_pose_index = 0 WHERE session_id = ?",
            arrayOf<Any>(SESSION_ID),
        )

        assertDuplicateFailsLoudWithoutMutation(
            fixture,
            "capture confirmation receipt session is unreachable",
        )
    }

    @Test
    fun duplicateNonFinalReceiptWithCompletedSessionButNoFinalReceiptFailsLoud() {
        val fixture = prepareUnregisteredAttempt("missing-final-token", poseCount = 3)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        fixture.sqlite.execSQL(
            "UPDATE shoot_sessions SET lifecycle_state = 'COMPLETED' WHERE session_id = ?",
            arrayOf<Any>(SESSION_ID),
        )

        assertDuplicateFailsLoudWithoutMutation(
            fixture,
            "capture confirmation completed session has no final receipt",
        )
    }

    @Test
    fun duplicateNonFinalReceiptAfterCoherentLaterAdvancementReturnsAlreadyApplied() {
        val fixture = prepareUnregisteredAttempt("later-original-token", poseCount = 3)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(
            fixture,
            rawToken = "later-second-token",
            poseIndex = 1,
            attemptNumber = 1L,
            confirmedAtEpochMillis = CONFIRMED_AT + 10L,
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            CaptureConfirmationResult.AlreadyApplied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun duplicateWithCorruptOutboxCreatedTimestampFailsLoudWithoutMutation() {
        val fixture = prepareUnregisteredAttempt("outbox-created-token", poseCount = 2)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        fixture.sqlite.execSQL(
            "UPDATE capture_export_outboxes SET created_at_epoch_millis = 51 " +
                "WHERE command_token = ?",
            arrayOf<Any>(fixture.command.token.value),
        )

        assertDuplicateFailsLoudWithoutMutation(
            fixture,
            "capture confirmation export outbox authority is inconsistent",
        )
    }

    @Test
    fun duplicateWithCorruptExportOutputCreatedTimestampFailsLoudWithoutMutation() {
        val fixture = prepareUnregisteredAttempt("export-created-token", poseCount = 2)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        fixture.sqlite.execSQL(
            "UPDATE capture_export_outputs SET created_at_epoch_millis = 51 " +
                "WHERE command_token = ? AND burst_ordinal = 1",
            arrayOf<Any>(fixture.command.token.value),
        )

        assertDuplicateFailsLoudWithoutMutation(
            fixture,
            "capture confirmation export output authority is inconsistent",
        )
    }

    @Test
    fun duplicateIgnoresPermittedMutableExportFieldsWithoutMutation() {
        val fixture = prepareUnregisteredAttempt("mutable-export-token", poseCount = 2)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        fixture.sqlite.execSQL(
            "UPDATE capture_export_outboxes " +
                "SET lifecycle_state = 'RETRY_PENDING', updated_at_epoch_millis = 700, " +
                "retry_metadata = 'retry-later' WHERE command_token = ?",
            arrayOf<Any>(fixture.command.token.value),
        )
        fixture.sqlite.execSQL(
            "UPDATE capture_export_outputs " +
                "SET lifecycle_state = 'EXPORTED', " +
                "claim_token = 'later-claim-' || burst_ordinal, " +
                "media_uri_string = 'content://later/' || burst_ordinal, " +
                "ambiguity_state = 'RESOLVED', updated_at_epoch_millis = 701 " +
                "WHERE command_token = ?",
            arrayOf<Any>(fixture.command.token.value),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            CaptureConfirmationResult.AlreadyApplied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun duplicateUsesPersistedPrivateAuthorityWithoutCallerInput() {
        val fixture = prepareUnregisteredAttempt("private-conflict-token", poseCount = 2)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        fixture.sqlite.execSQL(
            "UPDATE private_capture_outputs SET relative_path = 'private/retry/changed-1.jpg' " +
                "WHERE command_token = ? AND burst_ordinal = 1",
            arrayOf<Any>(fixture.command.token.value),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            CaptureConfirmationResult.AlreadyApplied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun duplicateWithChangedExportTargetMetadataIsRejectedWithoutMutation() {
        val fixture = prepareUnregisteredAttempt("export-conflict-token", poseCount = 2)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        val before = fixture.sqlite.authoritySnapshot()
        val changedExportTargets = fixture.exportTargets.mapIndexed { index, target ->
            if (index == 2) target.copy(intendedDisplayName = "changed-retry-name.jpg") else target
        }

        assertEquals(
            CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.INVALID_EXPORT_TARGETS,
            ),
            fixture.repository.confirmAndAdvance(
                fixture.command,
                changedExportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun firstApplicationSnapshotsExportTargetsAndDerivesPrivateOutputsFromJournal() {
        val fixture = prepareCapturingAttempt("mutable-input-token", poseCount = 2)
        val changedExportTargets = fixture.exportTargets.map { target ->
            target.copy(intendedDisplayName = "mutated-${target.identity.ordinal}.jpg")
        }
        val exportTargets = MutatingAfterFirstTraversalList(
            fixture.exportTargets,
            changedExportTargets,
        )

        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                exportTargets,
                CONFIRMED_AT,
            ),
        )

        assertEquals(changedExportTargets, exportTargets.currentValues())
        assertCommittedOutputs(fixture)
    }

    @Test
    fun unknownAttemptIsRejectedWithoutTransactionRowsOrSessionMutation() {
        val fixture = prepareUnregisteredAttempt("unknown-attempt-token", poseCount = 2)
        assertEquals(
            listOf(listOf(0L)),
            fixture.sqlite.rows(
                "SELECT COUNT(*) FROM capture_attempts WHERE command_token = ?",
                fixture.command.token.value,
            ),
        )

        assertFirstApplicationRejectedWithoutMutation(
            fixture,
            CaptureConfirmationRejectionReason.UNKNOWN_ATTEMPT,
        )
    }

    @Test
    fun exactTokenWithConflictingCommandPoseIsRejectedWithoutMutation() {
        val fixture = prepareCapturingAttempt("token-pose-conflict-token", poseCount = 2)
        val conflictingFixture = fixture.copy(
            command = ShootEffect.ConfirmAndAdvanceCapture(
                token = fixture.command.token,
                poseId = "conflicting-command-pose",
                poseIndex = fixture.command.poseIndex,
                outputs = fixture.command.outputs,
            ),
        )
        assertEquals(
            listOf(listOf(fixture.command.token.value, "pose-0", 0L)),
            fixture.sqlite.rows(
                "SELECT command_token, pose_id, pose_index FROM capture_attempts " +
                    "WHERE command_token = ?",
                conflictingFixture.command.token.value,
            ),
        )

        assertFirstApplicationRejectedWithoutMutation(
            conflictingFixture,
            CaptureConfirmationRejectionReason.TOKEN_POSE_CONFLICT,
        )
    }

    @Test
    fun registeredAttemptIsRejectedWithoutMutation() {
        val fixture = prepareRegisteredAttempt("registered-attempt-token", poseCount = 2)
        assertEquals(
            listOf(listOf("REGISTERED")),
            fixture.sqlite.rows(
                "SELECT lifecycle_state FROM capture_attempts WHERE command_token = ?",
                fixture.command.token.value,
            ),
        )

        assertFirstApplicationRejectedWithoutMutation(
            fixture,
            CaptureConfirmationRejectionReason.WRONG_ATTEMPT_STATE,
        )
    }

    @Test
    fun deletingShootBlocksConfirmationWithoutMutation() {
        val fixture = prepareCapturingAttempt("deleting-shoot-token", poseCount = 2)
        fixture.sqlite.execSQL(
            "UPDATE shoots SET lifecycle_state = 'DELETING' WHERE shoot_id = ?",
            arrayOf<Any>(SHOOT_ID),
        )
        assertEquals(
            listOf(listOf("DELETING", DELETION_GENERATION)),
            fixture.sqlite.rows(
                "SELECT lifecycle_state, deletion_generation FROM shoots WHERE shoot_id = ?",
                SHOOT_ID,
            ),
        )

        assertFirstApplicationResultWithoutMutation(
            fixture,
            CaptureConfirmationResult.BlockedByDeletion,
        )
    }

    @Test
    fun changedDeletionGenerationBlocksConfirmationWithoutMutation() {
        val fixture = prepareCapturingAttempt("changed-generation-token", poseCount = 2)
        fixture.sqlite.execSQL(
            "UPDATE shoots SET deletion_generation = ? WHERE shoot_id = ?",
            arrayOf<Any>(DELETION_GENERATION + 1L, SHOOT_ID),
        )
        assertEquals(
            listOf(listOf("ACTIVE", DELETION_GENERATION + 1L)),
            fixture.sqlite.rows(
                "SELECT lifecycle_state, deletion_generation FROM shoots WHERE shoot_id = ?",
                SHOOT_ID,
            ),
        )

        assertFirstApplicationResultWithoutMutation(
            fixture,
            CaptureConfirmationResult.BlockedByDeletion,
        )
    }

    @Test
    fun negativeDeletionGenerationFailsLoudWithoutConfirmationMutation() {
        val fixture = prepareCapturingAttempt("negative-confirmation-generation-token", poseCount = 2)
        fixture.sqlite.execSQL(
            "UPDATE shoots SET deletion_generation = -1 WHERE shoot_id = ?",
            arrayOf<Any>(SHOOT_ID),
        )
        fixture.sqlite.execSQL(
            "UPDATE capture_attempts SET captured_deletion_generation = -1 " +
                "WHERE command_token = ?",
            arrayOf<Any>(fixture.command.token.value),
        )

        assertFirstApplicationRejectedWithoutMutation(
            fixture,
            CaptureConfirmationRejectionReason.JOURNAL_AUTHORITY_INVALID,
        )
    }

    @Test
    fun inactiveSessionIsRejectedWithoutMutation() {
        val fixture = prepareCapturingAttempt("inactive-session-token", poseCount = 2)
        fixture.sqlite.execSQL(
            "UPDATE shoot_sessions SET lifecycle_state = 'COMPLETED' WHERE session_id = ?",
            arrayOf<Any>(SESSION_ID),
        )
        assertEquals(
            listOf(listOf("COMPLETED")),
            fixture.sqlite.rows(
                "SELECT lifecycle_state FROM shoot_sessions WHERE session_id = ?",
                SESSION_ID,
            ),
        )

        assertFirstApplicationRejectedWithoutMutation(
            fixture,
            CaptureConfirmationRejectionReason.INACTIVE_SESSION,
        )
    }

    @Test
    fun advancedSessionCurrentPoseIsRejectedAsStaleWithoutMutation() {
        val fixture = prepareCapturingAttempt("advanced-session-token", poseCount = 2)
        fixture.sqlite.execSQL(
            "UPDATE shoot_sessions SET current_pose_index = 1 WHERE session_id = ?",
            arrayOf<Any>(SESSION_ID),
        )
        assertEquals(
            listOf(listOf(1L)),
            fixture.sqlite.rows(
                "SELECT current_pose_index FROM shoot_sessions WHERE session_id = ?",
                SESSION_ID,
            ),
        )

        assertFirstApplicationRejectedWithoutMutation(
            fixture,
            CaptureConfirmationRejectionReason.STALE_POSE,
        )
    }

    @Test
    fun gappedOrderedPoseSetFailsLoudWithoutMutation() {
        val fixture = prepareCapturingAttempt("gapped-pose-token", poseCount = 3)
        fixture.sqlite.execSQL(
            "DELETE FROM shoot_poses WHERE shoot_id = ? AND pose_index = 1",
            arrayOf<Any>(SHOOT_ID),
        )
        assertEquals(
            listOf(listOf(0L), listOf(2L)),
            fixture.sqlite.rows(
                "SELECT pose_index FROM shoot_poses WHERE shoot_id = ? ORDER BY pose_index",
                SHOOT_ID,
            ),
        )

        assertFirstApplicationFailsLoudWithoutMutation(
            fixture,
            "capture confirmation pose sequence has a gap",
        )
    }

    @Test
    fun reconciliationRequiredCapturingAttemptIsRejectedWithoutMutation() {
        val fixture = prepareCapturingAttempt("reconciliation-token", poseCount = 2)
        fixture.sqlite.execSQL(
            "UPDATE capture_attempts SET reconciliation_required = 1 WHERE command_token = ?",
            arrayOf<Any>(fixture.command.token.value),
        )

        assertFirstApplicationRejectedWithoutMutation(
            fixture,
            CaptureConfirmationRejectionReason.WRONG_ATTEMPT_STATE,
        )
    }

    @Test
    fun preconfirmedCapturingAttemptIsRejectedWithoutMutation() {
        val fixture = prepareCapturingAttempt("preconfirmed-token", poseCount = 2)
        fixture.sqlite.execSQL(
            "UPDATE capture_attempts SET confirmed_at_epoch_millis = 49 WHERE command_token = ?",
            arrayOf<Any>(fixture.command.token.value),
        )

        assertFirstApplicationRejectedWithoutMutation(
            fixture,
            CaptureConfirmationRejectionReason.WRONG_ATTEMPT_STATE,
        )
    }

    @Test
    fun changedCurrentPoseIdentityIsRejectedAsStalePoseWithoutMutation() {
        val fixture = prepareCapturingAttempt("changed-pose-token", poseCount = 2)
        fixture.sqlite.execSQL(
            "UPDATE shoot_poses SET pose_id = 'replacement-pose' " +
                "WHERE shoot_id = ? AND pose_index = 0",
            arrayOf<Any>(SHOOT_ID),
        )

        assertFirstApplicationRejectedWithoutMutation(
            fixture,
            CaptureConfirmationRejectionReason.STALE_POSE,
        )
    }

    @Test
    fun privateOutputInsertConstraintFailureRollsBackEntireConfirmationTransaction() {
        val fixture = prepareCapturingAttempt("late-private-insert-token", poseCount = 2)
        fixture.installAbortingTestTrigger(
            name = "test_fail_private_output_insert",
            timingAndEvent = "BEFORE INSERT",
            table = "private_capture_outputs",
            whenClause = "NEW.burst_ordinal = 1",
            message = "test private output insert failure",
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertConstraintFailureRollsBack(
            fixture = fixture,
            before = before,
            expectedMessage = "test private output insert failure",
        )
    }

    @Test
    fun attemptCasFailureAfterPrivateOutputsRollsBackTriggerMutationAndWrites() {
        val fixture = prepareCapturingAttempt("late-attempt-cas-token", poseCount = 2)
        fixture.installTestTrigger(
            name = "test_force_attempt_cas_failure",
            timingAndEvent = "AFTER INSERT",
            table = "private_capture_outputs",
            whenClause = "NEW.burst_ordinal = 2",
            body = """
                UPDATE capture_attempts
                SET lifecycle_state = 'REGISTERED'
                WHERE command_token = NEW.command_token;
            """.trimIndent(),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertTypedFailureRollsBack(
            fixture = fixture,
            before = before,
            expectedResult = CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.TRANSACTION_CAS_FAILED,
            ),
        )
    }

    @Test
    fun sessionCasFailureAfterAttemptConfirmationRollsBackTriggerMutationAndWrites() {
        val fixture = prepareCapturingAttempt("late-session-cas-token", poseCount = 2)
        fixture.installTestTrigger(
            name = "test_force_session_cas_failure",
            timingAndEvent = "AFTER UPDATE OF lifecycle_state",
            table = "capture_attempts",
            whenClause =
                "OLD.lifecycle_state = 'CAPTURING' AND NEW.lifecycle_state = 'CONFIRMED'",
            body = """
                UPDATE shoot_sessions
                SET current_pose_index = current_pose_index + 1,
                    lifecycle_state = 'COMPLETED',
                    updated_at_epoch_millis = 999
                WHERE session_id = NEW.session_id;
            """.trimIndent(),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertTypedFailureRollsBack(
            fixture = fixture,
            before = before,
            expectedResult = CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.TRANSACTION_CAS_FAILED,
            ),
        )
    }

    @Test
    fun receiptInsertConstraintFailureRollsBackPriorWritesAndCasTransitions() {
        val fixture = prepareCapturingAttempt("late-receipt-insert-token", poseCount = 2)
        fixture.installAbortingTestTrigger(
            name = "test_fail_receipt_insert",
            timingAndEvent = "BEFORE INSERT",
            table = "capture_confirmation_receipts",
            message = "test receipt insert failure",
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertConstraintFailureRollsBack(
            fixture = fixture,
            before = before,
            expectedMessage = "test receipt insert failure",
        )
    }

    @Test
    fun outboxInsertConstraintFailureRollsBackReceiptAndEarlierWrites() {
        val fixture = prepareCapturingAttempt("late-outbox-insert-token", poseCount = 2)
        fixture.installAbortingTestTrigger(
            name = "test_fail_outbox_insert",
            timingAndEvent = "BEFORE INSERT",
            table = "capture_export_outboxes",
            message = "test outbox insert failure",
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertConstraintFailureRollsBack(
            fixture = fixture,
            before = before,
            expectedMessage = "test outbox insert failure",
        )
    }

    @Test
    fun laterExportOutputInsertConstraintFailureRollsBackEntireConfirmationTransaction() {
        val fixture = prepareCapturingAttempt("late-export-insert-token", poseCount = 2)
        fixture.installAbortingTestTrigger(
            name = "test_fail_later_export_output_insert",
            timingAndEvent = "BEFORE INSERT",
            table = "capture_export_outputs",
            whenClause = "NEW.burst_ordinal = 1",
            message = "test export output insert failure",
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertConstraintFailureRollsBack(
            fixture = fixture,
            before = before,
            expectedMessage = "test export output insert failure",
        )
    }

    @Test
    fun exportCardinalityFailureAfterThreeInsertsRollsBackTriggerDeletionAndWrites() {
        val fixture = prepareCapturingAttempt("late-cardinality-token", poseCount = 2)
        fixture.installTestTrigger(
            name = "test_force_export_cardinality_failure",
            timingAndEvent = "AFTER INSERT",
            table = "capture_export_outputs",
            whenClause = "NEW.burst_ordinal = 2",
            body = """
                DELETE FROM capture_export_outputs
                WHERE command_token = NEW.command_token AND burst_ordinal = 1;
            """.trimIndent(),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertTypedFailureRollsBack(
            fixture = fixture,
            before = before,
            expectedResult = CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.TRANSACTION_CARDINALITY_FAILURE,
            ),
        )
    }

    @Test
    fun registeredMissingAndNonFinalJournalAuthorityRejectWithoutMutation() {
        val registeredFixture = prepareRegisteredAttempt("journal-unavailable-registered-token", poseCount = 2)
        assertEquals(
            listOf(listOf("REGISTERED", null)),
            registeredFixture.sqlite.rows(
                "SELECT lifecycle_state, confirmed_at_epoch_millis FROM capture_attempts " +
                    "WHERE command_token = ?",
                registeredFixture.command.token.value,
            ),
        )
        val malformedExportTargets = registeredFixture.exportTargets.map { target ->
            target.copy(intendedDisplayName = "malformed-${target.identity.ordinal}.jpg")
        }
        assertJournalGateRejectsWithoutMutation(
            fixture = registeredFixture,
            exportTargets = malformedExportTargets,
            reason = CaptureConfirmationRejectionReason.WRONG_ATTEMPT_STATE,
        )
        resetDatabaseForNextScenario()

        // Scenario 2: CAPTURING attempt with zero journal rows (migrated-V3 shape).
        val migratedFixture = prepareCapturingAttempt("journal-unavailable-migrated-token", poseCount = 2)
        migratedFixture.sqlite.execSQL(
            "DELETE FROM capture_file_operations WHERE command_token = ?",
            arrayOf<Any>(migratedFixture.command.token.value),
        )
        assertEquals(
            listOf(listOf(0L)),
            migratedFixture.sqlite.rows(
                "SELECT COUNT(*) FROM capture_file_operations WHERE command_token = ?",
                migratedFixture.command.token.value,
            ),
        )
        assertJournalGateRejectsWithoutMutation(
            fixture = migratedFixture,
            exportTargets = migratedFixture.exportTargets,
            reason = CaptureConfirmationRejectionReason.JOURNAL_AUTHORITY_INVALID,
        )
        resetDatabaseForNextScenario()

        // Scenario 3: CAPTURING attempt with seeded EXPECTING_RESERVATION journal rows. The
        // unavailable gate still fires (before the journal authority count is even consulted)
        // and the seeded journal rows remain byte-identical.
        val journaledFixture = prepareCapturingAttempt("journal-unavailable-journaled-token", poseCount = 2)
        journaledFixture.sqlite.execSQL(
            "DELETE FROM capture_file_operations WHERE command_token = ?",
            arrayOf<Any>(journaledFixture.command.token.value),
        )
        journaledFixture.sqlite.seedExpectingReservationJournalRows(
            commandToken = journaledFixture.command.token.value,
            ordinals = listOf(0, 1, 2),
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
        )
        assertJournalGateRejectsWithoutMutation(
            fixture = journaledFixture,
            exportTargets = journaledFixture.exportTargets,
            reason = CaptureConfirmationRejectionReason.JOURNAL_AUTHORITY_INVALID,
        )
    }

    @Test
    fun confirmedReplayRejectsResidualJournalAuthority() {
        val fixture = prepareUnregisteredAttempt("residual-journal-token", poseCount = 2)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)

        // Positive control: with zero journal rows the receipt-backed replay classification is
        // unchanged and returns immutable AlreadyApplied evidence.
        val beforeReplay = fixture.sqlite.journalAuthoritySnapshot()
        assertEquals(
            CaptureConfirmationResult.AlreadyApplied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(beforeReplay, fixture.sqlite.journalAuthoritySnapshot())

        // A single residual journal row for the same token fail-closes the replay before
        // duplicate classification, without mutating anything — including the residual row.
        fixture.sqlite.seedExpectingReservationJournalRows(
            commandToken = fixture.command.token.value,
            ordinals = listOf(0),
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
        )
        val beforeRejectedReplay = fixture.sqlite.journalAuthoritySnapshot()
        assertEquals(
            CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.JOURNAL_AUTHORITY_INVALID,
            ),
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(beforeRejectedReplay, fixture.sqlite.journalAuthoritySnapshot())
    }

    @Test
    fun confirmedReplayRejectsByteEquivalentBlobResidualJournalAuthority() {
        val fixture = prepareUnregisteredAttempt("blob-residual-journal-token", poseCount = 2)
        fixture.sqlite.seedCoherentConfirmedConfirmationGraph(fixture)
        fixture.sqlite.seedExpectingReservationJournalRows(
            commandToken = fixture.command.token.value,
            ordinals = listOf(0),
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
        )

        fixture.sqlite.corruptJournalTokenStorageAsByteEquivalentBlob(
            fixture.command.token.value,
        )
        assertEquals(
            listOf(listOf("blob", fixture.command.token.value.encodeToByteArray().toHex())),
            fixture.sqlite.rows(
                "SELECT typeof(command_token), hex(command_token) " +
                    "FROM capture_file_operations " +
                    "WHERE CAST(command_token AS BLOB) = CAST(? AS BLOB)",
                fixture.command.token.value,
            ),
        )
        assertEquals(
            listOf(listOf(0L)),
            fixture.sqlite.rows(
                "SELECT COUNT(*) FROM capture_file_operations WHERE command_token = ?",
                fixture.command.token.value,
            ),
        )
        assertEquals(
            listOf(listOf(1L)),
            fixture.sqlite.rows(
                "SELECT COUNT(*) FROM capture_file_operations " +
                    "WHERE CAST(command_token AS BLOB) = CAST(? AS BLOB)",
                fixture.command.token.value,
            ),
        )
        assertEquals(
            1,
            fixture.sqlite.rows("PRAGMA foreign_key_check(`capture_file_operations`)").size,
        )
        val before = fixture.sqlite.journalAuthoritySnapshot()

        assertEquals(
            CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.JOURNAL_AUTHORITY_INVALID,
            ),
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.journalAuthoritySnapshot())
    }

    private fun ConfirmationFixture.installAbortingTestTrigger(
        name: String,
        timingAndEvent: String,
        table: String,
        whenClause: String? = null,
        message: String,
    ) {
        require('\'' !in message)
        installTestTrigger(
            name = name,
            timingAndEvent = timingAndEvent,
            table = table,
            whenClause = whenClause,
            body = "SELECT RAISE(ABORT, '$message');",
            message = message,
        )
    }

    private fun ConfirmationFixture.installTestTrigger(
        name: String,
        timingAndEvent: String,
        table: String,
        whenClause: String? = null,
        body: String,
        message: String? = null,
    ) {
        require(name.matches(Regex("[a-z_]+")))
        assertTestTriggerMetadataIsValueFree(name, message)
        sqlite.execSQL(
            """
            CREATE TRIGGER `$name`
            $timingAndEvent ON `$table`
            FOR EACH ROW
            ${whenClause?.let { clause -> "WHEN $clause" }.orEmpty()}
            BEGIN
                $body
            END
            """.trimIndent(),
        )
    }

    private fun ConfirmationFixture.assertTestTriggerMetadataIsValueFree(
        name: String,
        message: String?,
    ) {
        val triggerMetadata = listOfNotNull(name, message)
        val labels = sqlite.rows(
            "SELECT name FROM shoots UNION ALL SELECT label FROM shoot_poses",
        ).flatten().filterIsInstance<String>()
        val forbiddenValues = buildList {
            add(command.token.value)
            addAll(privateOutputs.map { output -> output.relativePath })
            addAll(exportTargets.map { target -> target.targetCollectionUri })
            addAll(exportTargets.map { target -> target.intendedDisplayName })
            addAll(exportTargets.map { target -> target.intendedRelativePath })
            addAll(labels)
        }

        forbiddenValues.forEach { forbiddenValue ->
            check(triggerMetadata.none { metadata -> forbiddenValue in metadata }) {
                "test trigger name and message must be value-free"
            }
        }
    }

    private fun assertTypedFailureRollsBack(
        fixture: ConfirmationFixture,
        before: AuthoritySnapshot,
        expectedResult: CaptureConfirmationResult,
    ) {
        assertEquals(
            expectedResult,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
        assertConfirmationRolledBack(fixture, before)
    }

    private fun assertConstraintFailureRollsBack(
        fixture: ConfirmationFixture,
        before: AuthoritySnapshot,
        expectedMessage: String,
    ) {
        val failure = try {
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                CONFIRMED_AT,
            )
            throw AssertionError("expected generic SQLite trigger failure")
        } catch (failure: SQLiteConstraintException) {
            failure
        }

        assertEquals(SQLiteConstraintException::class.java, failure.javaClass)
        assertEquals(
            expectedMessage,
            failure.message.orEmpty().substringBefore(" (code "),
        )
        assertConfirmationRolledBack(fixture, before)
    }

    private fun assertConfirmationRolledBack(
        fixture: ConfirmationFixture,
        before: AuthoritySnapshot,
    ) {
        assertEquals(before, fixture.sqlite.authoritySnapshot())
        assertEquals(
            listOf(listOf("CAPTURING", 10L, 20L, null)),
            fixture.sqlite.rows(
                "SELECT lifecycle_state, created_at_epoch_millis, " +
                    "updated_at_epoch_millis, confirmed_at_epoch_millis " +
                    "FROM capture_attempts WHERE command_token = ?",
                fixture.command.token.value,
            ),
        )
        assertEquals(
            listOf(listOf(0L, "ACTIVE", 1L, 10L)),
            fixture.sqlite.rows(
                "SELECT current_pose_index, lifecycle_state, created_at_epoch_millis, " +
                    "updated_at_epoch_millis FROM shoot_sessions WHERE session_id = ?",
                SESSION_ID,
            ),
        )
        assertNoConfirmationRows(fixture)
    }

    private fun assertFirstApplicationRejectedWithoutMutation(
        fixture: ConfirmationFixture,
        reason: CaptureConfirmationRejectionReason,
    ) {
        assertFirstApplicationResultWithoutMutation(
            fixture,
            CaptureConfirmationResult.Rejected(reason),
        )
    }

    private fun assertFirstApplicationResultWithoutMutation(
        fixture: ConfirmationFixture,
        expectedResult: CaptureConfirmationResult,
    ) {
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            expectedResult,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
        assertNoConfirmationRows(fixture)
    }

    private fun assertFirstApplicationFailsLoudWithoutMutation(
        fixture: ConfirmationFixture,
        expectedMessage: String,
    ) {
        val before = fixture.sqlite.authoritySnapshot()

        val failure = try {
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                CONFIRMED_AT,
            )
            throw AssertionError("expected first confirmation authority corruption to fail loud")
        } catch (failure: IllegalStateException) {
            failure
        }

        assertEquals(expectedMessage, failure.message)
        assertEquals(before, fixture.sqlite.authoritySnapshot())
        assertNoConfirmationRows(fixture)
    }

    private fun assertNoConfirmationRows(fixture: ConfirmationFixture) {
        assertEquals(
            listOf(listOf(0L, 0L, 0L, 0L)),
            fixture.sqlite.rows(
                "SELECT " +
                    "(SELECT COUNT(*) FROM private_capture_outputs WHERE command_token = ?), " +
                    "(SELECT COUNT(*) FROM capture_confirmation_receipts WHERE command_token = ?), " +
                    "(SELECT COUNT(*) FROM capture_export_outboxes WHERE command_token = ?), " +
                    "(SELECT COUNT(*) FROM capture_export_outputs WHERE command_token = ?)",
                fixture.command.token.value,
                fixture.command.token.value,
                fixture.command.token.value,
                fixture.command.token.value,
            ),
        )
    }

    private fun assertDuplicateFailsLoudWithoutMutation(
        fixture: ConfirmationFixture,
        expectedMessage: String,
    ) {
        val before = fixture.sqlite.authoritySnapshot()

        val failure = try {
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            )
            throw AssertionError("expected duplicate authority corruption to fail loud")
        } catch (failure: IllegalStateException) {
            failure
        }

        assertEquals(expectedMessage, failure.message)
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    private fun assertCommittedOutputs(fixture: ConfirmationFixture) {
        assertEquals(
            fixture.privateOutputs.map { output ->
                listOf(
                    output.identity.ordinal.toLong(),
                    output.relativePath,
                    output.byteCount,
                    "DURABLE",
                    output.capturedAtEpochMillis,
                    output.integrityMetadata,
                )
            },
            fixture.sqlite.rows(
                "SELECT burst_ordinal, relative_path, byte_count, durability_state, " +
                    "captured_at_epoch_millis, integrity_metadata FROM private_capture_outputs " +
                    "WHERE command_token = ? ORDER BY burst_ordinal",
                fixture.command.token.value,
            ),
        )
        assertEquals(
            listOf(listOf("PENDING", CONFIRMED_AT, CONFIRMED_AT, null)),
            fixture.sqlite.rows(
                "SELECT lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis, " +
                    "retry_metadata FROM capture_export_outboxes WHERE command_token = ?",
                fixture.command.token.value,
            ),
        )
        assertEquals(
            fixture.exportTargets.map { target ->
                listOf(
                    target.identity.ordinal.toLong(),
                    target.targetCollectionUri,
                    target.targetVolume,
                    target.intendedDisplayName,
                    target.intendedRelativePath,
                    target.intendedMimeType,
                    "PENDING",
                    null,
                    null,
                    "NONE",
                    DELETION_GENERATION,
                    CONFIRMED_AT,
                    CONFIRMED_AT,
                )
            },
            fixture.sqlite.rows(
                "SELECT burst_ordinal, target_collection_uri, target_volume, " +
                    "intended_display_name, intended_relative_path, intended_mime_type, " +
                    "lifecycle_state, claim_token, media_uri_string, ambiguity_state, " +
                    "deletion_generation, created_at_epoch_millis, updated_at_epoch_millis " +
                    "FROM capture_export_outputs WHERE command_token = ? ORDER BY burst_ordinal",
                fixture.command.token.value,
            ),
        )
        assertEquals(
            listOf(listOf(3L, 1L, 1L, 3L)),
            fixture.sqlite.rows(
                "SELECT " +
                    "(SELECT COUNT(*) FROM private_capture_outputs WHERE command_token = ?), " +
                    "(SELECT COUNT(*) FROM capture_confirmation_receipts WHERE command_token = ?), " +
                    "(SELECT COUNT(*) FROM capture_export_outboxes WHERE command_token = ?), " +
                    "(SELECT COUNT(*) FROM capture_export_outputs WHERE command_token = ?)",
                fixture.command.token.value,
                fixture.command.token.value,
                fixture.command.token.value,
                fixture.command.token.value,
            ),
        )
        assertEquals(
            listOf(listOf(0L)),
            fixture.sqlite.rows(
                "SELECT COUNT(*) FROM capture_file_operations WHERE command_token = ?",
                fixture.command.token.value,
            ),
        )
    }

    private fun prepareCapturingAttempt(
        rawToken: String,
        poseCount: Int,
    ): ConfirmationFixture {
        val fixture = prepareRegisteredAttempt(rawToken, poseCount)
        assertEquals(
            CaptureAttemptStartResult.Started,
            fixture.repository.markCaptureAttemptStarted(
                SESSION_ID,
                fixture.command.token,
                startedAtEpochMillis = 20L,
            ),
        )
        finalizeCaptureJournal(fixture)
        return fixture
    }

    private fun finalizeCaptureJournal(fixture: ConfirmationFixture) {
        val journal = RoomCaptureFileJournal(requireNotNull(database))
        identities(fixture.command.token).forEach { identity ->
            val capturedAt = journalCapturedAt(identity.ordinal)
            val byteCount = 100L + identity.ordinal
            val sha256 = journalSha256(identity.ordinal)
            val initial = requireNotNull(journal.snapshot(identity))
            val writing = requireNotNull(
                (journal.advance(
                    CaptureFileAdvanceRequest(
                        identity = identity,
                        expectedStage = initial.stage,
                        expectedUpdatedAtEpochMillis = initial.updatedAtEpochMillis,
                        targetStage = CaptureFileOperationStage.WRITING_TEMP,
                        byteCount = null,
                        sha256 = null,
                        capturedAtEpochMillis = null,
                        transitionedAtEpochMillis = capturedAt,
                    ),
                ) as CaptureFileJournalResult.Applied).snapshot,
            )
            val synced = (journal.advance(
                CaptureFileAdvanceRequest(
                    identity = identity,
                    expectedStage = writing.stage,
                    expectedUpdatedAtEpochMillis = writing.updatedAtEpochMillis,
                    targetStage = CaptureFileOperationStage.TEMP_SYNCED,
                    byteCount = byteCount,
                    sha256 = sha256,
                    capturedAtEpochMillis = capturedAt,
                    transitionedAtEpochMillis = capturedAt + 1L,
                ),
            ) as CaptureFileJournalResult.Applied).snapshot
            val renamePending = (journal.advance(
                CaptureFileAdvanceRequest(
                    identity = identity,
                    expectedStage = synced.stage,
                    expectedUpdatedAtEpochMillis = synced.updatedAtEpochMillis,
                    targetStage = CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
                    byteCount = byteCount,
                    sha256 = sha256,
                    capturedAtEpochMillis = capturedAt,
                    transitionedAtEpochMillis = capturedAt + 2L,
                ),
            ) as CaptureFileJournalResult.Applied).snapshot
            assertEquals(
                CaptureFileOperationStage.FINAL_DURABLE,
                (journal.advance(
                    CaptureFileAdvanceRequest(
                        identity = identity,
                        expectedStage = renamePending.stage,
                        expectedUpdatedAtEpochMillis = renamePending.updatedAtEpochMillis,
                        targetStage = CaptureFileOperationStage.FINAL_DURABLE,
                        byteCount = byteCount,
                        sha256 = sha256,
                        capturedAtEpochMillis = capturedAt,
                        transitionedAtEpochMillis = capturedAt + 3L,
                    ),
                ) as CaptureFileJournalResult.Applied).snapshot.stage,
            )
        }
    }

    private fun prepareRegisteredAttempt(
        rawToken: String,
        poseCount: Int,
    ): ConfirmationFixture {
        val fixture = prepareUnregisteredAttempt(rawToken, poseCount)
        val captureCommand = captureCommand(rawToken)
        assertEquals(
            AttemptRegistrationResult.Registered,
            fixture.repository.registerCaptureAttempt(
                SESSION_ID,
                captureCommand,
                recordedAtEpochMillis = 10L,
            ),
        )
        return fixture
    }

    private fun prepareUnregisteredAttempt(
        rawToken: String,
        poseCount: Int,
    ): ConfirmationFixture {
        val appDatabase = openDatabase()
        val sqlite = appDatabase.openHelper.writableDatabase
        seedActiveSession(sqlite, poseCount)
        val repository = RoomShootRepository(appDatabase)
        val captureCommand = captureCommand(rawToken)
        val command = ShootEffect.ConfirmAndAdvanceCapture(
            token = captureCommand.token,
            poseId = captureCommand.poseId,
            poseIndex = captureCommand.poseIndex,
            outputs = captureCommand.outputs,
        )
        return ConfirmationFixture(
            sqlite = sqlite,
            repository = repository,
            command = command,
            privateOutputs = privateOutputs(command.token),
            exportTargets = exportTargets(command.token),
        )
    }

    private fun applyConfirmationAtCurrentPose(
        fixture: ConfirmationFixture,
        rawToken: String,
        poseIndex: Int,
        attemptNumber: Long,
        confirmedAtEpochMillis: Long,
    ) {
        val captureCommand = captureCommand(rawToken, poseIndex, attemptNumber)
        assertEquals(
            AttemptRegistrationResult.Registered,
            fixture.repository.registerCaptureAttempt(
                SESSION_ID,
                captureCommand,
                recordedAtEpochMillis = confirmedAtEpochMillis - 2L,
            ),
        )
        assertEquals(
            CaptureAttemptStartResult.Started,
            fixture.repository.markCaptureAttemptStarted(
                SESSION_ID,
                captureCommand.token,
                startedAtEpochMillis = confirmedAtEpochMillis - 1L,
            ),
        )
        val confirmationCommand = ShootEffect.ConfirmAndAdvanceCapture(
            token = captureCommand.token,
            poseId = captureCommand.poseId,
            poseIndex = captureCommand.poseIndex,
            outputs = captureCommand.outputs,
        )
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                confirmationCommand,
                exportTargets(confirmationCommand.token),
                confirmedAtEpochMillis,
            ),
        )
    }

    private fun openDatabase(): AppDatabase =
        AppDatabase.create(context, databaseName).also { database = it }

    private fun closeDatabase() {
        database?.close()
        database = null
    }

    private fun seedActiveSession(
        sqlite: SupportSQLiteDatabase,
        poseCount: Int,
    ) {
        sqlite.execSQL(
            """
            INSERT INTO shoots
                (shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis,
                 lifecycle_state, deletion_generation)
            VALUES (?, 'Confirmation test shoot', 1, 1, 'ACTIVE', ?)
            """.trimIndent(),
            arrayOf<Any>(SHOOT_ID, DELETION_GENERATION),
        )
        repeat(poseCount) { index ->
            sqlite.execSQL(
                """
                INSERT INTO shoot_poses
                    (shoot_id, pose_index, pose_id, label, reference_asset_path,
                     mirror_allowed, validation_state, detector_metadata, model_metadata,
                     preprocessing_metadata)
                VALUES (?, ?, ?, ?, NULL, 0, 'VALID', NULL, NULL, NULL)
                """.trimIndent(),
                arrayOf<Any>(SHOOT_ID, index, "pose-$index", "Pose $index"),
            )
        }
        sqlite.execSQL(
            """
            INSERT INTO shoot_sessions
                (session_id, shoot_id, current_pose_index, next_attempt_number,
                 lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis)
            VALUES (?, ?, 0, 0, 'ACTIVE', 1, 1)
            """.trimIndent(),
            arrayOf<Any>(SESSION_ID, SHOOT_ID),
        )
    }

    private fun captureCommand(
        rawToken: String,
        poseIndex: Int = 0,
        attemptNumber: Long = 0L,
    ): ShootEffect.CaptureCommand =
        ShootEffect.CaptureCommand(
            CaptureAttempt.create(
                token = CaptureToken(rawToken),
                trigger = CaptureTrigger.MANUAL,
                poseId = "pose-$poseIndex",
                poseIndex = poseIndex,
                attemptNumber = attemptNumber,
            ),
        )

    private fun privateOutputs(token: CaptureToken): List<DurablePrivateOutput> =
        identities(token).map { identity ->
            val paths = CaptureFileOperationPaths.forIdentity(identity)
            DurablePrivateOutput(
                identity = identity,
                relativePath = paths.relativeFinalPath,
                byteCount = 100L + identity.ordinal,
                capturedAtEpochMillis = journalCapturedAt(identity.ordinal),
                integrityMetadata = journalSha256(identity.ordinal),
            )
        }

    private fun journalCapturedAt(ordinal: Int): Long = 21L + ordinal * 5L

    private fun journalSha256(ordinal: Int): String = (ordinal + 1).toString(16).padStart(64, '0')

    private fun exportTargets(token: CaptureToken): List<CaptureExportTarget> =
        identities(token).map { identity ->
            CaptureExportTarget(
                identity = identity,
                targetCollectionUri = "content://media/external_primary/images/media",
                targetVolume = "external_primary",
                intendedDisplayName = "${token.value}-${identity.ordinal}.jpg",
                intendedRelativePath = "Pictures/PoseGuideSnap/",
                intendedMimeType = "image/jpeg",
            )
        }

    private fun identities(token: CaptureToken): List<PrivateOutputIdentity> =
        (0..2).map { ordinal -> PrivateOutputIdentity(token, ordinal) }

    private fun SupportSQLiteDatabase.authoritySnapshot(): AuthoritySnapshot =
        AuthoritySnapshot(
            shoots = rows("SELECT * FROM shoots ORDER BY shoot_id"),
            poses = rows("SELECT * FROM shoot_poses ORDER BY shoot_id, pose_index"),
            sessions = rows("SELECT * FROM shoot_sessions ORDER BY session_id"),
            attempts = rows("SELECT * FROM capture_attempts ORDER BY command_token"),
            privateOutputs = rows(
                "SELECT * FROM private_capture_outputs ORDER BY command_token, burst_ordinal",
            ),
            receipts = rows("SELECT * FROM capture_confirmation_receipts ORDER BY command_token"),
            outboxes = rows("SELECT * FROM capture_export_outboxes ORDER BY command_token"),
            exportOutputs = rows(
                "SELECT * FROM capture_export_outputs ORDER BY command_token, burst_ordinal",
            ),
        )

    private fun SupportSQLiteDatabase.rows(
        sql: String,
        vararg args: Any,
    ): List<List<Any?>> = query(sql, args).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    (0 until cursor.columnCount).map { column ->
                        when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                            else -> error("unsupported SQLite cursor field type")
                        }
                    },
                )
            }
        }
    }

    private data class ConfirmationFixture(
        val sqlite: SupportSQLiteDatabase,
        val repository: RoomShootRepository,
        val command: ShootEffect.ConfirmAndAdvanceCapture,
        val privateOutputs: List<DurablePrivateOutput>,
        val exportTargets: List<CaptureExportTarget>,
    )

    private fun resetDatabaseForNextScenario() {
        closeDatabase()
        context.deleteDatabase(databaseName)
    }

    private fun ConfirmationFixture.mutateJournalWithoutStateTrigger(
        block: SupportSQLiteDatabase.() -> Unit,
    ) {
        sqlite.execSQL("DROP TRIGGER IF EXISTS `trigger_capture_file_operations_state_update`")
        try {
            sqlite.block()
        } finally {
            CaptureFileOperationStateTriggers.install(sqlite)
        }
    }

    private fun <A, B> runConcurrently(
        first: () -> A,
        second: () -> B,
    ): Pair<A, B> {
        val executor = Executors.newFixedThreadPool(2)
        val start = CyclicBarrier(3)
        return try {
            val firstFuture = executor.submit<A> {
                start.await(10L, TimeUnit.SECONDS)
                first()
            }
            val secondFuture = executor.submit<B> {
                start.await(10L, TimeUnit.SECONDS)
                second()
            }
            start.await(10L, TimeUnit.SECONDS)
            firstFuture.get(30L, TimeUnit.SECONDS) to
                secondFuture.get(30L, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10L, TimeUnit.SECONDS))
        }
    }

    private fun assertJournalGateRejectsWithoutMutation(
        fixture: ConfirmationFixture,
        exportTargets: List<CaptureExportTarget>,
        reason: CaptureConfirmationRejectionReason,
    ) {
        val before = fixture.sqlite.journalAuthoritySnapshot()

        assertEquals(
            CaptureConfirmationResult.Rejected(reason),
            fixture.repository.confirmAndAdvance(
                fixture.command,
                exportTargets,
                CONFIRMED_AT,
            ),
        )
        assertEquals(before, fixture.sqlite.journalAuthoritySnapshot())
        assertNoConfirmationRows(fixture)
    }

    private fun SupportSQLiteDatabase.seedRawConfirmationReceipt(commandToken: String) {
        execSQL(
            """
            INSERT INTO capture_confirmation_receipts
                (command_token, from_pose_index, to_pose_index,
                 applied_deletion_generation, applied_at_epoch_millis)
            VALUES (?, 0, 1, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(commandToken, DELETION_GENERATION, CONFIRMED_AT),
        )
    }

    private fun SupportSQLiteDatabase.seedMalformedConfirmationTimestamp(commandToken: String) {
        execSQL(
            "UPDATE capture_attempts SET confirmed_at_epoch_millis = 49 WHERE command_token = ?",
            arrayOf<Any>(commandToken),
        )
    }

    private fun SupportSQLiteDatabase.seedExpectingReservationJournalRows(
        commandToken: String,
        ordinals: List<Int>,
        createdAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ) {
        ordinals.forEach { ordinal ->
            val paths = CaptureFileOperationPaths.forIdentity(
                PrivateOutputIdentity(CaptureToken(commandToken), ordinal),
            )
            execSQL(
                """
                INSERT INTO capture_file_operations
                    (command_token, burst_ordinal, relative_final_path, relative_temp_path,
                     relative_quarantine_path, stage, byte_count, sha256,
                     captured_at_epoch_millis, last_failure_code, reconciliation_required,
                     created_at_epoch_millis, updated_at_epoch_millis)
                VALUES (?, ?, ?, ?, ?, 'EXPECTING_RESERVATION', NULL, NULL, NULL, NULL, 0, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    commandToken,
                    ordinal,
                    paths.relativeFinalPath,
                    paths.relativeTempPath,
                    paths.relativeQuarantinePath,
                    createdAtEpochMillis,
                    updatedAtEpochMillis,
                ),
            )
        }
    }

    private fun SupportSQLiteDatabase.corruptJournalTokenStorageAsByteEquivalentBlob(
        commandToken: String,
    ) {
        execSQL("DROP TRIGGER IF EXISTS `trigger_capture_file_operations_state_insert`")
        execSQL("DROP TRIGGER IF EXISTS `trigger_capture_file_operations_state_update`")
        execSQL("PRAGMA foreign_keys = OFF")
        try {
            execSQL(
                "UPDATE capture_file_operations SET command_token = CAST(? AS BLOB) " +
                    "WHERE command_token = ?",
                arrayOf<Any>(commandToken, commandToken),
            )
        } finally {
            execSQL("PRAGMA foreign_keys = ON")
            CaptureFileOperationStateTriggers.install(this)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02X".format(byte.toInt() and 0xff)
    }

    private fun SupportSQLiteDatabase.seedCoherentConfirmedConfirmationGraph(
        fixture: ConfirmationFixture,
        rawToken: String = fixture.command.token.value,
        poseIndex: Int = 0,
        attemptNumber: Long = 0L,
        confirmedAtEpochMillis: Long = CONFIRMED_AT,
        finalPose: Boolean = false,
    ) {
        val token = CaptureToken(rawToken)
        val commandToken = token.value
        execSQL(
            """
            INSERT INTO capture_attempts
                (command_token, session_id, pose_id, pose_index, attempt_number, trigger_type,
                 lifecycle_state, reconciliation_required, captured_deletion_generation,
                 created_at_epoch_millis, updated_at_epoch_millis, confirmed_at_epoch_millis)
            VALUES (?, ?, ?, ?, ?, 'MANUAL', 'CONFIRMED', 0, ?, 10, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                commandToken,
                SESSION_ID,
                "pose-$poseIndex",
                poseIndex,
                attemptNumber,
                DELETION_GENERATION,
                confirmedAtEpochMillis,
                confirmedAtEpochMillis,
            ),
        )
        privateOutputs(token).forEach { output ->
            execSQL(
                """
                INSERT INTO private_capture_outputs
                    (command_token, burst_ordinal, relative_path, byte_count, durability_state,
                     captured_at_epoch_millis, integrity_metadata)
                VALUES (?, ?, ?, ?, 'DURABLE', ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    commandToken,
                    output.identity.ordinal,
                    output.relativePath,
                    output.byteCount,
                    output.capturedAtEpochMillis,
                    output.integrityMetadata,
                ),
            )
        }
        execSQL(
            """
            INSERT INTO capture_confirmation_receipts
                (command_token, from_pose_index, to_pose_index,
                 applied_deletion_generation, applied_at_epoch_millis)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                commandToken,
                poseIndex,
                if (finalPose) null else poseIndex + 1,
                DELETION_GENERATION,
                confirmedAtEpochMillis,
            ),
        )
        execSQL(
            """
            INSERT INTO capture_export_outboxes
                (command_token, lifecycle_state, created_at_epoch_millis,
                 updated_at_epoch_millis, retry_metadata)
            VALUES (?, 'PENDING', ?, ?, NULL)
            """.trimIndent(),
            arrayOf<Any>(commandToken, confirmedAtEpochMillis, confirmedAtEpochMillis),
        )
        exportTargets(token).forEach { target ->
            execSQL(
                """
                INSERT INTO capture_export_outputs
                    (command_token, burst_ordinal, target_collection_uri, target_volume,
                     intended_display_name, intended_relative_path, intended_mime_type,
                     lifecycle_state, claim_token, media_uri_string, ambiguity_state,
                     deletion_generation, created_at_epoch_millis, updated_at_epoch_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', NULL, NULL, 'NONE', ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    commandToken,
                    target.identity.ordinal,
                    target.targetCollectionUri,
                    target.targetVolume,
                    target.intendedDisplayName,
                    target.intendedRelativePath,
                    target.intendedMimeType,
                    DELETION_GENERATION,
                    confirmedAtEpochMillis,
                    confirmedAtEpochMillis,
                ),
            )
        }
        if (finalPose) {
            execSQL(
                "UPDATE shoot_sessions SET current_pose_index = ?, next_attempt_number = ?, " +
                    "lifecycle_state = 'COMPLETED', updated_at_epoch_millis = ? " +
                    "WHERE session_id = ?",
                arrayOf<Any>(poseIndex, attemptNumber + 1L, confirmedAtEpochMillis, SESSION_ID),
            )
        } else {
            execSQL(
                "UPDATE shoot_sessions SET current_pose_index = ?, next_attempt_number = ?, " +
                    "updated_at_epoch_millis = ? WHERE session_id = ?",
                arrayOf<Any>(poseIndex + 1, attemptNumber + 1L, confirmedAtEpochMillis, SESSION_ID),
            )
        }
    }

    private fun SupportSQLiteDatabase.journalAuthoritySnapshot(): JournalAuthoritySnapshot =
        JournalAuthoritySnapshot(
            authority = authoritySnapshot(),
            fileOperations = rows(
                "SELECT * FROM capture_file_operations ORDER BY command_token, burst_ordinal",
            ),
        )

    private data class JournalAuthoritySnapshot(
        val authority: AuthoritySnapshot,
        val fileOperations: List<List<Any?>>,
    )

    private data class AuthoritySnapshot(
        val shoots: List<List<Any?>>,
        val poses: List<List<Any?>>,
        val sessions: List<List<Any?>>,
        val attempts: List<List<Any?>>,
        val privateOutputs: List<List<Any?>>,
        val receipts: List<List<Any?>>,
        val outboxes: List<List<Any?>>,
        val exportOutputs: List<List<Any?>>,
    )

    private class ConfirmationAfterJournalDeleteTestException : RuntimeException()

    private class MutatingAfterFirstTraversalList<T>(
        firstTraversalValues: List<T>,
        laterValues: List<T>,
    ) : AbstractList<T>() {
        private val firstTraversalValues = firstTraversalValues.toList()
        private val laterValues = laterValues.toList()
        private var firstTraversalReadCount = 0
        private var firstTraversalComplete = false

        init {
            require(this.firstTraversalValues.size == this.laterValues.size)
            require(this.firstTraversalValues.isNotEmpty())
        }

        override val size: Int
            get() = firstTraversalValues.size

        override fun get(index: Int): T {
            if (firstTraversalComplete) return laterValues[index]

            val value = firstTraversalValues[index]
            firstTraversalReadCount += 1
            if (firstTraversalReadCount == firstTraversalValues.size) {
                firstTraversalComplete = true
            }
            return value
        }

        fun currentValues(): List<T> =
            if (firstTraversalComplete) laterValues else firstTraversalValues
    }

    companion object {
        private const val SHOOT_ID = "confirmation-shoot"
        private const val SESSION_ID = "confirmation-session"
        private const val DELETION_GENERATION = 7L
        private const val CONFIRMED_AT = 50L
    }
}
