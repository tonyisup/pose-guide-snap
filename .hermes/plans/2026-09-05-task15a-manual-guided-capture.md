# Task 15A — Manual guided capture

**Status:** Complete; corrected final Pixel 6 checkpoint passed 4/4.

## Objective

Deliver the first complete guided capture path for a started Room session. The user can request one manual three-photo capture, wait for exact app-private durability and atomic confirmation, advance to the next pose, or stop safely. Automatic triggering, speech, and MediaStore execution remain later Task 15 work.

## Authority order

1. Reconcile the exact session before a Ready bootstrap may reach camera composition.
2. Restore reducer state from the validated Room snapshot and dispatch `ManualCaptureRequested`.
3. Register the reducer command and mark the attempt logically started.
4. For each ordinal, advance Room to `WRITING_TEMP` before reserving or invoking CameraX.
5. Sync and hash the temporary output, persist that evidence, admit publication, publish exactly once, and persist `FINAL_DURABLE`.
6. Dispatch reducer durability confirmation, derive its confirmation effect, and apply Room confirmation with deterministic export intent.
7. Advance the reducer only after Room returns applied or exact replay.
8. On failure or Stop during a capture, settle or reconcile exact journal paths before allowing retry or route exit. Process death is handled by the same startup recovery path.

## Implementation slices

- Add a serialized journaled CameraX coordinator with narrow authority, file, writer, clock, and recovery ports.
- Add ready-to-confirm startup recovery so three durable files cannot strand a session after process death.
- Add strict current-reference reconstruction from validated landmark and decode metadata.
- Add a route-owned guided camera ViewModel and an accessible manual Capture / Stop screen.
- Add host tests for ordering, exact three-output behavior, failure cleanup, confirmation replay, reducer restoration, reference parsing, and Stop deferral.
- Add generated-data Android tests for real Room plus app-private file composition, synthetic Compose controls, and one separately authorized real-camera method that exercises the same journaled coordinator through confirmation and advancement.

## Scope boundary

- No automatic shutter, speech, MediaStore create/write, physical export, network, analytics, storage permission, audio permission, or background service.
- No arbitrary directory scan or caller-selected private path.
- No route exit while an accepted CameraX write still owns unfinished authority.
- A failed or ambiguous recovery remains visibly blocking.

## Host gate

Run the complete JVM suite, lint, debug APK, unsigned release APK, Android-test assembly, and `git diff --check`. Freeze artifact hashes before proposing any device checkpoint.

The final candidate passes this gate. Its device checkpoint was limited to two synthetic control methods, one generated-data Room/private-file method, and one live rear-camera method through the journaled coordinator. The live method captured exactly three transient app-private photos and deleted its generated database and private root during teardown. It did not use the photo picker or MediaStore.

The first live candidate showed that CameraX's file-target API replaces the journal-owned temporary inode; durability correctly failed closed before confirmation. The corrected writer uses an already-open output stream so CameraX cannot replace that admitted path identity. Corrected frozen SHA-256 values are debug `336e4f662f98beee6a52a370fec658b97dbdf6ba47fe9163b102d584ccbacad7`, Android test `65637d4a7958c7d6b39cb266f90d0c46980547a8e1fec52b227b79e63e9e8a96`, and unsigned release `12064e8412632cb454c973f822cd8f243524d3494eb0bb235d940ca0faede94c`. The corrected exact 4/4 Pixel 6 run passed in 5.235 seconds and left no generated database, capture-root, active camera, or instrumentation-package residue.
