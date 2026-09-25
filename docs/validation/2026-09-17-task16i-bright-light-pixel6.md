# Task 16I bright-light detector repeat — Pixel 6 — 17 September 2026

Result: **The unchanged app detected exactly one person in all 96 recorded frames, with all 17 landmarks confidence-qualified and all four torso anchors available.** The new shooting conditions restored usable live pose evidence without lowering the `0.25` person gate. Lighting is a plausible contributor, but this room change does not isolate illumination from distance, framing, background, or camera placement.

## Scope and artifacts

After the user requested an unplugged camera run, the Pixel 6 (`oriole`, Android 16) was paired through Android wireless debugging. The user then confirmed readiness in the new position. One previously prepared 15-second warm-up plus 10-second full-body detector collection ran over wireless ADB. No source or APK changed, and the main app did not need reinstallation.

| Artifact | Local and installed SHA-256 |
|---|---|
| Debug app | `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66` |
| Android test | `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9` |

Local APK hashes and device-side hashes of both installed APKs matched before launch. Camera permission was granted; the collector also asserts it. The report directory was absent before collection.

Only `com.tonyisup.poseguidesnap.calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDetectorGateSequence` ran, with authorization `user-authorized-derived`, dataset `pixel6-person-gate-a`, sequence `single-person-full-body-bright-a`, fixture `positive`, case `single-person-full-body`, `warmupMs=15000`, and `durationMs=10000`.

Instrumentation passed **1/1 in 26.622 seconds**, producing 96 schema-v3 frames. The device-reported report SHA-256 exactly matched the ignored host copy: `749f27f02cf07e84e8232b4bf11543cb0d290656cefba05867e7800bdb7efaab`.

## Detector result

| Evidence | Bright full-body sequence |
|---|---:|
| Exactly-one-person frames | 96/96 |
| Successfully evaluated frames | 96/96 |
| Confidence-qualified landmarks per frame | 17/17 |
| Qualified torso anchors per frame | 4/4 |
| Person score min / p05 / median / p95 / max | `0.724650` / `0.746036` / `0.763399` / `0.768429` / `0.771101` |
| Keypoint score min / p05 / median / p95 / max | `0.878377` / `0.880329` / `0.896745` / `0.912282` / `0.914160` |
| Replayed locks / capture commands | 0 / 0 |

The earlier Task 16H full-body sequence had 0/97 accepted-person frames, a median person score of `0.000`, and a maximum of `0.145938`. Every frame in this repeat cleared the unchanged detector gate. The current live-camera path can therefore produce complete, usable pose evidence under at least this condition.

This is a detector-positive test, not a request to match the bundled meditation pose. The generic analyzer's zero positive match-lock rate is not a failed detector result. Framing, angular, positional, and overall scores stayed below their separate match-acquisition gates; none changed.

## Comparison limits and next check

The offline analyzer compared this bright positive with the retained Task 16H empty-scene sequence. Person-score positive p05 minus old negative p95 was `0.623623`; the corresponding keypoint gap was `0.729347`. These are exploratory comparisons across different shooting conditions. They do not replace an empty-scene control in the new location, repeated sequences, or pose-matching calibration. No image or measured lighting value was retained, so lighting alone is not a proven root cause.

The ignored combined analysis JSON hash is `3fd527cac6514b02cbd48caed138bc23d59f2e1e6fb564f5b1d1c8c767d1117d`; its Markdown hash is `82da080775ad18cef05d5642f7be516d5c99b07b36f10319688fdfe596568b70`. The original Task 16H reports and analysis were preserved.

The subsequent [Task 16J same-setup control](2026-09-17-task16j-bright-empty-scene-pixel6.md) collected `no-person-empty-scene-bright-a`: all 97 frames reported no person, with a maximum person score of `0.052640`. Its separate pair analysis supersedes the old-room comparison for this setup. Repeated detector cases and reference-pose calibration remain open. Automatic capture remains disabled; no production threshold or population accuracy claim follows from this pair.

## Cleanup and verification

The exact device report and fixed temporary report filename were removed, and the empty export directory was deleted. The app was force-stopped and only `com.tonyisup.poseguidesnap.test` was uninstalled. Final checks confirmed the report directory and instrumentation package were absent, the main app remained installed with its data preserved, and the camera service had no active clients. No installed-APK pulls were created. Wireless pairing remains available for the next user-ready test.

Only ignored schema-v3 scalar reports and analyses were retained on the host; no image, video, raw landmark, tensor, or pairing credential was added to project evidence. This checkpoint reused the frozen binaries: the 1/1 hardware test, strict report analysis, hash checks, and cleanup checks ran today; the earlier 714-JVM/14-Python/lint/build gate was not rerun because no code or artifact changed.
