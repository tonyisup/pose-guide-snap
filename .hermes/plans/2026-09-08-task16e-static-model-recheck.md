# Task 16E — same-artifact static MoveNet recheck

**Status:** Exact Pixel 6 method passed on the frozen Task 16D APK pair; cleanup completed.

## Objective

Distinguish a current-artifact model/loading/preprocessing/inference/mapping regression from a failure confined to the live-camera input path or scene, without opening the camera or reading private media.

## Frozen artifacts

- Installed debug APK SHA-256: `16843f2b88f11116161ec81ecbf460b691eac97fa4b1123248dbff426852e787`
- Android-test APK SHA-256: `488eb4b615f74479d06824f29c711cbadaf24013778acd35a3cf1fbfdcddac7e`
- Unsigned release APK SHA-256, host evidence only: `c7aae95d26bc295ab42f5dad7688814f4775df95cacf567fe668df7f3bc696ba`

## Exact device scope

1. Verify the installed main package bytes match the frozen debug APK.
2. Install and verify the frozen Android-test APK.
3. Run only `com.tonyisup.poseguidesnap.pose.MoveNetPoseDetectorTest#packagedModelAndFixtureDefineRepeatedOneZeroTwoPersonContract` through the existing AndroidJUnit runner.
4. Uninstall only `com.tonyisup.poseguidesnap.test` and remove temporary installed-APK pulls. Preserve the main app, its data, and unrelated device files.

The method verifies the exact packaged model and licensed public fixture hashes, repeated one-person inference with all 17 COCO identities, a generated black zero-person control, a generated side-by-side two-person control, caller bitmap ownership, and idempotent detector cleanup. It does not request camera permission, open the camera, capture a photo, read user photos or private media, access the network, write a calibration report, mutate Room, or change an application threshold.

## Decision rule

- **Pass:** the same Task 16D model package, static preprocessing, inference, and result mapping remain healthy on the Pixel. Investigate bounded live-camera image statistics and conversion/crop behavior next.
- **Fail:** investigate the exact failing static boundary before collecting more live-camera evidence.

This check cannot establish live-camera accuracy, calibration, sustained performance, or product readiness.

## Result

The separately authorized exact method passed 1/1 in 0.669 seconds. Installed main and Android-test package bytes matched their frozen hashes. The contract verified the packaged model and public fixture hashes, repeated one-person inference with all 17 COCO identities and torso anchors, a generated black zero-person control, a generated side-by-side two-person control, caller bitmap ownership, and idempotent detector cleanup.

The test package was then uninstalled, temporary installed-APK pulls were removed, and final checks found only the main app installed and no active camera client. The main app and its data were preserved. The pass rules out a current-artifact failure in model packaging, static image preprocessing, inference, or result mapping. The remaining investigation should measure the already-transient live-camera bitmap at the CameraX-to-detector boundary without retaining any image.
