package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.data.db.AuthorityOrdinalTriggers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorityOrdinalTriggersTest {
    @Test
    fun productionDefinitionsCoverInsertAndUpdateForAllOrdinalTables() {
        val actual = AuthorityOrdinalTriggers.definitions.map { definition ->
            Triple(definition.name, definition.table, definition.event)
        }.toSet()

        assertEquals(
            setOf(
                Triple(
                    "trigger_private_capture_outputs_burst_ordinal_insert",
                    "private_capture_outputs",
                    AuthorityOrdinalTriggers.Event.INSERT,
                ),
                Triple(
                    "trigger_private_capture_outputs_burst_ordinal_update",
                    "private_capture_outputs",
                    AuthorityOrdinalTriggers.Event.UPDATE,
                ),
                Triple(
                    "trigger_capture_export_outputs_burst_ordinal_insert",
                    "capture_export_outputs",
                    AuthorityOrdinalTriggers.Event.INSERT,
                ),
                Triple(
                    "trigger_capture_export_outputs_burst_ordinal_update",
                    "capture_export_outputs",
                    AuthorityOrdinalTriggers.Event.UPDATE,
                ),
                Triple(
                    "trigger_capture_file_operations_burst_ordinal_insert",
                    "capture_file_operations",
                    AuthorityOrdinalTriggers.Event.INSERT,
                ),
                Triple(
                    "trigger_capture_file_operations_burst_ordinal_update",
                    "capture_file_operations",
                    AuthorityOrdinalTriggers.Event.UPDATE,
                ),
            ),
            actual,
        )
    }

    @Test
    fun productionSqlIsIdempotentAndRejectsNonIntegerOrOutsideTheStableRange() {
        assertEquals(0, AuthorityOrdinalTriggers.MIN_BURST_ORDINAL)
        assertEquals(2, AuthorityOrdinalTriggers.MAX_BURST_ORDINAL)
        assertEquals(
            "burst_ordinal must be between 0 and 2",
            AuthorityOrdinalTriggers.ERROR_MESSAGE,
        )

        AuthorityOrdinalTriggers.definitions.forEach { definition ->
            val normalizedSql = definition.sql.replace(Regex("\\s+"), " ")

            assertTrue(
                definition.sql.contains(
                    "CREATE TRIGGER IF NOT EXISTS `${definition.name}`",
                ),
            )
            assertTrue(
                definition.sql.contains(
                    "BEFORE ${definition.event.sql} ON `${definition.table}`",
                ),
            )
            assertTrue(
                normalizedSql.contains(
                    "WHEN typeof(NEW.`burst_ordinal`) != 'integer' OR " +
                        "NEW.`burst_ordinal` NOT BETWEEN " +
                        "${AuthorityOrdinalTriggers.MIN_BURST_ORDINAL} AND " +
                        AuthorityOrdinalTriggers.MAX_BURST_ORDINAL,
                ),
            )
            assertTrue(
                definition.sql.contains(
                    "RAISE(ABORT, '${AuthorityOrdinalTriggers.ERROR_MESSAGE}')",
                ),
            )
            assertFalse(definition.sql.contains("DROP TRIGGER"))
        }
    }
}
