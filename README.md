# Pose Guide Snap

> **Project status: planning/bootstrap; no working app yet.**
>
> This repository currently contains the approved product and architecture contract only. There is no Android project, installable APK, camera preview, pose detection, spoken coaching, or capture workflow.

Pose Guide Snap is a planned Android-first guided selfie app. The intended MVP will let one person arrange a sequence of reference poses, receive concise framing and pose guidance, automatically trigger a three-photo capture only after a stable, high-confidence pose match, or request the same three-photo protocol manually. Pose processing is planned to stay on the device.

## Approved MVP boundary

- Native Android first, with provisional application ID `com.tonyisup.poseguidesnap` and `minSdk 29`.
- Pixel 6 running Android 16 is the first real-device acceptance target.
- Rear camera first; front-camera support is deferred until the rear-camera loop passes its acceptance gate.
- One person in static full-body or three-quarter-body poses.
- On-device reference and live pose processing; no account, cloud upload, or remote inference.
- Sensitive app-private files, Room/DataStore state, and quarantine metadata are excluded from Android cloud backup, device-to-device transfer, and supported cross-platform transfer surfaces; no partial restore is allowed.
- Automatic horizontal mirror matching by default, with a per-pose opt-out.
- A conservative three-photo capture after stable lock, with exactly three authoritative app-private outputs and exactly-once sequence advancement in the same Room confirmation transaction.
- Manual capture uses that same reducer-owned three-photo protocol; it bypasses only the pose-match/lock gate.
- Confirmed private captures are exported afterward by one committed, exactly-three-row MediaStore outbox. Exclusive durable claims and exact persisted MediaStore URIs prevent duplicate creation and foreign-row mutation; export never owns session advancement.
- Delete-shoot and delete-all establish a Room deletion barrier before touching files or export state. In-progress or ambiguous work remains as a visible reconciliation/quarantine tombstone until it can be resolved safely; deletion never reports success early.

These are product and architecture commitments, not claims of implemented behavior. In particular, a high skeleton-similarity score will not be presented as a guarantee of photographic quality. The product language is **“pose match,” not “perfect pose.”**

## Documentation

| Document | Purpose |
|---|---|
| [Product contract](docs/PRODUCT.md) | MVP journey, scope, non-goals, truth in claims, and deferred features |
| [Architecture](docs/ARCHITECTURE.md) | Planned component boundaries, state ownership, matching, coaching, and capture invariants |
| [Testing](docs/TESTING.md) | Planned test layers, quality gates, and Pixel 6 acceptance matrix |
| [Privacy](docs/PRIVACY.md) | Planned local data lifecycle, retention, logging, and deletion behavior |
| [ADR 0001: Android native first](docs/adr/0001-android-native-first.md) | Reversible platform decision and consequences |
| [ADR 0002: On-device pose processing](docs/adr/0002-on-device-pose-processing.md) | Reversible inference-location decision and consequences |
| [Approved implementation plan](.hermes/plans/2026-08-27_111939-pose-guide-snap-android-mvp.md) | Sequenced implementation tasks and gates |

Runtime setup, build, install, and usage instructions will be added only after those workflows exist and have been verified.
