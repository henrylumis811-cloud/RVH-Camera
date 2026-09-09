package com.rvh.camera.camera

import com.rvh.camera.imaging.CaptureIntent
import com.rvh.camera.imaging.CapturePlan
import kotlin.math.max
import kotlin.math.min

/**
 * Builds a motion-aware exposure sequence for computational night capture.
 *
 * The planner keeps the total scene brightness roughly constant while moving exposure budget
 * between shutter time and sensor gain. Stable scenes receive longer, cleaner integrations;
 * moving scenes receive shorter integrations at higher ISO so temporal fusion has a usable sharp
 * anchor instead of three equally blurred frames.
 */
object NightExposurePlanner {
    data class Setting(
        val exposureTimeNs: Long,
        val sensitivityIso: Int,
    )

    fun plan(
        capturePlan: CapturePlan,
        metadata: com.rvh.camera.imaging.FrameMetadata,
        profile: CameraHardwareProfile?,
        sceneMotionScore: Float,
    ): List<Setting>? {
        if (capturePlan.intent != CaptureIntent.NIGHT || capturePlan.frameCount < 2) return null
        if (profile?.supportsManualSensor != true) return null
        val exposureRange = profile.exposureTimeRangeNs ?: return null
        val isoRange = profile.isoRange ?: return null
        val measuredExposure = metadata.exposureTimeNs ?: return null
        val measuredIso = metadata.sensitivityIso ?: return null
        if (measuredExposure <= 0L || measuredIso <= 0) return null

        val sceneMotion = sceneMotionScore.coerceIn(0f, 1f)
        val oisMotion = com.rvh.camera.imaging.OisMotionAnalyzer.analyze(metadata)
        // Scene motion and camera shake are different signals. For exposure planning the safer
        // choice is the larger one: a moving subject can ghost even on a stabilized camera, while
        // a hand-held camera can blur a static scene despite strong scene-registration confidence.
        val motion = max(sceneMotion, oisMotion.score).coerceIn(0f, 1f)

        // A long integration is valuable only when the scene is sufficiently stable. Keep the
        // motion curve deliberately conservative because the preview motion score is low-cost
        // spatial evidence rather than gyro-grade optical-flow telemetry.
        val maxExposureByMotion = when {
            motion >= 0.55f -> 18_000_000L
            motion >= 0.35f -> 32_000_000L
            motion >= 0.18f -> 55_000_000L
            else -> 100_000_000L
        }

        val requestedExposure = max(
            exposureRange.first,
            min(exposureRange.last, maxExposureByMotion),
        )

        // Preserve the AE-metered exposure value (time * ISO) as the initial brightness budget.
        // If the requested shutter is shorter, the missing exposure is paid back with ISO.
        val exposureRatio = requestedExposure.toDouble() / measuredExposure.toDouble()
        val requestedIso = (measuredIso.toDouble() / exposureRatio)
            .roundIso()
            .coerceIn(isoRange.first, isoRange.last)

        val base = Setting(
            exposureTimeNs = requestedExposure,
            sensitivityIso = requestedIso,
        )

        // Equal exposures maximize registration consistency. For a moving scene, bias the first
        // frame shorter so the fusion stage has a deliberately sharp reference image.
        val sequence = ArrayList<Setting>(capturePlan.frameCount)
        repeat(capturePlan.frameCount.coerceIn(2, 4)) { index ->
            val multiplier = when {
                motion >= 0.55f -> when (index) {
                    0 -> 0.65
                    1 -> 0.85
                    else -> 1.0
                }
                motion >= 0.35f -> when (index) {
                    0 -> 0.75
                    1 -> 0.9
                    else -> 1.0
                }
                else -> 1.0
            }
            val exposure = (base.exposureTimeNs * multiplier)
                .toLong()
                .coerceIn(exposureRange.first, exposureRange.last)
            val iso = (base.exposureTimeNs.toDouble() * base.sensitivityIso.toDouble() /
                exposure.toDouble())
                .roundIso()
                .coerceIn(isoRange.first, isoRange.last)
            sequence += Setting(exposure, iso)
        }
        return sequence
    }

    private fun Double.roundIso(): Int {
        // Camera sensitivity is normally quantized by the HAL. Rounding to a practical step
        // avoids needlessly exotic values while leaving the final result to the capture result.
        val step = when {
            this < 800.0 -> 25.0
            this < 3200.0 -> 50.0
            else -> 100.0
        }
        return ((this / step).roundToInt() * step).toInt()
    }

    private fun Double.roundToInt(): Int = kotlin.math.round(this).toInt()
}
