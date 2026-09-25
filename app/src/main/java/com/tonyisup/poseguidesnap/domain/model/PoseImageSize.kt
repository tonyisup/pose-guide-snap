package com.tonyisup.poseguidesnap.domain.model

/** Upright image dimensions for normalized landmarks; independent of preview/display dimensions. */
data class PoseImageSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "pose image dimensions must be positive" }
    }

    val aspectRatio: Double get() = width.toDouble() / height

    companion object {
        /** Explicit unit-square coordinate space for detector-independent fixtures. */
        val UNIT_SQUARE = PoseImageSize(1, 1)
    }
}
