package com.tonyisup.poseguidesnap.pose.movenet

import com.tonyisup.poseguidesnap.architecture.KotlinDomainBoundaryAnalyzer
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveNetResultMapperTest {
    @Test
    fun rawOutputRequiresExactlySixSlotsOfExactlyFiftySixValues() {
        assertRejects { MoveNetRawOutput(List(5) { FloatArray(56) }) }
        assertRejects { MoveNetRawOutput(List(7) { FloatArray(56) }) }
        assertRejects { MoveNetRawOutput(List(6) { index -> FloatArray(if (index == 2) 55 else 56) }) }
        assertRejects { MoveNetRawOutput(List(6) { index -> FloatArray(if (index == 4) 57 else 56) }) }
        MoveNetRawOutput(List(6) { FloatArray(56) })
    }

    @Test
    fun rawOutputSnapshotsNestedCallerDataAndExposesNoArrayOrCopySurface() {
        val first = validSlot(instanceScore = 0.8f, x = 0.2f)
        val callerSlots = MutableList(6) { index -> if (index == 0) first else FloatArray(56) }
        val raw = MoveNetRawOutput(callerSlots)

        first.fill(Float.NaN)
        callerSlots[0] = FloatArray(55)
        callerSlots.clear()

        val observation = mapper().map(raw, squareGeometry(), 7)
        assertEquals(1, observation.detectedPersonCount)
        assertEquals(17, observation.landmarks.size)
        assertEquals(0.2f.toDouble(), observation.landmarks.first().x, 0.0)
        assertFalse(
            MoveNetRawOutput::class.java.methods.any { method ->
                method.name == "copy" ||
                    method.returnType.isArray ||
                    Collection::class.java.isAssignableFrom(method.returnType)
            },
        )
    }

    @Test
    fun mappingPolicyValidatesFiniteNormalizedThresholdAndDefaultsToUncalibratedQuarter() {
        assertEquals(0.25, MoveNetMappingPolicy().minimumPersonScore, 0.0)
        assertEquals(0.0, MoveNetMappingPolicy(0.0).minimumPersonScore, 0.0)
        assertEquals(1.0, MoveNetMappingPolicy(1.0).minimumPersonScore, 0.0)
        listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { invalid -> assertRejects { MoveNetMappingPolicy(invalid) } }
    }

    @Test
    fun detectorPublicSurfaceCannotOverridePinnedModelShapeOrThreadContract() {
        val overrideCapableConstructors = MoveNetPoseDetector::class.java.constructors.filter { constructor ->
            constructor.parameterTypes.any { type ->
                type.name == "android.content.Context" ||
                    type.name == "java.lang.String" ||
                    type == Int::class.javaPrimitiveType ||
                    type.name.endsWith("MoveNetDetectorSettings")
            }
        }
        assertEquals(
            "MoveNetPoseDetector must expose no public model/shape/thread override constructor",
            emptyList<java.lang.reflect.Constructor<*>>(),
            overrideCapableConstructors,
        )
        val companionClass = MoveNetPoseDetector::class.java.declaredClasses
            .single { it.simpleName == "Companion" }
        val createMethods = companionClass.methods
            .filter { it.name == "create" && !it.isSynthetic }
        assertEquals(1, createMethods.size)
        assertEquals(listOf("android.content.Context"), createMethods.single().parameterTypes.map { it.name })
    }

    @Test
    fun letterboxValidatesPositiveSourceAndPositiveMultipleOfThirtyTwoTarget() {
        assertRejects { MoveNetLetterboxGeometry(0, 1) }
        assertRejects { MoveNetLetterboxGeometry(1, 0) }
        assertRejects { MoveNetLetterboxGeometry(-1, 1) }
        assertRejects { MoveNetLetterboxGeometry(1, -1) }
        listOf(0, -32, 1, 31, 33, 255).forEach { invalidTarget ->
            assertRejects { MoveNetLetterboxGeometry(1, 1, invalidTarget) }
        }
        assertEquals(32, MoveNetLetterboxGeometry(1, 1, 32).targetSize)
    }

    @Test
    fun landscapeLetterboxUsesHalfUpResizeCenteredPaddingAndExactUnpaddingFormula() {
        val geometry = MoveNetLetterboxGeometry(sourceWidth = 1024, sourceHeight = 574)

        assertEquals(0.25, geometry.scale, 0.0)
        assertEquals(256, geometry.resizedWidth)
        assertEquals(144, geometry.resizedHeight)
        assertEquals(0, geometry.padLeft)
        assertEquals(0, geometry.padRight)
        assertEquals(56, geometry.padTop)
        assertEquals(56, geometry.padBottom)
        assertEquals(0.25, geometry.unpadX(0.25), 0.0)
        assertEquals(0.0, geometry.unpadY(56.0 / 256.0), 0.0)
        assertEquals(0.25, geometry.unpadY(92.0 / 256.0), 0.0)
        assertEquals(1.0, geometry.unpadY(200.0 / 256.0), 0.0)
    }

    @Test
    fun portraitAsymmetricAndHalfRoundingGeometryControlsAreDeterministic() {
        val portrait = MoveNetLetterboxGeometry(574, 1024)
        assertEquals(144, portrait.resizedWidth)
        assertEquals(256, portrait.resizedHeight)
        assertEquals(56, portrait.padLeft)
        assertEquals(56, portrait.padRight)
        assertEquals(0, portrait.padTop)
        assertEquals(0, portrait.padBottom)

        val asymmetric = MoveNetLetterboxGeometry(1000, 333)
        assertEquals(256, asymmetric.resizedWidth)
        assertEquals(85, asymmetric.resizedHeight)
        assertEquals(85, asymmetric.padTop)
        assertEquals(86, asymmetric.padBottom)

        val halfRound = MoveNetLetterboxGeometry(640, 321, targetSize = 320)
        assertEquals(320, halfRound.resizedWidth)
        assertEquals(161, halfRound.resizedHeight)
        assertEquals(79, halfRound.padTop)
        assertEquals(80, halfRound.padBottom)
    }

    @Test
    fun noAcceptedPeopleMapsToEmptyObservationWithExplicitTimestamp() {
        val observation = mapper().map(raw(), squareGeometry(), 123)

        assertEquals(123, observation.monotonicTimestampNanos)
        assertEquals(0, observation.detectedPersonCount)
        assertTrue(observation.landmarks.isEmpty())
    }

    @Test
    fun oneAcceptedPersonMapsExactCocoIdentityOrderWithZeroDepthAndConfidenceAliases() {
        val slot = FloatArray(56)
        EXPECTED_IDENTITIES.forEachIndexed { index, _ ->
            slot[index * 3] = (index + 1) / 20.0f
            slot[index * 3 + 1] = (index + 2) / 20.0f
            slot[index * 3 + 2] = (index + 3) / 20.0f
        }
        slot[55] = 0.9f

        val observation = mapper().map(raw(slot), squareGeometry(), 9)

        assertEquals(1, observation.detectedPersonCount)
        assertEquals(EXPECTED_IDENTITIES, observation.landmarks.map { it.type })
        observation.landmarks.forEachIndexed { index, landmark ->
            val score = slot[index * 3 + 2].toDouble()
            assertEquals(slot[index * 3 + 1].toDouble(), landmark.x, 0.0)
            assertEquals(slot[index * 3].toDouble(), landmark.y, 0.0)
            assertEquals(0.0, landmark.z, 0.0)
            assertEquals(score, landmark.visibility, 0.0)
            assertEquals(score, landmark.presence, 0.0)
        }
    }

    @Test
    fun finiteLowKeypointScoresRemainEvidenceWithoutAnAdapterKeypointThreshold() {
        val slot = validSlot(instanceScore = 0.8f)
        slot[2] = Float.MIN_VALUE

        val nose = mapper().map(raw(slot), squareGeometry(), 0).landmarks.first()

        assertEquals(Float.MIN_VALUE.toDouble(), nose.visibility, 0.0)
        assertEquals(Float.MIN_VALUE.toDouble(), nose.presence, 0.0)
    }

    @Test
    fun personThresholdIsAppliedBeforeCountingAndEqualityPasses() {
        val slots = List(6) { FloatArray(56) }.toMutableList()
        slots[0] = validSlot(instanceScore = 0.49f, x = 0.1f)
        slots[1] = validSlot(instanceScore = 0.5f, x = 0.2f)
        slots[2] = validSlot(instanceScore = 1.0f, x = 0.3f)

        val observation = mapper(minimumPersonScore = 0.5).map(MoveNetRawOutput(slots), squareGeometry(), 1)

        assertEquals(2, observation.detectedPersonCount)
        assertEquals(0.3f.toDouble(), observation.landmarks.first().x, 0.0)
    }

    @Test
    fun strongestAcceptedPersonIsSelectedWhileActualAcceptedCountIsPreserved() {
        val slots = List(6) { FloatArray(56) }.toMutableList()
        slots[0] = validSlot(instanceScore = 0.7f, x = 0.2f)
        slots[4] = validSlot(instanceScore = 0.9f, x = 0.8f)

        val observation = mapper().map(MoveNetRawOutput(slots), squareGeometry(), 2)

        assertEquals(2, observation.detectedPersonCount)
        assertEquals(0.8f.toDouble(), observation.landmarks.first().x, 0.0)
    }

    @Test
    fun exactPersonScoreTieSelectsLowerSlotIndex() {
        val slots = List(6) { FloatArray(56) }.toMutableList()
        slots[1] = validSlot(instanceScore = 0.75f, x = 0.2f)
        slots[3] = validSlot(instanceScore = 0.75f, x = 0.8f)

        val observation = mapper().map(MoveNetRawOutput(slots), squareGeometry(), 3)

        assertEquals(2, observation.detectedPersonCount)
        assertEquals(0.2f.toDouble(), observation.landmarks.first().x, 0.0)
    }

    @Test
    fun nonfiniteNegativeAndAboveOneInstanceScoresAreRejected() {
        val invalidScores = listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            -0.01f,
            1.01f,
        )
        invalidScores.forEach { score ->
            val slots = MutableList(6) { FloatArray(56).also { it[55] = -1.0f } }
            slots[0] = validSlot(instanceScore = score)
            val observation = mapper(minimumPersonScore = 0.0)
                .map(MoveNetRawOutput(slots), squareGeometry(), 4)
            assertEquals("score=$score", 0, observation.detectedPersonCount)
            assertTrue("score=$score", observation.landmarks.isEmpty())
        }
    }

    @Test
    fun invalidKeypointFieldsAndCoordinatesOutsideUnpaddedImageAreOmittedWithoutClamping() {
        val slot = validSlot(instanceScore = 0.9f)
        slot[0] = Float.NaN
        slot[4] = Float.POSITIVE_INFINITY
        slot[8] = -0.01f
        slot[11] = 1.01f
        slot[13] = 0.0f // Portrait padding maps this x below zero.
        slot[16] = 1.0f // Portrait padding maps this x above one.
        val geometry = MoveNetLetterboxGeometry(574, 1024)

        val observation = mapper().map(raw(slot), geometry, 5)

        assertEquals(1, observation.detectedPersonCount)
        assertEquals(
            EXPECTED_IDENTITIES.filterIndexed { index, _ -> index !in setOf(0, 1, 2, 3, 4, 5) },
            observation.landmarks.map { it.type },
        )
        assertTrue(observation.landmarks.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
    }

    @Test
    fun acceptedPersonWithNoRepresentableKeypointsFailsLoudly() {
        val slot = validSlot(instanceScore = 0.9f)
        repeat(17) { index -> slot[index * 3 + 2] = Float.NaN }

        assertThrows(IllegalStateException::class.java) {
            mapper().map(raw(slot), squareGeometry(), 6)
        }
    }

    @Test
    fun mappedSubsetExplicitlyOmitsSixteenOfThirtyThreeDomainIdentities() {
        val omitted = PoseLandmark.entries.filterNot(EXPECTED_IDENTITIES::contains)

        assertEquals(16, omitted.size)
        assertEquals(
            listOf(
                PoseLandmark.LEFT_EYE_INNER,
                PoseLandmark.LEFT_EYE_OUTER,
                PoseLandmark.RIGHT_EYE_INNER,
                PoseLandmark.RIGHT_EYE_OUTER,
                PoseLandmark.MOUTH_LEFT,
                PoseLandmark.MOUTH_RIGHT,
                PoseLandmark.LEFT_PINKY,
                PoseLandmark.RIGHT_PINKY,
                PoseLandmark.LEFT_INDEX,
                PoseLandmark.RIGHT_INDEX,
                PoseLandmark.LEFT_THUMB,
                PoseLandmark.RIGHT_THUMB,
                PoseLandmark.LEFT_HEEL,
                PoseLandmark.RIGHT_HEEL,
                PoseLandmark.LEFT_FOOT_INDEX,
                PoseLandmark.RIGHT_FOOT_INDEX,
            ),
            omitted,
        )
    }

    @Test
    fun mappedSubsetSupportsAllEightExistingJointAngleTriples() {
        val angleTriples = listOf(
            Triple(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST),
            Triple(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST),
            Triple(PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP),
            Triple(PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP),
            Triple(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE),
            Triple(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE),
            Triple(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE),
            Triple(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE),
        )

        assertEquals(8, angleTriples.size)
        assertTrue(angleTriples.flatMap { it.toList() }.all(EXPECTED_IDENTITIES::contains))
    }

    @Test
    fun mappingIsDeterministicAcrossRepeatedCalls() {
        val raw = raw(validSlot(instanceScore = 0.9f, x = 0.37f, y = 0.61f, keypointScore = 0.42f))
        val mapper = mapper()
        val geometry = MoveNetLetterboxGeometry(1024, 574)

        val first = mapper.map(raw, geometry, 88)
        repeat(10) { assertEquals(first, mapper.map(raw, geometry, 88)) }
    }

    @Test
    fun mapperRequiresExplicitNonnegativeMonotonicTimestamp() {
        assertRejects { mapper().map(raw(), squareGeometry(), -1) }
        assertEquals(0, mapper().map(raw(), squareGeometry(), 0).monotonicTimestampNanos)
    }

    @Test
    fun mapperSourcePassesIsolatedPsiDependencyAndClockContract() {
        val sourceFile = projectRoot().resolve(MAPPER_SOURCE_PATH)
        val isolatedSourceRoot = Files.createTempDirectory("movenet-mapper-boundary").toFile()
        try {
            sourceFile.copyTo(isolatedSourceRoot.resolve(sourceFile.name))
            val violations = KotlinDomainBoundaryAnalyzer().use { it.analyze(isolatedSourceRoot) }
            assertEquals(emptyList<String>(), violations)
        } finally {
            isolatedSourceRoot.deleteRecursively()
        }

        val disposable = Disposer.newDisposable("movenet-mapper-source-contract")
        try {
            val environment = KotlinCoreEnvironment.createForProduction(
                disposable,
                CompilerConfiguration(),
                EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
            val ktFile = KtPsiFactory(environment.project, markGenerated = false)
                .createFile(sourceFile.name, sourceFile.readText())
            val imports = ktFile.importDirectives.mapNotNull { it.importPath?.pathStr }
            assertTrue(
                "Unexpected mapper imports: $imports",
                imports.all { path ->
                    path.startsWith("com.tonyisup.poseguidesnap.domain.") ||
                        path.startsWith("kotlin.math.")
                },
            )
            assertFalse(imports.any { it.startsWith("com.google.ai.edge.litert") })
            assertFalse(imports.any { it.startsWith("org.tensorflow.lite") })
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun mapper(minimumPersonScore: Double = 0.25): MoveNetResultMapper =
        MoveNetResultMapper(MoveNetMappingPolicy(minimumPersonScore))

    private fun squareGeometry(): MoveNetLetterboxGeometry = MoveNetLetterboxGeometry(256, 256)

    private fun raw(firstSlot: FloatArray? = null): MoveNetRawOutput =
        MoveNetRawOutput(List(6) { index -> if (index == 0 && firstSlot != null) firstSlot else FloatArray(56) })

    private fun validSlot(
        instanceScore: Float,
        x: Float = 0.5f,
        y: Float = 0.5f,
        keypointScore: Float = 0.5f,
    ): FloatArray = FloatArray(56).also { slot ->
        repeat(17) { index ->
            slot[index * 3] = y
            slot[index * 3 + 1] = x
            slot[index * 3 + 2] = keypointScore
        }
        slot[51] = 0.1f
        slot[52] = 0.1f
        slot[53] = 0.9f
        slot[54] = 0.9f
        slot[55] = instanceScore
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private fun assertRejects(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }

    private companion object {
        const val MAPPER_SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/pose/movenet/MoveNetResultMapper.kt"

        val EXPECTED_IDENTITIES = listOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_EYE,
            PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_EAR,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE,
        )
    }
}
