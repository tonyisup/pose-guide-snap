package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.*
import org.junit.Test

class CalibrationCoachingCycleTest {
    private val guide = CalibrationAlignmentGuide()
    private val reference = BundledMeditationReference.observation
    private val corrected = guide.evaluate(reference)

    @Test
    fun briefPassThroughTargetThenSettlingElsewhereGetsFollowupBeforeHold() {
        // The real request parser and scheduler share this bounded preparation duration.
        val request = CalibrationCollectionRequest.fromRaw(
            authorization = DerivedCalibrationSequence.AUTHORIZATION,
            datasetId = "joint-cycle-regression", sequenceId = "joint-cycle-a",
            fixtureClass = "positive", caseClass = "centered-match",
            warmupMs = CalibrationCollectionRequest.MAX_WARMUP_MS.toString(), durationMs = "10000",
        )
        val speech = CalibrationSpokenGuidance()
        speech.start(request.warmupMs.toLong())
        speech.speechCompleted(10_000) // Allow a realistically long introduction.
        val low = wrist(0.0, 0.2)
        for (now in 10_000L..17_900L step 100) assertNull(speech.next(low, now, false))
        assertEquals(SpokenAlignmentCue.LEFT_HAND_ON_KNEE, speech.next(low, 18_000, false))
        speech.speechCompleted(22_000, SpokenAlignmentCue.LEFT_HAND_ON_KNEE)

        // Synthetic analogue of one <=3-second adjustment, briefly accepted for 200 ms.
        // It does not reconstruct the participant's unretained trajectory or final direction.
        val stillLeft = wrist(0.14, 0.0)
        for (now in 22_000L..29_900L step 100) {
            val feedback = when (now) {
                in 23_400L..23_600L -> corrected
                in 22_000L..24_900L -> low
                else -> stillLeft
            }
            assertNull(speech.next(feedback, now, false))
        }
        assertTrue(speech.armConfirmationSummary().contains("stableMaxMs=200 "))
        assertEquals(SpokenAlignmentCue.LEFT_HAND_UNCONFIRMED, speech.next(stillLeft, 30_000, false))
        speech.speechCompleted(34_000, SpokenAlignmentCue.LEFT_HAND_UNCONFIRMED)
        for (now in 34_000L..34_900L step 100) assertNull(speech.next(corrected, now, false))
        assertEquals(SpokenAlignmentCue.GOOD, speech.next(corrected, 35_000, false))
        speech.speechCompleted(36_000, SpokenAlignmentCue.GOOD)
        for (now in 36_000L..43_900L step 100) assertNull(speech.next(corrected, now, false))
    }

    @Test
    fun observedHandCueNamesTheSupportedRestingPosition() {
        assertEquals("Rest your left hand on your left knee, palm up. Let your arm relax.",
            CalibrationSpokenGuidance().cueFor(wrist(0.0, 0.2))?.text)
    }

    private fun wrist(dx: Double, dy: Double) = guide.evaluate(PoseObservation(
        reference.landmarks.map {
            if (it.type == PoseLandmark.LEFT_WRIST) it.copy(x = it.x + dx, y = it.y + dy) else it
        }, 0, 1, reference.imageSize,
    ))
}
