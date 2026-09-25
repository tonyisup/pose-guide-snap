# Task 16Z — clear adjustment wording and room for follow-up

The pending physical test below was not run. The user chose to build [Task 16AA movement-aware progression](2026-09-25-task16aa-movement-aware-coaching.md) first; use that newer build/protocol.

## Evidence and change

After the [Task 16Y run](2026-09-25-task16y-directional-arm-pixel6.md), the participant explained that they raised the left hand to the knee, then brought it just below the knee upon hearing “slightly,” all in one smooth movement lasting at most three seconds. The device recorded only 200 ms continuously inside the requested-joint radius. The signed trajectory was not retained, so the accepted interval cannot be tied to a particular part of that movement or an anatomical destination.

The prior 30-second schedule had 15272 ms left at the end of the only arm instruction. After eight quiet seconds, it was already inside the final-ten-second adjustment cutoff. It could therefore confirm a correct position but could not guide another correction. The ranked hypotheses were schedule cutoff, ambiguous wording prompting a reversal, and spatial target mismatch. The first is directly established by timing/code; the second is supported by the participant's account; the third remains open.

Arm cues now say, for example, **“Raise your left hand, then hold it there.”** All sixteen elbow/hand directions omit “slightly” and explicitly ask the participant to retain the new position. The introduction explains: “After each direction, adjust once, then hold that position while I check.” No knee destination or movement distance is invented from the unsigned evidence.

Spoken preparation now requires **60000 ms**, giving time for more than one adjustment cycle while retaining eight quiet seconds after completed speech. The general request limit is 3–60 seconds; collection remains bounded to 5–30 seconds and 600 scalar frames. The flash still begins preparation and switches off after 250 ms. New advice still stops during the final ten seconds. A late utterance still gets its existing bounded completion wait and eight-second quiet tail before hold, so actual preparation may extend beyond the nominal minute. The one-second fresh-evidence confirmation and 0.25 joint radius are unchanged. Production matching thresholds and automatic-capture status are unchanged. Solo phone-adjustment timing/process remains deferred.

## Verification

The initial one-minute request regression failed at the old parser limit. The minimized regression then used the real parser's accepted maximum and the existing speech scheduler, with a realistic ten-second introduction, four-second arm utterance, 200-ms passage through the target, and a settled residual error. Before the fix:

```text
./gradlew :app:testDebugUnitTest --tests '*CalibrationCoachingCycleTest' --offline
2 tests completed, 2 failed
expected:<LEFT_WRIST_RIGHT> but was:<null>
expected:<Raise your left hand[, then hold it there].> but was:<Raise your left hand[ slightly].>
```

After the change, the same scenario delivers the follow-up at 30 seconds, following the full eight-second pause, and Good after one continuous second at the corrected joint position. It also verifies the quiet interval after Good. Its synthetic geometry is not a replay of the participant's private movement and does not claim which follow-up direction they needed.

The full JDK 17 offline gate (`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`) passed **754/754 tests**, zero failures/errors/skips. Debug main/test assembly passed; lint reports zero errors and nine warnings. Parser tests accept 60000 ms and reject 60001 ms; measurement's 30000-ms ceiling remains enforced. Existing deadline, stale-frame, missing-body, joint, mirror, diagnostics and quiet-tail tests remain green.

- Main APK SHA-256: `65076d4c1cd2137b82661b5fc35d49e12cf7e8950088ce99ce1badb725a95d6b`.
- Test APK SHA-256: `cf2e052605cdfa73bf5605727c0c92d7b6c6db52f73ea06702cb51e95da174d0`.

Both APKs were installed with data preserved and their on-device hashes verified. The camera-free `CalibrationWarmupCueTest` passed **5/5 in 0.021 seconds**, including the synthetic 60000-ms deadline, flash-off acknowledgement and failure cleanup. It uses a simulated clock/torch callback; no actual flash, camera or speech was started. See the [native timing output](2026-09-25-task16z-timing-pixel6-output.txt). Main app/data remain installed, the test package and export directory are absent, and no Pose Guide Snap camera client remains. Physical wording and confirmation success remain pending a fresh Ready. Bounded debug confirmation diagnostics remain for that check; no new trajectory logging was added.

## Next physical protocol

Wait for Ready after explaining the one-minute preparation. Verify the phone is awake/unlocked, exact installed hashes, camera permission, absent export directory and unused output paths. Keep the sideways rear-camera setup. Start with the left hand in the lap; after each spoken direction make one adjustment, then retain that position while the app checks. Do not promise a particular direction or anatomical target.

Use `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`, `calibrationAuthorization=user-authorized-derived`, dataset `pixel6-joint-followup-a`, sequence `positive-joint-followup-landscape-a`, fixture `positive` (intended reference, provisional), case `centered-match`, `warmupMs=60000`, `durationMs=10000`. Reinstall and verify the test APK for the run. A legacy 30000-ms spoken request now fails before camera launch.

Retain only the scalar report/framing evidence, fixed completed-cue counts and bounded joint diagnostics. Treat final positive ground truth as provisional until the participant confirms intent. Pull/verify report bytes, remove only the exact report/temp and empty export directory, stop main, uninstall test, and verify main/data preserved, test/export absent and no Pose Guide Snap camera client. No camera collection is authorized by this development turn alone.
