# ADR 0004: Room V3 Shoot-Preparation Authority

- **Status:** Accepted and implemented through `5bc15c33c5b6c36196f99a2bd8259fe2b00ffeb3`; bounded Pixel 6 gate complete
- **Date:** 2026-08-31
- **Decision owner:** Product/architecture owner
- **Scope:** Shoot preparation, reference-import placement, playlist ordering, and durable session start

## Context

Task 12 was originally written as a Compose-only slice. The committed repository cannot execute that plan safely:

- production code cannot create or list shoots;
- Task 11B imports require an already-existing active shoot;
- Room has no ordered editor projection or reorder transaction;
- the UI would have to invent import tokens, pose IDs, indices, and ledger timestamps;
- `App()` requests camera permission and opens the live camera before a shoot exists;
- `reference_import_intents.pose_index` is unique per shoot, so a durable rejected or quarantined attempt permanently occupies the editor position even though no active pose exists;
- Room does not enforce the approved maximum of 20 validated references;
- no durable, idempotent operation creates a shoot session from an eligible playlist.

These are ownership gaps, not missing UI widgets. Putting compensating rules in Compose would duplicate policy, leak persistence details, and make retry/restart behavior non-authoritative.

## Decision

Task 12 is split into a Room-owned preparation slice followed by the UI slice. Room V3 becomes the authority for shoot creation, preparation projections, import admission, active playlist order, reorder, start eligibility, and durable session creation.

### 1. Import attempts do not own playlist positions

Room V3 removes `pose_index` from `reference_import_intents` and removes the unique `(shoot_id, pose_index)` index.

A reference-import attempt owns:

- import token;
- shoot ID;
- proposed pose ID;
- deterministic app-private asset and file-ledger identity;
- lifecycle and durability evidence;
- validation/provenance evidence.

It does **not** own an active playlist position. Rejected or quarantined attempts remain durable audit/reconciliation records without blocking a later import from becoming active.

On successful commit, Room appends the validated pose at the next contiguous active `shoot_poses.pose_index`. Active playlist order exists only in `shoot_poses`. The committed import remains linked by `(shoot_id, pose_id)`; it no longer duplicates mutable order.

Retries after a terminal rejection use a new opaque import token and pose ID. Existing rejected/quarantined evidence is not rewritten into a new attempt.

### 2. Room enforces the playlist bound before provider access

Import admission rejects a new attempt when the shoot already has 20 active validated poses. This check happens in the same transaction that reserves the logical intent and initial file-operation ledger, before the picker source is opened.

Concurrent reservations cannot both exceed the bound. A successful import is appended transactionally and the commit postcondition requires a contiguous active order.

### 3. A preparation repository owns application-facing policy

Compose receives redacted immutable projections and typed results from one narrow preparation boundary. It never receives Room entities or DAOs.

The boundary owns:

- create shoot;
- observe shoot summaries;
- observe one editor projection;
- allocate opaque import identity and injected ledger timestamps;
- submit the transient picker result to Task 11B;
- reorder the complete validated pose-ID set;
- evaluate start eligibility;
- durably and idempotently start a session.

IDs and wall-clock values are injected. Provider URIs remain callback-local and are never stored in Room, saved state, logs, filenames, or ViewModel state.

### 4. Reorder updates active order only

Reorder accepts the complete ordered set of committed validated pose IDs for one active shoot.

Room requires:

- exact set equality;
- 3–20 active validated poses;
- no active session;
- no nonterminal or reconciliation-required import work;
- no deletion barrier;
- contiguous final positions `0..n-1`.

The transaction uses collision-safe temporary indices, updates `shoot_poses` only, and verifies the postcondition before commit. Historical import intents retain immutable attempt identity and do not mirror order.

### 5. Start is durable and idempotent

Task 12 start is not a UI navigation event. Room accepts an injected session/start ID and timestamp, checks eligibility, and atomically creates one active `ShootSessionEntity` at pose index `0`.

Exact replay of the same start/session ID returns `AlreadyStarted` only when every persisted field and the owning shoot agree. A different start request while the shoot already has an active session is rejected.

The existing callback-installed authority-trigger mechanism will enforce at most one active session per shoot for direct SQL as well as repository calls. Multiple completed historical sessions remain allowed.

### 6. Camera permission follows durable start

The app root becomes shoot list → editor → durable start. The camera permission launcher and `CameraXController` route are unreachable until Room returns a successful or exact-idempotent start result.

After start, Task 12 enters the existing Task 10 camera diagnostic. Task 12 does not add TTS, automatic capture, capture-to-Room coordination, MediaStore I/O, or physical deletion.

## Migration

V2→V3 recreates `reference_import_intents` without `pose_index`, copies every existing row by exact remaining columns, recreates the unique `(shoot_id, pose_id)` and lifecycle indexes, and preserves the restrictive file-operation foreign key by rebuilding dependent tables in a foreign-key-safe transaction if SQLite requires it.

The committed V1 and V2 schema artifacts remain immutable. V3 is exported as a new artifact. Migration acceptance must prove:

- V1→V2→V3 and V2→V3 preserve shoots, active poses, import intents, file ledgers, and capture authority;
- rejected/quarantined attempts no longer occupy an active order position;
- committed imports remain linked to exactly one active pose by `(shoot_id, pose_id)`;
- no destructive migration fallback exists.

## Consequences

- Task 12 is no longer a UI-only task.
- Room V3 is required before the playlist editor.
- Reorder becomes simpler because historical import records do not duplicate mutable order.
- Durable rejected/quarantined evidence remains available without blocking replacement.
- The application layer, not Compose, constructs import identity and timelines.
- The final authorized Task 12 Pixel 6 gate passed synthetic-state editor semantics and callback tests. A separate pre-final manual run observed the production preparation flow and picker recreation; it is supporting evidence, not final-commit same-artifact proof. Future private-device runs remain separately permission-gated, and neither evidence class covers Task 13 speech or Task 14 capture/export behavior.

## Rejected alternatives

### Keep V2 and release/reuse rejected positions

Rejected because it would require mutating or deleting durable rejected/quarantined authority solely to free a UI position. It couples historical file-recovery evidence to mutable playlist layout and makes replacement correctness harder to prove.

### Let Compose skip occupied indices

Rejected because gaps and index selection would become UI policy, start eligibility would be ambiguous, and Room would not own the 3–20 contiguous playlist invariant.

### Treat Start as navigation only

Rejected because process death or duplicate taps could create an unowned camera route with no durable session identity. Session creation and its exact replay semantics belong in Room.
