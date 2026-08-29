# Development Environment

> **Project status: Tasks 1–11A are committed; Task 11A is host-reviewed and Pixel-exercised.** This document records the command-line Android toolchain, the historical Task 3 bootstrap checkpoint, and the commands used to verify the current prototype. Current HEAD includes rear CameraX preview/analysis, direct on-device MoveNet, internal exactly-three app-private candidate mechanics, and durable Room capture/deletion/claim authority. Authorized Pixel 6 testing has run; Gate 2 remains incomplete because the camera/filesystem path is not yet connected to Room or MediaStore I/O.

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

At the historical Task 3 checkpoint, the JVM suite checked the provisional package/version configuration and structurally parsed the source manifest and both backup-rule resources. Its Android instrumentation test checked that the installed package requested only AndroidX's app-signature permission `com.tonyisup.poseguidesnap.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and that `FLAG_ALLOW_BACKUP` was clear; that Task 3 bootstrap verification compiled the test without running a device or emulator. Later Task 9/10 authorization ran the relevant instrumentation on the Pixel 6 against the committed camera slice. The AndroidX permission protects non-exported dynamic receivers and grants no camera, storage, or network capability.

## Current boundary

The command-line build remains GREEN: the native Compose prototype and instrumentation APK compile, the JVM suite passes, and exact packaged-resource inspection verifies the package/version/SDK and fail-closed backup boundary. Current HEAD contains the committed, authorized-Pixel Task 10 camera slice and Task 11A Room confirmation/advancement, deletion-barrier, and claim authority. This is not a completed guided-capture product. It has no user-facing shutter or auto-capture, camera/filesystem-to-Room integration, reference import, MediaStore I/O, TTS/audio, physical deletion flow, analytics, cloud, or end-to-end workflow; Gate 2 remains unpassed.
