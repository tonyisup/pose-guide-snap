# Task 16AA — movement-aware coaching and readiness before hold

The user chose to build movement-aware progression before running the pending Task 16Z test. That one-minute fixed preparation protocol was not run. This update supersedes it.

## Implemented behavior

The debug coach now checks motion separately from target-position error. A complete, qualified single-person observation provides a transient sample of the 17 reference landmarks. Coordinates are aspect-corrected but retain translation and scale; displacement is divided by the smaller current/anchor torso length. Every landmark must remain within **0.06 torso lengths** of the settling-window anchor for **1000 ms**. Comparing with the window anchor catches accumulated drift that small frame-to-frame comparisons would miss. This is an uncalibrated debug stillness heuristic, not proof of absolute physical stillness or detection of palm/depth movement.

Movement beyond that radius, missing evidence, non-forward time, or a frame gap over 750 ms restarts the settling window. Speech also clears it, so stillness must be observed after an instruction finishes. The tracker keeps only one transient baseline, has a redacted string representation, and clears it on reset/close. No images, trajectories or coordinates are exported or written to disk.

“Good” requires both confirmed stillness and the existing one-second acceptance of the requested adjustment. The joint-position radius remains **0.25 torso lengths**; another stable but incorrect joint does not prevent confirmation of the requested joint. Moving elsewhere in the observed body does prevent confirmation until the participant settles. No movement is demanded merely to prove a response: an already-correct position can be confirmed once fresh stillness is established.

A further directional correction requires settled evidence and the existing eight quiet seconds after speech completion. Recovery prompts for missing/multiple people or changed geometry remain possible when motion cannot be measured. The final-ten-second cutoff for new directions remains. “Your framing is aligned” is not repeatedly queued while already aligned.

Preparation has an initial **30-second minimum allowance** and a **60-second hard deadline**. After the flash, the spoken collector waits on live readiness rather than sleeping for the full minute. It can enter the ten-second measurement hold only when:

- The latest complete-body evidence is fresh and settled.
- All four coached arm joints are within their existing positional radius and framing is aligned.
- No requested correction is outstanding and speech has completed.
- Eight quiet seconds have elapsed since the latest completed utterance.
- The initial allowance has elapsed and the hard deadline has not.

This is coaching readiness, not a claim that every production match criterion passes. Production pose thresholds and automatic-capture status are unchanged. The existing full-body/geometry admission checks still run before measurement.

At the deadline, unresolved preparation speaks: “I could not confirm a settled position and the adjustments in time. Test stopped. You can relax.” The shared preparation gate then throws before the collector can announce hold or record measurement. Even a late Good cannot skip its quiet pause to beat the deadline. A timeout may take additional time to finish its stop announcement, but cannot authorize a hold or report. Solo phone-adjustment workflow redesign remains deferred.

## Verification

Before implementation, the real scheduler failed both new movement regressions:

```text
./gradlew :app:testDebugUnitTest --tests '*CalibrationMovementGuidanceTest' --offline
2 tests completed, 2 failed
Must not confirm a moving joint: expected null, but was GOOD
Eight seconds alone must not allow another direction: expected null, but was LEFT_WRIST_UP
```

Those regressions now pass. Twelve new host tests exercise moving within target tolerance, movement continuing beyond the pause timer, accumulated drift, tolerated jitter, missing/stale/backward evidence, global translation/scale, redaction/reset, early ready completion, unresolved-arm/framing timeout, late Good, tracking loss and movement near the handoff. The preparation tests use the same gate as the device speech adapter and assert that timeout never reaches the hold continuation. The synthetic movements are not reconstructions of the participant's private trajectory.

The JDK 17 offline gate (`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`) passed **766/766 tests**, zero failures/errors/skips. Main/test builds pass; lint reports zero errors and nine warnings. Existing joint/direction, target radius, mirror, speech cadence and scalar privacy checks remain green. One diagnostic test was updated to distinguish the new UNSETTLED state from a stable positional mismatch.

- Main APK SHA-256: `175974604166245348f6b9538a20a4a2828839787df7860ff4cc248b2105cc4e`.
- Test APK SHA-256: `56d8a3536925ad5a75c2b14469b3f06259efb6709de41cb97e55bd2a79428111`.

Both APKs were installed with data preserved and their installed hashes verified. Camera-free `CalibrationWarmupCueTest` passed **6/6 in 0.019 seconds**; see the [native timing output](2026-09-25-task16aa-timing-pixel6-output.txt). The new blink-only path returns after flash-off acknowledgement so live readiness can control the rest of preparation. The regular timed path remains for non-spoken collection. These tests use synthetic clock/torch callbacks and open no camera or voice session. Cleanup verified main/data preserved, test/export absent and no Pose Guide Snap camera client. Physical motion sensitivity, wording and successful confirmation remain pending a fresh Ready.

## Bounded diagnostics

The existing fixed cue counts and 600-sample arm diagnostics remain. `UNSETTLED` means the stillness window is not yet satisfied, including its initial accumulation; it is not a count of verified movement events. Eligible error extrema still include unsettled observations. `stableMaxMs` remains the duration of target-position acceptance, not motion stability. No new raw motion log was added. Interpret these fields with the new motion gate, not with earlier positional-only reports.

## First physical run completed

The Ready-authorized run completed GOOD=1 for the left wrist, then took the intended readiness-timeout stop path without hold or measurement. Cleanup is verified. The motion-aware confirmation and fail-closed stop were exercised on device; participant-perceived timing and broader sensitivity remain unconfirmed. See the [Pixel result](2026-09-25-task16aa-movement-guided-pixel6.md). The sequence below is consumed and must not be reused.

## Executed physical protocol

Wait for Ready. Check that the Pixel is awake/unlocked, exact installed main/test hashes, camera permission, absent export directory and unused output paths. Reinstall the test APK. Preserve the sideways rear-camera setup. Start with the left hand in the lap; follow the named joint/direction, then stop and retain the new position. The app should wait while movement continues and give Good or a further correction only after settling. It may stop with the explanation above if guidance cannot be resolved in time.

Use `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`, authorization `user-authorized-derived`, dataset `pixel6-movement-guided-a`, sequence `positive-movement-guided-landscape-a`, fixture `positive` (intended reference, provisional), case `centered-match`, `warmupMs=60000` (hard preparation limit), `durationMs=10000`. On readiness timeout, there must be no hold, measurement report or capture. Retain only fixed cue/diagnostic summaries from the failed attempt. Do not retry automatically after telling the participant to relax.

On success, retain the scalar report/framing evidence and fixed aggregate diagnostics, verifying report bytes against the device hash. Confirm final pose intent before treating the positive label as ground truth. Clean only the exact report/temp and empty export directory, stop main, remove the test package, and verify main/data preserved, test/export absent and no Pose Guide Snap camera client. A new camera run is not part of this development turn.
