# Testing and Acceptance Contract

> **Status:** see [Development progress](DEVELOPMENT_PROGRESS.md) for the current state of the project and the [25 September 2026 review](PROJECT_REVIEW_2026-09-25.md) for the current assessment and milestone order.

## Settling-period flash cue

Every bounded calibration collector now emits one brief rear-light blink at the start of warm-up. The 250 ms programmed pulse and off-acknowledgement time are included in the existing warm-up deadline. A failed cue stops the test before collection; controller release also requests the light off. The [Pixel verification](validation/2026-09-17-warmup-flash-cue-pixel6.md) passed five synthetic timing/failure tests and one real-camera check with off acknowledged at 432 ms and total warm-up 15,001 ms. The fresh JVM suite passed 714/714, builds passed, and lint reported zero errors and nine warnings. No pose report or photo was collected by the cue verification.

## September 4 audit repair checkpoint

The audit-repair batch passed 643/643 unit tests and six exact authorized Pixel 6 methods on its recorded APK pair. Task 14B.1B brought the host suite to 652/652, Task 14B.2 to 655/655, and corrected Task 14B.3 to 665/665. Task 15A brings the current host suite to 690/690 with reducer restoration, selected-reference parsing/matching, cross-adapter ordering, duplicate callback, failure recovery, startup confirmation, safe Stop, resource lifetime, and navigation/UI contracts. Its corrected exact 4/4 Pixel checkpoint adds synthetic control behavior, generated-data composition, and real rear-camera capture through the journaled path. The earlier exact Task 14B Pixel checkpoints retain their recorded results.

## Testing principles

- Pure policy first: normalization, matching, coaching, and session transitions must be deterministic pure Kotlin before camera or automatic shutter integration.
- Named gates over blended confidence: coverage, framing, person count, angular similarity, positional similarity, and stability remain separately observable.
- Causal tests: tests must exercise behavior and invariants, not fail only because a planned class does not exist.
- Exactly-once capture: automatic and manual triggers, replay, stale callbacks, duplicate receipts, timeouts, crashes, and partial three-file failure must not advance more than once.
- Authority separation: all three app-private outputs and the Room confirmation/advance transaction complete before advancement; MediaStore export happens afterward and can never own or replay advancement.
- Backup exclusion is defense in depth: source and merged manifests plus both rule resources must exclude sensitive data from cloud, device-to-device, and supported cross-platform transfer; absent `INTERNET` permission is not evidence of this boundary.
- External identity is exact: only a durable per-output MediaStore URI can authorize automatic update/delete; metadata matching must preserve foreign rows.
- Same-artifact evidence: final functional, privacy, audio-route, and quality/security review must use the exact same APK digest.
- Truthful evidence: automated replay, instrumentation, emulator, and authorized real-device results must be labeled separately.
- No threshold by intuition: production lock thresholds require a documented positive/negative separation report and real-device calibration.
- Private hardware gate: use of the Pixel 6, earbuds, or private images requires explicit permission when that test begins.

## Planned quality gates

| Gate | Required evidence | Claims allowed after passing |
|---|---|---|
| Gate 0: approved boundary and toolchain | Approved product docs; verified JDK, Android SDK, platform/build tools, and `adb`; blank debug APK and unit tests from the command line | The project can bootstrap and build, not that guided capture works |
| Gate 1: deterministic offline engine | Pure JVM tests for normalization, mirror handling, separated gates, cue choice, hysteresis, idempotency, and sequence transitions; no Android/CameraX/LiteRT/Room/TTS types in domain | Offline domain behavior matches its fixtures, not live pose accuracy |
| Gate 2: single-reference camera slice | Licensed bundled reference; on-device extraction; Pixel 6 preview, analysis overlay, match report, and reducer-owned manual trigger through the unified private three-photo confirmation pipeline; auto-capture disabled | The manual slice and common durable capture protocol work on the tested APK/device |
| Gate 3: complete local MVP loop | Import/order at least five references; bounded speech; stable lock triggers the same private three-photo pipeline; Room confirmation advances once and queues export; five-pose no-touch completion; airplane-mode operation | The local MVP loop works in the tested conditions, with export reported separately |
| Gate 4: real-device acceptance | Same APK digest across functional, privacy, audio, storage/export/deletion, and quality/security checks; full Pixel 6 matrix | Only the exact documented behavior and conditions; still no store/publication claim |

Gate 0's command-line bootstrap is GREEN on the verified host. Task 9's direct MoveNet model boundary, Task 10 camera evidence, Task 11A's Room authority, Task 11B's transactional reference-import backend, Task 12's Room V3 preparation UI, Tasks 14A.1–14A.3, Tasks 14B.1A–14B.3, and Task 15A each have bounded Pixel evidence. Task 15A connects CameraX to the journaled manual path and passes its generated-data plus real-camera final-artifact checkpoint. No emulator evidence exists. The complete selected-reference production UI and remaining Gate 2 matrix are not yet proven, so Gates 2–4 remain unpassed.

The reviewed Task 10 APKs were reproducible byte-for-byte: main SHA-256 `a678f014cefc19281bd253cfdb64b97bf0a3ec65f2e3d2f374248bfd47dfc3ad` and instrumentation SHA-256 `3f0985b207286c0c4f249183ae5d434488edf129afd53ed401f70988bd8135c5`.

Task 11A evidence is deliberately narrower than the future end-to-end gate:

- Room V1 schema/runtime: 4/4 Pixel methods.
- Registration and capture-start authority: 22/22 current methods across the original 20-method run plus two focused negative-generation regressions.
- Confirmation, duplicate replay, immutable authority, and complete rollback matrix: 33/33 current methods across the original 32-method run plus one focused negative-generation regression.
- Deletion, targeted claims, restart, deterministic orderings, and two-database concurrency: 34/34 on the final hardening APK in 1.996 seconds.
- Production database entries and test-database residue were zero after the final 34-method and focused three-method runs. No MediaStore row or private image was created.

Task 11B evidence is also bounded and separate from the future UI/end-to-end gate:

- 413/413 JVM tests passed with zero failures, errors, or skips.
- Lint plus debug, release, and instrumentation APK assembly passed.
- Room V1 remained byte-identical; the V1→V2 migration ran on the Pixel 6 and preserved legacy data while installing the exact V2 authority contract.
- The exact APK pair passed 25/25 targeted Pixel 6 methods covering generated-byte no-clobber publication, every persisted file-ledger transition, transaction rollback/concurrency, restart cleanup/quarantine, picker-handler dispatch/redaction, and public-fixture MoveNet analysis.
- Test databases, Room lock files, reference-import files, and the instrumentation package were removed and verified absent. No provider image, camera capture, user database content, or MediaStore row was read or created.

Task 12 evidence remains bounded to shoot preparation and durable camera-route admission:

- 556/556 JVM tests passed with zero failures, errors, or skips.
- Lint plus debug, release, and instrumentation APK assembly passed.
- Room V1 and V2 schema artifacts remained byte-identical; V3 added sole active-order ownership in `shoot_poses`, preparation projections, and one-active-session enforcement.
- The final authorized Pixel 6 gate passed 4/4 `ShootEditorFlowTest` methods. That class uses synthetic screen state and no database, picker, camera, or device I/O; it verified recovery rendering, exact row-scoped text and reorder callbacks, start enablement/callback behavior, and visible import/reconciliation states.
- A separate manual Pixel run on a pre-final artifact observed production shoot creation/navigation, three public-fixture system Photo Picker imports, Room/MoveNet settlement, transactional reorder, durable start, camera gating, and picker callback survival across recreation. The final source changed afterward, so this is supporting manual evidence rather than same-artifact proof for `5bc15c3`.
- During that manual run, camera permission was denied after durable start and no preview or sensor run occurred. Device fixture, app/test data, instrumentation package, and rotation changes were cleaned up.
- Compose semantics and controls were exercised on-device, but no TalkBack or screen-reader usability claim is made. See `validation/2026-09-01-task12-room-v3-shoot-preparation-pixel6.md`.

Task 14A.1 evidence remains bounded to exact-session Room reconstruction:

- 579/579 JVM tests passed with zero failures, errors, or skips; lint plus debug, release, and instrumentation APK assembly passed.
- Room V1–V3 schema artifacts remained byte-identical.
- The installed main and instrumentation APK bytes matched their recorded local SHA-256 values.
- The Pixel 6 running Android 16 passed 6/6 focused methods: one nonzero close/reopen repository test plus five transaction/mutation-control/read-only tests.
- Room's generated blocking DAO uses an immediate transaction. Confirmation and deletion writers remained blocked while bootstrap was paused before its second SELECT; bootstrap returned complete pre-state and later reads returned complete post-state.
- Equivalent nontransactional confirmation/deletion reads produced the expected mixed pre/post facts, demonstrating that the transaction gate is causal rather than decorative.
- Repeated bootstrap reads changed no V3 authority table, schema digest, `total_changes()`, or `PRAGMA data_version`.
- UUID-named test databases and the instrumentation package were verified absent afterward. No camera, picker, image, MediaStore, UI, or personal-data path was exercised. See `validation/2026-09-02-task14a1-atomic-room-v3-bootstrap-pixel6.md`.

Task 14A.2 evidence remains bounded to Room active-session discovery:

- 591/591 JVM tests passed with zero failures, errors, or skips; lint plus debug, release, and instrumentation APK assembly passed; schemas unchanged.
- The new mapper class produced 12/12 behavioral runtime-assertion failures against its compile-safe placeholder before implementation, then 12/12 passes.
- The installed APK pair matched recorded SHA-256 values and passed 8/8 focused Pixel 6 methods: exact discovery across close/reopen, sessionless-shoot `None`, missing-shoot `UnknownShoot`, fail-closed multi-active corruption with the one-active triggers dropped in the UUID test database, plus the six Task 14A.1 regressions.
- Test databases and the instrumentation package were verified absent afterward. See `validation/2026-09-02-task14a2-active-session-discovery-pixel6.md`.

Task 14A.3 evidence remains bounded to stale-safe UI admission and retained bootstrap ownership:

- 618/618 JVM tests passed with zero failures, errors, or skips; lint plus debug, release, and instrumentation APK assembly passed; schemas unchanged.
- Behavioral RED/GREEN covered editor-scoped discovery, fresh click-time Resume, stale and rejected authority, queued-effect revocation, bootstrap mapping, cancellation, close-after-in-flight leases, stale-generation suppression, retry/exhaustion, and Ready-only camera authorization.
- Installed main and instrumentation APK bytes matched local SHA-256 values. The awake/unlocked Pixel 6 passed 11/11 methods across two successful bounded invocations: 5 editor Compose, 4 started-destination Compose with injected fake camera content, and 2 Room reopen regressions. The fourth destination method proves Retry and Back remain reachable by scrolling in a 320dp × 180dp viewport at 2.0 font scale.
- The first invocation found the device unable to launch the Compose test host; both Room tests passed while all UI tests failed before a semantics hierarchy existed. The unchanged APK pair passed after the device was explicitly made awake/unlocked and a separate rerun was authorized. This is recorded as an environmental harness failure, not hidden or counted as product evidence.
- Test databases and the instrumentation package were verified absent after every attempt. No camera, picker, image, MediaStore, personal-data, or TalkBack path was exercised. See `validation/2026-09-02-task14a3-stale-safe-resume-pixel6.md`.

Task 14B.1A evidence remains bounded to the Room V4 authority foundation and its explicitly separated repair candidates:

- The three-file Task 4 Android-test candidate v5 patch, SHA-256 `377dc02c781ece2cf78e48f93c727d02ea9d41082a12229ff22803e97a306491`, received specification `PASS` and engineering/security `APPROVED` on those exact bytes. It is not the digest of the complete 43-path Task 14B.1A candidate that landed at `57b33c9`.
- 635/635 JVM tests passed, and Android-test compilation passed.
- The cumulative Task 5 host gate passed `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`, and `assembleDebugAndroidTest`. Lint reported zero errors. Room V1–V3 remained byte-identical, regenerated V4 matched the frozen pre-build artifact, all debug/release/Android-test runtime classpaths and packaged dex were free of Room compiler/KSP/compiler payloads, and the emitted debug, release, and Android-test manifests contained no `INTERNET` permission.
- The Pixel 6 migration class passed 10/10. It covered direct V3→V4 and chained V1→V2→V3→V4 migration, exact preservation of preexisting values/nulls/cardinalities/SQLite storage classes, an empty journal after migration, schema/foreign-key/index checks, trigger rejection, restrictive children, and migrated-attempt confirmation behavior.
- A focused Pixel 6 run passed 2/2 for the NUL-sensitive Packet 2B snapshot oracle and direct migration. Packet 2B includes capture-file operations as a ninth same-transaction authority family, and the oracle losslessly distinguishes TEXT/BLOB values and embedded NULs while preserving `typeof(...)` evidence.
- The integrated five-class Pixel 6 run executed 105 tests with 0 failures and 0 errors. Ten expected `@Ignore` skips are direct first-application confirmation and rollback cases deferred to Task 14B.1C; they are explicitly excluded from passing evidence.
- Documentation candidate v1 exposed that the original implementation copied caller lists before entering the unfinished-confirmation transaction. Repair v1 was specification-rejected for a receipt-first bypass; repair v2 was specification-rejected because its nullable-timestamp guard let malformed non-null timestamps bypass unfinished lifecycle authority. The final eight-scenario Pixel 6 method covers REGISTERED and CAPTURING with null or malformed non-null timestamps, each with and without a raw receipt. It failed on v2 (`expected 0`, observed 3 element reads) and passed 1/1 after v3 moved the unavailable guard to explicit lifecycle state. Repair v3, SHA-256 `ed29acd613a890d213e398aaacf4a2d79512662ac0dbf88c411404fcfdb3bd3a`, received exact-byte specification `PASS` and engineering/security `APPROVED`; that approval covers only the two-file repair, not the complete landing candidate. The cumulative host gate passed again with 635/635 JVM tests and zero lint errors. The current-byte integrated five-class Pixel 6 run executed 106 tests with 0 failures, 0 errors, and 10 expected skips: AppDatabase 10, migration 10, confirmation 36 including 10 skips, deletion 44, and Packet 2B 6. Exact UUID test-database teardown left no matching residue.
- Registration/start/deletion/bootstrap tests prove exactly three initial rows per new attempt, logical-only `Started`, stable `EXPECTING_RESERVATION` deletion behavior, causal-clock checks, and fail-closed malformed storage/value/ownership/key/graph cases. The focused current-byte regression proves unfinished confirmation rejects before caller-list element traversal or authority mutation.
- Final complete-candidate specification review became stale when the exact 43-path candidate landed at `57b33c9`. Its narrow continuation returned `REQUEST_CHANGES` after reproducing a byte-equivalent `BLOB` journal token that ordinary text equality failed to count, allowing `AlreadyApplied` despite residual authority. A new deterministic Pixel 6 regression failed 1/1 on `57b33c9` with that result, then passed 1/1 after the byte-correlated query repair, now committed at `382a659`; both runs left no matching test-database residue. The repaired host/build gate passed with 635/635 JVM tests and zero lint errors. The repair is committed at `382a659`; this record does not establish missing complete-candidate exact-byte reviews or remote publication status.
- Task 14B.1A itself exposed no production per-file transition API. Task 14B.1B now adds that Room-only API without performing camera/filesystem work or changing the historical 14B.1A evidence above.

Task 14B.1B host evidence is bounded to Room authority:

- 652/652 JVM tests pass, including nine pure tests for the closed transition graph, evidence requirements, strictly increasing row clocks, stale requests, reconciliation, redaction, and the exact four admitted stages.
- Android-test compilation passes. The new tests cover raw SQLite storage-class/value rejection, byte-equivalent token and ordinal aliases, exact three-row/path/clock/lifecycle deletion authority, close/reopen persistence, WAL compare-and-set contention, and deletion/admission/settlement orderings paused after a real first-transaction write.
- The deletion clock test rejects every request one millisecond behind each authority family and accepts equality for each family, including capture-file journal clocks.
- No schema, dependency, permission, CameraX, filesystem, MediaStore, UI, network, or analytics behavior is added. The exact six-method Pixel 6 checkpoint passed against the recorded final APK pair; installed hashes matched local hashes and cleanup left no generated database residue. See the [Task 14B.1B validation record](validation/2026-09-04-task14b1b-room-file-admission-pixel6.md).

Task 14B.1C host evidence is also bounded to Room authority:

- 652/652 JVM tests pass, including a source-contract check that the public confirmation call has exactly three parameters and cannot receive caller-supplied private output authority. Production and Android-test Kotlin compilation pass.
- Five focused confirmation methods cover journal-derived output identity/evidence and exact consumption, rejection of missing/partial/non-final/conflicting/reconciliation rows, equality and backward timestamp boundaries, reopen replay with zero-residual-journal enforcement, rollback after journal deletion and post-write drift, plus deletion and duplicate-confirmation races across two WAL owners.
- Three Packet 2B methods cover confirmation-writer exclusion during the ninth bootstrap authority read, the expected mixed-state risk of nontransactional reads, and repeated read-only snapshots across every V4 authority table and the schema.
- The first five-method Pixel candidate exposed a safe-but-wrong backward-journal-clock rejection reason. The corrected implementation retained structural fail-closed validation and returned `INVALID_TIMESTAMP`; the full host gate passed again, then the exact final APK pair passed all eight methods across bounded 5/5 and 3/3 invocations. Installed hashes matched, cleanup left no generated database residue, and the main app data was preserved. See the [Task 14B.1C validation record](validation/2026-09-04-task14b1c-journal-confirmation-pixel6.md).

Task 14B.2 evidence is bounded to attempt settlement and Room restart reconstruction:

- 655/655 JVM tests pass with zero failures, errors, or skips. Contract tests cover the two stored lifecycle values, retryable failed history, failed-count invariants, same-pose ordering, final blocking authority, and redacted diagnostics.
- Six Android methods cover untouched and cleaned settlement, same-pose retry, aggregate reconciliation across close/reopen, final-durable deferral to confirmation, rollback after journal deletion, deletion validation with failed history, and two-owner one-winner settlement.
- Two earlier device candidates failed before settlement during shared test setup: an invalid one-pose session fixture first returned `JOURNAL_AUTHORITY_INVALID`, then a stale fixture identifier returned `STALE_POSE`. The final fixture and narrow registration classifier passed the full host gate and exact 6/6 Pixel run in 1.376 seconds.
- Installed hashes matched the final local APKs. Cleanup left no generated database residue, removed the instrumentation package, and preserved main-app data. No camera, filesystem, picker, personal media, MediaStore, audio, UI, or release deployment path ran. Migrated unfinished attempts without V4 journal rows remain fail-closed for Task 14B.3 bounded filesystem reconciliation. See the [Task 14B.2 validation record](validation/2026-09-04-task14b2-attempt-settlement-pixel6.md).

Task 14B.3 host evidence is bounded to exact app-private generated files and startup recovery:

- 665/665 JVM tests pass with zero failures, errors, or skips. Ten new tests cover no-clobber lease publication, synced byte/hash evidence, exact cleanup and retry, evidence-mismatch retention, unrelated sibling preservation, bounded journal-free observation, aggregate cleanup, final-ready deferral, ambiguity retention, and cleanup-only causal clocks.
- Lint has zero errors and 13 warnings. Debug, unsigned release, and Android-test APK assembly pass. Schemas, dependencies, and permissions remain unchanged.
- Three Android methods compile against the Android libc-backed file adapter. They use only UUID-named test databases and one UUID-named directory below `noBackupFilesDir`: journaled cleanup across close/reopen, journal-free all-absent settlement, and exact-file retention when legacy ownership is unavailable.
- The device methods do not invoke CameraX, open the picker, access personal media, touch MediaStore, use audio, launch UI, clear app data, or deploy the release build. Their Pixel 6 checkpoint requires fresh authorization after the final APK hashes are frozen.
- Corrected frozen candidate hashes are debug `50dc9ab424da5120f030a727d23c1034af7a222201869ece8e72c9b3648a3ec0`, Android test `2d893a6a6cd3578b97d06dd3e92f465cff4ff89e909ed48b5ec3b135a8385e0c`, and unsigned release `0ff77ed8657103de5822a50ffa3260cd9fa02664e0234573c2c85200492e088d`.
- The corrected exact 3/3 Pixel 6 invocation passed in 0.785 seconds. Installed hashes matched the frozen packages. Cleanup left no generated database/directory residue, removed the instrumentation package, and preserved the main app and its data. See the [Task 14B.3 validation record](validation/2026-09-05-task14b3-journaled-recovery-pixel6.md).

Task 15A host evidence covers the first manual guided-capture composition:

- 690/690 JVM tests pass with zero failures, errors, or skips. The coordinator checks exact global ordering from registration through three final-durable rows, ignores duplicate callbacks, settles expected failure, and completes unexpected authority failure as reconciliation-required rather than stranding the owner.
- Lint reports zero errors and 14 warnings. Debug, unsigned release, and Android-test APK assembly pass. Room schemas and dependency/permission declarations are unchanged.
- The selected validated Room reference supplies the overlay dimensions, label, mirror policy, and visible uncalibrated match evidence. Absence cannot silently fall back to the bundled demo pose.
- Android-test compilation includes two synthetic Compose control methods, one generated-data integration method, and one separately gated live rear-camera integration method. Both integration methods use a UUID database and UUID private root, traverse real Room V4 and the exact private-file adapter, confirm three outputs, and advance once. The generated method opens no camera; the live method captures exactly three transient app-private photos and deletes its generated root during teardown.
- The first live candidate safely exposed that CameraX's file-target API replaces the journal-owned temporary inode. Its unlocked 3/4 run passed both Compose methods and generated integration, while the live method refused durability before confirmation or advancement. The corrected writer uses CameraX's output-stream target so the already-admitted inode remains stable through close, sync, hash, and publication.
- Corrected frozen candidate hashes are debug `336e4f662f98beee6a52a370fec658b97dbdf6ba47fe9163b102d584ccbacad7`, Android test `65637d4a7958c7d6b39cb266f90d0c46980547a8e1fec52b227b79e63e9e8a96`, and unsigned release `12064e8412632cb454c973f822cd8f243524d3494eb0bb235d940ca0faede94c`.
- The corrected exact Pixel 6 checkpoint passed 4/4 in 5.235 seconds: both `GuidedCameraScreenTest` methods and the generated plus live methods in `JournaledGuidedCaptureIntegrationAndroidTest`. Installed hashes matched. The live method opened the rear camera and created exactly three transient app-private photos; no method opened the photo picker, read existing personal media, touched MediaStore, used audio, cleared main-app data, or deployed the release build. Generated database/file residue was absent, camera service had no active client afterward, and the instrumentation package was removed. See the [Task 15A validation record](validation/2026-09-06-task15a-manual-guided-capture-pixel6.md).

Task 16A host evidence covers the calibration harness:

- Six Python tests verify deterministic replay plus rejection of raw landmark fields, private-image markers, non-increasing relative time, framing-policy values outside the production contract, and release thresholds above acquisition thresholds.
- Two JVM fixture tests keep the analyzer policy exactly synchronized with Kotlin's uncalibrated framing/match/timing defaults and assert the bundled dataset's synthetic non-image declaration. Six framing tests prove independent center/scale evidence, complete reference extent, required head/bilateral arm/lower-leg/torso coverage, confidence boundaries, fail-closed behavior, and scalar-only immutable output. Matcher, reducer, and architecture tests prove acquire/retain asymmetry and immutable release-gate evidence.
- The synthetic report records 3/3 positive locks, one deliberate false lock in 5 negative sequences, zero duplicate-capture sequences, and negative all-frame score margins. These are analyzer contract results, not accuracy evidence.
- Existing acquisition thresholds remain unchanged; lower release thresholds now satisfy the approved hysteresis contract. Live diagnostics now evaluate framing rather than supplying a neutral value. Automatic capture behavior, dependencies, permissions, Room schemas, camera behavior, and device data remain unchanged. See [MATCH_CALIBRATION.md](validation/MATCH_CALIBRATION.md).

Task 16B evidence covers the bounded device-side producer and its first exact Pixel run:

- Eight collector JVM tests cover exact authorization, fixture labels, identifier syntax, timing bounds, scalar bounds, immutable bounded snapshots, private-field absence, deterministic JSON, and a byte-for-byte shared Kotlin/Python fixture. The complete JVM suite passes 708/708.
- The Android collector requires exact arguments, Pixel 6 (`oriole`), pre-granted camera permission, a 3–30 second warm-up (30 seconds required for spoken guidance), and a 5–30 second collection. It uses the full-screen production CameraX crop and bundled public reference, retains at most 600 scalar frames, syncs one fixed report below `noBackupFilesDir/calibration-export`, and deletes partial output on failure.
- Lint reports zero errors and 9 warnings. Debug, unsigned release, and Android-test assembly pass. Schemas and permissions remain unchanged. Frozen hashes are debug `339f7597960a959f53fa79b168c838e4b25e325ea814e5d3f7d3bce83afc7d76`, Android test `e75d82674b8ee361075f1872cde37eb4585eca37470cb19999899f75c5025e5d`, and unsigned release `27f397d0a4d517b67a013c868c11d5df63ebf390f400b5254c06e2f119fcf3d5`.
- The separately authorized positive and negative invocations each passed and produced 97 frames. The negative sequence evaluated throughout and never locked. The positive sequence detected exactly one person throughout but failed canonicalization before scoring on all frames. Device/host report hashes matched, combined analysis completed, exact device output was removed, and the instrumentation package was uninstalled. No threshold changed. See the [Task 16B validation record](validation/2026-09-08-task16b-derived-calibration-pixel6.md).

Task 16C host evidence covers the bounded diagnostic follow-up:

- Schema v2 requires one closed evaluation status, a confidence-qualified MoveNet landmark count in `[0, 17]`, and a qualified shoulder/hip count in `[0, 4]`; it retains no landmark identities or coordinates. Schema v1 remains readable.
- The analyzer refuses v2 lock replay from unevaluated frames and reports per-sequence status frequencies and count summaries. Its 12/12 tests cover shared Kotlin/Python bytes, v1 compatibility, fragment merging, field/range/status consistency, and the existing privacy/policy boundaries.
- The complete host gate passes 708/708 JVM tests, lint with zero errors and 9 warnings, and all APK assemblies. After the first device run, a host-only preflight correction left debug `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4` and unsigned release `50437b7e4651446e374204edb6095a0ddd23803fcdf5b71c7e10b4112473a769` unchanged while producing Android test `8b2d4e81440b11878bf0635c563d305985b25b600c3f385341a75088d726d4e4`.
- The separately authorized `positive-centered-diagnostic-a` invocation passed 1/1 with 97 schema-v2 frames, an exact device/host report hash match, and complete cleanup. Every frame was `no-person` with zero qualified landmark and torso-anchor counts. The corrected collector requires warm-up to end with five consecutive exactly-one-person frames or fails before recording and report creation. Its separately authorized `positive-centered-diagnostic-b` repeat failed that preflight 1/1 after 6.522 seconds, created no report, and completed exact cleanup. These results prove v2 status disambiguation and fail-closed preflight behavior but do not reproduce the earlier one-person condition. See the [Task 16C validation record](validation/2026-09-08-task16c-canonicalization-diagnostic-pixel6.md).
- A subsequent collector update shows live no-person/one-person/multiple-person feedback during warm-up. Its failure message contains only analyzed/no-person/one-person/multiple-person frame counts and final/maximum consecutive one-person counts; it writes no failed report. The full host gate remained green. Its exact `positive-centered-diagnostic-c` Pixel invocation used Android-test hash `d80047d2ecc39a011c345c50e3e2b51f5cba32aea1e6126d5b40a23f3780135b` and failed before recording after classifying all 146 analyzed frames as no-person. Exact cleanup completed.
- Task 16D adds the strongest valid raw MoveNet instance score to the immutable analyzed frame and reports only the maximum seen during a failed warm-up, beside the unchanged `0.25` gate. It exposes no slot identity, tensor, landmark, coordinate, or image and does not change the persisted report schema. The current host gate passes 709/709 JVM and 12/12 Python tests, with frozen debug `16843f2b88f11116161ec81ecbf460b691eac97fa4b1123248dbff426852e787`, Android test `488eb4b615f74479d06824f29c711cbadaf24013778acd35a3cf1fbfdcddac7e`, and unsigned release `c7aae95d26bc295ab42f5dad7688814f4775df95cacf567fe668df7f3bc696ba`. Its separately authorized `positive-centered-diagnostic-d` Pixel invocation failed before recording after 146/146 no-person frames, with `maximumValidPersonScore=0.0` and `minimumAcceptedPersonScore=0.25`; no report was written and exact cleanup completed.
- Task 16E reuses the same frozen main/test pair but runs only `MoveNetPoseDetectorTest#packagedModelAndFixtureDefineRepeatedOneZeroTwoPersonContract`. Its separately authorized Pixel invocation passed 1/1 in 0.669 seconds using only the packaged public fixture and generated controls. It verifies model/package/static-preprocessing/inference/mapping health on the current artifact while making no live-camera or calibration claim. Exact cleanup removed the test package and temporary pulls, preserved main-app data, and left no active camera client. See the [Task 16E validation record](validation/2026-09-08-task16e-static-movenet-recheck-pixel6.md).
- Task 16F samples at most 256 pixels from the transient upright CameraX crop and immediately reduces them to mean luminance and luminance range in `[0, 1]`. Only those scalars cross the analyzed-frame boundary. A failed preflight exposes minimum/maximum frame mean and maximum within-frame range without writing them to the calibration report. Its host gate passed 712/712 JVM and 12/12 Python tests with zero lint errors and 9 warnings. Frozen hashes were debug `a47f4588346abdbd7d569d2a02de38a29c12e41abfc5a169e8d8eceee3f52d94`, Android test `d04404d44440ff165ab6f74b341026dd8900531d115387472316e1970d68ae98`, and unsigned release `74ee660bd84b421ce9dd5cf7a42d72e72e4d108720fc7b4be99ac7e3df54c9e3`.
- Task 16F's separately authorized `positive-centered-diagnostic-e` Pixel invocation verified those installed hashes, then failed before recording after 146/146 no-person frames. `maximumValidPersonScore` was `0.0`, frame-mean luminance ranged from about `0.401` to `0.642`, and maximum within-frame range was about `0.373`. The input was neither black nor flat. No report was written, and exact cleanup completed. See the [Task 16F validation record](validation/2026-09-08-task16f-camera-bitmap-statistics-pixel6.md).
- Task 16G reduces all six-slot, 17-keypoint score values immediately to one maximum valid scalar in `[0, 1]`. Its first exact Pixel candidate exposed and caused correction of a traversal through bounding-box index 53; its reported `1.0` is invalid as keypoint evidence. A transient corrected startup attempt produced no evidence and motivated bounded failure classification. The final exact `positive-centered-diagnostic-h` repeat verified debug `28c3820a2780fb744c37d056b4742eb71eab9249c9947deb411c4089c6e57dcd` and test `d69892e64e8f9041f098ea3c249918c37037d54ba72ab916777a0fd02c2e4808`, then safely failed preflight after 145/145 no-person frames. Maximum valid person/keypoint scores were `0.14585432410240173`/`0.20261988043785095` against the unchanged `0.25` gate. No report was written and exact cleanup completed. The result proves partial live pose evidence exists but cannot calibrate a threshold without positive and negative score distributions. See the [Task 16G validation record](validation/2026-09-08-task16g-keypoint-score-diagnostic-pixel6.md).
- Task 16H adds schema-v3 per-frame maximum valid person/keypoint scores and retains schema-v1/v2 analyzer compatibility. Its exact Pixel 6 full-body positive and empty-scene negative invocations each passed 1/1 with 97 frames and matching device/host report hashes. Both sequences remained `no-person`; positive person-score p05/median/p95 were `0.000`/`0.000`/`0.117226` versus negative p95 `0.122414`, and positive keypoint p05/median/p95 were `0.103079`/`0.123211`/`0.147787` versus negative p95 `0.150982`. The distributions overlap and support no threshold change. The host gate passes 714/714 JVM and 14/14 Python tests with zero lint errors and 14 warnings; all APK assemblies pass, and Room schemas and permissions are unchanged. Frozen hashes are debug `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66`, Android test `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9`, and unsigned release `996b45a89d91c6aac51cb6dac92900dcd32d32557aef2118832b2741520a21ae`. See the [Task 16H validation record](validation/2026-09-08-task16h-detector-gate-calibration-pixel6.md).

## Pure JVM test matrix

Planned coverage:

- Coordinate validation and immutable model invariants.
- Translation and scale normalization.
- Default mirror matching, per-pose mirror opt-out, and left/right cue correctness.
- Joint-angle and normalized position scoring.
- Separate coverage, framing, and second-person gates.
- Deterministic seeded jitter and negative controls.
- Cue selection, confidence filtering, persistence, cooldown, suppression, and priority.
- Reducer transition table, dwell, hysteresis, timing, pause, stale frames, automatic and manual requests, manual bypass of only match/lock, capture success/failure, duplicate receipts, idempotency, and completion.
- Unified exactly-three-output policy; deterministic private identities; exclusive non-authoritative empty reservations; same-directory temp writes; stable reservation-identity verification; process-wide supported-mutation serialization; private write, sync, atomic rename, and cleanup failure; final-path collision/no-clobber; typed crash/collision reconciliation; retained prepare-time and post-prepare cleanup ownership with blocked submissions and serialized retry; foreign temp preservation; close-versus-third-publication precedence; per-file atomicity without a three-file atomicity claim; timeout; and duplicate callback.
- Room confirmation transaction success/failure proving atomic capture confirmation, three private-output records, exactly-once advancement, receipt application, and outbox creation.
- Crash before the Room transaction with cleanup/quarantine before retry; crash after it with capture/advance complete and export-only replay; cleanup failure and reconciliation-required blocking.
- One committed outbox with exactly three rows; composite `(commandToken, burstOrdinal)` uniqueness, ordinal 0–2 constraint, transaction-time cardinality assertion, and rollback on any violation.
- Paused two-worker interleaving proving one compare-and-set winner and exactly one `MediaStore.insert()`; crash probes at pre-claim, post-claim/pre-insert, post-insert/pre-URI-persist, post-URI/pre-publish, and publish/state-persist seams.
- Claimed/create-started missing-URI work remains reconciliation-required across timeout/restart and is never lease-reset for another create; known exact-URI work resumes without another insert.
- Exact target collection/volume and intended metadata persistence, with tests proving display name/relative path cannot authorize update/delete/reconciliation and a foreign MediaStore row remains unchanged.
- Delete-versus-worker interleavings for the atomic deletion generation: claims blocked after barrier, capture/advance blocked, untouched pending work cancelled, and worker barrier rechecks before create and publication.
- Quarantine retention, visible unresolved count/state, explicit resolution, app-level delete-all through the same barrier, incomplete deletion with minimal tombstone, and no-foreign-row deletion.
- Repository and coordinator behavior through narrow ports.
- Five-pose replay transcript with unique capture tokens and exactly-once advancement.
- Architecture checks that reject Android and SDK dependencies in domain packages.

## Android instrumentation matrix

Planned coverage:

- Exact MoveNet model load and deterministic one-person, black zero-person, and composed two-person extraction within documented tolerance; executed on the authorized Pixel 6.
- Camera keep-latest scheduling with one bounded off-UI blocking-detector worker, guaranteed `ImageProxy` closure, per-frame detector/mapper failure containment, and no unbounded queue.
- Rotation, crop, rear-camera, and mirror coordinate transforms.
- Room transactions, ordering, migrations, command-token/receipt uniqueness, and atomic confirmation + three private outputs + one advance + outbox creation.
- System photo-picker result handling, app-private copy, and failed-import cleanup.
- Reference validation for no person, multiple people, and insufficient coverage.
- Compose create, import, rejection, reorder, and start flow.
- Camera permission denial and recovery.
- Preview, analysis overlay, and captured-image coordinate alignment.
- Text-to-Speech initialization, offline-voice selection, network-required voice rejection, lifecycle, queue cancellation, absent `INTERNET` permission, and visual-only fallback.
- Source-manifest and merged-manifest assertions for `android:allowBackup="false"`, `android:fullBackupContent`, and `android:dataExtractionRules`; parsed source-rule assertions excluding all nine credential/device-protected domains in cloud, device-transfer, and compile-SDK-37 cross-platform sections; assertion that no custom `BackupAgent` is declared.
- APK-level backup-policy acceptance on the exact hashed candidate: use the pinned Build Tools `aapt2 dump xmltree` to inspect the packaged `AndroidManifest.xml` and both packaged compiled XML rule resources. Require both manifest references, no `android.permission.INTERNET`, and the complete nine-domain set in every applicable packaged rule section. Source or merged-intermediate checks cannot substitute for this artifact check.
- App-private capture publication for exactly three deterministic automatic or manual outputs, same-directory temp cleanup, sync/finalization failure, final-path collision/no-clobber, and durable-file inspection.
- Room transaction failure with no advance/receipt/outbox; startup cleanup or quarantine before retry; post-transaction restart with export-only replay; exactly-once receipt and advancement under duplicate callbacks.
- MediaStore outbox schema and worker tests for exact three-row cardinality, composite uniqueness, ordinal bounds, exclusive compare-and-set claims, the complete claim/create/URI/publication crash matrix, exact-URI-only mutation, and a paused two-worker one-insert interleaving.
- Deletion-generation interleavings with claims and publication, quarantine retention/resolution/visible count, app-level delete-all, incomplete tombstone retention, and preservation of already exported, ambiguously created, and foreign MediaStore rows.
- Camera-analysis resource cleanup when leaving the guided screen.

Executed Task 9/10 Pixel evidence for the committed Task 10 artifact includes 15/15 relevant instrumentation passes; selected breakdown and runtime evidence follow:

- Direct MoveNet public/generated fixture path: 1/1 GREEN.
- Generated-bitmap frame ownership, cleanup, and inference path: 7/7 GREEN.
- Installed manifest/permission boundary: 1/1 GREEN.
- Packaged main public-reference drawable reproduces the exact fixed 17-landmark MoveNet observation: 1/1 GREEN; production and instrumentation notices retain the exact source, CC BY 4.0 license, and SHA-256, and the card visibly credits Google AI Edge.
- Generated-byte owned-reservation publication and foreign-final no-clobber: 2/2 GREEN.
- Real reducer-command rear-camera exactly-three JPEG capture plus repeated-token collision/no-clobber: 1/1 GREEN; zero candidate/temp residue after cleanup.
- Real cadence counters: 61 frames received, 21 accepted, 40 skipped too soon, 0 stale; 9.64 analyzed results/s for the bounded sample.
- Generated-black direct MoveNet latency over 25 measured runs after warm-up: p50 124.79 ms, p95 126.53 ms, max 126.64 ms.
- One final-hash 60-second cadence-limited live run: camera active for all 13 samples; mean CPU 148.08%, max CPU 162.0%, post-20-second mean 150.44% in a 140–162% range; PSS 256,793–332,419 KiB with a 324,211–332,419 KiB post-warm range; RSS 408,708–486,192 KiB with a 477,684–486,192 KiB post-warm range; battery 31.0°C to 30.8°C; thermal status 0; no fatal/ANR; 14 GC log entries; camera release within the observed 400–500 ms window after backgrounding. Memory remained bounded. The integrated fixed-reference UI did not reproduce an earlier lower-CPU run and makes no performance-improvement claim; the 15-minute Gate 4 soak remains pending.
- Permission denial screen, explicit system permission-dialog launch, live rear preview, READY/no-person diagnostics, named fixed-reference evidence, bounded reference card, and background release were visually inspected without retaining screenshots or private frame data.
- Public-target overlay acceptance reported one person and 17/17 landmarks; the rear-unmirrored live skeleton and distinct fixed-reference ghost guide shared one preview transform. The live skeleton tracked the bundled meditation figure's head, shoulders, arms, hips, crossed legs, and ankles without clipping. The UI labeled framing as not evaluated, each stable pass/fail prototype gate as uncalibrated, and capture lock as disabled; numeric scores remain internal evidence rather than continuously animated text. The transient screenshot was deleted.

## Pixel 6 real-device acceptance

**Target:** Pixel 6 running Android 16. Rear camera is the only MVP camera acceptance path.

The exact candidate APK digest must be recorded and used for every item below:

- Full-body and three-quarter-body framing at practical mounted-phone distances.
- Five-pose no-touch completion with five unique confirmed capture tokens.
- Automatic and manual capture each use the same exactly-three-output private protocol; manual bypasses only match/lock gating.
- Exactly three durable authoritative private outputs, one committed Room confirmation/advance/receipt/outbox transaction, and exactly-once sequence advancement before any MediaStore export is required.
- Exclusive-claim post-confirmation MediaStore export, including a paused two-worker one-insert race, exact-URI resume, and an injected missing-URI ambiguous-create case that never issues another create or mutates a foreign row.
- Phone speaker output and connected-earbud output through Android's current media route, without forced Bluetooth routing; verify the selected voice does not require a network connection and visual-only fallback works when no offline voice is available.
- Camera permission denial and recovery.
- App background/foreground and safe pause/resume behavior.
- Process restart before a shoot and at the pre-/post-Room capture boundary.
- Private capture, Room confirmation, export, cleanup, and reconciliation failures without accidental recapture or advancement.
- Shoot deletion and clear-data/uninstall behavior, explicitly verifying that already exported MediaStore items are not promised to be deleted.
- Exact-candidate packaged backup manifest and rule-resource inspection, plus authorized backup/restore behavior where platform tooling permits.
- Low light, partial occlusion, no person, subject exit/re-entry, and a second person entering frame.
- Default horizontal mirror matching and per-pose opt-out.
- Airplane-mode operation after app and model installation.
- Authorized backup/restore inspection where Android tools permit, confirming no sensitive capture, Room, outbox, preference, tombstone, or quarantine state is transported or partially restored.
- Sustained 15-minute camera analysis with inference latency, dropped frames, allocations/GC pressure, thermal behavior, battery impact, and analysis-resource cleanup recorded.
- Storage and log inspection confirming that analysis frames, raw landmark arrays, private paths, and MediaStore URIs are not retained or logged contrary to [the privacy contract](PRIVACY.md).

Failure must be recorded honestly. Thresholds may not be lowered merely to manufacture a pass. Any code change creates a new APK digest and requires the affected same-digest reviews to run again.

## Match calibration evidence

A calibration report must identify:

- Authorized positive and negative fixture classes without committing private photos.
- Dataset size and population limits.
- Positive/negative score separation and false-lock cases.
- Coverage, framing, angular, positional, acquire, release, and dwell values.
- Time to lock, duplicate-capture rate, inference latency, and cue rate.
- Boundary fixtures that prevent chosen thresholds from drifting silently.

The report may support a development or tested-device threshold claim. It may not imply population-wide accuracy from a small internal dataset.

## Claim rules

Until the relevant gate passes, documentation and UI must use future or planned language. Passing a gate supports only the behavior, artifact, device, and conditions recorded in its evidence.

No test may turn “pose match” into “perfect pose,” claim that skeleton similarity guarantees a good photograph, or conceal limitations involving viewpoint, depth, occlusion, hands, lighting, expression, composition, or aesthetics.

## Related documents

- [Product contract](PRODUCT.md)
- [Architecture](ARCHITECTURE.md)
- [Privacy](PRIVACY.md)

## Guided calibration screen

Task 16N's debug-only helper has eight host tests for directional framing guidance and shared preview geometry. The full JVM suite now passes 722/722. The Android test APK includes `CalibrationGuideScreenTest` (two no-camera synthetic layout/rendering checks) and the explicitly selected `collectOneAuthorizedGuidedFramingSequence` method. The two synthetic Pixel screen tests passed 2/2 in 0.120 seconds on the verified APK pair; the subsequent guided collection passed mechanically with 96 one-person frames, but all failed canonicalization with only 8–10 qualified landmarks and two torso anchors. See the [camera result](validation/2026-09-25-task16n-guided-framing-pixel6.md). The guided camera protocol uses a new reference-aspect viewport and a separate dataset; historical portrait reports are not unchanged-setup controls. See the [Task 16N record](validation/2026-09-25-task16n-alignment-guide.md).

## Spoken calibration guidance

Task 16O adds six debug host checks for participant-relative speech cues, stability/cadence and complete-body readiness. Full host verification passes 728/728 with zero lint errors. The exact installed Pixel pair passed `CalibrationSpeechOutputTest#installedOfflineVoiceCompletesAudibleCheck` 1/1 in 6.950 seconds without a camera. The participant confirmed clear audibility. The first `collectOneAuthorizedSpokenGuidedSequence` attempt failed complete-body readiness in 22.796 seconds, completed its stop announcement and produced no report. The subsequent Task 16P landscape run passed collection 1/1 with 95 fully evaluated frames, passing centering but failing scale and pose criteria; broader audio-route/failure acceptance remains pending. See the [landscape result](validation/2026-09-25-task16p-landscape-spoken-pixel6.md). See the [attempt record](validation/2026-09-25-task16o-spoken-readiness-pixel6.md). See the [speech record](validation/2026-09-25-task16o-spoken-guidance.md).

Task 16Q addresses participant feedback about rushed spoken adjustments: 30-second preparation, completion-based eight-second pauses and a quiet final stretch. See the [timing update](validation/2026-09-25-task16q-settling-time.md); physical verification remains pending.

Task 16R adds one spoken “Good” after a requested adjustment is confirmed for one second, preserving eight quiet seconds afterward. Host checks pass 732/732; the [camera attempt](validation/2026-09-25-task16r-confirmation-pixel6.md) stopped at complete-body readiness before measurement, with cleanup complete. The [confirmation record](validation/2026-09-25-task16r-adjustment-confirmation.md) has the next protocol and artifact hashes.

The user-requested Task 16S repeat on unchanged artifacts passed collection with 91/91 fully evaluated frames and passing angular similarity. Framing and positional/overall criteria still prevented a lock. Cleanup passed. Solo camera-adjustment timing/process is deferred at the user’s request. See the [repeat result](validation/2026-09-25-task16s-adjusted-framing-pixel6.md).

Task 16T adds reference-specific arm coaching before fine framing and confirms each corrected arm with “Good.” Host checks pass 740/740; physical verification is pending. Participant testimony supersedes Task 16S’s positive label: arms intentionally differed, so its corrected analysis is a negative example with zero false locks, not positive-match evidence. See the [arm-coaching and ground-truth record](validation/2026-09-25-task16t-arm-coaching.md).

Task 16U fixes the preview startup race that stopped the first arm-guidance attempt before camera/speech initialization. Both camera-free native regressions pass; physical arm-coaching verification is still pending. See the [startup fix and retry protocol](validation/2026-09-25-task16u-preview-startup.md).

Task 16V completed the preview-fixed arm-guidance collection with 97 fully evaluated frames. Completed cues: LEFT_ARM=1, GOOD=0; no match lock. Cleanup passed. Arm correction and final positive ground truth remain unconfirmed pending participant feedback; this is not a coaching success or accuracy result. See the [arm-guidance retry](validation/2026-09-25-task16v-arm-guidance-pixel6.md).

Task 16W adds bounded confirmation diagnostics after the participant reported completing the left-hand adjustment without hearing “Good.” It measures eligible elbow/wrist error, evidence gaps and remaining time without changing guidance rules. Host checks pass 745/745; physical cause remains unconfirmed. See the [diagnostic record](validation/2026-09-25-task16w-arm-confirmation-diagnostics.md).

Task 16X isolated the missing confirmation: both elbow/wrist errors stayed outside tolerance despite 12.985 seconds after speech and continuous tracking. The unlocked retry collected 96 fully evaluated frames and cleaned up successfully; no Good or match lock occurred. This is a diagnosed rejection condition, not a completed coaching fix. See the [diagnostic result](validation/2026-09-25-task16x-arm-confirmation-pixel6.md).

Task 16Y adds participant-relative elbow/hand directions and confirmation of the requested joint, with 752/752 host tests passing. The first live run collected 96 fully evaluated frames: LEFT_WRIST_UP=1, GOOD=0. The wrist entered tolerance for at most 200 ms, below the one-second confirmation requirement. Cleanup passed; successful physical confirmation and participant feedback remain pending. See the [directional coaching record](validation/2026-09-25-task16y-directional-arm-guidance.md) and [Pixel result](validation/2026-09-25-task16y-directional-arm-pixel6.md).

Task 16Z responds to the participant's clarification that “slightly” prompted a quick reversal. Arm directions now end with “then hold it there,” and spoken preparation lasts at least 60 seconds to allow follow-up while retaining eight-second pauses. All 754 host tests and five camera-free Pixel timer checks pass. The verified update is installed; physical confirmation remains pending Ready. See the [coaching follow-up record](validation/2026-09-25-task16z-coaching-followup.md).

Task 16AA makes coaching progression depend on fresh stillness as well as target position. Follow-up directions retain the eight-second pause. After a 30-second initial allowance, resolved and settled guidance can start the hold; the 60-second deadline instead stops unresolved preparation with an explanation. All 766 host tests and six camera-free Pixel timing checks pass. The first camera run completed Good once for the left wrist, then stopped at the readiness deadline without hold or measurement. Cleanup passed; the participant later confirmed hearing Good while following the cues into a hovering hand position. See the [Pixel result](validation/2026-09-25-task16aa-movement-guided-pixel6.md). See the [movement-aware coaching record](validation/2026-09-25-task16aa-movement-aware-coaching.md).

Task 16AB makes the supported resting position explicit: wrist cues name the same-side knee and palm-up rest, and elbow cues preserve hand support. A closed preparation result/reason summary identifies any remaining readiness blocker without private motion samples. All 768 host tests pass. The first supported-hand run repeated the right-hand cue three times with no Good and stopped before measurement; terminal stillness passed, while arm position and framing failed. Cleanup passed. The participant reported the hand already on the knee and no clear way to adjust, prompting Task 16AC. See the [supported-hand coaching record](validation/2026-09-25-task16ab-supported-hand-coaching.md).

Task 16AC aligns the hand check with its instruction: each wrist now targets the participant's own knee in the camera view, while elbows retain reference targets. An unresolved hand receives one explanation instead of repeated identical commands; settling, confirmation and the stop deadline remain enforced. All 774 host tests pass. The first physical run completed the hand cue and explanation once each but still stopped before measurement. The participant confirmed the hand stayed supported and the explanation was clear. Cleanup passed; Task 16AD prepares a controlled knee-versus-lap comparison. See the [knee-relative coaching record](validation/2026-09-25-task16ac-knee-relative-coaching.md).

Task 16AD prepares a separate spoken hand-position comparison: supported knees, then hands in lap, with 30/15-second adjustment allowances and two 10-second measurements. It retains only bounded scalar summaries and requires fresh stillness; coaching thresholds stay unchanged. All 784 host tests pass, both APKs build, and the final main APK is installed/hash verified. The physical comparison is pending fresh Ready. See the [comparison protocol](validation/2026-09-25-task16ad-hand-placement-comparison.md).
