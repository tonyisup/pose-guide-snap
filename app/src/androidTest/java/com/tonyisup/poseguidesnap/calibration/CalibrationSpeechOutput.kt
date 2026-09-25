package com.tonyisup.poseguidesnap.calibration

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Bounded instrumentation-only speech. No recording, files, downloads or route/volume changes. */
internal class CalibrationSpeechOutput(context: Context) : AutoCloseable {
    private class Utterance(val id: String, val cue: SpokenAlignmentCue?) {
        val done = CountDownLatch(1)
        @Volatile var success = false
    }
    private val initialized = CountDownLatch(1)
    private val initStatus = AtomicInteger(TextToSpeech.ERROR)
    private val failed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val pending = AtomicReference<Utterance?>(null)
    private val sequence = AtomicInteger()
    private val lock = Any()
    private var ready = false
    private var guiding = false
    private var hasFocus = false
    private val guidance = CalibrationSpokenGuidance()
    private val completedCues = mutableMapOf<SpokenAlignmentCue, Int>()
    private var preparationSummary = "result=NOT_FINISHED blockers=unavailable"
    private val audio = context.getSystemService(AudioManager::class.java)
    private val engine = TextToSpeech(context) { status ->
        initStatus.set(status)
        initialized.countDown()
    }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0) {
                failed.set(true)
                engine.stop()
                finishPending(null, false)
            }
        }, Handler(Looper.getMainLooper()))
        .build()

    /** Call on the instrumentation thread with a foreground activity; never block the main thread. */
    fun initialize() {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(initialized.await(10, TimeUnit.SECONDS) && initStatus.get() == TextToSpeech.SUCCESS) {
            "Speech engine could not initialize"
        }
        val voice = engine.voices.orEmpty()
            .filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "US" }
                .thenByDescending { it.quality }.thenBy { it.name })
            .firstOrNull()
        check(voice != null) { "An installed offline English voice is required" }
        check(engine.setVoice(voice) == TextToSpeech.SUCCESS && engine.voice == voice)
        check(engine.setAudioAttributes(attributes) == TextToSpeech.SUCCESS)
        check(engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finishPending(utteranceId, true)
            @Deprecated("Required legacy callback")
            override fun onError(utteranceId: String?) {
                failed.set(true)
                finishPending(utteranceId, false)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                failed.set(true)
                finishPending(utteranceId, false)
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finishPending(utteranceId, false)
        }) == TextToSpeech.SUCCESS)
        check(!audio.isStreamMute(AudioManager.STREAM_MUSIC) && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
            "Media volume is muted; turn it up before the spoken test"
        }
        hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        check(hasFocus) { "Audio focus unavailable; spoken test cannot start" }
        synchronized(lock) { ready = true }
    }

    fun startWarmup(seconds: Int, deadlineMs: Long) = synchronized(lock) {
        guidance.start(deadlineMs, earliestHoldAtMs = deadlineMs - seconds * 1_000L + 30_000L)
        preparationSummary = "result=NOT_FINISHED blockers=unavailable"
        guiding = true
        enqueue("You have up to $seconds seconds to adjust. Face the rear camera. " +
            "Rest your hands on your knees, palms up, and let your arms relax.")
        Unit
    }

    fun offer(feedback: AlignmentFeedback, nowMs: Long) = synchronized(lock) {
        if (!guiding || closed.get()) return@synchronized
        ensureHealthy()
        val cue = guidance.next(feedback, nowMs, busy = pending.get() != null) ?: return@synchronized
        enqueue(cue.text, cue)
    }

    /** Bounded fixed-vocabulary counts, not a transcript or participant geometry. */
    fun completedCueSummary(): String = synchronized(lock) {
        SpokenAlignmentCue.entries.joinToString(" ") { "${it.name}=${completedCues[it] ?: 0}" }
    }

    fun armConfirmationSummary(): String = synchronized(lock) { guidance.armConfirmationSummary() }

    fun preparationSummary(): String = synchronized(lock) { preparationSummary }

    /** Continue live coaching until settled readiness; the hard deadline stops an unresolved run. */
    fun awaitReadyToHold(onTimeout: () -> Unit) {
        check(Looper.myLooper() != Looper.getMainLooper())
        CalibrationPreparationGate(
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            sleepMs = SystemClock::sleep,
            decision = { now -> synchronized(lock) {
                ensureHealthy()
                val status = guidance.preparationStatus(now)
                status.decision.also {
                    if (it != CalibrationPreparationDecision.WAITING) {
                        guiding = false
                        preparationSummary = status.summary() // Snapshot before the end announcement resets speech.
                    }
                }
            } },
        ).awaitReady(onTimeout)
    }

    fun stopGuidance() = synchronized(lock) { guiding = false }

    fun verifyAvailable() = synchronized(lock) { ensureHealthy() }

    fun sayAndAwait(text: String) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val utterance = synchronized(lock) {
            guiding = false
            enqueue(text)
        }
        check(utterance.done.await(10, TimeUnit.SECONDS) && utterance.success && !failed.get()) {
            "Spoken cue did not complete"
        }
    }

    private fun enqueue(text: String, cue: SpokenAlignmentCue? = null): Utterance {
        ensureHealthy()
        finishPending(null, false)
        val utterance = Utterance("calibration-${sequence.incrementAndGet()}", cue)
        pending.set(utterance)
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance.id) != TextToSpeech.SUCCESS) {
            failed.set(true)
            finishPending(utterance.id, false)
            error("Speech playback could not start")
        }
        return utterance
    }

    private fun ensureHealthy() {
        check(ready && !failed.get() && !closed.get()) { "Speech is unavailable" }
    }

    private fun finishPending(id: String?, success: Boolean) {
        synchronized(lock) {
            val utterance = pending.get() ?: return
            if (id != null && utterance.id != id) return
            if (pending.compareAndSet(utterance, null)) {
                if (success) {
                    guidance.speechCompleted(SystemClock.elapsedRealtime(), utterance.cue)
                    utterance.cue?.let { completedCues[it] = ((completedCues[it] ?: 0) + 1).coerceAtMost(600) }
                }
                else if (id != null) failed.set(true)
                utterance.success = success
                utterance.done.countDown()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            guiding = false
            guidance.clearMotionEvidence()
            finishPending(null, false)
            try { engine.stop() } finally {
                try { engine.shutdown() } finally {
                    if (hasFocus) audio.abandonAudioFocusRequest(focusRequest)
                }
            }
        }
    }
}
