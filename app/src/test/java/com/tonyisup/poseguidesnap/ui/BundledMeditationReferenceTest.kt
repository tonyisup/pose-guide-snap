package com.tonyisup.poseguidesnap.ui

import com.tonyisup.poseguidesnap.camera.PixelSize
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledMeditationReferenceTest {
    @Test
    fun bundledReferenceContainsExactPixelMappedMoveNetObservation() {
        assertEquals("Bundled meditation pose", BundledMeditationReference.label)
        assertEquals(PixelSize(1024.0, 574.0), BundledMeditationReference.pixelSize)
        assertTrue(BundledMeditationReference.mirrorAllowed)

        val observation = BundledMeditationReference.observation
        assertEquals(1, observation.detectedPersonCount)
        assertEquals(0L, observation.monotonicTimestampNanos)
        assertEquals(expectedLandmarks.size, observation.landmarks.size)
        expectedLandmarks.zip(observation.landmarks).forEach { (expected, actual) ->
            assertEquals(expected.type, actual.type)
            assertEquals(expected.x, actual.x, 0.0)
            assertEquals(expected.y, actual.y, 0.0)
            assertEquals(0.0, actual.z, 0.0)
            assertEquals(expected.confidence, actual.visibility, 0.0)
            assertEquals(expected.confidence, actual.presence, 0.0)
        }
    }

    @Test
    fun bundledReferenceIsDeterministicAndLandmarksCannotBeMutated() {
        assertTrue(BundledMeditationReference.observation === BundledMeditationReference.observation)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (BundledMeditationReference.observation.landmarks as MutableList<Landmark>).clear()
        }
        assertEquals(17, BundledMeditationReference.observation.landmarks.size)
        assertTrue(
            BundledReferenceMatchEvidence::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .all { Modifier.isFinal(it.modifiers) },
        )
    }

    @Test
    fun identicalObservationPassesNamedPrototypeGatesWithoutLockAuthority() {
        val evidence = BundledReferenceMatchEvidence.evaluate(BundledMeditationReference.observation)

        assertEquals(ReferenceMatchStatus.EVALUATED, evidence.status)
        assertEquals("Reference loaded: Bundled meditation pose (17 landmarks)", evidence.referenceLabel)
        assertEquals(PrototypeGateState.NOT_EVALUATED, evidence.framing.state)
        assertNull(evidence.framing.score)
        assertEquals("Framing gate: not evaluated", evidence.framing.label)
        assertPassingGate("Coverage gate", evidence.coverage)
        assertPassingGate("Angular gate", evidence.angular)
        assertPassingGate("Positional gate", evidence.positional)
        assertPassingGate("Overall gate", evidence.overall)
        assertEquals(MirrorSelection.NORMAL, evidence.selectedMirror)
        assertEquals("Selected mirror: normal", evidence.mirrorLabel)
        assertEquals(LockCaptureState.DISABLED, evidence.lockCaptureState)
        assertEquals("Capture lock: disabled in Task 10", evidence.captureLockLabel)
        assertFalse(evidence.labels.any { "eligibleForLock" in it || "capture ready" in it.lowercase() })
    }

    @Test
    fun waitingNoPersonAndMultiplePeopleRemainHonestlyUnevaluated() {
        val waiting = BundledReferenceMatchEvidence.evaluate(null)
        assertUnevaluated(
            evidence = waiting,
            status = ReferenceMatchStatus.WAITING_FOR_FRAME,
            reason = "waiting for a frame",
        )

        val noPerson = BundledReferenceMatchEvidence.evaluate(
            PoseObservation(emptyList(), monotonicTimestampNanos = 1L, detectedPersonCount = 0),
        )
        assertUnevaluated(noPerson, ReferenceMatchStatus.NO_PERSON, "no person")

        val multiple = BundledReferenceMatchEvidence.evaluate(
            PoseObservation(
                landmarks = BundledMeditationReference.observation.landmarks,
                monotonicTimestampNanos = 2L,
                detectedPersonCount = 3,
            ),
        )
        assertUnevaluated(multiple, ReferenceMatchStatus.MULTIPLE_PEOPLE, "multiple people (3)")
    }

    @Test
    fun missingTorsoAnchorsReportsCanonicalizationFailureWithoutInventedScores() {
        val live = PoseObservation(
            landmarks = listOf(BundledMeditationReference.observation.landmarks.first()),
            monotonicTimestampNanos = 3L,
            detectedPersonCount = 1,
        )

        val evidence = BundledReferenceMatchEvidence.evaluate(live)

        assertUnevaluated(
            evidence,
            ReferenceMatchStatus.CANONICALIZATION_FAILED,
            "pose canonicalization failed",
        )
    }

    @Test
    fun mainDrawableIsByteIdenticalToLicensedPublicAndroidTestFixture() {
        val root = projectRoot()
        val source = root.resolve("app/src/androidTest/assets/pose-fixtures/meditation_pose.png")
        val bundled = root.resolve("app/src/main/res/drawable-nodpi/meditation_pose.png")
        val productionNotice = root.resolve(
            "app/src/main/assets/reference-fixtures/meditation_pose_ATTRIBUTION.md",
        )
        val testNotice = root.resolve("app/src/androidTest/assets/pose-fixtures/ATTRIBUTION.md")

        assertTrue("Public source fixture is missing", source.isFile)
        assertTrue("Bundled main drawable is missing", bundled.isFile)
        assertEquals(EXPECTED_SHA_256, sha256(source.readBytes()))
        assertEquals(EXPECTED_SHA_256, sha256(bundled.readBytes()))
        assertTrue("Main drawable must be a byte-identical fixture copy", source.readBytes().contentEquals(bundled.readBytes()))
        assertTrue("Packaged production attribution notice is missing", productionNotice.isFile)
        listOf(productionNotice, testNotice).forEach { notice ->
            val text = notice.readText()
            assertTrue("Missing source attribution in ${notice.path}", "Google AI Edge / MediaPipe documentation" in text)
            assertTrue("Missing CC BY 4.0 in ${notice.path}", "Creative Commons Attribution 4.0 International" in text)
            assertTrue("Missing fixture hash in ${notice.path}", EXPECTED_SHA_256 in text)
            assertFalse("Attribution must not claim instrumentation-only use", "packaged only" in text)
        }
    }

    private fun assertPassingGate(name: String, gate: NamedPrototypeGateEvidence) {
        assertEquals(PrototypeGateState.PASS, gate.state)
        assertEquals(1.0, gate.score ?: Double.NaN, 0.0)
        assertEquals("$name: pass (uncalibrated)", gate.label)
    }

    private fun assertUnevaluated(
        evidence: BundledReferenceMatchEvidence,
        status: ReferenceMatchStatus,
        reason: String,
    ) {
        assertEquals(status, evidence.status)
        listOf(evidence.coverage, evidence.angular, evidence.positional, evidence.overall).forEach { gate ->
            assertEquals(PrototypeGateState.NOT_EVALUATED, gate.state)
            assertNull(gate.score)
            assertTrue("Missing unevaluated reason in ${gate.label}", reason in gate.label)
        }
        assertEquals(MirrorSelection.NOT_EVALUATED, evidence.selectedMirror)
        assertEquals(LockCaptureState.DISABLED, evidence.lockCaptureState)
        assertEquals("Capture lock: disabled in Task 10", evidence.captureLockLabel)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private data class ExpectedLandmark(
        val type: PoseLandmark,
        val x: Double,
        val y: Double,
        val confidence: Double,
    )

    private companion object {
        const val EXPECTED_SHA_256 =
            "e4b26bbe800988cd208a77b23a412109bb2b629e65ead9fa86c4c8a61998eedb"

        val expectedLandmarks = listOf(
            ExpectedLandmark(PoseLandmark.NOSE, 0.4022426903247833, 0.12006060282389323, 0.7816296219825745),
            ExpectedLandmark(PoseLandmark.LEFT_EYE, 0.42301028966903687, 0.09473117192586263, 0.6812842488288879),
            ExpectedLandmark(PoseLandmark.RIGHT_EYE, 0.38116219639778137, 0.09195698632134332, 0.8265299201011658),
            ExpectedLandmark(PoseLandmark.LEFT_EAR, 0.4376043975353241, 0.1473757955763075, 0.670898973941803),
            ExpectedLandmark(PoseLandmark.RIGHT_EAR, 0.3531184792518616, 0.15066040886773002, 0.839095950126648),
            ExpectedLandmark(PoseLandmark.LEFT_SHOULDER, 0.4845542907714844, 0.31610017352634007, 0.9289464950561523),
            ExpectedLandmark(PoseLandmark.RIGHT_SHOULDER, 0.29526934027671814, 0.3247823715209961, 0.9103005528450012),
            ExpectedLandmark(PoseLandmark.LEFT_ELBOW, 0.5042339563369751, 0.573079956902398, 0.832593560218811),
            ExpectedLandmark(PoseLandmark.RIGHT_ELBOW, 0.2801958918571472, 0.5892873340182834, 0.8635709881782532),
            ExpectedLandmark(PoseLandmark.LEFT_WRIST, 0.5966731309890747, 0.6167839898003472, 0.7849991321563721),
            ExpectedLandmark(PoseLandmark.RIGHT_WRIST, 0.18010017275810242, 0.6092118157280816, 0.6579967141151428),
            ExpectedLandmark(PoseLandmark.LEFT_HIP, 0.449966162443161, 0.7798119650946723, 0.8889274001121521),
            ExpectedLandmark(PoseLandmark.RIGHT_HIP, 0.3430059254169464, 0.7891096538967557, 0.9308686852455139),
            ExpectedLandmark(PoseLandmark.LEFT_KNEE, 0.6035696864128113, 0.6825155682033963, 0.5964075922966003),
            ExpectedLandmark(PoseLandmark.RIGHT_KNEE, 0.20208804309368134, 0.7013720406426324, 0.6801067590713501),
            ExpectedLandmark(PoseLandmark.LEFT_ANKLE, 0.3847403824329376, 0.9324774212307401, 0.6891353726387024),
            ExpectedLandmark(PoseLandmark.RIGHT_ANKLE, 0.4383878707885742, 0.9633452097574869, 0.8055703043937683),
        )
    }
}
