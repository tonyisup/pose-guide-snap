# Task 16AD — supported-hand versus lap comparison

## Reason for the diagnostic

In the [Task 16AC Pixel run](2026-09-25-task16ac-knee-relative-pixel6.md), the participant confirmed that the right hand stayed comfortably supported on the knee and the one-time explanation was clear. The coach still timed out with arm-position and framing blockers. Observed right-wrist-to-knee distance ranged 0.2422–0.3397 torso lengths around the uncalibrated 0.25 radius, without a confirmed interval. This is insufficient evidence to widen that radius: a change could also accept hands resting in the lap.

This separate diagnostic compares two explicitly requested positions. It does not run the normal coaching preparation gate, label a whole pose as positive/negative, adjust thresholds or trigger capture. Coaching and production scoring remain unchanged.

## Protocol

The offline voice gives the entire procedure. With the phone sideways using its rear camera:

1. Rest both hands on the knees, palms up. After that instruction completes, a flash marks **30 seconds** to get comfortable.
2. Once fresh full-body stillness is observed after the allowance, the voice announces a **10-second measurement**. Fresh post-speech stillness is required before that window opens.
3. Place both hands in the lap, away from the knees. A flash marks **15 seconds** to adjust after the instruction finishes.
4. After settling, another **10-second measurement** is announced, followed by “Comparison finished. You can relax.”

Typical duration is around 90 seconds including speech. Each initial allowance can extend by at most 15 seconds for tracking/stillness; each post-measurement-instruction settling wait is separately capped at 15 seconds. A failure announces “Comparison stopped. You can relax.” where speech remains available. There is no automatic restart.

Measurement admission requires fresh complete-body evidence, compatible preview/analysis geometry, qualified knees/arms/torso and the existing 1-second/0.06 motion check. Source timestamps must advance, be at or after the phase boundary and be no more than 750 ms old. The deliberately displaced lap position is measurable without passing a hand or framing match check. Each 10-second window separately counts unsettled, unavailable and discarded frames; only settled complete frames contribute distance statistics. At least 30 eligible frames per phase and fresh stillness at the end are required; reaching the 200-frame cap invalidates that phase. Earlier valid samples cannot complete a phase after tracking disappears. The second phase cannot start after an insufficient first phase.

## Retained evidence and interpretation

Each side/phase retains only a fixed 0.01-torso-length histogram, minimum/maximum distance and count at or below the fixed **0.25** cutoff. Output includes sample-quality counts, median and 95th-percentile **upper bin bounds**, extrema and cutoff counts. No per-frame distances, timestamps, coordinates, trajectories, images or recordings are exported. A single transient motion anchor is cleared at boundaries and cleanup. No report or temporary file is created on the phone.

Instrumentation emits a fixed header with a validated pseudonymous comparison ID, COMPLETE/STOPPED and `labels=participant_confirmation_pending`, then KNEES and LAP summaries. These are requested conditions, not detector-verified physical support or participant-confirmed ground truth. After the run, ask whether both positions were followed for their respective measurements. Compare supported upper-tail values against lap values separately for each side. Do not infer a generally safe threshold from one participant, hide overlap by pooling sides, or treat a histogram upper bound as an exact percentile. A stopped/capped/insufficient comparison cannot establish separation. No automatic threshold fitting is implemented.

## Verification and artifacts

Ten host tests cover authorization/identifier rejection, both phases and their speech-relative allowances, deliberate nonmatch measurement, movement/missing-body stops, speech failure, timestamp rejection, bounded counters, independent-side histogram quantiles, cleanup, fresh post-speech stillness and tracking loss at the end of a measurement. They use synthetic data; no new physical/audio run occurred during development.

The full JDK 17 offline gate passed **784/784 tests**, zero failures/errors/skips, main/test assembly and lint with zero errors/nine warnings:

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline
```

- Main APK SHA-256: `132b2ed1c3c7a9632d6a6f92b0732295c63e8257aecbdffbf64978a100516ae8`.
- Test APK SHA-256: `e362927823c70e4eec541282655b6fd98df5f8b4e01db5b0fa9a11207df648a3`.

The final main APK is installed with its device hash verified and app data preserved. Test/export absence and no own camera client were verified. The test APK is built but uninstalled. No Task 16AD camera or speech run has occurred.

## Next device run — requires fresh Ready

Verify awake/unlocked Pixel 6/oriole, camera permission, exact main/test hashes, absent exports, no own camera client and unused output name. Install the test APK, then recheck unlocked state immediately before launch. Invoke only:

```text
com.tonyisup.poseguidesnap.calibration.HandPlacementComparisonAndroidTest#collectOneAuthorizedHandsOnKneesAndLapComparison
calibrationAuthorization=user-authorized-derived
comparisonId=pixel6-hands-knees-lap-a
```

The ID is unused. Save fixed instrumentation output to `docs/validation/2026-09-25-task16ad-comparison-output.txt`. Do not invoke the prior guided-sequence method or provide positive/negative match labels. There is no JSON report to pull and no match analyzer to run. Retain the output hash and bounded summaries, ask for participant confirmation, stop main, uninstall only test, verify export absence and no own camera client, and preserve main/data/hash. Any additional physical attempt requires fresh Ready and a new identifier/output name. Solo phone-adjustment workflow remains deferred.
