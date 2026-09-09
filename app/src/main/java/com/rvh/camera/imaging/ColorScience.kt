package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative chroma finishing stage.
 *
 * It intentionally does not assume a YUV matrix (BT.601/709/etc.) because the camera HAL
 * owns that color-space contract. Instead it works in the native YUV chroma representation:
 * hue is preserved, saturation is controlled, and highlights are gently desaturated to avoid
 * ugly clipped color. A future RAW/RGB path can replace this with a calibrated 3x3/LUT model.
 */
class ColorScience(
    private val photoSaturation: Float = 1.035f,
    private val hdrSaturation: Float = 1.015f,
    private val nightSaturation: Float = 0.985f,
    private val portraitSaturation: Float = 1.005f,
) {
    fun apply(frame: Yuv420Frame, intent: CaptureIntent = CaptureIntent.PHOTO): Yuv420Frame {
        val saturation = when (intent) {
            CaptureIntent.PHOTO -> photoSaturation
            CaptureIntent.HDR -> hdrSaturation
            CaptureIntent.NIGHT -> nightSaturation
            CaptureIntent.PORTRAIT -> portraitSaturation
        }

        val chromaWidth = (frame.width + 1) / 2
        val chromaHeight = (frame.height + 1) / 2
        val u = frame.u.copyOf()
        val v = frame.v.copyOf()

        for (cy in 0 until chromaHeight) {
            val y0 = min(frame.height - 1, cy * 2)
            for (cx in 0 until chromaWidth) {
                val x0 = min(frame.width - 1, cx * 2)
                val chromaIndex = cy * chromaWidth + cx
                val yIndex = y0 * frame.width + x0
                val luma = frame.y[yIndex].toInt() and 0xFF

                // Chroma carries less reliable information near hard clipping. Reduce color
                // intensity there instead of allowing saturated RGB channels to break apart.
                val highlight = ((luma - 220f) / 35f).coerceIn(0f, 1f)
                val shadow = ((32f - luma) / 32f).coerceIn(0f, 1f)
                val localScale = saturation * (1f - 0.22f * highlight) * (1f - 0.04f * shadow)

                val du = (u[chromaIndex].toInt() and 0xFF) - 128
                val dv = (v[chromaIndex].toInt() and 0xFF) - 128
                val magnitude = max(abs(du), abs(dv))
                val safeScale = if (magnitude > 112) {
                    min(localScale, 112f / magnitude)
                } else {
                    localScale
                }

                u[chromaIndex] = (128 + du * safeScale).toInt().coerceIn(0, 255).toByte()
                v[chromaIndex] = (128 + dv * safeScale).toInt().coerceIn(0, 255).toByte()
            }
        }

        return Yuv420Frame(frame.metadata, frame.width, frame.height, frame.y.copyOf(), u, v)
    }
}
