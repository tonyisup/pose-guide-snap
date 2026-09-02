# ADR 0005: Atomic Room V3 Guided-Session Bootstrap

- **Status:** Accepted
- **Date:** 2026-09-02
- **Scope:** Read-only reconstruction by exact session ID

## Context

Room V3 durably owns shoot sessions, capture attempts, confirmation receipts, private-output authority, and export outboxes. Before this decision, application code could write those records but had no single projection for reconstructing an exact guided-session state after a process restart.

Reconstructing through independent DAO calls is unsafe: a confirmation or deletion transaction could otherwise divide the reads into different authority generations. Reconstructing `ShootState.initial` is also wrong because it discards the persisted pose index, attempt counter, receipt history, and blocking attempt.

## Decision

### One exact read boundary

`RoomShootRepository.loadGuidedSessionBootstrap(sessionId)` validates the opaque session ID and delegates to one `GuidedSessionDao.loadGuidedSessionBootstrap` method annotated `@Transaction`.

The DAO reads the exact session first. An absent session returns no constituent rows and maps to `UnknownSession`. A present session loads only its owning shoot, ordered poses, attempts, private outputs, receipts, outbox rows, and export-output rows in deterministic order.

The operation performs no write and does not alter the Room schema. V1–V3 schema artifacts remain byte-identical.

### Complete redacted projection

A successful immutable snapshot contains:

- opaque session and shoot IDs;
- active or completed lifecycle;
- immutable ordered pose IDs;
- current pose index and next attempt number;
- attempt and confirmed-attempt counts;
- immutable applied receipt tokens;
- deletion generation;
- unresolved export-output count;
- either no blocking attempt or one complete redacted blocking-attempt summary.

`Ready` and `Completed` contain no blocking attempt. `ReconciliationRequired` contains the same complete snapshot plus exactly one blocking attempt. Public string rendering is redacted. Names, labels, private paths, MediaStore URIs, claim tokens, Room entities, raw exceptions, images, frames, and landmark payloads do not cross the result boundary.

### Reachable V3 coherence

Capture attempts exactly and uniquely cover `0 until nextAttemptNumber`. V3 attempt states are `REGISTERED`, `CAPTURING`, and `CONFIRMED`.

Each confirmed attempt owns exactly:

- three durable private outputs at ordinals 0–2;
- one contiguous confirmation receipt;
- one pending outbox;
- three coherent export outputs at ordinals 0–2.

An active ready session has one confirmed attempt per completed pose, no receipt at the current pose, no blocking attempt, and a non-exhausted counter equal to the current pose index. A completed session has one confirmed attempt and receipt per pose, remains at the final pose, and ends with a null-target receipt.

A sole `REGISTERED` or `CAPTURING` final attempt, or a final attempt marked reconciliation-required, maps only to `ReconciliationRequired`. It authorizes no camera, file, confirmation, export, deletion, reducer event, or retry.

The valid persisted export vocabulary is:

- nonterminal: `PENDING`, `CLAIMED`, `CREATE_STARTED`, `URI_KNOWN`, `AMBIGUOUS`;
- terminal: `EXPORTED`, `CANCELLED`.

State-specific claim, URI, ambiguity, target, generation, ordinal, and timestamp facts are validated before an output contributes to the unresolved count.

### Transaction behavior

Room 2.8.4 generates the blocking DAO method as `performBlocking(database, false, true)`. Its installed runtime maps `isReadOnly=false` to an SQLite `IMMEDIATE` transaction. The bootstrap therefore owns SQLite's writer reservation while its constituent reads run.

A concurrent confirmation or deletion writer cannot commit between those reads. Instrumentation causally pauses bootstrap before its second SELECT, proves the real production writer remains blocked, releases bootstrap, verifies the complete pre-write snapshot, then verifies writer commit and a complete post-write snapshot. Separate mutation controls show that equivalent nontransactional constituent reads can combine pre-write and post-write facts.

This stronger writer-exclusion behavior supersedes the earlier rejected assumption that a writer should be forced to commit midway through a read transaction.

## Consequences

### Positive

- Persisted session state can be reconstructed without invented defaults.
- Attempt counters, receipts, output cardinality, and export state are checked as one authority graph.
- Confirmation and deletion cannot tear a bootstrap read.
- Invalid or unsupported authority fails closed with typed, redacted results.

### Cost

- The blocking Room read reserves SQLite's writer slot until eight bounded SELECTs and mapping complete.
- The projection validates more state than a screen currently consumes.
- Android-test compilation requires the Room KSP processor for the test-only database used to install a query callback.

## Original Task 14A.1 exclusions

The original Task 14A.1 slice did not add:

- UI or navigation resume;
- process-death routing;
- Room schema changes;
- capture-file reconciliation;
- CameraX capture;
- confirmation/export/deletion transitions;
- MediaStore I/O;
- speech or audio behavior.

Task 14A.3 now implements bounded UI admission and fail-closed process-recreation routing; the remaining capture, storage, and speech items stay separate ownership changes. Gate 2 remains open.

## Amendment: Task 14A.2 active-session discovery (2026-09-02)

Task 14A.2 extends this decision with Room-owned discovery of the at-most-one `ACTIVE` session for an exact shoot ID, consumable by a future UI resume path.

- `RoomShootRepository.findActiveGuidedSession(shootId)` validates the shoot ID against the existing ASCII ownership-identity policy, then reads the shoot row and all of its session rows in one `@Transaction` on `GuidedSessionDao` in deterministic order.
- The pure `ActiveGuidedSessionMapper` returns `Exact(sessionId)` only when the shoot is coherent and `ACTIVE` and exactly one coherent `ACTIVE` session exists. Zero active sessions return `None`; an absent shoot with no sessions returns `UnknownShoot`.
- Everything else fails closed as `Rejected(AUTHORITY_INCONSISTENT)`: a deleting or malformed shoot, orphaned sessions, any session row with an unknown lifecycle or incoherent shape, more than one active session (a trigger-bypass corruption state), or an exhausted attempt counter on the active session. Query or mapping failures become `Rejected(AUTHORITY_UNAVAILABLE)`.
- `Exact` enforces the safe-identity policy in its constructor and renders redacted; no session or shoot identity crosses `toString()`.

Discovery adds no write, schema change, UI, or navigation behavior. See [the Task 14A.2 validation record](../validation/2026-09-02-task14a2-active-session-discovery-pixel6.md).

## Amendment: Task 14A.3 stale-safe UI admission (2026-09-02)

Task 14A.3 composes discovery and bootstrap while preserving Room as the only persistence authority.

- Discovery is editor-scoped to the selected shoot rather than multiplied across each paginated list row. `hasResumableSession` is a non-authorizing display hint.
- Resume performs a fresh `findActiveGuidedSession(shootId)` call. Only `Exact(sessionId)` can mint a redacted in-memory handle. Stale, missing, rejected, exceptional, closed, or superseded results cannot navigate.
- Fresh Start and Resume use the same one-shot capability and constant route. Session identity is absent from route arguments, deep links, saved state, identity-derived keys, UI text, and string rendering.
- One route-scoped ViewModel owns the bootstrap database across configuration changes, denies leases after close begins, and physically closes after in-flight work returns.
- The route re-queries `loadGuidedSessionBootstrap(sessionId)`. Only an exact `Ready` snapshot composes the existing camera destination; every other result renders bounded recovery UI without constructing camera content.
- Process recreation discards the in-memory capability and starts at the shoot list. Reopening the shoot obtains fresh Room authority; the app never invents a resumable identity from navigation state.

See [the Task 14A.3 validation record](../validation/2026-09-02-task14a3-stale-safe-resume-pixel6.md).

## Evidence

Task 14A.1 passed 579 JVM tests and six exact-APK Pixel Room methods. Task 14A.2 raised the suite to 591 and passed eight exact-APK Pixel methods. Task 14A.3 raised the suite to 618 and passed eleven final-APK Pixel methods across two successful bounded invocations covering editor Resume semantics, Ready-only injected camera content, compact-height/large-font recovery scrolling, and exact Room discovery/bootstrap reopen regressions. Every slice passed lint plus debug, release, and Android-test APK assembly with Room V1–V3 unchanged. See the linked Task 14A.1–14A.3 validation records for hashes, failure history, cleanup, and evidence boundaries.
