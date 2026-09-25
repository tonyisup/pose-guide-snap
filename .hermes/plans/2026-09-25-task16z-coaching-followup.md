# Task 16Z — clearer adjustment and follow-up

Superseded by [Task 16AA](2026-09-25-task16aa-movement-aware-coaching.md). The user chose movement-aware progression before testing this fixed-duration version; its proposed physical sequence was not run.

Implemented and installed after the participant explained raising the hand to the knee then just below it in one smooth movement lasting at most three seconds. The word “slightly” prompted the second part; the old preparation cutoff blocked further direction. Arm cues now end with “then hold it there,” and spoken preparation requires 60000 ms while preserving eight quiet seconds, final-ten-second cutoff, one-second joint confirmation and the 0.25 radius.

Two regressions failed before the change and now pass. Full build/lint gate: 754/754 tests, zero lint errors/nine warnings. Exact installed hashes verified. Camera-free native timer tests: 5/5 in 0.021 seconds. Main/data preserved; test/export absent; no Pose Guide Snap camera client. No new camera collection this turn.

Use the [Task 16Z record](../../docs/validation/2026-09-25-task16z-coaching-followup.md) for artifact hashes and next physical protocol. Wait for fresh Ready, verify unlocked phone, then reinstall verified test APK. Dataset pixel6-joint-followup-a, sequence positive-joint-followup-landscape-a, warmupMs=60000, durationMs=10000. This sequence is unused. Physical wording/confirmation usability remains open. Previous positive ground truth remains provisional; do not infer full pose intent from the description of one hand. Solo phone-adjustment process remains deferred.
