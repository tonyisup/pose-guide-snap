package com.tonyisup.poseguidesnap.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
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
    fun nonFinalConfirmationAtomicallyPersistsAuthorityAndAdvancesExactlyOnePose() {
        val fixture = prepareCapturingAttempt("non-final-token", poseCount = 2)

        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
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
    }

    @Test
    fun finalPoseConfirmationCompletesSessionWhileRetainingValidCurrentIndex() {
        val fixture = prepareCapturingAttempt("final-token", poseCount = 1)

        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
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
        val fixture = prepareCapturingAttempt("duplicate-token", poseCount = 2)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            CaptureConfirmationResult.AlreadyApplied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun exactDuplicateAfterDatabaseCloseAndReopenReturnsAlreadyApplied() {
        var fixture = prepareCapturingAttempt("reopen-token", poseCount = 2)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
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
                fixture.privateOutputs,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun duplicateFinalReceiptWithActiveSessionFailsLoudWithoutMutation() {
        val fixture = prepareCapturingAttempt("final-active-token", poseCount = 1)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
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
        val fixture = prepareCapturingAttempt("rewound-token", poseCount = 2)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
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
        val fixture = prepareCapturingAttempt("missing-final-token", poseCount = 3)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
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
        val fixture = prepareCapturingAttempt("later-original-token", poseCount = 3)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
        applyConfirmationAtCurrentPose(
            fixture = fixture,
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
                fixture.privateOutputs,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun duplicateWithCorruptOutboxCreatedTimestampFailsLoudWithoutMutation() {
        val fixture = prepareCapturingAttempt("outbox-created-token", poseCount = 2)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
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
        val fixture = prepareCapturingAttempt("export-created-token", poseCount = 2)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
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
        val fixture = prepareCapturingAttempt("mutable-export-token", poseCount = 2)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
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
                fixture.privateOutputs,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun duplicateWithChangedPrivateImmutableMetadataIsRejectedWithoutMutation() {
        val fixture = prepareCapturingAttempt("private-conflict-token", poseCount = 2)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
        val before = fixture.sqlite.authoritySnapshot()
        val changedPrivateOutputs = fixture.privateOutputs.mapIndexed { index, output ->
            if (index == 1) output.copy(relativePath = "private/retry/changed-1.jpg") else output
        }

        assertEquals(
            CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.INVALID_PRIVATE_OUTPUTS,
            ),
            fixture.repository.confirmAndAdvance(
                fixture.command,
                changedPrivateOutputs,
                fixture.exportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun duplicateWithChangedExportTargetMetadataIsRejectedWithoutMutation() {
        val fixture = prepareCapturingAttempt("export-conflict-token", poseCount = 2)
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                fixture.privateOutputs,
                fixture.exportTargets,
                CONFIRMED_AT,
            ),
        )
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
                fixture.privateOutputs,
                changedExportTargets,
                confirmedAtEpochMillis = 999L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun firstApplicationPersistsSingleCallerListSnapshotsDespiteLaterCallerMutation() {
        val fixture = prepareCapturingAttempt("mutable-input-token", poseCount = 2)
        val changedPrivateOutputs = fixture.privateOutputs.map { output ->
            output.copy(relativePath = "private/mutated/capture-${output.identity.ordinal}.jpg")
        }
        val changedExportTargets = fixture.exportTargets.map { target ->
            target.copy(intendedDisplayName = "mutated-${target.identity.ordinal}.jpg")
        }
        val privateOutputs = MutatingAfterFirstTraversalList(
            fixture.privateOutputs,
            changedPrivateOutputs,
        )
        val exportTargets = MutatingAfterFirstTraversalList(
            fixture.exportTargets,
            changedExportTargets,
        )

        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                fixture.command,
                privateOutputs,
                exportTargets,
                CONFIRMED_AT,
            ),
        )

        assertEquals(changedPrivateOutputs, privateOutputs.currentValues())
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

        assertFirstApplicationFailsLoudWithoutMutation(
            fixture,
            "capture confirmation deletion generation is invalid",
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
                fixture.privateOutputs,
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
                fixture.privateOutputs,
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
                fixture.privateOutputs,
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
                fixture.privateOutputs,
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
                fixture.privateOutputs,
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
    }

    private fun prepareCapturingAttempt(
        rawToken: String,
        poseCount: Int,
    ): ConfirmationFixture {
        val fixture = prepareRegisteredAttempt(rawToken, poseCount)
        assertEquals(
            CaptureStartAuthorizationResult.Started,
            fixture.repository.authorizeCaptureStart(
                SESSION_ID,
                fixture.command.token,
                authorizedAtEpochMillis = 20L,
            ),
        )
        return fixture
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
            CaptureStartAuthorizationResult.Started,
            fixture.repository.authorizeCaptureStart(
                SESSION_ID,
                captureCommand.token,
                authorizedAtEpochMillis = confirmedAtEpochMillis - 1L,
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
                privateOutputs(confirmationCommand.token),
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
            DurablePrivateOutput(
                identity = identity,
                relativePath = "private/${token.value}/capture-${identity.ordinal}.jpg",
                byteCount = 100L + identity.ordinal,
                capturedAtEpochMillis = 30L + identity.ordinal,
                integrityMetadata = if (identity.ordinal == 0) null else
                    "sha256-${identity.ordinal}",
            )
        }

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
