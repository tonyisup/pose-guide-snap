package com.tonyisup.poseguidesnap.calibration

import java.util.ArrayList
import java.util.Collections

/** Ground-truth label supplied by the authorized collection procedure, never inferred from scores. */
enum class CalibrationFixtureClass(val wireValue: String) {
    POSITIVE("positive"),
    NEGATIVE("negative");

    companion object {
        fun fromWireValue(value: String?): CalibrationFixtureClass =
            entries.singleOrNull { fixture -> fixture.wireValue == value }
                ?: throw IllegalArgumentException("fixtureClass must be positive or negative")
    }
}

/** Closed categorical result retained without detector payload or landmark identities. */
enum class DerivedCalibrationEvaluationStatus(val wireValue: String) {
    NO_PERSON("no-person"),
    MULTIPLE_PEOPLE("multiple-people"),
    CANONICALIZATION_FAILED("canonicalization-failed"),
    EVALUATED("evaluated"),
}

/** Exact, fail-closed argument contract for one explicitly authorized device collection. */
class CalibrationCollectionRequest private constructor(
    val authorization: String,
    val datasetId: String,
    val sequenceId: String,
    val fixtureClass: CalibrationFixtureClass,
    val caseClass: String,
    val warmupMs: Int,
    val durationMs: Int,
) {
    companion object {
        const val MIN_WARMUP_MS = 3_000
        const val MAX_WARMUP_MS = 60_000
        const val MIN_DURATION_MS = 5_000
        const val MAX_DURATION_MS = 30_000
        const val MIN_RECORDED_FRAMES = 20
        const val OUTPUT_FILENAME = "calibration-sequence-v3.json"
        const val DETECTOR_POSITIVE_CASE_CLASS = "single-person-full-body"
        const val DETECTOR_NEGATIVE_CASE_CLASS = "no-person-empty-scene"

        fun fromRaw(
            authorization: String?,
            datasetId: String?,
            sequenceId: String?,
            fixtureClass: String?,
            caseClass: String?,
            warmupMs: String?,
            durationMs: String?,
        ): CalibrationCollectionRequest {
            require(authorization == DerivedCalibrationSequence.AUTHORIZATION) {
                "calibrationAuthorization must exactly authorize derived collection"
            }
            val requiredDatasetId = requireIdentifier(datasetId, "datasetId")
            val requiredSequenceId = requireIdentifier(sequenceId, "sequenceId")
            val requiredCaseClass = requireIdentifier(caseClass, "caseClass")
            return CalibrationCollectionRequest(
                authorization = authorization,
                datasetId = requiredDatasetId,
                sequenceId = requiredSequenceId,
                fixtureClass = CalibrationFixtureClass.fromWireValue(fixtureClass),
                caseClass = requiredCaseClass,
                warmupMs = requireBoundedInt(
                    warmupMs,
                    "warmupMs",
                    MIN_WARMUP_MS..MAX_WARMUP_MS,
                ),
                durationMs = requireBoundedInt(
                    durationMs,
                    "durationMs",
                    MIN_DURATION_MS..MAX_DURATION_MS,
                ),
            )
        }

        private fun requireBoundedInt(value: String?, name: String, range: IntRange): Int {
            val parsed = value?.toIntOrNull()
            require(parsed != null && parsed in range) {
                "$name must be an integer in [${range.first}, ${range.last}]"
            }
            return parsed
        }

        fun requireDetectorGateGroundTruth(request: CalibrationCollectionRequest) {
            val expectedCaseClass = when (request.fixtureClass) {
                CalibrationFixtureClass.POSITIVE -> DETECTOR_POSITIVE_CASE_CLASS
                CalibrationFixtureClass.NEGATIVE -> DETECTOR_NEGATIVE_CASE_CLASS
            }
            require(request.caseClass == expectedCaseClass) {
                "detector-gate caseClass must be $expectedCaseClass for ${request.fixtureClass.wireValue}"
            }
        }
    }
}

/**
 * One privacy-bounded calibration frame. It can retain only derived scalars and event observations;
 * no image, landmark, path, URI, wall-clock time, or detector payload can enter this type.
 */
data class DerivedCalibrationFrame(
    val elapsedMs: Int,
    val detectedPersonCount: Int,
    val evaluationStatus: DerivedCalibrationEvaluationStatus,
    val confidenceQualifiedLandmarkCount: Int,
    val qualifiedTorsoAnchorCount: Int,
    val maximumValidPersonScore: Double?,
    val maximumValidKeypointScore: Double?,
    val landmarkCoverage: Double,
    val framingScore: Double,
    val angularSimilarity: Double,
    val positionalSimilarity: Double,
    val overallMatch: Double,
    val mirrorUsed: Boolean,
    val inferenceLatencyMs: Double?,
    val cueEmitted: Boolean?,
    val captureCommands: Int?,
) {
    init {
        require(elapsedMs >= 0) { "elapsedMs must be nonnegative" }
        require(detectedPersonCount >= 0) { "detectedPersonCount must be nonnegative" }
        require(confidenceQualifiedLandmarkCount in 0..17) {
            "confidenceQualifiedLandmarkCount must be in [0, 17]"
        }
        require(qualifiedTorsoAnchorCount in 0..4) {
            "qualifiedTorsoAnchorCount must be in [0, 4]"
        }
        require(qualifiedTorsoAnchorCount <= confidenceQualifiedLandmarkCount) {
            "qualifiedTorsoAnchorCount cannot exceed confidenceQualifiedLandmarkCount"
        }
        require(
            when (evaluationStatus) {
                DerivedCalibrationEvaluationStatus.NO_PERSON -> detectedPersonCount == 0
                DerivedCalibrationEvaluationStatus.MULTIPLE_PEOPLE -> detectedPersonCount > 1
                DerivedCalibrationEvaluationStatus.CANONICALIZATION_FAILED,
                DerivedCalibrationEvaluationStatus.EVALUATED -> detectedPersonCount == 1
            },
        ) { "evaluationStatus must agree with detectedPersonCount" }
        require(
            evaluationStatus != DerivedCalibrationEvaluationStatus.EVALUATED ||
                qualifiedTorsoAnchorCount == 4,
        ) { "evaluated frames require all four confidence-qualified torso anchors" }
        require(
            evaluationStatus != DerivedCalibrationEvaluationStatus.NO_PERSON ||
                confidenceQualifiedLandmarkCount == 0,
        ) { "no-person frames cannot contain confidence-qualified landmarks" }
        requireOptionalCalibrationScore(maximumValidPersonScore, "maximumValidPersonScore")
        requireOptionalCalibrationScore(maximumValidKeypointScore, "maximumValidKeypointScore")
        requireCalibrationScore(landmarkCoverage, "landmarkCoverage")
        requireCalibrationScore(framingScore, "framingScore")
        requireCalibrationScore(angularSimilarity, "angularSimilarity")
        requireCalibrationScore(positionalSimilarity, "positionalSimilarity")
        requireCalibrationScore(overallMatch, "overallMatch")
        require(inferenceLatencyMs == null || inferenceLatencyMs.isFinite() && inferenceLatencyMs >= 0.0) {
            "inferenceLatencyMs must be null or finite and nonnegative"
        }
        require(captureCommands == null || captureCommands in 0..3) {
            "captureCommands must be null or in [0, 3]"
        }
    }
}

/**
 * One deterministic analyzer-compatible dataset document for the bounded public-reference Pixel
 * collector. Free-form provenance and population text are fixed here rather than accepted from
 * device arguments.
 */
class DerivedCalibrationSequence private constructor(
    val datasetId: String,
    val sequenceId: String,
    val fixtureClass: CalibrationFixtureClass,
    val caseClass: String,
    frames: List<DerivedCalibrationFrame>,
) {
    val frames: List<DerivedCalibrationFrame> = Collections.unmodifiableList(ArrayList(frames))

    init {
        requireIdentifier(datasetId, "datasetId")
        requireIdentifier(sequenceId, "sequenceId")
        requireIdentifier(caseClass, "caseClass")
        require(this.frames.isNotEmpty()) { "frames must not be empty" }
        require(this.frames.size <= MAX_FRAMES) { "frames must contain at most $MAX_FRAMES values" }
        this.frames.zipWithNext().forEach { (previous, current) ->
            require(current.elapsedMs > previous.elapsedMs) {
                "frame elapsedMs values must be strictly increasing"
            }
        }
    }

    /** Exact schemaVersion-3 document accepted by tools/calibration/analyze_match_reports.py. */
    fun toJson(): String = buildString {
        append('{')
        append("\"schemaVersion\":3,")
        append("\"datasetId\":").appendJsonString(datasetId).append(',')
        append("\"provenance\":").appendJsonString(PROVENANCE).append(',')
        append("\"authorization\":\"").append(AUTHORIZATION).append("\",")
        append("\"populationLimits\":[")
        POPULATION_LIMITS.forEachIndexed { index, limit ->
            if (index > 0) append(',')
            appendJsonString(limit)
        }
        append("],")
        append("\"containsPrivateImages\":false,")
        append("\"sequences\":[{")
        append("\"sequenceId\":").appendJsonString(sequenceId).append(',')
        append("\"fixtureClass\":\"").append(fixtureClass.wireValue).append("\",")
        append("\"caseClass\":").appendJsonString(caseClass).append(',')
        append("\"frames\":[")
        frames.forEachIndexed { index, frame ->
            if (index > 0) append(',')
            appendFrame(frame)
        }
        append("]}]")
        append('}')
    }

    companion object {
        const val AUTHORIZATION = "user-authorized-derived"
        const val MAX_FRAMES = 600

        private const val PROVENANCE =
            "User-authorized derived rear-camera observations against the bundled public meditation reference."
        private val POPULATION_LIMITS = listOf(
            "One user-authorized participant on one Pixel 6.",
            "One bundled public reference pose and one explicitly labeled case per sequence.",
            "Contains no images, raw landmarks, paths, URIs, wall-clock timestamps, or identity fields.",
            "Cannot support population-wide accuracy or automatic-capture enablement by itself.",
        )
        fun create(
            datasetId: String,
            sequenceId: String,
            fixtureClass: CalibrationFixtureClass,
            caseClass: String,
            frames: List<DerivedCalibrationFrame>,
        ): DerivedCalibrationSequence = DerivedCalibrationSequence(
            datasetId = datasetId,
            sequenceId = sequenceId,
            fixtureClass = fixtureClass,
            caseClass = caseClass,
            frames = frames,
        )
    }
}

private fun StringBuilder.appendFrame(frame: DerivedCalibrationFrame) {
    append('{')
    append("\"elapsedMs\":").append(frame.elapsedMs).append(',')
    append("\"detectedPersonCount\":").append(frame.detectedPersonCount).append(',')
    append("\"evaluationStatus\":").appendJsonString(frame.evaluationStatus.wireValue).append(',')
    append("\"confidenceQualifiedLandmarkCount\":")
        .append(frame.confidenceQualifiedLandmarkCount).append(',')
    append("\"qualifiedTorsoAnchorCount\":").append(frame.qualifiedTorsoAnchorCount).append(',')
    append("\"maximumValidPersonScore\":").appendNullableNumber(frame.maximumValidPersonScore).append(',')
    append("\"maximumValidKeypointScore\":").appendNullableNumber(frame.maximumValidKeypointScore).append(',')
    append("\"landmarkCoverage\":").append(frame.landmarkCoverage).append(',')
    append("\"framingScore\":").append(frame.framingScore).append(',')
    append("\"angularSimilarity\":").append(frame.angularSimilarity).append(',')
    append("\"positionalSimilarity\":").append(frame.positionalSimilarity).append(',')
    append("\"overallMatch\":").append(frame.overallMatch).append(',')
    append("\"mirrorUsed\":").append(frame.mirrorUsed).append(',')
    append("\"inferenceLatencyMs\":").appendNullableNumber(frame.inferenceLatencyMs).append(',')
    append("\"cueEmitted\":").appendNullableBoolean(frame.cueEmitted).append(',')
    append("\"captureCommands\":").appendNullableInt(frame.captureCommands)
    append('}')
}

private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    return append('"')
}

private fun StringBuilder.appendNullableNumber(value: Double?): StringBuilder =
    if (value == null) append("null") else append(value)

private fun StringBuilder.appendNullableBoolean(value: Boolean?): StringBuilder =
    if (value == null) append("null") else append(value)

private fun StringBuilder.appendNullableInt(value: Int?): StringBuilder =
    if (value == null) append("null") else append(value)

private fun requireCalibrationScore(value: Double, name: String) {
    require(value.isFinite() && value in 0.0..1.0) {
        "$name must be finite and in [0, 1]"
    }
}

private fun requireOptionalCalibrationScore(value: Double?, name: String) {
    require(value == null || value.isFinite() && value in 0.0..1.0) {
        "$name must be null or finite and in [0, 1]"
    }
}

private val CALIBRATION_IDENTIFIER = Regex("[a-z0-9][a-z0-9-]{0,63}")

private fun requireIdentifier(value: String?, name: String): String {
    require(value != null && CALIBRATION_IDENTIFIER.matches(value)) {
        "$name must match ${CALIBRATION_IDENTIFIER.pattern}"
    }
    return value
}
