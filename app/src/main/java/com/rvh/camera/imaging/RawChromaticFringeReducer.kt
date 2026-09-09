package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Evidence-gated chromatic-fringe suppression for developed RAW RGB.
 *
 * This is deliberately not a lens model: Camera2 does not expose per-channel CA
 * coefficients through the fields used by the RAW path. Instead we look for a very
 * specific image signature of lateral colour fringing: a narrow chroma residual that
 * sits on a strong luminance edge but is not explained by the underlying green edge.
 *
 * The operation only changes R-G/B-G residuals, preserving green/luma structure.
 * Natural coloured edges, clipped highlights, weak edges and noisy pixels are left alone.
 */
class RawChromaticFringeReducer {
    fun apply(input: RawDevelopmentPipeline.LinearRgb): RawDevelopmentPipeline.LinearRgb {
        val w = input.width
        val h = input.height
        if (w < 7 || h < 7) return input

        val outR = input.r.copyOf()
        val outG = input.g.copyOf()
        val outB = input.b.copyOf()

        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                val g0 = input.g[i]
                val luma = (0.2126f * input.r[i] + 0.7152f * g0 + 0.0722f * input.b[i])
                if (luma <= 0.015f || luma >= 1.015f) continue

                val gx = abs(input.g[i + 1] - input.g[i - 1])
                val gy = abs(input.g[i + w] - input.g[i - w])
                val useHorizontal = gx >= gy
                val greenGradient = max(gx, gy)
                if (greenGradient < 0.018f) continue

                val chromaRG = fringeCorrection(
                    channel = input.r,
                    green = input.g,
                    index = i,
                    step = if (useHorizontal) 1 else w,
                    greenGradient = greenGradient,
                    metadata = input.metadata,
                )
                val chromaBG = fringeCorrection(
                    channel = input.b,
                    green = input.g,
                    index = i,
                    step = if (useHorizontal) 1 else w,
                    greenGradient = greenGradient,
                    metadata = input.metadata,
                )

                if (chromaRG <= 0f && chromaBG <= 0f) continue

                val correctionScale = when {
                    greenGradient >= 0.12f -> 0.32f
                    greenGradient >= 0.06f -> 0.27f
                    else -> 0.20f
                }

                if (chromaRG > 0f) {
                    val d = input.r[i] - g0
                    outR[i] = (input.r[i] - d * chromaRG * correctionScale).coerceIn(0f, 4f)
                }
                if (chromaBG > 0f) {
                    val d = input.b[i] - g0
                    outB[i] = (input.b[i] - d * chromaBG * correctionScale).coerceIn(0f, 4f)
                }
            }
        }

        return input.copy(r = outR, g = outG, b = outB)
    }

    /** Returns a confidence-weighted correction amount in [0,1]. */
    private fun fringeCorrection(
        channel: FloatArray,
        green: FloatArray,
        index: Int,
        step: Int,
        greenGradient: Float,
        metadata: FrameMetadata,
    ): Float {
        val dM2 = channel[index - 2 * step] - green[index - 2 * step]
        val dM1 = channel[index - step] - green[index - step]
        val d0 = channel[index] - green[index]
        val dP1 = channel[index + step] - green[index + step]
        val dP2 = channel[index + 2 * step] - green[index + 2 * step]

        val sideBaseline = 0.25f * (dM2 + dM1 + dP1 + dP2)
        val residual = d0 - sideBaseline
        val residualMagnitude = abs(residual)

        val localSignal = max(
            0.01f,
            0.25f * (abs(channel[index - 2 * step]) + abs(channel[index - step]) +
                abs(channel[index + step]) + abs(channel[index + 2 * step])) + abs(channel[index]),
        )
        val noiseFloor = estimateNoiseFloor(metadata.sensorNoiseProfile, localSignal)
        val threshold = max(0.006f, noiseFloor * 2.2f)
        if (residualMagnitude <= threshold) return 0f

        // A natural colour edge normally changes R-G/B-G in the same direction as
        // the green luminance edge. A fringe instead tends to overshoot and decay.
        val chromaSlope = dP1 - dM1
        val greenSlope = green[index + step] - green[index - step]
        val slopeMagnitude = abs(chromaSlope)
        val slopeMismatch = if (abs(greenSlope) < 0.008f) {
            1f
        } else {
            val signedAgreement = chromaSlope * greenSlope
            when {
                signedAgreement < 0f -> 1f
                slopeMagnitude < abs(greenSlope) * 0.12f -> 0.75f
                slopeMagnitude > abs(greenSlope) * 2.8f -> 0.85f
                else -> 0f
            }
        }

        val leftDecay = abs(dM1 - dM2)
        val rightDecay = abs(dP1 - dP2)
        val haloShape = when {
            residual * (dM1 - sideBaseline) < 0f && residual * (dP1 - sideBaseline) < 0f -> 1f
            leftDecay > residualMagnitude * 0.18f || rightDecay > residualMagnitude * 0.18f -> 0.55f
            else -> 0f
        }

        // Require both strong chroma evidence and either an edge-direction mismatch
        // or a narrow halo-like shape. This is the main guard against altering real
        // coloured objects.
        val evidence = max(slopeMismatch, haloShape)
        if (evidence < 0.55f) return 0f

        val edgeConfidence = ((greenGradient - 0.018f) / 0.10f).coerceIn(0f, 1f)
        val chromaConfidence = ((residualMagnitude - threshold) / max(0.02f, threshold * 3f)).coerceIn(0f, 1f)
        return (0.25f + 0.75f * edgeConfidence) * (0.25f + 0.75f * chromaConfidence) * evidence
    }

    private fun estimateNoiseFloor(profile: FloatArray?, signal: Float): Float {
        if (profile == null || profile.size < 8) return 0.0045f
        var sum = 0f
        var count = 0
        for (c in 0 until 4) {
            val s = profile[c * 2]
            val o = profile[c * 2 + 1]
            if (s.isFinite() && o.isFinite() && s >= 0f && o >= 0f) {
                sum += kotlin.math.sqrt(max(0f, s * signal + o))
                count++
            }
        }
        if (count == 0) return 0.0045f
        return (sum / count).coerceIn(0.0015f, 0.05f)
    }
}
