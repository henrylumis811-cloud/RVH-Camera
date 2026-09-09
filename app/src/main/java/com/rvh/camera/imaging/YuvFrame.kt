package com.rvh.camera.imaging

import android.media.Image

/**
 * CPU-readable, tightly packed YUV 4:2:0 frame.
 *
 * The source Image is copied and can therefore be closed immediately. This is intentional:
 * Camera2 ImageReader buffers are a scarce resource and must not be held by slow processing.
 */
data class Yuv420Frame(
    override val metadata: FrameMetadata,
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int = width,
    val chromaRowStride: Int = width / 2,
) : ImageFrame {
    init {
        require(width > 0 && height > 0) { "Invalid YUV frame dimensions" }
        require(y.size == width * height) { "Invalid Y plane size" }
        require(u.size == chromaWidth(width) * chromaHeight(height)) { "Invalid U plane size" }
        require(v.size == chromaWidth(width) * chromaHeight(height)) { "Invalid V plane size" }
    }

    override fun close() = Unit

    /** Reuses the packed planes while replacing only the capture metadata. */
    fun withMetadata(updatedMetadata: FrameMetadata): Yuv420Frame = copy(metadata = updatedMetadata)

    companion object {
        fun fromImage(image: Image, metadata: FrameMetadata): Yuv420Frame {
            require(image.format == android.graphics.ImageFormat.YUV_420_888) {
                "Expected YUV_420_888, got ${image.format}"
            }

            val width = image.width
            val height = image.height
            val chromaWidth = chromaWidth(width)
            val chromaHeight = chromaHeight(height)

            val y = copyPlane(image.planes[0], width, height)
            val u = copyPlane(image.planes[1], chromaWidth, chromaHeight)
            val v = copyPlane(image.planes[2], chromaWidth, chromaHeight)

            return Yuv420Frame(
                metadata = metadata,
                width = width,
                height = height,
                y = y,
                u = u,
                v = v,
            )
        }

        private fun copyPlane(plane: Image.Plane, width: Int, height: Int): ByteArray {
            val source = plane.buffer.duplicate()
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val output = ByteArray(width * height)
            val row = ByteArray(maxOf(rowStride, width * pixelStride))

            for (y in 0 until height) {
                source.position(minOf(source.limit(), y * rowStride))
                val rowBytes = minOf(row.size, source.remaining())
                source.get(row, 0, rowBytes)

                val outputOffset = y * width
                if (pixelStride == 1) {
                    System.arraycopy(row, 0, output, outputOffset, width)
                } else {
                    for (x in 0 until width) {
                        val index = x * pixelStride
                        output[outputOffset + x] = if (index < rowBytes) row[index] else 0
                    }
                }
            }
            return output
        }

        private fun chromaWidth(width: Int): Int = (width + 1) / 2
        private fun chromaHeight(height: Int): Int = (height + 1) / 2
    }
}

/** Small helper for code paths that need to estimate memory before retaining a frame. */
fun Yuv420Frame.byteCount(): Long = y.size.toLong() + u.size.toLong() + v.size.toLong()
