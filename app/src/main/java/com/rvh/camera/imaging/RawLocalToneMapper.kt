package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative RAW-developed local tone adaptation.
 *
 * Compresses the low-frequency luminance field while preserving the high-frequency detail ratio.
 * This is deliberately weaker than a display HDR operator: the goal is to keep highlights and
 * shadows usable without creating halos or a synthetic "HDR" appearance.
 */
class RawLocalToneMapper(
    private val gridWidth: Int = 48,
    private val gridHeight: Int = 48,
) {
    fun apply(
        rgb: RawDevelopmentPipeline.LinearRgb,
        strength: Float = 0.28f,
    ): RawDevelopmentPipeline.LinearRgb {
        val w = rgb.width
        val h = rgb.height
        if (w < 8 || h < 8) return rgb

        val gw = min(gridWidth, max(8, w))
        val gh = min(gridHeight, max(8, h))
        val cellW = w.toFloat() / gw
        val cellH = h.toFloat() / gh
        val base = FloatArray(gw * gh)
        val count = IntArray(base.size)

        for (y in 0 until h) {
            val gy = min(gh - 1, (y / cellH).toInt())
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val luminance = (0.2126f * rgb.r[i] + 0.7152f * rgb.g[i] + 0.0722f * rgb.b[i])
                    .coerceAtLeast(0f)
                val gx = min(gw - 1, (x / cellW).toInt())
                val cell = gy * gw + gx
                base[cell] += luminance
                count[cell]++
            }
        }
        for (i in base.indices) {
            base[i] = if (count[i] == 0) 0f else base[i] / count[i]
        }

        // A compact edge-aware-ish blur of the coarse field. Since the field is already very
        // low-frequency, this removes tile structure without washing out scene boundaries.
        val smooth = FloatArray(base.size)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var sum = 0f
                var weight = 0f
                val center = base[gy * gw + gx]
                for (dy in -1..1) {
                    val yy = (gy + dy).coerceIn(0, gh - 1)
                    for (dx in -1..1) {
                        val xx = (gx + dx).coerceIn(0, gw - 1)
                        val neighbour = base[yy * gw + xx]
                        val spatial = when (abs(dx) + abs(dy)) { 0 -> 4f; 1 -> 2f; else -> 1f }
                        val range = 1f / (1f + abs(neighbour - center) * 3f)
                        val wgt = spatial * range
                        sum += neighbour * wgt
                        weight += wgt
                    }
                }
                smooth[gy * gw + gx] = if (weight > 0f) sum / weight else center
            }
        }

        val s = strength.coerceIn(0f, 0.55f)
        val outR = rgb.r.copyOf()
        val outG = rgb.g.copyOf()
        val outB = rgb.b.copyOf()

        for (y in 0 until h) {
            val fy = ((y + 0.5f) / cellH - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(gh - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / cellW - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(gw - 1, x0 + 1)
                val tx = fx - x0
                val b00 = smooth[y0 * gw + x0]
                val b10 = smooth[y0 * gw + x1]
                val b01 = smooth[y1 * gw + x0]
                val b11 = smooth[y1 * gw + x1]
                val localBase = ((b00 + (b10 - b00) * tx) * (1f - ty) +
                    (b01 + (b11 - b01) * tx) * ty).coerceAtLeast(1e-4f)

                val i = y * w + x
                val rr = rgb.r[i].coerceAtLeast(0f)
                val gg = rgb.g[i].coerceAtLeast(0f)
                val bb = rgb.b[i].coerceAtLeast(0f)
                val yIn = (0.2126f * rr + 0.7152f * gg + 0.0722f * bb).coerceAtLeast(1e-5f)

                // Preserve local texture as a bounded ratio around the slowly varying base.
                val detail = (yIn / localBase).coerceIn(0.68f, 1.48f)
                val shadow = ((0.30f - localBase) / 0.30f).coerceIn(0f, 1f)
                val highlight = ((localBase - 0.62f) / 0.78f).coerceIn(0f, 1f)

                // Gentle local exposure lift in dark regions and shoulder compression in bright
                // regions. Midtones remain close to identity.
                val liftedBase = localBase + s * 0.16f * shadow * (1f - localBase)
                val shoulder = if (liftedBase > 0.62f) {
                    val excess = liftedBase - 0.62f
                    liftedBase - s * 0.22f * highlight * excess / (1f + excess * 2.5f)
                } else liftedBase
                val targetY = (shoulder * detail).coerceAtLeast(0f)
                val scale = (targetY / yIn).coerceIn(0.72f, 1.28f)

                outR[i] = rr * scale
                outG[i] = gg * scale
                outB[i] = bb * scale
            }
        }

        return RawDevelopmentPipeline.LinearRgb(w, h, outR, outG, outB, rgb.metadata)
    }
}
