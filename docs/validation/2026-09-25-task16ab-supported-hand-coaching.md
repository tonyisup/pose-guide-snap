# Task 16AB — supported hand instructions

## Participant finding and intended pose

After [Task 16AA](2026-09-25-task16aa-movement-guided-pixel6.md), the participant confirmed hearing Good but explained that the directional hand instructions left their hand hovering above the knee. They followed the words literally; no cue named the knee or said to rest the hand. They suspect holding the unsupported arm contributed to shakiness. The retained metrics do not establish the final timeout cause.

The bundled public reference image was visually inspected. It depicts a seated pose with bent elbows and palms up by the knees. Exact physical contact cannot be established from the image or MoveNet's 17 points. This prototype now explicitly instructs a supported knee placement as the practical resting pose. A synthetic check puts each wrist at the corresponding reference knee's projected coordinates and verifies that both remain within the existing 0.25 torso-length acceptance radius. This demonstrates geometric compatibility, not a physical-contact detector or population-wide pose validation.

## Change

The introduction now says to rest the hands on the knees, palms up, and relax the arms. Wrist errors in any direction produce one of two fixed cues:

- LEFT_HAND_ON_KNEE: “Rest your left hand on your left knee, palm up. Let your arm relax.”
- RIGHT_HAND_ON_KNEE: the corresponding right-hand instruction.

The eight directional wrist cues were removed from the active vocabulary. Subsequent wrist corrections continue to name the supported destination instead of asking the participant to raise or slide a hovering hand. The eight elbow directions remain, prefaced with a reminder to keep the same-side hand resting on its knee. Anatomical sides remain participant-relative when facing the rear camera, including mirrored-reference selection.

Joint acceptance still compares projected reference positions. Good remains confirmation of the requested measured joint plus observed stillness; it cannot certify knee contact, palm orientation or an entire pose. The one-second stillness window, 0.06 motion radius, 0.25 joint radius, eight-second quiet intervals, 30-second initial allowance and 60-second deadline remain unchanged. Automatic capture and production matching thresholds remain unchanged. Solo phone-adjustment workflow redesign remains deferred.

## Bounded readiness diagnostics

The preparation decision now exposes the same decision with a closed set of reason flags. The speech adapter freezes one summary when it reaches READY or TIMED_OUT, before the final announcement changes speech state. Instrumentation prints `calibration preparation result=... blockers=...` once during cleanup. There are no frame samples, coordinates, durations, file locations or participant text in this diagnostic.

Possible flags are DEADLINE, INITIAL_ALLOWANCE, SPEECH, QUIET_PAUSE, TRACKING, UNSETTLED, ARM_POSITION, FRAMING and UNCONFIRMED_ADJUSTMENT. Missing/stale complete-body evidence reports TRACKING rather than attributing stale position values to arm/framing failure. READY reports `blockers=none`. If preparation never reaches its decision, the fixed placeholder is `result=NOT_FINISHED blockers=unavailable`. DEADLINE alone means the hard deadline was reached even if other checks then passed; it is not evidence of a particular spatial failure.

This lets a later run distinguish remaining framing, positional, motion, tracking and speech/pause constraints without retaining a private trajectory. The original admission rules remain enforced by the shared preparation gate.

## Verification

The new supported-hand regression ran before implementation:

```text
./gradlew :app:testDebugUnitTest --tests '*CalibrationSupportedHandsTest' --offline
2 tests completed, 1 failed
wristErrorsInEveryDirectionAskForTheSameSideKneeInsteadOfHovering: ComparisonFailure
```

The resting-position compatibility test already passed without changing tolerance. After implementation, both pass. Existing direction tests now cover eight elbow directions and the same-side hand cues; movement, joint-focus, mirror, fresh-evidence, quiet-pause and stop-before-hold checks still pass. The preparation tests additionally assert exact reason sets for unresolved arms, stale tracking and the remaining quiet pause, a framing-specific blocker, and a clean READY summary.

The full JDK 17 offline gate (`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`) passed **768/768 tests**, zero failures/errors/skips. Main/test assembly passed; lint reports zero errors and nine warnings.

- Main APK SHA-256: `be4e724cecea95534f90ac4f691cd8dc3d224b8bc02f5f239d98b8d9b3992195`.
- Test APK SHA-256: `d944ad68a656207df6aeb9b18e7711f03399f1f86da2bcb9eb89db152752a536`.

The main APK was installed with app data preserved, and its installed hash matches the value above. Test/export absence and no Pose Guide Snap camera client were verified. No additional native/camera/voice run was performed during implementation. The subsequent Ready-authorized physical result is recorded below.

## Next physical protocol

Check awake/unlocked Pixel state, installed hashes, camera permission, absent export directory and unused output names. Reinstall and verify the test APK. Keep the sideways rear-camera setup. The left hand may start in the lap; follow the introduction's resting instruction and subsequent named adjustments. Keep the hand supported during elbow corrections. Do not ask the participant to hover for this protocol.

Use `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`, authorization `user-authorized-derived`, dataset `pixel6-supported-hands-a`, sequence `positive-supported-hands-landscape-a`, fixture `positive` (intended reference, provisional), case `centered-match`, `warmupMs=60000` (hard deadline), `durationMs=10000`. This sequence was consumed by the physical run below; do not reuse it. Admission can occur after the 30-second allowance only when all coaching readiness conditions pass. An unresolved timeout must announce its stop without hold or measurement.

Retain fixed cue/confirmation counts and the new preparation summary on every result. On success also verify and retain only the scalar report/framing evidence; confirm final-pose intent before using the positive label as ground truth. Preserve main/data, remove only exact report/temp and the empty export directory, stop main, uninstall test and verify no Pose Guide Snap camera client. Do not automatically repeat after a stop announcement; another run requires fresh Ready. No private images, recordings, coordinates or trajectories are retained.

## First physical result

The [supported-hand Pixel run](2026-09-25-task16ab-supported-hands-pixel6.md) repeated RIGHT_HAND_ON_KNEE three times, completed no Good, and stopped before hold/measurement. At the deadline, tracking and stillness passed, but arm position, framing and an unconfirmed adjustment remained blockers. Cleanup passed. The participant reported that the hand was already resting on the knee at the first instruction and the repetitions offered no clear adjustment. This prompted Task 16AC. Physical readiness completion remains unverified.
