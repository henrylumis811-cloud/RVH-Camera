package com.rvh.camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Size

/**
 * Chooses a YUV still-processing stream conservatively.
 *
 * We never assume that the largest YUV stream can coexist with preview. The controller must
 * validate the complete session before switching the live camera into this mode.
 */
data class YuvCaptureConfig(
    val size: Size,
    val useMaximumResolutionMap: Boolean,
) {
    val pixelCount: Long
        get() = size.width.toLong() * size.height.toLong()
}

object YuvCaptureConfigSelector {
    fun choose(
        characteristics: CameraCharacteristics,
        preferredAspect: Float,
        targetPixels: Long,
    ): YuvCaptureConfig? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().toList()
        if (sizes.isEmpty()) return null

        val eligible = sizes.filter { it.width >= 640 && it.height >= 480 }
        val aspectMatched = eligible.filter {
            kotlin.math.abs((it.width.toFloat() / it.height) - preferredAspect) <= 0.08f
        }
        // For image-quality validation, resolution is more important than landing near an
        // arbitrary target pixel count. Prefer the largest YUV stream that matches the JPEG
        // aspect ratio so RVH is not accidentally tested on a 1080p stream when the camera can
        // provide a multi-megapixel still-processing buffer.
        val candidate = (aspectMatched.ifEmpty { eligible })
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
            ?: return null

        return YuvCaptureConfig(
            size = candidate,
            useMaximumResolutionMap = false,
        )
    }

    /** Selects a size from the maximum-resolution map when the sensor advertises that mode. */
    fun chooseMaximumResolution(
        characteristics: CameraCharacteristics,
        preferredAspect: Float,
        targetPixels: Long,
    ): YuvCaptureConfig? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return null
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            ?: return null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().toList()
        if (sizes.isEmpty()) return null

        val target = targetPixels.coerceAtLeast(640L * 480L)
        val candidate = sizes.minWithOrNull(compareBy<Size> {
            kotlin.math.abs(kotlin.math.log2((it.width.toLong() * it.height) / target.toDouble()))
        }.thenBy {
            kotlin.math.abs((it.width.toFloat() / it.height) - preferredAspect)
        }) ?: return null

        return YuvCaptureConfig(candidate, true)
    }

}
