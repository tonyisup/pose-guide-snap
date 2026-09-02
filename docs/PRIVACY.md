# Privacy and Local Data Contract

> **Project status: Tasks 1–12 are committed, host-reviewed, and boundedly Pixel-exercised.** The app requests `android.permission.CAMERA` plus AndroidX's app-signature permission `com.tonyisup.poseguidesnap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; it requests no `INTERNET`, storage, location, audio, or foreground-service permission and includes no network/cloud/analytics library. Task 12 adds Room V3 shoot preparation and the user-facing system Photo Picker workflow while retaining provider URIs only as immediate callback values. Capture-filesystem/Room coordination, MediaStore I/O, TTS, deletion UI, and the complete guided lifecycle remain unimplemented.

## Privacy boundary

The MVP is local-only after the app and pose model are installed:

- Reference-image and live-camera pose processing occurs on the device.
- There is no account, cloud upload, remote model inference, analytics SDK, advertising SDK, or subscription service.
- The app uses Android's system photo picker for explicit reference selection.
- Spoken guidance follows Android's current media route. The app will select only an installed TTS voice verified as not requiring a network connection, request no Android `INTERNET` permission, and fall back to visual-only coaching if an offline voice is unavailable. It will not implement bespoke Bluetooth routing.
- The absence of `INTERNET` permission is not treated as a backup control. The app explicitly opts every sensitive app-private domain out of Android cloud backup, device-to-device transfer, and supported cross-platform transfer surfaces.
- App-store publication is not part of the MVP and requires a separate distribution decision.

“On device” is an implementation boundary, not a claim that images are harmless or anonymous. Reference photos and captures may be sensitive personal data and must be handled accordingly.

## Data lifecycle contract

| Data | Source | Planned storage | Retention and deletion |
|---|---|---|---|
| Reference photos | Images explicitly selected through the system picker | Copied into app-private storage; the app will not depend indefinitely on an external provider URI | Removed when the owning shoot is deleted or when all local app data is deleted |
| Derived reference landmarks | On-device pose extraction from a selected reference | App-private structured storage with detector/model version and coordinate-transform metadata | Removed with the owning shoot or all local app data |
| Shoot and pose definitions | User-entered names, ordering, labels, mirror opt-out, and validation state | App-private Room database | Removed by delete-shoot or delete-all-local-data operations |
| Session state | Local guided-shoot progress and timestamps | App-private Room database only as needed for safe recovery | Removed with the shoot or all local app data according to the implemented retention contract |
| Live camera-analysis frames | Rear-camera analysis stream | Memory only; never intentionally persisted | Discarded after processing |
| Live pose observations | On-device extraction from analysis frames | Ephemeral for matching and state transitions; raw landmark arrays are not a release-log surface | Discarded when no longer needed for current processing; any derived session summary must be explicitly documented |
| Authoritative capture photos | Automatic or manual reducer command | Exactly three app-private files with deterministic `(commandToken, burstOrdinal 0..2)` identities; excluded from system backup/transfer | Removed only after the deletion barrier safely resolves export work; clear-data/uninstall can remove them |
| Exported capture photos | Post-confirmation MediaStore export worker | Android MediaStore; the exact returned URI is durably persisted before publication | Remain user-visible until the user deletes them through an appropriate media flow; shoot deletion, clear-data, and uninstall cannot promise removal |
| Capture attempt, private-output, and receipt records | Command token, trigger type, session/pose ownership, private output ordinal/path/durability, confirmation/advance receipt, score summary, state, and timestamps | Backup/transfer-excluded app-private Room database; the receipt is unique per command token | Removed only after the deletion barrier resolves in-progress work safely; reconciliation-required metadata and a minimal tombstone remain until explicitly resolved |
| Export outbox and per-output export state | One outbox, exactly three composite-keyed output rows, target collection/volume, intended metadata, claim token/state, exact URI when durably known, and retry/ambiguity metadata | Backup/transfer-excluded app-private Room database | Untouched pending rows may be cancelled behind the deletion barrier; in-progress or ambiguous rows and minimal tombstones remain until safe explicit resolution |
| Quarantined captures and reconciliation tombstones | Uncertain private-file, MediaStore-create, publication, or deletion outcomes | Backup/transfer-excluded app-private files and Room metadata | User-visible unresolved count/state; retained until explicit resolution or successful app-level delete using the same barrier |
| Preferences | Voice enablement, speech cadence, dwell duration, and bounded match settings | App-private DataStore | Removed by delete-all-local-app-data or Android's clear-data/uninstall behavior, subject to platform behavior |

Automatic and manual capture use one protocol. One command token identifies exactly three app-private outputs with `(commandToken, burstOrdinal)` identities for ordinals 0–2. For each output, the app exclusively creates an empty deterministic final reservation, writes and syncs a same-directory temporary file, verifies the reservation's stable filesystem identity, atomically replaces only that owned reservation with complete bytes, and syncs the directory. The capture-candidates directory is exclusively owned by the publisher and future reconciler; every supported in-process mutation shares one process-wide guard. A pre-existing final is never clobbered. An empty reservation is non-authoritative and any crash leftover or ownership mismatch is reconciliation-required; filename presence alone never permits confirmation or recapture. Code that bypasses this ownership boundary is unsupported and must not mutate the directory. Only each publication is atomic; the three-file set is not. The attempt cannot be confirmed until all three non-empty private files exist durably.

Task 11A implements the Room transaction that confirms an already-durable capture attempt, records all three authoritative private outputs, advances the pose exactly once, marks the unique confirmation/advance receipt applied, and creates one durable MediaStore export outbox with exactly three output rows. The output table enforces composite identity `(commandToken, burstOrdinal)`, ordinal 0–2, and exactly-three cardinality is verified before commit. Task 14 still owns capture-file coordination, cleanup, quarantine, and startup reconciliation around this transaction.

Task 11B implements the separate reference-import path. An explicit picker result is streamed into a deterministic app-private reservation/temp/final protocol without persisting the provider URI. Room records the logical intent and a closed file-operation stage before and after irreversible effects. Synced byte-count/hash evidence governs cleanup or quarantine, startup retries only exact token-derived paths, and an active validated pose is committed only after durable local MoveNet analysis. Rejection, failure, or restart cannot expose a partial active pose or authorize broad directory scanning.

Task 12 exposes that import path through redacted shoot-list/editor projections. Compose and lifecycle owners retain only opaque operation correlation; the provider URI remains local to the immediate Activity Result callback and is passed directly to the import handler. Room V3 solely owns active playlist order and durable session start, and the camera permission boundary remains unreachable until start succeeds.

Task 11A implements the targeted Room compare-and-set from `pending` to `claimed`; only its fresh winner carries external-create authority. Persisted `claimed` or later-stage replay is informational and requires reconciliation rather than authorizing another create. Neither Task 11A nor Task 11B performs `MediaStore.insert()`, update, or delete. Task 14 must consume this authority, persist the exact returned URI before later fallible work, and use only that URI for automatic MediaStore mutation.

Android can otherwise back up app-private files and databases independently of app network permission.[1] The application therefore sets `android:allowBackup="false"`, references legacy `android:fullBackupContent="@xml/backup_rules"` and API 31+ `android:dataExtractionRules="@xml/data_extraction_rules"`, and excludes `root`, `file`, `database`, `sharedpref`, `external`, `device_root`, `device_file`, `device_database`, and `device_sharedpref` in every applicable cloud, device-transfer, and compile-SDK-37 cross-platform-transfer section. There is no custom `BackupAgent`. Partial restore of capture files, Room receipts, outbox claims, or quarantine state is forbidden because split restoration can violate authority, exact identity, and receipt invariants.

## Storage rules

1. Copy a reference only after explicit user selection.
2. Validate and extract the reference on device.
3. Treat reference import as transactional: failed copy or validation must not leave an active pose record or orphaned private asset.
4. Store detector/model version and coordinate-transform metadata with derived reference landmarks so changes can be detected rather than silently reinterpreted.
5. Never persist camera-analysis frames.
6. Route both automatic and manual capture through the reducer-owned, exactly-three-output private capture protocol. Manual capture bypasses only pose-match/lock eligibility.
7. Publish each final private output atomically and without clobber after a same-directory temporary write and appropriate sync; do not claim the three-file set is filesystem-atomic.
8. Keep quarantined images app-private and backup/transfer-excluded, bind them to reconciliation metadata, show a user-facing unresolved count/state, and retain them until explicit resolution or successful app-level deletion.
9. Reconcile every nonterminal attempt on startup before allowing it to retry. A committed attempt replays only pending MediaStore export; it never recaptures or advances again.
10. Treat MediaStore export as post-confirmation outbox work with one exclusive durable pre-create claim and exact-URI-only mutation authority. Never use display name/relative path lookup or lease expiry to issue another create.
11. Delete through an atomic Room barrier: mark the shoot deleting, advance its deletion generation, block capture/advance and new exporter claims, cancel untouched pending work, and require workers to recheck the barrier before create and publication.
12. Do not remove authority while a claim/create/publish is active. Resolve or wait for exact-URI work; if safety cannot be proven, retain a minimal tombstone/quarantine, report incomplete/reconciliation-required, and preserve foreign MediaStore rows.

## Logging and diagnostics

Release builds must never log:

- Raw image bytes or camera frames.
- App-private reference paths.
- Raw landmark arrays.
- MediaStore URIs.
- User-entered shoot or pose labels when those values could identify private content.

Debug diagnostics may expose named gate status, score components, selected cue, session state, inference latency, and a non-sensitive capture-token suffix on screen. They must not turn private image or landmark data into logs or committed test artifacts.

## User controls

The MVP must provide:

- **Delete shoot:** establishes the atomic deletion-generation barrier, blocks capture/advance and new claims, cancels untouched pending work, and resolves or waits for in-progress exact-URI work. It reports success only after safe app-level removal; otherwise it exposes incomplete/reconciliation-required and retains the minimal tombstone and quarantine. Already exported MediaStore captures are not silently deleted.
- **Delete all local app data:** uses the same barrier across all shoots and preserves unresolved tombstones/quarantine until explicit resolution or successful app-level removal. Android clear-data or uninstall may forcibly remove this private authority, but cannot promise deletion of already or ambiguously exported MediaStore rows; the UI must explain that limitation.
- **Pause/stop:** stops active guidance, cancels pending speech and capture countdowns, and releases camera-analysis resources as appropriate.
- **Permission recovery:** camera or picker denial must produce a recoverable state rather than partial data or a misleading active-session claim.

Exact UI wording and Android-version-specific deletion behavior must be verified before distribution documentation is written.

## Model and network changes

The approved inference design is recorded in [ADR 0002](adr/0002-on-device-pose-processing.md). A future proposal for remote inference, cloud sync, accounts, analytics, or automatic model download is outside this contract. It requires a new ADR plus explicit consent, data-flow, retention, security, offline-degradation, and deletion design before implementation.

## Acceptance evidence

Privacy acceptance on the Pixel 6 must use the same APK digest as functional and audio-route acceptance. It must inspect source and merged manifests and then, using pinned Build Tools against that exact hashed APK, inspect the packaged manifest plus both packaged compiled backup-rule resources. The packaged artifact must retain both rule references, contain no `INTERNET` permission, and carry every nine-domain exclusion in each applicable rule section; source or intermediate checks are not a substitute. Acceptance must also exercise authorized platform backup/restore behavior where tooling permits; inspect app-private storage, Room/outbox state, MediaStore, and logs after reference import, live analysis, capture, claim/create/publication crash seams, export reconciliation, deletion-worker races, quarantine, clear-data, and uninstall flows; and prove a foreign MediaStore row is preserved. The result must clearly separate observed evidence from planned behavior and must not promise deletion Android cannot perform.

See [Testing](TESTING.md) for the complete acceptance gate.

## Source

[1] [Android Developers: Back up user data with Auto Backup](https://developer.android.com/identity/data/autobackup)
