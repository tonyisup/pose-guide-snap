# Pose Guide Snap

> **Project status: Tasks 1–12, 14A.1, and 14A.2 are implemented, host-verified, and boundedly Pixel-exercised. Gate 2 remains incomplete.**
>
> Task 12 completed the Room V3 shoot-preparation authority and a semantics-labeled create → Photo Picker import → validate → reorder → durably start workflow. The final Pixel follow-up landed in `5bc15c33c5b6c36196f99a2bd8259fe2b00ffeb3` after specification and quality/security/UX approval of exact staged digest `23318b4fa98a15405462f3419d1a5d47ac84af0ef12a79b40b1668d4212d0864`. Camera permission and camera construction remain unreachable until Room owns a valid active session. There is still no product shutter, auto-capture coordinator, capture-to-Room integration, MediaStore I/O worker, physical deletion UI, TTS/audio, or end-to-end guided workflow.

Pose Guide Snap is a planned Android-first guided selfie app. The intended MVP will let one person arrange a sequence of reference poses, receive concise framing and pose guidance, automatically trigger a three-photo capture only after a stable, high-confidence pose match, or request the same three-photo protocol manually. Pose processing is planned to stay on the device.

## Current implementation

The provisional application ID is `com.tonyisup.poseguidesnap`; the prototype version is `0.1.0` (`versionCode` 1), with `minSdk 29` and target SDK 37. It requests `android.permission.CAMERA` and AndroidX's app-signature `com.tonyisup.poseguidesnap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; it requests no `INTERNET`, storage, location, audio, or foreground-service permission and includes no network, cloud, or analytics library. The manifest disables Android backup and points to fail-closed legacy and API 31+ rules that exclude every supported app storage domain from cloud backup, device transfer, and the compile-SDK-37 iOS cross-platform transfer surface.

Verified Task 10–12 evidence:

- Task 10 JVM suite: 266/266 GREEN; lint and debug main/instrumentation builds GREEN.
- Reproducible APK SHA-256 values: main `a678f014cefc19281bd253cfdb64b97bf0a3ec65f2e3d2f374248bfd47dfc3ad`; instrumentation `3f0985b207286c0c4f249183ae5d434488edf129afd53ed401f70988bd8135c5`.
- Authorized Pixel 6 relevant instrumentation: 15/15 GREEN. The fixed attributed bundled reference, aligned live/reference skeletons, real rear-camera candidate capture, no-clobber behavior, zero private capture residue, and 400–500 ms camera release all passed.
- Final 60-second run: mean CPU 148.08%, peak 162%, thermal status 0, battery 31.0→30.8°C, no fatal/ANR, and bounded memory. It did **not** improve over the pre-cadence baseline; the 15-minute Gate 4 soak remains pending.
- No private images, screenshots, or raw landmark streams are retained.
- Task 11A JVM suite: 307/307 GREEN; lint plus debug, release, and instrumentation APK builds GREEN. Room V1 schema SHA-256: `e5eb94f4ff96944cc9de1aa5c2f6e8e326ba5caaa224c46f3247056cb1c33ab8`.
- Across explicitly authorized checkpoint runs, all 93 current Room-authority instrumentation methods passed: schema/runtime 4, registration/start 22, confirmation/rollback 33, and deletion/claim/restart/concurrency 34. The final hardening APK ran the 34-method Task 11A.4 class plus three focused negative-generation regressions; production database entries and test-database residue were zero after both runs.
- Only a fresh `PENDING → CLAIMED` compare-and-set grants external-create authority. Persisted claim replay is informational and reconciliation-required; Task 11A performs no MediaStore insertion or file deletion.
- Task 11B JVM suite: 413/413 GREEN; lint plus debug, release, and instrumentation APK builds GREEN. Room V1 remained byte-identical while V2 added the reference-import intent and file-operation ledger.
- The exact Task 11B APK pair passed 25/25 targeted Pixel 6 instrumentation methods covering V1→V2 migration, ledger transitions, no-clobber generated-byte publication, transaction rollback, restart reconciliation, picker-result dispatch/redaction, and public-fixture MoveNet analysis. Test databases, Room lock files, reference assets, and the instrumentation package were removed and verified absent.
- Current Task 12 host suite: 556/556 GREEN with zero failures, errors, or skips; lint plus debug, release, and instrumentation APK builds GREEN. Room V1 and V2 schema artifacts remain byte-identical, and V3 is the active schema.
- The final authorized Pixel 6 gate passed 4/4 synthetic-state `ShootEditorFlowTest` methods covering recovery rendering, row-scoped semantics and reorder callbacks, start enablement/callback behavior, and visible import/reconciliation states. A separate earlier manual Pixel run observed the production create/import/reorder/start path, picker recreation, and camera-permission boundary on a pre-final artifact; it is not same-artifact evidence for `5bc15c3`. Camera permission was denied and no preview or sensor run occurred. See the [Task 12 validation record](docs/validation/2026-09-01-task12-room-v3-shoot-preparation-pixel6.md).
- Task 14A.1 host suite: 579/579 GREEN; lint plus debug, release, and instrumentation APK builds GREEN; Room V1–V3 schema artifacts unchanged. The exact APK pair passed 6/6 Pixel 6 Room tests for nonzero close/reopen restoration, confirmation/deletion writer exclusion under the Room 2.8.4 immediate read transaction, two nontransactional mutation controls, and read-only table/schema evidence. See the [Task 14A.1 validation record](docs/validation/2026-09-02-task14a1-atomic-room-v3-bootstrap-pixel6.md).
- Task 14A.2 host suite: 591/591 GREEN; lint plus debug, release, and instrumentation APK builds GREEN; schemas unchanged. The exact APK pair passed 8/8 Pixel 6 Room tests: exact active-session discovery across close/reopen, fail-closed multi-active corruption with the one-active triggers bypassed, plus the six Task 14A.1 regressions. See the [Task 14A.2 validation record](docs/validation/2026-09-02-task14a2-active-session-discovery-pixel6.md).

Room can now reconstruct one exact guided session through a redacted atomic V3 snapshot and discover the at-most-one active session for an exact shoot ID, but no UI route consumes either after process death. Stale-safe UI resume routing is the next separate ownership change. Standalone speech is deferred until the guided-session coordinator exists. Gate 2 remains unpassed; capture/export integration, auto-capture, and the user-facing shutter remain disabled.

From the repository root on the [verified development environment](docs/DEVELOPMENT.md):

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The prototype APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. Building an APK is not evidence that the planned guided-capture workflow works; the hardware evidence above covers bounded Task 10 camera, Task 11A Room authority, Task 11B reference import, Task 12 editor semantics, and Task 14A.1 Room bootstrap slices, plus a separately labeled pre-final manual preparation observation. It does not establish their end-to-end integration.

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
| [ADR 0003: Persisted reference-import file ledger](docs/adr/0003-persisted-reference-import-file-ledger.md) | Durable cross-storage import authority and restart-recovery contract |
| [ADR 0004: Room V3 shoot-preparation authority](docs/adr/0004-room-v3-shoot-preparation-authority.md) | Import-attempt/order separation, preparation ownership, and durable start |
| [ADR 0005: Atomic Room V3 guided-session bootstrap](docs/adr/0005-atomic-room-v3-guided-session-bootstrap.md) | Exact-session reconstruction, active-session discovery, coherence validation, and immediate-transaction writer exclusion |
| [Task 14A.1 validation](docs/validation/2026-09-02-task14a1-atomic-room-v3-bootstrap-pixel6.md) | Exact APK hashes, 6/6 Pixel Room results, cleanup, and evidence limits |
| [Task 14A.2 validation](docs/validation/2026-09-02-task14a2-active-session-discovery-pixel6.md) | Exact APK hashes, 8/8 Pixel discovery/regression results, cleanup, and evidence limits |
| [Approved implementation plan](.hermes/plans/2026-08-27_111939-pose-guide-snap-android-mvp.md) | Sequenced implementation tasks and gates, amended by the approved telemetry-free MoveNet/LiteRT revision |

Install and product-usage instructions will be added only after those workflows exist and have been verified on an authorized target.
