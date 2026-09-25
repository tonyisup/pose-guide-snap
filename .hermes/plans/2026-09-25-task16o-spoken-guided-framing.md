# Task 16O — spoken guided framing

**Status:** Implemented; build/lint, 728/728 JVM checks and the exact 1/1 Pixel audio check pass. Main app/data preserved; test package removed and camera closed. The participant confirmed the audio was clearly audible. The physically ready camera attempt then failed complete-body readiness (1 failure in 22.796 seconds), announced a stop and produced no report. Test cleanup completed. The user reported upright orientation at about 8 feet; the next check is the [Task 16P landscape setup](2026-09-25-task16p-landscape-spoken-framing.md), pending fresh physical readiness. See the [record](../../docs/validation/2026-09-25-task16o-spoken-guidance.md).

The user cannot see the screen while facing the rear lens. Do not ask them to follow visual outlines or flip to the front camera. Spoken horizontal cues assume they face the rear lens. Review the public meditation pose before positioning the phone. Keep the full seated body visible, preserve good lighting, and keep the current audio route audible. No more visual-only repeats.

## Completed attempt (do not reuse sequence ID)

- Verify Pixel 6/oriole and current wireless transport; use existing pairing when available.
- Main SHA-256: `20d54557f60328f279f5306ce1e8b19085d7b233c50c2c04cd40f4d891732d8d`.
- Test SHA-256: `836f9e645a045590c7db58d1258c36f4cb3d32ea1f4ae2cd9940a0ca0862400c`.
- Reinstall test with `-r`; preserve main/data; verify installed hashes.
- Require unused host report names and absent device export directory.
- Select only `com.tonyisup.poseguidesnap.calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`.
- Authorization `user-authorized-derived`; dataset `pixel6-match-spoken-a`; sequence `positive-spoken-framing-bright-a`; fixture `positive`; case `centered-match`.
- `warmupMs=15000`; `durationMs=10000`.

The flash and voice mark the settling start. Follow spoken adjustments, wait for “Hold still,” and keep the pose until “Test finished.” If complete-body readiness is missing at the warm-up deadline, the test announces a stop instead of collecting an unusable match sequence. A failed preflight produces no report; do not try to pull a nonexistent file or describe the absence as data loss. Live framing/pose similarity can still fail even after complete-body readiness.

Retain only the scalar report and framing summary if produced. Verify the report digest and analyze this new speech/readiness protocol separately from visual-only runs, using `pixel6-match-spoken-positive-a-analysis` output names. No photographs, recordings, screenshots or raw landmarks.

Always clean up exact report/temp files if present and the empty export directory, stop the app, remove only the test package, and verify main/data preserved and no active camera client. No further camera run is authorized merely by an “audio was audible” reply; obtain physical readiness as normal. Do not change thresholds or enable automatic capture based on this run.
