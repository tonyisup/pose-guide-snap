package com.tonyisup.poseguidesnap.camera

import java.io.File
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisCadenceGateTest {
    @Test
    fun fixedTenHertzBoundaryAcceptsEqualityAndSkippedFrameDoesNotMoveAnchor() {
        val gate = AnalysisCadenceGate()

        assertEquals(AnalysisCadenceGate.Decision.ACCEPTED_FIRST, gate.decide(0L))
        assertEquals(
            AnalysisCadenceGate.Decision.SKIPPED_TOO_SOON,
            gate.decide(99_999_999L),
        )
        assertEquals(
            AnalysisCadenceGate.Decision.ACCEPTED_INTERVAL,
            gate.decide(100_000_000L),
        )

        assertEquals(
            AnalysisCadenceGate.Snapshot(
                received = 3L,
                accepted = 2L,
                skippedTooSoon = 1L,
                skippedStale = 0L,
                lastAcceptedTimestampNanos = 100_000_000L,
            ),
            gate.snapshot(),
        )
    }

    @Test
    fun equalAndDecreasingTimestampsAreStaleAndNeverMoveAcceptedAnchor() {
        val gate = AnalysisCadenceGate()

        assertEquals(AnalysisCadenceGate.Decision.ACCEPTED_FIRST, gate.decide(10L))
        assertEquals(AnalysisCadenceGate.Decision.SKIPPED_STALE, gate.decide(10L))
        assertEquals(AnalysisCadenceGate.Decision.SKIPPED_STALE, gate.decide(9L))

        assertEquals(
            AnalysisCadenceGate.Snapshot(
                received = 3L,
                accepted = 1L,
                skippedTooSoon = 0L,
                skippedStale = 2L,
                lastAcceptedTimestampNanos = 10L,
            ),
            gate.snapshot(),
        )
    }

    @Test
    fun negativeTimestampIsRejectedWithoutChangingSnapshot() {
        val gate = AnalysisCadenceGate()
        val initial = gate.snapshot()

        assertThrows(IllegalArgumentException::class.java) { gate.decide(-1L) }

        assertEquals(initial, gate.snapshot())
        assertEquals(0L, initial.received)
        assertNull(initial.lastAcceptedTimestampNanos)
    }

    @Test
    fun comparisonsNearLongMaximumAreOverflowSafe() {
        val gate = AnalysisCadenceGate()
        val first = Long.MAX_VALUE - AnalysisCadenceGate.MINIMUM_INTERVAL_NANOS

        assertEquals(AnalysisCadenceGate.Decision.ACCEPTED_FIRST, gate.decide(first))
        assertEquals(
            AnalysisCadenceGate.Decision.SKIPPED_TOO_SOON,
            gate.decide(Long.MAX_VALUE - 1L),
        )
        assertEquals(
            AnalysisCadenceGate.Decision.ACCEPTED_INTERVAL,
            gate.decide(Long.MAX_VALUE),
        )
        assertEquals(
            AnalysisCadenceGate.Decision.SKIPPED_STALE,
            gate.decide(Long.MAX_VALUE),
        )
        assertEquals(Long.MAX_VALUE, gate.snapshot().lastAcceptedTimestampNanos)
    }

    @Test
    fun repeatedSequencesProduceIdenticalDecisionsAndSnapshots() {
        val timestamps = listOf(
            0L,
            99_999_999L,
            100_000_000L,
            100_000_000L,
            99_999_999L,
            199_999_999L,
            200_000_000L,
        )
        val first = AnalysisCadenceGate()
        val second = AnalysisCadenceGate()

        val firstDecisions = timestamps.map(first::decide)
        val secondDecisions = timestamps.map(second::decide)

        assertEquals(firstDecisions, secondDecisions)
        assertEquals(first.snapshot(), second.snapshot())
        assertSnapshotInvariant(first.snapshot())
    }

    @Test
    fun concurrentEqualTimestampsHaveExactlyOneFirstAcceptance() {
        val workers = 64
        val gate = AnalysisCadenceGate()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(workers)
        val failure = AtomicReference<Throwable>()
        val decisions = Collections.synchronizedList(mutableListOf<AnalysisCadenceGate.Decision>())

        repeat(workers) {
            executor.execute {
                try {
                    start.await()
                    decisions += gate.decide(0L)
                } catch (caught: Throwable) {
                    failure.compareAndSet(null, caught)
                } finally {
                    finished.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("Concurrent decisions timed out", finished.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Unexpected concurrent failure", it) }

        assertEquals(1, decisions.count { it == AnalysisCadenceGate.Decision.ACCEPTED_FIRST })
        assertEquals(
            workers - 1,
            decisions.count { it == AnalysisCadenceGate.Decision.SKIPPED_STALE },
        )
        assertEquals(
            AnalysisCadenceGate.Snapshot(
                received = workers.toLong(),
                accepted = 1L,
                skippedTooSoon = 0L,
                skippedStale = (workers - 1).toLong(),
                lastAcceptedTimestampNanos = 0L,
            ),
            gate.snapshot(),
        )
    }

    @Test
    fun snapshotsAreDetachedImmutableValuesAndPreserveOutcomeInvariant() {
        val gate = AnalysisCadenceGate()
        val before = gate.snapshot()
        gate.decide(0L)
        val afterFirst = gate.snapshot()
        gate.decide(50_000_000L)
        val afterSkip = gate.snapshot()

        assertEquals(AnalysisCadenceGate.Snapshot(0L, 0L, 0L, 0L, null), before)
        assertEquals(AnalysisCadenceGate.Snapshot(1L, 1L, 0L, 0L, 0L), afterFirst)
        assertSnapshotInvariant(afterSkip)
        AnalysisCadenceGate.Snapshot::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                assertTrue("Snapshot field must be final: ${field.name}", Modifier.isFinal(field.modifiers))
            }
    }

    @Test
    fun receivedCounterOverflowFailsBeforeAnyGateStateMutation() {
        val gate = AnalysisCadenceGate()
        val received = AnalysisCadenceGate::class.java.getDeclaredField("received").apply {
            isAccessible = true
        }
        received.setLong(gate, Long.MAX_VALUE)
        val before = gate.snapshot()

        assertThrows(IllegalStateException::class.java) { gate.decide(0L) }

        assertEquals(before, gate.snapshot())
    }

    @Test
    fun sourceIsPureAndPolicyIsExactlyFixedTaskTenTenHertz() {
        val source = productionSource()

        assertTrue(source.contains("const val MINIMUM_INTERVAL_NANOS = 100_000_000L"))
        assertEquals(1, source.lineSequence().count { it.contains("100_000_000L") })
        listOf(
            "android.",
            "System.nanoTime",
            "System.currentTimeMillis",
            "java.time.",
            "kotlin.time.",
            "android.util.Log",
            "java.io.",
            "java.nio.file",
            "kotlinx.coroutines",
            "Thread.sleep",
            "delay(",
            "Random",
        ).forEach { forbidden ->
            assertFalse("Forbidden cadence gate surface: $forbidden", source.contains(forbidden))
        }
    }

    private fun assertSnapshotInvariant(snapshot: AnalysisCadenceGate.Snapshot) {
        assertEquals(
            snapshot.received,
            snapshot.accepted + snapshot.skippedTooSoon + snapshot.skippedStale,
        )
    }

    private fun productionSource(): String = projectRoot().resolve(SOURCE_PATH).readText()

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private companion object {
        const val SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/camera/AnalysisCadenceGate.kt"
    }
}
