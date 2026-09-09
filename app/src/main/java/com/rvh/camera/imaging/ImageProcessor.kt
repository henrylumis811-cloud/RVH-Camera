package com.rvh.camera.imaging

/** Compatibility facade for the evolving RVH imaging engine. */
class ImageProcessor(
    private val engine: ImageEngine = PassThroughImageEngine(),
) {
    fun process(input: JpegImageFrame, request: ProcessingRequest = ProcessingRequest()): ByteArray {
        return engine.process(input, request).jpegBytes()
    }
}
