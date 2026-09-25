# Task 16N — guided framing

**Status:** Guide implemented; 722/722 JVM checks and build/lint pass. Wireless pairing/connection recovered after restarting the idle ADB server. Both installed APK hashes matched and the two native synthetic screen checks passed 2/2 in 0.120 seconds. The main app/data remain; test package removed, app stopped, no export or camera client. The participant run then completed 1/1 in 26.843 seconds: all 96 frames detected one person, but only 8–10 landmarks/two torso anchors qualified; no pose/framing evaluation or lock occurred. Cleanup passed. See the [camera result](../../docs/validation/2026-09-25-task16n-guided-framing-pixel6.md). Confirm guide readability and full-body preview visibility, and strengthen full-body readiness handling before another physical repeat. See the [implementation record](../../docs/validation/2026-09-25-task16n-alignment-guide.md).

**Superseded participant protocol:** The user cannot see the rear-camera screen. Use the [spoken Task 16O plan](2026-09-25-task16o-spoken-guided-framing.md) for the next camera run; its audio check passed and the participant confirmed clear audibility. Do not repeat this visual-only protocol.

## Reconnect and check the screen

The Pixel is paired and connected; first check `adb devices -l`. Ask for the current Wireless debugging connection address only if discovery fails. Existing pairing may suffice; request a fresh pairing address/code only if reconnection requires it. Verify Pixel 6/oriole identity. Preserve the main app and its data when installing the new debug/test APK pair; verify local and installed SHA-256:

- Debug: `a6df771af2e7a395c8db4c95643a5f329dc72bb15af8c6738c60c9825ce9b8c2`.
- Android test: `00013bc7f01f82b9baf2afb2fe131b9f5f4af87859de5a0e82b335c5b652c295`.

`com.tonyisup.poseguidesnap.calibration.CalibrationGuideScreenTest` passed 2/2 on this exact pair. These synthetic tests use no activity or camera and export no bitmap. Do not repeat them for unchanged artifacts; reinstall/verify the test package when the participant is ready.

## Completed participant check (do not reuse sequence ID)

Use the better-lit setup and rear camera. Explain the white target/blue live outline, centering arrow, and size cues. Keep the phone still after framing; the landscape-shaped preview is inside the phone screen, so rotating the phone is not required. The flash starts 15 seconds to settle, followed by 10 seconds holding the reference pose. A reconnect message alone is not a confirmation that the participant is positioned.

- Method: `com.tonyisup.poseguidesnap.calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedGuidedFramingSequence`.
- Authorization: `user-authorized-derived`.
- Dataset: `pixel6-match-guided-a` (new viewport/feedback protocol).
- Sequence: `positive-guided-framing-bright-a`.
- Fixture: `positive`; case: `centered-match`.
- Warm-up: `15000`; duration: `10000`.
- Retain only the scalar report and framing summary under unused host filenames; strict analysis outputs `pixel6-match-guided-positive-a-analysis.json` and `.md`.

Check absent device export directory and unused host report paths before launch. Preserve existing reports and never infer a movement direction from the earlier symmetric scale summaries. The new guide uses transient live bounds to supply direction. Instrumentation success alone is not a match pass.

After report-hash verification, delete only the exact report/temp files and their empty export directory, force-stop the main app, uninstall only the test package, and verify the main app hash/data preserved and camera client list empty. Do not pull screenshots/private images/landmarks. If the device remains unavailable, leave the built artifacts ready without opening a camera.

Matching negatives must use the same new viewport in a subsequent explicit collection method; the old portrait negative protocol is not a paired control. No threshold relaxation or automatic-capture enablement follows from this run.
