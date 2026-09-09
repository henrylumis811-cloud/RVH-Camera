package com.rvh.camera.camera

import com.rvh.camera.imaging.CaptureIntent
import com.rvh.camera.imaging.CapturePlan
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Converts an imaging intent into conservative Camera2 AE compensation values.
 *
 * This layer deliberately stays below the artistic/image-processing pipeline: it decides how the
 * sensor should be exposed, not how the resulting pixels should look.
 */
object ExposureStrategy {
    fun compensationFor(
        plan: CapturePlan,
        index: Int,
        profile: CameraHardwareProfile?,
    ): Int? {
        if (profile?.supportsAeCompensation != true) return null
        val range = profile.aeCompensationRange ?: return null
        val stepEv = profile.aeCompensationStep ?: return null
        if (stepEv <= 0f || range.first > range.last) return null

        val requestedEv = when (plan.intent) {
            CaptureIntent.HDR -> hdrBracketEv(plan, index, profile)
            // Night is temporal denoising. Deliberately keeping the same exposure avoids creating
            // unnecessary ghosting and lets the fusion stage reject moving pixels.
            CaptureIntent.NIGHT -> 0f
            else -> 0f
        }

        val stepValue = (requestedEv / stepEv).roundToInt()
        return stepValue.coerceIn(range.first, range.last)
    }

    private fun hdrBracketEv(
        plan: CapturePlan,
        index: Int,
        profile: CameraHardwareProfile,
    ): Float {
        val maximumPositiveEv = profile.aeCompensationRange?.last?.times(profile.aeCompensationStep ?: 1f) ?: 0f
        val maximumNegativeEv = profile.aeCompensationRange?.first?.times(profile.aeCompensationStep ?: 1f) ?: 0f
        val availableHalfRange = minOf(abs(maximumNegativeEv), abs(maximumPositiveEv))

        // Stronger scene pressure earns a wider bracket, but we never ask the HAL for more than
        // its advertised range. Small brackets are preferable on inexpensive sensors.
        val desiredHalfRange = when {
            plan.score >= 0.82f -> 2.0f
            plan.score >= 0.68f -> 1.5f
            else -> 1.0f
        }.coerceAtMost(availableHalfRange)

        return if (plan.frameCount <= 2) {
            if (index == 0) -desiredHalfRange else desiredHalfRange
        } else {
            when (index) {
                0 -> -desiredHalfRange
                1 -> 0f
                else -> desiredHalfRange
            }
        }
    }
}
