# Task 16T — arm coaching

Implementation, 740/740 host tests, debug builds and lint are complete. Follow the [Task 16T artifact/protocol record](../../docs/validation/2026-09-25-task16t-arm-coaching.md) for the next physically ready participant trial. No camera run is authorized yet by this development-direction request. The new main build is installed and its hash verified on the paired phone; test installation occurs before the next authorized run.

Prior Task 16S ground truth was corrected in a separate copy preserving the original source after the participant said their arms intentionally differed. Do not aggregate its original positive-labeled report or superseded analysis. Earlier runs retain their labels because the clarification did not establish their ground truth.

Keep solo camera-adjustment timing/process deferred. Test arm instruction and matching “Good” with completed-cue counts plus participant feedback, before expanding to other body parts or production coaching. Main/data are preserved and no threshold is weakened.

The first physical attempt failed before camera binding or speech in 0.680 seconds because the viewport was unavailable. That startup race is fixed; two camera-free Pixel tests passed. Use the new test hash and unused `positive-arm-guided-landscape-b` identifier from the [Task 16U record](../../docs/validation/2026-09-25-task16u-preview-startup.md). Fresh physical Ready is required because the participant was told to relax during the fix.

Task 16V retry completed: 1/1 in 47.810 seconds, 97 fully evaluated frames, LEFT_ARM=1 and GOOD=0. Cleanup complete. Do not reuse `positive-arm-guided-landscape-b`. Obtain participant feedback on whether the left-hand correction was completed before hold; preserve provisional final-pose ground truth until clarified. See the [result](../../docs/validation/2026-09-25-task16v-arm-guidance-pixel6.md).
