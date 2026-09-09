package com.rvh.camera.imaging

import kotlin.math.ln

/**
 * Cheap metadata-only quality estimate for temporal history admission.
 *
 * This deliberately does not inspect the full Y plane. A history admission decision happens on
 * the camera callback path, so doing a full-resolution sharpness scan here would steal time from
 * the shutter pipeline. Pixel-level quality remains the responsibility of the fusion/quality gate.
 */
object FrameHistoryQualityEstimator {
    fun estimate(
        metadata: FrameMetadata,
        referenceIso: Int? = null,
        referenceExposureNs: Long? = null,
    ): Float {
        val isoScore = metadata.sensitivityIso?.let { iso ->
            val reference = (referenceIso ?: 100).coerceAtLeast(50)
            // Penalize unusually high gain logarithmically; ISO 800 is not treated as eight times
            // worse than ISO 100, because real sensors do not behave that linearly.
            val ratio = iso.toFloat().coerceAtLeast(1f) / reference
            (1f - (ln(ratio.toDouble()) / ln(16.0)).toFloat()).coerceIn(0f, 1f)
        } ?: 0.65f

        val exposureScore = metadata.exposureTimeNs?.let { exposureNs ->
            val reference = referenceExposureNs ?: 10_000_000L
            val ratio = exposureNs.toDouble().coerceAtLeast(1.0) / reference.toDouble().coerceAtLeast(1.0)
            // Longer exposures are increasingly vulnerable to hand motion. Keep the penalty soft
            // because Night intentionally uses longer exposures.
            (1f - (ln(ratio.coerceAtLeast(1.0)) / ln(12.0)).toFloat() * 0.45f).coerceIn(0.45f, 1f)
        } ?: 0.70f

        val focusScore = metadata.focusDistance?.let { distance ->
            // Zero is the Camera2 convention for infinity focus and is a perfectly valid state.
            // Without scene depth we only reject obviously pathological values.
            if (distance >= 0f && distance.isFinite()) 1f else 0.55f
        } ?: 0.65f

        val stabilizationScore = if (metadata.oisDataAvailable) 1f else 0.85f

        return (isoScore * 0.42f + exposureScore * 0.30f +
            focusScore * 0.18f + stabilizationScore * 0.10f).coerceIn(0f, 1f)
    }
}
