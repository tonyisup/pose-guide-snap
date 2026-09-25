# Task 16V — arm-guidance retry

After fresh physical Ready, the preview-fixed spoken collector completed **1/1 in 47.810 seconds**. The preview startup fix worked in this physical run. All 97 measurement frames were fully evaluated with one person, 17 qualified landmarks and four torso anchors. No match lock or capture occurred.

**Completed speech counts: LEFT_ARM=1, GOOD=0; every other adjustment cue=0.** The speech engine completed the left-arm instruction, but no confirmation completed. This does not prove whether the participant heard or understood it, completed the requested correction, or whether the coaching tolerance was appropriate. Full-body evidence was available during measurement; retained data do not contain per-arm errors or a preparation trajectory. Ask whether the participant moved their left hand into the requested position before the hold instruction. Do not infer failed detection or participant noncompliance.

## Protocol and verified artifacts

The participant was asked to keep the phone sideways, initially leave their left hand in their lap, and follow the spoken arm instruction. No device rotation check or screenshot was performed. Pixel 6/oriole, camera permission, absent export directory and unused host report path were verified.

- Main APK: `544745b1c6d7c16ca26eb2dd5b5739fb82b1b92def45a6ae12941cb58f2ed5c1`.
- Android test: `17e971b59e461317086757a05d4fc375514761f36ee8ee997a28467d74337043`.
- Method: `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`.
- Authorization: `user-authorized-derived`; dataset: `pixel6-match-arm-guided-a`; sequence: `positive-arm-guided-landscape-b`; fixture: `positive` (intended final reference pose, unconfirmed); case: `centered-match`; preparation: `30000` ms; measurement: `10000` ms.

This sequence identifier was used and must not be reused. The original positive label is provisional pending participant clarification of the final pose. The replay filenames explicitly say provisional. Do not include this report in positive calibration accuracy aggregates until ground truth is confirmed, and do not treat a successful collection as successful coaching.

## Scalar results (independent of pending ground truth)

Center similarity: 0.764334–0.772818; scale similarity: 0.791426–0.799581. Both remained below 0.800; center was the limiting component throughout. A symmetric scale score does not establish a closer/farther direction.

| Criterion | Minimum | Median | Maximum | Passing frames | Required |
|---|---:|---:|---:|---:|---:|
| landmarkCoverage | 0.973559 | 0.976010 | 0.978116 | 97/97 | 0.75 |
| framingScore | 0.764334 | 0.769721 | 0.772818 | 0/97 | 0.8 |
| angularSimilarity | 0.874178 | 0.876348 | 0.878892 | 97/97 | 0.85 |
| positionalSimilarity | 0.786928 | 0.792381 | 0.797028 | 0/97 | 0.8 |
| overallMatch | 0.831566 | 0.834590 | 0.837011 | 97/97 | 0.825 |

## Retained evidence and cleanup

Ignored files under `build/calibration/device/`:

- `positive-arm-guided-landscape-b.json`: `ca478d2a81feff7c08e3cab9254b3507e4b836fe434b629bf023f90b774be00b`.
- `positive-arm-guided-landscape-b-framing.txt`: `9637a7f272ddf027422549b33e9a460359b834ebcfa4143089641be833cb4d0c`.
- `pixel6-arm-guided-b-provisional-analysis.json`: `6ef51bf7f0d3bd6e38211e6c4a433e819097dd18ae8edcfeb3668666f5f0842f`.
- `pixel6-arm-guided-b-provisional-analysis.md`: `280ce5d4784dd7159fb9c2e857cfd97598b094ecb7ea25c078e5272fcca00eb5`.

Device-announced report hash matched pulled bytes before cleanup. Fixed-vocabulary completed-cue counts and instrumentation output are in the [run output](2026-09-25-task16v-camera-output.txt). Exact report/temp files and empty export directory were removed, main app stopped, and test package removed. Export/test absence and no Pose Guide Snap camera client were verified; main app/data preserved. No image, recording, raw landmarks or coordinates retained. Solo camera-adjustment timing/process remains deferred.

## Participant clarification

The participant reports that the left hand was already in the requested position before the hold cue. They later turned the palm downward before hold but do not know the interval. This rules out assuming they were still following the instruction when hold began. Palm orientation is not represented by this MoveNet arm-coaching path; the model supplies elbow/wrist positions and confidence, not palm/finger orientation. Motion could indirectly affect tracked wrist position/confidence, but there is no evidence that it caused the missing confirmation. The final whole-body ground truth remains provisional; confirming the left-hand action alone does not establish an exact full-reference pose.

Task 16W adds bounded confirmation diagnostics before changing any acceptance or timing rule. See the [diagnostic record](2026-09-25-task16w-arm-confirmation-diagnostics.md).
