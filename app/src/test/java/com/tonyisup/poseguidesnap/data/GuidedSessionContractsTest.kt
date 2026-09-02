package com.tonyisup.poseguidesnap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedSessionContractsTest {
    @Test
    fun persistedVocabulariesAreClosedAndExact() {
        assertEquals(
            listOf("ACTIVE", "COMPLETED"),
            GuidedSessionLifecycle.entries.map(Enum<*>::name),
        )
        assertEquals(
            listOf("REGISTERED", "CAPTURING", "CONFIRMED"),
            GuidedCaptureAttemptState.entries.map(Enum<*>::name),
        )
        assertEquals(
            listOf(
                "PENDING",
                "CLAIMED",
                "CREATE_STARTED",
                "URI_KNOWN",
                "EXPORTED",
                "AMBIGUOUS",
                "CANCELLED",
            ),
            GuidedExportState.entries.map(Enum<*>::name),
        )
    }

    @Test
    fun bootstrapRowsSnapshotCallerCollectionsAndRedactEveryPersistedString() {
        val secret = "<SECRET:bootstrap>"
        val poses = mutableListOf(pose(secret))
        val rows = GuidedSessionBootstrapRows(
            shoot = shoot(secret),
            session = session(secret),
            poses = poses,
            attempts = listOf(attempt(secret)),
            privateOutputs = listOf(privateOutput(secret)),
            receipts = listOf(receipt(secret)),
            outboxes = listOf(outbox(secret)),
            exportOutputs = listOf(exportOutput(secret)),
        )
        poses.clear()

        assertEquals(1, rows.poses.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (rows.poses as MutableList<GuidedPoseAuthorityRow>).clear()
        }
        listOf(
            rows,
            rows.shoot!!,
            rows.session!!,
            rows.poses.single(),
            rows.attempts.single(),
            rows.privateOutputs.single(),
            rows.receipts.single(),
            rows.outboxes.single(),
            rows.exportOutputs.single(),
        ).forEach { value ->
            val rendered = value.toString()
            assertTrue(rendered.endsWith("(redacted)"))
            assertFalse(rendered.contains(secret))
            assertFalse(rendered.contains("content://"))
            assertFalse(rendered.contains("/private/"))
        }
    }

    @Test
    fun resultVariantsEnforceTheirBlockingAndLifecycleShape() {
        val ready = snapshot()
        val completed = ready.copy(lifecycle = GuidedSessionLifecycle.COMPLETED)
        val blocked = ready.copy(
            nextAttemptNumber = 2L,
            attemptCount = 2,
            blockingAttempt = GuidedBlockingAttemptSummary(
                commandToken = "token-1",
                poseId = "pose-1",
                attemptNumber = 1L,
                poseIndex = 1,
                trigger = GuidedCaptureTrigger.AUTOMATIC,
                state = GuidedCaptureAttemptState.REGISTERED,
                reconciliationRequired = false,
                deletionGeneration = 0L,
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 10L,
            ),
        )

        assertEquals(ready, GuidedSessionBootstrapResult.Ready(ready).snapshot)
        assertEquals(completed, GuidedSessionBootstrapResult.Completed(completed).snapshot)
        assertEquals(
            blocked,
            GuidedSessionBootstrapResult.ReconciliationRequired(blocked).snapshot,
        )
        assertThrows(IllegalArgumentException::class.java) {
            GuidedSessionBootstrapResult.Ready(blocked)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuidedSessionBootstrapResult.Completed(ready)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuidedSessionBootstrapResult.ReconciliationRequired(ready)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuidedBlockingAttemptSummary(
                commandToken = "token-0",
                poseId = "pose-0",
                attemptNumber = 0L,
                poseIndex = 0,
                trigger = GuidedCaptureTrigger.MANUAL,
                state = GuidedCaptureAttemptState.CONFIRMED,
                reconciliationRequired = false,
                deletionGeneration = 0L,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
            )
        }
    }

    @Test
    fun publicResultsAreStableRedactedAndContainNoPersistedPayloadObjects() {
        val secretSession = "session-secret-123"
        val secretShoot = "shoot-secret-123"
        val readySnapshot = snapshot(sessionId = secretSession, shootId = secretShoot)
        val blockedSnapshot = readySnapshot.copy(
            nextAttemptNumber = 2L,
            attemptCount = 2,
            blockingAttempt = GuidedBlockingAttemptSummary(
                commandToken = "token-1",
                poseId = "pose-1",
                attemptNumber = 1L,
                poseIndex = 1,
                trigger = GuidedCaptureTrigger.AUTOMATIC,
                state = GuidedCaptureAttemptState.CAPTURING,
                reconciliationRequired = false,
                deletionGeneration = 0L,
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 11L,
            ),
        )
        val values = listOf(
            GuidedSessionBootstrapResult.Ready(readySnapshot),
            GuidedSessionBootstrapResult.Completed(
                readySnapshot.copy(lifecycle = GuidedSessionLifecycle.COMPLETED),
            ),
            GuidedSessionBootstrapResult.ReconciliationRequired(blockedSnapshot),
            GuidedSessionBootstrapResult.UnknownSession,
            GuidedSessionBootstrapResult.Rejected(
                GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY,
            ),
            readySnapshot,
        )

        assertEquals(
            listOf(
                "GuidedSessionBootstrapResult.Ready(redacted)",
                "GuidedSessionBootstrapResult.Completed(redacted)",
                "GuidedSessionBootstrapResult.ReconciliationRequired(redacted)",
                "GuidedSessionBootstrapResult.UnknownSession",
                "GuidedSessionBootstrapResult.Rejected(reason=INVALID_EXPORT_AUTHORITY)",
                "GuidedSessionSnapshot(redacted)",
            ),
            values.map(Any::toString),
        )
        values.forEach { value ->
            val rendered = value.toString()
            listOf(secretSession, secretShoot, "content://", "/private/", "claim-").forEach { secret ->
                assertFalse(rendered.contains(secret))
            }
        }

        val publicSnapshotFields = GuidedSessionSnapshot::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        listOf("label", "path", "uri", "claim").forEach { forbidden ->
            assertTrue(publicSnapshotFields.none { it.contains(forbidden, ignoreCase = true) })
        }
        val publicResultFieldTypes = listOf(
            GuidedSessionBootstrapResult.Ready::class.java,
            GuidedSessionBootstrapResult.Completed::class.java,
            GuidedSessionBootstrapResult.ReconciliationRequired::class.java,
            GuidedSessionBootstrapResult.Rejected::class.java,
        ).flatMap { type -> type.declaredFields.map { it.type.name } }
        assertTrue(publicResultFieldTypes.none { it.contains("AuthorityRow") })
    }

    @Test
    fun publicSnapshotOwnsImmutableOrderedPoseAndReceiptIdentity() {
        val poses = mutableListOf("pose-0", "pose-1", "pose-2")
        val receipts = mutableListOf("token-0")
        val snapshot = snapshot(
            orderedPoseIds = poses,
            appliedReceiptTokens = receipts,
        )
        poses.clear()
        receipts.clear()

        assertEquals(listOf("pose-0", "pose-1", "pose-2"), snapshot.orderedPoseIds)
        assertEquals(listOf("token-0"), snapshot.appliedReceiptTokens)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.orderedPoseIds as MutableList<String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.appliedReceiptTokens as MutableList<String>).clear()
        }
    }

    @Test
    fun contractsAndMapperHaveNoAndroidOrRoomTypesInTheirJvmSurface() {
        val types = listOf(
            GuidedSessionBootstrapMapper::class.java,
            GuidedSessionBootstrapRows::class.java,
            GuidedSessionSnapshot::class.java,
            GuidedSessionBootstrapResult::class.java,
            GuidedShootAuthorityRow::class.java,
            GuidedSessionAuthorityRow::class.java,
            GuidedPoseAuthorityRow::class.java,
            GuidedAttemptAuthorityRow::class.java,
            GuidedPrivateOutputAuthorityRow::class.java,
            GuidedReceiptAuthorityRow::class.java,
            GuidedOutboxAuthorityRow::class.java,
            GuidedExportOutputAuthorityRow::class.java,
        )
        val names = types.flatMap { type ->
            buildList {
                add(type.name)
                type.declaredFields.forEach { add(it.type.name) }
                type.declaredMethods.forEach { method ->
                    add(method.returnType.name)
                    addAll(method.parameterTypes.map(Class<*>::getName))
                }
            }
        }
        assertTrue(names.none { name ->
            name.startsWith("android.") || name.startsWith("androidx.room.")
        })
    }

    private fun snapshot(
        sessionId: String = "session-safe",
        shootId: String = "shoot-safe",
        orderedPoseIds: List<String> = listOf("pose-0", "pose-1", "pose-2"),
        appliedReceiptTokens: List<String> = listOf("token-0"),
    ): GuidedSessionSnapshot = GuidedSessionSnapshot(
        sessionId = sessionId,
        shootId = shootId,
        lifecycle = GuidedSessionLifecycle.ACTIVE,
        orderedPoseIds = orderedPoseIds,
        poseCount = 3,
        currentPoseIndex = 1,
        nextAttemptNumber = 1L,
        deletionGeneration = 0L,
        attemptCount = 1,
        confirmedAttemptCount = 1,
        appliedReceiptTokens = appliedReceiptTokens,
        unresolvedExportCount = 3,
        blockingAttempt = null,
    )

    private fun shoot(secret: String) = GuidedShootAuthorityRow(
        shootId = secret,
        name = secret,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 1L,
        lifecycleState = "ACTIVE",
        deletionGeneration = 0L,
    )

    private fun session(secret: String) = GuidedSessionAuthorityRow(
        sessionId = secret,
        shootId = secret,
        currentPoseIndex = 0,
        nextAttemptNumber = 1L,
        lifecycleState = "ACTIVE",
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 1L,
    )

    private fun pose(secret: String) = GuidedPoseAuthorityRow(
        shootId = secret,
        poseIndex = 0,
        poseId = secret,
        label = secret,
        referenceAssetPath = "/private/$secret",
        mirrorAllowed = false,
        validationState = "VALIDATED",
        detectorMetadata = secret,
        modelMetadata = secret,
        preprocessingMetadata = secret,
        landmarkPayload = secret,
        coordinateMetadata = secret,
    )

    private fun attempt(secret: String) = GuidedAttemptAuthorityRow(
        commandToken = secret,
        sessionId = secret,
        poseId = secret,
        poseIndex = 0,
        attemptNumber = 0L,
        triggerType = "MANUAL",
        lifecycleState = "CONFIRMED",
        reconciliationRequired = false,
        capturedDeletionGeneration = 0L,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 1L,
        confirmedAtEpochMillis = 1L,
    )

    private fun privateOutput(secret: String) = GuidedPrivateOutputAuthorityRow(
        commandToken = secret,
        burstOrdinal = 0,
        relativePath = "/private/$secret",
        byteCount = 1L,
        durabilityState = "DURABLE",
        capturedAtEpochMillis = 0L,
        integrityMetadata = secret,
    )

    private fun receipt(secret: String) = GuidedReceiptAuthorityRow(
        commandToken = secret,
        fromPoseIndex = 0,
        toPoseIndex = 1,
        appliedDeletionGeneration = 0L,
        appliedAtEpochMillis = 1L,
    )

    private fun outbox(secret: String) = GuidedOutboxAuthorityRow(
        commandToken = secret,
        lifecycleState = "PENDING",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        retryMetadata = secret,
    )

    private fun exportOutput(secret: String) = GuidedExportOutputAuthorityRow(
        commandToken = secret,
        burstOrdinal = 0,
        targetCollectionUri = "content://media/$secret/images/media",
        targetVolume = secret,
        intendedDisplayName = secret,
        intendedRelativePath = "/private/$secret",
        intendedMimeType = secret,
        lifecycleState = "CLAIMED",
        claimToken = "claim-$secret",
        mediaUriString = "content://$secret",
        ambiguityState = secret,
        deletionGeneration = 0L,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
}
