package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun registeredAndCapturingRequireCoherentCaptureFileOperations() {
        listOf("REGISTERED", "CAPTURING").forEach { state ->
            assertReconciliation(blockingRows(state), state)
            assertReconciliation(blockingRows(state, OPAQUE_TOKEN), "$state opaque token")
        }

        val evidenceForbidden = setOf(
            CaptureFileOperationStage.EXPECTING_RESERVATION,
            CaptureFileOperationStage.WRITING_TEMP,
            CaptureFileOperationStage.CLEANED_DURABLE,
        )
        val evidenceOptional = setOf(
            CaptureFileOperationStage.CLEANUP_REQUIRED,
            CaptureFileOperationStage.CLEANUP_PENDING_SYNC,
        )
        val evidenceRequired = setOf(
            CaptureFileOperationStage.TEMP_SYNCED,
            CaptureFileOperationStage.FINAL_RENAME_PENDING_SYNC,
            CaptureFileOperationStage.FINAL_DURABLE,
            CaptureFileOperationStage.QUARANTINE_REQUIRED,
            CaptureFileOperationStage.QUARANTINE_PENDING_SYNC,
            CaptureFileOperationStage.QUARANTINE_DURABLE,
        )
        assertEquals(
            CaptureFileOperationStage.entries.toSet(),
            evidenceForbidden + evidenceOptional + evidenceRequired,
        )
        CaptureFileOperationStage.entries.forEach { stage ->
            val evidenceCases = when (stage) {
                in evidenceForbidden -> listOf(false)
                in evidenceOptional -> listOf(false, true)
                in evidenceRequired -> listOf(true)
                else -> error("Unclassified capture stage ${stage.name}")
            }
            evidenceCases.forEach { hasEvidence ->
                assertReconciliation(
                    capturingRowsForStage(stage, hasEvidence = hasEvidence),
                    "${stage.name}/${if (hasEvidence) "full" else "none"}",
                )
            }
        }
        CaptureFileFailureCode.entries.forEach { failureCode ->
            assertReconciliation(
                capturingRowsForStage(
                    stage = CaptureFileOperationStage.CLEANUP_REQUIRED,
                    hasEvidence = false,
                    failureCode = failureCode,
                ),
                failureCode.name,
            )
        }

        val commonRowMutations = listOf(
            CaptureRowMutation("final-path") {
                it.copy(relativeFinalPath = "capture-candidates/wrong.jpg")
            },
            CaptureRowMutation("temp-path") {
                it.copy(relativeTempPath = "capture-candidates/wrong.pending")
            },
            CaptureRowMutation("quarantine-path") {
                it.copy(relativeQuarantinePath = "capture-quarantine/wrong.quarantined")
            },
            CaptureRowMutation("unknown-stage") { it.copy(stage = "UNKNOWN_STAGE") },
            CaptureRowMutation("unexpected-byte-evidence") { it.copy(byteCount = 1L) },
            CaptureRowMutation("unexpected-hash-evidence") { it.copy(sha256 = SHA_256) },
            CaptureRowMutation("unexpected-captured-clock") {
                it.copy(capturedAtEpochMillis = it.createdAtEpochMillis)
            },
            CaptureRowMutation("partial-required-evidence") {
                it.copy(
                    stage = CaptureFileOperationStage.TEMP_SYNCED.name,
                    byteCount = 1L,
                    sha256 = null,
                    capturedAtEpochMillis = null,
                    updatedAtEpochMillis = it.createdAtEpochMillis + 1L,
                )
            },
            CaptureRowMutation("failure-without-reconciliation") {
                it.copy(lastFailureCode = CaptureFileFailureCode.WRITE_FAILED.name)
            },
            CaptureRowMutation("reconciliation-without-failure") {
                it.copy(reconciliationRequired = true)
            },
            CaptureRowMutation("captured-before-creation") {
                it.copy(
                    stage = CaptureFileOperationStage.TEMP_SYNCED.name,
                    byteCount = 100L,
                    sha256 = SHA_256,
                    capturedAtEpochMillis = it.createdAtEpochMillis - 1L,
                    updatedAtEpochMillis = it.createdAtEpochMillis + 1L,
                )
            },
            CaptureRowMutation("creation-earlier-than-attempt") {
                it.copy(createdAtEpochMillis = 9L, updatedAtEpochMillis = 9L)
            },
            CaptureRowMutation("creation-later-than-attempt") {
                it.copy(createdAtEpochMillis = 11L, updatedAtEpochMillis = 11L)
            },
            CaptureRowMutation("negative-creation-clock") {
                it.copy(createdAtEpochMillis = -1L, updatedAtEpochMillis = 0L)
            },
            CaptureRowMutation("row-clock-reversal") {
                it.copy(updatedAtEpochMillis = it.createdAtEpochMillis - 1L)
            },
            CaptureRowMutation("foreign-owner-token") {
                val foreignToken = "token-foreign"
                val foreignPaths = CaptureFileOperationPaths.forIdentity(
                    PrivateOutputIdentity(CaptureToken(foreignToken), it.burstOrdinal),
                )
                it.copy(
                    commandToken = foreignToken,
                    relativeFinalPath = foreignPaths.relativeFinalPath,
                    relativeTempPath = foreignPaths.relativeTempPath,
                    relativeQuarantinePath = foreignPaths.relativeQuarantinePath,
                )
            },
            CaptureRowMutation("noncanonical-storage") { it.copy(hasCanonicalStorage = false) },
        )
        listOf("REGISTERED", "CAPTURING").forEach { state ->
            val coherent = blockingRows(state)
            val initial = coherent.captureFileOperations
            commonRowMutations.forEach { mutation ->
                (0..2).forEach { ordinal ->
                    assertCaptureAuthorityRejected(
                        coherent,
                        initial.mapAt(ordinal, mutation.transform),
                        "$state ordinal=$ordinal mutation=${mutation.label}",
                    )
                }
            }
            linkedMapOf(
                "empty-cardinality" to emptyList(),
                "missing-final-row" to initial.dropLast(1),
                "duplicate-ordinal" to listOf(initial[0], initial[1], initial[1]),
                "reversed-order" to initial.reversed(),
                "extra-ordinal" to initial + initial.last().copy(burstOrdinal = 3),
            ).forEach { (label, authority) ->
                assertCaptureAuthorityRejected(coherent, authority, "$state mutation=$label")
            }
        }

        val registered = blockingRows("REGISTERED")
        val registeredMutations = listOf(
            CaptureRowMutation("registered-progressed-stage") {
                progressedCaptureFileOperation(
                    it,
                    CaptureFileOperationStage.TEMP_SYNCED,
                    updatedAt = 10L,
                )
            },
            CaptureRowMutation("registered-progress-clock") {
                it.copy(updatedAtEpochMillis = 11L)
            },
            CaptureRowMutation("registered-failure-pair") {
                it.copy(
                    lastFailureCode = CaptureFileFailureCode.RESERVATION_FAILED.name,
                    reconciliationRequired = true,
                )
            },
        )
        registeredMutations.forEach { mutation ->
            (0..2).forEach { ordinal ->
                assertCaptureAuthorityRejected(
                    registered,
                    registered.captureFileOperations.mapAt(ordinal, mutation.transform),
                    "REGISTERED ordinal=$ordinal mutation=${mutation.label}",
                )
            }
        }

        (0..2).forEach { ordinal ->
            val progressed = capturingRowsForStage(
                CaptureFileOperationStage.TEMP_SYNCED,
                hasEvidence = true,
                ordinal = ordinal,
            )
            val failedCleanup = capturingRowsForStage(
                CaptureFileOperationStage.CLEANUP_REQUIRED,
                hasEvidence = false,
                failureCode = CaptureFileFailureCode.WRITE_FAILED,
                ordinal = ordinal,
            )
            val invalidCapturingAuthorities = listOf(
                CaptureAuthorityMutation(
                    "zero-byte-count",
                    progressed,
                    progressed.captureFileOperations.mapAt(ordinal) { it.copy(byteCount = 0L) },
                ),
                CaptureAuthorityMutation(
                    "negative-byte-count",
                    progressed,
                    progressed.captureFileOperations.mapAt(ordinal) { it.copy(byteCount = -1L) },
                ),
                CaptureAuthorityMutation(
                    "missing-byte-count",
                    progressed,
                    progressed.captureFileOperations.mapAt(ordinal) { it.copy(byteCount = null) },
                ),
                CaptureAuthorityMutation(
                    "uppercase-hash",
                    progressed,
                    progressed.captureFileOperations.mapAt(ordinal) {
                        it.copy(sha256 = SHA_256.uppercase())
                    },
                ),
                CaptureAuthorityMutation(
                    "short-hash",
                    progressed,
                    progressed.captureFileOperations.mapAt(ordinal) {
                        it.copy(sha256 = SHA_256.dropLast(1))
                    },
                ),
                CaptureAuthorityMutation(
                    "missing-hash",
                    progressed,
                    progressed.captureFileOperations.mapAt(ordinal) { it.copy(sha256 = null) },
                ),
                CaptureAuthorityMutation(
                    "missing-captured-clock",
                    progressed,
                    progressed.captureFileOperations.mapAt(ordinal) {
                        it.copy(capturedAtEpochMillis = null)
                    },
                ),
                CaptureAuthorityMutation(
                    "unknown-failure",
                    failedCleanup,
                    failedCleanup.captureFileOperations.mapAt(ordinal) {
                        it.copy(lastFailureCode = "UNKNOWN_FAILURE")
                    },
                ),
                CaptureAuthorityMutation(
                    "failure-without-reconciliation",
                    failedCleanup,
                    failedCleanup.captureFileOperations.mapAt(ordinal) {
                        it.copy(reconciliationRequired = false)
                    },
                ),
                CaptureAuthorityMutation(
                    "reconciliation-without-failure",
                    failedCleanup,
                    failedCleanup.captureFileOperations.mapAt(ordinal) {
                        it.copy(lastFailureCode = null, reconciliationRequired = true)
                    },
                ),
                CaptureAuthorityMutation(
                    "captured-after-row-update",
                    progressed,
                    progressed.captureFileOperations.mapAt(ordinal) {
                        it.copy(capturedAtEpochMillis = it.updatedAtEpochMillis + 1L)
                    },
                ),
                CaptureAuthorityMutation(
                    "progress-before-attempt-update",
                    progressed,
                    progressed.captureFileOperations.mapAt(ordinal) {
                        it.copy(capturedAtEpochMillis = 19L, updatedAtEpochMillis = 19L)
                    },
                ),
            )
            invalidCapturingAuthorities.forEach { mutation ->
                assertCaptureAuthorityRejected(
                    mutation.baseline,
                    mutation.authority,
                    "CAPTURING ordinal=$ordinal mutation=${mutation.label}",
                )
            }

            val sameTimestampAttempt = progressed.attempts.single()
                .copy(updatedAtEpochMillis = 10L)
            val sameTimestampOperations = progressed.captureFileOperations.mapAt(ordinal) {
                it.copy(
                    capturedAtEpochMillis = it.createdAtEpochMillis,
                    updatedAtEpochMillis = it.createdAtEpochMillis,
                )
            }
            assertRejected(
                GuidedSessionBootstrapRejectionReason.INVALID_CAPTURE_FILE_OPERATION_AUTHORITY,
                progressed.copyRows(
                    attempts = listOf(sameTimestampAttempt),
                    captureFileOperations = sameTimestampOperations,
                ),
                "CAPTURING ordinal=$ordinal mutation=no-progress-clock",
            )
        }
    }

    @Test
    fun confirmedAttemptRejectsResidualCaptureFileOperations() {
        val confirmed = canonicalReadyRows()
        assertTrue(GuidedSessionBootstrapMapper.map(confirmed) is GuidedSessionBootstrapResult.Ready)

        val valid = captureFileOperations("token-0", createdAt = 10L)
        listOf(
            listOf(valid.first()),
            valid,
            listOf(valid.first().copy(stage = "UNKNOWN_STAGE")),
            valid.map { it.copy(relativeTempPath = "capture-candidates/wrong.pending") },
        ).forEach { captureFileOperations ->
            assertRejected(
                GuidedSessionBootstrapRejectionReason.INVALID_CAPTURE_FILE_OPERATION_AUTHORITY,
                confirmed.copyRows(captureFileOperations = captureFileOperations),
            )
        }
    }

    @Test
    fun captureFileOperationsAreConstituentAuthority() {
        val source = captureFileOperations("token-orphan", createdAt = 10L).toMutableList()
        val rows = GuidedSessionBootstrapRows(
            shoot = null,
            session = null,
            captureFileOperations = source,
        )
        source.clear()

        assertEquals(3, rows.captureFileOperations.size)
        assertRejected(
            GuidedSessionBootstrapRejectionReason.ORPHANED_AUTHORITY,
            rows,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (rows.captureFileOperations as MutableList<GuidedCaptureFileOperationAuthorityRow>)
                .add(captureFileOperations("token-extra", createdAt = 10L).first())
        }

        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_CAPTURE_FILE_OPERATION_AUTHORITY,
            canonicalReadyRows().copyRows(
                captureFileOperations = captureFileOperations("token-foreign", createdAt = 10L),
            ),
        )

        val authorityRow = rows.captureFileOperations.first()
        val rendered = authorityRow.toString()
        assertEquals("GuidedCaptureFileOperationAuthorityRow(redacted)", rendered)
        assertFalse(rendered.contains(authorityRow.commandToken))
        assertFalse(rendered.contains(authorityRow.relativeFinalPath))
        assertEquals("GuidedSessionBootstrapRows(redacted)", rows.toString())
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
            captureFileOperations = captureFileOperations(
                token = blocking.commandToken,
                createdAt = blocking.createdAtEpochMillis,
            ),
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
        caseName: String? = null,
    ) {
        val result = GuidedSessionBootstrapMapper.map(rows)
        assertEquals(
            caseName?.let { "Unexpected result for $it" } ?: "Unexpected rejection result",
            GuidedSessionBootstrapResult.Rejected(expected),
            result,
        )
    }

    private fun assertCaptureAuthorityRejected(
        baseline: GuidedSessionBootstrapRows,
        authority: List<GuidedCaptureFileOperationAuthorityRow>,
        caseName: String,
    ) {
        assertRejected(
            GuidedSessionBootstrapRejectionReason.INVALID_CAPTURE_FILE_OPERATION_AUTHORITY,
            baseline.copyRows(captureFileOperations = authority),
            caseName,
        )
    }

    private fun assertReconciliation(rows: GuidedSessionBootstrapRows, caseName: String) {
        assertTrue(
            "Expected reconciliation for $caseName",
            GuidedSessionBootstrapMapper.map(rows) is
                GuidedSessionBootstrapResult.ReconciliationRequired,
        )
    }

    private fun canonicalReadyRows(): GuidedSessionBootstrapRows = authorityRows(
        currentPoseIndex = 1,
        nextAttemptNumber = 1L,
        lifecycleState = "ACTIVE",
        attempts = listOf(attempt(0L, 0, "CONFIRMED", 10L, 30L, 30L)),
    )

    private fun blockingRows(
        state: String,
        token: String = "token-0",
    ): GuidedSessionBootstrapRows = authorityRows(
        currentPoseIndex = 0,
        nextAttemptNumber = 1L,
        lifecycleState = "ACTIVE",
        attempts = listOf(attempt(0L, 0, state, 10L, 10L, null, token)),
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
            captureFileOperations = attempts
                .filter { attempt -> attempt.lifecycleState != "CONFIRMED" }
                .flatMap { attempt ->
                    captureFileOperations(
                        token = attempt.commandToken,
                        createdAt = attempt.createdAtEpochMillis,
                    )
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
        commandToken: String = "token-$number",
    ) = GuidedAttemptAuthorityRow(
        commandToken = commandToken,
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

    private fun captureFileOperations(
        token: String,
        createdAt: Long,
    ): List<GuidedCaptureFileOperationAuthorityRow> = (0..2).map { ordinal ->
        val paths = CaptureFileOperationPaths.forIdentity(
            PrivateOutputIdentity(CaptureToken(token), ordinal),
        )
        GuidedCaptureFileOperationAuthorityRow(
            commandToken = token,
            burstOrdinal = ordinal,
            relativeFinalPath = paths.relativeFinalPath,
            relativeTempPath = paths.relativeTempPath,
            relativeQuarantinePath = paths.relativeQuarantinePath,
            stage = "EXPECTING_RESERVATION",
            byteCount = null,
            sha256 = null,
            capturedAtEpochMillis = null,
            lastFailureCode = null,
            reconciliationRequired = false,
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = createdAt,
        )
    }

    private fun capturingRowsForStage(
        stage: CaptureFileOperationStage,
        hasEvidence: Boolean,
        failureCode: CaptureFileFailureCode? = null,
        ordinal: Int = 0,
    ): GuidedSessionBootstrapRows {
        val base = blockingRows("CAPTURING")
        val progressed = stage != CaptureFileOperationStage.EXPECTING_RESERVATION
        val updatedAt = if (progressed) 20L else 10L
        val attempt = base.attempts.single().copy(updatedAtEpochMillis = updatedAt)
        return base.copyRows(
            shoot = base.shoot!!.copy(updatedAtEpochMillis = updatedAt),
            session = base.session!!.copy(updatedAtEpochMillis = updatedAt),
            attempts = listOf(attempt),
            captureFileOperations = base.captureFileOperations.mapAt(ordinal) { row ->
                progressedCaptureFileOperation(
                    row = row,
                    stage = stage,
                    updatedAt = updatedAt,
                    hasEvidence = hasEvidence,
                ).copy(
                    lastFailureCode = failureCode?.name,
                    reconciliationRequired = failureCode != null,
                )
            },
        )
    }

    private fun progressedCaptureFileOperation(
        row: GuidedCaptureFileOperationAuthorityRow,
        stage: CaptureFileOperationStage,
        updatedAt: Long,
        hasEvidence: Boolean = true,
    ): GuidedCaptureFileOperationAuthorityRow = row.copy(
        stage = stage.name,
        byteCount = if (hasEvidence) 100L + row.burstOrdinal else null,
        sha256 = if (hasEvidence) SHA_256 else null,
        capturedAtEpochMillis = if (hasEvidence) updatedAt - 1L else null,
        updatedAtEpochMillis = updatedAt,
    )

    private fun List<GuidedCaptureFileOperationAuthorityRow>.mapAt(
        targetIndex: Int,
        transform: (GuidedCaptureFileOperationAuthorityRow) -> GuidedCaptureFileOperationAuthorityRow,
    ): List<GuidedCaptureFileOperationAuthorityRow> {
        require(targetIndex in indices) { "capture row index is out of bounds" }
        return mapIndexed { index, row ->
            if (index == targetIndex) transform(row) else row
        }
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
        captureFileOperations: List<GuidedCaptureFileOperationAuthorityRow> =
            this.captureFileOperations,
    ) = GuidedSessionBootstrapRows(
        shoot = shoot,
        session = session,
        poses = poses,
        attempts = attempts,
        privateOutputs = privateOutputs,
        receipts = receipts,
        outboxes = outboxes,
        exportOutputs = exportOutputs,
        captureFileOperations = captureFileOperations,
    )

    private data class ExportFacts(
        val state: String,
        val claim: String?,
        val uri: String?,
        val ambiguity: String,
        val unresolved: Boolean,
    )

    private data class CaptureRowMutation(
        val label: String,
        val transform: (
            GuidedCaptureFileOperationAuthorityRow,
        ) -> GuidedCaptureFileOperationAuthorityRow,
    )

    private data class CaptureAuthorityMutation(
        val label: String,
        val baseline: GuidedSessionBootstrapRows,
        val authority: List<GuidedCaptureFileOperationAuthorityRow>,
    )

    private companion object {
        const val SHOOT_ID = "shoot-safe"
        const val SESSION_ID = "session-safe"
        const val OPAQUE_TOKEN = "../private/name"
        const val SHA_256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
