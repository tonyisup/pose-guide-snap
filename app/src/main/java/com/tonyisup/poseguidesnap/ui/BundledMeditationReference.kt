package com.tonyisup.poseguidesnap.ui

import com.tonyisup.poseguidesnap.camera.PixelSize
import com.tonyisup.poseguidesnap.domain.match.DefaultPoseMatcher
import com.tonyisup.poseguidesnap.domain.match.MatchPolicy
import com.tonyisup.poseguidesnap.domain.match.PoseCanonicalizationResult
import com.tonyisup.poseguidesnap.domain.match.PoseCanonicalizer
import com.tonyisup.poseguidesnap.domain.match.PoseFeatures
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.MatchGateFailure
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import java.util.ArrayList
import java.util.Collections


/** Fixed public Task 10 ghost guide. It performs no runtime detection or I/O. */
object BundledMeditationReference {
    const val label: String = "Bundled meditation pose"
    val pixelSize: PixelSize = PixelSize(width = 1024.0, height = 574.0)
    const val mirrorAllowed: Boolean = true
    const val detectorMetadata: String = "MoveNet MultiPose Lightning float16 v1; Pixel-mapped static observation"

    val observation: PoseObservation = PoseObservation(
        landmarks = listOf(
            landmark(PoseLandmark.NOSE, 0.4022426903247833, 0.12006060282389323, 0.7816296219825745),
            landmark(PoseLandmark.LEFT_EYE, 0.42301028966903687, 0.09473117192586263, 0.6812842488288879),
            landmark(PoseLandmark.RIGHT_EYE, 0.38116219639778137, 0.09195698632134332, 0.8265299201011658),
            landmark(PoseLandmark.LEFT_EAR, 0.4376043975353241, 0.1473757955763075, 0.670898973941803),
            landmark(PoseLandmark.RIGHT_EAR, 0.3531184792518616, 0.15066040886773002, 0.839095950126648),
            landmark(PoseLandmark.LEFT_SHOULDER, 0.4845542907714844, 0.31610017352634007, 0.9289464950561523),
            landmark(PoseLandmark.RIGHT_SHOULDER, 0.29526934027671814, 0.3247823715209961, 0.9103005528450012),
            landmark(PoseLandmark.LEFT_ELBOW, 0.5042339563369751, 0.573079956902398, 0.832593560218811),
            landmark(PoseLandmark.RIGHT_ELBOW, 0.2801958918571472, 0.5892873340182834, 0.8635709881782532),
            landmark(PoseLandmark.LEFT_WRIST, 0.5966731309890747, 0.6167839898003472, 0.7849991321563721),
            landmark(PoseLandmark.RIGHT_WRIST, 0.18010017275810242, 0.6092118157280816, 0.6579967141151428),
            landmark(PoseLandmark.LEFT_HIP, 0.449966162443161, 0.7798119650946723, 0.8889274001121521),
            landmark(PoseLandmark.RIGHT_HIP, 0.3430059254169464, 0.7891096538967557, 0.9308686852455139),
            landmark(PoseLandmark.LEFT_KNEE, 0.6035696864128113, 0.6825155682033963, 0.5964075922966003),
            landmark(PoseLandmark.RIGHT_KNEE, 0.20208804309368134, 0.7013720406426324, 0.6801067590713501),
            landmark(PoseLandmark.LEFT_ANKLE, 0.3847403824329376, 0.9324774212307401, 0.6891353726387024),
            landmark(PoseLandmark.RIGHT_ANKLE, 0.4383878707885742, 0.9633452097574869, 0.8055703043937683),
        ),
        monotonicTimestampNanos = 0L,
        detectedPersonCount = 1,
    )

    private fun landmark(
        type: PoseLandmark,
        x: Double,
        y: Double,
        confidence: Double,
    ): Landmark = Landmark(
        type = type,
        x = x,
        y = y,
        z = 0.0,
        visibility = confidence,
        presence = confidence,
    )
}

enum class PrototypeGateState {
    PASS,
    FAIL,
    NOT_EVALUATED,
}

enum class ReferenceMatchStatus {
    WAITING_FOR_FRAME,
    NO_PERSON,
    MULTIPLE_PEOPLE,
    CANONICALIZATION_FAILED,
    EVALUATED,
}

enum class MirrorSelection {
    NORMAL,
    MIRRORED,
    NOT_EVALUATED,
}

enum class LockCaptureState {
    DISABLED,
}

data class NamedPrototypeGateEvidence(
    val state: PrototypeGateState,
    val score: Double?,
    val label: String,
) {
    init {
        require(score == null || score.isFinite() && score in 0.0..1.0)
        require((state == PrototypeGateState.NOT_EVALUATED) == (score == null))
        require(label.isNotBlank())
    }
}

/**
 * Named, deliberately uncalibrated Task 10 match evidence. This is display evidence only: framing
 * is not evaluated and capture lock is always disabled, even when all prototype pose gates pass.
 */
@ConsistentCopyVisibility
data class BundledReferenceMatchEvidence private constructor(
    val status: ReferenceMatchStatus,
    val referenceLabel: String,
    val framing: NamedPrototypeGateEvidence,
    val coverage: NamedPrototypeGateEvidence,
    val angular: NamedPrototypeGateEvidence,
    val positional: NamedPrototypeGateEvidence,
    val overall: NamedPrototypeGateEvidence,
    val selectedMirror: MirrorSelection,
    val mirrorLabel: String,
    val lockCaptureState: LockCaptureState,
    val captureLockLabel: String,
    val labels: List<String>,
) {
    companion object {
        private val canonicalizer = PoseCanonicalizer(
            minimumConfidence = 0.25,
            minimumTorsoScale = 1e-9,
        )
        private val policy = MatchPolicy.developmentDefaults()
        private val matcher = DefaultPoseMatcher(policy)
        private val referenceFeatures: PoseFeatures = when (
            val result = canonicalizer.canonicalize(BundledMeditationReference.observation)
        ) {
            is PoseCanonicalizationResult.Success -> result.features
            is PoseCanonicalizationResult.Failure ->
                error("Bundled meditation reference cannot be canonicalized: ${result.reason}")
        }

        fun evaluate(live: PoseObservation?): BundledReferenceMatchEvidence {
            if (live == null) {
                return unevaluated(ReferenceMatchStatus.WAITING_FOR_FRAME, "waiting for a frame")
            }
            if (live.detectedPersonCount == 0) {
                return unevaluated(ReferenceMatchStatus.NO_PERSON, "no person")
            }
            if (live.detectedPersonCount > 1) {
                return unevaluated(
                    ReferenceMatchStatus.MULTIPLE_PEOPLE,
                    "multiple people (${live.detectedPersonCount})",
                )
            }

            val normal = canonicalizer.canonicalize(live, mirror = false)
            val mirrored = canonicalizer.canonicalize(live, mirror = true)
            if (normal !is PoseCanonicalizationResult.Success || mirrored !is PoseCanonicalizationResult.Success) {
                return unevaluated(
                    ReferenceMatchStatus.CANONICALIZATION_FAILED,
                    "pose canonicalization failed",
                )
            }

            // Framing is intentionally a neutral matcher input because Task 10 does not evaluate it.
            val match = matcher.match(
                reference = referenceFeatures,
                observed = normal.features,
                mirroredObserved = mirrored.features,
                mirrorAllowed = BundledMeditationReference.mirrorAllowed,
                detectedPersonCount = live.detectedPersonCount,
                framingScore = 1.0,
            )
            val coverage = evaluatedGate(
                name = "Coverage gate",
                score = match.landmarkCoverage,
                failed = MatchGateFailure.INSUFFICIENT_LANDMARK_COVERAGE in match.gateFailures,
            )
            val angular = evaluatedGate(
                name = "Angular gate",
                score = match.angularSimilarity,
                failed = MatchGateFailure.ANGULAR_MISMATCH in match.gateFailures,
            )
            val positional = evaluatedGate(
                name = "Positional gate",
                score = match.positionalSimilarity,
                failed = MatchGateFailure.POSITIONAL_MISMATCH in match.gateFailures,
            )
            val overall = evaluatedGate(
                name = "Overall gate",
                score = match.overallMatch,
                failed = MatchGateFailure.LOW_OVERALL_MATCH in match.gateFailures,
            )
            val selectedMirror = if (match.mirrorUsed) MirrorSelection.MIRRORED else MirrorSelection.NORMAL
            val mirrorLabel = when (selectedMirror) {
                MirrorSelection.NORMAL -> "Selected mirror: normal"
                MirrorSelection.MIRRORED -> "Selected mirror: mirrored"
                MirrorSelection.NOT_EVALUATED -> error("Evaluated match must select a mirror candidate")
            }
            return create(
                status = ReferenceMatchStatus.EVALUATED,
                coverage = coverage,
                angular = angular,
                positional = positional,
                overall = overall,
                selectedMirror = selectedMirror,
                mirrorLabel = mirrorLabel,
            )
        }

        private fun unevaluated(
            status: ReferenceMatchStatus,
            reason: String,
        ): BundledReferenceMatchEvidence {
            val coverage = unevaluatedGate("Coverage gate", reason)
            val angular = unevaluatedGate("Angular gate", reason)
            val positional = unevaluatedGate("Positional gate", reason)
            val overall = unevaluatedGate("Overall gate", reason)
            return create(
                status = status,
                coverage = coverage,
                angular = angular,
                positional = positional,
                overall = overall,
                selectedMirror = MirrorSelection.NOT_EVALUATED,
                mirrorLabel = "Selected mirror: not evaluated ($reason)",
            )
        }

        private fun create(
            status: ReferenceMatchStatus,
            coverage: NamedPrototypeGateEvidence,
            angular: NamedPrototypeGateEvidence,
            positional: NamedPrototypeGateEvidence,
            overall: NamedPrototypeGateEvidence,
            selectedMirror: MirrorSelection,
            mirrorLabel: String,
        ): BundledReferenceMatchEvidence {
            val referenceLabel =
                "Reference loaded: ${BundledMeditationReference.label} (${BundledMeditationReference.observation.landmarks.size} landmarks)"
            val framing = NamedPrototypeGateEvidence(
                state = PrototypeGateState.NOT_EVALUATED,
                score = null,
                label = "Framing gate: not evaluated",
            )
            val captureLockLabel = "Capture lock: disabled in Task 10"
            val labels = Collections.unmodifiableList(
                ArrayList(
                    listOf(
                        referenceLabel,
                        framing.label,
                        coverage.label,
                        angular.label,
                        positional.label,
                        overall.label,
                        mirrorLabel,
                        captureLockLabel,
                    ),
                ),
            )
            return BundledReferenceMatchEvidence(
                status = status,
                referenceLabel = referenceLabel,
                framing = framing,
                coverage = coverage,
                angular = angular,
                positional = positional,
                overall = overall,
                selectedMirror = selectedMirror,
                mirrorLabel = mirrorLabel,
                lockCaptureState = LockCaptureState.DISABLED,
                captureLockLabel = captureLockLabel,
                labels = labels,
            )
        }

        private fun evaluatedGate(
            name: String,
            score: Double,
            failed: Boolean,
        ): NamedPrototypeGateEvidence {
            val state = if (failed) PrototypeGateState.FAIL else PrototypeGateState.PASS
            val result = if (failed) "fail" else "pass"
            return NamedPrototypeGateEvidence(
                state = state,
                score = score,
                label = "$name: $result (uncalibrated)",
            )
        }

        private fun unevaluatedGate(
            name: String,
            reason: String,
        ): NamedPrototypeGateEvidence = NamedPrototypeGateEvidence(
            state = PrototypeGateState.NOT_EVALUATED,
            score = null,
            label = "$name: not evaluated ($reason)",
        )
    }
}
