# Task 16M — recentered reference match

**Status:** Completed after the participant confirmed readiness. Collection passed 1/1 in 26.784 seconds with 97 fully evaluated frames, but no match lock. Center similarity improved to `0.787428–0.793429`; scale `0.663030–0.668498` limited every frame. Exact device cleanup passed. See the [Task 16M result](../../docs/validation/2026-09-17-task16m-recentered-reference-match-pixel6.md). Improve live alignment feedback before further participant repeats. The [flash cue](../../docs/validation/2026-09-17-warmup-flash-cue-pixel6.md) ran with the artifacts below.

## Reason for this check

Task 16L measured center similarity `0.381554–0.386197` and scale similarity `0.691971–0.711420`. Both failed `0.800`, and centering limited every frame. Pose detection remained complete. See the [evidence](../../docs/validation/2026-09-17-task16l-framing-components-pixel6.md).

The public reference body center is about 39% across and 53% down the full camera frame. Ask the participant to re-aim the phone so the middle of the complete seated body is roughly halfway down the full frame, slightly left of center, while keeping their whole body visible. Preserve lighting and imitate the displayed meditation pose. Do not prescribe closer/farther movement: the retained scale similarity is symmetric and gives no direction. These instructions test composition, not a policy change.

## Exact collection

- Debug APK: `8285ee02965cd7957beab44745c50cb3b198b8a92807530370fe29c5f22205bc`.
- Android test APK: `a6c28b202a9898250101a6a8ad926bde9e02986a4b502e0f01963e6b9591a8f7`.
- Method: `com.tonyisup.poseguidesnap.calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedFramingDiagnosticSequence`.
- Authorization argument: `user-authorized-derived`.
- Dataset: `pixel6-match-bright-a`; sequence: `positive-recentered-framing-bright-a`.
- Fixture: `positive`; case: `centered-match`.
- Warm-up: `15000` ms; collection: `10000` ms.
- One brief rear-light blink marks the start of warm-up. The light is turned off after a 250 ms programmed pulse and acknowledged off before the remaining warm-up completes. The 15-second deadline includes the pulse and off-acknowledgement time; measurement never begins if the cue fails.

Verify target/local/installed hashes, an unused host sequence filename, and an absent device export directory. Preserve the main app and its data. Keep the accepted-person preflight and frame bounds. Retain only the closed report and scalar framing summary; verify the report hash, remove the exact report/temp files and empty directory, stop the app, remove only the test package, and verify no active camera client.

The earlier Task 16L main/test binaries are preserved under the ignored `build/calibration/artifacts/` directory. No matching threshold, model, coordinate calculation, or report schema changed; the cue changes warm-up illumination briefly, so record the new artifact hashes with the next result.

## Decision

Compare center and scale components separately, then the independent angular/positional/overall criteria. Better centering alone is not a complete match. If the target remains difficult to reproduce, improve the collector's live alignment feedback before further blind repeats. A wrong-pose negative, repeated cases, and performance evidence remain pending. No threshold changes or automatic-capture enablement follow from this single repeat.
