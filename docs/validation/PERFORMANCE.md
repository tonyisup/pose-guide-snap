# Sustained performance evidence

Status: **current-artifact 15-minute gate pending**  
Updated: 6 September 2026

Task 16 requires a sustained 15-minute camera-analysis session on the target Pixel 6 after a match-policy candidate exists. The run must record inference latency, received/accepted/dropped frame counts, allocations and garbage collection pressure, bounded memory, CPU, battery temperature and change, Android thermal status, camera-analysis resource cleanup, cue rate, time to lock, false locks, and duplicate capture commands. It must use the exact app artifact evaluated by the calibration report.

The earlier Task 10 candidate has useful baseline evidence but cannot satisfy this gate for the current tree. Its final 60-second run reported 61 frames received, 21 accepted, 40 cadence skips, generated-black inference p50 124.79 ms and p95 126.53 ms, mean CPU 148.08%, bounded post-warm memory, battery temperature 31.0°C to 30.8°C, thermal status 0, no fatal error or ANR, and camera release in the observed 400–500 ms window. The integrated UI did not improve CPU over its earlier baseline. The exact artifact and limits are recorded in [the testing contract](../TESTING.md#live-device-evidence).

No current Task 15A or Task 16A 15-minute soak has run. Automatic capture, sustained-performance, thermal, and battery claims therefore remain open.
