# Task 14A.2 Active-Session Discovery — Pixel 6 Validation

- **Date:** 2026-09-02
- **Device class:** Pixel 6 (`oriole`)
- **Android release:** 16
- **Repository baseline:** `dfb221b1b332e008e21c494b0b0cd9dda45cb5d2`
- **Main APK SHA-256:** `61ac6dc7d95d250dbd1fd535b9dc49eebadc9a6a1669366b1e58f123f6d3f35d`
- **Instrumentation APK SHA-256:** `105f55720efe49ac1afb78b98aeae0022f777f009c48515d10c372daddb7201a`

## Scope

This run validated only the Task 14A.2 read-only active-session discovery boundary plus the existing Task 14A.1 Room bootstrap regression set. It did not exercise UI navigation, process-death routing, Photo Picker, camera permission, CameraX, private image capture, MediaStore, physical deletion, speech, audio, or the end-to-end guided workflow.

All tests used UUID-named databases in the target app's test-owned database namespace. No provider image, user database row, screenshot, camera frame, path/URI value, or device identifier was retained in this record.

## Pre-device host evidence

The candidate passed:

- 591/591 JVM tests, with zero failures, errors, or skips;
- `:app:lintDebug`;
- `:app:assembleDebug`;
- `:app:assembleRelease`;
- `:app:assembleDebugAndroidTest`;
- byte comparison proving Room V1, V2, and V3 schema artifacts were unchanged.

The new `ActiveGuidedSessionMapperTest` class first produced 12/12 runtime assertion failures against the compile-safe placeholder, then 12/12 passes against the implementation.

## Artifact identity

Exactly one connected device was identified as a Pixel 6. The locally built APKs were hashed, installed with replacement while preserving main-app data, pulled back from their installed package paths, and hashed again. Both installed hashes matched the values above.

## Focused instrumentation

Command scope:

- `RoomShootRepositoryAndroidTest#activeSessionDiscoveryFindsExactSessionAcrossReopen`
- `RoomShootRepositoryAndroidTest#activeSessionDiscoveryFailsClosedOnTriggerBypassedCorruption`
- `RoomShootRepositoryAndroidTest#roomV3BootstrapSurvivesReopen` (regression)
- all five methods in `GuidedSessionPacket2BAndroidTest` (regression)

Result:

- **8/8 passed**
- **0 failures**
- AndroidJUnitRunner elapsed time: **1.53 seconds**

Evidence for the new methods:

1. A production-started session was discovered as the exact active session, survived Room close/reopen with the same result, a second sessionless shoot returned `None`, and a missing shoot returned `UnknownShoot`.
2. With the one-active-session triggers dropped in the UUID test database only, a directly inserted second `ACTIVE` session made discovery fail closed with `Rejected(AUTHORITY_INCONSISTENT)` instead of choosing a winner.

## Cleanup

Every test closed its Room connections and deleted its UUID-named database in teardown. Post-run verification found zero matching test-database or sidecar filenames and the instrumentation package absent, with the main application package and its data preserved.

## Evidence boundary

This record proves the exact tested APK pair's Room discovery and bootstrap behavior only. It does not prove UI resume, production process-death recovery, camera capture, export, deletion completion, or Gate 2.
