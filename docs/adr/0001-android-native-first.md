# ADR 0001: Android Native First

- **Status:** Accepted product boundary; native Android and Task 10 camera slice implemented; full MVP acceptance pending
- **Date:** 2026-08-27
- **Decision owners:** Pose Guide Snap product and architecture review
- **Reversibility:** Reversible after the Android MVP is evaluated

## Context

Pose Guide Snap depends on tight camera preview/analysis/capture coordination, predictable lifecycle handling, Android media routing, on-device pose inference, local storage, and real-device verification. The first available acceptance device is a Pixel 6 running Android 16.

Building simultaneous Android and iOS clients, or adding a cross-platform abstraction before the core interaction is proven, would widen camera, audio, lifecycle, and packaging risk without evidence that audio-only pose correction and conservative auto-capture are trustworthy.

At the decision checkpoint, the repository contained only a buildable native Android/Compose prototype and no camera or guided-capture workflow. Current HEAD now includes the reviewed native Task 10 rear-camera/MoveNet slice and Task 11A Room confirmation/deletion/claim authority on the Pixel 6, but still lacks the user-facing shutter, reference import, camera/filesystem-to-Room coordination, MediaStore I/O, TTS/audio, physical deletion, and complete guided workflow.

## Decision

Build the first MVP as a native Android application using Kotlin and Android platform libraries. Use provisional application ID `com.tonyisup.poseguidesnap` and `minSdk 29`. Start with the rear camera and accept the Pixel 6 running Android 16 as the first real-device target.

The native manifest must explicitly set `android:allowBackup="false"` and reference both legacy `android:fullBackupContent` and API 31+ `android:dataExtractionRules` resources. Those resources fail closed across every app-private and device-protected domain for cloud backup, Android device transfer, and compile-SDK-37 cross-platform transfer. No custom `BackupAgent` is allowed.

Keep one Android app module initially, with explicit package and dependency boundaries. Do not create an iOS client or cross-platform UI in the MVP. Front-camera support is separately deferred until the rear-camera workflow passes its real-device acceptance gate.

## Rationale

Native Android is the shortest route to testing the riskiest interaction on the device that is already available. It lets CameraX, app-private file storage, Text-to-Speech, audio focus, Room, MediaStore export, DataStore, Compose, and the direct LiteRT/MoveNet adapter follow their platform contracts while the pure Kotlin domain remains isolated from Android dependencies.

This choice optimizes for evidence, not permanent platform exclusivity.

## Consequences

### Positive

- Camera, audio, lifecycle, permission, and storage behavior can be tested directly on the Pixel 6.
- The first implementation avoids a cross-platform compatibility layer around unproven interaction policy.
- Pure Kotlin matching, coaching, and session logic can still be kept portable behind explicit boundaries.
- One app module limits bootstrap cost while dependency tests preserve ownership rules.

### Negative

- iOS users receive no MVP client.
- Android-specific UI and adapter code will not be reusable as-is on iOS.
- `minSdk 29` narrows Android market coverage and must be revisited before any distribution decision.
- Native platform choices may make a later cross-platform migration more expensive if boundaries are not enforced.
- Rear-camera-first acceptance does not prove front-camera mirror, transform, or interaction behavior.
- Android system backup and transfer are separate platform surfaces from app networking; no-`INTERNET` alone cannot support the local-only claim, so manifest merging, XML rules, and authorized backup/restore behavior require dedicated tests.

## Alternatives considered

### Simultaneous native Android and iOS

Rejected for MVP because it doubles platform-specific camera, audio, lifecycle, and acceptance work before the core experience is proven.

### Flutter or React Native first

Rejected for MVP because the highest-risk behaviors are platform camera/audio integration and exact lifecycle/capture semantics, not shared UI delivery. A cross-platform shell would not remove the need for native adapters and device-specific evidence.

### Mobile web

Rejected because the required camera, background/lifecycle, media routing, still-capture, and local persistence behavior needs stronger platform control and acceptance guarantees than this MVP should assume from a browser surface.

## Reversibility plan

This ADR may be superseded after the Android rear-camera MVP passes or fails Gate 4 and the team has evidence about interaction value and platform-specific cost.

A reversal requires:

1. A new ADR selecting native iOS, a cross-platform framework, or another client strategy.
2. Evidence that the pure domain contracts remain platform-neutral or a documented migration plan for them.
3. Separate camera, coordinate-transform, audio-route, storage, backup/transfer exclusion, privacy, exact MediaStore identity, deletion barriers, and exactly-once capture acceptance on each new platform.
4. Updated product scope and deferred-feature table.

No data-format or cloud migration is implied by a platform expansion; [ADR 0002](0002-on-device-pose-processing.md) remains in force unless separately superseded.

## Related

- [Product contract](../PRODUCT.md)
- [Architecture contract](../ARCHITECTURE.md)
- [Testing contract](../TESTING.md)
