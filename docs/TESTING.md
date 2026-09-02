# Testing and Acceptance Contract

> **Project status: Tasks 1–12, 14A.1, and 14A.2 are implemented, host-verified, and boundedly Pixel-exercised.** The current JVM suite is 591/591 GREEN; lint and debug/release/instrumentation builds are GREEN. Task 14A.1 passed 6/6 exact-APK Pixel 6 Room tests; Task 14A.2 passed 8/8 exact-APK Pixel 6 tests covering active-session discovery across reopen, fail-closed multi-active corruption, and the 14A.1 regressions. This does not prove UI/process-death resume, camera, capture/export, deletion completion, audio, or the end-to-end workflow. Gates 2–4 remain open.

## Testing principles

- Pure policy first: normalization, matching, coaching, and session transitions must be deterministic pure Kotlin before camera or automatic shutter integration.
- Named gates over blended confidence: coverage, framing, person count, angular similarity, positional similarity, and stability remain separately observable.
- Causal tests: tests must exercise behavior and invariants, not fail only because a planned class does not exist.
- Exactly-once capture: automatic and manual triggers, replay, stale callbacks, duplicate receipts, timeouts, crashes, and partial three-file failure must not advance more than once.
- Authority separation: all three app-private outputs and the Room confirmation/advance transaction complete before advancement; MediaStore export happens afterward and can never own or replay advancement.
- Backup exclusion is defense in depth: source and merged manifests plus both rule resources must exclude sensitive data from cloud, device-to-device, and supported cross-platform transfer; absent `INTERNET` permission is not evidence of this boundary.
- External identity is exact: only a durable per-output MediaStore URI can authorize automatic update/delete; metadata matching must preserve foreign rows.
- Same-artifact evidence: final functional, privacy, audio-route, and quality/security review must use the exact same APK digest.
- Truthful evidence: automated replay, instrumentation, emulator, and authorized real-device results must be labeled separately.
- No threshold by intuition: production lock thresholds require a documented positive/negative separation report and real-device calibration.
- Private hardware gate: use of the Pixel 6, earbuds, or private images requires explicit permission when that test begins.

## Planned quality gates

| Gate | Required evidence | Claims allowed after passing |
|---|---|---|
| Gate 0: approved boundary and toolchain | Approved product docs; verified JDK, Android SDK, platform/build tools, and `adb`; blank debug APK and unit tests from the command line | The project can bootstrap and build, not that guided capture works |
| Gate 1: deterministic offline engine | Pure JVM tests for normalization, mirror handling, separated gates, cue choice, hysteresis, idempotency, and sequence transitions; no Android/CameraX/LiteRT/Room/TTS types in domain | Offline domain behavior matches its fixtures, not live pose accuracy |
| Gate 2: single-reference camera slice | Licensed bundled reference; on-device extraction; Pixel 6 preview, analysis overlay, match report, and reducer-owned manual trigger through the unified private three-photo confirmation pipeline; auto-capture disabled | The manual slice and common durable capture protocol work on the tested APK/device |
| Gate 3: complete local MVP loop | Import/order at least five references; bounded speech; stable lock triggers the same private three-photo pipeline; Room confirmation advances once and queues export; five-pose no-touch completion; airplane-mode operation | The local MVP loop works in the tested conditions, with export reported separately |
| Gate 4: real-device acceptance | Same APK digest across functional, privacy, audio, storage/export/deletion, and quality/security checks; full Pixel 6 matrix | Only the exact documented behavior and conditions; still no store/publication claim |

Gate 0's command-line bootstrap is GREEN on the verified host. Task 9's direct MoveNet model boundary, Task 10 camera evidence, Task 11A's Room authority, Task 11B's transactional reference-import backend, and Task 12's Room V3 preparation UI each have authorized Pixel evidence. They do not complete Gate 2: the full reducer/private-file/Room confirmation/manual-trigger/export path remains Task 14. No emulator evidence exists. Gates 2–4 therefore remain unpassed.

The reviewed Task 10 APKs were reproducible byte-for-byte: main SHA-256 `a678f014cefc19281bd253cfdb64b97bf0a3ec65f2e3d2f374248bfd47dfc3ad` and instrumentation SHA-256 `3f0985b207286c0c4f249183ae5d434488edf129afd53ed401f70988bd8135c5`.

Task 11A evidence is deliberately narrower than the future end-to-end gate:

- Room V1 schema/runtime: 4/4 Pixel methods.
- Registration and capture-start authority: 22/22 current methods across the original 20-method run plus two focused negative-generation regressions.
- Confirmation, duplicate replay, immutable authority, and complete rollback matrix: 33/33 current methods across the original 32-method run plus one focused negative-generation regression.
- Deletion, targeted claims, restart, deterministic orderings, and two-database concurrency: 34/34 on the final hardening APK in 1.996 seconds.
- Production database entries and test-database residue were zero after the final 34-method and focused three-method runs. No MediaStore row or private image was created.

Task 11B evidence is also bounded and separate from the future UI/end-to-end gate:

- 413/413 JVM tests passed with zero failures, errors, or skips.
- Lint plus debug, release, and instrumentation APK assembly passed.
- Room V1 remained byte-identical; the V1→V2 migration ran on the Pixel 6 and preserved legacy data while installing the exact V2 authority contract.
- The exact APK pair passed 25/25 targeted Pixel 6 methods covering generated-byte no-clobber publication, every persisted file-ledger transition, transaction rollback/concurrency, restart cleanup/quarantine, picker-handler dispatch/redaction, and public-fixture MoveNet analysis.
- Test databases, Room lock files, reference-import files, and the instrumentation package were removed and verified absent. No provider image, camera capture, user database content, or MediaStore row was read or created.

Task 12 evidence remains bounded to shoot preparation and durable camera-route admission:

- 556/556 JVM tests passed with zero failures, errors, or skips.
- Lint plus debug, release, and instrumentation APK assembly passed.
- Room V1 and V2 schema artifacts remained byte-identical; V3 added sole active-order ownership in `shoot_poses`, preparation projections, and one-active-session enforcement.
- The final authorized Pixel 6 gate passed 4/4 `ShootEditorFlowTest` methods. That class uses synthetic screen state and no database, picker, camera, or device I/O; it verified recovery rendering, exact row-scoped text and reorder callbacks, start enablement/callback behavior, and visible import/reconciliation states.
- A separate manual Pixel run on a pre-final artifact observed production shoot creation/navigation, three public-fixture system Photo Picker imports, Room/MoveNet settlement, transactional reorder, durable start, camera gating, and picker callback survival across recreation. The final source changed afterward, so this is supporting manual evidence rather than same-artifact proof for `5bc15c3`.
- During that manual run, camera permission was denied after durable start and no preview or sensor run occurred. Device fixture, app/test data, instrumentation package, and rotation changes were cleaned up.
- Compose semantics and controls were exercised on-device, but no TalkBack or screen-reader usability claim is made. See `validation/2026-09-01-task12-room-v3-shoot-preparation-pixel6.md`.

Task 14A.1 evidence remains bounded to exact-session Room reconstruction:

- 579/579 JVM tests passed with zero failures, errors, or skips; lint plus debug, release, and instrumentation APK assembly passed.
- Room V1–V3 schema artifacts remained byte-identical.
- The installed main and instrumentation APK bytes matched their recorded local SHA-256 values.
- The Pixel 6 running Android 16 passed 6/6 focused methods: one nonzero close/reopen repository test plus five transaction/mutation-control/read-only tests.
- Room's generated blocking DAO uses an immediate transaction. Confirmation and deletion writers remained blocked while bootstrap was paused before its second SELECT; bootstrap returned complete pre-state and later reads returned complete post-state.
- Equivalent nontransactional confirmation/deletion reads produced the expected mixed pre/post facts, demonstrating that the transaction gate is causal rather than decorative.
- Repeated bootstrap reads changed no V3 authority table, schema digest, `total_changes()`, or `PRAGMA data_version`.
- UUID-named test databases and the instrumentation package were verified absent afterward. No camera, picker, image, MediaStore, UI, or personal-data path was exercised. See `validation/2026-09-02-task14a1-atomic-room-v3-bootstrap-pixel6.md`.

Task 14A.2 evidence remains bounded to Room active-session discovery:

- 591/591 JVM tests passed with zero failures, errors, or skips; lint plus debug, release, and instrumentation APK assembly passed; schemas unchanged.
- The new mapper class produced 12/12 behavioral runtime-assertion failures against its compile-safe placeholder before implementation, then 12/12 passes.
- The installed APK pair matched recorded SHA-256 values and passed 8/8 focused Pixel 6 methods: exact discovery across close/reopen, sessionless-shoot `None`, missing-shoot `UnknownShoot`, fail-closed multi-active corruption with the one-active triggers dropped in the UUID test database, plus the six Task 14A.1 regressions.
- Test databases and the instrumentation package were verified absent afterward. See `validation/2026-09-02-task14a2-active-session-discovery-pixel6.md`.

## Pure JVM test matrix

Planned coverage:

- Coordinate validation and immutable model invariants.
- Translation and scale normalization.
- Default mirror matching, per-pose mirror opt-out, and left/right cue correctness.
- Joint-angle and normalized position scoring.
- Separate coverage, framing, and second-person gates.
- Deterministic seeded jitter and negative controls.
- Cue selection, confidence filtering, persistence, cooldown, suppression, and priority.
- Reducer transition table, dwell, hysteresis, timing, pause, stale frames, automatic and manual requests, manual bypass of only match/lock, capture success/failure, duplicate receipts, idempotency, and completion.
- Unified exactly-three-output policy; deterministic private identities; exclusive non-authoritative empty reservations; same-directory temp writes; stable reservation-identity verification; process-wide supported-mutation serialization; private write, sync, atomic rename, and cleanup failure; final-path collision/no-clobber; typed crash/collision reconciliation; retained prepare-time and post-prepare cleanup ownership with blocked submissions and serialized retry; foreign temp preservation; close-versus-third-publication precedence; per-file atomicity without a three-file atomicity claim; timeout; and duplicate callback.
- Room confirmation transaction success/failure proving atomic capture confirmation, three private-output records, exactly-once advancement, receipt application, and outbox creation.
- Crash before the Room transaction with cleanup/quarantine before retry; crash after it with capture/advance complete and export-only replay; cleanup failure and reconciliation-required blocking.
- One committed outbox with exactly three rows; composite `(commandToken, burstOrdinal)` uniqueness, ordinal 0–2 constraint, transaction-time cardinality assertion, and rollback on any violation.
- Paused two-worker interleaving proving one compare-and-set winner and exactly one `MediaStore.insert()`; crash probes at pre-claim, post-claim/pre-insert, post-insert/pre-URI-persist, post-URI/pre-publish, and publish/state-persist seams.
- Claimed/create-started missing-URI work remains reconciliation-required across timeout/restart and is never lease-reset for another create; known exact-URI work resumes without another insert.
- Exact target collection/volume and intended metadata persistence, with tests proving display name/relative path cannot authorize update/delete/reconciliation and a foreign MediaStore row remains unchanged.
- Delete-versus-worker interleavings for the atomic deletion generation: claims blocked after barrier, capture/advance blocked, untouched pending work cancelled, and worker barrier rechecks before create and publication.
- Quarantine retention, visible unresolved count/state, explicit resolution, app-level delete-all through the same barrier, incomplete deletion with minimal tombstone, and no-foreign-row deletion.
- Repository and coordinator behavior through narrow ports.
- Five-pose replay transcript with unique capture tokens and exactly-once advancement.
- Architecture checks that reject Android and SDK dependencies in domain packages.

## Android instrumentation matrix

Planned coverage:

- Exact MoveNet model load and deterministic one-person, black zero-person, and composed two-person extraction within documented tolerance; executed on the authorized Pixel 6.
- Camera keep-latest scheduling with one bounded off-UI blocking-detector worker, guaranteed `ImageProxy` closure, per-frame detector/mapper failure containment, and no unbounded queue.
- Rotation, crop, rear-camera, and mirror coordinate transforms.
- Room transactions, ordering, migrations, command-token/receipt uniqueness, and atomic confirmation + three private outputs + one advance + outbox creation.
- System photo-picker result handling, app-private copy, and failed-import cleanup.
- Reference validation for no person, multiple people, and insufficient coverage.
- Compose create, import, rejection, reorder, and start flow.
- Camera permission denial and recovery.
- Preview, analysis overlay, and captured-image coordinate alignment.
- Text-to-Speech initialization, offline-voice selection, network-required voice rejection, lifecycle, queue cancellation, absent `INTERNET` permission, and visual-only fallback.
- Source-manifest and merged-manifest assertions for `android:allowBackup="false"`, `android:fullBackupContent`, and `android:dataExtractionRules`; parsed source-rule assertions excluding all nine credential/device-protected domains in cloud, device-transfer, and compile-SDK-37 cross-platform sections; assertion that no custom `BackupAgent` is declared.
- APK-level backup-policy acceptance on the exact hashed candidate: use the pinned Build Tools `aapt2 dump xmltree` to inspect the packaged `AndroidManifest.xml` and both packaged compiled XML rule resources. Require both manifest references, no `android.permission.INTERNET`, and the complete nine-domain set in every applicable packaged rule section. Source or merged-intermediate checks cannot substitute for this artifact check.
- App-private capture publication for exactly three deterministic automatic or manual outputs, same-directory temp cleanup, sync/finalization failure, final-path collision/no-clobber, and durable-file inspection.
- Room transaction failure with no advance/receipt/outbox; startup cleanup or quarantine before retry; post-transaction restart with export-only replay; exactly-once receipt and advancement under duplicate callbacks.
- MediaStore outbox schema and worker tests for exact three-row cardinality, composite uniqueness, ordinal bounds, exclusive compare-and-set claims, the complete claim/create/URI/publication crash matrix, exact-URI-only mutation, and a paused two-worker one-insert interleaving.
- Deletion-generation interleavings with claims and publication, quarantine retention/resolution/visible count, app-level delete-all, incomplete tombstone retention, and preservation of already exported, ambiguously created, and foreign MediaStore rows.
- Camera-analysis resource cleanup when leaving the guided screen.

Executed Task 9/10 Pixel evidence for the committed Task 10 artifact includes 15/15 relevant instrumentation passes; selected breakdown and runtime evidence follow:

- Direct MoveNet public/generated fixture path: 1/1 GREEN.
- Generated-bitmap frame ownership, cleanup, and inference path: 7/7 GREEN.
- Installed manifest/permission boundary: 1/1 GREEN.
- Packaged main public-reference drawable reproduces the exact fixed 17-landmark MoveNet observation: 1/1 GREEN; production and instrumentation notices retain the exact source, CC BY 4.0 license, and SHA-256, and the card visibly credits Google AI Edge.
- Generated-byte owned-reservation publication and foreign-final no-clobber: 2/2 GREEN.
- Real reducer-command rear-camera exactly-three JPEG capture plus repeated-token collision/no-clobber: 1/1 GREEN; zero candidate/temp residue after cleanup.
- Real cadence counters: 61 frames received, 21 accepted, 40 skipped too soon, 0 stale; 9.64 analyzed results/s for the bounded sample.
- Generated-black direct MoveNet latency over 25 measured runs after warm-up: p50 124.79 ms, p95 126.53 ms, max 126.64 ms.
- One final-hash 60-second cadence-limited live run: camera active for all 13 samples; mean CPU 148.08%, max CPU 162.0%, post-20-second mean 150.44% in a 140–162% range; PSS 256,793–332,419 KiB with a 324,211–332,419 KiB post-warm range; RSS 408,708–486,192 KiB with a 477,684–486,192 KiB post-warm range; battery 31.0°C to 30.8°C; thermal status 0; no fatal/ANR; 14 GC log entries; camera release within the observed 400–500 ms window after backgrounding. Memory remained bounded. The integrated fixed-reference UI did not reproduce an earlier lower-CPU run and makes no performance-improvement claim; the 15-minute Gate 4 soak remains pending.
- Permission denial screen, explicit system permission-dialog launch, live rear preview, READY/no-person diagnostics, named fixed-reference evidence, bounded reference card, and background release were visually inspected without retaining screenshots or private frame data.
- Public-target overlay acceptance reported one person and 17/17 landmarks; the rear-unmirrored live skeleton and distinct fixed-reference ghost guide shared one preview transform. The live skeleton tracked the bundled meditation figure's head, shoulders, arms, hips, crossed legs, and ankles without clipping. The UI labeled framing as not evaluated, each stable pass/fail prototype gate as uncalibrated, and capture lock as disabled; numeric scores remain internal evidence rather than continuously animated text. The transient screenshot was deleted.

## Pixel 6 real-device acceptance

**Target:** Pixel 6 running Android 16. Rear camera is the only MVP camera acceptance path.

The exact candidate APK digest must be recorded and used for every item below:

- Full-body and three-quarter-body framing at practical mounted-phone distances.
- Five-pose no-touch completion with five unique confirmed capture tokens.
- Automatic and manual capture each use the same exactly-three-output private protocol; manual bypasses only match/lock gating.
- Exactly three durable authoritative private outputs, one committed Room confirmation/advance/receipt/outbox transaction, and exactly-once sequence advancement before any MediaStore export is required.
- Exclusive-claim post-confirmation MediaStore export, including a paused two-worker one-insert race, exact-URI resume, and an injected missing-URI ambiguous-create case that never issues another create or mutates a foreign row.
- Phone speaker output and connected-earbud output through Android's current media route, without forced Bluetooth routing; verify the selected voice does not require a network connection and visual-only fallback works when no offline voice is available.
- Camera permission denial and recovery.
- App background/foreground and safe pause/resume behavior.
- Process restart before a shoot and at the pre-/post-Room capture boundary.
- Private capture, Room confirmation, export, cleanup, and reconciliation failures without accidental recapture or advancement.
- Shoot deletion and clear-data/uninstall behavior, explicitly verifying that already exported MediaStore items are not promised to be deleted.
- Exact-candidate packaged backup manifest and rule-resource inspection, plus authorized backup/restore behavior where platform tooling permits.
- Low light, partial occlusion, no person, subject exit/re-entry, and a second person entering frame.
- Default horizontal mirror matching and per-pose opt-out.
- Airplane-mode operation after app and model installation.
- Authorized backup/restore inspection where Android tools permit, confirming no sensitive capture, Room, outbox, preference, tombstone, or quarantine state is transported or partially restored.
- Sustained 15-minute camera analysis with inference latency, dropped frames, allocations/GC pressure, thermal behavior, battery impact, and analysis-resource cleanup recorded.
- Storage and log inspection confirming that analysis frames, raw landmark arrays, private paths, and MediaStore URIs are not retained or logged contrary to [the privacy contract](PRIVACY.md).

Failure must be recorded honestly. Thresholds may not be lowered merely to manufacture a pass. Any code change creates a new APK digest and requires the affected same-digest reviews to run again.

## Match calibration evidence

A calibration report must identify:

- Authorized positive and negative fixture classes without committing private photos.
- Dataset size and population limits.
- Positive/negative score separation and false-lock cases.
- Coverage, framing, angular, positional, acquire, release, and dwell values.
- Time to lock, duplicate-capture rate, inference latency, and cue rate.
- Boundary fixtures that prevent chosen thresholds from drifting silently.

The report may support a development or tested-device threshold claim. It may not imply population-wide accuracy from a small internal dataset.

## Claim rules

Until the relevant gate passes, documentation and UI must use future or planned language. Passing a gate supports only the behavior, artifact, device, and conditions recorded in its evidence.

No test may turn “pose match” into “perfect pose,” claim that skeleton similarity guarantees a good photograph, or conceal limitations involving viewpoint, depth, occlusion, hands, lighting, expression, composition, or aesthetics.

## Related documents

- [Product contract](PRODUCT.md)
- [Architecture](ARCHITECTURE.md)
- [Privacy](PRIVACY.md)
