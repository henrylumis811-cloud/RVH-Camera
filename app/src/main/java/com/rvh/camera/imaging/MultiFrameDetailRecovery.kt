package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Multi-frame texture recovery performed after temporal fusion.
 *
 * Instead of sharpening the fused image blindly, this stage reuses aligned source frames to
 * recover high-frequency information that may have been attenuated by fusion/denoising. A source
 * contributes only when its local structure agrees with the reference and with the fused result.
 * This is deliberately conservative: disagreement is treated as motion/noise, not detail.
 */
class MultiFrameDetailRecovery(
    private val aligner: FrameAligner = FrameAligner(
        maxDimension = 320,
        searchRadius = 10,
        minimumConfidence = 0.28f,
        minimumConsensus = 0.50f,
    ),
    private val maximumGain: Float = 0.42f,
    private val minimumStructure: Float = 0.16f,
    private val disagreementLimit: Int = 24,
) {
    init {
        require(maximumGain in 0f..1f)
        require(minimumStructure in 0f..1f)
        require(disagreementLimit > 0)
    }

    fun apply(
        fused: Yuv420Frame,
        reference: Yuv420Frame,
        candidates: List<Yuv420Frame>,
    ): Yuv420Frame {
        if (candidates.isEmpty() || fused.width != reference.width || fused.height != reference.height) {
            return fused
        }

        val aligned = candidates.mapNotNull { candidate ->
            if (candidate.width != reference.width || candidate.height != reference.height) return@mapNotNull null
            val alignment = aligner.align(reference, candidate)
            if (!alignment.accepted) return@mapNotNull null
            alignment to candidate
        }
        if (aligned.isEmpty()) return fused

        val output = fused.y.copyOf()
        val width = fused.width
        val height = fused.height

        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val fusedValue = fused.y[index].toInt() and 0xFF
                val refValue = reference.y[index].toInt() and 0xFF
                val refStructure = localStructure(reference.y, width, height, x, y)
                if (refStructure < minimumStructure) continue

                var residualSum = 0f
                var weightSum = 0f
                var supporting = 0

                for ((alignment, candidate) in aligned) {
                    val mappedX = x + alignment.translation.dx
                    val mappedY = y + alignment.translation.dy
                    if (mappedX <= 1 || mappedX >= width - 2 || mappedY <= 1 || mappedY >= height - 2) continue

                    val candidateIndex = mappedY * width + mappedX
                    val candidateValue = candidate.y[candidateIndex].toInt() and 0xFF
                    if (abs(candidateValue - refValue) > disagreementLimit) continue

                    val candidateStructure = localStructure(
                        candidate.y,
                        width,
                        height,
                        mappedX,
                        mappedY,
                    )
                    if (candidateStructure < minimumStructure * 0.65f) continue

                    val refDetail = localDetail(reference.y, width, x, y)
                    val candidateDetail = localDetail(candidate.y, width, mappedX, mappedY)
                    val detailAgreement = if (abs(refDetail) < 2f || abs(candidateDetail) < 2f) {
                        0.55f
                    } else if (refDetail * candidateDetail > 0f) {
                        1f
                    } else {
                        0.05f
                    }
                    if (detailAgreement < 0.25f) continue

                    val fusedDetail = localDetail(fused.y, width, x, y)
                    val candidateResidual = candidateDetail - fusedDetail
                    val stability = (1f - abs(candidateValue - fusedValue) / disagreementLimit.toFloat())
                        .coerceIn(0f, 1f)
                    val structure = min(refStructure, candidateStructure)
                    val weight = alignment.confidence * alignment.consensus *
                        stability * detailAgreement * structure
                    if (weight < 0.045f) continue

                    residualSum += candidateResidual * weight
                    weightSum += weight
                    supporting++
                }

                if (supporting == 0 || weightSum <= 0f) continue

                // More than one agreeing source is a strong indicator that the residual is real
                // texture rather than a single-frame noise excursion.
                val supportBoost = (0.65f + min(2, supporting) * 0.18f).coerceAtMost(1f)
                val gain = maximumGain * supportBoost
                val recovered = fusedValue + (residualSum / weightSum) * gain
                output[index] = round(recovered).toInt().coerceIn(0, 255).toByte()
            }
        }

        return Yuv420Frame(
            metadata = fused.metadata,
            width = width,
            height = height,
            y = output,
            u = fused.u.copyOf(),
            v = fused.v.copyOf(),
        )
    }

    private fun localDetail(y: ByteArray, width: Int, x: Int, rowY: Int): Float {
        val index = rowY * width + x
        val center = y[index].toInt() and 0xFF
        val left = y[index - 1].toInt() and 0xFF
        val right = y[index + 1].toInt() and 0xFF
        val up = y[index - width].toInt() and 0xFF
        val down = y[index + width].toInt() and 0xFF
        val diagonal = (
            (y[index - width - 1].toInt() and 0xFF) +
                (y[index - width + 1].toInt() and 0xFF) +
                (y[index + width - 1].toInt() and 0xFF) +
                (y[index + width + 1].toInt() and 0xFF)
            ) / 4f
        val base = (left + right + up + down + diagonal * 2f + center * 2f) / 8f
        return center - base
    }

    private fun localStructure(
        y: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        rowY: Int,
    ): Float {
        if (x <= 0 || x >= width - 1 || rowY <= 0 || rowY >= height - 1) return 0f
        val index = rowY * width + x
        val left = y[index - 1].toInt() and 0xFF
        val right = y[index + 1].toInt() and 0xFF
        val up = y[index - width].toInt() and 0xFF
        val down = y[index + width].toInt() and 0xFF
        val gradient = (abs(right - left) + abs(down - up)) * 0.5f
        return (gradient / (gradient + 9f)).coerceIn(0f, 1f)
    }
}
