# Pixel 6 Camera Vertical-Slice Validation — 2026-08-28

## Scope and privacy

Authorized Pixel 6 (`oriole`) execution on Android 16 / API 36. Validation used the rear camera, generated black bitmaps, the already-attributed public pose fixture, and temporary visual inspection. No device serial, camera image, screenshot, raw landmark stream, tensor, or private filesystem path is retained in the repository.

Hardware execution in this note used these candidate artifacts:

- Main debug APK SHA-256: `a678f014cefc19281bd253cfdb64b97bf0a3ec65f2e3d2f374248bfd47dfc3ad`
- Instrumentation APK SHA-256: `3f0985b207286c0c4f249183ae5d434488edf129afd53ed401f70988bd8135c5`

These hashes bind the current hardware evidence but are not a commit/review digest. A clean build and a forced rebuild produced byte-identical APK pairs before installation; no APK rebuild occurred after the final pair was installed. Any later production or instrumentation code change requires affected hardware evidence and hashes to be refreshed.

## Passed hardware checks

- Direct MoveNet public/generated fixture instrumentation: 1/1.
- Generated-bitmap camera-frame engine instrumentation: 7/7.
- Installed permission contract: CAMERA plus the app-signature dynamic-receiver permission only.
- Denied-permission screen rendered readably with no clipping.
- The labeled `Allow camera` action launched Android's standard CAMERA permission dialog.
- Rear Preview, ImageAnalysis, and ImageCapture bound through one shared viewport.
- The live UI reached `Camera status: ready` and updated honest person/landmark diagnostics.
- The packaged main public-reference drawable reproduced the exact fixed one-person, 17-landmark MoveNet observation on Pixel: 1/1.
- The app rendered the bundled public meditation image in a bounded card with visible `Google AI Edge · CC BY 4.0` credit plus its fixed ghost skeleton in the same preview transform as the live skeleton. The exact source/license/hash notice is also packaged under main assets. Named coverage, angular, positional, overall, and mirror evidence remained separately visible as stable pass/fail states labeled uncalibrated; numeric scores remain internal evidence, framing was explicitly not evaluated, and capture lock was disabled.
- With the public fixture displayed on the Mac, the live UI reported one person and 17/17 landmarks. The rear-unmirrored live skeleton visibly tracked the public figure's head, shoulders, arms, hips, crossed legs, and ankles without clipping or scale/mirroring displacement. The dense evidence panel and reference card were readable and unclipped on the Pixel 6. The transient screenshots were deleted and are not retained.
- No capture, lock eligibility, persistence, or advancement claim was shown.
- Camera active-client state became empty within 500 ms after the app moved to the background.
- Generated-byte owned-reservation publication and pre-existing-final no-clobber: 2/2.
- Real reducer-command rear-camera exactly-three JPEG capture, repeated-token collision, byte preservation, and known-file cleanup: 1/1.
- After capture acceptance, the candidate/temp directories contained zero residual files.

## Initial sustained run before cadence bounding

One 60-second live run sampled process and device aggregates every five seconds:

- Camera active for all 13 samples.
- PSS warmed from 213,180 KiB to a stable post-warm-up band around 307–310 MiB; no continuing leak trend was observed.
- RSS warmed from 363,340 KiB to a stable band around 459–462 MiB.
- Process CPU averaged 134.77% and peaked at 144.0%.
- Battery temperature rose from 27.9°C to 28.8°C.
- Thermal status remained 0 throughout.
- Fatal/ANR count: 0.
- Runtime GC log count: 14.

The CPU/GC result was not accepted as the final live policy. Task 10 therefore added a fixed 10 Hz, explicit-camera-timestamp cadence gate before bitmap conversion.

## Cadence-limited hardware evidence

The final real CameraX cadence acceptance observed 61 frames, accepted 21, skipped 40 as too soon, and classified 0 as stale. The bounded analyzed-result rate was 9.64/s.

Generated-black direct MoveNet latency over 25 measured runs after three warm-ups was:

- p50: 124.79 ms
- p95: 126.53 ms
- maximum: 126.64 ms

The repeated 60-second live run sampled the same aggregate process/device surfaces every five seconds:

- Camera active for all 13 samples.
- PSS 256,793–332,419 KiB; after 20 seconds samples remained in a 324,211–332,419 KiB band.
- RSS 408,708–486,192 KiB; after 20 seconds samples remained in a 477,684–486,192 KiB band.
- Process CPU averaged 148.08%, peaked at 162.0%, and averaged 150.44% after 20 seconds in a 140–162% range.
- Battery temperature changed from 31.0°C to 30.8°C.
- Thermal status remained 0 throughout.
- Fatal/ANR count: 0.
- Runtime GC log count: 14.
- Camera active-client state became empty within 500 ms after backgrounding.

Compared with the pre-cadence run, final integrated mean CPU was 9.88% higher, peak CPU 12.50% higher, maximum PSS 7.11% higher, and maximum RSS 5.18% higher. Memory remained bounded rather than continuing to grow, thermal status stayed 0, temperature did not rise, and GC log count remained 14. Runs varied materially with device conditions: a prior non-final build begun at 25.8°C measured lower CPU, while an exact-source run begun at 38.2°C/status 2 was rejected as thermally incomparable. This final-hash run began at 31.0°C/status 0 and is the accepted same-artifact record; it does not support a claim that the integrated fixed-reference UI is cheaper than the pre-cadence slice. The later 15-minute Gate 4 soak remains required.

## Android app-private publication finding

The original pure-JVM no-clobber publisher used hard-link publication after syncing a same-directory temporary file. It passed JVM failure injection but failed on the Pixel:

- Java NIO `Files.createLink`: failed at `LINK_FINAL`.
- Direct Android libc `Os.link`: failed with errno 13 (`EACCES`).
- No stale final or temporary file caused the failure.

Conclusion: Android's app sandbox/SELinux denies hard-link creation in this app-private directory. Hard-link variants are not a viable production path.

A generated-byte disposable spike and the final 2/2 Android publisher acceptance passed this no-JNI protocol:

1. Atomically reserve the final identity with `CREATE_NEW`.
2. Reject a pre-existing foreign final without changing its bytes.
3. Write and sync the same-directory temporary file.
4. Verify the target remains the publisher-owned empty reservation.
5. Atomically rename the complete temporary file over only that owned reservation.
6. Sync the parent directory.

The reservation is non-authoritative and must never be treated as a durable output by filesystem scanning. Task 14's Room confirmation transaction remains the authority after all three candidate outputs publish. Crash leftovers remain explicit reconciliation work.

The final JVM publisher revision also serializes every supported in-process mutation of the exclusively owned capture-candidates directory through one process-wide guard. A deterministic paused-seam test proves a second publisher cannot mutate the identity between reservation verification and atomic rename. This boundary does not claim protection against same-UID code that bypasses the adapter; such mutation is unsupported.

The final capture-mechanics revision maps existing/crash-leftover deterministic identities to reconciliation-required and gives exact-three success precedence when close races after the third publication. It retains cleanup ownership both after a prepared output exists and when deterministic temp creation fails before a prepared output can be returned. The prepare-time owner controls only the verified reservation and cleanup-directory sync state, never the colliding foreign temp. While cleanup is pending, conflicting submission is blocked and serialized retry reports cleaned or still pending without a duplicate terminal callback.

## Pending before Task 10 acceptance

- Bind the final no-code-change candidate to an exact staged digest and refresh APK hashes if review changes code.
- Obtain specification PASS and quality/security APPROVED on the same staged bytes before commit.
