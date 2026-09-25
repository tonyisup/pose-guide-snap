# ADR 0010: Journaled Private Capture Files and Restart Recovery

- **Status:** Accepted and implemented locally; corrected host and bounded Pixel 6 verification complete
- **Date:** 2026-09-04
- **Decision owner:** Product/architecture owner
- **Scope:** Task 14B.3 app-private capture-file effects and startup cleanup only

## Context

Room can admit one exact capture-file effect, confirm three durable results, and classify an interrupted attempt as clean or reconciliation-required. Until this change, no component executed those admitted file effects or used the retained journal to recover after process death. A migrated V3 attempt can also be unfinished without V4 journal rows, so missing rows alone cannot prove that no deterministic capture file exists.

Recovery must avoid two unsafe shortcuts: treating a filename as proof of ownership, and scanning a private directory for files that merely resemble capture output. It also must not let the general capture API mutate an attempt after Room has moved that attempt into aggregate reconciliation.

## Decision

`JournaledPrivateCaptureStore` owns two closed namespaces below an injected app-private `noBackupFilesDir` root: `capture-candidates` and `capture-quarantine`. It accepts only the final, temporary, and quarantine paths re-derived from one opaque `(commandToken, ordinal)` identity.

A fresh write claim creates an exclusive empty final reservation and a same-directory temporary file. The returned in-memory lease binds both files to stable filesystem identities. Temp settlement requires positive bytes, file sync, SHA-256 calculation, and identity revalidation. Final publication revalidates the persisted evidence and lease, atomically replaces only the owned empty reservation, syncs the candidate directory, and verifies the published bytes. Android production and instrumentation use the existing libc-backed file adapter for exclusive create, no-follow file sync, inode/device ownership checks, atomic rename, exact deletion, and directory sync.

Cleanup starts only from a persisted `CLEANUP_PENDING_SYNC` row. It observes only the three exact paths, rejects symbolic links and nonregular entries, verifies positive final or quarantine bytes against persisted evidence, removes exact regular files, syncs each affected directory, and checks exact absence. Unrelated siblings are never enumerated or removed. An interrupted positive temporary file may be deleted without evidence because the temporary path is deterministic, private, and cannot represent a confirmed output.

Aggregate recovery uses a separate `RoomCaptureFileRecoveryJournal`. It exposes only snapshots and the closed cleanup chain while the owning attempt is `RECONCILIATION_REQUIRED`; it cannot admit a write, publication, or quarantine effect. Once all three rows reach `CLEANED_DURABLE`, the existing settlement transaction consumes them and changes the attempt to `FAILED_CLEANED`.

`CaptureAttemptStartupReconciler` handles one exact session:

1. Ordinary `REGISTERED` or `CAPTURING` authority first uses Task 14B.2 settlement.
2. Three final durable rows defer to confirmation without file mutation.
3. Aggregate reconciliation drives each non-quarantined row through cleanup, then settles the attempt.
4. A durable quarantine remains blocked for explicit repair.
5. A journal-free unfinished attempt is settled only when all nine deterministic final/temp/quarantine paths for ordinals 0–2 are absent. Any present, unsafe, or unavailable path preserves the blocker.

All recovery clocks are injected and advance the greatest relevant persisted timestamp. Storage corruption, stale ownership, deletion, compare-and-set loss, or filesystem ambiguity returns an outstanding result without claiming success.

## Consequences

- Restart can safely release an interrupted attempt after its exact owned files are durably absent.
- A V3 unfinished attempt with no V4 journal can be healed when bounded exact observation proves absence, without fabricating journal history.
- Unexpected bytes, symbolic links, nonregular entries, and durable quarantine remain visible as blocked authority.
- Capture-file mutations stay inside app-private, backup-excluded storage and use no personal-media or MediaStore permission.
- The adapter does not invoke CameraX, expose a shutter, confirm an attempt, advance a pose, export media, or implement user-facing repair.
- Task 15 must compose capture admission, CameraX writes, journal settlement, confirmation, and this startup recovery before the manual workflow is complete.

## Verification

- The corrected full host suite passes 665/665 with zero failures, errors, or skips. Generated-file tests cover no-clobber publication, exact cleanup, retried cleanup, evidence mismatch retention, unrelated sibling preservation, and bounded journal-free observation. Coordinator tests cover aggregate cleanup, final-ready deferral, journal-free absence, and ambiguity retention. A causal mapper regression permits post-aggregate clocks only for cleanup stages.
- Lint passes with zero errors and 13 warnings. Debug, unsigned release, and Android-test APK assembly pass.
- Three focused Android methods use the libc-backed production file adapter and only UUID-named Room databases plus one UUID-named directory below `noBackupFilesDir`. They cover journaled cleanup across database reopen, journal-free all-absent settlement, and present-file retention. The corrected exact 3/3 Pixel 6 checkpoint passes in 0.785 seconds; see the [validation record](../validation/2026-09-05-task14b3-journaled-recovery-pixel6.md).
- Room schema artifacts, dependency versions, and application permissions are unchanged.

## Related

- [ADR 0006: Room V4 Capture-File Journal Foundation](0006-room-v4-capture-file-journal-foundation.md)
- [ADR 0007: Room-Owned Capture-File Admission](0007-room-owned-capture-file-admission.md)
- [ADR 0008: Journal-Derived Atomic Confirmation](0008-journal-derived-atomic-confirmation.md)
- [ADR 0009: Room Attempt Settlement and Restart Reconstruction](0009-room-attempt-settlement-and-restart-reconstruction.md)
- [Task 14B.3 implementation plan](../../.hermes/plans/2026-09-04-task14b3-journaled-capture-filesystem.md)
