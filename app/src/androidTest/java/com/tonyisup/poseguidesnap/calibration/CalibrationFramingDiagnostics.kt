package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.match.FramingEvidence
import com.tonyisup.poseguidesnap.domain.match.FramingEvidenceStatus

/** Test-only bounded reducer. Retains counts and scalar extrema, never observations or coordinates. */
internal class CalibrationFramingDiagnostics {
    private var recordedFrames = 0
    private var evaluatedFrames = 0
    private var minimumCenterSimilarity: Double? = null
    private var maximumCenterSimilarity: Double? = null
    private var minimumScaleSimilarity: Double? = null
    private var maximumScaleSimilarity: Double? = null

    /** Caller serializes access with the same lock used to admit report frames. */
    fun record(evidence: FramingEvidence) {
        check(recordedFrames < DerivedCalibrationSequence.MAX_FRAMES)
        recordedFrames += 1
        if (evidence.status != FramingEvidenceStatus.EVALUATED) return
        evaluatedFrames += 1
        minimumCenterSimilarity = minOf(minimumCenterSimilarity ?: evidence.centerSimilarity, evidence.centerSimilarity)
        maximumCenterSimilarity = maxOf(maximumCenterSimilarity ?: evidence.centerSimilarity, evidence.centerSimilarity)
        minimumScaleSimilarity = minOf(minimumScaleSimilarity ?: evidence.scaleSimilarity, evidence.scaleSimilarity)
        maximumScaleSimilarity = maxOf(maximumScaleSimilarity ?: evidence.scaleSimilarity, evidence.scaleSimilarity)
    }

    fun summary(): String =
        "recordedFrames=$recordedFrames evaluatedFrames=$evaluatedFrames " +
            "unavailableFrames=${recordedFrames - evaluatedFrames} " +
            "minimumCenterSimilarity=${minimumCenterSimilarity ?: "unavailable"} " +
            "maximumCenterSimilarity=${maximumCenterSimilarity ?: "unavailable"} " +
            "minimumScaleSimilarity=${minimumScaleSimilarity ?: "unavailable"} " +
            "maximumScaleSimilarity=${maximumScaleSimilarity ?: "unavailable"}"
}
