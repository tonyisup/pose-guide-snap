# Task 14A.1 Atomic Room V3 Bootstrap — Pixel 6 Validation

- **Date:** 2026-09-02
- **Device class:** Pixel 6 (`oriole`)
- **Android release:** 16
- **Repository baseline:** `1b0fd3caf4883d83d3747a8a3a748c047b070fe6`
- **Main APK SHA-256:** `d1ecb9b0df5f8954f996c84832e0e06489f77cfc717074df1d2a5a156205c527`
- **Instrumentation APK SHA-256:** `2dbb8730709b91df8f41fe8daaac18695ecdb89ca6f30ab624280a421ecb2dbc`

## Scope

This run validated only the Task 14A.1 read-only Room V3 bootstrap boundary. It did not exercise UI navigation, process-death routing, Photo Picker, camera permission, CameraX, private image capture, MediaStore, physical deletion, speech, audio, or the end-to-end guided workflow.

The test package used UUID-named databases in the target app's test-owned database namespace. No provider image, user database row, screenshot, camera frame, path/URI value, or device identifier was retained in this record.

## Pre-device host evidence

The candidate passed:

- 579/579 JVM tests, with zero failures, errors, or skips;
- `:app:lintDebug`;
- `:app:assembleDebug`;
- `:app:assembleRelease`;
- `:app:assembleDebugAndroidTest`;
- byte comparison proving Room V1, V2, and V3 schema artifacts were unchanged.

Generated Room code wrapped the bootstrap DAO with `performBlocking(__db, false, true)`. Inspection of the installed Room 2.8.4 bytecode showed that `isReadOnly=false` selects SQLite transaction type `IMMEDIATE`.

## Artifact identity

Before execution, exactly one connected device was identified as a Pixel 6. The locally built APKs were hashed, installed with replacement while preserving main-app data, pulled back from their installed package paths, and hashed again. Both installed hashes matched the values above.

## Focused instrumentation

Command scope:

- `RoomShootRepositoryAndroidTest#roomV3BootstrapSurvivesReopen`
- all five methods in `GuidedSessionPacket2BAndroidTest`

Result:

- **6/6 passed**
- **0 failures**
- AndroidJUnitRunner elapsed time: **1.623 seconds**

Evidence by method:

1. Exact nonzero active state survived Room close/reopen with pose index, attempt counter, receipt token, and unresolved export count intact.
2. A production confirmation writer could not complete while bootstrap was paused inside its immediate transaction; bootstrap returned complete pre-confirmation authority, then the writer committed and a later bootstrap returned complete post-confirmation authority.
3. The equivalent deletion writer was likewise excluded until bootstrap completed; later bootstrap observed the complete deleting generation and cancelled untouched export rows.
4. Nontransactional confirmation reads deterministically combined a pre-confirmation session row with post-confirmation receipt/output facts, proving the mutation control.
5. Nontransactional deletion reads deterministically combined pre-deletion session/shoot facts with post-deletion state, proving the second mutation control.
6. Repeated bootstraps preserved SHA-256 digests of every V3 authority table and `sqlite_master`, and preserved `total_changes()` plus `PRAGMA data_version`.

## Cleanup

Every test closed its Room connections and deleted its UUID-named database in teardown. A separate post-run check found:

- zero matching Task 14A.1 test-database or sidecar filenames;
- instrumentation package absent;
- main application package and its existing data preserved.

The cleanup verifier initially had a remote-shell quoting error after all six tests passed. No success claim was made from that command. Cleanup was then independently checked with filtered database-name inspection and package lookup; both returned the zero/absent results above.

## Evidence boundary

This record proves the exact tested APK pair's Room bootstrap behavior only. It does not prove active-session discovery, UI resume, production process-death recovery, camera capture, export, deletion completion, or Gate 2.
