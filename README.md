# Pose Guide Snap

> **Project status: Tasks 1–12 and 14A.1–14A.3 are implemented and boundedly verified. The Task 14B.1A Room V4 journal foundation landed at `57b33c9`, but final specification review returned `REQUEST_CHANGES`; an uncommitted local repair is under review. Gate 2 remains incomplete, and no product-shipped claim is made.**
>
> Task 12 completed the Room V3 shoot-preparation workflow, and Task 14A.3 added stale-safe Room session discovery and Ready-only camera admission. The Task 14B.1A candidate migrates local persistence to Room V4, atomically pairs each new attempt with exactly three `capture_file_operations` rows, and reads that journal as bootstrap authority. `Started` is logical Room state only: it grants no camera, filesystem, or per-file effect authority. Unfinished confirmation is deliberately blocked until journal-owned first application in Task 14B.1C. There is still no product shutter, auto-capture coordinator, capture-to-filesystem/Room integration, MediaStore I/O worker, physical deletion UI, TTS/audio, or end-to-end guided workflow.

Pose Guide Snap is a planned Android-first guided selfie app. The intended MVP will let one person arrange a sequence of reference poses, receive concise framing and pose guidance, automatically trigger a three-photo capture only after a stable, high-confidence pose match, or request the same three-photo protocol manually. Pose processing is planned to stay on the device.

## Current implementation

The provisional application ID is `com.tonyisup.poseguidesnap`; the prototype version is `0.1.0` (`versionCode` 1), with `minSdk 29` and target SDK 37. It requests `android.permission.CAMERA` and AndroidX's app-signature `com.tonyisup.poseguidesnap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; it requests no `INTERNET`, storage, location, audio, or foreground-service permission and includes no network, cloud, or analytics library. The manifest disables Android backup and points to fail-closed legacy and API 31+ rules that exclude every supported app storage domain from cloud backup, device transfer, and the compile-SDK-37 iOS cross-platform transfer surface.

Verified implementation evidence:

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
- Task 14A.3 host suite: 618/618 GREEN; lint plus debug, release, and instrumentation APK builds GREEN; schemas unchanged. The final installed APK pair passed 11/11 Pixel 6 methods across two successful bounded invocations: five editor Compose methods, four Ready-only camera-admission/recovery Compose methods using injected fake camera content (including compact-height/large-font scroll reachability), and the two exact Room discovery/bootstrap reopen regressions. Selector failures and cleanup are recorded rather than counted as passing evidence. See the [Task 14A.3 validation record](docs/validation/2026-09-02-task14a3-stale-safe-resume-pixel6.md).
- Task 14B.1A checkpoints through Task 4 were implemented and verified before the 43-path foundation landed at `57b33c9`. The exact three-file Task 4 Android-test candidate v5 patch, SHA-256 `377dc02c781ece2cf78e48f93c727d02ea9d41082a12229ff22803e97a306491`, received specification `PASS` and engineering/security `APPROVED`. JVM tests passed 635/635, Android-test compilation passed, the Pixel 6 migration class passed 10/10, the focused NUL snapshot-oracle plus direct-migration run passed 2/2, and the integrated five-class Pixel 6 run executed 105 tests with 0 failures, 0 errors, and 10 expected `@Ignore` skips. Those skipped first-application confirmation tests remain deferred; they are not passing evidence. The later complete-candidate review became stale when that exact candidate landed and its narrow continuation returned `REQUEST_CHANGES` for a byte-equivalent `BLOB` residual-journal key that could evade confirmed-replay rejection.
- After documentation review exposed caller-list traversal before the unfinished-confirmation guard, repair v1 was specification-rejected for a receipt-first bypass and repair v2 was specification-rejected for using a nullable confirmation timestamp instead of lifecycle authority. The final eight-scenario Pixel 6 method covers REGISTERED and CAPTURING with null or malformed non-null confirmation timestamps, each with and without a raw receipt. It failed causally on v2 with 3 caller-element reads and passed 1/1 after v3 moved the unavailable guard to explicit lifecycle state. Repair v3, SHA-256 `ed29acd613a890d213e398aaacf4a2d79512662ac0dbf88c411404fcfdb3bd3a`, received exact-byte specification `PASS` and engineering/security `APPROVED`. The current-byte integrated five-class Pixel 6 run executed 106 tests with 0 failures, 0 errors, and 10 expected skips, and left no matching test-database residue. This approval covers only the two-file repair; it is not approval of the complete Task 14B.1A landing candidate.
- The Task 5 cumulative host gate passed again after that repair: 635/635 JVM tests, lint with zero errors, and debug, release, and Android-test APK assembly. Room V1–V3 remained byte-identical, regenerated V4 matched its frozen bytes, `room-compiler` was absent from runtime classpaths and packaged dex, and all three emitted APK manifests contained no `INTERNET` permission.
- The uncommitted follow-up changes the residual-authority count to byte-correlate token keys and adds a deterministic malformed-storage regression. The focused Pixel 6 test failed on `57b33c9` with `AlreadyApplied`, then passed 1/1 after the query repair with no test-database residue. The repaired cumulative candidate has not yet received specification or engineering/security approval and has not been committed or pushed.

In the corrected cumulative tree based on landed commit `57b33c9`, Room V4 reconstructs one exact guided session from nine same-transaction authority families, including capture-file operations. Fresh registration commits one attempt, exactly three deterministic initial journal rows, and the session counter compare-and-set atomically. V3→V4 migration preserves every preexisting authority value and creates no journal rows for migrated attempts. Deletion validates the journal's stable authority and causal clocks while leaving stable `EXPECTING_RESERVATION` rows unchanged and non-blocking. Malformed storage classes, values, ownership, keys, or authority graphs fail closed. No production per-file effect-admission or journal-transition API exists yet, and no camera/filesystem capture path or physical effect was implemented or proven by Task 14B.1A.

From the repository root on the [verified development environment](docs/DEVELOPMENT.md):

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The prototype APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. Building an APK is not evidence that the planned guided-capture workflow works; the hardware evidence above covers bounded Task 10 camera, Task 11A Room authority, Task 11B reference import, Task 12 editor semantics, Task 14A.1–14A.3 bootstrap/discovery/admission slices, and the Task 14B.1A Room V4 authority foundation, plus a separately labeled pre-final manual preparation observation. It does not establish their end-to-end integration.

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
| [Architecture](docs/ARCHITECTURE.md) | Current and planned component boundaries, state ownership, matching, coaching, and capture invariants |
| [Testing](docs/TESTING.md) | Current Room V4 candidate evidence and planned quality/Pixel 6 acceptance matrix |
| [Privacy](docs/PRIVACY.md) | Implemented Room metadata boundaries and planned local data lifecycle, retention, logging, and deletion behavior |
| [Development environment](docs/DEVELOPMENT.md) | Verified toolchain, build/test commands, and exact APK inspection procedure |
| [Model and runtime](docs/MODELS.md) | Exact MoveNet/LiteRT pins, provenance, privacy decision, 2D limitations, and upgrade gates |
| [ADR 0001: Android native first](docs/adr/0001-android-native-first.md) | Reversible platform decision and consequences |
| [ADR 0002: On-device pose processing](docs/adr/0002-on-device-pose-processing.md) | Reversible inference-location decision and consequences |
| [ADR 0003: Persisted reference-import file ledger](docs/adr/0003-persisted-reference-import-file-ledger.md) | Durable cross-storage import authority and restart-recovery contract |
| [ADR 0004: Room V3 shoot-preparation authority](docs/adr/0004-room-v3-shoot-preparation-authority.md) | Import-attempt/order separation, preparation ownership, and durable start |
| [ADR 0005: Atomic Room V3 guided-session bootstrap](docs/adr/0005-atomic-room-v3-guided-session-bootstrap.md) | Exact-session reconstruction, active-session discovery, coherence validation, and immediate-transaction writer exclusion |
| [ADR 0006: Room V4 capture-file journal foundation](docs/adr/0006-room-v4-capture-file-journal-foundation.md) | Durable capture-file intent, logical-start limits, migration behavior, bootstrap authority, and deferred effect admission/confirmation |
| [Task 14A.1 validation](docs/validation/2026-09-02-task14a1-atomic-room-v3-bootstrap-pixel6.md) | Exact APK hashes, 6/6 Pixel Room results, cleanup, and evidence limits |
| [Task 14A.2 validation](docs/validation/2026-09-02-task14a2-active-session-discovery-pixel6.md) | Exact APK hashes, 8/8 Pixel discovery/regression results, cleanup, and evidence limits |
| [Task 14A.3 validation](docs/validation/2026-09-02-task14a3-stale-safe-resume-pixel6.md) | Exact APK hashes, 11/11 Pixel stale-safe resume/admission results, correction RED/GREEN, cleanup, and evidence limits |
| [Approved implementation plan](.hermes/plans/2026-08-27_111939-pose-guide-snap-android-mvp.md) | Sequenced implementation tasks and gates, amended by the approved telemetry-free MoveNet/LiteRT revision |

Install and product-usage instructions will be added only after those workflows exist and have been verified on an authorized target.
