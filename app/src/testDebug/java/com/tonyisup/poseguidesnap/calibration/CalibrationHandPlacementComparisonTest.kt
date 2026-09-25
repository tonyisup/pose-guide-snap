package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.*
import org.junit.Test

class CalibrationHandPlacementComparisonTest {
    private val reference = BundledMeditationReference.observation
    private val guide = CalibrationAlignmentGuide()
    private val knees = hands(inLap = false)
    private val lap = hands(inLap = true)

    @Test
    fun authorizationAndIdentifierAreRequiredBeforeDiagnosticWork() {
        for (authorization in listOf(null, "", "yes")) assertThrows(IllegalArgumentException::class.java) {
            HandPlacementComparisonRequest.fromRaw(authorization, "hands-a")
        }
        for (id in listOf(null, "", "../file", "person name", "a\nb", "a".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) {
                HandPlacementComparisonRequest.fromRaw(DerivedCalibrationSequence.AUTHORIZATION, id)
            }
        }
        val request = HandPlacementComparisonRequest.fromRaw(DerivedCalibrationSequence.AUTHORIZATION, "hands-a")
        assertEquals("hands-a", request.id)
        assertEquals("HandPlacementComparisonRequest(redacted)", request.toString())
    }

    @Test
    fun bothPositionsAreMeasuredDespiteFailingCoachingMatchAndKeepSeparateSummaries() {
        assertNotNull(lap.armPose!!.correction) // A deliberate nonmatching control is measurable.
        val run = Run()
        run.run()
        assertEquals(5, run.words.size)
        assertEquals("Comparison finished. You can relax.", run.words.last().second)
        assertEquals(2, run.flashes.size)
        assertTrue(run.words[1].first - run.flashes[0] >= 30_000)
        assertTrue(run.words[3].first - run.flashes[1] >= 15_000)
        assertTrue(run.words.none { it.second == "Good." })
        val summaries = run.probe.summaries().map(::fields)
        for (summary in summaries) {
            assertTrue(summary.getValue("eligible").toInt() >= 30)
            assertEquals("false", summary["capped"])
        }
        assertEquals("KNEES", summaries[0]["phase"])
        assertEquals(summaries[0]["eligible"], summaries[0]["leftWithin025"])
        assertEquals(summaries[0]["eligible"], summaries[0]["rightWithin025"])
        assertEquals("LAP", summaries[1]["phase"])
        assertEquals("0", summaries[1]["leftWithin025"])
        assertEquals("0", summaries[1]["rightWithin025"])
    }

    @Test
    fun missingOrContinuallyMovingBodyCannotStartMeasurementsAtAnElapsedDeadline() {
        for (moving in listOf(false, true)) {
            val run = Run { now, _ ->
                if (!moving) AlignmentFeedback(AlignmentCue.INCOMPLETE) else shifted(now)
            }
            assertThrows(IllegalStateException::class.java) { run.run() }
            assertEquals(1, run.words.size)
            assertTrue(run.now >= 45_000 && run.now < 50_000)
            for (summary in run.probe.summaries()) assertEquals("0", fields(summary)["samples"])
        }
    }

    @Test
    fun speechFailureCannotStartAMeasurement() {
        val run = Run()
        run.failMeasurementSpeech = true
        assertThrows(IllegalStateException::class.java) { run.run() }
        for (summary in run.probe.summaries()) assertEquals("0", fields(summary)["samples"])
    }

    @Test
    fun motionDuringTheWindowIsCountedButNotUsedToCalibrateDistance() {
        val probe = measuring()
        for (now in 1_100L..10_900L step 100) probe.observe(shifted(now), now, now)
        assertFalse(probe.finishMeasurement(11_000))
        val summary = fields(probe.summaries()[0])
        assertEquals("0", summary["eligible"])
        assertEquals(summary["samples"], summary["unsettled"])
        assertEquals("unavailable", summary["rightP95Upper"])
    }

    @Test
    fun enoughEarlySamplesCannotCompleteAWindowAfterTrackingStops() {
        val probe = measuring()
        for (now in 1_100L..6_100L step 100) probe.observe(knees, now, now)
        assertTrue(fields(probe.summaries()[0]).getValue("eligible").toInt() >= 30)
        assertFalse(probe.finishMeasurement(11_000))
    }

    @Test
    fun staleDuplicateAndPreWindowFramesCannotContributeOrAuthorizeReadiness() {
        val probe = measuring()
        probe.observe(knees, 900, 1_100) // Before the measurement announcement completed.
        probe.observe(knees, 1_100, 2_000) // Stale, even though delivered during the window.
        probe.observe(knees, 2_000, 2_000)
        probe.observe(knees, 2_000, 2_100) // Duplicate resets motion evidence.
        probe.observe(AlignmentFeedback(AlignmentCue.INCOMPLETE), 2_200, 2_200)
        assertFalse(probe.finishMeasurement(11_000))
        val summary = fields(probe.summaries()[0])
        assertEquals("3", summary["discarded"])
        assertEquals("1", summary["unavailable"])
        assertEquals("1", summary["unsettled"])
        assertEquals("0", summary["eligible"])
    }

    @Test
    fun summariesAreBoundedAndClosingFreezesCountersAndClearsReadiness() {
        val probe = measuring()
        repeat(1_000) { probe.observe(knees, 1_001L + it, 1_001L + it) }
        assertFalse(probe.finishMeasurement(11_000))
        val summary = fields(probe.summaries()[0])
        assertEquals("200", summary["samples"])
        assertEquals("true", summary["capped"])
        val before = probe.summaries()
        probe.close()
        probe.observe(knees, 12_000, 12_000)
        assertEquals(before, probe.summaries())
        assertFalse(probe.isSettled(12_000))
        assertEquals("CalibrationHandPlacementProbe(redacted)", probe.toString())
        assertEquals(setOf("phase", "samples", "eligible", "unavailable", "unsettled", "discarded", "capped",
            "leftMin", "leftP50Upper", "leftP95Upper", "leftMax", "leftWithin025",
            "rightMin", "rightP50Upper", "rightP95Upper", "rightMax", "rightWithin025"), summary.keys)
    }

    @Test
    fun histogramQuantilesAndCutoffCountsUseOnlyEligibleSamplesAndKeepSidesSeparate() {
        val probe = measuring()
        for (now in 1_100L..10_900L step 100) {
            val leftDistance = when {
                now < 6_100 -> 0.2
                now < 10_100 -> 0.3
                else -> 0.4
            }
            // Controlled scalar inputs test aggregation independently of the geometry estimator.
            val arms = ArmPoseFeedback(ArmJoint.entries.associateWith {
                ArmJointOffset(when (it) {
                    ArmJoint.LEFT_WRIST -> leftDistance
                    ArmJoint.RIGHT_WRIST -> 0.1
                    else -> 0.0
                }, 0.0)
            })
            probe.observe(AlignmentFeedback(knees.cue, knees.observedBounds, knees.imageSize,
                arms, knees.motionSample), now, now)
        }
        assertTrue(probe.finishMeasurement(11_000))
        val summary = fields(probe.summaries()[0])
        assertEquals("89", summary["eligible"])
        assertEquals("0.2000", summary["leftMin"])
        assertEquals("0.3000", summary["leftP50Upper"])
        assertEquals("0.4000", summary["leftP95Upper"])
        assertEquals("0.4000", summary["leftMax"])
        assertEquals("40", summary["leftWithin025"])
        assertEquals("0.1000", summary["rightP95Upper"])
        assertEquals("89", summary["rightWithin025"])
    }

    @Test
    fun aPauseAfterSpeechStillRequiresNewStillnessBeforeMeasurement() {
        val probe = CalibrationHandPlacementProbe()
        probe.prepare(HandPlacementPhase.KNEES, 0)
        for (now in 0L..1_000L step 100) probe.observe(knees, now, now)
        assertTrue(probe.isSettled(1_000))
        probe.restartSettling(2_000)
        assertFalse(probe.isSettled(2_000))
        assertThrows(IllegalStateException::class.java) { probe.beginMeasurement(2_000) }
        for (now in 2_000L..3_000L step 100) probe.observe(knees, now, now)
        probe.beginMeasurement(3_000)
        assertThrows(IllegalStateException::class.java) { probe.finishMeasurement(12_999) }
    }

    private fun measuring() = CalibrationHandPlacementProbe().also { probe ->
        probe.prepare(HandPlacementPhase.KNEES, 0)
        for (now in 0L..1_000L step 100) probe.observe(knees, now, now)
        probe.beginMeasurement(1_000)
    }

    private inner class Run(val feedback: ((Long, Boolean) -> AlignmentFeedback)? = null) {
        val probe = CalibrationHandPlacementProbe()
        var now = 0L
        val words = mutableListOf<Pair<Long, String>>()
        val flashes = mutableListOf<Long>()
        var inLap = false
        var failMeasurementSpeech = false

        private fun advance(ms: Long) {
            repeat((ms / 100).toInt()) {
                now += 100
                probe.observe(feedback?.invoke(now, inLap) ?: if (inLap) lap else knees, now, now)
            }
        }

        fun run() = CalibrationHandPlacementComparisonProtocol(
            probe, { now }, ::advance,
            sayAndAwait = { text ->
                words += now to text
                if (text == HandPlacementPhase.LAP.instruction) inLap = true
                check(!failMeasurementSpeech || !text.startsWith("Keep that position"))
                advance(2_000)
            },
            blink = { advance(300); flashes += now },
            ensureHealthy = {},
        ).run()
    }

    private fun hands(inLap: Boolean): AlignmentFeedback = guide.evaluate(PoseObservation(reference.landmarks.map { point ->
        val target = when (point.type) {
            PoseLandmark.LEFT_WRIST -> if (inLap) PoseLandmark.LEFT_HIP else PoseLandmark.LEFT_KNEE
            PoseLandmark.RIGHT_WRIST -> if (inLap) PoseLandmark.RIGHT_HIP else PoseLandmark.RIGHT_KNEE
            else -> null
        }?.let { type -> reference.landmarks.first { it.type == type } }
        if (target == null) point else point.copy(x = target.x, y = target.y)
    }, 0, 1, reference.imageSize))

    private fun shifted(now: Long) = guide.evaluate(PoseObservation(reference.landmarks.map {
        it.copy(y = it.y - if (now % 200 == 0L) 0.04 else 0.0)
    }, 0, 1, reference.imageSize))

    private fun fields(summary: String) = summary.split(" ").associate {
        val (key, value) = it.split("="); key to value
    }
}
