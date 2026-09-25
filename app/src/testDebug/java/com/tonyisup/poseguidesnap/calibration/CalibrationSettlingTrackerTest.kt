package com.tonyisup.poseguidesnap.calibration

import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import org.junit.Assert.*
import org.junit.Test

class CalibrationSettlingTrackerTest {
    private val reference = BundledMeditationReference.observation
    private val guide = CalibrationAlignmentGuide()

    @Test
    fun cumulativeSlowDriftCannotHideBehindSmallFrameToFrameSteps() {
        val tracker = CalibrationSettlingTracker()
        for (now in 0L..1_900L step 100) {
            tracker.record(sample(dy = -now / 100 * 0.004), now)
            assertFalse(tracker.isSettled(now))
        }
        for (now in 2_000L..3_000L step 100) tracker.record(sample(dy = -0.08), now)
        assertTrue(tracker.isSettled(3_000))
    }

    @Test
    fun smallJitterIsAllowedButStaleMissingAndBackwardEvidenceResetStillness() {
        val tracker = CalibrationSettlingTracker()
        for (now in 0L..1_000L step 100) {
            tracker.record(sample(dy = if (now % 200 == 0L) 0.002 else -0.002), now)
        }
        assertTrue(tracker.isSettled(1_000))
        assertFalse(tracker.isSettled(1_751))
        tracker.record(sample(), 1_800)
        assertFalse(tracker.isSettled(1_800))
        for (now in 1_900L..2_800L step 100) tracker.record(sample(), now)
        assertTrue(tracker.isSettled(2_800))
        tracker.record(null, 2_900)
        tracker.record(sample(), 3_000)
        assertFalse(tracker.isSettled(3_000))
        tracker.record(sample(), 2_999)
        assertFalse(tracker.isSettled(3_000))
    }

    @Test
    fun translationAndScaleRemainMotionEvenWhenNormalizedArmPoseDoesNotChange() {
        for (moved in listOf(sample(dy = -0.04), sample(scale = 0.9))) {
            val tracker = CalibrationSettlingTracker()
            for (now in 0L..1_000L step 100) tracker.record(sample(), now)
            assertTrue(tracker.isSettled(1_000))
            tracker.record(moved, 1_100)
            assertFalse(tracker.isSettled(1_100))
        }
    }

    @Test
    fun transientMotionGeometryIsRedactedAndClearedOnReset() {
        val tracker = CalibrationSettlingTracker()
        val sample = sample()
        assertEquals("CalibrationMotionSample(redacted)", sample.toString())
        for (now in 0L..1_000L step 100) tracker.record(sample, now)
        tracker.reset()
        assertFalse(tracker.isSettled(1_000))
        assertEquals("CalibrationSettlingTracker(redacted)", tracker.toString())
    }

    private fun sample(dy: Double = 0.0, scale: Double = 1.0): CalibrationMotionSample =
        checkNotNull(guide.evaluate(PoseObservation(reference.landmarks.map {
            it.copy(x = 0.5 + (it.x - 0.5) * scale, y = 0.5 + (it.y - 0.5) * scale + dy)
        }, 0, 1, reference.imageSize)).motionSample)
}
