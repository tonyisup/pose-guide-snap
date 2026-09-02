# Task 14A.3 Stale-Safe Resume and Bootstrap-Gated Camera — Pixel 6 Validation

- **Date:** 2026-09-02
- **Device:** Google Pixel 6 (`oriole`), Android 16
- **Scope:** Editor-scoped resume, retained exact-session bootstrap, and Ready-only camera admission
- **Final main APK SHA-256:** `ab299c82f884482e0b438a46fc029c8a402276da0549d997446c4e2e023e6a86`
- **Final instrumentation APK SHA-256:** `fa63a29bf3b2993a5a162c78abba6d06d0ad42f51a3c43fe9ee60436262244f6`

## Implemented boundary

Task 14A.3 composes the Task 14A.2 discovery and Task 14A.1 bootstrap authorities without adding a second persistence owner:

1. The selected shoot's editor projection derives a non-authorizing `hasResumableSession` hint from `findActiveGuidedSession(shootId)`. Deleting shoots short-circuit to `false` because discovery deliberately rejects deleting authority.
2. A Resume click never trusts that hint. It acquires a fresh resource lease and calls `findActiveGuidedSession(shootId)` again. Only `Exact(sessionId)` produces a redacted `StartedSessionHandle`; stale `None`/`UnknownShoot`, typed rejection, exception, close, or a newer non-resumable projection cannot navigate.
3. Fresh Start and Resume select the same in-memory one-shot navigation capability and the same constant `started-session` route. No route argument, deep link, `SavedStateHandle`, or identity-derived ViewModel key carries the session ID.
4. The started route retains one `StartedSessionBootstrapViewModel`. Its owned Room database remains open through in-flight work and closes exactly once after the final lease returns.
5. That owner re-queries `loadGuidedSessionBootstrap(sessionId)`. Only an exact `Ready` snapshot invokes the existing camera destination. Loading, completed, reconciliation-required, missing, invalid, inconsistent, unavailable, stale-generation, and cleared states construct no camera content.
6. Process recreation starts at the shoot list because the navigation capability is intentionally not persisted. The user can reopen the shoot and re-discover the Room-owned active session; no retained route identity is invented after process death.

The list itself does not issue one discovery transaction per paginated row. Discovery is editor-scoped to the one selected shoot, which avoids an N+1 Room read pattern while keeping click-time authorization fresh.

## Host and build evidence

The final candidate passed:

- **618/618 JVM tests**, zero failures, errors, or skips;
- lint;
- debug APK assembly;
- release APK assembly;
- Android-test APK assembly;
- Room V1–V3 schema byte comparison;
- merged debug and release APK inspection showing no `android.permission.INTERNET`;
- APK inspection showing no Room compiler package in either runtime artifact.

New behavioral RED/GREEN evidence included:

- editor projection and fresh Resume discovery;
- stale hint handling and typed rejection mapping;
- revocation of a queued Resume navigation effect when a newer projection clears resumability;
- exhaustive bootstrap-result mapping and exact identity checks;
- cancellation propagation, noncooperative in-flight close, post-close lease denial, stale completion suppression, retry generation, and generation exhaustion;
- pure Ready-only camera authorization and bounded redacted non-Ready presentation;
- compact-height/large-font recovery scrolling, with a Pixel RED proving Retry had no scroll ancestor before the fix and a GREEN proving Retry and Back are reachable afterward;
- source-contract RED/GREEN for the retained route owner and removal of direct camera construction from `AppNavHost`.

## Device evidence

After local assembly, installed package bytes were streamed back and hashed. Both installed APK hashes exactly matched the local values above.

The final corrected APK pair passed **11/11** named methods across two successful bounded Pixel invocations:

```text
OK (9 tests)
OK (2 tests)
```

The methods comprised:

- 5/5 `ShootEditorFlowTest` synthetic-state Compose methods, including actionable Resume and disabled concurrent Start/Resume behavior;
- 4/4 `StartedSessionDestinationFlowTest` synthetic-state Compose methods, proving every non-Ready state excludes injected camera content, only `Ready` includes it, and Retry plus Back remain reachable by scrolling in a 320dp × 180dp viewport at 2.0 font scale;
- `RoomShootRepositoryAndroidTest.activeSessionDiscoveryFindsExactSessionAcrossReopen`;
- `RoomShootRepositoryAndroidTest.roomV3BootstrapSurvivesReopen`.

The two Compose classes inject screen state and fake camera content. They do not open CameraX, request camera permission, access the sensor, use the Photo Picker, read images, write MediaStore, or exercise personal data. The Room methods use UUID-named test databases and production Room V3 adapters.

## Failures, correction, and recovery

The first authorized invocation ran while the physical device could not launch the Compose test host. All eight Compose methods failed before obtaining a semantics hierarchy with Android's shared `No compose hierarchies found in the app` diagnostic; both Room methods passed. This was treated as an environmental test-host failure, not product evidence. The debug APK was inspected and confirmed to include the `androidx.activity.ComponentActivity` supplied by `ui-test-manifest`.

After the device was explicitly made awake/unlocked and a separate rerun was authorized, that pre-review APK pair passed all ten then-existing methods. Engineering review subsequently rejected staged digest `813c176ce94dbaed812e1cf40e798351e0ee108fc74f4f8e9f070dc21893552e` because the non-scrollable recovery column could clip Retry on compact-height or large-font displays.

The correction followed behavioral RED/GREEN on the Pixel:

1. Against the uncorrected code, `compactHeightAndLargeFontKeepRetryAndBackReachableByScrolling` failed exactly once because Retry had no ancestor exposing a scroll semantics action.
2. The production recovery column gained one remembered vertical scroll state. The same method then passed exactly once on the corrected APK.
3. The full corrected UI set passed 9/9. The runner silently omitted two mixed `Class#method` selectors, so that invocation was not reported as 11/11.
4. A subsequent selector attempt used stale guessed method names and returned `OK (0 tests)`; it is not passing evidence.
5. A final, separately authorized invocation used the source-exact names `activeSessionDiscoveryFindsExactSessionAcrossReopen` and `roomV3BootstrapSurvivesReopen`; both passed. Together, the corrected-artifact invocations provide 11/11 evidence.

After each attempt and correction run:

- test-database residue count was **0**;
- the instrumentation package was uninstalled and verified absent;
- the main application package and its data were not reset.

## Evidence boundary

This validation proves deterministic UI admission semantics, exact Room discovery/bootstrap regressions, and their source-level production composition on one exact APK pair. It does **not** prove a real process-kill/reopen gesture sequence, actual camera construction or release, preview, pose inference, capture, confirmation, export, deletion, TTS/audio, TalkBack usability, or the complete guided workflow. Gate 2 remains open.
