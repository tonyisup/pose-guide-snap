# Task 16AC — first knee-relative Pixel run

## Procedure and outcome

The participant said Ready for one spoken guided sequence with hands resting on the knees. Pixel 6/oriole was awake and unlocked at preflight and immediately before launch. Camera permission, installed hashes, absent export directory, unused output names and no own active camera client were verified.

- Main APK SHA-256: `764dacc9d848525343fe702642b150ca0367352da623a5b24c0dd2638943a668`.
- Test APK SHA-256: `d944ad68a656207df6aeb9b18e7711f03399f1f86da2bcb9eb89db152752a536`.
- Method: `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`.
- Authorization: `user-authorized-derived`; dataset `pixel6-knee-relative-a`; sequence `positive-knee-relative-landscape-a`; fixture `positive` (intended reference, provisional); case `centered-match`.
- Preparation deadline: 60000 ms; earliest hold: 30000 ms; requested measurement: 10000 ms.

The app completed RIGHT_HAND_ON_KNEE once and RIGHT_HAND_UNCONFIRMED once, then announced a stop before hold/measurement. GOOD and all other adjustment cues were zero. Instrumentation reports **1 failure in 69.522 seconds**, `Preparation timed out without settled, resolved adjustments`, the designed unresolved-preparation stop. Startup and the stop announcement contribute to elapsed test time. No report or capture was produced.

The participant confirmed both that their right hand was comfortably resting on the knee throughout the attempt and that the inability-to-confirm message was clear. This supports the instruction-loop fix and establishes a participant-reported supported position rejected by the current coaching check. It does not establish physical contact from camera landmarks or a whole-pose match.

See the [run output](2026-09-25-task16ac-camera-output.txt), SHA-256 `33065403566d98bb9d7acb6205b8fcec2f0c84f4fcb084de0ff4608b9319349b`.

## Fixed evidence and limits

```text
result=TIMED_OUT blockers=DEADLINE,ARM_POSITION,FRAMING,UNCONFIRMED_ADJUSTMENT
```

Tracking, stillness, speech completion and quiet time passed at the terminal decision. Arm position and framing did not, and the hand request remained unconfirmed. Fine framing is queued behind the unresolved arm correction; no framing instruction completed. The terminal snapshot is not a claim of stillness throughout the attempt.

Bounded arm diagnostics recorded **434 samples**, uncapped, zero gaps, `stableMaxMs=0` and `postSpeechWindowMinMs=24822`:

| State | Samples |
|---|---:|
| SPEAKING | 106 |
| UNAVAILABLE | 0 |
| UNSETTLED | 50 |
| MISMATCH | 278 |
| SETTLING | 0 |
| READY | 0 |
| EXPIRED | 0 |

The counts sum to 434. With `wristTarget=same_side_knee elbowTarget=reference`, eligible right-wrist distance ranged **0.2422–0.3397 torso lengths**; same-side elbow reference error ranged 0.1715–0.2293. The fixed hand radius is 0.25. The minimum crossed that radius, but no continuous accepted-position interval was recorded and there was no settled confirmation. These aggregates cannot reconstruct when that minimum occurred or distinguish anatomical offset, detector bias, detector jitter and actual small movements. Wrist ranges from the earlier reference-target version are not directly comparable.

The hand-position heuristic still needs evidence from supported and deliberately displaced controls. Do not widen the radius from this single supported attempt: that could also admit hands in the lap. The next work prepares a distinct two-position diagnostic, without changing coaching or match thresholds.

## Cleanup

Device report/temp absence was verified. Main was stopped, exact report/temp cleanup applied, the empty export directory removed and only the test package uninstalled. Main/data and its installed hash were preserved. Test/export absence and no Pose Guide Snap camera client were verified. No private images, recordings, coordinates or trajectories were retained. No source or APK change occurred during this physical run.

The dataset/sequence identifiers are consumed even though no report exists. No automatic rerun occurred. Follow-up development is [Task 16AD hand-placement comparison](2026-09-25-task16ad-hand-placement-comparison.md); its physical run requires fresh Ready.
