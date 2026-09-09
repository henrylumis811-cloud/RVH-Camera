package com.rvh.camera.imaging

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Converts Camera2 OIS samples into a conservative motion estimate for computational capture.
 *
 * OIS samples describe lens shift in the pre-correction active-array coordinate system. We do not
 * treat OIS as scene motion: it is specifically used to estimate camera shake and the stability of
 * the optical path during an exposure. The resulting score is deliberately bounded so optical
 * stabilization never causes the planner to become over-aggressive.
 */
object OisMotionAnalyzer {
    data class Metrics(
        val available: Boolean,
        val sampleCount: Int,
        val rmsShiftPixels: Float,
        val peakShiftPixels: Float,
        val pathLengthPixels: Float,
        val score: Float,
    )

    fun analyze(metadata: FrameMetadata): Metrics {
        val xs = metadata.oisXShifts
        val ys = metadata.oisYShifts
        val ts = metadata.oisTimestampsNs
        if (!metadata.oisDataAvailable || xs == null || ys == null || xs.isEmpty()) {
            return Metrics(false, 0, 0f, 0f, 0f, 0f)
        }
        val count = minOf(xs.size, ys.size, ts?.size ?: Int.MAX_VALUE)
        if (count <= 0) return Metrics(false, 0, 0f, 0f, 0f, 0f)

        var sumSq = 0.0
        var peak = 0f
        var path = 0.0
        var previousX = xs[0]
        var previousY = ys[0]
        for (i in 0 until count) {
            val x = xs[i].takeIf(Float::isFinite) ?: continue
            val y = ys[i].takeIf(Float::isFinite) ?: continue
            val magnitude = hypot(x, y)
            sumSq += magnitude.toDouble() * magnitude.toDouble()
            peak = maxOf(peak, magnitude)
            if (i > 0) path += hypot(x - previousX, y - previousY).toDouble()
            previousX = x
            previousY = y
        }
        val rms = sqrt((sumSq / count).coerceAtLeast(0.0)).toFloat()
        val pathNorm = (path / maxOf(1.0, count * 0.5)).toFloat()
        val score = (
            0.55f * (rms / 2.5f).coerceIn(0f, 1f) +
                0.25f * (peak / 5f).coerceIn(0f, 1f) +
                0.20f * (pathNorm / 2.5f).coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)
        return Metrics(true, count, rms, peak, path.toFloat(), score)
    }
}
