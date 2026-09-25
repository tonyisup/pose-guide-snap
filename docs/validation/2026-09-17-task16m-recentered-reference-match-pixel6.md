# Task 16M recentered reference match — Pixel 6 — 17 September 2026

Result: **Repositioning substantially improved center alignment, but the sequence acquired no match lock.** All 97 frames detected and evaluated one person with 17 qualified landmarks and four torso anchors. Scale was the lower framing component throughout. Further blind repeats are not the next step: improve live alignment feedback first.

## Collection and artifacts

The participant confirmed readiness for the recentered meditation-pose test. Wireless ADB ran only `MatchCalibrationCollectionAndroidTest#collectOneAuthorizedFramingDiagnosticSequence`, with authorization `user-authorized-derived`, dataset `pixel6-match-bright-a`, sequence `positive-recentered-framing-bright-a`, fixture `positive`, case `centered-match`, warm-up `15000` ms and collection `10000` ms. The requested brief flash marked the settling period; the collector awaited torch-off before measurement. The accepted-person preflight passed.

| Artifact | Verified local and installed SHA-256 |
|---|---|
| Debug app | `8285ee02965cd7957beab44745c50cb3b198b8a92807530370fe29c5f22205bc` |
| Android test | `a6c28b202a9898250101a6a8ad926bde9e02986a4b502e0f01963e6b9591a8f7` |

Instrumentation passed **1/1 in 26.784 seconds**. This is collection success, not a successful pose match. Local report names were unused and the device export directory absent before launch. The main app and its data were preserved.

## Results

| Framing component | Minimum | Maximum | Requirement |
|---|---:|---:|---:|
| Center similarity | 0.787428 | 0.793429 | 0.800 |
| Scale similarity | 0.663030 | 0.668498 | 0.800 |

Center alignment improved substantially from Task 16L's `0.381554–0.386197`, though it remained just below the requirement. Scale now limited every recorded frame. Its symmetric score does not identify whether the participant should move closer or farther. These runs do not isolate lighting, placement, or pose as a single causal variable; Task 16M also uses the new warm-up flash artifacts.

| Acquisition criterion | Required | Minimum | Median | Maximum | Passing frames |
|---|---:|---:|---:|---:|---:|
| Landmark coverage | 0.750 | 0.851429 | 0.857493 | 0.889770 | 97/97 |
| Framing | 0.800 | 0.663030 | 0.665181 | 0.668498 | 0/97 |
| Angular similarity | 0.850 | 0.821275 | 0.824626 | 0.828724 | 0/97 |
| Positional similarity | 0.800 | 0.778748 | 0.786085 | 0.794806 | 0/97 |
| Overall match | 0.825 | 0.800322 | 0.805689 | 0.810925 | 0/97 |

Person score min/median/max: `0.606271` / `0.615926` / `0.643357`. The separate positive-only strict analysis produced zero locks and zero capture commands. No matching negative has run, so false-lock rate remains unavailable. No threshold changed; automatic capture remains disabled and calibration remains incomplete.

## Retained evidence and cleanup

Only derived scalar evidence was retained in the ignored `build/calibration/device/` directory. No images, raw landmarks, coordinates, or body extents were pulled.

| File | SHA-256 |
|---|---|
| `positive-recentered-framing-bright-a.json` | `bcfae36a5515e698937ee722d613481b8a8e037168423f08c58d9ad58becdfb5` |
| `positive-recentered-framing-bright-a-framing.txt` | `6e15b319e7d17df17835b28ed3bd35a59821c9befdf042cb9bdbc2e7a337945f` |
| `pixel6-match-bright-recentered-a-analysis.json` | `eca4784c7176c071711b374311d748e6f1e8015b3e53cbaf2bda27fad8ac4693` |
| `pixel6-match-bright-recentered-a-analysis.md` | `cdc0d4d48e727f2f51fa34f419bbb53a2064910c41fd64034788cd75a5b9754a` |

The pulled report matched the device-reported hash. Exact report/temp files and the empty export directory were removed, the main app force-stopped, and only the test package uninstalled. Final checks verified the unchanged installed main hash, absent export directory and test package, and an empty active camera client list. The first host camera assertion rejected a line break between the label and `[]`; a whitespace-normalized recheck passed. No camera client remained.

No source or APK changed during this checkpoint. Strict analysis and device cleanup passed; unchanged host build/test gates were not repeated. The next development step is a live target/alignment guide with verified preview-coordinate mapping and transient directional feedback, before another participant run. Matching negatives, repeated cases, and performance evidence remain pending.
