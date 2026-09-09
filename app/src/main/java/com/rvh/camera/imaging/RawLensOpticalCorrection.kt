package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Calibration-gated optical correction for developed RAW RGB.
 *
 * Camera2 exposes lens intrinsics and Brown-Conrady distortion coefficients in the
 * pre-correction active-array coordinate system.  We use those values to inverse-map the
 * requested undistorted output pixel back into the original RAW-developed image.  No guessed
 * coefficients are used: when calibration is unavailable, this stage is a no-op.
 *
 * This stage intentionally corrects geometry only. Chromatic-aberration/fringe suppression is
 * kept separate so that it cannot silently invent a lens model from image content.
 */
class RawLensOpticalCorrection {
    fun apply(input: RawDevelopmentPipeline.LinearRgb): RawDevelopmentPipeline.LinearRgb {
        val intrinsics = input.metadata.lensIntrinsicCalibration
        val distortion = input.metadata.lensDistortion
        if (intrinsics == null || intrinsics.size < 4 || distortion == null || distortion.size < 5) {
            return input
        }

        val fx = intrinsics[0]
        val fy = intrinsics[1]
        val cx = intrinsics[2]
        val cy = intrinsics[3]
        if (!fx.isFinite() || !fy.isFinite() || fx <= 1f || fy <= 1f ||
            !cx.isFinite() || !cy.isFinite()
        ) return input

        val k1 = distortion[0]
        val k2 = distortion[1]
        val k3 = distortion[2]
        val p1 = distortion[3]
        val p2 = distortion[4]
        if (!listOf(k1, k2, k3, p1, p2).all { it.isFinite() }) return input

        // Very small coefficients are effectively already corrected. Avoid needless resampling.
        val coefficientMagnitude = max(
            max(abs(k1), abs(k2)),
            max(abs(k3), max(abs(p1), abs(p2))),
        )
        if (coefficientMagnitude < 1e-6f) return input

        val w = input.width
        val h = input.height
        val outR = FloatArray(w * h)
        val outG = FloatArray(w * h)
        val outB = FloatArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idealX = x + 0.5f
                val idealY = y + 0.5f
                val xn = (idealX - cx) / fx
                val yn = (idealY - cy) / fy
                val r2 = xn * xn + yn * yn
                val radial = 1f + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2
                val xd = xn * radial + 2f * p1 * xn * yn + p2 * (r2 + 2f * xn * xn)
                val yd = yn * radial + p1 * (r2 + 2f * yn * yn) + 2f * p2 * xn * yn
                val sx = xd * fx + cx - 0.5f
                val sy = yd * fy + cy - 0.5f
                val i = y * w + x
                if (sx < 0f || sy < 0f || sx > w - 1f || sy > h - 1f) {
                    outR[i] = input.r[i]
                    outG[i] = input.g[i]
                    outB[i] = input.b[i]
                } else {
                    outR[i] = bilinear(input.r, w, h, sx, sy)
                    outG[i] = bilinear(input.g, w, h, sx, sy)
                    outB[i] = bilinear(input.b, w, h, sx, sy)
                }
            }
        }

        return input.copy(r = outR, g = outG, b = outB)
    }

    private fun bilinear(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = min(width - 1, x0 + 1)
        val y1 = min(height - 1, y0 + 1)
        val tx = (x - x0).coerceIn(0f, 1f)
        val ty = (y - y0).coerceIn(0f, 1f)
        val a = values[y0 * width + x0] * (1f - tx) + values[y0 * width + x1] * tx
        val b = values[y1 * width + x0] * (1f - tx) + values[y1 * width + x1] * tx
        return a * (1f - ty) + b * ty
    }
}
