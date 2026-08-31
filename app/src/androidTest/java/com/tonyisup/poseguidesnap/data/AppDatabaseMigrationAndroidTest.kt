package com.tonyisup.poseguidesnap.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.db.AuthorityOrdinalTriggers
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationAndroidTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private lateinit var context: Context
    private lateinit var databaseName: String
    private var appDatabase: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "app_database_migration_android_test_${UUID.randomUUID()}.db"
        context.deleteRoomTestDatabase(databaseName)
        assertNoTestDatabaseResidue()
    }

    @After
    fun tearDown() {
        appDatabase?.close()
        appDatabase = null
        context.deleteRoomTestDatabase(databaseName)
        assertNoTestDatabaseResidue()
    }

    @Test
    fun migrationFromV1ThroughV2ToV3PreservesLegacyCaptureAuthorityAndInstallsExactV3Contract() {
        migrationHelper.createDatabase(databaseName, 1).use { v1 ->
            seedLegacyShootAndPose(v1)
            seedCaptureAuthority(v1)
            AuthorityOrdinalTriggers.install(v1)
            assertExactOrdinalTriggers(v1)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
        ).use { migrated ->
            assertLegacyPoseSurvived(migrated)
            assertCaptureAuthoritySurvived(migrated)
            assertV3ReferenceImportIntentContract(migrated)
            assertReferenceImportFileOperationContract(migrated)
            assertCanonicalImportTablesOnly(migrated)
            assertForeignKeyCheckEmpty(migrated)
            assertExactOrdinalTriggers(migrated)
        }

        appDatabase = AppDatabase.create(context, databaseName)
        val reopened = checkNotNull(appDatabase).openHelper.writableDatabase
        assertV3ReferenceImportIntentContract(reopened)
        assertForeignKeyCheckEmpty(reopened)
        assertExactOrdinalTriggers(reopened)
    }

    @Test
    fun migrationFromV2ToV3PreservesEveryRemainingIntentFileAndCaptureValue() {
        migrationHelper.createDatabase(databaseName, 2).use { v2 ->
            seedV2MigrationFixture(v2)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            AppDatabase.MIGRATION_2_3,
        ).use { migrated ->
            assertMigratedIntentValues(migrated)
            assertMigratedFileOperationValues(migrated)
            assertCaptureAuthoritySurvived(migrated)
            assertV3ReferenceImportIntentContract(migrated)
            assertReferenceImportFileOperationContract(migrated)
            assertCanonicalImportTablesOnly(migrated)
            assertForeignKeyCheckEmpty(migrated)
        }
    }

    private fun seedLegacyShootAndPose(v1: SupportSQLiteDatabase) {
        v1.execSQL(
            """
            INSERT INTO `shoots` (
                `shoot_id`,
                `name`,
                `created_at_epoch_millis`,
                `updated_at_epoch_millis`,
                `lifecycle_state`,
                `deletion_generation`
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                SHOOT_ID,
                TEST_SHOOT_NAME,
                CREATED_AT_EPOCH_MILLIS,
                UPDATED_AT_EPOCH_MILLIS,
                SHOOT_LIFECYCLE_STATE,
                DELETION_GENERATION,
            ),
        )
        v1.execSQL(
            """
            INSERT INTO `shoot_poses` (
                `shoot_id`,
                `pose_index`,
                `pose_id`,
                `label`,
                `reference_asset_path`,
                `mirror_allowed`,
                `validation_state`,
                `detector_metadata`,
                `model_metadata`,
                `preprocessing_metadata`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                SHOOT_ID,
                POSE_INDEX,
                POSE_ID,
                TEST_POSE_LABEL,
                null,
                MIRROR_ALLOWED,
                VALIDATION_STATE,
                TEST_DETECTOR_METADATA,
                TEST_MODEL_METADATA,
                TEST_PREPROCESSING_METADATA,
            ),
        )
    }

    private fun seedV2MigrationFixture(v2: SupportSQLiteDatabase) {
        seedLegacyShootAndPose(v2)
        seedCaptureAuthority(v2)
        IMPORT_FIXTURES.forEach { fixture ->
            v2.execSQL(
                """
                INSERT INTO `reference_import_intents` (
                    `import_token`,
                    `shoot_id`,
                    `pose_id`,
                    `pose_index`,
                    `relative_asset_path`,
                    `lifecycle_state`,
                    `created_at_epoch_millis`,
                    `updated_at_epoch_millis`,
                    `asset_ready_at_epoch_millis`,
                    `terminal_at_epoch_millis`
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    fixture.importToken,
                    SHOOT_ID,
                    fixture.poseId,
                    fixture.poseIndex,
                    fixture.relativeAssetPath,
                    fixture.lifecycleState,
                    fixture.createdAtEpochMillis,
                    fixture.updatedAtEpochMillis,
                    fixture.assetReadyAtEpochMillis,
                    fixture.terminalAtEpochMillis,
                ),
            )
        }
        FILE_OPERATION_FIXTURES.forEach { fixture ->
            v2.execSQL(
                """
                INSERT INTO `reference_import_file_operations` (
                    `import_token`,
                    `relative_asset_path`,
                    `relative_temp_path`,
                    `relative_quarantine_path`,
                    `stage`,
                    `byte_count`,
                    `sha256`,
                    `last_failure_code`,
                    `reconciliation_required`,
                    `created_at_epoch_millis`,
                    `updated_at_epoch_millis`
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    fixture.importToken,
                    fixture.relativeAssetPath,
                    fixture.relativeTempPath,
                    fixture.relativeQuarantinePath,
                    fixture.stage,
                    fixture.byteCount,
                    fixture.sha256,
                    fixture.lastFailureCode,
                    fixture.reconciliationRequired,
                    fixture.createdAtEpochMillis,
                    fixture.updatedAtEpochMillis,
                ),
            )
        }
    }


    private fun seedCaptureAuthority(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO `shoot_sessions` (
                `session_id`, `shoot_id`, `current_pose_index`, `next_attempt_number`,
                `lifecycle_state`, `created_at_epoch_millis`, `updated_at_epoch_millis`
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                CAPTURE_SESSION_ID,
                SHOOT_ID,
                POSE_INDEX,
                CAPTURE_NEXT_ATTEMPT_NUMBER,
                CAPTURE_SESSION_LIFECYCLE,
                CAPTURE_CREATED_AT,
                CAPTURE_UPDATED_AT,
            ),
        )
        db.execSQL(
            """
            INSERT INTO `capture_attempts` (
                `command_token`, `session_id`, `pose_id`, `pose_index`, `attempt_number`,
                `trigger_type`, `lifecycle_state`, `reconciliation_required`,
                `captured_deletion_generation`, `created_at_epoch_millis`,
                `updated_at_epoch_millis`, `confirmed_at_epoch_millis`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                CAPTURE_COMMAND_TOKEN,
                CAPTURE_SESSION_ID,
                POSE_ID,
                POSE_INDEX,
                CAPTURE_ATTEMPT_NUMBER,
                CAPTURE_TRIGGER_TYPE,
                CAPTURE_ATTEMPT_LIFECYCLE,
                CAPTURE_RECONCILIATION_REQUIRED,
                DELETION_GENERATION,
                CAPTURE_CREATED_AT,
                CAPTURE_UPDATED_AT,
                CAPTURE_CONFIRMED_AT,
            ),
        )
        repeat(3) { ordinal ->
            db.execSQL(
                """
                INSERT INTO `private_capture_outputs` (
                    `command_token`, `burst_ordinal`, `relative_path`, `byte_count`,
                    `durability_state`, `captured_at_epoch_millis`, `integrity_metadata`
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    CAPTURE_COMMAND_TOKEN,
                    ordinal,
                    "$CAPTURE_RELATIVE_PATH_PREFIX$ordinal.jpg",
                    CAPTURE_BYTE_COUNT + ordinal,
                    CAPTURE_DURABILITY_STATE,
                    CAPTURE_CAPTURED_AT + ordinal,
                    "$CAPTURE_INTEGRITY_METADATA_PREFIX$ordinal",
                ),
            )
        }
        db.execSQL(
            """
            INSERT INTO `capture_confirmation_receipts` (
                `command_token`, `from_pose_index`, `to_pose_index`,
                `applied_deletion_generation`, `applied_at_epoch_millis`
            ) VALUES (?, ?, NULL, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                CAPTURE_COMMAND_TOKEN,
                POSE_INDEX,
                DELETION_GENERATION,
                CAPTURE_CONFIRMED_AT,
            ),
        )
        db.execSQL(
            """
            INSERT INTO `capture_export_outboxes` (
                `command_token`, `lifecycle_state`, `created_at_epoch_millis`,
                `updated_at_epoch_millis`, `retry_metadata`
            ) VALUES (?, ?, ?, ?, NULL)
            """.trimIndent(),
            arrayOf<Any?>(
                CAPTURE_COMMAND_TOKEN,
                CAPTURE_OUTBOX_LIFECYCLE,
                CAPTURE_CONFIRMED_AT,
                CAPTURE_CONFIRMED_AT,
            ),
        )
        repeat(3) { ordinal ->
            db.execSQL(
                """
                INSERT INTO `capture_export_outputs` (
                    `command_token`, `burst_ordinal`, `target_collection_uri`, `target_volume`,
                    `intended_display_name`, `intended_relative_path`, `intended_mime_type`,
                    `lifecycle_state`, `claim_token`, `media_uri_string`, `ambiguity_state`,
                    `deletion_generation`, `created_at_epoch_millis`, `updated_at_epoch_millis`
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    CAPTURE_COMMAND_TOKEN,
                    ordinal,
                    CAPTURE_TARGET_COLLECTION_URI,
                    CAPTURE_TARGET_VOLUME,
                    "migration-$ordinal.jpg",
                    CAPTURE_INTENDED_RELATIVE_PATH,
                    CAPTURE_MIME_TYPE,
                    CAPTURE_EXPORT_LIFECYCLE,
                    CAPTURE_AMBIGUITY_STATE,
                    DELETION_GENERATION,
                    CAPTURE_CONFIRMED_AT,
                    CAPTURE_CONFIRMED_AT,
                ),
            )
        }
    }

    private fun assertLegacyPoseSurvived(migrated: SupportSQLiteDatabase) {
        migrated.query(
            """
            SELECT
                `shoot_id`,
                `pose_index`,
                `pose_id`,
                `label`,
                `reference_asset_path`,
                `mirror_allowed`,
                `validation_state`,
                `detector_metadata`,
                `model_metadata`,
                `preprocessing_metadata`,
                `landmark_payload`,
                `coordinate_metadata`
            FROM `shoot_poses`
            WHERE `shoot_id` = ? AND `pose_index` = ?
            """.trimIndent(),
            arrayOf<Any?>(SHOOT_ID, POSE_INDEX),
        ).use { cursor ->
            assertTrue("the legacy V1 pose row must survive migration", cursor.moveToFirst())
            assertEquals(SHOOT_ID, cursor.getString(cursor.getColumnIndexOrThrow("shoot_id")))
            assertEquals(POSE_INDEX, cursor.getInt(cursor.getColumnIndexOrThrow("pose_index")))
            assertEquals(POSE_ID, cursor.getString(cursor.getColumnIndexOrThrow("pose_id")))
            assertEquals(TEST_POSE_LABEL, cursor.getString(cursor.getColumnIndexOrThrow("label")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("reference_asset_path")))
            assertEquals(MIRROR_ALLOWED, cursor.getInt(cursor.getColumnIndexOrThrow("mirror_allowed")))
            assertEquals(VALIDATION_STATE, cursor.getString(cursor.getColumnIndexOrThrow("validation_state")))
            assertEquals(
                TEST_DETECTOR_METADATA,
                cursor.getString(cursor.getColumnIndexOrThrow("detector_metadata")),
            )
            assertEquals(
                TEST_MODEL_METADATA,
                cursor.getString(cursor.getColumnIndexOrThrow("model_metadata")),
            )
            assertEquals(
                TEST_PREPROCESSING_METADATA,
                cursor.getString(cursor.getColumnIndexOrThrow("preprocessing_metadata")),
            )
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("landmark_payload")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("coordinate_metadata")))
            assertFalse("only one pose row was seeded", cursor.moveToNext())
        }
    }

    private fun assertV3ReferenceImportIntentContract(migrated: SupportSQLiteDatabase) {
        assertEquals(EXPECTED_V3_REFERENCE_IMPORT_COLUMNS, migrated.referenceImportColumns())
        assertEquals(EXPECTED_REFERENCE_IMPORT_FOREIGN_KEYS, migrated.referenceImportForeignKeys())
        assertEquals(EXPECTED_V3_REFERENCE_IMPORT_INDEXES, migrated.referenceImportIndexes())
    }

    private fun assertReferenceImportFileOperationContract(migrated: SupportSQLiteDatabase) {
        assertEquals(EXPECTED_FILE_OPERATION_COLUMNS, migrated.fileOperationColumns())
        assertEquals(EXPECTED_FILE_OPERATION_FOREIGN_KEYS, migrated.fileOperationForeignKeys())
        assertEquals(EXPECTED_FILE_OPERATION_INDEXES, migrated.fileOperationIndexes())
    }

    private fun assertMigratedIntentValues(migrated: SupportSQLiteDatabase) {
        val actual = buildList {
            migrated.query(
                """
                SELECT
                    `import_token`, `shoot_id`, `pose_id`, `relative_asset_path`,
                    `lifecycle_state`, `created_at_epoch_millis`, `updated_at_epoch_millis`,
                    `asset_ready_at_epoch_millis`, `terminal_at_epoch_millis`
                FROM `reference_import_intents`
                ORDER BY `import_token`
                """.trimIndent(),
            ).use { cursor ->
                assertEquals(-1, cursor.getColumnIndex("pose_index"))
                while (cursor.moveToNext()) {
                    actual@ add(
                        IntentFixture(
                            importToken = cursor.getString(0),
                            poseId = cursor.getString(2),
                            poseIndex = -1,
                            relativeAssetPath = cursor.getString(3),
                            lifecycleState = cursor.getString(4),
                            createdAtEpochMillis = cursor.getLong(5),
                            updatedAtEpochMillis = cursor.getLong(6),
                            assetReadyAtEpochMillis = cursor.longOrNull(7),
                            terminalAtEpochMillis = cursor.longOrNull(8),
                        ),
                    )
                    assertEquals(SHOOT_ID, cursor.getString(1))
                }
            }
        }
        assertEquals(
            IMPORT_FIXTURES.map { fixture -> fixture.copy(poseIndex = -1) }.sortedBy(IntentFixture::importToken),
            actual,
        )
    }

    private fun assertMigratedFileOperationValues(migrated: SupportSQLiteDatabase) {
        val actual = buildList {
            migrated.query(
                """
                SELECT
                    `import_token`, `relative_asset_path`, `relative_temp_path`,
                    `relative_quarantine_path`, `stage`, `byte_count`, `sha256`,
                    `last_failure_code`, `reconciliation_required`,
                    `created_at_epoch_millis`, `updated_at_epoch_millis`
                FROM `reference_import_file_operations`
                ORDER BY `import_token`
                """.trimIndent(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        FileOperationFixture(
                            importToken = cursor.getString(0),
                            relativeAssetPath = cursor.getString(1),
                            relativeTempPath = cursor.getString(2),
                            relativeQuarantinePath = cursor.getString(3),
                            stage = cursor.getString(4),
                            byteCount = cursor.longOrNull(5),
                            sha256 = cursor.stringOrNull(6),
                            lastFailureCode = cursor.stringOrNull(7),
                            reconciliationRequired = cursor.getInt(8),
                            createdAtEpochMillis = cursor.getLong(9),
                            updatedAtEpochMillis = cursor.getLong(10),
                        ),
                    )
                }
            }
        }
        assertEquals(FILE_OPERATION_FIXTURES.sortedBy(FileOperationFixture::importToken), actual)
    }

    private fun assertCaptureAuthoritySurvived(migrated: SupportSQLiteDatabase) {
        assertEquals(
            listOf(
                CAPTURE_SESSION_ID,
                SHOOT_ID,
                POSE_INDEX.toString(),
                CAPTURE_NEXT_ATTEMPT_NUMBER.toString(),
                CAPTURE_SESSION_LIFECYCLE,
                CAPTURE_CREATED_AT.toString(),
                CAPTURE_UPDATED_AT.toString(),
            ),
            migrated.singleTextRow(
                "SELECT session_id, shoot_id, current_pose_index, next_attempt_number, " +
                    "lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis " +
                    "FROM shoot_sessions WHERE session_id = ?",
                CAPTURE_SESSION_ID,
            ),
        )
        assertEquals(
            listOf(
                CAPTURE_COMMAND_TOKEN,
                CAPTURE_SESSION_ID,
                POSE_ID,
                POSE_INDEX.toString(),
                CAPTURE_ATTEMPT_NUMBER.toString(),
                CAPTURE_TRIGGER_TYPE,
                CAPTURE_ATTEMPT_LIFECYCLE,
                CAPTURE_RECONCILIATION_REQUIRED.toString(),
                DELETION_GENERATION.toString(),
                CAPTURE_CREATED_AT.toString(),
                CAPTURE_UPDATED_AT.toString(),
                CAPTURE_CONFIRMED_AT.toString(),
            ),
            migrated.singleTextRow(
                "SELECT command_token, session_id, pose_id, pose_index, attempt_number, " +
                    "trigger_type, lifecycle_state, reconciliation_required, " +
                    "captured_deletion_generation, created_at_epoch_millis, " +
                    "updated_at_epoch_millis, confirmed_at_epoch_millis " +
                    "FROM capture_attempts WHERE command_token = ?",
                CAPTURE_COMMAND_TOKEN,
            ),
        )
        assertEquals(
            (0 until 3).map { ordinal ->
                listOf(
                    CAPTURE_COMMAND_TOKEN,
                    ordinal.toString(),
                    "$CAPTURE_RELATIVE_PATH_PREFIX$ordinal.jpg",
                    (CAPTURE_BYTE_COUNT + ordinal).toString(),
                    CAPTURE_DURABILITY_STATE,
                    (CAPTURE_CAPTURED_AT + ordinal).toString(),
                    "$CAPTURE_INTEGRITY_METADATA_PREFIX$ordinal",
                )
            },
            migrated.textRows(
                "SELECT command_token, burst_ordinal, relative_path, byte_count, " +
                    "durability_state, captured_at_epoch_millis, integrity_metadata " +
                    "FROM private_capture_outputs WHERE command_token = ? ORDER BY burst_ordinal",
                CAPTURE_COMMAND_TOKEN,
            ),
        )
        assertEquals(
            listOf(
                CAPTURE_COMMAND_TOKEN,
                POSE_INDEX.toString(),
                null,
                DELETION_GENERATION.toString(),
                CAPTURE_CONFIRMED_AT.toString(),
            ),
            migrated.singleTextRow(
                "SELECT command_token, from_pose_index, to_pose_index, " +
                    "applied_deletion_generation, applied_at_epoch_millis " +
                    "FROM capture_confirmation_receipts WHERE command_token = ?",
                CAPTURE_COMMAND_TOKEN,
            ),
        )
        assertEquals(
            listOf(
                CAPTURE_COMMAND_TOKEN,
                CAPTURE_OUTBOX_LIFECYCLE,
                CAPTURE_CONFIRMED_AT.toString(),
                CAPTURE_CONFIRMED_AT.toString(),
                null,
            ),
            migrated.singleTextRow(
                "SELECT command_token, lifecycle_state, created_at_epoch_millis, " +
                    "updated_at_epoch_millis, retry_metadata FROM capture_export_outboxes " +
                    "WHERE command_token = ?",
                CAPTURE_COMMAND_TOKEN,
            ),
        )
        assertEquals(
            (0 until 3).map { ordinal ->
                listOf(
                    CAPTURE_COMMAND_TOKEN,
                    ordinal.toString(),
                    CAPTURE_TARGET_COLLECTION_URI,
                    CAPTURE_TARGET_VOLUME,
                    "migration-$ordinal.jpg",
                    CAPTURE_INTENDED_RELATIVE_PATH,
                    CAPTURE_MIME_TYPE,
                    CAPTURE_EXPORT_LIFECYCLE,
                    null,
                    null,
                    CAPTURE_AMBIGUITY_STATE,
                    DELETION_GENERATION.toString(),
                    CAPTURE_CONFIRMED_AT.toString(),
                    CAPTURE_CONFIRMED_AT.toString(),
                )
            },
            migrated.textRows(
                "SELECT command_token, burst_ordinal, target_collection_uri, target_volume, " +
                    "intended_display_name, intended_relative_path, intended_mime_type, " +
                    "lifecycle_state, claim_token, media_uri_string, ambiguity_state, " +
                    "deletion_generation, created_at_epoch_millis, updated_at_epoch_millis " +
                    "FROM capture_export_outputs WHERE command_token = ? ORDER BY burst_ordinal",
                CAPTURE_COMMAND_TOKEN,
            ),
        )
    }

    private fun assertCanonicalImportTablesOnly(migrated: SupportSQLiteDatabase) {
        val tables = buildSet {
            migrated.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name LIKE 'reference_import_%' ORDER BY name",
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(
            setOf("reference_import_intents", "reference_import_file_operations"),
            tables,
        )
    }

    private fun assertForeignKeyCheckEmpty(migrated: SupportSQLiteDatabase) {
        migrated.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("migration must leave no foreign-key violations", cursor.moveToFirst())
        }
    }

    private fun SupportSQLiteDatabase.singleTextRow(sql: String, argument: String): List<String?> =
        query(sql, arrayOf<Any?>(argument)).use { cursor ->
            assertTrue("expected one authority row", cursor.moveToFirst())
            val row = (0 until cursor.columnCount).map { column ->
                if (cursor.isNull(column)) null else cursor.getString(column)
            }
            assertFalse("expected exactly one authority row", cursor.moveToNext())
            row
        }

    private fun SupportSQLiteDatabase.textRows(sql: String, argument: String): List<List<String?>> =
        query(sql, arrayOf<Any?>(argument)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        (0 until cursor.columnCount).map { column ->
                            if (cursor.isNull(column)) null else cursor.getString(column)
                        },
                    )
                }
            }
        }

    private fun android.database.Cursor.longOrNull(column: Int): Long? =
        if (isNull(column)) null else getLong(column)

    private fun android.database.Cursor.stringOrNull(column: Int): String? =
        if (isNull(column)) null else getString(column)

    private fun SupportSQLiteDatabase.referenceImportColumns(): List<ColumnContract> =
        tableColumns("reference_import_intents")

    private fun SupportSQLiteDatabase.fileOperationColumns(): List<ColumnContract> =
        tableColumns("reference_import_file_operations")

    private fun SupportSQLiteDatabase.tableColumns(tableName: String): List<ColumnContract> =
        buildList {
            query("PRAGMA table_info(`$tableName`)").use { cursor ->
                while (cursor.moveToNext()) {
                    val defaultValueIndex = cursor.getColumnIndexOrThrow("dflt_value")
                    add(
                        ColumnContract(
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                            defaultValue = if (cursor.isNull(defaultValueIndex)) {
                                null
                            } else {
                                cursor.getString(defaultValueIndex)
                            },
                            primaryKeyPosition = cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                        ),
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.referenceImportForeignKeys(): Set<ForeignKeyContract> =
        tableForeignKeys("reference_import_intents")

    private fun SupportSQLiteDatabase.fileOperationForeignKeys(): Set<ForeignKeyContract> =
        tableForeignKeys("reference_import_file_operations")

    private fun SupportSQLiteDatabase.tableForeignKeys(tableName: String): Set<ForeignKeyContract> =
        buildSet {
            query("PRAGMA foreign_key_list(`$tableName`)").use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        ForeignKeyContract(
                            table = cursor.getString(cursor.getColumnIndexOrThrow("table")),
                            from = cursor.getString(cursor.getColumnIndexOrThrow("from")),
                            to = cursor.getString(cursor.getColumnIndexOrThrow("to")),
                            onUpdate = cursor.getString(cursor.getColumnIndexOrThrow("on_update")),
                            onDelete = cursor.getString(cursor.getColumnIndexOrThrow("on_delete")),
                        ),
                    )
                }
            }
        }

    private fun SupportSQLiteDatabase.referenceImportIndexes(): Set<IndexContract> =
        tableIndexes("reference_import_intents")

    private fun SupportSQLiteDatabase.fileOperationIndexes(): Set<IndexContract> =
        tableIndexes("reference_import_file_operations")

    private fun SupportSQLiteDatabase.tableIndexes(tableName: String): Set<IndexContract> =
        buildSet {
            query("PRAGMA index_list(`$tableName`)").use { indexes ->
                while (indexes.moveToNext()) {
                    if (indexes.getString(indexes.getColumnIndexOrThrow("origin")) != "c") {
                        continue
                    }
                    val name = indexes.getString(indexes.getColumnIndexOrThrow("name"))
                    val columns = buildList {
                        query("PRAGMA index_info(`$name`)").use { details ->
                            while (details.moveToNext()) {
                                add(details.getString(details.getColumnIndexOrThrow("name")))
                            }
                        }
                    }
                    add(
                        IndexContract(
                            name = name,
                            columns = columns,
                            unique = indexes.getInt(indexes.getColumnIndexOrThrow("unique")) == 1,
                            partial = indexes.getInt(indexes.getColumnIndexOrThrow("partial")) == 1,
                        ),
                    )
                }
            }
        }

    private fun assertExactOrdinalTriggers(reopened: SupportSQLiteDatabase) {
        val actual = buildSet {
            reopened.query(
                "SELECT name, tbl_name, sql FROM sqlite_master WHERE type = 'trigger'",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val normalizedSql = cursor.getString(cursor.getColumnIndexOrThrow("sql"))
                        .replace(Regex("\\s+"), " ")
                        .uppercase()
                    assertTrue(
                        "ordinal trigger must reject non-integer values",
                        normalizedSql.contains("TYPEOF(NEW.`BURST_ORDINAL`) != 'INTEGER'"),
                    )
                    assertTrue(
                        "ordinal trigger must reject out-of-range values",
                        normalizedSql.contains("NEW.`BURST_ORDINAL` NOT BETWEEN 0 AND 2"),
                    )
                    add(
                        TriggerContract(
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            table = cursor.getString(cursor.getColumnIndexOrThrow("tbl_name")),
                            event = when {
                                normalizedSql.contains("BEFORE INSERT ON") -> "INSERT"
                                normalizedSql.contains("BEFORE UPDATE ON") -> "UPDATE"
                                else -> throw AssertionError("unexpected ordinal trigger SQL")
                            },
                        ),
                    )
                }
            }
        }
        assertEquals(EXPECTED_ORDINAL_TRIGGERS, actual)
    }

    private fun assertNoTestDatabaseResidue() {
        assertFalse(context.databaseList().contains(databaseName))
        val residue = context.roomTestDatabaseResidue(databaseName)
        assertTrue("test database residue remains: ${residue.map { it.name }}", residue.isEmpty())
    }

    private data class IntentFixture(
        val importToken: String,
        val poseId: String,
        val poseIndex: Int,
        val relativeAssetPath: String,
        val lifecycleState: String,
        val createdAtEpochMillis: Long,
        val updatedAtEpochMillis: Long,
        val assetReadyAtEpochMillis: Long?,
        val terminalAtEpochMillis: Long?,
    )

    private data class FileOperationFixture(
        val importToken: String,
        val relativeAssetPath: String,
        val relativeTempPath: String,
        val relativeQuarantinePath: String,
        val stage: String,
        val byteCount: Long?,
        val sha256: String?,
        val lastFailureCode: String?,
        val reconciliationRequired: Int,
        val createdAtEpochMillis: Long,
        val updatedAtEpochMillis: Long,
    )

    private data class ColumnContract(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
    )

    private data class ForeignKeyContract(
        val table: String,
        val from: String,
        val to: String,
        val onUpdate: String,
        val onDelete: String,
    )

    private data class IndexContract(
        val name: String,
        val columns: List<String>,
        val unique: Boolean,
        val partial: Boolean,
    )

    private data class TriggerContract(
        val name: String,
        val table: String,
        val event: String,
    )

    companion object {
        private const val SHOOT_ID = "migration-test-shoot"
        private const val TEST_SHOOT_NAME = "Migration test shoot"
        private const val CREATED_AT_EPOCH_MILLIS = 10L
        private const val UPDATED_AT_EPOCH_MILLIS = 20L
        private const val SHOOT_LIFECYCLE_STATE = "ACTIVE"
        private const val DELETION_GENERATION = 0L
        private const val POSE_INDEX = 3
        private const val POSE_ID = "migration-test-pose"
        private const val TEST_POSE_LABEL = "Migration test pose"
        private const val MIRROR_ALLOWED = 1
        private const val VALIDATION_STATE = "VALID"
        private const val TEST_DETECTOR_METADATA = "test-detector-metadata"
        private const val TEST_MODEL_METADATA = "test-model-metadata"
        private const val TEST_PREPROCESSING_METADATA = "test-preprocessing-metadata"

        private const val CAPTURE_SESSION_ID = "migration-capture-session"
        private const val CAPTURE_NEXT_ATTEMPT_NUMBER = 1L
        private const val CAPTURE_SESSION_LIFECYCLE = "COMPLETED"
        private const val CAPTURE_COMMAND_TOKEN = "migration-capture-command"
        private const val CAPTURE_ATTEMPT_NUMBER = 0L
        private const val CAPTURE_TRIGGER_TYPE = "MANUAL"
        private const val CAPTURE_ATTEMPT_LIFECYCLE = "CONFIRMED"
        private const val CAPTURE_RECONCILIATION_REQUIRED = 0
        private const val CAPTURE_CREATED_AT = 30L
        private const val CAPTURE_UPDATED_AT = 31L
        private const val CAPTURE_CONFIRMED_AT = 31L
        private const val CAPTURE_RELATIVE_PATH_PREFIX = "capture/migration-"
        private const val CAPTURE_BYTE_COUNT = 123L
        private const val CAPTURE_DURABILITY_STATE = "DURABLE"
        private const val CAPTURE_CAPTURED_AT = 31L
        private const val CAPTURE_INTEGRITY_METADATA_PREFIX = "sha256:migration-"
        private const val CAPTURE_OUTBOX_LIFECYCLE = "PENDING"
        private const val CAPTURE_TARGET_COLLECTION_URI = "content://media/external_primary/images/media"
        private const val CAPTURE_TARGET_VOLUME = "external_primary"
        private const val CAPTURE_INTENDED_RELATIVE_PATH = "Pictures/PoseGuideSnap"
        private const val CAPTURE_MIME_TYPE = "image/jpeg"
        private const val CAPTURE_EXPORT_LIFECYCLE = "PENDING"
        private const val CAPTURE_AMBIGUITY_STATE = "NONE"

        private val IMPORT_FIXTURES = listOf(
            IntentFixture(
                importToken = "import-cleaned",
                poseId = "pose-cleaned",
                poseIndex = 5,
                relativeAssetPath = "reference-assets/assets/cleaned.asset",
                lifecycleState = "REJECTED_CLEANED",
                createdAtEpochMillis = 50L,
                updatedAtEpochMillis = 53L,
                assetReadyAtEpochMillis = null,
                terminalAtEpochMillis = 53L,
            ),
            IntentFixture(
                importToken = "import-committed",
                poseId = POSE_ID,
                poseIndex = POSE_INDEX,
                relativeAssetPath = "reference-assets/assets/committed.asset",
                lifecycleState = "COMMITTED",
                createdAtEpochMillis = 40L,
                updatedAtEpochMillis = 43L,
                assetReadyAtEpochMillis = 42L,
                terminalAtEpochMillis = 43L,
            ),
            IntentFixture(
                importToken = "import-quarantined",
                poseId = "pose-quarantined",
                poseIndex = 4,
                relativeAssetPath = "reference-assets/assets/quarantined.asset",
                lifecycleState = "REJECTED_QUARANTINED",
                createdAtEpochMillis = 60L,
                updatedAtEpochMillis = 64L,
                assetReadyAtEpochMillis = 62L,
                terminalAtEpochMillis = 64L,
            ),
        )

        private val FILE_OPERATION_FIXTURES = listOf(
            FileOperationFixture(
                importToken = "import-cleaned",
                relativeAssetPath = "reference-assets/assets/cleaned.asset",
                relativeTempPath = "reference-assets/assets/.cleaned.pending",
                relativeQuarantinePath = "reference-assets/quarantine/cleaned.quarantined",
                stage = "CLEANED_DURABLE",
                byteCount = null,
                sha256 = null,
                lastFailureCode = null,
                reconciliationRequired = 0,
                createdAtEpochMillis = 50L,
                updatedAtEpochMillis = 52L,
            ),
            FileOperationFixture(
                importToken = "import-committed",
                relativeAssetPath = "reference-assets/assets/committed.asset",
                relativeTempPath = "reference-assets/assets/.committed.pending",
                relativeQuarantinePath = "reference-assets/quarantine/committed.quarantined",
                stage = "FINAL_DURABLE",
                byteCount = 101L,
                sha256 = "ab".repeat(32),
                lastFailureCode = null,
                reconciliationRequired = 0,
                createdAtEpochMillis = 40L,
                updatedAtEpochMillis = 42L,
            ),
            FileOperationFixture(
                importToken = "import-quarantined",
                relativeAssetPath = "reference-assets/assets/quarantined.asset",
                relativeTempPath = "reference-assets/assets/.quarantined.pending",
                relativeQuarantinePath = "reference-assets/quarantine/quarantined.quarantined",
                stage = "QUARANTINE_DURABLE",
                byteCount = 202L,
                sha256 = "cd".repeat(32),
                lastFailureCode = "STATE_MISMATCH",
                reconciliationRequired = 1,
                createdAtEpochMillis = 60L,
                updatedAtEpochMillis = 63L,
            ),
        )

        private val EXPECTED_V3_REFERENCE_IMPORT_COLUMNS = listOf(
            ColumnContract("import_token", "TEXT", true, null, 1),
            ColumnContract("shoot_id", "TEXT", true, null, 0),
            ColumnContract("pose_id", "TEXT", true, null, 0),
            ColumnContract("relative_asset_path", "TEXT", true, null, 0),
            ColumnContract("lifecycle_state", "TEXT", true, null, 0),
            ColumnContract("created_at_epoch_millis", "INTEGER", true, null, 0),
            ColumnContract("updated_at_epoch_millis", "INTEGER", true, null, 0),
            ColumnContract("asset_ready_at_epoch_millis", "INTEGER", false, null, 0),
            ColumnContract("terminal_at_epoch_millis", "INTEGER", false, null, 0),
        )

        private val EXPECTED_REFERENCE_IMPORT_FOREIGN_KEYS = setOf(
            ForeignKeyContract(
                table = "shoots",
                from = "shoot_id",
                to = "shoot_id",
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        )

        private val EXPECTED_V3_REFERENCE_IMPORT_INDEXES = setOf(
            IndexContract(
                name = "index_reference_import_intents_shoot_id_pose_id",
                columns = listOf("shoot_id", "pose_id"),
                unique = true,
                partial = false,
            ),
            IndexContract(
                name = "index_reference_import_intents_lifecycle_state",
                columns = listOf("lifecycle_state"),
                unique = false,
                partial = false,
            ),
        )

        private val EXPECTED_FILE_OPERATION_COLUMNS = listOf(
            ColumnContract("import_token", "TEXT", true, null, 1),
            ColumnContract("relative_asset_path", "TEXT", true, null, 0),
            ColumnContract("relative_temp_path", "TEXT", true, null, 0),
            ColumnContract("relative_quarantine_path", "TEXT", true, null, 0),
            ColumnContract("stage", "TEXT", true, null, 0),
            ColumnContract("byte_count", "INTEGER", false, null, 0),
            ColumnContract("sha256", "TEXT", false, null, 0),
            ColumnContract("last_failure_code", "TEXT", false, null, 0),
            ColumnContract("reconciliation_required", "INTEGER", true, null, 0),
            ColumnContract("created_at_epoch_millis", "INTEGER", true, null, 0),
            ColumnContract("updated_at_epoch_millis", "INTEGER", true, null, 0),
        )

        private val EXPECTED_FILE_OPERATION_FOREIGN_KEYS = setOf(
            ForeignKeyContract(
                table = "reference_import_intents",
                from = "import_token",
                to = "import_token",
                onUpdate = "NO ACTION",
                onDelete = "RESTRICT",
            ),
        )

        private val EXPECTED_FILE_OPERATION_INDEXES = setOf(
            IndexContract(
                name = "index_reference_import_file_operations_stage",
                columns = listOf("stage"),
                unique = false,
                partial = false,
            ),
            IndexContract(
                name = "index_reference_import_file_operations_reconciliation_required",
                columns = listOf("reconciliation_required"),
                unique = false,
                partial = false,
            ),
        )

        private val EXPECTED_ORDINAL_TRIGGERS = setOf(
            TriggerContract(
                name = "trigger_private_capture_outputs_burst_ordinal_insert",
                table = "private_capture_outputs",
                event = "INSERT",
            ),
            TriggerContract(
                name = "trigger_private_capture_outputs_burst_ordinal_update",
                table = "private_capture_outputs",
                event = "UPDATE",
            ),
            TriggerContract(
                name = "trigger_capture_export_outputs_burst_ordinal_insert",
                table = "capture_export_outputs",
                event = "INSERT",
            ),
            TriggerContract(
                name = "trigger_capture_export_outputs_burst_ordinal_update",
                table = "capture_export_outputs",
                event = "UPDATE",
            ),
        )
    }
}
