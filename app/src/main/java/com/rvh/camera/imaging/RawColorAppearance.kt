package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Final linear-RGB colour appearance stage.
 *
 * This is intentionally separate from sensor calibration. Calibration establishes accurate
 * linear colour; this stage manages photographic saturation, hue stability and gamut pressure
 * without treating every channel independently. OKLab is used only as a perceptual working
 * space; the image remains scene-linear until the tone mapper.
 */
class RawColorAppearance {
    fun apply(input: RawDevelopmentPipeline.LinearRgb): RawDevelopmentPipeline.LinearRgb {
        val r = input.r.copyOf()
        val g = input.g.copyOf()
        val b = input.b.copyOf()

        for (i in r.indices) {
            var rr = r[i].coerceAtLeast(0f)
            var gg = g[i].coerceAtLeast(0f)
            var bb = b[i].coerceAtLeast(0f)

            val y = (0.2126f * rr + 0.7152f * gg + 0.0722f * bb).coerceAtLeast(1e-6f)
            val maxC = max(rr, max(gg, bb))
            val minC = min(rr, min(gg, bb))
            val chromaRatio = ((maxC - minC) / y).coerceIn(0f, 4f)

            // Mild photographic colour lift in ordinary tones. Stronger saturation is avoided
            // in shadows/highlights, where sensor uncertainty and clipping are highest.
            val shadowGuard = ((y - 0.08f) / 0.20f).coerceIn(0f, 1f)
            val highlightGuard = 1f - ((y - 0.72f) / 0.95f).coerceIn(0f, 1f)
            val vividGuard = 1f - ((chromaRatio - 0.85f) / 1.6f).coerceIn(0f, 1f)
            val baseSaturation = 1f + 0.045f * shadowGuard * highlightGuard * vividGuard

            var lab = toOklab(rr, gg, bb)
            val hue = kotlin.math.atan2(lab[2].toDouble(), lab[1].toDouble()).toFloat()
            val skinWeight = hueDistance(hue, 0.55f).let { d ->
                // Around the orange/skin sector, reduce aggressive saturation changes.
                (1f - d / 0.55f).coerceIn(0f, 1f)
            }
            val saturationScale = 1f + (baseSaturation - 1f) * (1f - 0.55f * skinWeight)
            lab[1] *= saturationScale
            lab[2] *= saturationScale

            // Soft gamut pressure: if a saturated colour would drive a channel negative or
            // excessively above display range, reduce chroma rather than clipping one channel.
            var compressed = lab
            repeat(6) {
                val rgb = fromOklab(compressed)
                val pressure = gamutPressure(rgb[0], rgb[1], rgb[2])
                if (pressure <= 0f) return@repeat
                val factor = (1f - 0.17f * pressure).coerceIn(0.35f, 1f)
                compressed = floatArrayOf(compressed[0], compressed[1] * factor, compressed[2] * factor)
            }

            val out = fromOklab(compressed)
            rr = out[0].coerceIn(0f, 4f)
            gg = out[1].coerceIn(0f, 4f)
            bb = out[2].coerceIn(0f, 4f)

            r[i] = rr
            g[i] = gg
            b[i] = bb
        }

        return input.copy(r = r, g = g, b = b)
    }

    private fun toOklab(r: Float, g: Float, b: Float): FloatArray {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val l3 = cbrt(l.coerceAtLeast(0f).toDouble()).toFloat()
        val m3 = cbrt(m.coerceAtLeast(0f).toDouble()).toFloat()
        val s3 = cbrt(s.coerceAtLeast(0f).toDouble()).toFloat()
        return floatArrayOf(
            0.2104542553f * l3 + 0.7936177850f * m3 - 0.0040720468f * s3,
            1.9779984951f * l3 - 2.4285922050f * m3 + 0.4505937099f * s3,
            0.0259040371f * l3 + 0.7827717662f * m3 - 0.8086757660f * s3,
        )
    }

    private fun fromOklab(lab: FloatArray): FloatArray {
        val l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
        val m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
        val s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        return floatArrayOf(
            4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3,
            -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3,
            -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3,
        )
    }

    private fun gamutPressure(r: Float, g: Float, b: Float): Float {
        val negative = max(0f, max(-r, max(-g, -b)))
        val high = max(0f, max(r - 1.15f, max(g - 1.15f, b - 1.15f)))
        return max(negative * 2.5f, high / 0.85f).coerceIn(0f, 1f)
    }

    private fun hueDistance(a: Float, b: Float): Float {
        var d = abs(a - b)
        if (d > Math.PI.toFloat()) d = (2f * Math.PI.toFloat()) - d
        return d
    }
}
