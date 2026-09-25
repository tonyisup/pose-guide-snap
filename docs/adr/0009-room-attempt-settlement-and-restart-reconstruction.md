# ADR 0009: Room Attempt Settlement and Restart Reconstruction

- **Status:** Accepted and implemented locally; host and bounded Pixel 6 verification complete
- **Date:** 2026-09-04
- **Decision owner:** Product/architecture owner
- **Scope:** Task 14B.2 failure settlement, retry admission, and guided-session reconstruction only

## Context

Task 14B.1B can persist the state and evidence for each of three future capture-file effects. Task 14B.1C can confirm only when all three rows are coherent final authority. An interrupted or failed attempt still needs a durable outcome: the same pose must become retryable only when Room can prove no candidate effect remains, while partial or ambiguous work must survive restart as a blocker for later filesystem reconciliation.

Treating every unfinished attempt as either immediately retryable or permanently invalid would lose one of those guarantees. Retry could overlap unexamined bytes, while permanent rejection would strand clean failures that own no remaining file authority.

## Decision

The existing attempt lifecycle column accepts two additional stored values without changing the Room schema:

- `FAILED_CLEANED` is terminal and retryable. It owns no capture-file journal, private-output, receipt, outbox, or export rows.
- `RECONCILIATION_REQUIRED` is nonterminal and blocking. It retains exactly three coherent journal rows and owns no immutable confirmation children.

Attempts remain contiguous by attempt number. Bootstrap walks them in order against the ordered pose sequence. A failed-cleaned attempt stays on the same derived pose; only a confirmed attempt advances. At most one blocking attempt may exist, and it must be the final attempt for the active session's current pose and deletion generation. The public snapshot reports `failedAttemptCount` and enforces:

`attemptCount == confirmedAttemptCount + failedAttemptCount + blockingAttemptCount`.

`settleCaptureAttemptFailure(sessionId, token, settledAtEpochMillis)` derives its decision entirely from Room in one write transaction:

- Exactly three untouched `EXPECTING_RESERVATION` rows prove that no file effect was admitted.
- Exactly three clean `CLEANED_DURABLE` rows prove that admitted work was durably removed.
- Either clean shape deletes exactly those three journal rows, changes the attempt to `FAILED_CLEANED`, and advances the session clock atomically.
- Exactly three clean `FINAL_DURABLE` rows return `ReadyToConfirm` without mutation.
- Any other coherent capturing journal remains in place while the attempt changes to `RECONCILIATION_REQUIRED` and the session clock advances atomically.

Replay of either stored settlement state validates the complete residual authority before returning. A malformed storage class or value, wrong ownership, missing or extra row, immutable child on an unfinished attempt, backward clock, stale session/deletion generation, or compare-and-set loss rejects without a partial mutation.

Registration uses a narrow raw-storage attempt-admission classifier. A `REGISTERED`, `CAPTURING`, or `RECONCILIATION_REQUIRED` attempt blocks a new attempt; `FAILED_CLEANED` permits the next contiguous token for the same pose after the settlement clock. The ordinary per-file transition API cannot mutate an aggregate reconciliation state. Task 14B.3 adds recovery-only exact filesystem observations and a cleanup-only Room lane, as recorded in [ADR 0010](0010-journaled-private-capture-files-and-restart-recovery.md).

## Consequences

- A clean failure can be retried on the same pose after one durable Room settlement.
- Ambiguous work remains explicit and blocks new capture instead of being silently discarded.
- Restart reconstruction preserves failed history without treating a failed attempt as pose progress.
- Settlement, journal consumption or retention, attempt lifecycle, and the session causal clock share one rollback boundary.
- Duplicate settlement across two Room owners produces one applied writer and one validated replay.
- Deletion recognizes failed-cleaned history and still fails closed around unresolved reconciliation authority.
- V3→V4 migrated unfinished attempts still own no fabricated journal. Task 14B.2 does not infer a clean physical outcome from that absence; Task 14B.3 resolves only the case where all exact deterministic paths are absent.
- The change performs no CameraX call, filesystem mutation or scan, MediaStore operation, UI action, network request, or analytics work.

## Verification

- The full JVM suite passes 655/655 with zero failures, errors, or skips. Contract tests cover stored lifecycle shape, redaction, failed-count invariants, same-pose retry, and mixed failed/confirmed/blocking reconstruction.
- Lint passes with zero errors and 13 warnings. Debug, unsigned release, and Android-test APK assembly pass.
- Room schema artifacts and emitted permissions are unchanged; the app APKs request camera plus AndroidX's app-signature receiver permission, the test APK additionally requests `REORDER_TASKS`, and none requests Internet permission.
- Six focused Android methods cover clean settlement and retry, blocking reconciliation across reopen, final-ready no-mutation classification, rollback after journal deletion, deletion authority with failed history, and two-owner concurrent settlement.
- The final APK pair passed all 6/6 methods on the Pixel 6 in 1.376 seconds, with installed hashes matching the local artifacts and no generated database residue afterward. See the [validation record](../validation/2026-09-04-task14b2-attempt-settlement-pixel6.md).

## Related

- [ADR 0006: Room V4 Capture-File Journal Foundation](0006-room-v4-capture-file-journal-foundation.md)
- [ADR 0007: Room-Owned Capture-File Admission](0007-room-owned-capture-file-admission.md)
- [ADR 0008: Journal-Derived Atomic Confirmation](0008-journal-derived-atomic-confirmation.md)
- [ADR 0010: Journaled Private Capture Files and Restart Recovery](0010-journaled-private-capture-files-and-restart-recovery.md)
- [Task 14B.2 implementation plan](../../.hermes/plans/2026-09-04-task14b2-attempt-settlement.md)
