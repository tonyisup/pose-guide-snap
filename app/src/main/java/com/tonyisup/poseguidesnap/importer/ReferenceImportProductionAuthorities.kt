package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.ReferenceImportPolicy
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Injectable raw identity authority kept behind the production composition boundary. */
internal fun interface ReferenceImportRawIdSource {
    fun nextId(): String
}

/** Injectable wall-time authority kept behind the production composition boundary. */
internal fun interface ReferenceImportRawTimeSource {
    fun nowEpochMillis(): Long
}

private object UuidReferenceImportRawIdSource : ReferenceImportRawIdSource {
    override fun nextId(): String = UUID.randomUUID().toString()

    override fun toString(): String = "UuidReferenceImportRawIdSource(redacted)"
}

private object SystemReferenceImportRawTimeSource : ReferenceImportRawTimeSource {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()

    override fun toString(): String = "SystemReferenceImportRawTimeSource(redacted)"
}

/** Production-owned opaque token and pose identity allocator. */
internal class ProductionReferenceImportIdentityProvider(
    private val rawIdSource: ReferenceImportRawIdSource,
) : ReferenceImportTokenProvider, ReferenceImportPoseIdProvider {
    internal constructor() : this(UuidReferenceImportRawIdSource)

    override fun nextToken(): ReferenceImportToken = ReferenceImportToken(nextSafeIdentity())

    override fun nextPoseId(): String = nextSafeIdentity()

    override fun toString(): String = "ProductionReferenceImportIdentityProvider(redacted)"

    private fun nextSafeIdentity(): String {
        val candidate = try {
            rawIdSource.nextId()
        } catch (_: Exception) {
            throw IllegalStateException("reference import identity authority is unavailable")
        }
        if (!ReferenceImportPolicy.validateOwnershipIdentity(candidate)) {
            throw IllegalStateException("reference import identity authority returned an invalid value")
        }
        return candidate
    }
}

/**
 * Allocates disjoint blocks of fifteen timestamps. The atomic high-water mark prevents
 * repeated calls, concurrent calls, and wall-clock rollback from reusing ledger time.
 */
internal class ProductionReferenceImportLedgerTimelineProvider(
    private val rawTimeSource: ReferenceImportRawTimeSource,
) : ReferenceImportLedgerTimelineProvider {
    internal constructor() : this(SystemReferenceImportRawTimeSource)

    private val lastAllocatedEpochMillis = AtomicLong(NO_ALLOCATION)

    override fun nextTimeline(): ReferenceImportLedgerTimeline {
        while (true) {
            val rawNow = try {
                rawTimeSource.nowEpochMillis()
            } catch (_: Exception) {
                throw IllegalStateException("reference import timeline authority is unavailable")
            }
            if (rawNow < 0L) {
                throw IllegalStateException("reference import timeline authority returned an invalid value")
            }

            val previousEnd = lastAllocatedEpochMillis.get()
            val start = if (previousEnd == NO_ALLOCATION) {
                rawNow
            } else {
                val afterPrevious = addExactOrFail(previousEnd, 1L)
                maxOf(rawNow, afterPrevious)
            }
            val end = addExactOrFail(start, TIMELINE_OFFSET)
            if (!lastAllocatedEpochMillis.compareAndSet(previousEnd, end)) continue
            return timelineStartingAt(start)
        }
    }

    override fun toString(): String = "ProductionReferenceImportLedgerTimelineProvider(redacted)"

    private fun timelineStartingAt(first: Long): ReferenceImportLedgerTimeline =
        ReferenceImportLedgerTimeline(
            reservedAtEpochMillis = first,
            writingTempAtEpochMillis = first + 1L,
            tempSyncedAtEpochMillis = first + 2L,
            finalRenamePendingSyncAtEpochMillis = first + 3L,
            finalDurableAtEpochMillis = first + 4L,
            assetReadyAtEpochMillis = first + 5L,
            committedAtEpochMillis = first + 6L,
            cleanupRequiredAtEpochMillis = first + 7L,
            cleanupPendingSyncAtEpochMillis = first + 8L,
            cleanedDurableAtEpochMillis = first + 9L,
            quarantineRequiredAtEpochMillis = first + 10L,
            quarantinePendingSyncAtEpochMillis = first + 11L,
            quarantineDurableAtEpochMillis = first + 12L,
            reconciliationMarkedAtEpochMillis = first + 13L,
            failureSettledAtEpochMillis = first + TIMELINE_OFFSET,
        )

    private fun addExactOrFail(value: Long, increment: Long): Long =
        try {
            Math.addExact(value, increment)
        } catch (_: ArithmeticException) {
            throw IllegalStateException("reference import timeline authority is exhausted")
        }

    private companion object {
        const val NO_ALLOCATION = -1L
        const val TIMELINE_OFFSET = 14L
    }
}
