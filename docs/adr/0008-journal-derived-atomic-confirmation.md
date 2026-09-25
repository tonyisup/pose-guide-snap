# ADR 0008: Journal-Derived Atomic Confirmation

- **Status:** Accepted and implemented locally; host and bounded Pixel 6 verification complete
- **Date:** 2026-09-04
- **Decision owner:** Product/architecture owner
- **Scope:** Task 14B.1C first-application confirmation and replay authority only

## Context

Task 14B.1B can durably settle each capture-file row at `FINAL_DURABLE`, including its deterministic final path, positive byte count, lowercase SHA-256, capture timestamp, and causal row clock. The older confirmation API still accepted a private-output list from its caller. That list could describe files, but it could not prove that the Room-owned file protocol had durably produced them.

Confirmation also crosses several authority families: the attempt and session advance, three immutable private-output rows, one confirmation receipt, one export outbox, three export-output rows, and the transient capture-file journal. A partial commit or replay from residual transient authority would make restart and deletion ambiguous.

## Decision

The public `confirmAndAdvance` API accepts only the reducer command, three export destinations, and the confirmation clock. It cannot receive private output paths, byte counts, hashes, or capture timestamps.

First application requires an exact `CAPTURING` attempt and active owning session/shoot graph at the captured deletion generation. It then requires exactly three byte-correlated journal rows for ordinals 0–2. Every row must have its deterministic token-derived paths, `FINAL_DURABLE` stage, positive integer byte count, canonical lowercase SHA-256, integer capture timestamp, no failure or reconciliation marker, exact attempt creation time, and causal clocks bounded by the confirmation time. Raw SQLite storage is validated before typed values confer authority.

Room derives the immutable `private_capture_outputs` rows directly from that journal evidence. In the same write transaction it confirms the attempt, advances or completes the session, records the unique receipt, creates one export outbox and three export-output rows, revalidates the final journal, and deletes exactly three rows. Cardinality or authority drift at any point aborts the transaction and restores every prior row, including the journal.

Receipt-backed replay reads only persisted immutable confirmation authority. It requires zero residual byte-equivalent journal rows and compares caller-provided export destinations with the committed export rows. A coherent replay returns `AlreadyApplied` and performs no mutation.

## Concurrency and clocks

Confirmation must not precede the attempt, session, any final journal update, or any captured-at clock. Equality is accepted. The attempt and session compare-and-set queries repeat their own timestamp and deletion-generation predicates at the write point.

Room write transactions serialize confirmation against deletion and duplicate confirmation across independent WAL connections. Deletion first makes confirmation return `BlockedByDeletion`. Confirmation first leaves a coherent confirmed graph for deletion. Two simultaneous confirmations produce one `Applied` result and one immutable `AlreadyApplied` replay.

Packet 2B continues to read all nine authority families in one immediate transaction. A confirmation writer waits while that snapshot is open; a nontransactional sequence remains capable of observing a mixed pre-session/post-receipt view and is retained only as a negative control.

## Consequences

- Caller data cannot confer private-file identity or durability.
- A confirmed attempt owns immutable private-output evidence and no transient capture-file rows.
- Confirmation, advancement, export intent, and journal consumption share one rollback boundary.
- Reopen and duplicate replay do not need the consumed journal.
- The change performs no CameraX call, filesystem mutation, MediaStore operation, UI action, network request, or analytics work.
- Migrated unfinished V3 attempts still have no fabricated V4 journal and therefore cannot use first application.

## Verification

- The full JVM suite passes 652/652 with zero failures, errors, or skips. A source-contract test pins the three-parameter public API and absence of caller private-output authority.
- Production and Android-test Kotlin compilation pass.
- Five focused confirmation Android methods cover final-row derivation/consumption, malformed or incomplete authority, backward/equal clocks, reopen replay, injected rollback after deletion, post-write journal drift, deletion contention, and duplicate confirmation.
- Three focused Packet 2B Android methods cover immediate snapshot writer exclusion, the nontransactional mixed-state control, and repeated read-only evidence across every V4 authority table and the schema.
- The first five-method Pixel candidate exposed a safe-but-wrong backward-clock rejection reason. The corrected implementation retained raw structural validation while returning `INVALID_TIMESTAMP`, passed the full host gate again, and passed all eight exact methods across final 5/5 and 3/3 Pixel 6 invocations. See the [validation record](../validation/2026-09-04-task14b1c-journal-confirmation-pixel6.md).

## Related

- [ADR 0006: Room V4 Capture-File Journal Foundation](0006-room-v4-capture-file-journal-foundation.md)
- [ADR 0007: Room-Owned Capture-File Admission](0007-room-owned-capture-file-admission.md)
- [ADR 0009: Room Attempt Settlement and Restart Reconstruction](0009-room-attempt-settlement-and-restart-reconstruction.md)
- [Task 14B Room V4 authority reset](../../.hermes/plans/2026-09-02-task14b-room-v4-authority-reset.md)
