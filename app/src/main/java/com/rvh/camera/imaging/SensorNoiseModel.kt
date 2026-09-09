package com.rvh.camera.imaging

import kotlin.math.ln

/**
 * Small, conservative sensor-noise model used to scale CPU denoising.
 *
 * It deliberately models only what can be inferred safely from Camera2 metadata: gain (ISO) and
 * exposure time. It is not a replacement for a factory-calibrated photon/read-noise curve, but it
 * prevents the common mistake of treating every high-ISO frame as equally noisy.
 */
object SensorNoiseModel {
    /**
     * Camera2 SENSOR_NOISE_PROFILE follows N(x)=sqrt(S*x+O) for each CFA channel.
     * Return a normalized estimate suitable for the YUV finishing stage. Because YUV has already
     * passed through the HAL, this is used as a confidence/strength signal rather than as a raw
     * Bayer-domain noise correction.
     */
    fun estimateProfileNoise(profile: FloatArray?, iso: Int?, normalizedSignal: Float = 0.5f): Float? {
        if (profile == null || profile.size < 2) return null
        val pairs = profile.size / 2
        var sum = 0f
        for (channel in 0 until pairs) {
            val s = profile[channel * 2].coerceAtLeast(0f)
            val o = profile[channel * 2 + 1].coerceAtLeast(0f)
            sum += kotlin.math.sqrt((s * normalizedSignal.coerceIn(0f, 1f) + o).coerceAtLeast(0f))
        }
        if (pairs == 0) return null
        // ISO scaling is intentionally weak: Camera2's profile already describes the current
        // sensor amplification in the capture result on compliant devices.
        val raw = (sum / pairs).coerceAtLeast(0f)
        val scale = ((iso ?: 100).coerceAtLeast(50) / 100f).let { kotlin.math.sqrt(it).coerceIn(0.7f, 4f) }
        return (raw * scale * 3.0f).coerceIn(0f, 1f)
    }

    fun estimateIsoNoise(iso: Int): Float {
        val safeIso = iso.coerceAtLeast(50).toFloat()
        val normalized = (ln(safeIso / 100f) / ln(16f)).coerceIn(0f, 1f)
        return (0.04f + normalized * 0.96f).coerceIn(0f, 1f)
    }

    fun estimateExposureNoise(exposureTimeNs: Long): Float {
        val safeNs = exposureTimeNs.coerceAtLeast(250_000L).toDouble()
        // Around 8 ms is treated as the neutral reference. Very short exposures tend to be
        // photon-starved; longer exposures get progressively more efficient until motion becomes
        // the limiting factor, which is handled elsewhere by frame selection/alignment.
        val ratio = (8_000_000.0 / safeNs).coerceIn(0.125, 16.0)
        return (ln(ratio) / ln(16.0)).toFloat().coerceIn(0f, 1f)
    }
}
