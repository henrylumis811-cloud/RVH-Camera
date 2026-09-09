package com.rvh.camera.imaging

import kotlin.math.max
import kotlin.math.min

/**
 * Sensor-linear RAW development primitives.
 *
 * This deliberately stops before producing a JPEG. It establishes the mathematically correct
 * boundary for future RAW multi-frame fusion: black-level normalization happens before demosaic,
 * and the camera calibration metadata remains attached to the linear samples.
 */
class RawDevelopmentPipeline(
    private val opticalBlackCalibrator: RawOpticalBlackCalibrator = RawOpticalBlackCalibrator(),
) {
    data class LinearRgb(
        val width: Int,
        val height: Int,
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray,
        val metadata: FrameMetadata,
    )

    fun normalize(frame: RawSensorFrame): RawLinearMosaic {
        val metadata = frame.metadata
        val blackPattern = metadata.sensorBlackLevelPattern
        val dynamicBlack = metadata.sensorDynamicBlackLevel
        val opticalBlack = opticalBlackCalibrator.estimate(frame)
        val white = (metadata.sensorDynamicWhiteLevel ?: metadata.sensorWhiteLevel ?: 65535).coerceAtLeast(1)
        val cfa = metadata.sensorCfaArrangement ?: 0
        val normalized = FloatArray(frame.pixels.size)

        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val channel = cfaChannel(cfa, x, y)
                val black = when {
                    opticalBlack != null && channel < opticalBlack.size -> opticalBlack[channel]
                    dynamicBlack != null && channel < dynamicBlack.size -> dynamicBlack[channel]
                    blackPattern != null && channel < blackPattern.size -> blackPattern[channel]
                    else -> 0f
                }
                val value = frame.unsigned(y * frame.width + x).toFloat()
                var linear = ((value - black) / max(1f, white - black)).coerceIn(0f, 1.05f)
                linear *= shadingGain(metadata, x, y, frame.width, frame.height, channel)
                normalized[y * frame.width + x] = linear.coerceIn(0f, 1.05f)
            }
        }

        applyGreenSplitCorrection(normalized, frame.width, frame.height, cfa, metadata.sensorGreenSplit)

        return RawLinearMosaic(
            width = frame.width,
            height = frame.height,
            values = normalized,
            cfaArrangement = cfa,
            metadata = metadata,
        )
    }

    private fun shadingGain(
        metadata: FrameMetadata,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        channel: Int,
    ): Float {
        val map = metadata.sensorLensShadingMap ?: return 1f
        val mapWidth = metadata.sensorLensShadingMapWidth ?: return 1f
        val mapHeight = metadata.sensorLensShadingMapHeight ?: return 1f
        if (mapWidth < 2 || mapHeight < 2 || map.size < mapWidth * mapHeight * 4) return 1f
        // Camera2 defines the shading map over the pre-correction active array, not
        // necessarily over the full RAW buffer. RAW buffers can contain optical-black or
        // otherwise inactive margins, so mapping directly from the full RAW buffer can
        // spatially mis-register the correction. Use the calibrated active-array origin and
        // dimensions whenever the device reports them.
        val active = metadata.sensorPreCorrectionActiveArray
        val activeLeft = active?.getOrNull(0) ?: 0
        val activeTop = active?.getOrNull(1) ?: 0
        val activeWidth = active?.getOrNull(2)?.takeIf { it > 1 } ?: width
        val activeHeight = active?.getOrNull(3)?.takeIf { it > 1 } ?: height
        // RAW_SENSOR coordinates are expressed from the full pixel-array origin.
        // Keep the map anchored to that coordinate system rather than translating the
        // sample itself by the active-array offset.
        if (x < activeLeft || y < activeTop ||
            x >= activeLeft + activeWidth || y >= activeTop + activeHeight
        ) return 1f
        val normalizedX = ((x - activeLeft).toFloat() / max(1, activeWidth - 1)).coerceIn(0f, 1f)
        val normalizedY = ((y - activeTop).toFloat() / max(1, activeHeight - 1)).coerceIn(0f, 1f)
        val fx = normalizedX * (mapWidth - 1)
        val fy = normalizedY * (mapHeight - 1)
        val x0 = fx.toInt().coerceIn(0, mapWidth - 1)
        val y0 = fy.toInt().coerceIn(0, mapHeight - 1)
        val x1 = min(mapWidth - 1, x0 + 1)
        val y1 = min(mapHeight - 1, y0 + 1)
        val tx = fx - x0
        val ty = fy - y0
        val c = channel.coerceIn(0, 3)
        fun at(px: Int, py: Int): Float = map[(py * mapWidth + px) * 4 + c].coerceAtLeast(1f)
        val top = at(x0, y0) * (1f - tx) + at(x1, y0) * tx
        val bottom = at(x0, y1) * (1f - tx) + at(x1, y1) * tx
        return (top * (1f - ty) + bottom * ty).coerceIn(1f, 4f)
    }

    private fun applyGreenSplitCorrection(
        values: FloatArray,
        width: Int,
        height: Int,
        cfa: Int,
        reportedSplit: Float?,
    ) {
        val split = reportedSplit ?: return
        if (split < 1.03f) return
        var evenSum = 0.0
        var oddSum = 0.0
        var evenCount = 0
        var oddCount = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (cfaChannel(cfa, x, y) != 1) continue
                val value = values[y * width + x]
                if ((y and 1) == 0) {
                    evenSum += value
                    evenCount++
                } else {
                    oddSum += value
                    oddCount++
                }
            }
        }
        if (evenCount < 32 || oddCount < 32) return
        val evenMean = (evenSum / evenCount).toFloat().coerceAtLeast(1e-5f)
        val oddMean = (oddSum / oddCount).toFloat().coerceAtLeast(1e-5f)
        val ratio = evenMean / oddMean
        if (!ratio.isFinite() || ratio <= 0f) return
        val target = kotlin.math.sqrt(evenMean * oddMean)
        // Only correct the measured divergence; never invent a large colour cast correction.
        val strength = ((split - 1.0f) / 0.20f).coerceIn(0f, 1f)
        val evenScale = (target / evenMean).let { 1f + (it - 1f) * strength }
        val oddScale = (target / oddMean).let { 1f + (it - 1f) * strength }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (cfaChannel(cfa, x, y) != 1) continue
                val idx = y * width + x
                values[idx] = (values[idx] * if ((y and 1) == 0) evenScale else oddScale).coerceIn(0f, 1.05f)
            }
        }
    }

    /**
     * Edge-directed Bayer reconstruction.
     *
     * Unlike the old bilinear reference path, this keeps native CFA samples exact, reconstructs
     * green along the least-changing direction, then reconstructs colour-difference channels.
     * That is materially safer around fine edges and diagonal detail, while remaining bounded
     * enough for a budget-phone RAW path.
     */
    fun demosaic(mosaic: RawLinearMosaic): LinearRgb {
        val w = mosaic.width
        val h = mosaic.height
        val n = w * h
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)

        fun at(x: Int, y: Int): Float = mosaic.values[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        fun ch(x: Int, y: Int): Int = cfaChannel(mosaic.cfaArrangement, x, y)
        fun directionalGreen(x: Int, y: Int): Float {
            val left = at(x - 1, y)
            val right = at(x + 1, y)
            val up = at(x, y - 1)
            val down = at(x, y + 1)
            val hGrad = kotlin.math.abs(left - right) + 0.5f * kotlin.math.abs(at(x, y) - (left + right) * 0.5f)
            val vGrad = kotlin.math.abs(up - down) + 0.5f * kotlin.math.abs(at(x, y) - (up + down) * 0.5f)
            val gh = (left + right) * 0.5f
            val gv = (up + down) * 0.5f
            return when {
                hGrad < vGrad * 0.75f -> gh
                vGrad < hGrad * 0.75f -> gv
                else -> (gh + gv) * 0.5f
            }
        }
        fun sameChannelAverage(x: Int, y: Int, channel: Int, step: Int = 2): Float {
            var sum = 0f
            var weight = 0f
            val dirs = arrayOf(intArrayOf(-step, 0), intArrayOf(step, 0), intArrayOf(0, -step), intArrayOf(0, step))
            for (d in dirs) {
                val sx = x + d[0]
                val sy = y + d[1]
                if (sx !in 0 until w || sy !in 0 until h || ch(sx, sy) != channel) continue
                val grad = if (d[0] != 0) kotlin.math.abs(at(x - step, y) - at(x + step, y))
                else kotlin.math.abs(at(x, y - step) - at(x, y + step))
                val wt = 1f / (0.001f + grad)
                sum += at(sx, sy) * wt
                weight += wt
            }
            return if (weight > 0f) sum / weight else at(x, y)
        }
        fun diagonalChannelAverage(x: Int, y: Int, channel: Int): Float {
            var sum = 0f
            var weight = 0f
            for (dy in intArrayOf(-1, 1)) for (dx in intArrayOf(-1, 1)) {
                val sx = x + dx
                val sy = y + dy
                if (sx !in 0 until w || sy !in 0 until h || ch(sx, sy) != channel) continue
                val grad = kotlin.math.abs(at(x - dx, y - dy) - at(x + dx, y + dy))
                val wt = 1f / (0.001f + grad)
                sum += at(sx, sy) * wt
                weight += wt
            }
            return if (weight > 0f) sum / weight else at(x, y)
        }
        fun colourDifference(values: FloatArray, x: Int, y: Int, channel: Int): Float {
            var sum = 0f
            var weight = 0f
            val dirs = arrayOf(intArrayOf(-2, 0), intArrayOf(2, 0), intArrayOf(0, -2), intArrayOf(0, 2))
            for (d in dirs) {
                val sx = x + d[0]
                val sy = y + d[1]
                if (sx !in 0 until w || sy !in 0 until h || ch(sx, sy) != channel) continue
                val neighbourGreen = g[sy * w + sx]
                val diff = values[sy * w + sx] - neighbourGreen
                val localGrad = if (d[0] != 0) kotlin.math.abs(at(x - 1, y) - at(x + 1, y))
                else kotlin.math.abs(at(x, y - 1) - at(x, y + 1))
                val wt = 1f / (0.002f + localGrad)
                sum += diff * wt
                weight += wt
            }
            return if (weight > 0f) sum / weight else 0f
        }

        // First pass: exact CFA samples and edge-directed green.
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            when (ch(x, y)) {
                0 -> r[i] = at(x, y)
                3 -> b[i] = at(x, y)
                else -> g[i] = at(x, y)
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (ch(x, y) == 1) continue
            g[i] = directionalGreen(x, y).coerceIn(0f, 1f)
        }

        // Second pass: reconstruct colour difference, which is much more edge-stable than
        // independently averaging sparse red/blue samples.
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            when (ch(x, y)) {
                0 -> {
                    val diff = colourDifference(r, x, y, 0)
                    r[i] = (g[i] + diff).coerceIn(0f, 1f)
                    b[i] = diagonalChannelAverage(x, y, 3)
                }
                3 -> {
                    val diff = colourDifference(b, x, y, 3)
                    b[i] = (g[i] + diff).coerceIn(0f, 1f)
                    r[i] = diagonalChannelAverage(x, y, 0)
                }
                else -> {
                    r[i] = sameChannelAverage(x, y, 0, 1).coerceIn(0f, 1f)
                    b[i] = sameChannelAverage(x, y, 3, 1).coerceIn(0f, 1f)
                }
            }
        }

        applyWhiteBalance(r, g, b, mosaic)
        if (mosaic.metadata.sensorColorTransform != null) {
            applySensorColorTransform(r, g, b, mosaic.metadata.sensorColorTransform)
        } else {
            val calibrated = RawDualIlluminantColorScience.apply(
                LinearRgb(w, h, r, g, b, mosaic.metadata),
            )
            return calibrated
        }
        return LinearRgb(w, h, r, g, b, mosaic.metadata)
    }

    private fun applyWhiteBalance(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        mosaic: RawLinearMosaic,
    ) {
        val gains = mosaic.metadata.sensorColorGains ?: mosaic.metadata.sensorNeutralPoint?.let { neutral ->
            if (neutral.size >= 3) {
                val rg = neutral[0].coerceAtLeast(1e-4f)
                val gg = neutral[1].coerceAtLeast(1e-4f)
                val bg = neutral[2].coerceAtLeast(1e-4f)
                floatArrayOf(gg / rg, 1f, 1f, gg / bg)
            } else null
        } ?: return
        if (gains.size < 4) return
        val gr = gains[0].coerceIn(0.25f, 4f)
        val gg = gains[1].coerceIn(0.25f, 4f)
        val gb = gains[2].coerceIn(0.25f, 4f)
        val bl = gains[3].coerceIn(0.25f, 4f)
        for (i in r.indices) {
            r[i] = (r[i] * gr).coerceIn(0f, 4f)
            g[i] = (g[i] * ((gg + gb) * 0.5f)).coerceIn(0f, 4f)
            b[i] = (b[i] * bl).coerceIn(0f, 4f)
        }
    }

    private fun applySensorColorTransform(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        transform: FloatArray?,
    ) {
        if (transform == null || transform.size < 9) return
        for (i in r.indices) {
            val rr = r[i]
            val gg = g[i]
            val bb = b[i]
            r[i] = (transform[0] * rr + transform[1] * gg + transform[2] * bb).coerceIn(0f, 4f)
            g[i] = (transform[3] * rr + transform[4] * gg + transform[5] * bb).coerceIn(0f, 4f)
            b[i] = (transform[6] * rr + transform[7] * gg + transform[8] * bb).coerceIn(0f, 4f)
        }
    }

    private fun cfaChannel(arrangement: Int, x: Int, y: Int): Int {
        val parity = (y and 1) * 2 + (x and 1)
        return when (arrangement) {
            1 -> intArrayOf(1, 0, 3, 1)[parity] // GRBG
            2 -> intArrayOf(1, 3, 0, 1)[parity] // GBRG
            3 -> intArrayOf(3, 1, 1, 0)[parity] // BGGR
            else -> intArrayOf(0, 1, 1, 3)[parity] // RGGB
        }
    }
}

data class RawLinearMosaic(
    val width: Int,
    val height: Int,
    val values: FloatArray,
    val cfaArrangement: Int,
    val metadata: FrameMetadata,
)
