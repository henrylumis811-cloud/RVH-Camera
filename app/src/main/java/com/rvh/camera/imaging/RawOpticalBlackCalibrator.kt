package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Estimates per-CFA-channel black level directly from optically shielded sensor pixels.
 *
 * Camera2's dynamic black level is already useful, but when optical-black regions are exposed
 * the actual RAW frame contains a stronger per-frame measurement. We use robust medians from
 * those shielded pixels, reject implausible estimates, and blend only when enough evidence exists.
 * This runs before lens shading, demosaic, and all colour processing.
 */
class RawOpticalBlackCalibrator(
    private val minSamplesPerChannel: Int = 12,
) {
    fun estimate(frame: RawSensorFrame): FloatArray? {
        val regions = frame.metadata.sensorOpticalBlackRegions ?: return null
        if (regions.size < 4 || regions.size % 4 != 0) return null
        val cfa = frame.metadata.sensorCfaArrangement ?: return null
        val dynamic = frame.metadata.sensorDynamicBlackLevel
        val fixed = frame.metadata.sensorBlackLevelPattern
        val reference = FloatArray(4) { channel ->
            when {
                dynamic != null && channel < dynamic.size -> dynamic[channel]
                fixed != null && channel < fixed.size -> fixed[channel]
                else -> 0f
            }
        }

        val samples = Array(4) { ArrayList<Int>() }
        var regionIndex = 0
        while (regionIndex + 3 < regions.size) {
            val left = regions[regionIndex].coerceIn(0, frame.width)
            val top = regions[regionIndex + 1].coerceIn(0, frame.height)
            val right = (regions[regionIndex] + regions[regionIndex + 2]).coerceIn(left, frame.width)
            val bottom = (regions[regionIndex + 1] + regions[regionIndex + 3]).coerceIn(top, frame.height)
            if (right > left && bottom > top) {
                collectRegion(frame, cfa, left, top, right, bottom, samples)
            }
            regionIndex += 4
        }

        val result = reference.copyOf()
        var accepted = 0
        for (channel in 0 until 4) {
            if (samples[channel].size < minSamplesPerChannel) continue
            samples[channel].sort()
            val median = samples[channel][samples[channel].size / 2].toFloat()
            if (!median.isFinite()) continue

            // A black estimate should remain close to the device's reported calibration.
            // This guards against malformed metadata or accidentally including active pixels.
            val baseline = reference[channel]
            val tolerance = max(32f, baseline * 0.35f)
            if (baseline > 0f && abs(median - baseline) > tolerance) continue
            if (median < 0f) continue

            result[channel] = if (baseline > 0f) {
                // Optical-black pixels are the direct per-frame measurement; retain a small
                // amount of the HAL estimate to prevent tiny samples from causing colour jumps.
                median * 0.75f + baseline * 0.25f
            } else {
                median
            }
            accepted++
        }

        return if (accepted >= 3) result else null
    }

    private fun cfaChannel(arrangement: Int, x: Int, y: Int): Int {
        val xe = x and 1
        val ye = y and 1
        return when (arrangement) {
            0 -> when { ye == 0 && xe == 0 -> 0; ye == 1 && xe == 1 -> 3; else -> 1 }
            1 -> when { ye == 0 && xe == 1 -> 0; ye == 1 && xe == 0 -> 3; else -> 1 }
            2 -> when { ye == 1 && xe == 0 -> 0; ye == 0 && xe == 1 -> 3; else -> 1 }
            3 -> when { ye == 1 && xe == 1 -> 0; ye == 0 && xe == 0 -> 3; else -> 1 }
            else -> 1
        }
    }

    private fun collectRegion(
        frame: RawSensorFrame,
        cfa: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        samples: Array<ArrayList<Int>>,
    ) {
        // Decimate large optical-black regions to keep CPU cost bounded while retaining a
        // spatially representative sample from every CFA phase.
        val step = max(1, max(right - left, bottom - top) / 96)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val channel = cfaChannel(cfa, x, y)
                if (channel in 0..3) {
                    samples[channel].add(frame.unsigned(y * frame.width + x))
                }
                x += step
            }
            y += step
        }
    }
}
