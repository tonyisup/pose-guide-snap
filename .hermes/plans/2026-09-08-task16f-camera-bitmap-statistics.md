# Task 16F — bounded CameraX bitmap statistics

**Status:** Host verified and exact private-device diagnostic completed on the frozen APK pair; cleanup completed.

## Objective

Determine whether the transient CameraX bitmap reaching MoveNet is black or visually flat, after Task 16D observed a zero raw person score across 146 live frames and Task 16E proved the same APK's static model path healthy.

## Implementation boundary

1. Sample a fixed at-most-16×16 grid from the already-owned upright crop before inference.
2. Reduce those pixels immediately to normalized mean luminance and luminance range in `[0, 1]`.
3. Carry only the immutable two-scalar value beside the analyzed frame.
4. During the existing authorized warm-up, retain only minimum and maximum frame-mean luminance plus the maximum within-frame luminance range.
5. Include those aggregate values only in a failed preflight assertion. Do not persist them in the calibration-report schema.

The implementation retains no pixel, bitmap, image, color histogram, coordinate, timestamp, path, URI, tensor, landmark, or identifier. It does not change model input, the `0.25` person threshold, matching policy, report schema, or automatic-capture behavior.

## Decision rule

- Near-zero mean and range across the warm-up identifies a black CameraX conversion/crop result.
- Nonzero mean with negligible range identifies a flat image input.
- Nonzero mean and material range proves that varied scene pixels reach the detector, narrowing the next investigation to camera scene/framing differences or conversion details not captured by luminance.

The device run below received fresh exact authorization after the APK hashes and host gate were frozen.

## Host verification

- 712/712 JVM tests pass with zero failures, errors, or skips.
- 12/12 Python analyzer tests pass.
- Lint reports zero errors and 9 warnings.
- Debug, unsigned release, and Android-test APK assembly pass.
- Room V1–V4 schema hashes and packaged permissions are unchanged.
- Frozen SHA-256 values: debug `a47f4588346abdbd7d569d2a02de38a29c12e41abfc5a169e8d8eceee3f52d94`; Android test `d04404d44440ff165ab6f74b341026dd8900531d115387472316e1970d68ae98`; unsigned release `74ee660bd84b421ce9dd5cf7a42d72e72e4d108720fc7b4be99ac7e3df54c9e3`.

## Pixel 6 result

The separately authorized `positive-centered-diagnostic-e` method used a 15-second warm-up and a 10-second recording window conditional on preflight success. Installed main and Android-test bytes matched the frozen hashes. It failed safely before recording after 16.57 seconds with 146/146 no-person frames, `maximumValidPersonScore=0.0`, frame-mean luminance ranging from `0.4006791299019608` to `0.642319387254902`, and a maximum within-frame luminance range of `0.3730839215686274`.

No report or temporary report was created. Exact cleanup removed the empty export directory, test package, and temporary APK pulls, stopped the camera, and preserved the newly installed main app, its data, and unrelated files. Final checks found no active camera client. The live bitmap is neither black nor flat, so the next discriminator belongs in raw keypoint-score evidence rather than image-presence or threshold changes.
