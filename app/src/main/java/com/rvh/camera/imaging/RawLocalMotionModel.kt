package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Coarse local motion model for RAW multi-frame fusion.
 *
 * The global frame alignment is still the primary registration. This model only estimates a
 * bounded residual displacement on a sparse grid. Large residuals that disagree with the global
 * transform are treated as likely subject motion rather than forcing the whole frame to follow it.
 */
class RawLocalMotionModel private constructor(
    private val width: Int,
    private val height: Int,
    private val cellSize: Int,
    private val dx: FloatArray,
    private val dy: FloatArray,
    private val confidence: FloatArray,
) {
    fun sample(x: Int, y: Int): Sample {
        if (dx.isEmpty()) return Sample(0f, 0f, 0f)
        val gx = x.toFloat() / cellSize
        val gy = y.toFloat() / cellSize
        val x0 = gx.toInt().coerceIn(0, gridWidth - 1)
        val y0 = gy.toInt().coerceIn(0, gridHeight - 1)
        val x1 = min(gridWidth - 1, x0 + 1)
        val y1 = min(gridHeight - 1, y0 + 1)
        val tx = (gx - x0).coerceIn(0f, 1f)
        val ty = (gy - y0).coerceIn(0f, 1f)
        return bilinear(x0, y0, x1, y1, tx, ty)
    }

    data class Sample(val dx: Float, val dy: Float, val confidence: Float)

    private val gridWidth: Int get() = (width + cellSize - 1) / cellSize
    private val gridHeight: Int get() = (height + cellSize - 1) / cellSize

    private fun bilinear(x0: Int, y0: Int, x1: Int, y1: Int, tx: Float, ty: Float): Sample {
        fun value(a: Float, b: Float, c: Float, d: Float): Float {
            val top = a + (b - a) * tx
            val bottom = c + (d - c) * tx
            return top + (bottom - top) * ty
        }
        val i00 = y0 * gridWidth + x0
        val i10 = y0 * gridWidth + x1
        val i01 = y1 * gridWidth + x0
        val i11 = y1 * gridWidth + x1
        return Sample(
            value(dx[i00], dx[i10], dx[i01], dx[i11]),
            value(dy[i00], dy[i10], dy[i01], dy[i11]),
            value(confidence[i00], confidence[i10], confidence[i01], confidence[i11]),
        )
    }

    companion object {
        fun estimate(
            anchor: RawLinearMosaic,
            source: RawLinearMosaic,
            exposureScale: Float,
            globalDx: Float,
            globalDy: Float,
            globalConfidence: Float,
            cellSize: Int = 48,
        ): RawLocalMotionModel? {
            if (anchor.width != source.width || anchor.height != source.height) return null
            if (globalConfidence < 0.25f) return null
            val cell = cellSize.coerceIn(24, 96)
            val gw = (anchor.width + cell - 1) / cell
            val gh = (anchor.height + cell - 1) / cell
            val dx = FloatArray(gw * gh)
            val dy = FloatArray(gw * gh)
            val confidence = FloatArray(gw * gh)
            var any = false

            for (gy in 0 until gh) {
                val cy = min(anchor.height - 1, gy * cell + cell / 2)
                for (gx in 0 until gw) {
                    val cx = min(anchor.width - 1, gx * cell + cell / 2)
                    val index = gy * gw + gx
                    val baseScore = correlation(anchor, source, cx, cy, globalDx, globalDy, exposureScale, 12)
                    if (baseScore < 0.12f) continue

                    var bestScore = baseScore
                    var bestDx = 0f
                    var bestDy = 0f
                    for (ry in -2..2) {
                        for (rx in -2..2) {
                            if (rx == 0 && ry == 0) continue
                            val candidateDx = globalDx + rx
                            val candidateDy = globalDy + ry
                            val score = correlation(anchor, source, cx, cy, candidateDx, candidateDy, exposureScale, 12)
                            if (score > bestScore) {
                                bestScore = score
                                bestDx = candidateDx - globalDx
                                bestDy = candidateDy - globalDy
                            }
                        }
                    }
                    val improvement = (bestScore - baseScore).coerceAtLeast(0f)
                    val localConfidence = ((bestScore - 0.12f) / 0.45f).coerceIn(0f, 1f) *
                        (improvement / 0.10f).coerceIn(0f, 1f)
                    if (localConfidence > 0.12f) any = true
                    dx[index] = bestDx.coerceIn(-2f, 2f)
                    dy[index] = bestDy.coerceIn(-2f, 2f)
                    confidence[index] = localConfidence
                }
            }
            if (!any) return null

            // Stage 48: confidence-aware spatial regularization. Local correlation is intentionally
            // allowed to be noisy at weak-texture cells, but feeding that noise directly into the
            // pixel-wise fusion gate can create tiny islands of motion and visible seams. Smooth the
            // vector field only when neighboring estimates are compatible; strong motion boundaries
            // remain discontinuities instead of being blurred across the subject/background edge.
            regularizeField(dx, dy, confidence, gw, gh, passes = 2)

            return RawLocalMotionModel(anchor.width, anchor.height, cell, dx, dy, confidence)
        }


        private fun regularizeField(
            dx: FloatArray,
            dy: FloatArray,
            confidence: FloatArray,
            width: Int,
            height: Int,
            passes: Int,
        ) {
            if (width < 2 || height < 2) return
            repeat(passes.coerceIn(1, 3)) {
                val oldDx = dx.copyOf()
                val oldDy = dy.copyOf()
                val oldConfidence = confidence.copyOf()
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val center = y * width + x
                        val centerConfidence = oldConfidence[center]
                        if (centerConfidence <= 0f) continue

                        var sumDx = oldDx[center] * centerConfidence
                        var sumDy = oldDy[center] * centerConfidence
                        var sumWeight = centerConfidence
                        var support = centerConfidence

                        for (ny in max(0, y - 1)..min(height - 1, y + 1)) {
                            for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                                if (nx == x && ny == y) continue
                                val i = ny * width + nx
                                val neighborConfidence = oldConfidence[i]
                                if (neighborConfidence < 0.10f) continue
                                val dvx = oldDx[i] - oldDx[center]
                                val dvy = oldDy[i] - oldDy[center]
                                val vectorDifference = sqrt(dvx * dvx + dvy * dvy)
                                // Do not smear a moving subject into a static background or vice versa.
                                val compatibility = exp(-(vectorDifference * vectorDifference) / 1.25f)
                                val spatialWeight = if (nx == x || ny == y) 1f else 0.707f
                                val weight = neighborConfidence * compatibility * spatialWeight
                                if (weight <= 0.01f) continue
                                sumDx += oldDx[i] * weight
                                sumDy += oldDy[i] * weight
                                sumWeight += weight
                                support += neighborConfidence * compatibility
                            }
                        }

                        val smoothedConfidence = (support / 4f).coerceIn(0f, 1f)
                        if (sumWeight > 0.01f) {
                            dx[center] = (sumDx / sumWeight).coerceIn(-2f, 2f)
                            dy[center] = (sumDy / sumWeight).coerceIn(-2f, 2f)
                            confidence[center] = max(centerConfidence * 0.72f, smoothedConfidence)
                                .coerceIn(0f, 1f)
                        }
                    }
                }
            }
        }

        private fun correlation(
            anchor: RawLinearMosaic,
            source: RawLinearMosaic,
            cx: Int,
            cy: Int,
            dx: Float,
            dy: Float,
            exposureScale: Float,
            radius: Int,
        ): Float {
            var sumA = 0f
            var sumB = 0f
            var sumAA = 0f
            var sumBB = 0f
            var sumAB = 0f
            var count = 0
            for (oy in -radius..radius step 3) {
                val ay = cy + oy
                val sy = (ay + dy).toInt()
                if (ay !in 2 until anchor.height - 2 || sy !in 2 until source.height - 2) continue
                for (ox in -radius..radius step 3) {
                    val ax = cx + ox
                    val sx = (ax + dx).toInt()
                    if (ax !in 2 until anchor.width - 2 || sx !in 2 until source.width - 2) continue
                    val a = gradient(anchor, ax, ay)
                    val b = gradient(source, sx, sy) / exposureScale
                    sumA += a
                    sumB += b
                    sumAA += a * a
                    sumBB += b * b
                    sumAB += a * b
                    count++
                }
            }
            if (count < 9) return -1f
            val meanA = sumA / count
            val meanB = sumB / count
            val covariance = sumAB / count - meanA * meanB
            val varianceA = max(1e-6f, sumAA / count - meanA * meanA)
            val varianceB = max(1e-6f, sumBB / count - meanB * meanB)
            return (covariance / sqrt(varianceA * varianceB)).coerceIn(-1f, 1f)
        }

        private fun gradient(frame: RawLinearMosaic, x: Int, y: Int): Float {
            val xm = max(0, x - 2)
            val xp = min(frame.width - 1, x + 2)
            val ym = max(0, y - 2)
            val yp = min(frame.height - 1, y + 2)
            return abs(frame.values[y * frame.width + xp] - frame.values[y * frame.width + xm]) +
                abs(frame.values[yp * frame.width + x] - frame.values[ym * frame.width + x])
        }
    }
}
