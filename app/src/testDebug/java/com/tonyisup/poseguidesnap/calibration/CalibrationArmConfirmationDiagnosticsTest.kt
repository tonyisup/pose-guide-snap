package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.*
import org.junit.Test

class CalibrationArmConfirmationDiagnosticsTest {
    private val reference = BundledMeditationReference.observation
    private val guide = CalibrationAlignmentGuide()
    private val corrected = guide.evaluate(reference)
    private val wrong = guide.evaluate(PoseObservation(reference.landmarks.map {
        if (it.type == PoseLandmark.LEFT_ELBOW) it.copy(y = it.y - 0.3) else it
    }, 0, 1, reference.imageSize))

    @Test
    fun alreadyCorrectAfterInstructionEarnsGoodAndRecordsReady() {
        val speech = requestedArm()
        speech.speechCompleted(10_000, SpokenAlignmentCue.LEFT_ELBOW_DOWN)
        for (now in 10_000L..10_500L step 500) assertNull(speech.next(corrected, now, false))
        assertEquals(SpokenAlignmentCue.GOOD, speech.next(corrected, 11_000, false))
        val summary = fields(speech)
        assertEquals("1", summary["READY"])
        assertEquals("1000", summary["stableMaxMs"])
        assertEquals("0.0000", summary["elbowMax"])
        assertTrue(summary.getValue("wristMax").toDouble() in 0.0..ArmPoseFeedback.MAX_ARM_ERROR)
        assertEquals("same_side_knee", summary["wristTarget"])
        assertEquals("reference", summary["elbowTarget"])
    }

    @Test
    fun distinguishesElbowMismatchFromWristPositionAndMissingEvidence() {
        val speech = requestedArm()
        speech.next(wrong, 8_500, true)
        speech.speechCompleted(10_000, SpokenAlignmentCue.LEFT_ELBOW_DOWN)
        speech.next(wrong, 10_000, false)
        speech.next(wrong, 10_500, false)
        speech.next(wrong, 11_000, false)
        speech.next(AlignmentFeedback(AlignmentCue.INCOMPLETE), 11_500, false)
        val summary = fields(speech)
        assertEquals("1", summary["SPEAKING"])
        assertEquals("1", summary["MISMATCH"])
        assertEquals("2", summary["UNSETTLED"])
        assertEquals("1", summary["UNAVAILABLE"])
        assertTrue(summary.getValue("elbowMin").toDouble() > ArmPoseFeedback.MAX_ARM_ERROR)
        assertTrue(summary.getValue("wristMax").toDouble() < ArmPoseFeedback.MAX_ARM_ERROR)
    }

    @Test
    fun shortWindowIsDistinguishedFromWrongPositionAndHoldDoesNotOverwriteIt() {
        val speech = requestedArm()
        speech.speechCompleted(29_100, SpokenAlignmentCue.LEFT_ELBOW_DOWN)
        for (now in 29_100L..30_000L step 100) assertNull(speech.next(corrected, now, false))
        speech.speechCompleted(33_000) // Hold is not an arm instruction.
        val summary = fields(speech)
        assertEquals("900", summary["postSpeechWindowMinMs"])
        assertEquals("800", summary["stableMaxMs"])
        assertEquals("1", summary["EXPIRED"])
        assertEquals("0", summary["MISMATCH"])
        assertEquals("0", summary["READY"])
    }

    @Test
    fun frameGapsPreventConfirmationDespiteMatchingArmAndAreVisible() {
        val speech = requestedArm()
        speech.speechCompleted(10_000, SpokenAlignmentCue.LEFT_ELBOW_DOWN)
        for (now in 10_000L..13_000L step 1_000) assertNull(speech.next(corrected, now, false))
        val summary = fields(speech)
        assertTrue(summary.getValue("gaps").toInt() >= 3)
        assertEquals("0", summary["stableMaxMs"])
        assertEquals("0.0000", summary["elbowMax"])
    }

    @Test
    fun diagnosticsStayBoundedAndResetWithNewWarmup() {
        val speech = requestedArm()
        repeat(1_000) { speech.next(wrong, 8_001L + it, true) }
        val summary = fields(speech)
        assertEquals("600", summary["samples"])
        assertEquals("true", summary["capped"])
        assertEquals("unavailable", summary["elbowMax"])
        assertEquals(setOf("scope", "wristTarget", "elbowTarget", "jointMin", "jointMax", "samples", "capped", "gaps", "stableMaxMs", "postSpeechWindowMinMs",
            "elbowMin", "elbowMax", "wristMin", "wristMax") + ArmConfirmationState.entries.map { it.name }, summary.keys)
        speech.start(60_000)
        assertEquals("0", fields(speech)["samples"])
        assertEquals("unavailable", fields(speech)["postSpeechWindowMinMs"])
    }

    private fun requestedArm(): CalibrationSpokenGuidance = CalibrationSpokenGuidance().also { speech ->
        speech.start(30_000)
        speech.speechCompleted(0)
        for (now in 0L..7_500L step 500) assertNull(speech.next(wrong, now, false))
        assertEquals(SpokenAlignmentCue.LEFT_ELBOW_DOWN, speech.next(wrong, 8_000, false))
    }

    private fun fields(speech: CalibrationSpokenGuidance): Map<String, String> =
        speech.armConfirmationSummary().split(" ").associate {
            val (key, value) = it.split("="); key to value
        }
}
