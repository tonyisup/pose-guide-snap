package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.data.db.ReferenceImportIntentEntity
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDatabaseV3SchemaArtifactTest {
    private val v3Schema by lazy { schemaArtifact(3) }
    private val v2Schema by lazy { schemaArtifact(2) }

    @Test
    fun versionThreePinsVersionAndExactTableSet() {
        assertEquals(3, v3Schema["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(EXPECTED_TABLES, v3Schema.entitiesByName().keys)
    }

    @Test
    fun versionThreeIntentPinsExactColumnsPrimaryKeyForeignKeyAndIndexes() {
        val intent = v3Schema.entitiesByName().getValue(INTENT_TABLE)

        assertEquals(EXPECTED_INTENT_COLUMNS, intent.columnContracts())
        assertEquals(listOf("import_token"), intent["primaryKey"]!!.jsonObject.stringArray("columnNames"))
        assertEquals(
            setOf(foreignKey("shoots", "shoot_id", "shoot_id")),
            intent.foreignKeyContracts(),
        )
        assertEquals(
            setOf(
                index(
                    "index_reference_import_intents_shoot_id_pose_id",
                    "shoot_id",
                    "pose_id",
                    unique = true,
                ),
                index("index_reference_import_intents_lifecycle_state", "lifecycle_state"),
            ),
            intent.indexContracts(),
        )
        assertFalse("V3 intent columns must not contain pose_index", intent.columnNames().contains("pose_index"))
        assertFalse(
            "V3 intent indexes must not contain pose_index",
            intent.indexContracts().any { contract -> "pose_index" in contract.columns },
        )
    }

    @Test
    fun versionThreeChildLedgerPinsUnchangedExactContract() {
        val operation = v3Schema.entitiesByName().getValue(FILE_OPERATION_TABLE)

        assertEquals(EXPECTED_FILE_OPERATION_COLUMNS, operation.columnContracts())
        assertEquals(listOf("import_token"), operation["primaryKey"]!!.jsonObject.stringArray("columnNames"))
        assertEquals(
            setOf(foreignKey(INTENT_TABLE, "import_token", "import_token")),
            operation.foreignKeyContracts(),
        )
        assertEquals(
            setOf(
                index("index_reference_import_file_operations_stage", "stage"),
                index(
                    "index_reference_import_file_operations_reconciliation_required",
                    "reconciliation_required",
                ),
            ),
            operation.indexContracts(),
        )
    }

    @Test
    fun versionTwoHistoricalControlStillContainsPoseIndexAndItsUniqueIndex() {
        assertEquals(2, v2Schema["version"]!!.jsonPrimitive.content.toInt())
        val intent = v2Schema.entitiesByName().getValue(INTENT_TABLE)

        assertTrue("V2 intent must retain historical pose_index", "pose_index" in intent.columnNames())
        assertTrue(
            "V2 intent must retain the historical unique pose_index index",
            index(
                "index_reference_import_intents_shoot_id_pose_index",
                "shoot_id",
                "pose_index",
                unique = true,
            ) in intent.indexContracts(),
        )
    }

    @Test
    fun referenceImportIntentDoesNotDuplicateMutablePlaylistOrder() {
        val declaredInstanceFields = ReferenceImportIntentEntity::class.java.declaredFields
            .filterNot { field -> field.isSynthetic || Modifier.isStatic(field.modifiers) }
            .map { field -> field.name }
            .toSet()

        assertTrue(
            "Reference import intent must retain immutable import and pose identity fields",
            declaredInstanceFields.containsAll(setOf("importToken", "shootId", "poseId")),
        )
        assertFalse(
            "Reference import intent must not duplicate mutable playlist order via poseIndex; " +
                "declared instance fields were $declaredInstanceFields",
            "poseIndex" in declaredInstanceFields,
        )
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

    private fun JsonObject.columnNames(): List<String> =
        getValue("fields").jsonArray.map { field ->
            field.jsonObject["columnName"]!!.jsonPrimitive.content
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

    private fun JsonObject.indexContracts(): Set<IndexContract> =
        get("indices")?.jsonArray?.map { index ->
            val value = index.jsonObject
            IndexContract(
                name = value["name"]!!.jsonPrimitive.content,
                columns = value.stringArray("columnNames"),
                unique = value["unique"]!!.jsonPrimitive.content.toBoolean(),
            )
        }?.toSet().orEmpty()

    private fun JsonObject.stringArray(name: String): List<String> =
        (getValue(name) as JsonArray).map { it.jsonPrimitive.content }

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
        val name: String,
        val columns: List<String>,
        val unique: Boolean,
    )

    companion object {
        private const val INTENT_TABLE = "reference_import_intents"
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
            INTENT_TABLE,
            FILE_OPERATION_TABLE,
        )

        private val EXPECTED_INTENT_COLUMNS = listOf(
            ColumnContract("import_token", "TEXT", true),
            ColumnContract("shoot_id", "TEXT", true),
            ColumnContract("pose_id", "TEXT", true),
            ColumnContract("relative_asset_path", "TEXT", true),
            ColumnContract("lifecycle_state", "TEXT", true),
            ColumnContract("created_at_epoch_millis", "INTEGER", true),
            ColumnContract("updated_at_epoch_millis", "INTEGER", true),
            ColumnContract("asset_ready_at_epoch_millis", "INTEGER", false),
            ColumnContract("terminal_at_epoch_millis", "INTEGER", false),
        )

        private val EXPECTED_FILE_OPERATION_COLUMNS = listOf(
            ColumnContract("import_token", "TEXT", true),
            ColumnContract("relative_asset_path", "TEXT", true),
            ColumnContract("relative_temp_path", "TEXT", true),
            ColumnContract("relative_quarantine_path", "TEXT", true),
            ColumnContract("stage", "TEXT", true),
            ColumnContract("byte_count", "INTEGER", false),
            ColumnContract("sha256", "TEXT", false),
            ColumnContract("last_failure_code", "TEXT", false),
            ColumnContract("reconciliation_required", "INTEGER", true),
            ColumnContract("created_at_epoch_millis", "INTEGER", true),
            ColumnContract("updated_at_epoch_millis", "INTEGER", true),
        )

        private fun index(
            name: String,
            vararg columns: String,
            unique: Boolean = false,
        ) = IndexContract(name, columns.toList(), unique)

        private fun foreignKey(
            table: String,
            column: String,
            referencedColumn: String,
        ) = ForeignKeyContract(
            table = table,
            columns = listOf(column),
            referencedColumns = listOf(referencedColumn),
            onDelete = "RESTRICT",
            onUpdate = "NO ACTION",
        )
    }
}
