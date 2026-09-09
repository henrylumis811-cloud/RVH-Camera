package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative pre-demosaic recovery of isolated clipped Bayer samples.
 *
 * The recovery is allowed only when the target CFA plane is clipped but the corresponding
 * neighbour planes remain below saturation and exhibit a stable local colour ratio. This keeps
 * highlight hue information that would otherwise be lost before demosaic without inventing
 * detail when the whole neighbourhood is saturated.
 */
class RawBayerHighlightRecovery(
    private val saturation: Float = 1.015f,
    private val neighbourLimit: Float = 0.94f,
    private val maximumPullback: Float = 0.22f,
) {
    fun apply(mosaic: RawLinearMosaic): RawLinearMosaic {
        val w = mosaic.width
        val h = mosaic.height
        if (w < 9 || h < 9) return mosaic

        val source = mosaic.values
        val output = source.copyOf()
        val cfa = mosaic.cfaArrangement

        fun channel(x: Int, y: Int): Int = cfaChannel(cfa, x, y)
        fun value(x: Int, y: Int): Float = source[y * w + x]

        fun estimate(index: Int, x: Int, y: Int, targetChannel: Int): Float? {
            if (source[index] < saturation) return null

            val ratios = ArrayList<Float>(8)
            val offsets = arrayOf(
                intArrayOf(-2, 0), intArrayOf(2, 0),
                intArrayOf(0, -2), intArrayOf(0, 2),
                intArrayOf(-4, 0), intArrayOf(4, 0),
                intArrayOf(0, -4), intArrayOf(0, 4),
            )

            for (offset in offsets) {
                val sx = x + offset[0]
                val sy = y + offset[1]
                if (sx !in 1 until w - 1 || sy !in 1 until h - 1) continue
                if (channel(sx, sy) != targetChannel) continue

                val sourceValue = value(sx, sy)
                if (sourceValue >= saturation) continue

                val greenSamples = ArrayList<Float>(4)
                for (dy in -1..1) for (dx in -1..1) {
                    if (abs(dx) + abs(dy) != 1) continue
                    val px = sx + dx
                    val py = sy + dy
                    if (px !in 0 until w || py !in 0 until h) continue
                    if (channel(px, py) == 1) greenSamples += value(px, py)
                }
                if (greenSamples.size < 2) continue
                val reference = greenSamples.average().toFloat()
                if (reference <= 0.025f || reference >= neighbourLimit) continue

                val ratio = sourceValue / reference
                if (!ratio.isFinite() || ratio <= 0.05f || ratio > 3.5f) continue
                ratios += ratio
            }

            if (ratios.size < 4) return null
            ratios.sort()
            val median = ratios[ratios.size / 2]
            val deviations = ratios.map { abs(it - median) }.sorted()
            val mad = deviations[deviations.size / 2]
            val spread = mad / max(0.05f, abs(median))
            if (!spread.isFinite() || spread > 0.16f) return null

            // Require nearby unsaturated support in the other CFA planes as well. If the whole
            // neighbourhood is near white, there is no trustworthy colour ratio to reconstruct.
            var support = 0
            var supportSum = 0f
            for (dy in -2..2) for (dx in -2..2) {
                if (abs(dx) + abs(dy) != 2) continue
                val px = x + dx
                val py = y + dy
                if (px !in 0 until w || py !in 0 until h) continue
                val v = value(px, py)
                if (v < neighbourLimit) {
                    support++
                    supportSum += v
                }
            }
            if (support < 3) return null

            val greenAtCenter = run {
                var sum = 0f
                var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (abs(dx) + abs(dy) != 1) continue
                    val px = x + dx
                    val py = y + dy
                    if (px !in 0 until w || py !in 0 until h) continue
                    if (channel(px, py) == 1) {
                        sum += value(px, py)
                        count++
                    }
                }
                if (count >= 2) sum / count else 0f
            }
            if (greenAtCenter <= 0.02f || greenAtCenter >= neighbourLimit) return null

            val predicted = (greenAtCenter * median).coerceIn(0f, 1.05f)
            if (!predicted.isFinite() || predicted >= source[index] - 0.008f) return null

            val confidence = (
                ((0.18f - spread) / 0.18f).coerceIn(0f, 1f) *
                    ((support - 2f) / 5f).coerceIn(0f, 1f)
            )
            val pull = maximumPullback.coerceIn(0f, 0.45f) * confidence
            return source[index] + (predicted - source[index]) * pull
        }

        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val index = y * w + x
                val c = channel(x, y)
                // Only colour planes are reconstructed here. Green carries luminance structure
                // and is deliberately left untouched to avoid changing edge geometry.
                if (c == 1) continue
                estimate(index, x, y, c)?.let { output[index] = it.coerceIn(0f, 1.05f) }
            }
        }

        return mosaic.copy(values = output)
    }
}

private fun cfaChannel(arrangement: Int, x: Int, y: Int): Int {
    val parity = (y and 1) * 2 + (x and 1)
    return when (arrangement) {
        1 -> intArrayOf(1, 0, 3, 1)[parity]
        2 -> intArrayOf(1, 3, 0, 1)[parity]
        3 -> intArrayOf(3, 1, 1, 0)[parity]
        else -> intArrayOf(0, 1, 1, 3)[parity]
    }
}
