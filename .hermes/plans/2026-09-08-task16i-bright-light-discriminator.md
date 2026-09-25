# Task 16I — bright-light detector discriminator

**Status:** Completed on 17 September 2026 over wireless ADB after the participant confirmed readiness. The exact method passed 1/1 with 96/96 one-person, fully evaluated frames and all 17 landmarks confidence-qualified. No source, APK, or threshold changed. See the [validation record](../../docs/validation/2026-09-17-task16i-bright-light-pixel6.md).

## Root cause hypothesis

Insufficient camera-scene illumination suppresses the live MoveNet response until a full-body frame is statistically indistinguishable from empty-scene noise. The frozen Task 16H pair already proves that lowering the current `0.25` gate is unsafe under the first collection condition.

## Evidence behind the hypothesis

1. The static model/package/preprocessing/inference/mapping contract passed on the earlier Task 16D artifact pair in Task 16E; it was not rerun on the later Task 16H pair.
2. Task 16F proved that non-flat, changing CameraX pixels reach inference.
3. Task 16G measured valid but sub-threshold person/keypoint response.
4. Task 16H measured complete overlap between one full-body positive sequence and one empty-scene sequence: positive person-score p05/median/p95 `0.000`/`0.000`/`0.117226` versus negative p95 `0.122414`; positive keypoint p05/median/p95 `0.103079`/`0.123211`/`0.147787` versus negative p95 `0.150982`.
5. The camera conversion, crop, rotation, RGB packing, and model invocation code is unchanged from the earlier live CameraX slice. CameraX output rotation is enabled, and the analyzer requires zero remaining rotation. Source inspection does not by itself exclude a runtime geometry or color problem.

## Exact discriminator

Reuse the frozen Task 16H artifacts without rebuilding:

- debug `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66`;
- Android test `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9`.

Run only `calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDetectorGateSequence` with:

- dataset `pixel6-person-gate-a`;
- sequence `single-person-full-body-bright-a`;
- fixture `positive`;
- case `single-person-full-body`;
- warm-up `15000` ms;
- collection `10000` ms.

The participant should use bright, even front/overhead room lighting, avoid strong backlight, remain centered, and keep their complete body inside the displayed rear-camera preview throughout warm-up and collection.

## Decision rule

- If the bright positive distribution clears the existing empty-scene distribution, the changed shooting conditions are material. Lighting remains a candidate contributor, and a same-location empty-scene control plus repeated positive/negative collections are required before any policy proposal.
- If it remains indistinguishable from the existing empty-scene distribution, this repeat does not support lighting as a sufficient explanation. Investigate privacy-bounded CameraX geometry/color aggregates and controlled collection conditions next.
- If it improves but still overlaps, collect no threshold; repeat controlled lighting/distance cases first.

No single repeat selects a threshold, changes production policy, or enables automatic capture. Retain only the ignored schema-v3 scalar report and updated combined analysis. Perform the same exact device report, temporary-file, camera, test-package, and temporary-pull cleanup while preserving the main app and its data.

## Observed decision

Person-score min/p05/median/p95/max were `0.724650`/`0.746036`/`0.763399`/`0.768429`/`0.771101`, above the unchanged `0.25` gate throughout. All 96 frames had one accepted person, 17 qualified landmarks, and four torso anchors. The old-room empty-scene comparison is exploratory; a new-location control is still needed. Exact report/test-package cleanup completed, with the main app and its data preserved and no active camera clients.

The subsequent [Task 16J same-setup control](../../docs/validation/2026-09-17-task16j-bright-empty-scene-pixel6.md) passed with 97/97 no-person frames and a maximum person score of `0.052640`. The unchanged detector gate separates the pair. The [next prepared checkpoint](2026-09-17-task16k-bright-reference-match.md) measures reference-pose matching in a separate dataset.
