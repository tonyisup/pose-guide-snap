# Task 16O spoken calibration guidance — 25 September 2026

**Implemented and installed; 728/728 host tests and a 1/1 Pixel audio-only check pass.** The participant confirmed the audio check was clearly audible. The first spoken camera attempt subsequently stopped at full-body readiness without a report; see the [attempt record](2026-09-25-task16o-spoken-readiness-pixel6.md). The audio check opened no camera and collected no participant observation.

## Why this changed

After Task 16N's unevaluable guided run, the participant explained that they could not see the screen while facing the rear camera and had expected spoken cues. Visual-only guidance was therefore unusable from their position. This establishes a guidance-accessibility problem; it does not establish the cause of the missing body landmarks. Keep the rear lens facing the participant, rather than switching camera/mirroring assumptions.

## Behavior and boundary

- A new explicitly selected `collectOneAuthorizedSpokenGuidedSequence` uses the existing reference-aspect preview and flash. At the flash it says how many settling seconds are available and asks the participant to face the rear camera.
- Stable live positioning advice is spoken at most every four seconds, with no backlog. A cue must remain current for 500 ms, and a new adjustment waits until the current utterance ends. Advice uses fixed phrases, not generated descriptions of the participant.
- Horizontal instructions use the participant's left/right **while facing the unmirrored rear lens**. Vertical instructions ask for a small phone tilt and a return to the pose. Incomplete body evidence requests full-body visibility without guessing a size/direction correction.
- Before the hold, the collector requires five consecutive fully evaluated, complete-body frames, with the latest no more than 750 ms old. Person presence alone cannot admit the spoken collection. Failure announces that the test stopped and the participant can relax; no report is produced.
- Successful readiness is followed by “Hold still” with the requested duration. Measurement starts after that utterance completes. During measurement, adjustment speech is disabled. At the end it says the test finished and the participant can relax. Other failures attempt a stop announcement before cleanup.
- Speech uses an installed English voice whose API contract says it does not require a network connection and whose voice data is not marked uninstalled. Initialization, focus, non-muted media volume, enqueue success and completion are checked. Missing voice, initialization failure, mute, focus loss, or playback failure prevents a silently coached run. No voice download, volume change, Bluetooth route change, or audio recording is performed.
- The adapter uses normal Android media routing, requests transient audio focus, flushes obsolete speech at phase changes, and stops/shuts down/releases focus in cleanup. The listener uses utterance IDs so completion of an interrupted message cannot complete a newer message.

The cue selector/readiness gate live in `src/debug`; the TTS adapter and its caller remain instrumentation-only. A debug manifest query enables TTS engine discovery. The merged debug permissions remain only CAMERA and the app-signature receiver permission: no internet, microphone, audio-settings, or storage permission was added. Release UI and the production Task 13 audio/coordinator work remain open. Match policy, person thresholds, model, report schema, and automatic capture are unchanged. Warm-up speech is not a measured per-frame cue rate during the silent hold.

API behavior was checked against Android's [TextToSpeech contract](https://developer.android.com/reference/android/speech/tts/TextToSpeech) and [Voice network requirement](https://developer.android.com/reference/android/speech/tts/Voice#isNetworkConnectionRequired()). Selecting an offline-capable voice is not a substitute for a later airplane-mode/audio-route acceptance matrix.

## Verification

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline` passed. **728/728 JVM tests** passed with zero failures/errors/skips; lint has zero errors and nine warnings. Six new tests cover participant-relative directions, incomplete-body suppression, stability/cadence, busy/stale speech suppression, fresh consecutive readiness and presence-only rejection.

| Artifact | Verified local and installed SHA-256 |
|---|---|
| Debug app | `20d54557f60328f279f5306ce1e8b19085d7b233c50c2c04cd40f4d891732d8d` |
| Android test | `836f9e645a045590c7db58d1258c36f4cb3d32ea1f4ae2cd9940a0ca0862400c` |

Data-preserving installs were followed by exact installed-byte checks on the wireless Pixel 6/oriole. Only `CalibrationSpeechOutputTest#installedOfflineVoiceCompletesAudibleCheck` ran. It passed **1/1 in 6.950 seconds**, after selecting the installed offline voice, obtaining focus, checking media volume, and receiving playback completion for: “Audio check. Spoken guidance is ready. You do not need to pose.” See the [instrumentation output](2026-09-25-task16o-audio-pixel6-output.txt).

Engine completion alone does not establish audibility. The participant subsequently replied “Yes clearly,” confirming they heard this check on the current route. The audio-only check did not validate camera-bound guidance. A subsequent camera attempt reached the incomplete-body stop path and its completed speech announcement, but no measurement. Route interruption and full offline acceptance remain unverified.

Cleanup closed the speech adapter/activity, force-stopped the main app, removed only the test package, and verified absent calibration export/test package, the new main hash preserved with app data, and no active camera client. No photographs, recordings, screenshots, coordinates or raw landmarks were collected. See the [next-run plan](../../.hermes/plans/2026-09-25-task16o-spoken-guided-framing.md).
