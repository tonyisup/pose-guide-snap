# Task 16B derived calibration — Pixel 6 — 8 September 2026

Result: **2/2 exact authorized collector invocations passed, but calibration failed closed because the positive sequence never reached evaluation.** Automatic capture remains disabled and no threshold changed.

The user separately authorized data-preserving installation of the frozen APK pair, camera permission, the exact positive and negative collection arguments, derived-report pulls, analyzer execution, exact report cleanup, and removal of the instrumentation package. No additional test class, photo picker, existing personal-media read, still-photo capture, MediaStore operation, database mutation, app-data clear, network request, audio operation, or release installation ran.

## Device and frozen artifacts

- Device: Pixel 6 (`oriole`), Android 16.
- Source: local `codex/development-takeover` work based on `32a1429`; no production or test source changed between building this pair and completing both collections.

| Artifact | Local and installed SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `339f7597960a959f53fa79b168c838e4b25e325ea814e5d3f7d3bce83afc7d76` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `e75d82674b8ee361075f1872cde37eb4585eca37470cb19999899f75c5025e5d` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` — host build evidence only | `27f397d0a4d517b67a013c868c11d5df63ebf390f400b5254c06e2f119fcf3d5` |

Both tested packages installed successfully using data-preserving replacement. Hashes read from the installed `base.apk` files exactly matched the frozen app and instrumentation APKs. The host gate for this candidate passed 708/708 JVM tests, 8/8 Python analyzer tests, lint with zero errors and 9 warnings, and debug, unsigned-release, and Android-test APK assembly. Room V1–V4 schema hashes and packaged permission sets were unchanged.

## Exact collection results

Both invocations ran only `calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDerivedSequence` through `com.tonyisup.poseguidesnap.test/androidx.test.runner.AndroidJUnitRunner`. Each passed `calibrationAuthorization=user-authorized-derived`, `datasetId=pixel6-public-meditation-a`, `warmupMs=5000`, and `durationMs=10000`.

| Sequence | Fixture / case | Instrumentation result | Frames | Device and pulled SHA-256 |
|---|---|---:|---:|---|
| `positive-centered-a` | positive / `centered-match` | PASS in 16.818 s | 97 | `df6b882b304e6484cafdf902a859a1fa69f48f4e18297fa21ca1e796d9403a4d` |
| `negative-wrong-pose-a` | negative / `wrong-pose` | PASS in 16.877 s | 97 | `03232398f4e058ca106cc0ab9ae754494de4758d28191c0acbf5d8fefd6235c8` |

The fixed device report `no_backup/calibration-export/calibration-sequence-v1.json` was pulled immediately after each invocation under its sequence ID in ignored host build output. In both cases the pulled digest exactly matched the digest reported from bytes read back on the device.

## Analyzer result

The combined replay processed 194 frames and two sequences under the unchanged uncalibrated development policy:

| Metric | Result |
|---|---:|
| Positive locks | 0/1 |
| Negative false locks | 0/1 |
| Duplicate capture commands | 0 |
| Positive evaluated frames | 0/97 |
| Negative evaluated frames | 97/97 |
| Inference-latency samples | unavailable |
| Cue observations | unavailable |

Every positive frame reported exactly one person and all five scores as zero. Under the frozen runtime's closed branching, that combination means every positive frame reached `CANONICALIZATION_FAILED`; no positive frame reached the matcher. The v1 report did not retain the confidence-qualified torso counts needed to distinguish missing anchors from degenerate torso scale.

The negative sequence reached evaluation on every frame and remained safely below acquisition on four independent gates:

| Score | Minimum | Median | Maximum | Frames passing acquisition gate |
|---|---:|---:|---:|---:|
| Landmark coverage | 0.934 | 0.962 | 0.973 | 97/97 |
| Framing | 0.477 | 0.491 | 0.500 | 0/97 |
| Angular similarity | 0.539 | 0.544 | 0.549 | 0/97 |
| Positional similarity | 0.655 | 0.662 | 0.668 | 0/97 |
| Overall match | 0.598 | 0.603 | 0.607 | 0/97 |

The first combined analyzer attempt exposed a host-only input-composition defect: it rejected the two one-sequence files because they shared a dataset ID. The correction merges fragments only when their schema, provenance, authorization, population limits, and privacy marker match exactly, while continuing to reject duplicate sequence IDs. The corrected analyzer's 10/10 tests passed before replay. The final ignored aggregate JSON and Markdown reports have SHA-256 values `52412c5a11c651f2accaa92b6865ad90fe252012dc4223bf1de8132e81d8a429` and `e74e4d66f273b1845e49fcc9321f44a35d6d4f7cc720fb15a1e895c6db19f18b` after regeneration with the compatible analyzer.

## Decision and evidence limit

This is a safe failed calibration. The negative example supplies useful separation evidence, while the positive example cannot support threshold selection because pose scoring never ran. No numeric threshold can repair missing canonicalization evidence, so the development defaults remain uncalibrated and automatic capture remains disabled.

The collection covers one authorized participant, one Pixel 6, one public bundled reference, one centered positive attempt, one wrong-pose negative attempt, and the recorded lighting/viewpoint of that session. It cannot establish population accuracy, alternate-device behavior, production body-visibility requirements, sustained performance, or the complete guided-capture acceptance gate.

## Data boundary and cleanup

The rear camera and MoveNet ran transiently. The v1 reports contain relative time, person count, five derived scalar scores, mirror choice, null latency/cue observations, and zero capture commands. They contain no image, screenshot, raw landmark, tensor, private-media path, URI, wall-clock timestamp, identity field, or audio. The per-frame JSON reports and aggregate analyzer files remain ignored under `build/calibration/device`; none is committed.

After analysis, the exact device report and temporary name were deleted, and the empty `calibration-export` directory was removed and confirmed absent. The camera collector was closed. Only `com.tonyisup.poseguidesnap.test` was uninstalled and confirmed absent; the main app remains installed with its data preserved. Temporary host copies of installed APKs were deleted. Unrelated apps, files, and camera state were preserved.

The next bounded step is a new host-built schema-v2 diagnostic collector that records only evaluation status, total confidence-qualified MoveNet landmark count, and qualified shoulder/hip anchor count. Its frozen APK pair requires separate exact authorization before another positive camera collection.
