package com.rvh.camera.imaging


/**
 * Small, deterministic capture policy used before the expensive imaging pipeline.
 *
 * The planner is deliberately statistics-driven. It does not depend on ML and it never assumes
 * that a scene should receive a multi-frame capture merely because it is dark or contrasty.
 */
class CapturePlanner(
    private val maximumComputationalFrames: Int = 3,
) {
    init {
        require(maximumComputationalFrames >= 2)
    }

    fun analyze(frame: Yuv420Frame): SceneAnalysis {
        val quality = FrameQualityScorer.score(frame)
        val y = frame.y
        val step = maxOf(1, kotlin.math.sqrt(y.size.toDouble() / 32_768.0).toInt())

        var count = 0
        var sum = 0.0
        var high = 0
        var low = 0
        var veryHigh = 0
        var veryLow = 0

        val spatialGrid = FloatArray(SCENE_GRID_WIDTH * SCENE_GRID_HEIGHT)
        val spatialCounts = IntArray(spatialGrid.size)

        var row = 0
        while (row < frame.height) {
            var col = 0
            while (col < frame.width) {
                val value = y[row * frame.width + col].toInt() and 0xFF
                val gx = (col * SCENE_GRID_WIDTH / frame.width).coerceIn(0, SCENE_GRID_WIDTH - 1)
                val gy = (row * SCENE_GRID_HEIGHT / frame.height).coerceIn(0, SCENE_GRID_HEIGHT - 1)
                val gi = gy * SCENE_GRID_WIDTH + gx
                spatialGrid[gi] = spatialGrid[gi] + value
                spatialCounts[gi]++
                sum += value
                count++
                if (value >= 250) high++
                if (value <= 5) low++
                if (value >= 235) veryHigh++
                if (value <= 20) veryLow++
                col += step
            }
            row += step
        }

        if (count == 0) {
            return SceneAnalysis(
                quality = quality,
                meanLuma = 0f,
                highlightRatio = 1f,
                shadowRatio = 1f,
                dynamicRangePressure = 1f,
                lowLightScore = 1f,
            )
        }

        for (i in spatialGrid.indices) {
            if (spatialCounts[i] > 0) spatialGrid[i] /= spatialCounts[i].toFloat()
        }

        val mean = (sum / count).toFloat()
        val highlightRatio = (high.toFloat() / count).coerceIn(0f, 1f)
        val shadowRatio = (low.toFloat() / count).coerceIn(0f, 1f)
        val broadHighlight = veryHigh.toFloat() / count
        val broadShadow = veryLow.toFloat() / count

        // HDR pressure means the scene simultaneously contains information close to both ends
        // of the sensor output range. It is intentionally difficult to trigger.
        val dynamicRangePressure = (
            kotlin.math.sqrt(broadHighlight * broadShadow) * 8f +
                highlightRatio * 1.5f + shadowRatio * 1.5f
            ).coerceIn(0f, 1f)

        // Low light is primarily a luma statistic here. ISO/exposure metadata is incorporated by
        // choosePlan(), because the preview analysis may not have capture metadata yet.
        val lowLightScore = (
            ((96f - mean) / 96f).coerceIn(0f, 1f) * 0.65f +
                shadowRatio.coerceIn(0f, 1f) * 0.35f
            ).coerceIn(0f, 1f)

        return SceneAnalysis(
            quality = quality,
            meanLuma = mean,
            highlightRatio = highlightRatio,
            shadowRatio = shadowRatio,
            dynamicRangePressure = dynamicRangePressure,
            lowLightScore = lowLightScore,
            spatialLuma = spatialGrid,
        )
    }

    fun choosePlan(
        analysis: SceneAnalysis,
        metadata: FrameMetadata,
        vendorNightExtensionAvailable: Boolean = false,
        vendorHdrExtensionAvailable: Boolean = false,
    ): CapturePlan {
        val iso = metadata.sensitivityIso ?: 0
        val exposureMs = (metadata.exposureTimeNs ?: 0L) / 1_000_000.0
        val highIsoScore = if (iso > 0) ((iso - 320) / 1280f).coerceIn(0f, 1f) else 0f
        val longExposureScore = ((exposureMs - 8.0) / 24.0).toFloat().coerceIn(0f, 1f)

        val nightScore = (analysis.lowLightScore * 0.60f + highIsoScore * 0.25f +
            longExposureScore * 0.15f).coerceIn(0f, 1f)

        // A scene must have genuine two-sided range pressure before HDR wins. This prevents the
        // common mistake of enabling HDR simply because a small bright lamp exists in frame.
        val hdrScore = (analysis.dynamicRangePressure * 0.72f +
            analysis.quality.highlightClipping * 0.14f +
            analysis.quality.shadowClipping * 0.14f).coerceIn(0f, 1f)

        if (vendorNightExtensionAvailable && nightScore >= 0.78f) {
            return CapturePlan(
                intent = CaptureIntent.NIGHT,
                frameCount = maximumComputationalFrames.coerceAtMost(3),
                preferVendorExtension = true,
                score = nightScore,
            )
        }

        if (nightScore >= 0.72f) {
            return CapturePlan(
                intent = CaptureIntent.NIGHT,
                frameCount = maximumComputationalFrames.coerceAtMost(3),
                preferVendorExtension = false,
                score = nightScore,
            )
        }

        if (hdrScore >= 0.58f && analysis.meanLuma in 38f..210f) {
            return CapturePlan(
                intent = CaptureIntent.HDR,
                frameCount = maximumComputationalFrames,
                preferVendorExtension = vendorHdrExtensionAvailable,
                score = hdrScore,
            )
        }

        // Computational PHOTO is intentionally the default only when the image is not already
        // exceptionally clean. One extra frame is enough to reduce noise without making every
        // shutter press a burst.
        val noisyScene = highIsoScore >= 0.20f || analysis.quality.sharpness < 0.45f
        if (noisyScene) {
            return CapturePlan(
                intent = CaptureIntent.PHOTO,
                frameCount = 2,
                preferVendorExtension = false,
                score = maxOf(highIsoScore, 1f - analysis.quality.sharpness),
            )
        }

        return CapturePlan(
            intent = CaptureIntent.PHOTO,
            frameCount = 1,
            preferVendorExtension = false,
            score = analysis.quality.overall,
        )
    }
}

data class SceneAnalysis(
    val quality: FrameQuality,
    val meanLuma: Float,
    val highlightRatio: Float,
    val shadowRatio: Float,
    val dynamicRangePressure: Float,
    val lowLightScore: Float,
    /** Compact spatial luminance signature used for cheap temporal-change detection. */
    val spatialLuma: FloatArray = FloatArray(SCENE_GRID_WIDTH * SCENE_GRID_HEIGHT),
) {
    fun temporalDifference(other: SceneAnalysis): Float {
        if (spatialLuma.size != other.spatialLuma.size || spatialLuma.isEmpty()) return 1f
        var sum = 0f
        for (i in spatialLuma.indices) {
            sum += kotlin.math.abs(spatialLuma[i] - other.spatialLuma[i])
        }
        return (sum / spatialLuma.size / 255f).coerceIn(0f, 1f)
    }
}

private const val SCENE_GRID_WIDTH = 8
private const val SCENE_GRID_HEIGHT = 6

data class CapturePlan(
    val intent: CaptureIntent,
    val frameCount: Int,
    val preferVendorExtension: Boolean,
    val score: Float,
) {
    val isComputational: Boolean
        get() = frameCount > 1 || preferVendorExtension
}
