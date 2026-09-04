# Project audit — 4 September 2026

Audited revision: `382a659d0ef557cc3bc679b349356ceb16600c45` on `main`. The working tree was clean when the audit began. This report is the only project change made by the audit.

**Assessment: the Android and privacy foundations are sound, but delivery needs to focus on a working capture loop and real pose accuracy.** Keep the architecture. Fix the integration defects below, bring geometry/calibration validation forward, and make completion of Gate 2 the next demonstrable outcome.

The incomplete capture, export, speech and deletion work is explicitly documented. Those are planned milestones, not newly discovered regressions. Four actionable defects were identified in existing code.

## Findings

### 1. P1 — Matching changes when image aspect ratio changes

Confidence: 10/10. Reproduced against the compiled production matcher and diagnostic evaluator with public bundled landmarks; no camera or private images were used.

The MoveNet mapper produces coordinates normalized independently by image width and height: `unpadX` divides by `resizedWidth`, while `unpadY` divides by `resizedHeight`. The canonicalizer then treats these coordinates as equal geometric units: `Vector3(x, y, z)`. Torso normalization cannot undo the unequal horizontal and vertical scaling. See [MoveNetResultMapper.kt:78](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/pose/movenet/MoveNetResultMapper.kt:78), [PoseCanonicalizer.kt:47](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/domain/match/PoseCanonicalizer.kt:47), and [PoseCanonicalizer.kt:171](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/domain/match/PoseCanonicalizer.kt:171).

The current camera diagnostic compares the landscape 1024×574 bundled reference directly with live observations, without passing image dimensions into matching: [BundledMeditationReference.kt:125](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/ui/BundledMeditationReference.kt:125).

For the same pixel-space pose re-centered into a 574×1024 portrait frame, the actual code returned:

| Comparison | Coverage | Angular | Positional | Overall |
|---|---:|---:|---:|---:|
| Reference against itself | 1.000 | 1.000 | 1.000 | 1.000 |
| Identical pose, portrait framing | 1.000 | 0.940 | 0.593 | 0.766 |

The second case fails the positional and overall gates despite unchanged pose geometry. The replay used `x′=(x−0.4)×1024/574+0.5` and `y′=(y−0.5)×574/1024+0.5`, preserving pixel offsets while changing frame dimensions.

**Fix:** give matching an explicit aspect-correct coordinate contract, preserving normalized coordinates separately for rendering. Cover landscape-reference/portrait-camera and crop changes with cross-layer regressions before choosing thresholds. Review persisted reference observations when changing this contract.

### 2. P1 — An interrupted import can block preparation across all shoots

Confidence: 10/10 for the source-level failure path; no fresh device crash test was performed.

Process death after [ReferencePoseImporter.kt:354](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/importer/ReferencePoseImporter.kt:354), before its filesystem claim, can leave durable `PREPARING` and `EXPECTING_RESERVATION` records. The existing `ReferenceImportStartupReconciler` has no production callsites; the [editor composition:270](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorProductionFactory.kt:270) constructs only the import path.

Unfinished work blocks imports **globally**, not just for its owner: [RoomReferenceImportRepository.kt:249](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/data/RoomReferenceImportRepository.kt:249). It also blocks starting other shoots: [RoomShootPreparationRepository.kt:174](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootPreparationRepository.kt:174). Consequently, the [UI advice to create a new shoot:384](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorScreen.kt:384) cannot restore normal use.

Existing Android tests explicitly assert the cross-shoot block, including `unresolvedImportWorkInAnotherShootRejectsFreshReservationBeforeCreatingRows` and `reconciliationRequiredTerminalWorkInAnotherShootBlocksFreshReservationWithoutCreatingRows` in `RoomReferenceImportRepositoryAndroidTest`.

**Fix:** compose the existing reconciler with production startup/recovery, serialize it against fresh imports, and show truthful unresolved-work guidance. Preserve the global safety barrier. Verify recovery after process death through the actual app composition, including the ability to import into another shoot afterward.

### 3. P2 — The shoot list can retain stale reference counts

Confidence: 9/10. Verified in source against Room's documented behavior; not reproduced on a device during this audit.

The [list factory:48](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/ui/navigation/ShootListProductionFactory.kt:48) and [editor factory:228](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorProductionFactory.kt:228) create separate database instances. The [database builder:343](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/data/db/AppDatabase.kt:343) does not enable cross-instance invalidation. Room disables this notification mechanism by default. [Android Room documentation](https://developer.android.com/reference/androidx/room/RoomDatabase.Builder#enableMultiInstanceInvalidation()).

The list ViewModel remains alive outside navigation destinations and continuously collects its own database's Flow. Returning from the editor therefore does not itself refresh the list after another instance imports a reference.

**Fix:** synchronize invalidation across the existing database owners, or deliberately share database ownership with compatible close semantics. Verify create → import → Back shows the updated count without restarting or making an unrelated write.

### 4. P2 — Permanent camera denial leaves a nonfunctional “Allow camera” action

Confidence: 9/10. Source and documented platform behavior; not a fresh device reproduction.

Every denied state renders the same action at [App.kt:131](/Users/juicebox/src/pose-guide-snap/app/src/main/java/com/tonyisup/poseguidesnap/ui/App.kt:131), which requests permission again. After repeated denial, Android can stop displaying the permission dialog. The screen has no corresponding denial explanation or recovery guidance. [Android permission documentation](https://developer.android.com/training/permissions/requesting).

**Fix:** distinguish the initial request from a denied state, explain camera availability accurately, and offer a user-initiated recovery route when appropriate. Verify denial, permanent denial and return after granting permission in Settings.

## Direction and remaining risks

- **The core product loop is still unproven.** The Ready destination opens a camera diagnostic without consuming the session's selected reference. Its overlay remains the bundled meditation pose. First application of capture confirmation intentionally returns `JOURNAL_CONFIRMATION_NOT_AVAILABLE`; ten related Android tests are explicitly ignored. The project's own [Gate 2 definition](/Users/juicebox/src/pose-guide-snap/docs/TESTING.md:25) remains the right near-term target.
- **Accuracy work is scheduled too late to guide integration.** The plan places calibration after full hands-free integration. Bring a small representative positive/negative evaluation forward after fixing geometry. Measure false locks, time to lock, missing-body cases and cue usefulness. In the audit replay, removing both knees and both ankles still produced coverage 0.793 and `eligibleForLock=true` with neutral framing. Capture remains disabled, so this is a pre-integration policy gap; establish required body regions alongside the real framing gate. Existing synthetic tests and the public fixture do not establish real-world accuracy. The documented 15-minute performance soak also remains pending.
- **User recovery must accompany usable flows.** Started sessions currently lack production Stop/finish controls, and reference rows have labels and move controls but no image preview or mirror opt-out control. These belong in the next usable-product milestone; a durable Start alone does not establish a useful session.
- **Verification needs a repeatable integration gate.** No checked-in CI configuration was found. Add the existing host checks and a small production-composition test set to repeatable automation, alongside the separately controlled hardware acceptance process. External CI configuration was not inspected.
- **Status documents are stale about the latest repair.** README and several docs still describe it as uncommitted, although it is included in `382a659`. Correct the repository status while keeping review approval status separate; this audit does not establish any missing historical approval.

## What is working well

The pure Kotlin domain boundary, deterministic reducer, explicit capture ownership, atomic Room operations and non-destructive migrations are appropriate foundations. The persistence review found no additional high-confidence journal or migration defect. The packaged debug APK requests only camera and AndroidX's signature permission, with no Internet permission. Source backup exclusions cover sensitive local storage. Documentation distinguishes bounded test evidence from product completion.

These strengths support continuing the current project rather than restarting it.

## Recommended next milestone

1. Fix aspect-ratio handling and production import recovery, with focused regressions. Address list refresh and camera-denial recovery alongside them.
2. Finish the necessary existing capture-journal stages and connect one manual session through camera → exactly three durable private outputs → one Room confirmation/advance. Add a usable stop/recovery path. Verify restart behavior through the same production composition.
3. Use early real-device matching evidence to guide automatic lock, then deliver the five-pose offline hands-free loop with speech and the separately tracked export/deletion work.

Success should be measured by repeatable user journeys and recovery from ordinary interruptions, alongside the existing storage guarantees.

## Verification performed

- Fresh execution of `:app:testDebugUnitTest`: **635 tests, zero failures/errors/skips**.
- `:app:lintDebug`: **zero errors, 14 warnings**. Warnings include tooling/version notices, naming/style suggestions and a missing application icon.
- Debug app and Android-test APK assembly: successful incremental build checks.
- Inspected the packaged debug APK permissions.
- Replayed aspect-ratio changes through compiled production matcher and diagnostic classes.
- Reviewed product/architecture plans, UI composition, import recovery, persistence, matcher, reducer and relevant tests.

No fresh Android instrumentation, live UI, sensor, audio, MediaStore or sustained hardware run was performed. No private photos were accessed. This is an engineering/product audit, not release acceptance or a certification that every defect has been found.
