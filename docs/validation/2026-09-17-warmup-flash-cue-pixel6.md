# Settling-period flash cue — Pixel 6 — 17 September 2026

Result: **The user-requested rear-light blink is implemented, installed, and verified on the Pixel 6.** One blink marks the start of the collector's settling interval. The hardware check confirmed one on/off cycle, off acknowledgement 432 ms after the start, and a 15,001 ms settling period. No pose collection or photo capture ran during this check.

## Behavior

The camera controller retains its bound CameraX camera and exposes internal asynchronous torch control. The test collector awaits the on acknowledgement, starts its existing warm-up, holds the pulse for 250 ms, and awaits off acknowledgement. It then waits only the remainder of the original warm-up deadline. The off request runs even if enabling or waiting fails; failed off acknowledgement aborts the collector before measurement. Controller close/rebind also requests off before releasing its owned use cases.

All three bounded collector methods use the cue. Production matching thresholds, image processing, report schema, app permissions, and automatic-capture state are unchanged. The cue is wired to calibration collection, not to ordinary app launch or manual photo capture. No dependency was added. CameraX's [torch control contract](https://developer.android.com/reference/androidx/camera/core/CameraControl#enableTorch(boolean)) supplies asynchronous confirmation; the measured 432 ms includes hardware acknowledgement latency and is not an optical pulse-duration measurement.

## Verified artifacts

| Artifact | Local and installed SHA-256 |
|---|---|
| Debug app | `8285ee02965cd7957beab44745c50cb3b198b8a92807530370fe29c5f22205bc` |
| Android test | `a6c28b202a9898250101a6a8ad926bde9e02986a4b502e0f01963e6b9591a8f7` |

The main app was updated with data-preserving installation. Both installed APK hashes matched their local artifacts. The earlier Task 16L binaries were preserved under the ignored build tree. No release APK was rebuilt or installed for this change.

## Verification

- Debug build, Android-test build, and lint passed. The full JVM suite executed and passed **714/714**, with zero failures, errors, or skips. Lint reported zero errors and nine warnings.
- Exact `CalibrationWarmupCueTest` invocation passed **5/5 in 0.041 seconds**. Synthetic checks cover cue/deadline timing, delayed on/off acknowledgement, failed enable, interrupted blink, failed off acknowledgement, and duration validation before turning the light on.
- Exact `CalibrationWarmupCueCameraTest#rearLightBlinksAtWarmupStartAndIsOffBeforeMeasurement` invocation passed **1/1 in 17 seconds** on the Pixel 6 over wireless ADB. CameraX acknowledged one on/off cycle; `offAfterMs=432`, `warmupElapsedMs=15001`. The test opened the rear camera, discarded analyzed frames, and retained no image, landmark, calibration report, or photo.

After the check, the app was force-stopped and only the instrumentation package was removed. Final verification confirmed the updated main APK remained installed, its data was preserved, the export directory and test package were absent, and the camera service had no active clients. The flash was acknowledged off before test completion. The next recentered pose sequence remains unrun; its [plan](../../.hermes/plans/2026-09-17-task16m-recentered-reference-match.md) now uses these artifacts.
