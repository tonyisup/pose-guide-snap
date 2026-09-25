# Task 14B.2 — Attempt settlement and restart reconstruction

**Status:** Implemented and boundedly verified on the host and Pixel 6. The full host gate passes 655/655 tests; the exact six-method device checkpoint passes. See [ADR 0009](../../docs/adr/0009-room-attempt-settlement-and-restart-reconstruction.md) and the [validation record](../../docs/validation/2026-09-04-task14b2-attempt-settlement-pixel6.md).

## Goal

Make an unfinished three-photo attempt restart-safe without performing filesystem I/O. Room must distinguish a retryable failure with no candidate bytes from an attempt that still needs bounded filesystem reconciliation.

## Boundary

This packet changes Room authority, bootstrap reconstruction, retry admission, and tests. It does not call CameraX, create/delete/rename files, scan directories, export to MediaStore, or compose the guided-session coordinator.

## Lifecycle contract

`capture_attempts.lifecycle_state` gains two stored values without a schema change:

- `FAILED_CLEANED`: terminal and retryable. It owns no journal, private-output, receipt, outbox, or export rows.
- `RECONCILIATION_REQUIRED`: nonterminal and blocking. It retains exactly three coherent journal rows and owns no immutable confirmation children.

Attempt numbers remain contiguous and monotonic. Multiple `FAILED_CLEANED` attempts may precede the one confirmed attempt for a pose. Walking attempts in number order, every attempt belongs to the current unconfirmed pose; only `CONFIRMED` advances that derived pose. There may be at most one blocking attempt, and it must be the last attempt at the session's current pose and deletion generation.

## Settlement transaction

`settleCaptureAttemptFailure(sessionId, token, settledAtEpochMillis)` derives all authority from Room.

- A `REGISTERED` or `CAPTURING` attempt with exactly three untouched `EXPECTING_RESERVATION` rows can settle directly because no file effect was admitted.
- A `CAPTURING` attempt with exactly three clean `CLEANED_DURABLE` rows can settle directly.
- Direct settlement atomically deletes exactly three journal rows, changes the attempt to `FAILED_CLEANED`, and advances the owning session clock.
- Three clean `FINAL_DURABLE` rows return `READY_TO_CONFIRM` without mutation.
- Every other coherent `CAPTURING` journal is retained while the attempt atomically becomes `RECONCILIATION_REQUIRED`; the session clock advances in the same transaction.
- Replays of `FAILED_CLEANED` and `RECONCILIATION_REQUIRED` validate their full residual authority before returning idempotently.
- Malformed storage, missing/extra journal rows, immutable child rows on an unfinished attempt, backward clocks, stale ownership, deletion, and CAS loss reject without partial mutation.

The aggregate reconciliation state is deliberately not writable through the ordinary per-file journal API. Task 14B.3 will own the recovery-only filesystem observations and transitions.

## Retry and restart

Registration rejects while the session owns any `REGISTERED`, `CAPTURING`, or `RECONCILIATION_REQUIRED` attempt. It permits the next contiguous attempt after `FAILED_CLEANED` and requires its timestamp to follow the session settlement clock.

Bootstrap reconstructs failed history, confirmed progress, and the optional final blocker. Its snapshot exposes `failedAttemptCount`; the invariant is:

`attemptCount == confirmedAttemptCount + failedAttemptCount + blockingAttemptCount`.

## Verification

- Host contract tests cover lifecycle shape, retries, attempt ordering, redaction, and bootstrap reconstruction.
- Room instrumentation tests cover clean settlement, reconciliation marking, final-ready classification, replay across reopen, rollback, registration blocking/retry, and concurrent one-winner behavior.
- The existing full host gate must remain green and Room schemas/permissions must remain unchanged.
- Any Pixel run requires a new exact-method authorization after the build is frozen.

## Final result

- The host suite passes 655/655 with zero failures, errors, or skips; lint has zero errors and 13 warnings; debug, unsigned release, and Android-test assembly pass.
- Room schemas and application permissions are unchanged.
- The final app, Android-test, and unsigned release SHA-256 values are `bd080ac0dbeccd8678f7a4aca4b4503f21623361c1460faab1a5cd5aad51595b`, `41f33975475691f67db64ba2f3761b7988ae7301d8d7c7c36e5430a7928ec7b2`, and `9f8239a80a6d2bd5f435afc507b9872aaceff1488e2fb0aa854e2f3b7be5591d`.
- The exact six-method Pixel 6 run passed in 1.376 seconds. Installed app/test hashes matched the final local artifacts; generated databases and the instrumentation package were removed while main-app data remained intact.
- Migrated unfinished attempts with no V4 journal remain fail-closed. Task 14B.3 must inspect bounded filesystem state before resolving them; Task 14B.2 does not infer absence of candidate bytes from missing migrated metadata.
