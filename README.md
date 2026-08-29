# Pose Guide Snap

> **Project status: Task 10 is committed and hardware-exercised; Gate 2 remains incomplete.**
>
> `HEAD` and `origin/main` both point to the guided CameraX pose slice at `605c904d9c01002e8f231301094a8b3183dc2c36` (`feat: add guided camera pose slice`). The exact staged digest `61a3fc581b16902dcd592f992b253ec70fe71ea727136001583c09a422b2f6dd` received specification PASS and quality/security APPROVED before commit. The app now has rear preview/analysis, direct on-device MoveNet, an attributed bundled reference, named uncalibrated match evidence, aligned live/reference skeletons, and internal exactly-three app-private candidate-capture mechanics. It still has no product shutter, auto-capture, Room confirmation/advancement, reference import, MediaStore export, TTS/audio, deletion flow, or end-to-end guided workflow.

Pose Guide Snap is a planned Android-first guided selfie app. The intended MVP will let one person arrange a sequence of reference poses, receive concise framing and pose guidance, automatically trigger a three-photo capture only after a stable, high-confidence pose match, or request the same three-photo protocol manually. Pose processing is planned to stay on the device.

## Current implementation

The provisional application ID is `com.tonyisup.poseguidesnap`; the prototype version is `0.1.0` (`versionCode` 1), with `minSdk 29` and target SDK 37. It requests `android.permission.CAMERA` and AndroidX's app-signature `com.tonyisup.poseguidesnap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; it requests no `INTERNET`, storage, location, audio, or foreground-service permission and includes no network, cloud, or analytics library. The manifest disables Android backup and points to fail-closed legacy and API 31+ rules that exclude every supported app storage domain from cloud backup, device transfer, and the compile-SDK-37 iOS cross-platform transfer surface.

Verified Task 10 evidence:

- JVM suite: 266/266 GREEN; lint and debug main/instrumentation builds GREEN.
- Reproducible APK SHA-256 values: main `a678f014cefc19281bd253cfdb64b97bf0a3ec65f2e3d2f374248bfd47dfc3ad`; instrumentation `3f0985b207286c0c4f249183ae5d434488edf129afd53ed401f70988bd8135c5`.
- Authorized Pixel 6 relevant instrumentation: 15/15 GREEN. The fixed attributed bundled reference, aligned live/reference skeletons, real rear-camera candidate capture, no-clobber behavior, zero private capture residue, and 400–500 ms camera release all passed.
- Final 60-second run: mean CPU 148.08%, peak 162%, thermal status 0, battery 31.0→30.8°C, no fatal/ANR, and bounded memory. It did **not** improve over the pre-cadence baseline; the 15-minute Gate 4 soak remains pending.
- No private images, screenshots, or raw landmark streams are retained.

The internal candidate-capture mechanics are not the durable product protocol. Task 11A adds Room authority and deletion-generation barriers; Task 11B adds transactional reference import; Task 14 later connects the reducer, private capture, Room confirmation/advancement, and MediaStore export. Gate 2 remains unpassed until the required common manual path exists; auto-capture and the user-facing shutter remain disabled.

From the repository root on the [verified development environment](docs/DEVELOPMENT.md):

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The prototype APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. Building an APK is not evidence that the planned guided-capture workflow works; the hardware evidence above covers only the bounded Task 10 slice.

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
| [Model and runtime](docs/MODELS.md) | Exact MoveNet/LiteRT pins, provenance, privacy decision, 2D limitations, and upgrade gates |
| [ADR 0001: Android native first](docs/adr/0001-android-native-first.md) | Reversible platform decision and consequences |
| [ADR 0002: On-device pose processing](docs/adr/0002-on-device-pose-processing.md) | Reversible inference-location decision and consequences |
| [Approved implementation plan](.hermes/plans/2026-08-27_111939-pose-guide-snap-android-mvp.md) | Sequenced implementation tasks and gates, amended by the approved telemetry-free MoveNet/LiteRT revision |

Install and product-usage instructions will be added only after those workflows exist and have been verified on an authorized target.
