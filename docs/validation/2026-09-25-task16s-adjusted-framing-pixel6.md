# Task 16S — participant-adjusted framing repeat

**Ground-truth correction:** The participant subsequently confirmed that their arms deliberately differed from the reference. This run is an intentionally different-pose example, not a valid positive. The original labels and positive-only analysis below are preserved for audit and superseded by the [corrected analysis](2026-09-25-task16t-arm-coaching.md). Scalar values and cleanup evidence are unchanged.

The participant reported that tilting the phone while alone needed more time, explicitly deferred that timing/process change, and requested the same test again after improving camera framing. No timing, code or policy change was made. This request supplied physical readiness for one repeat.

The spoken collector passed 1/1 in 48.128 seconds. All 91 recorded frames were fully evaluated, with one person, 17 qualified landmarks and four torso anchors. Complete-body readiness passed. Strict replay produced no lock or capture command. Framing scale improved compared with Task 16P, and angular similarity now passed throughout; centering, scale, positional and overall criteria remain below their respective thresholds. This does not establish usable solo camera-adjustment timing or prove “Good” was emitted: no cue transcript is exported.

## Protocol and artifacts

Pixel 6/oriole, camera permission, absent export directory and unused host report path were verified before launch. Local and installed main hash: `1374ed781e9b9fae99fa695d9a52743569979191eff35397d07e8cd0ec393ef6`; test hash: `93fdb4b5bed065e6732827d1c87de93d9cc184a36da304d066fbd13fa1840ab8`.

Method: `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`; authorization `user-authorized-derived`; dataset `pixel6-match-confirmed-a`; sequence `positive-confirmed-landscape-b`; fixture `positive`; case `centered-match`; preparation `30000` ms; measurement `10000` ms. Do not reuse this sequence identifier.

## Scalar results

Center similarity: 0.774677–0.777426. Scale similarity: 0.786952–0.790808. Both require 0.800; center limited all recorded frames. Retained scale scores are symmetric and cannot determine a closer/farther instruction.

| Criterion | Minimum | Median | Maximum | Passing frames | Required |
|---|---:|---:|---:|---:|---:|
| landmarkCoverage | 0.930108 | 0.936670 | 0.945856 | 91/91 | 0.75 |
| framingScore | 0.774677 | 0.776095 | 0.777426 | 0/91 | 0.8 |
| angularSimilarity | 0.861557 | 0.864669 | 0.866208 | 91/91 | 0.85 |
| positionalSimilarity | 0.705074 | 0.709486 | 0.714437 | 0/91 | 0.8 |
| overallMatch | 0.784939 | 0.787097 | 0.788723 | 0/91 | 0.825 |

## Retained files and cleanup

Only scalar report/framing diagnostics and independent strict analysis were retained in ignored `build/calibration/device/`:

- `positive-confirmed-landscape-b.json`: `162304dae2e472ba57659126231c97e7ccc6a044eecd3504780191cc8f7ec3de`.
- `positive-confirmed-landscape-b-framing.txt`: `aa9f3d228a4595ba844ebe28fecd62a3c3cacadd90bb2e3dadc863a3fbe0b661`.
- `pixel6-match-confirmed-b-analysis.json`: `0df9126acddba286d7398e23cf7746d2c461da876e5664f1dbce21e276ab226d`.
- `pixel6-match-confirmed-b-analysis.md`: `72ce2373f8a769f8c25ac1b2d3dbfb75509e9764fd4bb52f5d7d72b4b20335b9`.

Report bytes matched the device-announced SHA-256 before cleanup. Exact report/temp files and the empty export directory were removed. Main app was stopped, test package removed, and absence of the export directory/test package and any Pose Guide Snap camera client was verified. Main app/data were preserved. No private images, recordings or raw landmarks were pulled. See the [instrumentation output](2026-09-25-task16s-camera-output.txt).

Solo camera-adjustment timing/process remains deferred at the participant's request. Keep the current implementation unchanged until that work resumes; the participant subsequently confirmed that “Good” was not heard (see feedback below).

## Participant feedback after the run

The participant did not hear “Good.” They heard “Facing the camera, move a little to your right,” followed by “Hold still for 10 seconds.” This confirms that a successful collection did not establish audible adjustment confirmation. The current flow starts measurement after timed preparation and complete-body readiness, without requiring the requested framing correction to be satisfied. Therefore the hold instruction must not be interpreted as confirmation of correct framing. Recorded center similarity remained below threshold; these measurement frames do not establish exactly what happened during preparation, whose per-cue history is not retained.

Future deferred process work should distinguish confirmed adjustment from the end of preparation and avoid an ambiguous correction-to-hold transition. No additional camera run, code change or threshold relaxation is authorized by this feedback alone. Solo camera-adjustment timing/process remains deferred per the participant's earlier request.
