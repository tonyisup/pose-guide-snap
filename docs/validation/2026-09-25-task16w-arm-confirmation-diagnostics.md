# Task 16W — diagnose missing arm confirmation

The participant placed the requested left hand before hold but heard no “Good.” They also flipped the palm down, with unknown timing. The current path checks elbow/wrist positions and confidence; it cannot evaluate palm orientation. Do not claim that palm orientation explains the missing acknowledgment, that the user failed to act, or that the whole final reference pose was confirmed.

## Investigation boundary

The diagnosing-bugs workflow is at feedback-loop construction. The recorded physical symptom is LEFT_ARM=1 / GOOD=0, but the prior privacy-bounded report contains no preparation arm errors or confirmation trajectory, so it cannot reproduce the participant's exact failure offline. The known-correct synthetic transition does produce “Good.” No root cause is established, no tolerance is relaxed and no timing/process fix is claimed. An exact physical replay is unavailable; add bounded instrumentation for the next explicitly ready trial instead of inventing missing observations. This is debug instrumentation, not production logging.

## Diagnostic added

The real guidance state machine records only after an arm instruction has been requested. Each frame is classified by a fixed enum: expired deadline, speech pending, unavailable body evidence, arm mismatch, stable-duration accumulation, or ready to confirm. Diagnostic counters do not feed any guidance decision. Existing min/max arm displacement calculations are split into elbow/wrist components without changing their maximum, mirror selection or the 0.25 torso-length tolerance.

The summary distinguishes:

- Position outside tolerance: post-speech, pre-deadline elbow/wrist error ranges and MISMATCH counts.
- Interrupted evidence: UNAVAILABLE counts, gaps over 750 ms, and the longest eligible stable interval.
- Insufficient time: remaining nominal preparation time when arm speech completes, stable duration, and EXPIRED counts. Only arm utterances update the remaining-window metric; the later hold and finish announcements cannot overwrite it.

One fixed summary line is emitted after shutdown under `calibration arm confirmation`. It retains no per-frame samples, coordinates, identities, private text, image or audio. At most 600 samples are aggregated; additional samples set `capped=true`. Error ranges are clipped to 0–10 torso lengths and formatted to four decimals, durations to 0–60000 ms. Ranges combine requested-arm samples and do not establish that minima occurred together. Frames while speaking or after expiry do not contribute to the eligible error range. The normal closed scalar report is unchanged.

The diagnostics are temporary, debug-only investigation support. Remove them once the original physical symptom is reproduced, fixed and verified, or explicitly retain them as a documented calibration tool. Current stage is instrumented and host-verified, not fixed.

## Verification

Command run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline`.

Result: **745 tests passed**, zero failures/errors/skips; debug app/test builds passed; lint zero errors and nine warnings. Five new tests exercise the actual guidance state transitions: corrected arm emits Good, elbow mismatch with acceptable wrist, unavailable evidence, short confirmation window with no hold overwrite, stale-frame resets, and bounded/reset diagnostics. Existing arm-guidance tests remain green. This validates the diagnostic distinctions but does not reproduce or solve the participant's unknown physical trajectory.

- Main APK SHA-256: `09f913b7aacd5321dd35afcd917b53c981ef68d7ce4552893113d1a015bf507e`.
- Test APK SHA-256: `3ce2babe39d8d1b44c605a2186e4009788023480ee4b5f793617f12daecfea22`.

The diagnostic main APK is installed on the paired Pixel and its installed hash verified. Main app/data remain intact; test package and export directory are absent, and no Pose Guide Snap camera client is active. No new participant run has occurred.

## Next physical diagnostic, pending Ready

Use unchanged spoken protocol: same sideways rear camera, left hand initially in lap, then follow the arm instruction and keep that position. Do not require a palm flip or ask the participant to time it. Verify the above installed hashes, permission, absent export directory and unused host output paths. Use method `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`, authorization `user-authorized-derived`, dataset `pixel6-arm-confirmation-diagnostic-a`, sequence `positive-arm-confirmation-landscape-a`, fixture `positive` (intended final pose, provisional), case `centered-match`, warmup `30000`, measurement `10000`.

Retain only scalar report, framing summary, fixed completed-cue counts and the new bounded confirmation summary. Preserve the raw source labels as provenance; confirm final pose intent before accuracy classification. Cleanup exact report/temp files and empty directory; stop main app, remove test package, verify no Pose Guide Snap camera client and preserve app/data. No physical run is started by this feedback alone. Solo camera-adjustment timing/process remains deferred.

The physical diagnostic is complete. The first attempt timed out while the participant had forgotten to unlock; the unchanged unlocked retry isolated spatial rejection with 12.985 seconds of available confirmation time and no tracking gaps. See the [Task 16X result](2026-09-25-task16x-arm-confirmation-pixel6.md). The coaching issue remains unresolved.
