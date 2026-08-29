package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.architecture.KotlinDomainBoundaryAnalyzer
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Outcome = KeepLatestFrameProcessor.SubmissionOutcome

class KeepLatestFrameProcessorTest {
    @Test
    fun firstSubmissionStartsExactlyOneDrainRunnable() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val frame = TestFrame("A", events)
        val processor = recordingProcessor(executor, events)

        assertEquals(Outcome.STARTED, processor.submit(frame))
        assertEquals(1, executor.executeCallCount)
        assertEquals(1, executor.queuedTaskCount)
        assertEquals(0, frame.closeCount)

        executor.runNext()

        assertEquals(listOf("process:A", "result:A", "close:A"), events)
        assertEquals(1, frame.closeCount)
        assertEquals(0, executor.queuedTaskCount)
    }

    @Test
    fun pendingFrameIsReplacedAndReleasedBeforeCurrentDrainsLatest() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val processor = recordingProcessor(executor, events)
        val a = TestFrame("A", events)
        val b = TestFrame("B", events)
        val c = TestFrame("C", events)

        assertEquals(Outcome.STARTED, processor.submit(a))
        assertEquals(Outcome.QUEUED, processor.submit(b))
        assertEquals(Outcome.REPLACED, processor.submit(c))

        assertEquals(0, a.closeCount)
        assertEquals(1, b.closeCount)
        assertEquals(0, c.closeCount)
        assertEquals(1, executor.executeCallCount)
        assertEquals(1, executor.queuedTaskCount)

        executor.runNext()

        assertEquals(
            listOf("close:B", "process:A", "result:A", "close:A", "process:C", "result:C", "close:C"),
            events,
        )
        assertCloseCounts(a, b, c)
    }

    @Test
    fun longSubmissionChainKeepsOnlyOnePendingFrameAndOneRunnable() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val processed = mutableListOf<String>()
        val processor = recordingProcessor(executor, events, processed)
        val first = TestFrame("A", events)
        val chain = (1..100).map { TestFrame(it.toString(), events) }

        assertEquals(Outcome.STARTED, processor.submit(first))
        chain.forEachIndexed { index, frame ->
            val expected = if (index == 0) Outcome.QUEUED else Outcome.REPLACED
            assertEquals("frame=${frame.id}", expected, processor.submit(frame))
            assertEquals(1, executor.executeCallCount)
            assertEquals(1, executor.queuedTaskCount)
        }

        assertEquals(0, chain.last().closeCount)
        chain.dropLast(1).forEach { assertEquals("frame=${it.id}", 1, it.closeCount) }

        executor.runNext()

        assertEquals(listOf("A", "100"), processed)
        assertEquals(1, first.closeCount)
        assertEquals(1, chain.last().closeCount)
        assertTrue(chain.all { it.closeCount == 1 })
        assertEquals(0, executor.queuedTaskCount)
    }

    @Test
    fun processFailureIsReportedAndLatestPendingFrameStillProcesses() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val processor = KeepLatestFrameProcessor<TestFrame, String>(
            executor = executor,
            process = { frame ->
                events += "process:${frame.id}"
                if (frame.id == "A") throw IllegalStateException("process-A")
                frame.id
            },
            onResult = { result -> events += "result:$result" },
            onFailure = { failure -> events += "failure:${failure.message}" },
        )
        val a = TestFrame("A", events)
        val b = TestFrame("B", events)
        val c = TestFrame("C", events)

        processor.submit(a)
        processor.submit(b)
        processor.submit(c)
        executor.runNext()

        assertEquals(
            listOf(
                "close:B",
                "process:A",
                "failure:process-A",
                "close:A",
                "process:C",
                "result:C",
                "close:C",
            ),
            events,
        )
        assertCloseCounts(a, b, c)
    }

    @Test
    fun resultCallbackFailureIsContainedAndLatestPendingFrameStillProcesses() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val processor = KeepLatestFrameProcessor<TestFrame, String>(
            executor = executor,
            process = { frame ->
                events += "process:${frame.id}"
                frame.id
            },
            onResult = { result ->
                events += "result:$result"
                if (result == "A") throw IllegalArgumentException("result-A")
            },
            onFailure = { failure -> events += "failure:${failure.message}" },
        )
        val a = TestFrame("A", events)
        val c = TestFrame("C", events)

        processor.submit(a)
        processor.submit(c)
        executor.runNext()

        assertEquals(
            listOf(
                "process:A",
                "result:A",
                "failure:result-A",
                "close:A",
                "process:C",
                "result:C",
                "close:C",
            ),
            events,
        )
        assertCloseCounts(a, c)
    }

    @Test
    fun throwingFailureCallbackCannotLeakFramesOrStrandRunningState() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        var failureCount = 0
        val processor = KeepLatestFrameProcessor<TestFrame, String>(
            executor = executor,
            process = { frame ->
                events += "process:${frame.id}"
                if (frame.id == "A") throw IllegalStateException("process-A")
                frame.id
            },
            onResult = { result -> events += "result:$result" },
            onFailure = {
                failureCount += 1
                throw IllegalStateException("failure-callback")
            },
        )
        val a = TestFrame("A", events)
        val c = TestFrame("C", events)
        val d = TestFrame("D", events)

        processor.submit(a)
        processor.submit(c)
        executor.runNext()

        assertEquals(1, failureCount)
        assertCloseCounts(a, c)
        assertEquals(Outcome.STARTED, processor.submit(d))
        assertEquals(2, executor.executeCallCount)
        executor.runNext()
        assertEquals(1, d.closeCount)
        assertEquals(listOf("A", "C", "D"), events.filter { it.startsWith("process:") }.map { it.substringAfter(':') })
    }

    @Test
    fun closeBeforeRunnableSkipsProcessingAndFutureSubmissionsAreRejectedAndReleased() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val processed = mutableListOf<String>()
        val processor = recordingProcessor(executor, events, processed)
        val a = TestFrame("A", events)
        val b = TestFrame("B", events)

        assertEquals(Outcome.STARTED, processor.submit(a))
        processor.close()
        processor.close()

        assertEquals(0, a.closeCount)
        assertEquals(emptyList<String>(), processed)
        executor.runNext()
        assertEquals(1, a.closeCount)
        assertEquals(emptyList<String>(), processed)

        assertEquals(Outcome.REJECTED_CLOSED, processor.submit(b))
        assertEquals(1, b.closeCount)
        assertEquals(1, executor.executeCallCount)
    }

    @Test
    fun closeWhileProcessingRemovesPendingIdempotentlyAndCurrentFinishes() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        lateinit var processor: KeepLatestFrameProcessor<TestFrame, String>
        val a = TestFrame("A", events)
        val b = TestFrame("B", events)
        processor = KeepLatestFrameProcessor(
            executor = executor,
            process = { frame ->
                events += "process:${frame.id}"
                processor.close()
                processor.close()
                assertEquals(0, frame.closeCount)
                assertEquals(1, b.closeCount)
                frame.id
            },
            onResult = { result -> events += "result:$result" },
            onFailure = { failure -> events += "failure:${failure.message}" },
        )

        assertEquals(Outcome.STARTED, processor.submit(a))
        assertEquals(Outcome.QUEUED, processor.submit(b))
        executor.runNext()

        assertEquals(listOf("process:A", "close:B", "result:A", "close:A"), events)
        assertCloseCounts(a, b)
        assertEquals(1, executor.executeCallCount)
    }

    @Test
    fun executorRejectionReleasesFrameReportsFailureAndAllowsRecovery() {
        val executor = ManualExecutor()
        executor.rejectNext = true
        val events = mutableListOf<String>()
        val processor = recordingProcessor(executor, events)
        val a = TestFrame("A", events)
        val b = TestFrame("B", events)

        assertEquals(Outcome.REJECTED_EXECUTOR, processor.submit(a))
        assertEquals(listOf("close:A", "failure:executor-rejected"), events)
        assertEquals(1, a.closeCount)
        assertEquals(0, executor.queuedTaskCount)

        assertEquals(Outcome.STARTED, processor.submit(b))
        assertEquals(2, executor.executeCallCount)
        executor.runNext()

        assertEquals(1, b.closeCount)
        assertEquals(listOf("process:B", "result:B"), events.filter { it.contains(":B") && !it.startsWith("close:") })
    }

    @Test
    fun reentrantSubmissionsFromProcessAndResultHonorLatestWithoutAnotherRunnable() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val outcomes = mutableListOf<Outcome>()
        lateinit var processor: KeepLatestFrameProcessor<TestFrame, String>
        val a = TestFrame("A", events)
        val b = TestFrame("B", events)
        val c = TestFrame("C", events)
        processor = KeepLatestFrameProcessor(
            executor = executor,
            process = { frame ->
                events += "process:${frame.id}"
                if (frame.id == "A") outcomes += processor.submit(b)
                frame.id
            },
            onResult = { result ->
                events += "result:$result"
                if (result == "A") outcomes += processor.submit(c)
            },
            onFailure = { failure -> events += "failure:${failure.message}" },
        )

        assertEquals(Outcome.STARTED, processor.submit(a))
        executor.runNext()

        assertEquals(listOf(Outcome.QUEUED, Outcome.REPLACED), outcomes)
        assertEquals(
            listOf("process:A", "result:A", "close:B", "close:A", "process:C", "result:C", "close:C"),
            events,
        )
        assertCloseCounts(a, b, c)
        assertEquals(1, executor.executeCallCount)
    }

    @Test
    fun repeatedKeepLatestSequencesRemainDeterministic() {
        val executor = ManualExecutor()
        val events = mutableListOf<String>()
        val processed = mutableListOf<String>()
        val processor = recordingProcessor(executor, events, processed)
        val allFrames = mutableListOf<TestFrame>()

        repeat(25) { iteration ->
            val a = TestFrame("$iteration-A", events)
            val b = TestFrame("$iteration-B", events)
            val c = TestFrame("$iteration-C", events)
            allFrames += listOf(a, b, c)

            assertEquals(Outcome.STARTED, processor.submit(a))
            assertEquals(Outcome.QUEUED, processor.submit(b))
            assertEquals(Outcome.REPLACED, processor.submit(c))
            assertEquals(iteration + 1, executor.executeCallCount)
            assertEquals(1, executor.queuedTaskCount)
            executor.runNext()
        }

        assertEquals(
            (0 until 25).flatMap { listOf("$it-A", "$it-C") },
            processed,
        )
        assertTrue(allFrames.all { it.closeCount == 1 })
        assertEquals(0, executor.queuedTaskCount)
    }

    @Test
    fun processorSourcePassesIsolatedPureDependencyContract() {
        val sourceFile = projectRoot().resolve(SOURCE_PATH)
        val isolatedSourceRoot = Files.createTempDirectory("keep-latest-frame-processor-boundary").toFile()
        try {
            sourceFile.copyTo(isolatedSourceRoot.resolve(sourceFile.name))
            val violations = KotlinDomainBoundaryAnalyzer().use { it.analyze(isolatedSourceRoot) }
            assertEquals(emptyList<String>(), violations)

            val imports = sourceFile.readLines()
                .map(String::trim)
                .filter { it.startsWith("import ") }
            assertEquals(
                listOf(
                    "import java.util.concurrent.Executor",
                    "import java.util.concurrent.RejectedExecutionException",
                ),
                imports,
            )
            val source = sourceFile.readText()
            listOf(
                "android.",
                "androidx.",
                "com.google",
                "kotlinx.coroutines",
                "java.io",
                "java.nio",
                "java.time",
                "kotlin.time",
                "System.",
                "Thread.",
                "Executors.",
            ).forEach { forbidden ->
                assertFalse("Unexpected source dependency $forbidden", source.contains(forbidden))
            }
        } finally {
            isolatedSourceRoot.deleteRecursively()
        }
    }

    private fun recordingProcessor(
        executor: ManualExecutor,
        events: MutableList<String>,
        processed: MutableList<String> = mutableListOf(),
    ): KeepLatestFrameProcessor<TestFrame, String> = KeepLatestFrameProcessor(
        executor = executor,
        process = { frame ->
            events += "process:${frame.id}"
            processed += frame.id
            frame.id
        },
        onResult = { result -> events += "result:$result" },
        onFailure = { failure -> events += "failure:${failure.message}" },
    )

    private fun assertCloseCounts(vararg frames: TestFrame) {
        frames.forEach { frame -> assertEquals("frame=${frame.id}", 1, frame.closeCount) }
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        var executeCallCount: Int = 0
            private set
        var rejectNext: Boolean = false

        val queuedTaskCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            executeCallCount += 1
            if (rejectNext) {
                rejectNext = false
                throw RejectedExecutionException("executor-rejected")
            }
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    private class TestFrame(
        val id: String,
        private val events: MutableList<String>,
    ) : AutoCloseable {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount += 1
            events += "close:$id"
        }
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private companion object {
        const val SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/camera/KeepLatestFrameProcessor.kt"
    }
}
