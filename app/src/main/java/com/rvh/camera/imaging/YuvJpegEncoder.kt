package com.rvh.camera.imaging

import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.ImageFormat
import java.io.ByteArrayOutputStream

/**
 * Final Android-side encoder for the packed YUV representation used by the pipeline.
 *
 * The processing engine deliberately stays in YUV until this boundary. Android documents
 * YUV_420_888 -> RGB as using the JFIF/Rec.601 full-range matrix by default, with the resulting
 * RGB interpreted as sRGB. For the final JPEG path we therefore convert to NV21 and let the
 * platform JPEG encoder perform the actual YUV->JPEG conversion.
 */
class YuvJpegEncoder(
    private val jpegQuality: Int = 100,
) {
    init {
        require(jpegQuality in 1..100) { "JPEG quality must be 1..100" }
    }

    fun encode(frame: Yuv420Frame): ByteArray {
        val width = frame.width and 1.inv()
        val height = frame.height and 1.inv()
        require(width > 0 && height > 0) { "Frame is too small to encode" }

        val nv21 = toNv21(frame, width, height)
        val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val output = ByteArrayOutputStream((width * height / 2).coerceAtLeast(4096))
        check(image.compressToJpeg(Rect(0, 0, width, height), jpegQuality, output)) {
            "JPEG encoding failed"
        }
        return output.toByteArray()
    }

    private fun toNv21(frame: Yuv420Frame, width: Int, height: Int): ByteArray {
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val ySize = width * height
        val chromaSize = chromaWidth * chromaHeight
        val output = ByteArray(ySize + chromaSize * 2)

        // Y plane.
        for (row in 0 until height) {
            System.arraycopy(frame.y, row * frame.width, output, row * width, width)
        }

        // NV21 is interleaved V,U. The pipeline stores packed U and V planes separately.
        var dst = ySize
        for (row in 0 until chromaHeight) {
            val srcRow = row * ((frame.width + 1) / 2)
            for (col in 0 until chromaWidth) {
                val index = srcRow + col
                output[dst++] = frame.v[index]
                output[dst++] = frame.u[index]
            }
        }
        return output
    }
}
