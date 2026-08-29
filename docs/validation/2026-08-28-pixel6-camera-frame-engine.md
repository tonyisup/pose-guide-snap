# Pixel 6 Camera Frame Engine Evidence — 2026-08-28

## Scope

Authorized execution of the Task 10 checkpoint-C generated-bitmap instrumentation contract. It validates bitmap ownership, crop copying, fixed upright-rotation requirements, bounded MoveNet frame processing, and serialized detector closure. It does not open the camera or access user media.

## Device

- Model: Google Pixel 6 (`oriole`)
- Android: 16 / API 36
- Build fingerprint: `google/oriole/oriole:16/CP1A.260305.018/14887507:user/release-keys`
- Device serial intentionally omitted

## Exact artifacts

- Main APK SHA-256: `b9ac002a18309ba9ef263cfae53c036e8601fac2cb73f9caef8d77603851cf1a`
- Instrumentation APK SHA-256: `d02e98d27faf2d2b4d51657ffc19ee3f14b01f385a30a3caedd5384bb4a23989`
- Source baseline: `d33c4055f4344d9caca5e751f10c7289e18f52f2` plus uncommitted Task 10 checkpoints A–C

## Result

Class executed:

`com.tonyisup.poseguidesnap.camera.MoveNetImageAnalyzerTest`

- Tests: 7
- Failures: 0
- Runtime: 0.28 seconds
- Verdict: `OK (7 tests)`

The suite verified full and sub-crop pixel orientation, independent mutable ARGB ownership, source recycling, idempotent frame close, invalid rotation/timestamp/crop handling, real MoveNet zero-person analysis, frame release, rejected post-close submissions, and serialized/idempotent engine shutdown.

## Claim boundary

This is real Android/Pixel evidence for generated bitmap and model-engine behavior. It does not validate CameraX lifecycle binding, real `ImageProxy` delivery, preview/overlay alignment, camera permission recovery, still capture, sustained latency, GC pressure, thermal behavior, or user-visible guidance.
