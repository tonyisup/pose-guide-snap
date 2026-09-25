# Task 16P — landscape spoken framing

**Status:** Completed after physical readiness. Instrumentation passed 1/1 in 33.107 seconds; all 95 frames had 17 qualified landmarks/four torso anchors and full evaluation. Center similarity 0.816952–0.834996 passed throughout; scale 0.677418–0.690808 and pose criteria failed, so no match lock occurred. Exact cleanup passed. Display rotation during the attempt was ROTATION_90. See the [Task 16P result](../../docs/validation/2026-09-25-task16p-landscape-spoken-pixel6.md). Before another run, establish which cue the user last heard and whether 15 seconds allowed time to follow it. Task 16O had stopped at readiness with an upright phone approximately 8 feet away. See the [attempt record](../../docs/validation/2026-09-25-task16o-spoken-readiness-pixel6.md).

Keep the rear lens facing the participant. Turn the phone sideways before launching the test, preserve approximately 8 feet of distance and good lighting, and aim to include the complete seated meditation pose. Do not rotate or reposition the phone during the hold. This tests whether landscape orientation supplies the missing vertical context; cropping remains a hypothesis. The participant cannot see the rear-camera screen, so spoken cues are essential.

## Completed unchanged-artifact run (do not reuse sequence ID)

- Verify Pixel 6/oriole and current wireless transport. Preserve main app/data.
- Main SHA-256: `20d54557f60328f279f5306ce1e8b19085d7b233c50c2c04cd40f4d891732d8d`.
- Test SHA-256: `836f9e645a045590c7db58d1258c36f4cb3d32ea1f4ae2cd9940a0ca0862400c`.
- Reinstall only the test APK with `-r`, verify installed hashes and camera permission.
- Require absent device export directory and unused host output names.
- Method: `com.tonyisup.poseguidesnap.calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedSpokenGuidedSequence`.
- Authorization: `user-authorized-derived`.
- Dataset: `pixel6-match-spoken-a`; sequence: `positive-spoken-landscape-bright-a`.
- Fixture: `positive`; case: `centered-match`; warm-up `15000`; duration `10000`.

The flash/voice begins settling. Follow spoken adjustments, wait for the hold instruction, and relax on the completion/stop announcement. Missing complete-body readiness aborts without measurement or a report; do not pull a nonexistent report. On successful collection, retain/hash-verify only the scalar report and framing summary and analyze separately using `pixel6-match-spoken-landscape-a-analysis` output names.

Clean up exact report/temp files if present and their empty directory; stop Pose Guide Snap and remove only its test package. Verify main hash/data preserved, export/test package absent and **no Pose Guide Snap camera client**. A separate user-opened camera app may remain active; do not force-stop it or claim global camera inactivity in that case.

No photos, screenshots, recordings or raw landmarks. No threshold changes, camera-facing changes or automatic-capture enablement. If this still cannot establish full-body readiness, investigate the specific missing evidence before another physical repeat.
