package com.tonyisup.poseguidesnap.ui

import com.tonyisup.poseguidesnap.camera.CameraControllerStatus
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation

enum class PersonGateState {
    WAITING,
    NO_PERSON,
    ONE_PERSON,
    MULTIPLE_PEOPLE,
}

data class LiveCameraDiagnostics(
    val cameraStatus: CameraControllerStatus,
    val personState: PersonGateState,
    val detectedPersonCount: Int,
    val observedLandmarkCount: Int,
    val cameraLabel: String,
    val personLabel: String,
    val landmarkLabel: String,
    val referenceLabel: String,
    val framingLabel: String,
    val coverageLabel: String,
    val angularLabel: String,
    val positionalLabel: String,
    val overallLabel: String,
    val mirrorLabel: String,
    val captureLockLabel: String,
    val recoverableActionText: String?,
) {
    companion object {
        fun from(
            cameraStatus: CameraControllerStatus,
            poseObservation: PoseObservation?,
            hasRecoverableFailure: Boolean,
        ): LiveCameraDiagnostics {
            val detectedPersonCount = poseObservation?.detectedPersonCount ?: 0
            val personState = when {
                poseObservation == null -> PersonGateState.WAITING
                detectedPersonCount == 0 -> PersonGateState.NO_PERSON
                detectedPersonCount == 1 -> PersonGateState.ONE_PERSON
                else -> PersonGateState.MULTIPLE_PEOPLE
            }
            val observedLandmarkCount = poseObservation?.landmarks
                ?.count { landmark -> landmark.type in COCO_LANDMARKS }
                ?: 0
            val recoverableFailure =
                hasRecoverableFailure || cameraStatus == CameraControllerStatus.FAILED
            val referenceEvidence = BundledReferenceMatchEvidence.evaluate(poseObservation)

            return LiveCameraDiagnostics(
                cameraStatus = cameraStatus,
                personState = personState,
                detectedPersonCount = detectedPersonCount,
                observedLandmarkCount = observedLandmarkCount,
                cameraLabel = if (recoverableFailure) {
                    "Camera status: recovery needed"
                } else {
                    cameraStatus.label
                },
                personLabel = when (personState) {
                    PersonGateState.WAITING -> "Person gate: waiting for a frame"
                    PersonGateState.NO_PERSON -> "Person gate: no person"
                    PersonGateState.ONE_PERSON -> "Person gate: one person"
                    PersonGateState.MULTIPLE_PEOPLE ->
                        "Person gate: multiple people ($detectedPersonCount)"
                },
                landmarkLabel = "Observed landmarks: $observedLandmarkCount of 17",
                referenceLabel = referenceEvidence.referenceLabel,
                framingLabel = referenceEvidence.framing.label,
                coverageLabel = referenceEvidence.coverage.label,
                angularLabel = referenceEvidence.angular.label,
                positionalLabel = referenceEvidence.positional.label,
                overallLabel = referenceEvidence.overall.label,
                mirrorLabel = referenceEvidence.mirrorLabel,
                captureLockLabel = referenceEvidence.captureLockLabel,
                recoverableActionText = if (recoverableFailure) "Retry camera" else null,
            )
        }
    }
}

private val CameraControllerStatus.label: String
    get() = when (this) {
        CameraControllerStatus.IDLE -> "Camera status: idle"
        CameraControllerStatus.BINDING -> "Camera status: starting"
        CameraControllerStatus.READY -> "Camera status: ready"
        CameraControllerStatus.FAILED -> "Camera status: recovery needed"
        CameraControllerStatus.CLOSED -> "Camera status: closed"
    }

private val COCO_LANDMARKS = setOf(
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
