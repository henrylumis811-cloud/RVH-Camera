package com.rvh.camera.camera

import com.rvh.camera.imaging.CaptureIntent
import com.rvh.camera.imaging.CapturePlan

/**
 * Decides whether the current hardware profile is safe to consider for a future true ZSL path.
 *
 * This is intentionally only a policy boundary. It does not claim that a regular output session
 * is reprocessable; the actual session must still be constructed with a valid reprocessable input
 * surface and accepted by Camera2's session validation.
 */
object ReprocessingPolicy {
    fun canAttemptTrueZsl(profile: CameraHardwareProfile?): Boolean {
        if (profile == null || !profile.supportsReprocessing) return false
        if (profile.pipelineDepth != null && profile.pipelineDepth <= 0) return false
        return profile.tier != CameraHardwareProfile.Tier.CONSERVATIVE
    }

    fun shouldPreferTrueZsl(
        profile: CameraHardwareProfile?,
        plan: CapturePlan,
    ): Boolean {
        if (!canAttemptTrueZsl(profile)) return false
        if (plan.intent == CaptureIntent.HDR) return false
        if (plan.preferVendorExtension) return false
        return plan.intent == CaptureIntent.PHOTO || plan.intent == CaptureIntent.NIGHT
    }
}
