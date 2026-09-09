package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Produces conservative frame-level weights before pixel-level agreement is applied.
 *
 * The first fusion tier is intentionally optimized for ordinary PHOTO captures where frames are
 * expected to be exposed similarly. HDR/NIGHT will eventually use a different exposure model.
 */
object FusionWeightModel {
    fun weight(
        reference: Yuv420Frame,
        candidate: Yuv420Frame,
        intent: CaptureIntent,
    ): Float {
        val referenceQuality = FrameQualityScorer.score(reference)
        val candidateQuality = FrameQualityScorer.score(candidate)

        var weight = (0.35f + candidateQuality.overall * 0.65f)

        // Lower ISO generally means less amplification noise. Use a soft ratio rather than a
        // hard preference so a sharper frame is not discarded merely because its ISO is higher.
        val refIso = reference.metadata.sensitivityIso
        val candidateIso = candidate.metadata.sensitivityIso
        if (refIso != null && candidateIso != null && refIso > 0 && candidateIso > 0) {
            val ratio = candidateIso.toFloat() / refIso.toFloat()
            val noisePenalty = (1f / kotlin.math.sqrt(max(1f, ratio)))
            weight *= (0.72f + 0.28f * noisePenalty).coerceIn(0.72f, 1f)
        }

        val refExposure = reference.metadata.exposureTimeNs
        val candidateExposure = candidate.metadata.exposureTimeNs
        if (intent == CaptureIntent.PHOTO && refExposure != null && candidateExposure != null &&
            refExposure > 0L && candidateExposure > 0L
        ) {
            val ratio = max(refExposure, candidateExposure).toDouble() /
                min(refExposure, candidateExposure).toDouble()
            // Ordinary multi-frame fusion should not silently turn into HDR. Similar exposure
            // receives full weight; increasingly different exposure receives a soft penalty.
            val exposurePenalty = (1.0 - 0.20 * ln(ratio)).coerceIn(0.55, 1.0).toFloat()
            weight *= exposurePenalty
        }

        // Avoid feeding heavily clipped frames into ordinary noise reduction. HDR gets to keep
        // them because its future exposure-aware compositor will treat highlights specially.
        if (intent != CaptureIntent.HDR) {
            val clipping = candidateQuality.highlightClipping * 0.65f +
                candidateQuality.shadowClipping * 0.35f
            weight *= (1f - clipping * 0.65f).coerceIn(0.35f, 1f)
        }

        // A frame that is substantially less exposed than the reference is still useful for
        // detail, but should not dominate the reference's overall brightness in PHOTO mode.
        if (intent == CaptureIntent.PHOTO) {
            val exposureSimilarity = 1f - abs(referenceQuality.exposure - candidateQuality.exposure)
            weight *= (0.80f + exposureSimilarity * 0.20f).coerceIn(0.80f, 1f)
        }

        return weight.coerceIn(0f, 1f)
    }
}
