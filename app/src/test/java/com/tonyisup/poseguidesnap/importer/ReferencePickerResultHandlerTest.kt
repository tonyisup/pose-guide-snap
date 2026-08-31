package com.tonyisup.poseguidesnap.importer

import android.net.Task11bTestUri
import android.net.Uri
import com.tonyisup.poseguidesnap.data.ReferenceAssetByteSource
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReferencePickerResultHandlerTest {
    @Test
    fun nullCallbackIsCancellationWithoutFactoryOrImporterWork() = runTest {
        val factoryCalls = AtomicInteger()
        val importerCalls = AtomicInteger()
        val handler = ReferencePickerResultHandler(
            importer = JournaledReferencePickerImporterPort {
                importerCalls.incrementAndGet()
                ReferencePoseImportResult.ReconciliationRequired
            },
            sourceFactory = ReferencePickerByteSourceFactory {
                factoryCalls.incrementAndGet()
                byteSource()
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = handler.handle(null, draft())

        assertSame(ReferencePickerResult.Cancelled, result)
        assertEquals(0, factoryCalls.get())
        assertEquals(0, importerCalls.get())
    }

    @Test
    fun acceptedContentSelectionBuildsExactRequestOnceOnInjectedWorker() = runTest {
        val callerThread = Thread.currentThread()
        val factoryThread = AtomicReference<Thread>()
        val importerThread = AtomicReference<Thread>()
        val selectedUri = Task11bTestUri.from(VALID_URI)
        val source = byteSource()
        val capturedRequest = AtomicReference<ReferencePoseImportRequest>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "task11b-picker-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val handler = ReferencePickerResultHandler(
                importer = JournaledReferencePickerImporterPort { request ->
                    importerThread.set(Thread.currentThread())
                    capturedRequest.set(request)
                    ReferencePoseImportResult.Succeeded(request.poseId, 4)
                },
                sourceFactory = ReferencePickerByteSourceFactory { uri ->
                    factoryThread.set(Thread.currentThread())
                    assertSame(selectedUri, uri)
                    source
                },
                dispatcher = dispatcher,
            )

            val result = handler.handle(selectedUri, draft())

            assertTrue(result is ReferencePickerResult.Completed)
            result as ReferencePickerResult.Completed
            assertTrue(result.importResult is ReferencePoseImportResult.Succeeded)
            assertNotSame(callerThread, factoryThread.get())
            assertNotSame(callerThread, importerThread.get())
            assertSame(factoryThread.get(), importerThread.get())
            val request = requireNotNull(capturedRequest.get())
            assertEquals(ReferenceImportToken(TOKEN), request.importToken)
            assertEquals("shoot-11b", request.shootId)
            assertEquals("pose-11b", request.poseId)
            assertEquals(LABEL, request.label)
            assertTrue(request.mirrorAllowed)
            assertEquals(101L, request.timeline.reservedAtEpochMillis)
            assertEquals(106L, request.timeline.assetReadyAtEpochMillis)
            assertEquals(107L, request.timeline.committedAtEpochMillis)
            assertEquals(115L, request.timeline.failureSettledAtEpochMillis)
            assertSame(source, request.source)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun invalidSchemesAuthoritiesOpaqueRelativeAndMalformedUrisHaveNoSideEffects() = runTest {
        val factoryCalls = AtomicInteger()
        val importerCalls = AtomicInteger()
        val handler = ReferencePickerResultHandler(
            importer = JournaledReferencePickerImporterPort {
                importerCalls.incrementAndGet()
                ReferencePoseImportResult.ReconciliationRequired
            },
            sourceFactory = ReferencePickerByteSourceFactory {
                factoryCalls.incrementAndGet()
                byteSource()
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val invalid = listOf(
            "file:///private/reference.jpg",
            "http://task11b.test/reference",
            "android.resource://task11b.test/reference",
            "content:opaque-reference",
            "content:///missing-authority",
            "content://   /blank-authority",
            "/storage/emulated/0/reference.jpg",
            "content://task11b.test/bad%zz",
        )

        invalid.forEach { raw ->
            assertSame(
                "Selection should be invalid: $raw",
                ReferencePickerResult.InvalidSelection,
                handler.handle(Task11bTestUri.from(raw), draft()),
            )
        }
        assertEquals(0, factoryCalls.get())
        assertEquals(0, importerCalls.get())
    }

    @Test
    fun factoryAndImporterExceptionsBecomeOneStableRedactedClosedResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val uri = Task11bTestUri.from(VALID_URI)
        val factoryFailure = ReferencePickerResultHandler(
            importer = JournaledReferencePickerImporterPort {
                throw AssertionError("importer must not run")
            },
            sourceFactory = ReferencePickerByteSourceFactory {
                throw IllegalStateException("$URI_MARKER $TOKEN $LABEL")
            },
            dispatcher = dispatcher,
        ).handle(uri, draft())
        val importerFailure = ReferencePickerResultHandler(
            importer = JournaledReferencePickerImporterPort {
                throw IllegalStateException("$URI_MARKER $TOKEN $LABEL")
            },
            sourceFactory = ReferencePickerByteSourceFactory { byteSource() },
            dispatcher = dispatcher,
        ).handle(uri, draft())

        assertSame(ReferencePickerResult.ReconciliationRequired, factoryFailure)
        assertSame(ReferencePickerResult.ReconciliationRequired, importerFailure)
        listOf(factoryFailure, importerFailure).forEach(::assertRedacted)
    }

    @Test
    fun cancellationQueuedBeforeImporterInvocationHasNoSourceOrImporterSideEffect() = runTest {
        val factoryCalls = AtomicInteger()
        val importerCalls = AtomicInteger()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handler = ReferencePickerResultHandler(
            importer = JournaledReferencePickerImporterPort {
                importerCalls.incrementAndGet()
                ReferencePoseImportResult.ReconciliationRequired
            },
            sourceFactory = ReferencePickerByteSourceFactory {
                factoryCalls.incrementAndGet()
                byteSource()
            },
            dispatcher = dispatcher,
        )
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            handler.handle(Task11bTestUri.from(VALID_URI), draft())
        }

        deferred.cancel()
        runCurrent()
        try {
            deferred.await()
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            // Cancellation must remain cancellation, not a reconciliation result.
        }
        assertEquals(0, factoryCalls.get())
        assertEquals(0, importerCalls.get())
    }

    @Test
    fun completedHandlerAndProductionFactoryDeclareNoCallbackUriStorage() = runTest {
        val selectedUri = Task11bTestUri.from(VALID_URI)
        val handler = ReferencePickerResultHandler(
            importer = JournaledReferencePickerImporterPort { request ->
                ReferencePoseImportResult.Succeeded(request.poseId, 4)
            },
            sourceFactory = ReferencePickerByteSourceFactory { byteSource() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(handler.handle(selectedUri, draft()) is ReferencePickerResult.Completed)

        val retainedFields = handler.javaClass.declaredFields.onEach { field ->
            field.isAccessible = true
        }
        assertTrue(retainedFields.none { field -> Uri::class.java.isAssignableFrom(field.type) })
        assertTrue(retainedFields.none { field -> field.get(handler) === selectedUri })
        assertTrue(
            ContentResolverReferencePickerByteSourceFactory::class.java.declaredFields.none { field ->
                Uri::class.java.isAssignableFrom(field.type)
            },
        )
        assertTrue(
            ReferencePickerImportDraft::class.java.declaredFields.none { field ->
                Uri::class.java.isAssignableFrom(field.type) ||
                    ReferenceAssetByteSource::class.java.isAssignableFrom(field.type)
            },
        )
    }

    @Test
    fun draftHandlerAndEveryResultStringStayStableAndRedacted() {
        val draft = draft()
        val completed = ReferencePickerResult.Completed(
            ReferencePoseImportResult.Succeeded("pose-result-secret", 4),
        )
        val values = listOf(
            draft,
            ReferencePickerResult.Cancelled,
            ReferencePickerResult.InvalidSelection,
            completed,
            ReferencePickerResult.ReconciliationRequired,
        )

        values.forEach(::assertRedacted)
        assertEquals("ReferencePickerImportDraft(redacted)", draft.toString())
        assertEquals("ReferencePickerResult.Cancelled(redacted)", ReferencePickerResult.Cancelled.toString())
        assertEquals(
            "ReferencePickerResult.InvalidSelection(redacted)",
            ReferencePickerResult.InvalidSelection.toString(),
        )
        assertEquals("ReferencePickerResult.Completed(redacted)", completed.toString())
        assertEquals(
            "ReferencePickerResult.ReconciliationRequired(redacted)",
            ReferencePickerResult.ReconciliationRequired.toString(),
        )
    }

    private fun assertRedacted(value: Any) {
        val rendered = value.toString()
        listOf(URI_MARKER, TOKEN, LABEL, "pose-result-secret", "content://").forEach { secret ->
            assertFalse("Rendered value exposed $secret: $rendered", rendered.contains(secret))
        }
    }

    private fun draft(): ReferencePickerImportDraft = ReferencePickerImportDraft(
        importToken = ReferenceImportToken(TOKEN),
        shootId = "shoot-11b",
        poseId = "pose-11b",
        label = LABEL,
        mirrorAllowed = true,
        timeline = ReferenceImportLedgerTimeline(
            reservedAtEpochMillis = 101L,
            writingTempAtEpochMillis = 102L,
            tempSyncedAtEpochMillis = 103L,
            finalRenamePendingSyncAtEpochMillis = 104L,
            finalDurableAtEpochMillis = 105L,
            assetReadyAtEpochMillis = 106L,
            committedAtEpochMillis = 107L,
            cleanupRequiredAtEpochMillis = 108L,
            cleanupPendingSyncAtEpochMillis = 109L,
            cleanedDurableAtEpochMillis = 110L,
            quarantineRequiredAtEpochMillis = 111L,
            quarantinePendingSyncAtEpochMillis = 112L,
            quarantineDurableAtEpochMillis = 113L,
            reconciliationMarkedAtEpochMillis = 114L,
            failureSettledAtEpochMillis = 115L,
        ),
    )

    private fun byteSource(): ReferenceAssetByteSource = ReferenceAssetByteSource {
        ByteArrayInputStream(byteArrayOf(11, 12, 13))
    }

    private companion object {
        const val VALID_URI = "content://task11b.test/reference"
        const val URI_MARKER = "task11b.test/reference"
        const val TOKEN = "token-11b-secret"
        const val LABEL = "label-11b-secret"
    }
}
