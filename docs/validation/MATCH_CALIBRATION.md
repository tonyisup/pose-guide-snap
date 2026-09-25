# Match calibration evidence

Status: **Detector separation passed in Tasks 16I–16J; Task 16K's reference-match positive never locked because framing failed every frame. Task 16L identified center alignment as the lower component; subject size also fails the existing framing requirement.**  
Date: 17 September 2026

## Decision

Automatic capture remains disabled. The repository has a privacy-bounded analyzer, a versioned derived-report contract, synthetic boundary fixtures, and a bounded Pixel 6 collector. Task 16H's full-body and empty-scene distributions overlapped. Task 16I reused those exact binaries in a better-lit location and detected one person with all 17 landmarks qualified in 96/96 frames. Person scores ranged from `0.724650` to `0.771101`, above the unchanged `0.25` detector gate. This establishes usable live pose evidence under the new shooting conditions, but does not isolate lighting as the cause or calibrate matching. Task 16J then collected the same-setup empty-scene control: 97/97 frames reported no person, with a maximum person score of `0.052640`. The pair clearly separates presence and absence around the existing `0.25` gate. Task 16K then measured an intended reference-match positive in a separate dataset: all 97 frames evaluated, but framing passed none and the replay never locked. Task 16L's scalar component diagnostic subsequently measured 97 evaluated frames: center similarity `0.381554–0.386197` was below scale similarity `0.691971–0.711420` throughout, and both failed `0.800`. A recentered repeat is prepared using the public reference placement; no closer/farther direction is inferred from the symmetric size score. Matching negatives, repeated cases, and sustained performance remain required; every threshold is still explicitly uncalibrated. See the [Task 16I record](2026-09-17-task16i-bright-light-pixel6.md) [Task 16J pair record](2026-09-17-task16j-bright-empty-scene-pixel6.md), and [Task 16K match record](2026-09-17-task16k-reference-match-pixel6.md).

## Data boundary

The analyzer accepts relative time, person count, named scalar match scores, mirror selection, and optional latency, cue, and capture-command observations. Schema v2 also requires a closed evaluation status, the total confidence-qualified MoveNet landmark count, and the qualified shoulder/hip anchor count. Schema v3 adds nullable maximum valid person/keypoint scores without a winning slot or keypoint identity. Its closed schema has no field for an image, image location, URI, absolute timestamp, raw landmark array, landmark identity, or coordinate. Unknown fields are rejected, and each input must declare provenance, authorization, population limits, and `containsPrivateImages: false`. Historical schema-v1/v2 reports remain readable; unevaluated frames cannot acquire or retain a replayed lock.

At runtime, framing is calculated from confidence-qualified MoveNet landmarks in the selected reference and current observation. It requires all four shoulder/hip torso anchors, at least one shared point in each of the head, left arm, right arm, left lower leg, and right lower leg regions, plus at least 13 of 17 shared points overall. The full qualified reference body defines the expected box while the live box uses shared identities, so missing extremities cannot shrink both sides of the comparison. It compares the two body-box centers and diagonal scales in image-normalized coordinates, and uses the lower similarity as the framing score so one component cannot conceal failure in the other. The retained `FramingEvidence` contains only status, a point count, and scalar scores; unavailable evidence produces zero scores and fails closed.

The checked-in `synthetic-contract-v1` dataset is hand-authored numerical data. It exercises inclusive gates, acquire dwell, release hysteresis, person-count rejection, individual score-gate failures, transient matches, and visible false-lock reporting. It cannot describe matcher accuracy.

Task 16B adds the bounded producer. Its explicit method requires the `user-authorized-derived` marker, lowercase pseudonymous identifiers, a positive or negative fixture label, a 3–60 second preparation budget (spoken guidance requires 60 seconds as its hard readiness deadline, with a 30-second minimum allowance as of Task 16AA), and a 5–30 second collection. It asserts Pixel 6 (`oriole`) hardware and pre-granted camera permission, reuses the full-screen production CameraX crop and bundled public meditation reference, and converts each temporary observation immediately into scalar evidence. The sequence contains at most 600 frames. It writes one synced deterministic report below backup-excluded app-private storage, hashes the bytes read back from disk, reports only non-sensitive summary fields, and removes stale or partial output. A successful file remains only for the authorized host pull and exact cleanup. Task 16C derives the three v2 diagnostic values inside that same callback before discarding the temporary observation.

## Policy under analysis

The policy fixture is marked `uncalibrated` and exactly mirrors the current Kotlin development defaults:

| Framing derivation setting | Value |
|---|---:|
| Minimum landmark confidence | 0.250 |
| Minimum shared landmarks | 13 of 17 |
| Required body regions | both shoulders and hips; head; left/right arm; left/right lower leg |
| Center error at zero similarity | 0.500 normalized coordinate units |
| Combined framing score | lower of center and scale similarity |

| Setting | Acquire | Retain lock |
|---|---:|---:|
| Minimum landmark coverage | 0.750 | 0.700 |
| Minimum framing score | 0.800 | 0.750 |
| Minimum angular similarity | 0.850 | 0.800 |
| Minimum positional similarity | 0.800 | 0.750 |
| Minimum overall match | 0.825 | 0.775 |
| Acquire dwell | 500 ms | — |
| Release hysteresis | — | 200 ms |

The JVM contract test compares every numeric value above to `FramingPolicy.developmentDefaults()`, `MatchPolicy.developmentDefaults()`, and `ShootTimingPolicy.uncalibratedDevelopmentDefaults()`. Behavioral tests freeze the required body-region contract.

## Synthetic contract result

The deterministic analyzer run processed 8 sequences and 21 frames:

| Metric | Result |
|---|---:|
| Positive sequence lock rate | 3/3 |
| Negative false-lock rate | 1/5 |
| Median time to first lock | 500 ms |
| P95 synthetic latency value | 36.0 ms |
| Duplicate-capture sequence rate | 0/8 |
| Negative false-capture sequences | 1 |
| Cue-emitting frame rate | 5/21 |

The one negative false lock and false capture are deliberate. That fixture supplies scores above every current threshold while its ground-truth class remains negative, proving the report exposes a separation failure instead of manufacturing a pass. The all-frame strict score margins are therefore negative. The synthetic latency and cue values test aggregation only and say nothing about device behavior.

## Verification

From the repository root:

```sh
python3 -m unittest discover -s tools/calibration -p 'test_*.py'
python3 tools/calibration/analyze_match_reports.py \
  --policy app/src/test/resources/calibration/development-policy-v1.json \
  --input app/src/test/resources/calibration/synthetic-contract-v1.json
./gradlew :app:testDebugUnitTest \
  --tests com.tonyisup.poseguidesnap.calibration.CalibrationPolicyFixtureTest --offline
```

The Python suite passes 14/14. The collector JVM coverage includes request/serialization, exact detector ground-truth labels, normalized nullable detector scores, and shared v2/v3 Kotlin/Python fixture bytes beside the existing framing and policy/resource coverage. Framing tests cover exact alignment, independent translation and scale behavior, confidence boundaries, missing required torso and body-region evidence, the earlier both-knees-and-ankles gap, invalid person counts, degenerate body extent, fail-closed evidence, and scalar-only immutable output. The analyzer rejects an injected raw `landmarks` field, a private-image marker, non-increasing relative timestamps, more than 600 frames, capture counts above three, invalid status/count or v3 detector-score values, unevaluated high-score lock replay, conflicting same-dataset fragments, framing-policy values outside the production contract, and an inverted acquire/release policy.

The Task 16C host gate passed 708/708 JVM tests, lint with zero errors and 9 warnings, and debug, unsigned-release, and Android-test APK assembly. Room schemas and packaged permissions remained unchanged. The first device-tested preflight correction produced Android-test hash `8b2d4e81440b11878bf0635c563d305985b25b600c3f385341a75088d726d4e4`. After that exact repeat failed before recording, an observability follow-up added live classification and aggregate-only failure evidence. Its exact Pixel invocation used Android-test hash `d80047d2ecc39a011c345c50e3e2b51f5cba32aea1e6126d5b40a23f3780135b`, classified all 146 warm-up frames as no-person, and failed safely before recording. Debug was `5cc37f30b245cd418e2852e890abcc34c33d29141734ff92fd8b4043c93119c4`, and unsigned release was `50437b7e4651446e374204edb6095a0ddd23803fcdf5b71c7e10b4112473a769`.

Task 16D adds one ephemeral diagnostic scalar: the strongest finite raw MoveNet instance score in `[0, 1]`. No person slot, raw tensor, landmark, or coordinate crosses the adapter. A failed preflight reports only the maximum score seen and the unchanged `0.25` acceptance threshold; neither enters the persisted report schema. The current host gate passes 709/709 JVM and 12/12 Python tests, zero lint errors and 9 warnings, and all APK assemblies. Frozen hashes are debug `16843f2b88f11116161ec81ecbf460b691eac97fa4b1123248dbff426852e787`, Android test `488eb4b615f74479d06824f29c711cbadaf24013778acd35a3cf1fbfdcddac7e`, and unsigned release `c7aae95d26bc295ab42f5dad7688814f4775df95cacf567fe668df7f3bc696ba`. Its separately authorized Pixel repeat analyzed 146 warm-up frames at about 9.7 frames per second, classified every frame as no-person, and observed a maximum valid raw score of exactly `0.0`. It failed before recording, wrote no report, and completed exact cleanup. This rules out a near-`0.25` miss and leaves the threshold unchanged.

Task 16E then ran the existing static MoveNet contract on that same frozen main/test pair. The exact public-fixture method passed 1/1 in 0.669 seconds, covering repeated one-person inference, generated zero/two-person controls, exact model and fixture hashes, all 17 identities, ownership, and cleanup. This localizes the unresolved failure beyond model packaging, static bitmap preprocessing, LiteRT inference, and mapping. The next diagnostic should measure only aggregate statistics from the transient CameraX-converted bitmap before inference. See the [Task 16E Pixel record](2026-09-08-task16e-static-movenet-recheck-pixel6.md).

Task 16F implements that next bounded diagnostic. It samples a fixed at-most-16×16 grid from the already-owned upright crop before inference and immediately reduces the samples to mean luminance and luminance range in `[0, 1]`. Failed preflight retains only minimum and maximum frame-mean luminance and maximum within-frame range. No pixel, bitmap, coordinate, histogram, or identifier crosses the boundary, and the values are absent from the persisted report schema. Its separately authorized Pixel repeat verified debug `a47f4588346abdbd7d569d2a02de38a29c12e41abfc5a169e8d8eceee3f52d94` and Android test `d04404d44440ff165ab6f74b341026dd8900531d115387472316e1970d68ae98`, then failed before recording with 146/146 no-person frames. The person score remained `0.0`, frame-mean luminance ranged from about `0.401` to `0.642`, and maximum within-frame range was about `0.373`. The live bitmap was neither black nor flat; no report was written and exact cleanup completed. See the [Task 16F Pixel record](2026-09-08-task16f-camera-bitmap-statistics-pixel6.md).

Task 16G reduces all six-slot, 17-keypoint score values to one maximum finite scalar in `[0, 1]`, without exposing which slot or keypoint produced it. Its first Pixel candidate incorrectly traversed through bounding-box index 53; the reported maximum `1.0` is invalid as keypoint evidence. The corrected traversal ends at keypoint index 50, and a causal regression sets all bounding-box values to `1.0` while preserving a lower keypoint maximum. A transient corrected startup attempt produced no evidence and motivated bounded failure classification. The final exact repeat verified debug `28c3820a2780fb744c37d056b4742eb71eab9249c9947deb411c4089c6e57dcd` and test `d69892e64e8f9041f098ea3c249918c37037d54ba72ab916777a0fd02c2e4808`, then analyzed 145 warm-up frames. All were below the unchanged `0.25` person gate, while maximum person and keypoint scores reached `0.14585432410240173` and `0.20261988043785095`. This is valid partial pose evidence, not a calibrated threshold. The method wrote no report and exact cleanup completed. See the [Task 16G Pixel record](2026-09-08-task16g-keypoint-score-diagnostic-pixel6.md).

Task 16H advances the derived report to schema v3 by adding only nullable maximum valid person and keypoint scores per frame. The analyzer remains compatible with v1/v2, validates both new fields in `[0, 1]`, and reports positive/negative p05, median, p95, extrema, and p05-minus-negative-p95 separation. A separately selectable detector-gate method records below-threshold frames after bounded warm-up and restricts ground truth to a full-body single-person positive or empty-scene negative. It does not change model input, production thresholds, matching, or capture behavior. The host gate passes 714/714 JVM and 14/14 Python tests with zero lint errors and 14 warnings. Frozen hashes are debug `c5360fce747727d3604b58b8be92a4ed9807b9cdbc3b98529cce95c509b29e66`, test `9b399b37bf075a9b25a9e3bad1d8cca7c6abd97e2dd2a0c432729dc0e79375e9`, and unsigned release `996b45a89d91c6aac51cb6dac92900dcd32d32557aef2118832b2741520a21ae`.

The exact Task 16H Pixel 6 full-body positive and empty-scene negative invocations each passed 1/1 with 97 frames. Device/host report hashes matched. Positive person-score p05/median/p95 were `0.000`/`0.000`/`0.117226`, while negative p95 was `0.122414`; positive keypoint p05/median/p95 were `0.103079`/`0.123211`/`0.147787`, while negative p95 was `0.150982`. Both sequences were `no-person` throughout. These overlapping distributions reject a threshold change under this condition. Exact cleanup completed; see the [Task 16H Pixel record](2026-09-08-task16h-detector-gate-calibration-pixel6.md).

## Task 16B bounded Pixel result

The frozen Task 16B debug hash `339f7597960a959f53fa79b168c838e4b25e325ea814e5d3f7d3bce83afc7d76` and Android-test hash `e75d82674b8ee361075f1872cde37eb4585eca37470cb19999899f75c5025e5d` received separate exact authorization. The positive and negative invocations each passed with 97 frames, and both device-reported report hashes matched the pulled host files. Exact device cleanup and instrumentation-package removal completed.

The combined replay reported 0/1 positive locks, 0/1 negative false locks, and zero capture commands. Every positive frame had exactly one detected person and all five scores at zero; under the frozen branch structure this means all 97 frames failed canonicalization. All 97 negative frames were evaluated. Their coverage ranged from 0.934 to 0.973, while framing, angular, positional, and overall scores all remained below acquisition. No numeric threshold can repair missing canonicalization evidence, so no threshold changed.

The first combined analyzer attempt also exposed a host bug: one-sequence fragments with the same dataset ID were rejected. The analyzer now merges them only when all metadata matches exactly and sequence IDs are unique. The [Task 16B Pixel record](2026-09-08-task16b-derived-calibration-pixel6.md) contains the exact method scope, report hashes, aggregate scores, evidence limits, and cleanup.

## Task 16C diagnostic result

The separately authorized frozen Task 16C pair matched its installed bytes. The exact `positive-centered-diagnostic-a` method passed 1/1 in 16.877 seconds and produced 97 frames. The device report hash `ee382186394c812da0a81cc32663d2afbc027d0f178d1b4a084906d83a7c50d2` matched the pulled ignored host file.

All 97 frames were `no-person`, with minimum/median/maximum qualified landmark and torso-anchor counts of zero. The sequence did not lock or emit a capture command. This proves schema v2 disambiguates `no-person` from Task 16B's inferred `canonicalization-failed`, despite both producing zero scores. It does not diagnose the earlier failure because it did not reproduce one-person detection.

Exact device report cleanup, camera-client disconnect, temporary APK cleanup, and test-package removal completed. The main app and unrelated data remain. The hashes, exact method arguments, aggregate result, evidence limits, and cleanup are recorded in the [Task 16C Pixel record](2026-09-08-task16c-canonicalization-diagnostic-pixel6.md).

The no-person run exposed a collector usability defect: the method could successfully record an entire window without its intended participant. The corrected Android-test APK requires the warm-up to end with at least five consecutive exactly-one-person frames. If that check fails, it writes no report and closes the camera. It deliberately does not gate on torso anchors, allowing the diagnostic to measure missing confidence-qualified anchors during collection.

A useful repeat requires fresh exact authorization and should use `positive-centered-diagnostic-b` with the corrected Android-test hash only after the participant confirms their entire body is visible in the displayed rear-camera preview throughout warm-up and collection.

## Evidence still required

A threshold candidate still requires separable positive evidence plus bounded negative examples on the target artifact and Pixel 6. The next discriminator is a same-artifact full-body positive repeat under bright, even illumination; if that still overlaps the empty-scene evidence, investigation returns to the transient CameraX geometry/color path. It cannot calibrate a threshold by itself. Any later calibration collection must document pose classes, required visible body regions, viewpoint, lighting, occlusion, mirror policy, subject count, repetition count, and every false-lock case. Source photos and raw landmarks must remain outside the repository.

After a candidate is selected, boundary fixtures must freeze each chosen gate and the acquire/release timing. The same candidate must then pass the complete production sequence and the sustained performance gate recorded in [PERFORMANCE.md](PERFORMANCE.md). Current evidence supports the harness, bounded collector execution, and a failed-closed diagnosis only; it does not support a device-calibrated policy, automatic capture, population accuracy, or a shipping claim.

## Task 16M recentered repeat — 17 September 2026

The updated flash-cue pair collected 97 fully evaluated frames. Center similarity improved to 0.787428–0.793429; scale 0.663030–0.668498 became the limiting framing component. Neither met 0.800, and angular, positional, and overall criteria passed no frames. No lock or capture occurred. Cleanup and strict scalar analysis passed. Improve live alignment feedback before further participant repeats; thresholds remain unchanged and matching calibration remains incomplete. See the [Task 16M record](2026-09-17-task16m-recentered-reference-match-pixel6.md).

## Task 16N guide implementation — 25 September 2026

Live guidance and a reference-aspect viewport are implemented for a new opt-in positive collection method. Host verification and both synthetic Pixel screen tests pass; the first guided camera check collected 96 one-person frames but no evaluable pose because only 8–10 landmarks and two torso anchors qualified. See the [camera result](2026-09-25-task16n-guided-framing-pixel6.md). Use the separate `pixel6-match-guided-a` dataset because the viewport and feedback protocol changed. The guided report remains separate from prior portrait observations and does not supply usable center/scale evidence. See the [implementation record](2026-09-25-task16n-alignment-guide.md).

## Task 16O spoken follow-up — 25 September 2026

The user could not see visual guidance while facing the rear lens. Spoken calibration cues and a five-frame complete-body readiness gate are implemented; 728/728 host checks and 1/1 Pixel audio-only check pass. The first spoken camera attempt stopped at complete-body readiness with no report. A landscape-orientation check at the participant-reported approximately 8 feet is prepared. See the [attempt record](2026-09-25-task16o-spoken-readiness-pixel6.md). Use the separate `pixel6-match-spoken-a` dataset for the changed feedback/readiness protocol. See the [speech record](2026-09-25-task16o-spoken-guidance.md).

## Task 16P landscape spoken result — 25 September 2026

The landscape run on unchanged speech artifacts passed 1/1 with 95 fully evaluated frames. Center similarity passed 0.800 throughout; scale 0.677418–0.690808 and angular/positional/overall criteria failed. No lock or capture occurred. Report verification, strict separate analysis and cleanup passed. The participant reported insufficient time between adjustments; Task 16Q extends settling and measures pauses from speech completion. See the [landscape result](2026-09-25-task16p-landscape-spoken-pixel6.md).

## Task 16Q — slower spoken preparation

The next spoken protocol uses at least 30 seconds, eight quiet seconds after each completed instruction and no new advice in the final ten seconds. Camera verification is pending physical readiness. See the [timing record](2026-09-25-task16q-settling-time.md).

Task 16R adds one spoken “Good” after a requested adjustment is confirmed for one second, preserving eight quiet seconds afterward. Host checks pass 732/732; the [camera attempt](2026-09-25-task16r-confirmation-pixel6.md) stopped at complete-body readiness before measurement, with cleanup complete. The [confirmation record](2026-09-25-task16r-adjustment-confirmation.md) has the next protocol and artifact hashes.

The user-requested Task 16S repeat on unchanged artifacts passed collection with 91/91 fully evaluated frames and passing angular similarity. Framing and positional/overall criteria still prevented a lock. Cleanup passed. Solo camera-adjustment timing/process is deferred at the user’s request. See the [repeat result](2026-09-25-task16s-adjusted-framing-pixel6.md).

Task 16T adds reference-specific arm coaching before fine framing and confirms each corrected arm with “Good.” Host checks pass 740/740; physical verification is pending. Participant testimony supersedes Task 16S’s positive label: arms intentionally differed, so its corrected analysis is a negative example with zero false locks, not positive-match evidence. See the [arm-coaching and ground-truth record](2026-09-25-task16t-arm-coaching.md).

Task 16U fixes the preview startup race that stopped the first arm-guidance attempt before camera/speech initialization. Both camera-free native regressions pass; physical arm-coaching verification is still pending. See the [startup fix and retry protocol](2026-09-25-task16u-preview-startup.md).

Task 16V completed the preview-fixed arm-guidance collection with 97 fully evaluated frames. Completed cues: LEFT_ARM=1, GOOD=0; no match lock. Cleanup passed. Arm correction and final positive ground truth remain unconfirmed pending participant feedback; this is not a coaching success or accuracy result. See the [arm-guidance retry](2026-09-25-task16v-arm-guidance-pixel6.md).

Task 16W adds bounded confirmation diagnostics after the participant reported completing the left-hand adjustment without hearing “Good.” It measures eligible elbow/wrist error, evidence gaps and remaining time without changing guidance rules. Host checks pass 745/745; physical cause remains unconfirmed. See the [diagnostic record](2026-09-25-task16w-arm-confirmation-diagnostics.md).

Task 16X isolated the missing confirmation: both elbow/wrist errors stayed outside tolerance despite 12.985 seconds after speech and continuous tracking. The unlocked retry collected 96 fully evaluated frames and cleaned up successfully; no Good or match lock occurred. This is a diagnosed rejection condition, not a completed coaching fix. See the [diagnostic result](2026-09-25-task16x-arm-confirmation-pixel6.md).

Task 16Y adds participant-relative elbow/hand directions and confirmation of the requested joint, with 752/752 host tests passing. The first live run collected 96 fully evaluated frames: LEFT_WRIST_UP=1, GOOD=0. The wrist entered tolerance for at most 200 ms, below the one-second confirmation requirement. Cleanup passed; successful physical confirmation and participant feedback remain pending. See the [directional coaching record](2026-09-25-task16y-directional-arm-guidance.md) and [Pixel result](2026-09-25-task16y-directional-arm-pixel6.md).

Task 16Z responds to the participant's clarification that “slightly” prompted a quick reversal. Arm directions now end with “then hold it there,” and spoken preparation lasts at least 60 seconds to allow follow-up while retaining eight-second pauses. All 754 host tests and five camera-free Pixel timer checks pass. The verified update is installed; physical confirmation remains pending Ready. See the [coaching follow-up record](2026-09-25-task16z-coaching-followup.md).

Task 16AA makes coaching progression depend on fresh stillness as well as target position. Follow-up directions retain the eight-second pause. After a 30-second initial allowance, resolved and settled guidance can start the hold; the 60-second deadline instead stops unresolved preparation with an explanation. All 766 host tests and six camera-free Pixel timing checks pass. The first camera run completed Good once for the left wrist, then stopped at the readiness deadline without hold or measurement. Cleanup passed; the participant later confirmed hearing Good while following the cues into a hovering hand position. See the [Pixel result](2026-09-25-task16aa-movement-guided-pixel6.md). See the [movement-aware coaching record](2026-09-25-task16aa-movement-aware-coaching.md).

Task 16AB makes the supported resting position explicit: wrist cues name the same-side knee and palm-up rest, and elbow cues preserve hand support. A closed preparation result/reason summary identifies any remaining readiness blocker without private motion samples. All 768 host tests pass. The first supported-hand run repeated the right-hand cue three times with no Good and stopped before measurement; terminal stillness passed, while arm position and framing failed. Cleanup passed. The participant reported the hand already on the knee and no clear way to adjust, prompting Task 16AC. See the [supported-hand coaching record](2026-09-25-task16ab-supported-hand-coaching.md).

Task 16AC aligns the hand check with its instruction: each wrist now targets the participant's own knee in the camera view, while elbows retain reference targets. An unresolved hand receives one explanation instead of repeated identical commands; settling, confirmation and the stop deadline remain enforced. All 774 host tests pass. The first physical run completed the hand cue and explanation once each but still stopped before measurement. The participant confirmed the hand stayed supported and the explanation was clear. Cleanup passed; Task 16AD prepares a controlled knee-versus-lap comparison. See the [knee-relative coaching record](2026-09-25-task16ac-knee-relative-coaching.md).

Task 16AD prepares a separate spoken hand-position comparison: supported knees, then hands in lap, with 30/15-second adjustment allowances and two 10-second measurements. It retains only bounded scalar summaries and requires fresh stillness; coaching thresholds stay unchanged. All 784 host tests pass, both APKs build, and the final main APK is installed/hash verified. The physical comparison is pending fresh Ready. See the [comparison protocol](2026-09-25-task16ad-hand-placement-comparison.md).
