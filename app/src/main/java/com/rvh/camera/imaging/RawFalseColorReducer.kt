package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative post-demosaic false-colour suppression.
 *
 * Demosaicing can create narrow R/B oscillations along high-frequency edges even when
 * the underlying scene chroma is smooth. This stage only suppresses a chroma residual
 * when a strong luminance edge and a rapidly changing local chroma residual agree.
 */
class RawFalseColorReducer {
    fun apply(rgb: RawDevelopmentPipeline.LinearRgb): RawDevelopmentPipeline.LinearRgb {
        val w = rgb.width
        val h = rgb.height
        if (w < 5 || h < 5) return rgb

        val r = rgb.r.copyOf()
        val g = rgb.g.copyOf()
        val b = rgb.b.copyOf()
        val originalR = rgb.r
        val originalG = rgb.g
        val originalB = rgb.b

        fun idx(x: Int, y: Int) = y * w + x
        fun clamp01(v: Float) = v.coerceIn(0f, 4f)

        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = idx(x, y)
            val gx = abs(originalG[idx(x + 1, y)] - originalG[idx(x - 1, y)])
            val gy = abs(originalG[idx(x, y + 1)] - originalG[idx(x, y - 1)])
            val edge = max(gx, gy)
            if (edge < 0.035f) continue

            // Estimate chroma at the centre and along the dominant edge normal.
            val horizontal = gx >= gy
            val offsets = if (horizontal) intArrayOf(-2, -1, 1, 2) else intArrayOf(-2, -1, 1, 2)
            var meanRg = 0f
            var meanBg = 0f
            var weight = 0f
            for (d in offsets) {
                val sx = if (horizontal) x + d else x
                val sy = if (horizontal) y else y + d
                val j = idx(sx, sy)
                val wgt = 1f / (1f + abs(d))
                meanRg += (originalR[j] - originalG[j]) * wgt
                meanBg += (originalB[j] - originalG[j]) * wgt
                weight += wgt
            }
            if (weight <= 0f) continue
            meanRg /= weight
            meanBg /= weight

            val centreRg = originalR[i] - originalG[i]
            val centreBg = originalB[i] - originalG[i]
            val residualRg = centreRg - meanRg
            val residualBg = centreBg - meanBg

            // Natural coloured edges usually maintain their chroma over several samples;
            // a demosaic false-colour spike is narrow and substantially larger than its
            // immediate neighbourhood. Require both channels to behave consistently or
            // one channel to be an isolated strong offender.
            val chromaScale = 0.015f + 0.18f * edge
            val rgStrong = abs(residualRg) > chromaScale
            val bgStrong = abs(residualBg) > chromaScale
            if (!rgStrong && !bgStrong) continue

            val rNeighbourSpread = localSpread(originalR, originalG, x, y, w, h, horizontal)
            val bNeighbourSpread = localSpread(originalB, originalG, x, y, w, h, horizontal)
            val isolatedRg = rgStrong && abs(residualRg) > rNeighbourSpread * 1.35f + 0.008f
            val isolatedBg = bgStrong && abs(residualBg) > bNeighbourSpread * 1.35f + 0.008f
            if (!isolatedRg && !isolatedBg) continue

            // Avoid attacking saturated highlights or broad strong chroma objects.
            val peak = max(originalR[i], max(originalG[i], originalB[i]))
            val minChannel = min(originalR[i], min(originalG[i], originalB[i]))
            if (peak > 3.75f || (peak > 1.0f && minChannel > 0.9f * peak)) continue

            val strength = ((edge - 0.035f) / 0.25f).coerceIn(0f, 1f)
            val shrink = 0.12f + 0.23f * strength
            if (isolatedRg) r[i] = clamp01(originalR[i] - residualRg * shrink)
            if (isolatedBg) b[i] = clamp01(originalB[i] - residualBg * shrink)
        }

        return RawDevelopmentPipeline.LinearRgb(w, h, r, g, b, rgb.metadata)
    }

    private fun localSpread(
        channel: FloatArray,
        green: FloatArray,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        horizontal: Boolean,
    ): Float {
        var sum = 0f
        var count = 0
        for (d in intArrayOf(-2, -1, 1, 2)) {
            val sx = if (horizontal) x + d else x
            val sy = if (horizontal) y else y + d
            if (sx !in 0 until w || sy !in 0 until h) continue
            sum += abs((channel[sy * w + sx] - green[sy * w + sx]))
            count++
        }
        return if (count == 0) 0.01f else sum / count
    }
}
