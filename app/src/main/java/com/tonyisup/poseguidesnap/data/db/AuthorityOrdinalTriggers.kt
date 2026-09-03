package com.tonyisup.poseguidesnap.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

internal object AuthorityOrdinalTriggers {
    const val MIN_BURST_ORDINAL = 0
    const val MAX_BURST_ORDINAL = 2
    const val ERROR_MESSAGE = "burst_ordinal must be between 0 and 2"

    val definitions: List<Definition> = listOf(
        definition(
            name = "trigger_private_capture_outputs_burst_ordinal_insert",
            table = "private_capture_outputs",
            event = Event.INSERT,
        ),
        definition(
            name = "trigger_private_capture_outputs_burst_ordinal_update",
            table = "private_capture_outputs",
            event = Event.UPDATE,
        ),
        definition(
            name = "trigger_capture_export_outputs_burst_ordinal_insert",
            table = "capture_export_outputs",
            event = Event.INSERT,
        ),
        definition(
            name = "trigger_capture_export_outputs_burst_ordinal_update",
            table = "capture_export_outputs",
            event = Event.UPDATE,
        ),
        definition(
            name = "trigger_capture_file_operations_burst_ordinal_insert",
            table = "capture_file_operations",
            event = Event.INSERT,
        ),
        definition(
            name = "trigger_capture_file_operations_burst_ordinal_update",
            table = "capture_file_operations",
            event = Event.UPDATE,
        ),
    )

    fun install(database: SupportSQLiteDatabase) {
        definitions.forEach { definition -> database.execSQL(definition.sql) }
    }

    private fun definition(
        name: String,
        table: String,
        event: Event,
    ): Definition = Definition(
        name = name,
        table = table,
        event = event,
        sql = """
            CREATE TRIGGER IF NOT EXISTS `$name`
            BEFORE ${event.sql} ON `$table`
            FOR EACH ROW
            WHEN typeof(NEW.`burst_ordinal`) != 'integer' OR
                NEW.`burst_ordinal` NOT BETWEEN $MIN_BURST_ORDINAL AND $MAX_BURST_ORDINAL
            BEGIN
                SELECT RAISE(ABORT, '$ERROR_MESSAGE');
            END
        """.trimIndent(),
    )

    data class Definition(
        val name: String,
        val table: String,
        val event: Event,
        val sql: String,
    )

    enum class Event(val sql: String) {
        INSERT("INSERT"),
        UPDATE("UPDATE"),
    }
}
