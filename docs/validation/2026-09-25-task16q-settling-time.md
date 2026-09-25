# Task 16Q — time to follow spoken adjustments

The participant reported that “move right” changed to “tilt down” before they had processed and completed the first movement, followed immediately by “hold still.” Task 16P therefore supplies useful complete-body evidence but does not establish usable guidance pacing.

The spoken collector now requires a 30,000 ms preparation window. The flash still marks its start. Each instruction must finish before an eight-second quiet action interval begins; current advice must be stable for one second. No new adjustment is issued during the final ten seconds. Before the hold announcement, any pending instruction must finish (bounded ten-second wait) and its remaining eight-second quiet interval must elapse. The introduction says “at least 30 seconds” because delayed speech may extend settling. Fresh body evidence continues through that extension. Speech failure aborts; hold cannot flush an unfinished adjustment. Collection remains ten seconds for the next protocol.

The general request bound is now 3–30 seconds, retaining old visual protocols. Production match thresholds, capture behavior and report schema are unchanged. This is instrumentation guidance, not completed production audio acceptance.

## Verification

Host build, 729/729 JVM tests and lint passed; lint has zero errors and nine warnings. Regression tests cover actual speech completion, right-to-tilt changes, late cue suppression, an extended quiet tail and stale advice. The fake-clock flash test now exercises 30 seconds.

- Main APK SHA-256: `6e81e8f49f88a2b7949feda14041f46942ffa3d952740ddd113c63dc67069f71`.
- Test APK SHA-256: `93fdb4b5bed065e6732827d1c87de93d9cc184a36da304d066fbd13fa1840ab8`.
- Participant camera verification of this pacing is pending a fresh physical Ready.

Both APKs were installed and their device hashes verified. The five camera-free synthetic timer tests passed on the Pixel in 0.023 seconds; see the [native output](2026-09-25-task16q-timing-pixel6-output.txt). The test package was removed afterward; the main app/data remain, export directory is absent and no Pose Guide Snap camera client is active. No participant camera run or new audio audibility check was performed.

## Next physical protocol

Keep the phone sideways, rear lens facing the participant, approximately eight feet away in the same good lighting. Use `collectOneAuthorizedSpokenGuidedSequence`, authorization `user-authorized-derived`, dataset `pixel6-match-spoken-slow-a`, sequence `positive-spoken-slow-landscape-a`, fixture `positive`, case `centered-match`, `warmupMs=30000`, `durationMs=10000`. Verify these exact installed artifacts, unused host output names and absent export directory first. Retain only scalar report/framing diagnostics and analyze separately. Remove exact exported report/temp files and empty directory, stop the main app, uninstall only the test package, verify no Pose Guide Snap camera client and preserve main app/data. Do not reuse the Task 16P sequence identifier or start a camera run without physical readiness.
