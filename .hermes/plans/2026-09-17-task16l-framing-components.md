# Task 16L — separate framing components

**Status:** Device collection complete: 1/1 pass in 26.637 seconds, 97 fully evaluated frames. Center similarity `0.381554–0.386197` was lower than scale similarity `0.691971–0.711420` throughout; both failed `0.800`. No match lock occurred. The earlier 3/3 synthetic checks also pass. See the [result](../../docs/validation/2026-09-17-task16l-framing-components-pixel6.md) and [next placement check](2026-09-17-task16m-recentered-reference-match.md).

## Evidence and hypothesis

Task 16K's intended reference match had one accepted person, 17 qualified landmarks, and four torso anchors in all 97 frames, yet framing stayed between `0.326322` and `0.347581` against `0.800`. Other pose scores also need calibration. See the [Task 16K evidence](../../docs/validation/2026-09-17-task16k-reference-match-pixel6.md).

The framing score is the minimum of center and scale similarity. A mismatch in image composition is the current hypothesis; the retained combined score cannot identify which component failed. This diagnostic reduces each accepted report frame to separate center/scale extrema plus evaluated/unavailable counts. No production calculation or threshold changes, and no coordinates, body extents, images, or observations are retained.

## Exact next collection

- Main APK: unchanged `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66`.
- Android test APK: `c768792ed7d10bd7cd9e19f3ceccfe593cbd60c7a5f987505c13138912716cbf`.
- Method: `com.tonyisup.poseguidesnap.calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedFramingDiagnosticSequence`.
- Authorization argument: `user-authorized-derived`.
- Dataset: `pixel6-match-bright-a`; sequence: `positive-centered-framing-bright-a`.
- Fixture: `positive`; case: `centered-match`.
- Warm-up: `15000` ms; collection: `10000` ms.

Use the working lighting/camera setup and displayed meditation reference. Keep the complete body visible. Do not prescribe a distance or lateral adjustment from the old combined score. The collector still requires five consecutive exactly-one-person frames at the end of warm-up and at least 20 recorded frames.

## Interpretation

- Compare center and scale ranges independently with the existing framing gate; both must pass for framing to pass.
- An unavailable sample is not a numerical zero. Read the counts before interpreting extrema.
- Scale similarity is symmetric: a low value alone does not reveal whether the body appears too small or too large. Center similarity likewise does not encode movement direction. These aggregates identify the failing component, not a directional instruction.
- Keep the original Task 16K positive report and its failed-positive label. Evaluate the new report separately, alongside the scalar status summary. Matching calibration, a wrong-pose negative, repeated cases, and sustained performance remain incomplete.

## Boundaries and cleanup

Verify local/installed hashes and the wireless target, preserve the installed main app/data, and use a new host sequence filename. Pull only the closed schema-v3 report and retain the closed scalar instrumentation summary. Verify the report hash, remove exact report/temp files and the empty export directory, stop the app, remove only the test package, and verify no active camera clients. Automatic capture remains disabled.
