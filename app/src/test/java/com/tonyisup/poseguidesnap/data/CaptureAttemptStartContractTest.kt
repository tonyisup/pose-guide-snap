package com.tonyisup.poseguidesnap.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAttemptStartContractTest {
    @Test
    fun logicalStartNameAndResultMakeNoPhysicalAuthorizationClaim() {
        val repositorySource = productionSource(REPOSITORY_SOURCE_PATH)
        val daoSource = productionSource(DAO_SOURCE_PATH)

        listOf(
            "sealed interface CaptureAttemptStartResult",
            "data object Started : CaptureAttemptStartResult",
            "data object AlreadyStarted : CaptureAttemptStartResult",
            "data object BlockedByDeletion : CaptureAttemptStartResult",
            "data class Rejected(val reason: CaptureAttemptStartRejectionReason) : " +
                "CaptureAttemptStartResult",
            "enum class CaptureAttemptStartRejectionReason",
            "internal object CaptureAttemptStartPolicy",
            "fun markCaptureAttemptStarted(",
            "startedAtEpochMillis: Long",
            "captureAttemptDao.markCaptureAttemptStarted(",
        ).forEach { marker ->
            assertTrue("Missing repository logical-start marker: $marker", marker in repositorySource)
        }
        listOf(
            "fun markCaptureAttemptStarted(",
            "startedAtEpochMillis: Long",
            "updated_at_epoch_millis = :startedAtEpochMillis",
        ).forEach { marker ->
            assertTrue("Missing DAO logical-start marker: $marker", marker in daoSource)
        }

        val resultStart = repositorySource.indexOf("sealed interface CaptureAttemptStartResult")
        val rejectionReasonStart =
            repositorySource.indexOf("enum class CaptureAttemptStartRejectionReason")
        assertTrue("CaptureAttemptStartResult declaration marker is missing", resultStart >= 0)
        assertTrue(
            "CaptureAttemptStartRejectionReason must follow CaptureAttemptStartResult",
            rejectionReasonStart > resultStart,
        )
        val resultDeclaration = repositorySource.substring(resultStart, rejectionReasonStart)
        assertEquals(
            setOf("Started", "AlreadyStarted", "BlockedByDeletion", "Rejected"),
            Regex("data (?:object|class) ([A-Za-z]+)")
                .findAll(resultDeclaration)
                .map { match -> match.groupValues[1] }
                .toSet(),
        )
        listOf("authoriz", "camera", "filesystem", "physical", "file authority").forEach { claim ->
            assertFalse(
                "Logical-start result vocabulary must not claim $claim authority",
                resultDeclaration.contains(claim, ignoreCase = true),
            )
        }
    }

    @Test
    fun oldCaptureAuthorizationApiIsAbsent() {
        val forbiddenIdentifiers = listOf(
            "authorize" + "CaptureStart",
            "CaptureStart" + "AuthorizationResult",
            "CaptureStart" + "RejectionReason",
            "CaptureStart" + "AuthorizationPolicy",
            "authorizedAt" + "EpochMillis",
        )
        val sources = productionKotlinSources()
        assertTrue("No production Kotlin sources were found", sources.isNotEmpty())

        forbiddenIdentifiers.forEach { forbidden ->
            val matches = sources.filter { source -> forbidden in source.readText() }
            assertTrue(
                "Forbidden production identifier $forbidden found in: " +
                    matches.joinToString { source -> source.relativeTo(projectRoot()).path },
                matches.isEmpty(),
            )
        }
    }

    private fun productionSource(relativePath: String): String {
        val source = projectRoot().resolve(relativePath)
        assertTrue("Production source does not exist: $relativePath", source.isFile)
        return source.readText()
    }

    private fun productionKotlinSources(): List<File> {
        val mainSource = projectRoot().resolve("app/src/main")
        assertTrue("Production source root does not exist", mainSource.isDirectory)
        return mainSource.walkTopDown()
            .filter { source -> source.isFile && source.extension == "kt" }
            .toList()
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { candidate -> candidate.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
    }

    private companion object {
        const val REPOSITORY_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootRepository.kt"
        const val DAO_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureAttemptDao.kt"
    }
}
