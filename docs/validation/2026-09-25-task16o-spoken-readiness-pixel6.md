# Task 16O spoken camera readiness — Pixel 6 — 25 September 2026

**The camera attempt stopped at complete-body readiness and produced no measurement report.** Instrumentation reported **1 test, 1 failure in 22.796 seconds** with `Spoken guided warm-up ended without five fresh, fully evaluated body frames`. This is not a successful pose match or successful collection. The intended rejection prevented another presence-only, unevaluable measurement sequence.

## Exact attempt

The participant confirmed physical readiness after confirming clear audio in the separate speech check. The wireless Pixel 6/oriole ran only `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`, authorization `user-authorized-derived`, dataset `pixel6-match-spoken-a`, sequence `positive-spoken-framing-bright-a`, fixture `positive`, case `centered-match`, warm-up `15000` ms, duration `10000` ms.

| Artifact | Verified local and installed SHA-256 |
|---|---|
| Debug app | `20d54557f60328f279f5306ce1e8b19085d7b233c50c2c04cd40f4d891732d8d` |
| Android test | `836f9e645a045590c7db58d1258c36f4cb3d32ea1f4ae2cd9940a0ca0862400c` |

The test package was reinstalled without clearing main-app data, camera permission was granted, hashes verified, host output names unused, and the device export directory absent before launch. The flash/voice settling path ran. The specific thrown exception occurs after the incomplete-body stop utterance has successfully completed according to the TTS listener. “Hold still” and the 10-second measurement were not reached. See the [instrumentation output](2026-09-25-task16o-spoken-camera-output.txt).

The retained error does not identify which body landmarks failed, their confidence, or whether stale evidence versus missing landmarks caused the final readiness failure. Do not reuse Task 16N's 8–10 landmark/two-anchor counts as measurements from this attempt. There are no center, scale, pose, lock-rate, or negative-control metrics to analyze from this attempt.

## Setup feedback and next discriminator

The participant reported that the phone was **upright, approximately 8 feet away**. The guide uses a wide, reference-aspect camera viewport. A wide crop within an upright camera orientation may exclude too much vertical context; this is a hypothesis, not a demonstrated cause from the failure text.

The next bounded check changes phone orientation to landscape while preserving approximately 8 feet of distance, lighting and the seated reference pose. The rear lens still faces the participant. Aim to include head, crossed legs and feet, keep the phone stationary after setup, and follow spoken cues. Do not switch to the front camera, weaken confidence/pose thresholds, or ask for another visual-only repeat. Use a new sequence ID and wait for physical readiness. See the [Task 16P plan](../../.hermes/plans/2026-09-25-task16p-landscape-spoken-framing.md).

## Cleanup

The exact device report path was verified absent; no report pull or analyzer run was attempted. Exact report/temp filenames were removed if present, the empty export directory deleted, the main app force-stopped, and only the test package uninstalled. The main hash/data were preserved.

An initial global-empty-camera assertion found the user's separate Google Camera app active. It was left untouched. A scoped recheck confirmed no Pose Guide Snap process or camera client, no export directory, and no test package. This is completed cleanup of this test, not a claim that no other app is using the device camera.

No private image, screenshot, recording, coordinate or raw landmark was pulled. Source/APKs remained unchanged; no repeated host gate was needed. Matching calibration and the complete production workflow remain incomplete, with automatic capture disabled.
