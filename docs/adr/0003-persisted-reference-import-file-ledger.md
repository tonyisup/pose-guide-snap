# ADR 0003: Persisted Reference-Import File Ledger

- **Status:** Accepted and implemented in `d368e96a0335b1281471faca706287c4980652f0`; Pixel 6 gate complete
- **Date:** 2026-08-30
- **Decision owner:** Product/architecture owner approved redesign after the third rejected Task 11B candidate
- **Scope:** Task 11B reference import only

## Context

A reference import spans two authorities that cannot participate in one physical transaction:

1. app-private filesystem publication and cleanup; and
2. Room intent, validation, and active-pose state.

Three exact-digest review cycles proved that reconstructing authority from current filenames alone is not sufficient:

- a foreign deterministic pending file could remain while Room claimed cleanup;
- process-local inode handles could not survive process death;
- retries could terminalize Room without re-establishing directory durability;
- unresolved Room rows could become terminal and stop retrying;
- a partial provider write could be quarantined without first proving file-data durability.

The problem is not another missing conditional. The architecture lacks a durable record of which filesystem side effect was admitted, which file bytes were synced, and which directory mutation was made durable.

## Decision

Task 11B uses a **persisted filesystem-operation ledger in Room**. The ledger is separate from the logical import lifecycle.

Room records a stage before and after each irreversible filesystem boundary. Restart reconciliation uses the persisted stage plus exact token-derived paths; it does not infer policy from arbitrary directory contents and does not scan for matching filenames.

`RECONCILIATION_REQUIRED` is an actionable, retryable condition. It is not terminal success or terminal rejection.

## Ownership split

| Boundary | Owns | Does not own |
|---|---|---|
| Reference import intent | shoot/pose/token identity, logical import lifecycle, final active-pose commit | file durability or inode identity |
| File-operation ledger | admitted file stage, expected exact paths, synced byte count/hash, cleanup/quarantine progress | person validation, pose order, active-pose publication |
| Reference asset store | exact app-private file operations under one process-wide mutation guard | Room lifecycle decisions |
| Import coordinator | ordered Room journal calls and filesystem effects | hidden clocks, UI, provider persistence |
| Startup reconciler | resume the exact ledger stage after process death | broad directory scans or new provider reads |

## Exact deterministic paths

For import token digest `H = SHA-256(token UTF-8)`:

- final reservation/asset: `reference-assets/assets/H.asset`
- pending temp: `reference-assets/assets/.H.asset.pending`
- quarantine: `reference-assets/quarantine/H.quarantined`

Raw tokens, shoot IDs, pose IDs, labels, and provider URIs never enter filenames.

## File-operation ledger

The V2 schema adds one `reference_import_file_operations` row per import token with restrictive foreign-key ownership.

Required fields:

- `import_token` — primary/foreign key to the logical intent;
- `stage` — closed stage vocabulary below;
- `relative_asset_path`, `relative_temp_path`, `relative_quarantine_path` — exact deterministic paths;
- `byte_count` and `sha256` — nullable until complete temp bytes have been synced;
- `created_at_epoch_millis`, `updated_at_epoch_millis` — injected wall-clock values;
- `last_failure_code` — closed, path-free code; nullable;
- `reconciliation_required` — explicit retry flag, never treated as terminal success.

No provider URI, absolute path, raw exception, image metadata, or landmark payload is stored in this ledger.

## Stage vocabulary

| Stage | Persisted meaning | Permitted next operation |
|---|---|---|
| `EXPECTING_RESERVATION` | Intent exists; no filesystem side effect is trusted yet | inspect/claim exact final reservation |
| `WRITING_TEMP` | Final reservation and temp creation were admitted; temp bytes may be partial | delete exact partial temp/reservation and sync, or continue only in the same live owner |
| `TEMP_SYNCED` | Temp is regular, nonempty, fsynced, and bound to persisted byte count/hash | verify exact identities, rename over owned reservation |
| `FINAL_RENAME_PENDING_SYNC` | Rename may have happened; final identity must match persisted byte count/hash | fsync assets directory and reobserve exact final/temp paths |
| `FINAL_DURABLE` | Final bytes and assets-directory rename are durable | atomically mark logical intent `ASSET_READY` |
| `CLEANUP_REQUIRED` | Cleanup was selected before deletion side effects | remove exact owned paths, fsync/reobserve |
| `CLEANUP_PENDING_SYNC` | Delete may have happened but directory durability is not established | fsync assets/quarantine directories and reobserve absence |
| `CLEANED_DURABLE` | Exact asset/temp/quarantine paths are absent after directory sync/reobservation | mark logical intent `REJECTED_CLEANED` |
| `QUARANTINE_REQUIRED` | Quarantine was selected; source bytes must already be synced and hash-bound | reserve exact quarantine, rename exact source |
| `QUARANTINE_PENDING_SYNC` | Quarantine rename may have happened | fsync source and quarantine directories; reobserve exact paths/inode/size/hash |
| `QUARANTINE_DURABLE` | Deterministic quarantine bytes and directory mutation are durable | mark logical intent `REJECTED_QUARANTINED` |

`reconciliation_required = true` may accompany any nonterminal stage. Retrying resumes that exact stage; it does not erase or replace it with a generic terminal lifecycle.

## Publication protocol

1. In one Room transaction, reserve the logical import and create its file ledger at `EXPECTING_RESERVATION`.
2. While the ledger remains `EXPECTING_RESERVATION`, claim the exact zero-byte final reservation and exact zero-byte temp with no-clobber semantics. A process death here leaves only zero-byte shapes that the `EXPECTING_RESERVATION` recovery rule may delete; a pre-existing nonempty temp is contradictory and must not be deleted.
3. Only after both exact empty files were successfully claimed, persist `WRITING_TEMP`. This stage means file creation was admitted and provider bytes may now be partial.
4. Stream provider bytes only into the already-claimed temp under the encoded-size limit.
5. Require a nonempty regular temp, fsync it, calculate byte count/hash, then persist `TEMP_SYNCED` with that evidence.
6. Verify the exact temp and exact owned zero-byte reservation, then atomically rename temp over reservation.
7. Persist `FINAL_RENAME_PENDING_SYNC` if the rename returned; if persistence fails, restart reconciliation remains safe from `TEMP_SYNCED` by exact inspection.
8. Fsync the assets directory and reobserve final regular bytes matching the persisted count/hash with temp absent.
9. Persist `FINAL_DURABLE`.
10. In one Room transaction, transition the logical intent to `ASSET_READY` only when the ledger is exactly `FINAL_DURABLE`.
11. Analyze the durable app-private final and commit the validated pose.

A process death at any point resumes from the last persisted stage. Current filenames may confirm or refute that stage, but cannot invent a later stage.

## Cleanup protocol

Partial `WRITING_TEMP` bytes are never quarantined as retained evidence because they were not proven data-durable. They are deleted through `CLEANUP_REQUIRED` → `CLEANUP_PENDING_SYNC` → `CLEANED_DURABLE`.

A file may enter quarantine only when its byte count/hash were persisted after file fsync (`TEMP_SYNCED` or later):

1. persist `QUARANTINE_REQUIRED` before rename;
2. verify source identity/count/hash;
3. reserve the exact deterministic quarantine path without clobber;
4. atomically rename source to quarantine;
5. persist `QUARANTINE_PENDING_SYNC`;
6. fsync both source and quarantine directories;
7. reobserve source absent and exact quarantine identity/count/hash unchanged;
8. persist `QUARANTINE_DURABLE`;
9. only then mark the logical intent `REJECTED_QUARANTINED`.

Cleanup follows the analogous rule: Room cannot record `REJECTED_CLEANED` until `CLEANED_DURABLE` exists.

## Restart and concurrency

- Startup enumerates retryable ledger rows, including rows with `reconciliation_required = true`.
- It never skips a row merely because a prior attempt required reconciliation.
- It inspects only the three exact token-derived paths.
- A process-local inode identity may be reminted only after no-follow regular-file validation inside the exclusively owned directory and under the process-wide mutation guard.
- Persisted stage plus byte count/hash controls whether an observed file may be resumed, deleted, or quarantined.
- A partial `WRITING_TEMP` file is deleted, not quarantined.
- `CLEANUP_PENDING_SYNC` retries the exact authorized deletion before directory sync; exact absence remains an acceptable idempotent outcome.
- `QUARANTINE_PENDING_SYNC` retries the exact evidence-bound move before directory sync; the source or destination shape is accepted only when the persisted count/hash proves it.
- `TEMP_SYNCED` and `FINAL_RENAME_PENDING_SYNC` recovery accept both exact namespace outcomes of an unsynced rename: synced temp plus zero reservation, or matching final plus absent temp.
- Directory sync is retried before any terminal Room lifecycle is recorded.
- Ordinary reservation replay remains nonauthorizing except for exact committed replay. A new user-selected retry after `REJECTED_CLEANED` uses a separate explicit command that atomically CAS-resets both the coherent logical intent and its unflagged `CLEANED_DURABLE` ledger row to a new `PREPARING`/`EXPECTING_RESERVATION` generation before any file claim or provider read.
- Room transactions and the process-wide filesystem mutation guard serialize supported in-process mutation. No claim is made against arbitrary same-UID code bypassing the adapter.

## Failure behavior

- Unknown, contradictory, nonregular, symlink, mismatched-size, or mismatched-hash states remain retryable reconciliation failures.
- No automatic path metadata lookup, broad scan, or provider reread is authorized.
- A failed Room journal update after a filesystem effect leaves the previous stage, whose restart rule must safely inspect both possible outcomes.
- A failed filesystem effect leaves the pre-effect stage or an explicit pending-sync stage.
- Raw exceptions, absolute paths, tokens, URIs, labels, and landmarks are never logged or returned.

## Verification requirements

Host tests must cover every stage and both crash windows around each filesystem effect:

- before/after reservation;
- partial write;
- after temp fsync before Room evidence;
- before/after rename;
- before/after directory fsync;
- before/after cleanup delete;
- before/after quarantine rename;
- failed Room update after each filesystem effect;
- retry from every `reconciliation_required` stage;
- concurrent same-token and same-pose attempts;
- no-clobber foreign final/temp/quarantine controls.

The authorized Pixel 6 gate must run the exact staged APK and prove V1→V2 migration, generated-byte file stages, restart recovery, public-fixture analysis, picker-handler dispatch, zero database/file residue, and no production database use.

## Consequences

- Task 11B is larger than the original import slice, but the ownership model is executable and reviewable.
- Room V2 is committed; future schema changes require an explicit migration from V2.
- Filename inference is demoted to evidence checked against the ledger, not authority.
- The existing rejected Task 11B candidates remain preserved in Git stashes for audit.
- Task 12 UI remains deferred and must call the final import/startup reconciliation APIs rather than reimplement policy.
