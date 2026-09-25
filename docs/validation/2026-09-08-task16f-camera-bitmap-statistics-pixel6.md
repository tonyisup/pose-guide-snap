# Task 16F CameraX bitmap statistics — Pixel 6 — 8 September 2026

Result: **The exact diagnostic found visually varied live-camera bitmap data but no raw person-instance response.** All 146 analyzed frames were `no-person`; the maximum valid person score was `0.0`, while frame-mean luminance ranged from about `0.401` to `0.642` and the maximum within-frame range was about `0.373`.

## Scope and artifacts

The user separately authorized data-preserving installation and installed-byte verification of the frozen main and Android-test APKs, camera permission, one exact collector method and argument set, conditional derived-report retrieval, exact output cleanup, and removal of only the test package. No other test method, private-image read, screenshot, still-photo capture, MediaStore operation, Room mutation, network request, audio operation, release installation, or app-data clear ran.

| Artifact | Local and installed SHA-256 |
|---|---|
| Debug app | `a47f4588346abdbd7d569d2a02de38a29c12e41abfc5a169e8d8eceee3f52d94` |
| Android test | `d04404d44440ff165ab6f74b341026dd8900531d115387472316e1970d68ae98` |
| Unsigned release — host evidence only | `74ee660bd84b421ce9dd5cf7a42d72e72e4d108720fc7b4be99ac7e3df54c9e3` |

The Pixel 6 (`oriole`) was connected and awake. Both installed packages matched the frozen local bytes, and camera permission was granted and verified. The participant was instructed immediately before launch to keep their entire body visible to the rear camera.

## Exact method result

Only `calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDerivedSequence` ran through `com.tonyisup.poseguidesnap.test/androidx.test.runner.AndroidJUnitRunner` with:

- `calibrationAuthorization=user-authorized-derived`
- `datasetId=pixel6-public-meditation-a`
- `sequenceId=positive-centered-diagnostic-e`
- `fixtureClass=positive`
- `caseClass=centered-match-diagnostic`
- `warmupMs=15000`
- `durationMs=10000`

It failed 1/1 before recording after 16.57 seconds:

| Evidence | Result |
|---|---:|
| Analyzed frames | 146 |
| No-person frames | 146 |
| Exactly-one-person frames | 0 |
| Multiple-person frames | 0 |
| Final/maximum consecutive one-person frames | 0 / 0 |
| Maximum valid raw person score | 0.0 |
| Minimum frame-mean luminance | 0.4006791299019608 |
| Maximum frame-mean luminance | 0.642319387254902 |
| Maximum within-frame luminance range | 0.3730839215686274 |
| Minimum accepted person score | 0.25 |

The analysis cadence remained about 9.7 frames per second. The substantial nonzero mean and within-frame range prove that the upright crop reaching the detector was neither black nor flat. Combined with Task 16E's same-artifact static model pass, this narrows the remaining failure to live-scene pose evidence or a camera-specific image characteristic not represented by aggregate luminance.

## Data boundary and cleanup

Each transient crop sampled a fixed at-most-16×16 grid and immediately discarded all pixels after reducing them to mean luminance and luminance range. Preflight retained only minimum/maximum mean and maximum range. No pixel, bitmap, image, histogram, coordinate, tensor, landmark, timestamp, path, URI, or identifier was retained or written.

Recording never began, and no report or temporary report was created. Exact cleanup removed the empty export directory, stopped Pose Guide Snap, uninstalled only `com.tonyisup.poseguidesnap.test`, and deleted temporary installed-APK pulls. Final checks found no export directory, test package, temporary pull, or active camera client. The new main app remains installed with its data preserved, and unrelated files were untouched.
