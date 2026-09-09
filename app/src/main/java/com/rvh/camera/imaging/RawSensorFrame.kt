package com.rvh.camera.imaging

import android.media.Image

/**
 * CPU-owned RAW_SENSOR frame.
 *
 * Camera2 RAW16 is normally a single Bayer-mosaic plane. The raw pixels are copied out of the
 * ImageReader immediately so the scarce HAL buffer can be returned to the camera. Values are
 * retained as unsigned 16-bit samples in little-endian order.
 */
data class RawSensorFrame(
    override val metadata: FrameMetadata,
    val width: Int,
    val height: Int,
    val pixels: ShortArray,
    val rowStrideBytes: Int = width * 2,
) : ImageFrame {
    init {
        require(width > 0 && height > 0) { "Invalid RAW dimensions" }
        require(pixels.size == width * height) { "Invalid RAW pixel count" }
    }

    override fun close() = Unit

    fun unsigned(index: Int): Int = pixels[index].toInt() and 0xFFFF

    fun normalized(index: Int): Float {
        val white = metadata.sensorWhiteLevel?.coerceAtLeast(1) ?: 65535
        val black = metadata.sensorBlackLevelPattern?.average()?.toFloat()?.coerceAtLeast(0f) ?: 0f
        return ((unsigned(index) - black) / (white - black).coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    companion object {
        fun fromImage(image: Image, metadata: FrameMetadata): RawSensorFrame {
            require(image.format == android.graphics.ImageFormat.RAW_SENSOR) {
                "Expected RAW_SENSOR, got ${image.format}"
            }
            require(image.planes.size == 1) { "RAW_SENSOR must expose one plane" }

            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val source = plane.buffer.duplicate()
            val output = ShortArray(width * height)
            val rowStride = plane.rowStride

            for (row in 0 until height) {
                val rowStart = row * rowStride
                for (column in 0 until width) {
                    val byteOffset = rowStart + column * 2
                    if (byteOffset + 1 >= source.limit()) continue
                    source.position(byteOffset)
                    val lo = source.get().toInt() and 0xFF
                    val hi = source.get().toInt() and 0xFF
                    output[row * width + column] = ((hi shl 8) or lo).toShort()
                }
            }

            return RawSensorFrame(
                metadata = metadata.copy(isRawSensorFrame = true),
                width = width,
                height = height,
                pixels = output,
                rowStrideBytes = rowStride,
            )
        }
    }
}

fun RawSensorFrame.byteCount(): Long = pixels.size.toLong() * 2L
