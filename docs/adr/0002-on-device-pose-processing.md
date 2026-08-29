# ADR 0002: On-Device Pose Processing

- **Status:** Accepted product boundary; direct MoveNet/LiteRT and Task 10 live camera slice implemented and hardware-exercised; full product acceptance pending
- **Date:** 2026-08-27
- **Decision owners:** Pose Guide Snap product and architecture review
- **Reversibility:** Reversible only through a new data and privacy decision

## Context

Reference photos and live camera frames may contain sensitive personal images. The MVP needs pose landmarks and matching during an interactive camera session, including an airplane-mode acceptance case. Accounts, uploads, server retention, network latency, and remote-inference failure would add risk without being necessary to test the core interaction.

A monocular pose detector is still limited when run locally: it cannot perfectly reconstruct depth, occluded joints, hand shape, or viewpoint. Moving inference to a server would not justify stronger product claims.

The repository now bundles an exact MoveNet MultiPose model and implements a direct LiteRT detector plus pure mapper. Authorized Pixel 6 instrumentation has exercised the fixed public reference, live rear-camera inference, and aligned live/reference skeleton rendering. This bounded evidence does not establish reference import, durable capture confirmation/advancement, calibration, or the complete guided workflow.

## Decision

Run pose landmark extraction and pose matching on the Android device for both explicitly selected reference images and live camera observations.

- Use the exact bundled MoveNet MultiPose Lightning float16 v1 model through minimal direct LiteRT `1.4.2` for both references and live observations. The blocking detector receives upright bitmaps; the camera layer must schedule it on one bounded off-UI worker with keep-latest backpressure and per-frame failure containment.
- Copy selected references into app-private storage and keep derived landmarks and model metadata app-private.
- Never persist live camera-analysis frames.
- Keep confirmed captures authoritative in app-private storage and export them afterward through the separate idempotent MediaStore outbox and deletion contract.
- Provide local-only operation after the app and model are installed. Coaching must use an installed TTS voice verified as not requiring a network connection, or fail safely to visual-only mode; the MVP requests no Android `INTERNET` permission.
- Explicitly exclude every sensitive app-private and device-protected domain from Android cloud backup, device-to-device transfer, and supported cross-platform transfer. Partial restore of capture, Room receipt, outbox claim/URI, tombstone, or quarantine state is forbidden; no custom `BackupAgent` may bypass the fail-closed XML rules.
- Do not add accounts, cloud upload, remote inference, or analytics SDKs to the MVP.

MediaPipe Tasks was evaluated and rejected for this boundary: its mandatory Google metrics path conflicts with the no-analytics/no-network contract, while excluding the transport stack breaks detector creation. Direct BlazePose execution was also rejected after a bounded spike required bespoke graph reconstruction and miscounted the one- and two-person controls. The selected MoveNet path preserves exact 1/0/2 person evidence in the denied-network spike but deliberately reduces the observation contract to 17 image-plane keypoints with `z = 0`.

## Rationale

On-device processing removes image upload, account, server-retention, network-latency, and connectivity dependencies from the first product proof. It supports the airplane-mode acceptance case and keeps pose policy testable against a versioned local model.

This decision is a privacy and reliability boundary. It is not a claim that on-device outputs are anonymous, perfectly accurate, or safe to retain without controls.

## Consequences

### Positive

- Reference processing and live guidance do not require image upload or remote inference.
- A guided session can continue without a network connection after installation.
- Inference latency avoids a network round trip and can be measured against the exact APK/device pair.
- There is no MVP account or server-side personal-image retention lifecycle.
- Model version and digest can be tied directly to stored reference landmarks and calibration evidence.

### Negative

- APK size, memory use, battery draw, and thermal load increase because the model runs locally.
- Inference speed and quality depend on device capability; Pixel 6 acceptance does not establish performance on every supported `minSdk 29` device.
- Model updates require an explicit app/model update and may require reference reprocessing or score recalibration.
- Local processing does not remove the need to protect app-private reference/capture photos, landmarks, logs, export outbox state, and MediaStore identifiers.
- App-private authority must remain coherent across export and deletion: one exactly-three-row outbox uses an exclusive durable pre-create claim and exact-URI-only MediaStore mutation; deletion uses a generation barrier and retains visible quarantine/tombstones when safe resolution is impossible.
- OS clear-data or uninstall may remove private authority and quarantine but cannot guarantee removal of already or ambiguously exported MediaStore rows.
- There is no server-side fallback for unsupported devices or difficult inputs.
- The selected 17-point 2D model retains viewpoint, depth, occlusion, hand/foot-detail, and image-plane-angle limitations.

## Alternatives considered

### Remote pose inference

Rejected for MVP because it would upload or transmit sensitive image data, require network and server operations, add latency and failure modes, and conflict with airplane-mode acceptance.

### Hybrid local/remote fallback

Rejected for MVP because a hidden fallback would make the privacy and offline contract conditional. An explicit opt-in hybrid design would still require consent, network-state UX, retention rules, and separate result consistency testing.

### Store only landmarks and discard selected references

Rejected for MVP because the ordered shoot needs durable private reference assets and reproducible reprocessing when model or transform metadata changes. References remain app-private and subject to explicit deletion.

## Reversibility plan

This ADR can be superseded, but remote or hybrid processing is not a configuration toggle. A reversal requires:

1. A new ADR with an explicit data-flow diagram and threat model.
2. User consent and clear UI for what leaves the device and when.
3. Defined server location, encryption, access control, retention, deletion, incident, and subprocessors policies.
4. Offline-degradation behavior that cannot silently change the capture threshold or product claim.
5. Separate calibration showing local and remote results do not create unsafe or misleading lock transitions.
6. Updated privacy documentation, tests, and same-artifact acceptance evidence before distribution.

Changing the client platform under [ADR 0001](0001-android-native-first.md) does not automatically reverse this on-device boundary.

## Related

- [Product contract](../PRODUCT.md)
- [Architecture contract](../ARCHITECTURE.md)
- [Privacy contract](../PRIVACY.md)
- [Testing contract](../TESTING.md)
