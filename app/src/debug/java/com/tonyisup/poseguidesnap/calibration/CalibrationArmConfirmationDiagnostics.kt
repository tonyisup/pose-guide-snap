package com.tonyisup.poseguidesnap.calibration

import java.util.Locale

internal enum class ArmConfirmationState { SPEAKING, UNAVAILABLE, UNSETTLED, MISMATCH, SETTLING, READY, EXPIRED }

/** Debug-only, bounded aggregates. Retains no frames, coordinates, text or participant identity. */
internal class CalibrationArmConfirmationDiagnostics {
    private val counts = IntArray(ArmConfirmationState.entries.size)
    private var samples = 0
    private var capped = false
    private var gaps = 0
    private var longestStableMs = 0L
    private var shortestPostSpeechWindowMs: Long? = null
    private var elbowMin: Double? = null
    private var elbowMax: Double? = null
    private var jointMin: Double? = null
    private var jointMax: Double? = null
    private var wristMin: Double? = null
    private var wristMax: Double? = null

    fun speechCompleted(remainingMs: Long) {
        val bounded = remainingMs.coerceIn(0, 60_000)
        shortestPostSpeechWindowMs = minOf(shortestPostSpeechWindowMs ?: bounded, bounded)
    }

    fun record(state: ArmConfirmationState, elbow: Double?, wrist: Double?, joint: Double?, stableMs: Long, gap: Boolean) {
        if (samples == 600) { capped = true; return }
        samples++
        counts[state.ordinal]++
        if (gap) gaps++
        // Measure error only when there was a real chance to confirm, after speech and before expiry.
        if (state in setOf(ArmConfirmationState.UNSETTLED, ArmConfirmationState.MISMATCH,
                ArmConfirmationState.SETTLING, ArmConfirmationState.READY)) {
            elbow?.takeIf { it.isFinite() }?.coerceIn(0.0, 10.0)?.let {
                elbowMin = minOf(elbowMin ?: it, it); elbowMax = maxOf(elbowMax ?: it, it)
            }
            wrist?.takeIf { it.isFinite() }?.coerceIn(0.0, 10.0)?.let {
                wristMin = minOf(wristMin ?: it, it); wristMax = maxOf(wristMax ?: it, it)
            }
            joint?.takeIf { it.isFinite() }?.coerceIn(0.0, 10.0)?.let {
                jointMin = minOf(jointMin ?: it, it); jointMax = maxOf(jointMax ?: it, it)
            }
            longestStableMs = maxOf(longestStableMs, stableMs.coerceIn(0, 60_000))
        }
    }

    fun summary(): String = buildString {
        append("scope=joint samples=$samples capped=$capped gaps=$gaps stableMaxMs=$longestStableMs ")
        append("wristTarget=same_side_knee elbowTarget=reference ")
        append("postSpeechWindowMinMs=${shortestPostSpeechWindowMs ?: "unavailable"} ")
        append("elbowMin=${number(elbowMin)} elbowMax=${number(elbowMax)} ")
        append("wristMin=${number(wristMin)} wristMax=${number(wristMax)} ")
        append("jointMin=${number(jointMin)} jointMax=${number(jointMax)} ")
        append(ArmConfirmationState.entries.joinToString(" ") { "${it.name}=${counts[it.ordinal]}" })
    }

    private fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "unavailable"
}
