# Testing and Acceptance Contract

> **Project status: bootstrap GREEN only.** A JVM bootstrap/privacy contract suite passes and the app plus instrumentation test APKs compile. No instrumentation, emulator, private-device, camera, pose, coaching, capture, storage, or workflow test has run.

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
| Gate 1: deterministic offline engine | Pure JVM tests for normalization, mirror handling, separated gates, cue choice, hysteresis, idempotency, and sequence transitions; no Android/CameraX/MediaPipe/Room/TTS types in domain | Offline domain behavior matches its fixtures, not live pose accuracy |
| Gate 2: single-reference camera slice | Licensed bundled reference; on-device extraction; Pixel 6 preview, analysis overlay, match report, and reducer-owned manual trigger through the unified private three-photo confirmation pipeline; auto-capture disabled | The manual slice and common durable capture protocol work on the tested APK/device |
| Gate 3: complete local MVP loop | Import/order at least five references; bounded speech; stable lock triggers the same private three-photo pipeline; Room confirmation advances once and queues export; five-pose no-touch completion; airplane-mode operation | The local MVP loop works in the tested conditions, with export reported separately |
| Gate 4: real-device acceptance | Same APK digest across functional, privacy, audio, storage/export/deletion, and quality/security checks; full Pixel 6 matrix | Only the exact documented behavior and conditions; still no store/publication claim |

Gate 0's command-line bootstrap is GREEN on the verified host: the native prototype builds, JVM tests pass, and the exact APK's manifest and backup resources have been inspected. Gates 1–4 have not passed; the instrumentation test is compile-only and no device or emulator evidence exists.

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
- Unified exactly-three-output policy; deterministic private identities; same-directory temp writes; private write, sync, and finalization failure; final-path collision/no-clobber; per-file atomicity without a three-file atomicity claim; timeout; and duplicate callback.
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

- MediaPipe model load and deterministic static-fixture extraction within documented tolerance.
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
- Sustained 15-minute camera analysis with inference latency, dropped frames, thermal behavior, and battery impact recorded.
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
