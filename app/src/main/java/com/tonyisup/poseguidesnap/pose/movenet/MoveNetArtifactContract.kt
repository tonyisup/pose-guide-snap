package com.tonyisup.poseguidesnap.pose.movenet

/** Exact reviewed MoveNet artifact and adapter pins shared by every inference path. */
object MoveNetArtifactContract {
    const val MODEL_ASSET_PATH = "movenet_multipose_lightning_float16_v1.tflite"
    const val MODEL_NAME = "MoveNet MultiPose Lightning float16"
    const val MODEL_VERSION = "1"
    const val MODEL_SHA_256 =
        "d4489f89e6bd6777a8b9a1a16189832131f84ff90d82fae729e670b84d7948dd"

    const val RUNTIME_NAME = "LiteRT"
    const val RUNTIME_VERSION = "1.4.2"
    const val ADAPTER_NAME = "MoveNetPoseDetector+MoveNetResultMapper"
    const val ADAPTER_VERSION = "1"
    const val PREPROCESSING_NAME = "ImageDecoder+letterbox"
    const val PREPROCESSING_VERSION = "1"

    const val INPUT_SIZE = 256
}
