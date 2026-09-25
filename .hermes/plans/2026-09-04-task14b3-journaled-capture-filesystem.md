# Task 14B.3 — Journaled capture filesystem and bounded restart reconciliation

**Status:** Implemented and boundedly verified on the host and Pixel 6. The corrected full host gate passes 665/665 tests and the exact device checkpoint passes 3/3. See [ADR 0010](../../docs/adr/0010-journaled-private-capture-files-and-restart-recovery.md) and the [validation record](../../docs/validation/2026-09-05-task14b3-journaled-recovery-pixel6.md).

## Goal

Connect the Room capture-file journal to an app-private filesystem adapter whose operations are exact, staged, restart-observable, and fail closed. Recover an interrupted current attempt from only its persisted token/paths and the three closed private namespaces before Task 15 is allowed to compose CameraX.

## Boundary

This packet adds an app-private file store, recovery-only Room cleanup transitions, one-session startup reconciliation, and generated-file tests. It does not expose a shutter, invoke CameraX, advance a confirmed pose, export to MediaStore, delete user-visible media, add permissions, or compose the guided-session coordinator.

## Filesystem contract

- The root is `noBackupFilesDir`; only deterministic `capture-candidates/<digest>-<ordinal>.jpg`, `capture-candidates/.<digest>-<ordinal>.jpg.pending`, and `capture-quarantine/<digest>-<ordinal>.quarantined` paths are accepted.
- Every path is re-derived from the opaque identity, normalized under the canonical root, and rejected if any namespace component escapes or is a symbolic link.
- One process-wide mutation guard covers every supported capture-candidate create, verify, rename, cleanup, and quarantine operation.
- A fresh write claim exclusively creates an empty final reservation and empty temp file and returns an in-memory lease carrying their stable file identities. Partial creation is ambiguous and is never silently erased.
- Temp settlement verifies the lease, positive size, fsync, and SHA-256 before returning evidence. Final publication re-verifies the exact lease/evidence, atomically replaces only the owned empty reservation, syncs the candidate directory, and verifies the final bytes.
- Recovery observes or mutates only the three exact paths for a persisted identity. Cleanup rejects symbolic links/nonregular files, deletes exact regular files, syncs affected directories, and preserves unrelated siblings.

## Room recovery contract

- Ordinary capture-file transitions remain available only to a normal `CAPTURING` attempt.
- A separate recovery-only journal permits only the closed cleanup chain while the attempt is `RECONCILIATION_REQUIRED`; it cannot admit writes, publication, or quarantine.
- Once all three rows reach `CLEANED_DURABLE`, failure settlement may atomically consume them and change the aggregate attempt from `RECONCILIATION_REQUIRED` to `FAILED_CLEANED`.
- A journal-free unfinished attempt may be healed only after a bounded exact-path observation proves final, temp, and quarantine absent for all three deterministic identities. Any present, unreadable, symbolic, or nonregular path remains blocked.
- All timestamps are injected and strictly advance the relevant persisted clock. Malformed Room storage, stale ownership, deletion, races, or filesystem ambiguity leave durable authority blocking.

## Startup reconciliation

For one exact active session:

1. Bootstrap and classify the final blocking attempt.
2. Let ordinary Task 14B.2 settlement immediately close untouched/fully cleaned rows or defer three final rows to confirmation.
3. For aggregate reconciliation, drive each non-quarantined row through `CLEANUP_REQUIRED → CLEANUP_PENDING_SYNC`, delete only its exact files, then record `CLEANED_DURABLE`.
4. After all three rows are clean, atomically settle the attempt to `FAILED_CLEANED`.
5. For journal-free unfinished authority, resolve only the all-absent exact-path case; retain every other case for later explicit repair.

## Verification

- Pure generated-file tests cover no-clobber lease publication, evidence verification, exact cleanup and retry, evidence-mismatch retention, unrelated-file preservation, and bounded restart observation.
- Pure coordinator tests cover cleanup recovery, final-ready deferral, journal-free all-absent healing, and ambiguity retention.
- Room instrumentation covers flagged aggregate reconciliation cleanup across close/reopen, journal-free all-absent recovery, and exact-file retention when legacy ownership is unavailable.
- The full host gate must remain green; Room schemas, dependencies, and permissions must remain unchanged.
- Any Pixel run requires a fresh exact-method authorization after the candidate APK pair is frozen.

## Host result and frozen device candidate

- The corrected full host suite passes 665/665 with zero failures, errors, or skips; lint has zero errors and 13 warnings; debug, unsigned release, and Android-test assembly pass.
- Room schemas, dependency versions, and permissions are unchanged.
- Corrected frozen SHA-256 values: debug `50dc9ab424da5120f030a727d23c1034af7a222201869ece8e72c9b3648a3ec0`; Android test `2d893a6a6cd3578b97d06dd3e92f465cff4ff89e909ed48b5ec3b135a8385e0c`; unsigned release `0ff77ed8657103de5822a50ffa3260cd9fa02664e0234573c2c85200492e088d`.
- The Pixel checkpoint contains only the three methods in `CaptureAttemptStartupReconcilerAndroidTest`. They use generated UUID databases and a generated UUID directory below `noBackupFilesDir`; teardown removes only those artifacts and the instrumentation package while preserving the installed main app and its data.
- The corrected exact three-method Pixel 6 invocation passed in 0.785 seconds. Installed hashes matched; generated database/directory residue and the instrumentation package were absent afterward; the main app and its data were preserved.
