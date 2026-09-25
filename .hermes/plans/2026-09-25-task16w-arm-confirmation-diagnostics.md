# Task 16W — arm confirmation diagnosis

The participant had placed the left hand before hold; palm orientation is not scored. Previous reports cannot reproduce the physical arm trajectory. Current status: bounded diagnostic support implemented, 745/745 host tests and build/lint pass; root cause remains unconfirmed and coaching behavior unchanged.

Use the [diagnostic record](../../docs/validation/2026-09-25-task16w-arm-confirmation-diagnostics.md) for exact hashes, unique labels, scalar-only evidence and cleanup. Wait for physical Ready, then measure rather than relax the tolerance. Preserve prior raw reports, keep Task 16V final-pose labeling provisional, and do not reopen the deferred solo camera-adjustment timing/process work without new direction.

Task 16X diagnostic completed on unchanged artifacts after unlocking the phone: 96 fully evaluated frames, LEFT_ARM=1/GOOD=0, 128 eligible MISMATCH frames, no gaps/unavailable evidence, 12985 ms post-speech time, elbow min .2721 and wrist min .2928 against .25. Spatial rejection is established; why the broad instruction did not lead to the expected positions is not. Do not reuse either confirmation sequence a or b. Next development: actionable joint-specific correction from transient signed geometry, not threshold relaxation. See the [result](../../docs/validation/2026-09-25-task16x-arm-confirmation-pixel6.md).

The user approved the next step. Task 16Y implements directional joint coaching and joint-specific acknowledgment with unchanged tolerance and timing; 752/752 tests pass. Continue from the [new protocol](../../docs/validation/2026-09-25-task16y-directional-arm-guidance.md), not the used Task 16X identifiers.
