package com.rvh.camera.imaging

import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import kotlin.math.pow

/**
 * Encodes the custom RAW renderer into JPEG/R on Android 14+.
 *
 * The renderer remains scene-linear until this boundary. The encoder creates an SDR sRGB base
 * and a BT.2020/PQ P010 HDR rendition. The implementation is intentionally allocation-conscious:
 * it does not create full-resolution temporary RGB/chroma FloatArrays in addition to the image
 * buffers, which is important on RAM-constrained phones.
 */
class RawUltraHdrEncoder {
    fun encode(rgb: RawDevelopmentPipeline.LinearRgb): ByteArray? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        if (rgb.width < 2 || rgb.height < 2 || rgb.width % 2 != 0 || rgb.height % 2 != 0) return null

        val sdr = buildSdr(rgb)
        val hdr = buildHdrP010(rgb)
        val out = ByteArrayOutputStream((rgb.width * rgb.height).coerceAtMost(16_000_000))
        return try {
            val ok = hdr.compressToJpegR(sdr, 100, out)
            if (ok) out.toByteArray() else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildSdr(rgb: RawDevelopmentPipeline.LinearRgb): YuvImage {
        val w = rgb.width
        val h = rgb.height
        val ySize = w * h
        val cw = w / 2
        val ch = h / 2
        val cSize = cw * ch
        val data = ByteArray(ySize + cSize * 2)
        val uOffset = ySize
        val vOffset = ySize + cSize

        fun luma(r: Float, g: Float, b: Float): Float =
            0.2126f * r + 0.7152f * g + 0.0722f * b

        for (i in 0 until ySize) {
            val r = linearToSrgb(sdrTone(rgb.r[i]))
            val g = linearToSrgb(sdrTone(rgb.g[i]))
            val b = linearToSrgb(sdrTone(rgb.b[i]))
            data[i] = quant8(luma(r, g, b))
        }
        for (cy in 0 until ch) for (cx in 0 until cw) {
            var u = 0f
            var v = 0f
            repeat(2) { dy -> repeat(2) { dx ->
                val i = (cy * 2 + dy) * w + cx * 2 + dx
                val r = linearToSrgb(sdrTone(rgb.r[i]))
                val g = linearToSrgb(sdrTone(rgb.g[i]))
                val b = linearToSrgb(sdrTone(rgb.b[i]))
                val yy = luma(r, g, b)
                u += (b - yy) / 1.772f + 0.5f
                v += (r - yy) / 1.402f + 0.5f
            }}
            val ci = cy * cw + cx
            data[uOffset + ci] = quant8(u * 0.25f)
            data[vOffset + ci] = quant8(v * 0.25f)
        }
        return YuvImage(
            data,
            ImageFormat.YUV_420_888,
            w,
            h,
            intArrayOf(w, cw, cw),
            ColorSpace.get(ColorSpace.Named.SRGB),
        )
    }

    private fun buildHdrP010(rgb: RawDevelopmentPipeline.LinearRgb): YuvImage {
        val w = rgb.width
        val h = rgb.height
        val yBytes = w * h * 2
        val uvBytes = w * (h / 2) * 2
        val data = ByteArray(yBytes + uvBytes)
        val uvOffset = yBytes

        fun put16(offset: Int, value: Int) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        fun pq(linear10000: Float): Float {
            val x = (linear10000 / 10000f).coerceIn(0f, 1f)
            val m1 = 2610f / 16384f
            val m2 = 2523f / 32f
            val c1 = 3424f / 4096f
            val c2 = 2413f / 128f
            val c3 = 2392f / 128f
            val p = x.pow(m1)
            return ((c1 + c2 * p) / (1f + c3 * p)).coerceIn(0f, 1f).pow(m2)
        }

        for (i in 0 until w * h) {
            val rr = (0.6274f * rgb.r[i] + 0.3293f * rgb.g[i] + 0.0433f * rgb.b[i]).coerceIn(0f, 10f)
            val gg = (0.0691f * rgb.r[i] + 0.9195f * rgb.g[i] + 0.0114f * rgb.b[i]).coerceIn(0f, 10f)
            val bb = (0.0164f * rgb.r[i] + 0.0880f * rgb.g[i] + 0.8956f * rgb.b[i]).coerceIn(0f, 10f)
            val yLinear = (0.2627f * rr + 0.6780f * gg + 0.0593f * bb).coerceIn(0f, 10f)
            val y = pq(yLinear * 1000f)
            put16(i * 2, (((y * 1023f + 0.5f).toInt().coerceIn(0, 1023)) shl 6))
        }
        for (cy in 0 until h / 2) for (cx in 0 until w / 2) {
            var u = 0f
            var v = 0f
            repeat(2) { dy -> repeat(2) { dx ->
                val i = (cy * 2 + dy) * w + cx * 2 + dx
                val rr = (0.6274f * rgb.r[i] + 0.3293f * rgb.g[i] + 0.0433f * rgb.b[i]).coerceIn(0f, 10f)
                val gg = (0.0691f * rgb.r[i] + 0.9195f * rgb.g[i] + 0.0114f * rgb.b[i]).coerceIn(0f, 10f)
                val bb = (0.0164f * rgb.r[i] + 0.0880f * rgb.g[i] + 0.8956f * rgb.b[i]).coerceIn(0f, 10f)
                val yLinear = (0.2627f * rr + 0.6780f * gg + 0.0593f * bb).coerceIn(0f, 10f)
                u += ((bb - yLinear) / 1.8814f + 0.5f).coerceIn(0f, 1f)
                v += ((rr - yLinear) / 1.4746f + 0.5f).coerceIn(0f, 1f)
            }}
            val p = uvOffset + (cy * w + cx * 2) * 2
            put16(p, (((u * 0.25f * 1023f + 0.5f).toInt().coerceIn(0, 1023)) shl 6))
            put16(p + 2, (((v * 0.25f * 1023f + 0.5f).toInt().coerceIn(0, 1023)) shl 6))
        }
        return YuvImage(
            data,
            ImageFormat.YCBCR_P010,
            w,
            h,
            intArrayOf(w * 2, w * 2),
            ColorSpace.get(ColorSpace.Named.BT2020_PQ),
        )
    }

    private fun sdrTone(value: Float): Float {
        val x = value.coerceAtLeast(0f)
        if (x <= 0.78f) return x
        val excess = x - 0.78f
        return (0.78f + excess / (1f + excess * 2.8f)).coerceIn(0f, 1f)
    }

    private fun linearToSrgb(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    private fun quant8(v: Float): Byte =
        (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
}
