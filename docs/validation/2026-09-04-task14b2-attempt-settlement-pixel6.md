# Task 14B.2 attempt-settlement validation — Pixel 6 — 4 September 2026

Result: **6/6 exact authorized Android methods passed**, with zero failures or skips, in one final instrumentation invocation. Runtime reported 1.376 seconds. Host verification on the same source passed 655/655 unit tests, lint with zero errors and 13 warnings, and debug/release/Android-test assembly.

The user explicitly authorized installation of the prepared app/test packages, these six isolated methods, and removal of generated test databases and the instrumentation package. No additional test class, camera session, photo picker, personal-media access, audio test, or release deployment was run.

## Candidate corrections

The first candidate failed all six methods during shared setup before any settlement mutation. Its one-pose fixture violated the production three-pose session invariant, and the newly tightened attempt-registration guard reused the complete bootstrap mapper, returning `JOURNAL_AUTHORITY_INVALID`. The implementation was narrowed to classify only the storage-sensitive attempt facts needed for admission, preserving fail-closed bootstrap validation without making registration depend on unrelated session cardinality. The fixture was expanded to three poses.

The second candidate also failed during shared setup before settlement. The test command retained the old `settlement-pose` identifier while the expanded fixture named its first row `settlement-pose-0`, so registration correctly returned `STALE_POSE`. Correcting that test-only identifier produced the final candidate. These two runs are setup RED results rather than evidence that settlement behavior failed; neither candidate changed attempt settlement authority, and generated database residue was removed after each run.

## Device and artifacts

- Device: Pixel 6 (`oriole`), Android 16.
- Build fingerprint: `google/oriole/oriole:16/CP1A.260305.018/14887507:user/release-keys`.
- Source: local `codex/development-takeover` work based on `32a1429`; no production or test source changed between building the final APK pair and completing the successful authorized run.

| Artifact | Final local and installed SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `bd080ac0dbeccd8678f7a4aca4b4503f21623361c1460faab1a5cd5aad51595b` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `41f33975475691f67db64ba2f3761b7988ae7301d8d7c7c36e5430a7928ec7b2` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` — host build evidence only | `9f8239a80a6d2bd5f435afc507b9872aaceff1488e2fb0aa854e2f3b7be5591d` |

Both tested packages installed successfully using data-preserving replacement. Hashes read from the installed `base.apk` files exactly matched the final local app and instrumentation APKs.

The first setup candidate used app SHA-256 `cc41dbc8e29bc1e24af6d9ed29fcc3128d4682aae399e841dc5eda490c5366d2` and instrumentation SHA-256 `a1d0846a0e71e08293e2ab421e732d239cca491c1bbd380bd52ff68e525bf982`. The second used the final app APK and instrumentation SHA-256 `22de4051ccc027b3058f80c9650257b9f86391929a66cc545f75b481fa62eba9`.

## Exact method results

| Class | Method | Result |
|---|---|---|
| `data.CaptureAttemptSettlementRepositoryAndroidTest` | `cleanedFailureSettlesAtomicallyAndAllowsNextAttempt` | PASS |
| `data.CaptureAttemptSettlementRepositoryAndroidTest` | `unfinishedFailureBecomesBlockingReconciliationAcrossReopen` | PASS |
| `data.CaptureAttemptSettlementRepositoryAndroidTest` | `finalDurableFailureSettlementDefersToConfirmationWithoutMutation` | PASS |
| `data.CaptureAttemptSettlementRepositoryAndroidTest` | `faultAfterSettlementJournalDeleteRollsBackAttemptJournalAndSessionClock` | PASS |
| `data.CaptureAttemptSettlementRepositoryAndroidTest` | `failedCleanedHistoryRemainsValidDeletionAuthority` | PASS |
| `data.CaptureAttemptSettlementRepositoryAndroidTest` | `concurrentCleanSettlementHasOneAppliedWinnerAndOneValidatedReplay` | PASS |

All class names use the `com.tonyisup.poseguidesnap` prefix. The runner was `com.tonyisup.poseguidesnap.test/androidx.test.runner.AndroidJUnitRunner`. One exact comma-separated `class` selector named only the six methods above.

The [raw final instrumentation output](2026-09-04-task14b2-attempt-settlement-pixel6-output.txt) records every successful `(class, method)` pair followed by `OK (6 tests)`. Output SHA-256: `c11d0d88107e96b8f44566edf2eb0d1ec457be01db43957dfa8f7192d476a955`.

## What this proves

On this APK pair and device, untouched or fully cleaned three-row journal authority settled atomically to retryable `FAILED_CLEANED`; retry registered the next contiguous attempt on the same pose; incomplete coherent authority became blocking `RECONCILIATION_REQUIRED` and remained so across close/reopen; three final durable rows deferred to confirmation without mutation; an injected fault after journal deletion rolled the attempt, journal, and session clock back together; failed-cleaned history remained valid deletion authority; and concurrent clean settlement produced one applied result plus one validated replay.

These are Room authority and transaction tests using generated data. They do not perform a physical file operation or establish CameraX/filesystem integration, capture quality, migrated journal-free recovery, personal-media handling, export, physical deletion, audio, TalkBack usability, or the end-to-end workflow. Task 14B.3 owns recovery-only filesystem observations and bounded startup reconciliation.

## Cleanup

Each test used a generated UUID-named Room database and asserted teardown. The post-run app database-directory check was empty, including no matching database, SQLite sidecar, or Room lock residue. The instrumentation package was uninstalled and confirmed absent. Temporary host copies of the installed APKs were removed. The main app remained installed; its application data was not cleared and no user file was removed.

This bounded run completes the Task 14B.2 device checkpoint. It does not complete Gate 2 because no coordinator yet binds the journal to CameraX and app-private filesystem effects.
