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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeletionExportRepositoryAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "deletion_export_repository_android_test_${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase(databaseName)
    }

    @Test
    fun activeShootBeginsDeletionOnceAndReplayDoesNotRewriteAuthority() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()

        assertEquals(
            BeginShootDeletionResult.Began(
                generation = 8L,
                cancelledOutputCount = 0,
                cancelledOutboxCount = 0,
                retainedOutputCount = 0,
            ),
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L),
        )
        assertEquals(
            listOf(listOf("DELETING", 8L, 100L)),
            fixture.sqlite.rows(
                "SELECT lifecycle_state, deletion_generation, updated_at_epoch_millis " +
                    "FROM shoots WHERE shoot_id = ?",
                SHOOT_ID,
            ),
        )
        val afterFirst = fixture.sqlite.authoritySnapshot()

        assertEquals(
            BeginShootDeletionResult.AlreadyDeleting(8L),
            fixture.repository.beginShootDeletion(SHOOT_ID, 999L),
        )
        assertEquals(afterFirst, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun deletionCancelsExactlyThreeUntouchedOutputsAndTheirPendingOutbox() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "all-pending-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        val attemptsBefore = fixture.sqlite.rows("SELECT * FROM capture_attempts")
        val privateBefore = fixture.sqlite.rows("SELECT * FROM private_capture_outputs")
        val receiptsBefore = fixture.sqlite.rows("SELECT * FROM capture_confirmation_receipts")

        assertEquals(
            BeginShootDeletionResult.Began(8L, 3, 1, 0),
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L),
        )
        assertEquals(
            listOf(listOf("CANCELLED", 100L), listOf("CANCELLED", 100L), listOf("CANCELLED", 100L)),
            fixture.sqlite.rows(
                "SELECT lifecycle_state, updated_at_epoch_millis FROM capture_export_outputs " +
                    "ORDER BY burst_ordinal",
            ),
        )
        assertEquals(
            listOf(listOf("CANCELLED", 100L)),
            fixture.sqlite.rows(
                "SELECT lifecycle_state, updated_at_epoch_millis FROM capture_export_outboxes",
            ),
        )
        assertEquals(attemptsBefore, fixture.sqlite.rows("SELECT * FROM capture_attempts"))
        assertEquals(privateBefore, fixture.sqlite.rows("SELECT * FROM private_capture_outputs"))
        assertEquals(receiptsBefore, fixture.sqlite.rows("SELECT * FROM capture_confirmation_receipts"))
    }

    @Test
    fun deletionCancelsOnlyUntouchedSiblingAndPreservesInProgressAuthority() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "mixed-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, CLAIMED, AMBIGUOUS),
        )
        val before = fixture.sqlite.authoritySnapshot()
        val preservedExportRowsBefore = fixture.sqlite.rows(
            "SELECT * FROM capture_export_outputs WHERE burst_ordinal IN (1, 2) " +
                "ORDER BY burst_ordinal",
        )

        assertEquals(
            BeginShootDeletionResult.Began(8L, 1, 0, 2),
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L),
        )
        val after = fixture.sqlite.authoritySnapshot()
        assertEquals(before.attempts, after.attempts)
        assertEquals(before.privateOutputs, after.privateOutputs)
        assertEquals(before.receipts, after.receipts)
        assertEquals(before.outboxes, after.outboxes)
        assertEquals(
            preservedExportRowsBefore,
            fixture.sqlite.rows(
                "SELECT * FROM capture_export_outputs WHERE burst_ordinal IN (1, 2) " +
                    "ORDER BY burst_ordinal",
            ),
        )
        assertEquals(
            listOf(
                listOf(0L, "CANCELLED", null, null, "NONE", 100L),
                listOf(1L, "CLAIMED", "claim-1", null, "NONE", 50L),
                listOf(2L, "AMBIGUOUS", "claim-2", null, "CREATE_RESULT_UNKNOWN", 50L),
            ),
            fixture.sqlite.rows(
                "SELECT burst_ordinal, lifecycle_state, claim_token, media_uri_string, " +
                    "ambiguity_state, updated_at_epoch_millis FROM capture_export_outputs " +
                    "ORDER BY burst_ordinal",
            ),
        )
    }

    @Test
    fun invalidUnknownOverflowAndUnsupportedDeletionRequestsDoNotMutate() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        val initial = fixture.sqlite.authoritySnapshot()

        assertEquals(
            BeginShootDeletionResult.Rejected(BeginShootDeletionRejectionReason.INVALID_SHOOT_ID),
            fixture.repository.beginShootDeletion(" ", 1L),
        )
        assertEquals(
            BeginShootDeletionResult.Rejected(BeginShootDeletionRejectionReason.INVALID_TIMESTAMP),
            fixture.repository.beginShootDeletion(SHOOT_ID, -1L),
        )
        assertEquals(
            BeginShootDeletionResult.UnknownShoot,
            fixture.repository.beginShootDeletion("unknown-shoot", 1L),
        )
        assertEquals(initial, fixture.sqlite.authoritySnapshot())

        fixture.sqlite.execSQL(
            "UPDATE shoots SET deletion_generation = ? WHERE shoot_id = ?",
            arrayOf<Any>(Long.MAX_VALUE, SHOOT_ID),
        )
        val overflow = fixture.sqlite.authoritySnapshot()
        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.GENERATION_EXHAUSTED,
            ),
            fixture.repository.beginShootDeletion(SHOOT_ID, 2L),
        )
        assertEquals(overflow, fixture.sqlite.authoritySnapshot())

        fixture.sqlite.execSQL(
            "UPDATE shoots SET deletion_generation = 7, lifecycle_state = 'DELETED' " +
                "WHERE shoot_id = ?",
            arrayOf<Any>(SHOOT_ID),
        )
        val unsupported = fixture.sqlite.authoritySnapshot()
        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.UNSUPPORTED_SHOOT_STATE,
            ),
            fixture.repository.beginShootDeletion(SHOOT_ID, 3L),
        )
        assertEquals(unsupported, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun negativeShootGenerationIsRejectedWithoutNormalizingOrCancellingAuthority() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "negative-deletion-generation-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            "UPDATE shoots SET deletion_generation = -1 WHERE shoot_id = ?",
            arrayOf<Any>(SHOOT_ID),
        )
        fixture.sqlite.execSQL(
            "UPDATE capture_attempts SET captured_deletion_generation = -1 " +
                "WHERE command_token = ?",
            arrayOf<Any>("negative-deletion-generation-token"),
        )
        fixture.sqlite.execSQL(
            "UPDATE capture_confirmation_receipts SET applied_deletion_generation = -1 " +
                "WHERE command_token = ?",
            arrayOf<Any>("negative-deletion-generation-token"),
        )
        fixture.sqlite.execSQL(
            "UPDATE capture_export_outputs SET deletion_generation = -1 " +
                "WHERE command_token = ?",
            arrayOf<Any>("negative-deletion-generation-token"),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun multipleOutboxesProduceExactAggregateCancellationAndRetentionCounts() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "aggregate-all-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "aggregate-mixed-token",
            attemptNumber = 1L,
            outputStates = listOf(PENDING, CLAIMED, AMBIGUOUS),
        )

        assertEquals(
            BeginShootDeletionResult.Began(8L, 4, 1, 2),
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L),
        )
        assertEquals(
            listOf(
                listOf("aggregate-all-token", "CANCELLED"),
                listOf("aggregate-mixed-token", "PENDING"),
            ),
            fixture.sqlite.rows(
                "SELECT command_token, lifecycle_state FROM capture_export_outboxes " +
                    "ORDER BY command_token",
            ),
        )
    }

    @Test
    fun preexistingCancelledOutputIsRetainedWhileUntouchedSiblingsAndOutboxCancel() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "pre-cancelled-token",
            attemptNumber = 0L,
            outputStates = listOf(CANCELLED, PENDING, PENDING),
        )
        val cancelledBefore = fixture.sqlite.rows(
            "SELECT * FROM capture_export_outputs WHERE burst_ordinal = 0",
        )

        assertEquals(
            BeginShootDeletionResult.Began(8L, 2, 1, 1),
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L),
        )
        assertEquals(
            cancelledBefore,
            fixture.sqlite.rows("SELECT * FROM capture_export_outputs WHERE burst_ordinal = 0"),
        )
    }

    @Test
    fun corruptCardinalityAndGenerationAreTypedInconsistencyWithoutMutation() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "corrupt-cardinality-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            "UPDATE capture_export_outputs SET deletion_generation = 8 " +
                "WHERE command_token = ? AND burst_ordinal = 0",
            arrayOf<Any>("corrupt-cardinality-token"),
        )
        val generationBefore = fixture.sqlite.authoritySnapshot()
        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L),
        )
        assertEquals(generationBefore, fixture.sqlite.authoritySnapshot())

        fixture.sqlite.execSQL(
            "UPDATE capture_export_outputs SET deletion_generation = 7 " +
                "WHERE command_token = ? AND burst_ordinal = 0",
            arrayOf<Any>("corrupt-cardinality-token"),
        )
        fixture.sqlite.execSQL(
            "DELETE FROM capture_export_outputs WHERE command_token = ? AND burst_ordinal = 2",
            arrayOf<Any>("corrupt-cardinality-token"),
        )
        val cardinalityBefore = fixture.sqlite.authoritySnapshot()
        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.beginShootDeletion(SHOOT_ID, 101L),
        )
        assertEquals(cardinalityBefore, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun ignoredShootCasIsTypedFailureWithoutMutation() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.execSQL(
            """
            CREATE TRIGGER `test_ignore_shoot_deletion_cas`
            BEFORE UPDATE OF lifecycle_state ON `shoots`
            FOR EACH ROW
            WHEN OLD.lifecycle_state = 'ACTIVE' AND NEW.lifecycle_state = 'DELETING'
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent(),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.TRANSACTION_CAS_FAILED,
            ),
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun postconditionDetectsPrivateAuthorityMutationAndRollsBackEverything() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "private-mutation-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            """
            CREATE TRIGGER `test_mutate_private_authority_during_deletion`
            AFTER UPDATE OF lifecycle_state ON `capture_export_outputs`
            FOR EACH ROW
            WHEN OLD.lifecycle_state = 'PENDING'
             AND NEW.lifecycle_state = 'CANCELLED'
             AND NEW.burst_ordinal = 1
            BEGIN
                UPDATE private_capture_outputs
                SET byte_count = 999
                WHERE command_token = NEW.command_token AND burst_ordinal = 0;
            END
            """.trimIndent(),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun postconditionDetectsInFlightAttemptMutationAndRollsBackEverything() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "attempt-mutation-export-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            """
            INSERT INTO capture_attempts
                (command_token, session_id, pose_id, pose_index, attempt_number, trigger_type,
                 lifecycle_state, reconciliation_required, captured_deletion_generation,
                 created_at_epoch_millis, updated_at_epoch_millis, confirmed_at_epoch_millis)
            VALUES ('in-flight-token', ?, 'pose-0', 0, 1, 'MANUAL',
                    'CAPTURING', 0, 7, 60, 61, NULL)
            """.trimIndent(),
            arrayOf<Any>(SESSION_ID),
        )
        fixture.sqlite.execSQL(
            """
            CREATE TRIGGER `test_mutate_in_flight_attempt_during_deletion`
            AFTER UPDATE OF lifecycle_state ON `capture_export_outputs`
            FOR EACH ROW
            WHEN OLD.lifecycle_state = 'PENDING'
             AND NEW.lifecycle_state = 'CANCELLED'
             AND NEW.burst_ordinal = 1
            BEGIN
                UPDATE capture_attempts
                SET trigger_type = 'AUTOMATIC'
                WHERE command_token = 'in-flight-token';
            END
            """.trimIndent(),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun freshTargetedClaimAcquiresExactAuthorityAndOnlyMutatesOneOutput() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-happy-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        val before = fixture.sqlite.authoritySnapshot()
        val identity = outputIdentity("claim-happy-token", 1)
        val claimToken = ExportClaimToken("claim-happy-owner")

        val result = fixture.repository.claimExportOutput(identity, claimToken, 80L)
        val acquired = result as ExportOutputClaimResult.Acquired
        assertEquals(identity, acquired.claim.identity)
        assertEquals(claimToken, acquired.claim.claimToken)
        assertEquals(7L, acquired.claim.deletionGeneration)
        assertEquals("content://media/external_primary/images/media", acquired.claim.targetCollectionUri)
        assertEquals("claim-happy-token-1.jpg", acquired.claim.intendedDisplayName)
        assertEquals(true, result.grantsFreshExternalCreateAuthority)
        val after = fixture.sqlite.authoritySnapshot()
        assertEquals(before.shoots, after.shoots)
        assertEquals(before.poses, after.poses)
        assertEquals(before.sessions, after.sessions)
        assertEquals(before.attempts, after.attempts)
        assertEquals(before.privateOutputs, after.privateOutputs)
        assertEquals(before.receipts, after.receipts)
        assertEquals(before.outboxes, after.outboxes)
        assertEquals(
            listOf(listOf("CLAIMED", "claim-happy-owner", 80L)),
            fixture.sqlite.rows(
                "SELECT lifecycle_state, claim_token, updated_at_epoch_millis " +
                    "FROM capture_export_outputs WHERE command_token = ? AND burst_ordinal = 1",
                "claim-happy-token",
            ),
        )
    }

    @Test
    fun exactClaimReplayAfterReopenIsNonAuthorizingAndDoesNotRewriteTimestamp() {
        var fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-replay-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        val identity = outputIdentity("claim-replay-token", 0)
        val token = ExportClaimToken("claim-replay-owner")
        assertEquals(
            true,
            fixture.repository.claimExportOutput(identity, token, 80L)
                .grantsFreshExternalCreateAuthority,
        )
        val before = fixture.sqlite.authoritySnapshot()
        fixture = reopenFixture()

        val replay = fixture.repository.claimExportOutput(identity, token, 999L)
        assertEquals(
            ExportOutputClaimResult.IdempotentReplay(ExportAuthorityStage.CLAIMED),
            replay,
        )
        assertEquals(false, replay.grantsFreshExternalCreateAuthority)
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun differentClaimCannotStealOutputAndOneTokenCannotOwnTwoOutputs() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-conflict-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        val firstIdentity = outputIdentity("claim-conflict-token", 0)
        val firstToken = ExportClaimToken("first-owner")
        assertEquals(
            true,
            fixture.repository.claimExportOutput(firstIdentity, firstToken, 80L)
                .grantsFreshExternalCreateAuthority,
        )
        val afterAcquire = fixture.sqlite.authoritySnapshot()

        assertEquals(
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.OWNED_BY_DIFFERENT_CLAIM,
            ),
            fixture.repository.claimExportOutput(
                firstIdentity,
                ExportClaimToken("second-owner"),
                81L,
            ),
        )
        assertEquals(
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.CLAIM_TOKEN_CONFLICT,
            ),
            fixture.repository.claimExportOutput(
                outputIdentity("claim-conflict-token", 1),
                firstToken,
                82L,
            ),
        )
        assertEquals(afterAcquire, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun deletionBlocksFreshClaimAndCancelledActiveOutputIsNotClaimable() {
        var fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-deletion-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        assertEquals(
            BeginShootDeletionResult.Began(8L, 3, 1, 0),
            fixture.repository.beginShootDeletion(SHOOT_ID, 70L),
        )
        val deletedSnapshot = fixture.sqlite.authoritySnapshot()
        assertEquals(
            ExportOutputClaimResult.BlockedByDeletion,
            fixture.repository.claimExportOutput(
                outputIdentity("claim-deletion-token", 0),
                ExportClaimToken("blocked-owner"),
                80L,
            ),
        )
        assertEquals(deletedSnapshot, fixture.sqlite.authoritySnapshot())

        closeDatabase()
        context.deleteDatabase(databaseName)
        fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-cancelled-token",
            attemptNumber = 0L,
            outputStates = listOf(CANCELLED, PENDING, PENDING),
        )
        val activeSnapshot = fixture.sqlite.authoritySnapshot()
        assertEquals(
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.NOT_CLAIMABLE,
            ),
            fixture.repository.claimExportOutput(
                outputIdentity("claim-cancelled-token", 0),
                ExportClaimToken("not-claimable-owner"),
                80L,
            ),
        )
        assertEquals(activeSnapshot, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun persistedClaimStagesReplayWithoutRegrantingAuthorityAfterRestart() {
        var fixture = openFixture()
        fixture.sqlite.seedShoot()
        val stages = listOf(
            ExportAuthorityStage.CLAIMED,
            ExportAuthorityStage.CREATE_STARTED,
            ExportAuthorityStage.URI_KNOWN,
            ExportAuthorityStage.EXPORTED,
            ExportAuthorityStage.AMBIGUOUS,
        )
        stages.forEachIndexed { index, stage ->
            val commandToken = "restart-stage-$index"
            fixture.sqlite.seedConfirmedExportAuthority(
                commandToken = commandToken,
                attemptNumber = index.toLong(),
                outputStates = listOf(PENDING, PENDING, PENDING),
            )
            val lifecycle = stage.name
            val mediaUri = if (stage == ExportAuthorityStage.URI_KNOWN || stage == ExportAuthorityStage.EXPORTED) {
                "content://media/exact-$index"
            } else {
                null
            }
            val ambiguity = if (stage == ExportAuthorityStage.AMBIGUOUS) {
                "CREATE_RESULT_UNKNOWN"
            } else {
                "NONE"
            }
            fixture.sqlite.execSQL(
                "UPDATE capture_export_outputs SET lifecycle_state = ?, claim_token = ?, " +
                    "media_uri_string = ?, ambiguity_state = ? " +
                    "WHERE command_token = ? AND burst_ordinal = 0",
                arrayOf<Any?>(lifecycle, "restart-owner-$index", mediaUri, ambiguity, commandToken),
            )
        }
        val before = fixture.sqlite.authoritySnapshot()
        fixture = reopenFixture()

        stages.forEachIndexed { index, stage ->
            val replay = fixture.repository.claimExportOutput(
                outputIdentity("restart-stage-$index", 0),
                ExportClaimToken("restart-owner-$index"),
                999L,
            )
            assertEquals(ExportOutputClaimResult.IdempotentReplay(stage), replay)
            assertEquals(false, replay.grantsFreshExternalCreateAuthority)
        }
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun ignoredClaimCasIsTypedFailureAndPostconditionMutationRollsBack() {
        var fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-cas-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            """
            CREATE TRIGGER `test_ignore_export_claim_cas`
            BEFORE UPDATE OF lifecycle_state ON `capture_export_outputs`
            FOR EACH ROW
            WHEN OLD.lifecycle_state = 'PENDING' AND NEW.lifecycle_state = 'CLAIMED'
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent(),
        )
        val casBefore = fixture.sqlite.authoritySnapshot()
        assertEquals(
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.TRANSACTION_CAS_FAILED,
            ),
            fixture.repository.claimExportOutput(
                outputIdentity("claim-cas-token", 0),
                ExportClaimToken("claim-cas-owner"),
                80L,
            ),
        )
        assertEquals(casBefore, fixture.sqlite.authoritySnapshot())

        closeDatabase()
        context.deleteDatabase(databaseName)
        fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-postcondition-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            """
            CREATE TRIGGER `test_mutate_private_authority_during_claim`
            AFTER UPDATE OF lifecycle_state ON `capture_export_outputs`
            FOR EACH ROW
            WHEN OLD.lifecycle_state = 'PENDING' AND NEW.lifecycle_state = 'CLAIMED'
            BEGIN
                UPDATE private_capture_outputs
                SET byte_count = 999
                WHERE command_token = NEW.command_token AND burst_ordinal = 0;
            END
            """.trimIndent(),
        )
        val postconditionBefore = fixture.sqlite.authoritySnapshot()
        assertEquals(
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.claimExportOutput(
                outputIdentity("claim-postcondition-token", 0),
                ExportClaimToken("claim-postcondition-owner"),
                80L,
            ),
        )
        assertEquals(postconditionBefore, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun invalidTimestampUnknownOutputAndCorruptAuthorityDoNotMutate() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-invalid-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        val before = fixture.sqlite.authoritySnapshot()
        assertEquals(
            ExportOutputClaimResult.Rejected(ExportOutputClaimRejectionReason.INVALID_TIMESTAMP),
            fixture.repository.claimExportOutput(
                outputIdentity("claim-invalid-token", 0),
                ExportClaimToken("invalid-time-owner"),
                -1L,
            ),
        )
        assertEquals(
            ExportOutputClaimResult.Rejected(ExportOutputClaimRejectionReason.UNKNOWN_OUTPUT),
            fixture.repository.claimExportOutput(
                outputIdentity("unknown-output-token", 0),
                ExportClaimToken("unknown-owner"),
                80L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())

        fixture.sqlite.execSQL(
            "UPDATE capture_export_outputs SET deletion_generation = 8 " +
                "WHERE command_token = ? AND burst_ordinal = 0",
            arrayOf<Any>("claim-invalid-token"),
        )
        val corrupt = fixture.sqlite.authoritySnapshot()
        assertEquals(
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.claimExportOutput(
                outputIdentity("claim-invalid-token", 0),
                ExportClaimToken("corrupt-owner"),
                80L,
            ),
        )
        assertEquals(corrupt, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun claimPostconditionDetectsOwningSessionMutationAndRollsBack() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-session-mutation-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            """
            CREATE TRIGGER `test_mutate_session_during_claim`
            AFTER UPDATE OF lifecycle_state ON `capture_export_outputs`
            FOR EACH ROW
            WHEN OLD.lifecycle_state = 'PENDING' AND NEW.lifecycle_state = 'CLAIMED'
            BEGIN
                UPDATE shoot_sessions
                SET next_attempt_number = 999
                WHERE session_id = 'deletion-session';
            END
            """.trimIndent(),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.claimExportOutput(
                outputIdentity("claim-session-mutation-token", 0),
                ExportClaimToken("claim-session-mutation-owner"),
                80L,
            ),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun malformedPersistedTargetAndNegativeGenerationAreTypedAuthorityFailures() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-persisted-corruption-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            "UPDATE capture_export_outputs SET target_collection_uri = 'content://not-a-collection' " +
                "WHERE command_token = ? AND burst_ordinal = 0",
            arrayOf<Any>("claim-persisted-corruption-token"),
        )
        val malformed = fixture.sqlite.authoritySnapshot()
        assertEquals(
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.claimExportOutput(
                outputIdentity("claim-persisted-corruption-token", 0),
                ExportClaimToken("malformed-target-owner"),
                80L,
            ),
        )
        assertEquals(malformed, fixture.sqlite.authoritySnapshot())

        fixture.sqlite.execSQL(
            "UPDATE capture_export_outputs SET target_collection_uri = " +
                "'content://media/external_primary/images/media', deletion_generation = -1 " +
                "WHERE command_token = ?",
            arrayOf<Any>("claim-persisted-corruption-token"),
        )
        fixture.sqlite.execSQL(
            "UPDATE capture_attempts SET captured_deletion_generation = -1 " +
                "WHERE command_token = ?",
            arrayOf<Any>("claim-persisted-corruption-token"),
        )
        fixture.sqlite.execSQL(
            "UPDATE capture_confirmation_receipts SET applied_deletion_generation = -1 " +
                "WHERE command_token = ?",
            arrayOf<Any>("claim-persisted-corruption-token"),
        )
        fixture.sqlite.execSQL(
            "UPDATE shoots SET deletion_generation = -1 WHERE shoot_id = ?",
            arrayOf<Any>(SHOOT_ID),
        )
        val negative = fixture.sqlite.authoritySnapshot()
        assertEquals(
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.claimExportOutput(
                outputIdentity("claim-persisted-corruption-token", 0),
                ExportClaimToken("negative-generation-owner"),
                81L,
            ),
        )
        assertEquals(negative, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun completedFinalSessionCanClaimAndReplayRemainsInformationalAfterDeletion() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "claim-final-session-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            "UPDATE shoot_sessions SET lifecycle_state = 'COMPLETED' WHERE session_id = ?",
            arrayOf<Any>(SESSION_ID),
        )
        val identity = outputIdentity("claim-final-session-token", 0)
        val token = ExportClaimToken("claim-final-session-owner")
        assertEquals(
            true,
            fixture.repository.claimExportOutput(identity, token, 80L)
                .grantsFreshExternalCreateAuthority,
        )
        assertEquals(
            BeginShootDeletionResult.Began(8L, 2, 0, 1),
            fixture.repository.beginShootDeletion(SHOOT_ID, 90L),
        )
        val afterDeletion = fixture.sqlite.authoritySnapshot()

        val replay = fixture.repository.claimExportOutput(identity, token, 999L)
        assertEquals(
            ExportOutputClaimResult.IdempotentReplay(ExportAuthorityStage.CLAIMED),
            replay,
        )
        assertEquals(false, replay.grantsFreshExternalCreateAuthority)
        assertEquals(afterDeletion, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun twoClaimTokensRaceForOneOutputAndExactlyOneAcquiresAuthority() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "concurrent-one-output-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        val secondDatabase = AppDatabase.create(context, databaseName)
        try {
            val secondRepository = RoomShootRepository(secondDatabase)
            val identity = outputIdentity("concurrent-one-output-token", 0)
            val (first, second) = runConcurrently(
                { fixture.repository.claimExportOutput(identity, ExportClaimToken("race-owner-a"), 80L) },
                { secondRepository.claimExportOutput(identity, ExportClaimToken("race-owner-b"), 81L) },
            )
            val results = listOf(first, second)
            assertEquals(1, results.count { result -> result is ExportOutputClaimResult.Acquired })
            assertEquals(
                1,
                results.count { result ->
                    result == ExportOutputClaimResult.Rejected(
                        ExportOutputClaimRejectionReason.OWNED_BY_DIFFERENT_CLAIM,
                    )
                },
            )
            assertEquals(
                listOf(listOf("CLAIMED", 1L)),
                fixture.sqlite.rows(
                    "SELECT lifecycle_state, COUNT(claim_token) FROM capture_export_outputs " +
                        "WHERE command_token = ? AND burst_ordinal = 0",
                    "concurrent-one-output-token",
                ),
            )
        } finally {
            secondDatabase.close()
        }
    }

    @Test
    fun oneClaimTokenRacesForTwoOutputsAndOwnsExactlyOne() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "concurrent-one-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        val secondDatabase = AppDatabase.create(context, databaseName)
        try {
            val secondRepository = RoomShootRepository(secondDatabase)
            val sharedToken = ExportClaimToken("shared-race-owner")
            val (first, second) = runConcurrently(
                {
                    fixture.repository.claimExportOutput(
                        outputIdentity("concurrent-one-token", 0),
                        sharedToken,
                        80L,
                    )
                },
                {
                    secondRepository.claimExportOutput(
                        outputIdentity("concurrent-one-token", 1),
                        sharedToken,
                        81L,
                    )
                },
            )
            val results = listOf(first, second)
            assertEquals(1, results.count { result -> result is ExportOutputClaimResult.Acquired })
            assertEquals(
                1,
                results.count { result ->
                    result == ExportOutputClaimResult.Rejected(
                        ExportOutputClaimRejectionReason.CLAIM_TOKEN_CONFLICT,
                    )
                },
            )
            assertEquals(
                listOf(listOf(1L)),
                fixture.sqlite.rows(
                    "SELECT COUNT(*) FROM capture_export_outputs WHERE claim_token = ?",
                    sharedToken.value,
                ),
            )
        } finally {
            secondDatabase.close()
        }
    }

    @Test
    fun deletionRacingClaimHasOneCoherentWinnerAndNoAuthorityLoss() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "deletion-claim-race-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        val secondDatabase = AppDatabase.create(context, databaseName)
        try {
            val secondRepository = RoomShootRepository(secondDatabase)
            val (deletion, claim) = runConcurrently(
                { fixture.repository.beginShootDeletion(SHOOT_ID, 90L) },
                {
                    secondRepository.claimExportOutput(
                        outputIdentity("deletion-claim-race-token", 0),
                        ExportClaimToken("deletion-claim-race-owner"),
                        80L,
                    )
                },
            )
            assertEquals(
                listOf(listOf("DELETING", 8L)),
                fixture.sqlite.rows(
                    "SELECT lifecycle_state, deletion_generation FROM shoots WHERE shoot_id = ?",
                    SHOOT_ID,
                ),
            )
            if (claim is ExportOutputClaimResult.Acquired) {
                assertEquals(BeginShootDeletionResult.Began(8L, 2, 0, 1), deletion)
                assertEquals(
                    listOf(listOf("CLAIMED"), listOf("CANCELLED"), listOf("CANCELLED")),
                    fixture.sqlite.rows(
                        "SELECT lifecycle_state FROM capture_export_outputs " +
                            "WHERE command_token = ? ORDER BY burst_ordinal",
                        "deletion-claim-race-token",
                    ),
                )
            } else {
                assertEquals(ExportOutputClaimResult.BlockedByDeletion, claim)
                assertEquals(BeginShootDeletionResult.Began(8L, 3, 1, 0), deletion)
                assertEquals(
                    listOf(listOf("CANCELLED"), listOf("CANCELLED"), listOf("CANCELLED")),
                    fixture.sqlite.rows(
                        "SELECT lifecycle_state FROM capture_export_outputs " +
                            "WHERE command_token = ? ORDER BY burst_ordinal",
                        "deletion-claim-race-token",
                    ),
                )
            }
        } finally {
            secondDatabase.close()
        }
    }

    @Test
    fun deletionRacingRegistrationEitherBlocksOrPreservesOldGenerationAttempt() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        val command = captureCommand("deletion-registration-race-token")
        val secondDatabase = AppDatabase.create(context, databaseName)
        try {
            val secondRepository = RoomShootRepository(secondDatabase)
            val (deletion, registration) = runConcurrently(
                { fixture.repository.beginShootDeletion(SHOOT_ID, 90L) },
                { secondRepository.registerCaptureAttempt(SESSION_ID, command, 80L) },
            )
            assertEquals(BeginShootDeletionResult.Began(8L, 0, 0, 0), deletion)
            if (registration == AttemptRegistrationResult.Registered) {
                assertEquals(
                    listOf(listOf("REGISTERED", 7L, 1L)),
                    fixture.sqlite.rows(
                        "SELECT lifecycle_state, captured_deletion_generation, " +
                            "(SELECT next_attempt_number FROM shoot_sessions WHERE session_id = ?) " +
                            "FROM capture_attempts WHERE command_token = ?",
                        SESSION_ID,
                        command.token.value,
                    ),
                )
                assertEquals(
                    CaptureStartAuthorizationResult.BlockedByDeletion,
                    fixture.repository.authorizeCaptureStart(SESSION_ID, command.token, 100L),
                )
            } else {
                assertEquals(
                    AttemptRegistrationResult.Rejected(
                        AttemptRegistrationRejectionReason.BLOCKED_BY_DELETION,
                    ),
                    registration,
                )
                assertEquals(
                    listOf(listOf(0L, 0L)),
                    fixture.sqlite.rows(
                        "SELECT (SELECT COUNT(*) FROM capture_attempts), next_attempt_number " +
                            "FROM shoot_sessions WHERE session_id = ?",
                        SESSION_ID,
                    ),
                )
            }
        } finally {
            secondDatabase.close()
        }
    }

    @Test
    fun deletionRacingConfirmationEitherBlocksOrPreservesCommittedAuthority() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        val captureCommand = captureCommand("deletion-confirmation-race-token")
        assertEquals(
            AttemptRegistrationResult.Registered,
            fixture.repository.registerCaptureAttempt(SESSION_ID, captureCommand, 70L),
        )
        assertEquals(
            CaptureStartAuthorizationResult.Started,
            fixture.repository.authorizeCaptureStart(SESSION_ID, captureCommand.token, 75L),
        )
        val confirmation = ShootEffect.ConfirmAndAdvanceCapture(
            token = captureCommand.token,
            poseId = captureCommand.poseId,
            poseIndex = captureCommand.poseIndex,
            outputs = captureCommand.outputs,
        )
        val secondDatabase = AppDatabase.create(context, databaseName)
        try {
            val secondRepository = RoomShootRepository(secondDatabase)
            val (deletion, confirmationResult) = runConcurrently(
                { fixture.repository.beginShootDeletion(SHOOT_ID, 90L) },
                {
                    secondRepository.confirmAndAdvance(
                        confirmation,
                        durableOutputs(confirmation.token),
                        exportTargets(confirmation.token),
                        80L,
                    )
                },
            )
            if (confirmationResult == CaptureConfirmationResult.Applied) {
                assertEquals(BeginShootDeletionResult.Began(8L, 3, 1, 0), deletion)
                assertEquals(
                    listOf(listOf("CONFIRMED", 3L, 1L, 1L, 3L)),
                    fixture.sqlite.rows(
                        "SELECT attempt.lifecycle_state, " +
                            "(SELECT COUNT(*) FROM private_capture_outputs), " +
                            "(SELECT COUNT(*) FROM capture_confirmation_receipts), " +
                            "(SELECT COUNT(*) FROM capture_export_outboxes), " +
                            "(SELECT COUNT(*) FROM capture_export_outputs) " +
                            "FROM capture_attempts AS attempt WHERE command_token = ?",
                        captureCommand.token.value,
                    ),
                )
            } else {
                assertEquals(CaptureConfirmationResult.BlockedByDeletion, confirmationResult)
                assertEquals(BeginShootDeletionResult.Began(8L, 0, 0, 0), deletion)
                assertEquals(
                    listOf(listOf("CAPTURING", 0L, 0L, 0L, 0L)),
                    fixture.sqlite.rows(
                        "SELECT attempt.lifecycle_state, " +
                            "(SELECT COUNT(*) FROM private_capture_outputs), " +
                            "(SELECT COUNT(*) FROM capture_confirmation_receipts), " +
                            "(SELECT COUNT(*) FROM capture_export_outboxes), " +
                            "(SELECT COUNT(*) FROM capture_export_outputs) " +
                            "FROM capture_attempts AS attempt WHERE command_token = ?",
                        captureCommand.token.value,
                    ),
                )
            }
        } finally {
            secondDatabase.close()
        }
    }

    @Test
    fun deletionWinnerBeforeClaimBlocksFreshAuthorityDeterministically() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "ordered-deletion-before-claim",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        assertEquals(
            BeginShootDeletionResult.Began(8L, 3, 1, 0),
            fixture.repository.beginShootDeletion(SHOOT_ID, 70L),
        )
        val afterDeletion = fixture.sqlite.authoritySnapshot()

        assertEquals(
            ExportOutputClaimResult.BlockedByDeletion,
            fixture.repository.claimExportOutput(
                outputIdentity("ordered-deletion-before-claim", 0),
                ExportClaimToken("ordered-blocked-claim"),
                80L,
            ),
        )
        assertEquals(afterDeletion, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun claimWinnerBeforeDeletionIsPreservedDeterministically() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "ordered-claim-before-deletion",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        val identity = outputIdentity("ordered-claim-before-deletion", 0)
        val token = ExportClaimToken("ordered-winning-claim")
        assertEquals(
            true,
            fixture.repository.claimExportOutput(identity, token, 70L)
                .grantsFreshExternalCreateAuthority,
        )
        assertEquals(
            BeginShootDeletionResult.Began(8L, 2, 0, 1),
            fixture.repository.beginShootDeletion(SHOOT_ID, 80L),
        )
        val afterDeletion = fixture.sqlite.authoritySnapshot()
        assertEquals(
            ExportOutputClaimResult.IdempotentReplay(ExportAuthorityStage.CLAIMED),
            fixture.repository.claimExportOutput(identity, token, 999L),
        )
        assertEquals(afterDeletion, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun deletionWinnerBeforeRegistrationBlocksCounterAndAttemptDeterministically() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        assertEquals(
            BeginShootDeletionResult.Began(8L, 0, 0, 0),
            fixture.repository.beginShootDeletion(SHOOT_ID, 70L),
        )
        val afterDeletion = fixture.sqlite.authoritySnapshot()

        assertEquals(
            AttemptRegistrationResult.Rejected(
                AttemptRegistrationRejectionReason.BLOCKED_BY_DELETION,
            ),
            fixture.repository.registerCaptureAttempt(
                SESSION_ID,
                captureCommand("ordered-deletion-before-registration"),
                80L,
            ),
        )
        assertEquals(afterDeletion, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun registrationWinnerBeforeDeletionIsPreservedAndLaterConfirmationCannotMutate() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        val capture = captureCommand("ordered-registration-before-deletion")
        assertEquals(
            AttemptRegistrationResult.Registered,
            fixture.repository.registerCaptureAttempt(SESSION_ID, capture, 70L),
        )
        assertEquals(
            BeginShootDeletionResult.Began(8L, 0, 0, 0),
            fixture.repository.beginShootDeletion(SHOOT_ID, 80L),
        )
        val afterDeletion = fixture.sqlite.authoritySnapshot()
        val confirmation = ShootEffect.ConfirmAndAdvanceCapture(
            token = capture.token,
            poseId = capture.poseId,
            poseIndex = capture.poseIndex,
            outputs = capture.outputs,
        )

        assertEquals(
            CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.WRONG_ATTEMPT_STATE,
            ),
            fixture.repository.confirmAndAdvance(
                confirmation,
                durableOutputs(capture.token),
                exportTargets(capture.token),
                90L,
            ),
        )
        assertEquals(afterDeletion, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun deletionWinnerBeforeConfirmationBlocksAllConfirmationRowsDeterministically() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        val capture = prepareCapturingAttempt(fixture, "ordered-deletion-before-confirmation")
        assertEquals(
            BeginShootDeletionResult.Began(8L, 0, 0, 0),
            fixture.repository.beginShootDeletion(SHOOT_ID, 80L),
        )
        val afterDeletion = fixture.sqlite.authoritySnapshot()
        val confirmation = ShootEffect.ConfirmAndAdvanceCapture(
            token = capture.token,
            poseId = capture.poseId,
            poseIndex = capture.poseIndex,
            outputs = capture.outputs,
        )

        assertEquals(
            CaptureConfirmationResult.BlockedByDeletion,
            fixture.repository.confirmAndAdvance(
                confirmation,
                durableOutputs(capture.token),
                exportTargets(capture.token),
                90L,
            ),
        )
        assertEquals(afterDeletion, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun confirmationWinnerBeforeDeletionPreservesDurableAuthorityDeterministically() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        val capture = prepareCapturingAttempt(fixture, "ordered-confirmation-before-deletion")
        val confirmation = ShootEffect.ConfirmAndAdvanceCapture(
            token = capture.token,
            poseId = capture.poseId,
            poseIndex = capture.poseIndex,
            outputs = capture.outputs,
        )
        assertEquals(
            CaptureConfirmationResult.Applied,
            fixture.repository.confirmAndAdvance(
                confirmation,
                durableOutputs(capture.token),
                exportTargets(capture.token),
                80L,
            ),
        )
        val durableRowsBeforeDeletion = fixture.sqlite.rows(
            "SELECT * FROM private_capture_outputs ORDER BY burst_ordinal",
        ) to fixture.sqlite.rows("SELECT * FROM capture_confirmation_receipts")

        assertEquals(
            BeginShootDeletionResult.Began(8L, 3, 1, 0),
            fixture.repository.beginShootDeletion(SHOOT_ID, 90L),
        )
        assertEquals(
            durableRowsBeforeDeletion,
            fixture.sqlite.rows("SELECT * FROM private_capture_outputs ORDER BY burst_ordinal") to
                fixture.sqlite.rows("SELECT * FROM capture_confirmation_receipts"),
        )
    }

    @Test
    fun deletionPostconditionDetectsSessionMutationAndRollsBack() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "deletion-session-mutation-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            """
            CREATE TRIGGER `test_mutate_session_during_deletion`
            AFTER UPDATE OF lifecycle_state ON `capture_export_outputs`
            FOR EACH ROW
            WHEN OLD.lifecycle_state = 'PENDING' AND NEW.lifecycle_state = 'CANCELLED'
            BEGIN
                UPDATE shoot_sessions
                SET updated_at_epoch_millis = 999
                WHERE session_id = 'deletion-session';
            END
            """.trimIndent(),
        )
        val before = fixture.sqlite.authoritySnapshot()

        assertEquals(
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            fixture.repository.beginShootDeletion(SHOOT_ID, 80L),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
    }

    @Test
    fun cancellationConstraintFailureRollsBackShootBarrierAndAllAuthority() {
        val fixture = openFixture()
        fixture.sqlite.seedShoot()
        fixture.sqlite.seedConfirmedExportAuthority(
            commandToken = "rollback-token",
            attemptNumber = 0L,
            outputStates = listOf(PENDING, PENDING, PENDING),
        )
        fixture.sqlite.execSQL(
            """
            CREATE TRIGGER `test_fail_deletion_cancellation`
            BEFORE UPDATE OF lifecycle_state ON `capture_export_outputs`
            FOR EACH ROW
            WHEN OLD.lifecycle_state = 'PENDING'
             AND NEW.lifecycle_state = 'CANCELLED'
             AND NEW.burst_ordinal = 1
            BEGIN
                SELECT RAISE(ABORT, 'test deletion cancellation failure');
            END
            """.trimIndent(),
        )
        val before = fixture.sqlite.authoritySnapshot()

        val failure = assertThrows(SQLiteConstraintException::class.java) {
            fixture.repository.beginShootDeletion(SHOOT_ID, 100L)
        }
        assertEquals(
            "test deletion cancellation failure",
            failure.message.orEmpty().substringBefore(" (code "),
        )
        assertEquals(before, fixture.sqlite.authoritySnapshot())
        assertEquals(
            listOf(listOf("ACTIVE", 7L, 1L)),
            fixture.sqlite.rows(
                "SELECT lifecycle_state, deletion_generation, updated_at_epoch_millis " +
                    "FROM shoots WHERE shoot_id = ?",
                SHOOT_ID,
            ),
        )
    }

    private fun openFixture(): Fixture {
        val appDatabase = AppDatabase.create(context, databaseName).also { database = it }
        return Fixture(
            sqlite = appDatabase.openHelper.writableDatabase,
            repository = RoomShootRepository(appDatabase),
        )
    }

    private fun closeDatabase() {
        database?.close()
        database = null
    }

    private fun reopenFixture(): Fixture {
        closeDatabase()
        return openFixture()
    }

    private fun outputIdentity(
        commandToken: String,
        ordinal: Int,
    ): PrivateOutputIdentity = PrivateOutputIdentity(CaptureToken(commandToken), ordinal)

    private fun captureCommand(rawToken: String): ShootEffect.CaptureCommand =
        ShootEffect.CaptureCommand(
            CaptureAttempt.create(
                token = CaptureToken(rawToken),
                trigger = CaptureTrigger.MANUAL,
                poseId = "pose-0",
                poseIndex = 0,
                attemptNumber = 0L,
            ),
        )

    private fun prepareCapturingAttempt(
        fixture: Fixture,
        rawToken: String,
    ): ShootEffect.CaptureCommand {
        val command = captureCommand(rawToken)
        check(
            fixture.repository.registerCaptureAttempt(SESSION_ID, command, 70L) ==
                AttemptRegistrationResult.Registered,
        )
        check(
            fixture.repository.authorizeCaptureStart(SESSION_ID, command.token, 75L) ==
                CaptureStartAuthorizationResult.Started,
        )
        return command
    }

    private fun durableOutputs(token: CaptureToken): List<DurablePrivateOutput> =
        (0..2).map { ordinal ->
            DurablePrivateOutput(
                identity = PrivateOutputIdentity(token, ordinal),
                relativePath = "private/${token.value}/$ordinal.jpg",
                byteCount = 100L + ordinal,
                capturedAtEpochMillis = 76L + ordinal,
                integrityMetadata = null,
            )
        }

    private fun exportTargets(token: CaptureToken): List<CaptureExportTarget> =
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

    private fun <First, Second> runConcurrently(
        first: () -> First,
        second: () -> Second,
    ): Pair<First, Second> {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val contentionBarrier = CyclicBarrier(3)
        return try {
            val firstFuture = executor.submit<First> {
                ready.countDown()
                contentionBarrier.await(10L, TimeUnit.SECONDS)
                first()
            }
            val secondFuture = executor.submit<Second> {
                ready.countDown()
                contentionBarrier.await(10L, TimeUnit.SECONDS)
                second()
            }
            check(ready.await(10L, TimeUnit.SECONDS)) {
                "concurrent authority workers did not both become ready"
            }
            contentionBarrier.await(10L, TimeUnit.SECONDS)
            val firstResult = firstFuture.get(30L, TimeUnit.SECONDS)
            val secondResult = secondFuture.get(30L, TimeUnit.SECONDS)
            firstResult to secondResult
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                "concurrent authority workers did not terminate"
            }
        }
    }

    private fun SupportSQLiteDatabase.seedShoot() {
        execSQL(
            """
            INSERT INTO shoots
                (shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis,
                 lifecycle_state, deletion_generation)
            VALUES (?, 'Deletion test shoot', 1, 1, 'ACTIVE', 7)
            """.trimIndent(),
            arrayOf<Any>(SHOOT_ID),
        )
        execSQL(
            """
            INSERT INTO shoot_poses
                (shoot_id, pose_index, pose_id, label, reference_asset_path,
                 mirror_allowed, validation_state, detector_metadata, model_metadata,
                 preprocessing_metadata)
            VALUES (?, 0, 'pose-0', 'Pose 0', NULL, 0, 'VALID', NULL, NULL, NULL)
            """.trimIndent(),
            arrayOf<Any>(SHOOT_ID),
        )
        execSQL(
            """
            INSERT INTO shoot_sessions
                (session_id, shoot_id, current_pose_index, next_attempt_number,
                 lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis)
            VALUES (?, ?, 0, 0, 'ACTIVE', 1, 1)
            """.trimIndent(),
            arrayOf<Any>(SESSION_ID, SHOOT_ID),
        )
    }

    private fun SupportSQLiteDatabase.seedConfirmedExportAuthority(
        commandToken: String,
        attemptNumber: Long,
        outputStates: List<String>,
    ) {
        require(outputStates.size == 3)
        execSQL(
            """
            INSERT INTO capture_attempts
                (command_token, session_id, pose_id, pose_index, attempt_number, trigger_type,
                 lifecycle_state, reconciliation_required, captured_deletion_generation,
                 created_at_epoch_millis, updated_at_epoch_millis, confirmed_at_epoch_millis)
            VALUES (?, ?, 'pose-0', 0, ?, 'MANUAL', 'CONFIRMED', 0, 7, 10, 50, 50)
            """.trimIndent(),
            arrayOf<Any>(commandToken, SESSION_ID, attemptNumber),
        )
        repeat(3) { ordinal ->
            execSQL(
                """
                INSERT INTO private_capture_outputs
                    (command_token, burst_ordinal, relative_path, byte_count, durability_state,
                     captured_at_epoch_millis, integrity_metadata)
                VALUES (?, ?, ?, ?, 'DURABLE', 30, NULL)
                """.trimIndent(),
                arrayOf<Any>(commandToken, ordinal, "private/$commandToken/$ordinal.jpg", 100 + ordinal),
            )
        }
        execSQL(
            """
            INSERT INTO capture_confirmation_receipts
                (command_token, from_pose_index, to_pose_index,
                 applied_deletion_generation, applied_at_epoch_millis)
            VALUES (?, 0, NULL, 7, 50)
            """.trimIndent(),
            arrayOf<Any>(commandToken),
        )
        execSQL(
            """
            INSERT INTO capture_export_outboxes
                (command_token, lifecycle_state, created_at_epoch_millis,
                 updated_at_epoch_millis, retry_metadata)
            VALUES (?, 'PENDING', 50, 50, NULL)
            """.trimIndent(),
            arrayOf<Any>(commandToken),
        )
        outputStates.forEachIndexed { ordinal, state ->
            val claimToken = when (state) {
                PENDING, CANCELLED -> null
                else -> "claim-$ordinal"
            }
            val ambiguity = if (state == AMBIGUOUS) "CREATE_RESULT_UNKNOWN" else "NONE"
            execSQL(
                """
                INSERT INTO capture_export_outputs
                    (command_token, burst_ordinal, target_collection_uri, target_volume,
                     intended_display_name, intended_relative_path, intended_mime_type,
                     lifecycle_state, claim_token, media_uri_string, ambiguity_state,
                     deletion_generation, created_at_epoch_millis, updated_at_epoch_millis)
                VALUES (?, ?, 'content://media/external_primary/images/media',
                        'external_primary', ?, 'Pictures/PoseGuideSnap/', 'image/jpeg',
                        ?, ?, NULL, ?, 7, 50, 50)
                """.trimIndent(),
                arrayOf<Any?>(commandToken, ordinal, "$commandToken-$ordinal.jpg", state, claimToken, ambiguity),
            )
        }
    }

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

    private data class Fixture(
        val sqlite: SupportSQLiteDatabase,
        val repository: RoomShootRepository,
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

    companion object {
        private const val SHOOT_ID = "deletion-shoot"
        private const val SESSION_ID = "deletion-session"
        private const val PENDING = "PENDING"
        private const val CLAIMED = "CLAIMED"
        private const val AMBIGUOUS = "AMBIGUOUS"
        private const val CANCELLED = "CANCELLED"
    }
}
