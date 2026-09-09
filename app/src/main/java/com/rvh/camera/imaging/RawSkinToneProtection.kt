package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative skin-colour protection after creative colour appearance.
 *
 * This is not beauty processing: it does not blur, smooth, brighten or reshape faces. It only
 * limits small hue/chroma excursions introduced by the photographic colour-appearance stage
 * when a pixel is strongly skin-like. Luminance and all spatial detail are preserved.
 */
class RawSkinToneProtection {
    fun apply(
        reference: RawDevelopmentPipeline.LinearRgb,
        input: RawDevelopmentPipeline.LinearRgb,
    ): RawDevelopmentPipeline.LinearRgb {
        require(reference.width == input.width && reference.height == input.height)

        val r = input.r.copyOf()
        val g = input.g.copyOf()
        val b = input.b.copyOf()

        for (i in r.indices) {
            val ref = toOklab(reference.r[i], reference.g[i], reference.b[i])
            val out = toOklab(r[i], g[i], b[i])
            val confidence = skinConfidence(
                reference.r[i], reference.g[i], reference.b[i], ref,
            )
            if (confidence <= 0.01f) continue

            // Preserve the creative result when it is already close to the calibrated colour.
            // Only a restrained fraction is pulled back toward the calibrated hue/chroma.
            val refHue = atan2(ref[2].toDouble(), ref[1].toDouble()).toFloat()
            val outHue = atan2(out[2].toDouble(), out[1].toDouble()).toFloat()
            val hueDelta = shortestAngle(refHue, outHue)
            val hueCorrection = (hueDelta * (0.28f * confidence)).coerceIn(-0.16f, 0.16f)
            val correctedHue = wrapAngle(outHue - hueCorrection)

            val refChroma = kotlin.math.sqrt(ref[1] * ref[1] + ref[2] * ref[2])
            val outChroma = kotlin.math.sqrt(out[1] * out[1] + out[2] * out[2])
            val chromaTarget = outChroma + (refChroma - outChroma) * (0.20f * confidence)

            out[1] = kotlin.math.cos(correctedHue) * chromaTarget
            out[2] = kotlin.math.sin(correctedHue) * chromaTarget
            // Deliberately retain out[0]: no skin brightening or texture manipulation.

            val rgb = fromOklab(out)
            r[i] = rgb[0].coerceIn(0f, 4f)
            g[i] = rgb[1].coerceIn(0f, 4f)
            b[i] = rgb[2].coerceIn(0f, 4f)
        }

        return input.copy(r = r, g = g, b = b)
    }

    private fun skinConfidence(
        r: Float,
        g: Float,
        b: Float,
        lab: FloatArray,
    ): Float {
        val y = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceAtLeast(0f)
        if (y < 0.055f || y > 0.92f) return 0f

        val maxRgb = max(r, max(g, b))
        val minRgb = min(r, min(g, b))
        val chroma = maxRgb - minRgb
        if (chroma < 0.025f || maxRgb <= 1e-5f) return 0f

        // Skin-like colours generally satisfy R >= G > B with moderate red/green dominance.
        val order = smoothStep((r - g + 0.025f) / 0.10f) *
            smoothStep((g - b + 0.035f) / 0.14f)
        val redRatio = (r / maxRgb).coerceIn(0f, 1f)
        val greenRatio = (g / maxRgb).coerceIn(0f, 1f)
        val balance = (1f - abs(redRatio - 1f).coerceAtMost(1f)) *
            smoothStep((greenRatio - 0.42f) / 0.48f)

        val hue = atan2(lab[2].toDouble(), lab[1].toDouble()).toFloat()
        val hueWeight = 1f - (shortestAngle(hue, 0.55f).let { abs(it) } / 0.58f).coerceIn(0f, 1f)
        val chromaWeight = smoothStep((chroma - 0.025f) / 0.32f) *
            (1f - smoothStep((chroma - 0.62f) / 0.48f))

        return (order * balance * hueWeight * chromaWeight).coerceIn(0f, 1f)
    }

    private fun smoothStep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun shortestAngle(a: Float, b: Float): Float {
        var d = b - a
        while (d > Math.PI.toFloat()) d -= 2f * Math.PI.toFloat()
        while (d < -Math.PI.toFloat()) d += 2f * Math.PI.toFloat()
        return d
    }

    private fun wrapAngle(angle: Float): Float {
        var a = angle
        while (a > Math.PI.toFloat()) a -= 2f * Math.PI.toFloat()
        while (a < -Math.PI.toFloat()) a += 2f * Math.PI.toFloat()
        return a
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
}
