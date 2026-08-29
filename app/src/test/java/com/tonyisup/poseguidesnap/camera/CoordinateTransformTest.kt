package com.tonyisup.poseguidesnap.camera

import com.tonyisup.poseguidesnap.architecture.KotlinDomainBoundaryAnalyzer
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateTransformTest {
    @Test
    fun asymmetricPointUsesExactClockwiseRotationFormulasAndInverseRoundTrips() {
        val source = PixelPoint(250.0, 450.0) // Crop-local (u, v) = (0.25, 0.625).
        val expected = mapOf(
            0 to NormalizedPoint(0.25, 0.625),
            90 to NormalizedPoint(0.375, 0.25),
            180 to NormalizedPoint(0.75, 0.375),
            270 to NormalizedPoint(0.625, 0.75),
        )

        expected.forEach { (rotation, upright) ->
            val transform = transform(rotationDegrees = rotation)
            assertEquals("rotation=$rotation", upright, transform.sourceToUpright(source))
            assertPointEquals(source, transform.uprightToSource(upright))
        }
    }

    @Test
    fun cropEdgesAndCornersAreInclusiveAndUprightSizeSwapsOnlyForQuarterTurns() {
        val expectedSizes = mapOf(
            0 to PixelSize(600.0, 400.0),
            90 to PixelSize(400.0, 600.0),
            180 to PixelSize(600.0, 400.0),
            270 to PixelSize(400.0, 600.0),
        )
        expectedSizes.forEach { (rotation, expectedSize) ->
            assertEquals(expectedSize, transform(rotationDegrees = rotation).uprightContentPixelSize)
        }

        val transform = transform(rotationDegrees = 90)
        val sourceCorners = listOf(
            PixelPoint(100.0, 200.0),
            PixelPoint(700.0, 200.0),
            PixelPoint(100.0, 600.0),
            PixelPoint(700.0, 600.0),
        )
        val uprightCorners = listOf(
            NormalizedPoint(1.0, 0.0),
            NormalizedPoint(1.0, 1.0),
            NormalizedPoint(0.0, 0.0),
            NormalizedPoint(0.0, 1.0),
        )

        sourceCorners.zip(uprightCorners).forEach { (source, upright) ->
            assertEquals(upright, transform.sourceToUpright(source))
            assertPointEquals(source, transform.uprightToSource(upright))
        }
    }

    @Test
    fun displayMirrorChangesXAfterRotationWhileRearDefaultRemainsUnmirrored() {
        val source = PixelPoint(250.0, 450.0)
        val rear = transform(rotationDegrees = 90)
        val mirrored = transform(rotationDegrees = 90, mirroredHorizontally = true)

        assertFalse(rear.mirroredHorizontally)
        assertEquals(NormalizedPoint(0.375, 0.25), rear.sourceToUpright(source))
        assertEquals(NormalizedPoint(0.625, 0.25), mirrored.sourceToUpright(source))
        assertPointEquals(source, mirrored.uprightToSource(NormalizedPoint(0.625, 0.25)))
    }

    @Test
    fun fullFrameNormalizationUsesInclusiveEdgesAndNeverClampsCropOrFrameCoordinates() {
        val transform = transform()

        assertEquals(
            NormalizedPoint(0.7, 0.75),
            transform.sourceToFullFrameNormalized(PixelPoint(700.0, 600.0)),
        )
        assertPointEquals(
            PixelPoint(700.0, 600.0),
            transform.fullFrameNormalizedToSource(NormalizedPoint(0.7, 0.75)),
        )
        assertEquals(NormalizedPoint(0.0, 0.0), transform.sourceToFullFrameNormalized(PixelPoint(0.0, 0.0)))
        assertEquals(NormalizedPoint(1.0, 1.0), transform.sourceToFullFrameNormalized(PixelPoint(1000.0, 800.0)))

        assertRejects { transform.sourceToUpright(PixelPoint(99.999, 400.0)) }
        assertRejects { transform.sourceToUpright(PixelPoint(700.001, 400.0)) }
        assertRejects { transform.sourceToFullFrameNormalized(PixelPoint(-0.001, 0.0)) }
        assertRejects { transform.sourceToFullFrameNormalized(PixelPoint(1000.001, 800.0)) }
    }

    @Test
    fun landscapeContentIntoPortraitPreviewUsesExactFillCenterScaleOffsetAndInverse() {
        val transform = PreviewFillCenterTransform(
            contentPixelSize = PixelSize(400.0, 200.0),
            viewportPixelSize = PixelSize(100.0, 300.0),
        )

        assertEquals(1.5, transform.scale, 0.0)
        assertEquals(PixelSize(600.0, 300.0), transform.renderedPixelSize)
        assertEquals(PixelPoint(-250.0, 0.0), transform.offset)
        val preview = transform.contentToPreview(NormalizedPoint(0.25, 0.5))
        assertEquals(PixelPoint(-100.0, 150.0), preview)
        assertEquals(NormalizedPoint(0.25, 0.5), transform.previewToContent(preview))
    }

    @Test
    fun portraitContentIntoLandscapePreviewUsesExactFillCenterScaleOffsetAndInverse() {
        val transform = PreviewFillCenterTransform(
            contentPixelSize = PixelSize(200.0, 400.0),
            viewportPixelSize = PixelSize(300.0, 100.0),
        )

        assertEquals(1.5, transform.scale, 0.0)
        assertEquals(PixelSize(300.0, 600.0), transform.renderedPixelSize)
        assertEquals(PixelPoint(0.0, -250.0), transform.offset)
        val preview = transform.contentToPreview(NormalizedPoint(0.5, 0.25))
        assertEquals(PixelPoint(150.0, -100.0), preview)
        assertEquals(NormalizedPoint(0.5, 0.25), transform.previewToContent(preview))
    }

    @Test
    fun previewCoordinatesOutsideViewportRemainRepresentableButOutsideRenderedContentReject() {
        val transform = PreviewFillCenterTransform(
            contentPixelSize = PixelSize(400.0, 200.0),
            viewportPixelSize = PixelSize(100.0, 300.0),
        )

        assertEquals(PixelPoint(-250.0, 150.0), transform.contentToPreview(NormalizedPoint(0.0, 0.5)))
        assertEquals(NormalizedPoint(0.0, 0.5), transform.previewToContent(PixelPoint(-250.0, 150.0)))
        assertRejects { transform.previewToContent(PixelPoint(-250.001, 150.0)) }
        assertRejects { transform.previewToContent(PixelPoint(350.001, 150.0)) }
    }

    @Test
    fun analysisAndCaptureAlignAcrossDifferentResolutionsEquivalentCropsAndRotations() {
        val analysis = FrameCoordinateTransform(
            fullSize = PixelSize(1000.0, 800.0),
            cropRect = PixelRect(100.0, 80.0, 900.0, 720.0),
            rotationDegrees = 90,
        )
        val capture = FrameCoordinateTransform(
            fullSize = PixelSize(2000.0, 1200.0),
            cropRect = PixelRect(200.0, 120.0, 1800.0, 1080.0),
            rotationDegrees = 270,
        )
        val analysisUpright = NormalizedPoint(0.375, 0.25)

        assertEquals(
            NormalizedPoint(0.625, 0.75),
            analysis.uprightToUpright(analysisUpright, capture),
        )
    }

    @Test
    fun mappingToAnotherUseCaseFailsWhenNormalizedFullFramePointMissesTargetCrop() {
        val analysis = FrameCoordinateTransform(
            fullSize = PixelSize(1000.0, 800.0),
            cropRect = PixelRect(0.0, 0.0, 1000.0, 800.0),
            rotationDegrees = 0,
        )
        val capture = FrameCoordinateTransform(
            fullSize = PixelSize(2000.0, 1200.0),
            cropRect = PixelRect(200.0, 120.0, 1800.0, 1080.0),
            rotationDegrees = 0,
        )

        assertRejects { analysis.uprightToUpright(NormalizedPoint(0.95, 0.5), capture) }
    }

    @Test
    fun valueObjectsRejectInvalidAndNonfiniteValuesAndRemainImmutable() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertRejects { PixelSize(invalid, 1.0) }
            assertRejects { PixelSize(1.0, invalid) }
        }
        assertRejects { PixelRect(1.0, 0.0, 1.0, 1.0) }
        assertRejects { PixelRect(0.0, 1.0, 1.0, 1.0) }
        assertRejects { PixelRect(Double.NaN, 0.0, 1.0, 1.0) }
        assertRejects { PixelPoint(Double.NaN, 0.0) }
        assertRejects { PixelPoint(0.0, Double.NEGATIVE_INFINITY) }
        listOf(-0.001, 1.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertRejects { NormalizedPoint(invalid, 0.5) }
            assertRejects { NormalizedPoint(0.5, invalid) }
        }

        val valueClasses = listOf(PixelSize::class.java, PixelRect::class.java, PixelPoint::class.java, NormalizedPoint::class.java)
        valueClasses.forEach { type ->
            assertTrue("${type.simpleName} must be final", Modifier.isFinal(type.modifiers))
            assertTrue(
                "${type.simpleName} must have only final instance fields",
                type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.all { Modifier.isFinal(it.modifiers) },
            )
        }
        assertRejects { PixelSize(1.0, 1.0).copy(width = 0.0) }
        assertRejects { NormalizedPoint(0.5, 0.5).copy(x = 2.0) }
    }

    @Test
    fun frameRejectsUnsupportedRotationAndCropOutsideFullFrame() {
        listOf(-90, 1, 89, 360).forEach { invalid ->
            assertRejects { transform(rotationDegrees = invalid) }
        }
        assertRejects {
            FrameCoordinateTransform(
                fullSize = PixelSize(100.0, 100.0),
                cropRect = PixelRect(-1.0, 0.0, 50.0, 50.0),
                rotationDegrees = 0,
            )
        }
        assertRejects {
            FrameCoordinateTransform(
                fullSize = PixelSize(100.0, 100.0),
                cropRect = PixelRect(0.0, 0.0, 100.001, 100.0),
                rotationDegrees = 0,
            )
        }
        FrameCoordinateTransform(
            fullSize = PixelSize(100.0, 100.0),
            cropRect = PixelRect(0.0, 0.0, 100.0, 100.0),
            rotationDegrees = 270,
        )
    }

    @Test
    fun repeatedFrameAndPreviewCallsAreDeterministic() {
        val frame = transform(rotationDegrees = 270, mirroredHorizontally = true)
        val source = PixelPoint(220.0, 480.0)
        val expectedUpright = frame.sourceToUpright(source)
        val preview = PreviewFillCenterTransform(frame.uprightContentPixelSize, PixelSize(375.0, 812.0))
        val expectedPreview = preview.contentToPreview(expectedUpright)

        repeat(20) {
            assertEquals(expectedUpright, frame.sourceToUpright(source))
            assertPointEquals(source, frame.uprightToSource(expectedUpright))
            assertEquals(expectedPreview, preview.contentToPreview(expectedUpright))
            assertEquals(expectedUpright, preview.previewToContent(expectedPreview))
        }
    }

    @Test
    fun coordinateTransformSourcePassesIsolatedPureDependencyContract() {
        val sourceFile = projectRoot().resolve(SOURCE_PATH)
        val isolatedSourceRoot = Files.createTempDirectory("coordinate-transform-boundary").toFile()
        try {
            sourceFile.copyTo(isolatedSourceRoot.resolve(sourceFile.name))
            val violations = KotlinDomainBoundaryAnalyzer().use { it.analyze(isolatedSourceRoot) }
            assertEquals(emptyList<String>(), violations)

            val imports = sourceFile.readLines()
                .map(String::trim)
                .filter { it.startsWith("import ") }
            assertEquals("Pure coordinate authority needs no external imports", emptyList<String>(), imports)
            val source = sourceFile.readText()
            listOf("android.", "androidx.", "com.google", "java.io", "java.nio", "System.").forEach { forbidden ->
                assertFalse("Unexpected source dependency $forbidden", source.contains(forbidden))
            }
        } finally {
            isolatedSourceRoot.deleteRecursively()
        }
    }

    private fun transform(
        rotationDegrees: Int = 0,
        mirroredHorizontally: Boolean = false,
    ): FrameCoordinateTransform = FrameCoordinateTransform(
        fullSize = PixelSize(1000.0, 800.0),
        cropRect = PixelRect(100.0, 200.0, 700.0, 600.0),
        rotationDegrees = rotationDegrees,
        mirroredHorizontally = mirroredHorizontally,
    )

    private fun assertPointEquals(expected: PixelPoint, actual: PixelPoint) {
        assertEquals(expected.x, actual.x, 1e-12)
        assertEquals(expected.y, actual.y, 1e-12)
    }

    private fun assertRejects(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private companion object {
        const val SOURCE_PATH =
            "app/src/main/java/com/tonyisup/poseguidesnap/camera/CoordinateTransform.kt"
    }
}
