# Pose Model and Runtime

## Approved pins

Pose Guide Snap bundles the following exact artifacts for local CPU inference:

| Artifact | Exact pin |
|---|---|
| Model | MoveNet MultiPose Lightning float16 TFLite v1, `app/src/main/assets/movenet_multipose_lightning_float16_v1.tflite` |
| Model SHA-256 | `d4489f89e6bd6777a8b9a1a16189832131f84ff90d82fae729e670b84d7948dd` |
| Model size | `9,585,276` bytes |
| Runtime | `com.google.ai.edge.litert:litert:1.4.2` |
| Runtime transitive | `com.google.ai.edge.litert:litert-api:1.4.2` only |

The model was copied byte-for-byte from the [official TF Hub download URL](https://tfhub.dev/google/lite-model/movenet/multipose/lightning/tflite/float16/1?lite-format=tflite), which redirects to official Google/Kaggle-hosted bytes. The [official Google/Kaggle registry](https://kaggle.com/models/google/movenet/tfLite) and the MoveNet MultiPose model card identify the model license as Apache 2.0. The runtime and API POMs also declare Apache 2.0.

No version range, dynamic version, remote model download, or runtime fallback is permitted. A model or runtime upgrade requires a reviewed change that records new exact coordinates, source URL, license, byte size, and digest; audits the complete runtime dependency graph and merged/package manifests for permissions, components, network, analytics, and delivery behavior; reruns deterministic fixtures and real-device acceptance; recalibrates thresholds; and defines whether stored reference landmarks must be regenerated. Reference and live observations must always use the same pinned model and preprocessing contract.

## Why this runtime and model

The direct BlazePose LiteRT spike was only **PARTIAL** and was not recommended: reproducing the MediaPipe graph was brittle and its fixture person counts were wrong. The direct MoveNet MultiPose spike was **VALIDATED** and recommended for the replacement because it produced deterministic fixture counts and has a minimal direct-interpreter Android dependency surface.

MediaPipe Tasks Vision is rejected for this product boundary. Its mandatory Google metrics path conflicts with the strict no-analytics/no-network contract, and excluding that metrics dependency causes linkage failure. Direct LiteRT avoids claiming that this incompatible dependency can simply be removed.

LiteRT `1.4.2` is deliberate. Its resolved runtime graph is exactly `litert:1.4.2` plus `litert-api:1.4.2`; both AAR manifests declare minSdk 21 and an empty application, with no permissions, services, or features, and the dependency audit found no other transitives. LiteRT `2.2.0` is rejected because it adds Play AI Delivery dependencies and foreground-service permissions that are irrelevant to a model bundled in the APK and executed by the CPU interpreter.

## Model contract and intended use

The model card establishes this contract:

- RGB `uint8` input shaped dynamically as `[1, H, W, 3]`, with height and width each a multiple of 32.
- One `float32` output shaped `[1, 6, 56]`.
- Up to six people, with 17 COCO keypoints per person: nose; left/right eye; left/right ear; left/right shoulder, elbow, wrist, hip, knee, and ankle; plus an instance box and score.
- Intended for on-device or browser pose detection, particularly fitness and fast movement with motion blur.
- The model itself does not store, use, or send input-image information during inference. Surveillance and identity recognition are explicitly out of scope.

Those statements describe the model, not the complete application. The application must still preserve its separate local-data, logging, backup, and network controls.

The model card reports quality differences across evaluated gender, age, and skin-tone subgroups, with different subgroup composition and results between its COCO and active-motion evaluation sets. This repository does not reinterpret those published numbers as product fairness, population validity, or equal performance. Product acceptance must retain subgroup-aware evaluation and must not generalize from the single retained fixture.

## Deliberate limitations and adapter semantics

MoveNet MultiPose is a 17-point **2D** model. It provides no `z` coordinate or world landmarks. It also lacks BlazePose-style hand detail, heel, foot-index, mouth, and inner-eye landmarks. Out-of-plane motion, depth differences, occlusion, framing, and viewpoint can therefore be invisible or ambiguous. Every pose angle derived from this model is an image-plane angle, not a 3D anatomical measurement.

The implemented adapter does not invent richer evidence. It maps `z = 0` explicitly and maps the one MoveNet keypoint score to both domain `visibility` and `presence` with documented semantics that they are aliases of the same model confidence, not independent probabilities. It validates the model's exact tensor contract, applies deterministic upright RGB resize/pad preprocessing, and leaves thresholds and mapping to the pure policy layer. Reference and live paths must use the same model, resize/pad behavior, coordinate unpadding, keypoint identities, score policy, and adapter version.

Person-score and keypoint-score thresholds are currently uncalibrated. The replacement spike proved only that bounded static fixtures reported exactly 1, 0, and 2 people and were deterministic across repeated runs while networking was denied. That is not Android-device inference evidence, a latency claim, an accuracy claim, a demographic-performance claim, or production calibration. No device or emulator inference was run for this checkpoint.

The retained positive fixture's origin, license, digest, and restrictions are documented in its [fixture attribution](../app/src/androidTest/assets/pose-fixtures/ATTRIBUTION.md). It must not be used alone to claim accuracy, fairness, fitness for production, or population-level validity.
