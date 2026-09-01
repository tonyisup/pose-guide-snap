package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.ReferenceImportFailureSettlement
import com.tonyisup.poseguidesnap.data.ReferenceImportAdmissionCheckBlockReason
import com.tonyisup.poseguidesnap.data.ReferenceImportAdmissionCheckResult
import com.tonyisup.poseguidesnap.data.ReferenceImportReserveRejectionReason
import com.tonyisup.poseguidesnap.data.ReferenceImportToken
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReferenceImportApplicationServiceTest {
    @Test
    fun roomAdmissionResultsMapExhaustivelyWithoutLosingTheirBarrierMeaning() {
        assertSame(
            ReferenceImportAdmissionResult.Allowed,
            ReferenceImportAdmissionCheckResult.Allowed.toApplicationAdmissionResult(),
        )
        ReferenceImportAdmissionCheckBlockReason.entries.forEach { reason ->
            val mapped = ReferenceImportAdmissionCheckResult.Blocked(reason)
                .toApplicationAdmissionResult()
            assertTrue(mapped is ReferenceImportAdmissionResult.Blocked)
            mapped as ReferenceImportAdmissionResult.Blocked
            assertEquals(reason.name, mapped.reason.name)
            assertNoSensitiveRendering(mapped)
        }
    }

    @Test
    fun allowedValidRequestAllocatesExactTrimmedUriFreeDraftAfterAdmission() {
        val events = mutableListOf<String>()
        val timeline = timeline(101L)
        val service = ReferenceImportApplicationService(
            admission = ReferenceImportAdmissionPort {
                events += "admission"
                ReferenceImportAdmissionResult.Allowed
            },
            tokenProvider = ReferenceImportTokenProvider {
                events += "token"
                ReferenceImportToken(TOKEN_ONE)
            },
            poseIdProvider = ReferenceImportPoseIdProvider {
                events += "pose"
                POSE_ONE
            },
            timelineProvider = ReferenceImportLedgerTimelineProvider {
                events += "timeline"
                timeline
            },
        )

        val result = service.allocate(
            ReferenceImportAllocationRequest(SHOOT_ID, "  $LABEL  "),
        )

        assertEquals(listOf("admission", "token", "pose", "timeline"), events)
        assertTrue("Allowed allocation must return Ready, got $result", result is ReferenceImportAllocationResult.Ready)
        result as ReferenceImportAllocationResult.Ready
        assertEquals(ReferenceImportToken(TOKEN_ONE), result.draft.importToken)
        assertEquals(SHOOT_ID, result.draft.shootId)
        assertEquals(POSE_ONE, result.draft.poseId)
        assertEquals(LABEL, result.draft.label)
        assertTrue(result.draft.mirrorAllowed)
        assertSame(timeline, result.draft.timeline)
        assertNoSensitiveRendering(result, result.draft)
        assertNoForbiddenRetainedType(ReferencePickerImportDraft::class.java)
        assertNoForbiddenRetainedType(ReferenceImportAllocationResult.Ready::class.java)
    }

    @Test
    fun invalidBlankNulOrProviderUriLabelBlocksBeforeAdmissionAndProviders() {
        val invalidLabels = listOf(
            "   ",
            "caller\u0000label",
            "reference CONTENT://private.provider/item",
        )
        invalidLabels.forEach { label ->
            val events = mutableListOf<String>()
            val service = recordingService(events)

            val result = service.allocate(ReferenceImportAllocationRequest(SHOOT_ID, label))

            assertAllocationBlocked(
                result,
                ReferenceImportAllocationBlockReason.INVALID_REQUEST,
                ReferenceImportRetryAction.NONE,
            )
            assertTrue("Invalid label reached an injected port", events.isEmpty())
            assertNoSensitiveRendering(result)
        }
    }

    @Test
    fun everyAdmissionBlockMapsToClosedAllocationReasonAndActionWithoutProviders() {
        val cases = listOf(
            AdmissionCase(
                ReferenceImportAdmissionBlockReason.UNKNOWN_SHOOT,
                ReferenceImportAllocationBlockReason.UNKNOWN_SHOOT,
                ReferenceImportRetryAction.NONE,
            ),
            AdmissionCase(
                ReferenceImportAdmissionBlockReason.SHOOT_DELETING,
                ReferenceImportAllocationBlockReason.SHOOT_DELETING,
                ReferenceImportRetryAction.NONE,
            ),
            AdmissionCase(
                ReferenceImportAdmissionBlockReason.PLAYLIST_FULL,
                ReferenceImportAllocationBlockReason.PLAYLIST_FULL,
                ReferenceImportRetryAction.NONE,
            ),
            AdmissionCase(
                ReferenceImportAdmissionBlockReason.ACTIVE_SESSION,
                ReferenceImportAllocationBlockReason.ACTIVE_SESSION,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
            ),
            AdmissionCase(
                ReferenceImportAdmissionBlockReason.IMPORT_IN_PROGRESS,
                ReferenceImportAllocationBlockReason.IMPORT_IN_PROGRESS,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
            ),
            AdmissionCase(
                ReferenceImportAdmissionBlockReason.RECONCILIATION_REQUIRED,
                ReferenceImportAllocationBlockReason.RECONCILIATION_REQUIRED,
                ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
            ),
            AdmissionCase(
                ReferenceImportAdmissionBlockReason.AUTHORITY_INCONSISTENT,
                ReferenceImportAllocationBlockReason.AUTHORITY_INCONSISTENT,
                ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
            ),
        )

        assertAllCases(cases.map { case ->
            case.admissionReason.name to {
                val events = mutableListOf<String>()
                val service = recordingService(
                    events = events,
                    admissionResult = ReferenceImportAdmissionResult.Blocked(case.admissionReason),
                )

                val result = service.allocate(validRequest())

                assertAllocationBlocked(result, case.allocationReason, case.retryAction)
                assertEquals(listOf("admission"), events)
                assertNoSensitiveRendering(result)
            }
        })
    }

    @Test
    fun providerFailureOrInvalidIdentityFailsClosedWithoutRawValueOrError() {
        val cases = listOf(
            "token failure" to {
                val tokenCalls = AtomicInteger()
                val service = ReferenceImportApplicationService(
                    admission = ReferenceImportAdmissionPort { ReferenceImportAdmissionResult.Allowed },
                    tokenProvider = ReferenceImportTokenProvider {
                        tokenCalls.incrementAndGet()
                        error("$TOKEN_ONE $PROVIDER_URI $ERROR_MARKER")
                    },
                    poseIdProvider = ReferenceImportPoseIdProvider { POSE_ONE },
                    timelineProvider = ReferenceImportLedgerTimelineProvider { timeline(201L) },
                )
                val result = service.allocate(validRequest())
                assertEquals(1, tokenCalls.get())
                assertAllocationBlocked(
                    result,
                    ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                    ReferenceImportRetryAction.RETRY_ALLOCATION,
                )
                assertNoSensitiveRendering(result)
            },
            "timeline failure" to {
                val timelineCalls = AtomicInteger()
                val service = ReferenceImportApplicationService(
                    admission = ReferenceImportAdmissionPort { ReferenceImportAdmissionResult.Allowed },
                    tokenProvider = ReferenceImportTokenProvider { ReferenceImportToken(TOKEN_ONE) },
                    poseIdProvider = ReferenceImportPoseIdProvider { POSE_ONE },
                    timelineProvider = ReferenceImportLedgerTimelineProvider {
                        timelineCalls.incrementAndGet()
                        error("$TOKEN_ONE $PROVIDER_URI $ERROR_MARKER")
                    },
                )
                val result = service.allocate(validRequest())
                assertEquals(1, timelineCalls.get())
                assertAllocationBlocked(
                    result,
                    ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                    ReferenceImportRetryAction.RETRY_ALLOCATION,
                )
                assertNoSensitiveRendering(result)
            },
        ) + listOf("", "../pose", "pose\u0000id", PROVIDER_URI).map { invalidPoseId ->
            "invalid pose identity" to {
                val poseCalls = AtomicInteger()
                val timelineCalls = AtomicInteger()
                val service = ReferenceImportApplicationService(
                    admission = ReferenceImportAdmissionPort { ReferenceImportAdmissionResult.Allowed },
                    tokenProvider = ReferenceImportTokenProvider { ReferenceImportToken(TOKEN_ONE) },
                    poseIdProvider = ReferenceImportPoseIdProvider {
                        poseCalls.incrementAndGet()
                        invalidPoseId
                    },
                    timelineProvider = ReferenceImportLedgerTimelineProvider {
                        timelineCalls.incrementAndGet()
                        timeline(201L)
                    },
                )
                val result = service.allocate(validRequest())
                assertEquals(1, poseCalls.get())
                assertEquals(0, timelineCalls.get())
                assertAllocationBlocked(
                    result,
                    ReferenceImportAllocationBlockReason.IDENTITY_UNAVAILABLE,
                    ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT,
                )
                assertNoSensitiveRendering(result)
            }
        }

        assertAllCases(cases)
    }

    @Test
    fun terminalReplacementAllocationsAlwaysUseFreshTokenAndPoseIdentity() {
        val tokenCalls = AtomicInteger()
        val poseCalls = AtomicInteger()
        val tokens = listOf(ReferenceImportToken(TOKEN_ONE), ReferenceImportToken(TOKEN_TWO))
        val poses = listOf(POSE_ONE, POSE_TWO)
        val service = ReferenceImportApplicationService(
            admission = ReferenceImportAdmissionPort { ReferenceImportAdmissionResult.Allowed },
            tokenProvider = ReferenceImportTokenProvider { tokens[tokenCalls.getAndIncrement()] },
            poseIdProvider = ReferenceImportPoseIdProvider { poses[poseCalls.getAndIncrement()] },
            timelineProvider = ReferenceImportLedgerTimelineProvider { timeline(301L) },
        )

        val first = service.allocate(validRequest())
        val terminal = service.classify(
            ReferencePickerResult.Completed(
                ReferencePoseImportResult.Rejected(
                    ReferencePoseImportRejectionReason.NO_PERSON,
                    ReferenceImportFailureSettlement.CLEANED,
                ),
            ),
        )
        val second = service.allocate(validRequest())

        assertEquals(2, tokenCalls.get())
        assertEquals(2, poseCalls.get())
        assertOutcome(
            terminal,
            ReferenceImportOutcomeStatus.VALIDATION_REJECTED,
            ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT,
        )
        assertTrue("First allocation must be Ready, got $first", first is ReferenceImportAllocationResult.Ready)
        assertTrue("Second allocation must be Ready, got $second", second is ReferenceImportAllocationResult.Ready)
        first as ReferenceImportAllocationResult.Ready
        second as ReferenceImportAllocationResult.Ready
        assertEquals(ReferenceImportToken(TOKEN_ONE), first.draft.importToken)
        assertEquals(ReferenceImportToken(TOKEN_TWO), second.draft.importToken)
        assertEquals(POSE_ONE, first.draft.poseId)
        assertEquals(POSE_TWO, second.draft.poseId)
        assertNotEquals(first.draft.importToken, second.draft.importToken)
        assertNotEquals(first.draft.poseId, second.draft.poseId)
    }

    @Test
    fun unknownShootAndDeletingShootReserveRejectionsKeepDifferentBoundedMeaning() {
        val service = recordingService(mutableListOf())

        val unknown = service.classify(
            ReferencePickerResult.Completed(
                ReferencePoseImportResult.ReserveRejected(
                    ReferenceImportReserveRejectionReason.UNKNOWN_SHOOT,
                ),
            ),
        )
        val deleting = service.classify(
            ReferencePickerResult.Completed(
                ReferencePoseImportResult.ReserveRejected(
                    ReferenceImportReserveRejectionReason.SHOOT_NOT_ACTIVE,
                ),
            ),
        )

        assertSame(ReferenceImportOutcomeStatus.RESERVE_REJECTED_UNKNOWN_SHOOT, unknown.status)
        assertSame(ReferenceImportOutcomeStatus.RESERVE_REJECTED_SHOOT_DELETING, deleting.status)
        assertNotEquals(unknown.status, deleting.status)
        assertSame(ReferenceImportRetryAction.NONE, unknown.retryAction)
        assertSame(ReferenceImportRetryAction.NONE, deleting.retryAction)
        assertNoSensitiveRendering(unknown, deleting)
    }

    @Test
    fun classifyMapsEveryClosedPickerAndImporterFamilyWithoutSensitiveDetail() {
        val cases = mutableListOf<OutcomeCase>()
        cases += OutcomeCase(
            ReferencePickerResult.Cancelled,
            ReferenceImportOutcomeStatus.CANCELLED,
            ReferenceImportRetryAction.NONE,
        )
        cases += OutcomeCase(
            ReferencePickerResult.InvalidSelection,
            ReferenceImportOutcomeStatus.INVALID_SELECTION,
            ReferenceImportRetryAction.RETRY_ALLOCATION,
        )
        cases += OutcomeCase(
            ReferencePickerResult.Completed(ReferencePoseImportResult.Succeeded(POSE_ONE, 3)),
            ReferenceImportOutcomeStatus.SUCCEEDED,
            ReferenceImportRetryAction.NONE,
        )
        cases += reserveOutcomeCases()
        ReferencePoseImportRejectionReason.entries.forEachIndexed { index, reason ->
            val status = when (reason) {
                ReferencePoseImportRejectionReason.NO_PERSON,
                ReferencePoseImportRejectionReason.MULTIPLE_PEOPLE,
                ReferencePoseImportRejectionReason.LOW_COVERAGE,
                -> ReferenceImportOutcomeStatus.VALIDATION_REJECTED

                ReferencePoseImportRejectionReason.PUBLICATION_FAILED,
                ReferencePoseImportRejectionReason.ASSET_READY_REJECTED,
                ReferencePoseImportRejectionReason.ANALYZER_FAILED,
                ReferencePoseImportRejectionReason.COMMIT_REJECTED,
                ReferencePoseImportRejectionReason.COMMIT_BLOCKED,
                -> ReferenceImportOutcomeStatus.TERMINAL_REJECTED
            }
            cases += OutcomeCase(
                ReferencePickerResult.Completed(
                    ReferencePoseImportResult.Rejected(
                        reason,
                        ReferenceImportFailureSettlement.entries[index % 2],
                    ),
                ),
                status,
                ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT,
            )
        }
        cases += OutcomeCase(
            ReferencePickerResult.Completed(ReferencePoseImportResult.ReconciliationRequired),
            ReferenceImportOutcomeStatus.RECONCILIATION_REQUIRED,
            ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
        )
        cases += OutcomeCase(
            ReferencePickerResult.ReconciliationRequired,
            ReferenceImportOutcomeStatus.RECONCILIATION_REQUIRED,
            ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY,
        )
        val service = recordingService(mutableListOf())

        assertAllCases(cases.mapIndexed { index, case ->
            "outcome-$index" to {
                val outcome = service.classify(case.pickerResult)
                assertOutcome(outcome, case.status, case.retryAction)
                assertNoSensitiveRendering(outcome)
                assertEquals(
                    2,
                    outcome.javaClass.declaredFields.count {
                        !it.isSynthetic && !Modifier.isStatic(it.modifiers)
                    },
                )
            }
        })
    }

    @Test
    fun publicContractIsClosedRedactedAndFreeOfAndroidRoomUriSourceClockOrRandomTypes() {
        assertEquals(
            setOf(
                "UNKNOWN_SHOOT",
                "SHOOT_DELETING",
                "PLAYLIST_FULL",
                "ACTIVE_SESSION",
                "IMPORT_IN_PROGRESS",
                "RECONCILIATION_REQUIRED",
                "AUTHORITY_INCONSISTENT",
            ),
            ReferenceImportAdmissionBlockReason.entries.map { it.name }.toSet(),
        )
        assertEquals(
            setOf("NONE", "RETRY_ALLOCATION", "RUN_RECONCILIATION_THEN_RETRY", "ALLOCATE_NEW_ATTEMPT"),
            ReferenceImportRetryAction.entries.map { it.name }.toSet(),
        )
        assertEquals(
            setOf(
                "INVALID_REQUEST",
                "UNKNOWN_SHOOT",
                "SHOOT_DELETING",
                "PLAYLIST_FULL",
                "ACTIVE_SESSION",
                "IMPORT_IN_PROGRESS",
                "RECONCILIATION_REQUIRED",
                "IDENTITY_UNAVAILABLE",
                "AUTHORITY_INCONSISTENT",
                "AUTHORITY_UNAVAILABLE",
            ),
            ReferenceImportAllocationBlockReason.entries.map { it.name }.toSet(),
        )
        assertEquals(
            setOf(
                "CANCELLED",
                "INVALID_SELECTION",
                "SUCCEEDED",
                "RESERVE_REJECTED_PLAYLIST_FULL",
                "RESERVE_REJECTED_ACTIVE_SESSION",
                "RESERVE_REJECTED_UNKNOWN_SHOOT",
                "RESERVE_REJECTED_SHOOT_DELETING",
                "RESERVE_REJECTED_UNRESOLVED_IMPORT",
                "RESERVE_REJECTED_IDENTITY",
                "RESERVE_REJECTED_AUTHORITY",
                "VALIDATION_REJECTED",
                "TERMINAL_REJECTED",
                "RECONCILIATION_REQUIRED",
                "AUTHORITY_UNAVAILABLE",
            ),
            ReferenceImportOutcomeStatus.entries.map { it.name }.toSet(),
        )

        val publicContractTypes = listOf(
            ReferenceImportAllocationRequest::class.java,
            ReferenceImportAdmissionPort::class.java,
            ReferenceImportTokenProvider::class.java,
            ReferenceImportPoseIdProvider::class.java,
            ReferenceImportLedgerTimelineProvider::class.java,
            ReferenceImportAdmissionResult::class.java,
            ReferenceImportAdmissionResult.Allowed::class.java,
            ReferenceImportAdmissionResult.Blocked::class.java,
            ReferenceImportAdmissionBlockReason::class.java,
            ReferenceImportAllocationResult::class.java,
            ReferenceImportAllocationResult.Ready::class.java,
            ReferenceImportAllocationResult.Blocked::class.java,
            ReferenceImportAllocationBlockReason::class.java,
            ReferenceImportRetryAction::class.java,
            ReferenceImportOutcome::class.java,
            ReferenceImportOutcomeStatus::class.java,
            ReferenceImportApplicationService::class.java,
        )
        publicContractTypes.forEach(::assertNoForbiddenPublicSignature)

        val source = projectRoot().resolve(SERVICE_SOURCE_PATH).readText()
        listOf(
            "import android.",
            "import androidx.room",
            "android.net.Uri",
            "java.net.URI",
            "ReferenceAssetByteSource",
            "ReferencePoseImportRequest",
            "RoomReferenceImport",
            "System.currentTimeMillis",
            "System.nanoTime",
            "java.time.Clock",
            "java.time.Instant",
            "kotlin.random.Random",
            "java.util.Random",
            "java.util.UUID",
        ).forEach { forbidden ->
            assertFalse("Application service source contains forbidden marker: $forbidden", forbidden in source)
        }

        val values = listOf(
            validRequest(),
            ReferenceImportAdmissionResult.Allowed,
            ReferenceImportAdmissionResult.Blocked(ReferenceImportAdmissionBlockReason.UNKNOWN_SHOOT),
            ReferenceImportAllocationResult.Ready(draft()),
            ReferenceImportAllocationResult.Blocked(
                ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
            ),
            ReferenceImportOutcome(
                ReferenceImportOutcomeStatus.TERMINAL_REJECTED,
                ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT,
            ),
            recordingService(mutableListOf()),
        ) + ReferenceImportAdmissionBlockReason.entries +
            ReferenceImportAllocationBlockReason.entries +
            ReferenceImportRetryAction.entries +
            ReferenceImportOutcomeStatus.entries
        values.forEach(::assertNoSensitiveRendering)
        assertEquals("ReferenceImportAllocationRequest(redacted)", validRequest().toString())
        assertEquals("ReferenceImportAdmissionResult.Allowed", ReferenceImportAdmissionResult.Allowed.toString())
        assertEquals(
            "ReferenceImportAllocationResult.Ready(redacted)",
            ReferenceImportAllocationResult.Ready(draft()).toString(),
        )
        assertEquals("ReferenceImportApplicationService(redacted)", recordingService(mutableListOf()).toString())
    }

    private fun reserveOutcomeCases(): List<OutcomeCase> =
        ReferenceImportReserveRejectionReason.entries.map { reason ->
            val expected = when (reason) {
                ReferenceImportReserveRejectionReason.PLAYLIST_FULL ->
                    ReferenceImportOutcomeStatus.RESERVE_REJECTED_PLAYLIST_FULL to
                        ReferenceImportRetryAction.NONE

                ReferenceImportReserveRejectionReason.ACTIVE_SESSION ->
                    ReferenceImportOutcomeStatus.RESERVE_REJECTED_ACTIVE_SESSION to
                        ReferenceImportRetryAction.RETRY_ALLOCATION

                ReferenceImportReserveRejectionReason.UNKNOWN_SHOOT ->
                    ReferenceImportOutcomeStatus.RESERVE_REJECTED_UNKNOWN_SHOOT to
                        ReferenceImportRetryAction.NONE

                ReferenceImportReserveRejectionReason.SHOOT_NOT_ACTIVE ->
                    ReferenceImportOutcomeStatus.RESERVE_REJECTED_SHOOT_DELETING to
                        ReferenceImportRetryAction.NONE

                ReferenceImportReserveRejectionReason.UNRESOLVED_IMPORT_WORK ->
                    ReferenceImportOutcomeStatus.RESERVE_REJECTED_UNRESOLVED_IMPORT to
                        ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY

                ReferenceImportReserveRejectionReason.TOKEN_CONFLICT,
                ReferenceImportReserveRejectionReason.POSE_ID_CONFLICT,
                ReferenceImportReserveRejectionReason.POSE_ALREADY_EXISTS,
                -> ReferenceImportOutcomeStatus.RESERVE_REJECTED_IDENTITY to
                    ReferenceImportRetryAction.ALLOCATE_NEW_ATTEMPT

                ReferenceImportReserveRejectionReason.INVALID_TIMESTAMP,
                ReferenceImportReserveRejectionReason.AUTHORITY_INCONSISTENT,
                -> ReferenceImportOutcomeStatus.RESERVE_REJECTED_AUTHORITY to
                    ReferenceImportRetryAction.RUN_RECONCILIATION_THEN_RETRY
            }
            OutcomeCase(
                ReferencePickerResult.Completed(ReferencePoseImportResult.ReserveRejected(reason)),
                expected.first,
                expected.second,
            )
        }

    private fun recordingService(
        events: MutableList<String>,
        admissionResult: ReferenceImportAdmissionResult = ReferenceImportAdmissionResult.Allowed,
    ): ReferenceImportApplicationService = ReferenceImportApplicationService(
        admission = ReferenceImportAdmissionPort {
            events += "admission"
            admissionResult
        },
        tokenProvider = ReferenceImportTokenProvider {
            events += "token"
            ReferenceImportToken(TOKEN_ONE)
        },
        poseIdProvider = ReferenceImportPoseIdProvider {
            events += "pose"
            POSE_ONE
        },
        timelineProvider = ReferenceImportLedgerTimelineProvider {
            events += "timeline"
            timeline(101L)
        },
    )

    private fun validRequest(): ReferenceImportAllocationRequest =
        ReferenceImportAllocationRequest(SHOOT_ID, LABEL)

    private fun draft(): ReferencePickerImportDraft = ReferencePickerImportDraft(
        importToken = ReferenceImportToken(TOKEN_ONE),
        shootId = SHOOT_ID,
        poseId = POSE_ONE,
        label = LABEL,
        mirrorAllowed = true,
        timeline = timeline(101L),
    )

    private fun timeline(first: Long): ReferenceImportLedgerTimeline = ReferenceImportLedgerTimeline(
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
        failureSettledAtEpochMillis = first + 14L,
    )

    private fun assertAllocationBlocked(
        actual: ReferenceImportAllocationResult,
        expectedReason: ReferenceImportAllocationBlockReason,
        expectedAction: ReferenceImportRetryAction,
    ) {
        assertTrue("Expected Blocked($expectedReason), got $actual", actual is ReferenceImportAllocationResult.Blocked)
        actual as ReferenceImportAllocationResult.Blocked
        assertSame(expectedReason, actual.reason)
        assertSame(expectedAction, actual.retryAction)
    }

    private fun assertOutcome(
        actual: ReferenceImportOutcome,
        expectedStatus: ReferenceImportOutcomeStatus,
        expectedAction: ReferenceImportRetryAction,
    ) {
        assertSame(expectedStatus, actual.status)
        assertSame(expectedAction, actual.retryAction)
    }

    private fun assertNoSensitiveRendering(vararg values: Any) {
        values.forEach { value ->
            val rendered = value.toString()
            listOf(
                SHOOT_ID,
                TOKEN_ONE,
                TOKEN_TWO,
                POSE_ONE,
                POSE_TWO,
                LABEL,
                PROVIDER_URI,
                ERROR_MARKER,
                "content://",
                "../",
            ).forEach { forbidden ->
                assertFalse("${value.javaClass.name} leaked '$forbidden': $rendered", forbidden in rendered)
            }
        }
    }

    private fun assertNoForbiddenRetainedType(type: Class<*>) {
        type.declaredFields.forEach { field ->
            val renderedType = field.genericType.typeName
            FORBIDDEN_TYPE_MARKERS.forEach { forbidden ->
                assertFalse("${type.name}.${field.name} retains forbidden type $renderedType", forbidden in renderedType)
            }
        }
    }

    private fun assertNoForbiddenPublicSignature(type: Class<*>) {
        val signatures = buildList {
            type.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }.forEach { constructor ->
                add(constructor.toGenericString())
            }
            type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.forEach { method ->
                add(method.toGenericString())
            }
            type.declaredFields.filter { Modifier.isPublic(it.modifiers) }.forEach { field ->
                add(field.toGenericString())
            }
        }
        signatures.forEach { signature ->
            FORBIDDEN_TYPE_MARKERS.forEach { forbidden ->
                assertFalse("${type.name} exposes forbidden public signature $signature", forbidden in signature)
            }
        }
    }

    private fun assertAllCases(cases: List<Pair<String, () -> Unit>>) {
        val failures = mutableListOf<String>()
        cases.forEach { (name, assertion) ->
            try {
                assertion()
            } catch (failure: AssertionError) {
                failures += "$name: ${failure.message}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString(separator = "\n"))
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
    }

    private data class AdmissionCase(
        val admissionReason: ReferenceImportAdmissionBlockReason,
        val allocationReason: ReferenceImportAllocationBlockReason,
        val retryAction: ReferenceImportRetryAction,
    )

    private data class OutcomeCase(
        val pickerResult: ReferencePickerResult,
        val status: ReferenceImportOutcomeStatus,
        val retryAction: ReferenceImportRetryAction,
    )

    private companion object {
        const val SERVICE_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/importer/ReferenceImportApplicationService.kt"
        const val SHOOT_ID = "shoot-12b3"
        const val TOKEN_ONE = "token-12b3-one-secret"
        const val TOKEN_TWO = "token-12b3-two-secret"
        const val POSE_ONE = "pose-12b3-one-secret"
        const val POSE_TWO = "pose-12b3-two-secret"
        const val LABEL = "caller label secret"
        const val PROVIDER_URI = "content://private.provider/reference"
        const val ERROR_MARKER = "provider-stack-secret"

        val FORBIDDEN_TYPE_MARKERS = listOf(
            "android.",
            "androidx.room",
            ".Room",
            ".Uri",
            ".URI",
            "ReferenceAssetByteSource",
            "ReferencePoseImportRequest",
            "Throwable",
        )
    }
}
