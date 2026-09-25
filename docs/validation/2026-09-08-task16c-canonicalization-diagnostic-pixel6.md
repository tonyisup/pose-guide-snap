# Task 16C canonicalization diagnostic — Pixel 6 — 8 September 2026

Result: **The first exact authorized diagnostic invocation passed 1/1 but observed no person on all 97 frames. Two separately authorized preflight repeats failed safely before recording; the observable repeat proved that all 146 analyzed warm-up frames were `no-person`.** No invocation reproduced the earlier one-person canonicalization failure. No threshold changed and automatic capture remains disabled.

The user separately authorized data-preserving installation of the frozen APK pair, installed-byte verification, camera permission, one positive diagnostic invocation, one derived-report pull and analysis, exact report cleanup, and removal of the instrumentation package. No additional test class, photo picker, existing personal-media read, still-photo capture, MediaStore operation, database mutation, app-data clear, network request, audio operation, or release installation ran.

## Device and frozen artifacts

- Device: Pixel 6 (`oriole`), Android 16.
- Device state before installation: exactly one authorized device connected; after the user unlocked it, power state was `Awake`, the screen was on, and keyguard was not showing.
- Source: local `codex/development-takeover` work based on `32a1429`; no APK-producing source changed between the host gate, artifact hashing, and device run.

| Artifact | Local and installed SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `71925174c5796febc83ccf48ef5bb101c62ab72c375b157a701c4c404bf251c2` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` — host build evidence only | `50437b7e4651446e374204edb6095a0ddd23803fcdf5b71c7e10b4112473a769` |

Both tested packages installed successfully using data-preserving replacement. Hashes read from the installed `base.apk` files exactly matched the frozen app and instrumentation APKs, after which those temporary host copies were deleted. Camera permission was granted and confirmed in package state.

The same source passed 708/708 JVM tests, 12/12 Python analyzer tests, lint with zero errors and 9 warnings, and all APK assemblies. Room V1–V4 schema hashes are unchanged. Debug and release request camera plus AndroidX's app-signature receiver permission; Android test requests only `REORDER_TASKS`; none requests internet access.

## Exact method result

Only `calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDerivedSequence` ran through `com.tonyisup.poseguidesnap.test/androidx.test.runner.AndroidJUnitRunner`. It passed exactly:

- `calibrationAuthorization=user-authorized-derived`
- `datasetId=pixel6-public-meditation-a`
- `sequenceId=positive-centered-diagnostic-a`
- `fixtureClass=positive`
- `caseClass=centered-match-diagnostic`
- `warmupMs=5000`
- `durationMs=10000`

The method passed 1/1 in 16.877 seconds and produced 97 frames. The device-reported SHA-256 for `calibration-sequence-v2.json` was `ee382186394c812da0a81cc32663d2afbc027d0f178d1b4a084906d83a7c50d2`; the pulled ignored host file matched exactly and passed the schema-v2 parser.

## Diagnostic result

| Evidence | Result |
|---|---:|
| Evaluation status | `no-person`: 97/97 frames |
| Confidence-qualified MoveNet landmarks | minimum 0; median 0; maximum 0 |
| Qualified shoulder/hip anchors | minimum 0; median 0; maximum 0 |
| Positive locks | 0/1 |
| Capture commands | 0 |
| Five match scores | zero on all frames |
| Latency and cue observations | unavailable by collector contract |

The aggregate analyzer JSON has SHA-256 `e6e2470e2fd5c936b566a988f0bb6fa76097199be51e1e49dce84b9b62d2a3d0`; its Markdown rendering has SHA-256 `cf5b3a03408379c26365fb8fabc25ed2c4f35990950f5ee03ee2e455e4bc5432`.

This result confirms that schema v2 distinguishes `no-person` from `canonicalization-failed`, which schema v1 represented with the same five zero scores. It does not explain the Task 16B positive failure because that earlier sequence reported exactly one detected person on all 97 frames. A useful repeat must first reproduce one-person detection while the participant's whole body is visible in the rear-camera preview.

## Data boundary and cleanup

The collector retained only relative time, person count, one closed evaluation status, two bounded counts, five scalar scores, mirror choice, null latency/cue observations, and zero capture commands. It retained no image, screenshot, raw landmark, landmark identity, coordinate, tensor, private-media path, URI, wall-clock timestamp, personal identifier, audio, or still photo. The per-frame report and aggregate analyzer files remain ignored under `build/calibration/device`; none is committed.

The exact device report and `.calibration-sequence-v2.tmp` were deleted, the empty `calibration-export` directory was removed, and absence was confirmed. The Pose Guide Snap camera client disconnected. Google Camera resumed as the active client afterward and was left untouched. Only `com.tonyisup.poseguidesnap.test` was uninstalled and confirmed absent; `com.tonyisup.poseguidesnap` remains installed with its data preserved. Temporary installed-APK host copies were confirmed absent.

## Corrected preflight repeat

The no-person result exposed a collector usability defect: an authorized window could complete successfully without ever seeing its intended participant. The corrected Android-test collector now uses the existing warm-up as a preflight and requires it to end with at least five consecutive exactly-one-person frames. If that condition is absent, the method fails before recording or writing a report, removes partial output, and closes the camera. It deliberately does not require torso anchors, because missing confidence-qualified anchors are the condition this diagnostic needs to measure.

Only Android-test source changed. The complete host gate passes again with 708/708 JVM tests, 12/12 Python tests, zero lint errors and 9 warnings, all APK assemblies, unchanged Room schema hashes, and unchanged packaged permissions.

| Corrected repeat artifact | SHA-256 |
|---|---|
| Existing installed debug APK | `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4` |
| Corrected Android-test APK | `8b2d4e81440b11878bf0635c563d305985b25b600c3f385341a75088d726d4e4` |
| Unchanged unsigned release APK — host evidence only | `50437b7e4651446e374204edb6095a0ddd23803fcdf5b71c7e10b4112473a769` |

The user separately authorized one corrected invocation using sequence `positive-centered-diagnostic-b`. The existing installed main APK matched `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4`, and the installed corrected Android-test APK matched `8b2d4e81440b11878bf0635c563d305985b25b600c3f385341a75088d726d4e4`. The Pixel was awake, interactive, and unlocked; camera permission remained granted. The participant was instructed immediately before launch to stand where the rear camera could see their entire body.

Only the same collector method ran with the following arguments:

- `calibrationAuthorization=user-authorized-derived`
- `datasetId=pixel6-public-meditation-a`
- `sequenceId=positive-centered-diagnostic-b`
- `fixtureClass=positive`
- `caseClass=centered-match-diagnostic`
- `warmupMs=5000`
- `durationMs=10000`

The method failed 1/1 after 6.522 seconds with `Warm-up ended without 5 consecutive exactly-one-person frames`. It failed before the recording window and created no `calibration-sequence-v2.json` or temporary report. The export directory was empty immediately after the failure.

Exact cleanup then removed the empty export directory, closed Pose Guide Snap, and uninstalled only `com.tonyisup.poseguidesnap.test`. Final checks found no export directory, no temporary installed-APK host copies, no active camera clients, and only `com.tonyisup.poseguidesnap` installed. The main app and unrelated data remain preserved.

The corrected preflight prevents another misleading no-person report. The repeat supplies no calibration sample and does not explain Task 16B's one-person canonicalization failure. Further private-device collection needs a new frozen artifact and fresh exact authorization after the preview/detection path is made easier to verify.

## Observable preflight candidate and device result

After the corrected repeat, the preflight was extended to show live on-device feedback for no person, one person with consecutive-frame progress, or multiple people. If preflight still fails, its assertion now includes only aggregate analyzed/no-person/one-person/multiple-person frame counts and final/maximum consecutive one-person counts. It writes none of those counters to disk and retains no image, landmark identity, coordinate, tensor, timestamp, path, URI, or personal identifier.

The full host gate passes with 708/708 JVM tests, 12/12 Python tests, zero lint errors and 9 warnings, all APK assemblies, unchanged Room V1–V4 hashes, and unchanged permissions. Debug and unsigned release remain byte-identical to the earlier device-tested pair. The observable Android-test SHA-256 is `d80047d2ecc39a011c345c50e3e2b51f5cba32aea1e6126d5b40a23f3780135b`.

The user separately authorized one exact invocation with sequence `positive-centered-diagnostic-c`, a 15-second warm-up, and a 10-second recording window conditional on preflight success. The installed main and Android-test APK hashes matched `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4` and `d80047d2ecc39a011c345c50e3e2b51f5cba32aea1e6126d5b40a23f3780135b`. The Pixel was awake, interactive, and unlocked; camera permission was granted. The participant was instructed immediately before launch to keep their entire body in the rear-camera view.

The method failed 1/1 after 16.538 seconds with these aggregate-only warm-up values:

| Evidence | Result |
|---|---:|
| Analyzed frames | 146 |
| No-person frames | 146 |
| Exactly-one-person frames | 0 |
| Multiple-person frames | 0 |
| Final consecutive one-person frames | 0 |
| Maximum consecutive one-person frames | 0 |

Camera analysis therefore ran at about 9.7 frames per second, but the mapping policy accepted no person in any frame. Recording never began and no report or temporary report was created; the export directory was empty immediately after failure. Exact cleanup removed the directory, closed the app camera, uninstalled only the test package, and confirmed that temporary installed-APK host copies were absent. Final camera state had no active clients, and only the main app remained installed with its data preserved.

## Task 16D person-score continuation

The 146-frame result rules out missing camera-analysis cadence but cannot show whether the strongest raw MoveNet instance score was near the unchanged `0.25` acceptance threshold. Task 16D therefore reduces each raw six-slot output to only its maximum valid instance-score scalar and carries that value through the immutable analyzed-frame boundary. A failed warm-up reports only the maximum seen and the fixed threshold; neither enters the persisted calibration report. It retains no slot identity, tensor, image, landmark, coordinate, timestamp, path, URI, or personal identifier.

The Task 16D host gate passes 709/709 JVM tests, 12/12 Python tests, zero lint errors and 9 warnings, all APK assemblies, unchanged Room hashes, and unchanged permissions. Frozen hashes are debug `16843f2b88f11116161ec81ecbf460b691eac97fa4b1123248dbff426852e787`, Android test `488eb4b615f74479d06824f29c711cbadaf24013778acd35a3cf1fbfdcddac7e`, and unsigned release `c7aae95d26bc295ab42f5dad7688814f4775df95cacf567fe668df7f3bc696ba`.

The user separately authorized data-preserving installation and installed-byte verification of the frozen debug and Android-test APKs, camera permission, and only `calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDerivedSequence` with these arguments:

- `calibrationAuthorization=user-authorized-derived`
- `datasetId=pixel6-public-meditation-a`
- `sequenceId=positive-centered-diagnostic-d`
- `fixtureClass=positive`
- `caseClass=centered-match-diagnostic`
- `warmupMs=15000`
- `durationMs=10000`

The Pixel was awake, interactive, and unlocked, and the participant was instructed immediately before launch to keep their entire body visible to the rear camera. The installed main and Android-test bytes matched the frozen hashes above. The exact method failed 1/1 after 16.594 seconds with these aggregate-only warm-up values:

| Evidence | Result |
|---|---:|
| Analyzed frames | 146 |
| No-person frames | 146 |
| Exactly-one-person frames | 0 |
| Multiple-person frames | 0 |
| Final consecutive one-person frames | 0 |
| Maximum consecutive one-person frames | 0 |
| Maximum valid raw person score | 0.0 |
| Minimum accepted person score | 0.25 |

Camera analysis again ran at about 9.7 frames per second, while even the strongest finite raw person score remained exactly zero. The failure therefore was not a near-threshold rejection. Recording never began, and no report or temporary report was created. Exact cleanup removed the empty export directory, stopped the app camera, uninstalled only `com.tonyisup.poseguidesnap.test`, and removed temporary installed-APK pulls. Final checks found no export directory, test package, temporary pull, or active camera client. The Task 16D main app remains installed with its data preserved, and unrelated device data was untouched.

The result does not justify lowering the `0.25` threshold. The next bounded discriminator is the existing static MoveNet instrumentation contract on the same artifact pair, using only the packaged licensed fixture and generated black/two-person controls. A pass would localize the remaining defect to the live-camera input path or scene; a failure would identify a current-artifact model, preprocessing, inference, or mapping regression.
