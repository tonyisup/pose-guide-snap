# Development checkpoint — 8 September 2026

Branch: `codex/development-takeover`, based on `32a1429`.

The first development batch implements the four defects identified in the [project audit](PROJECT_AUDIT_2026-09-04.md). Host verification and the six authorized Android runtime checks are complete. All six passed on the Pixel 6; see the [validation record](validation/2026-09-04-audit-repair-pixel6.md). Tasks 14B.1B, 14B.1C, 14B.2, corrected Task 14B.3, and Task 15A are also implemented locally; their exact 6/6, 8/8, 6/6, 3/3, and corrected 4/4 Pixel 6 checkpoints pass. Task 16E passed the static model contract on the Task 16D pair, Task 16F proved that varied live-camera pixels reach the detector, corrected Task 16G found positive but sub-threshold person/keypoint evidence, and Task 16H's exact positive/empty-scene distributions overlapped. Task 16I then reused the Task 16H binaries in better lighting over wireless ADB and obtained 96/96 one-person frames with all 17 landmarks qualified. Task 16J then obtained 97/97 no-person frames from the same-setup empty view; Task 16K then evaluated 97 intended reference-match frames but failed framing throughout. Task 16L's component diagnostic then measured 97 frames with center alignment lower than scale throughout; both missed the existing requirement. Production thresholds and automatic capture remain unchanged.

## Implemented

| Audit issue | Result | Evidence and limits |
|---|---|---|
| Matching changed with image aspect ratio | Observations carry upright image dimensions. Matching uses equal geometric units before torso normalization; overlays retain image-normalized coordinates. | The same public pose passes angular, positional, and overall gates across portrait, square, and landscape framing. The regression failed before the correction. This does not establish real-world lock accuracy. |
| Interrupted imports could block every shoot permanently | The production editor invokes ledger-based recovery before observation and retry. A shared process lock serializes entire imports and recovery across editor owners. Unreadable or unfinished recovery leads to a retryable unavailable state. Repair guidance has a reachable action. | Unit tests prove observation ordering, cross-owner exclusion, incomplete-recovery blocking, retry, and clock rollback/overflow behavior. A new Android test uses the actual production composition after database close/reopen, then verifies another shoot can import. The exact close/reopen test passed on the Pixel 6. |
| Shoot list retained old reference counts | Room cross-instance invalidation is enabled for the existing independently owned databases. | A new Android regression waits for both notification clients to register, observes the list, and makes exactly one write through the other database owner. The exact notification test passed on the Pixel 6. |
| Permanent camera denial offered an ineffective request | The permission screen distinguishes first request, ordinary denial, and a settings recovery action. It rechecks permission on return and scrolls on small screens. | Decision tests pass. The Compose test passed on the Pixel 6 and verifies which callback each button invokes; real Android permission dialogs and return-from-settings behavior remain pending. |

No Room schema, model artifact, or dependency version changed. The residual-journal correction previously described as uncommitted is present in `382a659`; current status documents now reflect that fact without inventing historical approvals.

## Verification

The final host gate is:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline
```

- Unit tests: **709 passed**, zero failures, errors, or skips.
- Python analyzer tests: **12 passed**.
- Lint: zero errors; 9 warnings.
- Debug app, unsigned release app, and Android-test APK assembly: passed.
- Causal recovery check: disconnecting the recovery hook, removing import serialization, and ignoring outstanding work caused the three targeted regressions to fail. Restoring the fixes passed the full suite.
- Independent review covered geometry/permissions and import recovery/database observation. The identified notification-test race and global incomplete-recovery gap were corrected.
- All four checked-in Room schemas remain byte-identical to the branch baseline. Debug/release app permissions remain camera plus AndroidX's app-signature receiver permission. The instrumentation APK requests only `REORDER_TASKS`. None requests Internet permission.
- Final Task 14B.2 artifacts: debug `bd080ac0dbeccd8678f7a4aca4b4503f21623361c1460faab1a5cd5aad51595b`; Android test `41f33975475691f67db64ba2f3761b7988ae7301d8d7c7c36e5430a7928ec7b2`; unsigned release `9f8239a80a6d2bd5f435afc507b9872aaceff1488e2fb0aa854e2f3b7be5591d`.
- Corrected frozen Task 14B.3 candidate: debug `50dc9ab424da5120f030a727d23c1034af7a222201869ece8e72c9b3648a3ec0`; Android test `2d893a6a6cd3578b97d06dd3e92f465cff4ff89e909ed48b5ec3b135a8385e0c`; unsigned release `0ff77ed8657103de5822a50ffa3260cd9fa02664e0234573c2c85200492e088d`.
- Corrected frozen Task 15A candidate: debug `336e4f662f98beee6a52a370fec658b97dbdf6ba47fe9163b102d584ccbacad7`; Android test `65637d4a7958c7d6b39cb266f90d0c46980547a8e1fec52b227b79e63e9e8a96`; unsigned release `12064e8412632cb454c973f822cd8f243524d3494eb0bb235d940ca0faede94c`.
- Task 16A host-only candidate: debug `43f5e5988a4cb319d00d17efe0a3f32e35f6dab61683f07b0db0e5774a0535cd`; Android test `65637d4a7958c7d6b39cb266f90d0c46980547a8e1fec52b227b79e63e9e8a96`; unsigned release `d42412d9cc1f72db4aa17378f3e5ee97fa77862bbc73fccdbe9b994eefc041d2`. No device invocation is claimed for this framing/policy artifact.
- Task 16B device-tested candidate: debug `339f7597960a959f53fa79b168c838e4b25e325ea814e5d3f7d3bce83afc7d76`; Android test `e75d82674b8ee361075f1872cde37eb4585eca37470cb19999899f75c5025e5d`; unsigned release `27f397d0a4d517b67a013c868c11d5df63ebf390f400b5254c06e2f119fcf3d5`. The separately authorized 2/2 Pixel collection and cleanup are recorded in the Task 16B validation record.
- Task 16C first device-tested candidate: debug `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4`; Android test `71925174c5796febc83ccf48ef5bb101c62ab72c375b157a701c4c404bf251c2`; unsigned release `50437b7e4651446e374204edb6095a0ddd23803fcdf5b71c7e10b4112473a769`. Its separately authorized 1/1 no-person Pixel diagnostic and cleanup are recorded in the Task 16C validation record.
- Task 16C preflight-corrected device-tested candidate: unchanged debug `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4`; Android test `8b2d4e81440b11878bf0635c563d305985b25b600c3f385341a75088d726d4e4`; unchanged unsigned release `50437b7e4651446e374204edb6095a0ddd23803fcdf5b71c7e10b4112473a769`. Its exact repeat failed safely before recording and completed cleanup.
- Task 16C observable-preflight device-tested candidate: unchanged debug `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4`; Android test `d80047d2ecc39a011c345c50e3e2b51f5cba32aea1e6126d5b40a23f3780135b`; unchanged unsigned release `50437b7e4651446e374204edb6095a0ddd23803fcdf5b71c7e10b4112473a769`. Its exact repeat failed safely before recording with 146/146 no-person frames and completed cleanup.
- Task 16D device-tested candidate: debug `16843f2b88f11116161ec81ecbf460b691eac97fa4b1123248dbff426852e787`; Android test `488eb4b615f74479d06824f29c711cbadaf24013778acd35a3cf1fbfdcddac7e`; unsigned release `c7aae95d26bc295ab42f5dad7688814f4775df95cacf567fe668df7f3bc696ba`. Its exact Pixel diagnostic failed safely before recording with 146/146 no-person frames, a maximum valid raw score of `0.0`, and complete cleanup.
- Task 16E same-artifact static recheck: unchanged debug and Android-test hashes above. The exact public-fixture method passed 1/1 in 0.669 seconds and completed cleanup, proving current-artifact model/package/static-preprocessing/inference/mapping health without opening the camera or reading private media.
- Task 16F device-tested candidate: debug `a47f4588346abdbd7d569d2a02de38a29c12e41abfc5a169e8d8eceee3f52d94`; Android test `d04404d44440ff165ab6f74b341026dd8900531d115387472316e1970d68ae98`; unsigned release `74ee660bd84b421ce9dd5cf7a42d72e72e4d108720fc7b4be99ac7e3df54c9e3`. Its exact Pixel diagnostic proved the live input was neither black nor flat and completed exact cleanup.
- Corrected Task 16G device-tested candidate: debug `28c3820a2780fb744c37d056b4742eb71eab9249c9947deb411c4089c6e57dcd`; test-only diagnostic APK `d69892e64e8f9041f098ea3c249918c37037d54ba72ab916777a0fd02c2e4808`; unsigned release `c6935eb3a14221a8c7655665595da0f47fc17c595bf7e6c6749d125b9b86010a`. Its final exact repeat found positive but sub-threshold live person/keypoint evidence across 145 frames and completed exact cleanup.
- Task 16H exact device candidate: debug `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66`; Android test `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9`; unsigned release `996b45a89d91c6aac51cb6dac92900dcd32d32557aef2118832b2741520a21ae`. Its exact full-body and empty-scene methods each passed 1/1 with 97 frames, matching report hashes, overlapping score distributions, and complete cleanup. See the [Task 16H validation record](validation/2026-09-08-task16h-detector-gate-calibration-pixel6.md).

Task 14B.1B adds a Room-owned per-file compare-and-set service, strict evidence and causal clocks, reconciliation markers, and a deletion interlock for exactly four admitted stages. Raw SQLite storage is checked before typed mapping, including byte-equivalent identity aliases, and deletion requires the same exact three-row/path/lifecycle graph as bootstrap. The host suite is now **652 passed**, Android-test compilation succeeds, and three two-owner tests deterministically pause a real transaction after its first write to force deletion-first, admission-first, and settlement-first orderings. No schema, dependency, permission, UI, CameraX, filesystem, or MediaStore behavior changed.

Task 14B.1C removes caller-provided private output authority from `confirmAndAdvance`. First application now requires exactly three coherent, ordered `FINAL_DURABLE` journal rows and derives each immutable output's path, byte count, capture time, and SHA-256 from them. Room revalidates those rows after all immutable writes, deletes exactly three, and commits output, attempt, session, receipt, outbox, export targets, and journal consumption together. Replay requires zero residual byte-equivalent journal rows. Focused Android coverage includes malformed and partial authority, every causal timestamp boundary, rollback after journal deletion and post-write drift, reopen replay, duplicate writers, deletion contention, and Packet 2B snapshot isolation. The 652-test JVM suite and Android-test compilation pass. No schema, dependency, permission, UI, CameraX, filesystem, or MediaStore behavior changed.

Task 14B.2 adds two stored outcomes without a schema change. `FAILED_CLEANED` consumes untouched or fully cleaned journal rows atomically and allows the next attempt on the same pose. `RECONCILIATION_REQUIRED` retains coherent ambiguous rows, blocks retry, and survives restart. Bootstrap now derives progress from confirmed attempts while counting failed history, and settlement replays validate residual authority. The full host gate passes 655/655, and the exact six-method Pixel 6 checkpoint covers retry, reopen, confirmation deferral, rollback, deletion authority, and concurrent settlement. Migrated unfinished attempts without V4 journal rows remain fail-closed for Task 14B.3 filesystem reconciliation. No dependency, permission, schema, UI, CameraX, filesystem, or MediaStore behavior changed.

Task 14B.3 adds a journal-aware store for exact paths below an injected `noBackupFilesDir` root. It creates and verifies an exclusive empty final reservation plus temporary file, syncs and hashes positive bytes, atomically publishes only through the owned reservation, and deletes only exact evidence-compatible files. Android uses the existing libc-backed inode/device ownership and directory-sync adapter. A cleanup-only Room lane can advance an aggregate reconciliation attempt without reopening write/publication authority. Startup recovery settles a migrated journal-free attempt only after all nine deterministic paths are absent; any exact present or ambiguous path remains blocked and untouched. Generated-file, coordinator, and bootstrap-clock tests bring the corrected host suite to 665/665. The first Pixel candidate passed the two journal-free cases but safely left journaled cleanup outstanding because bootstrap still rejected cleanup-row clocks newer than the aggregate attempt. The correction accepts newer clocks only for the three cleanup stages and keeps newer write/publication/quarantine stages invalid. The corrected exact 3/3 Pixel checkpoint passes in 0.785 seconds. No schema, dependency, permission, UI, CameraX, MediaStore, or personal-media behavior changed. See the [Task 14B.3 validation record](validation/2026-09-05-task14b3-journaled-recovery-pixel6.md).

Task 15A adds the first product-facing manual guided-capture composition. Startup settlement now gates camera readiness; an already-durable three-file attempt confirms before bootstrap continues, while unresolved storage remains blocking. The route restores reducer state from Room, loads the exact selected validated reference, and owns an attachable CameraX writer, manual Capture, and Stop controls. One serialized coordinator registers and starts the command, admits each ordinal before CameraX writes, syncs, hashes, and publishes the exact private file, confirms from three final Room rows, and advances once only after the confirmation transaction succeeds or exactly replays. Stop defers route exit while accepted work settles. The 690-test host suite, lint, all APK assemblies, source review, generated-data composition, and corrected exact 4/4 Pixel 6 checkpoint pass.

The first live candidate used debug hash `3c9a5109f692654479ea91bbdad3d126867980e1e6f8a679193cc5d776b843ce`, Android-test hash `75e72c7025a68cd584d499cee0ebbadb9a32aedeb7cc24c3e09ca261d8db0ec5`, and unsigned-release hash `e91db9a5297c35e9ac81a171dea3a1da647fe817a18cef065c8a028b511a620d`. Its first invocation was blocked by a securely locked device: the generated integration passed while both Compose hosts and camera binding failed before product behavior. After unlock, both Compose methods and generated integration passed, and the live method reached a successful CameraX save. CameraX's file-target path then replaced the journal-owned temporary inode, so the store rejected durability and recovered rather than confirming or advancing. The corrected writer passes CameraX an already-open stream, preserving the admitted temporary file identity through close, sync, hash, and publication. The complete host gate passed again before freezing the corrected hashes above. The [candidate-one raw output](validation/2026-09-06-task15a-manual-guided-capture-pixel6-candidate1-output.txt) records the unlocked 3/4 invocation.

The completed Task 14B.3 Pixel checkpoint is limited to:

1. `CaptureAttemptStartupReconcilerAndroidTest#mixedJournalAndExactFilesRecoverAcrossDatabaseReopen`
2. `CaptureAttemptStartupReconcilerAndroidTest#journalFreeLegacyAttemptSettlesOnlyAfterExactAbsenceAcrossReopen`
3. `CaptureAttemptStartupReconcilerAndroidTest#journalFreeLegacyAttemptWithExactFileRemainsBlockingAndPreservesFile`

Every bounded checkpoint above used an explicit method list and recorded artifact hashes. Generated test residue was absent afterward, and the instrumentation package was removed. No live camera session, personal-image access, audio test, or release deployment was performed. The changes remain local and uncommitted.

## Completed Task 15A Android checkpoint

The corrected final checkpoint passed these four methods on the frozen Task 15A app/test pair in 5.235 seconds:

1. `GuidedCameraScreenTest#readyControlsExposeCurrentPoseAndInvokeManualCaptureAndStop`
2. `GuidedCameraScreenTest#captureRequiresReadyCameraAndStoppingDisablesBothActions`
3. `JournaledGuidedCaptureIntegrationAndroidTest#generatedManualCapturePublishesExactlyThreeThenConfirmsAndAdvancesOnce`
4. `JournaledGuidedCaptureIntegrationAndroidTest#authorizedPixelRealCameraPublishesExactlyThreeThenConfirmsAndAdvancesOnce`

The first two methods use synthetic state. The third uses generated bytes, a UUID database, and a UUID private root. The fourth opened the Pixel 6 rear camera, captured exactly three transient photos into its generated app-private root through the new journaled coordinator, confirmed and advanced once, then deleted that root and database. The checkpoint did not open the photo picker, read existing personal media, touch MediaStore, use audio, clear the main app's data, or deploy the release build. Installed hashes matched the corrected frozen pair, camera service reported no active client afterward, and the instrumentation package was removed. See the [Task 15A validation record](validation/2026-09-06-task15a-manual-guided-capture-pixel6.md).

## Completed Android checkpoint

Target: the project's Pixel 6. The user granted the separate permission required by the [private hardware gate](TESTING.md). The exact debug/test APK pair below was installed, and these six methods passed:

1. `RoomShootPreparationRepositoryAndroidTest#listObserverReceivesReferenceChangesFromAnotherDatabaseOwner`
2. `ReferenceImportStartupReconcilerAndroidTest#productionRuntimeRecoversAbandonedReservationAndUnblocksOtherShootsAfterReopen`
3. `CameraPermissionRecoveryFlowTest#permanentDenialOpensSettingsInsteadOfRepeatingPermissionRequest`
4. `ShootEditorFlowTest#loadingMissingAndUnavailableExposeRecoverySemantics`
5. `ShootEditorFlowTest#importAndReconciliationStatusRemainVisible`
6. `ShootEditorFlowTest#allocationRepairGuidanceAlwaysHasActionEvenWithoutLocalImportRows`

| Artifact | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `8b6fd37dfc938fcfad715788607b7b21552763cc74d9968c8ed2bf2b56afa0e0` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `89cb8ce3de86a9a104d3a710d948f573ea625b25def7e7b9509e82f751783cf5` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` — build evidence only | `39a6f86620a8153be5a7583a3d8bcd53db7e90ecc9d7729f65b8661baad2ce78` |

The database tests use generated UUID-named databases and an isolated UUID-named reference-recovery directory. The Compose tests use synthetic state and injected callbacks. They do not request camera permission, start the camera, open the photo picker, launch Settings, or access the production database. These checks therefore cannot establish actual permission-dialog behavior or live capture.

Cleanup is limited to those generated test databases, their SQLite/Room sidecars, the exact generated recovery directory, and the instrumentation package. Keep the target application installed and preserve its existing data; do not clear application data or remove user files. If installation cannot preserve the current app's data, stop and report the conflict.

## Completed Task 14B.1B Android checkpoint

The final reviewed Task 14B.1B host build produced the exact artifacts below. The private-hardware checkpoint ran on that exact app/test pair and passed all six methods in 3.866 seconds.

1. `RoomCaptureFileJournalAndroidTest#captureFileJournalAdvancesLegalTransitionsAndIsIdempotent`
2. `RoomCaptureFileJournalAndroidTest#captureFileJournalRejectsInvalidStorageAndCausalTimestampsWithoutMutation`
3. `RoomCaptureFileJournalAndroidTest#captureFileJournalCasHasOneWinnerAcrossWalConnections`
4. `RoomCaptureFileJournalAndroidTest#captureFileJournalPersistsAcrossReopen`
5. `DeletionExportRepositoryAndroidTest#captureFileJournalDeletionInterlockLinearizesAdmittedEffects`
6. `DeletionExportRepositoryAndroidTest#deletionRejectsBackwardAndAcceptsEqualCompleteAuthorityClock`

| Artifact | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `0b4f91958dc1ebf7ecebac1272cb6470e1ec11817a538b5f520fbe701bfb6868` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `544198b7b0ac19b0b9cf36e509122f60abe45d8bccb243a0e2c6fc1b44b6f3ba` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` — build evidence only | `d127c1b9740380ca4de647740a5a99dacb898b116578b0381c35dedbea09671a` |

The run used generated UUID-named test databases and Room/SQLite sidecars only. The post-run database-directory check was empty; the instrumentation package was removed and confirmed absent, while the app remained installed with its data preserved. The run did not start the camera, open the photo picker, access personal media, test audio, or deploy the release build. Full results and evidence limits are in the [Task 14B.1B validation record](validation/2026-09-04-task14b1b-room-file-admission-pixel6.md).

## Completed Task 14B.1C Android checkpoint

The corrected final host gate produced this exact candidate pair for eight focused methods:

1. `CaptureConfirmationRepositoryAndroidTest#confirmationDerivesOutputsFromFinalJournalAndConsumesRows`
2. `CaptureConfirmationRepositoryAndroidTest#confirmationRejectsBackwardAndAcceptsEqualBoundaryTimestamps`
3. `CaptureConfirmationRepositoryAndroidTest#confirmationReplayRequiresNoResidualJournalAcrossReopen`
4. `CaptureConfirmationRepositoryAndroidTest#confirmationFaultAfterJournalDeleteRollsBackEverything`
5. `CaptureConfirmationRepositoryAndroidTest#confirmationDeletionAndDuplicateRacesHaveOneWinner`
6. `GuidedSessionPacket2BAndroidTest#immediateBootstrapBlocksConfirmationWriterAndReturnsCompletePreThenPostState`
7. `GuidedSessionPacket2BAndroidTest#nontransactionalConfirmationReadsCanProduceMixedPreSessionAndPostReceiptState`
8. `GuidedSessionPacket2BAndroidTest#repeatedBootstrapsAreReadOnlyAcrossEveryV4AuthorityTableAndSchema`

| Artifact | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `9746a8d1fa095352a826ec77c0e2c4568e957be6003503e66efd1a4e3c18c4c5` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `975eac3620a7b3a1a6b814c8f8aa822c42df1953fce14dc289bdd6cd785a8ce1` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` — build evidence only | `1698bc4a9899834a027c6c7ea043f18eea659421cb288b9edacfa9ad481ee442` |

The user authorized this private-hardware run under the [testing contract](TESTING.md). The first five-method candidate safely rejected every backward clock but mislabeled one journal-clock case as invalid authority. After the classifier was corrected and the complete host gate passed again, the final app/test pair passed the five confirmation methods in 4.401 seconds and the three Packet 2B methods in 1.257 seconds. Installed hashes matched the local artifacts. Generated database residue was absent, the instrumentation package was removed, and the main app remained installed with its data preserved. See the [Task 14B.1C validation record](validation/2026-09-04-task14b1c-journal-confirmation-pixel6.md).

## Completed Task 14B.2 Android checkpoint

The final host gate produced this exact candidate pair for six focused methods:

1. `CaptureAttemptSettlementRepositoryAndroidTest#cleanedFailureSettlesAtomicallyAndAllowsNextAttempt`
2. `CaptureAttemptSettlementRepositoryAndroidTest#unfinishedFailureBecomesBlockingReconciliationAcrossReopen`
3. `CaptureAttemptSettlementRepositoryAndroidTest#finalDurableFailureSettlementDefersToConfirmationWithoutMutation`
4. `CaptureAttemptSettlementRepositoryAndroidTest#faultAfterSettlementJournalDeleteRollsBackAttemptJournalAndSessionClock`
5. `CaptureAttemptSettlementRepositoryAndroidTest#failedCleanedHistoryRemainsValidDeletionAuthority`
6. `CaptureAttemptSettlementRepositoryAndroidTest#concurrentCleanSettlementHasOneAppliedWinnerAndOneValidatedReplay`

| Artifact | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `bd080ac0dbeccd8678f7a4aca4b4503f21623361c1460faab1a5cd5aad51595b` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `41f33975475691f67db64ba2f3761b7988ae7301d8d7c7c36e5430a7928ec7b2` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` — build evidence only | `9f8239a80a6d2bd5f435afc507b9872aaceff1488e2fb0aa854e2f3b7be5591d` |

Two earlier candidates failed safely in shared setup: first because a one-pose fixture violated the production session invariant, then because the expanded fixture's first pose identifier did not match the command. No settlement mutation ran in either case. After narrowing the registration guard to its storage-sensitive attempt facts and correcting the test fixture, the final pair passed 6/6 in 1.376 seconds. Installed hashes matched, cleanup left no generated database residue, the instrumentation package was removed, and main-app data was preserved. See the [Task 14B.2 validation record](validation/2026-09-04-task14b2-attempt-settlement-pixel6.md).

## Next implementation slice and product milestone

Task 15A's corrected exact Pixel checkpoint, the Task 16A calibration harness, and Task 16B's bounded collection are complete. Task 16B did not calibrate the matcher because all 97 positive frames failed canonicalization before scoring. Task 16C's first exact diagnostic classified all 97 frames as `no-person`; its corrected repeat then stopped before recording because the warm-up did not reach five consecutive exactly-one-person frames.

1. **Calibrate before enabling automatic capture.** Collect a bounded positive/negative matching set, define required body visibility, and document threshold separation on the target Pixel 6.
2. **Reuse the Task 15A protocol.** An automatic trigger may bypass neither Room admission nor exact private-file durability, confirmation, recovery, or one-advance authority.
3. **Keep export, deletion, and speech separate.** MediaStore execution, physical deletion, and audio each retain their own acceptance and privacy gates.

The manual milestone is boundedly host- and device-complete: the exact live method proved one selected-session command produces three durable private photos and one advance on the frozen candidate. Automatic capture stays disabled until calibration evidence supports its lock behavior.

## Task 16A calibration harness checkpoint

The runtime now derives framing from independent subject-center and body-scale evidence. It requires at least 13 shared confidence-qualified MoveNet points, all four shoulder/hip anchors, and head plus bilateral arm/lower-leg representation. The full qualified reference extent stays in the comparison so missing live extremities cannot disappear from both boxes. Unavailable evidence fails closed without retaining landmark payloads. The analyzer consumes only a closed set of derived scalar scores and event observations. It rejects raw landmark fields, private-image markers, unknown fields, invalid ranges, framing-policy values outside the production contract, inverted acquire/release thresholds, and non-increasing relative time. It replays the same inclusive mandatory acquisition gates, lower lock-retention gates, 500 ms acquire dwell, and 200 ms release hysteresis as the current development policy, then reports separation, false locks, time to lock, latency, cue rate, false captures, and duplicate capture commands.

The checked-in dataset contains 8 hand-authored sequences and no detector or human data. Its 3/3 positive locks, 1/5 deliberate negative false lock, 0/8 duplicate-capture sequences, and negative all-frame separation margins verify honest failure reporting rather than matching accuracy. Six Python tests pass. JVM fixture tests keep the analysis policy synchronized with the Kotlin defaults, framing tests cover its fail-closed scalar boundary, and matcher/reducer/architecture tests prove that release-only evidence retains but cannot acquire a lock. See [MATCH_CALIBRATION.md](validation/MATCH_CALIBRATION.md) and [PERFORMANCE.md](validation/PERFORMANCE.md).

## Task 16B bounded derived collector checkpoint

The separately selected Android method requires the exact derived-collection marker, pseudonymous lowercase identifiers, an explicit positive or negative label, a 3–15 second warm-up, and a 5–30 second window. It asserts Pixel 6 hardware and pre-granted camera permission, then reuses the full-screen production rear-camera crop, bundled public reference, and live match evaluator. Every callback is reduced immediately to relative time, person count, five scores, mirror choice, and null/zero event observations. At most 600 frames can enter the deterministic report; no image, raw landmark, tensor, path, URI, wall-clock time, identity, or throwable is retained.

The Kotlin producer matches a checked-in document byte-for-byte, and the Python consumer parses that same fixture. The Task 16B host gate passed 708/708 JVM tests, 8/8 Python tests, lint with zero errors and 9 warnings, and all APK assemblies. Its separately authorized positive and negative Pixel 6 invocations both passed with 97 frames; device and pulled report hashes matched, and exact cleanup completed. The negative sequence evaluated throughout and never locked. The positive sequence detected one person throughout but produced five zero scores on every frame, which under the frozen runtime means canonicalization failed before matching. The combined result is a safe calibration failure, so no threshold changed. See the [Task 16B validation record](validation/2026-09-08-task16b-derived-calibration-pixel6.md).

## Task 16C canonicalization diagnostic checkpoint

Schema v2 adds one closed evaluation status and two bounded counts: total confidence-qualified MoveNet landmarks and qualified shoulder/hip anchors. These values are derived inside the callback; no landmark identity, coordinate, observation, image, path, URI, timestamp, or throwable enters the retained frame. The analyzer remains compatible with Task 16B's v1 reports, requires all diagnostics for v2, rejects inconsistent status/count combinations, and refuses lock replay from unevaluated v2 frames.

The full host gate passes 708/708 JVM tests, 12/12 Python tests, lint with zero errors and 9 warnings, and all APK assemblies. Room V1–V4 hashes and packaged permissions remain unchanged. The first device-tested hashes were debug `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4`, Android test `71925174c5796febc83ccf48ef5bb101c62ab72c375b157a701c4c404bf251c2`, and unsigned release `50437b7e4651446e374204edb6095a0ddd23803fcdf5b71c7e10b4112473a769`.

The separately authorized `positive-centered-diagnostic-a` invocation passed 1/1 in 16.877 seconds and produced 97 frames with an exact device/host report hash match. All were `no-person`, with zero confidence-qualified landmarks and torso anchors, zero scores, no lock, and no capture command. Exact device output, test package, and temporary APK copies were removed; the main app remains. This did not reproduce Task 16B's one-person canonicalization failure.

The collector now requires warm-up to end with five consecutive exactly-one-person frames and otherwise fails without a report. That host-only correction changes only the Android-test hash to `8b2d4e81440b11878bf0635c563d305985b25b600c3f385341a75088d726d4e4`; debug and unsigned release remain byte-identical. The corrected full host gate passes. Its separately authorized `positive-centered-diagnostic-b` repeat verified both installed APK hashes, then failed 1/1 after 6.522 seconds because the warm-up condition was absent. Recording never began and no report was created. Cleanup removed the empty export directory and test package, closed the camera, and preserved the main app and unrelated data. The preflight therefore works, but the earlier one-person canonicalization failure remains unexplained. See the [Task 16C validation record](validation/2026-09-08-task16c-canonicalization-diagnostic-pixel6.md).

The next correction makes a failed preflight actionable without widening the retained-data boundary. The on-device status distinguishes no person, one person with consecutive-frame progress, and multiple people. A failure assertion reports only six aggregate frame counters and still leaves no report. The full host gate remained 708/708 JVM and 12/12 Python tests with zero lint errors and 9 warnings; Room schemas, debug/release bytes, and permissions were unchanged. The exact `positive-centered-diagnostic-c` Pixel invocation used Android-test hash `d80047d2ecc39a011c345c50e3e2b51f5cba32aea1e6126d5b40a23f3780135b`, analyzed 146 frames during its 15-second warm-up, and classified every frame as no-person. It failed before recording in 16.538 seconds and completed exact cleanup. This rules out missing analyzer cadence but leaves the raw instance-score boundary unobserved.

## Task 16D person-score diagnostic checkpoint

The MoveNet raw-output boundary now reduces all six person slots immediately to one nullable maximum valid instance score in `[0, 1]`. The immutable analyzed frame carries that scalar without any slot identity or tensor surface. The calibration preflight retains only the maximum seen during its bounded window and includes it in a failed assertion beside the unchanged `0.25` minimum accepted score. Neither value enters the persisted calibration schema, and no threshold changed.

The full host gate passes 709/709 JVM tests and 12/12 Python tests, with zero lint errors and 9 warnings and all APK assemblies successful. Room V1–V4 hashes and permissions are unchanged. Frozen hashes are debug `16843f2b88f11116161ec81ecbf460b691eac97fa4b1123248dbff426852e787`, Android test `488eb4b615f74479d06824f29c711cbadaf24013778acd35a3cf1fbfdcddac7e`, and unsigned release `c7aae95d26bc295ab42f5dad7688814f4775df95cacf567fe668df7f3bc696ba`.

The separately authorized `positive-centered-diagnostic-d` Pixel method verified the installed main and test bytes, then failed 1/1 before recording after 16.594 seconds. All 146 analyzed frames were no-person, exactly-one-person and multiple-person counts were zero, and the strongest valid raw instance score across the entire warm-up was `0.0` beside the unchanged `0.25` minimum. No report was created. Exact cleanup removed the empty export directory, test package, and temporary pulls, stopped the camera, and preserved the installed main app, its data, and unrelated files. The result rules out a borderline threshold rejection; the next check should run the existing packaged public-fixture detector contract on this same artifact pair before adding another camera diagnostic.

## Task 16E static model recheck

The separately authorized exact public-fixture method ran on that same frozen main/test pair and passed 1/1 in 0.669 seconds. It verified model and fixture hashes, repeated one-person inference with all 17 mapped identities, generated zero/two-person controls, caller bitmap ownership, and detector cleanup. It opened no camera and read no private media. Cleanup removed only the test package and temporary pulls, preserved main-app data, and left no active camera client. The pass localizes the remaining investigation to the transient CameraX bitmap conversion/crop path or the live scene. See the [Task 16E validation record](validation/2026-09-08-task16e-static-movenet-recheck-pixel6.md).

## Task 16F CameraX bitmap statistics checkpoint

The upright CameraX crop now computes one immutable `FrameVisualStatistics` before inference by sampling a fixed at-most-16×16 grid and immediately reducing it to normalized mean luminance and luminance range. The analyzed frame carries only those two scalars. Calibration preflight retains only the minimum and maximum frame mean plus the maximum within-frame range and includes them only if warm-up fails. Nothing is added to the persisted schema, and model input, mapping, matching, threshold, and capture behavior are unchanged.

The full host gate passes 712/712 JVM tests and 12/12 Python tests with zero failures, errors, or skips. Lint reports zero errors and 9 warnings; debug, unsigned release, and Android-test assembly pass. Room V1–V4 hashes and packaged permissions are unchanged. Frozen hashes are debug `a47f4588346abdbd7d569d2a02de38a29c12e41abfc5a169e8d8eceee3f52d94`, Android test `d04404d44440ff165ab6f74b341026dd8900531d115387472316e1970d68ae98`, and unsigned release `74ee660bd84b421ce9dd5cf7a42d72e72e4d108720fc7b4be99ac7e3df54c9e3`.

The separately authorized `positive-centered-diagnostic-e` Pixel method verified the installed main and test bytes, then failed before recording after 16.57 seconds. All 146 frames were no-person and the maximum raw person score was `0.0`, but frame-mean luminance ranged from about `0.401` to `0.642` and the maximum within-frame range was about `0.373`. The transient input was neither black nor flat. No report was created; exact cleanup removed the empty export directory, test package, and temporary pulls, stopped the camera, and preserved main-app data. See the [Task 16F validation record](validation/2026-09-08-task16f-camera-bitmap-statistics-pixel6.md).

## Task 16G raw keypoint-score checkpoint

The MoveNet raw-output boundary now also reduces all 102 keypoint scores across six slots and 17 identities to one nullable maximum valid scalar. It retains no winning slot, identity, coordinate, tensor, or raw collection. Calibration preflight keeps only the maximum observed during its bounded window and includes it only if warm-up fails. The report schema, model input, mapping threshold, matching policy, and capture behavior are unchanged.

The first full host gate passed 713/713 JVM tests and 12/12 Python tests with zero failures, errors, or skips. Lint reported zero errors and 9 warnings; all APK assemblies passed. Its frozen hashes were debug `0c5928c9edcdc6a7cf7276b3667540f1ada3500320c42238bb499e10c429b27f`, Android test `fc2aa5f30b4aaea2a4a3218490d6c0526b4faaf7ef3a4d0a2387dffc17a177c7`, and unsigned release `d0a90e11ee75e2a97e37c5e06ebfec076ea18e5168e306ef4c5bb55ee474e281`.

The separately authorized `positive-centered-diagnostic-f` Pixel invocation verified the installed first-candidate main and test bytes, then failed before recording after 16.557 seconds. It again saw 146/146 no-person frames, `maximumValidPersonScore=0.0`, and varied luminance, but reported `maximumValidKeypointScore=1.0`. The reducer range `2 until 55 step 3` included bounding-box index 53, so that value is invalid as keypoint evidence. Exact cleanup completed with no report, export directory, test package, temporary pull, or active camera client.

The correction bounds traversal to the 51-value keypoint block ending at score index 50. Its causal regression fills every bounding-box value with `1.0` while requiring a keypoint maximum of `0.85`. The corrected full gate again passes 713/713 JVM and 12/12 Python tests with zero lint errors and 9 warnings, all APK assemblies, unchanged Room hashes, and unchanged permissions. Corrected frozen hashes are debug `28c3820a2780fb744c37d056b4742eb71eab9249c9947deb411c4089c6e57dcd`, unchanged Android test `fc2aa5f30b4aaea2a4a3218490d6c0526b4faaf7ef3a4d0a2387dffc17a177c7`, and unsigned release `c6935eb3a14221a8c7655665595da0f47fc17c595bf7e6c6749d125b9b86010a`. See the [Task 16G validation record](validation/2026-09-08-task16g-keypoint-score-diagnostic-pixel6.md).

The separately authorized corrected `positive-centered-diagnostic-g` repeat verified those installed main/test bytes but failed 1/1 after 0.623 seconds, before warm-up or report creation, with the harness's generic camera-or-analysis assertion. The camera-service record showed the rear camera opened and disconnected; cleanup then removed the export directory, test package, and temporary pulls, stopped the app, preserved the corrected main app and its data, and left no active camera client. This attempt provides no raw-keypoint evidence.

The test-only follow-up retains no throwable object or message beyond the invocation. It reduces the failure to one of `preview`, `binding`, `startup`, or `analysis` plus at most four exception class names. The fresh full gate passes 713/713 JVM and 12/12 Python tests with zero failures, errors, or skips, zero lint errors and 14 warnings, all APK assemblies, unchanged Room schemas, and unchanged packaged permissions. The main and unsigned release hashes remain unchanged; the new Android test hash is `d69892e64e8f9041f098ea3c249918c37037d54ba72ab916777a0fd02c2e4808`.

The separately authorized `positive-centered-diagnostic-h` repeat verified the final installed main/test bytes and camera permission, then reached the bounded preflight. It analyzed 145 frames in 15 seconds: all 145 were below the unchanged person gate, maximum person score was `0.14585432410240173`, and maximum keypoint score was `0.20261988043785095`. Frame-mean luminance ranged from `0.1355476225490197` to `0.24370181985294126`, with maximum within-frame range `0.3239913725490196`. The preflight failed before recording, wrote no report, and completed exact cleanup. This resolves the Task 16G discriminator in favor of partial pose evidence; it does not establish a new threshold.

## Task 16H detector-gate calibration checkpoint

The derived report now uses schema v3 and adds only nullable per-frame maximum valid person/keypoint scores. The Python analyzer retains v1/v2 compatibility, rejects missing or out-of-range v3 values, and reports bounded positive/negative score distributions plus p05-minus-negative-p95 separation. The tool does not select a threshold.

The Android collector adds one separately selectable `collectOneAuthorizedDetectorGateSequence` method. It uses the existing exact authorization, Pixel/device, timing, camera, bounded-frame, sync/hash, and cleanup contracts, but it records after warm-up without requiring the current person gate. It accepts only `positive` / `single-person-full-body` or `negative` / `no-person-empty-scene`, preventing a wrong-pose sequence that still contains a person from being mislabeled as negative detector evidence.

The host gate passes 714/714 JVM and 14/14 Python tests with zero failures, errors, or skips. Lint reports zero errors and 14 warnings; debug, unsigned release, and Android-test assembly pass. Room V1–V4 schema hashes and packaged permissions are unchanged. Frozen hashes are debug `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66`, Android test `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9`, and unsigned release `996b45a89d91c6aac51cb6dac92900dcd32d32557aef2118832b2741520a21ae`.

The separately authorized exact Pixel 6 full-body and empty-scene invocations each passed 1/1 with 97 schema-v3 frames and matching device/host hashes. Both sequences remained `no-person`. Positive person-score p05/median/p95 were `0.000`/`0.000`/`0.117226`, while negative p95 was `0.122414`; positive keypoint p05/median/p95 were `0.103079`/`0.123211`/`0.147787`, while negative p95 was `0.150982`. The distributions overlap, so this condition rejects a threshold change. Exact cleanup completed. See the [Task 16H validation record](validation/2026-09-08-task16h-detector-gate-calibration-pixel6.md).

## Task 16I bright-light checkpoint

On 17 September, the participant moved to a better-lit location and confirmed readiness after wireless ADB pairing. The unchanged Task 16H main and test APKs matched their installed hashes. The exact 15-second warm-up plus 10-second full-body method passed 1/1 in 26.622 seconds and produced 96 frames, all with one accepted person, successful evaluation, 17 qualified landmarks, and four qualified torso anchors. Person-score min/p05/median/p95/max were `0.724650`/`0.746036`/`0.763399`/`0.768429`/`0.771101`, comfortably above the unchanged `0.25` gate.

This establishes that the live pipeline can supply usable pose evidence in the new conditions. It does not isolate lighting from other scene changes or calibrate reference-pose matching. The old-room empty-scene comparison was exploratory; Task 16J below provides the subsequent same-setup control. No threshold changed and automatic capture remains disabled. Report hashes matched, exact device cleanup completed, and the main app/data remain. No source or APK changed, so the earlier host gate was not rerun. See the [Task 16I validation record](validation/2026-09-17-task16i-bright-light-pixel6.md).

## Task 16J empty-scene checkpoint

The participant confirmed readiness after instructions to keep the phone and lighting unchanged and leave the rear-camera view empty. The frozen Task 16H binaries again matched the installed bytes. The exact detector-gate method passed 1/1 in 26.657 seconds and produced 97 frames, all reporting no person. Person-score min/p05/median/p95/max were `0.033058`/`0.034217`/`0.042591`/`0.049243`/`0.052640`.

Combined with Task 16I's 96/96 one-person frames, this establishes a successful presence/absence checkpoint for the new setup: positive minimum `0.724650`, existing gate `0.25`, negative maximum `0.052640`. The strict analyzer accepted the pair, report hashes matched, and exact device cleanup left the main app/data intact and no active camera clients. No code or APK changed, so the earlier host gate was not rerun. See the [Task 16J validation record](validation/2026-09-17-task16j-bright-empty-scene-pixel6.md).

The next prepared checkpoint uses the accepted-person collector for a deliberately matched reference pose and a clearly different pose, in a separate matching dataset. It has not run. Detector success does not establish reference-match accuracy; repeated cases and performance evidence remain required. Production policy is unchanged and automatic capture remains disabled.

## Task 16K reference-match positive and Task 16L diagnostic

The intended reference-match positive passed collection 1/1 in 26.642 seconds with 97 frames, all containing one accepted person, successful evaluation, 17 qualified landmarks, and four torso anchors. Coverage passed all 97 frames, framing passed none, angular similarity passed 50, and positional/overall gates passed four each. No frame passed every acquisition gate and no lock was acquired. Framing min/median/max were `0.326322`/`0.340900`/`0.347581` against `0.800`. The wrong-pose matching negative remains pending.

The framing evaluator uses the lower of center and scale similarity, while schema v3 retains only that combined score. The physical cause cannot be recovered from this report. Task 16L adds three Android-test source files/edits: an explicitly selectable framing-diagnostic collector, a scalar-only bounded reducer, and synthetic checks. It emits center/scale extrema and evaluated/unavailable counts for the same admitted report frames, without changing production code, thresholds, or report schema. Existing collector methods leave it disabled.

The main APK remains `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66`. The new test APK is `c768792ed7d10bd7cd9e19f3ceccfe593cbd60c7a5f987505c13138912716cbf`. Build/lint succeeded (zero errors, nine warnings), the unchanged 714-JVM suite was up-to-date, and 14/14 Python tests ran successfully. Exact installed-byte verification preceded the synthetic-only Pixel run, which passed 3/3 in 0.027 seconds without opening a camera. The new camera method has not run. Both device sessions completed cleanup, preserving the main app/data and leaving no active camera client. See the [Task 16K record](validation/2026-09-17-task16k-reference-match-pixel6.md) and [Task 16L next-run plan](../.hermes/plans/2026-09-17-task16l-framing-components.md).

## Task 16L live framing components

The participant confirmed readiness for the new diagnostic. Installed main/test hashes matched the prepared artifacts; the exact method passed collection 1/1 in 26.637 seconds. All 97 frames detected and evaluated one person, with 17 qualified landmarks, four torso anchors, and no unavailable framing samples. Center similarity ranged `0.381554–0.386197`, below scale similarity `0.691971–0.711420` throughout. Both independently failed `0.800`; no match lock or capture command occurred. Angular and overall scores also failed throughout, while positional similarity passed 22/97 frames.

The scalar summary and report were retained only in the ignored host build tree, and report hashes matched. Exact device cleanup preserved the app/data and left no active camera client. No code or artifact changed, so host tests were not repeated. The public reference's body center is approximately 39% across and 53% down the full camera frame; the next prepared check re-aims the phone toward that placement. The scalar results provide no closer/farther direction or proof of a production-code defect. No threshold changed and automatic capture remains disabled. See the [Task 16L record](validation/2026-09-17-task16l-framing-components-pixel6.md) and [Task 16M placement plan](../.hermes/plans/2026-09-17-task16m-recentered-reference-match.md).

## Requested settling-period flash cue

The user requested a visible blink at the start of the 15-second settling period. The bound rear-camera controller now exposes internal asynchronous torch control and requests off during release. The calibration collector uses a test-only timing helper: await on, begin the settling interval, request off after a 250 ms pulse, await off, then wait the remainder of the original deadline. Failure aborts before measurement. Production matching, report schema, permissions, and automatic-capture policy are unchanged.

The fresh full JVM run passed 714/714; debug/test builds and lint passed with zero errors and nine warnings. Five synthetic timing/failure tests passed on the Pixel, followed by a 1/1 hardware cue check: one on/off cycle, off acknowledged at 432 ms, and 15,001 ms total settling time. No pose report or photo was collected. Exact installed hashes are debug `8285ee02965cd7957beab44745c50cb3b198b8a92807530370fe29c5f22205bc` and test `a6c28b202a9898250101a6a8ad926bde9e02986a4b502e0f01963e6b9591a8f7`. The updated main app/data remain; the test package is removed and the camera is closed. Task 16M remains pending with its artifact hashes updated. See the [flash-cue record](validation/2026-09-17-warmup-flash-cue-pixel6.md).

## Task 16M recentered reference match

The flash-cue artifacts collected 97/97 fully evaluated one-person frames over wireless ADB; instrumentation passed 1/1 in 26.784 seconds. Center similarity improved to 0.787428–0.793429, while scale 0.663030–0.668498 limited all frames. Both remained below 0.800. Angular, positional, and overall acquisition criteria also passed zero frames, so no lock or capture occurred. Exact report hashes and cleanup passed; the main app/data remain and no camera client is active. No source, threshold, or APK changed during this checkpoint. Improve live alignment feedback before further blind positioning repeats. See the [Task 16M record](validation/2026-09-17-task16m-recentered-reference-match-pixel6.md).

## Task 16N live alignment guide — 25 September 2026

Implemented an opt-in guided collector with a reference-aspect preview, visible target/live body bounds, centering arrow, size cues, and a settling countdown. Guidance is transient, fails closed on incomplete body evidence or incompatible crop geometry, and clears before measurement. No production match policy, model, schema, release UI, or capture behavior changed. Full host verification passed 722/722 JVM checks (eight new), debug/test builds, and lint with zero errors/nine warnings. Two native synthetic screen checks compile but have not run; ADB reported no connected device, and nothing was installed or camera-tested. The next user input needed is the current Wireless debugging connection address. See the [implementation record](validation/2026-09-25-task16n-alignment-guide.md) and [device plan](../.hermes/plans/2026-09-25-task16n-guided-framing.md).

## Task 16N Pixel screen verification — 25 September 2026

Restarting the idle ADB service resolved a daemon routing failure; wireless pairing and automatic connection succeeded. Verified Pixel 6/oriole identity and data-preserving installed hashes, then ran only `CalibrationGuideScreenTest`: 2/2 passed in 0.120 seconds. Synthetic portrait/landscape View geometry, Canvas rendering, and the stop-adjusting transition are now device-checked. Actual camera crop alignment still awaits the participant run. No camera or activity opened and no participant report or image was collected. Cleanup preserved the updated main app/data, removed only the test package, and verified no export directory or active camera client. Source and APKs remained unchanged. See the [Task 16N record](validation/2026-09-25-task16n-alignment-guide.md).

## Task 16N guided camera result — 25 September 2026

The authorized flash-cued guided run passed mechanically 1/1 in 26.843 seconds. All 96 frames detected one person, but only 8–10 landmarks and two torso anchors qualified; every pose evaluation failed canonicalization and all framing components were unavailable. Stored zero match scores are failure placeholders. No lock or capture occurred. Exact report hashes, separate strict analysis, and device cleanup passed. The camera is closed and the updated main app/data remain. Before another physical repeat, confirm that the user could see the guidance and their whole body within the wide preview; improve readiness beyond person presence. No threshold or source changed. See the [camera result](validation/2026-09-25-task16n-guided-framing-pixel6.md).

## Task 16O spoken calibration guidance — 25 September 2026

User feedback established that the rear-camera screen was inaccessible from the posing position. Added fixed spoken positioning/phase cues, offline voice selection, bounded cadence without a backlog, completion/error handling, normal media routing/focus, and cleanup. A new opt-in spoken collector requires five fresh complete-body evaluated frames before the hold, instead of person presence alone. The debug manifest adds only TTS discovery; merged permissions remain CAMERA plus the app-signature permission. Production audio and automatic capture remain open. Build/lint and 728/728 JVM tests pass. The data-preserving installed pair passed 1/1 Pixel audio-only check in 6.950 seconds; the participant confirmed clear audibility, and spoken camera validation remains pending. Cleanup preserved main/data and left no test package, export or camera client. See the [speech record](validation/2026-09-25-task16o-spoken-guidance.md).

## Task 16O spoken readiness stop and Task 16P setup — 25 September 2026

The physically ready spoken camera attempt reported 1 failure in 22.796 seconds at the complete-body readiness gate. Its stop announcement completed; no hold/measurement/report occurred. The user reported an upright phone approximately 8 feet away. A controlled next check turns the phone sideways at the same distance/lighting; cropping is a hypothesis, not proven by this failure. No source/APK or threshold changed. Cleanup preserved main/data and removed all exact test residue. A separate Google Camera client was left untouched; Pose Guide Snap has no process or camera client. See the [attempt record](validation/2026-09-25-task16o-spoken-readiness-pixel6.md) and [landscape plan](../.hermes/plans/2026-09-25-task16p-landscape-spoken-framing.md).

## Task 16P landscape spoken result — 25 September 2026

The physically ready landscape check passed 1/1 in 33.107 seconds on unchanged artifacts. Display metadata confirmed ROTATION_90. All 95 frames detected one person with 17 qualified landmarks/four torso anchors and full pose evaluation. Center similarity 0.816952–0.834996 passed all frames; scale 0.677418–0.690808 limited framing. Angular, positional and overall criteria also passed zero frames, yielding no lock/capture. Strict scalar analysis/report hashes and exact cleanup passed; no camera client remains and main app/data are preserved. Before another movement/repeat, establish the last spoken adjustment and whether the user had time to follow it. See the [landscape result](validation/2026-09-25-task16p-landscape-spoken-pixel6.md).

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
