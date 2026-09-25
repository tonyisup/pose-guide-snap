# Match calibration tools

This directory analyzes derived match evidence without accepting images, image locations, URIs, or raw landmark arrays. The input schema is intentionally narrow and rejects unknown fields. Every dataset must state its provenance, authorization class, and population limits, and must set `containsPrivateImages` to `false`.

The bundled `synthetic-contract-v1` data tests arithmetic and failure reporting. It is not detector evidence and cannot justify a production threshold.

Run the deterministic contract analysis from the repository root:

```sh
python3 tools/calibration/analyze_match_reports.py \
  --policy app/src/test/resources/calibration/development-policy-v1.json \
  --input app/src/test/resources/calibration/synthetic-contract-v1.json \
  --json-output build/calibration/synthetic-contract-v1-analysis.json \
  --markdown-output build/calibration/synthetic-contract-v1-analysis.md
```

Run the analyzer tests:

```sh
python3 -m unittest discover -s tools/calibration -p 'test_*.py'
```

## Derived-report schema

Each dataset contains ordered sequences. A frame carries only:

- relative elapsed time;
- detected person count;
- in schema version 2, a closed evaluation status plus the total confidence-qualified MoveNet landmark count and qualified shoulder/hip anchor count;
- in schema version 3, nullable maximum valid person and keypoint scores in `[0, 1]`, with no winning slot or keypoint identity;
- coverage, framing, angular, positional, and overall scalar scores;
- whether the selected candidate was mirrored;
- optional inference latency, cue-emitted, and capture-command observations.

The analyzer replays the current inclusive acquisition gates, lower lock-retention gates, acquire dwell, and release hysteresis. It rejects any policy whose release threshold exceeds its matching acquisition threshold. It reports positive lock rate, negative false-lock rate, time to first lock, score separation, latency, cue rate, false captures, and duplicate capture commands. Actual capture counts remain input evidence; the tool never fabricates them from a lock transition.

The analyzer remains compatible with schema versions 1 and 2. Version 2 makes diagnostic status and counts mandatory so an unevaluated frame cannot acquire or retain a replayed lock even if malformed input supplies high scores. Version 3 adds the two detector-score maxima and reports positive/negative p05, median, p95, extrema, and p05-minus-negative-p95 separation. Each sequence is capped at 600 frames, and each frame's capture-command observation is capped at three. The checked-in `derived-collector-contract-v3` fixture is serialized byte-for-byte by the Kotlin collector contract and parsed by the Python tests, keeping the device producer and offline consumer synchronized.

Multiple input files may carry unique sequences for one dataset ID, which supports the collector's one-sequence fixed output. The analyzer merges those fragments only when provenance, authorization, population limits, and privacy metadata match exactly; it rejects conflicting fragments and duplicate sequence IDs.

For authorized device data, export only these derived fields and use pseudonymous sequence IDs. Keep source photos, absolute timestamps, raw landmarks, paths, and URIs outside the repository. A dataset marked `user-authorized-derived` must correspond to the exact bounded collection the user authorized. Record the device, app artifact, pose classes, lighting/viewpoint limits, and collection procedure in a separate validation record before considering a policy `device-calibrated`.

The tool measures evidence but does not choose thresholds automatically. Detector-gate collection is restricted to a labeled full-body single-person case or an empty scene; a wrong-pose sequence still contains a person and is not valid negative person-detection evidence. Threshold selection must use repeated positive and negative sequences, preserve every mandatory gate, and document false-lock cases and dataset limits. Automatic capture stays disabled until that review and the required hardware acceptance checks pass.
