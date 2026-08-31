package com.tonyisup.poseguidesnap.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import java.util.UUID
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
        assertEquals(160L, snapshot.updatedAtEpochMillis)
        assertEquals(listOf("pose-valid", "pose-validated"), snapshot.validatedReferences.map { it.poseId })
        assertEquals(listOf(0, 1), snapshot.validatedReferences.map { it.poseIndex })
        assertEquals(listOf("First pose", "Second pose"), snapshot.validatedReferences.map { it.label })
        assertEquals(listOf(false, true), snapshot.validatedReferences.map { it.mirrorAllowed })
        assertEquals(
            listOf(
                ImportWorkStatus.IN_PROGRESS,
                ImportWorkStatus.NEEDS_ATTENTION,
                ImportWorkStatus.NEEDS_ATTENTION,
            ),
            snapshot.importWork.map { it.status },
        )
        assertEquals(listOf(110L, 121L, 141L), snapshot.importWork.map { it.createdAtEpochMillis })
        assertEquals(listOf(120L, 140L, 160L), snapshot.importWork.map { it.updatedAtEpochMillis })
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
            "reference-assets/private/",
        ).forEach { secret -> assertFalse(rendered.contains(secret)) }
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
        sqlite.execSQL(
            "INSERT INTO shoot_poses (shoot_id, pose_index, pose_id, label, " +
                "reference_asset_path, mirror_allowed, validation_state, detector_metadata, " +
                "model_metadata, preprocessing_metadata, landmark_payload, coordinate_metadata) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL)",
            arrayOf<Any>(
                shootId,
                poseIndex,
                poseId,
                label,
                "reference-assets/private/$poseId",
                if (mirrorAllowed) 1 else 0,
                validationState,
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
}
