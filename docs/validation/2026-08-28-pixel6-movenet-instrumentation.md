# Pixel 6 MoveNet Instrumentation Evidence — 2026-08-28

## Scope

Authorized execution of the previously compile-only Task 9 instrumentation contract. The test uses only the bundled public documentation fixture, a generated black bitmap, and a generated side-by-side composition. It does not access the camera, user photos, or private media.

## Device

- Model: Google Pixel 6 (`oriole`)
- Android: 16
- API level: 36
- Build fingerprint: `google/oriole/oriole:16/CP1A.260305.018/14887507:user/release-keys`
- Connection: authorized USB ADB
- Device serial intentionally omitted

## Exact artifacts

- Main APK SHA-256: `0f3e77b63c4b7c8744239d0fecd709bd9ca675b252353a560e223ff9eb81387f`
- Instrumentation APK SHA-256: `417a912ef8cae3609b2f8288e14bf9e720fd59350798792c5e546aea9974b3b0`
- Source commit: `d33c4055f4344d9caca5e751f10c7289e18f52f2`

## Command and result

Class executed:

`com.tonyisup.poseguidesnap.pose.MoveNetPoseDetectorTest`

Runner result:

- Tests: 1
- Failures: 0
- Runtime: 0.68 seconds
- Verdict: `OK (1 test)`

The contract verified the exact packaged model and fixture, repeated one-person inference with all 17 mapped identities, a generated zero-person black control, a generated two-person side-by-side control, caller bitmap ownership, and idempotent detector cleanup.

## Installed permission surface

Requested and granted:

- `com.tonyisup.poseguidesnap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

No runtime permissions were granted. No camera, storage, network, foreground-service, or location permission was requested.

## Claim boundary

This validates model loading and bounded static fixture behavior on this exact Pixel 6 / APK pair. It does not establish live-camera alignment, population accuracy, calibrated thresholds, sustained performance, thermal behavior, or end-to-end guided capture.
