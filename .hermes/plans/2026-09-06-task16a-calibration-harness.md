# Task 16A — privacy-bounded match calibration harness

**Status:** Host verified; authorized device dataset and 15-minute performance evidence remain pending.

## Objective

Make future threshold decisions reproducible without committing private photos or raw landmarks, while preserving the current automatic-capture lockout.

## Implemented slice

1. Define a closed versioned JSON contract for derived scalar frame reports and explicit dataset authorization/population limits.
2. Reject unknown fields, private-image markers, invalid scalar ranges, and non-increasing relative times.
3. Derive framing from independent subject-center and body-scale evidence, requiring shared confidence-qualified head, bilateral arm/lower-leg, and torso coverage; compare against the full qualified reference extent so missing extremities cannot disappear from both boxes.
4. Model stricter acquisition gates and lower lock-retention gates, then replay them with acquire dwell and release hysteresis.
5. Report score separation, false locks, time to lock, inference latency, cue rate, false captures, and duplicate capture commands.
6. Add synthetic positive, boundary, failure, transient-match, and deliberate false-lock fixtures.
7. Tie the JSON development policy exactly to the Kotlin framing, matcher, and reducer defaults.
8. Record the harness evidence and the unpassed device/performance boundary.

## Invariants

- The repository receives no private photos, paths, URIs, absolute timestamps, or raw landmark arrays.
- Synthetic fixtures cannot change production thresholds or authorize automatic capture.
- Actual capture commands are measured input evidence; the analyzer does not infer them from lock transitions.
- Every mandatory matcher gate remains independent.
- A real calibration report states its limited dataset and cannot imply population-wide accuracy.

## Verification

- Python analyzer tests: 6/6.
- Focused JVM framing and policy/resource contracts: 8/8.
- Full host gate: 700/700 tests, zero lint errors, and all APK assemblies passed; schema hashes and packaged permissions stayed unchanged.
