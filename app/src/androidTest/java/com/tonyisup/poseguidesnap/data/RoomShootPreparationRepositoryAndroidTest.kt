package com.tonyisup.poseguidesnap.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomShootPreparationRepositoryAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "shoot_preparation_repository_android_test_${UUID.randomUUID()}.db"
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
    fun createShootPersistsTrimmedActiveShootAndExactReplayIsIdempotent() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val repository = repository()
        assertEquals("RoomShootPreparationRepository(redacted)", repository.toString())

        val created = repository.createShoot(
            shootId = "shoot-created",
            name = "  Morning set  ",
            createdAtEpochMillis = 101L,
        )

        assertTrue(created is ShootCreateResult.Created)
        val createdSummary = (created as ShootCreateResult.Created).summary
        assertSummary(
            createdSummary,
            shootId = "shoot-created",
            name = "Morning set",
            count = 0,
            lifecycle = ShootPreparationLifecycle.ACTIVE,
            updatedAtEpochMillis = 101L,
        )
        assertEquals("ShootCreateResult.Created(redacted)", created.toString())

        val replay = repository.createShoot(
            shootId = "shoot-created",
            name = "  Morning set  ",
            createdAtEpochMillis = 101L,
        )
        assertTrue(replay is ShootCreateResult.AlreadyExists)
        assertSummary(
            (replay as ShootCreateResult.AlreadyExists).summary,
            shootId = "shoot-created",
            name = "Morning set",
            count = 0,
            lifecycle = ShootPreparationLifecycle.ACTIVE,
            updatedAtEpochMillis = 101L,
        )
        assertEquals("ShootCreateResult.AlreadyExists(redacted)", replay.toString())
        val secondReplay = repository.createShoot(
            shootId = "shoot-created",
            name = "Morning set",
            createdAtEpochMillis = 101L,
        )
        assertTrue(secondReplay is ShootCreateResult.AlreadyExists)
        assertNotSame(replay, secondReplay)
        assertNotSame(
            (replay as ShootCreateResult.AlreadyExists).summary,
            (secondReplay as ShootCreateResult.AlreadyExists).summary,
        )

        assertEquals(
            ShootCreateResult.Rejected(ShootCreateRejectionReason.ID_CONFLICT),
            repository.createShoot(
                shootId = "shoot-created",
                name = "Different name",
                createdAtEpochMillis = 101L,
            ),
        )
        assertEquals(
            listOf(
                PersistedShoot(
                    shootId = "shoot-created",
                    name = "Morning set",
                    createdAtEpochMillis = 101L,
                    updatedAtEpochMillis = 101L,
                    lifecycleState = "ACTIVE",
                    deletionGeneration = 0L,
                ),
            ),
            sqlite.persistedShoots(),
        )
    }

    @Test
    fun createShootRejectsInvalidInputsBeforeDatabaseAccess() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val repository = repository()

        assertEquals(
            ShootCreateResult.Rejected(ShootCreateRejectionReason.INVALID_ID),
            repository.createShoot(
                shootId = " shoot-invalid",
                name = "Valid name",
                createdAtEpochMillis = 1L,
            ),
        )
        assertEquals(0, sqlite.persistedShoots().size)

        assertEquals(
            ShootCreateResult.Rejected(ShootCreateRejectionReason.INVALID_NAME),
            repository.createShoot(
                shootId = "shoot-invalid-name",
                name = " content://provider/private ",
                createdAtEpochMillis = 1L,
            ),
        )
        assertEquals(0, sqlite.persistedShoots().size)

        assertEquals(
            ShootCreateResult.Rejected(ShootCreateRejectionReason.INVALID_TIMESTAMP),
            repository.createShoot(
                shootId = "shoot-invalid-timestamp",
                name = "Valid name",
                createdAtEpochMillis = -1L,
            ),
        )
        assertEquals(0, sqlite.persistedShoots().size)
    }

    @Test
    fun shootSummaryObservationMovesFromEmptyToCreatedWithoutRoomTypes() = runBlocking {
        val sqlite = openDatabase().openHelper.writableDatabase
        val repository = repository()
        val summaries = repository.observeShoots()
        assertTrue(summaries.first().isEmpty())

        val created = repository.createShoot(
            shootId = "shoot-middle",
            name = "  Summary shoot  ",
            createdAtEpochMillis = 200L,
        )
        assertTrue(created is ShootCreateResult.Created)
        seedPose(sqlite, "shoot-middle", 0, "pose-valid", "VALID", "Legacy pose", false)
        seedPose(sqlite, "shoot-middle", 1, "pose-validated", "VALIDATED", "Validated pose", true)

        val singleShoot = summaries.first()
        assertEquals(1, singleShoot.size)
        val summary = singleShoot.single()
        assertSummary(
            summary,
            shootId = "shoot-middle",
            name = "Summary shoot",
            count = 2,
            lifecycle = ShootPreparationLifecycle.ACTIVE,
            updatedAtEpochMillis = 200L,
        )
        assertEquals("ShootSummary(redacted)", summary.toString())
        val secondSingleShoot = summaries.first()
        assertNotSame(singleShoot, secondSingleShoot)
        assertNotSame(summary, secondSingleShoot.single())
        assertThrows(UnsupportedOperationException::class.java) {
            (singleShoot as MutableList<ShootSummary>).add(summary)
        }

        seedShoot(sqlite, "shoot-z", "Later Z", createdAt = 300L, updatedAt = 300L)
        seedShoot(sqlite, "shoot-a", "Later A", createdAt = 300L, updatedAt = 300L)
        val ordered = summaries.first()
        assertEquals(listOf("shoot-a", "shoot-z", "shoot-middle"), ordered.map { it.shootId })
        assertEquals(listOf(300L, 300L, 200L), ordered.map { it.updatedAtEpochMillis })
    }

    @Test
    fun editorObservationCombinesOrderedReferencesAndBoundedImportWork() = runBlocking {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-editor", "Editor shoot", createdAt = 10L, updatedAt = 100L)
        seedPose(sqlite, "shoot-editor", 0, "pose-valid", "VALID", "First pose", false)
        seedPose(sqlite, "shoot-editor", 1, "pose-validated", "VALIDATED", "Second pose", true)
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-editor",
            token = "token-preparing-secret",
            poseId = "pose-preparing",
            lifecycleState = "PREPARING",
            createdAt = 110L,
            intentUpdatedAt = 110L,
            fileStage = "EXPECTING_RESERVATION",
            fileUpdatedAt = 120L,
            reconciliationRequired = false,
        )
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-editor",
            token = "token-ready-secret",
            poseId = "pose-ready",
            lifecycleState = "ASSET_READY",
            createdAt = 121L,
            intentUpdatedAt = 130L,
            fileStage = "FINAL_DURABLE",
            fileUpdatedAt = 140L,
            reconciliationRequired = true,
        )
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-editor",
            token = "token-quarantined-secret",
            poseId = "pose-quarantined",
            lifecycleState = "REJECTED_QUARANTINED",
            createdAt = 141L,
            intentUpdatedAt = 150L,
            fileStage = "QUARANTINE_DURABLE",
            fileUpdatedAt = 160L,
            reconciliationRequired = true,
            terminalAt = 150L,
        )
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-editor",
            token = "token-committed-secret",
            poseId = "pose-committed",
            lifecycleState = "COMMITTED",
            createdAt = 170L,
            intentUpdatedAt = 900L,
            fileStage = "FINAL_DURABLE",
            fileUpdatedAt = 900L,
            reconciliationRequired = false,
            terminalAt = 900L,
        )
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-editor",
            token = "token-terminal-quarantined-secret",
            poseId = "pose-terminal-quarantined",
            lifecycleState = "REJECTED_QUARANTINED",
            createdAt = 161L,
            intentUpdatedAt = 170L,
            fileStage = "QUARANTINE_DURABLE",
            fileUpdatedAt = 180L,
            reconciliationRequired = false,
            terminalAt = 170L,
        )
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-editor",
            token = "token-cleaned-secret",
            poseId = "pose-cleaned",
            lifecycleState = "REJECTED_CLEANED",
            createdAt = 180L,
            intentUpdatedAt = 999L,
            fileStage = "CLEANED_DURABLE",
            fileUpdatedAt = 999L,
            reconciliationRequired = false,
            terminalAt = 999L,
        )

        val snapshot = repository().observeShootEditor("shoot-editor").first()
        assertTrue(snapshot != null)
        snapshot ?: error("editor snapshot must exist")
        assertEquals("shoot-editor", snapshot.shootId)
        assertEquals("Editor shoot", snapshot.name)
        assertEquals(ShootPreparationLifecycle.ACTIVE, snapshot.lifecycle)
        assertEquals(180L, snapshot.updatedAtEpochMillis)
        assertEquals(listOf("pose-valid", "pose-validated"), snapshot.validatedReferences.map { it.poseId })
        assertEquals(listOf(0, 1), snapshot.validatedReferences.map { it.poseIndex })
        assertEquals(listOf("First pose", "Second pose"), snapshot.validatedReferences.map { it.label })
        assertEquals(listOf(false, true), snapshot.validatedReferences.map { it.mirrorAllowed })
        assertEquals(
            listOf(
                ImportWorkStatus.IN_PROGRESS,
                ImportWorkStatus.RECONCILIATION_REQUIRED,
                ImportWorkStatus.RECONCILIATION_REQUIRED,
                ImportWorkStatus.REJECTED_QUARANTINED,
            ),
            snapshot.importWork.map { it.status },
        )
        assertEquals(listOf(110L, 121L, 141L, 161L), snapshot.importWork.map { it.createdAtEpochMillis })
        assertEquals(listOf(120L, 140L, 160L, 180L), snapshot.importWork.map { it.updatedAtEpochMillis })
        assertEquals("ShootEditorSnapshot(redacted)", snapshot.toString())
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.validatedReferences as MutableList<ValidatedReferenceSummary>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.importWork as MutableList<ImportWorkSummary>).clear()
        }

        val publicProjectionFields = listOf(
            ShootEditorSnapshot::class.java,
            ValidatedReferenceSummary::class.java,
            ImportWorkSummary::class.java,
        ).flatMap { type -> type.declaredFields.map { field -> field.name.lowercase() } }
        assertTrue(publicProjectionFields.none { name ->
            "token" in name || "path" in name || "uri" in name
        })
        val rendered = buildList {
            add(snapshot.toString())
            addAll(snapshot.validatedReferences.map(Any::toString))
            addAll(snapshot.importWork.map(Any::toString))
        }.joinToString()
        listOf(
            "token-preparing-secret",
            "token-ready-secret",
            "token-quarantined-secret",
            "token-terminal-quarantined-secret",
            "reference-assets/private/",
        ).forEach { secret -> assertFalse(rendered.contains(secret)) }
    }

    @Test
    fun editorObservationBoundsHealthyTerminalQuarantineHistoryToNewestTwenty() = runBlocking {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-history", "History shoot", createdAt = 1L, updatedAt = 1L)
        (0..20).forEach { index ->
            val createdAt = index.toLong() + 1L
            seedImportWork(
                sqlite = sqlite,
                shootId = "shoot-history",
                token = "history-token-${index.toString().padStart(2, '0')}",
                poseId = "history-pose-$index",
                lifecycleState = "REJECTED_QUARANTINED",
                createdAt = createdAt,
                intentUpdatedAt = createdAt + 100L,
                fileStage = "QUARANTINE_DURABLE",
                fileUpdatedAt = createdAt + 200L,
                reconciliationRequired = false,
                terminalAt = createdAt + 100L,
            )
        }

        val snapshot = requireNotNull(repository().observeShootEditor("shoot-history").first())

        assertEquals(20, snapshot.importWork.size)
        assertEquals(
            (2L..21L).toList(),
            snapshot.importWork.map(ImportWorkSummary::createdAtEpochMillis),
        )
        assertTrue(snapshot.importWork.all { it.status == ImportWorkStatus.REJECTED_QUARANTINED })
        assertEquals(221L, snapshot.updatedAtEpochMillis)
    }

    @Test
    fun missingFileLedgerForRelevantIntentFailsClosed() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-missing-ledger", "Missing ledger", createdAt = 1L, updatedAt = 1L)
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-missing-ledger",
            token = "missing-ledger-token",
            poseId = "missing-ledger-pose",
            lifecycleState = "PREPARING",
            createdAt = 10L,
            intentUpdatedAt = 10L,
            fileStage = "EXPECTING_RESERVATION",
            fileUpdatedAt = 10L,
            reconciliationRequired = false,
        )
        sqlite.execSQL(
            "DELETE FROM reference_import_file_operations WHERE import_token = 'missing-ledger-token'",
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository().observeShootEditor("shoot-missing-ledger").first() }
        }
    }

    @Test
    fun editorObservationNeverMixesPoseAndImportWorkAcrossOneCommittedTransaction() = runBlocking {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-atomic-editor", "Atomic editor", createdAt = 1L, updatedAt = 1L)
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-atomic-editor",
            token = "atomic-editor-token",
            poseId = "atomic-editor-pose",
            lifecycleState = "ASSET_READY",
            createdAt = 10L,
            intentUpdatedAt = 20L,
            fileStage = "FINAL_DURABLE",
            fileUpdatedAt = 20L,
            reconciliationRequired = false,
        )
        val firstEmission = CompletableDeferred<Unit>()
        val emissionsTask = async {
            withTimeout(5_000L) {
                repository().observeShootEditor("shoot-atomic-editor")
                    .onEach { if (!firstEmission.isCompleted) firstEmission.complete(Unit) }
                    .take(2)
                    .toList()
            }
        }
        firstEmission.await()

        sqlite.beginTransaction()
        try {
            seedPose(
                sqlite,
                "shoot-atomic-editor",
                0,
                "atomic-editor-pose",
                "VALIDATED",
                "Atomic pose",
                true,
            )
            sqlite.execSQL(
                "UPDATE reference_import_intents SET lifecycle_state = 'COMMITTED', " +
                    "updated_at_epoch_millis = 30, terminal_at_epoch_millis = 30 " +
                    "WHERE import_token = 'atomic-editor-token'",
            )
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
        requireNotNull(database).invalidationTracker.refreshAsync()

        val emissions = emissionsTask.await().map(::requireNotNull)
        assertEquals(2, emissions.size)
        assertEquals(0, emissions[0].validatedReferences.size)
        assertEquals(1, emissions[0].importWork.size)
        assertEquals(1, emissions[1].validatedReferences.size)
        assertEquals(0, emissions[1].importWork.size)
        assertTrue(emissions.none { snapshot ->
            snapshot.validatedReferences.size == snapshot.importWork.size
        })
    }

    @Test
    fun deletingShootIsProjectedButCreateReplayIsRejectedAsIdentityConflict() = runBlocking {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(
            sqlite = sqlite,
            shootId = "shoot-deleting",
            name = "Deleting shoot",
            createdAt = 10L,
            updatedAt = 20L,
            lifecycleState = "DELETING",
            deletionGeneration = 1L,
        )
        val repository = repository()

        val summary = repository.observeShoots().first().single()
        assertEquals(ShootPreparationLifecycle.DELETING, summary.lifecycle)
        assertEquals(
            ShootCreateResult.Rejected(ShootCreateRejectionReason.ID_CONFLICT),
            repository.createShoot("shoot-deleting", "Deleting shoot", 10L),
        )
        val editor = repository.observeShootEditor("shoot-deleting").first()
        assertTrue(editor != null)
        assertEquals(ShootPreparationLifecycle.DELETING, editor?.lifecycle)
    }

    @Test
    fun editorObservationSurvivesCloseAndReopenWithFreshProjections() = runBlocking {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-reopen", "Reopen shoot", createdAt = 10L, updatedAt = 20L)
        seedPose(sqlite, "shoot-reopen", 0, "pose-reopen", "VALIDATED", "Reopen pose", true)
        val first = requireNotNull(repository().observeShootEditor("shoot-reopen").first())

        database?.close()
        database = null

        val second = requireNotNull(repository().observeShootEditor("shoot-reopen").first())
        assertNotSame(first, second)
        assertEquals(first.shootId, second.shootId)
        assertEquals(first.name, second.name)
        assertEquals(first.lifecycle, second.lifecycle)
        assertEquals(first.updatedAtEpochMillis, second.updatedAtEpochMillis)
        assertEquals(first.validatedReferences.map { it.poseId }, second.validatedReferences.map { it.poseId })
        assertNotSame(first.validatedReferences, second.validatedReferences)
    }

    @Test
    fun malformedPersistedPreparationAuthorityFailsClosed() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(
            sqlite,
            "shoot-unknown-lifecycle",
            "Unknown lifecycle",
            createdAt = 1L,
            updatedAt = 1L,
            lifecycleState = "UNKNOWN",
        )
        seedShoot(
            sqlite,
            "shoot-negative-generation",
            "Negative generation",
            createdAt = 2L,
            updatedAt = 2L,
            deletionGeneration = -1L,
        )
        seedShoot(sqlite, "shoot-gap", "Gap shoot", createdAt = 3L, updatedAt = 3L)
        seedPose(sqlite, "shoot-gap", 1, "pose-gap", "VALIDATED", "Gap pose", false)
        seedShoot(sqlite, "shoot-bad-work", "Bad work", createdAt = 4L, updatedAt = 4L)
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-bad-work",
            token = "bad-work-token",
            poseId = "bad-work-pose",
            lifecycleState = "PREPARING",
            createdAt = 10L,
            intentUpdatedAt = 10L,
            fileStage = "EXPECTING_RESERVATION",
            fileUpdatedAt = 9L,
            reconciliationRequired = false,
        )
        seedShoot(sqlite, "shoot-bad-preparing", "Bad preparing", createdAt = 5L, updatedAt = 5L)
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-bad-preparing",
            token = "bad-preparing-token",
            poseId = "bad-preparing-pose",
            lifecycleState = "PREPARING",
            createdAt = 20L,
            intentUpdatedAt = 21L,
            fileStage = "EXPECTING_RESERVATION",
            fileUpdatedAt = 21L,
            reconciliationRequired = false,
        )
        seedShoot(sqlite, "shoot-bad-ready", "Bad ready", createdAt = 6L, updatedAt = 6L)
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-bad-ready",
            token = "bad-ready-token",
            poseId = "bad-ready-pose",
            lifecycleState = "ASSET_READY",
            createdAt = 30L,
            intentUpdatedAt = 31L,
            fileStage = "FINAL_DURABLE",
            fileUpdatedAt = 31L,
            reconciliationRequired = false,
        )
        sqlite.execSQL(
            "UPDATE reference_import_intents SET asset_ready_at_epoch_millis = NULL " +
                "WHERE import_token = 'bad-ready-token'",
        )
        val repository = repository()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.observeShoots().first() }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.observeShootEditor("shoot-unknown-lifecycle").first() }
        }
        assertEquals(
            ShootCreateResult.Rejected(ShootCreateRejectionReason.AUTHORITY_INCONSISTENT),
            repository.createShoot("shoot-negative-generation", "Negative generation", 2L),
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.observeShootEditor("shoot-gap").first() }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.observeShootEditor("shoot-bad-work").first() }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.observeShootEditor("shoot-bad-preparing").first() }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.observeShootEditor("shoot-bad-ready").first() }
        }
    }

    @Test
    fun reorderThreeReferencesPersistsAcrossReopenAndChangesOnlyOrderAndShootTimestamp() = runBlocking {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-reorder-three", "Reorder three", createdAt = 1L, updatedAt = 10L)
        (0 until 3).forEach { index ->
            seedPose(
                sqlite,
                "shoot-reorder-three",
                index,
                "pose-$index",
                if (index == 0) "VALID" else "VALIDATED",
                "Pose $index",
                index % 2 == 0,
            )
        }
        val beforeRows = sqlite.poseAuthorityRows("shoot-reorder-three")

        assertEquals(
            ShootReorderResult.Reordered,
            repository().reorderValidatedReferences(
                "shoot-reorder-three",
                listOf("pose-2", "pose-0", "pose-1"),
                20L,
            ),
        )
        assertEquals(listOf("pose-2", "pose-0", "pose-1"), sqlite.poseOrder("shoot-reorder-three"))
        assertEquals(20L, sqlite.shootUpdatedAt("shoot-reorder-three"))
        assertEquals(beforeRows, sqlite.poseAuthorityRows("shoot-reorder-three"))

        database?.close()
        database = null
        val reopened = requireNotNull(repository().observeShootEditor("shoot-reorder-three").first())
        assertEquals(listOf("pose-2", "pose-0", "pose-1"), reopened.validatedReferences.map { it.poseId })
        assertEquals(listOf(0, 1, 2), reopened.validatedReferences.map { it.poseIndex })
        assertEquals(20L, reopened.updatedAtEpochMillis)
    }

    @Test
    fun reorderRejectsCorruptOrIncompleteValidatedPoseAuthorityWithoutWrites() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val corruptions = listOf(
            "missing-path" to "reference_asset_path = NULL",
            "provider-path" to "reference_asset_path = 'content://provider/private'",
            "absolute-path" to "reference_asset_path = '/data/user/0/private.asset'",
            "wrong-private-path" to "reference_asset_path = 'reference-assets/private/pose.asset'",
            "traversal-path" to "reference_asset_path = 'reference-assets/assets/../pose.asset'",
            "missing-detector" to "detector_metadata = NULL",
            "provider-detector" to "detector_metadata = 'content://provider/private'",
            "missing-model" to "model_metadata = NULL",
            "missing-preprocessing" to "preprocessing_metadata = NULL",
            "missing-landmarks" to "landmark_payload = NULL",
            "wrong-landmark-format" to "landmark_payload = 'NOSE,0.5,0.5,0.0,1.0,1.0'",
            "missing-coordinates" to "coordinate_metadata = NULL",
        )
        corruptions.forEach { (suffix, corruptionSql) ->
            val shootId = "shoot-authority-$suffix"
            seedShoot(sqlite, shootId, "Authority $suffix", createdAt = 1L, updatedAt = 10L)
            seedPose(sqlite, shootId, 0, "pose-$suffix-a", "VALIDATED", "A", false)
            seedPose(sqlite, shootId, 1, "pose-$suffix-b", "VALIDATED", "B", false)
            sqlite.execSQL(
                "UPDATE shoot_poses SET $corruptionSql WHERE shoot_id = ? AND pose_index = 0",
                arrayOf<Any>(shootId),
            )
        }
        val legacyMixedShootId = "shoot-authority-legacy-mixed"
        seedShoot(
            sqlite,
            legacyMixedShootId,
            "Authority legacy mixed",
            createdAt = 1L,
            updatedAt = 10L,
        )
        seedPose(sqlite, legacyMixedShootId, 0, "pose-legacy-mixed-a", "VALID", "A", false)
        seedPose(sqlite, legacyMixedShootId, 1, "pose-legacy-mixed-b", "VALID", "B", false)
        sqlite.execSQL(
            "UPDATE shoot_poses SET detector_metadata = 'mixed-evidence' " +
                "WHERE shoot_id = ? AND pose_index = 0",
            arrayOf<Any>(legacyMixedShootId),
        )
        val repository = repository()

        (corruptions.map { (suffix, _) -> suffix } + "legacy-mixed").forEach { suffix ->
            val shootId = "shoot-authority-$suffix"
            val beforeOrder = sqlite.poseIndexRows(shootId)
            assertEquals(
                ShootReorderResult.AuthorityInconsistent,
                repository.reorderValidatedReferences(
                    shootId,
                    listOf("pose-$suffix-b", "pose-$suffix-a"),
                    20L,
                ),
            )
            assertEquals(beforeOrder, sqlite.poseIndexRows(shootId))
            assertEquals(10L, sqlite.shootUpdatedAt(shootId))
        }
    }

    @Test
    fun immutableAuthorityMutationDuringPoseIndexUpdateFailsFinalVerificationAndRollsBack() {
        val sqlite = openDatabase().openHelper.writableDatabase
        val shootId = "shoot-authority-trigger"
        seedShoot(sqlite, shootId, "Authority trigger", createdAt = 1L, updatedAt = 10L)
        (0 until 3).forEach { index ->
            seedPose(
                sqlite,
                shootId,
                index,
                "pose-trigger-$index",
                "VALIDATED",
                "Pose $index",
                false,
            )
        }
        val beforeOrder = sqlite.poseIndexRows(shootId)
        val beforeAuthority = sqlite.poseAuthorityRows(shootId)
        sqlite.execSQL(
            """
            CREATE TRIGGER test_mutate_pose_authority_during_reorder
            AFTER UPDATE OF pose_index ON shoot_poses
            FOR EACH ROW
            WHEN NEW.shoot_id = 'shoot-authority-trigger' AND NEW.pose_id = 'pose-trigger-0'
            BEGIN
                UPDATE shoot_poses
                SET detector_metadata = 'trigger-mutated-detector'
                WHERE shoot_id = NEW.shoot_id AND pose_id = NEW.pose_id;
            END
            """.trimIndent(),
        )

        assertEquals(
            ShootReorderResult.AuthorityInconsistent,
            repository().reorderValidatedReferences(
                shootId,
                listOf("pose-trigger-2", "pose-trigger-0", "pose-trigger-1"),
                20L,
            ),
        )
        assertEquals(beforeOrder, sqlite.poseIndexRows(shootId))
        assertEquals(beforeAuthority, sqlite.poseAuthorityRows(shootId))
        assertEquals(10L, sqlite.shootUpdatedAt(shootId))
    }

    @Test
    fun reorderTwentyReferencesAndExactReplayDoesNotWrite() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-reorder-twenty", "Reorder twenty", createdAt = 1L, updatedAt = 10L)
        val original = (0 until 20).map { index -> "pose-$index" }
        original.forEachIndexed { index, poseId ->
            seedPose(sqlite, "shoot-reorder-twenty", index, poseId, "VALIDATED", "Pose $index", false)
        }
        val requested = original.reversed()
        val repository = repository()

        assertEquals(
            ShootReorderResult.Reordered,
            repository.reorderValidatedReferences("shoot-reorder-twenty", requested, 20L),
        )
        val afterReorder = sqlite.poseIndexRows("shoot-reorder-twenty")
        assertEquals(requested, sqlite.poseOrder("shoot-reorder-twenty"))
        assertEquals(
            ShootReorderResult.AlreadyOrdered,
            repository.reorderValidatedReferences("shoot-reorder-twenty", requested, 20L),
        )
        assertEquals(afterReorder, sqlite.poseIndexRows("shoot-reorder-twenty"))
        assertEquals(20L, sqlite.shootUpdatedAt("shoot-reorder-twenty"))
    }

    @Test
    fun reorderRejectsDuplicateMissingForeignPartialAndSupersetOrdersWithoutWrites() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-invalid-order", "Invalid order", createdAt = 1L, updatedAt = 10L)
        (0 until 3).forEach { index ->
            seedPose(sqlite, "shoot-invalid-order", index, "pose-$index", "VALIDATED", "Pose $index", false)
        }
        val before = sqlite.poseIndexRows("shoot-invalid-order")
        val repository = repository()
        listOf(
            listOf("pose-0", "pose-0", "pose-2") to ShootReorderInvalidReason.DUPLICATE_POSE_ID,
            listOf("pose-0", "pose-1") to ShootReorderInvalidReason.ORDER_MISMATCH,
            listOf("pose-0", "pose-1", "pose-foreign") to ShootReorderInvalidReason.ORDER_MISMATCH,
            listOf("pose-0", "pose-1", "pose-2", "pose-foreign") to ShootReorderInvalidReason.ORDER_MISMATCH,
        ).forEach { (order, reason) ->
            assertEquals(
                ShootReorderResult.InvalidRequest(reason),
                repository.reorderValidatedReferences("shoot-invalid-order", order, 20L),
            )
            assertEquals(before, sqlite.poseIndexRows("shoot-invalid-order"))
            assertEquals(10L, sqlite.shootUpdatedAt("shoot-invalid-order"))
        }
    }

    @Test
    fun reorderDistinguishesUnknownDeletingActiveSessionAndGlobalUnresolvedWork() {
        val sqlite = openDatabase().openHelper.writableDatabase
        listOf("deleting", "session", "unresolved-source", "unresolved-target").forEach { suffix ->
            seedShoot(sqlite, "shoot-$suffix", "Shoot $suffix", createdAt = 1L, updatedAt = 10L)
        }
        listOf("deleting", "session", "unresolved-target").forEach { suffix ->
            seedPose(sqlite, "shoot-$suffix", 0, "pose-$suffix-a", "VALIDATED", "A", false)
            seedPose(sqlite, "shoot-$suffix", 1, "pose-$suffix-b", "VALIDATED", "B", false)
        }
        sqlite.execSQL(
            "UPDATE shoots SET lifecycle_state = 'DELETING', deletion_generation = 1 " +
                "WHERE shoot_id = 'shoot-deleting'",
        )
        seedActiveSession(sqlite, "shoot-session")
        seedImportWork(
            sqlite = sqlite,
            shootId = "shoot-unresolved-source",
            token = "global-unresolved-token",
            poseId = "global-unresolved-pose",
            lifecycleState = "PREPARING",
            createdAt = 10L,
            intentUpdatedAt = 10L,
            fileStage = "EXPECTING_RESERVATION",
            fileUpdatedAt = 10L,
            reconciliationRequired = false,
        )
        val repository = repository()

        assertEquals(
            ShootReorderResult.UnknownShoot,
            repository.reorderValidatedReferences("shoot-unknown", listOf("pose-a", "pose-b"), 20L),
        )
        assertEquals(
            ShootReorderResult.ShootDeleting,
            repository.reorderValidatedReferences(
                "shoot-deleting",
                listOf("pose-deleting-b", "pose-deleting-a"),
                20L,
            ),
        )
        assertEquals(
            ShootReorderResult.ActiveSession,
            repository.reorderValidatedReferences(
                "shoot-session",
                listOf("pose-session-b", "pose-session-a"),
                20L,
            ),
        )
        assertEquals(
            ShootReorderResult.UnresolvedImportWork,
            repository.reorderValidatedReferences(
                "shoot-unresolved-target",
                listOf("pose-unresolved-target-b", "pose-unresolved-target-a"),
                20L,
            ),
        )
    }

    @Test
    fun reorderFailsClosedForMissingMismatchedLedgerAndCorruptPersistedPlaylist() {
        val sqlite = openDatabase().openHelper.writableDatabase
        listOf("missing-ledger", "mismatched-ledger", "gap", "negative", "validation").forEach { suffix ->
            seedShoot(sqlite, "shoot-$suffix", "Shoot $suffix", createdAt = 1L, updatedAt = 10L)
        }
        listOf("missing-ledger", "mismatched-ledger").forEach { suffix ->
            seedPose(sqlite, "shoot-$suffix", 0, "pose-$suffix-a", "VALIDATED", "A", false)
            seedPose(sqlite, "shoot-$suffix", 1, "pose-$suffix-b", "VALIDATED", "B", false)
        }
        seedImportWork(
            sqlite,
            "shoot-missing-ledger",
            "missing-global-ledger-token",
            "missing-global-ledger-pose",
            "REJECTED_QUARANTINED",
            1L,
            2L,
            "QUARANTINE_DURABLE",
            2L,
            false,
            terminalAt = 2L,
        )
        sqlite.execSQL(
            "DELETE FROM reference_import_file_operations WHERE import_token = 'missing-global-ledger-token'",
        )
        seedImportWork(
            sqlite,
            "shoot-mismatched-ledger",
            "mismatched-global-ledger-token",
            "mismatched-global-ledger-pose",
            "REJECTED_CLEANED",
            3L,
            4L,
            "CLEANED_DURABLE",
            4L,
            false,
            terminalAt = 4L,
        )
        sqlite.execSQL(
            "UPDATE reference_import_file_operations SET relative_temp_path = 'wrong/private.tmp' " +
                "WHERE import_token = 'mismatched-global-ledger-token'",
        )
        seedPose(sqlite, "shoot-gap", 0, "pose-gap-a", "VALIDATED", "A", false)
        seedPose(sqlite, "shoot-gap", 2, "pose-gap-b", "VALIDATED", "B", false)
        seedPose(sqlite, "shoot-negative", -1, "pose-negative-a", "VALIDATED", "A", false)
        seedPose(sqlite, "shoot-negative", 0, "pose-negative-b", "VALIDATED", "B", false)
        seedPose(sqlite, "shoot-validation", 0, "pose-validation-a", "VALIDATED", "A", false)
        seedPose(sqlite, "shoot-validation", 1, "pose-validation-b", "CORRUPT", "B", false)
        val repository = repository()

        listOf("missing-ledger", "mismatched-ledger").forEach { suffix ->
            assertEquals(
                ShootReorderResult.AuthorityInconsistent,
                repository.reorderValidatedReferences(
                    "shoot-$suffix",
                    listOf("pose-$suffix-b", "pose-$suffix-a"),
                    20L,
                ),
            )
        }
        listOf("gap", "negative", "validation").forEach { suffix ->
            assertEquals(
                ShootReorderResult.AuthorityInconsistent,
                repository.reorderValidatedReferences(
                    "shoot-$suffix",
                    listOf("pose-$suffix-b", "pose-$suffix-a"),
                    20L,
                ),
            )
            assertEquals(10L, sqlite.shootUpdatedAt("shoot-$suffix"))
        }
    }

    @Test
    fun latePoseConstraintAndShootTimestampCasFailureRollBackAllReorderWrites() {
        val sqlite = openDatabase().openHelper.writableDatabase
        listOf("constraint", "cas").forEach { suffix ->
            seedShoot(sqlite, "shoot-$suffix", "Shoot $suffix", createdAt = 1L, updatedAt = 10L)
            (0 until 3).forEach { index ->
                seedPose(sqlite, "shoot-$suffix", index, "pose-$suffix-$index", "VALIDATED", "Pose", false)
            }
        }
        sqlite.execSQL(
            """
            CREATE TRIGGER test_fail_final_reorder_index
            BEFORE UPDATE OF pose_index ON shoot_poses
            FOR EACH ROW
            WHEN NEW.shoot_id = 'shoot-constraint' AND NEW.pose_index = 1
            BEGIN
                SELECT RAISE(ABORT, 'test reorder index failure');
            END
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            CREATE TRIGGER test_force_reorder_timestamp_cas_failure
            AFTER UPDATE OF pose_index ON shoot_poses
            FOR EACH ROW
            WHEN NEW.shoot_id = 'shoot-cas' AND NEW.pose_index < 0
            BEGIN
                UPDATE shoots SET updated_at_epoch_millis = 99 WHERE shoot_id = NEW.shoot_id;
            END
            """.trimIndent(),
        )
        val repository = repository()

        listOf("constraint", "cas").forEach { suffix ->
            val before = sqlite.poseIndexRows("shoot-$suffix")
            assertEquals(
                ShootReorderResult.AuthorityInconsistent,
                repository.reorderValidatedReferences(
                    "shoot-$suffix",
                    listOf("pose-$suffix-2", "pose-$suffix-0", "pose-$suffix-1"),
                    20L,
                ),
            )
            assertEquals(before, sqlite.poseIndexRows("shoot-$suffix"))
            assertEquals(10L, sqlite.shootUpdatedAt("shoot-$suffix"))
            assertTrue(sqlite.poseIndexRows("shoot-$suffix").all { row -> row.first >= 0L })
        }
    }

    @Test
    fun reorderLeavesImmutableImportIntentAndFileHistoryUnchanged() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-import-history", "Import history", createdAt = 1L, updatedAt = 10L)
        seedPose(sqlite, "shoot-import-history", 0, "pose-history-a", "VALIDATED", "A", false)
        seedPose(sqlite, "shoot-import-history", 1, "pose-history-b", "VALIDATED", "B", false)
        seedImportWork(
            sqlite,
            "shoot-import-history",
            "history-token",
            "pose-history-a",
            "COMMITTED",
            1L,
            5L,
            "FINAL_DURABLE",
            5L,
            false,
            terminalAt = 5L,
        )
        val beforeHistory = sqlite.importHistoryRows()

        assertEquals(
            ShootReorderResult.Reordered,
            repository().reorderValidatedReferences(
                "shoot-import-history",
                listOf("pose-history-b", "pose-history-a"),
                20L,
            ),
        )
        assertEquals(beforeHistory, sqlite.importHistoryRows())
    }

    @Test
    fun concurrentSameTimestampReordersHaveOneWinnerAndOneStaleLoser() {
        val firstDatabase = openDatabase()
        val sqlite = firstDatabase.openHelper.writableDatabase
        seedShoot(sqlite, "shoot-race", "Race", createdAt = 1L, updatedAt = 10L)
        (0 until 3).forEach { index ->
            seedPose(sqlite, "shoot-race", index, "pose-race-$index", "VALIDATED", "Pose", false)
        }
        val secondDatabase = AppDatabase.create(context, databaseName)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val firstOrder = listOf("pose-race-2", "pose-race-0", "pose-race-1")
        val secondOrder = listOf("pose-race-1", "pose-race-2", "pose-race-0")
        try {
            val requests = listOf(
                RoomShootPreparationRepository(firstDatabase) to firstOrder,
                RoomShootPreparationRepository(secondDatabase) to secondOrder,
            )
            val futures = requests.map { (repository, order) ->
                executor.submit<Pair<List<String>, ShootReorderResult>> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    order to repository.reorderValidatedReferences("shoot-race", order, 20L)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val outcomes = futures.map { future -> future.get(10, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.count { (_, result) -> result == ShootReorderResult.Reordered })
            assertEquals(1, outcomes.count { (_, result) -> result == ShootReorderResult.StaleTimestamp })
            val winningOrder = outcomes.single { (_, result) ->
                result == ShootReorderResult.Reordered
            }.first
            assertEquals(winningOrder, sqlite.poseOrder("shoot-race"))
            assertEquals((0L..2L).toList(), sqlite.poseIndexRows("shoot-race").map { row -> row.first })
            assertTrue(sqlite.poseIndexRows("shoot-race").all { row -> row.first >= 0L })
            assertEquals(20L, sqlite.shootUpdatedAt("shoot-race"))
        } finally {
            executor.shutdownNow()
            secondDatabase.close()
        }
    }

    private fun openDatabase(): AppDatabase =
        AppDatabase.create(context, databaseName).also { database = it }

    private fun repository(): RoomShootPreparationRepository =
        RoomShootPreparationRepository(database ?: openDatabase())

    private fun seedShoot(
        sqlite: SupportSQLiteDatabase,
        shootId: String,
        name: String,
        createdAt: Long,
        updatedAt: Long,
        lifecycleState: String = "ACTIVE",
        deletionGeneration: Long = 0L,
    ) {
        sqlite.execSQL(
            "INSERT INTO shoots (shoot_id, name, created_at_epoch_millis, " +
                "updated_at_epoch_millis, lifecycle_state, deletion_generation) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                shootId,
                name,
                createdAt,
                updatedAt,
                lifecycleState,
                deletionGeneration,
            ),
        )
    }

    private fun seedPose(
        sqlite: SupportSQLiteDatabase,
        shootId: String,
        poseIndex: Int,
        poseId: String,
        validationState: String,
        label: String,
        mirrorAllowed: Boolean,
    ) {
        val validatedAuthority = if (validationState == "VALIDATED") {
            arrayOf<Any?>(
                "reference-assets/assets/${"ab".repeat(32)}.asset",
                "detector=evidence",
                "model=evidence",
                "preprocessing=evidence",
                "v1|NOSE,0.5,0.5,0.0,1.0,1.0",
                "space=normalized-upright-source",
            )
        } else {
            arrayOfNulls(6)
        }
        sqlite.execSQL(
            "INSERT INTO shoot_poses (shoot_id, pose_index, pose_id, label, " +
                "reference_asset_path, mirror_allowed, validation_state, detector_metadata, " +
                "model_metadata, preprocessing_metadata, landmark_payload, coordinate_metadata) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                shootId,
                poseIndex,
                poseId,
                label,
                validatedAuthority[0],
                if (mirrorAllowed) 1 else 0,
                validationState,
                validatedAuthority[1],
                validatedAuthority[2],
                validatedAuthority[3],
                validatedAuthority[4],
                validatedAuthority[5],
            ),
        )
    }

    private fun seedImportWork(
        sqlite: SupportSQLiteDatabase,
        shootId: String,
        token: String,
        poseId: String,
        lifecycleState: String,
        createdAt: Long,
        intentUpdatedAt: Long,
        fileStage: String,
        fileUpdatedAt: Long,
        reconciliationRequired: Boolean,
        terminalAt: Long? = null,
    ) {
        val operationPaths = ReferenceImportFileOperationPaths.forToken(ReferenceImportToken(token))
        sqlite.execSQL(
            "INSERT INTO reference_import_intents (import_token, shoot_id, pose_id, " +
                "relative_asset_path, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis, asset_ready_at_epoch_millis, terminal_at_epoch_millis) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                token,
                shootId,
                poseId,
                operationPaths.relativeAssetPath,
                lifecycleState,
                createdAt,
                intentUpdatedAt,
                if (lifecycleState == "ASSET_READY" || lifecycleState == "COMMITTED") {
                    intentUpdatedAt
                } else {
                    null
                },
                terminalAt,
            ),
        )
        val keepsEvidence = fileStage !in setOf(
            "EXPECTING_RESERVATION",
            "WRITING_TEMP",
            "CLEANED_DURABLE",
        )
        sqlite.execSQL(
            "INSERT INTO reference_import_file_operations (import_token, relative_asset_path, " +
                "relative_temp_path, relative_quarantine_path, stage, byte_count, sha256, " +
                "last_failure_code, reconciliation_required, created_at_epoch_millis, " +
                "updated_at_epoch_millis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                token,
                operationPaths.relativeAssetPath,
                operationPaths.relativeTempPath,
                operationPaths.relativeQuarantinePath,
                fileStage,
                if (keepsEvidence) 7L else null,
                if (keepsEvidence) "ab".repeat(32) else null,
                if (reconciliationRequired) "STATE_MISMATCH" else null,
                if (reconciliationRequired) 1 else 0,
                createdAt,
                fileUpdatedAt,
            ),
        )
    }

    private fun seedActiveSession(sqlite: SupportSQLiteDatabase, shootId: String) {
        sqlite.execSQL(
            "INSERT INTO shoot_sessions (session_id, shoot_id, current_pose_index, " +
                "next_attempt_number, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis) VALUES (?, ?, 0, 0, 'ACTIVE', 1, 1)",
            arrayOf<Any>("session-$shootId", shootId),
        )
    }

    private fun SupportSQLiteDatabase.poseOrder(shootId: String): List<String> =
        query(
            "SELECT pose_id FROM shoot_poses WHERE shoot_id = ? ORDER BY pose_index",
            arrayOf(shootId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun SupportSQLiteDatabase.poseIndexRows(shootId: String): List<Pair<Long, String>> =
        query(
            "SELECT pose_index, pose_id FROM shoot_poses WHERE shoot_id = ? ORDER BY pose_index",
            arrayOf(shootId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1))
            }
        }

    private fun SupportSQLiteDatabase.poseAuthorityRows(shootId: String): List<PoseAuthorityRow> =
        query(
            "SELECT pose_id, label, reference_asset_path, mirror_allowed, validation_state, " +
                "detector_metadata, model_metadata, preprocessing_metadata, landmark_payload, " +
                "coordinate_metadata FROM shoot_poses WHERE shoot_id = ? ORDER BY pose_id",
            arrayOf(shootId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PoseAuthorityRow(
                            poseId = cursor.getString(0),
                            values = (1 until cursor.columnCount).map { column ->
                                if (cursor.isNull(column)) null else cursor.getString(column)
                            },
                        ),
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.shootUpdatedAt(shootId: String): Long =
        query(
            "SELECT updated_at_epoch_millis FROM shoots WHERE shoot_id = ?",
            arrayOf(shootId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.importHistoryRows(): List<List<String?>> =
        query(
            "SELECT intent.import_token, intent.shoot_id, intent.pose_id, intent.relative_asset_path, " +
                "intent.lifecycle_state, intent.created_at_epoch_millis, intent.updated_at_epoch_millis, " +
                "intent.asset_ready_at_epoch_millis, intent.terminal_at_epoch_millis, " +
                "file.relative_asset_path, file.relative_temp_path, file.relative_quarantine_path, " +
                "file.stage, file.byte_count, file.sha256, file.last_failure_code, " +
                "file.reconciliation_required, file.created_at_epoch_millis, file.updated_at_epoch_millis " +
                "FROM reference_import_intents AS intent " +
                "INNER JOIN reference_import_file_operations AS file " +
                "ON file.import_token = intent.import_token ORDER BY intent.import_token",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { column ->
                        if (cursor.isNull(column)) null else cursor.getString(column)
                    })
                }
            }
        }

    private fun SupportSQLiteDatabase.persistedShoots(): List<PersistedShoot> =
        query(
            "SELECT shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis, " +
                "lifecycle_state, deletion_generation FROM shoots ORDER BY shoot_id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PersistedShoot(
                            shootId = cursor.getString(0),
                            name = cursor.getString(1),
                            createdAtEpochMillis = cursor.getLong(2),
                            updatedAtEpochMillis = cursor.getLong(3),
                            lifecycleState = cursor.getString(4),
                            deletionGeneration = cursor.getLong(5),
                        ),
                    )
                }
            }
        }

    private fun assertSummary(
        actual: ShootSummary,
        shootId: String,
        name: String,
        count: Int,
        lifecycle: ShootPreparationLifecycle,
        updatedAtEpochMillis: Long,
    ) {
        assertEquals(shootId, actual.shootId)
        assertEquals(name, actual.name)
        assertEquals(count, actual.validatedReferenceCount)
        assertEquals(lifecycle, actual.lifecycle)
        assertEquals(updatedAtEpochMillis, actual.updatedAtEpochMillis)
    }

    private data class PersistedShoot(
        val shootId: String,
        val name: String,
        val createdAtEpochMillis: Long,
        val updatedAtEpochMillis: Long,
        val lifecycleState: String,
        val deletionGeneration: Long,
    )

    private data class PoseAuthorityRow(
        val poseId: String,
        val values: List<String?>,
    )
}
