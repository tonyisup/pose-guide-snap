package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.*
import org.junit.Test

class CalibrationPreparationGateTest {
    private val guide = CalibrationAlignmentGuide()
    private val reference = BundledMeditationReference.observation
    private val correct = guide.evaluate(reference)
    private val wrong = guide.evaluate(PoseObservation(reference.landmarks.map {
        if (it.type == PoseLandmark.LEFT_WRIST) it.copy(y = it.y + 0.2) else it
    }, 0, 1, reference.imageSize))

    @Test
    fun freshSettledResolvedGuidanceCanAdvanceAfterMinimumWithoutWaitingForTimeout() {
        val run = Run { correct }
        run.start()
        assertEquals(30_000L, run.now)
        assertTrue(run.holdStarted)
        assertEquals(0, run.stops)
        assertEquals("result=READY blockers=none", run.finalStatus!!.summary())
        assertFalse(SpokenAlignmentCue.GOOD in run.cues) // Already correct, no invented adjustment.
    }

    @Test
    fun visibleButUnresolvedArmStopsOnceAndNeverStartsHold() {
        val run = Run { wrong }
        assertThrows(IllegalStateException::class.java) { run.start() }
        assertEquals(60_000L, run.now)
        assertEquals(1, run.stops)
        assertFalse(run.holdStarted)
        assertTrue(SpokenAlignmentCue.LEFT_HAND_ON_KNEE in run.cues)
        assertEquals(setOf(CalibrationPreparationBlocker.DEADLINE, CalibrationPreparationBlocker.ARM_POSITION,
            CalibrationPreparationBlocker.UNCONFIRMED_ADJUSTMENT), run.finalStatus!!.blockers)
    }

    @Test
    fun lateGoodCannotSkipItsQuietPauseToBeatTheDeadline() {
        val run = Run { now -> if (now < 51_000) wrong else correct }
        assertThrows(IllegalStateException::class.java) { run.start() }
        assertTrue(SpokenAlignmentCue.GOOD in run.cues)
        assertEquals(60_000L, run.now)
        assertFalse(run.holdStarted)
        assertEquals(1, run.stops)
        assertEquals(setOf(CalibrationPreparationBlocker.DEADLINE, CalibrationPreparationBlocker.QUIET_PAUSE),
            run.finalStatus!!.blockers)
    }

    @Test
    fun lostFramesCannotTurnPreviouslySettledEvidenceIntoPermissionToHold() {
        val run = Run { now -> if (now <= 29_000) correct else null }
        assertThrows(IllegalStateException::class.java) { run.start() }
        assertFalse(run.holdStarted)
        assertEquals(1, run.stops)
        assertEquals(setOf(CalibrationPreparationBlocker.DEADLINE, CalibrationPreparationBlocker.TRACKING),
            run.finalStatus!!.blockers)
    }

    @Test
    fun motionNearTheMinimumDelaysHandoffUntilFreshStillnessReturns() {
        val run = Run { now ->
            if (now in 29_000L..31_900L) {
                guide.evaluate(PoseObservation(reference.landmarks.map {
                    it.copy(y = it.y - if (now % 200 != 0L) 0.04 else 0.0)
                }, 0, 1, reference.imageSize))
            } else correct
        }
        run.start()
        assertTrue(run.now >= 33_000L && run.now < 60_000L)
        assertTrue(run.holdStarted)
        assertEquals(0, run.stops)
    }

    @Test
    fun framingErrorAlsoPreventsHoldAfterArmsAreCorrect() {
        val small = guide.evaluate(PoseObservation(reference.landmarks.map {
            it.copy(x = 0.5 + (it.x - 0.5) * 0.8, y = 0.5 + (it.y - 0.5) * 0.8)
        }, 0, 1, reference.imageSize))
        assertNull(small.armPose!!.correction)
        val run = Run { small }
        assertThrows(IllegalStateException::class.java) { run.start() }
        assertFalse(run.holdStarted)
        assertEquals(1, run.stops)
        assertTrue(CalibrationPreparationBlocker.FRAMING in run.finalStatus!!.blockers)
        assertFalse(CalibrationPreparationBlocker.ARM_POSITION in run.finalStatus!!.blockers)
    }

    private class Run(private val feedback: (Long) -> AlignmentFeedback?) {
        var now = 0L
        var stops = 0
        var holdStarted = false
        var finalStatus: CalibrationPreparationStatus? = null
        val cues = mutableListOf<SpokenAlignmentCue>()
        private val speech = CalibrationSpokenGuidance()
        private var pending: SpokenAlignmentCue? = null
        private var completeAt = 0L

        fun start() {
            speech.start(60_000, earliestHoldAtMs = 30_000)
            speech.speechCompleted(0)
            CalibrationPreparationGate(
                elapsedRealtimeMs = { now },
                sleepMs = { now += it },
                decision = { time ->
                    if (pending != null && time >= completeAt) {
                        speech.speechCompleted(time, pending)
                        pending = null
                    }
                    feedback(time)?.let {
                        speech.next(it, time, pending != null)?.let { cue ->
                            cues += cue
                            pending = cue
                            completeAt = time + 1_000
                        }
                    }
                    speech.preparationStatus(time).also {
                        if (it.decision != CalibrationPreparationDecision.WAITING) finalStatus = it
                    }.decision
                },
            ).awaitReady { stops++ }
            holdStarted = true // Same success-only order used by the device collector.
        }
    }
}
