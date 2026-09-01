package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.data.db.ShootPreparationShootRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShootPaginationTest {
    @Test
    fun pageRequestValidatesBoundedQuerySizeAndNonnegativeOffset() {
        val first = ShootPageRequest(limit = 50, offset = 0)
        assertEquals(50, first.limit)
        assertEquals(0, first.offset)
        assertEquals(51, first.queryLimit)

        listOf(0, ShootPageRequest.MAX_LIMIT + 1).forEach { invalidLimit ->
            assertThrows(IllegalArgumentException::class.java) {
                ShootPageRequest(invalidLimit, 0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShootPageRequest(50, -1)
        }
    }

    @Test
    fun limitPlusOneProjectionMapsOnlyTheVisiblePageAndReturnsImmutableRedactedData() {
        var mapped = 0
        val rows = (0..50).map { index -> row(index) }
        val page = projectShootSummaryPage(
            rows = rows,
            request = ShootPageRequest(limit = 50, offset = 0),
            mapRow = { row ->
                mapped += 1
                ShootSummary(
                    shootId = row.shootId,
                    name = row.name,
                    validatedReferenceCount = row.acceptedReferenceCount.toInt(),
                    lifecycle = ShootPreparationLifecycle.ACTIVE,
                    updatedAtEpochMillis = row.updatedAtEpochMillis,
                )
            },
        )

        assertEquals(50, mapped)
        assertEquals(50, page.items.size)
        assertTrue(page.hasMore)
        assertEquals((0 until 50).map { "shoot-$it" }, page.items.map { it.shootId })
        assertEquals("ShootSummaryPage(redacted)", page.toString())
        assertFalse(page.toString().contains("shoot-0"))
        assertThrows(UnsupportedOperationException::class.java) {
            (page.items as MutableList<ShootSummary>).clear()
        }
    }

    @Test
    fun pageProjectionRejectsDaoOverdeliveryWithoutInventingAGlobalShootMaximum() {
        val request = ShootPageRequest(limit = 50, offset = 500)
        assertThrows(IllegalStateException::class.java) {
            projectShootSummaryPage(
                rows = (0..51).map(::row),
                request = request,
                mapRow = { error("must reject before mapping") },
            )
        }

        val finalPage = projectShootSummaryPage(
            rows = listOf(row(500)),
            request = request,
            mapRow = { persisted ->
                ShootSummary(
                    persisted.shootId,
                    persisted.name,
                    0,
                    ShootPreparationLifecycle.ACTIVE,
                    persisted.updatedAtEpochMillis,
                )
            },
        )
        assertEquals(listOf("shoot-500"), finalPage.items.map { it.shootId })
        assertFalse(finalPage.hasMore)
    }

    @Test
    fun daoAndRepositoryDeclareParameterizedObservedPaginationWithoutChangingLegacyObservation() {
        val dao = source("app/src/main/java/com/tonyisup/poseguidesnap/data/db/ShootPreparationDao.kt")
        val repository = source(
            "app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootPreparationRepository.kt",
        )
        listOf(
            "fun observeShootPage(limit: Int, offset: Int): Flow<List<ShootPreparationShootRow>>",
            "LIMIT :limit OFFSET :offset",
            "ORDER BY shoot.updated_at_epoch_millis DESC, shoot.shoot_id ASC",
        ).forEach { marker ->
            assertTrue("Missing observed DAO pagination marker: $marker", marker in dao)
        }
        listOf(
            "fun observeShootPage(limit: Int, offset: Int): Flow<ShootSummaryPage>",
            "ShootPageRequest(limit, offset)",
            "dao.observeShootPage(request.queryLimit, request.offset)",
            "projectShootSummaryPage(",
        ).forEach { marker ->
            assertTrue("Missing repository pagination marker: $marker", marker in repository)
        }
        assertTrue("Legacy non-UI observation must remain", "fun observeShoots(): Flow<List<ShootSummary>>" in repository)
        assertFalse("No global persisted shoot maximum may be introduced", "MAX_SHOOT_LIST_ITEMS" in repository)
    }

    private fun row(index: Int): ShootPreparationShootRow = ShootPreparationShootRow(
        shootId = "shoot-$index",
        name = "Shoot $index",
        createdAtEpochMillis = index.toLong(),
        updatedAtEpochMillis = index.toLong(),
        lifecycleState = "ACTIVE",
        deletionGeneration = 0L,
        acceptedReferenceCount = 0L,
        totalReferenceCount = 0L,
    )

    private fun source(path: String): String = projectRoot().resolve(path).readText()

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }
}
