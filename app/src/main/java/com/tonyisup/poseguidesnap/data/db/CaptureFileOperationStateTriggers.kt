package com.tonyisup.poseguidesnap.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

internal object CaptureFileOperationStateTriggers {
    const val ERROR_MESSAGE = "capture file operation state is invalid"

    val definitions: List<Definition> = listOf(
        definition(
            name = "trigger_capture_file_operations_state_insert",
            event = Event.INSERT,
        ),
        definition(
            name = "trigger_capture_file_operations_state_update",
            event = Event.UPDATE,
        ),
    )

    private fun definition(
        name: String,
        event: Event,
    ): Definition {
        val updateRules = if (event == Event.UPDATE) {
            """
                OR NEW.`command_token` != OLD.`command_token`
                OR NEW.`burst_ordinal` != OLD.`burst_ordinal`
                OR NEW.`relative_final_path` != OLD.`relative_final_path`
                OR NEW.`relative_temp_path` != OLD.`relative_temp_path`
                OR NEW.`relative_quarantine_path` != OLD.`relative_quarantine_path`
                OR NEW.`created_at_epoch_millis` != OLD.`created_at_epoch_millis`
                OR NEW.`updated_at_epoch_millis` <= OLD.`updated_at_epoch_millis`
            """.trimIndent()
        } else {
            ""
        }
        val sql = """
            CREATE TRIGGER IF NOT EXISTS `$name`
            BEFORE ${event.sql} ON `capture_file_operations`
            FOR EACH ROW
            WHEN typeof(NEW.`command_token`) != 'text'
                OR typeof(NEW.`burst_ordinal`) != 'integer'
                OR typeof(NEW.`relative_final_path`) != 'text'
                OR typeof(NEW.`relative_temp_path`) != 'text'
                OR typeof(NEW.`relative_quarantine_path`) != 'text'
                OR typeof(NEW.`stage`) != 'text'
                OR (NEW.`byte_count` IS NOT NULL AND typeof(NEW.`byte_count`) != 'integer')
                OR (NEW.`sha256` IS NOT NULL AND typeof(NEW.`sha256`) != 'text')
                OR (
                    NEW.`captured_at_epoch_millis` IS NOT NULL
                    AND typeof(NEW.`captured_at_epoch_millis`) != 'integer'
                )
                OR (
                    NEW.`last_failure_code` IS NOT NULL
                    AND typeof(NEW.`last_failure_code`) != 'text'
                )
                OR typeof(NEW.`reconciliation_required`) != 'integer'
                OR NEW.`reconciliation_required` NOT IN (0, 1)
                OR typeof(NEW.`created_at_epoch_millis`) != 'integer'
                OR NEW.`created_at_epoch_millis` < 0
                OR typeof(NEW.`updated_at_epoch_millis`) != 'integer'
                OR NEW.`updated_at_epoch_millis` < NEW.`created_at_epoch_millis`
                OR NEW.`stage` NOT IN (
                    'EXPECTING_RESERVATION',
                    'WRITING_TEMP',
                    'TEMP_SYNCED',
                    'FINAL_RENAME_PENDING_SYNC',
                    'FINAL_DURABLE',
                    'CLEANUP_REQUIRED',
                    'CLEANUP_PENDING_SYNC',
                    'CLEANED_DURABLE',
                    'QUARANTINE_REQUIRED',
                    'QUARANTINE_PENDING_SYNC',
                    'QUARANTINE_DURABLE'
                )
                OR NOT (
                    (
                        NEW.`byte_count` IS NULL
                        AND NEW.`sha256` IS NULL
                        AND NEW.`captured_at_epoch_millis` IS NULL
                    )
                    OR (
                        NEW.`byte_count` IS NOT NULL
                        AND NEW.`sha256` IS NOT NULL
                        AND NEW.`captured_at_epoch_millis` IS NOT NULL
                        AND NEW.`byte_count` > 0
                        AND length(NEW.`sha256`) = 64
                        AND NEW.`sha256` NOT GLOB '*[^0-9a-f]*'
                    )
                )
                OR (
                    NEW.`captured_at_epoch_millis` IS NOT NULL
                    AND (
                        NEW.`captured_at_epoch_millis` < NEW.`created_at_epoch_millis`
                        OR NEW.`captured_at_epoch_millis` > NEW.`updated_at_epoch_millis`
                    )
                )
                OR (
                    NEW.`stage` IN (
                        'EXPECTING_RESERVATION',
                        'WRITING_TEMP',
                        'CLEANED_DURABLE'
                    )
                    AND (
                        NEW.`byte_count` IS NOT NULL
                        OR NEW.`sha256` IS NOT NULL
                        OR NEW.`captured_at_epoch_millis` IS NOT NULL
                    )
                )
                OR (
                    NEW.`stage` IN (
                        'TEMP_SYNCED',
                        'FINAL_RENAME_PENDING_SYNC',
                        'FINAL_DURABLE',
                        'QUARANTINE_REQUIRED',
                        'QUARANTINE_PENDING_SYNC',
                        'QUARANTINE_DURABLE'
                    )
                    AND (
                        NEW.`byte_count` IS NULL
                        OR NEW.`sha256` IS NULL
                        OR NEW.`captured_at_epoch_millis` IS NULL
                    )
                )
                OR (
                    NEW.`last_failure_code` IS NOT NULL
                    AND NEW.`last_failure_code` NOT IN (
                        'RESERVATION_FAILED',
                        'WRITE_FAILED',
                        'FILE_SYNC_FAILED',
                        'RENAME_FAILED',
                        'DIRECTORY_SYNC_FAILED',
                        'DELETE_FAILED',
                        'STATE_MISMATCH',
                        'EVIDENCE_MISMATCH'
                    )
                )
                OR (
                    (NEW.`reconciliation_required` = 0 AND NEW.`last_failure_code` IS NOT NULL)
                    OR (NEW.`reconciliation_required` = 1 AND NEW.`last_failure_code` IS NULL)
                )
                ${updateRules.prependIndent("    ").trimEnd()}
            BEGIN
                SELECT RAISE(ABORT, '$ERROR_MESSAGE');
            END
        """.trimIndent().replace("\n                \n", "\n")
        return Definition(name = name, event = event, sql = sql)
    }

    fun install(database: SupportSQLiteDatabase) {
        definitions.forEach { definition -> database.execSQL(definition.sql) }
    }

    data class Definition(
        val name: String,
        val event: Event,
        val sql: String,
    ) {
        override fun toString(): String = "CaptureFileOperationStateTriggers.Definition(redacted)"
    }

    enum class Event(val sql: String) {
        INSERT("INSERT"),
        UPDATE("UPDATE"),
    }
}
