# Task 16N alignment guide — 25 September 2026

**Implemented, host-verified, and synthetic-screen-verified on the Pixel 6.** The updated APKs were installed with app data preserved; both native screen tests passed. The subsequent participant camera check collected 96 one-person frames but no evaluable full-body pose; see the [camera result](2026-09-25-task16n-guided-framing-pixel6.md). No camera was opened for these screen checks.

Task 16M improved center similarity but still failed scale and pose criteria. This change adds an explicitly selected guided calibration screen so the participant can adjust against a visible target before another collection.

## Behavior

- A white dashed target box and center cross share the preview with a blue live body box. An arrow points from the live center toward the target in picture coordinates; text does not guess the participant's left/right orientation.
- Center alignment is addressed first. With all 17 public-reference landmark identities confidently visible, the live/reference diagonal comparison determines closer/farther advice. Missing, low-confidence, absent, multiple-person, or degenerate observations clear participant geometry and withhold size advice.
- “Framing aligned” refers only to the independent framing requirement. Pose matching and acquisition thresholds remain unchanged. Automatic capture stays disabled.
- The existing flash starts 15 seconds of settling. Guidance shows remaining seconds, clears stale feedback after 750 ms without an update, and clears participant geometry at measurement start and close. Adjustment messages stop during the 10-second hold.
- The reference image and status sit outside the camera area. The preview retains the public reference's 1024:574 aspect ratio within either screen orientation. Analysis and preview still share the controller's CameraX viewport. Both target and live overlays use the live crop's existing fill-center transform; the reference is not independently fitted over a differently shaped live crop.
- Relative aspect disagreement over 1% with either the reference or visible preview suppresses guidance and prevents the guided collection from proceeding past preflight. Small crop rounding is tolerated. Actual Pixel crop/display agreement remains a device check, not a claim established by host tests.

The original collector used a full portrait preview and a separate landscape reference thumbnail. Source inspection establishes that layout difference; it does not prove it caused prior match failures. This guided method changes crop composition and participant feedback, so its reports use a separate dataset and must not be treated as unchanged-setup repeats.

CameraX's shared viewport contract and transform behavior were checked against the official [configuration documentation](https://developer.android.com/media/camera/camerax/configuration#crop-rect) and [output-transform guidance](https://developer.android.com/media/camera/camerax/transform-output).

## Implementation boundary

`CalibrationAlignmentGuide.kt` lives in `src/debug`, with eight JVM checks in `src/testDebug`. The screen and its two synthetic rendering checks live only in `src/androidTest`. The new `collectOneAuthorizedGuidedFramingSequence` method is opt-in and accepts intentional positive fixtures. Existing collector methods retain their earlier layout. Production match code, policies, model, report schema, release UI, and persistence behavior are unchanged.

Participant bounds exist only in transient display state and are redacted from object string representations. No image, coordinate, body extent, or raw landmark is added to the exported schema or instrumentation summary. The existing bounded scalar report and framing summary remain the only collection outputs.

## Verification

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline` passed. The full JVM suite passed **722/722**, including **8/8** guide checks, with zero failures/errors/skips. Lint reported zero errors and nine warnings. The initial new test fixtures incorrectly used the private `PoseObservation.copy`; they were corrected to use its existing public immutable constructor before the successful run.

Tests cover both size directions, centering priority, missing/low-confidence/degenerate evidence, zero/multiple people, crop mismatch, rounding, portrait/landscape layout geometry, common target/live projection, rear-view horizontal order, and redacted diagnostics. The two native screen tests passed **2/2 in 0.120 seconds** on the wireless Pixel 6. They checked actual View layout at portrait and landscape sizes, synthetic Canvas rendering, and clearing guidance before measurement, without opening an activity or camera. This does not verify live camera crop/overlay alignment. See the [instrumentation output](2026-09-25-task16n-screen-pixel6-output.txt).

| Artifact | Verified local and installed SHA-256 |
|---|---|
| Debug APK | `a6df771af2e7a395c8db4c95643a5f329dc72bb15af8c6738c60c9825ce9b8c2` |
| Android test APK | `00013bc7f01f82b9baf2afb2fe131b9f5f4af87859de5a0e82b335c5b652c295` |

See the [device-check plan](../../.hermes/plans/2026-09-25-task16n-guided-framing.md). Matching calibration, matching negatives with the same viewport, repeated cases, and performance evidence remain pending. These host/synthetic checks remain separate from the subsequent live-camera result.

## Wireless reconnection and cleanup

The first pairing attempts failed before code verification: the existing ADB daemon logged “No route to host,” while a direct connection to the supplied pairing endpoint succeeded. Restarting the idle ADB service resolved the discrepancy; pairing and automatic connection then succeeded. Pixel 6/oriole identity was verified. No pairing code is retained in this record.

Both APKs were installed with `-r` and their installed bytes matched the hashes above. After the synthetic tests, the main app was force-stopped and only the test package was uninstalled. Final checks verified the installed main hash, absent test package, absent calibration export directory, and an empty active camera client list. The app/data and wireless pairing remain available. No participant report, photograph, screenshot, or landmark payload was collected. Source/APKs were unchanged during this checkpoint, so the already-passing host gates were not repeated.
