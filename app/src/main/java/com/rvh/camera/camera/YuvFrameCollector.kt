package com.rvh.camera.camera

import android.media.ImageReader
import android.os.Handler
import com.rvh.camera.imaging.FrameMetadata
import com.rvh.camera.imaging.Yuv420Frame

/**
 * Camera2 boundary for application-visible YUV frames.
 *
 * It deliberately owns Image lifetime: every acquired Image is closed immediately after its
 * planes are copied into our packed YUV representation. This prevents an ImageReader producer
 * from stalling because application buffers remain open.
 */
class YuvFrameCollector(
    private val reader: ImageReader,
    handler: Handler,
    private val metadataForTimestamp: (Long) -> FrameMetadata?,
    private val onFrame: (Yuv420Frame) -> Unit,
    private val onError: (String) -> Unit,
) {
    init {
        reader.setOnImageAvailableListener({ source ->
            runCatching {
                source.acquireLatestImage()?.use { image ->
                    val metadata = metadataForTimestamp(image.timestamp)
                        ?: return@use
                    onFrame(Yuv420Frame.fromImage(image, metadata))
                }
            }.onFailure { error ->
                onError(error.message ?: "Unable to read YUV frame")
            }
        }, handler)
    }
}
