package com.tonyisup.poseguidesnap.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDatabaseV2SchemaArtifactTest {
    private val schema by lazy {
        val file = File("schemas/com.tonyisup.poseguidesnap.data.db.AppDatabase/2.json")
        assertTrue("Room V2 schema artifact must exist at ${file.path}", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    @Test
    fun versionTwoAddsTheExactDurableReferenceImportAuthoritySurface() {
        assertEquals(2, schema["version"]!!.jsonPrimitive.content.toInt())
        val entities = entitiesByName()
        assertEquals(EXPECTED_TABLES, entities.keys)

        val poseColumns = entities.getValue("shoot_poses")["fields"]!!
            .jsonArray
            .map { field -> field.jsonObject["columnName"]!!.jsonPrimitive.content }
            .toSet()
        assertTrue("V2 poses must persist deterministic landmark evidence", "landmark_payload" in poseColumns)
        assertTrue("V2 poses must persist coordinate metadata", "coordinate_metadata" in poseColumns)

        val operation = entities.getValue(FILE_OPERATION_TABLE)
        val fields = operation["fields"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            EXPECTED_FILE_OPERATION_COLUMNS,
            fields.map { field -> field["columnName"]!!.jsonPrimitive.content },
        )
        assertEquals(
            EXPECTED_FILE_OPERATION_AFFINITIES,
            fields.associate { field ->
                field["columnName"]!!.jsonPrimitive.content to field["affinity"]!!.jsonPrimitive.content
            },
        )
        assertEquals(
            EXPECTED_FILE_OPERATION_NULLABLE_COLUMNS,
            fields.mapNotNull { field ->
                val notNull = field["notNull"]?.jsonPrimitive?.content?.toBoolean() ?: false
                field["columnName"]!!.jsonPrimitive.content.takeUnless { notNull }
            }.toSet(),
        )
        assertEquals(
            listOf("import_token"),
            operation["primaryKey"]!!.jsonObject.stringArray("columnNames"),
        )
    }

    @Test
    fun fileLedgerPinsRestrictiveOwnershipAndExactIndexes() {
        val operation = entitiesByName().getValue(FILE_OPERATION_TABLE)
        val foreignKeys = operation["foreignKeys"]!!.jsonArray.map { foreignKey ->
            val value = foreignKey.jsonObject
            ForeignKeyContract(
                table = value["table"]!!.jsonPrimitive.content,
                columns = value.stringArray("columns"),
                referencedColumns = value.stringArray("referencedColumns"),
                onDelete = value["onDelete"]!!.jsonPrimitive.content,
                onUpdate = value["onUpdate"]!!.jsonPrimitive.content,
            )
        }.toSet()
        assertEquals(
            setOf(
                ForeignKeyContract(
                    table = "reference_import_intents",
                    columns = listOf("import_token"),
                    referencedColumns = listOf("import_token"),
                    onDelete = "RESTRICT",
                    onUpdate = "NO ACTION",
                ),
            ),
            foreignKeys,
        )

        val indexes = operation["indices"]!!.jsonArray.map { index ->
            val value = index.jsonObject
            IndexContract(
                name = value["name"]!!.jsonPrimitive.content,
                columns = value.stringArray("columnNames"),
                unique = value["unique"]!!.jsonPrimitive.content.toBoolean(),
            )
        }.toSet()
        assertEquals(
            setOf(
                IndexContract(
                    name = "index_reference_import_file_operations_stage",
                    columns = listOf("stage"),
                    unique = false,
                ),
                IndexContract(
                    name = "index_reference_import_file_operations_reconciliation_required",
                    columns = listOf("reconciliation_required"),
                    unique = false,
                ),
            ),
            indexes,
        )
    }

    private fun entitiesByName(): Map<String, JsonObject> =
        schema["entities"]!!.jsonArray.associate { entity ->
            val value = entity.jsonObject
            value["tableName"]!!.jsonPrimitive.content to value
        }

    private fun JsonObject.stringArray(name: String): List<String> =
        (getValue(name) as JsonArray).map { it.jsonPrimitive.content }

    private data class ForeignKeyContract(
        val table: String,
        val columns: List<String>,
        val referencedColumns: List<String>,
        val onDelete: String,
        val onUpdate: String,
    )

    private data class IndexContract(
        val name: String,
        val columns: List<String>,
        val unique: Boolean,
    )

    companion object {
        private const val FILE_OPERATION_TABLE = "reference_import_file_operations"
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
            FILE_OPERATION_TABLE,
        )
        private val EXPECTED_FILE_OPERATION_COLUMNS = listOf(
            "import_token",
            "relative_asset_path",
            "relative_temp_path",
            "relative_quarantine_path",
            "stage",
            "byte_count",
            "sha256",
            "last_failure_code",
            "reconciliation_required",
            "created_at_epoch_millis",
            "updated_at_epoch_millis",
        )
        private val EXPECTED_FILE_OPERATION_AFFINITIES = mapOf(
            "import_token" to "TEXT",
            "relative_asset_path" to "TEXT",
            "relative_temp_path" to "TEXT",
            "relative_quarantine_path" to "TEXT",
            "stage" to "TEXT",
            "byte_count" to "INTEGER",
            "sha256" to "TEXT",
            "last_failure_code" to "TEXT",
            "reconciliation_required" to "INTEGER",
            "created_at_epoch_millis" to "INTEGER",
            "updated_at_epoch_millis" to "INTEGER",
        )
        private val EXPECTED_FILE_OPERATION_NULLABLE_COLUMNS = setOf(
            "byte_count",
            "sha256",
            "last_failure_code",
        )
    }
}
