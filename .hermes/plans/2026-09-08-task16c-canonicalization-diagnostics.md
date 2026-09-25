# Task 16C — bounded canonicalization diagnostics

**Status:** Host verified and three exact Pixel invocations complete; the observable repeat proved 146/146 analyzed warm-up frames were `no-person`, so the earlier one-person canonicalization failure remains unreproduced.

## Objective

Explain the Task 16B positive canonicalization failure without retaining images, raw landmarks, landmark identities, coordinates, timestamps, paths, URIs, or personal identifiers.

## Implemented slice

1. Advance the device-report schema to version 2 while keeping the analyzer compatible with historical schema-version-1 reports.
2. Add one closed per-frame evaluation status: `no-person`, `multiple-people`, `canonicalization-failed`, or `evaluated`.
3. Add only two bounded counts: confidence-qualified MoveNet landmarks in `[0, 17]` and confidence-qualified shoulder/hip torso anchors in `[0, 4]`.
4. Require status/person-count agreement, require all four qualified torso anchors for an evaluated frame, and require zero qualified landmarks for a no-person frame.
5. Make analyzer replay fail closed for v2 unevaluated frames even if malformed input supplies passing scalar scores.
6. Report per-sequence status frequencies plus minimum, median, and maximum diagnostic counts in JSON and Markdown.
7. Keep fragment merging strict across schema version, provenance, authorization, population limits, privacy marker, and unique sequence IDs.
8. Write the new fixed device output as `calibration-sequence-v2.json`; stale and partial output behavior remains bounded as before.

## Invariants

- The collector never persists a landmark identity or coordinate. Counts are calculated inside the frame callback and only the bounded scalar frame enters the retained list.
- Schema v2 diagnostics are mandatory and unknown fields remain rejected.
- Schema v1 reports remain readable for historical Task 16B analysis and have unavailable diagnostic summaries.
- The diagnostic evidence cannot select thresholds or enable automatic capture by itself.
- The instrumentation marker remains a mechanical fail-closed argument and cannot replace exact user authorization for the final frozen artifact and physical collection.
- The warm-up must end with at least five consecutive exactly-one-person frames before recording begins; otherwise the collector fails without a report.

## Host verification

- 708/708 JVM tests pass with zero failures, errors, or skips.
- 12/12 Python analyzer tests pass.
- Lint reports zero errors and 9 warnings.
- Debug, unsigned release, and Android-test APK assembly pass.
- Room V1–V4 schema hashes and packaged permissions are unchanged.
- Device-tested corrected-repeat SHA-256 values: unchanged debug `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4`; Android test `8b2d4e81440b11878bf0635c563d305985b25b600c3f385341a75088d726d4e4`; unchanged unsigned release `50437b7e4651446e374204edb6095a0ddd23803fcdf5b71c7e10b4112473a769`.
- Debug and release request only camera plus AndroidX's app-signature receiver permission. The Android-test APK requests only `REORDER_TASKS`; none requests internet access.

## First device result

The separately authorized frozen pair matched its installed bytes. The exact `positive-centered-diagnostic-a` invocation passed 1/1 in 16.877 seconds and produced 97 schema-v2 frames with a matching device/host report digest. Every frame was `no-person`, with zero confidence-qualified landmarks and torso anchors. The app camera disconnected, exact device output and temporary names were removed, the test package was uninstalled, and the main app plus unrelated data remain.

This proves schema-v2 status disambiguation but does not explain the earlier one-person canonicalization failure. The host-corrected collector now fails before recording unless warm-up ends with five consecutive exactly-one-person frames.

## Corrected repeat result

The separately authorized `positive-centered-diagnostic-b` repeat verified the installed main and corrected Android-test hashes, then ran only the exact collector method with the same 5-second warm-up and 10-second requested recording window. It failed 1/1 after 6.522 seconds because warm-up never ended with five consecutive exactly-one-person frames. Recording never began and no report or temporary report was created. Exact cleanup removed the empty export directory and test package, closed the app camera, and preserved the main app and unrelated data.

The preflight therefore works and prevents unusable no-person evidence. Task 16C remains a safe diagnostic failure: no threshold changes, automatic capture remains disabled, and another private-device collection should wait until the preview/detection path is easier to verify and a new frozen artifact receives fresh exact authorization.

## Host-only preflight observability follow-up

The collector now updates the existing on-device status text from each warm-up result: no person, exactly one person with current consecutive-frame progress, or multiple people. A failed preflight assertion reports only six aggregate scalars: analyzed, no-person, one-person, and multiple-person frame counts plus final and maximum consecutive one-person counts. These values are never written to a report. The collector still deletes partial output and closes the camera on failure, and it retains no image, landmark identity, coordinate, tensor, timestamp, path, URI, or personal identifier.

The complete host gate remains green at 708/708 JVM tests, 12/12 Python tests, zero lint errors and 9 warnings, and all APK assemblies. Room schemas and packaged permissions remain unchanged. The observable candidate hashes are unchanged debug `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4`, Android test `d80047d2ecc39a011c345c50e3e2b51f5cba32aea1e6126d5b40a23f3780135b`, and unchanged unsigned release `50437b7e4651446e374204edb6095a0ddd23803fcdf5b71c7e10b4112473a769`.

The separately authorized `positive-centered-diagnostic-c` invocation used a 15-second warm-up and failed 1/1 after 16.538 seconds. It analyzed 146 frames: 146 no-person, zero one-person, and zero multiple-person frames, with zero final and maximum consecutive one-person frames. Recording never began and no report was created. Exact cleanup removed the empty export directory and test package, closed the app camera, and preserved the main app and unrelated data. This rules out a camera-analysis cadence failure but does not distinguish a low raw MoveNet instance score from an empty or poorly framed scene.
