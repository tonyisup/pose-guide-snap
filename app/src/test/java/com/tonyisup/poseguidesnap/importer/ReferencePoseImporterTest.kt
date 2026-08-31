package com.tonyisup.poseguidesnap.importer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePoseImporterTest {
    @Test
    fun productionImportSurfaceExposesOnlyTheJournalBackedCoordinatorAndAdapters() {
        val source = productionSource(
            "app/src/main/java/com/tonyisup/poseguidesnap/importer/ReferencePoseImporter.kt",
        )

        listOf(
            "class JournaledReferencePoseImporter(",
            "ReferenceImportFileJournalPort",
            "JournaledReferenceImportAssetPort",
            "RoomReferenceImportFileJournalAdapter",
            "JournaledReferenceAssetStoreAdapter",
            "DurableReferenceAnalyzerAsset",
        ).forEach { marker -> assertTrue("Missing journal-backed marker: $marker", marker in source) }
        listOf(
            "class ReferencePoseImporter(",
            "interface ReferenceImportAssetPort",
            "class ReferenceAssetStoreAdapter",
            "PublishedReferenceAsset",
        ).forEach { forbidden ->
            assertFalse("Unsafe legacy import surface remains: $forbidden", forbidden in source)
        }
    }

    private fun productionSource(relativePath: String): String = projectRoot().resolve(relativePath).readText()

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
    }
}
