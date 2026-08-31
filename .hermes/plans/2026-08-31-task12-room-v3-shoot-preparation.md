# Task 12 Room V3 Shoot Preparation and Playlist Editor Plan

> **For Hermes:** Use the `software-development:subagent-driven-development` skill to implement this plan task-by-task. Require behavioral RED→GREEN evidence and specification plus quality/security approval of the same exact staged digest before each commit.

**Goal:** Add Room-owned shoot preparation and an accessible Compose workflow for create → import → validate → reorder → durably start, without entering the camera route before a valid session exists.

**Architecture:** Room V3 decouples immutable reference-import attempts from mutable active playlist positions. A narrow preparation repository and application facade own IDs, timestamps, cardinality, order, unresolved-work barriers, and idempotent session creation; Compose renders projections and passes a transient Photo Picker callback URI without persisting it. The existing Task 10 camera diagnostic becomes reachable only after durable start.

**Tech Stack:** Kotlin; Room 2.8.4/KSP; coroutines/Flow; Jetpack Compose Material 3; Navigation Compose; lifecycle ViewModel Compose; Android Photo Picker; JUnit; Android instrumentation and Compose UI tests.

**Approved decisions:** Room V3 decoupling; durable idempotent Room session creation; enter the existing camera diagnostic after successful start. Task 13 TTS and Task 14 capture/MediaStore integration remain out of scope.

---

## Non-negotiable boundaries

1. Compose never allocates pose indices, calls DAOs, constructs a 15-timestamp ledger timeline, or persists a provider URI.
2. Room rejects import admission before provider access when 20 active validated poses already exist.
3. Rejected/quarantined attempts remain durable but own no active playlist position.
4. `shoot_poses` is the sole mutable order authority.
5. Reorder and start are Room transactions with typed results and postcondition checks.
6. Start requires exactly 3–20 contiguous validated poses, no active session, no deletion, and no unresolved import work.
7. Exact start replay is idempotent; a different concurrent start loses.
8. Camera permission and `CameraXController` are unreachable before durable start.
9. No TTS, automatic capture, capture confirmation integration, MediaStore I/O, deletion UI, analytics, network, or cloud scope.
10. No Pixel 6, emulator, `adb`, or connected instrumentation run without explicit authorization for that exact run.

## Slice 12A — Room V3 preparation authority

### Task 12A.1: Prove and close the maximum-20 admission gap

**Objective:** Make Room reject the twenty-first active reference before it creates intent/file-ledger authority.

**Files:**
- Modify: `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/RoomReferenceImportRepositoryAndroidTest.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/ReferenceImportDao.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/ReferenceImportContracts.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomReferenceImportRepository.kt`

**TDD steps:**

1. Add a behavioral test using the existing `reserveImport()` API: seed an active shoot and 20 validated `shoot_poses`, reserve another import, require rejection without naming a future enum value, and assert no intent or file-ledger row exists.
2. Compile the test without changing production behavior. Compilation is not RED evidence. After plan approval, request explicit authorization for this single focused Pixel 6 method, run it, and require the causal runtime failure that current code returns `Reserved` and creates both authority rows.
3. Only after observing that behavioral RED, add typed `PLAYLIST_FULL`, a DAO count query limited to active validated poses for the exact shoot, and the repository branch.
4. Check the bound inside the reservation transaction before inserting either authority row.
5. Add 19→20 success, exact replay at 20, deletion, active-session, and two-connection concurrent reservation cases.
6. Run focused host tests, the complete 413+ JVM suite, and instrumentation compilation.

**Acceptance:** The existing API cannot reserve work that could become the 21st active pose, and rejection creates no durable side effect.

### Task 12A.2: Write V3 schema-artifact REDs

**Objective:** Pin the approved import-attempt/order separation before changing entities.

**Files:**
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/data/AppDatabaseV3SchemaArtifactTest.kt`
- Modify: `app/src/test/java/com/tonyisup/poseguidesnap/data/AuthorityEntityCounterTypeTest.kt`
- Modify: `app/src/test/java/com/tonyisup/poseguidesnap/data/AuthorityEntityToStringTest.kt`

**TDD steps:**

1. Add behavioral/source-artifact assertions against the existing schema API that fail because V2 still contains `reference_import_intents.pose_index` and its unique index.
2. Require V3 to preserve every V2 table and capture-authority contract while removing only duplicated import-order authority.
3. Pin exact columns, foreign keys, indexes, affinities, nullable widths, and redacted String fields.
4. Require committed import coherence by `(shoot_id, pose_id)` and active order solely in `shoot_poses`.

**Acceptance:** RED identifies duplicated order authority, not a nonexistent future class.

### Task 12A.3: Migrate reference-import contracts away from pose index

**Objective:** Remove active playlist position from logical/file import contracts while keeping deterministic token/file ownership unchanged.

**Files:**
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/ReferenceImportContracts.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/AuthorityEntities.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/ReferenceImportDao.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomReferenceImportRepository.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/importer/ReferencePoseImporter.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/importer/ReferencePickerResultHandler.kt`
- Modify: all focused JVM/androidTest callers

**TDD steps:**

1. Change tests first so reservation/evidence/draft identity is token + shoot + pose, never pose index.
2. Verify failures are causal at existing constructor/repository behavior.
3. Remove `poseIndex` from reservation, evidence, pending import, picker draft, importer request, entity, matching, and redacted result plumbing.
4. Make commit calculate `nextIndex = activeValidatedCount` in the transaction, require existing positions exactly `0..<nextIndex`, reject at 20, insert the active validated pose at `nextIndex`, and verify the committed intent/pose link by `(shootId, poseId)`.
5. Ensure rejected/quarantined intent replay no longer conflicts with a later attempt using a new token/pose ID.
6. Keep provider URI, raw token, labels, paths, and landmark payloads out of logs/errors.

**Acceptance:** Immutable attempts have no order field; successful imports append contiguously; terminal rejections block neither replacement nor start once no unresolved work remains.

### Task 12A.4: Add and verify `MIGRATION_2_3`

**Objective:** Preserve V2 data while installing the approved V3 schema without destructive fallback.

**Files:**
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/AppDatabase.kt`
- Create: `app/schemas/com.tonyisup.poseguidesnap.data.db.AppDatabase/3.json`
- Modify: `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/AppDatabaseMigrationAndroidTest.kt`
- Modify: `app/src/test/java/com/tonyisup/poseguidesnap/data/AppDatabaseV3SchemaArtifactTest.kt`

**TDD steps:**

1. Add a migration test that builds a populated V2 database containing committed, cleaned, quarantined, and retryable imports plus capture authority.
2. Compile and inspect the initial RED: V3/migration absent while V1/V2 artifacts remain unchanged.
3. Recreate `reference_import_intents` without `pose_index`; preserve exact remaining bytes and restrictive ownership. Rebuild the dependent file-operation table only if SQLite foreign-key rules require it.
4. Recreate only the `(shoot_id, pose_id)` unique index and lifecycle index.
5. Register `MIGRATION_2_3`; retain `MIGRATION_1_2`; never add destructive fallback.
6. Generate V3; verify V1 and V2 hashes remain byte-identical.
7. Run host schema tests and instrumentation compilation. Defer migration runtime execution to explicit device authorization.

**Acceptance:** Fresh V3 and both real migrations validate; no V1/V2 artifact changes; all historical intent/file/capture authority survives.

### Task 12A.5: Add one-active-session direct-SQL protection

**Objective:** Allow many completed sessions but at most one active session per shoot.

**Files:**
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/AuthorityOrdinalTriggers.kt` or create a narrowly named session-authority trigger module
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/AppDatabase.kt`
- Modify: host trigger-contract tests
- Modify: `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/AppDatabaseAndroidTest.kt`

**TDD steps:**

1. Add host REDs pinning exact trigger names/SQL for INSERT and lifecycle UPDATE.
2. Add instrumentation tests proving direct SQL rejects a second ACTIVE session for one shoot, permits completed history, survives reopen, and installs idempotently.
3. Implement callback-installed triggers using the existing authority-trigger path; do not pretend generated schema JSON records them.
4. Ensure migration/runtime callback ordering cannot expose an unguarded repository start.

**Acceptance:** Repository and direct SQL both preserve one-active-session authority.

## Slice 12B — Preparation repository and import facade

### Task 12B.1: Define redacted preparation projections and typed results

**Objective:** Create an application-facing contract with no Room/Android types.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/data/ShootPreparationContracts.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/data/ShootPreparationContractsTest.kt`

**Contract:**

- `ShootSummary`
- `ShootEditorSnapshot`
- `ValidatedReferenceSummary`
- `ImportWorkSummary` with only redacted status/retryability
- typed create/reorder/start eligibility and result values
- injected `ShootId`, `PoseId`, `ImportToken`, `SessionId`, and wall-clock providers

**TDD steps:** Validate nonblank trimmed names, timestamps, 0–20 editor snapshots, unique IDs, contiguous active order, closed status vocabularies, fresh result objects, and redacted `toString()` values.

### Task 12B.2: Add create/list/editor Room queries

**Objective:** Let the application create and observe preparation state without exposing entities.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/ShootPreparationDao.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/AppDatabase.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootPreparationRepository.kt`
- Create: `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/RoomShootPreparationRepositoryAndroidTest.kt`

**TDD steps:**

1. REDs for empty summaries, create, exact replay, ID conflict, trimmed/blank names, deletion state, ordered validated poses, terminal vs unresolved import summaries, and restart observation.
2. Insert one active shoot transactionally with injected identity/time.
3. Return Flow-based redacted projections ordered by update time and pose index; do not add `room-ktx` unless current Room runtime cannot support the required Flow API.
4. Prove invalid/corrupt rows fail closed rather than being normalized in UI.

### Task 12B.3: Add the import allocation/application facade

**Objective:** Keep opaque identity, timeline, retry, and provider handling outside Compose.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/importer/ReferenceImportApplicationService.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/importer/ReferenceImportApplicationServiceTest.kt`

**TDD steps:**

1. RED for allocate draft: active shoot below 20 returns a URI-free redacted draft with injected token/pose/time values and `mirrorAllowed=true`.
2. RED for full, deleting, active-session, and unresolved startup-reconciliation barriers before picker launch.
3. Keep `Uri` only as the immediate `ReferencePickerResultHandler.handle()` parameter; never put it in application state.
4. Translate Task 11B results into bounded editor statuses and explicit retry actions.
5. A terminal cleaned/quarantined attempt never gets rewritten; replacement allocates new identity.

**Acceptance:** UI requests an import action, launches the system picker, and passes the callback directly; all authority inputs come from the application service.

## Slice 12C — Reorder and durable start

### Task 12C.1: Implement transactional reorder

**Objective:** Make Room the sole owner of validated playlist order.

**Files:**
- Modify: `ShootPreparationContracts.kt`
- Modify: `ShootPreparationDao.kt`
- Modify: `RoomShootPreparationRepository.kt`
- Modify: `RoomShootPreparationRepositoryAndroidTest.kt`

**TDD steps:**

1. REDs through the existing preparation repository for exact-set reorder, duplicate/missing/foreign IDs, 2/21 entries, deleting shoot, active session, unresolved import, late constraint rollback, exact replay, and two-connection concurrency.
2. Require the complete current committed validated pose-ID set.
3. Move to collision-safe temporary negative indices inside one transaction, then assign `0..n-1` and verify exact postcondition.
4. Reject negative/corrupt persisted indices before mutation; never normalize them.
5. Observe reordered state after close/reopen.

**Acceptance:** No partial order escapes any failure; import history remains unchanged.

### Task 12C.2: Implement eligibility and idempotent durable start

**Objective:** Atomically create one active session only for a complete safe playlist.

**Files:**
- Modify: `ShootPreparationContracts.kt`
- Modify: `ShootPreparationDao.kt`
- Modify: `RoomShootPreparationRepository.kt`
- Modify: `RoomShootPreparationRepositoryAndroidTest.kt`

**TDD steps:**

1. REDs for 0/2/3/20/21-corrupt references, gaps, nonvalidated row, unresolved import, deleting shoot, exact replay, conflicting replay, existing active session, two-connection one-winner start, and trigger-enforced direct SQL.
2. Require exactly 3–20 contiguous validated active poses and no unresolved import work.
3. Insert `ShootSessionEntity(sessionId=startToken, currentPoseIndex=0, nextAttemptNumber=0, ACTIVE, injected timestamps)` in one transaction.
4. Return `Started`, exact `AlreadyStarted`, or typed ineligibility/conflict; verify persisted postcondition before return.
5. Start does not initialize camera, TTS, capture, or MediaStore.

**Acceptance:** A successful/identical replay yields one durable active session; a competing start cannot create another.

## Slice 12D — Accessible Compose preparation workflow

### Task 12D.1: Add maintained UI dependencies and pure state reduction

**Objective:** Establish testable navigation/ViewModel surfaces without coupling UI to Room.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorViewModel.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorViewModelTest.kt`

**Dependencies:** Pin stable Navigation Compose, lifecycle ViewModel Compose, and Compose UI test artifacts. Do not add analytics, networking, image-loader, drag-and-drop, or DI frameworks.

**TDD steps:** RED/GREEN loading, empty, content, importing, cancelled, rejected, retryable, reconciliation-required, reorder pending/failure, start ineligibility, start success, and stale async callback suppression.

### Task 12D.2: Replace camera-first root with navigation

**Objective:** Keep camera permission and camera construction behind durable start.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/ui/navigation/AppNavHost.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/ui/shoots/ShootListScreen.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/ui/App.kt`
- Add host/source contract tests

**TDD steps:**

1. Add a source/Compose RED proving root contains no permission launcher or `CameraXController` before the started-session route.
2. Render loading, retry, empty, create, and shoot-summary states.
3. Navigate list → editor without camera permission.
4. Reuse the existing camera permission/live screen only after a durable start result.

### Task 12D.3: Build editor and Photo Picker callback path

**Objective:** Deliver create → import → validate → reorder → start with accessible controls.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorScreen.kt`
- Modify: `ShootEditorViewModel.kt`
- Create: `app/src/androidTest/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorFlowTest.kt`

**TDD steps:**

1. Render reference count, validation label, mirror setting, terminal rejection, unresolved/retry state, and start eligibility in text/semantics—not color alone.
2. Use the system Photo Picker launcher. Keep callback URI as a local callback value and immediately pass it to the application service.
3. Use accessible move-up/move-down actions; drag may be added later only as a redundant affordance.
4. Disable and explain Start outside 3–20 or while unresolved work exists.
5. After `Started`/exact `AlreadyStarted`, navigate to the existing camera diagnostic route.

### Task 12D.4: Verify the complete bounded flow

**Host gates:**

```sh
./gradlew --rerun-tasks :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

**Required evidence:**

- exact JVM count with zero failure/error/skip;
- V1 and V2 schema hashes unchanged; V3 artifact exact;
- no `INTERNET`, storage, audio, location, or foreground-service permission;
- no network/analytics dependency;
- no provider URI/private path/raw landmark logging;
- instrumentation compilation only until device authorization;
- exact staged candidate manifest/digest;
- independent specification PASS then quality/security PASS on identical bytes.

**Device gate requiring separate authorization:**

- V1→V2→V3 and V2→V3 migration runtime;
- one-active-session triggers after reopen;
- create/import/reject-replace/reorder/start persistence;
- system Photo Picker cancel/success callback;
- no camera permission prompt before durable start;
- camera diagnostic reached after durable start;
- database/file/test-package cleanup and no private residue.

## Commit strategy

Use bounded reviewed commits rather than one mixed UI/schema commit:

1. `feat: add Room V3 shoot preparation authority` — Slices 12A–12C, exact-digest review and authorized migration gate before landing.
2. `feat: add pose playlist editor` — Slice 12D, exact-digest review and separately authorized UI/device gate before landing.
3. `docs: close shoot preparation milestone` — synchronize README, product, architecture, privacy, testing, development, ADR status, and main implementation plan after both feature commits.

A rejection at any review or device gate invalidates that exact candidate. Do not commit a plausible subset or carry approval across changed bytes.
