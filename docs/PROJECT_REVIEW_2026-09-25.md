# Project review — 25 September 2026

Reviewed revision: `4e79e21` on `main` (the merge of [PR #1](https://github.com/tonyisup/pose-guide-snap/pull/1)). The question asked was whether the project is heading in the right direction and whether effort is being spent on problems that the platform or libraries already solve. This review, a CI workflow, and the process changes it recommends are the only repository changes made by it.

**Assessment: the product idea and the pure-domain core are sound; the effort is going to the wrong places in three specific ways.** About 65% of production code is a capture-file journal that re-implements what CameraX, Room transactions, WorkManager, and MediaStore already provide, while the features it exists to protect (export, delete, viewing captures) do not exist. Twenty-nine calibration sub-tasks and fifteen device runs have produced zero automatic locks, because the production app never feeds a camera frame into the lock logic and because two of the five lock gates reject every correct pose ever recorded. The per-commit and per-digest approval process kept nineteen days of work uncommitted on one machine. The original plan's own risk table warned of "architecture theater"; that is what happened.

## Numbers

| Measure | Value |
|---|---|
| Main Kotlin | 31,787 lines / 103 files |
| Test Kotlin | 49,487 lines / 125 files |
| Markdown | 8,172 lines / 98 files, 423 SHA-256 digests |
| Room | 4 schema versions in 5 days; 11 tables; 10 SQL triggers |
| Plan units | 18 planned; ~53 tracked (Task 14 became 9, Task 16 became 30) |
| Gates | 0 and 1 passed; 2 substantively passed by Task 15A but never declared; 3 (the MVP) not close |
| Automatic locks on device | 0 |

| Where the production lines went | Lines | Share |
|---|---:|---:|
| data, data/db, importer, and the camera journal/publisher/file-ops | ~21,000 | ~65% |
| ui (three screens) | 6,140 | ~19% |
| domain (match, coach, model, reducer) | 2,695 | ~8.5% |
| pose/movenet adapter | 449 | ~1.4% |
| CameraX binding and analysis | 1,156 | ~3.6% |

Absent from production code: any `TextToSpeech`, `WorkManager`, or `MediaStore` call; any `FileProvider` or share/view intent; any caller of `DefaultCoachingEngine`; any construction of `ShootEvent.FrameObserved` or `AutomaticCaptureRequested`.

## Finding 1 — the persistence layer re-implements the platform

Eleven tables, four schema versions, ten triggers reinstalled on every open (`AppDatabase.kt:324-336`), thirty-seven enum classes in `data/`, and two identical copies of an eleven-state file-stage machine, one for captures and one for imports.

Per three-photo burst (inferred from the code path, not measured): three SHA-256 passes per photo (`JournaledPrivateCaptureStore.kt:200,244,253-257`), four journal transactions per photo each running a four-table-join classifier (`CaptureFileOperationDao.kt:121-288`), about five fsyncs per photo, all sequential and all before the next shutter (`JournaledThreePhotoCaptureCoordinator.kt:352-397`).

What it defends against, in an app-private database with backup disabled: byte-level key matching that "intentionally bypass[es] every index" as "a security property" (`DeletionExportDao.kt:378`), which only matters if foreign code writes the private database; inode/device identity checks (`AndroidPrivateCaptureFileOps.kt:127-142`) against a race the single serial executor already rules out; re-reading seven tables after a write in the same transaction to confirm SQLite did what the UPDATE said (`RoomShootRepository.kt:501-545`); synthetic "causal clocks" that production fills with `first..first+14` ms (`ReferenceImportProductionAuthorities.kt:92-108`) and that reject capture registration after a restart if the wall clock has stepped backwards (`GuidedSessionStartupReconciler.kt:146`, `RoomShootRepository.kt:2309-2316`). CameraX `OutputFileOptions` already writes temp-then-rename; the app renames that output a second time.

Behind the schema: export outbox rows are written on every confirmation (`RoomShootRepository.kt:1623-1651`) and never read; `claimExportOutput` and `beginShootDeletion` have no callers; quarantine has three stages and no code path that quarantines; `CameraXThreePhotoCapture` (454 lines) and `ReferenceAssetStore` are dead. Captured photos land in `noBackupFilesDir/capture-candidates` with no viewer, share, or export.

The platform answer is roughly 500–900 lines: CameraX `ImageCapture.OutputFileOptions` to `filesDir/captures/<token>-<n>.jpg`; four small tables (shoot, pose, session, capture); one `@Transaction` inserting three capture rows and advancing the pose; a startup sweep that deletes orphan files and drops rows whose file is missing; copy-then-rename import; one WorkManager unique worker doing the MediaStore insert with `IS_PENDING` and storing the returned URI; a transactional delete plus directory removal. It delivers more user-facing behaviour than today. The one regression is power loss within seconds of a capture leaving a committed row pointing at a truncated JPEG; one `fd.sync()` before commit covers it. Crash mid-burst and crash between MediaStore insert and URI save both get better, because the current design answers those with `RECONCILIATION_REQUIRED` and "reconciliation-required forever".

## Finding 2 — matching never locks, and the fix is policy

`GuidedCameraViewModel` has only `manualCapture()` and never looks at a pose. The match display's lock state has one value, `DISABLED` (`BundledMeditationReference.kt:88-90`). Every "lock" in every validation record was simulated afterwards in `tools/calibration/analyze_match_reports.py`.

The five acquire gates (`MatchPolicy.kt:79-83`) are coverage ≥ 0.75, framing ≥ 0.80, angular ≥ 0.85, positional ≥ 0.80, overall ≥ 0.825, with a 500 ms dwell. The framing gate compares the live subject's bounding-box centre and size with the stock reference photo's (`PoseFramingEvaluator.kt:124-141`); it is a composition requirement, not a pose requirement, and `PRODUCT.md` itself says a match score "does not guarantee composition".

Replay of every raw report in `build/calibration/device/` (500 ms dwell, same coverage floor):

| Run | Label | Framing | Angular | Positional | Overall | Current rule | Drop framing only | Angular only | Angular ≥0.85 and overall ≥0.825 |
|---|---|---|---|---|---|---|---|---|---|
| negative-arms-differ-landscape-a | negative | 0.77 | 0.86 | 0.71 | 0.78 | – | – | false lock @537 ms | – |
| positive-arm-guided-landscape-b | positive | 0.76 | 0.87 | 0.79 | 0.83 | – | – | lock | lock @530 ms |
| positive-arm-confirmation-landscape-b | positive | 0.75 | 0.90 | 0.75 | 0.83 | – | – | lock | lock @2575 ms |
| positive-joint-guided-landscape-a | positive | 0.73 | 0.87 | 0.78 | 0.82 | – | – | lock | lock @539 ms |
| positive-centered-bright-a | positive | 0.34 | 0.80–0.88 | 0.71–0.82 | 0.79–0.83 | – | – | lock | – |
| positive-recentered-framing-bright-a | positive | 0.66 | 0.82 | 0.78 | 0.80 | – | – | – | – |
| positive-spoken-landscape-bright-a | positive | 0.68 | 0.79 | 0.72 | 0.76 | – | – | – | – |

The current rule has never locked. Dropping framing alone does nothing because the separate positional gate rejects every correct-pose frame (0.73–0.80 against 0.80). Angular-only is unsafe: it locks the wrong-arms negative. One blended score with a body-visible check locks the three 25 September positives in 0.5–2.6 s and rejects the negative. Positional similarity is what discriminates (0.71 negative, 0.75–0.80 positive); it belongs in the blend, not as its own hard gate. Caveats: one participant, one reference, one true negative; `positive-confirmed-landscape-b` is the negative run relabelled. The threshold is a first guess.

The Task 16 series responded to every failed run after 16M with coaching, speech, or timing features built in `app/src/debug` and `app/src/androidTest`, worded for the one bundled reference, while the production coaching engine has no callers. That coaching behaviour (wait for the user to settle, confirm with "Good", re-cue after a pause, stop with an explanation on timeout) is the right product behaviour for a solo user who cannot see the rear-camera screen; it belongs in the production app, generalized to any joint and any reference.

Detection: keep MoveNet on LiteRT direct. MediaPipe Tasks and ML Kit both ship Google usage metrics, which the no-telemetry boundary rules out. No library does pose similarity; the hand-rolled math is about 170 lines and fine. Procrustes alignment and excluding face landmarks from the positional term are the standard improvements.

## Finding 3 — the process was the bottleneck

The plan header required same-digest specification and security approval before each commit, and `TESTING.md` required fresh authorization per hardware run and per changed APK digest. In practice that produced about twenty "do you authorize" round trips, several for one-line test-fixture edits, and nineteen days of uncommitted work. Task 14B.1 was rejected after four review rounds and split into three; its first third went through three repair rounds. The same status paragraph was pasted into six documents that had drifted to four different task letters. The 4 September audit's two structural recommendations, CI and closing Gate 2, were not done.

Recommended process changes: CI on every push; hardware authorization once per working session rather than per APK digest; same-digest approval reserved for the final Gate 4 acceptance; one status location (`DEVELOPMENT_PROGRESS.md`) that the other documents link to; the pending Task 16AD comparison left unrun.

## Milestones

1. **First automatic lock in the production app.** Replace the five hard gates with a body-visible check plus one blended `overallMatch ≥ 0.825` with the existing dwell and hysteresis; add Procrustes alignment and drop face points from the positional term; feed `MoveNetImageAnalyzer` observations through `DefaultPoseMatcher` into `ShootReducer` in `GuidedCameraViewModel` and route the automatic capture effect to the existing manual-capture path; show the three captures after confirmation; one Pixel 6 run in which the app captures by itself.
2. **Decide whether to replace the journal layer** (recommendation: replace, as described in Finding 1, as Room V5 with a migration that keeps shoots, poses, sessions, and confirmed captures). Add WorkManager export and a real delete either way.
3. **The MVP loop.** Production text-to-speech with `DefaultCoachingEngine` and the generalized settle/confirm/re-cue behaviour; five imported references, hands-free, in airplane mode.

Not recommended before milestone 1 completes: further Task 16 lettered sub-tasks, new ADRs, schema versions, triggers, or rejection-reason enums, and any threshold work tuned in replay against an app that cannot lock.
