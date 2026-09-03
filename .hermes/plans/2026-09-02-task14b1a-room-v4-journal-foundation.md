# Task 14B.1A Room V4 Journal Foundation Implementation Plan

> **For Hermes:** Implement only after user sign-off. Use behavioral TDD. Do not implement Task 14B.1B or later work in this candidate.

**Goal:** Add the Room V4 capture-file journal, atomically seed it at registration, adopt it in bootstrap and logical attempt start, and fail closed before confirmation—without exposing per-file effect admission or enabling camera/filesystem behavior.

**Architecture:** `capture_file_operations` becomes durable intent authority at registration. A renamed logical-start API validates the exact initial journal before moving an attempt to `CAPTURING`, but it grants no physical capability. Guided bootstrap reads the journal as its ninth authority family. Confirmation of unfinished attempts is deliberately blocked until the later journal-derived confirmation stage exists.

**Tech stack:** Kotlin, Room 2.8.4, SQLite triggers, JUnit 4, AndroidJUnitRunner, Gradle/KSP exported schemas.

**Baseline:** `main` at `6997861487d3d7dd31c21b9a947d1ccde3b2f418`.

---

## Boundary

### Included

1. Additive Room V4 schema and real V3->V4 migration.
2. `capture_file_operations` entity, DAO reads/inserts, exact contracts, deterministic paths, UTF-16 validation, and callback-installed triggers.
3. Atomic `registerCaptureAttempt(...)` seeding of one attempt plus exactly three journal rows plus one counter CAS.
4. Rename `authorizeCaptureStart(...)` to `markCaptureAttemptStarted(...)`; validate exactly three initial journal rows before `REGISTERED -> CAPTURING`.
5. Guided-session bootstrap ninth read and lifecycle/cardinality validation.
6. Close deletion's pre-V4 causal clock across every existing authority family first, then add the journal losslessly in a separate subtask. Stable `EXPECTING_RESERVATION` rows do not interlock deletion.
7. Fail closed when `confirmAndAdvance(...)` targets any unfinished attempt. Existing already-confirmed immutable replay may remain readable only with zero transient journal rows.
8. V4 schema artifact, host tests, build gates, and bounded real-Room tests only after separate device authorization.
9. Documentation of the non-authorizing logical-start contract and fail-closed milestone.

### Excluded

- No production per-file stage transition or reconciliation API.
- No durable physical-effect admission.
- No deletion in-flight-effect interlock.
- No journal-derived confirmation or caller-output API removal.
- No CameraX, filesystem, MediaStore, UI, reducer, coordinator, navigation, speech, or network change.
- No real shutter, file reservation/write/rename/delete/quarantine, directory scan, or personal-media access.
- No device command without a new exact-run authorization.

### Shippability limit

This candidate intentionally leaves the new flow unable to confirm:

- new registration can create durable journal intent;
- logical start can mark the attempt `CAPTURING`;
- no production API can advance a journal row out of `EXPECTING_RESERVATION`;
- confirmation of every unfinished attempt returns a closed rejection without mutation;
- no production composition currently calls registration, logical start, or confirmation.

The stage is safe to land but not user-facing capture functionality.

---

## Frozen V4 contract

### Table

Create `capture_file_operations` with this exact ordered shape:

1. `command_token TEXT NOT NULL`
2. `burst_ordinal INTEGER NOT NULL`
3. `relative_final_path TEXT NOT NULL`
4. `relative_temp_path TEXT NOT NULL`
5. `relative_quarantine_path TEXT NOT NULL`
6. `stage TEXT NOT NULL`
7. `byte_count INTEGER NULL`
8. `sha256 TEXT NULL`
9. `captured_at_epoch_millis INTEGER NULL`
10. `last_failure_code TEXT NULL`
11. `reconciliation_required INTEGER NOT NULL`
12. `created_at_epoch_millis INTEGER NOT NULL`
13. `updated_at_epoch_millis INTEGER NOT NULL`

Authority constraints:

- PK `(command_token, burst_ordinal)`.
- FK `command_token -> capture_attempts(command_token)`, `ON DELETE RESTRICT`, `ON UPDATE NO ACTION`.
- indexes on `stage` and `reconciliation_required`.
- ordinal integer `0..2` trigger coverage.
- callback-installed INSERT/UPDATE shape triggers pin SQLite storage classes, enum/failure values, Boolean `0/1`, all-null/all-present evidence, positive count, lowercase SHA-256, stage/evidence compatibility, `created <= captured <= updated`, immutable identity/path/created fields, and strictly increasing real-update timestamps.
- every textual authority field, including nullable SHA and failure code, must have SQLite `text` storage when present.

The table uses the complete future stage vocabulary so V4 does not need another schema migration merely to enable Task 14B.1B. This task writes only `EXPECTING_RESERVATION`.

### Paths and token encoding

For lowercase `H = SHA-256(well-formed command-token UTF-8)` and ordinal `o`:

- `capture-candidates/H-o.jpg`
- `capture-candidates/.H-o.jpg.pending`
- `capture-quarantine/H-o.quarantined`

Use one indexed `isWellFormedUtf16(String)` validator before UTF-8 encoding:

- every high surrogate must be immediately followed by one low surrogate;
- no low surrogate may appear alone;
- valid BMP, supplementary/non-BMP pairs, Unicode, and path-like opaque tokens remain valid;
- direct path construction rejects malformed input with a fixed value-free contract error;
- registration maps the same invalidity before opening a Room transaction to `AttemptRegistrationRejectionReason.INVALID_COMMAND_TOKEN_ENCODING`.

No error, result, `toString`, or test output may include the raw token or generated paths.

### Logical start semantics

Replace:

```kotlin
authorizeCaptureStart(...): CaptureStartAuthorizationResult
```

with:

```kotlin
markCaptureAttemptStarted(...): CaptureAttemptStartResult
```

There are no production callers to preserve. Update all test callers and add a static source contract proving the old authorization-shaped API is absent.

`CaptureAttemptStartResult.Started` means only that the Room lifecycle transition committed. It is not authority to call CameraX or touch the filesystem.

Serialization contract:

- deletion first -> logical start returns `BlockedByDeletion` without mutation;
- logical start first -> `Started`, then deletion may return `Began` because all rows are stable `EXPECTING_RESERVATION`;
- after that two-success ordering, the attempt remains persisted for reconciliation/deletion handling and no physical effect was admitted;
- do not claim exactly one winner between logical start and deletion.

### Confirmation guard

Until Task 14B.1C:

- any `REGISTERED` or `CAPTURING` attempt returns `Rejected(JOURNAL_CONFIRMATION_NOT_AVAILABLE)` without consuming caller output or mutating Room, whether its journal is coherent, absent, partial, or malformed;
- this guard executes inside the confirmation transaction after exact attempt resolution and before any output/receipt/outbox/session mutation;
- migrated unfinished V3 attempts therefore cannot bypass the V4 authority model;
- an already-confirmed replay may use the existing immutable confirmation graph only when it is coherent and no transient journal rows exist;
- confirmed-plus-residual journal authority returns `JOURNAL_AUTHORITY_INVALID` without mutation;
- the existing caller-supplied private-output parameter remains temporarily compile-compatible but is never authoritative for a new confirmation in this stage;
- Task 14B.1C must remove that parameter when journal-derived confirmation is implemented.

This is an explicit fail-closed milestone, not a claim that confirmation works.

---

## Task 1: Compile-safe V4 contracts and schema

**Create:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/CaptureFileOperationContracts.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationEntity.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationStateTriggers.kt`
- `app/src/test/java/com/tonyisup/poseguidesnap/data/CaptureFileOperationContractTest.kt`
- `app/src/test/java/com/tonyisup/poseguidesnap/data/CaptureFileOperationStateTriggersTest.kt`
- `app/src/test/java/com/tonyisup/poseguidesnap/data/AppDatabaseV4SchemaArtifactTest.kt`
- `app/schemas/com.tonyisup.poseguidesnap.data.db.AppDatabase/4.json`

**Modify:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/AppDatabase.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/AuthorityOrdinalTriggers.kt`
- entity redaction/numeric-width host tests.

**TDD sequence:**

1. Add final compile-safe declarations, V4 entity/DAO membership, `MIGRATION_3_4`, and database callback registration. Compile and generate the V4 artifact before calling a missing artifact a test failure.
2. RED path/UTF-16 tests. Expected missing behavior: two distinct lone-surrogate strings do not receive the fixed rejection; valid supplementary Unicode is the positive control.
3. GREEN with the shared indexed validator and deterministic path generator.
4. RED exact entity/contract redaction and numeric-width tests; GREEN without exposing values.
5. RED full trigger SQL and V4 schema-artifact tests; GREEN with exact frozen shape.
6. Preserve V1-V3 schema files byte-for-byte.

**Required host methods:**

- `CaptureFileOperationContractTest.wellFormedUtf16AcceptsBmpSupplementaryUnicodeAndPathLikeTokens`
- `CaptureFileOperationContractTest.distinctLoneSurrogatesRejectBeforeHashingWithoutValueLeak`
- `CaptureFileOperationContractTest.deterministicPathsAreStableDistinctAndNormalized`
- `CaptureFileOperationContractTest.publicContractsRenderWithoutTokenOrPaths`
- `CaptureFileOperationStateTriggersTest.insertAndUpdateSqlPinsEveryStorageAndShapeRule`
- `AppDatabaseV4SchemaArtifactTest.schemaV4MatchesFrozenCaptureFileOperationContract`

Remove stale XML, run all six exact methods, and parse fresh XML to require every exact `(suite, method)` pair—not an aggregate count.

---

## Task 2: Atomic registration seeding

**Modify:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/CaptureAttemptContracts.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureAttemptDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootRepository.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/RoomShootRepositoryAndroidTest.kt`

**Compile-safe RED:** Add final `INVALID_COMMAND_TOKEN_ENCODING` and `JOURNAL_AUTHORITY_INVALID` values and compile. Add a deliberately incomplete insert surface that performs no journal mutation.

**First RED:** A valid registration expects exactly three ordered rows but observes zero while attempt/counter behavior remains current.

**GREEN:** In one Room transaction:

1. validate opaque token encoding before transaction entry;
2. validate registration time is nondecreasing relative to owning session/shoot authority;
3. claim the attempt number with existing CAS protection;
4. insert the attempt;
5. derive and insert ordinals `0,1,2` at `EXPECTING_RESERVATION`, null evidence/failure, reconciliation `0`, and matching registration timestamps;
6. assert exact insert/cardinality postconditions;
7. roll back everything on any failure.

Duplicate registration is informational only when the attempt and its exact three journal rows are coherent. Missing/partial/conflicting rows return `JOURNAL_AUTHORITY_INVALID` without mutation.

**Required Android methods, compile only until authorized:**

- `registerCaptureAttemptAtomicallySeedsExactlyThreeCaptureFileOperations`
- `registrationRejectsDistinctIllFormedUtf16TokensWithoutMutation`
- `registrationRejectsBackwardAndAcceptsEqualOwningSessionTimestamp`
- `journalInsertFailureRollsBackAttemptRowsAndCounter`
- `concurrentDuplicateRegistrationSeedsOneCompleteJournal`
- `registrationReplayRejectsMissingPartialOrConflictingJournal`

Use UUID databases, no printed identifiers/paths, exact teardown, and test-only fault triggers/connections—no production writer hooks.

---

## Task 3: Bootstrap, logical start, split deletion clocks, and confirmation guard

**Create:**

- `app/src/test/java/com/tonyisup/poseguidesnap/data/GuidedSessionDaoTransactionContractTest.kt`
- `app/src/test/java/com/tonyisup/poseguidesnap/data/CaptureAttemptStartContractTest.kt`

**Modify:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/CaptureAttemptContracts.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureAttemptDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootRepository.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/GuidedSessionDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/GuidedSessionContracts.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/GuidedSessionBootstrapMapper.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/DeletionExportDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/DeletionExportContracts.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/CaptureConfirmationContracts.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationDao.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/AppDatabaseMigrationAndroidTest.kt`
- affected host and Android tests.

**TDD families and independently reviewable checkpoints:**

### Task 3A: Non-authorizing logical start

1. Rename logical-start API/contracts and prove the old authorization name is absent.
2. RED missing-row logical start: expected `Rejected(JOURNAL_AUTHORITY_INVALID)`, current logical start succeeds.
3. GREEN one-transaction journal validation plus lifecycle CAS.
4. RED/green nondecreasing start timestamp, coherent replay, and migrated V3 missing-journal rejection.

### Task 3B: Ninth-family bootstrap validation

1. RED bootstrap with residual confirmed journal and partial active journal; current mapper can return `Ready`/ordinary reconciliation.
2. GREEN ninth same-transaction query, shared validator, exact session join/order, and lifecycle cardinality rules.

### Task 3C.1: Complete the existing deletion-authority causal clock

This checkpoint fixes the stale premise discovered after Task 3B: current deletion has no complete causal-clock maximum even before the V4 journal is considered. Do not add the journal to deletion in this checkpoint.

1. RED the current public `beginShootDeletion(...)` boundary with a nonnegative request timestamp older than each existing deletion-authority clock family loaded by the transaction: shoot, sessions, attempts, private outputs, confirmation receipts, export outboxes, and export outputs. Each case must return typed `Rejected(INVALID_TIMESTAMP)` without mutation; the RED must fail because current deletion either commits or escapes through an untyped storage/trigger failure.
2. GREEN by validating every applicable creation, update, capture, confirmation/application, and settlement clock in those existing families before the first write. Reject malformed, negative, backward-within-row, or request-before-authority clocks without normalizing values.
3. Reuse the exact pre-write snapshots already loaded for deletion; do not add an aggregate-only SQL `MAX(...)` that can hide malformed rows or detach the clock from the authority validated later in the transaction.
4. Preserve replay semantics: an already-`DELETING` shoot remains immutable `AlreadyDeleting`, while an `ACTIVE` shoot may begin only when `requestedAtEpochMillis` is greater than or equal to the complete validated pre-V4 maximum.
5. Require one-at-a-time clock-family fixtures plus an equal-to-maximum positive control and a postcondition proving every authority family remains unchanged except the shoot and the already-supported cancellable export rows/outboxes.

### Task 3C.2: Add the V4 journal to deletion losslessly

Only after Task 3C.1 passes both reviews:

1. RED a newer stable `EXPECTING_RESERVATION` journal row: deletion must return typed `Rejected(INVALID_TIMESTAMP)` without mutation.
2. GREEN by loading all capture-file journal rows owned by the shoot inside the same deletion transaction, validating their canonical storage, ownership, cardinality, paths, stage/evidence/failure/reconciliation shape, and causal clocks with the shared journal validator.
3. Include journal `created_at_epoch_millis`, `updated_at_epoch_millis`, and present `captured_at_epoch_millis` in the complete maximum without filtering malformed residual rows out of the authority snapshot.
4. Keep coherent `EXPECTING_RESERVATION` rows non-blocking and byte-for-byte unchanged. No journal row may transition, disappear, or grant physical authority during deletion.
5. RED safe serialization: logical start commits and deletion is expected to commit too; do not manufacture one-winner behavior. The two-success test must prove all rows remain `EXPECTING_RESERVATION`, no output/receipt/outbox exists, and no physical-effect authority was created.
6. Add an in-transaction mutation/postcondition test proving journal drift rolls the entire deletion transaction back.

### Task 3D: Fail-closed confirmation guard

1. RED unfinished confirmation expects `JOURNAL_CONFIRMATION_NOT_AVAILABLE`; current caller-owned confirmation mutates outputs/session.
2. GREEN performs exact command-token attempt lookup and token/pose matching inside the transaction, then returns journal-unavailable unconditionally for `REGISTERED` and `CAPTURING`. That rejection precedes caller-list element traversal, receipt lookup, journal reads, session/shoot loads, deletion classification, broader-authority reads, and writes. On the `CONFIRMED` path, caller snapshots and validation retain their existing precedence before receipt and residual-journal adjudication. Any residual journal row returns `JOURNAL_AUTHORITY_INVALID`; only a coherent receipt-backed confirmed replay with zero journal rows may return immutable `AlreadyApplied`.
3. Preserve existing rollback/replay evidence deliberately: seed coherent confirmed graphs directly in tests; keep duplicate and immutable-corruption replay tests active; mark first-application and writer-fault cases explicitly deferred to Task 14B.1C rather than deleting them.

**Required host methods:**

- three exact bootstrap mapper methods for coherent/missing/partial/residual authority;
- two exact DAO transaction/source methods for ninth read, shared `@Transaction`, exact join, and order;
- `CaptureAttemptStartContractTest.logicalStartNameAndResultMakeNoPhysicalAuthorizationClaim`;
- `CaptureAttemptStartContractTest.oldCaptureAuthorizationApiIsAbsent`.

Fresh XML must contain every exact method.

**Required Android methods, compile only until authorized:**

- `markCaptureAttemptStartedRejectsMissingPartialOrCorruptInitialJournal`
- `markCaptureAttemptStartedEnforcesNondecreasingTimestamp`
- `deletionFirstBlocksLogicalStartWithoutMutation`
- `logicalStartThenDeletionCanBothCommitBeforeAnyFileEffectAdmission`
- `alreadyStartedRequiresCoherentJournal`
- `deletionRejectsRequestBehindEachExistingAuthorityClockWithoutMutation`
- `deletionAcceptsRequestEqualToCompleteExistingAuthorityClockMaximum`
- `deletionClockIncludesStableCaptureFileJournalAuthority`
- `deletionPreservesStableCaptureFileJournalAuthorityByteForByte`
- `deletionJournalPostconditionDriftRollsBackEverything`
- `unfinishedConfirmationIsUnavailableWithoutMutation`
- `confirmedReplayRejectsResidualJournalAuthority`
- migrated V3 `REGISTERED` and `CAPTURING` attempts return journal-unavailable without caller-list traversal or mutation;
- a coherent migrated V3 confirmed graph with zero journal rows retains `AlreadyApplied`.

Task 3C.1 and Task 3C.2 are separate review/acceptance boundaries. A passing journal-only test may not substitute for Task 3C.1's complete pre-V4 graph closure. Do not begin Task 3C.2 until the exact Task 3C.1 bytes pass specification and engineering/security review.

---

## Task 4: Real V3->V4 runtime evidence

**Modify:**

- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/AppDatabaseMigrationAndroidTest.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/AppDatabaseAndroidTest.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/GuidedSessionPacket2BAndroidTest.kt`

**Required evidence:**

- direct V3->V4 and chained V1->V2->V3->V4;
- exact preservation of every seeded preexisting value, null, cardinality, and SQLite `typeof(...)` result;
- new table empty after migration;
- migrated unfinished `REGISTERED` and `CAPTURING` attempts fail closed at confirmation without traversing caller lists or mutating authority, while a coherent migrated confirmed graph with zero journal rows retains immutable `AlreadyApplied` replay;
- exact columns, PK, FK, indexes; empty `foreign_key_check`;
- trigger rejection of malformed storage classes and shapes before and after reopen;
- restrictive attempt deletion while journal children exist;
- Packet 2B uses a V4 test database and includes the ninth family in its complete snapshot/digest;
- exact UUID database/WAL/SHM/journal/lock cleanup.

**Closed 2026-09-03:** exact candidate `~/.hermes/cache/task4-candidate-v5.patch` at SHA-256 `377dc02c781ece2cf78e48f93c727d02ea9d41082a12229ff22803e97a306491` passed specification and engineering/security review without byte drift. Verification: Android-test compilation passed; JVM `635/635`; focused Pixel 6 snapshot-oracle and direct-migration methods `2/2`; migration class `10/10`; integrated five-class Pixel 6 suite `105` tests, `0` failures, `0` errors, `10` expected `@Ignore` skips. The structural snapshot encodes TEXT/BLOB losslessly with `hex()` while preserving `typeof(...)`, including an embedded-NUL non-aliasing regression. No files were staged, committed, pushed, reset, or deployed.

Compile instrumentation now. Ask separately before any Pixel command, naming the exact APKs, methods, device, and cleanup.

---

## Task 5: Cumulative gates, documentation, and landing

**Documentation:**

- update the master MVP plan to show 14B split into 14B.1A/1B/1C;
- create ADR `docs/adr/0006-room-v4-capture-file-journal-foundation.md`;
- update README, product, architecture, testing, privacy, and development docs only where current-state claims change;
- state explicitly that `Started` is logical Room state, no per-file effect admission API exists, unfinished confirmation is blocked, and no camera/filesystem behavior is implemented or proven.

**Host/build gates:**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Then prove:

- V1-V3 schemas are byte-identical and regenerated V4 is byte-identical;
- no Room compiler on runtime classpaths or packaged APK;
- no `INTERNET` permission;
- no camera/UI/reducer/coordinator production file changed;
- no old `authorizeCaptureStart` API remains;
- no per-file production transition method exists;
- no credentials or sensitive runtime values appear in logs/docs/tests;
- whitespace and closed file-scope checks pass.

**Final-review scope discrepancy:** the original no-UI-file bullet is not literally true. The already reviewed Task 3B checkpoint added one exhaustive mapping arm in `ui/session/StartedSessionBootstrapWorkflow.kt` so `INVALID_CAPTURE_FILE_OPERATION_AUTHORITY` follows the existing fail-closed corrupt-state path. That line introduces no route, control, reducer/coordinator transition, CameraX/filesystem call, or user-facing capture behavior. Do not silently waive this mismatch: the complete-candidate specification reviewer must approve it as a compile-adapter-only exception or reject the candidate.

**Task 5 documentation-review remediation:** documentation candidate v1 at SHA-256 `61153394876c5fbc04655e2bf6033a230758998c8bb9e084039fca44985ea205` was rejected because the master status overstated Task 4's approval scope and production copied caller lists before the unfinished-confirmation guard. Repair v1 at SHA-256 `5a50a69780c7dec93e683ff4b68ff5f3eb6ee417969eaa5b8242b007d7d4081d` was specification-rejected for a receipt-first bypass. Repair v2 at SHA-256 `d31b524a27d090e254b5f76d7a479d35619696b48df2cae6103efe5197c70a8a` was specification-rejected because its nullable-timestamp guard did not enforce unfinished lifecycle authority. Repair v3 now performs exact command-token attempt lookup and token/pose matching before unconditional `REGISTERED`/`CAPTURING` rejection, which precedes caller-list element traversal, receipt lookup, journal reads, session/shoot loads, deletion classification, broader-authority reads, and writes. On the `CONFIRMED` path, caller snapshot/validation still precedes receipt and residual-journal adjudication, and any residual journal row fails closed with `JOURNAL_AUTHORITY_INVALID`. The final eight-scenario Pixel 6 method covers both unfinished lifecycles with null or malformed non-null timestamps, each with and without a raw receipt. It failed on v2 (`expected 0`, observed 3 reads) and passed `1/1` on v3. Repair v3, SHA-256 `ed29acd613a890d213e398aaacf4a2d79512662ac0dbf88c411404fcfdb3bd3a`, received exact-byte specification `PASS` and engineering/security `APPROVED`; that approval covers only the two-file repair, not the complete landing candidate. The post-v3 cumulative host gate passed all five Gradle tasks, JVM `635/635`, and lint with zero errors. The current-byte integrated five-class Pixel 6 run executed `106` tests with `0` failures, `0` errors, and `10` expected skips, and exact UUID teardown left no matching test-database residue.

**Landing:** Stage one complete 14B.1A candidate. Compute its exact staged digest. Require specification `PASS` and engineering/security `APPROVED` on those same bytes. Any change invalidates both. Commit and push only after both gates pass.

Suggested subject:

```text
feat: add fail-closed Room capture journal foundation
```

---

## Exit criteria

Task 14B.1A is complete only when:

- every new attempt is atomically paired with exactly three initial journal rows;
- malformed UTF-16 cannot reach hashing or persistence;
- V3 migration preserves existing authority and fabricates no journal rows;
- bootstrap reads and validates the journal as its ninth authority family;
- logical start is explicitly non-authorizing and requires coherent initial rows;
- logical start followed by deletion is proven safe when no file effect was admitted;
- deletion timestamps cannot move backward relative to stable journal authority;
- unfinished confirmation cannot consume caller-selected metadata or mutate authority;
- no production per-file transition, physical effect, or user-facing capture path exists;
- exact candidate bytes pass both reviews before commit/push.

## Approval requested

Approval of this plan authorizes host-side implementation and non-device builds only. It does not authorize Pixel/device execution, camera use, filesystem effects, personal-media access, commit, or push without the later gates stated above.
