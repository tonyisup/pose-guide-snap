package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.model.Shoot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShootPreparationContractsTest {
    @Test
    fun statusVocabulariesAreClosedAndExact() {
        assertEquals(
            listOf("ACTIVE", "DELETING"),
            ShootPreparationLifecycle.entries.map(Enum<*>::name),
        )
        assertEquals(
            listOf("IN_PROGRESS", "RECONCILIATION_REQUIRED", "REJECTED_QUARANTINED"),
            ImportWorkStatus.entries.map(Enum<*>::name),
        )
        assertEquals(
            listOf(
                "INVALID_SHOOT_ID",
                "INVALID_POSE_ID",
                "INVALID_TIMESTAMP",
                "INVALID_CARDINALITY",
                "DUPLICATE_POSE_ID",
                "ORDER_MISMATCH",
            ),
            ShootReorderInvalidReason.entries.map(Enum<*>::name),
        )
    }

    @Test
    fun reorderRequestValidationRejectsUnsafeIdentityCardinalityDuplicatesAndNegativeTime() {
        listOf("", ".", "..", " shoot", "content://provider/private", "bad/id").forEach { invalid ->
            assertReorderRequestInvalid(
                expected = ShootReorderInvalidReason.INVALID_SHOOT_ID,
                shootId = invalid,
                orderedPoseIds = listOf("pose-a", "pose-b"),
                reorderedAtEpochMillis = 1L,
            )
            assertReorderRequestInvalid(
                expected = ShootReorderInvalidReason.INVALID_POSE_ID,
                shootId = "shoot-safe",
                orderedPoseIds = listOf("pose-a", invalid),
                reorderedAtEpochMillis = 1L,
            )
        }
        listOf(0, 1, Shoot.MAX_REFERENCE_POSES + 1).forEach { count ->
            assertReorderRequestInvalid(
                expected = ShootReorderInvalidReason.INVALID_CARDINALITY,
                shootId = "shoot-safe",
                orderedPoseIds = (0 until count).map { index -> "pose-$index" },
                reorderedAtEpochMillis = 1L,
            )
        }
        assertReorderRequestInvalid(
            expected = ShootReorderInvalidReason.DUPLICATE_POSE_ID,
            shootId = "shoot-safe",
            orderedPoseIds = listOf("pose-a", "pose-a"),
            reorderedAtEpochMillis = 1L,
        )
        assertReorderRequestInvalid(
            expected = ShootReorderInvalidReason.INVALID_TIMESTAMP,
            shootId = "shoot-safe",
            orderedPoseIds = listOf("pose-a", "pose-b"),
            reorderedAtEpochMillis = -1L,
        )
    }

    @Test
    fun validReorderRequestSnapshotsCallerListAndRedactsItsRepresentation() {
        val callerOwned = mutableListOf("pose-a", "pose-b", "pose-c")

        val validation = ShootReorderPolicy.validateRequest(
            shootId = "shoot-secret",
            orderedPoseIds = callerOwned,
            reorderedAtEpochMillis = 11L,
        )

        assertTrue(validation is ShootReorderRequestValidation.Valid)
        validation as ShootReorderRequestValidation.Valid
        callerOwned.clear()
        assertEquals(listOf("pose-a", "pose-b", "pose-c"), validation.orderedPoseIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (validation.orderedPoseIds as MutableList<String>).clear()
        }
        val rendered = validation.toString()
        assertEquals("ShootReorderRequestValidation.Valid(redacted)", rendered)
        listOf("shoot-secret", "pose-a", "pose-b", "pose-c").forEach { secret ->
            assertFalse(rendered.contains(secret))
        }
    }

    @Test
    fun reorderOrderDecisionRequiresExactSetAndExplicitTimestampPolicy() {
        val current = listOf("pose-a", "pose-b", "pose-c")

        assertEquals(
            ShootReorderOrderDecision.MUTATE,
            ShootReorderPolicy.classifyValidatedOrder(current, listOf("pose-c", "pose-a", "pose-b"), 10L, 11L),
        )
        assertEquals(
            ShootReorderOrderDecision.ALREADY_ORDERED,
            ShootReorderPolicy.classifyValidatedOrder(current, current, 10L, 10L),
        )
        assertEquals(
            ShootReorderOrderDecision.ALREADY_ORDERED,
            ShootReorderPolicy.classifyValidatedOrder(current, current, 10L, 99L),
        )
        assertEquals(
            ShootReorderOrderDecision.STALE_TIMESTAMP,
            ShootReorderPolicy.classifyValidatedOrder(current, current, 10L, 9L),
        )
        assertEquals(
            ShootReorderOrderDecision.STALE_TIMESTAMP,
            ShootReorderPolicy.classifyValidatedOrder(current, listOf("pose-b", "pose-a", "pose-c"), 10L, 10L),
        )
        listOf(
            listOf("pose-a", "pose-b"),
            listOf("pose-a", "pose-b", "pose-c", "pose-foreign"),
            listOf("pose-a", "pose-b", "pose-foreign"),
            listOf("pose-a", "pose-a", "pose-c"),
        ).forEach { invalid ->
            assertEquals(
                ShootReorderOrderDecision.INVALID_ORDER,
                ShootReorderPolicy.classifyValidatedOrder(current, invalid, 10L, 11L),
            )
        }
        assertEquals(
            ShootReorderOrderDecision.AUTHORITY_INCONSISTENT,
            ShootReorderPolicy.classifyValidatedOrder(
                listOf("pose-a", "pose-a", "pose-c"),
                current,
                10L,
                11L,
            ),
        )
        assertEquals(
            ShootReorderOrderDecision.AUTHORITY_INCONSISTENT,
            ShootReorderPolicy.classifyValidatedOrder(current, current, -1L, 11L),
        )
    }

    @Test
    fun reorderResultsAreClosedStableAndValueFree() {
        val results = listOf(
            ShootReorderResult.Reordered,
            ShootReorderResult.AlreadyOrdered,
            ShootReorderResult.InvalidRequest(ShootReorderInvalidReason.ORDER_MISMATCH),
            ShootReorderResult.UnknownShoot,
            ShootReorderResult.ShootDeleting,
            ShootReorderResult.ActiveSession,
            ShootReorderResult.UnresolvedImportWork,
            ShootReorderResult.StaleTimestamp,
            ShootReorderResult.AuthorityInconsistent,
        )
        assertEquals(9, results.map { it::class }.distinct().size)
        assertEquals(
            listOf(
                "ShootReorderResult.Reordered",
                "ShootReorderResult.AlreadyOrdered",
                "ShootReorderResult.InvalidRequest(reason=ORDER_MISMATCH)",
                "ShootReorderResult.UnknownShoot",
                "ShootReorderResult.ShootDeleting",
                "ShootReorderResult.ActiveSession",
                "ShootReorderResult.UnresolvedImportWork",
                "ShootReorderResult.StaleTimestamp",
                "ShootReorderResult.AuthorityInconsistent",
            ),
            results.map(Any::toString),
        )
        results.forEach { result ->
            assertFalse(result.toString().contains("shoot-secret"))
            assertFalse(result.toString().contains("pose-secret"))
            assertFalse(result.toString().contains("content://", ignoreCase = true))
        }
    }

    @Test
    fun startVocabulariesAndResultsAreClosedStableAndValueFree() {
        assertEquals(
            listOf("INVALID_SHOOT_ID", "INVALID_SESSION_ID", "INVALID_TIMESTAMP"),
            ShootStartInvalidReason.entries.map(Enum<*>::name),
        )
        assertEquals(
            listOf("TOO_FEW_VALIDATED_REFERENCES"),
            ShootStartIneligibleReason.entries.map(Enum<*>::name),
        )
        val results = listOf(
            ShootStartResult.Started,
            ShootStartResult.AlreadyStarted,
            ShootStartResult.InvalidRequest(ShootStartInvalidReason.INVALID_SESSION_ID),
            ShootStartResult.UnknownShoot,
            ShootStartResult.ShootDeleting,
            ShootStartResult.IneligiblePlaylist(
                ShootStartIneligibleReason.TOO_FEW_VALIDATED_REFERENCES,
            ),
            ShootStartResult.UnresolvedImportWork,
            ShootStartResult.ActiveSessionConflict,
            ShootStartResult.SessionIdentityConflict,
            ShootStartResult.StaleOrConflictingReplay,
            ShootStartResult.AuthorityInconsistent,
        )

        assertEquals(11, results.map { it::class }.distinct().size)
        assertEquals(
            listOf(
                "ShootStartResult.Started",
                "ShootStartResult.AlreadyStarted",
                "ShootStartResult.InvalidRequest(reason=INVALID_SESSION_ID)",
                "ShootStartResult.UnknownShoot",
                "ShootStartResult.ShootDeleting",
                "ShootStartResult.IneligiblePlaylist(reason=TOO_FEW_VALIDATED_REFERENCES)",
                "ShootStartResult.UnresolvedImportWork",
                "ShootStartResult.ActiveSessionConflict",
                "ShootStartResult.SessionIdentityConflict",
                "ShootStartResult.StaleOrConflictingReplay",
                "ShootStartResult.AuthorityInconsistent",
            ),
            results.map(Any::toString),
        )
        results.forEach { result ->
            val rendered = result.toString()
            listOf("shoot-secret", "session-secret", "content://", "/private/path").forEach { secret ->
                assertFalse(rendered.contains(secret, ignoreCase = true))
            }
        }
    }

    @Test
    fun startRequestValidationRejectsUnsafeIdentitiesAndNegativeTimeBeforeRoom() {
        listOf("", ".", "..", " shoot", "shoot ", "content://private", "bad/id").forEach { invalid ->
            assertStartRequestInvalid(
                ShootStartInvalidReason.INVALID_SHOOT_ID,
                shootId = invalid,
                sessionId = "session-safe",
                startedAtEpochMillis = 1L,
            )
            assertStartRequestInvalid(
                ShootStartInvalidReason.INVALID_SESSION_ID,
                shootId = "shoot-safe",
                sessionId = invalid,
                startedAtEpochMillis = 1L,
            )
        }
        assertStartRequestInvalid(
            ShootStartInvalidReason.INVALID_TIMESTAMP,
            shootId = "shoot-safe",
            sessionId = "session-safe",
            startedAtEpochMillis = -1L,
        )

        val valid = ShootStartPolicy.validateRequest("shoot-safe", "session-safe", 0L)
        assertTrue(valid is ShootStartRequestValidation.Valid)
        assertEquals("ShootStartRequestValidation.Valid(redacted)", valid.toString())
        val rendered = valid.toString()
        assertFalse(rendered.contains("shoot-safe"))
        assertFalse(rendered.contains("session-safe"))
    }

    @Test
    fun startPlaylistCardinalityDistinguishesIneligibleEligibleAndCorruptAuthority() {
        mapOf(
            0L to ShootStartPlaylistDecision.INELIGIBLE_TOO_FEW,
            2L to ShootStartPlaylistDecision.INELIGIBLE_TOO_FEW,
            3L to ShootStartPlaylistDecision.ELIGIBLE,
            20L to ShootStartPlaylistDecision.ELIGIBLE,
            21L to ShootStartPlaylistDecision.AUTHORITY_INCONSISTENT,
            -1L to ShootStartPlaylistDecision.AUTHORITY_INCONSISTENT,
        ).forEach { (count, expected) ->
            assertEquals(expected, ShootStartPolicy.classifyPlaylistCardinality(count))
        }
    }

    @Test
    fun startSessionIdentityPrecedenceIsClosedRedactedAndIndependentOfTargetState() {
        val request = ShootStartRequestValidation.Valid("shoot-safe", "session-safe", 10L)
        val exact = startSession()

        assertEquals(
            listOf("ABSENT", "OWNED", "SESSION_IDENTITY_CONFLICT", "AUTHORITY_INCONSISTENT"),
            ShootStartIdentityDecision.entries.map(Enum<*>::name),
        )
        assertEquals(
            ShootStartIdentityDecision.ABSENT,
            ShootStartPolicy.classifySessionIdentity(request, exactSession = null),
        )
        assertEquals(
            ShootStartIdentityDecision.OWNED,
            ShootStartPolicy.classifySessionIdentity(request, exactSession = exact),
        )
        assertEquals(
            ShootStartIdentityDecision.SESSION_IDENTITY_CONFLICT,
            ShootStartPolicy.classifySessionIdentity(
                request,
                exactSession = exact.copy(shootId = "shoot-other"),
            ),
        )
        listOf(
            exact.copy(sessionId = "session-other"),
            exact.copy(shootId = "bad/id"),
            exact.copy(currentPoseIndex = -1),
            exact.copy(nextAttemptNumber = -1L),
            exact.copy(lifecycleState = "UNKNOWN"),
            exact.copy(createdAtEpochMillis = -1L),
            exact.copy(updatedAtEpochMillis = -1L),
            exact.copy(createdAtEpochMillis = 11L, updatedAtEpochMillis = 10L),
        ).forEach { malformed ->
            assertEquals(
                ShootStartIdentityDecision.AUTHORITY_INCONSISTENT,
                ShootStartPolicy.classifySessionIdentity(request, malformed),
            )
        }
        listOf(request, exact).forEach { value ->
            assertFalse(value.toString().contains("shoot-safe"))
            assertFalse(value.toString().contains("session-safe"))
        }
    }

    @Test
    fun startSessionClassificationRequiresAnExactReplayAcrossEveryPersistedField() {
        val request = ShootStartRequestValidation.Valid("shoot-safe", "session-safe", 10L)
        val exact = startSession()

        assertEquals(
            ShootStartSessionDecision.ELIGIBLE,
            ShootStartPolicy.classifySession(request, exactSession = null, activeSessionCount = 0),
        )
        assertEquals(
            ShootStartSessionDecision.ACTIVE_SESSION_CONFLICT,
            ShootStartPolicy.classifySession(request, exactSession = null, activeSessionCount = 1),
        )
        listOf(-1, 2).forEach { invalidCount ->
            assertEquals(
                ShootStartSessionDecision.AUTHORITY_INCONSISTENT,
                ShootStartPolicy.classifySession(request, exactSession = null, activeSessionCount = invalidCount),
            )
        }
        assertEquals(
            ShootStartSessionDecision.ALREADY_STARTED,
            ShootStartPolicy.classifySession(request, exactSession = exact, activeSessionCount = 1),
        )
        assertEquals(
            ShootStartSessionDecision.AUTHORITY_INCONSISTENT,
            ShootStartPolicy.classifySession(request, exactSession = exact, activeSessionCount = 0),
        )

        listOf(
            exact.copy(currentPoseIndex = 1),
            exact.copy(nextAttemptNumber = 1L),
            exact.copy(lifecycleState = "COMPLETED"),
            exact.copy(createdAtEpochMillis = 9L),
            exact.copy(updatedAtEpochMillis = 11L),
        ).forEach { mismatch ->
            assertEquals(
                ShootStartSessionDecision.STALE_OR_CONFLICTING_REPLAY,
                ShootStartPolicy.classifySession(request, mismatch, activeSessionCount = 1),
            )
        }
        assertEquals(
            ShootStartSessionDecision.SESSION_IDENTITY_CONFLICT,
            ShootStartPolicy.classifySession(
                request,
                exact.copy(shootId = "shoot-other"),
                activeSessionCount = 0,
            ),
        )
        assertEquals(
            ShootStartSessionDecision.AUTHORITY_INCONSISTENT,
            ShootStartPolicy.classifySession(
                request,
                exact.copy(sessionId = "session-other"),
                activeSessionCount = 1,
            ),
        )
        assertEquals(
            ShootStartSessionDecision.STALE_OR_CONFLICTING_REPLAY,
            ShootStartPolicy.classifySession(
                request.copy(startedAtEpochMillis = 11L),
                exact,
                activeSessionCount = 1,
            ),
        )
    }

    @Test
    fun malformedPersistedSessionAuthorityNeverClassifiesAsReplay() {
        val request = ShootStartRequestValidation.Valid("shoot-safe", "session-safe", 10L)
        val exact = startSession()
        listOf(
            exact.copy(sessionId = "bad/id"),
            exact.copy(shootId = "bad/id"),
            exact.copy(currentPoseIndex = -1),
            exact.copy(nextAttemptNumber = -1L),
            exact.copy(lifecycleState = "UNKNOWN"),
            exact.copy(createdAtEpochMillis = -1L),
            exact.copy(updatedAtEpochMillis = -1L),
            exact.copy(createdAtEpochMillis = 11L, updatedAtEpochMillis = 10L),
        ).forEach { malformed ->
            assertEquals(
                ShootStartSessionDecision.AUTHORITY_INCONSISTENT,
                ShootStartPolicy.classifySession(request, malformed, activeSessionCount = 1),
            )
        }
        assertEquals("ShootStartSessionSnapshot(redacted)", exact.toString())
        assertFalse(exact.toString().contains("shoot-safe"))
        assertFalse(exact.toString().contains("session-safe"))
    }

    @Test
    fun startPublicContractContainsNoAndroidOrRoomTypes() {
        val publicTypes = listOf(
            ShootStartResult::class.java,
            ShootStartInvalidReason::class.java,
            ShootStartIneligibleReason::class.java,
        )
        val typeNames = publicTypes.flatMap { type ->
            buildList {
                add(type.name)
                type.declaredFields.forEach { field -> add(field.type.name) }
                type.declaredMethods.forEach { method ->
                    add(method.returnType.name)
                    addAll(method.parameterTypes.map(Class<*>::getName))
                }
            }
        }
        assertTrue(typeNames.none { name ->
            name.startsWith("android.") || name.startsWith("androidx.room.")
        })
    }

    @Test
    fun startRepositoryBoundaryPersistsOnlySessionAuthorityAndHasNoCameraOrMediaSideEffects() {
        val source = productionSource(
            "app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootPreparationRepository.kt",
        )
        val startBoundary = source
            .substringAfter("    fun startShoot(")
            .substringBefore("    private fun reorderValidatedReferencesInTransaction(")
        listOf(
            "ShootStartPolicy.validateRequest(",
            "inTransaction { startShootInTransaction(validation) }",
        ).forEach { marker ->
            assertTrue("Missing durable start marker: $marker", marker in startBoundary)
        }
        listOf(
            "CameraXController",
            "Manifest.permission.CAMERA",
            "requestPermission",
            "MediaStore",
            "TextToSpeech",
            "CaptureAttemptEntity",
            "insertShoot(",
            "compareAndSetPoseIndex(",
        ).forEach { forbidden ->
            assertFalse("Forbidden shoot-start side effect: $forbidden", forbidden in startBoundary)
        }
    }

    @Test
    fun validProjectionFieldsArePreserved() {
        val shoot = ShootSummary(
            shootId = "shoot-1",
            name = "Morning set",
            validatedReferenceCount = 2,
            lifecycle = ShootPreparationLifecycle.ACTIVE,
            updatedAtEpochMillis = 9L,
        )
        val reference = reference(index = 1, poseId = "pose-2")
        val work = ImportWorkSummary(
            status = ImportWorkStatus.RECONCILIATION_REQUIRED,
            createdAtEpochMillis = 3L,
            updatedAtEpochMillis = 8L,
        )

        assertEquals("shoot-1", shoot.shootId)
        assertEquals("Morning set", shoot.name)
        assertEquals(2, shoot.validatedReferenceCount)
        assertEquals(ShootPreparationLifecycle.ACTIVE, shoot.lifecycle)
        assertEquals(9L, shoot.updatedAtEpochMillis)
        assertEquals("pose-2", reference.poseId)
        assertEquals(1, reference.poseIndex)
        assertEquals("Pose 1", reference.label)
        assertTrue(reference.mirrorAllowed)
        assertEquals(ImportWorkStatus.RECONCILIATION_REQUIRED, work.status)
        assertEquals(3L, work.createdAtEpochMillis)
        assertEquals(8L, work.updatedAtEpochMillis)
    }

    @Test
    fun editorAcceptsAnEmptyPreparation() {
        val editor = editor(updatedAtEpochMillis = 0L)

        assertEquals("shoot-1", editor.shootId)
        assertEquals("Morning set", editor.name)
        assertEquals(ShootPreparationLifecycle.ACTIVE, editor.lifecycle)
        assertTrue(editor.validatedReferences.isEmpty())
        assertTrue(editor.importWork.isEmpty())
        assertEquals(0L, editor.updatedAtEpochMillis)
    }

    @Test
    fun editorAcceptsExactlyTwentyOrderedReferences() {
        val references = references(Shoot.MAX_REFERENCE_POSES)

        val editor = editor(validatedReferences = references, updatedAtEpochMillis = 20L)

        assertEquals(Shoot.MAX_REFERENCE_POSES, editor.validatedReferences.size)
        assertEquals((0 until Shoot.MAX_REFERENCE_POSES).toList(), editor.validatedReferences.map { it.poseIndex })
        assertEquals(references.map { it.poseId }, editor.validatedReferences.map { it.poseId })
    }

    @Test
    fun editorRejectsDuplicatePoseIds() {
        assertThrows(IllegalArgumentException::class.java) {
            editor(
                validatedReferences = listOf(
                    reference(index = 0, poseId = "same-pose"),
                    reference(index = 1, poseId = "same-pose"),
                ),
            )
        }
    }

    @Test
    fun editorRejectsGapsAndDuplicateIndexes() {
        listOf(
            listOf(reference(index = 0), reference(index = 2)),
            listOf(reference(index = 0), reference(index = 0, poseId = "pose-other")),
        ).forEach { invalidReferences ->
            assertThrows(IllegalArgumentException::class.java) {
                editor(validatedReferences = invalidReferences)
            }
        }
    }

    @Test
    fun editorRejectsTwentyOneReferences() {
        assertThrows(IllegalArgumentException::class.java) {
            editor(validatedReferences = references(Shoot.MAX_REFERENCE_POSES + 1))
        }
    }

    @Test
    fun everyTextProjectionFieldRejectsBlankNulAndProviderUriValues() {
        listOf("", " \t\n", "bad\u0000value", "ConTent://provider/private").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ShootSummary(invalid, "name", 0, ShootPreparationLifecycle.ACTIVE, 0L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ShootSummary("shoot", invalid, 0, ShootPreparationLifecycle.ACTIVE, 0L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ValidatedReferenceSummary(invalid, 0, "label", false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ValidatedReferenceSummary("pose", 0, invalid, false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ShootEditorSnapshot(
                    invalid,
                    "name",
                    ShootPreparationLifecycle.ACTIVE,
                    emptyList(),
                    emptyList(),
                    0L,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                ShootEditorSnapshot(
                    "shoot",
                    invalid,
                    ShootPreparationLifecycle.ACTIVE,
                    emptyList(),
                    emptyList(),
                    0L,
                )
            }
        }
    }

    @Test
    fun countsAndIndexesStayWithinProjectionBounds() {
        listOf(-1, Shoot.MAX_REFERENCE_POSES + 1).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) {
                ShootSummary("shoot", "name", count, ShootPreparationLifecycle.ACTIVE, 0L)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            reference(index = -1)
        }
    }

    @Test
    fun timestampsAreNonnegativeAndOrdered() {
        assertThrows(IllegalArgumentException::class.java) {
            ShootSummary("shoot", "name", 0, ShootPreparationLifecycle.ACTIVE, -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportWorkSummary(ImportWorkStatus.IN_PROGRESS, -1L, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportWorkSummary(ImportWorkStatus.IN_PROGRESS, 0L, -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportWorkSummary(ImportWorkStatus.IN_PROGRESS, 2L, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            editor(updatedAtEpochMillis = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            editor(
                importWork = listOf(
                    ImportWorkSummary(ImportWorkStatus.IN_PROGRESS, 2L, 3L),
                ),
                updatedAtEpochMillis = 2L,
            )
        }
    }

    @Test
    fun iterableInputsAreDefensivelyCopiedAndUnmodifiable() {
        val sourceReferences = mutableListOf(reference(index = 0))
        val sourceWork = mutableListOf(
            ImportWorkSummary(ImportWorkStatus.IN_PROGRESS, 1L, 2L),
        )
        val editor = editor(
            validatedReferences = sourceReferences,
            importWork = sourceWork,
            updatedAtEpochMillis = 2L,
        )

        sourceReferences.clear()
        sourceWork.clear()
        assertEquals(1, editor.validatedReferences.size)
        assertEquals(1, editor.importWork.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (editor.validatedReferences as MutableList).add(reference(index = 1))
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (editor.importWork as MutableList).clear()
        }
    }

    @Test
    fun publicRepresentationsAreStableTypeOnlyAndRedacted() {
        val shootIdSecret = "<SECRET:shoot-id>"
        val shootNameSecret = "<SECRET:shoot-name>"
        val poseIdSecret = "<SECRET:pose-id>"
        val labelSecret = "<SECRET:label>"
        val shoot = ShootSummary(
            shootIdSecret,
            shootNameSecret,
            1,
            ShootPreparationLifecycle.ACTIVE,
            4L,
        )
        val reference = ValidatedReferenceSummary(poseIdSecret, 0, labelSecret, true)
        val work = ImportWorkSummary(ImportWorkStatus.REJECTED_QUARANTINED, 2L, 3L)
        val editor = ShootEditorSnapshot(
            shootIdSecret,
            shootNameSecret,
            ShootPreparationLifecycle.DELETING,
            listOf(reference),
            listOf(work),
            4L,
        )
        val expected = mapOf(
            shoot to "ShootSummary(redacted)",
            reference to "ValidatedReferenceSummary(redacted)",
            work to "ImportWorkSummary(redacted)",
            editor to "ShootEditorSnapshot(redacted)",
        )

        expected.forEach { (value, exactRepresentation) ->
            val rendered = value.toString()
            assertEquals(exactRepresentation, rendered)
            listOf(shootIdSecret, shootNameSecret, poseIdSecret, labelSecret).forEach { secret ->
                assertFalse(rendered.contains(secret))
            }
            assertFalse(rendered.contains("content://", ignoreCase = true))
        }
        ShootPreparationLifecycle.entries.forEach { lifecycle ->
            assertEquals(lifecycle.name, lifecycle.toString())
        }
        ImportWorkStatus.entries.forEach { status ->
            assertEquals(status.name, status.toString())
        }
    }

    @Test
    fun instancesAndTheirCollectionsAreIsolatedEvenWhenEmpty() {
        val first = editor()
        val second = editor()

        assertNotSame(first, second)
        assertNotSame(first.validatedReferences, second.validatedReferences)
        assertNotSame(first.importWork, second.importWork)
    }

    private fun editor(
        validatedReferences: Iterable<ValidatedReferenceSummary> = emptyList(),
        importWork: Iterable<ImportWorkSummary> = emptyList(),
        updatedAtEpochMillis: Long = 10L,
    ): ShootEditorSnapshot = ShootEditorSnapshot(
        shootId = "shoot-1",
        name = "Morning set",
        lifecycle = ShootPreparationLifecycle.ACTIVE,
        validatedReferences = validatedReferences,
        importWork = importWork,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun references(count: Int): List<ValidatedReferenceSummary> =
        (0 until count).map { index -> reference(index = index) }

    private fun reference(
        index: Int,
        poseId: String = "pose-$index",
    ): ValidatedReferenceSummary = ValidatedReferenceSummary(
        poseId = poseId,
        poseIndex = index,
        label = "Pose $index",
        mirrorAllowed = true,
    )

    private fun assertReorderRequestInvalid(
        expected: ShootReorderInvalidReason,
        shootId: String,
        orderedPoseIds: List<String>,
        reorderedAtEpochMillis: Long,
    ) {
        val validation = ShootReorderPolicy.validateRequest(
            shootId = shootId,
            orderedPoseIds = orderedPoseIds,
            reorderedAtEpochMillis = reorderedAtEpochMillis,
        )
        assertTrue(validation is ShootReorderRequestValidation.Invalid)
        validation as ShootReorderRequestValidation.Invalid
        assertEquals(ShootReorderResult.InvalidRequest(expected), validation.result)
        assertEquals("ShootReorderRequestValidation.Invalid(redacted)", validation.toString())
    }

    private fun assertStartRequestInvalid(
        expected: ShootStartInvalidReason,
        shootId: String,
        sessionId: String,
        startedAtEpochMillis: Long,
    ) {
        val validation = ShootStartPolicy.validateRequest(
            shootId = shootId,
            sessionId = sessionId,
            startedAtEpochMillis = startedAtEpochMillis,
        )
        assertTrue(validation is ShootStartRequestValidation.Invalid)
        validation as ShootStartRequestValidation.Invalid
        assertEquals(ShootStartResult.InvalidRequest(expected), validation.result)
        assertEquals("ShootStartRequestValidation.Invalid(redacted)", validation.toString())
    }

    private fun startSession(): ShootStartSessionSnapshot = ShootStartSessionSnapshot(
        sessionId = "session-safe",
        shootId = "shoot-safe",
        currentPoseIndex = 0,
        nextAttemptNumber = 0L,
        lifecycleState = "ACTIVE",
        createdAtEpochMillis = 10L,
        updatedAtEpochMillis = 10L,
    )

    private fun productionSource(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
        return root.resolve(relativePath).readText()
    }
}
