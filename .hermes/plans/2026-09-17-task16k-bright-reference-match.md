# Task 16K — reference-pose matching in the working camera setup

**Status:** Positive collection complete: 1/1 mechanical pass, 97 evaluated one-person frames, no match lock. Framing failed 97/97 frames; negative collection remains pending. A test-only component diagnostic is prepared before another pose attempt. See the [result](../../docs/validation/2026-09-17-task16k-reference-match-pixel6.md) and [Task 16L plan](2026-09-17-task16l-framing-components.md).

## Basis

Tasks 16I and 16J separated full-body presence (96/96 frames) from an empty view (97/97 frames) using the existing `0.25` detector gate. See the [pair evidence](../../docs/validation/2026-09-17-task16j-bright-empty-scene-pixel6.md). Detector-positive labels do not supply reference-match ground truth. Use a separate matching dataset for this checkpoint.

## Unchanged artifacts and method

- Debug SHA-256: `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66`.
- Android test SHA-256: `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9`.
- The original test APK is retained at ignored `build/calibration/artifacts/task16h-app-debug-androidTest.apk`; the default build output now contains the separate Task 16L diagnostic APK.
- Method: `com.tonyisup.poseguidesnap.calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDerivedSequence`.
- Authorization argument: `user-authorized-derived`; dataset: `pixel6-match-bright-a`.
- Warm-up: `15000` ms; collection: `10000` ms.
- Retain the existing requirement that warm-up ends with at least five consecutive exactly-one-person frames. No new app build or production policy is needed.

## Participant cases

1. Positive: sequence `positive-centered-bright-a`, fixture `positive`, case `centered-match`. Keep the successful lighting and camera setup, imitate the displayed bundled meditation reference, and keep the complete body visible. Coordinate positioning before launch. Retain scalar evidence even if matcher gates reject it; do not relabel based on the output.
2. Negative: sequence `negative-wrong-pose-bright-a`, fixture `negative`, case `wrong-pose`. After the positive test and renewed participant readiness, keep one complete person visible in a clearly different pose with the same lighting/camera setup. This is a matching negative and must not use the detector-gate method or be pooled with empty-scene detector controls.

Analyze per-frame evaluation status, landmark coverage, framing, angular/positional similarity, overall score, and replayed lock behavior. A failed positive requires investigating the specific failed gate and collection geometry before considering any threshold change. One pair cannot calibrate a production policy or enable automatic capture.

## Device and evidence handling

Verify the wireless target, local and installed hashes, camera permission, absent previous device report, and unused host sequence filename. Reuse the main app and preserve its data. Pull only the closed scalar report, verify the device-reported hash, and remove only the exact report/temp files and empty export directory. Stop the app, remove only the test package, and verify no active camera client. Preserve previous ignored reports and analyze this matching dataset separately. Record the result and leave automatic capture disabled.
