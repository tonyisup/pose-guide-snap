# Architecture Contract

> **Status:** see [Development progress](DEVELOPMENT_PROGRESS.md) for the current state of the project and the [25 September 2026 review](PROJECT_REVIEW_2026-09-25.md) for the current assessment and milestone order.

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

The implementation uses one Android app module. Package boundaries and dependency tests enforce separation until build time or ownership provides evidence that additional Gradle modules are worth their cost.

## Runtime shape

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

CameraX provides rear preview, CPU image analysis, and still capture through one shared viewport. The pinned MoveNet boundary remains on device with bounded workers, keep-latest backpressure, rotation/crop conversion, per-frame containment, and `ImageProxy` closure. Tasks 11–14 establish reference import, started-session admission, Room V4 capture authority, exact app-private effects, journal-derived confirmation, and startup cleanup. Task 15A adds one route-owned guided ViewModel and a serialized coordinator: manual reducer effects enter Room before each CameraX write, produce exactly three durable private files, confirm and advance once, or settle through recovery. MediaStore export intent is persisted during confirmation, but no MediaStore operation runs. DataStore remains reserved for small preferences such as voice enablement, speech cadence, dwell duration, and match thresholds.

The September 4 repair connects reference-import recovery before each editor observation and retry. Live imports and recovery share one process-wide protocol lock, and incomplete recovery blocks admission with a retryable state. Independently owned Room instances enable cross-instance invalidation so the list observes editor writes. Camera permission recovery distinguishes a first request, denial with rationale, and a settings action after permanent denial.

Pose observations carry the dimensions of the upright inference image. Rendering retains coordinates normalized independently by width and height; matching converts x (and width-relative depth) to image-height units before torso normalization. Task 15A strictly reconstructs saved reference dimensions from exactly one `decoded=WxH` preprocessing field and uses the selected reference for both the ghost overlay and visible uncalibrated match evidence. No schema migration was needed.

Task 16A adds an offline evidence boundary beside the runtime architecture. The runtime framing evaluator compares subject-center and body-scale evidence, requires confidence-qualified head, bilateral arm/lower-leg, and torso coverage, and fails closed when evidence is insufficient. Its reference box uses the full qualified reference body so missing live extremities cannot disappear from both sides of the comparison. It returns immutable scalar evidence only. A closed JSON schema accepts those derived match scores and relative event observations, then replays the same mandatory acquisition gates, lower lock-retention gates, and reducer timing for analysis. The runtime `MatchResult` carries immutable acquisition and release failure sets; the reducer consults release eligibility only while a lock is already held. The analyzer cannot receive images, raw landmarks, paths, or URIs, and it does not choose thresholds or authorize capture. The runtime matcher and reducer remain the sole product policy implementation.

Task 16B supplies the device-side producer for that closed boundary. An explicitly selected instrumentation method validates fixed authorization and bounded timing arguments, asserts Pixel 6 hardware and camera permission, and reuses the full-screen production CameraX crop plus bundled public reference. Each callback converts the temporary pose observation immediately into scalar evidence; the collector retains at most 600 derived frames and writes one synced, hashed JSON report below backup-excluded app-private storage. The exact authorized Pixel run passed both invocations and cleaned up, but the positive sequence failed canonicalization on all 97 frames.

Task 16C adds the minimum bounded evidence needed to diagnose that failure. Schema v2 records only a closed evaluation status, the total count of confidence-qualified MoveNet landmarks, and the count of qualified shoulder/hip anchors. It retains no landmark identity or coordinate. The analyzer reads v1 history, requires the diagnostic fields for v2, and cannot acquire or retain a replayed lock from a v2 frame that did not reach evaluation. Its first exact positive Pixel run produced 97 `no-person` frames with zero qualified counts, proving the categorical distinction but not reproducing Task 16B's one-person canonicalization condition. Its corrected repeat failed before recording when warm-up did not reach five consecutive exactly-one-person frames, proving that unusable input is now rejected without a report.

Task 16D keeps raw model diagnostics inside the adapter boundary. Each six-slot output is reduced immediately to the strongest finite instance score in `[0, 1]`; no slot identity or tensor surface leaves the mapper. The immutable analyzed frame carries that nullable scalar, and calibration preflight retains only the maximum seen for a failed assertion beside the unchanged `0.25` threshold. The scalar is not part of the persisted calibration schema and cannot enable capture or select thresholds by itself.

Its exact Pixel repeat processed 146 warm-up frames at about 9.7 frames per second, while every mapped frame remained no-person and the maximum valid raw instance score stayed at `0.0`. This leaves frame delivery intact and makes the live-camera image path or scene the leading boundary to investigate, subject to a same-artifact static public-fixture recheck of model loading, preprocessing, inference, and mapping.

Task 16E passed that same-artifact static recheck. Task 16F therefore derives `FrameVisualStatistics` from a fixed at-most-16×16 grid of the already-owned upright CameraX crop before inference. The reducer ignores alpha, computes normalized RGB luminance mean and range, and discards the samples. Only the two bounded scalars reach `AnalyzedCameraFrame`; failed calibration preflight retains three aggregate extrema. The values do not enter the calibration schema or alter model input, mapping, matching, or capture policy.

Task 16F's exact Pixel run proved that varied luminance reaches inference while every observed person-instance score in that run remained zero. Task 16G therefore reduces the raw score at every keypoint position across all six output slots to one maximum valid scalar. The reduction happens inside `MoveNetRawOutput`, and no slot or keypoint identity crosses the adapter. `AnalyzedCameraFrame` carries only that maximum; failed preflight retains only the maximum across its window. The final corrected Pixel diagnostic found positive person/keypoint maxima below the existing person gate, localizing the failure to detector confidence rather than an entirely absent output signal.

Task 16H adds those two nullable maxima to schema-v3 `DerivedCalibrationFrame` output. The detector-gate collection method bypasses only the current person-acceptance preflight; it retains the same bounded timing, frame cap, device check, authorization, private output, sync/hash, and cleanup boundaries. Its ground truth is closed to one full-body person or an empty scene. The exact Pixel 6 pair collected 97 frames per case, but both remained `no-person` and their person/keypoint score distributions overlapped. The analyzer keeps older schemas readable and reports distributions without mutating or recommending production policy.

## State and policy ownership

| Boundary | Owns | Must not own |
|---|---|---|
| `domain/model` | Immutable pose, match, cue, shoot, capture-token, and session values | Android or SDK objects |
| `domain/match` | Coordinate-independent normalization, allowed mirroring, feature extraction, scoring, and independent lock gates | Camera lifecycle, capture, speech, persistence, or sequence advancement |
| `domain/coach` | Deterministic selection of one actionable cue and suppression policy | Free-form generation, audio routing, shoot state, or capture policy |
| `domain/session` | The reducer/state machine and the sole authority to request capture or advance the sequence | CameraX, LiteRT, Room, TTS, Compose, or wall-clock calls |
| `pose/movenet` | Fixed bundled-model loading, direct blocking LiteRT inference, deterministic letterbox geometry, and conversion into immutable 17-point 2D domain observations | Camera scheduling, hidden clocks, match thresholds beyond explicit mapper policy, coaching, capture, or sequence state |
| `camera` | CameraX binding, upright frame conversion, one bounded off-UI inference worker, keep-latest backpressure, per-frame failure containment, rotation/crop transforms, `ImageProxy` closure, still-capture mechanics, serialized journal/file coordination, exact private capture-file effects, and startup cleanup coordination | Lock policy or direct sequence-state mutation |
| `audio` | Text-to-Speech lifecycle, bounded cadence/queue behavior, audio focus, and current-route playback | Bespoke Bluetooth routing or shoot policy |
| `data` | Room entities/DAOs, DataStore preferences, app-private reference/capture assets, and MediaStore export state | Match, coaching, capture eligibility, or sequence-advancement decisions |
| `ui` | Compose rendering, accessibility, input, and display of named states | Score calculation, hidden threshold changes, capture decisions, or sequence advancement |
| Guided camera ViewModel | Translate user and adapter inputs into reducer events, interpret reducer effects through ports, and expose redacted UI state | Invent policy outside reducer transitions or receive private paths/evidence |

Camera frames and MoveNet outputs must become immutable domain observations before decision logic runs. Camera, speech, time, storage, and capture are ports. They are not owners of shoot policy.

## Current Room V4 logical and physical authority boundary

The Task 14B.1A candidate adds `capture_file_operations`, keyed by `(command_token, burst_ordinal)`, with deterministic private final/temp/quarantine relative paths, a closed stage/failure vocabulary, optional byte-count/hash/capture-time evidence, reconciliation state, causal timestamps, a restrictive attempt foreign key, indexes, and callback-installed storage/shape triggers. Fresh registration commits one attempt, rows for ordinals 0–2 in `EXPECTING_RESERVATION`, and one session-counter compare-and-set in a single transaction. Missing, partial, conflicting, malformed, or byte-aliasing authority rejects without partial registration.

`markCaptureAttemptStarted(...)` is deliberately a logical Room transition from `REGISTERED` to `CAPTURING`. `Started` does not grant CameraX access, filesystem access, reservation creation, file writes, rename/sync permission, or any per-file capability. Task 14B.1B adds a separate production Room API that may admit one exact future per-file effect only after the attempt is already `CAPTURING`. Stable `EXPECTING_RESERVATION` rows therefore represent intent only.

Guided bootstrap reads capture-file operations as Packet 2B's ninth authority family in the same Room transaction as shoot, session, poses, attempts, private outputs, receipts, outboxes, and export outputs. `REGISTERED` requires exactly three coherent initial rows; `CAPTURING` requires exactly three coherent allowed rows; `FAILED_CLEANED` requires no journal or immutable children; `RECONCILIATION_REQUIRED` retains exactly three coherent rows and no immutable confirmation children; `CONFIRMED` requires no transient journal rows. It walks attempts in number order, advances the derived pose only for confirmation, counts failed history, and exposes at most one final blocker. Malformed storage classes, values, ownership, keys, paths, clocks, cardinality, or cross-family graphs fail closed through redacted rejection families.

Each Task 14B.1B transition is one Room transaction over an exact opaque token and ordinal, expected stage, and expected row clock. A scalar raw-storage classifier validates the attempt/session/shoot/current-pose graph before the mutation CAS. The first CAS into `WRITING_TEMP`, `FINAL_RENAME_PENDING_SYNC`, `CLEANUP_PENDING_SYNC`, or `QUARANTINE_PENDING_SYNC` is the durable admission point for that specific future effect. Stable stages do not block deletion. Exact target replay is informational and does not grant a second effect.

Confirmation performs exact command-token attempt lookup and token/pose matching. First application requires `CAPTURING`, an active owning graph at the captured deletion generation, and exactly three ordered `FINAL_DURABLE` journal rows with deterministic paths, complete positive byte/hash/capture-time evidence, no reconciliation marker, valid SQLite storage classes, and clocks no later than the requested confirmation. The public API accepts export destinations but no private output list. It derives the three immutable private outputs from journal authority, advances the session, records the unique receipt and export outbox, revalidates the journal, then deletes exactly three rows in one Room transaction. Any failure rolls back every write, including journal deletion. Receipt-backed `AlreadyApplied` uses immutable persisted authority and requires zero residual byte-equivalent journal rows. Confirmation and deletion, and two duplicate confirmations, serialize to one committed winner.

Task 14B.2 failure settlement derives its result from the same Room graph. Three untouched `EXPECTING_RESERVATION` rows or three clean `CLEANED_DURABLE` rows are consumed while the attempt becomes `FAILED_CLEANED` and the session clock advances atomically; retry then creates the next contiguous attempt on the same pose. Three clean `FINAL_DURABLE` rows return ready-to-confirm without mutation. Every other coherent capturing journal is retained while the attempt becomes `RECONCILIATION_REQUIRED`, blocking retry across restart. Replays validate the stored result, and the ordinary per-file API cannot mutate aggregate reconciliation authority. Settlement faults roll back journal, attempt, and session together; duplicate writers serialize to one application and one validated replay.

Task 14B.3 connects those persisted stages to exact app-private effects without invoking CameraX. A write lease binds an empty final reservation and temporary file to stable identities. Temp sync records positive byte-count, SHA-256, and capture time; publication atomically replaces only the owned reservation and syncs the directory. Recovery gets a separate cleanup-only Room journal while the attempt is aggregate reconciliation. It observes and removes only the three persisted paths per ordinal, preserves unrelated siblings, and settles only after all rows become `CLEANED_DURABLE`. Durable quarantine, unexpected positive bytes, symlinks, nonregular files, and unavailable storage remain blocked.

Task 15A composes those boundaries without weakening them. Startup recovery must return settled before bootstrap can yield `Ready`; three already-final files are confirmed before the camera is reconsidered. A manual request comes from `ShootReducer`, then one executor serializes registration, logical start, each Room admission, CameraX callback, file sync/publication, and final Room evidence. The reducer receives durability only after all three rows are `FINAL_DURABLE`, and it receives advancement only after Room returns applied or exact replay. Stop marks the reducer pause intent and defers route exit until accepted work becomes durable/confirmed or cleanly settled. Unexpected authority failures return reconciliation-required instead of stranding the owner.

The V3→V4 migration is additive: it preserves every preexisting authority value and creates the new table empty rather than fabricating intent for migrated attempts. A migrated unfinished attempt can now settle only through Task 14B.3's bounded legacy lane, after the final, temporary, and quarantine paths for all three deterministic identities are proven absent. Any present or ambiguous path remains blocked. Deletion validates raw SQLite storage before typed mapping, requires the same journal cardinality/path/lifecycle coherence as bootstrap, includes stable journal clocks in its complete causal maximum, and leaves stable rows byte-for-byte unchanged. Only the four admitted stages interlock deletion.

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
3. For each output, the capture adapter atomically claims the absent deterministic final identity with an exclusive empty reservation, writes and syncs a same-directory temporary file, verifies that exact reservation by stable filesystem identity, atomically replaces only that owned reservation with the complete temporary bytes, and syncs the directory. The dedicated capture-candidates directory is exclusively owned by this publisher and the future reconciler; all supported in-process mutation uses one process-wide guard so verification and rename/removal are indivisible within that ownership model. Code that bypasses the adapter is outside the supported concurrency contract and must not mutate this directory. A pre-existing final is never replaced. Each final-file publication is atomic; the three-file set is not.
4. An empty reservation is not an authoritative output and may never be accepted by filesystem scanning. Capture is not eligible for confirmation until all three non-empty authoritative private files exist durably. A reservation leftover, ownership mismatch, or deterministic-final collision is reconciliation-required. A failed cleanup retains its prepared-output ownership, reports that a final may exist, blocks conflicting submissions, and exposes explicit serialized retry until cleanup succeeds or remains pending. None of these states permits advancement or blind recapture.
5. One Room transaction is the logical ownership boundary: it confirms the attempt, records all three authoritative private outputs, advances the pose exactly once, marks the unique confirmation/advance receipt applied, and creates a durable MediaStore export outbox entry.
6. If that Room transaction fails, it creates no confirmation, advance, receipt, or outbox. Unconfirmed private files are cleaned or quarantined. If resolution cannot be proven, the attempt enters reconciliation-required and automatic recapture is forbidden.
7. A crash before the Room transaction may leave an empty reservation, a temporary file, or non-empty unconfirmed private files; startup resolves them through deterministic attempt identity plus recorded protocol state before retry and never treats filename presence alone as authority. A crash after the transaction leaves capture and advancement complete and replays only pending export work.
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

Task 11A implements shoot, ordered-pose, session, and these capture authority records:

- `CaptureAttempt`: command token, session/pose ownership, automatic/manual trigger, lifecycle state, reconciliation flag, and timestamps.
- `PrivateCaptureOutput`: command token, ordinal 0–2, deterministic app-private identity/path, durability state, capture metadata, and integrity metadata as needed.
- `CaptureConfirmationReceipt`: a unique command-token marker showing that the Room confirmation/advance transaction has already been applied.
- `CaptureExportOutbox`: exactly one committed outbox per command token.
- `CaptureExportOutput`: composite key `(commandToken, burstOrdinal)`, ordinal constraint 0–2, exact target collection/volume, intended metadata, pending/claimed/create-started/exported/reconciliation-required state, unique claim token, durably recorded exact URI when known, and retry/diagnostic metadata. Exactly three rows must exist before the owning confirmation transaction commits.

Task 11B introduced Room V2 with one logical reference-import intent and one exact file-operation-ledger row per token. Task 12's Room V3 removes duplicated playlist position from immutable import attempts; `shoot_poses` solely owns mutable active order. The ledger records deterministic relative paths, a closed pre/post-effect stage vocabulary, injected timestamps, synced byte-count/hash evidence, and a path-free reconciliation failure code. App-private publication uses exact token-derived paths and no-clobber reservation/temp/final semantics. Startup enumerates retryable ledger rows and resumes only from the persisted stage plus exact evidence; it never scans arbitrary filenames or rereads a provider to invent authority. A validated pose becomes active only after the durable asset and detector/provenance evidence satisfy the final Room transaction.

Room V4 adds `CaptureFileOperation` as durable capture-file intent. New attempts own exactly three initial rows; migrated V3 attempts own none. Task 14B.1B transitions those rows through a closed graph with all-or-none byte-count, SHA-256, and capture-time evidence plus exact reconciliation markers. Task 14B.2 adds terminal retryable `FAILED_CLEANED` attempts and blocking `RECONCILIATION_REQUIRED` attempts, including atomic journal consumption or retention. Room writes do not claim that a corresponding physical operation happened; a later coordinator must admit the effect, run the adapter once only for a fresh `Applied` result, then durably settle the observed outcome. All entity/snapshot/result diagnostics are redacted.

Task 14A.1's `GuidedSessionDao` reads one exact session and its owning shoot, ordered poses, attempts, private outputs, receipts, outbox, and export outputs under one `@Transaction`; Task 14B.1A adds capture-file operations to that same transaction as the ninth authority family. The pure mapper rejects counter gaps, receipt discontinuity, wrong cardinality, malformed state-specific export or journal facts, multiple blocking attempts, and unsupported ownership/lifecycle state. Task 14B.2 extends its attempt walk so failed-cleaned history increments `failedAttemptCount` without advancing pose progress, while a final aggregate reconciliation attempt becomes the sole blocker. `Ready`, `Completed`, and `ReconciliationRequired` expose only immutable opaque state needed by a future coordinator; names, labels, paths, URIs, claim tokens, Room entities, raw exceptions, and image-derived payloads remain internal and redacted. Room 2.8.4 generates this blocking transaction as SQLite `IMMEDIATE`, excluding confirmation/deletion writers until the snapshot completes. Task 14A.2 adds `findActiveGuidedSession(shootId)`: one transactional read of the shoot and all its sessions, mapped purely to `Exact`/`None`/`UnknownShoot` or a typed fail-closed rejection for deleting shoots, orphans, multi-active corruption, unknown lifecycles, or exhausted counters.

Task 14A.3 keeps discovery editor-scoped to the selected shoot instead of issuing one transactional lookup per paginated list row. The projection's `hasResumableSession` value is only a display hint; Resume performs a fresh discovery read and stale or rejected outcomes cannot navigate. Start and Resume mint the same redacted in-memory capability for one constant route. A route-scoped ViewModel retains the bootstrap database through configuration changes, closes after in-flight leases return, and authorizes camera composition only for an exact `Ready` snapshot. Navigation identity is not saved; process recreation starts at the list and requires Room re-discovery after the user reopens the shoot.

Selected references and every confirmed capture are authoritative in app-private storage; live analysis frames are never persisted. Filesystem operations do not claim multi-file atomicity. After all three per-file no-clobber publications are durable, one Room transaction atomically owns logical confirmation, the three private-output records, exactly-once advancement, receipt application, and outbox creation.

The export worker consumes only committed outbox work and uses a durable compare-and-set claim before any create. It stores the returned exact URI before publication and never uses display name or relative path as mutation authority. A claimed/create-started row with no durable URI is reconciliation-required forever unless explicitly resolved; timeout or restart cannot authorize a second create.

Delete-shoot and app-level delete-all use the same atomic deletion-generation barrier. They block new capture/advance and claims, cancel untouched pending work, and retain in-progress authority until exact-URI work settles. Quarantined images remain app-private, are tied to reconciliation metadata, appear as a user-facing unresolved count/state, and remain until explicit resolution or successful app-level deletion. If resolution is unsafe, deletion reports incomplete and retains the minimal tombstone. OS clear-data or uninstall can forcibly remove private quarantine and state, but cannot promise removal of MediaStore rows that were already exported or whose creation outcome was ambiguous; foreign rows are never selected by metadata for cleanup.

All app-private references, captures, Room databases, DataStore preferences, outbox/tombstone metadata, and quarantine are excluded from Android system cloud backup, device-to-device transfer, and supported cross-platform transfer surfaces. The manifest must set `android:allowBackup="false"` and reference both legacy `android:fullBackupContent` and API 31+ `android:dataExtractionRules` fail-closed resources. No custom `BackupAgent` is permitted. Partial restore of capture, Room, or outbox state is forbidden because it can split authority from receipts and violate exactly-once invariants.

See [Privacy](PRIVACY.md) for the complete data contract and [Testing](TESTING.md) for the gates that must prove these boundaries.

## Dependency direction

Domain packages must remain pure Kotlin. They may not import Android, CameraX, LiteRT, Room, Text-to-Speech, Compose, or concrete storage APIs. Adapters depend inward on domain contracts. UI and platform callbacks submit events; they do not mutate session state directly.

This direction is enforced with source-level dependency tests and remains a required gate as camera, model, persistence, audio, and export adapters are added.

## Decision records

- [ADR 0001: Android native first](adr/0001-android-native-first.md)
- [ADR 0002: On-device pose processing](adr/0002-on-device-pose-processing.md)
- [ADR 0003: Persisted reference-import file ledger](adr/0003-persisted-reference-import-file-ledger.md)
- [ADR 0004: Room V3 shoot-preparation authority](adr/0004-room-v3-shoot-preparation-authority.md)
- [ADR 0005: Atomic Room V3 guided-session bootstrap](adr/0005-atomic-room-v3-guided-session-bootstrap.md)
- [ADR 0006: Room V4 capture-file journal foundation](adr/0006-room-v4-capture-file-journal-foundation.md)
- [ADR 0007: Room-owned capture-file admission](adr/0007-room-owned-capture-file-admission.md)
- [ADR 0008: Journal-derived atomic confirmation](adr/0008-journal-derived-atomic-confirmation.md)
- [ADR 0009: Room attempt settlement and restart reconstruction](adr/0009-room-attempt-settlement-and-restart-reconstruction.md)
- [ADR 0010: Journaled private capture files and restart recovery](adr/0010-journaled-private-capture-files-and-restart-recovery.md)
