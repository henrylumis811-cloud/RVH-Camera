package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * A deliberately small rolling-shutter motion model.
 *
 * Instead of assuming one translation is valid for every sensor row, two horizontal bands are
 * aligned independently. The resulting motion is linearly interpolated from the top of the
 * image to the bottom. This catches the common case where the phone or scene moves during sensor
 * readout without paying for dense optical flow.
 */
data class RowMotionModel(
    val top: FrameTranslation,
    val bottom: FrameTranslation,
    val topConfidence: Float,
    val bottomConfidence: Float,
) {
    fun translationAt(y: Int, height: Int): FrameTranslation {
        if (height <= 1) return top
        val t = (y.toFloat() / (height - 1).toFloat()).coerceIn(0f, 1f)
        return FrameTranslation(
            dx = round(top.dx + (bottom.dx - top.dx) * t).toInt(),
            dy = round(top.dy + (bottom.dy - top.dy) * t).toInt(),
        )
    }

    val confidence: Float
        get() = min(topConfidence, bottomConfidence)
}

class RollingShutterAligner(
    private val maxDimension: Int = 240,
    private val searchRadius: Int = 10,
    private val minimumConfidence: Float = 0.20f,
) {
    init {
        require(maxDimension >= 32)
        require(searchRadius >= 1)
        require(minimumConfidence in 0f..1f)
    }

    fun align(reference: Yuv420Frame, candidate: Yuv420Frame): RowMotionModel? {
        if (reference.width != candidate.width || reference.height != candidate.height) return null

        val top = alignBand(reference, candidate, 0.08f, 0.42f) ?: return null
        val bottom = alignBand(reference, candidate, 0.58f, 0.92f) ?: return null

        // A wildly different top/bottom result is possible when the scene has insufficient
        // texture. Reject it rather than creating a rubber-sheet warp.
        if (abs(top.translation.dx - bottom.translation.dx) > searchRadius * 2 ||
            abs(top.translation.dy - bottom.translation.dy) > searchRadius * 2
        ) {
            return null
        }

        return RowMotionModel(
            top = top.translation,
            bottom = bottom.translation,
            topConfidence = top.confidence,
            bottomConfidence = bottom.confidence,
        )
    }

    private fun alignBand(
        reference: Yuv420Frame,
        candidate: Yuv420Frame,
        startFraction: Float,
        endFraction: Float,
    ): AlignmentResult? {
        val factor = max(
            1,
            ceil(max(reference.width, reference.height).toDouble() / maxDimension).toInt(),
        )
        val startY = (reference.height * startFraction).toInt().coerceIn(0, reference.height - 1)
        val endY = (reference.height * endFraction).toInt().coerceIn(startY + 1, reference.height)
        val sampleWidth = (reference.width + factor - 1) / factor
        val sampleHeight = max(1, (endY - startY + factor - 1) / factor)
        val radius = min(searchRadius, max(2, sampleWidth / 8))
        val marginX = max(2, sampleWidth / 12)
        val marginY = max(1, sampleHeight / 10)

        var best = Float.POSITIVE_INFINITY
        var second = Float.POSITIVE_INFINITY
        var bestDx = 0
        var bestDy = 0

        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val error = bandError(
                    reference, candidate, startY, endY, factor, dx, dy, marginX, marginY,
                )
                if (error < best) {
                    second = best
                    best = error
                    bestDx = dx
                    bestDy = dy
                } else if (error < second) {
                    second = error
                }
            }
        }

        if (!best.isFinite() || !second.isFinite()) return null

        // Refine the coarse estimate at full pixel resolution. This matters on small bands where
        // the downsample factor can otherwise turn a one-pixel rolling-shutter shift into a
        // persistent half-step error. The refinement is only a tiny 3x3 neighbourhood.
        val coarseDx = bestDx * factor
        val coarseDy = bestDy * factor
        var refinedBest = Float.POSITIVE_INFINITY
        var refinedSecond = Float.POSITIVE_INFINITY
        var refinedDx = coarseDx
        var refinedDy = coarseDy
        for (dyRefine in (coarseDy - factor)..(coarseDy + factor)) {
            for (dxRefine in (coarseDx - factor)..(coarseDx + factor)) {
                val error = bandError(
                    reference, candidate, startY, endY, 1, dxRefine, dyRefine,
                    max(2, reference.width / 80), max(1, (endY - startY) / 40),
                )
                if (error < refinedBest) {
                    refinedSecond = refinedBest
                    refinedBest = error
                    refinedDx = dxRefine
                    refinedDy = dyRefine
                } else if (error < refinedSecond) {
                    refinedSecond = error
                }
            }
        }

        val separation = ((refinedSecond - refinedBest) / (refinedSecond + 1e-5f)).coerceIn(0f, 1f)
        val absoluteQuality = (1f - refinedBest / 64f).coerceIn(0f, 1f)
        val confidence = (separation * 0.80f + absoluteQuality * 0.20f).coerceIn(0f, 1f)
        if (confidence < minimumConfidence || separation < 0.05f) return null

        return AlignmentResult(
            translation = FrameTranslation(refinedDx, refinedDy),
            confidence = confidence,
            error = refinedBest,
            accepted = true,
        )
    }

    private fun bandError(
        reference: Yuv420Frame,
        candidate: Yuv420Frame,
        startY: Int,
        endY: Int,
        factor: Int,
        dx: Int,
        dy: Int,
        marginX: Int,
        marginY: Int,
    ): Float {
        val sampleWidth = (reference.width + factor - 1) / factor
        val sampleHeight = max(1, (endY - startY + factor - 1) / factor)
        val startX = marginX
        val endX = sampleWidth - marginX - 1
        val startSampleY = marginY
        val endSampleY = sampleHeight - marginY - 1
        if (startX > endX || startSampleY > endSampleY) return Float.POSITIVE_INFINITY

        var sum = 0L
        var count = 0
        val stride = max(1, min(sampleWidth, sampleHeight) / 96)
        var sy = startSampleY
        while (sy <= endSampleY) {
            val refY = min(reference.height - 1, startY + sy * factor)
            val candY = refY + dy * factor
            if (candY in 0 until candidate.height) {
                var sx = startX
                val refRow = refY * reference.width
                val candRow = candY * candidate.width
                while (sx <= endX) {
                    val refX = sx * factor
                    val candX = refX + dx * factor
                    if (candX in 0 until candidate.width) {
                        val a = reference.y[refRow + refX].toInt() and 0xFF
                        val b = candidate.y[candRow + candX].toInt() and 0xFF
                        sum += abs(a - b).toLong()
                        count++
                    }
                    sx += stride
                }
            }
            sy += stride
        }
        return if (count == 0) Float.POSITIVE_INFINITY else sum.toFloat() / count
    }
}
