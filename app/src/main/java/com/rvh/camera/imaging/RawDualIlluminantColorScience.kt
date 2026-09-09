package com.rvh.camera.imaging

import kotlin.math.sqrt

/**
 * DNG-style dual-illuminant fallback for RAW devices that do not expose a usable
 * COLOR_CORRECTION_TRANSFORM result.
 *
 * Camera2 describes calibration transforms as reference-sensor -> device-sensor and forward
 * matrices as white-balanced reference-sensor -> XYZ D50. We therefore invert the calibration
 * matrix first, then apply the forward matrix and a fixed XYZ-D50 -> linear-sRGB conversion.
 * If both illuminants are available, the two camera-to-XYZ matrices are smoothly interpolated
 * using a bounded scene-temperature estimate from the captured Bayer WB gains.
 */
object RawDualIlluminantColorScience {
    private const val D50_TO_SRGB_00 = 3.1338561f
    private const val D50_TO_SRGB_01 = -1.6168667f
    private const val D50_TO_SRGB_02 = -0.4906146f
    private const val D50_TO_SRGB_10 = -0.9787684f
    private const val D50_TO_SRGB_11 = 1.9161415f
    private const val D50_TO_SRGB_12 = 0.0334540f
    private const val D50_TO_SRGB_20 = 0.0719453f
    private const val D50_TO_SRGB_21 = -0.2289914f
    private const val D50_TO_SRGB_22 = 1.4052427f

    fun apply(rgb: RawDevelopmentPipeline.LinearRgb): RawDevelopmentPipeline.LinearRgb {
        val metadata = rgb.metadata
        val calibration1 = sanitizeMatrix(metadata.sensorCalibrationTransform1)
        val forward1 = sanitizeMatrix(metadata.sensorForwardMatrix1) ?: return rgb
        val calibration2 = sanitizeMatrix(metadata.sensorCalibrationTransform2)
        val forward2 = sanitizeMatrix(metadata.sensorForwardMatrix2)

        val matrix = if (calibration2 != null && forward2 != null &&
            metadata.sensorReferenceIlluminant1 != null && metadata.sensorReferenceIlluminant2 != null) {
            val k1 = illuminantKelvin(metadata.sensorReferenceIlluminant1)
            val k2 = illuminantKelvin(metadata.sensorReferenceIlluminant2)
            val sceneK = estimateSceneKelvin(metadata.sensorColorGains)
            val t = illuminantBlendWeight(sceneK, k1, k2)
            // Interpolate the two calibration stages separately. This preserves the
            // meaning of Camera2's reference-sensor -> device-sensor transform instead
            // of interpolating two already-composed matrices.
            val calibration = lerpMatrix(calibration1 ?: identity3x3(), calibration2, t)
            val forward = lerpMatrix(forward1, forward2, t)
            composeCameraToXyz(calibration, forward) ?: composeCameraToXyz(calibration1, forward1)
                ?: return rgb
        } else {
            composeCameraToXyz(calibration1, forward1) ?: return rgb
        }

        val r = rgb.r.copyOf()
        val g = rgb.g.copyOf()
        val b = rgb.b.copyOf()
        for (i in r.indices) {
            val cr = r[i]
            val cg = g[i]
            val cb = b[i]
            val x = matrix[0] * cr + matrix[1] * cg + matrix[2] * cb
            val y = matrix[3] * cr + matrix[4] * cg + matrix[5] * cb
            val z = matrix[6] * cr + matrix[7] * cg + matrix[8] * cb
            r[i] = (D50_TO_SRGB_00 * x + D50_TO_SRGB_01 * y + D50_TO_SRGB_02 * z).coerceIn(0f, 4f)
            g[i] = (D50_TO_SRGB_10 * x + D50_TO_SRGB_11 * y + D50_TO_SRGB_12 * z).coerceIn(0f, 4f)
            b[i] = (D50_TO_SRGB_20 * x + D50_TO_SRGB_21 * y + D50_TO_SRGB_22 * z).coerceIn(0f, 4f)
        }
        return rgb.copy(r = r, g = g, b = b)
    }

    private fun composeCameraToXyz(calibration: FloatArray?, forward: FloatArray?): FloatArray? {
        if (forward == null || forward.size < 9) return null
        if (calibration == null || calibration.size < 9) return forward.copyOf(9)
        val inverse = invert3x3(calibration) ?: return null
        return multiply3x3(forward, inverse)
    }

    private fun estimateSceneKelvin(gains: FloatArray?): Float {
        if (gains == null || gains.size < 4) return 5500f
        val red = gains[0].coerceIn(0.5f, 4f)
        val blue = gains[3].coerceIn(0.5f, 4f)
        // The blue/red WB ratio is a useful monotonic proxy for scene CCT. Keep
        // the mapping deliberately broad and bounded; this value is used only to
        // choose between two calibrated endpoints, never as a colour transform itself.
        val ratio = (blue / red).coerceIn(0.35f, 4f)
        return (6500f / sqrt(ratio)).coerceIn(2000f, 12000f)
    }

    private fun illuminantBlendWeight(sceneK: Float, k1: Float, k2: Float): Float {
        if (!sceneK.isFinite() || !k1.isFinite() || !k2.isFinite()) return 0.5f
        if (kotlin.math.abs(k1 - k2) < 1f) return 0.5f
        // Camera colour calibration is much closer to linear in reciprocal
        // temperature (mired) than in Kelvin. Blend in that domain.
        val sceneMired = 1_000_000f / sceneK.coerceIn(2000f, 12000f)
        val mired1 = 1_000_000f / k1.coerceIn(2000f, 12000f)
        val mired2 = 1_000_000f / k2.coerceIn(2000f, 12000f)
        return ((sceneMired - mired1) / (mired2 - mired1)).coerceIn(0f, 1f)
    }

    private fun illuminantKelvin(code: Int): Float = when (code) {
        17 -> 2856f // Standard A
        18 -> 4874f // Standard B
        19 -> 6770f // Standard C
        20 -> 5500f // D55
        21 -> 6504f // D65
        22 -> 7504f // D75
        23 -> 5003f // D50
        24 -> 3200f // ISO studio tungsten
        3 -> 2856f // Tungsten
        4 -> 4230f // Fluorescent
        9 -> 7000f // Fine weather
        10 -> 6500f // Cloudy
        11 -> 7500f // Shade
        12 -> 6200f // Daylight fluorescent
        13 -> 5000f // Day white fluorescent
        14 -> 4150f // Cool white fluorescent
        15 -> 3500f // White fluorescent
        else -> 5500f
    }

    private fun sanitizeMatrix(input: FloatArray?): FloatArray? {
        if (input == null || input.size < 9) return null
        val out = input.copyOf(9)
        if (out.any { !it.isFinite() }) return null
        // Camera2 matrices can contain very small rational values. Reject only
        // pathological magnitude; do not renormalise a valid calibration matrix.
        if (out.any { kotlin.math.abs(it) > 16f }) return null
        return out
    }

    private fun identity3x3(): FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    private fun lerpMatrix(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        return FloatArray(9) { i -> a[i] + (b[i] - a[i]) * t }
    }

    private fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        return FloatArray(9) { index ->
            val row = index / 3
            val col = index % 3
            a[row * 3] * b[col] +
                a[row * 3 + 1] * b[3 + col] +
                a[row * 3 + 2] * b[6 + col]
        }
    }

    private fun invert3x3(m: FloatArray): FloatArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val aei = a * e * i
        val bfg = b * f * g
        val cdh = c * d * h
        val ceg = c * e * g
        val bdi = b * d * i
        val afh = a * f * h
        val det = aei + bfg + cdh - ceg - bdi - afh
        if (!det.isFinite() || kotlin.math.abs(det) < 1e-7f) return null
        val inv = 1f / det
        return floatArrayOf(
            (e * i - f * h) * inv,
            (c * h - b * i) * inv,
            (b * f - c * e) * inv,
            (f * g - d * i) * inv,
            (a * i - c * g) * inv,
            (c * d - a * f) * inv,
            (d * h - e * g) * inv,
            (b * g - a * h) * inv,
            (a * e - b * d) * inv,
        )
    }
}
