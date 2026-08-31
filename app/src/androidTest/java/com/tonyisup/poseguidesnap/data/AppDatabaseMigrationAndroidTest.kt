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
    fun migrationFromCommittedV1PreservesLegacyPoseAndInstallsV2AuthorityContract() {
        migrationHelper.createDatabase(databaseName, 1).use { v1 ->
            seedLegacyShootAndPose(v1)
            AuthorityOrdinalTriggers.install(v1)
            assertExactOrdinalTriggers(v1)
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        ).use { migrated ->
            assertLegacyPoseSurvived(migrated)
            assertReferenceImportIntentContract(migrated)
            assertReferenceImportFileOperationContract(migrated)
            assertExactOrdinalTriggers(migrated)
        }

        appDatabase = AppDatabase.create(context, databaseName)
        val reopened = checkNotNull(appDatabase).openHelper.writableDatabase
        assertExactOrdinalTriggers(reopened)
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

    private fun assertReferenceImportIntentContract(migrated: SupportSQLiteDatabase) {
        assertEquals(EXPECTED_REFERENCE_IMPORT_COLUMNS, migrated.referenceImportColumns())
        assertEquals(EXPECTED_REFERENCE_IMPORT_FOREIGN_KEYS, migrated.referenceImportForeignKeys())
        assertEquals(EXPECTED_REFERENCE_IMPORT_INDEXES, migrated.referenceImportIndexes())
    }

    private fun assertReferenceImportFileOperationContract(migrated: SupportSQLiteDatabase) {
        assertEquals(EXPECTED_FILE_OPERATION_COLUMNS, migrated.fileOperationColumns())
        assertEquals(EXPECTED_FILE_OPERATION_FOREIGN_KEYS, migrated.fileOperationForeignKeys())
        assertEquals(EXPECTED_FILE_OPERATION_INDEXES, migrated.fileOperationIndexes())
    }

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

        private val EXPECTED_REFERENCE_IMPORT_COLUMNS = listOf(
            ColumnContract("import_token", "TEXT", true, null, 1),
            ColumnContract("shoot_id", "TEXT", true, null, 0),
            ColumnContract("pose_id", "TEXT", true, null, 0),
            ColumnContract("pose_index", "INTEGER", true, null, 0),
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

        private val EXPECTED_REFERENCE_IMPORT_INDEXES = setOf(
            IndexContract(
                name = "index_reference_import_intents_shoot_id_pose_id",
                columns = listOf("shoot_id", "pose_id"),
                unique = true,
                partial = false,
            ),
            IndexContract(
                name = "index_reference_import_intents_shoot_id_pose_index",
                columns = listOf("shoot_id", "pose_index"),
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
