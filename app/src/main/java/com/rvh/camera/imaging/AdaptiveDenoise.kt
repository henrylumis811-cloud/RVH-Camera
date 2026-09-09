package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max

/**
 * Edge-aware single-frame denoise used when sensor noise remains after HAL processing or when
 * only one computational frame is available. It is intentionally small and bounded: flat areas
 * receive stronger smoothing while edges retain their original structure.
 */
class AdaptiveDenoise(
    private val lumaStrength: Float = 0.42f,
    private val chromaStrength: Float = 0.68f,
) {
    fun apply(
        frame: Yuv420Frame,
        intent: CaptureIntent,
        contributingFrames: Int = 1,
    ): Yuv420Frame {
        val y = ByteArray(frame.y.size)
        val width = frame.width
        val height = frame.height

        // Chroma is substantially noisier at 4:2:0 and has lower spatial resolution, so it can
        // be filtered more strongly without visibly destroying fine detail.
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val u = frame.u.copyOf()
        val v = frame.v.copyOf()

        // Do not blur clean daylight frames merely because a denoise stage exists. The strength
        // is driven by sensor ISO and by how many frames already contributed to the fusion.
        // Multi-frame fusion provides its own noise reduction, so a clean fused frame needs only
        // a light finishing pass. Single-frame high-ISO captures get progressively stronger
        // filtering. This is much safer for fine texture than a fixed global denoise amount.
        val iso = frame.metadata.sensitivityIso ?: 100
        val exposureNs = frame.metadata.exposureTimeNs ?: 8_000_000L
        val isoNoise = SensorNoiseModel.estimateIsoNoise(iso)
        val exposureNoise = SensorNoiseModel.estimateExposureNoise(exposureNs)
        // Photon noise falls as exposure and ISO improve, while read noise becomes more visible
        // at high gain. Combine both terms instead of using ISO alone so bright long-exposure
        // night frames are not over-smoothed.
        val metadataNoise = SensorNoiseModel.estimateProfileNoise(
            frame.metadata.sensorNoiseProfile,
            iso,
        )
        val sensorNoise = if (metadataNoise != null) {
            // Prefer the calibrated profile, but retain a small exposure term because the
            // profile describes sensor noise, not scene motion or photon starvation by itself.
            (0.78f * metadataNoise + 0.14f * isoNoise + 0.08f * exposureNoise).coerceIn(0f, 1f)
        } else {
            (0.68f * isoNoise + 0.32f * exposureNoise).coerceIn(0f, 1f)
        }
        val fusionRelief = when {
            contributingFrames >= 3 -> 0.42f
            contributingFrames == 2 -> 0.62f
            else -> 1f
        }
        val intentScale = when (intent) {
            CaptureIntent.NIGHT -> 1.35f
            CaptureIntent.HDR -> 0.90f
            CaptureIntent.PORTRAIT -> 0.82f
            CaptureIntent.PHOTO -> 0.72f
        }
        val strength = (lumaStrength * (0.06f + sensorNoise * 0.94f) * intentScale * fusionRelief)
            .coerceIn(0.025f, 0.80f)

        // Preserve the boundary and use a 4-neighbour edge-aware kernel in the interior.
        System.arraycopy(frame.y, 0, y, 0, frame.y.size)
        for (row in 1 until height - 1) {
            val base = row * width
            for (col in 1 until width - 1) {
                val i = base + col
                val center = frame.y[i].toInt() and 0xFF
                val left = frame.y[i - 1].toInt() and 0xFF
                val right = frame.y[i + 1].toInt() and 0xFF
                val up = frame.y[i - width].toInt() and 0xFF
                val down = frame.y[i + width].toInt() and 0xFF

                val differences = intArrayOf(abs(left - center), abs(right - center), abs(up - center), abs(down - center))
                var weighted = center.toFloat()
                var total = 1f
                for (difference in differences.indices) {
                    val d = differences[difference]
                    val neighbour = when (difference) {
                        0 -> left
                        1 -> right
                        2 -> up
                        else -> down
                    }
                    // Strong edges get almost no contribution from the opposite side. Flat
                    // regions converge toward their local mean and suppress sensor grain.
                    val edgeProtection = (1f - d / 42f).coerceIn(0f, 1f)
                    val weight = strength * edgeProtection
                    weighted += neighbour * weight
                    total += weight
                }
                y[i] = (weighted / total).toInt().coerceIn(0, 255).toByte()
            }
        }

        val chroma = (chromaStrength * (0.22f + sensorNoise * 0.78f) * when (intent) {
            CaptureIntent.NIGHT -> 1.05f
            CaptureIntent.HDR -> 0.95f
            else -> 1f
        } * fusionRelief).coerceIn(0.06f, 0.90f)

        smoothChroma(frame.u, u, cw, ch, chroma)
        smoothChroma(frame.v, v, cw, ch, chroma)

        return Yuv420Frame(frame.metadata, width, height, y, u, v)
    }

    private fun smoothChroma(source: ByteArray, output: ByteArray, width: Int, height: Int, strength: Float) {
        if (width < 3 || height < 3) return
        for (row in 1 until height - 1) {
            val base = row * width
            for (col in 1 until width - 1) {
                val i = base + col
                val center = source[i].toInt() and 0xFF
                val neighbours = intArrayOf(source[i - 1].toInt() and 0xFF, source[i + 1].toInt() and 0xFF,
                    source[i - width].toInt() and 0xFF, source[i + width].toInt() and 0xFF)
                var sum = center.toFloat()
                var weight = 1f
                for (value in neighbours) {
                    val w = strength * (1f - abs(value - center) / 64f).coerceIn(0f, 1f)
                    sum += value * w
                    weight += w
                }
                output[i] = (sum / max(1f, weight)).toInt().coerceIn(0, 255).toByte()
            }
        }
    }
}
