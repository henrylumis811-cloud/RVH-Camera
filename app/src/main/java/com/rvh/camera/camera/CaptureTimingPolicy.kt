package com.rvh.camera.camera

import com.rvh.camera.imaging.CaptureIntent
import com.rvh.camera.imaging.CapturePlan

/**
 * Decides whether a capture should give the 3A controller a short opportunity to settle first.
 * This is deliberately conservative: ordinary photos stay low-latency, while computational
 * captures get a brief metering checkpoint when the device can benefit from it.
 */
object CaptureTimingPolicy {
    fun needsAePrecapture(plan: CapturePlan, profile: CameraHardwareProfile?): Boolean {
        if (profile == null) return false
        if (!profile.supportsAeCompensation && plan.intent == CaptureIntent.PHOTO) return false
        return plan.frameCount > 1 || plan.intent == CaptureIntent.NIGHT || plan.intent == CaptureIntent.HDR
    }
}
