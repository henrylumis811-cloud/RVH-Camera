package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Result of the first computational-photography fusion tier.
 *
 * The fused image remains YUV so later stages can decide how to perform tone mapping, color
 * science and encoding without repeatedly converting between RGB and YUV.
 */
data class FusionResult(
    val frame: Yuv420Frame,
    val contributingFrames: Int,
    val rejectedFrames: Int,
)

/**
 * Conservative two-frame fusion.
 *
 * The reference frame is retained as the visual anchor. Additional frames contribute only when
 * their alignment is trusted and their local luma agrees sufficiently with the reference. This
 * makes the first implementation useful for noise reduction without creating obvious ghosting
 * around moving subjects.
 */
class FrameFusion(
    private val minimumAlignmentConfidence: Float = 0.20f,
    private val motionRejectThreshold: Int = 38,
    private val minimumCandidateWeight: Float = 0.08f,
) {
    init {
        require(minimumAlignmentConfidence in 0f..1f)
        require(motionRejectThreshold > 0)
        require(minimumCandidateWeight in 0f..1f)
    }

    private val aligner = FrameAligner()
    private val rollingShutterAligner = RollingShutterAligner()

    fun fuse(
        reference: Yuv420Frame,
        candidates: List<Yuv420Frame>,
        request: ProcessingRequest = ProcessingRequest(),
    ): FusionResult {
        require(candidates.all { it.width == reference.width && it.height == reference.height }) {
            "All fusion frames must have identical dimensions"
        }

        if (candidates.isEmpty()) {
            return FusionResult(reference, contributingFrames = 1, rejectedFrames = 0)
        }

        val accepted = candidates.mapNotNull { candidate ->
            val alignment = aligner.align(reference, candidate)
            if (alignment.accepted &&
                alignment.confidence >= minimumAlignmentConfidence &&
                alignment.consensus >= 0.45f
            ) {
                val frameWeight = FusionWeightModel.weight(reference, candidate, request.intent)
                val rowMotion = rollingShutterAligner.align(reference, candidate)
                AlignedCandidate(candidate, alignment, frameWeight, rowMotion, request.intent)
            } else {
                null
            }
        }

        if (accepted.isEmpty()) {
            return FusionResult(reference, contributingFrames = 1, rejectedFrames = candidates.size)
        }

        val masks = accepted.map { aligned ->
            aligned to LocalMotionMaskBuilder.build(
                reference = reference,
                candidate = aligned.frame,
                translation = aligned.alignment.translation,
                rowMotion = aligned.rowMotion,
            )
        }

        val y = ByteArray(reference.width * reference.height)
        fuseLuma(reference, masks, y)

        val chromaWidth = (reference.width + 1) / 2
        val chromaHeight = (reference.height + 1) / 2
        val u = ByteArray(chromaWidth * chromaHeight)
        val v = ByteArray(chromaWidth * chromaHeight)
        fuseChroma(reference, masks, u, v)

        val result = Yuv420Frame(
            metadata = reference.metadata,
            width = reference.width,
            height = reference.height,
            y = y,
            u = u,
            v = v,
        )

        return FusionResult(
            frame = result,
            contributingFrames = accepted.size + 1,
            rejectedFrames = candidates.size - accepted.size,
        )
    }

    private fun fuseLuma(
        reference: Yuv420Frame,
        candidates: List<Pair<AlignedCandidate, LocalMotionMask>>,
        output: ByteArray,
    ) {
        val width = reference.width
        val height = reference.height
        val refY = reference.y

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val refValue = refY[row + x].toInt() and 0xFF
                var weightedSum = refValue.toFloat()
                var totalWeight = 1f

                for ((candidate, motionMask) in candidates) {
                    val rowTranslation = chooseTranslation(candidate, y, height)
                    val mappedX = x + rowTranslation.dx
                    val mappedY = y + rowTranslation.dy
                    val motionConfidence = motionMask.sample(x, y)
                    if (motionConfidence < 0.12f) continue
                    if (mappedX !in 0 until width || mappedY !in 0 until height) continue

                    val value = candidate.frame.y[mappedY * width + mappedX].toInt() and 0xFF
                    val difference = abs(value - refValue)

                    // Large disagreement usually indicates motion, occlusion or an alignment
                    // failure. Keep the reference pixel rather than manufacturing a ghost.
                    if (difference > motionRejectThreshold) continue

                    val agreement = 1f - difference.toFloat() / motionRejectThreshold.toFloat()
                    val local = LocalFusionMetrics.measure(
                        reference = reference,
                        candidate = candidate.frame,
                        referenceX = x,
                        referenceY = y,
                        candidateX = mappedX,
                        candidateY = mappedY,
                    )
                    val edgeProtection = (0.35f + local.edgeAgreement * 0.65f)
                        .coerceIn(0.35f, 1f)
                    val exposureConfidence = ExposureFusionMetrics.candidateConfidence(
                        referenceValue = refValue,
                        candidateValue = value,
                        referenceExposureNs = reference.metadata.exposureTimeNs,
                        candidateExposureNs = candidate.frame.metadata.exposureTimeNs,
                        intent = candidate.intent,
                    )
                    val weight = (
                        candidate.alignment.confidence *
                            candidate.frameWeight *
                            agreement *
                            edgeProtection *
                            local.noiseConfidence *
                            motionConfidence *
                            exposureConfidence
                        ).coerceIn(0f, 1f)
                    if (weight < minimumCandidateWeight) continue

                    weightedSum += value * weight
                    totalWeight += weight
                }

                output[row + x] = (weightedSum / totalWeight).toInt().coerceIn(0, 255).toByte()
            }
        }
    }


    private fun chooseTranslation(candidate: AlignedCandidate, y: Int, height: Int): FrameTranslation {
        val rowMotion = candidate.rowMotion
        if (rowMotion == null || rowMotion.confidence < candidate.alignment.confidence * 0.72f) {
            return candidate.alignment.translation
        }

        // Rolling-shutter correction is allowed to move only a bounded amount away from the
        // robust global estimate. This prevents two weak band matches from turning a moving
        // foreground into a rubber-sheet warp.
        val row = rowMotion.translationAt(y, height)
        val global = candidate.alignment.translation
        val maxDeviation = ceil(3f + 5f * (1f - candidate.alignment.confidence)).toInt()
        val dx = (row.dx - global.dx).coerceIn(
            -maxDeviation.coerceAtLeast(1),
            maxDeviation.coerceAtLeast(1),
        )
        val dy = (row.dy - global.dy).coerceIn(
            -maxDeviation.coerceAtLeast(1),
            maxDeviation.coerceAtLeast(1),
        )
        return FrameTranslation(global.dx + dx, global.dy + dy)
    }

    private fun fuseChroma(
        reference: Yuv420Frame,
        candidates: List<Pair<AlignedCandidate, LocalMotionMask>>,
        outputU: ByteArray,
        outputV: ByteArray,
    ) {
        val width = (reference.width + 1) / 2
        val height = (reference.height + 1) / 2
        val refU = reference.u
        val refV = reference.v

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sumU = (refU[row + x].toInt() and 0xFF).toFloat()
                var sumV = (refV[row + x].toInt() and 0xFF).toFloat()
                var totalWeight = 1f

                for ((candidate, motionMask) in candidates) {
                    // Chroma is sampled at 2x2 luma resolution. Use the same row-wise motion
                    // model and local motion confidence as luma fusion so a moving subject cannot
                    // leave a colored ghost behind after its luma contribution was rejected.
                    val lumaY = (y * 2).coerceAtMost(reference.height - 1)
                    val rowTranslation = chooseTranslation(candidate, lumaY, reference.height)
                    val mappedX = x + kotlin.math.round(rowTranslation.dx / 2f).toInt()
                    val mappedY = y + kotlin.math.round(rowTranslation.dy / 2f).toInt()
                    if (mappedX !in 0 until width || mappedY !in 0 until height) continue

                    val motionConfidence = motionMask.sample(
                        (x * 2).coerceAtMost(reference.width - 1),
                        lumaY,
                    )
                    if (motionConfidence < 0.12f) continue

                    val index = mappedY * width + mappedX
                    val candidateLuma = candidate.frame.y[(mappedY * 2).coerceAtMost(reference.height - 1) * reference.width +
                        (mappedX * 2).coerceAtMost(reference.width - 1)].toInt() and 0xFF
                    val referenceLuma = reference.y[lumaY * reference.width +
                        (x * 2).coerceAtMost(reference.width - 1)].toInt() and 0xFF
                    val exposureConfidence = ExposureFusionMetrics.candidateConfidence(
                        referenceValue = referenceLuma,
                        candidateValue = candidateLuma,
                        referenceExposureNs = reference.metadata.exposureTimeNs,
                        candidateExposureNs = candidate.frame.metadata.exposureTimeNs,
                        intent = candidate.intent,
                    )
                    val weight = (candidate.alignment.confidence * candidate.frameWeight *
                        motionConfidence * exposureConfidence)
                    if (weight < minimumCandidateWeight) continue

                    sumU += (candidate.frame.u[index].toInt() and 0xFF) * weight
                    sumV += (candidate.frame.v[index].toInt() and 0xFF) * weight
                    totalWeight += weight
                }

                outputU[row + x] = (sumU / totalWeight).toInt().coerceIn(0, 255).toByte()
                outputV[row + x] = (sumV / totalWeight).toInt().coerceIn(0, 255).toByte()
            }
        }
    }

    private data class AlignedCandidate(
        val frame: Yuv420Frame,
        val alignment: AlignmentResult,
        val frameWeight: Float,
        val rowMotion: RowMotionModel?,
        val intent: CaptureIntent,
    )
}
