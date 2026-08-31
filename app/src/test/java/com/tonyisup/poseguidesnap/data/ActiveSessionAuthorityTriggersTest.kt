package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.data.db.ActiveSessionAuthorityTriggers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSessionAuthorityTriggersTest {
    @Test
    fun productionDefinitionsAreExactlyTheClosedInsertAndUpdateAuthoritySet() {
        assertEquals(
            listOf("INSERT", "UPDATE"),
            ActiveSessionAuthorityTriggers.Event.entries.map(Enum<*>::name),
        )

        val definitions = ActiveSessionAuthorityTriggers.definitions
        assertEquals("expected one INSERT and one UPDATE authority trigger", 2, definitions.size)
        assertEquals(
            setOf(
                Triple(
                    "trigger_shoot_sessions_one_active_insert",
                    "shoot_sessions",
                    ActiveSessionAuthorityTriggers.Event.INSERT,
                ),
                Triple(
                    "trigger_shoot_sessions_one_active_update",
                    "shoot_sessions",
                    ActiveSessionAuthorityTriggers.Event.UPDATE,
                ),
            ),
            definitions.map { definition ->
                Triple(definition.name, definition.table, definition.event)
            }.toSet(),
        )
        assertEquals(
            "trigger names must be unique",
            definitions.size,
            definitions.map(ActiveSessionAuthorityTriggers.Definition::name).distinct().size,
        )
    }

    @Test
    fun productionSqlOnlyRejectsASecondActiveSessionForTheSameShoot() {
        assertEquals(
            "one active shoot session per shoot",
            ActiveSessionAuthorityTriggers.ERROR_MESSAGE,
        )
        assertEquals(2, ActiveSessionAuthorityTriggers.definitions.size)

        ActiveSessionAuthorityTriggers.definitions.forEach { definition ->
            val normalizedSql = definition.sql.replace(Regex("\\s+"), " ").trim()

            assertTrue(
                definition.sql.contains(
                    "CREATE TRIGGER IF NOT EXISTS `${definition.name}`",
                ),
            )
            assertTrue(
                definition.sql.contains(
                    "BEFORE ${definition.event.sql} ON `shoot_sessions`",
                ),
            )
            assertTrue(
                normalizedSql.contains(
                    "WHEN NEW.`lifecycle_state` = 'ACTIVE' AND EXISTS ( " +
                        "SELECT 1 FROM `shoot_sessions` AS existing_session " +
                        "WHERE existing_session.`shoot_id` = NEW.`shoot_id` " +
                        "AND existing_session.`session_id` != NEW.`session_id` " +
                        "AND existing_session.`lifecycle_state` = 'ACTIVE' )",
                ),
            )
            assertTrue(
                definition.sql.contains(
                    "RAISE(ABORT, '${ActiveSessionAuthorityTriggers.ERROR_MESSAGE}')",
                ),
            )
            assertEquals(2, Regex("'ACTIVE'").findAll(definition.sql).count())
            assertFalse(definition.sql.contains("COMPLETED"))
            assertFalse(definition.sql.contains("DROP TRIGGER"))
        }
    }

    @Test
    fun publicDefinitionToStringIsStableTypeOnlyAndRedacted() {
        val sensitive = "<SECRET:definition>"
        val definition = ActiveSessionAuthorityTriggers.Definition(
            name = sensitive,
            table = sensitive,
            event = ActiveSessionAuthorityTriggers.Event.INSERT,
            sql = sensitive,
        )

        assertEquals(
            "ActiveSessionAuthorityTriggers.Definition(redacted)",
            definition.toString(),
        )
        assertFalse(definition.toString().contains(sensitive))
    }
}
