# Task 16AC — match hand instructions to the participant's knees

## Finding

The [Task 16AB physical run](2026-09-25-task16ab-supported-hands-pixel6.md) repeated the right-hand-on-knee command three times and stopped before hold/measurement. The participant reported that their hand was already resting on the knee at the first instruction and they could not work out an adjustment from the repetitions. At the deadline the stillness check passed, while arm position and framing failed.

Code inspection found an instruction/acceptance inconsistency: the words named the participant's knee, but wrist acceptance still compared with the reference person's wrist in torso-normalized coordinates. A person's knee can occupy a different projected location even when their hand is supported. New synthetic regressions reproduced rejection of a wrist at its own displaced knee, acceptance of the old reference wrist position away from that knee, missing knee evidence being ignored, and identical repeated commands. All five initial tests failed before the fix. This demonstrates the logic defect; it cannot quantify detector error or reconstruct the previous participant trajectory.

## Change and limitations

The debug meditation coach now measures each wrist's distance from the same-side observed knee in aspect-corrected, torso-normalized coordinates. Both knees must be confidence-qualified along with the existing arm and torso evidence. The radius remains 0.25 torso lengths. The target changes intentionally; this is not equivalent to the old reference-wrist admission rule. Elbows still compare with the normal/mirrored reference. Only elbow error selects that reference orientation, so a large mirror-independent wrist-to-knee distance cannot conceal the better elbow reference. Spoken sides remain anatomical.

This checks projected proximity only. It cannot establish physical support, palm direction, depth or an entire meditation pose. It is still an uncalibrated coaching heuristic, not a validated physical-contact detector. Production match scoring, framing requirements and automatic-capture behavior are unchanged.

Each unresolved hand request receives its normal resting instruction once. If it remains unresolved after the existing quiet and stillness requirements, the guide explains once:

> If your right hand is already resting on your right knee, keep it there. I can't confirm its position yet.

The left-hand equivalent names the left side. Further identical hand commands and explanations are suppressed for that unresolved request. The guide continues observing the requested hand; a fresh position-plus-stillness confirmation still earns Good, clears the request and resets that side's notice budget. Starting a new preparation session also resets the budgets. An explanation cannot earn Good, resolve a hand check or bypass readiness. Normal recovery cues remain available. The unchanged deadline stops unresolved preparation without hold/measurement. Eight-second quiet intervals, one-second motion windows, 0.06 motion radius, 30-second initial allowance and 60-second deadline remain in force.

Bounded arm diagnostics now include the fixed labels `wristTarget=same_side_knee elbowTarget=reference`. Wrist error ranges therefore mean distance to the participant's knee, not the earlier reference-wrist discrepancy. Do not compare those ranges across versions as if their targets were unchanged. No additional coordinates, trajectories, private images or recordings are retained.

## Verification

The new regression class initially ran five tests, all failing. After the implementation, all five passed. A sixth test covers mirrored elbow selection under a large wrist error. The existing movement fixture now moves around the knee target, preserving the checks that moving within the accepted radius cannot earn Good and an elapsed quiet timer cannot interrupt continued motion. Eight elbow-direction, requested-joint focus, mirror, body-evidence, preparation and bounded-diagnostic checks remain covered. Existing repeated-hand expectations now require the explanation rather than another identical command.

The full JDK 17 offline gate passed:

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline
774 tests, 0 failures, 0 errors, 0 skipped
Main/test APK assembly passed; lint: 0 errors, 9 warnings
```

- Main APK SHA-256: `764dacc9d848525343fe702642b150ca0367352da623a5b24c0dd2638943a668`.
- Test APK SHA-256: `d944ad68a656207df6aeb9b18e7711f03399f1f86da2bcb9eb89db152752a536` (unchanged instrumentation artifact; coaching code is in the main debug APK).

The main APK was installed with app data preserved and its installed hash verified. Test/export absence and no Pose Guide Snap camera client were verified afterward. The subsequent Ready-authorized physical run is recorded below. The host regressions do not validate real knee localization, speech comprehension, physical support or end-to-end readiness. Solo phone-adjustment workflow redesign remains deferred.

## Next physical protocol

After fresh Ready, verify awake/unlocked Pixel state, camera permission, exact installed hashes, absent exports and unused output names. Install the test APK and recheck unlocked state immediately before launch. Use the sideways rear camera. Start with hands comfortably resting on the knees, palms up; follow any named directions. If the inability-to-confirm explanation occurs while the hand is already resting, keep it comfortable rather than searching for an unspecified adjustment. Wait for the finished/stopped announcement before relaxing.

Use `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`, authorization `user-authorized-derived`, dataset `pixel6-knee-relative-a`, sequence `positive-knee-relative-landscape-a`, fixture `positive` (intended reference, provisional), case `centered-match`, `warmupMs=60000`, `durationMs=10000`. These identifiers were consumed by the physical run below; do not reuse them. Save instrumentation to `docs/validation/2026-09-25-task16ac-camera-output.txt`.

On success, verify the scalar report hash and run the existing strict analyzer; participant confirmation is required before treating the provisional positive label as ground truth. On timeout, retain only fixed cue/arm/preparation summaries and verify report/temp absence. Stop main, remove only exact report/temp and empty export directory, uninstall only test, and verify main/data/hash preserved plus no own camera client. No automatic repeat after an end announcement. A meaningful next result is whether supported hands pass their projected-position check without a repeated command loop, and which preparation blockers remain.

## First physical result

The [Pixel run](2026-09-25-task16ac-knee-relative-pixel6.md) completed the right-hand instruction once and the explanation once, with no Good, then stopped before hold/measurement. Stillness passed at the terminal decision; arm position, framing and an unconfirmed adjustment remained. The participant confirmed a comfortably supported right hand throughout and a clear explanation. Cleanup passed. Task 16AD prepares a separate supported-hand versus lap comparison; coaching thresholds remain unchanged.
