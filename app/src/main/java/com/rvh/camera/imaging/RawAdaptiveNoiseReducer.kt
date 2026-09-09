package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Conservative sensor-domain denoiser for fused Bayer mosaics.
 *
 * It uses the per-CFA-channel Camera2 noise model N(x)=sqrt(S*x+O), but only removes
 * a bounded residual toward same-CFA neighbours. Strong gradients and isolated detail
 * therefore retain substantially more of their original signal than flat regions.
 */
class RawAdaptiveNoiseReducer {
    fun apply(mosaic: RawLinearMosaic, strength: Float = 1f): RawLinearMosaic {
        val w = mosaic.width
        val h = mosaic.height
        if (w < 7 || h < 7) return mosaic

        val source = mosaic.values
        val out = source.copyOf()
        val s = strength.coerceIn(0f, 1f)
        val metadata = mosaic.metadata
        val profile = metadata.sensorNoiseProfile
        val iso = (metadata.sensitivityIso ?: 100).coerceAtLeast(50)

        fun channel(x: Int, y: Int): Int = cfaChannel(mosaic.cfaArrangement, x, y).coerceIn(0, 3)
        fun noiseAt(value: Float, c: Int): Float {
            if (profile == null || profile.size < 2) {
                val isoFactor = sqrt(iso / 100f).coerceIn(0.7f, 3f)
                return (0.006f + 0.018f * isoFactor * sqrt(value.coerceIn(0f, 1f) + 0.02f))
                    .coerceIn(0.003f, 0.08f)
            }
            val base = (c * 2).coerceAtMost(profile.size - 2)
            val snrS = profile[base].coerceAtLeast(0f)
            val snrO = profile[base + 1].coerceAtLeast(0f)
            return sqrt((snrS * value.coerceIn(0f, 1f) + snrO).coerceAtLeast(0f))
                .coerceIn(0.001f, 0.12f)
        }

        // A 5x5 same-plane neighbourhood. Because Bayer samples of the same channel are
        // separated by two pixels, every candidate below remains on the exact CFA plane.
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                val c = channel(x, y)
                val center = source[i]
                var weighted = 0f
                var weight = 0f
                var strongestGradient = 0f

                for (dy in -2..2 step 2) {
                    for (dx in -2..2 step 2) {
                        if (dx == 0 && dy == 0) continue
                        val sx = x + dx
                        val sy = y + dy
                        if (channel(sx, sy) != c) continue
                        val j = sy * w + sx
                        val neighbour = source[j]
                        val distance = abs(neighbour - center)
                        strongestGradient = max(strongestGradient, distance)
                        val localNoise = max(noiseAt(center, c), noiseAt(neighbour, c))
                        val similarity = 1f / (1f + distance / max(0.004f, localNoise * 2.2f))
                        val spatial = if (abs(dx) + abs(dy) == 2) 1f else 0.7f
                        val wt = similarity * spatial
                        weighted += neighbour * wt
                        weight += wt
                    }
                }

                if (weight <= 0f) continue
                val mean = weighted / weight
                val noise = noiseAt(center, c)
                val residual = mean - center
                val noiseGate = (abs(residual) / max(0.004f, noise * 1.8f)).coerceIn(0f, 1f)
                val edgeGate = (1f - strongestGradient / max(0.01f, noise * 5.5f)).coerceIn(0f, 1f)
                val flatness = (1f - strongestGradient / max(0.02f, noise * 8f)).coerceIn(0f, 1f)
                val amount = (0.38f * s * noiseGate * (0.35f + 0.65f * flatness) * edgeGate)
                    .coerceIn(0f, 0.32f)
                out[i] = (center + residual * amount).coerceIn(0f, 1.05f)
            }
        }

        return mosaic.copy(values = out)
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
}
