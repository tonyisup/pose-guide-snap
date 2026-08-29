package com.tonyisup.poseguidesnap.ui

import com.tonyisup.poseguidesnap.camera.CameraControllerStatus
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCameraDiagnosticsTest {
    @Test
    fun personGateStateNamesAreExactAndWaitingIsDistinct() {
        assertEquals(
            listOf("MULTIPLE_PEOPLE", "NO_PERSON", "ONE_PERSON", "WAITING"),
            PersonGateState.entries.map(Enum<*>::name).sorted(),
        )
    }

    @Test
    fun absentObservationIsWaitingRatherThanNoPerson() {
        val diagnostics = diagnostics(poseObservation = null)

        assertEquals(PersonGateState.WAITING, diagnostics.personState)
        assertEquals(0, diagnostics.detectedPersonCount)
        assertEquals(0, diagnostics.observedLandmarkCount)
        assertEquals("Person gate: waiting for a frame", diagnostics.personLabel)
        assertEquals("Observed landmarks: 0 of 17", diagnostics.landmarkLabel)
        assertEquals("Framing gate: not evaluated", diagnostics.framingLabel)
        assertEquals("Coverage gate: not evaluated (waiting for a frame)", diagnostics.coverageLabel)
        assertEquals("Angular gate: not evaluated (waiting for a frame)", diagnostics.angularLabel)
        assertEquals("Positional gate: not evaluated (waiting for a frame)", diagnostics.positionalLabel)
        assertEquals("Overall gate: not evaluated (waiting for a frame)", diagnostics.overallLabel)
        assertEquals("Selected mirror: not evaluated (waiting for a frame)", diagnostics.mirrorLabel)
        assertEquals("Capture lock: disabled in Task 10", diagnostics.captureLockLabel)
    }

    @Test
    fun zeroPersonObservationIsAnObservedNoPersonFrame() {
        val diagnostics = diagnostics(poseObservation = observation(personCount = 0))

        assertEquals(PersonGateState.NO_PERSON, diagnostics.personState)
        assertEquals(0, diagnostics.detectedPersonCount)
        assertEquals("Person gate: no person", diagnostics.personLabel)
    }

    @Test
    fun onePersonWithAllCocoLandmarksReportsSeventeen() {
        val diagnostics = diagnostics(
            poseObservation = observation(personCount = 1, landmarkTypes = cocoLandmarks),
        )

        assertEquals(PersonGateState.ONE_PERSON, diagnostics.personState)
        assertEquals(1, diagnostics.detectedPersonCount)
        assertEquals(17, diagnostics.observedLandmarkCount)
        assertEquals("Person gate: one person", diagnostics.personLabel)
        assertEquals("Observed landmarks: 17 of 17", diagnostics.landmarkLabel)
    }

    @Test
    fun multiplePeoplePreservesDetectorCountAndObservedLandmarks() {
        val diagnostics = diagnostics(
            poseObservation = observation(personCount = 4, landmarkTypes = cocoLandmarks.take(6)),
        )

        assertEquals(PersonGateState.MULTIPLE_PEOPLE, diagnostics.personState)
        assertEquals(4, diagnostics.detectedPersonCount)
        assertEquals(6, diagnostics.observedLandmarkCount)
        assertEquals("Person gate: multiple people (4)", diagnostics.personLabel)
        assertEquals("Observed landmarks: 6 of 17", diagnostics.landmarkLabel)
    }

    @Test
    fun onlyCocoIdentitiesContributeToTheSeventeenLandmarkDiagnostic() {
        val diagnostics = diagnostics(
            poseObservation = observation(
                personCount = 1,
                landmarkTypes = cocoLandmarks + PoseLandmark.LEFT_THUMB,
            ),
        )

        assertEquals(17, diagnostics.observedLandmarkCount)
        assertEquals("Observed landmarks: 17 of 17", diagnostics.landmarkLabel)
    }

    @Test
    fun bundledReferenceObservationSurfacesEveryNamedUncalibratedGate() {
        val diagnostics = diagnostics(
            poseObservation = PoseObservation(
                landmarks = BundledMeditationReference.observation.landmarks,
                monotonicTimestampNanos = 9L,
                detectedPersonCount = 1,
            ),
        )

        assertEquals("Coverage gate: pass (uncalibrated)", diagnostics.coverageLabel)
        assertEquals("Angular gate: pass (uncalibrated)", diagnostics.angularLabel)
        assertEquals("Positional gate: pass (uncalibrated)", diagnostics.positionalLabel)
        assertEquals("Overall gate: pass (uncalibrated)", diagnostics.overallLabel)
        assertEquals("Selected mirror: normal", diagnostics.mirrorLabel)
        assertEquals("Capture lock: disabled in Task 10", diagnostics.captureLockLabel)
    }

    @Test
    fun everyControllerStateHasAnExactHonestLabel() {
        val expected = mapOf(
            CameraControllerStatus.IDLE to "Camera status: idle",
            CameraControllerStatus.BINDING to "Camera status: starting",
            CameraControllerStatus.READY to "Camera status: ready",
            CameraControllerStatus.FAILED to "Camera status: recovery needed",
            CameraControllerStatus.CLOSED to "Camera status: closed",
        )

        expected.forEach { (status, label) ->
            assertEquals(label, diagnostics(cameraStatus = status).cameraLabel)
        }
    }

    @Test
    fun controllerFailureOffersOnlyGenericRecovery() {
        val diagnostics = diagnostics(cameraStatus = CameraControllerStatus.FAILED)

        assertEquals("Camera status: recovery needed", diagnostics.cameraLabel)
        assertEquals("Retry camera", diagnostics.recoverableActionText)
    }

    @Test
    fun callbackFailureOverridesReadyLabelUntilRecovery() {
        val failed = diagnostics(
            cameraStatus = CameraControllerStatus.READY,
            hasRecoverableFailure = true,
        )
        val recovered = diagnostics(
            cameraStatus = CameraControllerStatus.READY,
            hasRecoverableFailure = false,
        )

        assertEquals("Camera status: recovery needed", failed.cameraLabel)
        assertEquals("Retry camera", failed.recoverableActionText)
        assertEquals("Camera status: ready", recovered.cameraLabel)
        assertNull(recovered.recoverableActionText)
    }

    @Test
    fun bundledReferenceIsNamedWhileMatchEvidenceWaitsForAFrame() {
        val diagnostics = diagnostics()

        assertEquals(
            "Reference loaded: Bundled meditation pose (17 landmarks)",
            diagnostics.referenceLabel,
        )
    }

    @Test
    fun derivationIsDeterministicAndResultFieldsAreImmutable() {
        val observation = observation(personCount = 1, landmarkTypes = cocoLandmarks)
        val first = diagnostics(poseObservation = observation)
        val second = diagnostics(poseObservation = observation)

        assertEquals(first, second)
        assertTrue(
            LiveCameraDiagnostics::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .all { Modifier.isFinal(it.modifiers) },
        )
    }

    @Test
    fun diagnosticsSurfaceCannotRetainPrivateFailurePayloads() {
        val diagnostics = diagnostics(
            cameraStatus = CameraControllerStatus.FAILED,
            poseObservation = observation(personCount = 1, landmarkTypes = cocoLandmarks.take(2)),
            hasRecoverableFailure = true,
        )
        val labels = listOf(
            diagnostics.cameraLabel,
            diagnostics.personLabel,
            diagnostics.landmarkLabel,
            diagnostics.referenceLabel,
            diagnostics.recoverableActionText.orEmpty(),
        ).joinToString("|")

        assertFalse(
            LiveCameraDiagnostics::class.java.declaredFields.any {
                Throwable::class.java.isAssignableFrom(it.type)
            },
        )
        listOf("/data/", "tensor", "Throwable", "Exception", "monotonicTimestampNanos").forEach { privateMarker ->
            assertFalse("Private marker leaked into labels: $privateMarker", privateMarker in labels)
        }
    }

    private fun diagnostics(
        cameraStatus: CameraControllerStatus = CameraControllerStatus.IDLE,
        poseObservation: PoseObservation? = null,
        hasRecoverableFailure: Boolean = false,
    ): LiveCameraDiagnostics = LiveCameraDiagnostics.from(
        cameraStatus = cameraStatus,
        poseObservation = poseObservation,
        hasRecoverableFailure = hasRecoverableFailure,
    )

    private fun observation(
        personCount: Int,
        landmarkTypes: List<PoseLandmark> = emptyList(),
    ): PoseObservation = PoseObservation(
        landmarks = landmarkTypes.mapIndexed { index, type ->
            Landmark(
                type = type,
                x = (index + 1).toDouble() / (landmarkTypes.size + 1).toDouble(),
                y = 0.5,
                z = 0.0,
                visibility = 0.8,
                presence = 0.8,
            )
        },
        monotonicTimestampNanos = 42L,
        detectedPersonCount = personCount,
    )

    private companion object {
        val cocoLandmarks = listOf(
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
