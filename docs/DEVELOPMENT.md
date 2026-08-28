# Development Environment

> **Project status: buildable Android/Compose prototype.** This document records the command-line Android toolchain and Task 3 bootstrap verified on 2026-08-28. Camera, pose detection, coaching, capture, storage, export, and the product workflow are not implemented. No device or emulator test has been run.

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

The Task 3 JVM suite checks the provisional package/version configuration and structurally parses the source manifest and both backup-rule resources. The Android instrumentation test checks that the installed package requests only AndroidX's app-signature permission `com.tonyisup.poseguidesnap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and that `FLAG_ALLOW_BACKUP` is clear; it is compiled by `assembleDebugAndroidTest` but has **not** been run because this bootstrap verification uses no device or emulator. That permission protects non-exported dynamic receivers and grants no camera, storage, or network capability.

## Current boundary

The command-line bootstrap is GREEN: the native Compose prototype and instrumentation test APK compile, the JVM tests pass, and exact packaged-resource inspection verifies the package/version/SDK and fail-closed backup boundary. This proves only that Gate 0 can bootstrap and build. The app currently renders `Pose Guide Snap — prototype`; it has no camera, pose detection, coaching, capture, persistence, export, analytics, cloud, or product workflow.
