# Task 16E static MoveNet recheck — Pixel 6 — 8 September 2026

Result: **The exact same-artifact static MoveNet contract passed 1/1 in 0.669 seconds.** This rules out a current-artifact failure in model packaging, static image preprocessing, inference, or result mapping. It does not establish that the live-camera bitmap contains usable scene data.

## Scope and artifacts

The user separately authorized installed-byte verification, installation of the frozen Android-test APK, one exact instrumentation method, test-package removal, and deletion of temporary installed-APK pulls. The method used only the instrumentation APK's packaged licensed public fixture, a generated black bitmap, and a generated side-by-side two-person bitmap. It did not request camera permission, open the camera, capture or read a private image, access MediaStore, mutate Room, use audio, access the network, or install the release APK.

| Artifact | Local and installed SHA-256 |
|---|---|
| Debug app | `16843f2b88f11116161ec81ecbf460b691eac97fa4b1123248dbff426852e787` |
| Android test | `488eb4b615f74479d06824f29c711cbadaf24013778acd35a3cf1fbfdcddac7e` |
| Unsigned release — host evidence only | `c7aae95d26bc295ab42f5dad7688814f4775df95cacf567fe668df7f3bc696ba` |

The Pixel 6 (`oriole`) was connected and awake. The installed main APK matched the frozen debug bytes before the test package was installed. The installed test APK then matched the frozen Android-test bytes.

## Exact method and result

Only this method ran through `com.tonyisup.poseguidesnap.test/androidx.test.runner.AndroidJUnitRunner`:

`com.tonyisup.poseguidesnap.pose.MoveNetPoseDetectorTest#packagedModelAndFixtureDefineRepeatedOneZeroTwoPersonContract`

The runner reported `OK (1 test)` in 0.669 seconds. The method verified:

- the exact packaged model size and SHA-256;
- the exact packaged public fixture dimensions, size, and SHA-256;
- repeated one-person inference with all 17 mapped COCO landmark identities and all torso anchors;
- zero-person behavior for a generated black control;
- two-person behavior for a generated side-by-side composition;
- caller bitmap ownership and idempotent detector cleanup.

## Decision and cleanup

The Task 16D live-camera run analyzed 146 frames at about 9.7 frames per second but observed a maximum raw person score of `0.0`. This static pass shows that the same APK's model package, bitmap letterboxing/RGB packing, LiteRT invocation, and mapper work on known pixels. The next bounded diagnostic belongs at the CameraX `ImageProxy` conversion/crop boundary and should retain only aggregate image statistics.

After the method passed, only `com.tonyisup.poseguidesnap.test` was uninstalled. Temporary main/test APK pulls were removed. Final checks found only `com.tonyisup.poseguidesnap` installed and no active camera clients. The main app, its data, and unrelated device files remained untouched.
