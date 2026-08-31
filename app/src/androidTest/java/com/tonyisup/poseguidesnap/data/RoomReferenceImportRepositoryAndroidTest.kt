package com.tonyisup.poseguidesnap.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomReferenceImportRepositoryAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "reference_import_repository_android_test_${UUID.randomUUID()}.db"
        context.deleteRoomTestDatabase(databaseName)
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteRoomTestDatabase(databaseName)
        assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
    }

    @Test
    fun reservationAtomicallyCreatesOneCoherentInitialFileLedgerWithoutAnActivePose() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        val reservation = reservation()
        val paths = ReferenceImportFileOperationPaths.forToken(reservation.importToken)

        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository().reserveImport(reservation, 10L),
        )
        assertEquals(
            listOf(
                listOf(
                    TOKEN,
                    paths.relativeAssetPath,
                    paths.relativeTempPath,
                    paths.relativeQuarantinePath,
                    "EXPECTING_RESERVATION",
                    null,
                    null,
                    null,
                    0L,
                    10L,
                    10L,
                ),
            ),
            sqlite.rows(
                "SELECT import_token, relative_asset_path, relative_temp_path, " +
                    "relative_quarantine_path, stage, byte_count, sha256, last_failure_code, " +
                    "reconciliation_required, created_at_epoch_millis, updated_at_epoch_millis " +
                    "FROM reference_import_file_operations WHERE import_token = ?",
                TOKEN,
            ),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(1, sqlite.fileOperationCount())
        assertEquals(0, sqlite.poseCount(SHOOT_ID))

        assertEquals(
            ReferenceImportReserveResult.ExistingWorkRequiresReconciliation,
            repository().reserveImport(reservation, 10L),
        )
        assertEquals(
            ReferenceImportReserveResult.ExistingWorkRequiresReconciliation,
            repository().reserveImport(reservation, 999L),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(1, sqlite.fileOperationCount())
        assertEquals(0, sqlite.poseCount(SHOOT_ID))
    }

    @Test
    fun ledgerInsertFailureRollsBackTheIntentAndLeavesNoActivePose() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        sqlite.execSQL(
            """
            CREATE TRIGGER test_fail_initial_file_ledger_insert
            BEFORE INSERT ON reference_import_file_operations
            FOR EACH ROW
            WHEN NEW.import_token = '$TOKEN'
            BEGIN
                SELECT RAISE(ABORT, 'test file ledger insert failure');
            END
            """.trimIndent(),
        )

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            repository().reserveImport(reservation(), 10L),
        )
        assertEquals(0, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
        assertEquals(0, sqlite.poseCount(SHOOT_ID))
    }

    @Test
    fun exactReplayFailsClosedWhenItsFileLedgerIsMissing() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        val reservation = reservation()
        assertEquals(ReferenceImportReserveResult.Reserved, repository().reserveImport(reservation, 10L))
        sqlite.execSQL(
            "DELETE FROM reference_import_file_operations WHERE import_token = ?",
            arrayOf<Any>(TOKEN),
        )

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            repository().reserveImport(reservation, 11L),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
        assertEquals(0, sqlite.poseCount(SHOOT_ID))
    }

    @Test
    fun reserveReadyCommitAndExactReplayPersistOneValidatedPose() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        val repository = repository()
        val reservation = reservation()
        val evidence = evidence()

        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository.reserveImport(reservation, 10L),
        )
        assertEquals(
            listOf(listOf("PREPARING", 10L, 10L, null, null)),
            sqlite.rows(
                "SELECT lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis, " +
                    "asset_ready_at_epoch_millis, terminal_at_epoch_millis " +
                    "FROM reference_import_intents WHERE import_token = ?",
                TOKEN,
            ),
        )
        assertEquals(0, sqlite.poseCount(SHOOT_ID))
        setFileStage(sqlite, TOKEN, "FINAL_DURABLE")

        assertEquals(
            ReferenceImportAssetReadyResult.MarkedAssetReady,
            repository.markAssetReady(reservation.importToken, reservation.relativeAssetPath, 20L),
        )
        assertEquals(
            ReferenceImportAssetReadyResult.AlreadyAssetReady,
            repository.markAssetReady(reservation.importToken, reservation.relativeAssetPath, 999L),
        )
        assertEquals(0, sqlite.poseCount(SHOOT_ID))

        assertEquals(ReferenceImportCommitResult.Committed, repository.commitImport(evidence, 30L))
        assertEquals(
            listOf(listOf("COMMITTED", 10L, 30L, 20L, 30L)),
            sqlite.rows(
                "SELECT lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis, " +
                    "asset_ready_at_epoch_millis, terminal_at_epoch_millis " +
                    "FROM reference_import_intents WHERE import_token = ?",
                TOKEN,
            ),
        )
        assertEquals(
            listOf(
                listOf(
                    4L,
                    POSE_ID,
                    "Reference pose",
                    evidence.relativeAssetPath,
                    1L,
                    "VALIDATED",
                    evidence.detectorMetadata,
                    evidence.modelMetadata,
                    evidence.preprocessingMetadata,
                    evidence.landmarkPayload.value,
                    evidence.coordinateMetadata,
                ),
            ),
            sqlite.rows(
                "SELECT pose_index, pose_id, label, reference_asset_path, mirror_allowed, " +
                    "validation_state, detector_metadata, model_metadata, preprocessing_metadata, " +
                    "landmark_payload, coordinate_metadata FROM shoot_poses WHERE shoot_id = ?",
                SHOOT_ID,
            ),
        )

        closeDatabase()
        assertEquals(
            ReferenceImportReserveResult.AlreadyCommitted,
            repository().reserveImport(reservation, 999L),
        )
        assertEquals(
            ReferenceImportCommitResult.AlreadyCommitted,
            repository().commitImport(evidence, 999L),
        )
        assertEquals(
            ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.EVIDENCE_CONFLICT,
            ),
            repository().commitImport(evidence(label = "Changed label"), 999L),
        )
    }

    @Test
    fun exactPreparingReservationClosesWhileTokenPoseIdAndPoseIndexContradictionsFailClosed() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        val repository = repository()
        val original = reservation()
        assertEquals(ReferenceImportReserveResult.Reserved, repository.reserveImport(original, 10L))

        assertEquals(
            ReferenceImportReserveResult.ExistingWorkRequiresReconciliation,
            repository.reserveImport(original, 999L),
        )
        assertEquals(
            ReferenceImportReserveResult.Rejected(ReferenceImportReserveRejectionReason.TOKEN_CONFLICT),
            repository.reserveImport(
                reservation(shootId = SHOOT_ID, poseId = "pose-other", poseIndex = 5, token = TOKEN),
                11L,
            ),
        )
        assertEquals(
            ReferenceImportReserveResult.Rejected(ReferenceImportReserveRejectionReason.POSE_ID_CONFLICT),
            repository.reserveImport(
                reservation(poseId = POSE_ID, poseIndex = 5, token = "token-pose-id-conflict"),
                11L,
            ),
        )
        assertEquals(
            ReferenceImportReserveResult.Rejected(ReferenceImportReserveRejectionReason.POSE_INDEX_CONFLICT),
            repository.reserveImport(
                reservation(poseId = "pose-other", poseIndex = 4, token = "token-index-conflict"),
                11L,
            ),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(0, sqlite.poseCount(SHOOT_ID))
    }

    @Test
    fun unresolvedExactStatesCloseAndCleanedExactRetryAtomicallyResetsTheSameRow() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val states = listOf("preparing", "asset-ready", "quarantined", "cleaned")
        states.forEach { suffix -> seedShoot(sqlite, "shoot-$suffix") }
        val repository = repository()
        val reservations = states.associateWith { suffix ->
            reservation("shoot-$suffix", "pose-$suffix", 0, "token-$suffix")
        }
        reservations.values.forEach { candidate ->
            assertEquals(ReferenceImportReserveResult.Reserved, repository.reserveImport(candidate, 10L))
        }
        setFileStage(sqlite, "token-asset-ready", "FINAL_DURABLE")
        assertEquals(
            ReferenceImportAssetReadyResult.MarkedAssetReady,
            repository.markAssetReady(
                requireNotNull(reservations["asset-ready"]).importToken,
                requireNotNull(reservations["asset-ready"]).relativeAssetPath,
                11L,
            ),
        )
        setFileStage(sqlite, "token-quarantined", "QUARANTINE_DURABLE")
        setFileStage(sqlite, "token-cleaned", "CLEANED_DURABLE")
        listOf(
            "quarantined" to ReferenceImportFailureSettlement.QUARANTINED,
            "cleaned" to ReferenceImportFailureSettlement.CLEANED,
        ).forEach { (suffix, settlement) ->
            assertEquals(
                ReferenceImportSettlementResult.Settled,
                repository.settleFailure(
                    requireNotNull(reservations[suffix]).importToken,
                    settlement,
                    20L,
                ),
            )
        }

        listOf("preparing", "asset-ready", "quarantined").forEach { suffix ->
            assertEquals(
                ReferenceImportReserveResult.ExistingWorkRequiresReconciliation,
                repository.reserveImport(requireNotNull(reservations[suffix]), 21L),
            )
        }
        val cleaned = requireNotNull(reservations["cleaned"])
        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.INVALID_TIMESTAMP,
            ),
            repository.reserveImport(cleaned, 19L),
        )
        assertEquals("REJECTED_CLEANED", sqlite.intentState("token-cleaned"))
        assertEquals(
            ReferenceImportReserveResult.ExistingWorkRequiresReconciliation,
            repository.reserveImport(cleaned, 20L),
        )
        assertEquals(
            ReferenceImportRestartCleanedResult.Rejected(
                ReferenceImportRestartCleanedRejectionReason.INVALID_TIMESTAMP,
            ),
            repository.restartCleanedImport(cleaned, 20L),
        )
        assertEquals(
            ReferenceImportRestartCleanedResult.Restarted,
            repository.restartCleanedImport(cleaned, 21L),
        )
        assertEquals(
            ReferenceImportRestartCleanedResult.Rejected(
                ReferenceImportRestartCleanedRejectionReason.WRONG_STATE,
            ),
            repository.restartCleanedImport(cleaned, 22L),
        )
        assertEquals(
            listOf(listOf("PREPARING", 21L, 21L, null, null)),
            sqlite.rows(
                "SELECT lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis, " +
                    "asset_ready_at_epoch_millis, terminal_at_epoch_millis " +
                    "FROM reference_import_intents WHERE import_token = ?",
                "token-cleaned",
            ),
        )
        assertEquals(
            listOf(listOf("EXPECTING_RESERVATION", null, null, null, 0L, 21L, 21L)),
            sqlite.rows(
                "SELECT stage, byte_count, sha256, last_failure_code, " +
                    "reconciliation_required, created_at_epoch_millis, updated_at_epoch_millis " +
                    "FROM reference_import_file_operations WHERE import_token = ?",
                "token-cleaned",
            ),
        )
        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.POSE_ID_CONFLICT,
            ),
            repository.reserveImport(
                reservation("shoot-cleaned", "pose-cleaned", 1, "different-token"),
                21L,
            ),
        )
        assertEquals(4, sqlite.intentCount())
        assertEquals(0, states.sumOf { suffix -> sqlite.poseCount("shoot-$suffix") })
    }

    @Test
    fun cleanedRestartRollsBackLogicalResetWhenFileLedgerResetFailsLate() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        val repository = repository()
        val cleaned = reservation(token = "restart-rollback-token")
        assertEquals(ReferenceImportReserveResult.Reserved, repository.reserveImport(cleaned, 10L))
        setFileStage(sqlite, cleaned.importToken.value, "CLEANED_DURABLE")
        assertEquals(
            ReferenceImportSettlementResult.Settled,
            repository.settleFailure(cleaned.importToken, ReferenceImportFailureSettlement.CLEANED, 20L),
        )
        sqlite.execSQL(
            """
            CREATE TRIGGER test_fail_cleaned_file_reset
            BEFORE UPDATE OF stage ON reference_import_file_operations
            FOR EACH ROW
            WHEN OLD.import_token = '${cleaned.importToken.value}'
              AND OLD.stage = 'CLEANED_DURABLE'
              AND NEW.stage = 'EXPECTING_RESERVATION'
            BEGIN
                SELECT RAISE(ABORT, 'generated reset failure');
            END
            """.trimIndent(),
        )

        assertEquals(
            ReferenceImportRestartCleanedResult.Rejected(
                ReferenceImportRestartCleanedRejectionReason.TRANSACTION_CAS_FAILED,
            ),
            repository.restartCleanedImport(cleaned, 21L),
        )
        assertEquals("REJECTED_CLEANED", sqlite.intentState(cleaned.importToken.value))
        assertEquals(
            listOf(listOf("CLEANED_DURABLE", 10L)),
            sqlite.rows(
                "SELECT stage, updated_at_epoch_millis " +
                    "FROM reference_import_file_operations WHERE import_token = ?",
                cleaned.importToken.value,
            ),
        )

        sqlite.execSQL("DROP TRIGGER test_fail_cleaned_file_reset")
        assertEquals(
            ReferenceImportRestartCleanedResult.Restarted,
            repository.restartCleanedImport(cleaned, 21L),
        )
    }

    @Test
    fun committedReservationRequiresAnExactValidatedPoseRow() {
        val sqlite = openDatabase().openHelper.writableDatabase
        listOf("missing", "path", "validation").forEach { suffix ->
            seedShoot(sqlite, "shoot-$suffix")
            val token = "token-$suffix"
            prepare(repository(), "shoot-$suffix", "pose-$suffix", 0, token, 10L)
            assertEquals(
                ReferenceImportCommitResult.Committed,
                repository().commitImport(
                    evidence("shoot-$suffix", "pose-$suffix", 0, token),
                    30L,
                ),
            )
        }
        sqlite.execSQL("DELETE FROM shoot_poses WHERE shoot_id = 'shoot-missing'")
        sqlite.execSQL(
            "UPDATE shoot_poses SET reference_asset_path = 'reference-assets/assets/wrong.asset' " +
                "WHERE shoot_id = 'shoot-path'",
        )
        sqlite.execSQL(
            "UPDATE shoot_poses SET validation_state = 'VALID' WHERE shoot_id = 'shoot-validation'",
        )

        listOf("missing", "path", "validation").forEach { suffix ->
            assertEquals(
                ReferenceImportReserveResult.Rejected(
                    ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
                ),
                repository().reserveImport(
                    reservation("shoot-$suffix", "pose-$suffix", 0, "token-$suffix"),
                    31L,
                ),
            )
        }
    }

    @Test
    fun commitRequiresActiveNonDeletingShootNoActiveSessionAndVacantIdentity() {
        val sqlite = openDatabase().openHelper.writableDatabase
        listOf("deleting", "session", "pose-id", "pose-index").forEach { suffix ->
            seedShoot(sqlite, "shoot-$suffix")
        }
        val repository = repository()

        prepare(repository, "shoot-deleting", "pose-deleting", 0, "token-deleting", 10L)
        sqlite.execSQL(
            "UPDATE shoots SET lifecycle_state = 'DELETING', deletion_generation = 1 " +
                "WHERE shoot_id = 'shoot-deleting'",
        )
        assertEquals(
            ReferenceImportCommitResult.BlockedByDeletion,
            repository.commitImport(evidence("shoot-deleting", "pose-deleting", 0, "token-deleting"), 30L),
        )

        prepare(repository, "shoot-session", "pose-session", 0, "token-session", 40L)
        seedSession(sqlite, "shoot-session")
        assertEquals(
            ReferenceImportCommitResult.Rejected(ReferenceImportCommitRejectionReason.ACTIVE_SESSION),
            repository.commitImport(evidence("shoot-session", "pose-session", 0, "token-session"), 60L),
        )

        prepare(repository, "shoot-pose-id", "pose-owned", 1, "token-pose-id", 70L)
        seedPose(sqlite, "shoot-pose-id", "pose-owned", 0)
        assertEquals(
            ReferenceImportCommitResult.Rejected(ReferenceImportCommitRejectionReason.POSE_ID_CONFLICT),
            repository.commitImport(evidence("shoot-pose-id", "pose-owned", 1, "token-pose-id"), 90L),
        )

        prepare(repository, "shoot-pose-index", "pose-new", 1, "token-pose-index", 100L)
        seedPose(sqlite, "shoot-pose-index", "pose-owned", 1)
        assertEquals(
            ReferenceImportCommitResult.Rejected(ReferenceImportCommitRejectionReason.POSE_INDEX_CONFLICT),
            repository.commitImport(evidence("shoot-pose-index", "pose-new", 1, "token-pose-index"), 120L),
        )

        assertEquals(0, sqlite.poseCount("shoot-deleting"))
        assertEquals(0, sqlite.poseCount("shoot-session"))
        assertEquals("ASSET_READY", sqlite.intentState("token-deleting"))
        assertEquals("ASSET_READY", sqlite.intentState("token-session"))
    }

    @Test
    fun recoveryLookupIsExactAndLogicalIntentNeverUsesAReconciliationTerminalState() {
        val sqlite = openDatabase().openHelper.writableDatabase
        listOf("cleaned", "quarantined", "preparing").forEach { suffix ->
            val shootId = "shoot-$suffix"
            seedShoot(sqlite, shootId)
            val reservation = reservation(
                shootId,
                "pose-$suffix",
                0,
                "token-$suffix",
            )
            assertEquals(
                ReferenceImportReserveResult.Reserved,
                repository().reserveImport(reservation, 10L),
            )
        }

        setFileStage(sqlite, "token-cleaned", "CLEANED_DURABLE")
        setFileStage(sqlite, "token-quarantined", "QUARANTINE_DURABLE")
        assertEquals(
            ReferenceImportSettlementResult.Settled,
            repository().settleFailure(
                ReferenceImportToken("token-cleaned"),
                ReferenceImportFailureSettlement.CLEANED,
                20L,
            ),
        )
        assertEquals(
            ReferenceImportSettlementResult.Settled,
            repository().settleFailure(
                ReferenceImportToken("token-quarantined"),
                ReferenceImportFailureSettlement.QUARANTINED,
                21L,
            ),
        )
        assertEquals(0, sqlite.poseCount("shoot-cleaned"))
        assertEquals(0, sqlite.poseCount("shoot-quarantined"))
        assertEquals(0, sqlite.poseCount("shoot-preparing"))

        val pending = requireNotNull(
            repository().findExactImportForRecovery(ReferenceImportToken("token-preparing")),
        )
        assertEquals(ReferenceImportLifecycle.PREPARING, pending.lifecycle)
        assertEquals("token-preparing", pending.importToken.value)
        assertEquals(null, repository().findExactImportForRecovery(ReferenceImportToken("missing-token")))
        val forbiddenLogicalStateCount = sqlite.query(
            "SELECT COUNT(*) FROM reference_import_intents " +
                "WHERE lifecycle_state = 'RECONCILIATION_REQUIRED'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
        assertEquals(0, forbiddenLogicalStateCount)
    }

    @Test
    fun lateAuthorityContradictionRollsBackPoseAndCommittedTransition() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        val repository = repository()
        prepare(repository, SHOOT_ID, POSE_ID, 4, TOKEN, 10L)
        sqlite.execSQL(
            """
            CREATE TRIGGER test_corrupt_reference_commit
            AFTER INSERT ON shoot_poses
            FOR EACH ROW
            WHEN NEW.shoot_id = '$SHOOT_ID'
            BEGIN
                UPDATE reference_import_intents
                SET lifecycle_state = 'ASSET_READY', terminal_at_epoch_millis = NULL
                WHERE import_token = '$TOKEN';
            END
            """.trimIndent(),
        )

        assertEquals(
            ReferenceImportCommitResult.Rejected(
                ReferenceImportCommitRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            repository.commitImport(evidence(), 30L),
        )
        assertEquals(0, sqlite.poseCount(SHOOT_ID))
        assertEquals("ASSET_READY", sqlite.intentState(TOKEN))
    }

    @Test
    fun concurrentReservationsForSamePoseHaveExactlyOneUniqueIndexWinner() {
        val first = openDatabase()
        val sqlite = first.openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        val second = AppDatabase.create(context, databaseName)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = listOf(
                Callable {
                    RoomReferenceImportRepository(first).reserveImport(
                        reservation(token = "concurrent-a"),
                        10L,
                    )
                },
                Callable {
                    RoomReferenceImportRepository(second).reserveImport(
                        reservation(token = "concurrent-b"),
                        10L,
                    )
                },
            )
            val results = executor.invokeAll(calls).map { future -> future.get() }

            assertEquals(1, results.count { result -> result == ReferenceImportReserveResult.Reserved })
            assertEquals(
                1,
                results.count { result ->
                    result == ReferenceImportReserveResult.Rejected(
                        ReferenceImportReserveRejectionReason.POSE_ID_CONFLICT,
                    ) || result == ReferenceImportReserveResult.Rejected(
                        ReferenceImportReserveRejectionReason.POSE_INDEX_CONFLICT,
                    )
                },
            )
            assertEquals(1, sqlite.intentCount())
        } finally {
            executor.shutdownNow()
            second.close()
        }
    }

    private fun openDatabase(): AppDatabase =
        AppDatabase.create(context, databaseName).also { database = it }

    private fun closeDatabase() {
        database?.close()
        database = null
    }

    private fun repository(): RoomReferenceImportRepository =
        RoomReferenceImportRepository(database ?: openDatabase())

    private fun prepare(
        repository: RoomReferenceImportRepository,
        shootId: String,
        poseId: String,
        poseIndex: Int,
        token: String,
        startAt: Long,
    ) {
        val reservation = reservation(shootId, poseId, poseIndex, token)
        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository.reserveImport(reservation, startAt),
        )
        setFileStage(
            requireNotNull(database).openHelper.writableDatabase,
            token,
            "FINAL_DURABLE",
        )
        assertEquals(
            ReferenceImportAssetReadyResult.MarkedAssetReady,
            repository.markAssetReady(
                reservation.importToken,
                reservation.relativeAssetPath,
                startAt + 1L,
            ),
        )
    }

    private fun setFileStage(
        sqlite: SupportSQLiteDatabase,
        token: String,
        stage: String,
    ) {
        val keepsEvidence = stage != "CLEANED_DURABLE"
        sqlite.execSQL(
            "UPDATE reference_import_file_operations SET stage = ?, byte_count = ?, sha256 = ?, " +
                "last_failure_code = NULL, reconciliation_required = 0 " +
                "WHERE import_token = ?",
            arrayOf<Any?>(
                stage,
                if (keepsEvidence) 7L else null,
                if (keepsEvidence) "ab".repeat(32) else null,
                token,
            ),
        )
    }

    private fun reservation(
        shootId: String = SHOOT_ID,
        poseId: String = POSE_ID,
        poseIndex: Int = 4,
        token: String = TOKEN,
    ): ReferenceImportReservation = ReferenceImportReservation(
        importToken = ReferenceImportToken(token),
        shootId = shootId,
        poseId = poseId,
        poseIndex = poseIndex,
        relativeAssetPath = ReferenceImportAssetPath.forToken(ReferenceImportToken(token)),
    )

    private fun evidence(
        shootId: String = SHOOT_ID,
        poseId: String = POSE_ID,
        poseIndex: Int = 4,
        token: String = TOKEN,
        label: String = "Reference pose",
    ): ReferenceImportEvidence = ReferenceImportEvidence(
        importToken = ReferenceImportToken(token),
        shootId = shootId,
        poseId = poseId,
        poseIndex = poseIndex,
        label = label,
        relativeAssetPath = ReferenceImportAssetPath.forToken(ReferenceImportToken(token)),
        mirrorAllowed = true,
        landmarkPayload = ReferenceLandmarkPayload.from(
            listOf(
                Landmark(PoseLandmark.NOSE, 0.1, 0.2, 0.0, 0.9, 0.8),
                Landmark(PoseLandmark.LEFT_SHOULDER, 0.2, 0.3, 0.0, 0.9, 0.8),
            ),
        ),
        detectorMetadata = "movenet-multipose-v1",
        modelMetadata = "lightning-float16-v1",
        preprocessingMetadata = "letterbox-256-v1",
        coordinateMetadata = "upright-normalized-v1",
    )

    private fun seedShoot(sqlite: SupportSQLiteDatabase, shootId: String) {
        sqlite.execSQL(
            "INSERT INTO shoots (shoot_id, name, created_at_epoch_millis, " +
                "updated_at_epoch_millis, lifecycle_state, deletion_generation) " +
                "VALUES (?, 'Test shoot', 1, 1, 'ACTIVE', 0)",
            arrayOf<Any>(shootId),
        )
    }

    private fun seedSession(sqlite: SupportSQLiteDatabase, shootId: String) {
        sqlite.execSQL(
            "INSERT INTO shoot_sessions (session_id, shoot_id, current_pose_index, " +
                "next_attempt_number, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis) VALUES (?, ?, 0, 0, 'ACTIVE', 1, 1)",
            arrayOf<Any>("session-$shootId", shootId),
        )
    }

    private fun seedPose(
        sqlite: SupportSQLiteDatabase,
        shootId: String,
        poseId: String,
        poseIndex: Int,
    ) {
        sqlite.execSQL(
            "INSERT INTO shoot_poses (shoot_id, pose_index, pose_id, label, " +
                "reference_asset_path, mirror_allowed, validation_state, detector_metadata, " +
                "model_metadata, preprocessing_metadata, landmark_payload, coordinate_metadata) " +
                "VALUES (?, ?, ?, 'Existing', NULL, 0, 'VALID', NULL, NULL, NULL, NULL, NULL)",
            arrayOf<Any>(shootId, poseIndex, poseId),
        )
    }

    private fun SupportSQLiteDatabase.intentCount(): Int =
        query("SELECT COUNT(*) FROM reference_import_intents").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.fileOperationCount(): Int =
        query("SELECT COUNT(*) FROM reference_import_file_operations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.poseCount(shootId: String): Int =
        query("SELECT COUNT(*) FROM shoot_poses WHERE shoot_id = ?", arrayOf(shootId)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.intentState(token: String): String =
        query(
            "SELECT lifecycle_state FROM reference_import_intents WHERE import_token = ?",
            arrayOf(token),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.rows(sql: String, argument: String): List<List<Any?>> =
        query(sql, arrayOf(argument)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        (0 until cursor.columnCount).map { column ->
                            when (cursor.getType(column)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                                android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                                android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column)
                                else -> error("unexpected cursor type")
                            }
                        },
                    )
                }
            }
        }

    companion object {
        private const val SHOOT_ID = "shoot-1"
        private const val POSE_ID = "pose-5"
        private const val TOKEN = "import-token"
    }
}
