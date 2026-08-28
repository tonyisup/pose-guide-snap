# Pose Guide Snap

> **Project status: buildable Android/Compose prototype.**
>
> This repository contains a real native Android project, a minimal prototype screen, JVM bootstrap/privacy contract tests, and an instrumentation test that compiles into a test APK. The prototype does **not** implement camera preview, pose detection, coaching, capture, storage, export, or the planned product workflow. No device or emulator test has been run.

Pose Guide Snap is a planned Android-first guided selfie app. The intended MVP will let one person arrange a sequence of reference poses, receive concise framing and pose guidance, automatically trigger a three-photo capture only after a stable, high-confidence pose match, or request the same three-photo protocol manually. Pose processing is planned to stay on the device.

## Current prototype

The app currently renders only:

> Pose Guide Snap — prototype

The provisional application ID is `com.tonyisup.poseguidesnap`; the prototype version is `0.1.0` (`versionCode` 1), with `minSdk 29` and target SDK 37. It requests no `INTERNET`, camera, or storage permission and includes no analytics, cloud, camera, storage, or network library. The manifest disables Android backup and points to fail-closed legacy and API 31+ rules that exclude every supported app storage domain from cloud backup, device transfer, and the compile-SDK-37 iOS cross-platform transfer surface. AndroidX contributes the app-signature permission `com.tonyisup.poseguidesnap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` to protect its non-exported dynamic receivers; it grants no camera, storage, or network capability.

From the repository root on the [verified development environment](docs/DEVELOPMENT.md):

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The prototype APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. Building an APK is not evidence that the planned guided-capture workflow works, and the compiled instrumentation test has not been executed on a device or emulator.

## Approved MVP boundary

- Native Android first, with provisional application ID `com.tonyisup.poseguidesnap` and `minSdk 29`.
- Pixel 6 running Android 16 is the first real-device acceptance target.
- Rear camera first; front-camera support is deferred until the rear-camera loop passes its acceptance gate.
- One person in static full-body or three-quarter-body poses.
- On-device reference and live pose processing; no account, cloud upload, or remote inference.
- Sensitive app-private files, Room/DataStore state, and quarantine metadata are excluded from Android cloud backup, device-to-device transfer, and supported cross-platform transfer surfaces; no partial restore is allowed.
- Automatic horizontal mirror matching by default, with a per-pose opt-out.
- A conservative three-photo capture after stable lock, with exactly three authoritative app-private outputs and exactly-once sequence advancement in the same Room confirmation transaction.
- Manual capture uses that same reducer-owned three-photo protocol; it bypasses only the pose-match/lock gate.
- Confirmed private captures are exported afterward by one committed, exactly-three-row MediaStore outbox. Exclusive durable claims and exact persisted MediaStore URIs prevent duplicate creation and foreign-row mutation; export never owns session advancement.
- Delete-shoot and delete-all establish a Room deletion barrier before touching files or export state. In-progress or ambiguous work remains as a visible reconciliation/quarantine tombstone until it can be resolved safely; deletion never reports success early.

These are product and architecture commitments, not claims of implemented behavior. In particular, a high skeleton-similarity score will not be presented as a guarantee of photographic quality. The product language is **“pose match,” not “perfect pose.”**

## Documentation

| Document | Purpose |
|---|---|
| [Product contract](docs/PRODUCT.md) | MVP journey, scope, non-goals, truth in claims, and deferred features |
| [Architecture](docs/ARCHITECTURE.md) | Planned component boundaries, state ownership, matching, coaching, and capture invariants |
| [Testing](docs/TESTING.md) | Current bootstrap gate and planned quality/Pixel 6 acceptance matrix |
| [Privacy](docs/PRIVACY.md) | Implemented bootstrap boundary and planned local data lifecycle, retention, logging, and deletion behavior |
| [Development environment](docs/DEVELOPMENT.md) | Verified toolchain, build/test commands, and exact APK inspection procedure |
| [ADR 0001: Android native first](docs/adr/0001-android-native-first.md) | Reversible platform decision and consequences |
| [ADR 0002: On-device pose processing](docs/adr/0002-on-device-pose-processing.md) | Reversible inference-location decision and consequences |
| [Approved implementation plan](.hermes/plans/2026-08-27_111939-pose-guide-snap-android-mvp.md) | Sequenced implementation tasks and gates |

Install and product-usage instructions will be added only after those workflows exist and have been verified on an authorized target.
