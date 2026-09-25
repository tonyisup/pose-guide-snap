# Task 16G raw keypoint-score diagnostic — Pixel 6 — 8 September 2026

Result: **The final corrected run found positive but sub-threshold live MoveNet evidence.** The first candidate remains invalid because it exposed an off-by-one reducer defect, and a transient corrected startup attempt produced no evidence. The final exact repeat analyzed 145 frames, wrote no report, and completed exact cleanup.

## First candidate scope and artifacts

The user separately authorized data-preserving installation and installed-byte verification, camera permission, one exact collector invocation, conditional report retrieval, exact cleanup, and removal of only the test package.

| First candidate artifact | Local and installed SHA-256 |
|---|---|
| Debug app | `0c5928c9edcdc6a7cf7276b3667540f1ada3500320c42238bb499e10c429b27f` |
| Android test | `fc2aa5f30b4aaea2a4a3218490d6c0526b4faaf7ef3a4d0a2387dffc17a177c7` |
| Unsigned release — host evidence only | `d0a90e11ee75e2a97e37c5e06ebfec076ea18e5168e306ef4c5bb55ee474e281` |

Only `calibration.MatchCalibrationCollectionAndroidTest#collectOneAuthorizedDerivedSequence` ran with sequence `positive-centered-diagnostic-f`, the established provenance/class labels, a 15-second warm-up, and a conditional 10-second recording window. The Pixel was awake, both installed package hashes matched, camera permission was verified, and the participant was instructed immediately before launch to keep their entire body visible.

The method failed 1/1 before recording after 16.557 seconds:

| Evidence | Result |
|---|---:|
| Analyzed/no-person frames | 146 / 146 |
| Exactly-one/multiple-person frames | 0 / 0 |
| Maximum valid raw person score | 0.0 |
| Reported maximum valid keypoint score | 1.0 — invalid |
| Frame-mean luminance range | 0.4060461642156865–0.6429938939950985 |
| Maximum within-frame luminance range | 0.3694454901960784 |

## Root cause and correction

The first reducer traversed `2 until 55 step 3`. Valid keypoint scores occupy indices `2, 5, …, 50`; the sequence also included index 53, which is a bounding-box coordinate. The observed `1.0` therefore cannot be interpreted as keypoint confidence.

The correction bounds traversal to the 51-value keypoint block. Its regression sets every bounding-box value to `1.0` while requiring the maximum keypoint score to remain `0.85`, so the prior defect would fail causally. The corrected full host gate passes 713/713 JVM tests and 12/12 Python tests with zero lint errors and 9 warnings. Schemas and permissions are unchanged.

| Corrected candidate artifact | SHA-256 |
|---|---|
| Debug app | `28c3820a2780fb744c37d056b4742eb71eab9249c9947deb411c4089c6e57dcd` |
| Android test | `fc2aa5f30b4aaea2a4a3218490d6c0526b4faaf7ef3a4d0a2387dffc17a177c7` |
| Unsigned release — host evidence only | `c6935eb3a14221a8c7655665595da0f47fc17c595bf7e6c6749d125b9b86010a` |

## Corrected candidate startup attempt

The user separately authorized the corrected `positive-centered-diagnostic-g` invocation with the same bounded method, labels, timing, conditional report retrieval, and cleanup. Local and installed bytes matched debug `28c3820a2780fb744c37d056b4742eb71eab9249c9947deb411c4089c6e57dcd` and Android test `fc2aa5f30b4aaea2a4a3218490d6c0526b4faaf7ef3a4d0a2387dffc17a177c7`; camera permission was granted and verified.

The method failed 1/1 after 0.623 seconds, before warm-up or report creation, with `Rear camera or pose analysis failed`. Camera-service state showed that the rear camera opened and disconnected, but the harness did not retain the originating exception. This result provides no evidence about keypoint scores or person detection.

Exact cleanup removed the empty export directory, stopped Pose Guide Snap, uninstalled only the test package, deleted temporary installed-APK pulls, preserved the corrected main app and its data, and left no active camera client.

## Bounded failure-classification follow-up

The test harness now reduces runtime failure evidence to one bounded stage (`preview`, `binding`, `startup`, or `analysis`) and a cause chain capped at four exception class names. It does not retain throwable objects, messages, images, pixels, landmarks, coordinates, paths, URIs, timestamps, or identifiers. The fresh host gate passes 713/713 JVM and 12/12 Python tests with zero failures, errors, or skips, zero lint errors and 14 warnings, all APK assemblies, unchanged Room schemas, and unchanged packaged permissions.

| Current diagnostic artifact | SHA-256 |
|---|---|
| Debug app — unchanged | `28c3820a2780fb744c37d056b4742eb71eab9249c9947deb411c4089c6e57dcd` |
| Android test — test-only follow-up | `d69892e64e8f9041f098ea3c249918c37037d54ba72ab916777a0fd02c2e4808` |
| Unsigned release — unchanged, host evidence only | `c6935eb3a14221a8c7655665595da0f47fc17c595bf7e6c6749d125b9b86010a` |

## Final corrected diagnostic

The user separately authorized `positive-centered-diagnostic-h` with the same exact method, dataset/class labels, 15-second warm-up, conditional 10-second collection, and cleanup. The preserved main and newly installed test bytes matched the current hashes above, camera permission was verified, and the participant was instructed immediately before launch to remain fully visible to the rear camera.

The method completed preflight and failed closed before recording after 16.49 seconds:

| Evidence | Result |
|---|---:|
| Analyzed/no-person frames | 145 / 145 |
| Exactly-one/multiple-person frames | 0 / 0 |
| Maximum valid raw person score | 0.14585432410240173 |
| Maximum valid raw keypoint score | 0.20261988043785095 |
| Frame-mean luminance range | 0.1355476225490197–0.24370181985294126 |
| Maximum within-frame luminance range | 0.3239913725490196 |
| Unchanged minimum accepted person score | 0.25 |

The positive nonzero person and keypoint maxima establish that live pose evidence reaches the model output. The person evidence remained below the current gate throughout this sequence. This is a diagnostic discriminator, not a threshold calibration result: it provides neither a positive score distribution nor negative separation evidence.

## Cleanup and claim boundary

No report or temporary report was created by any attempt. Exact cleanup removed the empty export directory, stopped Pose Guide Snap, uninstalled only `com.tonyisup.poseguidesnap.test`, and deleted temporary installed-APK pulls. Final checks found no export directory, test package, temporary pull, or active camera client. The corrected main app remains installed with its data preserved, and unrelated files were untouched.

The final result resolves the absent-versus-partial discriminator but cannot justify a threshold change. Any candidate person gate requires separately authorized positive and negative score distributions on frozen artifacts.
