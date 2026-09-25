# Task 14B.1B Room file-admission validation — Pixel 6 — 4 September 2026

Result: **6/6 exact authorized Android methods passed**, with zero failures or skips, in one instrumentation invocation. Runtime reported 3.866 seconds. Host verification on the same source passed 652/652 unit tests, lint with zero errors and 13 warnings, and debug/release/Android-test assembly.

The user explicitly authorized installation of the prepared app/test packages, these six isolated tests, and removal of generated test databases and the instrumentation package. No additional test class, camera session, photo picker, personal-media access, audio test, or release deployment was run.

## Device and artifacts

- Device: Pixel 6 (`oriole`), Android 16.
- Build fingerprint: `google/oriole/oriole:16/CP1A.260305.018/14887507:user/release-keys`.
- Source: local `codex/development-takeover` work based on `32a1429`; no production or test source changed between building this APK pair and completing the authorized run.

| Artifact | Local and installed SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `0b4f91958dc1ebf7ecebac1272cb6470e1ec11817a538b5f520fbe701bfb6868` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `544198b7b0ac19b0b9cf36e509122f60abe45d8bccb243a0e2c6fc1b44b6f3ba` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` — host build evidence only | `d127c1b9740380ca4de647740a5a99dacb898b116578b0381c35dedbea09671a` |

Both tested packages installed successfully using data-preserving replacement. Hashes read from the installed `base.apk` files exactly matched the reviewed local app and instrumentation APKs.

## Exact method results

| Class | Method | Result |
|---|---|---|
| `data.RoomCaptureFileJournalAndroidTest` | `captureFileJournalAdvancesLegalTransitionsAndIsIdempotent` | PASS |
| `data.RoomCaptureFileJournalAndroidTest` | `captureFileJournalRejectsInvalidStorageAndCausalTimestampsWithoutMutation` | PASS |
| `data.RoomCaptureFileJournalAndroidTest` | `captureFileJournalCasHasOneWinnerAcrossWalConnections` | PASS |
| `data.RoomCaptureFileJournalAndroidTest` | `captureFileJournalPersistsAcrossReopen` | PASS |
| `data.DeletionExportRepositoryAndroidTest` | `captureFileJournalDeletionInterlockLinearizesAdmittedEffects` | PASS |
| `data.DeletionExportRepositoryAndroidTest` | `deletionRejectsBackwardAndAcceptsEqualCompleteAuthorityClock` | PASS |

All class names use the `com.tonyisup.poseguidesnap` prefix. The runner was `com.tonyisup.poseguidesnap.test/androidx.test.runner.AndroidJUnitRunner`. A single comma-separated `class` selector named only the six methods above.

The [raw instrumentation output](2026-09-04-task14b1b-room-file-admission-pixel6-output.txt) records the six exact `(class, method)` pairs with successful status, followed by `OK (6 tests)`. Output SHA-256: `a128e966a985e67a19ee0c52801fb4a124df6b97b569ece2d88b1b95bedbeb4b`.

## What this proves

On this APK pair and device, the Room-owned journal advanced legal transitions and replayed them idempotently; rejected malformed storage, corrupt authority, illegal transitions, stale snapshots, and backward causal clocks without mutation; allowed only one compare-and-set winner across two WAL connections; persisted across close/reopen; serialized deletion against admitted and settled file effects in all three forced transaction orders; and accepted exact deletion-clock equality while rejecting one-millisecond-backward requests.

These are authority and transaction tests using generated data. They do not execute a physical file operation or establish CameraX/filesystem integration, capture quality, personal-media handling, export, physical deletion, audio, TalkBack usability, or the end-to-end workflow.

## Cleanup

Each test used a generated UUID-named Room database and asserted teardown. The post-run app database-directory check was empty, including no matching database, SQLite sidecar, or Room lock residue. The instrumentation package was uninstalled and confirmed absent. The main app remained installed; its application data was not cleared and no user file was removed.

This bounded run completes the Task 14B.1B device checkpoint. It does not complete Gate 2 because no coordinator yet binds the journal to CameraX and app-private filesystem effects. Journal-derived atomic confirmation remains the next authority slice.
