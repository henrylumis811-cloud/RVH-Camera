package com.rvh.camera.imaging

/**
 * Stable boundary between camera acquisition and computational photography.
 * Implementations may be CPU, GPU or native without changing camera capture code.
 */
interface ImageEngine {
    fun process(frame: ImageFrame, request: ProcessingRequest): ProcessedImage
}

/** Lightweight ownership contract; implementations must release backing resources when done. */
interface ImageFrame {
    val metadata: FrameMetadata
    fun close()
}

interface ProcessedImage {
    val metadata: FrameMetadata
    fun jpegBytes(): ByteArray
}
