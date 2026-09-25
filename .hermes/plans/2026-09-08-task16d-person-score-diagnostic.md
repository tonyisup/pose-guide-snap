# Task 16D — bounded MoveNet person-score diagnostic

**Status:** Host verified and exact private-device diagnostic completed on the frozen APK pair. The next static public-fixture recheck has a separate method scope and requires fresh exact authorization.

## Objective

Distinguish a genuinely negligible MoveNet person response from an instance score just below the unchanged `0.25` acceptance threshold, without retaining an image, raw tensor, person slot, landmark, coordinate, timestamp, path, URI, or personal identifier.

## Implemented slice

1. Reduce each six-slot MoveNet output immediately to the strongest finite instance score in `[0, 1]` without exposing any slot identity or mutable tensor surface.
2. Carry that one nullable scalar beside the immutable analyzed camera frame, with constructor validation that rejects nonfinite and out-of-range values.
3. During the existing preflight, retain only the maximum valid score seen across the bounded warm-up.
4. Include that maximum and the unchanged minimum accepted score in a failed preflight assertion. Do not add either value to the persisted calibration-report schema.
5. Keep the existing five-consecutive-one-person requirement, failed-output deletion, camera closure, and automatic-capture disablement unchanged.

## Host verification

- 709/709 JVM tests pass with zero failures, errors, or skips.
- 12/12 Python analyzer tests pass.
- Lint reports zero errors and 9 warnings.
- Debug, unsigned release, and Android-test APK assembly pass.
- Room V1–V4 schema hashes and packaged permissions are unchanged.
- Frozen SHA-256 values: debug `16843f2b88f11116161ec81ecbf460b691eac97fa4b1123248dbff426852e787`; Android test `488eb4b615f74479d06824f29c711cbadaf24013778acd35a3cf1fbfdcddac7e`; unsigned release `c7aae95d26bc295ab42f5dad7688814f4775df95cacf567fe668df7f3bc696ba`.

## Evidence boundary

The strongest valid instance score is model-derived scalar evidence. It cannot identify a person, reconstruct an image, select a person slot, or reveal a keypoint. A device run may use it only to decide whether the next investigation concerns camera framing/input or the uncalibrated person-score boundary. It cannot justify lowering the threshold or enabling automatic capture by itself.

## Pixel 6 result

The separately authorized `positive-centered-diagnostic-d` invocation used a 15-second warm-up and a 10-second recording window conditional on preflight success. Installed bytes matched the frozen debug and Android-test hashes. The method failed safely before recording after 16.594 seconds: all 146 analyzed frames were `no-person`, exactly-one-person and multiple-person counts were zero, both consecutive-one-person counts were zero, and `maximumValidPersonScore=0.0` beside `minimumAcceptedPersonScore=0.25`.

No report or temporary report was created. Exact cleanup removed the empty export directory, stopped the app camera, uninstalled only the test package, and removed temporary installed-APK pulls. The main app remains installed with its data preserved. The healthy analysis cadence plus a zero maximum rules out a borderline threshold miss and does not justify changing `0.25`.
