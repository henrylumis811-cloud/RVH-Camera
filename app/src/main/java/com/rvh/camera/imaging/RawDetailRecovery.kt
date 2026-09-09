package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Conservative Bayer-domain micro-detail recovery.
 *
 * This is deliberately not a conventional sharpen filter. The residual is measured only on the
 * same CFA plane, against two spatial scales, and is restored only when local structure agrees
 * with the residual and the calibrated sensor noise model says the signal is sufficiently above
 * the noise floor. This keeps demosaic from turning sensor noise into false colour texture.
 */
class RawDetailRecovery(
    private val maximumGain: Float = 0.34f,
) {
    fun apply(mosaic: RawLinearMosaic, strength: Float = 1f): RawLinearMosaic {
        val w = mosaic.width
        val h = mosaic.height
        if (w < 9 || h < 9) return mosaic

        val source = mosaic.values
        val output = source.copyOf()
        val metadata = mosaic.metadata
        val profile = metadata.sensorNoiseProfile
        val iso = (metadata.sensitivityIso ?: 100).coerceAtLeast(50)
        val gainScale = strength.coerceIn(0f, 1f)

        fun channel(x: Int, y: Int): Int = cfaChannel(mosaic.cfaArrangement, x, y).coerceIn(0, 3)

        fun noiseAt(value: Float, c: Int): Float {
            if (profile == null || profile.size < 2) {
                val isoFactor = sqrt(iso / 100f).coerceIn(0.7f, 3f)
                return (0.006f + 0.018f * isoFactor * sqrt(value.coerceIn(0f, 1f) + 0.02f))
                    .coerceIn(0.003f, 0.08f)
            }
            val base = (c * 2).coerceAtMost(profile.size - 2)
            val s = profile[base].coerceAtLeast(0f)
            val o = profile[base + 1].coerceAtLeast(0f)
            return sqrt((s * value.coerceIn(0f, 1f) + o).coerceAtLeast(0f))
                .coerceIn(0.001f, 0.12f)
        }

        fun sample(x: Int, y: Int, dx: Int, dy: Int, c: Int): Float? {
            val sx = x + dx
            val sy = y + dy
            if (sx !in 0 until w || sy !in 0 until h) return null
            if (channel(sx, sy) != c) return null
            return source[sy * w + sx]
        }

        for (y in 4 until h - 4) {
            for (x in 4 until w - 4) {
                val index = y * w + x
                val c = channel(x, y)
                val center = source[index]
                val noise = noiseAt(center, c)

                val near = floatArrayOf(
                    sample(x, y, -2, 0, c) ?: center,
                    sample(x, y, 2, 0, c) ?: center,
                    sample(x, y, 0, -2, c) ?: center,
                    sample(x, y, 0, 2, c) ?: center,
                )
                val far = floatArrayOf(
                    sample(x, y, -4, 0, c) ?: center,
                    sample(x, y, 4, 0, c) ?: center,
                    sample(x, y, 0, -4, c) ?: center,
                    sample(x, y, 0, 4, c) ?: center,
                )

                val nearMean = near.average().toFloat()
                val farMean = far.average().toFloat()
                val localBase = 0.72f * nearMean + 0.28f * farMean
                val detail = center - localBase

                val horizontal = abs(near[1] - near[0])
                val vertical = abs(near[3] - near[2])
                val gradient = 0.5f * (horizontal + vertical)
                val secondHorizontal = abs(near[0] - 2f * center + near[1])
                val secondVertical = abs(near[2] - 2f * center + near[3])
                val curvature = 0.5f * (secondHorizontal + secondVertical)

                // A detail residual is trusted when the local gradient supports structure and
                // when the residual is coherent at both spatial scales.
                val scaleAgreement = 1f - abs(nearMean - farMean) /
                    max(0.01f, abs(center - farMean) + noise * 4f)
                val structure = (gradient / (gradient + noise * 4f + 0.004f)).coerceIn(0f, 1f)
                val curvaturePenalty = (curvature / (curvature + gradient + noise * 5f + 0.004f))
                    .coerceIn(0f, 1f)
                val confidence = (
                    0.55f * structure +
                        0.45f * scaleAgreement.coerceIn(0f, 1f)
                    ) * (1f - 0.65f * curvaturePenalty)

                val signalToNoise = abs(detail) / max(0.003f, noise * 2.2f)
                val thresholdGate = ((signalToNoise - 1f) / 2.5f).coerceIn(0f, 1f)
                val edgeProtection = (1f - curvaturePenalty * 0.55f).coerceIn(0f, 1f)
                val gain = maximumGain * gainScale * confidence * thresholdGate * edgeProtection

                // Avoid sharpening near saturation where clipped values make residuals unreliable.
                val saturationGate = if (center > 0.94f) {
                    ((1.05f - center) / 0.11f).coerceIn(0f, 1f)
                } else 1f

                output[index] = (center + detail * gain * saturationGate)
                    .coerceIn(0f, min(1.05f, center + noise * 4f + 0.25f))
            }
        }

        return mosaic.copy(values = output)
    }
}

private fun cfaChannel(arrangement: Int, x: Int, y: Int): Int {
    val parity = (y and 1) * 2 + (x and 1)
    return when (arrangement) {
        1 -> intArrayOf(1, 0, 3, 1)[parity]
        2 -> intArrayOf(1, 3, 0, 1)[parity]
        3 -> intArrayOf(3, 1, 1, 0)[parity]
        else -> intArrayOf(0, 1, 1, 3)[parity]
    }
}
