# ADR 0006: Room V4 Capture-File Journal Foundation

- **Status:** Accepted architecture; the Task 14B.1A foundation landed at `57b33c9`, but final specification review returned `REQUEST_CHANGES` for a malformed residual-authority key; an uncommitted local repair awaits exact-byte review, and no product-shipped claim is made
- **Date:** 2026-09-03
- **Decision owner:** Product/architecture owner
- **Scope:** Task 14B.1A Room V4 capture-file journal foundation only

## Context

A capture attempt spans Room authority and later app-private filesystem effects that cannot share one physical transaction. Room V3 can register and mark an attempt `CAPTURING`, but it has no durable per-file intent rows connecting the attempt to the three deterministic capture identities. Treating logical attempt start as permission to invoke CameraX or mutate the filesystem would leave deletion, restart, and confirmation unable to distinguish durable admission from process-local intent.

The rejected monolithic Task 14B.1 design combined schema, logical start, per-file effect admission, deletion interlocks, reconciliation, and first-application confirmation. Review exposed a false one-winner claim between logical start and deletion: when all file intents are still stable and no effect has been admitted, logical start and later deletion may both safely commit. The work therefore needs independently landable authority stages.

## Decision

Adopt Room V4 as an additive journal foundation and split Task 14B.1 into three stages:

1. **Task 14B.1A:** establish the Room V4 journal, registration seeding, non-authorizing logical start, bootstrap/deletion participation, and fail-closed confirmation guard.
2. **Task 14B.1B:** add Room-owned per-file physical-effect admission, stage transition, reconciliation, and deletion interlock APIs without performing physical I/O.
3. **Task 14B.1C:** make first-application confirmation derive its three immutable private outputs from coherent final journal authority.

### Room V4 journal

Room V4 adds `capture_file_operations`, keyed by `(command_token, burst_ordinal)` with ordinals 0–2 and a restrictive foreign key to the owning capture attempt. Each new attempt registration transaction atomically commits exactly:

- one capture attempt;
- three ordered journal rows at `EXPECTING_RESERVATION`, with deterministic final, temporary, and quarantine paths and no file evidence; and
- one session attempt-counter compare-and-set.

Any failed insert or postcondition rolls back the whole registration. The table reserves the closed future stage and evidence vocabulary, but Task 14B.1A writes only the initial stage and exposes no production API that can admit or transition a per-file physical effect.

### Logical start is non-authorizing

`markCaptureAttemptStarted(...)` replaces the misleading authorization-shaped API name. A successful `CaptureAttemptStartResult.Started` means only that coherent Room authority was validated and the attempt lifecycle transition to `CAPTURING` committed. It is not permission to call CameraX, reserve a path, write bytes, rename, delete, quarantine, or otherwise touch the filesystem.

Deletion first blocks a later logical start. Logical start first may be followed by successful deletion while all three rows remain stable `EXPECTING_RESERVATION`, because no physical effect has been admitted. No one-winner claim applies until Task 14B.1B adds per-file admission.

### Bootstrap and deletion authority

Guided-session bootstrap reads `capture_file_operations` as its ninth authority family in the same Room transaction as the existing session graph. It validates exact attempt ownership, cardinality, ordinals, deterministic paths, lifecycle compatibility, evidence/failure shape, clocks, and canonical storage before returning a typed projection.

Stable coherent `EXPECTING_RESERVATION` rows are non-blocking for deletion. They nevertheless participate losslessly in deletion's causal clock: every journal `created_at_epoch_millis`, `updated_at_epoch_millis`, and present `captured_at_epoch_millis` is validated and included without filtering contradictory rows out of the authority snapshot. Malformed storage classes, row shapes, ownership, paths, cardinality, clocks, or authority graphs fail closed before mutation.

Packet 2B advances to a V4 test database and includes the journal as the ninth authority family in its complete snapshot and digest.

### Confirmation remains fail closed

Task 14B.1A does not implement first application:

- exact command-token attempt lookup and token/pose matching occur first; an unfinished `REGISTERED` or `CAPTURING` attempt then returns `JOURNAL_CONFIRMATION_NOT_AVAILABLE` unconditionally before caller-list element traversal, receipt lookup, journal reads, session/shoot loads, deletion classification, broader-authority reads, or mutation;
- on the `CONFIRMED` path, caller snapshot/validation retains its existing precedence before receipt and residual-journal adjudication;
- a confirmed replay with any residual capture-file journal row returns `JOURNAL_AUTHORITY_INVALID`;
- only a coherent, receipt-backed confirmed replay with zero journal rows can remain `AlreadyApplied`.

The temporarily retained caller-supplied private-output parameter is not authority for a new confirmation in this stage. Task 14B.1C must remove it and derive first application from exactly three coherent ordered `FINAL_DURABLE` journal rows.

## Invariants

1. Every newly registered V4 attempt is atomically paired with exactly three initial journal rows; no partial attempt/journal/counter commit is observable.
2. `Started` is a logical Room result only and grants no camera or filesystem capability.
3. Task 14B.1A has no production per-file effect-admission, transition, or reconciliation API.
4. Stable `EXPECTING_RESERVATION` rows do not interlock deletion, but all of their causal clocks are validated and included in deletion's complete maximum.
5. Malformed SQLite storage classes, stages, evidence, paths, ownership, cardinality, clocks, or cross-table graphs fail closed without normalization or partial mutation.
6. Unfinished confirmation cannot reach caller-selected output traversal or first-application writes.
7. Confirmed replay is immutable only when its receipt-backed graph is coherent and transient journal authority is absent.
8. Bootstrap and Packet 2B treat the journal as constituent authority, not optional metadata.

## Migration and compatibility

V3→V4 is additive. It creates the new table and indexes, installs the ordinal and row-shape triggers through the existing database callback, preserves every existing V3 authority row and storage value, and fabricates zero capture-file journal rows.

Migrated unfinished `REGISTERED` and `CAPTURING` attempts therefore cannot acquire journal authority by inference. Confirmation returns `JOURNAL_CONFIRMATION_NOT_AVAILABLE`; later settlement/restart work must resolve them explicitly. A migrated coherent confirmed graph remains eligible only for receipt-backed `AlreadyApplied` replay with zero journal rows. V1–V3 schema artifacts remain unchanged.

## Consequences

### Positive

- Every new capture attempt has three durable file-intent identities before it becomes reachable.
- Logical lifecycle state is separated from physical-effect admission.
- Bootstrap, deletion, migration, and replay can fail closed against one persisted journal family.
- Stable no-effect intent does not unnecessarily block deletion.
- Task 14B.1B and Task 14B.1C can be reviewed and landed independently without enabling a half-built capture path.

### Cost

- Room V4 adds a table, indexes, trigger installation, migration evidence, and a ninth bootstrap/Packet 2B authority family.
- New unfinished attempts intentionally cannot confirm until Task 14B.1C.
- Per-file mutation requests and result contracts may exist as data shapes, but no production service can execute them in Task 14B.1A.
- Migrated unfinished V3 attempts require later explicit settlement/restart handling rather than synthesized journal rows.

## Rejected alternatives

### Land the original monolithic Task 14B.1

Rejected because it conflated logical start, physical-effect admission, reconciliation, deletion interlocks, and first application, and its start/deletion one-winner claim was false before any effect admission.

### Treat `Started` as capture authorization

Rejected because a Room lifecycle transition alone does not durably admit a specific external effect. A future coordinator must obtain the per-file admission introduced by Task 14B.1B before invoking CameraX or filesystem operations.

### Block deletion on every journal row

Rejected because a coherent `EXPECTING_RESERVATION` row records stable intent, not an admitted external effect. It must influence causal time without becoming an in-flight-effect interlock.

### Synthesize journal rows during V3→V4 migration

Rejected because V3 does not contain enough durable per-file history to reconstruct truthful stages or effects. Fabrication would convert absence of evidence into authority.

### Preserve caller-selected first-application outputs

Rejected because caller lists cannot replace journal-owned path, durability, hash, capture-time, and stage authority. First application remains unavailable until Task 14B.1C derives it from final journal rows.

## Verification and evidence

The exact Task 4 candidate v5 patch SHA-256 is `377dc02c781ece2cf78e48f93c727d02ea9d41082a12229ff22803e97a306491`. Specification review returned `PASS`, and engineering/security review returned `APPROVED` on those same bytes.

- JVM suite: 635/635 passed.
- Android-test compilation: passed.
- Integrated Pixel 6 five-class suite: 105 tests, 0 failures, 0 errors, 10 expected skips.
- Migration class: 10/10 passed.
- Earlier Task 3C/3D Pixel 6 suite: 79/79 passed twice, with 10 expected skips.
- The cumulative Task 5 host gate passed JVM tests, lint, and debug, release, and Android-test assembly; V1–V3 and frozen V4 schema bytes remained exact, Room compiler payloads were absent from runtime classpaths and packaged dex, and emitted APK manifests retained no `INTERNET` permission.
- Documentation candidate v1 found caller-list traversal before the unfinished-confirmation guard. Repair v1 was specification-rejected for a receipt-first bypass; repair v2 was specification-rejected because its nullable-timestamp guard did not enforce unfinished lifecycle authority. The final eight-scenario Pixel 6 method covers REGISTERED and CAPTURING with null or malformed non-null timestamps, each with and without a raw receipt. It failed on v2 with 3 reads and passed 1/1 after v3 moved the unavailable guard to explicit lifecycle state. Repair v3, SHA-256 `ed29acd613a890d213e398aaacf4a2d79512662ac0dbf88c411404fcfdb3bd3a`, received exact-byte specification `PASS` and engineering/security `APPROVED`; that approval covers only the two-file repair, not the complete landing candidate. The cumulative host gate then passed again with 635/635 JVM tests and zero lint errors. The current-byte integrated five-class Pixel 6 run executed 106 tests with 0 failures, 0 errors, and 10 expected skips, and left no matching test-database residue.

Task 4 SHA-256 `377dc02c781ece2cf78e48f93c727d02ea9d41082a12229ff22803e97a306491` identifies only the three-file Task 4 Android-test candidate; repair-v3 SHA-256 `ed29acd613a890d213e398aaacf4a2d79512662ac0dbf88c411404fcfdb3bd3a` identifies only its two-file call-order repair. Neither identifies or approves the complete 43-path Task 14B.1A candidate that landed at `57b33c9`. Final review later reproduced a byte-equivalent `BLOB` residual-journal token that ordinary text equality missed and returned `REQUEST_CHANGES`. The uncommitted local query-and-regression repair passed a causal Pixel 6 RED/GREEN cycle and host/build gates but still requires exact-byte specification and engineering/security review; no product-shipped claim is made.

## Scope boundary

Task 14B.1A implemented and proved Room authority only. It did not implement or prove CameraX capture, app-private file reservation/write/sync/rename/delete/quarantine, startup filesystem reconciliation, a guided-session coordinator, MediaStore behavior, physical deletion, UI shutter behavior, speech/audio, or personal-media access. It adds no network or analytics behavior.

One production UI-package file has a compile-adapter-only delta: `StartedSessionBootstrapWorkflow.kt` maps `INVALID_CAPTURE_FILE_OPERATION_AUTHORITY` to the existing fail-closed corrupt-state result. It adds no route, control, reducer/coordinator transition, camera construction, filesystem call, or user-facing capture capability. The final candidate review must adjudicate this explicit exception to the implementation plan's literal no-UI-file wording.

Per-file physical-effect admission and reconciliation are deferred to Task 14B.1B. Journal-owned first-application confirmation is deferred to Task 14B.1C. Attempt settlement/restart reconstruction remains Task 14B.2; the journaled filesystem adapter and bounded startup reconciliation remain Task 14B.3; only Task 15 may compose logical start, per-file admission, CameraX/filesystem effects, and durable settlement.

## Related

- [ADR 0005: Atomic Room V3 Guided-Session Bootstrap](0005-atomic-room-v3-guided-session-bootstrap.md)
- [Task 14B Room V4 authority reset](../../.hermes/plans/2026-09-02-task14b-room-v4-authority-reset.md)
- [Task 14B.1A implementation plan](../../.hermes/plans/2026-09-02-task14b1a-room-v4-journal-foundation.md)