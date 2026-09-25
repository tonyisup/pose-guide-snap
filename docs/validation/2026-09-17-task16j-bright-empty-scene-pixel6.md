# Task 16J empty-scene control — Pixel 6 — 17 September 2026

Result: **All 97 empty-scene frames reported no person.** Together with Task 16I's 96/96 one-person frames, the same-setup pair clearly separates person presence from absence around the unchanged `0.25` gate. This is a bounded detector checkpoint, not completed matching calibration or population accuracy evidence.

## Scope and artifacts

The participant was instructed to leave the phone and lighting unchanged and step completely out of the rear-camera view, then confirmed readiness. One 15-second warm-up plus 10-second empty-scene collection ran on the paired Pixel 6 over wireless ADB. No source, APK, or production threshold changed.

| Artifact | Local and installed SHA-256 |
|---|---|
| Debug app | `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66` |
| Android test | `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9` |

The main app was already installed and its hash matched. The test package was reinstalled and its installed hash matched. The export directory was absent before launch; camera permission was granted and asserted by the collector.

Only `com.tonyisup.poseguidesnap.calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDetectorGateSequence` ran, using authorization `user-authorized-derived`, dataset `pixel6-person-gate-a`, sequence `no-person-empty-scene-bright-a`, fixture `negative`, case `no-person-empty-scene`, `warmupMs=15000`, and `durationMs=10000`.

Instrumentation passed **1/1 in 26.657 seconds** and produced 97 schema-v3 frames. The device and host report hashes matched exactly: `212913851b1ddbf63b11b8feaa0d6c8ad811b1934af94b0a3d6e81c123dc7460`.

## Pair result

The positive sequence is the immediately preceding [Task 16I collection](2026-09-17-task16i-bright-light-pixel6.md), with report hash `749f27f02cf07e84e8232b4bf11543cb0d290656cefba05867e7800bdb7efaab`.

| Evidence | Full-body positive | Empty-scene negative |
|---|---:|---:|
| Recorded frames | 96 | 97 |
| Exactly-one-person frames | 96 | 0 |
| No-person frames | 0 | 97 |
| Qualified landmarks per frame | 17 | 0 |
| Qualified torso anchors per frame | 4 | 0 |
| Person score min / p05 / median / p95 / max | `0.724650` / `0.746036` / `0.763399` / `0.768429` / `0.771101` | `0.033058` / `0.034217` / `0.042591` / `0.049243` / `0.052640` |
| Keypoint score min / p05 / median / p95 / max | `0.878377` / `0.880329` / `0.896745` / `0.912282` / `0.914160` | `0.166007` / `0.172038` / `0.187738` / `0.206024` / `0.217873` |
| Replayed locks / capture commands | 0 / 0 | 0 / 0 |

The minimum positive person score exceeded the maximum negative person score by `0.672009`. Positive p05 minus negative p95 was `0.696793` for person scores and `0.674304` for keypoint scores. All positive frames exceeded the existing person gate and all negative frames stayed below it.

The strict analyzer accepted both reports and produced a separate pair analysis, preserving the earlier old-room comparison. Analysis JSON SHA-256: `eacb267f5a30feb067af788ecd2d7689794d8d3415d41b0c7630a5759b5c0b55`. Analysis Markdown SHA-256: `f9bdd5812f2604f9b739e23330b6c4b6a01e8f62e61dd1fe35925c8e1556aaed`.

## Decision and next checkpoint

Keep the existing `0.25` person gate. The live detector distinguishes presence and absence in this pair without a code change. The new collection conditions restored useful evidence, but these runs do not isolate lighting from framing, distance, or background. The same-setup condition follows the participant instructions and readiness response; images and scene geometry were not retained for independent confirmation.

This positive label means that a person is present. It does not mean the participant matched the bundled reference pose, so the generic analyzer's zero positive match-lock rate is not a detector failure. Next, collect a deliberately matched reference pose using the existing accepted-person preflight and a separate matching dataset, followed by a deliberately different pose in that same setup. Repeated detector cases, matching calibration, and sustained performance evidence remain open. Automatic capture stays disabled.

## Cleanup and verification

After the hash-verified pull, the exact report and fixed temporary filename were removed, the empty export directory was deleted, the app was force-stopped, and only the instrumentation package was uninstalled. Final checks confirmed the report directory and test package were absent, the main app remained installed with its data preserved, and the camera service reported `Active Camera Clients: []`. No installed-APK pulls were created. Wireless pairing remains available.

Only ignored schema-v3 scalar reports and analyses remain on the host. No private image, video, landmark array, tensor, or pairing credential was added to the evidence. The 1/1 hardware test, strict analysis, hash verification, and device cleanup checks ran for this checkpoint. No code or binary changed, so the earlier 714-JVM/14-Python/lint/build gate was not rerun.
