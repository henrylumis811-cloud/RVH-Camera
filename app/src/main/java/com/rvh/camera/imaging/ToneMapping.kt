package com.rvh.camera.imaging

import kotlin.math.max
import kotlin.math.min

/**
 * Scene-adaptive luminance tone mapping for the fused Y plane.
 *
 * This deliberately operates after fusion/detail recovery and before final color encoding.
 * It is conservative: highlights are compressed before clipping, shadows are lifted only when
 * the scene has enough headroom, and the curve changes with capture intent.
 */
class ToneMapper(
    private val shadowLift: Float = 0.10f,
    private val highlightCompression: Float = 0.24f,
) {
    fun apply(frame: Yuv420Frame, intent: CaptureIntent = CaptureIntent.PHOTO): Yuv420Frame {
        val histogram = IntArray(256)
        for (value in frame.y) histogram[value.toInt() and 0xFF]++

        val low = percentile(histogram, frame.y.size, 0.01f)
        val high = percentile(histogram, frame.y.size, 0.995f)
        val range = max(1, high - low)

        val intentShadow = when (intent) {
            CaptureIntent.NIGHT -> shadowLift * 1.45f
            CaptureIntent.HDR -> shadowLift * 1.15f
            CaptureIntent.PORTRAIT -> shadowLift * 0.85f
            CaptureIntent.PHOTO -> shadowLift
        }
        val intentHighlight = when (intent) {
            CaptureIntent.HDR -> highlightCompression * 1.35f
            CaptureIntent.NIGHT -> highlightCompression * 0.80f
            CaptureIntent.PORTRAIT -> highlightCompression * 0.90f
            CaptureIntent.PHOTO -> highlightCompression
        }

        val output = ByteArray(frame.y.size)
        for (i in frame.y.indices) {
            val normalized = ((frame.y[i].toInt() and 0xFF) - low).toFloat() / range
            val x = normalized.coerceIn(0f, 1f)

            // Mild toe + shoulder. The center remains close to linear so skin and midtones are
            // not pushed into a stylized look.
            // Keep the midtone anchor close to identity. The toe only acts on deep shadows,
            // while the shoulder only acts on the upper highlight region.
            val shadowRegion = ((0.38f - x) / 0.38f).coerceIn(0f, 1f)
            val lifted = x + intentShadow * shadowRegion * (1f - x)
            val highlightRegion = ((lifted - 0.72f) / 0.28f).coerceIn(0f, 1f)
            val compressed = lifted - intentHighlight * highlightRegion * highlightRegion * 0.10f
            val mapped = min(1f, max(0f, compressed))

            output[i] = (mapped * 255f).toInt().coerceIn(0, 255).toByte()
        }

        return Yuv420Frame(frame.metadata, frame.width, frame.height, output, frame.u.copyOf(), frame.v.copyOf())
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).coerceAtLeast(1f).toInt()
        var cumulative = 0
        for (i in histogram.indices) {
            cumulative += histogram[i]
            if (cumulative >= target) return i
        }
        return 255
    }
}
