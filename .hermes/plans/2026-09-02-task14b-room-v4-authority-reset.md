# Task 14B Room V4 Authority Reset

**Status:** Approved on 2026-09-02 after four rejected exact-plan revisions. Task 14B.1A checkpoints are implemented and verified in the working tree; its complete landing candidate still awaits final same-digest review. Tasks 14B.1B and 14B.1C remain deferred.

**Baseline:** `main` at `6997861487d3d7dd31c21b9a947d1ccde3b2f418`.

## Why the monolith was rejected

The rejected 660-line Task 14B.1 plan conflated two different events:

1. the Room attempt transition `REGISTERED -> CAPTURING`; and
2. durable admission of a specific external file effect.

It then claimed capture start and deletion had exactly one winner. That was false. If start committed first while all three file rows remained `EXPECTING_RESERVATION`, deletion could also commit because no external effect was admitted. Both successes are safe only if `Started` is explicitly non-authorizing for camera and filesystem work.

Four exact-plan review rounds also showed that the original all-at-once packet was too broad to review efficiently. It combined schema, registration, bootstrap, capture start, per-file transitions, deletion interlocks, confirmation replacement, migration, and documentation in one candidate.

The exact rejected source is preserved at:

- Repository history artifact: `.hermes/plans/2026-09-02-task14b1-monolith-rejected.md`
- Immutable cache copy: `~/.hermes/cache/pose-guide-snap-task14b-plan-rejections/task14b1-monolith-5ba07c22.md`
- Original SHA-256: `5ba07c22c14b3f777f26faabc93cb5b0c11a464986f1da483c555f7e414e5974`

## Correct authority model

### Logical attempt start

The current `authorizeCaptureStart(...)` name is misleading. The operation only decides whether the attempt may move from `REGISTERED` to `CAPTURING` against coherent Room authority. It does not admit CameraX or filesystem work.

The replacement API will be named `markCaptureAttemptStarted(...)`. Its successful result means only:

- the owning shoot/session/attempt was coherent and active;
- exactly three journal rows existed and were structurally valid;
- the attempt lifecycle CAS committed;
- no physical effect was authorized or performed.

Safe serialization:

- deletion first: logical start rejects;
- logical start first: start may succeed and deletion may then succeed while every row remains at stable `EXPECTING_RESERVATION`;
- after deletion, every future physical-effect admission rejects.

There is intentionally no one-winner claim between logical start and deletion.

### Physical file-effect admission

The first durable authority for a physical effect is a per-ordinal journal CAS into one of these admitted stages:

- `WRITING_TEMP`
- `FINAL_RENAME_PENDING_SYNC`
- `CLEANUP_PENDING_SYNC`
- `QUARANTINE_PENDING_SYNC`

Only this transition participates in the deletion one-winner contract:

- deletion commits first -> new effect admission rejects without mutation;
- admission commits first -> deletion rejects until exact settlement reaches a stable stage;
- settlement commits first -> deletion may commit and later admissions reject.

A future coordinator must receive a successful per-file admission before invoking CameraX or a filesystem adapter. `markCaptureAttemptStarted(...)` alone is never sufficient.

## Source reachability at reset

Current production source has no caller of:

- `RoomShootRepository.registerCaptureAttempt(...)`
- `RoomShootRepository.authorizeCaptureStart(...)`
- `RoomShootRepository.confirmAndAdvance(...)`

outside the repository declaration itself. CameraX construction is separate and not composed with this persistence path. That allows the authority substrate to land fail-closed in independently reviewed stages without enabling a half-built user feature.

## Replacement stages

### Task 14B.1A — Room V4 journal foundation

Land one additive V4 authority foundation:

- full `capture_file_operations` schema, restrictive FK, indexes, and callback-installed shape/storage triggers;
- deterministic hashed paths with one shared well-formed UTF-16 validator before UTF-8 encoding;
- V3->V4 preservation with an empty new journal;
- atomic registration of one attempt plus exactly three `EXPECTING_RESERVATION` rows plus one counter CAS;
- ninth guided-session bootstrap read and lifecycle/cardinality validation;
- rename `authorizeCaptureStart(...)` to non-authorizing `markCaptureAttemptStarted(...)` and validate the initial journal before the lifecycle CAS;
- explicitly test the safe two-success serialization: logical start succeeds, then deletion succeeds because no file effect is admitted;
- include journal clocks in deletion's causal maximum, while stable `EXPECTING_RESERVATION` rows do not interlock deletion;
- fail closed at confirmation for every unfinished attempt until journal-derived confirmation lands; legacy already-confirmed replay may remain readable only when immutable authority is coherent and no transient journal exists;
- no production per-file progression API;
- no physical-effect admission, camera, filesystem, coordinator, MediaStore, UI, or device run.

This stage is independently landable because every new attempt has journal authority before becoming reachable, but no new attempt can progress to physical work or confirmation.

Detailed plan: `.hermes/plans/2026-09-02-task14b1a-room-v4-journal-foundation.md`.

### Task 14B.1B — Per-file admission and deletion interlock

Build on landed 14B.1A:

- add typed per-ordinal Room CAS transitions and reconciliation requests;
- define the exact admitted/stable stage matrix;
- enforce strictly increasing row clocks and nondecreasing cross-authority clocks;
- add the deletion interlock only for admitted-but-unsettled stages;
- prove deletion-first, admission-first, and settlement-first orderings with paused real transactions;
- keep confirmation fail-closed;
- do not compose CameraX or filesystem behavior.

This is the only stage that claims exactly one winner between deletion and a new file-effect admission.

### Task 14B.1C — Journal-derived confirmation

Build on landed 14B.1B:

- remove caller-selected private outputs from `confirmAndAdvance(...)`;
- derive exactly three immutable outputs from coherent ordered `FINAL_DURABLE` rows;
- atomically create confirmed outputs, receipt, outbox, and export rows;
- consume exactly three transient journal rows in the same transaction;
- reject residual transient authority on replay;
- preserve rollback, idempotency, deletion, clock, and concurrency guarantees;
- still do not compose camera/filesystem/coordinator behavior.

## Downstream sequence

1. Task 14B.2: attempt settlement and restart reconstruction.
2. Task 14B.3: journaled app-private filesystem adapter and bounded startup reconciliation.
3. Task 15: guided-session coordinator. Only this stage may order logical start -> per-file admission -> CameraX/filesystem effect -> durable settlement.
4. MediaStore export execution, deletion completion, and TTS remain separate.

## Review and landing policy

Each stage is a separate independently landable candidate:

- behavioral RED before production behavior;
- host and build gates before any device request;
- fresh explicit authorization for a bounded Pixel 6 runtime method set;
- exact staged digest;
- specification `PASS` and engineering/security `APPROVED` on the same bytes;
- any byte change invalidates both approvals;
- no later stage is folded into an earlier candidate merely to make tests convenient.

## Recorded decision

The authority reset was approved before Task 14B.1A implementation began. That approval authorized host-side implementation and build verification only. Every later Pixel/device run was separately bounded and authorized; the decision did not authorize camera use, filesystem publication, or any other external side effect.
