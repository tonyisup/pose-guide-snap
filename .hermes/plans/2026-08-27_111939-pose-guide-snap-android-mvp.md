# Pose Guide Snap Android MVP Implementation Plan

> **For Hermes:** Use the `software-development:subagent-driven-development` skill to implement this plan task-by-task. Require specification and quality/security approval of the same exact staged digest before each commit.

**Goal:** Build an Android-first, privacy-preserving guided selfie app that imports a sequence of reference-pose photos, coaches one person through the sequence over normal Android audio routing, and captures/advances automatically only after a stable, high-confidence pose match.

**Architecture:** Start with a single Android app module whose boundaries are explicit but not prematurely split into Gradle modules. Keep pose normalization, scoring, coaching, and session transitions as pure Kotlin domain code; place direct LiteRT/MoveNet inference, CameraX, app-private capture storage, Room, Text-to-Speech, MediaStore export, and Compose behind narrow adapters. Automatic and manual capture share one reducer-owned, exactly-three-output private capture pipeline. A Room transaction owns logical confirmation and advancement; MediaStore export follows through an idempotent outbox.

**Tech Stack:** Native Android; Kotlin; Jetpack Compose; CameraX Preview/ImageAnalysis/ImageCapture; direct LiteRT `1.4.2` with MoveNet MultiPose Lightning float16 v1; coroutines/Flow; Room; DataStore for preferences; Android TextToSpeech; JUnit, kotlinx-coroutines-test, Turbine, Compose UI tests, and Android instrumentation tests.

**Status:** Product and architecture defaults are approved, including the current app-private capture-authority revision that supersedes the earlier MediaStore-authoritative wording. Tasks 1–12 and Task 14A.1 are implemented. Task 9 uses direct bundled MoveNet/LiteRT after exact-artifact review proved MediaPipe Tasks Core's mandatory Google metrics path incompatible with the no-analytics/no-network contract. Task 10's reviewed camera slice is committed at `605c904`; Task 11A's reviewed Room capture authority is committed at `5335466`; Task 11B's reviewed transactional reference-import backend is committed at `d368e96`; and Task 12's reviewed Room V3 preparation workflow is committed through `5bc15c3`. Task 14A.1 adds exact-session atomic Room V3 reconstruction; its authorized evidence is bounded to Room and does not complete Gate 2. Active-session discovery/UI resume is the next ownership boundary. Standalone Task 13 speech is deferred until the Task 15 coordinator exists. This revision supersedes every MediaPipe-specific instruction below.

---

## 1. Baseline Audit

As inspected on 2026-08-27:

- The repository root existed but contained no files, including no hidden project files.
- It is not a Git repository.
- The host currently has no usable JDK, Gradle, Android SDK, `adb`, or Android Studio installation.
- Homebrew and Xcode Command Line Tools are present.
- A Pixel 6 running Android 16 is available as the intended first real-device acceptance target.

This means the first implementation step is a fail-loud toolchain gate, not application code. Do not generate an Android project and then discover midway that it cannot build or run.

## 2. Product Boundary

### 2.1 MVP user journey

1. The user creates a named shoot.
2. The user imports 3–20 reference photos and arranges them in order.
3. The app validates each image locally and rejects references that do not contain exactly one sufficiently visible person.
4. The user mounts the phone, selects rear-camera capture, optionally connects earbuds, and starts the shoot.
5. The app announces framing corrections first, then the single highest-value pose correction.
6. When coverage, framing, similarity, and stability gates all pass for a configured dwell period, the app announces capture and writes exactly three authoritative app-private photos.
7. One Room transaction confirms all three durable outputs, advances exactly once, marks the confirmation/advance receipt applied, and queues MediaStore export. Manual capture uses this same pipeline and bypasses only the match/lock gate.
8. The process continues while an idempotent worker exports confirmed captures to MediaStore afterward; private capture authority and session advancement never depend on export completion.

### 2.2 MVP scope

- Android-first native application.
- One person, static full-body or three-quarter-body poses.
- Local reference-image import through Android's system picker.
- Ordered pose playlists.
- Rear camera first; front-camera support is deferred until the rear-camera loop passes the real-device gate.
- On-device reference and live pose landmark extraction.
- Visual overlay, score, and concise spoken correction.
- Spoken output through Android's current media route, including connected earbuds; the app will not implement bespoke Bluetooth routing.
- Stable-match auto-capture with hysteresis, cooldown, idempotency, and a reducer-owned manual trigger that bypasses only pose-match/lock eligibility.
- Exactly three authoritative app-private outputs per automatic or manual command, with post-confirmation MediaStore export.
- Local-only operation after the model and app are installed.
- Explicit exclusion of all sensitive app-private data from Android cloud backup, device-to-device transfer, and supported cross-platform transfer; partial restore is forbidden.
- No account and no cloud upload.

### 2.3 Explicit non-goals for MVP

- iOS or cross-platform UI.
- Couples, groups, children-specific flows, or multiple simultaneous bodies.
- Dynamic/action pose sequences.
- Facial-expression scoring, attractiveness scoring, body-shape judgment, or beauty filters.
- Generative pose suggestions.
- Background, outfit, lighting-quality, or scene-aesthetic scoring.
- Automatic correction for arbitrary camera azimuth or severe perspective differences.
- Remote model inference, analytics SDKs, ads, subscriptions, or app-store publication.
- A claim that a high skeleton-match score guarantees a good photograph.

### 2.4 Product truth that must remain visible

A monocular 2D/weak-3D detector cannot perfectly reconstruct depth, occluded joints, hand shape, or viewpoint. The app should say **“pose match”**, not **“perfect pose.”** References with a substantially different viewpoint from the live camera may be impossible to match reliably; the import validator and live UI must explain this rather than silently lowering thresholds.

## 3. Approved Product Decisions

These defaults were explicitly approved before implementation:

1. **Android first.** This is the fastest path to a real test on the available Pixel 6 and avoids compromising camera/audio behavior for cross-platform abstraction before the core interaction is proven.
2. **Rear camera first.** The earbuds-first interaction matters most when the user cannot see the screen, and rear-camera output gives the sharper proof of value. Add front-camera support only after the state machine is sound.
3. **No cloud in MVP.** Pose landmarks and matching can run on-device, eliminating account, upload, latency, and sensitive-image retention problems.
4. **Conservative shutter policy.** A missed capture is recoverable; an unintended burst and sequence advance destroys trust. Auto-capture therefore requires independent coverage, framing, similarity, and stability gates.
5. **Pure domain core.** Camera frames and detector results become immutable domain observations before any decision logic runs. Camera, speech, time, storage, and capture are ports—not owners of shoot policy.
6. **Single app module initially.** Enforce package boundaries and dependency rules in tests. Split modules only after build times or ownership justify it.

## 4. Runtime Architecture

CameraX provides separate preview, CPU image-analysis, and still-image-capture use cases; the app will bind those use cases together without enabling camera extensions in MVP.[1] The exact bundled MoveNet MultiPose model runs through direct LiteRT for both imported references and upright camera frames.[2] Because the detector is blocking, the camera adapter owns one bounded off-UI keep-latest worker rather than an asynchronous model callback.

```text
System Photo Picker
        │
        ▼
ReferencePoseImporter ──> MoveNetDetector(blocking) ──> ReferencePose
        │                                           │
        └──────────────> ShootRepository(Room) <────┘
                                                    │
CameraX Preview + ImageAnalysis                    ▼
        │                                  GuidedShootCoordinator
        ▼                                           │
MoveNetDetector(blocking, off-UI) ─> PoseObservation ─> PoseMatcher
                                                    │
                                          MatchResult + CoachingCue
                                             │              │
                                             ▼              ▼
                                      ShootStateReducer  SpeechCoach
                                             │
                                      CaptureCommand(token)
                                             │
                                             ▼
                                    CameraX ImageCapture
                                             │
                                             ▼
                            App-private capture files
                                      │
                                      ▼
                      Room confirmation + sequence advance
                                      │
                                      ▼
                          MediaStore export outbox worker
```

Room is appropriate for the shoot/pose/session relationships because they are structured data with transactional ordering requirements.[3] DataStore is reserved for small user preferences such as voice enabled, speech cadence, dwell duration, and match threshold.

### 4.1 Ownership boundaries

- `domain/model`: immutable pose, match, cue, shoot, and session values.
- `domain/match`: normalization, mirroring, feature extraction, scoring, and independent gates.
- `domain/coach`: deterministic selection of one actionable cue plus suppression rules.
- `domain/session`: reducer/state machine; the only authority allowed to request capture or advance the sequence.
- `pose/movenet`: fixed bundled-model loading, direct blocking LiteRT inference, letterbox geometry, and conversion into immutable 17-point 2D domain observations.
- `camera`: CameraX binding, upright conversion, one bounded off-UI keep-latest inference worker, per-frame failure containment, `ImageProxy` closure, rotation/crop transforms, and still capture.
- `audio`: TextToSpeech lifecycle, cadence, queue policy, and audio-focus behavior. Android audio-focus handling must follow current platform behavior rather than force routing.[4]
- `data`: Room entities/DAOs, DataStore settings, app-private reference/capture assets, and MediaStore export records.
- `ui`: Compose screens and rendering only; UI does not calculate scores or decide capture.

### 4.2 Core contracts

Planned domain contracts:

```kotlin
interface PoseDetector {
    fun detectUpright(image: UprightPoseImage): PoseDetection
}

interface LivePoseScheduler {
    fun submitLatest(frame: PoseFrame)
    fun observeDetections(): Flow<PoseDetection>
}

interface PoseMatcher {
    fun compare(reference: ReferencePose, live: PoseObservation): MatchResult
}

interface SpeechCoach {
    suspend fun speak(cue: CoachingCue)
    suspend fun stop()
}

interface CapturePort {
    suspend fun captureThreePrivateOutputs(command: CaptureCommand): PrivateCaptureResult
}

interface ShootRepository {
    fun observeShoot(id: ShootId): Flow<Shoot>
    suspend fun replacePoseOrder(id: ShootId, poseIds: List<PoseId>)
    suspend fun confirmCaptureAdvanceAndEnqueueExport(result: PrivateCaptureResult): CaptureReceipt
}
```

All automatic and manual capture commands carry a unique token derived from session ID, pose ID, and attempt number. A successful token can be applied only once. Manual capture is not a second port or protocol; it is a reducer event that skips only pose-match/lock eligibility before emitting the same command.

### 4.3 Pose scoring

Do not reduce the problem to a single raw Euclidean distance.

1. Reject observations with zero people or more than one sufficiently visible person.
2. Transform sensor coordinates into the displayed/captured crop coordinate system.
3. Remove landmarks below the visibility/presence floor.
4. Compute both normal and horizontally mirrored candidates where the reference allows mirroring.
5. Normalize translation around torso center and scale by torso length/shoulder-hip geometry.
6. Compute weighted joint-angle features for shoulders, elbows, wrists, hips, knees, ankles, and torso lean.
7. Compute normalized landmark-position error separately.
8. Return independent values for landmark coverage, framing, angular similarity, positional similarity, and overall match.
9. Refuse lock if any required gate fails; a weighted average must never hide missing legs, poor framing, or a second person.

No production threshold is accepted merely because it “looks reasonable.” Thresholds remain development defaults until fixture and real-device calibration produce a documented positive/negative separation report.

### 4.4 Coaching policy

- Framing/coverage corrections have priority over limb corrections.
- Emit one concise cue at a time.
- Select the largest actionable semantic error whose confidence is high enough.
- Require persistence across several frames before speaking.
- Suppress repeats for a configurable interval unless the error materially worsens.
- Prefer relative language: “raise your left hand,” “step back,” “turn your shoulders slightly right.”
- Never speak numeric match scores through earbuds during normal shooting.
- Announce lock, capture, next pose, pause, and completion as explicit state changes.

### 4.5 Capture state machine

```text
Preparing
  -> SearchingForPerson
  -> Framing
  -> Coaching
  -> LockCandidate
  -> Locked
  -> Capturing(token)
  -> ConfirmingAndAdvancing(token)
  -> CaptureConfirmedAndAdvanced(token)
  -> SearchingForPerson | Completed

Any active state -> Paused -> previous safe state
Any active state -> Failed(recoverable | terminal)
Capturing + CaptureFailureCleanupConfirmed(token) -> Coaching
Capturing + CaptureFailureReconciliationRequired(token) -> Failed(terminal)
```

Required invariants:

- Automatic `CaptureCommand` can be emitted only from `Locked`; `ManualCaptureRequested` is reducer-owned and bypasses only pose-match/lock eligibility before emitting the same command.
- Every command writes exactly three authoritative app-private outputs with deterministic `(commandToken, burstOrdinal 0..2)` identities. Each file uses a same-directory temporary write, appropriate sync, and atomic no-clobber final publication; the three-file set is not claimed to be filesystem-atomic.
- A pose cannot advance until all three private files exist durably and one Room transaction confirms the attempt, records the outputs, advances exactly once, marks the receipt applied, and creates the export outbox.
- Capture or confirmation failure does not advance or create an outbox. It returns to a re-armed coaching state only after unconfirmed private files are cleaned or quarantined; uncertain resolution enters reconciliation-required with no automatic recapture.
- After Room confirmation, capture and advancement are complete. MediaStore export is idempotent outbox work and retries never recapture or re-advance.
- Replayed/stale frames cannot emit another command for an already confirmed token.
- Falling below the release threshold re-arms lock; the acquire threshold is higher than the release threshold.
- Pausing cancels pending speech and capture countdowns.

## 5. Data and Privacy Design

Planned Room entities:

- `ShootEntity(id, name, createdAt, updatedAt, currentOrderVersion, lifecycleState, deletionGeneration)`
- `PoseEntity(id, shootId, sortIndex, label, referenceAssetPath, landmarkPayload, detectorVersion, mirrorAllowed, validationStatus)`
- `SessionEntity(id, shootId, startedAt, completedAt, state)`
- `CaptureAttemptEntity(commandToken, sessionId, poseId, triggerType, state, reconciliationRequired, startedAt, confirmedAt)`
- `PrivateCaptureOutputEntity(commandToken, burstOrdinal, deterministicPrivatePath, durabilityState, scoreSummary, capturedAt, integrityMetadata)`
- `CaptureConfirmationReceiptEntity(commandToken, appliedAt)` with a unique command-token key
- `CaptureExportOutboxEntity(commandToken, state, createdAt, updatedAt, retryMetadata)` with exactly one committed row per confirmed command
- `CaptureExportOutputEntity(commandToken, burstOrdinal, targetCollectionUri, targetVolume, intendedDisplayName, intendedRelativePath, intendedMimeType, claimToken, exportState, mediaUriString: String?, ambiguityState)` with composite primary key/uniqueness `(commandToken, burstOrdinal)` and a database ordinal constraint of 0–2

Rules:

- Copy selected reference images into app-private storage after explicit selection; do not depend indefinitely on an external provider URI.
- Store the extracted landmark payload, detector/model version, and coordinate-transform metadata with each reference.
- Treat one automatic or manual capture token as one exactly-three-output attempt; identify outputs by `(commandToken, burstOrdinal)` for ordinals 0–2.
- Write each image to a same-directory temporary file, sync it and its directory as appropriate, and atomically publish its deterministic final app-private path without clobber. A collision is a failure, not permission to replace. Do not claim multi-file filesystem atomicity.
- Confirm only after all three authoritative private files are durable. In one Room transaction, confirm the attempt, record all three private outputs, advance once, apply the idempotent receipt, and create one durable MediaStore export outbox with exactly three per-output rows. Verify output cardinality is exactly three inside that transaction before commit. This is the session ownership boundary.
- If private write/finalization or the Room transaction fails, do not advance or create an outbox. Clean or quarantine unconfirmed files; if resolution cannot be proven, require reconciliation and forbid automatic recapture.
- On startup, resolve pre-transaction private files by deterministic attempt identity before retry. For committed attempts, replay only pending export work.
- Export afterward through a worker that first wins a durable Room compare-and-set from pending to claimed with a fresh unique claim token. Only that winner may call `MediaStore.insert()`. Claimed/create-started work without a durably stored exact URI never returns to pending and is never auto-created again after timeout/restart; it enters reconciliation-required.
- Persist exact target collection/volume and intended metadata for diagnostics, but never treat display name or relative path as unique authority. After `insert()` returns, durably record the exact URI before any fallible publication step. Automatic update/delete is allowed only through that output's exact durable URI; ambiguous missing-URI outcomes fail closed and preserve foreign rows.
- Never persist camera-analysis frames.
- Never log raw image paths, landmark arrays, or MediaStore URIs in release builds.
- Delete through an atomic Room barrier: mark the shoot deleting, advance its deletion generation, block capture/advance and new exporter claims, and cancel untouched pending work. Workers recheck the barrier before external create and before publication. Do not remove authority during claim/create/publish; wait for or resolve exact-URI work, and retain a minimal tombstone with reconciliation-required state if safe completion is impossible. Report incomplete rather than success.
- Keep quarantined images app-private and backup/transfer-excluded, tied to reconciliation metadata, visible as a user-facing unresolved count/state, and retained until explicit resolution or successful app-level deletion. Delete-all uses the same barrier. Clear-data/uninstall can forcibly remove private quarantine/state but cannot promise removal of already or ambiguously exported MediaStore rows.
- The Task 3 manifest sets `android:allowBackup="false"`, `android:fullBackupContent="@xml/backup_rules"`, and `android:dataExtractionRules="@xml/data_extraction_rules"`. Both resources exclude every applicable `root`, `file`, `database`, `sharedpref`, `external`, `device_root`, `device_file`, `device_database`, and `device_sharedpref` domain for cloud backup and device transfer; compile SDK 37 also includes a fail-closed `cross-platform-transfer platform="ios"` section. No custom `BackupAgent` is permitted. Partial restore of capture/Room/outbox state is forbidden because it can violate authority and receipt invariants.[5]
- Add `docs/PRIVACY.md` before any distribution build.

## 6. Quality Gates

### Gate 0 — Approved boundary and usable toolchain

- This plan and the product boundary are approved.
- JDK, Android SDK, platform/build tools, and `adb` are installed.
- `java -version`, `sdkmanager --list`, and `adb version` succeed.
- A blank debug APK builds and unit tests run.

### Gate 1 — Deterministic offline engine

- Pure JVM tests prove normalization, mirror handling, gate separation, cue choice, hysteresis, idempotency, and sequence transitions.
- Synthetic jitter and negative-control fixtures are deterministic.
- No Android camera, TTS, Room, or LiteRT object enters the domain package.

### Gate 2 — Single-reference camera slice

- One bundled, licensed reference fixture is extracted on-device.
- Camera preview, analysis overlay, and a live match report run on the Pixel 6.
- The reducer-owned manual trigger succeeds through the same exactly-three-output private confirmation and export-outbox protocol used by automatic capture; only auto-lock eligibility is disabled.
- Auto-capture remains disabled.

### Gate 3 — Complete local MVP loop

- A user can import and order at least five references.
- Speech is concise and rate-limited.
- Stable matching triggers exactly one private three-photo capture, the Room confirmation transaction advances exactly one pose and queues export, and all five poses can complete without touching the phone.
- Airplane mode does not break the session.

### Gate 4 — Real-device acceptance

- Same exact APK digest is used for functional, privacy, audio-route, and quality/security review.
- Pixel 6 acceptance matrix passes rear camera, phone speaker, connected earbuds, permission denial/recovery, app background/foreground, pre/post-confirmation restart, capture/export/reconciliation and deletion behavior, low light, partial occlusion, no person, and a second person entering frame.
- No publication or store claim is made until this gate passes.

## 7. Implementation Tasks

The tasks below are intentionally sequential. Bootstrap establishes a truthful GREEN baseline; later REDs must be causal behavior failures, not manufactured tests that fail only because a class has not been created.

**Execution pre-flight:** Because Tasks 1 and 2 each end in a commit, initialize the empty directory with `git init -b main` before Task 1. This establishes version-control history only; it does not scaffold application code.

### Task 1: Approve and document the product boundary

**Objective:** Turn the recommendations in this plan into the repository's explicit product contract before runtime work.

**Files:**
- Modify: `.hermes/plans/2026-08-27_111939-pose-guide-snap-android-mvp.md`
- Create: `README.md`
- Create: `docs/PRODUCT.md`
- Create: `docs/ARCHITECTURE.md`
- Create: `docs/TESTING.md`
- Create: `docs/PRIVACY.md`
- Create: `docs/adr/0001-android-native-first.md`
- Create: `docs/adr/0002-on-device-pose-processing.md`

**Steps:**

1. Copy the approved scope, non-goals, user journey, state ownership, and truth-in-claims language into the named docs.
2. Mark the project status as “planning/bootstrap; no working app yet.”
3. Record Android-first and on-device inference as reversible ADRs with consequences.
4. Add an explicit deferred-features table.
5. Review docs for claims that imply camera, coaching, or capture already works.
6. Commit: `docs: define pose guide snap MVP boundary`.

**Verification:** `README.md` links every required document and none claims an implemented feature.

### Task 2: Install and verify the Android toolchain

**Objective:** Establish a reproducible build environment before scaffolding.

**Files:**
- Create: `docs/DEVELOPMENT.md`

**Steps:**

1. Install the current stable Android Studio or an equivalent official JDK + Android command-line SDK toolchain. Prefer Android Studio because the host currently has none of these components.
2. Record exact installed versions and paths in `docs/DEVELOPMENT.md`.
3. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` consistently and expose `platform-tools` on `PATH`.
4. Verify:
   - `java -version`
   - `sdkmanager --list`
   - `adb version`
5. Stop immediately if the SDK license, JDK, or platform tool cannot be verified.
6. Commit: `docs: record Android development toolchain`.

**Verification:** A fresh shell can execute all three verification commands without relying on Android Studio's GUI process.

### Task 3: Bootstrap the Android project and truthful GREEN baseline

**Objective:** Create the smallest buildable native Android app with pinned stable dependencies.

**Files:**
- Create: `.gitignore`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradlew`, `gradlew.bat`, and `gradle/wrapper/*`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/backup_rules.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/MainActivity.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/ui/App.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/BootstrapTest.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/BackupExclusionContractTest.kt`
- Create: `app/src/androidTest/java/com/tonyisup/poseguidesnap/BackupExclusionManifestTest.kt`

**Steps:**

1. Verify the repository is on `main` and that only the approved documentation/toolchain work from Tasks 1–2 is present.
2. Use application ID `com.tonyisup.poseguidesnap` provisionally; change it now if a different publishing identity is required.
3. Set `minSdk = 29`; resolve and pin current stable compile/target SDK, AGP, Kotlin, Compose BOM, CameraX, Room, DataStore, coroutines, and test versions. Do not select alpha dependencies without a written reason.
4. Set the application manifest to `android:allowBackup="false"`, reference both backup-rule resources, and declare no custom `BackupAgent`. Add fail-closed legacy and API 31+ XML rules excluding all nine supported credential/device-protected domains from cloud and device transfer; when compiling with SDK 37, include the supported iOS cross-platform-transfer section with the same exclusions and non-authoritative counterpart metadata.
5. Add source/merged-manifest and parsed rule-content tests. Prove every required domain is excluded in every applicable mode and reject partial capture/Room/outbox restoration.
6. Add a minimal Compose screen that states “Pose Guide Snap — prototype.”
7. Add one bootstrap test that asserts the package/version configuration, not a fabricated missing-class RED.
8. Run:
   - `./gradlew --version`
   - `./gradlew :app:testDebugUnitTest`
   - `./gradlew :app:assembleDebug`
9. Hash the exact candidate APK, then use the pinned SDK Build Tools `aapt2 dump xmltree` against that APK—not only source or merged intermediates—to inspect `AndroidManifest.xml`, `res/xml/backup_rules.xml`, and `res/xml/data_extraction_rules.xml`. Require the packaged manifest references and the complete nine-domain exclusion set in every applicable packaged rule section; also assert the packaged manifest has no `android.permission.INTERNET`.
10. Where authorized platform tooling permits, inspect a forced backup/restore or transfer dry run and record that no sensitive app state is included.
11. Commit: `build: bootstrap Android application`.

The planned rule content is fail-closed, not an allowlist of selected sensitive paths:

- `backup_rules.xml` contains a `<full-backup-content>` root and `<exclude domain="…" path="."/>` for each of `root`, `file`, `database`, `sharedpref`, `external`, `device_root`, `device_file`, `device_database`, and `device_sharedpref`.
- `data_extraction_rules.xml` contains `<cloud-backup>`, `<device-transfer>`, and, for compile SDK 37, `<cross-platform-transfer platform="ios">`. Each section contains the same nine whole-domain exclusions and no sensitive-data include. Any required cross-platform counterpart fields are non-authoritative transfer metadata and cannot weaken those exclusions.
- Contract tests parse the XML structure and compare each section's exclusion-domain set to that exact nine-domain set; token-presence checks alone are insufficient.

**Verification:** The debug APK exists and the test task is GREEN from the command line. Source and merged manifests retain `allowBackup=false` plus both rule references. Inspection of the exact hashed APK proves its packaged manifest retains those references and no `INTERNET` permission, while both packaged compiled XML resources contain the exact whole-domain exclusion sets. Authorized platform inspection is recorded where tools permit.

### Task 4: Define immutable domain models and dependency boundaries

**Objective:** Establish app-independent data contracts before bringing in a platform detector runtime or CameraX.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/model/Landmark.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/model/PoseObservation.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/model/ReferencePose.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/model/MatchResult.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/model/CoachingCue.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/model/Shoot.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/architecture/DomainBoundaryTest.kt`

**Steps:**

1. Write a causal architecture test that scans `domain/` imports and rejects Android, CameraX, detector SDKs including LiteRT, Room, and Compose packages.
2. Add immutable value types with validated normalized-coordinate and confidence ranges.
3. Make timestamps explicit values supplied by a clock, not calls to system time inside models.
4. Run the architecture test and all JVM tests.
5. Commit: `feat: define pose guidance domain contracts`.

**Verification:** Domain source compiles as pure Kotlin and the boundary test proves forbidden dependencies are absent.

### Task 5: Implement canonicalization and pose feature extraction

**Objective:** Convert raw landmark observations into comparable, viewpoint-limited pose features.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/match/PoseCanonicalizer.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/match/PoseFeatures.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/domain/match/PoseCanonicalizerTest.kt`
- Create: `app/src/test/resources/poses/*.json`

**Steps:**

1. Add deterministic fixture observations for baseline, translated, scaled, mirrored, low-confidence, and occluded cases.
2. Write behavioral REDs proving translation/scale invariance, explicit mirror handling, and confidence filtering.
3. Implement torso-centered normalization and weighted joint-angle extraction.
4. Add property-style jitter tests with seeded random input.
5. Run: `./gradlew :app:testDebugUnitTest --tests '*PoseCanonicalizerTest'`.
6. Commit: `feat: canonicalize pose landmarks deterministically`.

**Verification:** Equal poses remain close under permitted translation/scale/jitter; occluded landmarks are excluded rather than replaced with invented coordinates.

### Task 6: Implement multi-gate pose matching

**Objective:** Produce explainable match results without allowing one aggregate score to hide a failed safety gate.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/match/DefaultPoseMatcher.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/match/MatchPolicy.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/domain/match/DefaultPoseMatcherTest.kt`

**Steps:**

1. Write REDs for same-pose, clearly different pose, mirrored match, insufficient coverage, poor framing, and multiple-person rejection.
2. Return named subscores and gate failures in `MatchResult`.
3. Keep development thresholds in `MatchPolicy`; do not scatter constants.
4. Ensure overall score cannot set `eligibleForLock=true` when any mandatory gate fails.
5. Run focused and full JVM tests.
6. Commit: `feat: add explainable multi-gate pose matcher`.

**Verification:** Test output identifies exactly which gate blocked lock for every negative control.

### Task 7: Implement deterministic coaching cues

**Objective:** Convert stable match errors into one concise, actionable correction.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/coach/CoachingPolicy.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/coach/DefaultCoachingEngine.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/domain/coach/DefaultCoachingEngineTest.kt`

**Steps:**

1. Write REDs proving framing precedes limb cues, low-confidence joints produce no cue, and left/right respects mirror transforms.
2. Implement a fixed semantic cue vocabulary rather than free-form text generation.
3. Add persistence and material-change requirements to prevent frame-to-frame chatter.
4. Test cue priority and no-cue states.
5. Commit: `feat: derive concise pose coaching cues`.

**Verification:** Identical match inputs always produce the same cue and no cue judges appearance or body shape.

### Task 8: Implement the shoot reducer and capture invariants

**Objective:** Make capture/advance policy deterministic and independently testable.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/session/ShootState.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/session/ShootEvent.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/session/ShootEffect.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/domain/session/ShootReducer.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/domain/session/ShootReducerTest.kt`

**Steps:**

1. Write transition-table REDs for acquire dwell, release hysteresis, pause, stale frame, automatic capture, `ManualCaptureRequested` bypassing only match/lock, shared capture success, explicit `CaptureFailureCleanupConfirmed` recovery, explicit `CaptureFailureReconciliationRequired` terminal failure, duplicate confirmation/advance receipt, export-status events that cannot advance, and final completion.
2. Implement a pure `(state, event) -> state + effects` reducer.
3. Emit unique capture tokens and reject duplicate/stale receipts.
4. Use injected monotonic timestamps in tests.
5. Run focused tests repeatedly to prove determinism.
6. Commit: `feat: add idempotent guided shoot state machine`.

**Verification:** A generated/replayed event sequence can never advance two poses from one capture token.

### Task 9: Add telemetry-free MoveNet reference and live detector boundary

**Objective:** Execute the exact bundled MoveNet MultiPose model through minimal direct LiteRT, then translate its 17-point 2D output into the domain without leaking Android or LiteRT types into pure mapping policy.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/pose/movenet/MoveNetPoseDetector.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/pose/movenet/MoveNetResultMapper.kt`
- Create: `app/src/main/assets/movenet_multipose_lightning_float16_v1.tflite`
- Create: `docs/MODELS.md`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/pose/movenet/MoveNetResultMapperTest.kt`
- Create: `app/src/androidTest/java/com/tonyisup/poseguidesnap/pose/MoveNetPoseDetectorTest.kt`
- Create: `app/src/androidTest/assets/pose-fixtures/*`
- Create: `app/src/androidTest/assets/pose-fixtures/ATTRIBUTION.md`

**Steps:**

1. Document the exact MoveNet model/runtime pins, license, SHA-256, 17-point 2D limitations, score-alias semantics, and the rejected MediaPipe telemetry path.
2. Add mapper REDs for exact output shape, deterministic letterbox unpadding, immutable raw snapshots, person count/selection, invalid evidence, and the 17-to-domain identity map.
3. Implement the pure mapper and fixed direct-LiteRT blocking detector. The detector accepts only upright caller-owned bitmaps and owns no clock, threshold, executor, queue, or CameraX policy.
4. Add the licensed static-image instrumentation fixture plus black zero-person and composed two-person controls. Compile the contract without claiming execution.
5. Prove the exact APK has only the generated app-signature permission, the runtime graph is exactly LiteRT `1.4.2` plus `litert-api:1.4.2`, and model/fixture bytes match reviewed hashes.
6. Keep real detector instrumentation and threshold calibration pending for the authorized Pixel 6 gate.
7. Commit: `feat: integrate on-device pose landmark detection`.

**Verification:** Pure mapping is deterministic; no Android/LiteRT type appears under `domain/`; exact APK/runtime/model privacy and provenance gates pass. Runtime inference remains compile-only until authorized device acceptance.

### Task 10: Build the single-reference CameraX vertical slice

**Objective:** Prove preview, coordinate transforms, live detection, overlay, and exactly-three-output app-private capture mechanics on the real device before auto-capture. Do not expose a standalone manual persistence path.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/camera/CameraController.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/camera/CameraXController.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/camera/CoordinateTransform.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/ui/camera/GuidedCameraScreen.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/ui/camera/PoseOverlay.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/ui/camera/GuidedCameraViewModel.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/camera/CoordinateTransformTest.kt`
- Create: `app/src/androidTest/java/com/tonyisup/poseguidesnap/ui/camera/GuidedCameraScreenTest.kt`

**Steps:**

1. Write transform REDs for sensor rotation, crop, preview scaling, and rear-camera coordinates.
2. Bind Preview, ImageAnalysis with keep-latest backpressure, and ImageCapture. Convert each accepted analysis frame into an upright bitmap, close every `ImageProxy` in `finally`, and run the blocking MoveNet detector on one bounded off-UI worker. A newer pending frame replaces an older pending frame; never queue unbounded inference work.
3. Render a bundled reference and live skeleton in the same coordinate space.
4. Display named gate states rather than only an aggregate score.
5. Add deterministic same-directory temp writes, appropriate sync, and per-file atomic no-clobber publication for exactly three private outputs. On Android, claim the absent final identity with an exclusive empty reservation, verify that exact owned reservation before atomic rename, serialize every supported capture-directory mutation through one process-wide publisher/reconciler guard, and treat every existing/crash-leftover identity as non-authoritative reconciliation work. Retain prepared-output ownership across cleanup failure, block conflicting capture, and expose serialized cleanup retry; exact-three completion wins if close races after the third publication. Keep the user-facing manual trigger disabled until Task 14 connects these mechanics to the reducer, Room confirmation, and export outbox; there must be no second protocol.
6. Contain detector/mapper exceptions per frame so malformed output cannot terminate analysis. Measure latency, dropped frames, allocations/GC pressure, thermal behavior, and sustained resource cleanup locally without logging images, raw tensors, landmarks, or private paths.
7. Run on the Pixel 6 and save an evidence note under `docs/validation/`.
8. Commit: `feat: add guided camera pose slice`.

**Verification:** Preview, overlay, and all three candidate private captures align on the exact APK under test; write/finalization failure and collision do not clobber final files; camera-analysis resources close when leaving the screen. Gate 2 manual acceptance remains pending until Task 14 connects the full common protocol.

**Completion:** Specification PASS and quality/security APPROVED were recorded on exact staged digest `61a3fc581b16902dcd592f992b253ec70fe71ea727136001583c09a422b2f6dd`; commit `605c904` contains the approved bytes. JVM 266/266, lint/build, reproducible APKs, 15/15 relevant Pixel instrumentation, zero private capture residue, camera release, and aligned public live/reference skeleton checks passed. The final 60-second run remained thermally stable with bounded memory but did not improve over the pre-cadence CPU baseline; the 15-minute Gate 4 soak remains pending. No product shutter, auto-capture, Room confirmation/advance, import, export, audio, deletion, or end-to-end flow was enabled.

### Task 11A: Add Room authority for capture, confirmation, and deletion barriers

**Objective:** Establish the durable Room source of truth for shoots and capture authority without adding photo-picker or reference-import UI.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/AppDatabase.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/*Entity.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/data/db/*Dao.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/data/RoomShootRepository.kt`
- Create: `app/src/androidTest/java/com/tonyisup/poseguidesnap/data/RoomShootRepositoryTest.kt`

**Steps:**

1. Write REDs for schema migration, atomic pose ordering, capture-attempt identity, unique confirmation receipts, exactly three private-output records, one-outbox/exactly-three-export-output cardinality, composite output uniqueness, ordinal bounds, duplicate confirmation, rollback, and deletion-generation barriers.
2. Add Room schema and migrations from version 1 onward for shoots/poses/sessions, capture attempts, authoritative private outputs, confirmation/advance receipts, one export outbox, and exactly three constrained per-output rows. Include composite `(commandToken, burstOrdinal)` identity, ordinal 0–2 constraints, claim state/token, exact target collection/volume, intended metadata, exact URI, and deletion-generation state; never use destructive fallback in release code.
3. Add the repository transaction that atomically confirms an already-durable exactly-three-output attempt, records exactly three authoritative private outputs, advances once, applies the unique receipt, creates one outbox plus exactly three constrained output rows, and verifies cardinality before commit.
4. Add the atomic deleting/deletion-generation transition that blocks capture/advance and new claims while cancelling untouched pending work without deleting in-progress authority.
5. Run repository JVM/instrumentation tests, migrations, and force-close/relaunch checks. Do not add photo-picker result handling, reference copying, or import UI in this slice.
6. Stage the exact candidate, record its digest, and obtain specification PASS plus quality/security APPROVED on those same bytes.
7. Commit only the approved digest: `feat: add Room capture authority`.

**Verification:** Force-close/relaunch preserves Room state. Repository tests prove duplicate confirmation cannot advance twice; every confirmed token has exactly three private-output records, one unique receipt, one advance, one outbox, and exactly three constrained output rows; a failed cardinality or confirmation transaction creates none of those partial effects; and a deletion barrier blocks capture/advance and new claims without deleting in-progress authority.

**Implementation evidence:** The Task 11A candidate defines the Room V1 authority schema, strict integer ordinal triggers, deletion-aware registration/start authorization, atomic confirmation/advance/receipt/outbox persistence, deletion barriers, and targeted claim CAS with non-authorizing restart replay. JVM 307/307, lint, debug/release/instrumentation builds, schema stability, and all 93 current Room instrumentation methods passed across explicitly authorized Pixel 6 checkpoints. The final Task 11A.4 class passed 34/34, and the three final negative-generation regressions passed 3/3, with zero production-database use and zero test-database residue. This checkpoint adds no private-file coordinator, MediaStore I/O, physical deletion, reference import, UI, TTS, or end-to-end workflow.

### Task 11B: Add transactional reference import

**Objective:** Handle explicit system-picker results by copying and validating one reference into app-private authority without leaving partial rows or assets.

**Approved 2026-08-30 architecture amendment:** Three exact-digest candidates proved that current-filename inference plus process-local inode handles cannot safely compose Room with crash-interrupted file publication. Task 11B now requires the persisted reference-import filesystem ledger defined by `docs/adr/0003-persisted-reference-import-file-ledger.md`. Room must journal the admitted stage before and after each file/rename/delete/quarantine/fsync boundary; partial unsynced temp bytes are deleted rather than quarantined; only hash/count-bound synced bytes may be retained; reconciliation-required remains retryable; and logical `ASSET_READY`, `REJECTED_CLEANED`, or `REJECTED_QUARANTINED` cannot be persisted before the corresponding ledger stage is durable. Ordinary reservation replay is nonauthorizing except for exact committed replay; a new user-selected retry after coherent `REJECTED_CLEANED` must use a separate atomic command that resets both logical intent and `CLEANED_DURABLE` ledger authority before any provider read or file claim. This amendment supersedes any narrower Task 11B interpretation based only on deterministic filename inspection.

**Files:**
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/data/ReferenceAssetStore.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/importer/ReferencePickerResultHandler.kt`
- Create: `app/src/main/java/com/tonyisup/poseguidesnap/importer/ReferencePoseImporter.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/importer/ReferencePoseImporterTest.kt`
- Create: `app/src/androidTest/java/com/tonyisup/poseguidesnap/importer/ReferencePickerResultHandlerTest.kt`

**Steps:**

1. Write REDs for accepted system-picker results, cancelled/invalid results, valid one-person import, no-person rejection, multiple-person rejection, low-coverage rejection, failed private copy, detector failure, database failure, and cleanup failure.
2. Handle only explicit system-picker result URIs; copy selected bytes into app-private storage before extraction and do not retain provider-URI authority.
3. Run the bundled MoveNet detector off the UI thread and validate person count and required coverage through named policy.
4. Persist serialized landmarks with exact detector/model digest, runtime version, preprocessing/letterbox version, coordinate-transform metadata, and validation status.
5. Make asset-plus-row publication transactional at the repository boundary: success exposes both the validated pose row and private reference asset; every failure removes or quarantines the owned partial asset and leaves no active partial row. Never reinterpret an existing row/asset silently.
6. Run importer JVM tests and picker-result instrumentation tests, including force-close/relaunch preservation of validated references. Broader create/import/reorder UI remains Task 12.
7. Stage the exact candidate, record its digest, and obtain specification PASS plus quality/security APPROVED on those same bytes.
8. Commit only the approved digest: `feat: import validated reference poses`.

**Verification:** A valid picker result produces one durable app-private reference and one matching validated pose row with detector/preprocessing metadata. Cancellation, rejection, copy/detector/database failure, or restart exposes no partial active row or orphaned unquarantined asset; force-close/relaunch preserves validated references.

**Implementation evidence:** The persisted Room file-operation ledger, journal-backed importer, exact cleanup/quarantine recovery, V1→V2 migration, generated-byte picker path, and public-fixture analyzer landed in `d368e96a0335b1281471faca706287c4980652f0`. Host gates pass 413/413 JVM tests, lint, debug/release APK assembly, and instrumentation assembly. The explicitly authorized Pixel 6 gate passed all 25 targeted methods on Android 16/API 36, including migration, restart, concurrency, rollback, redaction, and residue checks. Exact APK hashes and the corrected Room/serialization runtime dependency boundary are recorded in `docs/validation/2026-08-30-task11b-persisted-reference-import-ledger-pixel6.md`. Task 12 UI remains deferred.

### Task 12: Add Room V3 shoot preparation and playlist editing UI

**Objective:** Provide the minimum safe preparation workflow for a hands-free session: create → import → validate → reorder → durably start, with camera permission unreachable until Room owns an active session.

**Approved architecture amendment:** The original UI-only Task 12 was not executable because production code had no shoot create/list/reorder/start API and V2 rejected/quarantined import attempts permanently occupied unique playlist positions. The owner approved Room V3 decoupling of immutable import attempts from mutable active playlist order, durable idempotent Room session creation, and navigation to the existing camera diagnostic only after successful start. See `docs/adr/0004-room-v3-shoot-preparation-authority.md`.

**Execution plan:** Follow `.hermes/plans/2026-08-31-task12-room-v3-shoot-preparation.md` exactly. It splits the work into:

1. Room-owned maximum-20 import admission before provider access.
2. V3 migration removing `pose_index` from reference-import intents while preserving V1/V2 authority.
3. Redacted shoot-preparation create/list/editor/import-allocation contracts.
4. Atomic validated-pose reorder with `shoot_poses` as the sole order authority.
5. Durable, idempotent start with exactly one active session per shoot.
6. Accessible list/editor/Photo Picker UI and camera routing only after start.

**Scope boundary:** Task 12 may enter the existing Task 10 camera diagnostic after durable start. It must not add Task 13 TTS or Task 14 automatic/manual capture coordination, MediaStore I/O, or physical deletion behavior.

**Verification:** A five-pose shoot can be created, imported, reordered, and durably started without camera permission during preparation. Room owns every cardinality/order/start decision; a terminal rejected or quarantined import does not block replacement; exact start replay creates no duplicate session.

**Implementation evidence:** Task 12 landed across the reviewed commits from `6df8b4431e3cc28ceb9c12736caf0e09f1ec6276` through `5bc15c33c5b6c36196f99a2bd8259fe2b00ffeb3`. The final host gate passed 556/556 JVM tests plus lint and debug/release/instrumentation APK assembly. The final authorized Pixel 6 gate passed 4/4 synthetic-state `ShootEditorFlowTest` methods for recovery rendering, row-scoped semantics/reorder callbacks, start enablement/callback behavior, and visible import/reconciliation states. A separate manual Pixel run on a pre-final artifact observed the production create/import/reorder/start path, picker recreation, and camera-permission boundary; it is not same-artifact proof for `5bc15c3`. The final follow-up passed specification and quality/security/UX review on exact staged digest `23318b4fa98a15405462f3419d1a5d47ac84af0ef12a79b40b1668d4212d0864`. See `docs/validation/2026-09-01-task12-room-v3-shoot-preparation-pixel6.md`. Task 13 speech and Task 14 capture/export remain deferred.

### Task 13: Add Text-to-Speech coaching with cadence control

**Status:** Deferred. Standalone speech duplicated foreground, lifecycle, cancellation, timeout, and resource authority before the guided-session coordinator exists. Task 15 will derive a bounded speech subplan from that coordinator; Task 17 retains human-heard speaker/earbud acceptance.

### Task 14: Add unified durable capture and MediaStore export outbox

**Status:** Split into separately approved ownership changes after broader plans proved unreviewable.

**Task 14A.1 implemented:** One immutable, redacted, exact-session Room V3 bootstrap validates the complete persisted attempt/receipt/private/outbox/export graph under one immediate transaction. The exact APK pair passed 6/6 Pixel Room tests; see `docs/adr/0005-atomic-room-v3-guided-session-bootstrap.md` and `docs/validation/2026-09-02-task14a1-atomic-room-v3-bootstrap-pixel6.md`.

**Next boundary:** Add active-session discovery and UI/process-death resume without changing bootstrap authority. Capture-file reconciliation, coordinator integration, MediaStore, and deletion completion each require later independent approval.

### Task 15: Integrate the complete hands-free sequence

**Objective:** Deliver the full import → coach → capture → advance → complete loop.

**Files:**
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/ui/camera/GuidedCameraViewModel.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/ui/camera/GuidedCameraScreen.kt`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/ui/navigation/AppNavHost.kt`
- Create: `app/src/test/java/com/tonyisup/poseguidesnap/session/FivePoseSessionTest.kt`
- Create: `app/src/androidTest/java/com/tonyisup/poseguidesnap/session/GuidedShootEndToEndTest.kt`

**Steps:**

1. Add a fixture-driven five-pose RED using replayed landmark streams.
2. Integrate repository, matcher, coaching, reducer, speech, and capture ports.
3. Add pause, resume, skip, retry, manual capture, and stop controls. The manual control dispatches `ManualCaptureRequested` to the reducer and uses Task 14's exact three-photo private confirmation/outbox pipeline; it bypasses no durability, ownership, cleanup, or receipt rule.
4. Add progress announcements and completion summary.
5. Keep diagnostics user-visible in debug builds: gate status, score components, cue, state, inference latency, and capture token suffix.
6. Run the replay E2E test and full JVM/instrumentation suite.
7. Commit: `feat: complete guided pose sequence workflow`.

**Verification:** The deterministic replay completes five poses with five unique confirmed tokens and the expected bounded utterance transcript. Automatic and manual trigger variants produce the same capture effects and exactly-once confirmation shape.

### Task 16: Calibrate thresholds and harden performance

**Objective:** Replace development defaults with documented evidence and ensure sustained device operation.

**Files:**
- Create: `tools/calibration/README.md`
- Create: `tools/calibration/analyze_match_reports.py`
- Create: `app/src/test/resources/calibration/*`
- Create: `docs/validation/MATCH_CALIBRATION.md`
- Create: `docs/validation/PERFORMANCE.md`
- Modify: `app/src/main/java/com/tonyisup/poseguidesnap/domain/match/MatchPolicy.kt`

**Steps:**

1. Define authorized positive and negative fixture classes without committing private photos.
2. Export only derived, non-image match reports for offline analysis.
3. Measure positive/negative separation, false-lock cases, time-to-lock, inference latency, cue rate, and duplicate-capture rate.
4. Select acquire/release/coverage/dwell defaults from the evidence; retain user-safe bounds.
5. Add regression fixtures at the chosen boundaries.
6. Run a sustained 15-minute camera-analysis session and inspect thermal/battery/frame-latency behavior.
7. Commit: `test: calibrate pose lock and performance gates`.

**Verification:** `MATCH_CALIBRATION.md` states dataset limits and does not imply population-wide accuracy from a small internal set.

### Task 17: Run the explicit Pixel 6 and earbud acceptance gate

**Objective:** Verify the actual interaction on the private device without substituting emulator or desktop evidence.

**Files:**
- Create: `docs/validation/PIXEL_6_ACCEPTANCE.md`
- Create: `docs/validation/PRIVACY_ACCEPTANCE.md`

**Steps:**

1. Pause for explicit permission before using the private device and earbuds.
2. Record the APK SHA-256 and device/build identifiers.
3. Exercise phone speaker and connected-earbud routes without forcing routing APIs.
4. Run the full Gate 4 matrix: five-pose sequence, unified automatic/manual capture, no person, partial body, low light, occlusion, second person, permission denial/recovery, background/foreground, private-file write/finalization/collision failure, Room transaction/cardinality failure, pre/post-confirmation restart, exactly-once advancement, exclusive-claim export races and crash seams, deletion-barrier/reconciliation/quarantine behavior, and airplane mode.
5. Inspect app-private storage, Room/outbox state, MediaStore, and logs for authoritative-output durability, unintended frame/landmark retention, duplicate export, exact-URI authority, foreign-row preservation, and deletion-barrier behavior. Inspect source/merged manifests and rule resources, then exercise authorized backup/restore tooling where the platform permits to confirm sensitive state is not transported or partially restored.
6. Record failures honestly; do not lower thresholds to manufacture a pass.
7. Rebuild only if code changes, then repeat all same-digest reviews.
8. Commit: `test: document Pixel 6 guided shoot acceptance`.

**Verification:** The report names exact artifact/device evidence and clearly separates automated, replay, and authorized-real results.

### Task 18: Final documentation and release-readiness review

**Objective:** Make the repository truthful, reproducible, and ready for a separate distribution decision.

**Files:**
- Modify: `README.md`
- Modify: `docs/PRODUCT.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/TESTING.md`
- Modify: `docs/PRIVACY.md`
- Modify: `docs/DEVELOPMENT.md`
- Create: `docs/KNOWN_LIMITATIONS.md`
- Create: `docs/RELEASE_CHECKLIST.md`

**Steps:**

1. Document only verified features, including separate private-capture confirmation and MediaStore export status.
2. Add setup, build, test, install, permission, data-deletion, reconciliation, export-retry, and troubleshooting instructions. State that shoot deletion and delete-all use the deletion-generation barrier and may retain a visible unresolved tombstone/quarantine rather than report false success; clear-data/uninstall cannot promise deletion of already or ambiguously exported MediaStore items.
3. Document viewpoint, occlusion, lighting, clothing, mobility, and detector limitations.
4. Run:
   - `./gradlew clean testDebugUnitTest lintDebug assembleDebug`
   - connected instrumentation tests on the approved device
5. Stage the exact candidate, calculate its digest, and perform same-digest specification and quality/security review.
6. Commit only after both reviews approve the same staged digest.
7. Do not publish; create a separate distribution plan if publication is requested.

**Verification:** A new developer can build and run tests from the documented environment, and a user can understand what data exists and how to delete it.

## 8. Test Matrix

### Pure JVM tests

- Coordinate validation and immutable model invariants.
- Translation/scale normalization.
- Mirror semantics and left/right cue correctness.
- Joint-angle and position scoring.
- Coverage/framing/second-person gate separation.
- Seeded jitter stability.
- Cue selection, persistence, cooldown, and priority.
- Reducer transition table, automatic/manual ownership, hysteresis, timing, idempotent confirmation/advance receipt, pause, failure, and completion.
- Exactly-three private output identities, per-file write/sync/finalization failure, no-clobber collision, and cleanup/quarantine behavior.
- Atomic Room confirmation + three authoritative output records + one advance + one receipt + one export outbox entry, including transaction rollback.
- Pre-/post-Room crash boundaries, exactly-once advancement, one-outbox/exactly-three-row cardinality, composite uniqueness, and ordinal constraints.
- Exclusive pre-create claim, paused two-worker one-insert interleaving, claim/create/URI/publication crash seams, exact-URI-only mutation, missing-URI reconciliation without lease reset, and foreign-row preservation.
- Atomic deletion-generation barrier, delete-vs-worker interleavings, quarantine retention/resolution/visible state, delete-all through the same barrier, incomplete tombstone behavior, and exported/ambiguous/foreign-row preservation.
- Repository/coordinator behavior through fakes.
- Five-pose replay transcript and capture tokens.

### Android instrumentation tests

- Exact MoveNet model load and one/zero/two-person static fixture extraction; compile first and run only on an authorized device.
- Bounded off-UI keep-latest scheduling, guaranteed `ImageProxy` closure, per-frame detector/mapper failure containment, and no unbounded inference queue.
- Room transactions, ordering, migration, and uniqueness.
- Photo-picker import result handling.
- Compose create/import/reorder/start flow.
- Camera permission denial/recovery.
- Preview/analysis/capture coordinate alignment.
- Text-to-Speech offline-voice selection, network-required voice rejection, absent `INTERNET` permission, lifecycle, and visual-only fallback.
- Exactly three deterministic app-private output publications for automatic and manual capture, including temp cleanup, sync/finalization failure, no-clobber collision, and durable-file inspection.
- Room confirmation transaction rollback, receipt uniqueness, exactly-once advancement, pre/post-transaction process restart, cleanup/quarantine, and reconciliation blocking.
- Post-confirmation MediaStore outbox export with exact three-row schema, compare-and-set claim, exact target and URI state, paused concurrency, full crash-seam coverage, ambiguous-create fail-closed behavior, foreign-row preservation, and no capture/advance replay.
- Shoot deletion-generation ordering, claim/create/publication barrier rechecks, untouched-pending cancellation, in-progress authority retention, quarantine lifecycle/visible unresolved state, delete-all, and preservation of already or ambiguously exported and foreign MediaStore items.
- Source and merged manifest plus XML-rule assertions for complete cloud/device/cross-platform backup exclusion, with authorized platform backup/restore inspection where tools permit.

### Authorized real-device tests

- Rear camera full-body framing at practical tripod distances.
- Speaker and Bluetooth earbuds.
- Five-pose no-touch completion plus reducer-owned manual capture through the identical three-photo protocol.
- Low light, occlusion, second person, and subject exit/re-entry.
- App pause/resume; private capture, Room confirmation, export, cleanup, and reconciliation failure recovery.
- Shoot deletion and clear-data/uninstall behavior, including the platform limit for already exported MediaStore photos.
- Authorized backup/restore inspection where supported, including source/merged manifest and exact rule-content evidence that capture, Room, DataStore, outbox, tombstone, and quarantine state is excluded.
- Airplane-mode operation.
- Sustained performance and thermal behavior.

## 9. Risks and Mitigations

| Risk | Impact | Mitigation / gate |
|---|---|---|
| 2D pose similarity fails under viewpoint changes | Incorrect cues or impossible lock | Import warnings, viewpoint-limited MVP, independent angle/position gates, calibration negatives |
| Coordinate mismatch between analysis and captured crop | Overlay looks right but saved image is wrong | One transform authority, deterministic transform tests, Gate 2 real-device evidence |
| Capture duplicates or advances early | Core trust failure | Pure reducer for automatic/manual triggers, deterministic tokens, durable private outputs, unique Room receipt, transactional advancement, crash/replay tests |
| Speech becomes noisy or stale | User confusion when screen is distant | One-cue policy, persistence, bounded queue, state priority, cancellation tests |
| TTS is inaudible or steals audio unexpectedly | Hands-free flow fails | Current media route, transient focus, explicit phone/earbud acceptance, visual fallback |
| MoveNet/model or preprocessing changes alter scores | Existing references drift | Persist detector version, model digest, preprocessing version, calibration fixtures, explicit reprocessing path |
| Private authority or export state is lost/duplicated | Privacy or trust harm | No-clobber publication, exact-three outbox rows, exclusive create claim, exact-URI authority, ambiguous-create reconciliation, deletion barrier, and privacy acceptance |
| Android backup/transfer partially restores private state | Privacy leak or broken authority/receipt invariants | `allowBackup=false`, fail-closed legacy/API31+/cross-platform XML rules, no BackupAgent, merged-manifest/rule tests, authorized platform inspection |
| Reference imports retain sensitive images unexpectedly | Privacy harm | App-private copy, no frame persistence, deletion contract, privacy acceptance |
| CameraX use-case combination degrades resolution/performance | Poor photo or low inference FPS | No extensions, keep-latest analysis, measured target resolutions, Pixel 6 performance gate |
| Empty-repo bootstrap expands into architecture theater | Slow delivery | Single module, first useful vertical slice, defer front camera/iOS/groups/generative features |
| Threshold tuning papers over representation errors | False confidence | Named subscores, negative controls, no production threshold before calibration report |

## 10. Approved Decisions

1. **Platform:** Android-native first rather than React Native/Flutter or simultaneous iOS.
2. **Publishing identity:** Provisional application ID `com.tonyisup.poseguidesnap`; any permanent publishing-identity change requires a later explicit decision.
3. **Minimum Android version:** `minSdk 29` for MVP simplicity, with market coverage revisited only after proof of value.
4. **Rear-camera-first scope:** Front camera is post-Gate-4 work.
5. **Reference semantics:** Automatic horizontal mirroring by default, with a per-pose opt-out.
6. **Capture policy:** Exactly three photos per automatic or manual reducer command. Manual bypasses only pose-match/lock eligibility; both paths require three durable private outputs and the same Room confirmation/advance receipt.
7. **Storage contract:** App-private captures are authoritative. One Room transaction owns confirmation, three output records, exactly-once advancement, receipt application, and export-outbox creation. MediaStore export is idempotent post-confirmation work with explicit separate deletion behavior.
8. **Pose runtime:** The exact bundled MoveNet MultiPose model runs through minimal direct LiteRT `1.4.2`. The detector is fixed and blocking; imported references call it off the UI thread, while live CameraX analysis uses a separate one-worker keep-latest scheduler with per-frame failure containment.

**Approval recorded:** Defaults 1–5 and the bounded Tasks 1–8 phase were approved on 2026-08-27. Decisions 6–7 supersede the earlier MediaStore-authoritative capture/storage wording. Decision 8 was approved on 2026-08-28 after the MediaPipe telemetry conflict and replacement spikes. Later changes to these boundaries require an explicit plan revision.

## 11. Completed Execution Slices

The approved first bounded phase executed **Tasks 1–8 only**:

- establish docs and toolchain,
- bootstrap the app,
- build the pure pose/match/coach/session domain,
- produce a deterministic replay report,
- stop before detector integration, camera, earbuds, or private image access.

This yielded the core ownership boundary and causal tests without touching private device data. Task 9 then added the reviewed direct MoveNet/LiteRT boundary. Task 10 added the committed, authorized-Pixel CameraX pose slice at `605c904`, including the fixed attributed reference, aligned live/reference skeletons, and internal candidate-capture mechanics. Task 11A added committed Room capture authority at `5335466`; Task 11B added the committed transactional reference-import backend at `d368e96`; Task 12 added committed Room V3 preparation plus the create → Photo Picker import → validate → reorder → durably start workflow through `5bc15c3`; and Task 14A.1 added atomic exact-session Room V3 reconstruction. Gate 2 remains unpassed. Active-session discovery/UI resume is next; standalone Task 13 speech is deferred into Task 15.

## Sources

[1] https://developer.android.com/media/camera/camerax/architecture — CameraX architecture
[2] https://www.kaggle.com/models/google/movenet/tfLite — official Google MoveNet registry and model contract
[3] https://developer.android.com/training/data-storage/room — Save data in a local database using Room
[4] https://developer.android.com/media/optimize/audio-focus — Manage audio focus
[5] https://developer.android.com/identity/data/autobackup — Android Auto Backup, device transfer, and cross-platform transfer configuration
