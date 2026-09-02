# Task 12 Pixel 6 Validation — Room V3 Shoot Preparation

- **Device-run date:** 2026-09-01
- **Documentation date:** 2026-09-02
- **Scope:** Explicitly authorized Task 12 preparation/editor device gate only
- **Device:** Pixel 6
- **OS:** Android 16
- **Final implementation commit:** `5bc15c33c5b6c36196f99a2bd8259fe2b00ffeb3`
- **Final reviewed staged digest:** `23318b4fa98a15405462f3419d1a5d47ac84af0ef12a79b40b1668d4212d0864`

## Host gates

The final source passed:

- 556/556 JVM tests with zero failures, errors, or skips across 54 XML suites;
- `lintDebug`;
- debug APK assembly;
- release APK assembly;
- instrumentation APK assembly;
- `git diff --check`;
- manifest inspection with no `INTERNET`, storage, audio, location, or foreground-service permission.

The committed schema artifacts remained explicit and immutable:

- Room V1 SHA-256: `e5eb94f4ff96944cc9de1aa5c2f6e8e326ba5caaa224c46f3247056cb1c33ab8`;
- Room V2 SHA-256: `0c9ea87ccbf1c57a404d4302ca1d8a7713ec934663dd6000df34766487929bbd`;
- Room V3 SHA-256: `53f30c71d5bad0b4efc66075346ffaccc6e69402ae4a904b7c94c5f45ff3ae25`.

## Device evidence

### Final-artifact Compose instrumentation

The final authorized Pixel 6 follow-up passed **4/4** `ShootEditorFlowTest` methods:

```text
OK (4 tests)
```

That class renders `ShootEditorScreen` from synthetic state and explicitly uses no database, picker, camera, or device I/O. It verified:

- loading, missing, unavailable, and retry semantics;
- each reference label, validation state, and mirror policy within its exact redacted position-tagged row;
- accessible move-up/move-down semantics and the exact reorder callback payload;
- Start disabled below three references and enabled at three, with the callback invoked once;
- visible import-in-progress and reconciliation-required states.

The test dependency was explicitly aligned to maintained Android-test-only Espresso `3.7.0` after the first Android 16 run exposed the removed reflective `InputManager.getInstance` path in the older transitive runtime. Off-screen `LazyColumn` items were materialized by scrolling before assertions. Repeated row text was asserted within the exact row ancestor in the unmerged semantics tree.

### Earlier manual production workflow observation

Before the final source change, a separate authorized manual Pixel run observed:

- production shoot creation and navigation from the Room-backed shoot list;
- three imports of the bundled public meditation fixture through Android's system Photo Picker;
- callback-local provider URI handling;
- Room/file-ledger/MoveNet settlement into three validated active references;
- transactionally persisted reorder;
- one durable Room session created before navigation to the started-session route;
- camera explanation and the Android camera-permission dialog becoming reachable only after durable start;
- denial of camera permission, with no camera preview or sensor run;
- a pending Photo Picker operation surviving portrait-to-landscape recreation and settling into the same editor after selection.

The final source changed afterward to add redacted row tags and strengthen row-scoped assertions. The manual production observation is therefore supporting pre-final evidence, not same-artifact proof for commit `5bc15c33c5b6c36196f99a2bd8259fe2b00ffeb3` or final staged digest `23318b4fa98a15405462f3419d1a5d47ac84af0ef12a79b40b1668d4212d0864`.

## Visual, accessibility, and privacy findings

The earlier manual run found and corrected two visible defects before the final gate:

- route content was transparent over the activity background despite the dark theme, producing mixed colors and unreadable system-bar contrast;
- the untouched blank reference-label field appeared as an immediate error with duplicate guidance.

The final source paints one dark root `Surface`, keeps an untouched blank label visually neutral while submission remains fail-closed, and retains visible errors for invalid nonblank values. Compose semantics and controls ran on the Pixel, but this was **not** a TalkBack or screen-reader usability session; the documentation makes no unqualified accessibility-usability claim.

Tests and the manual run used only the bundled attributed public fixture. No private image, camera frame, raw landmark stream, provider URI, private path, or MediaStore row was committed. Documentation review later found 19 transient host screenshots from the manual run under `/tmp`; all 19 were removed on 2026-09-02 and the bounded filename pattern was verified empty.

## Cleanup

After the authorized run:

- the public fixture copied to the device was removed;
- application and instrumentation test data were removed;
- the instrumentation package was absent;
- display rotation settings were restored to their exact prior values;
- no camera permission remained granted and no camera preview had run.

## Review and claim boundary

Specification and quality/security/UX reviewers approved the identical final staged digest with no Critical, Important, or Minor findings. That digest was committed as `5bc15c33c5b6c36196f99a2bd8259fe2b00ffeb3` and verified on `origin/main`.

This evidence closes the implemented Task 12 milestone without collapsing its evidence classes. The final artifact proves the bounded synthetic-state Compose behaviors listed above; the production create → import → validate → reorder → durably start path was manually observed only on the labeled pre-final artifact. No final-commit same-artifact production-workflow claim is made. Neither evidence class proves Task 13 speech, a user-facing shutter, automatic capture, capture-to-Room confirmation/advancement, MediaStore export, physical deletion, TalkBack usability, or the complete guided workflow. Gates 2–4 remain unpassed.
