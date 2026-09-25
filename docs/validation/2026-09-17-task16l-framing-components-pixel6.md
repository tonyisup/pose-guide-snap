# Task 16L framing components — Pixel 6 — 17 September 2026

Result: **Centering was the lower framing component in every recorded frame; subject size also stayed below the existing framing requirement.** All 97 frames detected and evaluated one person with 17 qualified landmarks and four torso anchors. No match lock or capture command occurred. The diagnostic identifies the failed components, not a completed matching calibration or a proven production-code defect.

## Scope and artifacts

The participant confirmed readiness after instructions to keep the same camera setup and meditation pose, with their complete body visible. One 15-second warm-up plus 10-second collection ran on the Pixel 6 over wireless ADB. The accepted-person preflight passed.

| Artifact | Local and installed SHA-256 |
|---|---|
| Unchanged debug app | `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66` |
| Task 16L Android test | `c768792ed7d10bd7cd9e19f3ceccfe593cbd60c7a5f987505c13138912716cbf` |

The main app was preserved; the test package was installed and both installed hashes verified. The report directory was absent before launch, and camera permission was granted and checked by the collector.

Only `com.tonyisup.poseguidesnap.calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedFramingDiagnosticSequence` ran, with authorization `user-authorized-derived`, dataset `pixel6-match-bright-a`, sequence `positive-centered-framing-bright-a`, fixture `positive`, case `centered-match`, `warmupMs=15000`, and `durationMs=10000`.

Instrumentation passed **1/1 in 26.637 seconds**, reporting 97 recorded/evaluated framing samples and zero unavailable samples. Device and host report hashes matched exactly: `64a0192715ff442e4b091f9e703b154aa4f9dd7d5cde6162dd26859212ae5cdb`.

## Framing result

| Component | Minimum | Maximum | Requirement |
|---|---:|---:|---:|
| Center similarity | `0.381554` | `0.386197` | `0.800` |
| Scale similarity | `0.691971` | `0.711420` | `0.800` |

The center maximum was below the scale minimum, so center alignment determined the combined framing score throughout. Both components failed independently. The framing median was `0.383511`; changing only center alignment would still leave the observed scale values below the existing requirement.

The retained instrumentation summary contains only these counts and scalar extrema. Its exact ignored host text SHA-256 is `65211444e4132e14c094aae6041c7324ffb5fd4d6e6dba3e09791879485adf8c`. It contains no image, coordinate, body extent, landmark identity, or raw observation.

## Other match criteria

| Acquisition criterion | Required | Minimum | Median | Maximum | Passing frames |
|---|---:|---:|---:|---:|---:|
| Landmark coverage | `0.750` | `0.854184` | `0.863283` | `0.871919` | 97/97 |
| Framing | `0.800` | `0.381554` | `0.383511` | `0.386197` | 0/97 |
| Angular similarity | `0.850` | `0.822470` | `0.827497` | `0.832615` | 0/97 |
| Positional similarity | `0.800` | `0.789640` | `0.798061` | `0.803740` | 22/97 |
| Overall match | `0.825` | `0.807744` | `0.812563` | `0.818177` | 0/97 |

All frames had one accepted person, successful evaluation, 17 confidence-qualified landmarks, and four torso anchors. Person-score min/median/max were `0.658776`/`0.666930`/`0.676099`. No frame passed all acquisition criteria; the replay acquired no lock and emitted no capture command. Fixing framing alone is not evidence that the remaining pose criteria will pass.

The strict positive-only analysis JSON SHA-256 is `6b50466cfc5d0a256ca336d45f66b772de78244f5cfe3788cbd06049e455e041`; its Markdown hash is `93a7c98c539231eb4271de1e3d24867f154de96ca09c721bd296b0bba66ae2b3`. Earlier reports and analyses remain unchanged. A matching negative has not run, so negative false-lock rate remains unavailable.

## Placement guidance and limits

The evaluator compares body-bound centers in image-normalized coordinates. From the **bundled public reference only**, the target center is `(0.391835, 0.527651)`: approximately halfway down the full camera frame and slightly left of center. This is a public target, not a retained private participant position. Source inspection also confirms that the collector displays a separate reference thumbnail above the full-screen camera preview, with no alignment marker on that preview. That is a usability limitation; it does not prove why the participant's composition differed.

The next bounded check re-aims the phone so the middle of the complete seated body approaches that reference placement while preserving lighting and full-body visibility. Use the same diagnostic APK and a new sequence ID. The scalar center score does not identify a movement direction, and the scale score is symmetric, so do not infer that the participant must move closer or farther from this report. A live alignment guide is a useful subsequent usability improvement if the placement remains hard to reproduce. Do not weaken any gate to manufacture a pass.

## Cleanup and verification

After the hash-verified report pull, the exact report and temporary filename were removed, the empty export directory was deleted, the app was force-stopped, and only the test package was uninstalled. Final checks confirmed no export directory, no instrumentation package, the main app/data preserved, and `Active Camera Clients: []`. No installed-APK pulls were created. Wireless pairing remains available.

No source or APK changed during this checkpoint. The new camera method is now verified mechanically on the device in addition to its earlier 3/3 synthetic checks. Strict analysis, report-hash verification, and exact device cleanup passed. Host compilation/tests were not rerun for unchanged code. Matching calibration remains incomplete and automatic capture remains disabled.
