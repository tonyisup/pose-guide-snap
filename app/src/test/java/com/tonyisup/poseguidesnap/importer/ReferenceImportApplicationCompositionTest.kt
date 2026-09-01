package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.ReferenceImportPolicy
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReferenceImportApplicationCompositionTest {
    @Test
    fun injectedRawIdentitySourceProducesSafeOpaqueDistinctTokenAndPoseIdsWithoutRenderingThem() {
        val rawValues = listOf(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        )
        val provider = identityProvider(rawValues.iterator())

        val token = provider.nextToken()
        val poseId = (provider as ReferenceImportPoseIdProvider).nextPoseId()

        assertEquals(rawValues[0], token.value)
        assertEquals(rawValues[1], poseId)
        assertNotEquals(token.value, poseId)
        assertTrue(ReferenceImportPolicy.validateOwnershipIdentity(token.value))
        assertTrue(ReferenceImportPolicy.validateOwnershipIdentity(poseId))
        rawValues.forEach { raw -> assertFalse(provider.toString().contains(raw)) }
        assertEquals("ProductionReferenceImportIdentityProvider(redacted)", provider.toString())
    }

    @Test
    fun defaultProductionIdentitySourceProducesFreshSafeOpaqueValuesAcrossConcurrentCalls() {
        val provider = defaultIdentityProvider()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 128).map { index ->
                executor.submit(Callable {
                    if (index % 2 == 0) {
                        provider.nextToken().value
                    } else {
                        (provider as ReferenceImportPoseIdProvider).nextPoseId()
                    }
                })
            }
            val values = futures.map { it.get() }

            assertEquals(values.size, values.toSet().size)
            assertTrue(values.all(ReferenceImportPolicy::validateOwnershipIdentity))
            assertTrue(values.none { value ->
                value.contains("content://", ignoreCase = true) ||
                    '/' in value ||
                    '\\' in value ||
                    '\u0000' in value
            })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun timelineProviderAllocatesStrictFifteenValueBlocksAcrossRepeatedCallsAndClockRollback() {
        val rawTimes = longArrayOf(1_000L, 900L, 1_001L)
        val calls = AtomicInteger()
        val provider = timelineProvider {
            rawTimes[calls.getAndUpdate { index -> (index + 1).coerceAtMost(rawTimes.lastIndex) }]
        }

        val first = timelineValues(provider.nextTimeline())
        val rollback = timelineValues(provider.nextTimeline())
        val repeated = timelineValues(provider.nextTimeline())

        listOf(first, rollback, repeated).forEach(::assertStrictTimeline)
        assertTrue(first.last() < rollback.first())
        assertTrue(rollback.last() < repeated.first())
        assertEquals(1_000L, first.first())
        assertEquals(1_014L, first.last())
        assertEquals(1_015L, rollback.first())
        assertEquals(1_029L, rollback.last())
        assertEquals("ProductionReferenceImportLedgerTimelineProvider(redacted)", provider.toString())
        assertFalse(provider.toString().contains("1000"))
    }

    @Test
    fun concurrentTimelineAllocationsAreDisjointStrictAndGloballyMonotonicByAllocatedBlock() {
        val provider = timelineProvider { 5_000L }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val timelines = (0 until 32).map {
                executor.submit(Callable { timelineValues(provider.nextTimeline()) })
            }.map { it.get() }

            timelines.forEach(::assertStrictTimeline)
            val sorted = timelines.sortedBy(List<Long>::first)
            sorted.zipWithNext().forEach { (first, second) ->
                assertTrue(first.last() < second.first())
            }
            val allValues = timelines.flatten()
            assertEquals(32 * TIMELINE_VALUE_COUNT, allValues.size)
            assertEquals(allValues.size, allValues.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun timelineOverflowAndNegativeRawTimeFailClosedWithoutRenderingRawAuthority() {
        listOf(-1L, Long.MAX_VALUE - 13L, Long.MAX_VALUE).forEach { raw ->
            val provider = timelineProvider { raw }
            try {
                provider.nextTimeline()
                fail("raw time $raw must fail closed")
            } catch (failure: RuntimeException) {
                assertFalse(failure.toString().contains("content://private-authority"))
                assertEquals("ProductionReferenceImportLedgerTimelineProvider(redacted)", provider.toString())
            }
        }
    }

    @Test
    fun productionCompositionSourceWiresRoomAdmissionAndAllHiddenAuthoritiesIntoService() {
        val composition = projectRoot().resolve(COMPOSITION_SOURCE_PATH)
        assertTrue("Production composition source is missing", composition.isFile)
        val source = composition.readText()
        val requiredMarkers = listOf(
            "RoomReferenceImportRepository(database)",
            "RoomReferenceImportAdmissionAdapter(repository)",
            "ProductionReferenceImportIdentityProvider(",
            "ProductionReferenceImportLedgerTimelineProvider(",
            "ReferenceImportApplicationService(",
            "admission = admission",
            "tokenProvider = identityProvider",
            "poseIdProvider = identityProvider",
            "timelineProvider = timelineProvider",
        )
        requiredMarkers.forEach { marker ->
            assertTrue("Production composition is missing wiring marker: $marker", marker in source)
        }
        listOf("androidx.compose", "@Composable", "System.currentTimeMillis", "UUID.randomUUID").forEach { forbidden ->
            assertFalse("Composition factory must not expose UI or mint authority directly: $forbidden", forbidden in source)
        }
    }

    private fun identityProvider(rawValues: Iterator<String>): ReferenceImportTokenProvider {
        return ProductionReferenceImportIdentityProvider(
            ReferenceImportRawIdSource { rawValues.next() },
        )
    }

    private fun defaultIdentityProvider(): ReferenceImportTokenProvider {
        return ProductionReferenceImportIdentityProvider()
    }

    private fun timelineProvider(now: () -> Long): ReferenceImportLedgerTimelineProvider {
        return ProductionReferenceImportLedgerTimelineProvider(
            ReferenceImportRawTimeSource { now() },
        )
    }

    private fun assertStrictTimeline(values: List<Long>) {
        assertEquals(TIMELINE_VALUE_COUNT, values.size)
        assertTrue(values.first() >= 0L)
        assertTrue(values.zipWithNext().all { (first, second) -> first < second })
    }

    private fun timelineValues(timeline: ReferenceImportLedgerTimeline): List<Long> = listOf(
        timeline.reservedAtEpochMillis,
        timeline.writingTempAtEpochMillis,
        timeline.tempSyncedAtEpochMillis,
        timeline.finalRenamePendingSyncAtEpochMillis,
        timeline.finalDurableAtEpochMillis,
        timeline.assetReadyAtEpochMillis,
        timeline.committedAtEpochMillis,
        timeline.cleanupRequiredAtEpochMillis,
        timeline.cleanupPendingSyncAtEpochMillis,
        timeline.cleanedDurableAtEpochMillis,
        timeline.quarantineRequiredAtEpochMillis,
        timeline.quarantinePendingSyncAtEpochMillis,
        timeline.quarantineDurableAtEpochMillis,
        timeline.reconciliationMarkedAtEpochMillis,
        timeline.failureSettledAtEpochMillis,
    )

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
    }

    private companion object {
        const val COMPOSITION_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/importer/ReferenceImportApplicationComposition.kt"
        const val TIMELINE_VALUE_COUNT = 15
    }
}
