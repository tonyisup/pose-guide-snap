# Task 16X — arm-confirmation diagnostic

## Startup attempt and unlocked retry

The initially authorized Task 16W attempt (`positive-arm-confirmation-landscape-a`) timed out at camera readiness in 30.661 seconds before speech/preparation or measurement. No report or arm diagnostic was generated. Exact export/test cleanup succeeded. The participant then said they had forgotten to unlock the phone and that it was now unlocked. This is a plausible explanation for the timeout, not proof of a new code regression. Subsequent narrow system checks showed Awake, keyguard showing=false, inputRestricted=false and no active camera clients (`[]`). Do not reuse the first sequence ID. See the [timeout output](2026-09-25-task16w-camera-output.txt).

After the participant unlocked the phone, the authorized unchanged-artifact retry passed **1/1 in 47.833 seconds**. Unlocked state, Pixel 6/oriole, camera permission, local/installed hashes, absent export directory and unused output path were verified first. No code, threshold, timing or instruction change was made between these attempts. Future launches must check unlocked state before asking the participant to move into position.

- Main: `09f913b7aacd5321dd35afcd917b53c981ef68d7ce4552893113d1a015bf507e`.
- Test: `3ce2babe39d8d1b44c605a2186e4009788023480ee4b5f793617f12daecfea22`.
- Method: `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`.
- Authorization: `user-authorized-derived`; dataset: `pixel6-arm-confirmation-diagnostic-a`; sequence: `positive-arm-confirmation-landscape-b`; fixture: `positive` (intended final pose, provisional); case: `centered-match`; preparation `30000` ms; measurement `10000` ms.

## Why confirmation did not play

The speech engine completed LEFT_ARM once and GOOD zero times; all other adjustment cues were zero. The diagnostic sampled 175 frames after the arm request without capping:

- 47 frames while speech was pending; 128 eligible frames classified MISMATCH.
- Zero unavailable frames, zero frame gaps, zero stable accumulation, zero READY and zero EXPIRED frames.
- **12985 ms remained** when the arm instruction completed.
- Eligible elbow error: **0.2721–0.3526 torso lengths**.
- Eligible wrist error: **0.2928–0.3881 torso lengths**.
- Both errors must be at most **0.25** for one continuous second; neither entered that range in any eligible sample.

Thus the observed decision was spatial rejection, not a missing completion callback, tracking interruption or lack of an available confirmation window. There is no evidence implicating palm orientation, which this path cannot measure. The record does not establish whether the reference comparison is too rigid for the participant, whether the instruction was too vague, or whether the participant's perceived placement differs from the target in image space. Do not equate this diagnostic with a fixed coaching defect or relax the tolerance to force “Good.” Error magnitudes are unsigned: they cannot justify a guessed up/down/left/right correction.

Next development should make coaching actionable for the actual residual elbow/wrist displacement, using transient signed geometry and a regression test for the specific requested adjustment. Keep production matching thresholds unchanged and retain physical validation as pending. The deferred solo camera-adjustment process is a separate issue.

## Measurement evidence

All 96 frames were fully evaluated, with one person, 17 qualified landmarks and four torso anchors. Center similarity 0.814434–0.822425 passed 0.800 throughout; scale 0.744240–0.768868 failed and limited framing. No match lock or capture occurred. The requested positive label remains provisional until final pose intent is confirmed; analysis filenames say provisional and must not be used as positive accuracy evidence yet.

| Criterion | Minimum | Median | Maximum | Passing frames | Required |
|---|---:|---:|---:|---:|---:|
| landmarkCoverage | 0.943371 | 0.960605 | 0.967221 | 96/96 | 0.75 |
| framingScore | 0.744240 | 0.764520 | 0.768868 | 0/96 | 0.8 |
| angularSimilarity | 0.900985 | 0.903558 | 0.908703 | 96/96 | 0.85 |
| positionalSimilarity | 0.729038 | 0.765884 | 0.771626 | 0/96 | 0.8 |
| overallMatch | 0.817017 | 0.834437 | 0.837447 | 78/96 | 0.825 |

## Retention and cleanup

Ignored scalar-only files under `build/calibration/device/`:

- `positive-arm-confirmation-landscape-b.json`: `eaa32730427a18c7b010dac6ac786833749fde02396f973d00cb83b944db43f6`.
- `positive-arm-confirmation-landscape-b-framing.txt`: `a9ff4c5672126cad98b532904f5b2ed10cf19fe43648705590622080a0bf5109`.
- `pixel6-arm-confirmation-b-provisional-analysis.json`: `7ca6f32f444619f19a80fc85639da11414ed2af78536a422c960c541ebb0ea5c`.
- `pixel6-arm-confirmation-b-provisional-analysis.md`: `be3d056359cdd2be4a0e46c370918b8aaf4323259dd46ef9110f9a7726e42463`.

Report bytes matched the device SHA-256 before cleanup. The [run output](2026-09-25-task16x-camera-output.txt) retains only instrumentation status, scalar framing and fixed cue/confirmation aggregates. No images, recordings, raw landmarks or coordinates were retained. Exact report/temp and empty export directory were removed, main app stopped and test package uninstalled. Test/export absence and no Pose Guide Snap camera client were verified; main app/data preserved.
