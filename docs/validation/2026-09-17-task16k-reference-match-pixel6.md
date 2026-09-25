# Task 16K reference-pose positive — Pixel 6 — 17 September 2026

Result: **All 97 frames detected one person and evaluated the pose, but none passed every match-acquisition gate.** Framing failed throughout; positional and overall scores passed only four frames each. No match lock or capture command occurred. This is a recorded failed positive, not a completed calibration pair.

## Scope and artifacts

The participant confirmed readiness after instructions to match the displayed meditation reference with their complete body visible in the working lighting/camera setup. One 15-second warm-up plus 10-second positive collection ran over wireless ADB on the Pixel 6. The accepted-person preflight passed.

| Artifact used for the camera run | Local and installed SHA-256 |
|---|---|
| Debug app | `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66` |
| Android test | `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9` |

Both installed APK hashes matched their frozen local artifacts before launch. The main app did not need reinstallation. Camera permission was granted and checked by the collector; the previous export directory was absent.

Only `com.tonyisup.poseguidesnap.calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDerivedSequence` ran, using authorization `user-authorized-derived`, dataset `pixel6-match-bright-a`, sequence `positive-centered-bright-a`, fixture `positive`, case `centered-match`, `warmupMs=15000`, and `durationMs=10000`.

Instrumentation passed **1/1 in 26.642 seconds**, producing 97 schema-v3 frames. That pass means the collection completed, not that matching passed. Device and host report hashes matched: `ffefc9a61d570702b6564cb8fe796cc118439bf6636b3b446acf05b45c92dda8`.

## Match result

Every frame had one accepted person, successful evaluation, 17 confidence-qualified landmarks, and all four torso anchors. Person scores ranged from `0.572568` to `0.683142` with median `0.640203`. Detection and canonicalization therefore did not block this sequence.

| Acquisition criterion | Required | Minimum | Median | Maximum | Passing frames |
|---|---:|---:|---:|---:|---:|
| Landmark coverage | `0.750` | `0.784660` | `0.864442` | `0.897627` | 97/97 |
| Framing | `0.800` | `0.326322` | `0.340900` | `0.347581` | 0/97 |
| Angular similarity | `0.850` | `0.799043` | `0.854839` | `0.884665` | 50/97 |
| Positional similarity | `0.800` | `0.710733` | `0.775953` | `0.817541` | 4/97 |
| Overall match | `0.825` | `0.786250` | `0.802827` | `0.830565` | 4/97 |

No frame passed all five criteria; the offline replay acquired no lock. No negative matching sequence has run, so false-lock rate and positive/negative separation are unavailable. The full-body and empty-scene detector cases remain in their separate detector dataset and are not relabeled or pooled as reference-match controls.

The strict positive-only analysis JSON hash is `696756b593836de0bca071d1a767003e16ffdc93917e8a0ae86f606bfeeceb8d`; its Markdown hash is `0c7d0faa99c3811e0105957171d6040ba40e3ce402d2712ffdded29c49d50c92`.

## Investigation and next measurement

Source inspection confirms that `PoseFramingEvaluator` independently calculates center and scale similarity, then chooses their minimum as the framing score. The schema-v3 report retains only that minimum. It cannot tell whether the participant was off-center, occupied a different image fraction, or both. The matched pose, display framing, reference/camera aspect ratios, and collection setup remain possible contributors; this report does not identify the geometric root cause or justify telling the participant to move closer or farther away.

The next diagnostic separates the two existing scalar components before another positioning attempt. It does not change the evaluator, image pipeline, thresholds, schema-v3 reports, or capture behavior. The planned wrong-pose negative remains pending until the positive's framing failure is better understood.

## Test-only diagnostic follow-up

Three Android-test source files add an explicitly selectable `collectOneAuthorizedFramingDiagnosticSequence` method and a bounded reducer. For the same frames admitted to the existing report, the reducer retains only evaluated/unavailable counts and minimum/maximum center and scale similarity. It emits the closed scalar summary through instrumentation status. It stores no observation, coordinate, body extent, image, or raw landmark. Existing collector methods leave the diagnostic disabled.

The main APK remains byte-identical. The new Android-test SHA-256 is `c768792ed7d10bd7cd9e19f3ceccfe593cbd60c7a5f987505c13138912716cbf`. The original Task 16H test APK was preserved under the ignored build tree for comparable future collections.

Verification:

- `:app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --offline` succeeded. The unchanged JVM suite was up-to-date with 714 tests and zero failures/errors/skips; no fresh JVM execution is claimed. Android-test compilation/assembly and lint ran; lint reported zero errors and nine warnings.
- The Python analyzer suite ran and passed 14/14 tests.
- The new test APK was installed and its device-side hash verified. Only `CalibrationFramingDiagnosticsTest` ran: **3/3 passed in 0.027 seconds**. Its synthetic cases distinguish equal combined scores caused by center versus scale, exclude unavailable frames from numerical extrema, and verify true zero values plus the 600-frame cap. These tests open no camera and read no private device data.
- The subsequent [Task 16L camera diagnostic](2026-09-17-task16l-framing-components-pixel6.md) passed collection with 97 evaluated frames. Center similarity `0.381554–0.386197` limited framing throughout, and scale similarity `0.691971–0.711420` also failed the existing requirement. Its [plan](../../.hermes/plans/2026-09-17-task16l-framing-components.md) retains the existing accepted-person preflight and collection bounds.

## Cleanup and decision

After the positive collection, the exact report and fixed temporary filename were removed, the empty export directory was deleted, the app was stopped, and only the instrumentation package was uninstalled. After the later synthetic checks, the new test package was also removed. Final checks confirmed the report directory and test package were absent, the main app remained installed with its data preserved, and the camera service had no active clients. No installed-APK pulls were created.

Only ignored scalar reports/analyses were retained on the host. Production policy remains unchanged and automatic capture remains disabled. The confirmed limitation is that the old report loses the component responsible for a low framing score; the physical cause of this failed positive is still unresolved.
