# Task 16U — preview startup race

## Failed Task 16T physical attempt

After physical Ready, `collectOneAuthorizedSpokenGuidedSequence` attempted dataset `pixel6-match-arm-guided-a`, sequence `positive-arm-guided-landscape-a`, fixture `positive`, case `centered-match`, preparation `30000` ms and measurement `10000` ms. Pixel/oriole, granted permission, exact local/installed hashes, absent export and unused output name were verified.

Main SHA-256: `544745b1c6d7c16ca26eb2dd5b5739fb82b1b92def45a6ae12941cb58f2ed5c1`. Attempted test SHA-256: `96152009d1f892a71f7867b906fcda0effd6762b93ffee4e4673453e57d9f4be`.

The test failed in **0.680 seconds**, before camera binding, speech initialization, flash, warmup or measurement: `failureStage=preview failureTypes=viewport-unavailable`. No report or cue counts were produced. This is unrelated to participant body readiness and does not test arm coaching. Exact export cleanup, test-package removal and main-app stop succeeded; no Pose Guide Snap camera client remained and app/data were preserved. See the [failed output](2026-09-25-task16t-camera-output.txt). Do not reuse the attempted sequence identifier.

## Cause and correction

A `preview.post` callback ran before the preview had usable attachment/dimensions. Android's [PreviewView API contract](https://developer.android.com/reference/androidx/camera/view/PreviewView#getViewPort()) permits a null viewport when unattached or zero-sized. Posting work does not establish those preconditions.

The instrumentation collector now uses `CalibrationPreviewReadiness`, a main-thread one-shot gate observing attachment and layout. It binds only when the preview is attached, dimensions are positive, display is available and the real viewport is non-null. It removes listeners before binding. Final cleanup cancels the gate before closing the camera, preventing a later layout from initiating a bind. The existing 30-second camera-ready timeout bounds the wait. Production code, arm-guidance logic, participant timing, match thresholds and report schema are unchanged.

## Verification

The updated Android-test APK assembled and lint passed (zero errors, nine warnings). No main or host-domain code changed; the unchanged main artifact retains the Task 16T 740/740 host verification.

Two native camera-free regressions passed **2/2 in 2.780 seconds** on the Pixel. A real attached zero-sized PreviewView reproduces null viewport even after a posted runnable; resizing then invokes readiness exactly once. Cancellation prevents a later usable layout from invoking the callback. These tests instantiate no camera controller and do not open a camera. See [native output](2026-09-25-task16u-preview-pixel6-output.txt).

- Main APK remains `544745b1c6d7c16ca26eb2dd5b5739fb82b1b92def45a6ae12941cb58f2ed5c1`.
- Updated test APK, installed hash verified: `17e971b59e461317086757a05d4fc375514761f36ee8ee997a28467d74337043`.
- Test package removed after verification, main/data preserved, and no Pose Guide Snap camera client active.

## Next physical retry

The participant was told to relax during the fix. Require fresh physical Ready. Follow the [arm-coaching protocol](2026-09-25-task16t-arm-coaching.md), substituting the updated test hash and unused sequence `positive-arm-guided-landscape-b` in dataset `pixel6-match-arm-guided-a`. Reinstall and verify the test package before collection. Keep the same sideways setup, left hand initially in lap, then follow the instruction. Save scalar evidence and fixed completed-cue counts only. Ask about the requested correction and whether “Good” was heard; confirm final pose intent before accepting the positive label for analysis. Perform exact cleanup afterward. This startup verification does not replace a successful physical arm-guidance run.
