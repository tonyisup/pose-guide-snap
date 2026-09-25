# Task 16N guided framing — Pixel 6 — 25 September 2026

Result: **The guided collection completed, but none of its 96 frames could evaluate the pose or full-body framing.** Every frame detected one person. Only 8–10 of 17 landmarks and two of four torso anchors met confidence requirements. No match lock or capture command occurred. This is an incomplete-body observation, not a measured center/scale mismatch.

## Collection

The participant confirmed readiness after the guide's two synthetic Pixel screen tests passed. One wireless run selected `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedGuidedFramingSequence`, authorization `user-authorized-derived`, dataset `pixel6-match-guided-a`, sequence `positive-guided-framing-bright-a`, fixture `positive`, case `centered-match`, warm-up `15000` ms, and collection `10000` ms. The existing flash cue marked the settling period.

| Artifact | Verified local and installed SHA-256 |
|---|---|
| Debug app | `a6df771af2e7a395c8db4c95643a5f329dc72bb15af8c6738c60c9825ce9b8c2` |
| Android test | `00013bc7f01f82b9baf2afb2fe131b9f5f4af87859de5a0e82b335c5b652c295` |

The device was verified as Pixel 6/oriole. Before launch, installed hashes matched, host report filenames were unused, the export directory was absent, and camera permission was granted. Only the test package needed reinstalling; the main app/data were preserved. Instrumentation passed **1/1 in 26.843 seconds**.

The final consecutive-one-person preflight and guide aspect check passed. The latter only verifies aspect compatibility, not complete body visibility or pixel-perfect camera/overlay alignment. The current preflight does not require all body landmarks or all four torso anchors, so mechanical collection success does not establish usable match evidence.

## Observations

| Observation | Result |
|---|---|
| Exactly one person | 96/96 frames |
| Match evaluation | `canonicalization-failed` in 96/96 |
| Qualified landmark count | 8 in 10 frames; 9 in 36; 10 in 50 |
| Qualified torso anchors | 2 in all 96 frames |
| Framing evaluation | 0 evaluated; 96 unavailable |
| Center/scale extrema | Unavailable |
| Person score min/median/max | 0.591546 / 0.702038 / 0.754972 |
| Maximum keypoint score min/median/max | 0.873144 / 0.904868 / 0.943241 |
| Replay locks / capture commands | 0 / 0 |

The stored coverage/framing/angular/positional/overall values are all zero because evaluation was unavailable. **These are fail-closed placeholders, not evidence of measured zero similarity.** A high maximum keypoint score does not establish adequate confidence for the rest of the body.

The new reference-aspect viewport changes the visible crop from earlier portrait runs. Cropping is a plausible explanation, but the retained scalar counts cannot identify which body parts were missing or distinguish crop, obstruction, camera placement, lighting, or detector confidence. No private image or coordinate was pulled. Do not infer a closer/farther direction or weaken thresholds from this result.

## Evidence and cleanup

| Ignored host file under `build/calibration/device/` | SHA-256 |
|---|---|
| `positive-guided-framing-bright-a.json` | `d697b9a599d254ffeebfbf50d77124197fc2aafe7514640bc44a7ff6872b845e` |
| `positive-guided-framing-bright-a-framing.txt` | `b9b80493f3dd4d5c63a7522b0b6d92d3adc1ff1289d9a6c38caba96fc92179bd` |
| `pixel6-match-guided-positive-a-analysis.json` | `64fbdc88fd053b1a92129b99a919b041d4ce9f180324a18282a2a9e5067fb6bf` |
| `pixel6-match-guided-positive-a-analysis.md` | `9222c0cd2e7da24edc1f56b1a2cf6ac23da9c9d52be5e9418d7964eefc41bc3a` |

The report matched the device-reported digest. Strict analysis treated the guided dataset separately from older portrait observations. No negative sequence is available, so false-lock rate remains unavailable.

Exact device report/temp files and their empty directory were removed, the main app was force-stopped, and only the test package was uninstalled. Final checks confirmed absent export/test package, preserved installed main hash and app data, and an empty camera client list. No photos, screenshots, private coordinates, or raw landmarks were retained. Source/APKs did not change; unchanged host gates were not repeated.

## Next decision

Before another physical repeat, establish whether the participant could see the guidance screen and whether their full body fit inside the wide preview. Also strengthen the guided collector's readiness handling so person presence alone cannot start an unusable full-body match collection. The view's layout tests do not answer whether rear-camera guidance is readable from the participant's position. No further camera run is scheduled by this record. Calibration, matching negatives under the new viewport, repeated cases, and performance evidence remain incomplete; automatic capture stays disabled.

## Participant feedback and follow-up

The participant then confirmed they could not see the rear-camera screen and expected audible cues. Task 16O adds spoken calibration guidance and a complete-body readiness gate. Its 1/1 audio-only Pixel check passes; audibility is participant-confirmed and a new camera run remains pending. See the [spoken-guidance record](2026-09-25-task16o-spoken-guidance.md). The cause of the missing landmarks remains unproven.
