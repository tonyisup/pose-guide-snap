package com.tonyisup.poseguidesnap.importer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePickerResultHandlerSourceContractTest {
    @Test
    fun productionSourceOwnsOnlyExplicitCallbackValidationDeferredStreamAndInjectedDispatch() {
        val source = productionSource(HANDLER_SOURCE_PATH)
        listOf(
            "suspend fun handle(uri: Uri?, draft: ReferencePickerImportDraft): ReferencePickerResult",
            "dispatcher: CoroutineDispatcher = Dispatchers.IO",
            "withContext(dispatcher)",
            "coroutineContext.ensureActive()",
            "sourceFactory.create(uri)",
            "importer.importReference(",
            "ReferenceAssetByteSource {",
            "contentResolver.openInputStream(uri)",
            "ReferencePickerResult.Completed",
            "catch (cancelled: CancellationException)",
            "throw cancelled",
        ).forEach { marker ->
            assertTrue("Missing picker handler contract marker: $marker", marker in source)
        }

        listOf(
            "ActivityResultContracts",
            "PickVisualMedia",
            "Intent",
            "MediaStore",
            "DocumentsContract",
            "takePersistableUriPermission",
            "FLAG_GRANT_PERSISTABLE_URI_PERMISSION",
            "persistedUriPermissions",
            ".query(",
            "getType(",
            "openFileDescriptor(",
            "android.util.Log",
            "Log.",
            "printStackTrace",
            "File(",
            "Paths.",
            "Environment.",
            "System.currentTimeMillis",
            "System.nanoTime",
        ).forEach { forbidden ->
            assertFalse("Forbidden picker handler marker: $forbidden", forbidden in source)
        }
        assertEquals(1, Regex("contentResolver\\.openInputStream\\(uri\\)").findAll(source).count())
    }

    @Test
    fun draftAndResultContractsHaveNoUriSourceErrorOrDefaultDataClassRendering() {
        val source = productionSource(HANDLER_SOURCE_PATH)
        val draft = source
            .substringAfter("class ReferencePickerImportDraft(")
            .substringBefore("fun interface JournaledReferencePickerImporterPort")
        val results = source
            .substringAfter("sealed interface ReferencePickerResult")
            .substringBefore("class ReferencePickerResultHandler")

        listOf("Uri", "ReferenceAssetByteSource", "Throwable", "Exception", "source").forEach { forbidden ->
            assertFalse("Draft retained forbidden value: $forbidden", forbidden in draft)
        }
        listOf("Uri", "Throwable", "Exception", "label", "importToken").forEach { forbidden ->
            assertFalse("Result retained forbidden value: $forbidden", forbidden in results)
        }
        assertFalse("Draft must not use generated data-class toString", draft.trimStart().startsWith("data class"))
        assertTrue("Draft must define stable redaction", "ReferencePickerImportDraft(redacted)" in draft)
        assertTrue("Completed must retain only the existing import result", "val importResult: ReferencePoseImportResult" in results)
    }

    @Test
    fun coroutineDependenciesAreExplicitAndUsePinnedCatalogAliasesExactlyOnce() {
        val build = productionSource("app/build.gradle.kts")
        val catalog = productionSource("gradle/libs.versions.toml")

        assertEquals(
            1,
            build.lineSequence().count { it.trim() == "implementation(libs.kotlinx.coroutines.android)" },
        )
        assertEquals(
            1,
            build.lineSequence().count { it.trim() == "testImplementation(libs.kotlinx.coroutines.test)" },
        )
        assertTrue("coroutines = \"1.11.0\"" in catalog)
        assertTrue(
            "kotlinx-coroutines-android = { module = \"org.jetbrains.kotlinx:kotlinx-coroutines-android\", version.ref = \"coroutines\" }" in catalog,
        )
        assertTrue(
            "kotlinx-coroutines-test = { module = \"org.jetbrains.kotlinx:kotlinx-coroutines-test\", version.ref = \"coroutines\" }" in catalog,
        )
    }

    private fun productionSource(relativePath: String): String = projectRoot().resolve(relativePath).readText()

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
    }

    private companion object {
        const val HANDLER_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/importer/ReferencePickerResultHandler.kt"
    }
}
