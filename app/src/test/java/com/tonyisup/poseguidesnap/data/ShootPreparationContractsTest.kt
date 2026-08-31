package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.model.Shoot
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
            listOf("IN_PROGRESS", "NEEDS_ATTENTION"),
            ImportWorkStatus.entries.map(Enum<*>::name),
        )
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
            status = ImportWorkStatus.NEEDS_ATTENTION,
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
        assertEquals(ImportWorkStatus.NEEDS_ATTENTION, work.status)
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
        val work = ImportWorkSummary(ImportWorkStatus.NEEDS_ATTENTION, 2L, 3L)
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
}
