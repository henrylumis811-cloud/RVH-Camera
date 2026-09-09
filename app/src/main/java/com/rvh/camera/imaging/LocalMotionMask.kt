package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Low-resolution confidence map identifying regions that disagree after global alignment.
 *
 * This is intentionally not a full optical-flow implementation. It provides a cheap local
 * motion gate so a moving person, leaf, or vehicle does not contaminate an otherwise useful
 * multi-frame fusion.
 */
data class LocalMotionMask(
    val width: Int,
    val height: Int,
    val confidence: ByteArray,
    val scaleX: Float,
    val scaleY: Float,
) {
    init {
        require(width > 0 && height > 0)
        require(confidence.size == width * height)
    }

    /** 0 = strongly moving/disagreeing, 255 = strong local agreement. */
    fun sample(x: Int, y: Int): Float {
        val sx = (x / scaleX).toInt().coerceIn(0, width - 1)
        val sy = (y / scaleY).toInt().coerceIn(0, height - 1)
        return (confidence[sy * width + sx].toInt() and 0xFF) / 255f
    }
}

object LocalMotionMaskBuilder {
    /**
     * Builds a compact mask after the candidate has been globally aligned to the reference.
     * The comparison is intentionally local and downsampled to keep the capture path cheap.
     */
    fun build(
        reference: Yuv420Frame,
        candidate: Yuv420Frame,
        translation: FrameTranslation? = null,
        rowMotion: RowMotionModel? = null,
        maxDimension: Int = 160,
        disagreementSoftLimit: Int = 18,
        disagreementHardLimit: Int = 52,
    ): LocalMotionMask {
        require(reference.width == candidate.width && reference.height == candidate.height)
        require(translation != null || rowMotion != null) { "A translation or row-motion model is required" }
        require(maxDimension >= 32)
        require(disagreementSoftLimit >= 1)
        require(disagreementHardLimit > disagreementSoftLimit)

        val factor = maxOf(
            1,
            kotlin.math.ceil(
                max(reference.width, reference.height).toDouble() / maxDimension,
            ).toInt(),
        )
        val width = (reference.width + factor - 1) / factor
        val height = (reference.height + factor - 1) / factor
        val output = ByteArray(width * height)
        for (my in 0 until height) {
            val y = min(reference.height - 1, my * factor)
            val rowTranslation = rowMotion?.translationAt(y, reference.height) ?: translation!!
            val dx = rowTranslation.dx
            val dy = rowTranslation.dy
            val refRow = y * reference.width
            val mappedY = y + dy
            val dstRow = my * width

            for (mx in 0 until width) {
                val x = min(reference.width - 1, mx * factor)
                val mappedX = x + dx
                val index = dstRow + mx

                if (mappedX !in 0 until candidate.width || mappedY !in 0 until candidate.height) {
                    output[index] = 0
                    continue
                }

                val refValue = reference.y[refRow + x].toInt() and 0xFF
                val candidateValue = candidate.y[mappedY * candidate.width + mappedX].toInt() and 0xFF
                val disagreement = abs(refValue - candidateValue)

                // Compare a tiny local gradient as well. A moving edge can have similar mean
                // luma while still being structurally inconsistent.
                val refGradient = gradient(reference, x, y)
                val candidateGradient = gradient(candidate, mappedX, mappedY)
                val gradientDisagreement = abs(refGradient - candidateGradient)

                val combined = disagreement + gradientDisagreement * 0.35f
                val confidence = when {
                    combined <= disagreementSoftLimit -> 1f
                    combined >= disagreementHardLimit -> 0f
                    else -> 1f - (combined - disagreementSoftLimit) /
                        (disagreementHardLimit - disagreementSoftLimit).toFloat()
                }
                output[index] = (confidence * 255f).toInt().coerceIn(0, 255).toByte()
            }
        }

        return LocalMotionMask(
            width = width,
            height = height,
            confidence = output,
            scaleX = factor.toFloat(),
            scaleY = factor.toFloat(),
        )
    }

    private fun gradient(frame: Yuv420Frame, x: Int, y: Int): Float {
        if (x <= 0 || y <= 0 || x >= frame.width - 1 || y >= frame.height - 1) return 0f
        val row = y * frame.width
        val left = frame.y[row + x - 1].toInt() and 0xFF
        val right = frame.y[row + x + 1].toInt() and 0xFF
        val up = frame.y[row - frame.width + x].toInt() and 0xFF
        val down = frame.y[row + frame.width + x].toInt() and 0xFF
        return (abs(right - left) + abs(down - up)).toFloat() * 0.5f
    }
}
