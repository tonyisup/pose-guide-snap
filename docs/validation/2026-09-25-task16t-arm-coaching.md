# Task 16T — arm coaching and corrected ground truth

The participant clarified that their arms intentionally differed from the meditation reference during Task 16S and that the voice never asked them to adjust their arms. Development therefore moves to arm-specific pose guidance. Solo camera-adjustment timing/process remains deferred at their request.

## Corrected interpretation of Task 16S

The original report was labeled `positive` / `centered-match` before that clarification. Those labels are superseded for accuracy analysis. Preserve the original report and its device hash (`162304dae2e472ba57659126231c97e7ccc6a044eecd3504780191cc8f7ec3de`) unchanged as an audit artifact; do not include it as a positive in calibration aggregates. The original positive analysis is also superseded.

A separate scalar-only copy has corrected dataset `pixel6-match-arm-mismatch-a`, sequence `negative-arms-differ-landscape-a`, fixture `negative`, and case `different-arm-pose`. Every parsed frame is unchanged from the original; only ground-truth identifiers, labels and provenance/population-limit text differ. The correction uses participant testimony about Task 16S only; it does not relabel earlier runs by assumption. Reanalysis produces 0/1 negative false locks and no positive sequences. This is not evidence of isolated arm sensitivity: framing and positional/overall criteria also failed. The aggregate angular score passed despite intentionally different arms, showing why it cannot stand in for per-arm coaching evidence.

Corrected files are ignored under `build/calibration/device/`:

| File | SHA-256 |
|---|---|
| `negative-arms-differ-landscape-a.json` | `615d40422ac13036980d6a54b474f45e564b30ff6ebb3fd7ea71310b42d41603` |
| `pixel6-arm-mismatch-corrected-analysis.json` | `13f235b78c68068e088fc76cdd8a9ebe0b6a18349a4bcd91fa79b20d0a64e948` |
| `pixel6-arm-mismatch-corrected-analysis.md` | `cbfec6fb65043310730cb9dfcf1c527a4b5011e33ce3115d55fb151de02f56a9` |

## Implemented behavior

The existing debug guide now compares each visible elbow and wrist with the bundled reference in aspect-corrected, torso-normalized coordinates. Both allowed reference orientations are considered; live landmarks retain anatomical identities so left/right speech always names the participant's arm. Per-arm error is the larger elbow/wrist displacement. The temporary coaching tolerance is 0.25 torso lengths; it is uncalibrated and does not change production match/capture thresholds.

Once compatible full-body evidence is available, a misplaced arm takes priority over fine framing. The voice asks the participant to place their left or right hand just above the corresponding knee, with their elbow beside their waist. This wording is specific to the bundled meditation reference, not a general pose coach. If both arms differ, the larger error is selected initially, and that arm stays the focus until corrected. No palm/finger/depth correction is inferred from MoveNet's unsupported landmarks.

“Good” requires one second of fresh evidence that the requested arm is within tolerance. Correcting the other arm cannot satisfy it. The next instruction still waits eight seconds after completion. Missing body evidence, multiple people and incompatible geometry take precedence. A matched arm is not a whole-pose match. The generic aligned message now says only “Your framing is aligned.” Preparation remains at least 30 seconds, new adjustments stop in the final ten seconds, and timed measurement behavior is unchanged.

The instrumentation output now includes bounded counts by fixed cue enum after shutdown, incremented only by successful utterance completion. No transcript, landmarks, images or coordinates are retained. Counts distinguish completed playback from the participant's confirmation of hearing/understanding it; they cannot prove audibility. The scalar report schema is unchanged.

## Verification and artifact boundary

- Host tests: **740/740**, zero failures/errors/skips, including eight new arm-guidance tests.
- Debug app/test assembly passed; lint: zero errors, nine warnings.
- New regressions cover the reference/mirror, translation/distance invariance, individual elbow/wrist mismatches on both sides, anatomical side under mirroring, missing/low-confidence evidence, invalid geometry/person count, arm priority with failed framing, one-arm confirmation and eight-second pause, and retaining the requested arm when the other becomes worse.
- Main APK SHA-256: `544745b1c6d7c16ca26eb2dd5b5739fb82b1b92def45a6ae12941cb58f2ed5c1`.
- Instrumentation APK SHA-256: `96152009d1f892a71f7867b906fcda0effd6762b93ffee4e4673453e57d9f4be`.
- The subsequent authorized attempt stopped before camera binding or voice initialization due to a preview startup race. The [startup fix](2026-09-25-task16u-preview-startup.md) passed camera-free native checks and supplies the updated test hash and retry identifier. Physical coaching tolerance, wording and audibility remain unverified. Production coaching/audio acceptance and automatic capture remain incomplete.

The new main APK is installed on the paired Pixel, and its installed hash matches the recorded build. App data are preserved; the test package and export directory are absent, and no Pose Guide Snap camera client is active.

## Original attempted protocol (superseded by the Task 16U retry)

Keep the same sideways rear-camera setup and framing. Start with the right arm approximately matching the reference and the left hand in the lap, then follow the spoken left-arm instruction. The objective is to verify arm advice followed by “Good,” with completed-cue counts and participant feedback. Use the explicit spoken collector, authorization `user-authorized-derived`, dataset `pixel6-match-arm-guided-a`, unused sequence `positive-arm-guided-landscape-a`, fixture `positive` (intended final pose), case `centered-match`, preparation `30000` ms, collection `10000` ms. Verify these local/installed hashes, camera permission, absent export and unused host names first. Do not treat that requested positive label as confirmed ground truth unless the participant says they attempted the full final reference pose; correct the analysis label if they intentionally retain a different pose.

Reinstall the test APK only after readiness and retain only the scalar report, framing summary and fixed completed-cue counts. Analyze separately. Remove the exact report/temp files and empty export directory, stop the main app, remove only the test package, and verify no Pose Guide Snap camera client while preserving main/data. Do not ask for a calibration accuracy conclusion from one usability trial.
