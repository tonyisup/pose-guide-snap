# Task 14B.3 journaled recovery validation — Pixel 6 — 5 September 2026

Result: **3/3 exact authorized Android methods passed**, with zero failures or skips, in the corrected final instrumentation invocation. Runtime reported 0.785 seconds. Host verification on the same final source passed 665/665 unit tests, lint with zero errors and 13 warnings, and debug/release/Android-test assembly.

The user separately authorized each frozen candidate installation, the same three isolated methods, and removal of generated test databases/directories plus the instrumentation package. No additional test class, camera session, photo picker, personal-media access, MediaStore operation, audio test, UI flow, app-data clear, or release deployment was run.

## Device and final artifacts

- Device: Pixel 6 (`oriole`), Android 16.
- Build fingerprint: `google/oriole/oriole:16/CP1A.260305.018/14887507:user/release-keys`.
- Source: local `codex/development-takeover` work based on `32a1429`; no production or test source changed between building the corrected final APK pair and completing the successful run.

| Artifact | Final local and installed SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `50dc9ab424da5120f030a727d23c1034af7a222201869ece8e72c9b3648a3ec0` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `2d893a6a6cd3578b97d06dd3e92f465cff4ff89e909ed48b5ec3b135a8385e0c` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` — host build evidence only | `0ff77ed8657103de5822a50ffa3260cd9fa02664e0234573c2c85200492e088d` |

Both tested packages installed successfully using data-preserving replacement. Hashes read from the installed `base.apk` files exactly matched the final local app and instrumentation APKs.

## Exact method results

| Class | Method | Result |
|---|---|---|
| `camera.CaptureAttemptStartupReconcilerAndroidTest` | `mixedJournalAndExactFilesRecoverAcrossDatabaseReopen` | PASS |
| `camera.CaptureAttemptStartupReconcilerAndroidTest` | `journalFreeLegacyAttemptSettlesOnlyAfterExactAbsenceAcrossReopen` | PASS |
| `camera.CaptureAttemptStartupReconcilerAndroidTest` | `journalFreeLegacyAttemptWithExactFileRemainsBlockingAndPreservesFile` | PASS |

All class names use the `com.tonyisup.poseguidesnap` prefix. The runner was `com.tonyisup.poseguidesnap.test/androidx.test.runner.AndroidJUnitRunner`. One exact comma-separated `class` selector named only the three methods above.

The [raw final instrumentation output](2026-09-05-task14b3-journaled-recovery-pixel6-output.txt) records every successful `(class, method)` pair followed by `OK (3 tests)`. Output SHA-256: `a4670e6ad09f5adeaf5b7d9acef59bf8a409eac28ab725c1a6f4f2b9fb43be09`.

## Device-found correction

The first frozen candidate used app SHA-256 `5a646a906506da9908e3682432f77df9e5a09ddbf1aefd68730fe15831e8cd7a`, instrumentation SHA-256 `2d893a6a6cd3578b97d06dd3e92f465cff4ff89e909ed48b5ec3b135a8385e0c`, and unsigned release SHA-256 `2d32ff5505f7c46bbf14467f99a9e47a6bcf08f013cb3fff9202ad1b095b11ef`. Both journal-free methods passed. The journaled method performed cleanup but returned `Outstanding(SETTLEMENT_FAILED)` because the Task 14B.2 bootstrap mapper still required every aggregate-reconciliation journal clock to be at or before the attempt's aggregate clock. That rule rejected the legitimate newer clocks written by Task 14B.3's cleanup-only lane.

The failure was safe: it did not claim settlement success, did not enable retry, and teardown removed the generated database and file root. The correction permits a journal row newer than the aggregate attempt only when its stage is `CLEANUP_REQUIRED`, `CLEANUP_PENDING_SYNC`, or `CLEANED_DURABLE`. A new causal host regression proves that a newer `WRITING_TEMP` row remains invalid. The corrected full host gate passed before the final candidate was frozen and separately authorized. The [raw first-candidate output](2026-09-05-task14b3-journaled-recovery-pixel6-attempt1-output.txt) has SHA-256 `6cc139d278446d38a3d1c9cf7d4994b27f64680203401c0daf3326ba5f9680fb`.

## What this proves

On the final APK pair and Pixel 6:

- A flagged mixed journal with a positive temporary candidate survived a real database close/reopen, progressed only through recovery cleanup stages, deleted its exact owned files, preserved an unrelated sibling, consumed all three journal rows, and settled to retryable `FAILED_CLEANED`.
- A current unfinished legacy attempt with no V4 journal settled only after the final, temporary, and quarantine paths for all three deterministic identities were observed absent across reopen.
- A legacy attempt with one exact deterministic file remained blocked, preserved those bytes, and retained its `CAPTURING` authority.
- The Android libc-backed adapter successfully exercised exclusive reservation, file and directory sync, inode/device ownership checks, exact deletion, and bounded path observation inside a generated app-private root.

These are generated database and app-private filesystem tests. They do not prove CameraX-to-journal composition, photographic capture or quality, personal-media handling, confirmation after three live captures, MediaStore export, product deletion, audio, TalkBack usability, or the end-to-end guided workflow.

## Cleanup

Each method used a generated UUID-named Room database and a generated UUID-named directory below `noBackupFilesDir`. Test teardown removed both, including SQLite/Room sidecars and intentionally retained exact-file fixtures. Post-run exact-pattern checks returned no generated database or directory residue. The instrumentation package was uninstalled and confirmed absent. Temporary host copies of installed APKs were removed. The corrected main app remains installed; its application data was not cleared and no user file was removed.

This bounded run completes the Task 14B.3 device checkpoint. It does not complete Gate 2 because Task 15 must still connect reducer-owned manual capture, Room admission, CameraX writes, file settlement, confirmation, and advancement.
