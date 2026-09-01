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

    @Test
    fun importAdmissionAllowsAnActiveEmptyShootWithoutWritingRows() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)

        assertEquals(
            ReferenceImportAdmissionCheckResult.Allowed,
            repository().checkImportAdmission(SHOOT_ID),
        )
        assertEquals(0, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
    }

    @Test
    fun importAdmissionClassifiesUnknownDeletingFullAndActiveSessionWithoutWrites() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "admission-deleting")
        seedShoot(sqlite, "admission-full")
        seedShoot(sqlite, "admission-session")
        sqlite.execSQL(
            "UPDATE shoots SET lifecycle_state = 'DELETING', deletion_generation = 1 " +
                "WHERE shoot_id = 'admission-deleting'",
        )
        (0 until 20).forEach { index ->
            seedValidatedPose(sqlite, "admission-full", "admission-pose-$index", index)
        }
        seedSession(sqlite, "admission-session")
        val repository = repository()

        assertEquals(
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.UNKNOWN_SHOOT,
            ),
            repository.checkImportAdmission("admission-unknown"),
        )
        assertEquals(
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.SHOOT_DELETING,
            ),
            repository.checkImportAdmission("admission-deleting"),
        )
        assertEquals(
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.PLAYLIST_FULL,
            ),
            repository.checkImportAdmission("admission-full"),
        )
        assertEquals(
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.ACTIVE_SESSION,
            ),
            repository.checkImportAdmission("admission-session"),
        )
        assertEquals(0, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
    }

    @Test
    fun importAdmissionDistinguishesInProgressFromReconciliationRequired() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        val repository = repository()
        val reservation = reservation()
        assertEquals(ReferenceImportReserveResult.Reserved, repository.reserveImport(reservation, 10L))

        assertEquals(
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.IMPORT_IN_PROGRESS,
            ),
            repository.checkImportAdmission(SHOOT_ID),
        )
        sqlite.execSQL(
            "UPDATE reference_import_file_operations SET reconciliation_required = 1, " +
                "last_failure_code = 'STATE_MISMATCH' WHERE import_token = ?",
            arrayOf<Any>(reservation.importToken.value),
        )
        assertEquals(
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.RECONCILIATION_REQUIRED,
            ),
            repository.checkImportAdmission(SHOOT_ID),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(1, sqlite.fileOperationCount())
    }

    @Test
    fun importAdmissionPrioritizesAnyReconciliationRequiredWorkOverOlderInProgressWork() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "admission-mixed-source")
        seedShoot(sqlite, "admission-mixed-target")
        val repository = repository()
        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository.reserveImport(
                reservation(
                    shootId = "admission-mixed-source",
                    poseId = "admission-mixed-progress-pose",
                    token = "admission-mixed-progress-token",
                ),
                10L,
            ),
        )
        val reconciliationToken = ReferenceImportToken("admission-mixed-reconciliation-token")
        val reconciliationPaths = ReferenceImportFileOperationPaths.forToken(reconciliationToken)
        sqlite.execSQL(
            "INSERT INTO reference_import_intents (import_token, shoot_id, pose_id, " +
                "relative_asset_path, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis, asset_ready_at_epoch_millis, terminal_at_epoch_millis) " +
                "VALUES (?, 'admission-mixed-source', 'admission-mixed-reconciliation-pose', ?, " +
                "'REJECTED_QUARANTINED', 20, 40, NULL, 40)",
            arrayOf<Any>(reconciliationToken.value, reconciliationPaths.relativeAssetPath),
        )
        sqlite.execSQL(
            "INSERT INTO reference_import_file_operations (import_token, relative_asset_path, " +
                "relative_temp_path, relative_quarantine_path, stage, byte_count, sha256, " +
                "last_failure_code, reconciliation_required, created_at_epoch_millis, " +
                "updated_at_epoch_millis) VALUES (?, ?, ?, ?, 'QUARANTINE_DURABLE', 7, ?, " +
                "'STATE_MISMATCH', 1, 20, 30)",
            arrayOf<Any>(
                reconciliationToken.value,
                reconciliationPaths.relativeAssetPath,
                reconciliationPaths.relativeTempPath,
                reconciliationPaths.relativeQuarantinePath,
                "ef".repeat(32),
            ),
        )

        assertEquals(
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.RECONCILIATION_REQUIRED,
            ),
            repository.checkImportAdmission("admission-mixed-target"),
        )
        assertEquals(2, sqlite.intentCount())
        assertEquals(2, sqlite.fileOperationCount())
    }

    @Test
    fun importAdmissionFailsClosedWhenRequiredLedgerAuthorityIsMissing() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        val repository = repository()
        val reservation = reservation()
        assertEquals(ReferenceImportReserveResult.Reserved, repository.reserveImport(reservation, 10L))
        sqlite.execSQL(
            "DELETE FROM reference_import_file_operations WHERE import_token = ?",
            arrayOf<Any>(reservation.importToken.value),
        )

        assertEquals(
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.AUTHORITY_INCONSISTENT,
            ),
            repository.checkImportAdmission(SHOOT_ID),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
    }

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
    fun reservationIsRejectedBeforeCreatingRowsWhenAnActiveSessionExists() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        seedSession(sqlite, SHOOT_ID)
        val reservation = reservation(
            poseId = "active-session-pose",
            token = "active-session-token",
        )

        val result = repository().reserveImport(reservation, 10L)

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.ACTIVE_SESSION,
            ),
            result,
        )
        assertEquals(
            emptyList<List<Any?>>(),
            sqlite.rows(
                "SELECT import_token FROM reference_import_intents WHERE import_token = ?",
                reservation.importToken.value,
            ),
        )
        assertEquals(
            emptyList<List<Any?>>(),
            sqlite.rows(
                "SELECT import_token FROM reference_import_file_operations WHERE import_token = ?",
                reservation.importToken.value,
            ),
        )
        assertEquals(0, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
    }

    @Test
    fun reservationDistinguishesDeletingShootFromCorruptShootLifecycleWithoutWritingAuthority() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val deletingShoot = "reserve-deleting-shoot"
        val corruptShoot = "reserve-corrupt-shoot"
        seedShoot(sqlite, deletingShoot)
        seedShoot(sqlite, corruptShoot)
        sqlite.execSQL(
            "UPDATE shoots SET lifecycle_state = 'DELETING', deletion_generation = 1 " +
                "WHERE shoot_id = ?",
            arrayOf<Any>(deletingShoot),
        )
        sqlite.execSQL(
            "UPDATE shoots SET lifecycle_state = 'CORRUPT_UNKNOWN_LIFECYCLE' WHERE shoot_id = ?",
            arrayOf<Any>(corruptShoot),
        )
        val repository = repository()

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.SHOOT_NOT_ACTIVE,
            ),
            repository.reserveImport(
                reservation(
                    shootId = deletingShoot,
                    poseId = "reserve-deleting-pose",
                    token = "reserve-deleting-token",
                ),
                10L,
            ),
        )
        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            repository.reserveImport(
                reservation(
                    shootId = corruptShoot,
                    poseId = "reserve-corrupt-pose",
                    token = "reserve-corrupt-token",
                ),
                20L,
            ),
        )
        assertEquals(0, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
        assertEquals(0, sqlite.poseCount(deletingShoot))
        assertEquals(0, sqlite.poseCount(corruptShoot))
    }

    @Test
    fun secondReservationIsRejectedBeforeRowsWhenUnresolvedImportWorkExists() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        val repository = repository()
        val first = reservation(
            poseId = "first-unresolved-pose",
            token = "first-unresolved-token",
        )
        val second = reservation(
            poseId = "second-fresh-pose",
            token = "second-fresh-token",
        )
        val firstPaths = ReferenceImportFileOperationPaths.forToken(first.importToken)

        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository.reserveImport(first, 10L),
        )

        val secondResult = repository.reserveImport(second, 20L)

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.UNRESOLVED_IMPORT_WORK,
            ),
            secondResult,
        )
        assertEquals(
            listOf(
                listOf(
                    first.importToken.value,
                    SHOOT_ID,
                    first.poseId,
                    first.relativeAssetPath,
                    "PREPARING",
                    10L,
                    10L,
                    null,
                    null,
                ),
            ),
            sqlite.rows(
                "SELECT import_token, shoot_id, pose_id, relative_asset_path, lifecycle_state, " +
                    "created_at_epoch_millis, updated_at_epoch_millis, " +
                    "asset_ready_at_epoch_millis, terminal_at_epoch_millis " +
                    "FROM reference_import_intents WHERE import_token = ?",
                first.importToken.value,
            ),
        )
        assertEquals(
            listOf(
                listOf(
                    first.importToken.value,
                    firstPaths.relativeAssetPath,
                    firstPaths.relativeTempPath,
                    firstPaths.relativeQuarantinePath,
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
                first.importToken.value,
            ),
        )
        assertEquals(
            emptyList<List<Any?>>(),
            sqlite.rows(
                "SELECT import_token FROM reference_import_intents WHERE import_token = ?",
                second.importToken.value,
            ),
        )
        assertEquals(
            emptyList<List<Any?>>(),
            sqlite.rows(
                "SELECT import_token FROM reference_import_file_operations WHERE import_token = ?",
                second.importToken.value,
            ),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(1, sqlite.fileOperationCount())
    }

    @Test
    fun unresolvedImportWorkInAnotherShootRejectsFreshReservationBeforeCreatingRows() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val shootA = "cross-shoot-a"
        val shootB = "cross-shoot-b"
        seedShoot(sqlite, shootA)
        seedShoot(sqlite, shootB)
        val repository = repository()
        val reservationA = reservation(
            shootId = shootA,
            poseId = "cross-shoot-pose-a",
            token = "cross-shoot-token-a",
        )
        val reservationB = reservation(
            shootId = shootB,
            poseId = "cross-shoot-pose-b",
            token = "cross-shoot-token-b",
        )
        val pathsA = ReferenceImportFileOperationPaths.forToken(reservationA.importToken)

        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository.reserveImport(reservationA, 10L),
        )

        val resultB = repository.reserveImport(reservationB, 20L)

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.UNRESOLVED_IMPORT_WORK,
            ),
            resultB,
        )
        assertEquals(
            listOf(
                listOf(
                    reservationA.importToken.value,
                    shootA,
                    reservationA.poseId,
                    reservationA.relativeAssetPath,
                    "PREPARING",
                    10L,
                    10L,
                    null,
                    null,
                ),
            ),
            sqlite.rows(
                "SELECT import_token, shoot_id, pose_id, relative_asset_path, lifecycle_state, " +
                    "created_at_epoch_millis, updated_at_epoch_millis, " +
                    "asset_ready_at_epoch_millis, terminal_at_epoch_millis " +
                    "FROM reference_import_intents WHERE import_token = ?",
                reservationA.importToken.value,
            ),
        )
        assertEquals(
            listOf(
                listOf(
                    reservationA.importToken.value,
                    pathsA.relativeAssetPath,
                    pathsA.relativeTempPath,
                    pathsA.relativeQuarantinePath,
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
                reservationA.importToken.value,
            ),
        )
        assertEquals(
            emptyList<List<Any?>>(),
            sqlite.rows(
                "SELECT import_token FROM reference_import_intents WHERE import_token = ?",
                reservationB.importToken.value,
            ),
        )
        assertEquals(
            emptyList<List<Any?>>(),
            sqlite.rows(
                "SELECT import_token FROM reference_import_file_operations WHERE import_token = ?",
                reservationB.importToken.value,
            ),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(1, sqlite.fileOperationCount())
    }

    @Test
    fun reconciliationRequiredTerminalWorkInAnotherShootBlocksFreshReservationWithoutCreatingRows() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val shootA = "terminal-reconciliation-shoot-a"
        val shootB = "terminal-reconciliation-shoot-b"
        seedShoot(sqlite, shootA)
        seedShoot(sqlite, shootB)
        val repository = repository()
        val reservationA = reservation(
            shootId = shootA,
            poseId = "terminal-reconciliation-pose-a",
            token = "terminal-reconciliation-token-a",
        )
        val reservationB = reservation(
            shootId = shootB,
            poseId = "terminal-reconciliation-pose-b",
            token = "terminal-reconciliation-token-b",
        )
        val pathsA = ReferenceImportFileOperationPaths.forToken(reservationA.importToken)
        val canonicalHash = "cd".repeat(32)

        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository.reserveImport(reservationA, 10L),
        )
        sqlite.execSQL(
            "UPDATE reference_import_intents SET lifecycle_state = 'REJECTED_QUARANTINED', " +
                "updated_at_epoch_millis = 40, terminal_at_epoch_millis = 40 " +
                "WHERE import_token = ?",
            arrayOf<Any>(reservationA.importToken.value),
        )
        sqlite.execSQL(
            "UPDATE reference_import_file_operations SET stage = 'QUARANTINE_DURABLE', " +
                "byte_count = 7, sha256 = ?, last_failure_code = 'STATE_MISMATCH', " +
                "reconciliation_required = 1, updated_at_epoch_millis = 30 " +
                "WHERE import_token = ?",
            arrayOf<Any>(canonicalHash, reservationA.importToken.value),
        )

        val resultB = repository.reserveImport(reservationB, 50L)

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.UNRESOLVED_IMPORT_WORK,
            ),
            resultB,
        )
        assertEquals(
            listOf(
                listOf(
                    reservationA.importToken.value,
                    shootA,
                    reservationA.poseId,
                    reservationA.relativeAssetPath,
                    "REJECTED_QUARANTINED",
                    10L,
                    40L,
                    null,
                    40L,
                ),
            ),
            sqlite.rows(
                "SELECT import_token, shoot_id, pose_id, relative_asset_path, lifecycle_state, " +
                    "created_at_epoch_millis, updated_at_epoch_millis, " +
                    "asset_ready_at_epoch_millis, terminal_at_epoch_millis " +
                    "FROM reference_import_intents WHERE import_token = ?",
                reservationA.importToken.value,
            ),
        )
        assertEquals(
            listOf(
                listOf(
                    reservationA.importToken.value,
                    pathsA.relativeAssetPath,
                    pathsA.relativeTempPath,
                    pathsA.relativeQuarantinePath,
                    "QUARANTINE_DURABLE",
                    7L,
                    canonicalHash,
                    "STATE_MISMATCH",
                    1L,
                    10L,
                    30L,
                ),
            ),
            sqlite.rows(
                "SELECT import_token, relative_asset_path, relative_temp_path, " +
                    "relative_quarantine_path, stage, byte_count, sha256, last_failure_code, " +
                    "reconciliation_required, created_at_epoch_millis, updated_at_epoch_millis " +
                    "FROM reference_import_file_operations WHERE import_token = ?",
                reservationA.importToken.value,
            ),
        )
        assertEquals(
            emptyList<List<Any?>>(),
            sqlite.rows(
                "SELECT import_token FROM reference_import_intents WHERE shoot_id = ?",
                shootB,
            ),
        )
        assertEquals(
            emptyList<List<Any?>>(),
            sqlite.rows(
                "SELECT import_token FROM reference_import_file_operations WHERE import_token = ?",
                reservationB.importToken.value,
            ),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(1, sqlite.fileOperationCount())
    }

    @Test
    fun missingFileLedgerAuthorityInAnotherShootRejectsFreshReservationBeforeRows() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val shootA = "missing-ledger-shoot-a"
        val shootB = "missing-ledger-shoot-b"
        seedShoot(sqlite, shootA)
        seedShoot(sqlite, shootB)
        val repository = repository()
        val reservationA = reservation(
            shootId = shootA,
            poseId = "missing-ledger-pose-a",
            token = "missing-ledger-token-a",
        )
        val reservationB = reservation(
            shootId = shootB,
            poseId = "missing-ledger-pose-b",
            token = "missing-ledger-token-b",
        )

        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository.reserveImport(reservationA, 10L),
        )
        sqlite.execSQL(
            "UPDATE reference_import_intents SET lifecycle_state = 'REJECTED_QUARANTINED', " +
                "updated_at_epoch_millis = 40, terminal_at_epoch_millis = 40 " +
                "WHERE import_token = ?",
            arrayOf<Any>(reservationA.importToken.value),
        )
        sqlite.execSQL(
            "DELETE FROM reference_import_file_operations WHERE import_token = ?",
            arrayOf<Any>(reservationA.importToken.value),
        )

        val resultB = repository.reserveImport(reservationB, 50L)

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            resultB,
        )
        assertEquals(
            emptyList<List<Any?>>(),
            sqlite.rows(
                "SELECT import_token FROM reference_import_intents WHERE import_token = ?",
                reservationB.importToken.value,
            ),
        )
        assertEquals(
            emptyList<List<Any?>>(),
            sqlite.rows(
                "SELECT import_token FROM reference_import_file_operations WHERE import_token = ?",
                reservationB.importToken.value,
            ),
        )
        assertEquals(
            listOf(
                listOf(
                    reservationA.importToken.value,
                    shootA,
                    reservationA.poseId,
                    reservationA.relativeAssetPath,
                    "REJECTED_QUARANTINED",
                    10L,
                    40L,
                    null,
                    40L,
                ),
            ),
            sqlite.rows(
                "SELECT import_token, shoot_id, pose_id, relative_asset_path, lifecycle_state, " +
                    "created_at_epoch_millis, updated_at_epoch_millis, " +
                    "asset_ready_at_epoch_millis, terminal_at_epoch_millis " +
                    "FROM reference_import_intents WHERE import_token = ?",
                reservationA.importToken.value,
            ),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
    }

    @Test
    fun malformedSettledLedgerAuthorityInAnotherShootRejectsFreshReservationBeforeRows() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "malformed-settled-source")
        seedShoot(sqlite, "malformed-settled-target")
        val repository = repository()
        val settled = reservation(
            shootId = "malformed-settled-source",
            poseId = "malformed-settled-pose",
            token = "malformed-settled-token",
        )
        assertEquals(ReferenceImportReserveResult.Reserved, repository.reserveImport(settled, 10L))
        setFileStage(sqlite, settled.importToken.value, "QUARANTINE_DURABLE")
        assertEquals(
            ReferenceImportSettlementResult.Settled,
            repository.settleFailure(
                settled.importToken,
                ReferenceImportFailureSettlement.QUARANTINED,
                20L,
            ),
        )
        sqlite.execSQL(
            "UPDATE reference_import_file_operations SET relative_temp_path = 'wrong/private.tmp' " +
                "WHERE import_token = ?",
            arrayOf<Any>(settled.importToken.value),
        )
        val fresh = reservation(
            shootId = "malformed-settled-target",
            poseId = "malformed-settled-fresh-pose",
            token = "malformed-settled-fresh-token",
        )

        assertEquals(
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.AUTHORITY_INCONSISTENT,
            ),
            repository.checkImportAdmission(fresh.shootId),
        )
        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            repository.reserveImport(fresh, 30L),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(1, sqlite.fileOperationCount())
    }

    @Test
    fun twentyFirstReferenceReservationIsRejectedWithoutCreatingImportRows() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        (0 until 20).forEach { poseIndex ->
            seedValidatedPose(sqlite, SHOOT_ID, "existing-pose-$poseIndex", poseIndex)
        }

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.PLAYLIST_FULL,
            ),
            repository().reserveImport(
                reservation(
                    poseId = "twenty-first-pose",
                    token = "twenty-first-token",
                ),
                20L,
            ),
        )
        assertEquals(0, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
    }

    @Test
    fun preparingReservationHoldsTwentiethCapacityWhileItsExactReplayKeepsReplayClassification() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        (0 until 19).forEach { poseIndex ->
            seedValidatedPose(sqlite, SHOOT_ID, "existing-pose-$poseIndex", poseIndex)
        }
        val repository = repository()
        val twentieth = reservation(
            poseId = "twentieth-pose",
            token = "twentieth-token",
        )

        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository.reserveImport(twentieth, 20L),
        )
        assertEquals(
            ReferenceImportAdmissionCheckResult.Blocked(
                ReferenceImportAdmissionCheckBlockReason.PLAYLIST_FULL,
            ),
            repository.checkImportAdmission(SHOOT_ID),
        )
        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.PLAYLIST_FULL,
            ),
            repository.reserveImport(
                reservation(
                    poseId = "twenty-first-pose",
                    token = "twenty-first-token",
                ),
                21L,
            ),
        )
        assertEquals(
            ReferenceImportReserveResult.ExistingWorkRequiresReconciliation,
            repository.reserveImport(twentieth, 22L),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(1, sqlite.fileOperationCount())
        assertEquals(19, sqlite.poseCount(SHOOT_ID))
    }

    @Test
    fun legacyValidPoseCountsTowardCapacityAndAllowsContiguousAppend() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        seedPose(sqlite, SHOOT_ID, "legacy-valid-pose", 0)
        val repository = repository()

        prepare(repository, SHOOT_ID, "appended-pose", "appended-token", 10L)

        assertEquals(
            ReferenceImportCommitResult.Committed(1),
            repository.commitImport(
                evidence(
                    shootId = SHOOT_ID,
                    poseId = "appended-pose",
                    token = "appended-token",
                ),
                30L,
            ),
        )
        assertEquals(
            listOf(listOf(0L, "legacy-valid-pose"), listOf(1L, "appended-pose")),
            sqlite.rows(
                "SELECT pose_index, pose_id FROM shoot_poses " +
                    "WHERE shoot_id = ? ORDER BY pose_index",
                SHOOT_ID,
            ),
        )
    }

    @Test
    fun twentyLegacyValidPosesRejectReservationBeforeImportRows() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        (0 until 20).forEach { poseIndex ->
            seedPose(sqlite, SHOOT_ID, "legacy-valid-pose-$poseIndex", poseIndex)
        }

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.PLAYLIST_FULL,
            ),
            repository().reserveImport(
                reservation(poseId = "overflow-pose", token = "overflow-token"),
                20L,
            ),
        )
        assertEquals(0, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
    }

    @Test
    fun cleanedRejectionDoesNotConsumeReservationCapacity() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        (0 until 19).forEach { poseIndex ->
            seedValidatedPose(sqlite, SHOOT_ID, "existing-pose-$poseIndex", poseIndex)
        }
        val repository = repository()
        val rejected = reservation(
            poseId = "rejected-pose",
            token = "rejected-token",
        )
        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository.reserveImport(rejected, 20L),
        )
        setFileStage(sqlite, rejected.importToken.value, "CLEANED_DURABLE")
        assertEquals(
            ReferenceImportSettlementResult.Settled,
            repository.settleFailure(
                rejected.importToken,
                ReferenceImportFailureSettlement.CLEANED,
                21L,
            ),
        )

        assertEquals(
            ReferenceImportReserveResult.Reserved,
            repository.reserveImport(
                reservation(
                    poseId = "replacement-pose",
                    token = "replacement-token",
                ),
                22L,
            ),
        )
        assertEquals(2, sqlite.intentCount())
        assertEquals(2, sqlite.fileOperationCount())
        assertEquals(19, sqlite.poseCount(SHOOT_ID))
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

        assertEquals(ReferenceImportCommitResult.Committed(0), repository.commitImport(evidence, 30L))
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
                    0L,
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
            ReferenceImportReserveResult.AlreadyCommitted(0),
            repository().reserveImport(reservation, 999L),
        )
        assertEquals(
            ReferenceImportCommitResult.AlreadyCommitted(0),
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
    fun exactPreparingReservationClosesWhileTokenAndPoseIdContradictionsFailClosed() {
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
                reservation(shootId = SHOOT_ID, poseId = "pose-other", token = TOKEN),
                11L,
            ),
        )
        assertEquals(
            ReferenceImportReserveResult.Rejected(ReferenceImportReserveRejectionReason.POSE_ID_CONFLICT),
            repository.reserveImport(
                reservation(poseId = POSE_ID, token = "token-pose-id-conflict"),
                11L,
            ),
        )
        assertEquals(1, sqlite.intentCount())
        assertEquals(0, sqlite.poseCount(SHOOT_ID))
    }

    @Test
    fun unresolvedAndTerminalRejectedExactStatesRequireReconciliation() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val states = listOf("preparing", "asset-ready", "quarantined", "cleaned")
        states.forEach { suffix -> seedShoot(sqlite, "shoot-$suffix") }
        val repository = repository()
        val reservations = states.associateWith { suffix ->
            reservation("shoot-$suffix", "pose-$suffix", "token-$suffix")
        }
        reservations.values.forEach { candidate ->
            seedReservedImport(sqlite, candidate, 10L)
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

        states.forEach { suffix ->
            assertEquals(
                ReferenceImportReserveResult.ExistingWorkRequiresReconciliation,
                repository.reserveImport(requireNotNull(reservations[suffix]), 21L),
            )
        }
        assertEquals(4, sqlite.intentCount())
        assertEquals(0, states.sumOf { suffix -> sqlite.poseCount("shoot-$suffix") })
    }

    @Test
    fun committedReservationRequiresAnExactValidatedPoseRow() {
        val sqlite = openDatabase().openHelper.writableDatabase
        listOf("missing", "path", "validation").forEach { suffix ->
            seedShoot(sqlite, "shoot-$suffix")
            val token = "token-$suffix"
            prepare(repository(), "shoot-$suffix", "pose-$suffix", token, 10L)
            assertEquals(
                ReferenceImportCommitResult.Committed(0),
                repository().commitImport(
                    evidence("shoot-$suffix", "pose-$suffix", token),
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
                    reservation("shoot-$suffix", "pose-$suffix", "token-$suffix"),
                    31L,
                ),
            )
        }
    }

    @Test
    fun commitRequiresActiveNonDeletingShootNoActiveSessionAndVacantIdentity() {
        val sqlite = openDatabase().openHelper.writableDatabase
        listOf("deleting", "session", "pose-id").forEach { suffix ->
            seedShoot(sqlite, "shoot-$suffix")
        }
        val repository = repository()

        seedPreparedImport(
            repository,
            sqlite,
            "shoot-deleting",
            "pose-deleting",
            "token-deleting",
            10L,
        )
        sqlite.execSQL(
            "UPDATE shoots SET lifecycle_state = 'DELETING', deletion_generation = 1 " +
                "WHERE shoot_id = 'shoot-deleting'",
        )
        assertEquals(
            ReferenceImportCommitResult.BlockedByDeletion,
            repository.commitImport(evidence("shoot-deleting", "pose-deleting", "token-deleting"), 30L),
        )

        seedPreparedImport(
            repository,
            sqlite,
            "shoot-session",
            "pose-session",
            "token-session",
            40L,
        )
        seedSession(sqlite, "shoot-session")
        assertEquals(
            ReferenceImportCommitResult.Rejected(ReferenceImportCommitRejectionReason.ACTIVE_SESSION),
            repository.commitImport(evidence("shoot-session", "pose-session", "token-session"), 60L),
        )

        seedPreparedImport(
            repository,
            sqlite,
            "shoot-pose-id",
            "pose-owned",
            "token-pose-id",
            70L,
        )
        seedPose(sqlite, "shoot-pose-id", "pose-owned", 0)
        assertEquals(
            ReferenceImportCommitResult.Rejected(ReferenceImportCommitRejectionReason.POSE_ID_CONFLICT),
            repository.commitImport(evidence("shoot-pose-id", "pose-owned", "token-pose-id"), 90L),
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
                "token-$suffix",
            )
            seedReservedImport(sqlite, reservation, 10L)
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
        prepare(repository, SHOOT_ID, POSE_ID, TOKEN, 10L)
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
    fun reservationRejectsGapAndUnknownValidationStateBeforeCreatingImportRows() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-gap")
        seedShoot(sqlite, "shoot-invalid")
        seedValidatedPose(sqlite, "shoot-gap", "gap-existing", 1)
        seedPose(sqlite, "shoot-invalid", "invalid-existing", 0)
        sqlite.execSQL(
            "UPDATE shoot_poses SET validation_state = 'BROKEN' WHERE shoot_id = 'shoot-invalid'",
        )
        val repository = repository()

        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            repository.reserveImport(
                reservation("shoot-gap", "gap-new", "gap-token"),
                10L,
            ),
        )
        assertEquals(
            ReferenceImportReserveResult.Rejected(
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            repository.reserveImport(
                reservation("shoot-invalid", "invalid-new", "invalid-token"),
                20L,
            ),
        )
        assertEquals(1, sqlite.poseCount("shoot-gap"))
        assertEquals(1, sqlite.poseCount("shoot-invalid"))
        assertEquals(0, sqlite.intentCount())
        assertEquals(0, sqlite.fileOperationCount())
    }

    @Test
    fun concurrentReservationsForSamePoseHaveExactlyOneUniqueIdentityWinner() {
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
                    )
                },
            )
            assertEquals(1, sqlite.intentCount())
        } finally {
            executor.shutdownNow()
            second.close()
        }
    }

    @Test
    fun concurrentDistinctReservationsAtNineteenHaveOneFinalCapacityWinner() {
        val first = openDatabase()
        val sqlite = first.openHelper.writableDatabase
        seedShoot(sqlite, SHOOT_ID)
        (0 until 19).forEach { poseIndex ->
            seedValidatedPose(sqlite, SHOOT_ID, "existing-pose-$poseIndex", poseIndex)
        }
        val second = AppDatabase.create(context, databaseName)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = listOf(
                Callable {
                    RoomReferenceImportRepository(first).reserveImport(
                        reservation(poseId = "capacity-a", token = "capacity-token-a"),
                        10L,
                    )
                },
                Callable {
                    RoomReferenceImportRepository(second).reserveImport(
                        reservation(poseId = "capacity-b", token = "capacity-token-b"),
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
                        ReferenceImportReserveRejectionReason.PLAYLIST_FULL,
                    )
                },
            )
            assertEquals(1, sqlite.intentCount())
            assertEquals(1, sqlite.fileOperationCount())
            assertEquals(19, sqlite.poseCount(SHOOT_ID))
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
        token: String,
        startAt: Long,
    ) {
        val reservation = reservation(shootId, poseId, token)
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

    private fun seedPreparedImport(
        repository: RoomReferenceImportRepository,
        sqlite: SupportSQLiteDatabase,
        shootId: String,
        poseId: String,
        token: String,
        startAt: Long,
    ) {
        val reservation = reservation(shootId, poseId, token)
        seedReservedImport(sqlite, reservation, startAt)
        setFileStage(sqlite, token, "FINAL_DURABLE")
        assertEquals(
            ReferenceImportAssetReadyResult.MarkedAssetReady,
            repository.markAssetReady(
                reservation.importToken,
                reservation.relativeAssetPath,
                startAt + 1L,
            ),
        )
    }

    private fun seedReservedImport(
        sqlite: SupportSQLiteDatabase,
        reservation: ReferenceImportReservation,
        createdAtEpochMillis: Long,
    ) {
        val paths = ReferenceImportFileOperationPaths.forToken(reservation.importToken)
        sqlite.execSQL(
            "INSERT INTO reference_import_intents (import_token, shoot_id, pose_id, " +
                "relative_asset_path, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis, asset_ready_at_epoch_millis, terminal_at_epoch_millis) " +
                "VALUES (?, ?, ?, ?, 'PREPARING', ?, ?, NULL, NULL)",
            arrayOf<Any>(
                reservation.importToken.value,
                reservation.shootId,
                reservation.poseId,
                reservation.relativeAssetPath,
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
                reservation.importToken.value,
                paths.relativeAssetPath,
                paths.relativeTempPath,
                paths.relativeQuarantinePath,
                createdAtEpochMillis,
                createdAtEpochMillis,
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
        token: String = TOKEN,
    ): ReferenceImportReservation = ReferenceImportReservation(
        importToken = ReferenceImportToken(token),
        shootId = shootId,
        poseId = poseId,
        relativeAssetPath = ReferenceImportAssetPath.forToken(ReferenceImportToken(token)),
    )

    private fun evidence(
        shootId: String = SHOOT_ID,
        poseId: String = POSE_ID,
        token: String = TOKEN,
        label: String = "Reference pose",
    ): ReferenceImportEvidence = ReferenceImportEvidence(
        importToken = ReferenceImportToken(token),
        shootId = shootId,
        poseId = poseId,
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

    private fun seedValidatedPose(
        sqlite: SupportSQLiteDatabase,
        shootId: String,
        poseId: String,
        poseIndex: Int,
    ) {
        val validated = evidence(
            shootId = shootId,
            poseId = poseId,
            token = "existing-token-$poseIndex",
            label = "Existing reference",
        )
        sqlite.execSQL(
            "INSERT INTO shoot_poses (shoot_id, pose_index, pose_id, label, " +
                "reference_asset_path, mirror_allowed, validation_state, detector_metadata, " +
                "model_metadata, preprocessing_metadata, landmark_payload, coordinate_metadata) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'VALIDATED', ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                validated.shootId,
                poseIndex,
                validated.poseId,
                validated.label,
                validated.relativeAssetPath,
                1,
                validated.detectorMetadata,
                validated.modelMetadata,
                validated.preprocessingMetadata,
                validated.landmarkPayload.value,
                validated.coordinateMetadata,
            ),
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
