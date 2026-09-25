# Task 16R — spoken adjustment confirmation

The participant requested “Good” after completing a spoken adjustment. The debug guidance now remembers the most recent requested correction and confirms it once, after one second of continuous fresh complete-body evidence. Horizontal and vertical corrections use separate axis budgets derived from the existing radial framing tolerance; size corrections use the existing scale threshold. Recovering whole-body or single-person visibility requires the guide's valid complete-body evidence. A change in the highest-priority cue, overshooting, partial detection or incompatible camera geometry is not confirmation. “Good” means the requested framing adjustment is satisfied, not that the complete pose matches.

Acknowledgment is allowed during the final preparation stretch, but never interrupts speech. It restarts the eight-second quiet interval before either another adjustment or hold. New adjustment requests remain suppressed in the last ten seconds; a late acknowledgment may extend preparation beyond the announced minimum 30 seconds. No queue, participant geometry export, production audio change or match-policy change is introduced.

## Verification

Host tests passed 732/732; debug app/test assembly passed; lint reports zero errors and nine warnings. Regression checks cover correction-specific success, one-time acknowledgment, preserved pauses, priority changes without success, overshoot, missing evidence, stale frames, busy speech, scale success while centering still needs work, and a late acknowledgment extending settling. The subsequent authorized camera attempt stopped at complete-body readiness before measurement; see the [device result](2026-09-25-task16r-confirmation-pixel6.md). Participant confirmation of cue audibility and pacing is still pending.

- Main APK: `1374ed781e9b9fae99fa695d9a52743569979191eff35397d07e8cd0ec393ef6`.
- Unchanged instrumentation APK: `93fdb4b5bed065e6732827d1c87de93d9cc184a36da304d066fbd13fa1840ab8`.

The updated main APK was installed on the paired Pixel and its installed hash verified. Main app/data are preserved; the test package and export directory are absent, and no Pose Guide Snap camera client is active.

## Attempted physical protocol (do not reuse sequence ID)

Use the [Task 16Q protocol and cleanup](2026-09-25-task16q-settling-time.md), substituting the above main hash, dataset `pixel6-match-confirmed-a`, sequence `positive-confirmed-landscape-a`, and separate analysis output names. Preparation remains `30000` ms and measurement `10000` ms. Require fresh physical Ready before opening the camera. Keep the phone sideways at approximately eight feet in good light, rear lens toward the participant. Reinstall and verify the test APK before invoking the spoken collector. Retain only scalar diagnostics and preserve app/data during cleanup.
