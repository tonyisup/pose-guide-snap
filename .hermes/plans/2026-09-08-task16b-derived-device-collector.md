# Task 16B — bounded derived Pixel calibration collector

**Status:** Host and bounded device execution complete; calibration failed closed at positive canonicalization.

## Objective

Produce analyzer-ready detector evidence on the target device without retaining camera images, raw landmarks, private paths, URIs, wall-clock timestamps, or identity fields.

## Implemented slice

1. Add an exact instrumentation-argument contract that requires the `user-authorized-derived` marker, lowercase pseudonymous identifiers, an explicit positive or negative fixture label, a 3–15 second warm-up, and a 5–30 second collection window.
2. Assert the target is a Pixel 6 (`oriole`) and that camera permission was granted before opening the rear camera.
3. Reuse the app's full-screen CameraX viewport, bundled public meditation reference, MoveNet observation, framing evaluator, and match evaluator.
4. Convert each accepted frame immediately into relative time, person count, five scalar scores, mirror selection, and explicit null/zero event observations. Keep at most 600 immutable derived frames.
5. Sync one deterministic schema-version-1 JSON report into a fixed backup-excluded app-private file, hash the bytes read back from disk, and report only its filename, digest, frame count, dataset ID, and sequence ID.
6. Delete stale output before a run and delete partial output after any failure. A successful report remains only long enough for the separately authorized host pull and exact cleanup.
7. Cross-check one collector document byte-for-byte in JVM tests and parse that same fixture in the Python analyzer tests.

## Invariants

- The collector is fail-closed when selected without every exact bounded argument.
- The argument marker is a mechanical guard and does not replace fresh user authorization for the final hashed APK pair and physical collection.
- The collection callback stores no `PoseObservation`, landmark, image, bitmap, tensor, path, URI, absolute time, or throwable.
- The public reference overlay does not change the production full-screen camera crop.
- Unevaluated match evidence becomes zero scalar scores and an unmirrored selection.
- This small internal dataset cannot establish population accuracy or enable automatic capture by itself.

## Host verification

- 708/708 JVM tests pass with zero failures, errors, or skips.
- 8/8 Python analyzer tests pass.
- Lint reports zero errors and 9 warnings.
- Debug, unsigned release, and Android-test APK assembly pass.
- Room V1–V4 schema hashes and packaged permissions are unchanged.
- Frozen SHA-256 values: debug `339f7597960a959f53fa79b168c838e4b25e325ea814e5d3f7d3bce83afc7d76`; Android test `e75d82674b8ee361075f1872cde37eb4585eca37470cb19999899f75c5025e5d`; unsigned release `27f397d0a4d517b67a013c868c11d5df63ebf390f400b5254c06e2f119fcf3d5`.

## Device result

The separately authorized frozen pair passed both exact 10-second collector invocations and produced 97 frames per sequence. The negative wrong-pose sequence evaluated on all frames and never locked. The positive centered sequence detected exactly one person on all frames but never reached evaluation because canonicalization failed, so it could not support threshold selection. The reports were pulled with matching device/host hashes, analyzed together after correcting same-dataset fragment merging, and removed from the device. The instrumentation package was uninstalled; the main app and unrelated data remain.

No threshold changed and automatic capture remains disabled. The complete result and cleanup are recorded in `docs/validation/2026-09-08-task16b-derived-calibration-pixel6.md`.
