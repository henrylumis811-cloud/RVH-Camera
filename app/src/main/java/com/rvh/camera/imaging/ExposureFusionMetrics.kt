package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Region-aware exposure confidence for computational HDR/NIGHT fusion.
 *
 * This does not attempt to reconstruct a linear sensor response from JPEG/YUV. Instead it answers
 * the safer question: "is this candidate more informative than the reference at this pixel?"
 * The reference remains the visual anchor and ordinary PHOTO fusion keeps the conservative path.
 */
object ExposureFusionMetrics {
    fun candidateConfidence(
        referenceValue: Int,
        candidateValue: Int,
        referenceExposureNs: Long?,
        candidateExposureNs: Long?,
        intent: CaptureIntent,
    ): Float {
        if (intent == CaptureIntent.PHOTO ||
            referenceExposureNs == null || candidateExposureNs == null ||
            referenceExposureNs <= 0L || candidateExposureNs <= 0L
        ) {
            return 1f
        }

        val ref = referenceValue.coerceIn(0, 255)
        val candidate = candidateValue.coerceIn(0, 255)

        val refShadow = smoothRegion(ref, 32, 92)
        val refHighlight = smoothRegion(ref, 168, 238)
        val candidateShadow = smoothRegion(candidate, 32, 92)
        val candidateHighlight = smoothRegion(candidate, 168, 238)

        return when (intent) {
            CaptureIntent.HDR -> {
                // In HDR, prefer the frame that is not clipped in a highlight or crushed in a
                // shadow. Midtones remain deliberately close to neutral so HDR does not flatten
                // the scene or replace the reference unnecessarily.
                val shadowBenefit = (refShadow * candidateShadow).coerceIn(0f, 1f)
                val highlightBenefit = (refHighlight * candidateHighlight).coerceIn(0f, 1f)
                val candidateMid = midtoneConfidence(candidate)
                val refMid = midtoneConfidence(ref)

                val usefulShadow = if (ref <= 48 && candidate > ref) {
                    0.35f + 0.65f * shadowBenefit
                } else 0.35f
                val usefulHighlight = if (ref >= 232 && candidate < ref) {
                    0.35f + 0.65f * highlightBenefit
                } else 0.35f

                val exposureRatio = candidateExposureNs.toDouble() / referenceExposureNs.toDouble()
                val exposureDifference = kotlin.math.abs(kotlin.math.ln(exposureRatio))
                val bracketStrength = (exposureDifference / kotlin.math.ln(2.5)).toFloat()
                    .coerceIn(0f, 1f)

                (0.30f + 0.45f * bracketStrength * max(usefulShadow, usefulHighlight) +
                    0.25f * max(candidateMid, refMid)).coerceIn(0f, 1f)
            }

            CaptureIntent.NIGHT -> {
                // Night capture benefits primarily from brighter, non-clipped shadow detail.
                // Avoid encouraging already-hot highlights to contribute more.
                val shadowGain = if (ref < 100) {
                    ((candidate - ref).toFloat() / 100f).coerceIn(0f, 1f)
                } else 0f
                val highlightSafety = if (candidate > 242) 0.08f else 1f
                val exposureRatio = candidateExposureNs.toDouble() / referenceExposureNs.toDouble()
                val temporalDifference = (kotlin.math.abs(kotlin.math.ln(exposureRatio)) / kotlin.math.ln(4.0))
                    .toFloat().coerceIn(0f, 1f)

                (0.55f + 0.30f * shadowGain + 0.15f * temporalDifference)
                    .coerceIn(0f, 1f) * highlightSafety
            }

            CaptureIntent.PORTRAIT -> 1f
            CaptureIntent.PHOTO -> 1f
        }
    }

    private fun smoothRegion(value: Int, start: Int, end: Int): Float {
        if (value <= start) return 0f
        if (value >= end) return 1f
        return (value - start).toFloat() / (end - start).toFloat()
    }

    private fun midtoneConfidence(value: Int): Float {
        val distance = abs(value - 128).toFloat() / 128f
        return (1f - distance).coerceIn(0f, 1f)
    }
}
