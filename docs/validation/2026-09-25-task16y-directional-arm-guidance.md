# Task 16Y — directional elbow and hand coaching

The user approved replacing the broad arm-placement instruction after Task 16X established that both measured elbow/wrist errors remained outside tolerance during an uninterrupted 12.985-second post-speech window. The old instruction did not identify the residual movement, and confirmation required both joints of the arm to match even if only one had been corrected.

## Implemented behavior

Debug coaching now chooses the most displaced visible elbow or wrist and describes its dominant signed correction, for example “Lower your left elbow slightly” or “Move your left hand slightly to your right.” Wrist movement is worded as hand movement. Horizontal words use the participant's own left/right while facing the unmirrored rear camera; vertical directions follow image up/down. No palm orientation, depth or finger pose is inferred.

Target-minus-live offsets are calculated transiently using the same aspect-corrected torso normalization and allowed mirrored reference candidates as before. They have redacted string representations and are never exported. Initial selection chooses the largest joint error. After a request, the same joint remains the focus even when another joint's error grows. If the participant overshoots or the remaining dominant direction changes, the next permitted cue uses the current direction instead of repeating an obsolete one.

“Good” now confirms the requested **joint**, after its full two-dimensional error stays at or below the unchanged **0.25 torso-length radius** for one second of fresh complete-body evidence. Another elbow/wrist remaining wrong cannot block that confirmation. Correcting just one axis is insufficient if the joint's total error remains outside the radius; advice then updates to the remaining direction. Confirmation is not a whole-arm or whole-pose match.

The existing one-second cue stability, eight-second quiet interval after completed speech, 30-second minimum preparation, final-ten-second adjustment cutoff, and quiet interval before hold remain unchanged. Body/geometry failures still take precedence. Production match/capture thresholds are untouched, and solo camera-adjustment timing/process remains deferred.

## Bounded diagnostics

Completed-cue counts now use fixed joint/direction enum names such as LEFT_ELBOW_DOWN; obsolete LEFT_ARM/RIGHT_ARM phrases are removed from the active vocabulary. Arm diagnostic summaries explicitly include `scope=joint` and bounded `jointMin`/`jointMax`, alongside same-arm elbow/wrist ranges. READY/MISMATCH refer to the requested joint. These summaries must not be interpreted with the earlier whole-arm confirmation semantics. The 600-sample cap, error/duration clipping and lack of raw samples/coordinates remain. The normal scalar report schema is unchanged.

## Verification

Before implementation, `./gradlew :app:testDebugUnitTest --tests '*CalibrationDirectionalArmGuidanceTest' --offline` failed both new regressions: the cue was LEFT_ARM instead of LEFT_ELBOW_DOWN, and fixing the requested elbow did not earn Good while the wrist remained wrong. After the change those tests pass.

The final host gate (`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline`, JDK 17) passed **752/752 tests**, zero failures/errors/skips; debug app/test assembly passed; lint has zero errors and nine warnings. Seven directional regression tests cover:

- Joint/direction naming and confirmation scoped to the corrected joint, with the next request respecting eight quiet seconds.
- All sixteen participant-relative joint/direction combinations, verifying that following each direction reduces reference error.
- Mirrored reference side and horizontal-direction changes.
- Overshoot reversal while retaining the requested joint.
- No false confirmation for an unresolved second axis.
- Redacted transient offsets and the unchanged radius.

Existing missing-body, geometry, stale-frame, speech, cutoff, diagnostic-bound and mirror tests remain green. This is synthetic and build verification; the original participant coaching issue still needs a live check. Do not claim that physical anatomy, perspective or wording usability is calibrated.

- Main APK SHA-256: `6c53a901cbbdd8a76f9ebd89d35d66089c463d8aed8eaec5faff3e8df1ceb9d9`.
- Unchanged test APK SHA-256: `3ce2babe39d8d1b44c605a2186e4009788023480ee4b5f793617f12daecfea22`.

The main APK was installed with app data preserved and its installed SHA-256 verified against the value above. The test package and calibration export directory are absent, and no Pose Guide Snap camera client remains. No camera collection was started during this development turn.

## First physical check completed

The participant's Ready authorized the check below. Collection passed 1/1 with 96 fully evaluated frames. LEFT_WRIST_UP completed once, but the longest accepted wrist interval was only 200 ms, so Good did not play. Cleanup is verified; participant feedback and successful physical confirmation remain pending. See the [Pixel result](2026-09-25-task16y-directional-arm-pixel6.md). The sequence below has been consumed and must not be reused.

## Executed bounded protocol

First verify the phone is awake/unlocked, the exact installed hashes, camera permission, absent export directory and unused host output names. Preserve the sideways rear-camera setup. Start with the left hand in the lap and follow whichever joint/direction is spoken; do not promise a particular direction from retained unsigned diagnostics. Keep the requested position after adjusting.

Use `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`, authorization `user-authorized-derived`, dataset `pixel6-joint-guided-a`, sequence `positive-joint-guided-landscape-a`, fixture `positive` (intended final reference pose, provisional), case `centered-match`, `warmupMs=30000`, `durationMs=10000`. Reinstall and verify the test APK before this physically authorized run. Retain only scalar report/framing evidence, fixed completed-cue counts and bounded joint-confirmation summary. Confirm the participant's final-pose intent before treating the report as positive calibration ground truth; otherwise keep its analysis provisional.

Remove exact report/temp files and the empty export directory, stop the main app, uninstall only the test package, and verify no Pose Guide Snap camera client while preserving main/data. No camera is opened by the development request itself.
