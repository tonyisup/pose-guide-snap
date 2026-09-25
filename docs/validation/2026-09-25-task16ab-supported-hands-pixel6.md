# Task 16AB — first supported-hand Pixel run

## Procedure and outcome

The participant said Ready for one supported-hand spoken run. The Pixel 6/oriole was awake and unlocked at preflight and immediately before launch. Camera permission, exact installed APK hashes, absent export directory, unused output names and no Pose Guide Snap camera client were verified. The participant was reminded to keep the sideways rear-camera setup and follow the supported knee-rest instructions.

- Main APK SHA-256: `be4e724cecea95534f90ac4f691cd8dc3d224b8bc02f5f239d98b8d9b3992195`.
- Test APK SHA-256: `d944ad68a656207df6aeb9b18e7711f03399f1f86da2bcb9eb89db152752a536`.
- Method: `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`.
- Authorization: `user-authorized-derived`; dataset `pixel6-supported-hands-a`; sequence `positive-supported-hands-landscape-a`; fixture `positive` (intended reference, provisional); case `centered-match`.
- Preparation deadline: 60000 ms; earliest hold: 30000 ms; requested measurement: 10000 ms.

Instrumentation reports **1 failure in 69.595 seconds**, `Preparation timed out without settled, resolved adjustments`. This is the designed unresolved-preparation stop path. The stop announcement completed before the gate threw; no hold, measurement report or capture started. Elapsed instrumentation time includes startup and speech. This is not a successful collection or a match-accuracy result.

See the [run output](2026-09-25-task16ab-camera-output.txt), SHA-256 `6f3c10cd908d53dc777d90c4f42639e0c5db47874f454cfe8f49bd3aa966279e`.

## Evidence and limits

Completed cues: **RIGHT_HAND_ON_KNEE=3**, **GOOD=0**, every other adjustment cue zero. The right-hand cue says: “Rest your right hand on your right knee, palm up. Let your arm relax.” These are completed counts, not an ordered transcript or participant confirmation of hearing them.

The terminal preparation snapshot reports:

```text
result=TIMED_OUT blockers=DEADLINE,ARM_POSITION,FRAMING,UNCONFIRMED_ADJUSTMENT
```

At that terminal decision, fresh complete-body tracking and the stillness check passed. Speech, the quiet pause and the initial allowance were not blocking. Arm-position and framing checks failed, and an adjustment remained unconfirmed. This identifies the final gate state, not the history of every frame or a cause of the positional discrepancy. In particular, the timeout cannot be attributed solely to shakiness. Fine framing is deliberately queued behind arm correction in the current guide; no framing instruction was completed in this run. The closed FRAMING flag does not identify which framing adjustment was needed.

The bounded requested-joint diagnostic recorded **430 samples**, uncapped, with zero gaps:

| State | Samples |
|---|---:|
| SPEAKING | 123 |
| UNAVAILABLE | 0 |
| UNSETTLED | 95 |
| MISMATCH | 212 |
| SETTLING | 0 |
| READY | 0 |
| EXPIRED | 0 |

The counts sum to 430. `stableMaxMs=0` means no continuous requested-position acceptance interval was recorded. `postSpeechWindowMinMs=14883` is the minimum preparation time remaining after a completed arm instruction, not an observed movement duration. Eligible right-wrist/requested-joint error ranged 0.2619–0.4697 torso lengths, entirely above the unchanged 0.25 radius; same-arm elbow error ranged 0.2001–0.2830. These aggregates cover the tracked request, not every joint throughout preparation. UNSETTLED includes stillness-window accumulation and is not a count of verified movements.

The wording names a supported destination, while acceptance still compares projected reference wrist position. The participant subsequently reported that the right hand was already resting on the knee at the first instruction, and that the repetitions left them unable to work out how to adjust. This establishes an unhelpful instruction loop. Their response does not specify any subsequent movement, and the scalar evidence cannot verify contact or reconstruct a trajectory. Code inspection confirmed that wrist acceptance still targeted the reference person's torso-relative wrist location rather than the participant's own knee. That inconsistency can reject a supported hand when knee position differs; it is reproducible synthetically, but its contribution versus detector error in this unretained physical run cannot be quantified. Do not widen acceptance thresholds or claim support detection from these aggregates.

## Cleanup and next step

The report and temporary report were verified absent. Main was stopped, the exact report/temp cleanup applied, the empty export directory removed and only the test package uninstalled. Main and app data were preserved, installed main hash reverified, and test/export absence plus no Pose Guide Snap camera client confirmed. No private images, recordings, coordinates or trajectories were retained. No source/APK/timing/threshold changes were made during this run.

The sequence ID is consumed despite the absence of a report. Participant feedback led to Task 16AC: align the hand-placement target with the same-side observed knee and replace repeated identical commands with one explicit inability-to-confirm explanation. Repeated identical support instructions did not resolve the measured right-wrist mismatch in this attempt. Another physical run needs fresh Ready; do not repeat automatically after the stop announcement. Solo phone-adjustment workflow redesign remains deferred.
