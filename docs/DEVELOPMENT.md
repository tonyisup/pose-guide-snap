# Development Environment

> **Project status: Tasks 1–12 and 14A.1–14A.3 are implemented and boundedly verified. The Task 14B.1A Room V4 journal foundation landed at `57b33c9`, but final specification review returned `REQUEST_CHANGES`; an uncommitted local repair is under review.** This document records the command-line Android toolchain and commands used to verify the current prototype. The foundation adds durable intent, migration, bootstrap, logical-start, confirmation guard, and deletion-clock behavior only. Gate 2 remains incomplete because no production per-file effect-admission or journal-transition API connects the camera/filesystem path to Room confirmation or MediaStore I/O, and no product-shipped claim is made.

## Verified host

| Component | Verified value |
|---|---|
| Host | macOS 26.6.2 (build 25G83), Apple Silicon (`arm64`) |
| Xcode Command Line Tools | `/Library/Developer/CommandLineTools` |
| Homebrew | 6.0.20 at `/opt/homebrew/bin/brew` |
| JDK | Homebrew OpenJDK 17.0.20.1 |
| Gradle wrapper | 9.5.0, distribution SHA-256 pinned |
| Android Gradle Plugin | 9.3.2 with built-in Kotlin 2.2.10 |
| Compose compiler plugin | 2.2.10 |
| Compose BOM | 2026.08.00 (UI 1.12.0, Material 3 1.4.0) |
| `JAVA_HOME` | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| Android command-line tools | Homebrew cask `15859902`; Android CLI `1.0.15985488`; legacy `sdkmanager` `22.0` |
| Android SDK root | `/Users/juicebox/Library/Android/sdk` |
| Android platform | `platforms/android-37.0` revision 2 |
| Android build tools | `build-tools/36.0.0` |
| Android platform tools | `37.0.1`; ADB `1.0.41` |
| Android Studio | Not installed; the approved equivalent official JDK + command-line SDK path is used |

The toolchain deliberately does not depend on a running Android Studio GUI. [Android Gradle plugin 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes) uses JDK 17, Gradle 9.5, and Build Tools 36.0.0; the project pins that stack in its wrapper, version catalog, and app build configuration. AGP's built-in Kotlin 2.2.10 is used, so the project does not apply `org.jetbrains.kotlin.android`; the Compose compiler plugin matches Kotlin at 2.2.10. Android's [Java build guidance](https://developer.android.com/build/jdks) is the source of truth for the JDK requirement.

## Shell configuration

`~/.zprofile` contains:

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Use a new login shell after changing this file. A system-wide JDK symlink is not required because `JAVA_HOME` and `PATH` select the verified Homebrew JDK directly.

## Reproduce the toolchain

Install the JDK and official Android command-line tools:

```sh
brew install openjdk@17
brew install --cask android-commandlinetools
```

Set the shell variables above, open a new login shell, and install the pinned SDK components with the current Android CLI:

```sh
android sdk install \
  platform-tools@37.0.1 \
  platforms/android-37.0@2.0.0 \
  build-tools/36.0.0@36.0.0
```

Review and accept the Android SDK licenses interactively when authorized. Do not script legal acceptance blindly. On the verified host, `sdkmanager --licenses` reports `All SDK package licenses accepted.`

## Verification

The plan requires the legacy commands below because they remain common in Android build documentation. `sdkmanager` now prints a deprecation warning and points to `android sdk`, but the command still succeeds.

Run from a fresh login shell:

```sh
zsh -lic 'java -version'
zsh -lic 'sdkmanager --list'
zsh -lic 'adb version'
```

Expected identifying output:

- `openjdk version "17.0.20.1"`
- `sdkmanager` exits successfully after listing the repository (with a deprecation warning)
- `Android Debug Bridge version 1.0.41`, platform-tools `37.0.1`

Use the non-deprecated Android CLI for focused package checks:

```sh
zsh -lic 'android sdk list "platform-tools"'
zsh -lic 'android sdk list "platforms*"'
zsh -lic 'android sdk list "build-tools*"'
```

The verified installed packages are `platform-tools 37.0.1`, `platforms/android-37.0` revision `2.0.0`, and `build-tools/36.0.0`.

## Project bootstrap verification

From the repository root, run the pinned wrapper and JVM contract tests:

```sh
./gradlew --version
./gradlew :app:testDebugUnitTest
```

Compile both the prototype and its instrumentation test APK:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

The clean, combined proof used for the Task 3 candidate is:

```sh
./gradlew clean \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest
```

The app APK is `app/build/outputs/apk/debug/app-debug.apk`. Hash that exact file before inspecting it:

```sh
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
```

Do not use `apkanalyzer` on this verified split Homebrew/SDK layout. Inspect the exact hashed APK with the pinned Build Tools binary:

```sh
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump xmltree \
  app/build/outputs/apk/debug/app-debug.apk \
  --file AndroidManifest.xml
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump xmltree \
  app/build/outputs/apk/debug/app-debug.apk \
  --file res/xml/backup_rules.xml
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump xmltree \
  app/build/outputs/apk/debug/app-debug.apk \
  --file res/xml/data_extraction_rules.xml
```

## Room schema and migration workflow

Room schema changes must update the annotated database version, add an explicit migration, register it in the database builder, and export the new schema artifact under `app/schemas/com.tonyisup.poseguidesnap.data.db.AppDatabase/`. Historical schema artifacts are immutable evidence: a V4 change must leave `1.json`, `2.json`, and `3.json` byte-identical and add or reproduce the exact `4.json` contract.

For the Task 14B.1A candidate, `MIGRATION_3_4` creates only the empty `capture_file_operations` table and its indexes. Callback installation owns the ordinal and row-shape triggers on create/open. Migration tests must compare every preexisting value, null, cardinality, and SQLite storage class, verify no journal rows were synthesized for migrated attempts, exercise both direct V3→V4 and chained V1→V2→V3→V4 paths, and reject malformed rows after reopen.

The focused host commands for this candidate are:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
```

Before landing, rerun the cumulative build gates rather than treating compilation alone as release evidence:

```sh
./gradlew :app:lintDebug \
  :app:assembleDebug \
  :app:assembleRelease \
  :app:assembleDebugAndroidTest
```

Then verify that historical schemas did not drift, V4 reproduces exactly, no Room compiler leaks onto runtime/package classpaths, the APK still requests no `INTERNET` permission, no old `authorizeCaptureStart` API remains, and no production per-file journal transition or physical capture-file path was introduced.

At the historical Task 3 checkpoint, the JVM suite checked the provisional package/version configuration and structurally parsed the source manifest and both backup-rule resources. Its Android instrumentation test checked that the installed package requested only AndroidX's app-signature permission `com.tonyisup.poseguidesnap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and that `FLAG_ALLOW_BACKUP` was clear; that Task 3 bootstrap verification compiled the test without running a device or emulator. Later Task 9/10 authorization ran the relevant instrumentation on the Pixel 6 against the committed camera slice. The AndroidX permission protects non-exported dynamic receivers and grants no camera, storage, or network capability.

## Current boundary

The exact three-file Task 4 Android-test candidate v5 patch has SHA-256 `377dc02c781ece2cf78e48f93c727d02ea9d41082a12229ff22803e97a306491`; specification review returned `PASS` and engineering/security review returned `APPROVED` on those exact bytes. Its verified evidence is 635/635 JVM tests, Android-test compilation, Pixel 6 migration 10/10, focused NUL oracle plus direct migration 2/2, and an integrated five-class Pixel 6 run of 105 tests with 0 failures, 0 errors, and 10 expected `@Ignore` skips. The skipped direct first-application tests remain deferred to Task 14B.1C and are not passing evidence. This digest does not identify the complete 43-path Task 14B.1A candidate that landed at `57b33c9`.

Documentation candidate v1 exposed caller-list traversal before the unfinished-confirmation guard. Repair v1 was specification-rejected for a receipt-first bypass; repair v2 was specification-rejected because its nullable-timestamp guard did not enforce unfinished lifecycle authority. The final eight-scenario Pixel 6 method covers REGISTERED and CAPTURING with null or malformed non-null timestamps, each with and without a raw receipt. It failed on v2 with 3 reads and passed 1/1 after v3 moved the unavailable guard to explicit lifecycle state. Repair v3, SHA-256 `ed29acd613a890d213e398aaacf4a2d79512662ac0dbf88c411404fcfdb3bd3a`, received exact-byte specification `PASS` and engineering/security `APPROVED`; that approval covers only the two-file repair, not the complete landing candidate. The current-byte integrated five-class Pixel 6 run executed 106 tests with 0 failures, 0 errors, and 10 expected skips, and left no matching test-database residue.

The cumulative Task 5 host command passed again after that repair: 635/635 JVM tests, lint with zero errors, and debug, release, and Android-test assembly. Post-build checks confirmed byte-identical Room V1–V3 schemas and frozen-versus-regenerated V4, zero Room compiler markers on runtime classpaths or in packaged dex, and no `INTERNET` permission in the emitted debug, release, or Android-test manifest.

After `57b33c9` landed, final specification review reproduced a malformed byte-equivalent `BLOB` journal key that ordinary text equality did not count, allowing confirmed replay to return `AlreadyApplied` despite residual authority. The uncommitted repair uses byte-correlated token matching and adds a deterministic Android regression. That exact method failed on `57b33c9`, then passed 1/1 after the repair; the cumulative host/build gate passed with 635/635 JVM tests and zero lint errors. The repaired candidate remains unreviewed, uncommitted, and unpushed.

This is not a completed guided-capture product. Room V4 can persist and validate initial capture-file intent, but `Started` grants only logical Room state, unfinished confirmation remains blocked, and no production API can admit or transition a per-file effect. There is no new camera/filesystem capture path, physical file operation, personal-media access, MediaStore I/O, network, analytics, TTS/audio, physical deletion flow, or full Pixel acceptance evidence.
