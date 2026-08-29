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

class AppDatabaseSchemaArtifactTest {
    private val schema: JsonObject by lazy {
        val file = File("schemas/com.tonyisup.poseguidesnap.data.db.AppDatabase/1.json")
        assertTrue("Room V1 schema artifact must exist at ${file.path}", file.isFile)
        Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    @Test
    fun versionOneArtifactDefinesTheCompleteAuthoritySurface() {
        assertEquals(1, schema["version"]!!.jsonPrimitive.content.toInt())

        val entities = schema["entities"]!!.jsonArray.associateBy { entity ->
            entity.jsonObject["tableName"]!!.jsonPrimitive.content
        }
        assertEquals(REQUIRED_COLUMNS.keys, entities.keys)

        REQUIRED_COLUMNS.forEach { (tableName, expectedColumns) ->
            val entity = entities.getValue(tableName).jsonObject
            val actualColumns = entity["fields"]!!.jsonArray.map { field ->
                field.jsonObject["columnName"]!!.jsonPrimitive.content
            }.toSet()
            assertEquals("columns for $tableName", expectedColumns, actualColumns)
            val actualNullableColumns = entity["fields"]!!.jsonArray.mapNotNull { field ->
                val value = field.jsonObject
                val isNotNull = value["notNull"]?.jsonPrimitive?.content?.toBoolean() ?: false
                value["columnName"]!!.jsonPrimitive.content.takeUnless { isNotNull }
            }.toSet()
            assertEquals(
                "nullable columns for $tableName",
                REQUIRED_NULLABLE_COLUMNS[tableName].orEmpty(),
                actualNullableColumns,
            )
            assertEquals(
                "primary key for $tableName",
                REQUIRED_PRIMARY_KEYS.getValue(tableName),
                entity["primaryKey"]!!.jsonObject.stringArray("columnNames"),
            )
        }
    }

    @Test
    fun versionOneArtifactPinsOwnershipForeignKeysToRestrictAndNoAction() {
        val entities = entitiesByName()
        REQUIRED_COLUMNS.keys.forEach { tableName ->
            val expectedForeignKeys = REQUIRED_FOREIGN_KEYS[tableName].orEmpty()
            val actual = entities.getValue(tableName)["foreignKeys"]
                ?.jsonArray
                ?.map { foreignKey ->
                    val value = foreignKey.jsonObject
                    ForeignKeyContract(
                        table = value["table"]!!.jsonPrimitive.content,
                        columns = value.stringArray("columns"),
                        referencedColumns = value.stringArray("referencedColumns"),
                        onDelete = value["onDelete"]!!.jsonPrimitive.content,
                        onUpdate = value["onUpdate"]!!.jsonPrimitive.content,
                    )
                }
                ?.toSet()
                .orEmpty()
            assertEquals("foreign keys for $tableName", expectedForeignKeys, actual)
        }
    }

    @Test
    fun versionOneArtifactPinsWorkflowIndexesAndUniqueness() {
        val entities = entitiesByName()
        REQUIRED_COLUMNS.keys.forEach { tableName ->
            val expectedIndexes = REQUIRED_INDEXES[tableName].orEmpty()
            val actual = entities.getValue(tableName)["indices"]
                ?.jsonArray
                ?.map { index ->
                    val value = index.jsonObject
                    IndexContract(
                        name = value["name"]!!.jsonPrimitive.content,
                        columns = value.stringArray("columnNames"),
                        unique = value["unique"]!!.jsonPrimitive.content.toBoolean(),
                    )
                }
                ?.toSet()
                .orEmpty()
            assertEquals("indexes for $tableName", expectedIndexes, actual)
        }
    }

    @Test
    fun versionOneArtifactPinsEveryColumnAffinityExactly() {
        val entities = entitiesByName()
        assertEquals(REQUIRED_AFFINITIES.keys, entities.keys)

        REQUIRED_AFFINITIES.forEach { (tableName, expectedAffinities) ->
            val actualAffinities = entities.getValue(tableName)["fields"]!!
                .jsonArray
                .associate { field ->
                    val value = field.jsonObject
                    value["columnName"]!!.jsonPrimitive.content to
                        value["affinity"]!!.jsonPrimitive.content
                }
            assertEquals("affinities for $tableName", expectedAffinities, actualAffinities)
        }
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
        private val REQUIRED_COLUMNS = mapOf(
            "shoots" to setOf(
                "shoot_id", "name", "created_at_epoch_millis", "updated_at_epoch_millis",
                "lifecycle_state", "deletion_generation",
            ),
            "shoot_poses" to setOf(
                "shoot_id", "pose_index", "pose_id", "label", "reference_asset_path",
                "mirror_allowed", "validation_state", "detector_metadata", "model_metadata",
                "preprocessing_metadata",
            ),
            "shoot_sessions" to setOf(
                "session_id", "shoot_id", "current_pose_index", "next_attempt_number",
                "lifecycle_state", "created_at_epoch_millis", "updated_at_epoch_millis",
            ),
            "capture_attempts" to setOf(
                "command_token", "session_id", "pose_id", "pose_index", "attempt_number",
                "trigger_type", "lifecycle_state", "reconciliation_required",
                "captured_deletion_generation", "created_at_epoch_millis",
                "updated_at_epoch_millis", "confirmed_at_epoch_millis",
            ),
            "private_capture_outputs" to setOf(
                "command_token", "burst_ordinal", "relative_path", "byte_count",
                "durability_state", "captured_at_epoch_millis", "integrity_metadata",
            ),
            "capture_confirmation_receipts" to setOf(
                "command_token", "from_pose_index", "to_pose_index",
                "applied_deletion_generation", "applied_at_epoch_millis",
            ),
            "capture_export_outboxes" to setOf(
                "command_token", "lifecycle_state", "created_at_epoch_millis",
                "updated_at_epoch_millis", "retry_metadata",
            ),
            "capture_export_outputs" to setOf(
                "command_token", "burst_ordinal", "target_collection_uri", "target_volume",
                "intended_display_name", "intended_relative_path", "intended_mime_type",
                "lifecycle_state", "claim_token", "media_uri_string", "ambiguity_state",
                "deletion_generation", "created_at_epoch_millis", "updated_at_epoch_millis",
            ),
        )

        private val REQUIRED_AFFINITIES = mapOf(
            "shoots" to mapOf(
                "shoot_id" to "TEXT",
                "name" to "TEXT",
                "created_at_epoch_millis" to "INTEGER",
                "updated_at_epoch_millis" to "INTEGER",
                "lifecycle_state" to "TEXT",
                "deletion_generation" to "INTEGER",
            ),
            "shoot_poses" to mapOf(
                "shoot_id" to "TEXT",
                "pose_index" to "INTEGER",
                "pose_id" to "TEXT",
                "label" to "TEXT",
                "reference_asset_path" to "TEXT",
                "mirror_allowed" to "INTEGER",
                "validation_state" to "TEXT",
                "detector_metadata" to "TEXT",
                "model_metadata" to "TEXT",
                "preprocessing_metadata" to "TEXT",
            ),
            "shoot_sessions" to mapOf(
                "session_id" to "TEXT",
                "shoot_id" to "TEXT",
                "current_pose_index" to "INTEGER",
                "next_attempt_number" to "INTEGER",
                "lifecycle_state" to "TEXT",
                "created_at_epoch_millis" to "INTEGER",
                "updated_at_epoch_millis" to "INTEGER",
            ),
            "capture_attempts" to mapOf(
                "command_token" to "TEXT",
                "session_id" to "TEXT",
                "pose_id" to "TEXT",
                "pose_index" to "INTEGER",
                "attempt_number" to "INTEGER",
                "trigger_type" to "TEXT",
                "lifecycle_state" to "TEXT",
                "reconciliation_required" to "INTEGER",
                "captured_deletion_generation" to "INTEGER",
                "created_at_epoch_millis" to "INTEGER",
                "updated_at_epoch_millis" to "INTEGER",
                "confirmed_at_epoch_millis" to "INTEGER",
            ),
            "private_capture_outputs" to mapOf(
                "command_token" to "TEXT",
                "burst_ordinal" to "INTEGER",
                "relative_path" to "TEXT",
                "byte_count" to "INTEGER",
                "durability_state" to "TEXT",
                "captured_at_epoch_millis" to "INTEGER",
                "integrity_metadata" to "TEXT",
            ),
            "capture_confirmation_receipts" to mapOf(
                "command_token" to "TEXT",
                "from_pose_index" to "INTEGER",
                "to_pose_index" to "INTEGER",
                "applied_deletion_generation" to "INTEGER",
                "applied_at_epoch_millis" to "INTEGER",
            ),
            "capture_export_outboxes" to mapOf(
                "command_token" to "TEXT",
                "lifecycle_state" to "TEXT",
                "created_at_epoch_millis" to "INTEGER",
                "updated_at_epoch_millis" to "INTEGER",
                "retry_metadata" to "TEXT",
            ),
            "capture_export_outputs" to mapOf(
                "command_token" to "TEXT",
                "burst_ordinal" to "INTEGER",
                "target_collection_uri" to "TEXT",
                "target_volume" to "TEXT",
                "intended_display_name" to "TEXT",
                "intended_relative_path" to "TEXT",
                "intended_mime_type" to "TEXT",
                "lifecycle_state" to "TEXT",
                "claim_token" to "TEXT",
                "media_uri_string" to "TEXT",
                "ambiguity_state" to "TEXT",
                "deletion_generation" to "INTEGER",
                "created_at_epoch_millis" to "INTEGER",
                "updated_at_epoch_millis" to "INTEGER",
            ),
        )

        private val REQUIRED_PRIMARY_KEYS = mapOf(
            "shoots" to listOf("shoot_id"),
            "shoot_poses" to listOf("shoot_id", "pose_index"),
            "shoot_sessions" to listOf("session_id"),
            "capture_attempts" to listOf("command_token"),
            "private_capture_outputs" to listOf("command_token", "burst_ordinal"),
            "capture_confirmation_receipts" to listOf("command_token"),
            "capture_export_outboxes" to listOf("command_token"),
            "capture_export_outputs" to listOf("command_token", "burst_ordinal"),
        )

        private val REQUIRED_NULLABLE_COLUMNS = mapOf(
            "shoot_poses" to setOf(
                "reference_asset_path",
                "detector_metadata",
                "model_metadata",
                "preprocessing_metadata",
            ),
            "capture_attempts" to setOf("confirmed_at_epoch_millis"),
            "private_capture_outputs" to setOf("integrity_metadata"),
            "capture_confirmation_receipts" to setOf("to_pose_index"),
            "capture_export_outboxes" to setOf("retry_metadata"),
            "capture_export_outputs" to setOf("claim_token", "media_uri_string"),
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
            "shoots" to setOf(
                index("index_shoots_lifecycle_state", "lifecycle_state"),
            ),
            "shoot_poses" to setOf(
                index("index_shoot_poses_shoot_id_pose_id", "shoot_id", "pose_id", unique = true),
            ),
            "shoot_sessions" to setOf(
                index("index_shoot_sessions_shoot_id_lifecycle_state", "shoot_id", "lifecycle_state"),
            ),
            "capture_attempts" to setOf(
                index(
                    "index_capture_attempts_session_id_attempt_number",
                    "session_id",
                    "attempt_number",
                    unique = true,
                ),
                index("index_capture_attempts_lifecycle_state", "lifecycle_state"),
            ),
            "private_capture_outputs" to setOf(
                index("index_private_capture_outputs_durability_state", "durability_state"),
            ),
            "capture_export_outboxes" to setOf(
                index("index_capture_export_outboxes_lifecycle_state", "lifecycle_state"),
            ),
            "capture_export_outputs" to setOf(
                index("index_capture_export_outputs_claim_token", "claim_token", unique = true),
                index("index_capture_export_outputs_lifecycle_state", "lifecycle_state"),
                index("index_capture_export_outputs_deletion_generation", "deletion_generation"),
            ),
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
