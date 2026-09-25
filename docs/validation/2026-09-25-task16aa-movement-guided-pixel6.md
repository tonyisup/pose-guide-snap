# Task 16AA — first movement-aware Pixel run

## Procedure and outcome

The participant said Ready for the movement-aware protocol. Pixel 6/oriole, awake/unlocked state, camera permission, installed main/test hashes, absent export directory and unused output names were verified. The phone remained in the intended sideways rear-camera setup, with the participant instructed to start with the left hand in the lap and retain each new position after adjusting.

- Main APK SHA-256: `175974604166245348f6b9538a20a4a2828839787df7860ff4cc248b2105cc4e`.
- Test APK SHA-256: `56d8a3536925ad5a75c2b14469b3f06259efb6709de41cb97e55bd2a79428111`.
- Method: `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`.
- Authorization: `user-authorized-derived`; dataset `pixel6-movement-guided-a`; sequence `positive-movement-guided-landscape-a`; fixture `positive` (intended reference, provisional); case `centered-match`.
- Preparation deadline: 60000 ms, earliest possible hold: 30000 ms; requested measurement: 10000 ms.

The app completed **one Good** under the new positional-plus-stillness rules, then stopped at the preparation deadline because readiness was not confirmed. Instrumentation reports **1 failure in 69.835 seconds**, specifically `Preparation timed out without settled, resolved adjustments`. This is the designed stop path, not a successful collection. Its stop announcement completed before the gate threw. No hold, measurement report or capture was started. The elapsed test time includes startup and the stop announcement, not a longer preparation budget.

See the [run output](2026-09-25-task16aa-camera-output.txt), SHA-256 `5407e15bc2b78f69a9fdd8b1a850b146f45033306ba24e4206e6be1c6ae72756`.

## Fixed cue and confirmation evidence

Completed cue counts:

- LEFT_WRIST_UP: 2.
- LEFT_WRIST_RIGHT: 1.
- GOOD: 1.
- Every other adjustment cue: 0.

These are counts, not an ordered transcript. The only requested joint was the left wrist, so the Good confirms that joint under the coach's rules. The participant subsequently confirmed hearing Good. They described following the directional wording literally: raising the left hand to hover above/left of the knee, moving it right while still hovering, then hearing Good and trying to maintain that position. They suspect the unsupported arm was shaky. This establishes missing support instructions, not a measured cause of the final timeout. It does not independently establish precise stillness timing or absence of interruptions. Good accepted the projected wrist position, not physical support or palm orientation.

The bounded arm diagnostic recorded **261 samples**, uncapped, with zero gaps:

| State | Samples |
|---|---:|
| SPEAKING | 86 |
| UNAVAILABLE | 0 |
| UNSETTLED | 85 |
| MISMATCH | 89 |
| SETTLING | 0 |
| READY | 1 |
| EXPIRED | 0 |

The state counts sum to 261. `stableMaxMs=1205` reflects continuous target-position acceptance. READY additionally requires the independent motion window to be settled. `postSpeechWindowMinMs=19002` is the minimum preparation time remaining after a completed arm instruction. Eligible wrist/requested-joint error ranged 0.1890–0.9824 torso lengths; same-arm elbow error ranged 0.1851–0.5180. The joint acceptance radius remains 0.25.

UNSETTLED includes initial stillness-window accumulation and does not mean 85 verified movement events. No unavailable evidence or gaps were recorded while a wrist request was being tracked; the summary does not describe all later frames after Good cleared that request. The output supports one accepted settled wrist correction and a successful deadline stop. It cannot identify the final readiness blocker among other joint error, framing, renewed movement, tracking or remaining quiet time. Do not guess the blocker or loosen thresholds from these aggregates. This run provides no positive/negative match-accuracy result.

## Cleanup and next decision

The device report and temporary report file were verified absent. The exact report/temp cleanup was applied, the empty export directory removed, main stopped and only the test package uninstalled. Main/data and its installed hash are preserved; test/export absence and no Pose Guide Snap camera client were verified. Only fixed cue/diagnostic counts and instrumentation status were retained; no images, recordings, trajectories or raw coordinates were retained. No source/APK/timing/threshold change occurred during this run.

Participant feedback led to [Task 16AB supported-hand coaching](2026-09-25-task16ab-supported-hand-coaching.md), including bounded readiness reason flags. The sequence ID is consumed even though no report exists. Do not repeat automatically after the relax announcement; another physical run needs fresh Ready. This attempt is not a verified supported meditation pose and has no match-accuracy report to reclassify.
