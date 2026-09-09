package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Luma-preserving chroma cleanup for developed RAW RGB.
 *
 * The reducer only smooths chroma residuals. It deliberately leaves the luminance structure
 * alone, so fine edges and texture are not blurred merely because high-ISO colour noise exists.
 */
class RawChromaNoiseReducer {
    fun apply(rgb: RawDevelopmentPipeline.LinearRgb, strength: Float = 1f): RawDevelopmentPipeline.LinearRgb {
        val w = rgb.width
        val h = rgb.height
        if (w < 3 || h < 3) return rgb
        val outR = rgb.r.copyOf()
        val outB = rgb.b.copyOf()
        val s = strength.coerceIn(0f, 1f)

        fun lum(i: Int): Float = 0.2126f * rgb.r[i] + 0.7152f * rgb.g[i] + 0.0722f * rgb.b[i]
        fun clampIndex(x: Int, y: Int): Int = y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)

        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = lum(i).coerceAtLeast(1e-4f)
            val cr = rgb.r[i] - rgb.g[i]
            val cb = rgb.b[i] - rgb.g[i]
            var sumCr = 0f
            var sumCb = 0f
            var weight = 0f
            var neighbourLuma = 0f
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val j = clampIndex(x + dx, y + dy)
                val nl = lum(j)
                val lumaDelta = abs(nl - l) / max(0.02f, l)
                val wt = 1f / (1f + 6f * lumaDelta)
                sumCr += (rgb.r[j] - rgb.g[j]) * wt
                sumCb += (rgb.b[j] - rgb.g[j]) * wt
                neighbourLuma += nl * wt
                weight += wt
            }
            if (weight <= 0f) continue
            val meanCr = sumCr / weight
            val meanCb = sumCb / weight
            val noiseSuspect = (1f - min(1f, l * 3.5f)) * 0.65f + 0.15f
            val localStrength = (s * noiseSuspect).coerceIn(0f, 0.75f)
            val edgeProtection = (1f - min(1f, abs(neighbourLuma / weight - l) / max(0.02f, l) * 2f)).coerceIn(0f, 1f)
            val amount = localStrength * edgeProtection
            outR[i] = (rgb.g[i] + cr * (1f - amount) + meanCr * amount).coerceIn(0f, 4f)
            outB[i] = (rgb.g[i] + cb * (1f - amount) + meanCb * amount).coerceIn(0f, 4f)
        }
        return RawDevelopmentPipeline.LinearRgb(w, h, outR, rgb.g.copyOf(), outB, rgb.metadata)
    }
}
