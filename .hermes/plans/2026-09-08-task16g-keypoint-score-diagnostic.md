# Task 16G — bounded raw keypoint-score diagnostic

**Status:** Complete. The final corrected exact repeat found positive but sub-threshold person and keypoint evidence across 145 live frames, failed closed before report creation, and completed exact cleanup. No threshold changed.

## Objective

Determine whether MoveNet produces any partial/keypoint signal for the visually varied live-camera bitmap after Task 16F found a maximum person-instance score of `0.0`.

## Implementation boundary

1. Reduce all 102 raw keypoint-score values across six slots and 17 identities immediately to one maximum finite scalar in `[0, 1]`.
2. Do not retain or expose the winning person slot, keypoint identity, coordinate, tensor, pixel, image, timestamp, path, URI, or identifier.
3. Carry only the nullable maximum beside the existing analyzed frame.
4. Retain only the maximum across the bounded preflight and include it only in a failed assertion.
5. Keep the report schema, model input, person threshold, matching policy, and automatic-capture disablement unchanged.

## Decision rule

- A zero maximum keypoint score indicates no raw pose signal despite visually varied pixels.
- A positive keypoint maximum beside a zero person-instance maximum indicates partial evidence that the instance gate does not accept.

Either outcome remains diagnostic evidence only and cannot justify a threshold change or automatic capture. A private-device run requires fresh authorization after the final host gate and APK hashes are frozen.

## Host verification

- 713/713 JVM tests pass with zero failures, errors, or skips.
- 12/12 Python analyzer tests pass.
- Lint reports zero errors and 9 warnings.
- Debug, unsigned release, and Android-test APK assembly pass.
- Room V1–V4 schema hashes and packaged permissions are unchanged.
- Corrected frozen SHA-256 values: debug `28c3820a2780fb744c37d056b4742eb71eab9249c9947deb411c4089c6e57dcd`; Android test `fc2aa5f30b4aaea2a4a3218490d6c0526b4faaf7ef3a4d0a2387dffc17a177c7`; unsigned release `c6935eb3a14221a8c7655665595da0f47fc17c595bf7e6c6749d125b9b86010a`.
- A test-only follow-up reduces runtime failure evidence to a bounded stage and at most four exception class names. Its fresh host gate passes 713/713 JVM and 12/12 Python tests with zero lint errors and 14 warnings, unchanged Room schemas, and unchanged packaged permissions. Main and release hashes are unchanged; the current Android test hash is `d69892e64e8f9041f098ea3c249918c37037d54ba72ab916777a0fd02c2e4808`.

## First candidate device result

The separately authorized `positive-centered-diagnostic-f` invocation verified first-candidate debug `0c5928c9edcdc6a7cf7276b3667540f1ada3500320c42238bb499e10c429b27f` and Android test `fc2aa5f30b4aaea2a4a3218490d6c0526b4faaf7ef3a4d0a2387dffc17a177c7`, then failed safely before recording after 16.557 seconds. It again analyzed 146/146 no-person frames with varied luminance and `maximumValidPersonScore=0.0`, but reported `maximumValidKeypointScore=1.0`.

Review of the reducer showed that `2 until 55 step 3` includes index 53. The 17-keypoint block ends at index 50; index 53 is the third bounding-box coordinate. The reported `1.0` is therefore invalid as keypoint evidence. No report was created, and exact cleanup removed the empty export directory, test package, and temporary pulls, stopped the camera, and preserved main-app data.

The correction bounds score traversal to indices `2..50` and adds a regression with every bounding-box value set to `1.0`, proving those four fields cannot influence the maximum keypoint score. The first candidate must not be used for the Task 16G decision rule.

## Corrected candidate startup result

The separately authorized `positive-centered-diagnostic-g` repeat verified the corrected debug and original test APK bytes, camera permission, and the exact collector method. It failed 1/1 after 0.623 seconds, before warm-up or report creation, with the generic camera-or-analysis assertion. Camera-service state showed that the rear camera opened and disconnected, but the harness discarded the triggering exception. Exact cleanup removed the export directory, test package, and temporary pulls, stopped the app, preserved the corrected main app and data, and left no active camera client.

The test-only follow-up records only a bounded stage (`preview`, `binding`, `startup`, or `analysis`) and an exception-class chain capped at four types. It never records the exception message, image, pixel, landmark, coordinate, path, URI, timestamp, or identifier. Another exact run requires fresh authorization for the changed Android test APK.

## Final corrected result

The separately authorized `positive-centered-diagnostic-h` invocation verified debug `28c3820a2780fb744c37d056b4742eb71eab9249c9947deb411c4089c6e57dcd` and test `d69892e64e8f9041f098ea3c249918c37037d54ba72ab916777a0fd02c2e4808`, then completed its full 15-second preflight. All 145 analyzed frames remained below the unchanged `0.25` person gate. Maximum valid person score was `0.14585432410240173`; maximum valid keypoint score was `0.20261988043785095`. The method failed before recording, wrote no report, and completed exact cleanup.

This satisfies the Task 16G decision rule: the live pipeline carries partial pose evidence. It does not justify lowering the threshold because a single positive maximum supplies no negative separation or distribution evidence.
