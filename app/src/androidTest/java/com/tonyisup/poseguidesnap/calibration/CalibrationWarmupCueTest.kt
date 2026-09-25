package com.tonyisup.poseguidesnap.calibration

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic timing and failure checks; no camera or device data is accessed. */
@RunWith(AndroidJUnit4::class)
class CalibrationWarmupCueTest {
    @Test
    fun spokenBlinkReturnsForLiveReadinessWithoutSleepingThroughPreparation() {
        var now = 0L
        val requests = mutableListOf<Boolean>()
        var startedAt: Long? = null
        val cue = CalibrationWarmupCue(
            setTorchEnabled = { requests += it },
            elapsedRealtimeMs = { now },
            sleepMs = { now += it },
        )
        assertEquals(0L, cue.blink { startedAt = it })
        assertEquals(0L, startedAt)
        assertEquals(250L, now)
        assertEquals(listOf(true, false), requests)
    }

    @Test
    fun blinkStartsWarmupAndOffAcknowledgementDoesNotExtendItsDeadline() {
        var now = 0L
        var torchOn = false
        val events = mutableListOf<String>()
        val cue = CalibrationWarmupCue(
            setTorchEnabled = { enabled ->
                events += "torch=$enabled at=$now"
                now += if (enabled) 40 else 80
                torchOn = enabled
            },
            elapsedRealtimeMs = { now },
            sleepMs = { duration -> now += duration },
        )

        cue.run(60_000) { events += "warmup at=$now" }

        assertEquals(listOf("torch=true at=0", "warmup at=40", "torch=false at=290"), events)
        assertEquals(60_040L, now)
        assertFalse(torchOn)
    }

    @Test
    fun failedEnableStillRequestsOffAndNeverStartsWarmup() {
        val requests = mutableListOf<Boolean>()
        val cue = CalibrationWarmupCue(
            setTorchEnabled = { enabled ->
                requests += enabled
                if (enabled) error("enable failed")
            },
            elapsedRealtimeMs = { 0L },
            sleepMs = { error("must not wait") },
        )

        assertThrows(IllegalStateException::class.java) {
            cue.run(15_000) { error("must not start") }
        }
        assertEquals(listOf(true, false), requests)
    }

    @Test
    fun interruptedBlinkRequestsOffBeforePropagatingFailure() {
        val requests = mutableListOf<Boolean>()
        val cue = CalibrationWarmupCue(
            setTorchEnabled = { requests += it },
            elapsedRealtimeMs = { 0L },
            sleepMs = { throw InterruptedException("interrupted") },
        )

        assertThrows(InterruptedException::class.java) { cue.run(15_000) {} }
        assertEquals(listOf(true, false), requests)
    }

    @Test
    fun failedOffAcknowledgementAbortsBeforeTheRemainingWarmup() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val cue = CalibrationWarmupCue(
            setTorchEnabled = { if (!it) error("off failed") },
            elapsedRealtimeMs = { now },
            sleepMs = { waits += it; now += it },
        )

        assertThrows(IllegalStateException::class.java) { cue.run(15_000) {} }
        assertEquals(listOf(250L), waits)
    }

    @Test
    fun invalidDurationCannotTurnTheLightOn() {
        val requests = mutableListOf<Boolean>()
        val cue = CalibrationWarmupCue(
            setTorchEnabled = { requests += it },
            elapsedRealtimeMs = { 0L },
            sleepMs = { error("must not wait") },
        )

        assertThrows(IllegalArgumentException::class.java) { cue.run(0) {} }
        assertEquals(emptyList<Boolean>(), requests)
    }
}
