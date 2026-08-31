package com.tonyisup.poseguidesnap.importer

import android.content.ContentResolver
import android.net.Uri
import com.tonyisup.poseguidesnap.data.ReferenceAssetByteSource
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Callback-independent import values. URI and byte-source authority never enter this object. */
class ReferencePickerImportDraft(
    val importToken: ReferenceImportToken,
    val shootId: String,
    val poseId: String,
    val label: String,
    val mirrorAllowed: Boolean,
    val timeline: ReferenceImportLedgerTimeline,
) {
    init {
        require(label.isNotBlank() && !label.contains("content://", ignoreCase = true)) {
            "reference picker label must be nonblank and URI-free"
        }
    }

    override fun toString(): String = "ReferencePickerImportDraft(redacted)"
}

/** The picker can invoke only the journal-backed coordinator contract. */
fun interface JournaledReferencePickerImporterPort {
    fun importReference(request: ReferencePoseImportRequest): ReferencePoseImportResult
}

/** Creates the exact one-shot source for one validated system-picker callback URI. */
fun interface ReferencePickerByteSourceFactory {
    fun create(uri: Uri): ReferenceAssetByteSource
}

/** Defers provider access until the asset store opens its injected source. */
class ContentResolverReferencePickerByteSourceFactory(
    private val contentResolver: ContentResolver,
) : ReferencePickerByteSourceFactory {
    override fun create(uri: Uri): ReferenceAssetByteSource {
        val opened = AtomicBoolean(false)
        return ReferenceAssetByteSource {
            if (!opened.compareAndSet(false, true)) {
                throw IllegalStateException("reference picker source already opened")
            }
            contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("reference picker source unavailable")
        }
    }

    override fun toString(): String = "ContentResolverReferencePickerByteSourceFactory(redacted)"
}

sealed interface ReferencePickerResult {
    data object Cancelled : ReferencePickerResult {
        override fun toString(): String = "ReferencePickerResult.Cancelled(redacted)"
    }

    data object InvalidSelection : ReferencePickerResult {
        override fun toString(): String = "ReferencePickerResult.InvalidSelection(redacted)"
    }

    class Completed(val importResult: ReferencePoseImportResult) : ReferencePickerResult {
        override fun toString(): String = "ReferencePickerResult.Completed(redacted)"
    }

    data object ReconciliationRequired : ReferencePickerResult {
        override fun toString(): String = "ReferencePickerResult.ReconciliationRequired(redacted)"
    }
}

/** Handles only the explicit system Photo Picker callback value; launching remains a separate task. */
class ReferencePickerResultHandler(
    private val importer: JournaledReferencePickerImporterPort,
    private val sourceFactory: ReferencePickerByteSourceFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun handle(uri: Uri?, draft: ReferencePickerImportDraft): ReferencePickerResult {
        if (uri == null) return ReferencePickerResult.Cancelled
        if (!isValidContentSelection(uri)) return ReferencePickerResult.InvalidSelection

        return try {
            withContext(dispatcher) {
                coroutineContext.ensureActive()
                val source = sourceFactory.create(uri)
                coroutineContext.ensureActive()
                ReferencePickerResult.Completed(
                    importer.importReference(
                        ReferencePoseImportRequest(
                            importToken = draft.importToken,
                            shootId = draft.shootId,
                            poseId = draft.poseId,
                            label = draft.label,
                            mirrorAllowed = draft.mirrorAllowed,
                            timeline = draft.timeline,
                            source = source,
                        ),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ReferencePickerResult.ReconciliationRequired
        }
    }

    override fun toString(): String = "ReferencePickerResultHandler(redacted)"

    private fun isValidContentSelection(uri: Uri): Boolean {
        return try {
            if (!uri.isHierarchical) {
                false
            } else if (!uri.scheme.equals(CONTENT_SCHEME, ignoreCase = true)) {
                false
            } else if (uri.authority.isNullOrBlank()) {
                false
            } else {
                val parsed = URI(uri.toString())
                parsed.isAbsolute &&
                    !parsed.isOpaque &&
                    parsed.scheme.equals(CONTENT_SCHEME, ignoreCase = true) &&
                    !parsed.rawAuthority.isNullOrBlank()
            }
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val CONTENT_SCHEME = "content"
    }
}
