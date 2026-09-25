# Audit repair validation — Pixel 6 — 4 September 2026

Result: **6/6 exact authorized Android methods passed**, zero failures or skips, in one instrumentation invocation. Runtime reported 4.896 seconds. Host verification on the same source had already passed 643/643 unit tests, lint with zero errors and 14 existing warnings, and debug/release/Android-test assembly.

The user explicitly authorized installation of the prepared app/test packages, these six isolated tests, and removal of generated test data and the instrumentation package. No additional test class, camera session, real permission interaction, or personal-image workflow was run.

## Device and artifacts

- Device: Pixel 6 (`oriole`), Android 16.
- Build fingerprint: `google/oriole/oriole:16/CP1A.260305.018/14887507:user/release-keys`.
- Run and cleanup completed on 2026-09-04, with cleanup recorded at approximately 16:42 UTC.
- Source: local `codex/development-takeover` work based on `32a1429`; no production or test source changed between building that approved APK pair and completing its authorized six-method run.

| Artifact | SHA-256 verified before installation |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `8b6fd37dfc938fcfad715788607b7b21552763cc74d9968c8ed2bf2b56afa0e0` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `89cb8ce3de86a9a104d3a710d948f573ea625b25def7e7b9509e82f751783cf5` |

Both packages installed successfully using data-preserving replacement, without permission auto-granting or clearing application data.

## Exact method results

| Class | Method | Result |
|---|---|---|
| `data.RoomShootPreparationRepositoryAndroidTest` | `listObserverReceivesReferenceChangesFromAnotherDatabaseOwner` | PASS |
| `importer.ReferenceImportStartupReconcilerAndroidTest` | `productionRuntimeRecoversAbandonedReservationAndUnblocksOtherShootsAfterReopen` | PASS |
| `ui.CameraPermissionRecoveryFlowTest` | `permanentDenialOpensSettingsInsteadOfRepeatingPermissionRequest` | PASS |
| `ui.editor.ShootEditorFlowTest` | `loadingMissingAndUnavailableExposeRecoverySemantics` | PASS |
| `ui.editor.ShootEditorFlowTest` | `importAndReconciliationStatusRemainVisible` | PASS |
| `ui.editor.ShootEditorFlowTest` | `allocationRepairGuidanceAlwaysHasActionEvenWithoutLocalImportRows` | PASS |

All class names use the `com.tonyisup.poseguidesnap` prefix. The runner was `com.tonyisup.poseguidesnap.test/androidx.test.runner.AndroidJUnitRunner`. A single comma-separated `class` selector named only the six methods above.

The [raw instrumentation output](2026-09-04-audit-repair-pixel6-output.txt) records all six exact `(class, method)` pairs with successful status, followed by `OK (6 tests)`. The output was parsed to require exactly that method set, with no duplicates or extra methods; aggregate count alone was not used as proof. Output SHA-256: `92ba7c12c237de168b3c0bd07c292f34dc2c7a83b647a8b5cada0ddc9385da25`.

## Cleanup and limits

Before installation, no app-local files matched the two generated test-database prefixes or the generated reference-recovery directory prefix. After execution, the same bounded check found zero matching databases, SQLite sidecars, Room lock files, or recovery directories. The tests also asserted exact teardown internally.

The instrumentation package was uninstalled successfully and its package lookup returned absent. The main app remains installed. No application-data clear, main-app uninstall, production-database read, provider-image access, or user-file removal was performed.

The Room recovery test demonstrates recovery from a persisted abandoned reservation after database close/reopen through the production runtime factory. It is not an operating-system process-kill test. The notification test demonstrates one-write invalidation between independently owned Room databases, rather than the entire import/Back navigation journey. Compose permission testing uses injected callbacks, so it proves button routing and semantics rather than Android permission dialogs or return-from-Settings lifecycle behavior.

The manual three-photo capture workflow, automatic matching accuracy, export, physical deletion, and speech remain unimplemented or unverified as described in the [development checkpoint](../DEVELOPMENT_PROGRESS.md). This bounded run does not complete Gate 2.
