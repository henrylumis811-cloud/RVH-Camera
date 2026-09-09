package com.rvh.camera.imaging

import kotlin.math.max
import kotlin.math.min

/**
 * Multi-scale luminance tone mapper designed for camera frames rather than display images.
 *
 * A low-resolution luminance base is estimated from the frame, then the local/base ratio is
 * retained as detail while the base is gently compressed. This gives HDR/Night frames local
 * highlight protection and shadow recovery without applying one global curve to every pixel.
 */
class LocalToneMapper(
    private val gridWidth: Int = 48,
    private val gridHeight: Int = 48,
) {
    fun apply(frame: Yuv420Frame, intent: CaptureIntent): Yuv420Frame {
        if (frame.y.isEmpty()) return frame

        val w = frame.width
        val h = frame.height
        val gw = min(gridWidth, max(8, w))
        val gh = min(gridHeight, max(8, h))
        val cellW = w.toFloat() / gw
        val cellH = h.toFloat() / gh

        // Build a robust low-resolution luminance field. Using a small Gaussian-like kernel
        // rather than a single global histogram makes the mapping follow actual scene regions.
        val base = FloatArray(gw * gh)
        val count = IntArray(gw * gh)
        for (y in 0 until h) {
            val gy = min(gh - 1, (y / cellH).toInt())
            val row = y * w
            for (x in 0 until w) {
                val gx = min(gw - 1, (x / cellW).toInt())
                val index = gy * gw + gx
                base[index] += (frame.y[row + x].toInt() and 0xFF) / 255f
                count[index]++
            }
        }
        for (i in base.indices) base[i] = if (count[i] == 0) 0.5f else base[i] / count[i]

        // Smooth the grid once. This prevents tile boundaries while remaining inexpensive at
        // full still resolution.
        val smooth = FloatArray(base.size)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var sum = 0f
                var weight = 0f
                for (dy in -1..1) {
                    val yy = (gy + dy).coerceIn(0, gh - 1)
                    for (dx in -1..1) {
                        val xx = (gx + dx).coerceIn(0, gw - 1)
                        val d = kotlin.math.abs(dx) + kotlin.math.abs(dy)
                        val wgt = when (d) { 0 -> 4f; 1 -> 2f; else -> 1f }
                        sum += base[yy * gw + xx] * wgt
                        weight += wgt
                    }
                }
                smooth[gy * gw + gx] = sum / weight
            }
        }

        val strength = when (intent) {
            CaptureIntent.HDR -> 0.72f
            CaptureIntent.NIGHT -> 0.58f
            CaptureIntent.PORTRAIT -> 0.28f
            CaptureIntent.PHOTO -> 0.34f
        }
        val shadowBoost = when (intent) {
            CaptureIntent.NIGHT -> 0.18f
            CaptureIntent.HDR -> 0.13f
            else -> 0.06f
        }
        val highlightProtect = when (intent) {
            CaptureIntent.HDR -> 0.22f
            CaptureIntent.NIGHT -> 0.08f
            else -> 0.05f
        }

        val output = ByteArray(frame.y.size)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / cellH - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(gh - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / cellW - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(gw - 1, x0 + 1)
                val tx = fx - x0
                val b00 = smooth[y0 * gw + x0]
                val b10 = smooth[y0 * gw + x1]
                val b01 = smooth[y1 * gw + x0]
                val b11 = smooth[y1 * gw + x1]
                val localBase = ((b00 + (b10 - b00) * tx) * (1f - ty) +
                    (b01 + (b11 - b01) * tx) * ty).coerceIn(0.003f, 0.997f)

                val input = (frame.y[y * w + x].toInt() and 0xFF) / 255f
                // Compress only the local base. Preserve local detail as a bounded multiplicative
                // ratio so texture is not flattened by the dynamic-range operation.
                val detailRatio = (input / localBase).coerceIn(0.72f, 1.38f)
                val normalized = localBase
                val shadow = ((0.42f - normalized) / 0.42f).coerceIn(0f, 1f)
                val highlight = ((normalized - 0.62f) / 0.38f).coerceIn(0f, 1f)

                var mappedBase = normalized
                mappedBase += shadowBoost * shadow * (1f - normalized)
                mappedBase -= highlightProtect * highlight * highlight * normalized

                // Mild logarithmic compression in difficult regions. The exponential form keeps
                // midtones close to identity and avoids the synthetic HDR look of hard clipping.
                val compression = if (mappedBase > 0.55f) {
                    val t = ((mappedBase - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    mappedBase * (1f - strength * 0.12f * t)
                } else {
                    mappedBase
                }
                val mapped = (compression * detailRatio).coerceIn(0f, 1f)

                // Blend toward the original for scenes where local mapping is intentionally mild.
                val result = input * (1f - strength) + mapped * strength
                output[y * w + x] = (result * 255f).toInt().coerceIn(0, 255).toByte()
            }
        }

        return Yuv420Frame(frame.metadata, w, h, output, frame.u.copyOf(), frame.v.copyOf())
    }
}
