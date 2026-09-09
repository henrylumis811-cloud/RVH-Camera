package com.rvh.camera.imaging

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Final linear-RGB photographic rendering for the RAW path.
 *
 * Uses a luminance-domain soft shoulder instead of clipping channels independently. This keeps
 * highlight hue relationships intact and preserves useful sensor headroom until the last stage.
 */
class RawToneMapper {
    fun apply(rgb: RawDevelopmentPipeline.LinearRgb, strength: Float = 1f): RawDevelopmentPipeline.LinearRgb {
        val n = rgb.width * rgb.height
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val shoulderStrength = strength.coerceIn(0f, 1f)

        for (i in 0 until n) {
            val rr = rgb.r[i].coerceAtLeast(0f)
            val gg = rgb.g[i].coerceAtLeast(0f)
            val bb = rgb.b[i].coerceAtLeast(0f)

            // Stage 50 audit: this class is the final scene-linear boundary. Do not apply an
            // SDR luminance shoulder here: that would destroy the headroom that the JPEG/R
            // encoder is supposed to preserve. SDR tone mapping belongs exclusively in the SDR
            // encoder; HDR/PQ receives the retained scene-linear headroom.
            val y = (0.2126f * rr + 0.7152f * gg + 0.0722f * bb).coerceAtLeast(1e-6f)
            val peak = max(rr, max(gg, bb))
            val chromaPressure = ((peak / max(y, 1e-6f)) - 1.35f).coerceAtLeast(0f)
            val compression = (chromaPressure / 3.0f * shoulderStrength).coerceIn(0f, 0.35f)
            val keep = 1f - compression
            r[i] = (y + (rr - y) * keep).coerceIn(0f, 4f)
            g[i] = (y + (gg - y) * keep).coerceIn(0f, 4f)
            b[i] = (y + (bb - y) * keep).coerceIn(0f, 4f)
        }
        return RawDevelopmentPipeline.LinearRgb(rgb.width, rgb.height, r, g, b, rgb.metadata)
    }
}
