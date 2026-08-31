package com.tonyisup.poseguidesnap.importer

import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonyisup.poseguidesnap.data.ReferenceAssetByteSource
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Compile-only: uses a synthetic callback URI and injected bytes; it never reads a provider or device. */
@RunWith(AndroidJUnit4::class)
class ReferencePickerResultHandlerAndroidTest {
    @Test
    fun syntheticContentCallbackDispatchesOffMainAndReturnsOnlyRedactedResults() = runBlocking {
        val uri = Uri.parse("content://task11b.test/reference")
        val mainThread = Looper.getMainLooper().thread
        val factoryThread = AtomicReference<Thread>()
        val importerThread = AtomicReference<Thread>()
        val factoryCalls = AtomicInteger()
        val importerCalls = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "task11b-android-picker-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val source = ReferenceAssetByteSource {
                ByteArrayInputStream(byteArrayOf(11, 12, 13))
            }
            val handler = ReferencePickerResultHandler(
                importer = JournaledReferencePickerImporterPort { request ->
                    importerCalls.incrementAndGet()
                    importerThread.set(Thread.currentThread())
                    assertSame(source, request.source)
                    ReferencePoseImportResult.Succeeded(request.poseId, request.poseIndex)
                },
                sourceFactory = ReferencePickerByteSourceFactory { selected ->
                    factoryCalls.incrementAndGet()
                    factoryThread.set(Thread.currentThread())
                    assertSame(uri, selected)
                    source
                },
                dispatcher = dispatcher,
            )

            val completed = handler.handle(uri, draft())
            val cancelled = handler.handle(null, draft())
            val invalid = handler.handle(Uri.parse("file:///private/reference.jpg"), draft())

            assertTrue(completed is ReferencePickerResult.Completed)
            assertSame(ReferencePickerResult.Cancelled, cancelled)
            assertSame(ReferencePickerResult.InvalidSelection, invalid)
            assertEquals(1, factoryCalls.get())
            assertEquals(1, importerCalls.get())
            assertNotSame(mainThread, factoryThread.get())
            assertNotSame(mainThread, importerThread.get())
            listOf(
                completed,
                cancelled,
                invalid,
                ReferencePickerResult.ReconciliationRequired,
                draft(),
            ).forEach { value ->
                val rendered = value.toString()
                assertFalse(rendered.contains("content://"))
                assertFalse(rendered.contains("token-android-secret"))
                assertFalse(rendered.contains("label-android-secret"))
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun draft(): ReferencePickerImportDraft = ReferencePickerImportDraft(
        importToken = ReferenceImportToken("token-android-secret"),
        shootId = "shoot-android",
        poseId = "pose-android",
        poseIndex = 2,
        label = "label-android-secret",
        mirrorAllowed = false,
        restartCleanedImport = true,
        timeline = ReferenceImportLedgerTimeline(
            reservedAtEpochMillis = 1L,
            writingTempAtEpochMillis = 2L,
            tempSyncedAtEpochMillis = 3L,
            finalRenamePendingSyncAtEpochMillis = 4L,
            finalDurableAtEpochMillis = 5L,
            assetReadyAtEpochMillis = 6L,
            committedAtEpochMillis = 7L,
            cleanupRequiredAtEpochMillis = 8L,
            cleanupPendingSyncAtEpochMillis = 9L,
            cleanedDurableAtEpochMillis = 10L,
            quarantineRequiredAtEpochMillis = 11L,
            quarantinePendingSyncAtEpochMillis = 12L,
            quarantineDurableAtEpochMillis = 13L,
            reconciliationMarkedAtEpochMillis = 14L,
            failureSettledAtEpochMillis = 15L,
        ),
    )
}
