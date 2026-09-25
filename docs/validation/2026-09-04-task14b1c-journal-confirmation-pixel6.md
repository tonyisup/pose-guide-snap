# Task 14B.1C journal-derived confirmation validation — Pixel 6 — 4 September 2026

Result: **8/8 exact authorized Android methods passed**, with zero failures or skips, across two bounded final invocations. The five confirmation methods completed in 4.401 seconds and the three Packet 2B methods completed in 1.257 seconds. Host verification on the corrected source passed 652/652 unit tests, lint with zero errors and 13 warnings, and debug/release/Android-test assembly.

The user explicitly authorized installation of the prepared app/test packages, these eight isolated methods, and removal of generated test databases and the instrumentation package. No additional test class, camera session, photo picker, personal-media access, audio test, or release deployment was run.

## Causal correction

The first five-method candidate exposed a result-classification defect. Four methods passed, while `confirmationRejectsBackwardAndAcceptsEqualBoundaryTimestamps` observed `JOURNAL_AUTHORITY_INVALID` for the `journal updated 0` backward-clock case where the contract requires `INVALID_TIMESTAMP`. The rejection was fail-closed and did not mutate authority, but its typed reason was wrong.

The implementation was corrected to keep raw journal shape/storage validation ahead of typed mapping while comparing confirmation time with structurally valid journal clocks as a separate step. Post-write validation repeats both checks before journal consumption. The complete host gate passed again, the corrected app APK was installed with data-preserving replacement, and all five confirmation methods then passed. This provides a causal RED/GREEN result for the correction.

## Device and artifacts

- Device: Pixel 6 (`oriole`), Android 16.
- Build fingerprint: `google/oriole/oriole:16/CP1A.260305.018/14887507:user/release-keys`.
- Source: local `codex/development-takeover` work based on `32a1429`; no production or test source changed between building the final APK pair and completing the successful authorized run.

| Artifact | Final local and installed SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `9746a8d1fa095352a826ec77c0e2c4568e957be6003503e66efd1a4e3c18c4c5` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `975eac3620a7b3a1a6b814c8f8aa822c42df1953fce14dc289bdd6cd785a8ce1` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` — host build evidence only | `1698bc4a9899834a027c6c7ea043f18eea659421cb288b9edacfa9ad481ee442` |

Both tested packages installed successfully using data-preserving replacement. Hashes read from the installed `base.apk` files exactly matched the final local app and instrumentation APKs.

The causal RED app APK SHA-256 was `66c0e8c108a07384e71521aecba7539bda05df938ba1860dea446ddf8d89aff9`. Its instrumentation APK was byte-identical to the final test APK because the correction changed production code only.

## Exact method results

| Class | Method | Result |
|---|---|---|
| `data.CaptureConfirmationRepositoryAndroidTest` | `confirmationDerivesOutputsFromFinalJournalAndConsumesRows` | PASS |
| `data.CaptureConfirmationRepositoryAndroidTest` | `confirmationRejectsBackwardAndAcceptsEqualBoundaryTimestamps` | PASS after causal correction |
| `data.CaptureConfirmationRepositoryAndroidTest` | `confirmationReplayRequiresNoResidualJournalAcrossReopen` | PASS |
| `data.CaptureConfirmationRepositoryAndroidTest` | `confirmationFaultAfterJournalDeleteRollsBackEverything` | PASS |
| `data.CaptureConfirmationRepositoryAndroidTest` | `confirmationDeletionAndDuplicateRacesHaveOneWinner` | PASS |
| `data.GuidedSessionPacket2BAndroidTest` | `immediateBootstrapBlocksConfirmationWriterAndReturnsCompletePreThenPostState` | PASS |
| `data.GuidedSessionPacket2BAndroidTest` | `nontransactionalConfirmationReadsCanProduceMixedPreSessionAndPostReceiptState` | PASS |
| `data.GuidedSessionPacket2BAndroidTest` | `repeatedBootstrapsAreReadOnlyAcrossEveryV4AuthorityTableAndSchema` | PASS |

All class names use the `com.tonyisup.poseguidesnap` prefix. The runner was `com.tonyisup.poseguidesnap.test/androidx.test.runner.AndroidJUnitRunner`. The final run used one exact comma-separated five-method selector followed by one exact three-method selector.

The [raw instrumentation output](2026-09-04-task14b1c-journal-confirmation-pixel6-output.txt) records the causal failure and both successful final invocations. Output SHA-256: `7a0244cd166d0691acaf6dc97273caedccec84e7ada5495f5e2571be0cdd13c2`.

## What this proves

On this APK pair and device, first confirmation derived the three immutable private outputs from exact final journal authority and consumed the three transient rows in the same transaction; rejected missing, partial, non-final, conflicting, reconciliation-marked, malformed, residual, and backward-clock authority without partial mutation; accepted exact clock equality; rolled every authority family and the journal back after faults and post-write drift; replayed from immutable authority across reopen only with zero residual journal rows; and serialized deletion and duplicate confirmation to one committed winner.

The Packet 2B tests also prove that the immediate nine-family bootstrap snapshot excludes a concurrent confirmation writer, that the nontransactional control can construct the expected mixed view, and that repeated bootstraps leave every V4 authority table and the schema unchanged.

These are authority and transaction tests using generated data. They do not execute a physical file operation or establish CameraX/filesystem integration, capture quality, personal-media handling, export, physical deletion, audio, TalkBack usability, or the end-to-end workflow.

## Cleanup

Each test used a generated UUID-named Room database and asserted teardown. The post-run app database-directory check was empty, including no matching database, SQLite sidecar, or Room lock residue. The instrumentation package was uninstalled and confirmed absent. The main app remained installed; its application data was not cleared and no user file was removed.

This bounded run completes the Task 14B.1C device checkpoint. It does not complete Gate 2 because no coordinator yet binds the journal to CameraX and app-private filesystem effects.
