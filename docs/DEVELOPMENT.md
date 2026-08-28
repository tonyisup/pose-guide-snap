# Development Environment

> **Project status: planning/bootstrap; no working app yet.** This document records the command-line Android toolchain verified on 2026-08-28. The Android project and Gradle wrapper are Task 3 work and do not exist yet.

## Verified host

| Component | Verified value |
|---|---|
| Host | macOS 26.6.2 (build 25G83), Apple Silicon (`arm64`) |
| Xcode Command Line Tools | `/Library/Developer/CommandLineTools` |
| Homebrew | 6.0.20 at `/opt/homebrew/bin/brew` |
| JDK | Homebrew OpenJDK 17.0.20.1 |
| `JAVA_HOME` | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| Android command-line tools | Homebrew cask `15859902`; Android CLI `1.0.15985488`; legacy `sdkmanager` `22.0` |
| Android SDK root | `/Users/juicebox/Library/Android/sdk` |
| Android platform | `platforms/android-37.0` revision 2 |
| Android build tools | `build-tools/36.0.0` |
| Android platform tools | `37.0.1`; ADB `1.0.41` |
| Android Studio | Not installed; the approved equivalent official JDK + command-line SDK path is used |

The toolchain deliberately does not depend on a running Android Studio GUI. [Android Gradle plugin 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes) uses JDK 17, Gradle 9.5, and Build Tools 36.0.0; the project will pin those build versions when Task 3 creates the wrapper. Android's [Java build guidance](https://developer.android.com/build/jdks) is the source of truth for the JDK requirement.

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

## Current boundary

This toolchain verification does **not** prove an Android application builds. Task 3 must create and pin the Gradle wrapper, bootstrap the app, run its unit test task, and assemble a debug APK before Gate 0 can pass.
