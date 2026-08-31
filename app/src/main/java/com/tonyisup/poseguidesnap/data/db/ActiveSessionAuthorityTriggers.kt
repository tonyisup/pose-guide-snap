package com.tonyisup.poseguidesnap.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

internal object ActiveSessionAuthorityTriggers {
    const val ERROR_MESSAGE = "one active shoot session per shoot"

    val definitions: List<Definition> = listOf(
        definition(
            name = "trigger_shoot_sessions_one_active_insert",
            event = Event.INSERT,
        ),
        definition(
            name = "trigger_shoot_sessions_one_active_update",
            event = Event.UPDATE,
        ),
    )

    fun install(database: SupportSQLiteDatabase) {
        definitions.forEach { definition -> database.execSQL(definition.sql) }
    }

    private fun definition(
        name: String,
        event: Event,
    ): Definition = Definition(
        name = name,
        table = TABLE,
        event = event,
        sql = """
            CREATE TRIGGER IF NOT EXISTS `$name`
            BEFORE ${event.sql} ON `$TABLE`
            FOR EACH ROW
            WHEN NEW.`lifecycle_state` = '$ACTIVE' AND EXISTS (
                SELECT 1 FROM `$TABLE` AS existing_session
                WHERE existing_session.`shoot_id` = NEW.`shoot_id`
                  AND existing_session.`session_id` != NEW.`session_id`
                  AND existing_session.`lifecycle_state` = '$ACTIVE'
            )
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
    ) {
        override fun toString(): String = "ActiveSessionAuthorityTriggers.Definition(redacted)"
    }

    enum class Event(val sql: String) {
        INSERT("INSERT"),
        UPDATE("UPDATE"),
    }

    private const val TABLE = "shoot_sessions"
    private const val ACTIVE = "ACTIVE"
}
