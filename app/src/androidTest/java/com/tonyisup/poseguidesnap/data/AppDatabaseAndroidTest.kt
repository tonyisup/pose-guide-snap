package com.tonyisup.poseguidesnap.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.db.ActiveSessionAuthorityTriggers
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var appDatabase: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "pose_guide_snap_private_android_test_${UUID.randomUUID()}.db"
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
    fun createdDatabaseContainsAuthorityTablesForeignKeysAndWorkflowIndexes() {
        val sqlite = openDatabase().openHelper.writableDatabase

        val tables = sqlite.stringColumn(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
            "name",
        )
        assertTrue(tables.containsAll(REQUIRED_TABLES))

        REQUIRED_FOREIGN_KEYS.forEach { (table, expected) ->
            val actual = buildSet {
                sqlite.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
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
            assertEquals("foreign keys for $table", expected, actual)
        }

        REQUIRED_INDEXES.forEach { (table, expected) ->
            val actual = sqlite.indexContracts(table)
            expected.forEach { index ->
                assertTrue("missing index on $table: $index; actual=$actual", index in actual)
            }
        }
    }

    @Test
    fun privateCaptureOutputRejectsNonIntegerAndOutsideRangeInsertAndUpdateOrdinals() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedAttempt(sqlite)

        assertOrdinalRejected {
            sqlite.execSQL(privateOutputInsert(-1, "negative.jpg"))
        }
        assertOrdinalRejected {
            sqlite.execSQL(privateOutputInsert(3, "too-high.jpg"))
        }
        assertOrdinalRejected {
            sqlite.execSQL(privateOutputInsert(0.5, "fractional.jpg"))
        }

        sqlite.execSQL(privateOutputInsert(0, "valid.jpg"))
        assertOrdinalRejected {
            sqlite.execSQL(
                "UPDATE private_capture_outputs SET burst_ordinal = 3 " +
                    "WHERE command_token = 'command-1' AND burst_ordinal = 0",
            )
        }
        assertOrdinalRejected {
            sqlite.execSQL(
                "UPDATE private_capture_outputs SET burst_ordinal = 1.5 " +
                    "WHERE command_token = 'command-1' AND burst_ordinal = 0",
            )
        }
    }

    @Test
    fun captureExportOutputRejectsNonIntegerAndOutsideRangeInsertAndUpdateOrdinals() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedOutbox(sqlite)

        assertOrdinalRejected {
            sqlite.execSQL(exportOutputInsert(-1, "claim-negative"))
        }
        assertOrdinalRejected {
            sqlite.execSQL(exportOutputInsert(3, "claim-too-high"))
        }
        assertOrdinalRejected {
            sqlite.execSQL(exportOutputInsert(0.5, "claim-fractional"))
        }

        sqlite.execSQL(exportOutputInsert(0, "claim-valid"))
        assertOrdinalRejected {
            sqlite.execSQL(
                "UPDATE capture_export_outputs SET burst_ordinal = 3 " +
                    "WHERE command_token = 'command-1' AND burst_ordinal = 0",
            )
        }
        assertOrdinalRejected {
            sqlite.execSQL(
                "UPDATE capture_export_outputs SET burst_ordinal = 1.5 " +
                    "WHERE command_token = 'command-1' AND burst_ordinal = 0",
            )
        }
    }

    @Test
    fun ordinalConstraintsRemainInstalledExactlyOnceAfterReopen() {
        var sqlite = openDatabase().openHelper.writableDatabase
        assertEquals(4, sqlite.ordinalTriggerCount())
        appDatabase?.close()
        appDatabase = null

        sqlite = openDatabase().openHelper.writableDatabase
        assertEquals(4, sqlite.ordinalTriggerCount())
        seedAttempt(sqlite)
        assertOrdinalRejected {
            sqlite.execSQL(privateOutputInsert(3, "reopen-too-high.jpg"))
        }
    }

    @Test
    fun activeSessionInsertRejectsSecondActiveForSameShootButPermitsCompletedHistoryAndDifferentShoots() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-session-authority-1")
        seedShoot(sqlite, "shoot-session-authority-2")

        insertSession(
            sqlite,
            sessionId = "completed-history-1",
            shootId = "shoot-session-authority-1",
            lifecycleState = "COMPLETED",
        )
        insertSession(
            sqlite,
            sessionId = "active-session-1",
            shootId = "shoot-session-authority-1",
            lifecycleState = "ACTIVE",
        )
        assertActiveSessionRejected {
            insertSession(
                sqlite,
                sessionId = "active-session-2",
                shootId = "shoot-session-authority-1",
                lifecycleState = "ACTIVE",
            )
        }

        insertSession(
            sqlite,
            sessionId = "completed-history-2",
            shootId = "shoot-session-authority-1",
            lifecycleState = "COMPLETED",
        )
        insertSession(
            sqlite,
            sessionId = "active-session-other-shoot",
            shootId = "shoot-session-authority-2",
            lifecycleState = "ACTIVE",
        )

        assertEquals(1, sqlite.sessionCount("shoot-session-authority-1", "ACTIVE"))
        assertEquals(2, sqlite.sessionCount("shoot-session-authority-1", "COMPLETED"))
        assertEquals(1, sqlite.sessionCount("shoot-session-authority-2", "ACTIVE"))
    }

    @Test
    fun completedSessionUpdateToActiveIsRejectedUntilExistingActiveSessionCompletes() {
        val sqlite = openDatabase().openHelper.writableDatabase
        seedShoot(sqlite, "shoot-session-update-authority")
        insertSession(
            sqlite,
            sessionId = "first-active-session",
            shootId = "shoot-session-update-authority",
            lifecycleState = "ACTIVE",
        )
        insertSession(
            sqlite,
            sessionId = "second-completed-session",
            shootId = "shoot-session-update-authority",
            lifecycleState = "COMPLETED",
        )

        assertActiveSessionRejected {
            sqlite.execSQL(
                "UPDATE shoot_sessions SET lifecycle_state = 'ACTIVE', " +
                    "updated_at_epoch_millis = 2 " +
                    "WHERE session_id = 'second-completed-session'",
            )
        }
        assertEquals("COMPLETED", sqlite.sessionState("second-completed-session"))

        sqlite.execSQL(
            "UPDATE shoot_sessions SET lifecycle_state = 'COMPLETED', " +
                "updated_at_epoch_millis = 2 WHERE session_id = 'first-active-session'",
        )
        sqlite.execSQL(
            "UPDATE shoot_sessions SET lifecycle_state = 'ACTIVE', " +
                "updated_at_epoch_millis = 3 " +
                "WHERE session_id = 'second-completed-session'",
        )

        assertEquals("COMPLETED", sqlite.sessionState("first-active-session"))
        assertEquals("ACTIVE", sqlite.sessionState("second-completed-session"))
        assertEquals(1, sqlite.sessionCount("shoot-session-update-authority", "ACTIVE"))
    }

    @Test
    fun activeSessionAuthorityTriggersRemainExactBehavioralAndIdempotentAfterReopen() {
        var sqlite = openDatabase().openHelper.writableDatabase
        assertEquals(EXPECTED_ACTIVE_SESSION_TRIGGER_NAMES, sqlite.sessionAuthorityTriggerNames())
        seedShoot(sqlite, "shoot-session-reopen-authority")
        insertSession(
            sqlite,
            sessionId = "reopen-active-session",
            shootId = "shoot-session-reopen-authority",
            lifecycleState = "ACTIVE",
        )
        assertActiveSessionRejected {
            insertSession(
                sqlite,
                sessionId = "reopen-rejected-session-before-close",
                shootId = "shoot-session-reopen-authority",
                lifecycleState = "ACTIVE",
            )
        }

        appDatabase?.close()
        appDatabase = null
        sqlite = openDatabase().openHelper.writableDatabase

        assertEquals(EXPECTED_ACTIVE_SESSION_TRIGGER_NAMES, sqlite.sessionAuthorityTriggerNames())
        ActiveSessionAuthorityTriggers.install(sqlite)
        ActiveSessionAuthorityTriggers.install(sqlite)
        assertEquals(EXPECTED_ACTIVE_SESSION_TRIGGER_NAMES, sqlite.sessionAuthorityTriggerNames())
        assertActiveSessionRejected {
            insertSession(
                sqlite,
                sessionId = "reopen-rejected-session-after-open",
                shootId = "shoot-session-reopen-authority",
                lifecycleState = "ACTIVE",
            )
        }
    }

    private fun openDatabase(): AppDatabase =
        AppDatabase.create(context, databaseName).also { appDatabase = it }

    private fun assertNoTestDatabaseResidue() {
        assertFalse(context.databaseList().contains(databaseName))
        assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
    }

    private fun seedShoot(
        sqlite: SupportSQLiteDatabase,
        shootId: String,
    ) {
        sqlite.execSQL(
            "INSERT INTO shoots " +
                "(shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis, " +
                "lifecycle_state, deletion_generation) " +
                "VALUES ('$shootId', 'Session authority test shoot', 1, 1, 'ACTIVE', 0)",
        )
    }

    private fun insertSession(
        sqlite: SupportSQLiteDatabase,
        sessionId: String,
        shootId: String,
        lifecycleState: String,
    ) {
        sqlite.execSQL(
            "INSERT INTO shoot_sessions " +
                "(session_id, shoot_id, current_pose_index, next_attempt_number, " +
                "lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis) " +
                "VALUES ('$sessionId', '$shootId', 0, 1, '$lifecycleState', 1, 1)",
        )
    }

    private fun seedAttempt(sqlite: SupportSQLiteDatabase) {
        sqlite.execSQL(
            "INSERT INTO shoots " +
                "(shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis, " +
                "lifecycle_state, deletion_generation) " +
                "VALUES ('shoot-1', 'Test shoot', 1, 1, 'active', 0)",
        )
        sqlite.execSQL(
            "INSERT INTO shoot_sessions " +
                "(session_id, shoot_id, current_pose_index, next_attempt_number, " +
                "lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis) " +
                "VALUES ('session-1', 'shoot-1', 0, 2, 'active', 1, 1)",
        )
        sqlite.execSQL(
            "INSERT INTO capture_attempts " +
                "(command_token, session_id, pose_id, pose_index, attempt_number, trigger_type, " +
                "lifecycle_state, reconciliation_required, captured_deletion_generation, " +
                "created_at_epoch_millis, updated_at_epoch_millis, confirmed_at_epoch_millis) " +
                "VALUES ('command-1', 'session-1', 'pose-1', 0, 1, 'manual', " +
                "'captured', 0, 0, 1, 1, NULL)",
        )
    }

    private fun seedOutbox(sqlite: SupportSQLiteDatabase) {
        seedAttempt(sqlite)
        sqlite.execSQL(
            "INSERT INTO capture_confirmation_receipts " +
                "(command_token, from_pose_index, to_pose_index, " +
                "applied_deletion_generation, applied_at_epoch_millis) " +
                "VALUES ('command-1', 0, NULL, 0, 2)",
        )
        sqlite.execSQL(
            "INSERT INTO capture_export_outboxes " +
                "(command_token, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis, retry_metadata) " +
                "VALUES ('command-1', 'pending', 2, 2, NULL)",
        )
    }

    private fun privateOutputInsert(ordinal: Number, path: String): String =
        "INSERT INTO private_capture_outputs " +
            "(command_token, burst_ordinal, relative_path, byte_count, durability_state, " +
            "captured_at_epoch_millis, integrity_metadata) " +
            "VALUES ('command-1', $ordinal, '$path', 100, 'durable', 1, NULL)"

    private fun exportOutputInsert(ordinal: Number, claimToken: String): String =
        "INSERT INTO capture_export_outputs " +
            "(command_token, burst_ordinal, target_collection_uri, target_volume, " +
            "intended_display_name, intended_relative_path, intended_mime_type, " +
            "lifecycle_state, claim_token, media_uri_string, ambiguity_state, " +
            "deletion_generation, created_at_epoch_millis, updated_at_epoch_millis) " +
            "VALUES ('command-1', $ordinal, 'content://media/external/images/media', " +
            "'external_primary', 'photo.jpg', 'Pictures/PoseGuideSnap', 'image/jpeg', " +
            "'pending', '$claimToken', NULL, 'none', 0, 2, 2)"

    private fun assertActiveSessionRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected SQLite to reject a second ACTIVE session for one shoot")
        } catch (error: SQLiteConstraintException) {
            assertEquals(
                ActiveSessionAuthorityTriggers.ERROR_MESSAGE,
                error.message.orEmpty().substringBefore(" (code "),
            )
        }
    }

    private fun SupportSQLiteDatabase.sessionCount(
        shootId: String,
        lifecycleState: String,
    ): Int = query(
        "SELECT COUNT(*) AS session_count FROM shoot_sessions " +
            "WHERE shoot_id = '$shootId' AND lifecycle_state = '$lifecycleState'",
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(cursor.getColumnIndexOrThrow("session_count"))
    }

    private fun SupportSQLiteDatabase.sessionState(sessionId: String): String =
        query(
            "SELECT lifecycle_state FROM shoot_sessions WHERE session_id = '$sessionId'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(cursor.getColumnIndexOrThrow("lifecycle_state"))
        }

    private fun SupportSQLiteDatabase.sessionAuthorityTriggerNames(): Set<String> =
        stringColumn(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'trigger' AND tbl_name = 'shoot_sessions'",
            "name",
        )

    private fun assertOrdinalRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected SQLite to reject a non-integer or out-of-range ordinal")
        } catch (error: SQLiteConstraintException) {
            assertTrue(
                "unexpected constraint message: ${error.message}",
                error.message.orEmpty().contains("burst_ordinal must be between 0 and 2"),
            )
        }
    }

    private fun SupportSQLiteDatabase.ordinalTriggerCount(): Int =
        query(
            "SELECT COUNT(*) AS trigger_count FROM sqlite_master " +
                "WHERE type = 'trigger' AND tbl_name IN " +
                "('private_capture_outputs', 'capture_export_outputs')",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(cursor.getColumnIndexOrThrow("trigger_count"))
        }

    private fun SupportSQLiteDatabase.stringColumn(sql: String, column: String): Set<String> =
        buildSet {
            query(sql).use { cursor ->
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow(column)))
                }
            }
        }

    private fun SupportSQLiteDatabase.indexContracts(table: String): Set<IndexContract> =
        buildSet {
            query("PRAGMA index_list(`$table`)").use { indexes ->
                while (indexes.moveToNext()) {
                    val indexName = indexes.getString(indexes.getColumnIndexOrThrow("name"))
                    val unique = indexes.getInt(indexes.getColumnIndexOrThrow("unique")) == 1
                    val columns = mutableListOf<String>()
                    query("PRAGMA index_info(`$indexName`)").use { details ->
                        while (details.moveToNext()) {
                            columns += details.getString(details.getColumnIndexOrThrow("name"))
                        }
                    }
                    add(IndexContract(columns, unique))
                }
            }
        }

    private data class ForeignKeyContract(
        val table: String,
        val from: String,
        val to: String,
        val onUpdate: String,
        val onDelete: String,
    )

    private data class IndexContract(
        val columns: List<String>,
        val unique: Boolean,
    )

    companion object {
        private val EXPECTED_ACTIVE_SESSION_TRIGGER_NAMES = setOf(
            "trigger_shoot_sessions_one_active_insert",
            "trigger_shoot_sessions_one_active_update",
        )

        private val REQUIRED_TABLES = setOf(
            "shoots",
            "shoot_poses",
            "shoot_sessions",
            "capture_attempts",
            "private_capture_outputs",
            "capture_confirmation_receipts",
            "capture_export_outboxes",
            "capture_export_outputs",
        )

        private val REQUIRED_FOREIGN_KEYS = mapOf(
            "shoot_poses" to setOf(foreignKey("shoots", "shoot_id", "shoot_id")),
            "shoot_sessions" to setOf(foreignKey("shoots", "shoot_id", "shoot_id")),
            "capture_attempts" to setOf(foreignKey("shoot_sessions", "session_id", "session_id")),
            "private_capture_outputs" to setOf(foreignKey("capture_attempts", "command_token", "command_token")),
            "capture_confirmation_receipts" to setOf(foreignKey("capture_attempts", "command_token", "command_token")),
            "capture_export_outboxes" to setOf(foreignKey("capture_confirmation_receipts", "command_token", "command_token")),
            "capture_export_outputs" to setOf(foreignKey("capture_export_outboxes", "command_token", "command_token")),
        )

        private val REQUIRED_INDEXES = mapOf(
            "shoots" to setOf(IndexContract(listOf("lifecycle_state"), false)),
            "shoot_poses" to setOf(IndexContract(listOf("shoot_id", "pose_id"), true)),
            "shoot_sessions" to setOf(IndexContract(listOf("shoot_id", "lifecycle_state"), false)),
            "capture_attempts" to setOf(
                IndexContract(listOf("session_id", "attempt_number"), true),
                IndexContract(listOf("lifecycle_state"), false),
            ),
            "private_capture_outputs" to setOf(IndexContract(listOf("durability_state"), false)),
            "capture_export_outboxes" to setOf(IndexContract(listOf("lifecycle_state"), false)),
            "capture_export_outputs" to setOf(
                IndexContract(listOf("claim_token"), true),
                IndexContract(listOf("lifecycle_state"), false),
                IndexContract(listOf("deletion_generation"), false),
            ),
        )

        private fun foreignKey(
            table: String,
            from: String,
            to: String,
        ) = ForeignKeyContract(
            table = table,
            from = from,
            to = to,
            onUpdate = "NO ACTION",
            onDelete = "RESTRICT",
        )
    }
}
