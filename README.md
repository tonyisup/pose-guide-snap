# Pose Guide Snap

> **Project status: Task 11A's Room authority is committed, host-reviewed, and Pixel-exercised. Gate 2 remains incomplete.**
>
> Task 11A landed in `53354660c77e51b039e86c091d644faee209593d` after specification PASS and quality/security APPROVED on exact staged digest `bae48fff2ed2cd4f3cda1c69de46f9114f26e84c088c7cc155d1acadeef415d8`. It adds a backup-excluded Room V1 authority schema, deletion-aware attempt registration and capture-start authorization, atomic exactly-three-output confirmation/advancement/receipt/outbox persistence, deletion-generation barriers, and targeted export-claim compare-and-set authority. It still has no product shutter, auto-capture coordinator, reference import, MediaStore I/O worker, physical deletion, TTS/audio, deletion UI, or end-to-end guided workflow.

Pose Guide Snap is a planned Android-first guided selfie app. The intended MVP will let one person arrange a sequence of reference poses, receive concise framing and pose guidance, automatically trigger a three-photo capture only after a stable, high-confidence pose match, or request the same three-photo protocol manually. Pose processing is planned to stay on the device.

## Current implementation

The provisional application ID is `com.tonyisup.poseguidesnap`; the prototype version is `0.1.0` (`versionCode` 1), with `minSdk 29` and target SDK 37. It requests `android.permission.CAMERA` and AndroidX's app-signature `com.tonyisup.poseguidesnap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; it requests no `INTERNET`, storage, location, audio, or foreground-service permission and includes no network, cloud, or analytics library. The manifest disables Android backup and points to fail-closed legacy and API 31+ rules that exclude every supported app storage domain from cloud backup, device transfer, and the compile-SDK-37 iOS cross-platform transfer surface.

Verified Task 10 and Task 11A evidence:

- JVM suite: 266/266 GREEN; lint and debug main/instrumentation builds GREEN.
- Reproducible APK SHA-256 values: main `a678f014cefc19281bd253cfdb64b97bf0a3ec65f2e3d2f374248bfd47dfc3ad`; instrumentation `3f0985b207286c0c4f249183ae5d434488edf129afd53ed401f70988bd8135c5`.
- Authorized Pixel 6 relevant instrumentation: 15/15 GREEN. The fixed attributed bundled reference, aligned live/reference skeletons, real rear-camera candidate capture, no-clobber behavior, zero private capture residue, and 400–500 ms camera release all passed.
- Final 60-second run: mean CPU 148.08%, peak 162%, thermal status 0, battery 31.0→30.8°C, no fatal/ANR, and bounded memory. It did **not** improve over the pre-cadence baseline; the 15-minute Gate 4 soak remains pending.
- No private images, screenshots, or raw landmark streams are retained.
- Task 11A JVM suite: 307/307 GREEN; lint plus debug, release, and instrumentation APK builds GREEN. Room V1 schema SHA-256: `e5eb94f4ff96944cc9de1aa5c2f6e8e326ba5caaa224c46f3247056cb1c33ab8`.
- Across explicitly authorized checkpoint runs, all 93 current Room-authority instrumentation methods passed: schema/runtime 4, registration/start 22, confirmation/rollback 33, and deletion/claim/restart/concurrency 34. The final hardening APK ran the 34-method Task 11A.4 class plus three focused negative-generation regressions; production database entries and test-database residue were zero after both runs.
- Only a fresh `PENDING → CLAIMED` compare-and-set grants external-create authority. Persisted claim replay is informational and reconciliation-required; Task 11A performs no MediaStore insertion or file deletion.

The internal camera candidate-capture mechanics are not yet connected to the durable Room protocol. Task 11B adds transactional reference import; Task 14 later connects the reducer, private capture, Task 11A confirmation/advancement, and MediaStore export. Gate 2 remains unpassed until the required common manual path exists; auto-capture and the user-facing shutter remain disabled.

From the repository root on the [verified development environment](docs/DEVELOPMENT.md):

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The prototype APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. Building an APK is not evidence that the planned guided-capture workflow works; the hardware evidence above covers only the bounded Task 10 camera and Task 11A Room-authority slices, not their end-to-end integration.

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
