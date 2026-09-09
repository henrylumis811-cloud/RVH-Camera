package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative texture/detail recovery for an already fused frame.
 *
 * This is intentionally not a generic sharpen filter. It estimates a local base signal, measures
 * high-frequency residual, and only restores a bounded amount where the local structure is strong
 * enough to distinguish detail from sensor noise. Chroma is left untouched in this tier.
 */
class DetailRecovery(
    private val maximumGain: Float = 0.55f,
    private val noiseThreshold: Float = 3.5f,
    private val edgeThreshold: Float = 10f,
) {
    init {
        require(maximumGain in 0f..1f)
        require(noiseThreshold >= 0f)
        require(edgeThreshold > 0f)
    }

    fun apply(frame: Yuv420Frame): Yuv420Frame {
        val output = ByteArray(frame.y.size)
        val width = frame.width
        val height = frame.height

        // Preserve the boundary exactly. It avoids inventing edge pixels where the local kernel
        // does not have a complete neighbourhood.
        System.arraycopy(frame.y, 0, output, 0, frame.y.size)

        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val center = frame.y[index].toInt() and 0xFF
                val left = frame.y[index - 1].toInt() and 0xFF
                val right = frame.y[index + 1].toInt() and 0xFF
                val up = frame.y[index - width].toInt() and 0xFF
                val down = frame.y[index + width].toInt() and 0xFF

                // A cross-shaped base estimate is cheaper than a full convolution and is enough
                // to expose the local high-frequency residual.
                val base = (left + right + up + down + center * 4) / 8
                val detail = center - base

                val gradient = (abs(right - left) + abs(down - up)) * 0.5f
                val curvature = (abs(left - 2 * center + right) +
                    abs(up - 2 * center + down)) * 0.5f

                // High curvature in a flat area is more likely to be noise. Around a real edge,
                // the gradient provides evidence that the residual belongs to actual structure.
                val structure = (gradient / (gradient + edgeThreshold)).coerceIn(0f, 1f)
                val noiseRatio = curvature / (curvature + gradient + 8f)
                val confidence = (structure * (1f - noiseRatio * 0.70f)).coerceIn(0f, 1f)

                val magnitude = abs(detail).toFloat()
                if (magnitude <= noiseThreshold || confidence <= 0f) continue

                val aboveThreshold = ((magnitude - noiseThreshold) /
                    max(1f, 32f - noiseThreshold)).coerceIn(0f, 1f)
                val gain = maximumGain * confidence * aboveThreshold
                val restored = center + detail * gain

                output[index] = restored.roundToByte()
            }
        }

        return Yuv420Frame(
            metadata = frame.metadata,
            width = width,
            height = height,
            y = output,
            u = frame.u.copyOf(),
            v = frame.v.copyOf(),
        )
    }

    private fun Float.roundToByte(): Byte =
        kotlin.math.round(this).toInt().coerceIn(0, 255).toByte()
}
