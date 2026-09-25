package com.tonyisup.poseguidesnap.calibration

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.MainActivity
import com.tonyisup.poseguidesnap.R
import com.tonyisup.poseguidesnap.camera.CameraControllerStatus
import com.tonyisup.poseguidesnap.camera.CameraXController
import com.tonyisup.poseguidesnap.domain.match.FramingPolicy
import com.tonyisup.poseguidesnap.domain.match.PoseFramingEvaluator
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetMappingPolicy
import com.tonyisup.poseguidesnap.ui.BundledReferenceMatchEvidence
import com.tonyisup.poseguidesnap.ui.BundledMeditationReference
import com.tonyisup.poseguidesnap.ui.MirrorSelection
import com.tonyisup.poseguidesnap.ui.ReferenceMatchStatus
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly selected Pixel collector for one labeled pose sequence against the bundled public
 * reference. It persists only the closed scalar report and never retains an image or landmark.
 */
@RunWith(AndroidJUnit4::class)
class MatchCalibrationCollectionAndroidTest {
    @Test
    fun collectOneAuthorizedSpokenGuidedSequence() {
        val request = requestFromArguments()
        require(request.fixtureClass == CalibrationFixtureClass.POSITIVE)
        require(request.warmupMs == 60_000) { "Spoken guidance requires 60 seconds for adjustment and follow-up" }
        collect(
            request = request,
            requireAcceptedPersonPreflight = true,
            includeFramingDiagnostics = true,
            includeAlignmentGuide = true,
            includeSpokenGuidance = true,
        )
    }

    @Test
    fun collectOneAuthorizedGuidedFramingSequence() {
        val request = requestFromArguments()
        require(request.fixtureClass == CalibrationFixtureClass.POSITIVE) {
            "Alignment guidance is only for an intentional reference-match positive"
        }
        collect(
            request = request,
            requireAcceptedPersonPreflight = true,
            includeFramingDiagnostics = true,
            includeAlignmentGuide = true,
        )
    }

    @Test
    fun collectOneAuthorizedFramingDiagnosticSequence() {
        collect(
            request = requestFromArguments(),
            requireAcceptedPersonPreflight = true,
            includeFramingDiagnostics = true,
        )
    }

    @Test
    fun collectOneAuthorizedDerivedSequence() {
        collect(
            request = requestFromArguments(),
            requireAcceptedPersonPreflight = true,
        )
    }

    @Test
    fun collectOneAuthorizedDetectorGateSequence() {
        val request = requestFromArguments()
        CalibrationCollectionRequest.requireDetectorGateGroundTruth(request)
        collect(
            request = request,
            requireAcceptedPersonPreflight = false,
        )
    }

    private fun requestFromArguments(): CalibrationCollectionRequest {
        val arguments = InstrumentationRegistry.getArguments()
        return CalibrationCollectionRequest.fromRaw(
            authorization = arguments.getString(ARG_AUTHORIZATION),
            datasetId = arguments.getString(ARG_DATASET_ID),
            sequenceId = arguments.getString(ARG_SEQUENCE_ID),
            fixtureClass = arguments.getString(ARG_FIXTURE_CLASS),
            caseClass = arguments.getString(ARG_CASE_CLASS),
            warmupMs = arguments.getString(ARG_WARMUP_MS),
            durationMs = arguments.getString(ARG_DURATION_MS),
        )
    }

    private fun collect(
        request: CalibrationCollectionRequest,
        requireAcceptedPersonPreflight: Boolean,
        includeFramingDiagnostics: Boolean = false,
        includeAlignmentGuide: Boolean = false,
        includeSpokenGuidance: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Pixel 6", Build.MODEL)
        assertEquals("oriole", Build.DEVICE)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA),
        )

        val outputDirectory = context.noBackupFilesDir.resolve(OUTPUT_DIRECTORY)
        val outputFile = outputDirectory.resolve(CalibrationCollectionRequest.OUTPUT_FILENAME)
        val temporaryFile = outputDirectory.resolve(TEMPORARY_FILENAME)
        prepareEmptyOutput(outputDirectory, outputFile, temporaryFile)

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val screen = createScreen(activity, includeAlignmentGuide)
        val ready = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val controllerStatus = AtomicReference(CameraControllerStatus.IDLE)
        val runtimeFailureEvidence = AtomicReference<String?>(null)
        val previewReadiness = AtomicReference<CalibrationPreviewReadiness?>(null)
        val preflightLock = Any()
        var preflightActive = false
        var consecutiveOnePersonFrames = 0
        var maximumConsecutiveOnePersonFrames = 0
        var preflightAnalyzedFrames = 0
        var preflightNoPersonFrames = 0
        var preflightOnePersonFrames = 0
        var preflightMultiplePeopleFrames = 0
        var preflightMaximumValidPersonScore: Double? = null
        var preflightMaximumValidKeypointScore: Double? = null
        var preflightMinimumMeanLuminance: Double? = null
        var preflightMaximumMeanLuminance: Double? = null
        var preflightMaximumLuminanceRange: Double? = null
        val recording = AtomicBoolean(false)
        val warmupDeadlineMs = AtomicLong(Long.MAX_VALUE)
        val guideGeometryValid = AtomicBoolean(!includeAlignmentGuide)
        val bodyReadiness = GuidedBodyReadiness()
        val speech = AtomicReference<CalibrationSpeechOutput?>(null)
        val startedAtNanos = AtomicLong(Long.MAX_VALUE)
        val frames = ArrayList<DerivedCalibrationFrame>()
        val frameLock = Any()
        val framingDiagnostics = if (includeFramingDiagnostics) CalibrationFramingDiagnostics() else null
        val framingEvaluator = if (includeFramingDiagnostics) PoseFramingEvaluator() else null
        var completed = false
        var announcedEnd = false
        val controller = CameraXController.create(
            context = context.applicationContext,
            onFrame = { analyzed ->
                val detectedPersonCount = analyzed.poseObservation.detectedPersonCount
                val preflightFeedback = synchronized(preflightLock) {
                    if (preflightActive) {
                        preflightAnalyzedFrames += 1
                        analyzed.maximumValidPersonScore?.let { score ->
                            preflightMaximumValidPersonScore = maxOf(
                                preflightMaximumValidPersonScore ?: score,
                                score,
                            )
                        }
                        analyzed.maximumValidKeypointScore?.let { score ->
                            preflightMaximumValidKeypointScore = maxOf(
                                preflightMaximumValidKeypointScore ?: score,
                                score,
                            )
                        }
                        val visualStatistics = analyzed.visualStatistics
                        preflightMinimumMeanLuminance = minOf(
                            preflightMinimumMeanLuminance ?: visualStatistics.meanLuminance,
                            visualStatistics.meanLuminance,
                        )
                        preflightMaximumMeanLuminance = maxOf(
                            preflightMaximumMeanLuminance ?: visualStatistics.meanLuminance,
                            visualStatistics.meanLuminance,
                        )
                        preflightMaximumLuminanceRange = maxOf(
                            preflightMaximumLuminanceRange ?: visualStatistics.luminanceRange,
                            visualStatistics.luminanceRange,
                        )
                        when (detectedPersonCount) {
                            0 -> {
                                preflightNoPersonFrames += 1
                                consecutiveOnePersonFrames = 0
                                "No person detected. Step back and keep your whole body visible."
                            }
                            1 -> {
                                preflightOnePersonFrames += 1
                                consecutiveOnePersonFrames += 1
                                maximumConsecutiveOnePersonFrames = maxOf(
                                    maximumConsecutiveOnePersonFrames,
                                    consecutiveOnePersonFrames,
                                )
                                "One person detected: " +
                                    "$consecutiveOnePersonFrames/" +
                                    "$MIN_CONSECUTIVE_ONE_PERSON_WARMUP_FRAMES. Hold still."
                            }
                            else -> {
                                preflightMultiplePeopleFrames += 1
                                consecutiveOnePersonFrames = 0
                                "Multiple people detected. Keep only one person in view."
                            }
                        }
                    } else {
                        null
                    }
                }
                if (preflightFeedback != null) {
                    if (screen.guide != null) {
                        guideGeometryValid.set(screen.guide.update(
                            analyzed.poseObservation,
                            ((warmupDeadlineMs.get() - SystemClock.elapsedRealtime() + 999L) / 1000L).toInt(),
                        ))
                        if (includeSpokenGuidance) {
                            val feedback = screen.guide.feedback
                            val fullBody = feedback.observedBounds != null &&
                                BundledReferenceMatchEvidence.evaluate(analyzed.poseObservation).status ==
                                ReferenceMatchStatus.EVALUATED
                            val now = SystemClock.elapsedRealtime()
                            bodyReadiness.record(fullBody, now)
                            speech.get()?.offer(feedback, now)
                        }
                    } else {
                        screen.status.text = preflightFeedback
                    }
                }
                if (!recording.get()) return@create
                val elapsedNanos = analyzed.sourceMonotonicTimestampNanos - startedAtNanos.get()
                if (elapsedNanos < 0L) return@create
                val elapsedMs = (elapsedNanos / NANOS_PER_MILLISECOND).toInt()
                if (elapsedMs >= request.durationMs) return@create
                val evidence = BundledReferenceMatchEvidence.evaluate(analyzed.poseObservation)
                val evaluationStatus = when (evidence.status) {
                    ReferenceMatchStatus.NO_PERSON -> DerivedCalibrationEvaluationStatus.NO_PERSON
                    ReferenceMatchStatus.MULTIPLE_PEOPLE ->
                        DerivedCalibrationEvaluationStatus.MULTIPLE_PEOPLE
                    ReferenceMatchStatus.CANONICALIZATION_FAILED ->
                        DerivedCalibrationEvaluationStatus.CANONICALIZATION_FAILED
                    ReferenceMatchStatus.EVALUATED -> DerivedCalibrationEvaluationStatus.EVALUATED
                    ReferenceMatchStatus.WAITING_FOR_REFERENCE,
                    ReferenceMatchStatus.WAITING_FOR_FRAME -> {
                        failed.set(true)
                        return@create
                    }
                }
                val qualifiedLandmarks = analyzed.poseObservation.landmarks.filter { landmark ->
                    landmark.type in MOVENET_LANDMARKS &&
                        minOf(landmark.visibility, landmark.presence) >= MINIMUM_LANDMARK_CONFIDENCE
                }
                val derived = DerivedCalibrationFrame(
                    elapsedMs = elapsedMs,
                    detectedPersonCount = detectedPersonCount,
                    evaluationStatus = evaluationStatus,
                    confidenceQualifiedLandmarkCount = qualifiedLandmarks.size,
                    qualifiedTorsoAnchorCount = qualifiedLandmarks.count { landmark ->
                        landmark.type in TORSO_ANCHORS
                    },
                    maximumValidPersonScore = analyzed.maximumValidPersonScore,
                    maximumValidKeypointScore = analyzed.maximumValidKeypointScore,
                    landmarkCoverage = evidence.coverage.score ?: 0.0,
                    framingScore = evidence.framing.score ?: 0.0,
                    angularSimilarity = evidence.angular.score ?: 0.0,
                    positionalSimilarity = evidence.positional.score ?: 0.0,
                    overallMatch = evidence.overall.score ?: 0.0,
                    mirrorUsed = evidence.selectedMirror == MirrorSelection.MIRRORED,
                    inferenceLatencyMs = null,
                    cueEmitted = null,
                    captureCommands = 0,
                )
                synchronized(frameLock) {
                    val lastElapsedMs = frames.lastOrNull()?.elapsedMs
                    if (
                        frames.size < DerivedCalibrationSequence.MAX_FRAMES &&
                        (lastElapsedMs == null || derived.elapsedMs > lastElapsedMs)
                    ) {
                        if (framingDiagnostics != null && framingEvaluator != null) {
                            framingDiagnostics.record(
                                framingEvaluator.evaluate(
                                    BundledMeditationReference.observation,
                                    analyzed.poseObservation,
                                ),
                            )
                        }
                        frames += derived
                    }
                }
            },
            onState = { state ->
                controllerStatus.set(state.status)
                if (state.status == CameraControllerStatus.READY) ready.countDown()
                if (state.status == CameraControllerStatus.FAILED) {
                    failed.set(true)
                }
            },
            onFailure = { failure ->
                runtimeFailureEvidence.set(
                    safeRuntimeFailureEvidence(
                        controllerStatus = controllerStatus.get(),
                        failure = failure,
                    ),
                )
                failed.set(true)
                ready.countDown()
            },
        )

        try {
            instrumentation.runOnMainSync {
                previewReadiness.set(CalibrationPreviewReadiness(screen.preview) { viewPort, rotation ->
                    controller.bind(
                        lifecycleOwner = activity,
                        surfaceProvider = screen.preview.surfaceProvider,
                        viewPort = viewPort,
                        targetRotation = rotation,
                    )
                })
                activity.setContentView(screen.root)
            }
            assertTrue("Rear camera did not become ready", ready.await(30, TimeUnit.SECONDS))
            assertFalse(
                "Rear camera or pose analysis failed; " +
                    (runtimeFailureEvidence.get() ?: "failureStage=unknown failureTypes=unknown"),
                failed.get(),
            )

            if (includeSpokenGuidance) {
                instrumentation.runOnMainSync { speech.set(CalibrationSpeechOutput(activity)) }
                checkNotNull(speech.get()).initialize()
            }

            instrumentation.runOnMainSync {
                screen.status.text = if (requireAcceptedPersonPreflight) {
                    "Stand in the rear-camera view until one person is detected."
                } else {
                    when (request.fixtureClass) {
                        CalibrationFixtureClass.POSITIVE ->
                            "Keep exactly one full-body person in the rear-camera view."
                        CalibrationFixtureClass.NEGATIVE ->
                            "Keep the rear-camera view empty."
                    }
                }
            }
            synchronized(preflightLock) {
                consecutiveOnePersonFrames = 0
                maximumConsecutiveOnePersonFrames = 0
                preflightAnalyzedFrames = 0
                preflightNoPersonFrames = 0
                preflightOnePersonFrames = 0
                preflightMultiplePeopleFrames = 0
                preflightMaximumValidPersonScore = null
                preflightMaximumValidKeypointScore = null
                preflightMinimumMeanLuminance = null
                preflightMaximumMeanLuminance = null
                preflightMaximumLuminanceRange = null
            }
            val warmupCue = CalibrationWarmupCue(
                setTorchEnabled = { enabled ->
                    controller.setTorchEnabled(enabled).get(5, TimeUnit.SECONDS)
                },
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                sleepMs = SystemClock::sleep,
            )
            val onWarmupStarted: (Long) -> Unit = { startedAt ->
                warmupDeadlineMs.set(startedAt + request.warmupMs)
                speech.get()?.startWarmup(request.warmupMs / 1000, warmupDeadlineMs.get())
                synchronized(preflightLock) { preflightActive = true }
            }
            if (includeSpokenGuidance) {
                warmupCue.blink(onWarmupStarted)
                checkNotNull(speech.get()).awaitReadyToHold {
                    speech.get()?.sayAndAwait(CalibrationPreparationGate.TIMEOUT_MESSAGE)
                    announcedEnd = true
                }
            } else {
                warmupCue.run(request.warmupMs, onWarmupStarted)
            }
            val preflightSummary = synchronized(preflightLock) {
                preflightActive = false
                CalibrationPreflightSummary(
                    analyzedFrames = preflightAnalyzedFrames,
                    noPersonFrames = preflightNoPersonFrames,
                    onePersonFrames = preflightOnePersonFrames,
                    multiplePeopleFrames = preflightMultiplePeopleFrames,
                    finalConsecutiveOnePersonFrames = consecutiveOnePersonFrames,
                    maximumConsecutiveOnePersonFrames = maximumConsecutiveOnePersonFrames,
                    maximumValidPersonScore = preflightMaximumValidPersonScore,
                    maximumValidKeypointScore = preflightMaximumValidKeypointScore,
                    minimumMeanLuminance = preflightMinimumMeanLuminance,
                    maximumMeanLuminance = preflightMaximumMeanLuminance,
                    maximumLuminanceRange = preflightMaximumLuminanceRange,
                )
            }
            instrumentation.runOnMainSync {
                speech.get()?.stopGuidance()
                if (includeSpokenGuidance) screen.guide?.finishWarmup()
            }
            if (includeSpokenGuidance && !bodyReadiness.isReady(SystemClock.elapsedRealtime())) {
                speech.get()?.sayAndAwait("I could not see your whole body clearly. Test stopped. You can relax.")
                announcedEnd = true
                error("Spoken guided warm-up ended without five fresh, fully evaluated body frames")
            }
            assertFalse(
                "Rear camera or pose analysis failed; " +
                    (runtimeFailureEvidence.get() ?: "failureStage=unknown failureTypes=unknown"),
                failed.get(),
            )
            if (requireAcceptedPersonPreflight) {
                assertTrue("Guided preview and analysis crop must match the reference aspect", guideGeometryValid.get())
                assertTrue(
                    "Warm-up ended without $MIN_CONSECUTIVE_ONE_PERSON_WARMUP_FRAMES " +
                        "consecutive exactly-one-person frames; " +
                        preflightSummary.failureEvidence(),
                    preflightSummary.finalConsecutiveOnePersonFrames >=
                        MIN_CONSECUTIVE_ONE_PERSON_WARMUP_FRAMES,
                )
            } else {
                assertTrue(
                    "Detector-gate warm-up produced too few analyzed frames; " +
                        preflightSummary.failureEvidence(),
                    preflightSummary.analyzedFrames >= CalibrationCollectionRequest.MIN_RECORDED_FRAMES,
                )
            }
            instrumentation.runOnMainSync {
                screen.guide?.finishWarmup()
                screen.status.text = if (requireAcceptedPersonPreflight) {
                    when (request.fixtureClass) {
                        CalibrationFixtureClass.POSITIVE -> "Hold the meditation pose."
                        CalibrationFixtureClass.NEGATIVE -> "Hold the planned different position."
                    }
                } else {
                    when (request.fixtureClass) {
                        CalibrationFixtureClass.POSITIVE -> "Hold the full-body position."
                        CalibrationFixtureClass.NEGATIVE -> "Keep the camera view empty."
                    }
                }
            }
            speech.get()?.sayAndAwait("Hold still for ${request.durationMs / 1000} seconds.")
            startedAtNanos.set(SystemClock.elapsedRealtimeNanos())
            recording.set(true)
            SystemClock.sleep(request.durationMs.toLong())
            recording.set(false)
            speech.get()?.sayAndAwait("Test finished. You can relax.")
            announcedEnd = includeSpokenGuidance
            assertFalse(
                "Rear camera or pose analysis failed; " +
                    (runtimeFailureEvidence.get() ?: "failureStage=unknown failureTypes=unknown"),
                failed.get(),
            )

            val (snapshot, framingSummary) = synchronized(frameLock) {
                frames.toList() to framingDiagnostics?.summary()
            }
            assertTrue(
                "Too few analyzed frames for the bounded sequence",
                snapshot.size >= CalibrationCollectionRequest.MIN_RECORDED_FRAMES,
            )
            val document = DerivedCalibrationSequence.create(
                datasetId = request.datasetId,
                sequenceId = request.sequenceId,
                fixtureClass = request.fixtureClass,
                caseClass = request.caseClass,
                frames = snapshot,
            )
            val digest = writeReport(temporaryFile, outputFile, document.toJson())
            completed = true
            if (framingSummary != null) {
                instrumentation.sendStatus(
                    2,
                    Bundle().apply { putString("stream", "calibration framing $framingSummary") },
                )
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "calibration report " +
                            "file=${CalibrationCollectionRequest.OUTPUT_FILENAME} " +
                            "sha256=$digest frames=${snapshot.size} " +
                            "dataset=${request.datasetId} sequence=${request.sequenceId}",
                    )
                },
            )
        } catch (failure: Throwable) {
            if (includeSpokenGuidance && !announcedEnd) {
                runCatching { speech.get()?.sayAndAwait("Test stopped. You can relax.") }
            }
            throw failure
        } finally {
            synchronized(preflightLock) {
                preflightActive = false
            }
            recording.set(false)
            instrumentation.runOnMainSync {
                previewReadiness.get()?.close()
                screen.guide?.close()
                try { controller.close() } finally {
                    try { speech.get()?.close() } finally { activity.finish() }
                }
            }
            speech.get()?.let { output ->
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "calibration completed cues ${output.completedCueSummary()}")
                })
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "calibration arm confirmation ${output.armConfirmationSummary()}")
                })
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "calibration preparation ${output.preparationSummary()}")
                })
            }
            temporaryFile.delete()
            if (!completed) outputFile.delete()
        }
    }

    private fun prepareEmptyOutput(directory: File, output: File, temporary: File) {
        check(directory.isDirectory || directory.mkdirs())
        check(!output.exists() || output.delete())
        check(!temporary.exists() || temporary.delete())
    }

    private fun createScreen(activity: MainActivity, includeAlignmentGuide: Boolean): CalibrationScreen {
        lateinit var screen: CalibrationScreen
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (includeAlignmentGuide) {
                val guide = CalibrationGuideScreen(activity)
                screen = CalibrationScreen(guide.root, guide.preview, guide.status, guide)
                return@runOnMainSync
            }
            val reference = ImageView(activity).apply {
                setImageResource(R.drawable.meditation_pose)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                contentDescription = "Bundled public meditation pose"
            }
            val status = TextView(activity).apply {
                text = "Get ready. Keep your whole body in the rear-camera view."
                textSize = 18f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.DKGRAY)
                setPadding(24, 16, 24, 16)
            }
            val preview = PreviewView(activity).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val overlay = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                addView(
                    reference,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        activity.resources.displayMetrics.heightPixels / 4,
                    ),
                )
                addView(
                    status,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            val root = FrameLayout(activity).apply {
                addView(
                    preview,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    overlay,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            screen = CalibrationScreen(root, preview, status)
        }
        return screen
    }

    private fun writeReport(temporary: File, output: File, json: String): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        check(temporary.renameTo(output))
        check(output.length() == bytes.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256")
        output.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class CalibrationScreen(
        val root: FrameLayout,
        val preview: PreviewView,
        val status: TextView,
        val guide: CalibrationGuideScreen? = null,
    )

    private data class CalibrationPreflightSummary(
        val analyzedFrames: Int,
        val noPersonFrames: Int,
        val onePersonFrames: Int,
        val multiplePeopleFrames: Int,
        val finalConsecutiveOnePersonFrames: Int,
        val maximumConsecutiveOnePersonFrames: Int,
        val maximumValidPersonScore: Double?,
        val maximumValidKeypointScore: Double?,
        val minimumMeanLuminance: Double?,
        val maximumMeanLuminance: Double?,
        val maximumLuminanceRange: Double?,
    ) {
        fun failureEvidence(): String =
            "analyzedFrames=$analyzedFrames " +
                "noPersonFrames=$noPersonFrames " +
                "onePersonFrames=$onePersonFrames " +
                "multiplePeopleFrames=$multiplePeopleFrames " +
                "finalConsecutiveOnePersonFrames=$finalConsecutiveOnePersonFrames " +
                "maximumConsecutiveOnePersonFrames=$maximumConsecutiveOnePersonFrames " +
                "maximumValidPersonScore=${maximumValidPersonScore ?: "unavailable"} " +
                "maximumValidKeypointScore=${maximumValidKeypointScore ?: "unavailable"} " +
                "minimumMeanLuminance=${minimumMeanLuminance ?: "unavailable"} " +
                "maximumMeanLuminance=${maximumMeanLuminance ?: "unavailable"} " +
                "maximumLuminanceRange=${maximumLuminanceRange ?: "unavailable"} " +
                "minimumAcceptedPersonScore=" +
                MoveNetMappingPolicy.DEFAULT_MINIMUM_PERSON_SCORE
    }

    private companion object {
        fun safeRuntimeFailureEvidence(
            controllerStatus: CameraControllerStatus,
            failure: Throwable,
        ): String {
            val failureStage = when (controllerStatus) {
                CameraControllerStatus.FAILED -> "binding"
                CameraControllerStatus.READY -> "analysis"
                else -> "startup"
            }
            val failureTypes = generateSequence(failure) { current -> current.cause }
                .take(MAX_FAILURE_CAUSE_DEPTH)
                .map { current -> current.javaClass.simpleName.ifEmpty { "Throwable" } }
                .joinToString(">")
            return "failureStage=$failureStage failureTypes=$failureTypes"
        }

        const val ARG_AUTHORIZATION = "calibrationAuthorization"
        const val ARG_DATASET_ID = "datasetId"
        const val ARG_SEQUENCE_ID = "sequenceId"
        const val ARG_FIXTURE_CLASS = "fixtureClass"
        const val ARG_CASE_CLASS = "caseClass"
        const val ARG_WARMUP_MS = "warmupMs"
        const val ARG_DURATION_MS = "durationMs"
        const val OUTPUT_DIRECTORY = "calibration-export"
        const val TEMPORARY_FILENAME = ".calibration-sequence-v3.tmp"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MIN_CONSECUTIVE_ONE_PERSON_WARMUP_FRAMES = 5
        const val MAX_FAILURE_CAUSE_DEPTH = 4
        val MINIMUM_LANDMARK_CONFIDENCE =
            FramingPolicy.developmentDefaults().minimumLandmarkConfidence
        val MOVENET_LANDMARKS = setOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_EYE,
            PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_EAR,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE,
        )
        val TORSO_ANCHORS = setOf(
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
        )
    }
}
