package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/** Translation in full-resolution pixel coordinates. */
data class FrameTranslation(
    val dx: Int,
    val dy: Int,
)

data class AlignmentResult(
    val translation: FrameTranslation,
    val confidence: Float,
    val error: Float,
    val accepted: Boolean,
    /** Fraction of sampled image patches agreeing with the winning motion. */
    val consensus: Float = 0f,
)

/**
 * Robust coarse-to-fine luma alignment for computational multi-frame capture.
 *
 * Unlike a single full-image SAD search, this estimator:
 *  - uses normalized correlation so small exposure changes do not look like motion;
 *  - samples multiple spatial patches so one moving subject cannot dominate the estimate;
 *  - solves the search hierarchically at two resolutions;
 *  - refines the final translation with a quadratic fit for sub-pixel accuracy;
 *  - exposes a consensus score that the fusion stage can use as a second ghost-rejection gate.
 *
 * The returned translation follows the convention used by FrameFusion: candidate[x + dx, y + dy]
 * corresponds to reference[x, y].
 */
class FrameAligner(
    private val maxDimension: Int = 384,
    private val searchRadius: Int = 14,
    private val minimumConfidence: Float = 0.24f,
    private val minimumConsensus: Float = 0.45f,
) {
    init {
        require(maxDimension >= 64)
        require(searchRadius >= 1)
        require(minimumConfidence in 0f..1f)
        require(minimumConsensus in 0f..1f)
    }

    fun align(reference: Yuv420Frame, candidate: Yuv420Frame): AlignmentResult {
        if (reference.width != candidate.width || reference.height != candidate.height) {
            return AlignmentResult(FrameTranslation(0, 0), 0f, 1f, false, 0f)
        }
        if (reference.width < 32 || reference.height < 32) {
            return AlignmentResult(FrameTranslation(0, 0), 0f, 1f, false, 0f)
        }

        val factor = max(
            1,
            kotlin.math.ceil(max(reference.width, reference.height).toDouble() / maxDimension).toInt(),
        )
        val ref = LumaPlane.fromFrame(reference, factor)
        val cand = LumaPlane.fromFrame(candidate, factor)

        // A 3x3 patch layout gives spatial consensus without the cost of dense optical flow.
        val patches = buildPatches(ref.width, ref.height)
        if (patches.isEmpty()) {
            return AlignmentResult(FrameTranslation(0, 0), 0f, 1f, false, 0f)
        }

        val coarseRadius = min(searchRadius, max(2, min(ref.width, ref.height) / 10))
        val coarse = search(ref, cand, patches, -coarseRadius, coarseRadius, 2)
        if (coarse == null) {
            return AlignmentResult(FrameTranslation(0, 0), 0f, 1f, false, 0f)
        }

        // Refine around the coarse winner at the next spatial scale. Keeping the search local
        // prevents ambiguous distant matches from winning merely because they are textured.
        val refineRadius = max(2, min(4, coarseRadius / 2 + 1))
        val refined = search(
            ref,
            cand,
            patches,
            coarse.dx - refineRadius,
            coarse.dx + refineRadius,
            1,
            coarse.dy - refineRadius,
            coarse.dy + refineRadius,
        ) ?: coarse

        val subpixel = refineSubpixel(ref, cand, patches, refined.dx, refined.dy)
        val fullScaleDx = round(subpixel.dx * factor).toInt()
        val fullScaleDy = round(subpixel.dy * factor).toInt()

        val consensus = subpixel.consensus
        val nccQuality = ((subpixel.ncc + 1f) * 0.5f).coerceIn(0f, 1f)
        val confidence = (
            nccQuality * 0.42f +
                subpixel.separation * 0.23f +
                consensus * 0.35f
            ).coerceIn(0f, 1f)

        // Reject weak texture even when the numerical winner is technically unique. This is
        // important on walls, skies and dark scenes where a false alignment can look plausible.
        val accepted = confidence >= minimumConfidence && consensus >= minimumConsensus &&
            nccQuality >= 0.61f

        return AlignmentResult(
            translation = FrameTranslation(fullScaleDx, fullScaleDy),
            confidence = confidence,
            error = 1f - nccQuality,
            accepted = accepted,
            consensus = consensus,
        )
    }

    private fun search(
        reference: LumaPlane,
        candidate: LumaPlane,
        patches: List<Patch>,
        minDx: Int,
        maxDx: Int,
        step: Int,
        minDy: Int = minDx,
        maxDy: Int = maxDx,
    ): Match? {
        var best: Match? = null
        var second = Float.NEGATIVE_INFINITY
        var dy = minDy
        while (dy <= maxDy) {
            var dx = minDx
            while (dx <= maxDx) {
                val score = score(reference, candidate, patches, dx, dy)
                if (score != null) {
                    if (best == null || score.ncc > best!!.ncc) {
                        second = best?.ncc ?: Float.NEGATIVE_INFINITY
                        best = score.copy(dx = dx, dy = dy, separation = 0f)
                    } else if (score.ncc > second) {
                        second = score.ncc
                    }
                }
                dx += step
            }
            dy += step
        }
        val winner = best ?: return null
        return winner.copy(
            separation = if (second.isFinite()) {
                ((winner.ncc - second) / (1f - second + 1e-5f)).coerceIn(0f, 1f)
            } else 0f,
        )
    }

    private fun refineSubpixel(
        reference: LumaPlane,
        candidate: LumaPlane,
        patches: List<Patch>,
        dx: Int,
        dy: Int,
    ): SubpixelMatch {
        val center = score(reference, candidate, patches, dx, dy)
            ?: return SubpixelMatch(dx.toFloat(), dy.toFloat(), 0f, 0f, 0f)

        // Quadratic interpolation of the NCC peak. We keep the correction below half a pixel to
        // avoid extrapolating across a flat or ambiguous correlation surface.
        val xMinus = score(reference, candidate, patches, dx - 1, dy)?.ncc ?: center.ncc
        val xPlus = score(reference, candidate, patches, dx + 1, dy)?.ncc ?: center.ncc
        val yMinus = score(reference, candidate, patches, dx, dy - 1)?.ncc ?: center.ncc
        val yPlus = score(reference, candidate, patches, dx, dy + 1)?.ncc ?: center.ncc

        return SubpixelMatch(
            dx = dx + quadraticPeak(xMinus, center.ncc, xPlus),
            dy = dy + quadraticPeak(yMinus, center.ncc, yPlus),
            ncc = center.ncc,
            separation = center.separation,
            consensus = center.consensus,
        )
    }

    private fun quadraticPeak(left: Float, center: Float, right: Float): Float {
        val denominator = left - 2f * center + right
        if (abs(denominator) < 1e-5f) return 0f
        return (-0.5f * (right - left) / denominator).coerceIn(-0.45f, 0.45f)
    }

    private fun score(
        reference: LumaPlane,
        candidate: LumaPlane,
        patches: List<Patch>,
        dx: Int,
        dy: Int,
    ): Match? {
        val scores = FloatArray(patches.size)
        var count = 0
        for (patch in patches) {
            val ncc = patchNcc(reference, candidate, patch, dx, dy)
            if (ncc != null) scores[count++] = ncc
        }
        if (count < 3) return null

        scores.sort(0, count)
        // Trim the worst third. A moving foreground occupying one or two patches therefore has
        // much less influence on the global motion estimate.
        val trim = max(0, count / 3)
        val end = count - trim
        if (end <= trim) return null
        var sum = 0f
        for (i in trim until end) sum += scores[i]
        val robustMean = sum / (end - trim)
        val median = scores[count / 2]
        val consensus = scores.count { it >= max(0.15f, median - 0.10f) }.toFloat() / count
        return Match(dx, dy, robustMean, 0f, 0f, consensus)
    }

    private fun patchNcc(
        reference: LumaPlane,
        candidate: LumaPlane,
        patch: Patch,
        dx: Int,
        dy: Int,
    ): Float? {
        val x0 = patch.x0
        val y0 = patch.y0
        val x1 = patch.x1
        val y1 = patch.y1
        if (x0 + dx < 1 || y0 + dy < 1 || x1 + dx >= candidate.width - 1 || y1 + dy >= candidate.height - 1) {
            return null
        }

        // Gradient magnitude suppresses broad exposure shifts and emphasizes stable structure.
        var meanA = 0f
        var meanB = 0f
        var n = 0
        val step = max(1, min(patch.width, patch.height) / 24)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                meanA += gradient(reference, x, y)
                meanB += gradient(candidate, x + dx, y + dy)
                n++
                x += step
            }
            y += step
        }
        if (n < 9) return null
        meanA /= n
        meanB /= n

        var numerator = 0f
        var denomA = 0f
        var denomB = 0f
        y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val a = gradient(reference, x, y) - meanA
                val b = gradient(candidate, x + dx, y + dy) - meanB
                numerator += a * b
                denomA += a * a
                denomB += b * b
                x += step
            }
            y += step
        }
        val denominator = sqrt(denomA * denomB)
        if (denominator < 1e-4f) return null
        return (numerator / denominator).coerceIn(-1f, 1f)
    }

    private fun gradient(frame: LumaPlane, x: Int, y: Int): Float {
        val row = y * frame.width
        val left = frame.data[row + x - 1].toInt() and 0xFF
        val right = frame.data[row + x + 1].toInt() and 0xFF
        val up = frame.data[row - frame.width + x].toInt() and 0xFF
        val down = frame.data[row + frame.width + x].toInt() and 0xFF
        return (abs(right - left) + abs(down - up)).toFloat() * 0.5f
    }

    private fun buildPatches(width: Int, height: Int): List<Patch> {
        val patchWidth = max(16, width / 4)
        val patchHeight = max(16, height / 4)
        val result = ArrayList<Patch>(9)
        val xs = intArrayOf(width / 8, width / 2 - patchWidth / 2, width - width / 8 - patchWidth)
        val ys = intArrayOf(height / 8, height / 2 - patchHeight / 2, height - height / 8 - patchHeight)
        for (y in ys) for (x in xs) {
            val x0 = x.coerceIn(2, max(2, width - patchWidth - 3))
            val y0 = y.coerceIn(2, max(2, height - patchHeight - 3))
            result += Patch(x0, y0, x0 + patchWidth - 1, y0 + patchHeight - 1)
        }
        return result.distinct()
    }

    private data class Patch(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width: Int get() = x1 - x0 + 1
        val height: Int get() = y1 - y0 + 1
    }

    private data class Match(
        val dx: Int,
        val dy: Int,
        val ncc: Float,
        val separation: Float,
        val nccQuality: Float,
        val consensus: Float,
    )

    private data class SubpixelMatch(
        val dx: Float,
        val dy: Float,
        val ncc: Float,
        val separation: Float,
        val consensus: Float,
    )

    private data class LumaPlane(val width: Int, val height: Int, val data: ByteArray) {
        companion object {
            fun fromFrame(frame: Yuv420Frame, factor: Int): LumaPlane {
                if (factor == 1) return LumaPlane(frame.width, frame.height, frame.y)
                val width = (frame.width + factor - 1) / factor
                val height = (frame.height + factor - 1) / factor
                val data = ByteArray(width * height)
                for (y in 0 until height) {
                    val sourceY = min(frame.height - 1, y * factor)
                    val sourceRow = sourceY * frame.width
                    val destRow = y * width
                    for (x in 0 until width) {
                        data[destRow + x] = frame.y[sourceRow + min(frame.width - 1, x * factor)]
                    }
                }
                return LumaPlane(width, height, data)
            }
        }
    }
}
