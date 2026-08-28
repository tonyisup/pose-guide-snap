# Architecture Contract

> **Project status: buildable prototype only.** A minimal Compose activity exists; this document defines intended architecture and ownership rules for product components that have not been implemented.

## Fixed MVP decisions

| Decision | Approved value |
|---|---|
| Platform | Native Android first |
| Application ID | `com.tonyisup.poseguidesnap` provisionally |
| Minimum Android version | `minSdk 29` |
| Acceptance device | Pixel 6 running Android 16 |
| Camera | Rear camera first |
| Pose inference | On device for imported references and live observations |
| Mirror behavior | Automatic horizontal mirror matching by default; per-pose opt-out |
| Automatic capture | Three-photo burst after stable lock |
| Storage | Backup/transfer-excluded app-private references, landmarks, authoritative captures, and quarantine; post-confirmation MediaStore export |

The implementation will begin as one Android app module. Package boundaries and dependency tests will enforce separation before build time or ownership provides evidence that Gradle modules are worth their cost.

## Planned runtime shape

```text
System Photo Picker
        |
        v
ReferencePoseImporter --> PoseDetector(IMAGE) --> ReferencePose
        |                                         |
        +--------------> ShootRepository <--------+
                                                  |
CameraX Preview + ImageAnalysis                   v
        |                                GuidedShootCoordinator
        v                                         |
PoseDetector(blocking, off-UI) -> PoseObservation -> PoseMatcher
                                                  |
                                        MatchResult + CoachingCue
                                           |                |
                                           v                v
                                    ShootStateReducer   SpeechCoach
                                           |
                                  CaptureCommand(token)
                                           |
                                           v
                                  CameraX ImageCapture
                                           |
                                           v
                         App-private files + Room confirmation
                                           |
                                           v
                              MediaStore export outbox worker
```

CameraX is planned to provide preview, CPU image analysis, and still image capture. The implemented pose boundary runs the exact bundled MoveNet MultiPose Lightning model through direct LiteRT `1.4.2`. Its detector is deliberately blocking and accepts only upright bitmaps; the future camera adapter owns one bounded off-UI worker, keep-latest backpressure, rotation/crop conversion, per-frame failure containment, and `ImageProxy` closure. Room is planned for ordered shoot, pose, session, and capture relationships. DataStore is reserved for small preferences such as voice enablement, speech cadence, dwell duration, and match thresholds.

## State and policy ownership

| Boundary | Owns | Must not own |
|---|---|---|
| `domain/model` | Immutable pose, match, cue, shoot, capture-token, and session values | Android or SDK objects |
| `domain/match` | Coordinate-independent normalization, allowed mirroring, feature extraction, scoring, and independent lock gates | Camera lifecycle, capture, speech, persistence, or sequence advancement |
| `domain/coach` | Deterministic selection of one actionable cue and suppression policy | Free-form generation, audio routing, shoot state, or capture policy |
| `domain/session` | The reducer/state machine and the sole authority to request capture or advance the sequence | CameraX, LiteRT, Room, TTS, Compose, or wall-clock calls |
| `pose/movenet` | Fixed bundled-model loading, direct blocking LiteRT inference, deterministic letterbox geometry, and conversion into immutable 17-point 2D domain observations | Camera scheduling, hidden clocks, match thresholds beyond explicit mapper policy, coaching, capture, or sequence state |
| `camera` | CameraX binding, upright frame conversion, one bounded off-UI inference worker, keep-latest backpressure, per-frame failure containment, rotation/crop transforms, `ImageProxy` closure, and still-capture mechanics | Lock policy or sequence advancement |
| `audio` | Text-to-Speech lifecycle, bounded cadence/queue behavior, audio focus, and current-route playback | Bespoke Bluetooth routing or shoot policy |
| `data` | Room entities/DAOs, DataStore preferences, app-private reference/capture assets, and MediaStore export state | Match, coaching, capture eligibility, or sequence-advancement decisions |
| `ui` | Compose rendering, accessibility, input, and display of named states | Score calculation, hidden threshold changes, capture decisions, or sequence advancement |
| `GuidedShootCoordinator` | Translate adapter inputs into reducer events and interpret reducer effects through ports | Invent policy outside reducer transitions |

Camera frames and MoveNet outputs must become immutable domain observations before decision logic runs. Camera, speech, time, storage, and capture are ports. They are not owners of shoot policy.

## Planned state machine

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

1. An automatic `CaptureCommand` can be emitted only from `Locked`. A manual request is also a reducer event and bypasses only the pose-match/lock gate; both paths produce the same three-photo command and protocol.
2. One command token identifies exactly three outputs with deterministic `(commandToken, burstOrdinal)` identities for ordinals 0–2.
3. For each output, the capture adapter writes a same-directory temporary file, syncs file data as appropriate, and atomically publishes the final app-private file without clobbering an existing identity. Each final-file publication is atomic; the three-file set is not.
4. Capture is not eligible for confirmation until all three authoritative private files exist durably. A collision, private write/finalization failure, or uncertain cleanup produces no advancement.
5. One Room transaction is the logical ownership boundary: it confirms the attempt, records all three authoritative private outputs, advances the pose exactly once, marks the unique confirmation/advance receipt applied, and creates a durable MediaStore export outbox entry.
6. If that Room transaction fails, it creates no confirmation, advance, receipt, or outbox. Unconfirmed private files are cleaned or quarantined. If resolution cannot be proven, the attempt enters reconciliation-required and automatic recapture is forbidden.
7. A crash before the Room transaction may leave only unconfirmed private files; startup resolves them by deterministic attempt identity before retry. A crash after the transaction leaves capture and advancement complete and replays only pending export work.
8. The confirmation transaction creates one committed outbox and exactly three per-output rows. `CaptureExportOutput` has composite primary key or uniqueness `(commandToken, burstOrdinal)`, a database constraint limiting `burstOrdinal` to 0–2, and a transaction-time cardinality assertion of exactly three before commit.
9. A Room compare-and-set transition from `pending` to `claimed` with a fresh unique claim token is the exclusive pre-create authority. Only the winner may call `MediaStore.insert()`. A row that reaches claimed/create-started without a durably stored exact URI is never returned to pending, never lease-expired into another create, and enters reconciliation-required after interruption.
10. Each output stores the exact target MediaStore collection/volume and intended metadata for diagnostics. Display name and relative path are not unique authority and may never, alone or together, authorize lookup, update, delete, or reconciliation.
11. Immediately after `insert()` returns, the worker durably stores that exact URI before any later fallible publication step. Automatic update or delete is allowed only through that output's durably recorded exact URI. Missing-URI ambiguity fails closed and preserves all possibly foreign rows.
12. Capture commands carry a unique token derived from session ID, pose ID, and attempt number. Replayed frames, duplicate callbacks, duplicate confirmations, and duplicate worker runs cannot advance or export a known output twice.
13. Lock acquisition uses a higher threshold than lock release, with a dwell period, hysteresis, and cooldown. Pausing cancels pending speech and capture countdowns.
14. Delete-shoot atomically marks the shoot `deleting`, advances a deletion generation, blocks capture/advance and new exporter claims, and cancels only untouched pending export work. The worker rechecks the same generation/barrier immediately before external create and before publication.
15. Deletion does not remove private authority or outbox state while claim/create/publish work is in progress. It waits for or safely resolves exact-URI work; ambiguity becomes reconciliation-required. Unsafe completion retains a minimal tombstone plus quarantine/reconciliation state and returns incomplete—not success.

## Pose-match contract

The match pipeline must not collapse trust decisions into one Euclidean distance or weighted average. It will:

1. Reject observations with zero people or more than one sufficiently visible person.
2. Transform sensor coordinates into the displayed and captured crop coordinate system.
3. Remove landmarks below the visibility and presence floor.
4. Evaluate normal and horizontally mirrored candidates when the reference permits mirroring. Mirroring is allowed by default and can be disabled per pose.
5. Normalize translation around the torso center and scale by torso or shoulder/hip geometry.
6. Calculate weighted joint-angle features and normalized landmark-position error separately.
7. Return named coverage, framing, angular-similarity, positional-similarity, and overall-match values.
8. Refuse lock whenever any required gate fails. A high aggregate score cannot hide missing legs, poor framing, or a second person.

The selected 17-point MoveNet model is strictly 2D. It cannot recover depth and omits hand detail, heels, foot indices, mouth corners, and inner/outer eye points; occlusion and viewpoint remain ambiguous. Architecture and UI must preserve that limitation. The system reports a **pose match**, never a perfect pose or guaranteed good photograph.

## Coaching contract

- Framing and coverage corrections have priority over limb corrections.
- Emit one concise cue at a time from a fixed semantic vocabulary.
- Select the largest actionable error with sufficient confidence.
- Require persistence across frames before speaking and suppress repeats for a configurable interval unless the error materially worsens.
- Use relative instructions such as “raise your left hand” or “step back.”
- Never speak numeric scores during normal shooting.
- Announce lock, capture, next pose, pause, and completion as state transitions.
- Follow Android's current media route and audio-focus behavior; do not force Bluetooth routing.
- Select only an installed TTS voice verified as not requiring a network connection. If none is available, expose recoverable visual-only coaching and do not synthesize speech.

## Data ownership

Planned structured records include shoot, ordered pose, session, and these capture records:

- `CaptureAttempt`: command token, session/pose ownership, automatic/manual trigger, lifecycle state, reconciliation flag, and timestamps.
- `PrivateCaptureOutput`: command token, ordinal 0–2, deterministic app-private identity/path, durability state, capture metadata, and integrity metadata as needed.
- `CaptureConfirmationReceipt`: a unique command-token marker showing that the Room confirmation/advance transaction has already been applied.
- `CaptureExportOutbox`: exactly one committed outbox per command token.
- `CaptureExportOutput`: composite key `(commandToken, burstOrdinal)`, ordinal constraint 0–2, exact target collection/volume, intended metadata, pending/claimed/create-started/exported/reconciliation-required state, unique claim token, durably recorded exact URI when known, and retry/diagnostic metadata. Exactly three rows must exist before the owning confirmation transaction commits.

Selected references and every confirmed capture are authoritative in app-private storage; live analysis frames are never persisted. Filesystem operations do not claim multi-file atomicity. After all three per-file no-clobber publications are durable, one Room transaction atomically owns logical confirmation, the three private-output records, exactly-once advancement, receipt application, and outbox creation.

The export worker consumes only committed outbox work and uses a durable compare-and-set claim before any create. It stores the returned exact URI before publication and never uses display name or relative path as mutation authority. A claimed/create-started row with no durable URI is reconciliation-required forever unless explicitly resolved; timeout or restart cannot authorize a second create.

Delete-shoot and app-level delete-all use the same atomic deletion-generation barrier. They block new capture/advance and claims, cancel untouched pending work, and retain in-progress authority until exact-URI work settles. Quarantined images remain app-private, are tied to reconciliation metadata, appear as a user-facing unresolved count/state, and remain until explicit resolution or successful app-level deletion. If resolution is unsafe, deletion reports incomplete and retains the minimal tombstone. OS clear-data or uninstall can forcibly remove private quarantine and state, but cannot promise removal of MediaStore rows that were already exported or whose creation outcome was ambiguous; foreign rows are never selected by metadata for cleanup.

All app-private references, captures, Room databases, DataStore preferences, outbox/tombstone metadata, and quarantine are excluded from Android system cloud backup, device-to-device transfer, and supported cross-platform transfer surfaces. The manifest must set `android:allowBackup="false"` and reference both legacy `android:fullBackupContent` and API 31+ `android:dataExtractionRules` fail-closed resources. No custom `BackupAgent` is permitted. Partial restore of capture, Room, or outbox state is forbidden because it can split authority from receipts and violate exactly-once invariants.

See [Privacy](PRIVACY.md) for the complete data contract and [Testing](TESTING.md) for the gates that must prove these boundaries.

## Dependency direction

Domain packages must remain pure Kotlin. They may not import Android, CameraX, LiteRT, Room, Text-to-Speech, Compose, or concrete storage APIs. Adapters depend inward on domain contracts. UI and platform callbacks submit events; they do not mutate session state directly.

This direction will be enforced with source-level dependency tests before camera or model integration begins.

## Decision records

- [ADR 0001: Android native first](adr/0001-android-native-first.md)
- [ADR 0002: On-device pose processing](adr/0002-on-device-pose-processing.md)
