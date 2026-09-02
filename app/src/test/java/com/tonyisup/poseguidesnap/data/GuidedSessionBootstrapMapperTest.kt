package com.tonyisup.poseguidesnap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedSessionBootstrapMapperTest {
    @Test
    fun canonicalReachableNonzeroAuthorityMapsToReadyCompleteSnapshot() {
        val result = GuidedSessionBootstrapMapper.map(canonicalReadyRows())

        assertTrue(result is GuidedSessionBootstrapResult.Ready)
        result as GuidedSessionBootstrapResult.Ready
        assertEquals(
            GuidedSessionSnapshot(
                sessionId = SESSION_ID,
                shootId = SHOOT_ID,
                lifecycle = GuidedSessionLifecycle.ACTIVE,
                orderedPoseIds = listOf("pose-0", "pose-1", "pose-2"),
                poseCount = 3,
                currentPoseIndex = 1,
                nextAttemptNumber = 1L,
                deletionGeneration = 0L,
                attemptCount = 1,
                confirmedAttemptCount = 1,
                appliedReceiptTokens = listOf("token-0"),
                unresolvedExportCount = 3,
                blockingAttempt = null,
            ),
            result.snapshot,
        )
    }

    @Test
    fun registeredAndCapturingFinalAttemptsMapToCompleteReconciliationSnapshots() {
        listOf("REGISTERED", "CAPTURING").forEach { state ->
            val result = GuidedSessionBootstrapMapper.map(blockingRows(state))

            assertTrue(result is GuidedSessionBootstrapResult.ReconciliationRequired)
            result as GuidedSessionBootstrapResult.ReconciliationRequired
            assertEquals(3, result.snapshot.poseCount)
            assertEquals(0, result.snapshot.currentPoseIndex)
            assertEquals(1L, result.snapshot.nextAttemptNumber)
            assertEquals(1, result.snapshot.attemptCount)
            assertEquals(0, result.snapshot.confirmedAttemptCount)
            assertEquals(0, result.snapshot.unresolvedExportCount)
            assertEquals(
                GuidedBlockingAttemptSummary(
                    commandToken = "token-0",
                    poseId = "pose-0",
                    attemptNumber = 0L,
                    poseIndex = 0,
                    trigger = GuidedCaptureTrigger.MANUAL,
                    state = GuidedCaptureAttemptState.valueOf(state),
                    reconciliationRequired = false,
                    deletionGeneration = 0L,
                    createdAtEpochMillis = 10L,
                    updatedAtEpochMillis = 10L,
                ),
                result.snapshot.blockingAttempt,
            )
        }
    }

    @Test
    fun completedAuthorityRequiresOneConfirmedAttemptPerPoseAndNullFinalReceipt() {
        val result = GuidedSessionBootstrapMapper.map(completedRows())

        assertTrue(result is GuidedSessionBootstrapResult.Completed)
        result as GuidedSessionBootstrapResult.Completed
        assertEquals(GuidedSessionLifecycle.COMPLETED, result.snapshot.lifecycle)
        assertEquals(3, result.snapshot.poseCount)
        assertEquals(2, result.snapshot.currentPoseIndex)
        assertEquals(3L, result.snapshot.nextAttemptNumber)
        assertEquals(3, result.snapshot.confirmedAttemptCount)
        assertEquals(9, result.snapshot.unresolvedExportCount)
        assertNull(result.snapshot.blockingAttempt)
    }

    @Test
    fun completelyAbsentAuthorityMapsToUnknownSessionButOrphansAreRejected() {
        assertEquals(
            GuidedSessionBootstrapResult.UnknownSession,
            GuidedSessionBootstrapMapper.map(
                GuidedSessionBootstrapRows(shoot = null, session = null),
            ),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.ORPHANED_AUTHORITY,
            GuidedSessionBootstrapRows(
                shoot = null,
                session = null,
                poses = listOf(pose(0)),
            ),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.ORPHANED_AUTHORITY,
            canonicalReadyRows().copyRows(session = null),
        )
    }

    @Test
    fun completeExportVocabularyHasExactStateSpecificFactsAndUnresolvedClassification() {
        val cases = listOf(
            ExportFacts("PENDING", null, null, "NONE", unresolved = true),
            ExportFacts("CLAIMED", "claim-secret", null, "NONE", unresolved = true),
            ExportFacts("CREATE_STARTED", "claim-secret", null, "NONE", unresolved = true),
            ExportFacts(
                "URI_KNOWN",
                "claim-secret",
                "content://media/external/images/media/1",
                "NONE",
                unresolved = true,
            ),
            ExportFacts(
                "EXPORTED",
                "claim-secret",
                "content://media/external/images/media/1",
                "NONE",
                unresolved = false,
            ),
            ExportFacts("AMBIGUOUS", "claim-secret", null, "INSERT_UNKNOWN", unresolved = true),
            ExportFacts("CANCELLED", null, null, "NONE", unresolved = false),
        )

        cases.forEach { facts ->
            val rows = canonicalReadyRows().withExports { output -> output.withFacts(facts) }
            val result = GuidedSessionBootstrapMapper.map(rows)
            assertTrue("Expected Ready for ${facts.state}, got $result", result is GuidedSessionBootstrapResult.Ready)
            result as GuidedSessionBootstrapResult.Ready
            assertEquals(if (facts.unresolved) 3 else 0, result.snapshot.unresolvedExportCount)
        }
    }

    @Test
    fun exportClaimUriAmbiguityAndTargetCoherenceAreValidatedWithoutCrossingResult() {
        val invalid = listOf(
            ExportFacts("PENDING", "claim-secret", null, "NONE", true),
            ExportFacts("CANCELLED", null, "content://media/secret", "NONE", false),
            ExportFacts("CLAIMED", null, null, "NONE", true),
            ExportFacts("CREATE_STARTED", "claim-secret", "content://media/secret", "NONE", true),
            ExportFacts("URI_KNOWN", "claim-secret", "file:///private/secret", "NONE", true),
            ExportFacts("EXPORTED", "claim-secret", "content://media/secret", "INSERT_UNKNOWN", false),
            ExportFacts("AMBIGUOUS", "claim-secret", null, "NONE", true),
        )
        invalid.forEach { facts ->
            assertRejected(
                GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY,
                canonicalReadyRows().withExports { it.withFacts(facts) },
            )
        }
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY,
            canonicalReadyRows().withExports {
                it.copy(targetCollectionUri = "content://media/external_primary/images/media/42")
            },
        )
    }

    @Test
    fun privateAndMediaPathsRejectDrivePrefixesAndVolumesRequireAscii() {
        val canonical = canonicalReadyRows()
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_PRIVATE_OUTPUT_AUTHORITY,
            canonical.copyRows(
                privateOutputs = canonical.privateOutputs.mapIndexed { index, row ->
                    if (index == 0) row.copy(relativePath = "C:/captures/photo.jpg") else row
                },
            ),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY,
            canonical.withExports { it.copy(intendedRelativePath = "C:/Pictures/PoseGuideSnap/") },
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY,
            canonical.withExports {
                it.copy(
                    targetVolume = "éxternal",
                    targetCollectionUri = "content://media/éxternal/images/media",
                )
            },
        )
    }

    @Test
    fun ownershipIdsUseAsciiSegmentPolicyButCaptureTokenRemainsOpaque() {
        val canonical = canonicalReadyRows()
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_SHOOT_AUTHORITY,
            canonical.copyRows(
                shoot = canonical.shoot!!.copy(shootId = "shoot-é"),
                session = canonical.session!!.copy(shootId = "shoot-é"),
                poses = canonical.poses.map { it.copy(shootId = "shoot-é") },
            ),
        )

        val opaqueToken = "../private/name"
        val opaqueRows = canonical.copyRows(
            attempts = canonical.attempts.map { it.copy(commandToken = opaqueToken) },
            privateOutputs = canonical.privateOutputs.map { it.copy(commandToken = opaqueToken) },
            receipts = canonical.receipts.map { it.copy(commandToken = opaqueToken) },
            outboxes = canonical.outboxes.map { it.copy(commandToken = opaqueToken) },
            exportOutputs = canonical.exportOutputs.map { it.copy(commandToken = opaqueToken) },
        )
        val result = GuidedSessionBootstrapMapper.map(opaqueRows)
        assertTrue(result is GuidedSessionBootstrapResult.Ready)
        result as GuidedSessionBootstrapResult.Ready
        assertEquals(listOf(opaqueToken), result.snapshot.appliedReceiptTokens)
        assertTrue(!result.toString().contains(opaqueToken))
    }

    @Test
    fun attemptsMustUniquelyAndContiguouslyCoverCounterAndPoseHistory() {
        val canonical = canonicalReadyRows()
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY,
            canonical.copyRows(session = canonical.session!!.copy(nextAttemptNumber = 2L)),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY,
            canonical.copyRows(
                attempts = canonical.attempts + canonical.attempts.single().copy(commandToken = "token-other"),
            ),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY,
            canonical.copyRows(attempts = listOf(canonical.attempts.single().copy(poseIndex = 1))),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY,
            canonical.copyRows(attempts = listOf(canonical.attempts.single().copy(poseId = "pose-1"))),
        )
    }

    @Test
    fun everyConfirmedAttemptOwnsExactReceiptPrivateOutboxAndExportCardinality() {
        val canonical = canonicalReadyRows()
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_PRIVATE_OUTPUT_AUTHORITY,
            canonical.copyRows(privateOutputs = canonical.privateOutputs.dropLast(1)),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_PRIVATE_OUTPUT_AUTHORITY,
            canonical.copyRows(
                privateOutputs = canonical.privateOutputs.mapIndexed { index, row ->
                    if (index == 2) row.copy(burstOrdinal = 1) else row
                },
            ),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_RECEIPT_AUTHORITY,
            canonical.copyRows(receipts = emptyList()),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_OUTBOX_AUTHORITY,
            canonical.copyRows(outboxes = emptyList()),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY,
            canonical.copyRows(exportOutputs = canonical.exportOutputs.dropLast(1)),
        )
    }

    @Test
    fun unconfirmedBlockingAttemptMustOwnNoConfirmedChildAuthority() {
        val blocking = blockingRows("REGISTERED")
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_PRIVATE_OUTPUT_AUTHORITY,
            blocking.copyRows(privateOutputs = privateOutputs("token-0", 5L)),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_RECEIPT_AUTHORITY,
            blocking.copyRows(receipts = listOf(receipt("token-0", 0, 1, 5L))),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_OUTBOX_AUTHORITY,
            blocking.copyRows(outboxes = listOf(outbox("token-0", 5L))),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY,
            blocking.copyRows(exportOutputs = exportOutputs("token-0", 5L)),
        )
    }

    @Test
    fun soleBlockingAttemptMustBeFinalCurrentGenerationEntryAfterAllEarlierPoses() {
        val completedFirst = canonicalReadyRows()
        val blocking = attempt(
            number = 1L,
            poseIndex = 1,
            state = "CAPTURING",
            createdAt = 40L,
            updatedAt = 45L,
            confirmedAt = null,
        )
        val valid = completedFirst.copyRows(
            session = completedFirst.session!!.copy(nextAttemptNumber = 2L, updatedAtEpochMillis = 45L),
            attempts = completedFirst.attempts + blocking,
        )
        assertTrue(
            GuidedSessionBootstrapMapper.map(valid) is
                GuidedSessionBootstrapResult.ReconciliationRequired,
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY,
            valid.copyRows(attempts = valid.attempts.reversed()),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY,
            valid.copyRows(
                attempts = listOf(valid.attempts[0].copy(lifecycleState = "REGISTERED"), valid.attempts[1]),
            ),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY,
            valid.copyRows(attempts = listOf(valid.attempts[0], valid.attempts[1].copy(poseIndex = 2))),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY,
            valid.copyRows(
                attempts = listOf(
                    valid.attempts[0],
                    valid.attempts[1].copy(capturedDeletionGeneration = 1L),
                ),
            ),
        )
    }

    @Test
    fun readyAndCompletedLifecycleRulesRejectUnreachableCountersAndFinalReceipts() {
        val ready = canonicalReadyRows()
        assertRejected(
            GuidedSessionBootstrapRejectionReason.AUTHORITY_INCONSISTENT,
            ready.copyRows(session = ready.session!!.copy(nextAttemptNumber = Long.MAX_VALUE)),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.AUTHORITY_INCONSISTENT,
            ready.copyRows(session = ready.session!!.copy(currentPoseIndex = 2)),
        )
        val completed = completedRows()
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_RECEIPT_AUTHORITY,
            completed.copyRows(
                receipts = completed.receipts.mapIndexed { index, row ->
                    if (index == 2) row.copy(toPoseIndex = 2) else row
                },
            ),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.AUTHORITY_INCONSISTENT,
            completed.copyRows(session = completed.session!!.copy(nextAttemptNumber = 2L)),
        )
    }

    @Test
    fun ownershipGenerationAndTimestampOrderingAreFailClosed() {
        val canonical = canonicalReadyRows()
        val mutations = listOf(
            GuidedSessionBootstrapRejectionReason.INVALID_SHOOT_AUTHORITY to
                canonical.copyRows(shoot = canonical.shoot!!.copy(createdAtEpochMillis = 31L)),
            GuidedSessionBootstrapRejectionReason.INVALID_SESSION_AUTHORITY to
                canonical.copyRows(session = canonical.session!!.copy(shootId = "shoot-other")),
            GuidedSessionBootstrapRejectionReason.INVALID_POSE_AUTHORITY to
                canonical.copyRows(poses = canonical.poses.map { it.copy(shootId = "shoot-other") }),
            GuidedSessionBootstrapRejectionReason.INVALID_ATTEMPT_AUTHORITY to
                canonical.copyRows(
                    attempts = canonical.attempts.map { it.copy(createdAtEpochMillis = 31L) },
                ),
            GuidedSessionBootstrapRejectionReason.INVALID_PRIVATE_OUTPUT_AUTHORITY to
                canonical.copyRows(
                    privateOutputs = canonical.privateOutputs.map { it.copy(capturedAtEpochMillis = 31L) },
                ),
            GuidedSessionBootstrapRejectionReason.INVALID_RECEIPT_AUTHORITY to
                canonical.copyRows(
                    receipts = canonical.receipts.map { it.copy(appliedDeletionGeneration = 1L) },
                ),
            GuidedSessionBootstrapRejectionReason.INVALID_OUTBOX_AUTHORITY to
                canonical.copyRows(
                    outboxes = canonical.outboxes.map { it.copy(createdAtEpochMillis = 31L) },
                ),
            GuidedSessionBootstrapRejectionReason.INVALID_EXPORT_AUTHORITY to
                canonical.copyRows(
                    exportOutputs = canonical.exportOutputs.map { it.copy(deletionGeneration = 1L) },
                ),
        )
        mutations.forEach { (reason, rows) -> assertRejected(reason, rows) }
    }

    @Test
    fun legacyValidPoseAuthorityRemainsReachableWithoutPrivateAssetMetadata() {
        val canonical = canonicalReadyRows()
        val legacyRows = canonical.copyRows(
            poses = canonical.poses.map { pose ->
                pose.copy(
                    referenceAssetPath = null,
                    validationState = "VALID",
                    detectorMetadata = null,
                    modelMetadata = null,
                    preprocessingMetadata = null,
                    landmarkPayload = null,
                    coordinateMetadata = null,
                )
            },
        )

        assertTrue(
            GuidedSessionBootstrapMapper.map(legacyRows) is GuidedSessionBootstrapResult.Ready,
        )
    }

    @Test
    fun unsupportedOrMalformedLifecycleAndPoseAuthorityAreTypedRejections() {
        val canonical = canonicalReadyRows()
        assertRejected(
            GuidedSessionBootstrapRejectionReason.UNSUPPORTED_LIFECYCLE,
            canonical.copyRows(session = canonical.session!!.copy(lifecycleState = "DELETING")),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.UNSUPPORTED_LIFECYCLE,
            canonical.copyRows(shoot = canonical.shoot!!.copy(lifecycleState = "DELETING")),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_POSE_AUTHORITY,
            canonical.copyRows(poses = canonical.poses.dropLast(1)),
        )
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_POSE_AUTHORITY,
            canonical.copyRows(
                poses = canonical.poses.mapIndexed { index, row ->
                    if (index == 2) row.copy(poseIndex = 1) else row
                },
            ),
        )
    }

    private fun assertRejected(
        expected: GuidedSessionBootstrapRejectionReason,
        rows: GuidedSessionBootstrapRows,
    ) {
        val result = GuidedSessionBootstrapMapper.map(rows)
        assertEquals(
            GuidedSessionBootstrapResult.Rejected(expected),
            result,
        )
    }

    private fun canonicalReadyRows(): GuidedSessionBootstrapRows = authorityRows(
        currentPoseIndex = 1,
        nextAttemptNumber = 1L,
        lifecycleState = "ACTIVE",
        attempts = listOf(attempt(0L, 0, "CONFIRMED", 10L, 30L, 30L)),
    )

    private fun blockingRows(state: String): GuidedSessionBootstrapRows = authorityRows(
        currentPoseIndex = 0,
        nextAttemptNumber = 1L,
        lifecycleState = "ACTIVE",
        attempts = listOf(attempt(0L, 0, state, 10L, 10L, null)),
    )

    private fun completedRows(): GuidedSessionBootstrapRows = authorityRows(
        currentPoseIndex = 2,
        nextAttemptNumber = 3L,
        lifecycleState = "COMPLETED",
        attempts = (0..2).map { index ->
            attempt(
                number = index.toLong(),
                poseIndex = index,
                state = "CONFIRMED",
                createdAt = index * 20L + 10L,
                updatedAt = index * 20L + 20L,
                confirmedAt = index * 20L + 20L,
            )
        },
    )

    private fun authorityRows(
        currentPoseIndex: Int,
        nextAttemptNumber: Long,
        lifecycleState: String,
        attempts: List<GuidedAttemptAuthorityRow>,
    ): GuidedSessionBootstrapRows {
        val confirmed = attempts.filter { it.lifecycleState == "CONFIRMED" }
        val finalTime = attempts.maxOfOrNull(GuidedAttemptAuthorityRow::updatedAtEpochMillis) ?: 0L
        return GuidedSessionBootstrapRows(
            shoot = GuidedShootAuthorityRow(
                shootId = SHOOT_ID,
                name = "Secret shoot label",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = finalTime,
                lifecycleState = "ACTIVE",
                deletionGeneration = 0L,
            ),
            session = GuidedSessionAuthorityRow(
                sessionId = SESSION_ID,
                shootId = SHOOT_ID,
                currentPoseIndex = currentPoseIndex,
                nextAttemptNumber = nextAttemptNumber,
                lifecycleState = lifecycleState,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = finalTime,
            ),
            poses = (0..2).map(::pose),
            attempts = attempts,
            privateOutputs = confirmed.flatMap { attempt ->
                privateOutputs(attempt.commandToken, attempt.confirmedAtEpochMillis!! - 5L)
            },
            receipts = confirmed.map { attempt ->
                receipt(
                    token = attempt.commandToken,
                    fromPose = attempt.poseIndex,
                    toPose = if (attempt.poseIndex == 2) null else attempt.poseIndex + 1,
                    at = attempt.confirmedAtEpochMillis!!,
                )
            },
            outboxes = confirmed.map { attempt ->
                outbox(attempt.commandToken, attempt.confirmedAtEpochMillis!!)
            },
            exportOutputs = confirmed.flatMap { attempt ->
                exportOutputs(attempt.commandToken, attempt.confirmedAtEpochMillis!!)
            },
        )
    }

    private fun pose(index: Int) = GuidedPoseAuthorityRow(
        shootId = SHOOT_ID,
        poseIndex = index,
        poseId = "pose-$index",
        label = "Secret pose label $index",
        referenceAssetPath = "reference-assets/assets/${index.toString().padStart(64, '0')}.asset",
        mirrorAllowed = index % 2 == 0,
        validationState = "VALIDATED",
        detectorMetadata = "detector-secret",
        modelMetadata = "model-secret",
        preprocessingMetadata = "preprocess-secret",
        landmarkPayload = "v1|landmark-secret",
        coordinateMetadata = "coordinate-secret",
    )

    private fun attempt(
        number: Long,
        poseIndex: Int,
        state: String,
        createdAt: Long,
        updatedAt: Long,
        confirmedAt: Long?,
    ) = GuidedAttemptAuthorityRow(
        commandToken = "token-$number",
        sessionId = SESSION_ID,
        poseId = "pose-$poseIndex",
        poseIndex = poseIndex,
        attemptNumber = number,
        triggerType = if (number % 2L == 0L) "MANUAL" else "AUTOMATIC",
        lifecycleState = state,
        reconciliationRequired = false,
        capturedDeletionGeneration = 0L,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = updatedAt,
        confirmedAtEpochMillis = confirmedAt,
    )

    private fun privateOutputs(token: String, capturedAt: Long) = (0..2).map { ordinal ->
        GuidedPrivateOutputAuthorityRow(
            commandToken = token,
            burstOrdinal = ordinal,
            relativePath = "captures/private-$token-$ordinal.jpg",
            byteCount = 100L + ordinal,
            durabilityState = "DURABLE",
            capturedAtEpochMillis = capturedAt,
            integrityMetadata = "integrity-secret-$ordinal",
        )
    }

    private fun receipt(token: String, fromPose: Int, toPose: Int?, at: Long) =
        GuidedReceiptAuthorityRow(
            commandToken = token,
            fromPoseIndex = fromPose,
            toPoseIndex = toPose,
            appliedDeletionGeneration = 0L,
            appliedAtEpochMillis = at,
        )

    private fun outbox(token: String, at: Long) = GuidedOutboxAuthorityRow(
        commandToken = token,
        lifecycleState = "PENDING",
        createdAtEpochMillis = at,
        updatedAtEpochMillis = at,
        retryMetadata = null,
    )

    private fun exportOutputs(token: String, at: Long) = (0..2).map { ordinal ->
        GuidedExportOutputAuthorityRow(
            commandToken = token,
            burstOrdinal = ordinal,
            targetCollectionUri = "content://media/external_primary/images/media",
            targetVolume = "external_primary",
            intendedDisplayName = "secret-$token-$ordinal.jpg",
            intendedRelativePath = "Pictures/PoseGuideSnap/",
            intendedMimeType = "image/jpeg",
            lifecycleState = "PENDING",
            claimToken = null,
            mediaUriString = null,
            ambiguityState = "NONE",
            deletionGeneration = 0L,
            createdAtEpochMillis = at,
            updatedAtEpochMillis = at,
        )
    }

    private fun GuidedSessionBootstrapRows.withExports(
        transform: (GuidedExportOutputAuthorityRow) -> GuidedExportOutputAuthorityRow,
    ): GuidedSessionBootstrapRows = copyRows(exportOutputs = exportOutputs.map(transform))

    private fun GuidedExportOutputAuthorityRow.withFacts(facts: ExportFacts) = copy(
        lifecycleState = facts.state,
        claimToken = facts.claim,
        mediaUriString = facts.uri,
        ambiguityState = facts.ambiguity,
    )

    private fun GuidedSessionBootstrapRows.copyRows(
        shoot: GuidedShootAuthorityRow? = this.shoot,
        session: GuidedSessionAuthorityRow? = this.session,
        poses: List<GuidedPoseAuthorityRow> = this.poses,
        attempts: List<GuidedAttemptAuthorityRow> = this.attempts,
        privateOutputs: List<GuidedPrivateOutputAuthorityRow> = this.privateOutputs,
        receipts: List<GuidedReceiptAuthorityRow> = this.receipts,
        outboxes: List<GuidedOutboxAuthorityRow> = this.outboxes,
        exportOutputs: List<GuidedExportOutputAuthorityRow> = this.exportOutputs,
    ) = GuidedSessionBootstrapRows(
        shoot = shoot,
        session = session,
        poses = poses,
        attempts = attempts,
        privateOutputs = privateOutputs,
        receipts = receipts,
        outboxes = outboxes,
        exportOutputs = exportOutputs,
    )

    private data class ExportFacts(
        val state: String,
        val claim: String?,
        val uri: String?,
        val ambiguity: String,
        val unresolved: Boolean,
    )

    private companion object {
        const val SHOOT_ID = "shoot-safe"
        const val SESSION_ID = "session-safe"
    }
}
