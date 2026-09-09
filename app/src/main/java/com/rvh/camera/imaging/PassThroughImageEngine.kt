package com.rvh.camera.imaging

/** Build-001-safe engine: preserves the captured JPEG while the computational pipeline is developed. */
class PassThroughImageEngine : ImageEngine {
    override fun process(frame: ImageFrame, request: ProcessingRequest): ProcessedImage {
        return when (frame) {
            is JpegImageFrame -> JpegProcessedImage(frame.metadata, frame.bytes)
            else -> throw IllegalArgumentException("Unsupported frame type for pass-through engine")
        }
    }
}

data class JpegImageFrame(
    override val metadata: FrameMetadata,
    val bytes: ByteArray,
) : ImageFrame {
    override fun close() = Unit
}

data class JpegProcessedImage(
    override val metadata: FrameMetadata,
    private val bytes: ByteArray,
) : ProcessedImage {
    override fun jpegBytes(): ByteArray = bytes
}
