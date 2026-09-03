# REJECTED — Task 14B.1 Room V4 Capture-File Journal Authority Implementation Plan

> **Historical artifact only. Do not execute.** Exact reviewed source digest before this rejection banner was `5ba07c22c14b3f777f26faabc93cb5b0c11a464986f1da483c555f7e414e5974`. Four exact-plan review rounds exposed a false capture-start/deletion one-winner claim. The replacement architecture is documented in `2026-09-02-task14b-room-v4-authority-reset.md` and split into independently landable stages.

**Goal:** Transfer all pre-confirmation capture-file metadata and progression authority to Room, from attempt registration through capture-start admission and per-file stage transitions to atomic confirmation consumption, without enabling camera or filesystem behavior.

**Architecture:** Add an additive Room V4 `capture_file_operations` table keyed by `(command_token, burst_ordinal)`. The existing attempt-registration transaction creates exactly three deterministic journal rows before the attempt becomes reachable; capture start requires a coherent initial journal; a Room journal API owns per-ordinal CAS transitions; deletion cannot cross an admitted-but-unsettled file effect; and confirmation derives immutable private-output rows from exactly three coherent `FINAL_DURABLE` journal rows, then deletes those transient rows in the same confirmation transaction. Callback-installed row-shape triggers preserve SQLite storage classes before Room mapping. Existing V3 rows are preserved value-for-value and no journal rows are synthesized.

**Tech stack:** Kotlin, Android Room 2.8.4, SQLite triggers, JUnit 4 host tests, AndroidJUnitRunner instrumentation, Gradle/KSP exported schemas.

**Baseline:** clean `main` at `6997861487d3d7dd31c21b9a947d1ccde3b2f418` (`feat: add stale-safe guided session resume`). Focused pre-edit host baseline: 35/35 across `CaptureAttemptPolicyTest`, `PrivateCaptureFilePublisherTest`, `AppDatabaseV3SchemaArtifactTest`, and `AuthorityEntityCounterTypeTest`.

---

## Non-negotiable boundary

### Included

1. Additive Room V4 schema and real V3→V4 migration.
2. Capture-specific closed journal contracts, deterministic relative paths, entity, DAO, and Room API.
3. Atomic creation of exactly three journal rows inside `registerCaptureAttempt(...)`.
4. Journal-aware `authorizeCaptureStart(...)`.
5. Per-ordinal stage/evidence/reconciliation CAS while the owning attempt/session/shoot remains active at the same nonnegative deletion generation.
6. A deletion admission interlock: `beginShootDeletion(...)` must reject without mutation while any journal row is in an admitted-but-unsettled external-effect stage.
7. `confirmAndAdvance(...)` derives private output authority from exactly three ordered `FINAL_DURABLE` rows and atomically consumes those rows when immutable confirmed rows/receipt/outbox replace them.
8. Host, schema, migration, runtime Room, redaction, concurrency/rollback, deletion-race, and documentation coverage.

### Excluded

- No changes under `camera/`, `ui/`, reducer/session behavior, navigation, or Compose.
- No CameraX invocation, shutter, capture callback, app-private file create/write/rename/delete/quarantine, directory scan, or startup filesystem reconciliation.
- No guided-session coordinator.
- No MediaStore create/update/delete or export-state transition.
- No attempt retry/reset, `FAILED_CLEANED`, or attempt lifecycle `RECONCILIATION_REQUIRED`; those belong to Task 14B.2 settlement/restart recovery. Task 14B.1 only blocks deletion while a previously admitted file effect lacks a durable outcome; it does not execute or resolve that effect.
- No speech/TTS/audio, network, analytics, location, Bluetooth, foreground service, or personal-data access.
- No device command without a separate exact-run authorization.

### Safety claims and non-claims

- Room owns intended paths, exact per-ordinal progress, evidence, and confirmation consumption.
- A persisted path is authority only for the exact journal row that stores it; filenames never recreate authority.
- This packet does not prove or perform any physical filesystem effect.
- `CaptureFileJournalResult.Applied` means a Room state transition committed; it does not claim bytes were physically written, synced, renamed, deleted, or quarantined.
- Only a future journaled adapter may produce physical observations, and only a future coordinator may order adapter calls against these CAS transitions.

---

## Frozen schema and contract

### Table: `capture_file_operations`

Columns in exact order:

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

Constraints:

- Primary key: `(command_token, burst_ordinal)`.
- Restrictive FK: `command_token → capture_attempts(command_token)`, `ON DELETE RESTRICT`, `ON UPDATE NO ACTION`.
- Exact non-unique indexes:
  - `index_capture_file_operations_stage(stage)`
  - `index_capture_file_operations_reconciliation_required(reconciliation_required)`
- Extend `AuthorityOrdinalTriggers` with INSERT and UPDATE ordinal triggers for this table. Both must require `typeof(NEW.burst_ordinal) = 'integer'` and `0 <= burst_ordinal <= 2`.
- Add callback-installed `CaptureFileOperationStateTriggers` INSERT and UPDATE triggers. They must reject before Room mapping unless:
  - token, all three paths, and stage have SQLite storage class `text`;
  - stage is one exact closed value;
  - nullable `byte_count` and `captured_at_epoch_millis` have storage class `integer` when present;
  - nullable `sha256` must satisfy `NEW.sha256 IS NULL OR typeof(NEW.sha256) = 'text'`, and nullable `last_failure_code` must likewise have storage class `text` when present;
  - `reconciliation_required` has storage class `integer` and value `0` or `1`;
  - created/updated timestamps have storage class `integer`, are nonnegative, and are ordered;
  - when evidence exists, `created_at_epoch_millis <= captured_at_epoch_millis <= updated_at_epoch_millis`;
  - the evidence bundle is all-null or all-present, with positive byte count and canonical lowercase SHA-256;
  - stage/evidence optionality follows the frozen matrix below; and
  - `last_failure_code` is null or one closed value and is non-null exactly when reconciliation is `1`.
- The UPDATE trigger additionally freezes `command_token`, `burst_ordinal`, all three paths, and `created_at_epoch_millis`, and requires `NEW.updated_at_epoch_millis > OLD.updated_at_epoch_millis`. Idempotent replays perform no UPDATE; every real stage/failure/resolution mutation advances time strictly.
- Pin trigger names and full SQL in host tests. Runtime tests must prove direct INSERT/UPDATE corruption is rejected after create and reopen. `CREATE TRIGGER IF NOT EXISTS` is acceptable for this first definition only; a later semantic change must explicitly replace/validate the installed SQL.
- Do not add an FK to confirmed private outputs; journal rows precede confirmation and are consumed when immutable output authority is written.

### Deterministic paths

For `H = lowercase SHA-256(command token UTF-8)` and ordinal `o`:

- final: `capture-candidates/H-o.jpg`
- temp: `capture-candidates/.H-o.jpg.pending`
- quarantine: `capture-quarantine/H-o.quarantined`

Generate these internally from `PrivateOutputIdentity`; never accept caller-selected paths. Capture tokens are opaque and may be path-like or Unicode, so do not apply the shoot/session safe-segment validator. Before UTF-8 encoding, require the JVM `String` to be well-formed UTF-16: every high surrogate must be followed by one low surrogate and no low surrogate may appear alone. Reject before hashing because Java's replacement encoding maps distinct lone surrogates to the same bytes. Valid BMP Unicode and valid surrogate pairs remain accepted. Stored paths must pass the existing normalized private-relative-path contract and must never contain the raw token.

Implement one data-layer `isWellFormedUtf16(value: String)` validator with an indexed `Character.isHighSurrogate`/`isLowSurrogate` scan. `CaptureFileOperationPaths.forIdentity(...)` calls it before encoding and throws only a fixed, value-free contract error on direct misuse. `registerCaptureAttempt(...)` calls the same validator before opening its Room transaction and maps failure to closed `AttemptRegistrationRejectionReason.INVALID_COMMAND_TOKEN_ENCODING`; it must not insert an attempt or journal row or advance the counter.

### Stage vocabulary

```text
EXPECTING_RESERVATION
WRITING_TEMP
TEMP_SYNCED
FINAL_RENAME_PENDING_SYNC
FINAL_DURABLE
CLEANUP_REQUIRED
CLEANUP_PENDING_SYNC
CLEANED_DURABLE
QUARANTINE_REQUIRED
QUARANTINE_PENDING_SYNC
QUARANTINE_DURABLE
```

Legal transitions mirror the proven reference-import journal:

```text
EXPECTING_RESERVATION → WRITING_TEMP
WRITING_TEMP → TEMP_SYNCED
TEMP_SYNCED → FINAL_RENAME_PENDING_SYNC
FINAL_RENAME_PENDING_SYNC → FINAL_DURABLE
TEMP_SYNCED | FINAL_RENAME_PENDING_SYNC | FINAL_DURABLE → QUARANTINE_REQUIRED
QUARANTINE_REQUIRED → QUARANTINE_PENDING_SYNC
QUARANTINE_PENDING_SYNC → QUARANTINE_DURABLE
EXPECTING_RESERVATION | WRITING_TEMP | TEMP_SYNCED |
FINAL_RENAME_PENDING_SYNC | FINAL_DURABLE |
QUARANTINE_REQUIRED | QUARANTINE_PENDING_SYNC → CLEANUP_REQUIRED
CLEANUP_REQUIRED → CLEANUP_PENDING_SYNC
CLEANUP_PENDING_SYNC → CLEANED_DURABLE
```

### Failure vocabulary

```text
RESERVATION_FAILED
WRITE_FAILED
FILE_SYNC_FAILED
RENAME_FAILED
DIRECTORY_SYNC_FAILED
DELETE_FAILED
STATE_MISMATCH
EVIDENCE_MISMATCH
```

### Evidence rules

Treat `(byte_count, sha256, captured_at_epoch_millis)` as one all-or-none evidence bundle.

- `EXPECTING_RESERVATION`, `WRITING_TEMP`, and `CLEANED_DURABLE`: evidence forbidden.
- `TEMP_SYNCED`, `FINAL_RENAME_PENDING_SYNC`, `FINAL_DURABLE`, `QUARANTINE_REQUIRED`, `QUARANTINE_PENDING_SYNC`, and `QUARANTINE_DURABLE`: evidence required.
- `CLEANUP_REQUIRED` and `CLEANUP_PENDING_SYNC`: evidence optional but all-or-none.
- When present: `byte_count > 0`, SHA-256 is exactly 64 lowercase hex characters, and `captured_at_epoch_millis >= 0`.
- `created_at_epoch_millis >= 0`, `updated_at_epoch_millis >= created_at_epoch_millis`; every per-row journal transition timestamp must be strictly greater than the expected prior `updated_at_epoch_millis`.
- Cross-authority timestamps are nondecreasing, because distinct durable events may share one injected wall-clock millisecond: registration must be `>=` the owning session's current `updated_at`; capture authorization must be `>=` attempt registration; the first and every later journal transition must be `>=` capture authorization and still strictly greater than that row's prior update; when evidence is first attached, `captured_at_epoch_millis` must be `>=` authorization and `<=` that transition; confirmation must be `>=` the attempt, owning session, and every final journal `updated_at`/`captured_at` timestamp; deletion must be `>=` every timestamp in the shoot's loaded authority graph that it causally follows or may update. Equality passes at these cross-authority boundaries; one-millisecond-backward values reject without mutation.
- `reconciliation_required == (last_failure_code != null)`.
- A normal successful `advance` clears failure/reconciliation fields.
- Exact failure marking is separate from stage advancement and preserves current evidence.
- Resolution clears failure/reconciliation only against exact token/ordinal/stage/timestamp and otherwise mutates nothing.

### Result family

```text
CaptureFileJournalResult.Applied(snapshot)
CaptureFileJournalResult.Idempotent(snapshot)
CaptureFileJournalResult.BlockedByDeletion
CaptureFileJournalResult.Rejected(reason)
```

Final compile-safe contract shapes:

```kotlin
data class CaptureFileOperationSnapshot(
    val identity: PrivateOutputIdentity,
    val paths: CaptureFileOperationPaths,
    val stage: CaptureFileOperationStage,
    val byteCount: Long?,
    val sha256: String?,
    val capturedAtEpochMillis: Long?,
    val lastFailureCode: CaptureFileFailureCode?,
    val reconciliationRequired: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class GuidedCaptureFileOperationAuthorityRow(
    val commandToken: String,
    val burstOrdinal: Int,
    val relativeFinalPath: String,
    val relativeTempPath: String,
    val relativeQuarantinePath: String,
    val stage: String,
    val byteCount: Long?,
    val sha256: String?,
    val capturedAtEpochMillis: Long?,
    val lastFailureCode: String?,
    val reconciliationRequired: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class CaptureFileAdvanceRequest(
    val identity: PrivateOutputIdentity,
    val expectedStage: CaptureFileOperationStage,
    val expectedUpdatedAtEpochMillis: Long,
    val targetStage: CaptureFileOperationStage,
    val byteCount: Long?,
    val sha256: String?,
    val capturedAtEpochMillis: Long?,
    val transitionedAtEpochMillis: Long,
)

data class CaptureFileReconciliationRequest(
    val identity: PrivateOutputIdentity,
    val expectedStage: CaptureFileOperationStage,
    val expectedUpdatedAtEpochMillis: Long,
    val failureCode: CaptureFileFailureCode,
    val markedAtEpochMillis: Long,
)

data class CaptureFileReconciliationResolutionRequest(
    val identity: PrivateOutputIdentity,
    val expectedStage: CaptureFileOperationStage,
    val expectedUpdatedAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long,
)
```

`GuidedSessionBootstrapRows` adds
`captureFileOperations: Iterable<GuidedCaptureFileOperationAuthorityRow> = emptyList()` and snapshots it into an unmodifiable `List`, exactly like its other authority families. Both types render fixed redacted strings.

Requests do not accept paths, session identity, pose identity, or deletion generation. The repository derives deterministic paths from `PrivateOutputIdentity`, joins the attempt/session/shoot graph by the opaque token, and binds the persisted current pose plus captured/current nonnegative deletion generations in every CAS. This prevents callers from selecting a second ownership graph.

Closed rejection reasons:

```text
INVALID_TIMESTAMP
INVALID_EVIDENCE
UNKNOWN_OPERATION
WRONG_ATTEMPT_STATE
ILLEGAL_TRANSITION
STALE_SNAPSHOT
CONTRADICTORY_STATE
PERSISTED_STATE_INVALID
```

All entities, path objects, requests, snapshots, results, and exceptions must render as fixed type-only or enum-only strings. They must not render tokens, paths, hashes, labels, URIs, or exception text.

---

## Migration rule

`MIGRATION_3_4` is additive:

1. Create the empty `capture_file_operations` table.
2. Create the two exact indexes.
3. Let the shared database callback install the two ordinal triggers.
4. Let the same callback install the two capture-file row-shape triggers.
5. Do not synthesize paths, hashes, capture times, stages, or journal rows for V3 data.
6. Do not rewrite any V3 attempt/session/shoot/output/receipt/outbox row.
7. Preserve V1–V3 schema artifacts byte-for-byte.

Why no legacy rewrite: coherent V3 `CONFIRMED` attempts remain governed by their immutable output/receipt/outbox graph. Migrated `REGISTERED` and `CAPTURING` attempts have no V4 journal authority, so the V4 bootstrap, registration replay, and start paths must reject them as invalid journal authority and await Task 14B.2 recovery rather than returning `Ready`, authorizing capture, or inventing rows.

Required historical schema SHA-256 values:

- V1: `e5eb94f4ff96944cc9de1aa5c2f6e8e326ba5caaa224c46f3247056cb1c33ab8`
- V2: `0c9ea87ccbf1c57a404d4302ca1d8a7713ec934663dd6000df34766487929bbd`
- V3: `53f30c71d5bad0b4efc66075346ffaccc6e69402ae4a904b7c94c5f45ff3ae25`

---

## Task 1: Compile-safe V4 persistence surface

**Objective:** Add the minimum contracts/entity/DAO/database/migration surface needed for meaningful repository REDs; do not yet change registration, start, or confirmation behavior.

**Create:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/CaptureFileOperationContracts.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationEntity.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationStateTriggers.kt`
- `app/src/test/java/com/tonyisup/poseguidesnap/data/CaptureFileOperationContractTest.kt`
- `app/src/test/java/com/tonyisup/poseguidesnap/data/CaptureFileOperationStateTriggersTest.kt`
- `app/src/test/java/com/tonyisup/poseguidesnap/data/AppDatabaseV4SchemaArtifactTest.kt`

**Modify:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/AppDatabase.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/AuthorityOrdinalTriggers.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/CaptureConfirmationContracts.kt` only to expose/reuse the existing path validator; behavior must not change.
- `app/src/main/java/com/tonyisup/poseguidesnap/data/GuidedSessionContracts.kt`: add final redacted `GuidedCaptureFileOperationAuthorityRow`, a default-empty immutable `captureFileOperations` field on `GuidedSessionBootstrapRows`, and `INVALID_CAPTURE_FILE_OPERATION_AUTHORITY`; the mapper/DAO behavior remains deliberately unchanged until Task 3.
- `app/src/main/java/com/tonyisup/poseguidesnap/data/ReferenceImportFileOperationContracts.kt` and the current data-layer SHA consumer only if extracting one shared canonical SHA validator; preserve behavior.
- Existing entity width/redaction/trigger tests.
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/GuidedSessionPacket2BAndroidTest.kt`: advance its test-only `@Database` to V4, include `CaptureFileOperationEntity`, and include the new table in its complete authority digest without changing the immediate-read transaction gate.

**TDD sequence:**

1. Add the compile-safe V4 surface first: final enum/data-class/request signatures, entity, DAO accessor, `MIGRATION_3_4`, database version/entity registration, and a generated V4 schema artifact whose new table has no migrated rows. Keep ownership behavior deliberately incomplete: `isWellFormedUtf16(...)` returns `true`; `CaptureFileOperationPaths.forIdentity(...)` returns fixed safe placeholder paths; `hasValidCaptureFileOperationEvidence(...)` returns `false`; and `CaptureFileOperationStateTriggers.definitions` is empty. Do not change registration/start/confirmation.
2. Run `:app:kspDebugKotlin` and `:app:assembleDebugAndroidTest`; missing symbols, annotation failures, or absent schema files are setup errors and must be fixed before RED.
3. Add host tests for known-digest deterministic paths, path-like/Unicode token controls, valid astral surrogate-pair acceptance, distinct lone-high and lone-low surrogate rejection before hashing, no token leakage, exact stage/failure vocabularies, exhaustive evidence matrix, redaction, numeric getter widths, exact ordinal/state-trigger definitions, and V4 schema shape.
4. Run the focused host tests. Required genuine REDs are assertion failures: expected deterministic digest paths but got placeholders; expected each malformed surrogate token to throw the fixed invalid-encoding contract error but path derivation returned a placeholder; expected a valid evidence vector but got `false`; expected two state-trigger definitions but got zero. The V4 artifact assertions may already pass and are not the RED.
5. Implement only the deterministic path/evidence/trigger behavior needed for GREEN; install both trigger families in the shared callback.
6. Regenerate V4 and pin exact artifact assertions: 11 tables, exact column order/nullability/affinity, PK/FK, exact index sets for every table, and V1–V3 hash preservation.
7. Run focused host tests GREEN and recompile instrumentation.

**Commands:**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
python3 -c "import shutil; shutil.rmtree('app/build/test-results/testDebugUnitTest', ignore_errors=True)"
./gradlew :app:testDebugUnitTest --tests '*CaptureFileOperationContractTest' --tests '*AppDatabaseV4SchemaArtifactTest' --tests '*AuthorityEntityCounterTypeTest' --tests '*AuthorityEntityToStringTest' --tests '*AuthorityOrdinalTriggersTest' --tests '*CaptureFileOperationStateTriggersTest'
./gradlew :app:kspDebugKotlin
./gradlew :app:assembleDebugAndroidTest
python3 -c "import glob,xml.etree.ElementTree as E; want={'com.tonyisup.poseguidesnap.data.CaptureFileOperationContractTest','com.tonyisup.poseguidesnap.data.AppDatabaseV4SchemaArtifactTest','com.tonyisup.poseguidesnap.data.AuthorityEntityCounterTypeTest','com.tonyisup.poseguidesnap.data.AuthorityEntityToStringTest','com.tonyisup.poseguidesnap.data.AuthorityOrdinalTriggersTest','com.tonyisup.poseguidesnap.data.CaptureFileOperationStateTriggersTest'}; roots=[E.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/TEST-*.xml')]; got={r.attrib['name'] for r in roots if int(r.attrib['tests'])>0}; assert want <= got, ('missing test classes',sorted(want-got)); print({'verified_host_test_classes':len(want)})"
```

---

## Task 2: Atomic journal creation at attempt registration

**Objective:** Make a successful fresh registration commit exactly one attempt, three deterministic initial journal rows, and one attempt-counter CAS in one Room transaction.

**Modify:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootRepository.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationDao.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/RoomShootRepositoryAndroidTest.kt`
- Host registration policy tests only where pure contract expectations change.

**First genuine behavioral RED:**

`RoomShootRepositoryAndroidTest.registerCaptureAttemptAtomicallySeedsExactlyThreeCaptureFileOperations`

Use the existing `registerCaptureAttempt(...)` API. With the compile-safe V4 table present but registration unchanged, assert `Registered`, one counter advance, and exact journal rows. Expected meaningful failure: expected three rows, actual zero.

After that RED is GREEN, add final `AttemptRegistrationRejectionReason.INVALID_COMMAND_TOKEN_ENCODING` and compile before adding `registrationRejectsDistinctIllFormedUtf16TokensWithoutMutation`. At this cumulative boundary, Task 1 path derivation rejects malformed UTF-16 by throwing its fixed contract exception; the genuine RED expects `Rejected(INVALID_COMMAND_TOKEN_ENCODING)` but observes that fixed exception. GREEN moves the same validator to the pre-transaction registration policy boundary and returns the closed result.

**GREEN behavior:**

- A fresh registration first requires `registeredAtEpochMillis >=` the owning session's current `updated_at_epoch_millis`; equality is accepted, while one millisecond backward returns existing `INVALID_TIMESTAMP` without inserting an attempt/journal row or advancing the counter. Its attempt/session CAS repeats the bound timestamp predicate so a concurrent session update cannot be overwritten by an older registration.
- Before opening the transaction or deriving paths, registration applies the Task 1 well-formed UTF-16 validator to `command.token.value`. Each of two distinct lone-surrogate tokens returns `Rejected(INVALID_COMMAND_TOKEN_ENCODING)` with exact zero attempt rows, zero journal rows, and no counter change. A valid non-BMP token represented by a correct surrogate pair and a valid path-like Unicode token continue through ordinary registration.
- A valid fresh registration inserts ordinals `0,1,2`, exact deterministic paths, stage `EXPECTING_RESERVATION`, null evidence/failure, `reconciliation_required = 0`, and created/updated timestamps equal to the registration timestamp.
- Insert all three rows after the attempt insert and before the session counter CAS, then assert exact row count and shape before return.
- Fault-inject a real second/third journal insert failure and prove attempt, all journal rows, and counter CAS roll back.
- Concurrent duplicate registration produces one `Registered`, one informational `AlreadyRegistered`, exactly one attempt, exactly three rows, and one counter advance.
- Runtime boundary tests cover registration timestamp equality and one-millisecond-backward rejection against a nonzero owning-session timestamp and prove exact no mutation on rejection.
- Exact duplicate registration requires coherent journal rows while the attempt is `REGISTERED` or `CAPTURING`; absent/partial/conflicting rows reject with a new closed `JOURNAL_AUTHORITY_INVALID` registration reason and mutate nothing.
- A coherent `CONFIRMED` legacy replay may remain `AlreadyRegistered` with no transient journal rows; confirmed immutable authority is checked by the existing confirmation graph, not reconstructed.

Do not add camera/filesystem calls or new writer hooks for tests. Use test-only SQLite triggers/connections for rollback orchestration.

**Focused execution:** Compile first with `./gradlew :app:assembleDebugAndroidTest`. After explicit device authorization, run exactly these methods and require six executed tests: `registerCaptureAttemptAtomicallySeedsExactlyThreeCaptureFileOperations`, `registrationRejectsDistinctIllFormedUtf16TokensWithoutMutation`, `registrationRejectsBackwardAndAcceptsEqualOwningSessionTimestamp`, `journalInsertFailureRollsBackAttemptRowsAndCounter`, `concurrentDuplicateRegistrationSeedsOneCompleteJournal`, and `registrationReplayRejectsMissingPartialOrConflictingJournal` in `RoomShootRepositoryAndroidTest`.

```bash
ANDROID_SERIAL=REDACTED_AUTHORIZED_DEVICE_SERIAL ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class='com.tonyisup.poseguidesnap.data.RoomShootRepositoryAndroidTest#registerCaptureAttemptAtomicallySeedsExactlyThreeCaptureFileOperations,com.tonyisup.poseguidesnap.data.RoomShootRepositoryAndroidTest#registrationRejectsDistinctIllFormedUtf16TokensWithoutMutation,com.tonyisup.poseguidesnap.data.RoomShootRepositoryAndroidTest#registrationRejectsBackwardAndAcceptsEqualOwningSessionTimestamp,com.tonyisup.poseguidesnap.data.RoomShootRepositoryAndroidTest#journalInsertFailureRollsBackAttemptRowsAndCounter,com.tonyisup.poseguidesnap.data.RoomShootRepositoryAndroidTest#concurrentDuplicateRegistrationSeedsOneCompleteJournal,com.tonyisup.poseguidesnap.data.RoomShootRepositoryAndroidTest#registrationReplayRejectsMissingPartialOrConflictingJournal'
```

---

## Task 3: Journal-aware capture-start admission

**Objective:** Ensure the fresh `REGISTERED → CAPTURING` CAS cannot authorize physical work without complete initial Room journal authority.

**Create:**

- `app/src/test/java/com/tonyisup/poseguidesnap/data/GuidedSessionDaoTransactionContractTest.kt`

**Modify:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootRepository.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureAttemptDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/GuidedSessionDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/GuidedSessionBootstrapMapper.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/RoomShootRepositoryAndroidTest.kt`
- `app/src/test/java/com/tonyisup/poseguidesnap/data/GuidedSessionBootstrapMapperTest.kt`
- Host capture-start policy tests.

**Compile-safe predecessor before RED:** Add final `CaptureStartRejectionReason.JOURNAL_AUTHORITY_INVALID` and compile before adding the runtime test. The compile-safe guided-bootstrap row and rejection contracts already exist from Task 1.

**First capture-start RED:** Through existing `authorizeCaptureStart(...)`, delete one initial journal row and assert `Rejected(JOURNAL_AUTHORITY_INVALID)`. Current behavior returns `Started`; the required assertion mismatch is expected `Rejected(JOURNAL_AUTHORITY_INVALID)`, actual `Started`.

**First bootstrap RED:** Construct a coherent confirmed attempt and immutable confirmation graph plus one residual `GuidedCaptureFileOperationAuthorityRow`; assert `Rejected(INVALID_CAPTURE_FILE_OPERATION_AUTHORITY)`. With the compile-safe row field present but mapper unchanged, current behavior is `Ready`; missing symbols or enum values are not RED.

**GREEN behavior:**

- Run journal validation and the start CAS in one Room transaction.
- Fresh start requires exactly three ordered rows for the command token, deterministic paths, valid entity shape, stage `EXPECTING_RESERVATION`, empty evidence/failure, and matching registration timestamps.
- The start CAS requires `authorizedAtEpochMillis >= attempt.updated_at_epoch_millis`; equality is accepted and a backward value rejects without mutation. The CAS updates attempt time to the authorization time.
- Missing, partial, duplicate-impossible, invalid-path, invalid-stage, evidence-bearing, or reconciliation-marked initial rows reject with `JOURNAL_AUTHORITY_INVALID` and leave the attempt `REGISTERED`.
- `AlreadyStarted` remains informational and non-authorizing. It is allowed only when the `CAPTURING` attempt has exactly three coherent valid journal rows; rows may have progressed beyond the initial stage.
- Legacy V3 `REGISTERED`/`CAPTURING` attempts with no journal fail closed.
- Deletion/start concurrency still produces one winner at the nonnegative deletion generation.
- `GuidedSessionDao.loadGuidedSessionBootstrap(...)` performs a ninth constituent read of capture-file operation rows joined through attempts for the exact session and ordered by attempt number, ordinal, and token in the same `@Transaction`.
- `GuidedSessionBootstrapMapper` treats capture-file operations as constituent authority, rejects orphan/foreign tokens, and applies one closed validator shared with repository admission rather than duplicating path/stage/evidence rules. A `REGISTERED` attempt requires exactly three coherent initial rows; a `CAPTURING` attempt requires exactly three coherent valid rows in allowed initial/progressed/reconciliation stages; a `CONFIRMED` attempt requires zero journal rows. Missing/partial/conflicting unfinished journal authority and confirmed-plus-residual dual authority return `INVALID_CAPTURE_FILE_OPERATION_AUTHORITY` without becoming `Ready`. Coherent `REGISTERED`/`CAPTURING` journal authority preserves the existing `ReconciliationRequired` result.

Do not expose path/token details in either rejection family.

**Bootstrap-focused host execution:** Run the named mapper/source-contract tests and require a nonzero XML count before any device command:

```bash
python3 -c "import shutil; shutil.rmtree('app/build/test-results/testDebugUnitTest', ignore_errors=True)"
./gradlew :app:testDebugUnitTest --tests '*GuidedSessionBootstrapMapperTest.registeredAndCapturingRequireCoherentCaptureFileOperations' --tests '*GuidedSessionBootstrapMapperTest.confirmedAttemptRejectsResidualCaptureFileOperations' --tests '*GuidedSessionBootstrapMapperTest.captureFileOperationsAreConstituentAuthority' --tests '*GuidedSessionDaoTransactionContractTest'
python3 -c "import glob,xml.etree.ElementTree as E; want={('com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapMapperTest','registeredAndCapturingRequireCoherentCaptureFileOperations'),('com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapMapperTest','confirmedAttemptRejectsResidualCaptureFileOperations'),('com.tonyisup.poseguidesnap.data.GuidedSessionBootstrapMapperTest','captureFileOperationsAreConstituentAuthority'),('com.tonyisup.poseguidesnap.data.GuidedSessionDaoTransactionContractTest','loadGuidedSessionBootstrapIsTransactionalAndReadsJournalNinth'),('com.tonyisup.poseguidesnap.data.GuidedSessionDaoTransactionContractTest','journalQueryIsExactSessionJoinedAndOrdered')}; roots=[E.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/TEST-*.xml')]; got={(r.attrib['name'],t.attrib['name'].split('(')[0]) for r in roots for t in r.findall('testcase')}; assert want <= got, ('missing bootstrap tests',sorted(want-got)); print({'verified_bootstrap_host_tests':len(want)})"
```

`GuidedSessionDaoTransactionContractTest` pins the `@Transaction` wrapper, the ninth `findCaptureFileOperations(sessionId)` call, its placement in the returned snapshot, the exact attempt/session join that prevents foreign-session rows, and `ORDER BY attempt.attempt_number ASC, operation.burst_ordinal ASC, operation.command_token ASC`.

**Focused execution:** Compile with `./gradlew :app:assembleDebugAndroidTest`. After explicit device authorization, run exactly these four `RoomShootRepositoryAndroidTest` methods and require instrumentation `OK (4 tests)`: `captureStartRejectsMissingPartialOrCorruptInitialJournalWithoutMutation`, `captureStartEnforcesNondecreasingAuthorizationTimestamp`, `captureStartDeletionRaceHasExactlyOneWinner`, and `alreadyStartedRequiresCoherentProgressedJournal`.

```bash
ANDROID_SERIAL=REDACTED_AUTHORIZED_DEVICE_SERIAL ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class='com.tonyisup.poseguidesnap.data.RoomShootRepositoryAndroidTest#captureStartRejectsMissingPartialOrCorruptInitialJournalWithoutMutation,com.tonyisup.poseguidesnap.data.RoomShootRepositoryAndroidTest#captureStartEnforcesNondecreasingAuthorizationTimestamp,com.tonyisup.poseguidesnap.data.RoomShootRepositoryAndroidTest#captureStartDeletionRaceHasExactlyOneWinner,com.tonyisup.poseguidesnap.data.RoomShootRepositoryAndroidTest#alreadyStartedRequiresCoherentProgressedJournal'
```

---

## Task 4: Room-owned per-ordinal CAS transitions

**Objective:** Add a capture-specific journal API whose typed requests are the only production surface for stage/evidence/reconciliation progression.

**Create:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomCaptureFileJournal.kt`
- `app/src/test/java/com/tonyisup/poseguidesnap/data/CaptureFileTransitionPolicyTest.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/RoomCaptureFileJournalAndroidTest.kt`

**Modify:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/CaptureFileOperationContracts.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootRepository.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/DeletionExportDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/DeletionExportContracts.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/DeletionExportRepositoryAndroidTest.kt`

**Compile-safe predecessor before RED:** Create the final public methods `advance(CaptureFileAdvanceRequest)`, `markReconciliationRequired(CaptureFileReconciliationRequest)`, and `clearReconciliationRequired(CaptureFileReconciliationResolutionRequest)`. Each placeholder must return `Rejected(PERSISTED_STATE_INVALID)` without touching Room. Create the final request/snapshot/result signatures first and compile. The first behavioral RED calls `advance(...)` for a valid `EXPECTING_RESERVATION → WRITING_TEMP` request and expects `Applied`; the required assertion failure is expected `Applied`, actual `Rejected(PERSISTED_STATE_INVALID)`.

**TDD families, one RED/GREEN cycle at a time:**

1. Exact legal transition and evidence bundle.
2. Exact idempotent replay returns `Idempotent` without timestamp/row mutation.
3. Stale timestamp/stage and contradictory target evidence mutate nothing.
4. Illegal transition mutates nothing.
5. Unknown operation and invalid persisted row return closed rejection reasons.
6. Failure marking and exact resolution preserve/clear evidence as specified.
7. Active attempt/session/shoot/deletion-generation predicate; deleting or generation mismatch returns `BlockedByDeletion` or a closed rejection without mutation.
8. Causal time: every transition is strictly later than the row snapshot and not earlier than attempt capture authorization; evidence capture time is between authorization and the persistence transition. Cross-authority equality passes; backward values mutate nothing.
9. One-winner concurrent CAS on separate WAL connections.
10. Close/reopen preserves state and behavior.

Every SQL mutation must bind token, ordinal, expected stage, expected updated timestamp, attempt `CAPTURING`, exact session/pose/deletion generation, active session/shoot, and nonnegative generations. No broad directory or filename lookup.

**Deletion interlock and causal clock:** The admitted-but-unsettled external-effect stages are exactly `WRITING_TEMP`, `FINAL_RENAME_PENDING_SYNC`, `CLEANUP_PENDING_SYNC`, and `QUARANTINE_PENDING_SYNC`. In the same transaction as `beginShootDeletion(...)`, reject with new reason `CAPTURE_FILE_EFFECT_IN_FLIGHT` before generation advance if any operation owned by the shoot is in one of those stages. Stable pre/post-effect stages do not by themselves block deletion.

Before any deletion mutation, compute the maximum persisted time across the complete graph already loaded for that shoot: shoot created/updated; session created/updated; attempt created/updated/confirmed when present; private-output captured time; receipt applied time; capture-file-operation created/updated/captured when present; outbox created/updated; and export-output created/updated. Require `requestedAtEpochMillis >=` every present value. Equality is accepted. A one-millisecond-backward request returns `Rejected(INVALID_TIMESTAMP)` and mutates no shoot, journal, output, or outbox row. Repeat the bound clock predicate in the shoot/output/outbox UPDATE statements so a concurrent newer row cannot be overwritten by the older request.

This yields one generation model:

1. Deletion commits before a new admission: the admission sees `DELETING`/generation mismatch and returns `BlockedByDeletion` without mutation.
2. Admission commits before deletion: deletion rejects without mutation; the already-admitted adapter outcome can be recorded while the shoot remains active; after the row reaches a stable post-effect stage, a deletion retry may commit.
3. Outcome settlement commits before deletion: deletion may commit; every later new capture-effect admission is blocked.

Add paused WAL interleaving tests for all three orders. A failure marker on an admitted stage remains blocking until later reconciliation produces a stable stage; Task 14B.1 does not invent that resolution. Add a separate nonzero-clock matrix `deletionRejectsBackwardAndAcceptsEqualCompleteAuthorityClock` covering equality and a one-millisecond-backward value against each authority family listed above, with exact no-mutation snapshots.

**Focused host execution:** Remove stale host XML, run the transition policy class, and verify that exact suite produced at least one test:

```bash
python3 -c "import shutil; shutil.rmtree('app/build/test-results/testDebugUnitTest', ignore_errors=True)"
./gradlew :app:testDebugUnitTest --tests '*CaptureFileTransitionPolicyTest'
python3 -c "import glob,xml.etree.ElementTree as E; want='com.tonyisup.poseguidesnap.data.CaptureFileTransitionPolicyTest'; roots=[E.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/TEST-*.xml')]; got={r.attrib['name']:int(r.attrib['tests']) for r in roots}; assert got.get(want,0)>0, ('missing test class',want); print({'verified_transition_policy_tests':got[want]})"
```

Compile with `./gradlew :app:assembleDebugAndroidTest`. After explicit device authorization, run exactly four `RoomCaptureFileJournalAndroidTest` methods—`captureFileJournalAdvancesLegalTransitionsAndIsIdempotent`, `captureFileJournalRejectsInvalidStorageAndCausalTimestampsWithoutMutation`, `captureFileJournalCasHasOneWinnerAcrossWalConnections`, and `captureFileJournalPersistsAcrossReopen`—plus `DeletionExportRepositoryAndroidTest.captureFileJournalDeletionInterlockLinearizesAdmittedEffects` and `DeletionExportRepositoryAndroidTest.deletionRejectsBackwardAndAcceptsEqualCompleteAuthorityClock`; require six executed tests.

```bash
ANDROID_SERIAL=REDACTED_AUTHORIZED_DEVICE_SERIAL ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class='com.tonyisup.poseguidesnap.data.RoomCaptureFileJournalAndroidTest#captureFileJournalAdvancesLegalTransitionsAndIsIdempotent,com.tonyisup.poseguidesnap.data.RoomCaptureFileJournalAndroidTest#captureFileJournalRejectsInvalidStorageAndCausalTimestampsWithoutMutation,com.tonyisup.poseguidesnap.data.RoomCaptureFileJournalAndroidTest#captureFileJournalCasHasOneWinnerAcrossWalConnections,com.tonyisup.poseguidesnap.data.RoomCaptureFileJournalAndroidTest#captureFileJournalPersistsAcrossReopen,com.tonyisup.poseguidesnap.data.DeletionExportRepositoryAndroidTest#captureFileJournalDeletionInterlockLinearizesAdmittedEffects,com.tonyisup.poseguidesnap.data.DeletionExportRepositoryAndroidTest#deletionRejectsBackwardAndAcceptsEqualCompleteAuthorityClock'
```

---

## Task 5: Ledger-derived atomic confirmation and journal consumption

**Objective:** Remove caller authority over private path/count/hash/capture time and replace transient journal rows with immutable confirmed-output/receipt/outbox authority in the existing confirmation transaction.

**Modify:**

- `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootRepository.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/CaptureConfirmationContracts.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureConfirmationDao.kt`
- `app/src/main/java/com/tonyisup/poseguidesnap/data/db/CaptureFileOperationDao.kt`
- `app/src/test/java/com/tonyisup/poseguidesnap/data/CaptureConfirmationPolicyTest.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/CaptureConfirmationRepositoryAndroidTest.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/GuidedSessionPacket2BAndroidTest.kt`: seed and consume final journal rows in its confirmation writer; make the immediate bootstrap transaction pause before its ninth capture-file-operation SELECT; include that ninth family in complete pre/post and nontransactional mixed-snapshot assertions; and preserve its V4 test-only database, complete authority-table digest, query callback, and writer-exclusion gate.
- All current compile-time call sites/tests of `confirmAndAdvance(...)`.

**API change:**

```kotlin
fun confirmAndAdvance(
    command: ShootEffect.ConfirmAndAdvanceCapture,
    exportTargets: List<CaptureExportTarget>,
    confirmedAtEpochMillis: Long,
): CaptureConfirmationResult
```

Remove the caller-supplied `List<DurablePrivateOutput>` parameter. `DurablePrivateOutput` may remain as an internal validated projection if useful, but callers must not construct confirmation authority.

**Behavioral RED and compile-safe API migration:**

1. Using the existing signature, seed a capturing attempt plus three `FINAL_DURABLE` journal rows, pass a different but currently valid caller-built private-output list, and assert that immutable outputs come from the journal and the journal is consumed. Required current failure: the persisted immutable outputs equal the caller-selected values instead of the journal values; missing methods are not RED.
2. Implement ledger-derived confirmation while temporarily retaining the existing overload. The old overload must delegate to the final signature and ignore—not validate or persist—the caller list, making the same behavioral test GREEN.
3. Migrate every production/test caller to the final signature above, delete the old overload in the same task, and rerun focused compilation/tests. No caller-supplied private-output authority remains in the final candidate.

**GREEN behavior:**

- Load exactly three ordered journal rows inside the confirmation transaction.
- Require ordinals `0,1,2`, exact deterministic paths/identity, stage `FINAL_DURABLE`, complete valid evidence, no reconciliation failure, owning attempt `CAPTURING`, and unchanged nonnegative deletion generation.
- Require `confirmedAtEpochMillis` to be nonnegative and `>=` the attempt `updated_at_epoch_millis`, owning session `updated_at_epoch_millis`, and every final row's `updated_at_epoch_millis` and `captured_at_epoch_millis`. Equality at every cross-authority boundary is valid; each one-millisecond-backward case rejects without mutation.
- Derive immutable `PrivateCaptureOutputEntity` rows from the ledger.
- Preserve the existing atomic order for attempt confirmation, session advancement, receipt, outbox, and export outputs.
- Delete exactly three transient journal rows only after all immutable rows/CAS operations succeed, assert delete count, and rely on the surrounding transaction for rollback.
- Exact duplicate after success/reopen returns `AlreadyApplied` from immutable authority only when zero transient journal rows remain. Any residual, partial, or newly injected journal row on a confirmed attempt is contradictory dual authority and returns `JOURNAL_AUTHORITY_INVALID` without mutation, including after reopen.
- Missing/partial/non-final/conflicting/reconciliation-marked rows reject and mutate nothing.
- Fault-inject failure after journal deletion and prove the transaction restores journal rows and all pre-confirmation state.
- Confirmation/deletion and concurrent duplicate confirmation retain one-winner semantics.

Do not let `integrity_metadata` or captured timestamp be caller-selected. Persist the journal SHA-256 into the existing immutable integrity field without adding another confirmed-output schema column in V4.

**Focused host execution:** Remove stale host XML, run the confirmation policy class, and verify that exact suite produced at least one test:

```bash
python3 -c "import shutil; shutil.rmtree('app/build/test-results/testDebugUnitTest', ignore_errors=True)"
./gradlew :app:testDebugUnitTest --tests '*CaptureConfirmationPolicyTest'
python3 -c "import glob,xml.etree.ElementTree as E; want='com.tonyisup.poseguidesnap.data.CaptureConfirmationPolicyTest'; roots=[E.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/TEST-*.xml')]; got={r.attrib['name']:int(r.attrib['tests']) for r in roots}; assert got.get(want,0)>0, ('missing test class',want); print({'verified_confirmation_policy_tests':got[want]})"
```

Compile with `./gradlew :app:assembleDebugAndroidTest`. After explicit device authorization, run exactly five `CaptureConfirmationRepositoryAndroidTest` methods and require five executed tests: `confirmationDerivesOutputsFromFinalJournalAndConsumesRows`, `confirmationRejectsBackwardAndAcceptsEqualBoundaryTimestamps`, `confirmationReplayRequiresNoResidualJournalAcrossReopen`, `confirmationFaultAfterJournalDeleteRollsBackEverything`, and `confirmationDeletionAndDuplicateRacesHaveOneWinner`. Also run exactly `GuidedSessionPacket2BAndroidTest.immediateBootstrapBlocksConfirmationWriterAndReturnsCompletePreThenPostState`, `nontransactionalConfirmationReadsCanProduceMixedPreSessionAndPostReceiptState`, and renamed `repeatedBootstrapsAreReadOnlyAcrossEveryV4AuthorityTableAndSchema`; require three executed tests.

```bash
ANDROID_SERIAL=REDACTED_AUTHORIZED_DEVICE_SERIAL ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class='com.tonyisup.poseguidesnap.data.CaptureConfirmationRepositoryAndroidTest#confirmationDerivesOutputsFromFinalJournalAndConsumesRows,com.tonyisup.poseguidesnap.data.CaptureConfirmationRepositoryAndroidTest#confirmationRejectsBackwardAndAcceptsEqualBoundaryTimestamps,com.tonyisup.poseguidesnap.data.CaptureConfirmationRepositoryAndroidTest#confirmationReplayRequiresNoResidualJournalAcrossReopen,com.tonyisup.poseguidesnap.data.CaptureConfirmationRepositoryAndroidTest#confirmationFaultAfterJournalDeleteRollsBackEverything,com.tonyisup.poseguidesnap.data.CaptureConfirmationRepositoryAndroidTest#confirmationDeletionAndDuplicateRacesHaveOneWinner'
ANDROID_SERIAL=REDACTED_AUTHORIZED_DEVICE_SERIAL ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class='com.tonyisup.poseguidesnap.data.GuidedSessionPacket2BAndroidTest#immediateBootstrapBlocksConfirmationWriterAndReturnsCompletePreThenPostState,com.tonyisup.poseguidesnap.data.GuidedSessionPacket2BAndroidTest#nontransactionalConfirmationReadsCanProduceMixedPreSessionAndPostReceiptState,com.tonyisup.poseguidesnap.data.GuidedSessionPacket2BAndroidTest#repeatedBootstrapsAreReadOnlyAcrossEveryV4AuthorityTableAndSchema'
```

---

## Task 6: Real migration/runtime verification

**Objective:** Prove V4 on the actual Android SQLite/Room runtime without touching production data.

**Modify:**

- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/AppDatabaseMigrationAndroidTest.kt`
- `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/AppDatabaseAndroidTest.kt`
- Runtime test helpers only where needed for UUID database cleanup.

**Required runtime evidence:**

- Direct V3→V4 and chained V1→V2→V3→V4 migrations.
- Seed coherent confirmed, `REGISTERED`, and `CAPTURING` states in separate V3 aggregates. Before and after migration, compare every preexisting value, null, cardinality, and SQLite `typeof(...)` result exactly; do not claim database-file or row-byte identity. Prove the new journal is empty.
- Exact fresh/migrated columns, PK, FK, indexes; `PRAGMA foreign_key_check` empty; no staging tables.
- Reject orphan rows, duplicate key, ordinals `-1`, `3`, `0.5`, and `1.5` on INSERT and UPDATE.
- Through the installed state triggers, reject fractional/text `byte_count` and timestamps, Boolean values other than integer `0/1`, a 64-byte lowercase-hex SQLite BLOB in `sha256`, non-text storage for every other closed textual field, partial evidence bundles, noncanonical text hashes, invalid stages/failures, invalid stage/evidence combinations, `captured_at` outside `created..updated`, UPDATE attempts with `NEW.updated_at <= OLD.updated_at`, and UPDATE attempts that change an immutable identity/path/created field. Repeat after close/reopen. Failed corruption attempts must leave exact row values unchanged; snapshot, transition, and confirmation probes must not mutate authority afterward.
- Restrict attempt deletion while journal children exist.
- Trigger installation once after close/reopen and idempotent builder reopen.
- Registration, start, journal CAS, confirmation consumption, rollback, replay, concurrency, and deletion-race named methods.
- Dedicated UUID database names only; remove exact database/WAL/SHM/journal/lock artifacts and instrumentation package after the bounded run; assert zero residue.

**Exact migration/runtime method set:**

- `AppDatabaseMigrationAndroidTest.migrate3To4PreservesExistingAuthorityValuesAndStorageClasses`
- `AppDatabaseMigrationAndroidTest.migrate1To4CreatesCaptureFileJournalSchema`
- `AppDatabaseAndroidTest.captureFileOperationTriggersRejectInvalidStorageClassesAfterReopen`
- `AppDatabaseAndroidTest.captureFileOperationForeignKeyAndDeletionRestrictionSurviveReopen`

Compile with `./gradlew :app:assembleDebugAndroidTest`. After explicit authorization, invoke exactly the four named methods and require `OK (4 tests)`; an empty selector or compilation alone is not runtime evidence.

```bash
ANDROID_SERIAL=REDACTED_AUTHORIZED_DEVICE_SERIAL ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class='com.tonyisup.poseguidesnap.data.AppDatabaseMigrationAndroidTest#migrate3To4PreservesExistingAuthorityValuesAndStorageClasses,com.tonyisup.poseguidesnap.data.AppDatabaseMigrationAndroidTest#migrate1To4CreatesCaptureFileJournalSchema,com.tonyisup.poseguidesnap.data.AppDatabaseAndroidTest#captureFileOperationTriggersRejectInvalidStorageClassesAfterReopen,com.tonyisup.poseguidesnap.data.AppDatabaseAndroidTest#captureFileOperationForeignKeyAndDeletionRestrictionSurviveReopen'
```

**Permission gate:** Compile instrumentation first. Before any `adb`, install, `am instrument`, or `connected*AndroidTest` command, ask once for authorization naming the exact APK pair, device, method set, and cleanup. No authorization carries across runs.

---

## Task 7: Cumulative gates and documentation

**Objective:** Bind docs and verification to one exact candidate without overclaiming physical I/O.

**Documentation:**

- Update `.hermes/plans/2026-08-27_111939-pose-guide-snap-android-mvp.md`.
- Update `README.md`, `docs/PRODUCT.md`, `docs/ARCHITECTURE.md`, `docs/TESTING.md`, `docs/PRIVACY.md`, and `docs/DEVELOPMENT.md` where current-state claims change.
- Create `docs/adr/0006-room-v4-capture-file-journal-authority.md`.
- Create a dated Task 14B.1 validation record only after an authorized Android run; bind it to exact APK SHA-256 values and list evidence limits.

**Host/build gates:**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Then verify:

- V1–V3 schema hashes unchanged and V4 regeneration byte-identical.
- Runtime dependency classpaths contain no Room compiler.
- Emitted debug/release manifests contain no `android.permission.INTERNET`.
- APK scan contains no Room compiler package.
- No camera/UI/reducer/coordinator production file changed.
- No credentials, sensitive runtime-derived absolute paths/URIs/tokens/hashes, or raw exception text appear in rendered diagnostics, logs, or narrative documentation. Synthetic fixture identities, known deterministic digest vectors, historical schema hashes, staged-diff hashes, and APK verification hashes are explicitly allowed where required to make tests and evidence reproducible.
- `git diff --check`; explicit no-index whitespace checks for untracked files before staging.

---

## Task 8: Exact candidate review and landing

**Objective:** Land only bytes that pass both independent gates.

1. Stage the complete source/test/schema/doc candidate.
2. Prove no unstaged/untracked files and index/worktree blob equality.
3. Compute the exact binary staged-diff SHA-256 digest.
4. Dispatch read-only specification review of that digest.
5. Only after specification `PASS`, dispatch/read engineering-security review of the same digest (reviews may run concurrently only if both are explicitly non-authorizing; commit still waits for both).
6. Any change invalidates both reviews; restage, recompute digest, rerun relevant gates, and obtain replacement approvals.
7. Commit only after specification `PASS` and engineering/security `APPROVED` for the same digest.
8. Push `main`, then verify local `HEAD == origin/main` and clean status.

Suggested subject:

```text
feat: add Room capture-file journal authority
```

---

## Exit criteria

Task 14B.1 is complete only when:

- Fresh registration atomically commits `1 attempt + 3 journal rows + 1 counter CAS`.
- Fresh start cannot win without exact coherent initial journal authority.
- Room owns exact per-ordinal stage/evidence/reconciliation CAS.
- Confirmation accepts no caller-built private output list, derives exactly three immutable outputs from coherent `FINAL_DURABLE` rows, and atomically consumes the transient journal.
- V3→V4 is additive and preserves all V3 rows and V1–V3 schema artifacts.
- Runtime migration/trigger/transaction/concurrency evidence is executed on an explicitly authorized Pixel 6 or is honestly reported open; no runtime claim is inferred from compilation.
- Camera, filesystem, UI, coordinator, MediaStore, speech, and network surfaces remain unchanged.
- Documentation explicitly says no physical file behavior is implemented or proven by this packet.
- The exact staged bytes pass specification and engineering/security review before commit/push.

## Downstream sequence

1. **Task 14B.2:** attempt settlement and restart reconstruction (`FAILED_CLEANED` versus blocking reconciliation), still no physical I/O.
2. **Task 14B.3:** journaled app-private filesystem adapter and startup reconciliation using only persisted exact paths and closed namespace shapes.
3. **Task 15:** guided-session coordinator wires reducer → Room → adapter → Room; only then expose a user-facing shutter/auto-capture path.
4. MediaStore export execution, deletion completion, and TTS remain independent later packets.
