package com.tonyisup.poseguidesnap.camera

data class PixelSize(
    val width: Double,
    val height: Double,
) {
    init {
        require(width.isFinite() && width > 0.0) { "Pixel width must be positive and finite: $width" }
        require(height.isFinite() && height > 0.0) { "Pixel height must be positive and finite: $height" }
    }
}

data class PixelRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Pixel rect edges must be finite: ($left, $top, $right, $bottom)"
        }
        require(right > left && bottom > top) {
            "Pixel rect must be nonempty: ($left, $top, $right, $bottom)"
        }
    }

    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

data class PixelPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Pixel point must be finite: ($x, $y)" }
    }
}

data class NormalizedPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && x in 0.0..1.0) { "Normalized x must be finite and in [0, 1]: $x" }
        require(y.isFinite() && y in 0.0..1.0) { "Normalized y must be finite and in [0, 1]: $y" }
    }
}

class FrameCoordinateTransform(
    val fullSize: PixelSize,
    val cropRect: PixelRect,
    val rotationDegrees: Int,
    val mirroredHorizontally: Boolean = false,
) {
    init {
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            "Clockwise rotation must be one of 0, 90, 180, or 270 degrees: $rotationDegrees"
        }
        require(
            cropRect.left >= 0.0 &&
                cropRect.top >= 0.0 &&
                cropRect.right <= fullSize.width &&
                cropRect.bottom <= fullSize.height,
        ) {
            "Crop rect $cropRect must be within full frame $fullSize"
        }
    }

    val uprightContentPixelSize: PixelSize = when (rotationDegrees) {
        90, 270 -> PixelSize(cropRect.height, cropRect.width)
        else -> PixelSize(cropRect.width, cropRect.height)
    }

    /** Crop edges are inclusive: left/top map to zero and right/bottom map to one. */
    fun sourceToUpright(point: PixelPoint): NormalizedPoint {
        requireInsideCrop(point)
        val u = (point.x - cropRect.left) / cropRect.width
        val v = (point.y - cropRect.top) / cropRect.height
        val rotated = when (rotationDegrees) {
            0 -> NormalizedPoint(u, v)
            90 -> NormalizedPoint(1.0 - v, u)
            180 -> NormalizedPoint(1.0 - u, 1.0 - v)
            270 -> NormalizedPoint(v, 1.0 - u)
            else -> error("Rotation was validated during construction")
        }
        return if (mirroredHorizontally) {
            NormalizedPoint(1.0 - rotated.x, rotated.y)
        } else {
            rotated
        }
    }

    /** Inverse of [sourceToUpright], including display mirror and clockwise rotation. */
    fun uprightToSource(point: NormalizedPoint): PixelPoint {
        val unmirroredX = if (mirroredHorizontally) 1.0 - point.x else point.x
        val (u, v) = when (rotationDegrees) {
            0 -> unmirroredX to point.y
            90 -> point.y to (1.0 - unmirroredX)
            180 -> (1.0 - unmirroredX) to (1.0 - point.y)
            270 -> (1.0 - point.y) to unmirroredX
            else -> error("Rotation was validated during construction")
        }
        return PixelPoint(
            x = cropRect.left + u * cropRect.width,
            y = cropRect.top + v * cropRect.height,
        )
    }

    /** Full-frame edges are inclusive and out-of-frame points fail rather than clamp. */
    fun sourceToFullFrameNormalized(point: PixelPoint): NormalizedPoint {
        requireInsideFullFrame(point)
        return NormalizedPoint(
            x = point.x / fullSize.width,
            y = point.y / fullSize.height,
        )
    }

    fun fullFrameNormalizedToSource(point: NormalizedPoint): PixelPoint = PixelPoint(
        x = point.x * fullSize.width,
        y = point.y * fullSize.height,
    )

    /**
     * Converts through normalized full-frame coordinates so use cases may differ in resolution.
     * A corresponding target source point outside [target]'s crop fails instead of being clamped.
     */
    fun uprightToUpright(
        point: NormalizedPoint,
        target: FrameCoordinateTransform,
    ): NormalizedPoint {
        val sourcePoint = uprightToSource(point)
        val normalizedFullFrame = sourceToFullFrameNormalized(sourcePoint)
        val targetSourcePoint = target.fullFrameNormalizedToSource(normalizedFullFrame)
        return target.sourceToUpright(targetSourcePoint)
    }

    private fun requireInsideCrop(point: PixelPoint) {
        require(
            point.x in cropRect.left..cropRect.right &&
                point.y in cropRect.top..cropRect.bottom,
        ) {
            "Source point $point must be within inclusive crop $cropRect"
        }
    }

    private fun requireInsideFullFrame(point: PixelPoint) {
        require(point.x in 0.0..fullSize.width && point.y in 0.0..fullSize.height) {
            "Source point $point must be within inclusive full frame $fullSize"
        }
    }
}

class PreviewFillCenterTransform(
    val contentPixelSize: PixelSize,
    val viewportPixelSize: PixelSize,
) {
    val scale: Double = maxOf(
        viewportPixelSize.width / contentPixelSize.width,
        viewportPixelSize.height / contentPixelSize.height,
    )
    val renderedPixelSize: PixelSize = PixelSize(
        width = contentPixelSize.width * scale,
        height = contentPixelSize.height * scale,
    )
    val offset: PixelPoint = PixelPoint(
        x = (viewportPixelSize.width - renderedPixelSize.width) / 2.0,
        y = (viewportPixelSize.height - renderedPixelSize.height) / 2.0,
    )

    /** Center-cropped content points may intentionally map outside the viewport. */
    fun contentToPreview(point: NormalizedPoint): PixelPoint = PixelPoint(
        x = offset.x + point.x * renderedPixelSize.width,
        y = offset.y + point.y * renderedPixelSize.height,
    )

    /** Points outside rendered content fail through [NormalizedPoint] validation; no clamp is applied. */
    fun previewToContent(point: PixelPoint): NormalizedPoint = NormalizedPoint(
        x = (point.x - offset.x) / renderedPixelSize.width,
        y = (point.y - offset.y) / renderedPixelSize.height,
    )
}
