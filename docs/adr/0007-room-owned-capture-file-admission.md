# ADR 0007: Room-Owned Capture-File Admission

- **Status:** Accepted and implemented locally; host and bounded Pixel 6 verification complete
- **Date:** 2026-09-04
- **Decision owner:** Product/architecture owner
- **Scope:** Task 14B.1B per-file Room transitions, reconciliation, causal authority, and deletion interlock only

## Context

Room V4 gives every new capture attempt three deterministic file-operation identities, but Task 14B.1A leaves them at stable intent. A future coordinator needs an exact durable point where one process gains permission to perform one physical reservation, write, rename, cleanup, or quarantine action. Logical attempt state cannot provide that permission because deletion may commit after logical start while no external effect is in flight.

Room and the filesystem cannot share one transaction. The admission boundary must therefore distinguish a fresh effect grant from replay, reject stale or malformed authority, serialize with deletion across database owners, and retain enough evidence for later restart reconciliation.

## Decision

Add `RoomCaptureFileJournal` as the only production service that can change `capture_file_operations`. It performs no physical I/O. Every request names one opaque command token and burst ordinal, supplies the exact expected stage and row clock, and proposes one target state with a strictly later transition clock.

The transition graph is closed:

- `EXPECTING_RESERVATION -> WRITING_TEMP`
- `WRITING_TEMP -> TEMP_SYNCED`
- `TEMP_SYNCED -> FINAL_RENAME_PENDING_SYNC`
- `FINAL_RENAME_PENDING_SYNC -> FINAL_DURABLE`
- `TEMP_SYNCED`, `FINAL_RENAME_PENDING_SYNC`, or `FINAL_DURABLE -> QUARANTINE_REQUIRED`
- `QUARANTINE_REQUIRED -> QUARANTINE_PENDING_SYNC -> QUARANTINE_DURABLE`
- every unfinished state may enter `CLEANUP_REQUIRED -> CLEANUP_PENDING_SYNC -> CLEANED_DURABLE`

Stages that assert durable bytes require an all-or-none positive byte count, lowercase 64-character SHA-256, and capture timestamp. Failure and reconciliation markers are also all-or-none. Normal progress clears a previous failure marker. A reconciliation request changes only the failure marker and row clock; resolving it clears both.

Before typed entity mapping, scalar queries validate SQLite storage classes, values, deterministic paths, and byte-equivalent token/ordinal identity candidates. Mutation then validates the attempt, session, shoot, current pose, deletion generation, lifecycle, and cross-authority clocks. The final update is an exact compare-and-set over the source row and owning graph. A malformed or ambiguous durable identity cannot be normalized into authority.

`Applied` is the only result that grants a fresh future physical effect. An exact target replay returns `Idempotent` and grants nothing. Stale, contradictory, illegal, invalid-evidence, invalid-owner, and deletion-blocked requests do not mutate the journal.

## Deletion interlock

Exactly four stages represent an admitted but unsettled effect:

- `WRITING_TEMP`
- `FINAL_RENAME_PENDING_SYNC`
- `CLEANUP_PENDING_SYNC`
- `QUARANTINE_PENDING_SYNC`

Deletion and a new transition into one of these stages serialize through Room's write transaction. Deletion first causes the later admission to return `BlockedByDeletion`. Admission first causes deletion to return `CAPTURE_FILE_EFFECT_IN_FLIGHT`. A settlement into a stable stage may commit first and then allow deletion. Stable intent, required-action, and durable-result stages contribute to causal validation but do not themselves block deletion.

Deletion validates raw journal storage before typed mapping and binds every row to exact attempt ownership, cardinality, ordinals, deterministic paths, lifecycle state, creation time, progress clocks, and evidence. `REGISTERED` requires three pristine initial rows. `CAPTURING` requires three rows whose progressed clocks cannot predate capture authorization. `CONFIRMED` requires no residual journal rows. Journal clocks participate in the complete deletion maximum, and postconditions require unchanged journal bytes.

## Consequences

- A future coordinator has one durable, replay-safe point for each physical action.
- Logical `Started` remains non-authorizing.
- Deletion has an exact one-winner contract with new effect admission and can proceed after durable settlement.
- SQLite type coercion and byte-equivalent key aliases fail closed instead of creating duplicate authority.
- The implementation adds no CameraX call, file mutation, MediaStore work, UI control, or network behavior.
- Task 14B.1C later derived confirmation from exactly three coherent `FINAL_DURABLE` rows and consumed that transient journal authority atomically; see [ADR 0008](0008-journal-derived-atomic-confirmation.md).

## Verification

- Nine pure JVM tests cover every legal edge, illegal transitions, evidence, clocks, stale requests, reconciliation, redaction, and the exact admitted-stage set.
- The complete host suite passes 652/652, and Android-test compilation passes.
- Android tests cover legal and idempotent mutation, malformed/coercible storage, byte-equivalent token and ordinal aliases, owner and causal-clock rejection, two-owner compare-and-set contention, reopen persistence, deletion graph integrity, and equality at every deletion-authority clock family.
- The deletion/admission test uses two Room owners and a query callback to pause a real first transaction after its decisive write. It forces deletion-first, admission-first, and settlement-first orderings.
- The bounded six-method Pixel 6 checkpoint passed on the final app/test APK pair. Installed hashes matched the reviewed local artifacts, all six exact methods passed in one invocation, and cleanup left no generated database residue. See the [validation record](../validation/2026-09-04-task14b1b-room-file-admission-pixel6.md).

## Related

- [ADR 0006: Room V4 Capture-File Journal Foundation](0006-room-v4-capture-file-journal-foundation.md)
- [ADR 0008: Journal-Derived Atomic Confirmation](0008-journal-derived-atomic-confirmation.md)
- [Task 14B Room V4 authority reset](../../.hermes/plans/2026-09-02-task14b-room-v4-authority-reset.md)
