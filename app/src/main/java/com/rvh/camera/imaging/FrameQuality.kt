package com.rvh.camera.imaging

/**
 * Cheap CPU-side quality measurements used to decide whether a frame is worth feeding into
 * a multi-frame fusion pass. The scorer deliberately avoids Bitmap/RGB conversion.
 */
data class FrameQuality(
    val sharpness: Float,
    val exposure: Float,
    val highlightClipping: Float,
    val shadowClipping: Float,
) {
    val overall: Float
        get() = (sharpness * 0.45f + exposure * 0.30f +
            (1f - highlightClipping) * 0.15f + (1f - shadowClipping) * 0.10f)
            .coerceIn(0f, 1f)
}

object FrameQualityScorer {
    /**
     * Scores only the luma plane. Sampling keeps the operation bounded even for large sensors.
     */
    fun score(frame: Yuv420Frame, maxSamples: Int = 65_536): FrameQuality {
        require(maxSamples > 0)
        val y = frame.y
        val step = maxOf(1, kotlin.math.sqrt(y.size.toDouble() / maxSamples).toInt())

        var count = 0
        var sum = 0.0
        var sumSquares = 0.0
        var clippedHigh = 0
        var clippedLow = 0
        var edgeEnergy = 0.0

        var row = 1
        while (row < frame.height - 1) {
            var col = 1
            while (col < frame.width - 1) {
                val index = row * frame.width + col
                val center = y[index].toInt() and 0xFF
                val left = y[index - 1].toInt() and 0xFF
                val right = y[index + 1].toInt() and 0xFF
                val up = y[index - frame.width].toInt() and 0xFF
                val down = y[index + frame.width].toInt() and 0xFF

                sum += center
                sumSquares += center.toDouble() * center
                if (center >= 250) clippedHigh++
                if (center <= 5) clippedLow++
                edgeEnergy += kotlin.math.abs(right - left) + kotlin.math.abs(down - up)
                count++
                col += step
            }
            row += step
        }

        if (count == 0) return FrameQuality(0f, 0f, 1f, 1f)

        val mean = sum / count
        val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
        val rms = kotlin.math.sqrt(variance)
        val sharpness = (edgeEnergy / count / 255.0 * 2.0).coerceIn(0.0, 1.0).toFloat()

        // A broad mid-tone preference. This is intentionally conservative; the final exposure
        // engine will use capture metadata and multi-frame relationships as well.
        val exposure = (1.0 - kotlin.math.abs(mean - 112.0) / 112.0).coerceIn(0.0, 1.0).toFloat()
        val highlightClipping = (clippedHigh.toDouble() / count).coerceIn(0.0, 1.0).toFloat()
        val shadowClipping = (clippedLow.toDouble() / count).coerceIn(0.0, 1.0).toFloat()

        // Keep variance referenced so the scorer remains sensitive to completely flat frames.
        val varianceAdjustment = (rms / 64.0).coerceIn(0.0, 1.0)
        return FrameQuality(
            sharpness = (sharpness * 0.85 + varianceAdjustment * 0.15).toFloat(),
            exposure = exposure,
            highlightClipping = highlightClipping,
            shadowClipping = shadowClipping,
        )
    }
}
