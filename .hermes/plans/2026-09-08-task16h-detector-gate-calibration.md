# Task 16H — detector-gate calibration evidence

**Status:** Complete. The frozen positive and empty-scene Pixel 6 collections each passed 1/1 with 97 frames and exact report-hash matches. Their detector-score distributions overlapped, so the evidence rejects a threshold change under this condition. Exact cleanup completed.

## Objective

Collect privacy-bounded per-frame detector-score distributions that can evaluate the current MoveNet person gate after Task 16G found positive but sub-threshold live evidence.

## Implementation boundary

1. Advance the derived calibration document to schema version 3 while retaining schema-v1/v2 analyzer compatibility.
2. Add only nullable maximum valid person and keypoint scores in `[0, 1]` to each derived frame.
3. Retain no person slot, keypoint identity, coordinate, tensor, image, pixel, path, URI, wall-clock timestamp, or personal identifier.
4. Add one separately selectable Android method that records below-threshold frames after a bounded camera warm-up instead of requiring the current person gate to pass.
5. Restrict its ground-truth labels to `positive` / `single-person-full-body` and `negative` / `no-person-empty-scene`.
6. Report positive and negative frame counts, p05, median, p95, extrema, and p05-minus-negative-p95 separation without selecting or applying a threshold.
7. Keep production model input, mapping threshold, match policy, report location, and automatic-capture disablement unchanged.

## Host verification

- 714/714 JVM tests pass with zero failures, errors, or skips.
- 14/14 Python analyzer tests pass.
- Lint reports zero errors and 14 warnings.
- Debug, unsigned release, and Android-test APK assembly pass.
- Room V1–V4 schema hashes and packaged permissions are unchanged.
- Frozen SHA-256 values: debug `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66`; Android test `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9`; unsigned release `996b45a89d91c6aac51cb6dac92900dcd32d32557aef2118832b2741520a21ae`.

## Device decision rule

The bounded first checkpoint needs one full-body single-person sequence and one empty-scene sequence on the same frozen Pixel 6 artifact pair. The analyzer may describe their score distributions and separation. It must not promote a threshold from two sequences. Any threshold candidate still requires repeated positive and negative collections that cover the documented distance, viewpoint, lighting, and false-positive conditions.

## Device result

- `single-person-full-body-a`: 1/1 pass, 97 frames, report SHA-256 `1faa6ca5208cfb6e85ecebd6caf03746cfa48d691d8ac5a18b4b76cc0794ba58`.
- `no-person-empty-scene-a`: 1/1 pass, 97 frames, report SHA-256 `50973bcfedb4500060f000db3f0b1ab9b18697a936cdc81b253f984daf920fb3`.
- Person-score positive p05/median/p95 were `0.000`/`0.000`/`0.117226`; negative p95 was `0.122414`.
- Keypoint-score positive p05/median/p95 were `0.103079`/`0.123211`/`0.147787`; negative p95 was `0.150982`.
- Both sequences were `no-person` for all 97 frames. No lock or capture command occurred.
- The distributions overlap and do not support lowering the unchanged `0.25` person gate. Automatic capture remains disabled.
- Exact cleanup removed device reports, the fixed temporary filename, the empty export directory, the instrumentation package, and temporary installed-APK pulls; the main app and its data remain installed and active camera clients are empty.
- See the [Task 16H validation record](../../docs/validation/2026-09-08-task16h-detector-gate-calibration-pixel6.md).
