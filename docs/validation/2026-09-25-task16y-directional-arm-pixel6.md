# Task 16Y — first directional hand-coaching run

## Authorized procedure

The participant said Ready after installation of directional joint coaching. The Pixel 6/oriole was awake and unlocked before launch; installed main/test hashes, camera permission, absent export directory and unused output names were verified. The sideways rear-camera setup and instruction to start with the left hand in the lap were carried forward. Actual movement and final reference-pose intent remain pending participant feedback.

- Main APK SHA-256: `6c53a901cbbdd8a76f9ebd89d35d66089c463d8aed8eaec5faff3e8df1ceb9d9`.
- Test APK SHA-256: `3ce2babe39d8d1b44c605a2186e4009788023480ee4b5f793617f12daecfea22`.
- Method: `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`.
- Authorization: `user-authorized-derived`; dataset: `pixel6-joint-guided-a`; sequence: `positive-joint-guided-landscape-a`; fixture: `positive` (intended final reference, provisional); case: `centered-match`.
- Preparation: 30000 ms minimum; measurement: 10000 ms.

Collection passed **1/1 in 48.450 seconds**. This verifies collection completion, not coaching success. See the [instrumentation output](2026-09-25-task16y-camera-output.txt).

## Coaching evidence

Completed adjustment cues were **LEFT_WRIST_UP=1**, spoken as “Raise your left hand slightly,” and **GOOD=0**. All other adjustment cues were zero. The diagnostic applies to the requested wrist alone (`scope=joint`), unlike earlier whole-arm confirmation reports.

- 172 samples, uncapped; zero gaps or unavailable samples.
- 21 SPEAKING, 146 MISMATCH, five SETTLING; zero READY or EXPIRED.
- Requested-joint error: 0.2017–1.0075 torso lengths during eligible samples. Elbow error: 0.1731–0.3207.
- The wrist entered the unchanged 0.25 radius, but the longest continuous accepted interval was **200 ms**, below the required 1000 ms.
- **15272 ms remained** when the instruction completed.

The recorded reason for no Good is insufficient continuous in-tolerance evidence, despite continuous tracking and an available confirmation window. The bounded aggregates do not establish the movement's order, whether the participant overshot, whether they held still outside tolerance, or whether the spatial comparison was appropriate for their pose. Do not infer palm orientation or loosen acceptance to manufacture a confirmation.

There was also no opportunity for a second directional correction under the current schedule: 15272 ms remaining minus the eight-second quiet interval leaves 7272 ms, already inside the final-ten-second cutoff. Good remained eligible during that time. This is a schedule limitation relevant to continued hand coaching, not evidence that a particular reverse/second-axis cue was needed. Solo phone-adjustment timing/process remains deferred.

The participant subsequently reported raising the left hand to the knee, then lowering it just below the knee after noticing the word “slightly.” They described one smooth movement taking no more than three seconds from start to finish. This supports ambiguity in the instruction and does not support assuming they spent the whole preparation period adjusting. It does not identify which portion of the movement produced the 200-ms accepted interval, or confirm full final-pose ground truth. The positive label remains provisional. [Task 16Z](2026-09-25-task16z-coaching-followup.md) changes wording and allows time for follow-up.

## Measurement evidence

All **96/96** measurement frames contained one person, 17 qualified landmarks, four torso anchors and full evaluation. No mirrored candidate, replayed match lock or capture occurred. Center similarity was 0.743908–0.747672 and scale similarity 0.734261–0.738349; both were below 0.8. The requested positive fixture label is provisional and must not be used as confirmed positive accuracy evidence.

| Criterion | Minimum | Median | Maximum | Passing frames | Required |
|---|---:|---:|---:|---:|---:|
| landmarkCoverage | 0.936898 | 0.944345 | 0.954145 | 96/96 | 0.75 |
| framingScore | 0.734261 | 0.736564 | 0.738349 | 0/96 | 0.8 |
| angularSimilarity | 0.865652 | 0.867463 | 0.869133 | 96/96 | 0.85 |
| positionalSimilarity | 0.784062 | 0.788819 | 0.793385 | 0/96 | 0.8 |
| overallMatch | 0.824859 | 0.828133 | 0.830510 | 95/96 | 0.825 |

## Retention and cleanup

Ignored scalar-only artifacts under `build/calibration/device/`:

- `positive-joint-guided-landscape-a.json`: `ec7b3df672e8040ed4b74115db4b20165ce0e2206748a4b8c7c3147ffb82311f`.
- `positive-joint-guided-landscape-a-framing.txt`: `5a16a02d9e886fc1d4bdec8672f4eb59dddcc237aef6b656dbdada2da8771fdb`.
- `pixel6-joint-guided-a-provisional-analysis.json`: `d8da54e06c906615257f0fb5067e4929d06fc783a5489f7423e63af645033f23`.
- `pixel6-joint-guided-a-provisional-analysis.md`: `5533a4d88bd880ea4cca84ae2242b1319f72315c69a1f508aa4737b1f12fa247`.

Report bytes matched the device-reported SHA-256 before cleanup; the strict analyzer accepted the report independently. No images, recordings, raw landmarks or coordinates were retained. The exact report/temp files and empty export directory were removed; the main app was stopped and only the test package uninstalled. Test/export absence, the unchanged installed main hash and no Pose Guide Snap camera client were verified. Main app/data were preserved. No source, timing, threshold or APK changes occurred during this run.
