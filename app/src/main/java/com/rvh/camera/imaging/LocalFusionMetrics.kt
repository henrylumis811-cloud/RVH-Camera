package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max

/**
 * Cheap local statistics used by the first edge-preserving fusion tier.
 *
 * The metrics intentionally operate on luma only. They are not a full denoiser or optical-flow
 * estimator; their job is to decide whether a candidate pixel is safe to average with the
 * reference pixel.
 */
object LocalFusionMetrics {
    data class PixelMetrics(
        val referenceGradient: Float,
        val candidateGradient: Float,
        val edgeAgreement: Float,
        val noiseConfidence: Float,
    )

    fun measure(
        reference: Yuv420Frame,
        candidate: Yuv420Frame,
        referenceX: Int,
        referenceY: Int,
        candidateX: Int,
        candidateY: Int,
    ): PixelMetrics {
        val refGradient = gradient(reference, referenceX, referenceY)
        val candGradient = gradient(candidate, candidateX, candidateY)

        val gradientDifference = abs(refGradient - candGradient)
        val edgeAgreement = (1f - gradientDifference / max(16f, refGradient + candGradient + 8f))
            .coerceIn(0f, 1f)

        // A large second derivative relative to the local gradient is a useful cheap proxy for
        // high-frequency noise. It is deliberately only a confidence modifier, never a blur.
        val refCurvature = curvature(reference, referenceX, referenceY)
        val normalizedNoise = refCurvature / (refCurvature + refGradient + 12f)
        val noiseConfidence = (1f - normalizedNoise * 0.65f).coerceIn(0.35f, 1f)

        return PixelMetrics(
            referenceGradient = refGradient,
            candidateGradient = candGradient,
            edgeAgreement = edgeAgreement,
            noiseConfidence = noiseConfidence,
        )
    }

    private fun gradient(frame: Yuv420Frame, x: Int, y: Int): Float {
        if (x <= 0 || y <= 0 || x >= frame.width - 1 || y >= frame.height - 1) return 0f
        val row = y * frame.width
        val center = frame.y[row + x].toInt() and 0xFF
        val left = frame.y[row + x - 1].toInt() and 0xFF
        val right = frame.y[row + x + 1].toInt() and 0xFF
        val up = frame.y[row - frame.width + x].toInt() and 0xFF
        val down = frame.y[row + frame.width + x].toInt() and 0xFF
        // L1 gradient is faster than sqrt and is sufficient for a confidence gate.
        return (abs(right - left) + abs(down - up)).toFloat() * 0.5f +
            abs(center - ((left + right) shr 1)).toFloat() * 0.25f
    }

    private fun curvature(frame: Yuv420Frame, x: Int, y: Int): Float {
        if (x <= 0 || y <= 0 || x >= frame.width - 1 || y >= frame.height - 1) return 0f
        val row = y * frame.width
        val center = frame.y[row + x].toInt() and 0xFF
        val left = frame.y[row + x - 1].toInt() and 0xFF
        val right = frame.y[row + x + 1].toInt() and 0xFF
        val up = frame.y[row - frame.width + x].toInt() and 0xFF
        val down = frame.y[row + frame.width + x].toInt() and 0xFF
        return (abs(left - 2 * center + right) + abs(up - 2 * center + down)) * 0.5f
    }
}
