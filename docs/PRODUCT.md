# Product Contract

> **Project status: Tasks 1–12, 14A.1–14A.3, Task 14B.1B–14B.3, Task 15A manual guided capture, and Tasks 16A–16H are implemented. The last host gate passed 728/728 JVM and 14/14 Python tests with zero lint errors.** Task 16I's unchanged-build bright-light repeat detected one person with all 17 landmarks qualified in 96/96 frames. Task 16J's same-setup empty-scene control reported no person in 97/97 frames. Task 16K evaluated 97/97 intended reference-match frames but never locked; framing is the largest blocker. Task 16L identified center alignment as the lower component, with size also below the existing requirement. Further calibration remains needed; no threshold changed. Automatic capture, MediaStore execution, audio, physical deletion, calibrated matching, and the complete guided workflow remain open. Repository landing is not a product-shipped claim. Task 16M collected 97 fully evaluated frames with the flash cue: centering improved, but scale and pose criteria still prevented a lock. Exact cleanup passed; live alignment feedback is next. See the [Task 16M record](validation/2026-09-17-task16m-recentered-reference-match-pixel6.md).

## Product goal

Build an Android-first, privacy-preserving guided selfie app that imports an ordered sequence of reference-pose photos, coaches one person through that sequence over normal Android audio routing, and captures and advances automatically only after a stable, high-confidence pose match.

## MVP user journey

1. The user creates a named shoot.
2. The user imports 3–20 reference photos and arranges them in order. Automatic horizontal mirror matching is enabled by default, and the user may opt out for an individual pose.
3. The app validates each image locally and rejects references that do not contain exactly one sufficiently visible person.
4. The user mounts the phone, selects rear-camera capture, optionally connects earbuds, and starts the shoot.
5. The app announces framing corrections first, then the single highest-value pose correction.
6. When coverage, framing, similarity, and stability gates all pass for the configured dwell period, the app announces capture and writes exactly three authoritative photos to app-private storage. One Room transaction confirms those durable outputs, advances exactly once, records the applied receipt, and queues export.
7. The process continues until the sequence completes or the user pauses or stops it. Manual capture remains reducer-owned and uses the same three-photo confirmation pipeline, bypassing only the pose-match/lock gate.
8. An idempotent worker exports confirmed captures to MediaStore afterward. Shoot definitions, references, landmarks, authoritative captures, and capture state remain app-private; MediaStore export does not own advancement.

## MVP scope

- Native Android application.
- Provisional application ID `com.tonyisup.poseguidesnap`.
- `minSdk 29`.
- Pixel 6 running Android 16 as the first real-device acceptance target.
- One person in static full-body or three-quarter-body poses.
- Local reference-image import through Android's system picker.
- Ordered pose playlists of 3–20 references.
- Rear-camera capture first.
- On-device landmark extraction for both reference images and live camera observations.
- Automatic horizontal mirror matching by default, with a per-pose opt-out.
- Visual overlay, named match gates, an overall score, and one concise spoken correction at a time.
- Spoken output through Android's current media route, including connected earbuds; no bespoke Bluetooth routing. The MVP may use only an installed TTS voice verified as not requiring a network connection and falls back to visual-only coaching otherwise.
- Stable-match auto-capture with independent coverage, framing, similarity, and stability gates, plus hysteresis, cooldown, idempotency, and a reducer-owned manual trigger that bypasses only those lock gates.
- One unified three-photo protocol for automatic and manual capture.
- App-private reference copies and authoritative app-private captures, followed by idempotent MediaStore export with separate deletion behavior.
- Local-only operation after the app and pose model are installed. Pose processing and coaching do not require a network connection, the MVP requests no Android `INTERNET` permission, and all sensitive app-private domains are explicitly excluded from Android cloud backup, device transfer, and supported cross-platform transfer.
- No account and no cloud upload.

## Explicit MVP non-goals

- iOS or a cross-platform UI.
- Couples, groups, children-specific flows, or multiple simultaneous bodies.
- Dynamic or action-pose sequences.
- Facial-expression scoring, attractiveness scoring, body-shape judgment, or beauty filters.
- Generative pose suggestions.
- Background, outfit, lighting-quality, or scene-aesthetic scoring.
- Automatic correction for arbitrary camera azimuth or severe perspective differences.
- Remote model inference, analytics SDKs, ads, subscriptions, or app-store publication.
- A claim that a high skeleton-match score guarantees a good photograph.

## Truth in claims

A monocular 2D/weak-3D detector cannot perfectly reconstruct depth, occluded joints, hand shape, or viewpoint. Product copy and UI must say **“pose match”**, not **“perfect pose.”**

References captured from a substantially different viewpoint than the live camera may be impossible to match reliably. Import validation and the live UI must explain that limitation rather than silently lowering thresholds. A high match score describes the supported skeleton features and gates only; it does not guarantee composition, lighting, expression, aesthetics, or photographic quality.

Production thresholds cannot be accepted because they merely look reasonable. They remain development defaults until deterministic fixtures and authorized real-device calibration produce a documented separation report for positive and negative examples. No publication or store claim may be made before the real-device acceptance gate passes.

## Current user-visible and shipping boundary

Task 15A composes the existing reducer, Room, file, recovery, and CameraX boundaries for a manual three-photo request. It loads the selected validated reference, renders its landmarks and uncalibrated evidence, advances only after journal-derived Room confirmation, and defers Stop while accepted work settles. A failed attempt becomes retryable only after exact cleanup is durable; uncertain bytes, unsafe paths, or quarantine remain blocking across restart.

The candidate remains pre-release and does not complete Gate 2. The corrected Task 15A artifact passed one bounded real rear-camera three-photo capture through Room confirmation and one advance on the Pixel 6. Full production-UI traversal, interruption recovery on hardware, automatic matching calibration, export, audio, and the complete user journey still require separate evidence.

## Approved product policies

### Matching

The matcher will reject zero-person and multiple-person observations, transform observations into the displayed and captured coordinate system, filter low-confidence landmarks, evaluate allowed mirrored and non-mirrored candidates, normalize translation and scale, and retain independent coverage, framing, angular-similarity, and positional-similarity results. An aggregate score cannot hide a failed required gate.

### Coaching

Framing and coverage corrections take priority over limb corrections. The app will emit one concise, high-confidence semantic cue at a time, require persistence before speaking, suppress repeats, and use relative language. It will not speak numeric match scores during normal shooting or judge appearance or body shape.

### Capture

Capture is intentionally conservative: a missed capture is recoverable, while an unintended capture and sequence advance damages trust. Only the session reducer may request automatic or manual capture or authorize advancement. A manual request bypasses only the pose-match/lock gate; it does not bypass ownership, durability, cleanup, or exactly-once rules.

One command token identifies exactly three outputs with deterministic `(commandToken, burstOrdinal 0..2)` identities. Each image is written to a same-directory temporary file, synced as appropriate, and atomically published without clobbering an existing final app-private file. Android publication first claims the absent final identity with an exclusive empty reservation, verifies that exact reservation before atomically replacing it with complete temporary bytes, and syncs the directory. An empty reservation, existing deterministic final, or ownership mismatch requires reconciliation and forbids blind recapture. Failed cleanup retains ownership and blocks another command until explicit serialized retry resolves or remains pending. A close arriving after the third file publishes does not downgrade a complete three-output result. Atomicity applies to each file, not to the three-file set. The attempt is eligible for confirmation only after all three private files exist durably. One Room transaction then confirms the attempt, records all three authoritative private outputs, advances the pose exactly once, marks an idempotent confirmation/advance receipt applied, and creates a durable MediaStore export outbox entry. That transaction—not MediaStore—is the session ownership boundary.

The transaction creates one outbox with exactly three output rows, enforced by composite `(commandToken, burstOrdinal)` uniqueness, ordinal 0–2, and a transaction-time cardinality check. Export runs afterward. A Room compare-and-set grants one unique claim token the exclusive right to call `MediaStore.insert()` for an output. Claimed/create-started work without a durably persisted exact URI is never reset or automatically created again; it becomes reconciliation-required. The worker stores the exact returned URI before publication and uses only that URI for automatic update/delete. Target collection/volume, display name, relative path, and intended metadata are diagnostics, never standalone mutation authority; ambiguous outcomes preserve foreign rows.

Delete-shoot atomically marks the shoot deleting, advances a deletion generation, blocks capture/advance and new export claims, and cancels untouched pending work. Workers recheck the barrier before create and publication. In-progress exact-URI work is resolved or awaited; ambiguity remains reconciliation-required with a visible unresolved count, app-private quarantine, and minimal tombstone. Delete-shoot and delete-all report incomplete rather than success until safe app-level resolution. Clear-data/uninstall can remove private quarantine/state but cannot promise removal of already or ambiguously exported MediaStore rows.

## Deferred features

Deferral is not a promise that a feature will ship. Each item needs a new product decision after the listed condition is met.

| Feature | MVP disposition | Revisit condition |
|---|---|---|
| Front-camera capture | Deferred | Rear-camera loop passes the full Pixel 6 real-device acceptance gate and mirror/coordinate semantics are revalidated |
| iOS client or cross-platform UI | Deferred with no schedule | Android interaction proves useful and camera/audio trade-offs are reviewed in a new ADR |
| Couples, groups, or multiple simultaneous bodies | Deferred with no schedule | A separate multi-person interaction and safety contract is approved |
| Dynamic or action-pose sequences | Deferred with no schedule | Temporal matching, motion blur, and capture-safety behavior have dedicated evidence |
| Arbitrary camera-azimuth or severe perspective correction | Deferred research | Calibration demonstrates reliable viewpoint handling without misleading lock behavior |
| Generative pose suggestions | Outside MVP; no roadmap commitment | Requires a separate product, model, safety, and privacy decision |
| Accounts, cloud sync, or remote inference | Outside MVP; no roadmap commitment | Requires a new ADR, consent model, data-retention policy, and threat review |
| Analytics, ads, or subscriptions | Outside MVP; no roadmap commitment | Requires explicit product and privacy approval |
| App-store publication | Deferred | A separate distribution plan follows successful quality, privacy, and real-device acceptance gates |

## Related documents

- [Architecture](ARCHITECTURE.md)
- [Testing](TESTING.md)
- [Privacy](PRIVACY.md)
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
- [ADR 0011: Journaled manual guided capture](adr/0011-journaled-manual-guided-capture.md)
- [Task 14A.1 validation](validation/2026-09-02-task14a1-atomic-room-v3-bootstrap-pixel6.md)
- [Task 14A.2 validation](validation/2026-09-02-task14a2-active-session-discovery-pixel6.md)
- [Task 14A.3 validation](validation/2026-09-02-task14a3-stale-safe-resume-pixel6.md)
- [Task 14B.2 validation](validation/2026-09-04-task14b2-attempt-settlement-pixel6.md)
