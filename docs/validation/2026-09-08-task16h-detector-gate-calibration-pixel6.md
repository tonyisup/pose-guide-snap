# Task 16H detector-gate calibration — Pixel 6 — 8 September 2026

Result: **The exact positive and empty-scene collections passed mechanically but produced overlapping detector-score distributions.** This is valid negative calibration evidence. It does not justify lowering the person gate, and automatic capture remains disabled.

## Authorized scope and artifacts

The user separately authorized a data-preserving installation, installed-byte verification, camera permission, one full-body positive sequence, one empty-scene negative sequence, scalar-only report retrieval, combined offline analysis, exact device cleanup, and removal of only the instrumentation package. The participant was instructed immediately before the positive launch to keep their entire body visible to the rear camera and immediately before the negative launch to leave the camera view.

| Artifact | Local and installed SHA-256 |
|---|---|
| Debug app | `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66` |
| Android test | `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9` |
| Unsigned release — host evidence only | `996b45a89d91c6aac51cb6dac92900dcd32d32557aef2118832b2741520a21ae` |

The target was the connected Pixel 6 (`oriole`) running Android 16. Camera permission was granted and verified. Only `calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDetectorGateSequence` ran. Both invocations used dataset `pixel6-person-gate-a`, a 15-second warm-up, and a 10-second recording window.

| Sequence | Fixture / case | Instrumentation result | Frames | Report SHA-256 |
|---|---|---:|---:|---|
| `single-person-full-body-a` | `positive` / `single-person-full-body` | 1/1 pass in 26.513 s | 97 | `1faa6ca5208cfb6e85ecebd6caf03746cfa48d691d8ac5a18b4b76cc0794ba58` |
| `no-person-empty-scene-a` | `negative` / `no-person-empty-scene` | 1/1 pass in 26.510 s | 97 | `50973bcfedb4500060f000db3f0b1ab9b18697a936cdc81b253f984daf920fb3` |

Each device-reported report hash matched the ignored host copy exactly. The combined schema-v3 analysis completed against `development-policy-v1.json`; its JSON SHA-256 is `7b61818ef009ebd1cf8f919244847aceebb7ee5f8b02bf0805ae00cdbf0f821f` and its Markdown SHA-256 is `5bdd178f53710c67d9a65f9169123cfc3be6f27f292bb7e259099f79888d79ba`.

## Detector evidence

Both sequences were classified as `no-person` for all 97 recorded frames. Neither acquired a replayed match lock or emitted a capture command.

| Score distribution | Full-body positive | Empty-scene negative | Separation |
|---|---:|---:|---:|
| Maximum valid person score | min `0.000`; p05 `0.000`; median `0.000`; p95 `0.117226`; max `0.145938` | min `0.000`; p05 `0.000`; median `0.000`; p95 `0.122414`; max `0.136911` | positive p05 − negative p95 `-0.122414` |
| Maximum valid keypoint score | min `0.098364`; p05 `0.103079`; median `0.123211`; p95 `0.147787`; max `0.165554` | min `0.097535`; p05 `0.108081`; median `0.125264`; p95 `0.150982`; max `0.160802` | positive p05 − negative p95 `-0.047903` |

The positive person-score maximum exceeded the negative maximum by only about `0.009`, while the robust ranges overlapped. The positive keypoint distribution was also slightly lower than the negative distribution at p05, median, and p95. The two cases are therefore not separable under this collection condition. Lowering the production `0.25` person gate would admit empty-scene frames before it reliably admitted this full-body positive sequence.

This result narrows the next discriminator to collection conditions and the transient live-camera path. The static same-artifact model contract already passes, and CameraX delivered varied non-flat pixels in Task 16F. A bright-light same-artifact positive repeat can test whether scene illumination is the dominant condition before any preprocessing change is considered. Two sequences cannot select a threshold or establish population accuracy.

## Cleanup and boundary

After each pull, the exact device report and fixed temporary filename were removed and the empty `calibration-export` directory was deleted. Final cleanup stopped Pose Guide Snap, uninstalled only `com.tonyisup.poseguidesnap.test`, and deleted the two temporary installed-APK pulls. Final checks found no export directory, instrumentation package, temporary pull, or active camera client. The exact debug app remains installed with its data preserved, and unrelated files were untouched.

The retained ignored host artifacts contain only the closed schema-v3 derived scalars. No image, pixel, raw landmark, coordinate, tensor, person slot, keypoint identity, path, URI, wall-clock timestamp, or personal identifier was retained.
