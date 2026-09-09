package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative single-channel highlight recovery after demosaic.
 *
 * A channel that clips while the other two retain useful headroom still contains colour
 * information in its neighbours. We recover only when a stable local colour ratio exists;
 * if every channel is saturated, or the ratio is unstable, the clipped value is preserved.
 * This is intentionally a reconstruction pass, not a hallucination pass.
 */
class RawHighlightReconstructor(
    private val saturation: Float = 1.02f,
    private val usableNeighbour: Float = 0.92f,
) {
    fun apply(rgb: RawDevelopmentPipeline.LinearRgb): RawDevelopmentPipeline.LinearRgb {
        val w = rgb.width
        val h = rgb.height
        val outR = rgb.r.copyOf()
        val outG = rgb.g.copyOf()
        val outB = rgb.b.copyOf()

        fun reconstruct(target: Int, index: Int): Float? {
            val x = index % w
            val y = index / w
            val ratios = ArrayList<Float>(8)
            val offsets = arrayOf(
                intArrayOf(-2, 0), intArrayOf(2, 0),
                intArrayOf(0, -2), intArrayOf(0, 2),
            )

            for (d in offsets) {
                val sx = x + d[0]
                val sy = y + d[1]
                if (sx !in 0 until w || sy !in 0 until h) continue
                val i = sy * w + sx
                val r = rgb.r[i]
                val g = rgb.g[i]
                val b = rgb.b[i]
                val reference = when (target) {
                    0 -> g
                    1 -> (r + b) * 0.5f
                    else -> g
                }
                val source = when (target) {
                    0 -> r
                    1 -> g
                    else -> b
                }
                if (reference <= 0.03f || source >= saturation) continue
                if (reference > usableNeighbour) continue
                val ratio = source / reference
                if (!ratio.isFinite() || ratio <= 0f || ratio > 4f) continue
                ratios += ratio
            }
            if (ratios.size < 3) return null
            ratios.sort()
            val median = ratios[ratios.size / 2]
            val deviations = ratios.map { abs(it - median) }.sorted()
            val mad = deviations[deviations.size / 2]
            val ratioMean = median
            val relativeSpread = mad / max(0.05f, abs(ratioMean))
            if (!relativeSpread.isFinite() || relativeSpread > 0.22f) return null

            val r = rgb.r[index]
            val g = rgb.g[index]
            val b = rgb.b[index]
            val base = when (target) {
                0 -> g
                1 -> (r + b) * 0.5f
                else -> g
            }
            if (base <= 0.03f || base > 1.25f) return null
            val estimated = (base * ratioMean).coerceIn(0f, 1.35f)
            // A clipped channel is allowed to be pulled back below the saturation ceiling when
            // neighbouring colour ratios consistently predict a lower value. This is the useful
            // case: the original sample has lost its channel detail, while nearby unsaturated
            // samples preserve the hue relationship. Never alter a merely bright, unsaturated
            // channel.
            val current = when (target) { 0 -> r; 1 -> g; else -> b }
            return if (current >= saturation && estimated < current - 0.015f) estimated else null
        }

        for (i in 0 until w * h) {
            if (rgb.r[i] >= saturation && rgb.g[i] < usableNeighbour) {
                reconstruct(0, i)?.let { outR[i] = it }
            }
            if (rgb.g[i] >= saturation && rgb.r[i] < usableNeighbour && rgb.b[i] < usableNeighbour) {
                reconstruct(1, i)?.let { outG[i] = it }
            }
            if (rgb.b[i] >= saturation && rgb.g[i] < usableNeighbour) {
                reconstruct(2, i)?.let { outB[i] = it }
            }
        }

        return rgb.copy(r = outR, g = outG, b = outB)
    }
}
