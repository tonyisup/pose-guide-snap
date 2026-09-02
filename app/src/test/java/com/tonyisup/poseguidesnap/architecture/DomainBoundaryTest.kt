package com.tonyisup.poseguidesnap.architecture

import com.tonyisup.poseguidesnap.domain.model.BodySide
import com.tonyisup.poseguidesnap.domain.model.CoachingCue
import com.tonyisup.poseguidesnap.domain.model.CoachingDirection
import com.tonyisup.poseguidesnap.domain.model.CoachingJoint
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.MatchGateFailure
import com.tonyisup.poseguidesnap.domain.model.MatchResult
import com.tonyisup.poseguidesnap.domain.model.PoseDetectorMetadata
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.domain.model.ReferencePose
import com.tonyisup.poseguidesnap.domain.model.Shoot
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBoundaryTest {
    @Test
    fun analyzerRejectsFrameworkImportInTemporarySource() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("Fixture.kt").writeText(
                """
                package fixture

                import android.graphics.Bitmap

                data class Fixture(val bitmap: Bitmap)
                """.trimIndent(),
            )

            assertEquals(
                listOf(
                    "Fixture.kt: forbidden dependency reference android.graphics.Bitmap",
                    "Fixture.kt: forbidden import android.graphics.Bitmap",
                ),
                boundaryViolations(fixtureRoot),
            )
        }
    }

    @Test
    fun negativeControlsCoverEveryForbiddenDependencyAndWallClockCall() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("ForbiddenImports.kt").writeText(
                """
                package fixture

                import android.graphics.Bitmap
                import androidx.camera.core.ImageProxy
                import androidx.compose.runtime.Composable
                import androidx.room.Entity
                import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
                import java.io.File
                import java.nio.file.Path
                import java.time.Clock
                import kotlin.io.path.Path
                """.trimIndent(),
            )
            fixtureRoot.resolve("WallClock.kt").writeText(
                """
                package fixture

                fun nowMillis() = System.currentTimeMillis()
                fun nowNanos() = java.lang.System.nanoTime()
                """.trimIndent(),
            )

            val violations = boundaryViolations(fixtureRoot)
            assertEquals(11, violations.size)
            FORBIDDEN_IMPORT_PREFIXES.forEach { prefix ->
                assertTrue(
                    "Negative control did not exercise $prefix: $violations",
                    violations.any { it.contains("forbidden import $prefix") },
                )
            }
            assertTrue(violations.any { it.contains("System.currentTimeMillis") })
            assertTrue(violations.any { it.contains("System.nanoTime") })
        }
    }

    @Test
    fun analyzerInspectsExecutableStringTemplateExpressions() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("StringTemplate.kt").writeText(
                """
                package fixture

                val now = "${'$'}{System.currentTimeMillis()}"
                """.trimIndent(),
            )

            assertEquals(
                listOf("StringTemplate.kt: forbidden wall-clock call System.currentTimeMillis"),
                boundaryViolations(fixtureRoot),
            )
        }
    }

    @Test
    fun analyzerResolvesAliasedWallClockImports() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("AliasedWallClock.kt").writeText(
                """
                package fixture

                import java.lang.System as WallClock

                val now = WallClock.currentTimeMillis()
                """.trimIndent(),
            )

            assertEquals(
                listOf("AliasedWallClock.kt: forbidden wall-clock call System.currentTimeMillis"),
                boundaryViolations(fixtureRoot),
            )
        }
    }

    @Test
    fun analyzerRejectsSemicolonImportsAndDirectlyImportedSystemCalls() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("SemicolonImport.kt").writeText(
                """
                package fixture

                import java.io.File;
                """.trimIndent(),
            )
            fixtureRoot.resolve("ImportedSystemCalls.kt").writeText(
                """
                package fixture

                import java.lang.System.currentTimeMillis
                import java.lang.System.nanoTime as clockNanos

                val millis = currentTimeMillis()
                val nanos = clockNanos()
                """.trimIndent(),
            )

            assertEquals(
                listOf(
                    "ImportedSystemCalls.kt: forbidden wall-clock call System.currentTimeMillis",
                    "ImportedSystemCalls.kt: forbidden wall-clock call System.nanoTime",
                    "ImportedSystemCalls.kt: forbidden wall-clock import System.currentTimeMillis",
                    "ImportedSystemCalls.kt: forbidden wall-clock import System.nanoTime",
                    "SemicolonImport.kt: forbidden import java.io.File",
                ),
                boundaryViolations(fixtureRoot),
            )
        }
    }

    @Test
    fun analyzerRejectsWallClockImportsWithoutCalls() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("ClockImports.kt").writeText(
                """
                package fixture

                import java.lang.System.currentTimeMillis
                import java.lang.System.nanoTime as platformNanos
                import java.time.Clock as WallClock
                import java.time.Instant.now as wallNow
                import kotlin.time.TimeSource as MarkSource
                import kotlin.time.TimeSource.Monotonic.markNow as hiddenMark
                """.trimIndent(),
            )

            assertEquals(
                listOf(
                    "ClockImports.kt: forbidden import java.time.Clock",
                    "ClockImports.kt: forbidden wall-clock import Instant.now",
                    "ClockImports.kt: forbidden wall-clock import System.currentTimeMillis",
                    "ClockImports.kt: forbidden wall-clock import System.nanoTime",
                    "ClockImports.kt: forbidden wall-clock import TimeSource",
                    "ClockImports.kt: forbidden wall-clock import TimeSource.markNow",
                ),
                boundaryViolations(fixtureRoot),
            )
        }
    }

    @Test
    fun analyzerRejectsParenthesizedSystemClockProviderAndQualifiedFileCalls() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("ReviewerBypasses.kt").writeText(
                """
                package fixture

                val millis = (java.lang.System).currentTimeMillis()
                val instant = java.time.Clock.systemUTC().instant()
                val file = java.io.File("x")
                """.trimIndent(),
            )

            assertEquals(
                listOf(
                    "ReviewerBypasses.kt: forbidden dependency reference java.io.File",
                    "ReviewerBypasses.kt: forbidden wall-clock call Clock.systemUTC",
                    "ReviewerBypasses.kt: forbidden wall-clock call System.currentTimeMillis",
                ),
                boundaryViolations(fixtureRoot),
            )
        }
    }

    @Test
    fun analyzerResolvesSystemInstantAndTimeSourceAliases() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("ProviderAliases.kt").writeText(
                """
                package fixture

                import java.lang.System as PlatformSystem
                import java.time.Instant as WallInstant
                import kotlin.time.TimeSource as ClockSource

                val millis = PlatformSystem.currentTimeMillis()
                val instant = WallInstant.now()
                val mark = ClockSource.Monotonic.markNow()
                """.trimIndent(),
            )

            assertEquals(
                listOf(
                    "ProviderAliases.kt: forbidden wall-clock call Instant.now",
                    "ProviderAliases.kt: forbidden wall-clock call System.currentTimeMillis",
                    "ProviderAliases.kt: forbidden wall-clock call TimeSource.markNow",
                    "ProviderAliases.kt: forbidden wall-clock import TimeSource",
                ),
                boundaryViolations(fixtureRoot),
            )
        }
    }

    @Test
    fun analyzerRejectsFullyQualifiedAndImportedFrameworkTypeReferences() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("FrameworkTypes.kt").writeText(
                """
                package fixture

                import androidx.camera.core.ImageProxy as CameraFrame

                data class FrameworkTypes(
                    val bitmap: android.graphics.Bitmap,
                    val frame: CameraFrame,
                    val pose: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker,
                )
                """.trimIndent(),
            )

            val violations = boundaryViolations(fixtureRoot)
            assertTrue(violations.contains("FrameworkTypes.kt: forbidden import androidx.camera.core.ImageProxy"))
            assertTrue(violations.contains("FrameworkTypes.kt: forbidden dependency reference android.graphics.Bitmap"))
            assertTrue(violations.contains("FrameworkTypes.kt: forbidden dependency reference androidx.camera.core.ImageProxy"))
            assertTrue(
                violations.contains(
                    "FrameworkTypes.kt: forbidden dependency reference " +
                        "com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker",
                ),
            )
        }
    }

    @Test
    fun analyzerRejectsExecutableFrameworkPropertyReferences() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("FrameworkProperties.kt").writeText(
                """
                package fixture

                import android.os.Build as DeviceBuild

                val sdk = DeviceBuild.VERSION.SDK_INT
                val separator = java.io.File.separatorChar
                """.trimIndent(),
            )

            assertEquals(
                listOf(
                    "FrameworkProperties.kt: forbidden dependency reference android.os.Build.VERSION.SDK_INT",
                    "FrameworkProperties.kt: forbidden dependency reference java.io.File.separatorChar",
                    "FrameworkProperties.kt: forbidden import android.os.Build",
                ),
                boundaryViolations(fixtureRoot),
            )
        }
    }

    @Test
    fun analyzerRejectsEverySupportedJavaTimeNowCall() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("JavaTimeNow.kt").writeText(
                """
                package fixture

                import java.time.Instant
                import java.time.LocalDate
                import java.time.LocalTime
                import java.time.LocalDateTime
                import java.time.OffsetDateTime
                import java.time.ZonedDateTime

                val instant = Instant.now()
                val date = LocalDate.now()
                val time = LocalTime.now()
                val dateTime = LocalDateTime.now()
                val offset = OffsetDateTime.now()
                val zoned = ZonedDateTime.now()
                """.trimIndent(),
            )

            assertEquals(
                listOf(
                    "JavaTimeNow.kt: forbidden wall-clock call Instant.now",
                    "JavaTimeNow.kt: forbidden wall-clock call LocalDate.now",
                    "JavaTimeNow.kt: forbidden wall-clock call LocalDateTime.now",
                    "JavaTimeNow.kt: forbidden wall-clock call LocalTime.now",
                    "JavaTimeNow.kt: forbidden wall-clock call OffsetDateTime.now",
                    "JavaTimeNow.kt: forbidden wall-clock call ZonedDateTime.now",
                ),
                boundaryViolations(fixtureRoot),
            )
        }
    }

    @Test
    fun analyzerRejectsWallClockCallableReferences() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("CallableReferences.kt").writeText(
                """
                package fixture

                import java.lang.System.currentTimeMillis as wallMillis

                val millisProvider = System::nanoTime
                val importedProvider = ::wallMillis
                """.trimIndent(),
            )

            assertEquals(
                listOf(
                    "CallableReferences.kt: forbidden wall-clock call System.currentTimeMillis",
                    "CallableReferences.kt: forbidden wall-clock call System.nanoTime",
                    "CallableReferences.kt: forbidden wall-clock import System.currentTimeMillis",
                ),
                boundaryViolations(fixtureRoot),
            )
        }
    }

    @Test
    fun analyzerIgnoresForbiddenTokensInsideCommentsAndStrings() {
        withTemporarySourceTree { fixtureRoot ->
            fixtureRoot.resolve("Safe.kt").writeText(
                """
                package fixture

                // import android.graphics.Bitmap
                /*
                 * import androidx.camera.core.ImageProxy
                 * System.currentTimeMillis()
                 */
                val example = "import androidx.room.Entity"
                val escapedTemplate = "\${'$'}{System.currentTimeMillis()}"
                val longerExample = ${"\"\"\""}
                    import com.google.mediapipe.tasks.Fixture
                    System.nanoTime()
                ${"\"\"\""}.trimIndent()
                """.trimIndent(),
            )

            assertTrue(boundaryViolations(fixtureRoot).isEmpty())
        }
    }

    @Test
    fun realDomainTreeContainsRequiredPureKotlinContracts() {
        val domainRoot = projectRoot().resolve(DOMAIN_SOURCE_PATH)
        assertTrue("Missing domain source tree: $domainRoot", domainRoot.isDirectory)

        val sourceFiles = domainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(domainRoot).invariantSeparatorsPath }
            .toSet()
        assertTrue("Domain source tree contains no Kotlin files", sourceFiles.isNotEmpty())
        assertEquals(REQUIRED_MODEL_FILES, REQUIRED_MODEL_FILES.intersect(sourceFiles))
        assertEquals(emptyList<String>(), boundaryViolations(domainRoot))
    }

    @Test
    fun guidedSessionBootstrapContractsAndMapperRemainPureKotlin() {
        val dataRoot = projectRoot().resolve(
            "app/src/main/java/com/tonyisup/poseguidesnap/data",
        )
        withTemporarySourceTree { fixtureRoot ->
            setOf(
                "GuidedSessionContracts.kt",
                "GuidedSessionBootstrapMapper.kt",
            ).forEach { fileName ->
                val source = dataRoot.resolve(fileName)
                assertTrue("Missing guided bootstrap source: $source", source.isFile)
                source.copyTo(fixtureRoot.resolve(fileName))
            }

            assertEquals(emptyList<String>(), boundaryViolations(fixtureRoot))
        }
    }

    @Test
    fun landmarkValidatesCoordinatesDepthAndConfidence() {
        val minimum = landmark(x = 0.0, y = 0.0, z = -10.0, visibility = 0.0, presence = 0.0)
        val maximum = landmark(x = 1.0, y = 1.0, z = 10.0, visibility = 1.0, presence = 1.0)
        assertEquals(0.0, minimum.x, 0.0)
        assertEquals(1.0, maximum.x, 0.0)

        listOf(-0.0001, 1.0001, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            .forEach { invalid ->
                assertRejects { landmark(x = invalid) }
                assertRejects { landmark(y = invalid) }
                assertRejects { landmark(visibility = invalid) }
                assertRejects { landmark(presence = invalid) }
            }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertRejects { landmark(z = invalid) }
        }
    }

    @Test
    fun poseObservationOwnsTimestampPersonCountAndLandmarkSnapshot() {
        val callerLandmarks = mutableListOf(landmark())
        val observation = PoseObservation(
            landmarks = callerLandmarks,
            monotonicTimestampNanos = 0,
            detectedPersonCount = 1,
        )
        callerLandmarks.clear()

        assertEquals(1, observation.landmarks.size)
        assertEquals(0, observation.monotonicTimestampNanos)
        assertThrows(UnsupportedOperationException::class.java) {
            (observation.landmarks as MutableList).clear()
        }
        assertEquals(
            0,
            PoseObservation(emptyList(), monotonicTimestampNanos = 1, detectedPersonCount = 0)
                .detectedPersonCount,
        )

        assertRejects { PoseObservation(listOf(landmark()), -1, 1) }
        assertRejects { PoseObservation(listOf(landmark()), 1, -1) }
        assertRejects { PoseObservation(emptyList(), 1, 1) }
        assertRejects { PoseObservation(listOf(landmark()), 1, 0) }
        assertRejects {
            PoseObservation(listOf(landmark(), landmark()), 1, 1)
        }
    }

    @Test
    fun referencePoseValidatesIdentityMetadataTimestampAndLandmarkSnapshot() {
        val callerLandmarks = mutableListOf(landmark())
        val reference = referencePose(id = "pose-1", landmarks = callerLandmarks)
        callerLandmarks.clear()

        assertEquals("pose-1", reference.id)
        assertEquals(1, reference.landmarks.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (reference.landmarks as MutableList).clear()
        }
        assertRejects { referencePose(id = " ") }
        assertRejects { referencePose(label = "\t") }
        assertRejects { referencePose(importedAtEpochMillis = -1) }
        assertRejects { referencePose(landmarks = emptyList()) }
        assertRejects { referencePose(landmarks = listOf(landmark(), landmark())) }
        assertRejects { PoseDetectorMetadata("", "pose-landmarker", "1") }
        assertRejects { PoseDetectorMetadata("detector", "", "1") }
        assertRejects { PoseDetectorMetadata("detector", "pose-landmarker", " ") }
    }

    @Test
    fun matchResultBoundsNamedScoresAndCannotHideMandatoryFailures() {
        val mutableFailures = linkedSetOf(MatchGateFailure.POOR_FRAMING)
        val blocked = matchResult(
            gateFailures = mutableFailures,
            mirrorUsed = true,
            eligibleForLock = false,
        )
        mutableFailures.clear()

        assertEquals(setOf(MatchGateFailure.POOR_FRAMING), blocked.gateFailures)
        assertTrue(blocked.mirrorUsed)
        assertFalse(blocked.eligibleForLock)
        assertThrows(UnsupportedOperationException::class.java) {
            (blocked.gateFailures as MutableSet).add(MatchGateFailure.NO_PERSON)
        }
        assertRejects {
            matchResult(
                gateFailures = setOf(MatchGateFailure.MULTIPLE_PEOPLE),
                eligibleForLock = true,
            )
        }

        listOf(-0.01, 1.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
            .forEach { invalid ->
                assertRejects { matchResult(landmarkCoverage = invalid) }
                assertRejects { matchResult(framingScore = invalid) }
                assertRejects { matchResult(angularSimilarity = invalid) }
                assertRejects { matchResult(positionalSimilarity = invalid) }
                assertRejects { matchResult(overallMatch = invalid) }
            }

        val eligible = matchResult(eligibleForLock = true)
        assertTrue(eligible.eligibleForLock)
        assertTrue(eligible.gateFailures.isEmpty())
    }

    @Test
    fun coachingCueUsesFixedSemanticVocabularyWithExplicitSideAndDirection() {
        val cue = CoachingCue.MoveJoint(
            joint = CoachingJoint.WRIST,
            side = BodySide.LEFT,
            direction = CoachingDirection.UP,
        )
        assertEquals(BodySide.LEFT, cue.side)
        assertEquals(CoachingDirection.UP, cue.direction)
        assertEquals(CoachingCue.PoseMatched, CoachingCue.PoseMatched)
        assertRejects { CoachingCue.TurnShoulders(CoachingDirection.UP) }
        assertRejects { CoachingCue.LeanTorso(CoachingDirection.FORWARD) }
    }

    @Test
    fun shootValidatesDefinitionCardinalityUniquenessTimestampAndOrdering() {
        val first = referencePose("pose-1")
        val second = referencePose("pose-2")
        val third = referencePose("pose-3")
        val callerReferences = mutableListOf(first, second, third)
        val shoot = Shoot(
            id = "shoot-1",
            name = "Morning sequence",
            referencePoses = callerReferences,
            createdAtEpochMillis = 0,
        )
        callerReferences.reverse()
        callerReferences.clear()

        assertEquals(listOf("pose-1", "pose-2", "pose-3"), shoot.referencePoses.map { it.id })
        assertThrows(UnsupportedOperationException::class.java) {
            (shoot.referencePoses as MutableList).clear()
        }
        assertRejects { shoot(id = " ") }
        assertRejects { shoot(name = "") }
        assertRejects { shoot(createdAtEpochMillis = -1) }
        assertRejects { shoot(referencePoses = listOf(first, second)) }
        assertRejects {
            shoot(referencePoses = (1..21).map { referencePose("pose-$it") })
        }
        assertRejects { shoot(referencePoses = listOf(first, first, third)) }
        assertEquals(
            20,
            shoot(referencePoses = (1..20).map { referencePose("pose-$it") })
                .referencePoses.size,
        )
    }

    private fun boundaryViolations(sourceRoot: File): List<String> =
        KotlinDomainBoundaryAnalyzer().use { analyzer -> analyzer.analyze(sourceRoot) }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private inline fun withTemporarySourceTree(block: (File) -> Unit) {
        val root = Files.createTempDirectory("domain-boundary-fixture").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun landmark(
        type: PoseLandmark = PoseLandmark.NOSE,
        x: Double = 0.5,
        y: Double = 0.5,
        z: Double = 0.0,
        visibility: Double = 1.0,
        presence: Double = 1.0,
    ): Landmark = Landmark(type, x, y, z, visibility, presence)

    private fun referencePose(
        id: String = "pose-1",
        label: String = "Reference pose",
        importedAtEpochMillis: Long = 1,
        landmarks: Iterable<Landmark> = listOf(landmark()),
    ): ReferencePose = ReferencePose(
        id = id,
        label = label,
        importedAtEpochMillis = importedAtEpochMillis,
        detectorMetadata = PoseDetectorMetadata(
            detectorName = "pose-detector",
            modelName = "pose-landmarker",
            modelVersion = "1",
        ),
        mirrorMatchingAllowed = true,
        landmarks = landmarks,
    )

    private fun matchResult(
        landmarkCoverage: Double = 1.0,
        framingScore: Double = 1.0,
        angularSimilarity: Double = 1.0,
        positionalSimilarity: Double = 1.0,
        overallMatch: Double = 1.0,
        gateFailures: Iterable<MatchGateFailure> = emptySet(),
        mirrorUsed: Boolean = false,
        eligibleForLock: Boolean = false,
    ): MatchResult = MatchResult(
        landmarkCoverage = landmarkCoverage,
        framingScore = framingScore,
        angularSimilarity = angularSimilarity,
        positionalSimilarity = positionalSimilarity,
        overallMatch = overallMatch,
        gateFailures = gateFailures,
        mirrorUsed = mirrorUsed,
        eligibleForLock = eligibleForLock,
    )

    private fun shoot(
        id: String = "shoot-1",
        name: String = "Shoot",
        referencePoses: Iterable<ReferencePose> = listOf(
            referencePose("pose-1"),
            referencePose("pose-2"),
            referencePose("pose-3"),
        ),
        createdAtEpochMillis: Long = 1,
    ): Shoot = Shoot(id, name, referencePoses, createdAtEpochMillis)

    private fun assertRejects(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }

    private companion object {
        const val DOMAIN_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/domain"

        val REQUIRED_MODEL_FILES = setOf(
            "model/Landmark.kt",
            "model/PoseObservation.kt",
            "model/ReferencePose.kt",
            "model/MatchResult.kt",
            "model/CoachingCue.kt",
            "model/Shoot.kt",
        )
        val FORBIDDEN_IMPORT_PREFIXES = setOf(
            "android",
            "androidx",
            "com.google.mediapipe",
            "java.time.Clock",
            "java.io",
            "java.nio.file",
            "kotlin.io.path",
        )
    }
}
