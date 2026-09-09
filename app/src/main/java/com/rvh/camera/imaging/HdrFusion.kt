package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Exposure-aware HDR compositor. Unlike ordinary temporal denoise, HDR frames are deliberately
 * allowed to disagree in luma. Each pixel is normalized by exposure*ISO, and the compositor
 * prefers the frame with useful headroom instead of averaging clipped values together.
 */
class HdrFusion {
    fun fuse(frames: List<Yuv420Frame>): FusionResult {
        require(frames.isNotEmpty())
        if (frames.size == 1) return FusionResult(frames.first(), 1, 0)
        val reference = frames[frames.size / 2]
        val valid = frames.filter { exposureFactor(it) > 0.0 }
        if (valid.size < 2) return FusionResult(reference, 1, frames.size - 1)
        val referenceFactor = exposureFactor(reference).takeIf { it > 0.0 } ?: exposureFactor(valid[0])

        val width = reference.width
        val height = reference.height
        val outY = ByteArray(width * height)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                var sum = 0.0
                var weightSum = 0.0
                for (frame in valid) {
                    val value = frame.y[i].toInt() and 0xFF
                    val factor = exposureFactor(frame) / referenceFactor
                    val normalized = srgbToLinear(value / 255.0) / factor
                    // Reliability peaks in the middle of a frame's usable range. Values close
                    // to either clipping end are still useful, but receive less weight.
                    val middle = 1.0 - abs(value - 128.0) / 128.0
                    val highlightSafety = if (value >= 248) 0.08 else 1.0
                    val shadowSafety = if (value <= 7) 0.18 else 1.0
                    val weight = (0.18 + 0.82 * middle) * highlightSafety * shadowSafety
                    sum += normalized * weight
                    weightSum += weight
                }
                val linear = if (weightSum > 0.0) sum / weightSum else 0.18
                outY[i] = (linearToSrgb(linear).coerceIn(0.0, 1.0) * 255.0).toInt().toByte()
            }
        }

        // Chroma follows the exposure-aware luma selection. Avoid averaging strongly different
        // chroma from bracket frames, which otherwise produces desaturated HDR colours.
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val outU = ByteArray(cw * ch)
        val outV = ByteArray(cw * ch)
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                val ci = cy * cw + cx
                val px = min(width - 1, cx * 2)
                val py = min(height - 1, cy * 2)
                val yi = py * width + px
                var sumU = 0.0
                var sumV = 0.0
                var total = 0.0
                for (frame in valid) {
                    val value = frame.y[yi].toInt() and 0xFF
                    val middle = 1.0 - abs(value - 128.0) / 128.0
                    val weight = (0.15 + 0.85 * middle).coerceIn(0.05, 1.0)
                    sumU += (frame.u[ci].toInt() and 0xFF) * weight
                    sumV += (frame.v[ci].toInt() and 0xFF) * weight
                    total += weight
                }
                outU[ci] = (sumU / total).toInt().coerceIn(0, 255).toByte()
                outV[ci] = (sumV / total).toInt().coerceIn(0, 255).toByte()
            }
        }

        return FusionResult(
            frame = Yuv420Frame(reference.metadata, width, height, outY, outU, outV),
            contributingFrames = valid.size,
            rejectedFrames = frames.size - valid.size,
        )
    }

    private fun exposureFactor(frame: Yuv420Frame): Double {
        val exposure = frame.metadata.exposureTimeNs ?: return 0.0
        val iso = frame.metadata.sensitivityIso ?: 100
        if (exposure <= 0L || iso <= 0) return 0.0
        return exposure.toDouble() * iso.toDouble()
    }

    private fun srgbToLinear(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(value: Double): Double =
        if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055

    private fun Double.pow(exponent: Double): Double = kotlin.math.exp(kotlin.math.ln(this.coerceAtLeast(1e-9)) * exponent)
}
