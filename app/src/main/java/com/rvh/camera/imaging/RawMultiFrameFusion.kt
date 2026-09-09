package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sensor-domain multi-frame fusion.
 *
 * Fusion happens on the Bayer mosaic before demosaic. Frames are normalized for exposure,
 * aligned against an anchor using a low-resolution robust gradient search, then merged with
 * sensor-noise-aware weights and a bounded robust outlier gate. This intentionally does not
 * invent colour between CFA sites; each output sample is built only from the same CFA channel.
 */
class RawMultiFrameFusion(
    private val maximumFrames: Int = 4,
) {
    data class Input(
        val frame: RawLinearMosaic,
        val quality: Float = 1f,
    )

    data class Result(
        val mosaic: RawLinearMosaic,
        val contributingFrames: Int,
        val anchorIndex: Int,
        val averageAlignmentConfidence: Float,
    )

    fun fuse(inputs: List<Input>): Result? {
        val valid = inputs.take(maximumFrames).filter {
            it.frame.width > 8 && it.frame.height > 8 &&
                it.frame.values.size == it.frame.width * it.frame.height
        }
        if (valid.isEmpty()) return null
        if (valid.size == 1) {
            return Result(valid.first().frame, 1, 0, 1f)
        }

        val anchorIndex = chooseAnchor(valid)
        val anchor = valid[anchorIndex].frame
        val aligned = ArrayList<AlignedFrame>(valid.size)
        var confidenceSum = 0f
        var contributing = 0

        valid.forEachIndexed { index, input ->
            val exposureScale = relativeExposure(anchor.metadata, input.frame.metadata)
            val motion = if (index == anchorIndex) {
                Alignment(0f, 0f, 1f)
            } else {
                estimateTranslation(anchor, input.frame, exposureScale)
            }
            if (motion.confidence >= 0.28f || index == anchorIndex) {
                aligned += AlignedFrame(
                input = input,
                alignment = motion,
                exposureScale = exposureScale,
                oisModel = OisRollingShutterModel.from(input.frame.metadata, input.frame.height),
                localMotion = if (index == anchorIndex) null else RawLocalMotionModel.estimate(
                    anchor = anchor,
                    source = input.frame,
                    exposureScale = exposureScale,
                    globalDx = motion.dx,
                    globalDy = motion.dy,
                    globalConfidence = motion.confidence,
                ),
                isAnchor = index == anchorIndex,
            )
                confidenceSum += motion.confidence
                contributing++
            }
        }

        if (aligned.size == 1) return Result(anchor, 1, anchorIndex, 1f)

        val output = FloatArray(anchor.values.size)
        for (y in 0 until anchor.height) {
            for (x in 0 until anchor.width) {
                val idx = y * anchor.width + x
                val anchorValue = anchor.values[idx]
                var weighted = 0.0
                var totalWeight = 0.0
                var anchorWeighted = 0.0
                var secondaryWeighted = 0.0

                val plane = cfaPlane(anchor.cfaArrangement, x, y)
                val samples = ArrayList<Sample>(aligned.size)
                aligned.forEach { candidate ->
                    val sample = cfaWeightedInterpolation(
                        candidate.input.frame,
                        x + candidate.alignment.dx,
                        y + candidate.alignment.dy,
                        plane,
                    ) ?: return@forEach
                    val normalized = (sample / candidate.exposureScale).coerceIn(0f, 1.05f)
                    samples += Sample(candidate, normalized)
                }

                // A moving subject can make the anchor itself disagree with the other
                // frames. Use a robust temporal consensus as a second gate. With three or
                // four frames, the median rejects a transient subject edge without blurring
                // the whole scene; with one/two samples we retain the existing anchor/noise
                // gate because there is not enough temporal evidence to make a safe decision.
                val consensus = temporalConsensus(samples.map { it.value })
                val anchorNoise = estimateNoise(anchor.metadata, x, y, anchorValue)
                val consensusShift = abs(consensus - anchorValue)
                val consensusDominant = samples.size >= 3 &&
                    consensusShift > (anchorNoise * 3.0f + 0.012f)
                val referenceValue = if (consensusDominant) consensus else anchorValue

                // Stage 46: turn temporal disagreement into a local motion confidence.
                // Static regions should keep the full multi-frame SNR gain, while a moving
                // subject must collapse toward the chosen anchor instead of being averaged
                // into a ghost. The threshold is tied to the calibrated sensor noise model
                // so ordinary shot/read noise does not masquerade as motion.
                val temporalMad = temporalAbsoluteDeviation(samples.map { it.value }, consensus)
                val motionThreshold = (anchorNoise * 4.0f + 0.010f).coerceIn(0.012f, 0.22f)
                val motionConfidence = ((temporalMad - motionThreshold) /
                    max(0.001f, motionThreshold * 2.5f)).coerceIn(0f, 1f)
                val localGradient = gradient(anchor, x, y)

                samples.forEach { measured ->
                    val normalized = measured.value
                    val residual = abs(normalized - referenceValue)
                    val noise = estimateNoise(anchor.metadata, x, y, referenceValue)
                    val robustThreshold = (noise * 2.75f + 0.006f).coerceIn(0.008f, 0.18f)
                    val robust = if (residual <= robustThreshold) {
                        1f
                    } else {
                        robustThreshold / max(robustThreshold, residual)
                    }
                    val consensusResidual = abs(normalized - consensus)
                    val consensusThreshold = (noise * 2.25f + 0.004f).coerceIn(0.007f, 0.14f)
                    val temporalAgreement = if (samples.size >= 3) {
                        if (consensusResidual <= consensusThreshold) 1f
                        else (consensusThreshold / max(consensusThreshold, consensusResidual)).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    val sourceX = (x + measured.candidate.alignment.dx).toInt()
                    val sourceY = (y + measured.candidate.alignment.dy).toInt()
                    val sourceGradient = if (sourceX in 2 until measured.candidate.input.frame.width - 2 &&
                        sourceY in 2 until measured.candidate.input.frame.height - 2) {
                        gradient(measured.candidate.input.frame, sourceX, sourceY) / measured.candidate.exposureScale
                    } else {
                        localGradient
                    }
                    val gradientScale = max(0.01f, abs(localGradient) + abs(sourceGradient))
                    val gradientAgreement = (1f - abs(localGradient - sourceGradient) / gradientScale).coerceIn(0.18f, 1f)
                    val spatial = measured.candidate.alignment.confidence.coerceIn(0.05f, 1f)
                    val quality = measured.candidate.input.quality.coerceIn(0.1f, 1f)
                    val oisStability = measured.candidate.oisModel.scoreForRow(y)
                    val edgeAgreement = exp(-(residual * residual) / max(1e-6f, 2f * robustThreshold * robustThreshold))

                    // Protect the anchor when the local scene is moving. We do not discard
                    // every secondary frame: a soft gate keeps partially agreeing samples
                    // useful while strongly disagreeing motion regions converge to the anchor.
                    val localMotion = measured.candidate.localMotion?.sample(x, y) ?: RawLocalMotionModel.Sample(0f, 0f, 0f)
                    val localDisplacement = sqrt(localMotion.dx * localMotion.dx + localMotion.dy * localMotion.dy)
                    val localMotionConfidence = (localMotion.confidence * (localDisplacement / 0.75f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    val anchorProtection = if (measured.candidate.isAnchor) {
                        1f + motionConfidence * 3.5f
                    } else {
                        (1f - motionConfidence * 0.92f - localMotionConfidence * 0.82f).coerceAtLeast(0.04f)
                    }
                    val weight = (quality * spatial * oisStability * robust * temporalAgreement *
                        gradientAgreement * edgeAgreement * anchorProtection).toDouble()
                    weighted += normalized * weight
                    totalWeight += weight
                    if (measured.candidate.isAnchor) {
                        anchorWeighted += weight
                    } else {
                        secondaryWeighted += weight
                    }
                }

                output[idx] = if (totalWeight > 0.001) {
                    // Stage 49: prevent a rapidly changing secondary/anchor weight ratio from
                    // producing visible fusion seams at strong edges. Motion confidence caps the
                    // secondary contribution, while edge strength makes that cap stricter where a
                    // small registration error would otherwise create a halo or double contour.
                    val edgeStrength = (abs(localGradient) / max(anchorNoise * 6f, 0.018f)).coerceIn(0f, 1f)
                    val motionCap = (1f - motionConfidence * 0.82f).coerceIn(0.12f, 1f)
                    val edgeCap = (1f - edgeStrength * 0.55f).coerceIn(0.32f, 1f)
                    val maximumSecondaryRatio = (motionCap * edgeCap).coerceIn(0.10f, 0.92f)
                    val allowedSecondary = anchorWeighted * maximumSecondaryRatio /
                        max(0.001, 1.0 - maximumSecondaryRatio)
                    val scaleSecondary = if (secondaryWeighted > allowedSecondary && secondaryWeighted > 0.0) {
                        (allowedSecondary / secondaryWeighted).coerceIn(0.02, 1.0)
                    } else {
                        1.0
                    }
                    val stabilizedTotal = anchorWeighted + secondaryWeighted * scaleSecondary
                    val stabilizedWeighted = if (secondaryWeighted > 0.0) {
                        val secondaryMean = (weighted - anchorValue * anchorWeighted) / secondaryWeighted
                        anchorValue * anchorWeighted + secondaryMean * secondaryWeighted * scaleSecondary
                    } else {
                        anchorValue * anchorWeighted
                    }
                    (stabilizedWeighted / max(0.001, stabilizedTotal)).toFloat().coerceIn(0f, 1.05f)
                } else {
                    anchorValue
                }
            }
        }

        return Result(
            mosaic = anchor.copy(values = output),
            contributingFrames = contributing,
            anchorIndex = anchorIndex,
            averageAlignmentConfidence = (confidenceSum / aligned.size).coerceIn(0f, 1f),
        )
    }

    private data class Sample(
        val candidate: AlignedFrame,
        val value: Float,
    )

    private fun temporalConsensus(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        if (values.size == 1) return values[0]
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if ((sorted.size and 1) == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) * 0.5f
        }
    }

    private fun temporalAbsoluteDeviation(values: List<Float>, center: Float): Float {
        if (values.size < 2) return 0f
        val deviations = values.map { abs(it - center) }.sorted()
        val middle = deviations.size / 2
        return if ((deviations.size and 1) == 1) {
            deviations[middle]
        } else {
            (deviations[middle - 1] + deviations[middle]) * 0.5f
        }
    }

    private fun chooseAnchor(inputs: List<Input>): Int {
        return inputs.indices.maxByOrNull { index ->
            val metadata = inputs[index].frame.metadata
            val iso = metadata.sensitivityIso ?: 100
            val exposure = metadata.exposureTimeNs ?: 8_000_000L
            val isoScore = 1f / (1f + max(0, iso - 100) / 400f)
            val exposureScore = (exposure.coerceIn(500_000L, 20_000_000L) / 20_000_000f)
            inputs[index].quality.coerceIn(0f, 1f) * (0.7f * isoScore + 0.3f * exposureScore)
        } ?: 0
    }

    private fun relativeExposure(anchor: FrameMetadata, frame: FrameMetadata): Float {
        val a = (anchor.exposureTimeNs ?: 1L).coerceAtLeast(1L).toDouble() *
            (anchor.sensitivityIso ?: 100).coerceAtLeast(1)
        val b = (frame.exposureTimeNs ?: 1L).coerceAtLeast(1L).toDouble() *
            (frame.sensitivityIso ?: 100).coerceAtLeast(1)
        return (b / a).toFloat().coerceIn(0.125f, 8f)
    }

    private fun estimateTranslation(
        anchor: RawLinearMosaic,
        source: RawLinearMosaic,
        exposureScale: Float,
    ): Alignment {
        if (anchor.width != source.width || anchor.height != source.height || anchor.cfaArrangement != source.cfaArrangement) {
            return Alignment(0f, 0f, 0f)
        }
        val scale = 4
        val radius = 6
        var best = Alignment(0f, 0f, -Float.MAX_VALUE)
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val score = patchCorrelation(anchor, source, dx * scale, dy * scale, exposureScale, scale)
                if (score > best.confidence) best = Alignment((dx * scale).toFloat(), (dy * scale).toFloat(), score)
            }
        }
        if (best.confidence <= -0.5f) return Alignment(0f, 0f, 0f)

        var refined = best
        for (dy in -2..2) {
            for (dx in -2..2) {
                val candidateDx = best.dx + dx
                val candidateDy = best.dy + dy
                val score = patchCorrelation(anchor, source, candidateDx.toInt(), candidateDy.toInt(), exposureScale, 2)
                if (score > refined.confidence) refined = Alignment(candidateDx, candidateDy, score)
            }
        }
        return refined.copy(confidence = ((refined.confidence + 1f) * 0.5f).coerceIn(0f, 1f))
    }

    private fun patchCorrelation(
        anchor: RawLinearMosaic,
        source: RawLinearMosaic,
        dx: Int,
        dy: Int,
        exposureScale: Float,
        step: Int,
    ): Float {
        val w = anchor.width
        val h = anchor.height
        val margin = 12
        var sumA = 0f
        var sumB = 0f
        var sumAA = 0f
        var sumBB = 0f
        var sumAB = 0f
        var count = 0
        for (y in margin until h - margin step step) {
            val sy = y + dy
            if (sy !in margin until h - margin) continue
            for (x in margin until w - margin step step) {
                val sx = x + dx
                if (sx !in margin until w - margin) continue
                val a = gradient(anchor, x, y)
                val b = gradient(source, sx, sy) / exposureScale
                sumA += a
                sumB += b
                sumAA += a * a
                sumBB += b * b
                sumAB += a * b
                count++
            }
        }
        if (count < 32) return -1f
        val meanA = sumA / count
        val meanB = sumB / count
        val covariance = sumAB / count - meanA * meanB
        val varianceA = max(1e-6f, sumAA / count - meanA * meanA)
        val varianceB = max(1e-6f, sumBB / count - meanB * meanB)
        return (covariance / sqrt(varianceA * varianceB)).coerceIn(-1f, 1f)
    }

    /**
     * Gradient measured on a single Bayer plane. Adjacent physical pixels can belong to
     * different colours, so the old 1-pixel gradient could inject false colour edges into
     * motion estimation. A two-pixel step stays on the same CFA plane.
     */
    private fun gradient(frame: RawLinearMosaic, x: Int, y: Int): Float {
        val xm = max(0, x - 2)
        val xp = min(frame.width - 1, x + 2)
        val ym = max(0, y - 2)
        val yp = min(frame.height - 1, y + 2)
        val gx = abs(frame.values[y * frame.width + xp] - frame.values[y * frame.width + xm])
        val gy = abs(frame.values[yp * frame.width + x] - frame.values[ym * frame.width + x])
        return gx + gy
    }

    /**
     * Interpolates only within the exact Bayer plane requested by the anchor sample. This is
     * deliberately not ordinary bilinear interpolation: a four-neighbour RAW bilinear sample
     * mixes R/G/B sites whenever the motion estimate is fractional. Keeping interpolation on
     * the same 2x2 CFA phase prevents colour contamination during sub-pixel multi-frame fusion.
     */
    private fun cfaWeightedInterpolation(
        frame: RawLinearMosaic,
        x: Float,
        y: Float,
        plane: Int,
    ): Float? {
        if (x < 0f || y < 0f || x > frame.width - 1f || y > frame.height - 1f) return null

        fun nearestPlaneCoordinate(value: Float, axis: Int): IntArray {
            val base = value.toInt().coerceIn(0, axis - 1)
            val candidates = IntArray(5)
            var count = 0
            for (delta in -2..2) {
                val c = base + delta
                if (c in 0 until axis) {
                    // Candidate parity is filtered by the caller's full x/y coordinate.
                    candidates[count++] = c
                }
            }
            return candidates.copyOf(count)
        }

        val xCandidates = nearestPlaneCoordinate(x, frame.width)
        val yCandidates = nearestPlaneCoordinate(y, frame.height)

        // Bayer plane phases are coupled, so rather than interpolate across colour sites,
        // collect nearby samples belonging to the exact same interleaved plane and use a
        // distance-weighted reconstruction. The radius is deliberately small to avoid
        // smearing fine detail across multiple CFA periods.
        val points = ArrayList<Triple<Int, Int, Float>>(4)
        val unique = LinkedHashSet<Long>()
        for (cy in yCandidates) {
            for (cx in xCandidates) {
                if (cfaPlane(frame.cfaArrangement, cx, cy) != plane) continue
                if (abs(cx - x) <= 2.01f && abs(cy - y) <= 2.01f) {
                    val key = (cy.toLong() shl 32) xor (cx.toLong() and 0xffffffffL)
                    if (unique.add(key)) {
                        points += Triple(cx, cy, frame.values[cy * frame.width + cx])
                    }
                }
            }
        }
        if (points.isEmpty()) return null

        var weighted = 0.0
        var total = 0.0
        for ((px, py, value) in points) {
            val distance2 = (px - x) * (px - x) + (py - y) * (py - y)
            val weight = 1.0 / (0.001 + distance2)
            weighted += value * weight
            total += weight
        }
        return if (total > 0.0) (weighted / total).toFloat() else null
    }

    private fun estimateNoise(metadata: FrameMetadata, x: Int, y: Int, signal: Float): Float {
        val profile = metadata.sensorNoiseProfile
        val plane = cfaPlane(metadata.sensorCfaArrangement ?: 0, x, y)
        if (profile != null && plane * 2 + 1 < profile.size) {
            val slope = max(0f, profile[plane * 2])
            val offset = max(0f, profile[plane * 2 + 1])
            return sqrt(max(0f, slope * signal + offset))
        }
        return (0.004f + 0.06f * sqrt(signal.coerceAtLeast(0f))).coerceIn(0.003f, 0.12f)
    }

    /** Returns the exact interleaved Bayer plane [R, Geven, Godd, B]. */
    private fun cfaPlane(arrangement: Int, x: Int, y: Int): Int {
        val parity = (y and 1) * 2 + (x and 1)
        return when (arrangement) {
            1 -> intArrayOf(1, 0, 3, 2)[parity] // GRBG: G_even, R, B, G_odd
            2 -> intArrayOf(1, 3, 0, 2)[parity] // GBRG: G_even, B, R, G_odd
            3 -> intArrayOf(3, 1, 2, 0)[parity] // BGGR: B, G_even, G_odd, R
            else -> intArrayOf(0, 1, 2, 3)[parity] // RGGB: R, G_even, G_odd, B
        }
    }

    private data class Alignment(val dx: Float, val dy: Float, val confidence: Float)
    private data class AlignedFrame(
        val input: Input,
        val alignment: Alignment,
        val exposureScale: Float,
        val oisModel: OisRollingShutterModel,
        val localMotion: RawLocalMotionModel?,
        val isAnchor: Boolean,
    )
}

