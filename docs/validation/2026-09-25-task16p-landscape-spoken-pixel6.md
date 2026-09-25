# Task 16P landscape spoken framing — Pixel 6 — 25 September 2026

**The landscape setup produced 95/95 fully evaluated frames, with centering passing throughout, but no match lock.** Scale and the independent angular, positional and overall criteria still failed. The preceding upright spoken attempt had stopped at complete-body readiness; this run reached and completed measurement. This supports using the landscape setup for further investigation without proving orientation alone caused the earlier failure.

## Setup and exact run

The participant had reported an upright phone approximately 8 feet away. For this run they were asked to turn it sideways, preserve approximately the same distance/lighting, keep the rear lens facing them, and include the whole seated meditation pose. The public reference image was shown in the conversation. They confirmed readiness.

ADB verified Pixel 6/oriole, and a display-metadata-only check during the attempt returned `mRotation=ROTATION_90`. No screenshot, window text, image or device dump was retained by that check; only the rotation value was kept temporarily. Camera/overlay pixel alignment is not established solely by that metadata.

| Artifact | Verified local and installed SHA-256 |
|---|---|
| Debug app | `20d54557f60328f279f5306ce1e8b19085d7b233c50c2c04cd40f4d891732d8d` |
| Android test | `836f9e645a045590c7db58d1258c36f4cb3d32ea1f4ae2cd9940a0ca0862400c` |

Only `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence` ran: authorization `user-authorized-derived`, dataset `pixel6-match-spoken-a`, sequence `positive-spoken-landscape-bright-a`, fixture `positive`, case `centered-match`, warm-up `15000` ms and collection `10000` ms. Local/installed hashes matched, output names were unused, and the device export directory was absent before launch.

Instrumentation passed **1/1 in 33.107 seconds**, including the spoken phase transitions. The complete-body readiness and aspect checks passed; the measurement starts after the hold instruction finishes, and the completion instruction finishes before the test exits. The run does not export a per-cue speech transcript or prove which adjustment cues the participant heard.

## Results

All 95 frames detected one person, qualified all 17 landmarks and four torso anchors, and completed pose evaluation. Framing diagnostics reported 95 evaluated and zero unavailable samples.

| Framing component | Minimum | Maximum | Requirement |
|---|---:|---:|---:|
| Center similarity | 0.816952 | 0.834996 | 0.800 |
| Scale similarity | 0.677418 | 0.690808 | 0.800 |

Center alignment passed every frame. Scale limited every frame and failed throughout. The retained scale score is symmetric; do not issue a closer/farther instruction by guessing from that score. Live speech uses transient bounds to determine direction, but those bounds and emitted-cue history are not retained in this report.

| Acquisition criterion | Required | Minimum | Median | Maximum | Passing frames |
|---|---:|---:|---:|---:|---:|
| Landmark coverage | 0.750 | 0.909306 | 0.921984 | 0.960115 | 95/95 |
| Framing | 0.800 | 0.677418 | 0.685526 | 0.690808 | 0/95 |
| Angular similarity | 0.850 | 0.789424 | 0.798482 | 0.801779 | 0/95 |
| Positional similarity | 0.800 | 0.697702 | 0.704422 | 0.753902 | 0/95 |
| Overall match | 0.825 | 0.747511 | 0.751172 | 0.772702 | 0/95 |

Person-score min/median/max: `0.709264` / `0.721007` / `0.771820`. Maximum-keypoint-score min/median/max: `0.909561` / `0.920385` / `0.938522`. The independent positive-only strict replay produced zero locks and zero capture commands. A successful collection is not a successful pose match; correcting scale alone does not establish passing pose similarity.

## Retained evidence and cleanup

| Ignored host file under `build/calibration/device/` | SHA-256 |
|---|---|
| `positive-spoken-landscape-bright-a.json` | `eb7d397235f991864e174e166635bd01dfc1cd8673ca95e3868cd1e74d29326d` |
| `positive-spoken-landscape-bright-a-framing.txt` | `2390b19c20dc1af119d973aee64f3fb0b8f5bf95081cab06d2fb2cfcce03e1e5` |
| `pixel6-match-spoken-landscape-a-analysis.json` | `a640ba3284c3f9b534dc6193c823c4bc391492dd8d5b5bac37ce4fdfc5d4d217` |
| `pixel6-match-spoken-landscape-a-analysis.md` | `316b12b626d6b60bc9ef0b25eec7adcaf38bc71ca5d912c250d70fb8ad434376` |

The pulled report matched the device-reported digest. Analysis stayed within the spoken-guidance dataset; no matching negative is available. No photo, audio recording, private coordinate, landmark array or screenshot was pulled.

Exact device report/temp files and their empty directory were removed. The main app was force-stopped and only the test package uninstalled. Final checks verified the unchanged installed main hash/data, absent export/test package, and an empty global camera client list. Source/APKs did not change, so passing host checks were not repeated.

## Next decision

The participant has been asked which adjustment they last heard before “Hold still” and whether they had time to follow it. Establish whether the fixed 15-second window cuts off useful preparation before choosing another movement or changing the coaching flow. Preserve the now-working landscape/full-body setup. Matching thresholds remain unchanged and automatic capture remains disabled; pose coaching, matching negatives, repeated cases and performance acceptance remain open.
