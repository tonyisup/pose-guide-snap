package com.tonyisup.poseguidesnap.data

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDatabaseV4SchemaArtifactTest {
    @Test
    fun schemaV4MatchesFrozenCaptureFileOperationContract() {
        val schema = schemaArtifact(4)
        assertEquals(4, schema["version"]!!.jsonPrimitive.content.toInt())
        val entities = schema.entitiesByName()
        assertEquals(EXPECTED_TABLES, entities.keys)

        val operation = entities.getValue(CAPTURE_FILE_OPERATION_TABLE)
        assertEquals(EXPECTED_COLUMNS, operation.columnContracts())
        assertEquals(
            listOf("command_token", "burst_ordinal"),
            operation["primaryKey"]!!.jsonObject.stringArray("columnNames"),
        )
        assertEquals(
            setOf(
                ForeignKeyContract(
                    table = "capture_attempts",
                    columns = listOf("command_token"),
                    referencedColumns = listOf("command_token"),
                    onDelete = "RESTRICT",
                    onUpdate = "NO ACTION",
                ),
            ),
            operation.foreignKeyContracts(),
        )
        assertEquals(
            mapOf(
                "index_capture_file_operations_stage" to
                    IndexContract(listOf("stage"), unique = false),
                "index_capture_file_operations_reconciliation_required" to
                    IndexContract(listOf("reconciliation_required"), unique = false),
            ),
            operation.indexContracts(),
        )

        EXPECTED_INDEXES.forEach { (table, expected) ->
            assertEquals("exact indexes for $table", expected, entities.getValue(table).indexContracts())
        }
        HISTORICAL_SCHEMA_HASHES.forEach { (version, expectedHash) ->
            val file = File("schemas/com.tonyisup.poseguidesnap.data.db.AppDatabase/$version.json")
            assertTrue("Room V$version schema artifact must exist", file.isFile)
            assertEquals("Room V$version schema bytes changed", expectedHash, sha256(file.readBytes()))
        }
    }

    private fun schemaArtifact(version: Int): JsonObject {
        val file = File("schemas/com.tonyisup.poseguidesnap.data.db.AppDatabase/$version.json")
        assertTrue("Room V$version schema artifact must exist at ${file.path}", file.isFile)
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    private fun JsonObject.entitiesByName(): Map<String, JsonObject> =
        getValue("entities").jsonArray.associate { entity ->
            val value = entity.jsonObject
            value["tableName"]!!.jsonPrimitive.content to value
        }

    private fun JsonObject.columnContracts(): List<ColumnContract> =
        getValue("fields").jsonArray.map { field ->
            val value = field.jsonObject
            ColumnContract(
                name = value["columnName"]!!.jsonPrimitive.content,
                affinity = value["affinity"]!!.jsonPrimitive.content,
                notNull = value["notNull"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            )
        }

    private fun JsonObject.foreignKeyContracts(): Set<ForeignKeyContract> =
        get("foreignKeys")?.jsonArray?.map { foreignKey ->
            val value = foreignKey.jsonObject
            ForeignKeyContract(
                table = value["table"]!!.jsonPrimitive.content,
                columns = value.stringArray("columns"),
                referencedColumns = value.stringArray("referencedColumns"),
                onDelete = value["onDelete"]!!.jsonPrimitive.content,
                onUpdate = value["onUpdate"]!!.jsonPrimitive.content,
            )
        }?.toSet().orEmpty()

    private fun JsonObject.indexContracts(): Map<String, IndexContract> =
        get("indices")?.jsonArray?.associate { index ->
            val value = index.jsonObject
            value["name"]!!.jsonPrimitive.content to IndexContract(
                columns = value.stringArray("columnNames"),
                unique = value["unique"]!!.jsonPrimitive.content.toBoolean(),
            )
        }.orEmpty()

    private fun JsonObject.stringArray(name: String): List<String> =
        (getValue(name) as JsonArray).map { it.jsonPrimitive.content }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class ColumnContract(
        val name: String,
        val affinity: String,
        val notNull: Boolean,
    )

    private data class ForeignKeyContract(
        val table: String,
        val columns: List<String>,
        val referencedColumns: List<String>,
        val onDelete: String,
        val onUpdate: String,
    )

    private data class IndexContract(
        val columns: List<String>,
        val unique: Boolean,
    )

    companion object {
        private const val CAPTURE_FILE_OPERATION_TABLE = "capture_file_operations"

        private val EXPECTED_TABLES = setOf(
            "shoots",
            "shoot_poses",
            "shoot_sessions",
            "capture_attempts",
            "private_capture_outputs",
            "capture_confirmation_receipts",
            "capture_export_outboxes",
            "capture_export_outputs",
            "reference_import_intents",
            "reference_import_file_operations",
            CAPTURE_FILE_OPERATION_TABLE,
        )

        private val EXPECTED_COLUMNS = listOf(
            ColumnContract("command_token", "TEXT", true),
            ColumnContract("burst_ordinal", "INTEGER", true),
            ColumnContract("relative_final_path", "TEXT", true),
            ColumnContract("relative_temp_path", "TEXT", true),
            ColumnContract("relative_quarantine_path", "TEXT", true),
            ColumnContract("stage", "TEXT", true),
            ColumnContract("byte_count", "INTEGER", false),
            ColumnContract("sha256", "TEXT", false),
            ColumnContract("captured_at_epoch_millis", "INTEGER", false),
            ColumnContract("last_failure_code", "TEXT", false),
            ColumnContract("reconciliation_required", "INTEGER", true),
            ColumnContract("created_at_epoch_millis", "INTEGER", true),
            ColumnContract("updated_at_epoch_millis", "INTEGER", true),
        )

        private fun indexes(vararg values: Pair<String, IndexContract>): Map<String, IndexContract> =
            mapOf(*values)

        private fun index(vararg columns: String, unique: Boolean = false) =
            IndexContract(columns.toList(), unique)

        private val EXPECTED_INDEXES = mapOf(
            "shoots" to indexes("index_shoots_lifecycle_state" to index("lifecycle_state")),
            "shoot_poses" to indexes(
                "index_shoot_poses_shoot_id_pose_id" to index("shoot_id", "pose_id", unique = true),
            ),
            "shoot_sessions" to indexes(
                "index_shoot_sessions_shoot_id_lifecycle_state" to
                    index("shoot_id", "lifecycle_state"),
            ),
            "capture_attempts" to indexes(
                "index_capture_attempts_session_id_attempt_number" to
                    index("session_id", "attempt_number", unique = true),
                "index_capture_attempts_lifecycle_state" to index("lifecycle_state"),
            ),
            "private_capture_outputs" to indexes(
                "index_private_capture_outputs_durability_state" to index("durability_state"),
            ),
            "capture_confirmation_receipts" to emptyMap(),
            "capture_export_outboxes" to indexes(
                "index_capture_export_outboxes_lifecycle_state" to index("lifecycle_state"),
            ),
            "capture_export_outputs" to indexes(
                "index_capture_export_outputs_claim_token" to index("claim_token", unique = true),
                "index_capture_export_outputs_lifecycle_state" to index("lifecycle_state"),
                "index_capture_export_outputs_deletion_generation" to index("deletion_generation"),
            ),
            "reference_import_intents" to indexes(
                "index_reference_import_intents_shoot_id_pose_id" to
                    index("shoot_id", "pose_id", unique = true),
                "index_reference_import_intents_lifecycle_state" to index("lifecycle_state"),
            ),
            "reference_import_file_operations" to indexes(
                "index_reference_import_file_operations_stage" to index("stage"),
                "index_reference_import_file_operations_reconciliation_required" to
                    index("reconciliation_required"),
            ),
            CAPTURE_FILE_OPERATION_TABLE to indexes(
                "index_capture_file_operations_stage" to index("stage"),
                "index_capture_file_operations_reconciliation_required" to
                    index("reconciliation_required"),
            ),
        )

        private val HISTORICAL_SCHEMA_HASHES = mapOf(
            1 to "e5eb94f4ff96944cc9de1aa5c2f6e8e326ba5caaa224c46f3247056cb1c33ab8",
            2 to "0c9ea87ccbf1c57a404d4302ca1d8a7713ec934663dd6000df34766487929bbd",
            3 to "53f30c71d5bad0b4efc66075346ffaccc6e69402ae4a904b7c94c5f45ff3ae25",
        )
    }
}
